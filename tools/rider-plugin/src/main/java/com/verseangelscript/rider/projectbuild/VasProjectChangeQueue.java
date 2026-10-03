package com.verseangelscript.rider.projectbuild;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded, coalescing event ingress; all potentially expensive checks run on the dispatcher. */
public final class VasProjectChangeQueue {
    private final Object lock = new Object();
    private final int limit;
    private final Consumer<Runnable> dispatch;
    private final BooleanSupplier active;
    private final Consumer<Path> check;
    private final Runnable overflow;
    private final LinkedHashSet<Path> pending = new LinkedHashSet<>();
    private boolean scheduled;
    private volatile boolean stopped;

    /**
     * The dispatcher must enqueue promptly, execute each task once, and never run it inline.
     * {@code active} and {@code overflow} must be cheap, non-blocking callbacks: they can run
     * on the submitting thread. The limit counts distinct pending paths, excluding the path
     * currently being checked; another event for that path schedules a fresh check.
     * Cancellation, overflow, or a callback/dispatch failure permanently stops this queue.
     */
    public VasProjectChangeQueue(int limit, Consumer<Runnable> dispatch, BooleanSupplier active,
                                 Consumer<Path> check, Runnable overflow) {
        if (limit <= 0) throw new IllegalArgumentException("Pending change limit must be positive");
        this.limit = limit;
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.active = Objects.requireNonNull(active, "active");
        this.check = Objects.requireNonNull(check, "check");
        this.overflow = Objects.requireNonNull(overflow, "overflow");
    }

    /**
     * Performs no filesystem operations and never invokes {@code check} inline.
     * Overflow clears pending paths and invokes {@code overflow} once. Failures clear and
     * stop the queue, invoke the same fail-closed callback, and propagate the original error.
     */
    public void submit(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            boolean exceeded = false;
            synchronized (lock) {
                if (!activeLocked() || pending.contains(path)) return;
                if (pending.size() >= limit) {
                    stopLocked();
                    exceeded = true;
                } else {
                    pending.add(path);
                    if (scheduled) return;
                    scheduled = true;
                }
            }
            if (exceeded) {
                overflow.run();
                return;
            }

            Thread submitter = Thread.currentThread();
            AtomicBoolean dispatching = new AtomicBoolean(true);
            try {
                dispatch.accept(() -> {
                    // A misconfigured direct executor must never move filesystem work onto
                    // the EDT. Reject it even when the dispatcher swallows our exception.
                    if (Thread.currentThread() == submitter && dispatching.get()) {
                        IllegalStateException failure = new IllegalStateException("Change checks require asynchronous dispatch");
                        fail(failure);
                        throw failure;
                    }
                    drain();
                });
            } finally {
                dispatching.set(false);
            }
        } catch (RuntimeException | Error failure) {
            fail(failure);
            throw failure;
        }
    }

    private void drain() {
        try {
            while (true) {
                Path path;
                synchronized (lock) {
                    if (!activeLocked()) return;
                    if (pending.isEmpty()) {
                        // Submission and relinquishing worker ownership use the same lock,
                        // so an event arriving at worker exit cannot lose its wake-up.
                        scheduled = false;
                        return;
                    }
                    var iterator = pending.iterator();
                    path = iterator.next();
                    iterator.remove();
                }
                if (stopped || !active.getAsBoolean()) {
                    synchronized (lock) { stopLocked(); }
                    return;
                }
                // Never hold the ingress lock across user code or filesystem work.
                // Cancellation cannot interrupt a check that has already started.
                check.accept(path);
            }
        } catch (RuntimeException | Error failure) {
            fail(failure);
            throw failure;
        }
    }

    private boolean activeLocked() {
        if (stopped) return false;
        if (active.getAsBoolean()) return true;
        stopLocked();
        return false;
    }

    private void stopLocked() {
        stopped = true;
        pending.clear();
    }

    private void fail(Throwable failure) {
        synchronized (lock) {
            if (stopped) return;
            stopLocked();
        }
        try { overflow.run(); }
        catch (RuntimeException | Error callbackFailure) {
            if (callbackFailure != failure) failure.addSuppressed(callbackFailure);
        }
    }
}
