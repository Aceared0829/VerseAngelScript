'use strict';

const fs = require('node:fs/promises');
const { watch } = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

// Directory fs.watch on macOS returns before its FSEventStream necessarily
// starts (Node v24.20.0 deps/uv/src/unix/fsevents.c:742-794). No public ready
// event exists. Establish readiness on this exact watcher using an independent
// sentinel, never the source whose single saved change the test must observe.
async function readyDirectoryWatch(root, onEvent, { createWatcher = watch, writeProbe = fs.writeFile,
  timeoutMs = 5000, probeIntervalMs = 50, signal } = {}) {
  const name = `.vas-watch-ready-${randomUUID()}`, sentinel = path.join(root, name);
  const audit = { root, sentinel: name, phase: 'readiness', probes: 0, events: [], dropped: 0 };
  let ready = false, closed = false, timer, expiryTimer, watcher, signalReady, signalError, failure;
  const controller = new AbortController();
  const received = new Promise(resolve => { signalReady = resolve; });
  const failed = new Promise((_, reject) => { signalError = reject; });
  // A late watcher error remains observable by wait(), without an unhandled
  // rejection between the readiness phase and the selected-input wait.
  void failed.catch(() => {});
  function fail(error) {
    if (closed || failure) return;
    failure = error; audit.error = error.message;
    controller.abort(error); signalError(error);
  }
  const aborted = () => fail(new Error('Directory watcher readiness cancelled'));
  function record(kind, filename, watcherId) {
    if (closed) return;
    const rawName = filename == null ? null : String(filename);
    const event = { kind, name: rawName?.slice(0, 1024) ?? null, phase: audit.phase };
    if (Number.isSafeInteger(watcherId) && watcherId > 0) event.watcherId = watcherId;
    if (audit.events.length < 64) audit.events.push(event); else audit.dropped++;
    if (rawName === name && !ready) { ready = true; audit.ready = { ...event }; signalReady(); }
    try { onEvent(kind, filename); } catch (error) { fail(error); }
  }
  function close() {
    closed = true; clearTimeout(timer); clearTimeout(expiryTimer);
    signal?.removeEventListener('abort', aborted); watcher?.close();
  }
  try {
    signal?.addEventListener('abort', aborted);
    if (signal?.aborted) aborted();
    if (failure) throw failure;
    watcher = createWatcher(root, record);
    watcher.on('error', fail);
    const expires = Date.now() + timeoutMs;
    expiryTimer = setTimeout(() => fail(new Error(`Timed out: independent directory watcher readiness; ${JSON.stringify(audit)}`)), timeoutMs);
    while (!ready) {
      if (failure) throw failure;
      // Probes are bounded to the readiness sentinel. The interval controls
      // probe load, not a presumed startup delay: only an actual receipt opens
      // the gate, and even a long delay with no receipt must fail.
      audit.probes++;
      // Drain each write even when its callback arrives first or an error/
      // cancellation occurs. Timeout aborts the owned fs.writeFile operation.
      await writeProbe(sentinel, `${audit.probes}\n`, { signal: controller.signal });
      if (failure) throw failure;
      try {
        await Promise.race([received, failed, new Promise(resolve => {
          timer = setTimeout(resolve, Math.min(probeIntervalMs, Math.max(0, expires - Date.now())));
        })]);
      } finally { clearTimeout(timer); }
    }
    if (failure) throw failure;
    clearTimeout(expiryTimer);
    audit.phase = 'ready';
    return { audit, close, wait: promise => failure ? Promise.reject(failure) : Promise.race([promise, failed]) };
  } catch (error) { close(); throw failure || error; }
}

module.exports = { readyDirectoryWatch };
