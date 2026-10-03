'use strict';

const { readVersion } = require('./projectVersions');

/** File notifications may arrive after the compiler. They can preserve a result
 * only by matching that result's immutable, pre-launch input versions. */
class ProjectInputObservations {
  constructor(diagnostics, dependencies, read = readVersion) {
    Object.assign(this, { diagnostics, dependencies, read });
    this.registrations = new Map();
    this.queue = new Map();
    this.epoch = 0;
    this.revision = 0;
    this.cancel = { cancelled: false };
  }
  live() {
    for (const [key, item] of this.registrations) {
      if (!this.diagnostics.current(item.token)) this.registrations.delete(key);
    }
    return [...this.registrations.values()];
  }
  register(plan, token) {
    if (this.cancel.cancelled) throw new Error('VAS input observations have been disposed.');
    this.live();
    if (!this.registrations.has(plan.key) && this.registrations.size >= 256) {
      throw new Error('VAS current project validation limit reached (256 units).');
    }
    this.registrations.set(plan.key, { plan, token, versions: plan.inputVersions });
    this.epoch++;
    this.revision++;
  }
  invalidate() {
    if (this.cancel.cancelled) return;
    this.diagnostics.invalidate();
    this.registrations.clear();
    this.epoch++;
    this.revision++;
  }
  changed(file, { kind = 'change', documentDigest } = {}) {
    if (this.cancel.cancelled) return;
    this.revision++;
    // Create/delete events and unknown generations cannot establish equality.
    if (kind !== 'change' || !this.live().length) { this.invalidate(); return; }
    if (!this.queue.has(file) && this.queue.size >= 64) {
      this.queue.clear(); this.invalidate();
      return;
    }
    const previous = this.queue.get(file);
    if (previous?.documentDigest !== undefined && documentDigest !== undefined && previous.documentDigest !== documentDigest) {
      this.queue.clear(); this.invalidate();
      return;
    }
    // A plain FS notification cannot erase evidence from a clean editor reload.
    this.queue.set(file, { file, documentDigest: documentDigest ?? previous?.documentDigest });
    this.start();
  }
  start() {
    if (this.pending || !this.queue.size || this.cancel.cancelled) return;
    this.pending = this.process().catch(() => { this.queue.clear(); this.invalidate(); }).finally(() => {
      this.pending = undefined;
      // A microtask can enqueue after process() drains but before this finally.
      // Do not leave that delivered event behind an already-resolved barrier.
      this.start();
    });
  }
  async process() {
    let events = 0;
    const budget = { bytes: 0 };
    while (this.queue.size && !this.cancel.cancelled) {
      // Bound an uninterrupted event wave, including repeatedly replaced paths.
      if (++events > 256) { this.queue.clear(); this.invalidate(); return; }
      const [key, event] = this.queue.entries().next().value;
      this.queue.delete(key);
      for (let attempt = 0; attempt < 8; attempt++) {
        const epoch = this.epoch, live = this.live();
        if (!live.length) { this.invalidate(); break; }
        let version, failed;
        try { version = await this.read(event.file, this.cancel, budget); }
        catch (error) { failed = error; }
        if (this.cancel.cancelled) return;
        const current = this.live();
        if (epoch !== this.epoch || live.length !== current.length || live.some((item, index) => item !== current[index])) {
          if (attempt === 7) this.invalidate();
          continue;
        }
        if (failed) { this.invalidate(); break; }
        let affected = false, unchanged = true;
        for (const item of current) {
          const candidates = item.versions?.lookup(event.file, version) || [];
          if (candidates.length || this.dependencies.affects(item.plan.key, event.file)) {
            affected = true;
            if (!candidates.length || !item.versions.matches(event.file, version, event.documentDigest)) unchanged = false;
          }
        }
        // Unknown paths stay conservative; same content in an unrelated file
        // is never proof that it was an input to this generation.
        if (!affected || !unchanged) this.invalidate();
        break;
      }
    }
  }
  async settle() {
    // New events can arrive during a filesystem await. A single captured
    // promise is not a barrier for events appended while it was settling.
    while (!this.cancel.cancelled && (this.pending || this.queue.size)) {
      this.start();
      await this.pending;
    }
  }
  async validate(checkFresh) {
    for (let attempt = 0; attempt < 8; attempt++) {
      await this.settle();
      if (this.cancel.cancelled) throw new Error('VAS input observations have been disposed.');
      const revision = this.revision;
      await checkFresh();
      if (this.cancel.cancelled) throw new Error('VAS input observations have been disposed.');
      // Freshness is the final asynchronous read before launch/publication.
      // If it overlapped delivered events, settle and re-read instead of
      // waiting on those events after the last freshness check.
      if (this.current(revision)) return revision;
    }
    throw new Error('VAS inputs kept changing during validation. Build Project again.');
  }
  current(revision) {
    // The caller must check again in its own synchronous publication/launch
    // continuation: another microtask may run after validate resolves.
    return !this.cancel.cancelled && revision === this.revision && !this.pending && !this.queue.size;
  }
  dispose() {
    this.cancel.cancelled = true;
    this.queue.clear(); this.registrations.clear(); this.epoch++; this.revision++;
  }
}

module.exports = { ProjectInputObservations };
