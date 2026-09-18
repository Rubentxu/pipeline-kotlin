'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { add } = require('../src/app');

test('add works', () => { assert.strictEqual(add(2, 3), 5); });

// WU-LPR-064 failure path: DEMO_BROKEN=true requires a deliberate failure.
test('deliberate break switch', () => {
  assert.notStrictEqual(process.env.DEMO_BROKEN, 'true', 'DEMO_BROKEN=true requested a failing build');
});
