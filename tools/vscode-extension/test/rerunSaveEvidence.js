'use strict';

const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { sameFileName } = require('../src/toolchain');
const { documentDigest, readVersion } = require('../src/projectVersions');

// Test-only evidence for one real workbench save. OS watcher delivery and
// onDidSaveTextDocument have no guaranteed relative order or shared write ID.
// A receipt inside this willSave epoch must observe the exact saved version;
// it is not evidence that the OS attached this callback to a particular write.
class RerunSaveEvidence {
  constructor({ files, version, text, before, read = readVersion }) {
    const bytes = Buffer.from(text, 'utf8');
    assert.ok(bytes.length <= 64 * 1024, 'rerun audit fixture must remain small');
    this.expected = { version, documentDigest: documentDigest(text), byteLength: bytes.length,
      digest: createHash('sha256').update(bytes).digest('hex') };
    assert.ok(this.expected.documentDigest, 'rerun text must be valid Unicode');
    assert.equal(before.kind, 'readable');
    assert.notEqual(before.digest, this.expected.digest, 'this rerun must save changed bytes, not a no-op');
    this.files = files; this.read = read;
    this.events = []; this.pending = new Set(); this.cancel = { cancelled: false };
    this.budget = { bytes: 0 }; // readVersion bounds each read and the aggregate.
  }
  willSave(event) {
    if (this.cancel.cancelled) return;
    if (this.will || event.version !== this.expected.version || !event.dirty || event.digest !== this.expected.documentDigest) {
      this.error = new Error('Rerun willSave did not describe the selected dirty document version'); return;
    }
    this.will = { ...event };
  }
  didSave(event) {
    if (this.cancel.cancelled) return;
    if (!this.will || this.saved || event.order <= this.will.order || event.version !== this.expected.version ||
      event.dirty || event.digest !== this.expected.documentDigest) {
      this.error = new Error('Rerun didSave did not confirm the selected document version'); return;
    }
    this.saved = { ...event };
    for (const event of this.events) this.capture(event);
  }
  observed(event) {
    if (this.cancel.cancelled || !this.will || event.order <= this.will.order ||
      !['change', 'create'].includes(event.kind) || !this.files.some(file => sameFileName(file, event.path))) return;
    if (this.events.length >= 64) { this.error = new Error('Rerun saved-notification audit limit exceeded'); return; }
    const receipt = { ...event };
    this.events.push(receipt);
    if (this.saved) this.capture(receipt);
  }
  capture(receipt) {
    // An early callback may precede completion of the physical write. Wait for
    // the matching didSave before this single stable read, without polling or
    // retrying a mismatch. No promise waits indefinitely for a missing save.
    const pending = (async () => {
      try {
        const value = await this.read(receipt.path, this.cancel, this.budget);
        if (!this.cancel.cancelled) {
          receipt.snapshot = value;
          if (value.kind !== 'readable' || value.digest !== this.expected.digest || value.byteLength !== this.expected.byteLength) {
            throw new Error('Rerun notification did not observe the expected saved bytes');
          }
        }
      } catch (error) {
        if (!this.cancel.cancelled) { receipt.error = error.message; this.error = error; }
      }
    })();
    this.pending.add(pending);
    void pending.then(() => this.pending.delete(pending));
  }
  currentNotifications() {
    if (this.cancel.cancelled) return [];
    if (this.error) throw this.error;
    return this.events.filter(event => event.snapshot?.kind === 'readable' &&
      event.snapshot.digest === this.expected.digest && event.snapshot.byteLength === this.expected.byteLength);
  }
  async settle() { while (this.pending.size) await Promise.all([...this.pending]); }
  verify(proof, current) {
    const events = this.currentNotifications();
    assert.ok(this.saved && events.length, 'rerun requires an actual scoped notification observing its saved bytes');
    assert.equal(proof?.sourceDigestVersion, 1, 'rerun requires actual compiler loaded-source proof');
    assert.equal(proof.sourceDigestAlgorithm, 'sha256');
    assert.equal(proof.sourceDigest, this.expected.digest, 'native compiler must load this saved editor content');
    assert.equal(proof.sourceByteLength, this.expected.byteLength);
    assert.equal(current.kind, 'readable');
    assert.equal(current.digest, this.expected.digest);
    assert.equal(current.byteLength, this.expected.byteLength);
    assert.notEqual(current.ino, '0');
    assert.ok(events.some(event => event.snapshot.dev === current.dev && event.snapshot.ino === current.ino),
      'observed editor path must still be the actual compiler section physical file');
    return events;
  }
  snapshot() { return { expected: this.expected, will: this.will, saved: this.saved, events: this.events, error: this.error?.message }; }
  async dispose() { this.cancel.cancelled = true; await this.settle(); }
}

module.exports = { RerunSaveEvidence };
