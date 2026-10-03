package com.verseangelscript.rider.projectbuild;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class VasProjectChangeQueueTest {
    private static final Path FIRST = Path.of("first.vas");
    private static final Path SECOND = Path.of("second.vas");
    private static final Path THIRD = Path.of("third.vas");

    private static final class Dispatcher implements Consumer<Runnable> {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        @Override public void accept(Runnable task) { submissions++; tasks.add(task); }
        void runNext() { tasks.remove().run(); }
    }

    @Test public void checksAreDeferredAndPendingDuplicatesCoalesceAtTheLimit() {
        Dispatcher dispatcher = new Dispatcher();
        List<Path> checked = new ArrayList<>();
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, dispatcher, () -> true, checked::add, overflows::incrementAndGet);
        for (int i = 0; i < 100; i++) {
            queue.submit(FIRST);
            queue.submit(SECOND);
        }
        assertTrue(checked.isEmpty());
        assertEquals(1, dispatcher.submissions);
        assertEquals(1, dispatcher.tasks.size());
        assertEquals(0, overflows.get());
        dispatcher.runNext();
        assertEquals(List.of(FIRST, SECOND), checked);
        assertTrue(dispatcher.tasks.isEmpty());
    }

    @Test public void arrivalsDuringDrainUseTheExistingWorker() {
        Dispatcher dispatcher = new Dispatcher();
        List<Path> checked = new ArrayList<>();
        AtomicReference<VasProjectChangeQueue> reference = new AtomicReference<>();
        reference.set(new VasProjectChangeQueue(2, dispatcher, () -> true, path -> {
            checked.add(path);
            if (path.equals(FIRST)) {
                reference.get().submit(SECOND);
                reference.get().submit(THIRD);
                reference.get().submit(SECOND);
            }
        }, () -> fail("Unexpected overflow")));
        reference.get().submit(FIRST);
        dispatcher.runNext();
        assertEquals(List.of(FIRST, SECOND, THIRD), checked);
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void eventForInFlightPathIsCheckedAgain() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<VasProjectChangeQueue> reference = new AtomicReference<>();
        reference.set(new VasProjectChangeQueue(1, dispatcher, () -> true, path -> {
            if (checks.incrementAndGet() == 1) {
                reference.get().submit(path);
                reference.get().submit(path);
            }
        }, () -> fail("Unexpected overflow")));
        reference.get().submit(FIRST);
        dispatcher.runNext();
        assertEquals(2, checks.get());
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void arrivalAfterWorkerExitSchedulesANewWorker() {
        Dispatcher dispatcher = new Dispatcher();
        List<Path> checked = new ArrayList<>();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher, () -> true, checked::add, () -> fail("Unexpected overflow"));
        queue.submit(FIRST);
        dispatcher.runNext();
        queue.submit(SECOND);
        assertEquals(List.of(FIRST), checked);
        assertEquals(2, dispatcher.submissions);
        dispatcher.runNext();
        assertEquals(List.of(FIRST, SECOND), checked);
    }

    @Test(timeout = 10000) public void submissionRacingWorkerExitCannotLoseItsWakeup() throws Exception {
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "vas-change-queue-test");
            workerThread.set(thread);
            return thread;
        });
        CountDownLatch atExit = new CountDownLatch(1);
        CountDownLatch releaseExit = new CountDownLatch(1);
        CountDownLatch submitting = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(2);
        AtomicBoolean exitPaused = new AtomicBoolean();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, task -> {
            dispatches.incrementAndGet();
            worker.execute(task);
        }, () -> {
            // Test-only gate: hold the worker immediately before its empty-queue
            // decision while another thread attempts to submit under the same lock.
            if (Thread.currentThread() == workerThread.get() && checks.get() == 1 && exitPaused.compareAndSet(false, true)) {
                atExit.countDown();
                await(releaseExit);
            }
            return true;
        }, path -> {
            checks.incrementAndGet();
            checked.countDown();
        }, () -> fail("Unexpected overflow"));
        Thread submitter = new Thread(() -> {
            submitting.countDown();
            queue.submit(SECOND);
            submitted.countDown();
        }, "vas-change-submitter-test");
        try {
            queue.submit(FIRST);
            assertTrue(atExit.await(3, TimeUnit.SECONDS));
            submitter.start();
            assertTrue(submitting.await(3, TimeUnit.SECONDS));
            releaseExit.countDown();
            assertTrue(submitted.await(3, TimeUnit.SECONDS));
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertEquals(2, checks.get());
            assertEquals(2, dispatches.get());
        } finally {
            releaseExit.countDown();
            submitter.join(3000);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(submitter.isAlive());
        }
    }

    @Test public void inactiveQueueSchedulesNothing() {
        Dispatcher dispatcher = new Dispatcher();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher, () -> false,
            path -> fail("Inactive check"), () -> fail("Cancellation is not overflow"));
        queue.submit(FIRST);
        assertEquals(0, dispatcher.submissions);
    }

    @Test public void cancellationBeforeDrainDiscardsAllPendingChecksPermanently() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicBoolean active = new AtomicBoolean(true);
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, dispatcher, active::get,
            path -> fail("Cancelled check"), () -> fail("Cancellation is not overflow"));
        queue.submit(FIRST);
        queue.submit(SECOND);
        active.set(false);
        dispatcher.runNext();
        active.set(true);
        queue.submit(THIRD);
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void cancellationDuringCheckStopsTheRemainingPaths() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicBoolean active = new AtomicBoolean(true);
        List<Path> checked = new ArrayList<>();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, dispatcher, active::get, path -> {
            checked.add(path);
            active.set(false);
        }, () -> fail("Cancellation is not overflow"));
        queue.submit(FIRST);
        queue.submit(SECOND);
        dispatcher.runNext();
        queue.submit(THIRD);
        assertEquals(List.of(FIRST), checked);
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void cancellationAfterDequeuePreventsTheCheckFromStarting() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicInteger activeChecks = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher,
            () -> activeChecks.incrementAndGet() < 3,
            path -> fail("Cancelled check"), () -> fail("Cancellation is not overflow"));
        queue.submit(FIRST);
        dispatcher.runNext();
        assertEquals(3, activeChecks.get());
        queue.submit(SECOND);
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void overflowClearsPendingPathsAndCallsBackOnlyOnce() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, dispatcher, () -> true,
            path -> fail("Overflowed queue must not check"), overflows::incrementAndGet);
        queue.submit(FIRST);
        queue.submit(SECOND);
        queue.submit(THIRD);
        for (int i = 0; i < 100; i++) queue.submit(Path.of("extra" + i));
        assertEquals(1, overflows.get());
        assertEquals(1, dispatcher.submissions);
        dispatcher.runNext();
        assertEquals(1, overflows.get());
    }

    @Test(timeout = 10000) public void slowCheckDoesNotBlockSubmissionOrTheSwingEdt() throws Exception {
        assertFalse("The test coordinator must not block the EDT", SwingUtilities.isEventDispatchThread());
        AtomicReference<Thread> edt = new AtomicReference<>();
        // Rider's host suite shares this JVM and event queue. Establish that its
        // preceding EDT work has finished before measuring this queue's contract.
        // Initialization is still bounded by the unchanged whole-test timeout.
        SwingUtilities.invokeAndWait(() -> edt.set(Thread.currentThread()));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch responsive = new CountDownLatch(1);
        AtomicReference<Thread> submitter = new AtomicReference<>();
        AtomicReference<Thread> checker = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, task -> {
            dispatches.incrementAndGet();
            worker.execute(() -> recordFailure(task, asynchronousFailure));
        }, active::get, path -> {
            checker.set(Thread.currentThread());
            assertFalse("The slow checker must run off EDT", SwingUtilities.isEventDispatchThread());
            checks.incrementAndGet();
            if (path.equals(FIRST)) {
                started.countDown();
                await(release);
            }
            finished.countDown();
        }, () -> fail("Unexpected overflow"));
        try {
            Thread workerThread = worker.submit(Thread::currentThread).get(3, TimeUnit.SECONDS);
            assertNotSame(edt.get(), workerThread);
            SwingUtilities.invokeLater(() -> recordFailure(() -> {
                if (!active.get()) return;
                submitter.set(Thread.currentThread());
                queue.submit(FIRST);
                submitted.countDown();
            }, asynchronousFailure));
            awaitQueuePhase(started, "Checker must start after EDT/worker readiness", asynchronousFailure, edt.get(), workerThread);
            awaitQueuePhase(submitted, "Submission must return while the check is blocked", asynchronousFailure, edt.get(), workerThread);
            SwingUtilities.invokeLater(() -> recordFailure(() -> {
                if (!active.get()) return;
                queue.submit(SECOND);
                queue.submit(SECOND);
                responsive.countDown();
            }, asynchronousFailure));
            awaitQueuePhase(responsive, "EDT must process another event during the slow check", asynchronousFailure, edt.get(), workerThread);
            assertSame(edt.get(), submitter.get());
            assertSame(workerThread, checker.get());
            assertNotSame(submitter.get(), checker.get());
            assertEquals("The slow check must still be held during the EDT probe", 1, release.getCount());
            assertEquals(1, checks.get());
            assertEquals(1, dispatches.get());
            release.countDown();
            awaitQueuePhase(finished, "Both distinct checks must finish after release", asynchronousFailure, edt.get(), workerThread);
            // A second countDown alone does not prove the drain has ended: an
            // incorrectly retained duplicate could still be checked afterwards.
            worker.submit(() -> { }).get(3, TimeUnit.SECONDS);
            if (asynchronousFailure.get() != null)
                throw new AssertionError("Queue drain must finish without asynchronous errors", asynchronousFailure.get());
            assertEquals(2, checks.get());
            assertEquals(1, dispatches.get());
        } finally {
            // A failed readiness/response assertion can leave an EDT callback
            // queued. Deactivate it and drain posted callbacks before closing its
            // executor, so it cannot leak RejectedExecutionException into a later test.
            active.set(false);
            release.countDown();
            try { SwingUtilities.invokeAndWait(() -> { }); }
            finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static void recordFailure(Runnable action, AtomicReference<Throwable> failure) {
        try { action.run(); }
        catch (Throwable exception) { failure.compareAndSet(null, exception); }
    }

    private static void awaitQueuePhase(CountDownLatch latch, String phase, AtomicReference<Throwable> failure,
                                       Thread edt, Thread worker) throws InterruptedException {
        boolean completed = latch.await(3, TimeUnit.SECONDS);
        if (failure.get() != null) throw new AssertionError(phase, failure.get());
        if (!completed) {
            StringBuilder evidence = new StringBuilder(phase);
            for (Thread thread : List.of(edt, worker)) {
                evidence.append("\n").append(thread.getName()).append(": ").append(thread.getState());
                for (StackTraceElement frame : thread.getStackTrace()) evidence.append("\n  at ").append(frame);
            }
            fail(evidence.toString());
        }
    }

    @Test(timeout = 10000) public void overflowDuringSlowCheckStopsPendingWorkWithoutWaiting() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, worker::execute, () -> true, path -> {
            checks.incrementAndGet();
            started.countDown();
            await(release);
        }, overflows::incrementAndGet);
        try {
            queue.submit(FIRST);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            queue.submit(SECOND);
            queue.submit(THIRD);
            assertEquals(1, overflows.get());
            assertEquals(1, checks.get());
        } finally {
            release.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(1, checks.get());
    }

    @Test public void checkFailureClearsStopsAndRethrowsTheOriginalFailure() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicInteger overflows = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("failed check");
        VasProjectChangeQueue queue = new VasProjectChangeQueue(2, dispatcher, () -> true, path -> {
            checks.incrementAndGet();
            throw failure;
        }, overflows::incrementAndGet);
        queue.submit(FIRST);
        queue.submit(SECOND);
        assertSame(failure, assertThrows(IllegalStateException.class, dispatcher::runNext));
        queue.submit(THIRD);
        assertEquals(1, checks.get());
        assertEquals(1, overflows.get());
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void dispatchFailureIsFailClosedAndRetainsTheOriginalException() {
        AtomicInteger overflows = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("rejected dispatch");
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, task -> { throw failure; }, () -> true,
            path -> fail("Rejected task must not check"), overflows::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.submit(FIRST)));
        queue.submit(SECOND);
        assertEquals(1, overflows.get());
    }

    @Test public void failureOfOverflowHandlerIsSuppressedBehindTheCheckFailure() {
        Dispatcher dispatcher = new Dispatcher();
        Error failure = new AssertionError("failed check");
        RuntimeException overflowFailure = new IllegalStateException("failed invalidation");
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher, () -> true,
            path -> { throw failure; }, () -> { throw overflowFailure; });
        queue.submit(FIRST);
        assertSame(failure, assertThrows(AssertionError.class, dispatcher::runNext));
        assertArrayEquals(new Throwable[]{overflowFailure}, failure.getSuppressed());
        queue.submit(SECOND);
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void activeCallbackFailureAlsoClearsStopsAndSurfaces() {
        Dispatcher dispatcher = new Dispatcher();
        AtomicInteger overflows = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("failed activity check");
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher, () -> { throw failure; },
            path -> fail("Failed activity check"), overflows::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.submit(FIRST)));
        queue.submit(SECOND);
        assertEquals(1, overflows.get());
        assertEquals(0, dispatcher.submissions);
    }

    @Test public void overflowCallbackFailureSurfacesWithoutRestartingTheQueue() {
        Dispatcher dispatcher = new Dispatcher();
        RuntimeException failure = new IllegalStateException("failed invalidation");
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, dispatcher, () -> true,
            path -> fail("Overflowed queue must not check"), () -> {
                overflows.incrementAndGet();
                throw failure;
            });
        queue.submit(FIRST);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.submit(SECOND)));
        dispatcher.runNext();
        queue.submit(THIRD);
        assertEquals(1, overflows.get());
        assertEquals(1, dispatcher.submissions);
    }

    @Test public void inlineDispatcherIsRejectedWithoutCheckingOnTheSubmittingThread() {
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, Runnable::run, () -> true,
            path -> fail("Inline check"), overflows::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> queue.submit(FIRST));
        queue.submit(SECOND);
        assertEquals(1, overflows.get());
    }

    @Test public void swallowedInlineRejectionStillStopsTheQueue() {
        AtomicInteger overflows = new AtomicInteger();
        VasProjectChangeQueue queue = new VasProjectChangeQueue(1, task -> {
            try { task.run(); } catch (IllegalStateException ignored) { }
        }, () -> true, path -> fail("Inline check"), overflows::incrementAndGet);
        queue.submit(FIRST);
        queue.submit(SECOND);
        assertEquals(1, overflows.get());
    }

    @Test public void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new VasProjectChangeQueue(0, task -> {}, () -> true, path -> {}, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new VasProjectChangeQueue(-1, task -> {}, () -> true, path -> {}, () -> {}));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Latch did not complete", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
