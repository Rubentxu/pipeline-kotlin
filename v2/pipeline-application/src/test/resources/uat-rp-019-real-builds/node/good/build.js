#!/usr/bin/env node
// Minimal Node build script: writes a deterministic artefact under dist/.
const fs = require('fs');
const path = require('path');

const dist = path.join(__dirname, '..', 'dist');
fs.mkdirSync(dist, { recursive: true });
const out = path.join(dist, 'output.txt');
fs.writeFileSync(out, 'ORACLE_NODE_BUILD_OK\n');
console.log('ORACLE_NODE_BUILD_OK', out);
