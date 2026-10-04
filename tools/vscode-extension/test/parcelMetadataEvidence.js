'use strict';

const assert = require('node:assert/strict');
const { sameFileName } = require('../src/toolchain');

// Receipts from one actual Parcel subscription. An initial create establishes
// that this backend knows the target before compilation. It cannot stand in for
// the separate update caused by the single post-publication metadata operation.
class ParcelMetadataEvidence {
  constructor(target, { created = () => {}, updated = () => {} } = {}) {
    this.target = target;
    this.created = created;
    this.updated = updated;
    this.phase = 'initial';
  }

  observed(event) {
    if (this.phase === 'closed' || !sameFileName(event.path, this.target)) return false;
    if (this.phase === 'initial' && event.type === 'create' && !this.initial) {
      this.initial = { ...event };
      this.created(this.initial);
      return true;
    }
    if (this.phase === 'triggered' && event.type === 'update' && !this.update) {
      this.update = { ...event };
      this.updated(this.update);
      return true;
    }
    return false;
  }

  trigger() {
    assert.equal(this.phase, 'initial', 'metadata trigger must occur only once');
    assert.ok(this.initial, 'the same subscription must observe target creation before compilation');
    this.phase = 'triggered';
  }

  close() { this.phase = 'closed'; }
}

module.exports = { ParcelMetadataEvidence };
