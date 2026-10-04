'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { ParcelMetadataEvidence } = require('./parcelMetadataEvidence');

const target = path.resolve('fixture', 'shared 文😀.vas');

test('Parcel target knowledge requires its initial create, never another path or an update', () => {
  for (const events of [[], [{ type: 'create', path: path.resolve('fixture', 'sentinel') }],
    [{ type: 'update', path: target }], [{ type: 'delete', path: target }]]) {
    const evidence = new ParcelMetadataEvidence(target, { created: () => assert.fail('unexpected target creation') });
    for (const event of events) assert.equal(evidence.observed(event), false);
    assert.throws(() => evidence.trigger(), /observe target creation/);
    evidence.close();
  }
});

test('initial and repeated target creates cannot satisfy the separate post-publication strict update', () => {
  const creates = [], updates = [];
  const evidence = new ParcelMetadataEvidence(target, { created: event => creates.push(event), updated: event => updates.push(event) });
  assert.equal(evidence.observed({ type: 'create', path: target }), true);
  assert.equal(creates.length, 1); assert.equal(updates.length, 0);
  assert.equal(evidence.observed({ type: 'update', path: target }), false, 'pre-trigger update is historical');
  evidence.trigger();
  for (const event of [{ type: 'create', path: target }, { type: 'delete', path: target },
    { type: 'update', path: path.resolve('fixture', 'sentinel') }]) assert.equal(evidence.observed(event), false);
  assert.equal(updates.length, 0, 'no target update remains missing despite initial or unrelated receipts');
  assert.equal(evidence.observed({ type: 'update', path: target }), true);
  assert.equal(updates.length, 1); assert.equal(creates.length, 1);
  assert.equal(evidence.observed({ type: 'update', path: target }), false);
  assert.throws(() => evidence.trigger(), /only once/);
  evidence.close();
  assert.equal(evidence.observed({ type: 'update', path: target }), false);
});
