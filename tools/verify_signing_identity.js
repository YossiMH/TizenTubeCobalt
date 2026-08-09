#!/usr/bin/env node
// Verify that a release signing keystore has the exact expected identity.
//
// Usage:
//   node tools/verify_signing_identity.js <keystore-b64-file> <password-file> <expected-fingerprint-file>
//
// Behavior:
//   - Decodes the base64 keystore to a temporary PKCS12/JKS file.
//   - Runs the real keytool to print the certificate SHA-256 fingerprint.
//   - Normalizes both sides (uppercase, no colons/spaces) and compares.
//   - Exits 0 when the fingerprint matches, 1 otherwise, printing a clear
//     diagnostic. Never prints the keystore password.
'use strict';
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function normalize(fp) {
  return String(fp)
    .replace(/^SHA-?256[: ]*/i, '')
    .replace(/[^0-9A-Fa-f]/g, '')
    .toUpperCase();
}

function main(argv) {
  if (argv.length !== 3) {
    console.error('usage: verify_signing_identity.js <keystore-b64-file> <password-file> <expected-fingerprint-file>');
    process.exit(2);
  }
  const [b64File, passFile, expectedFile] = argv;
  const b64 = fs.readFileSync(b64File, 'utf8').trim();
  const password = fs.readFileSync(passFile, 'utf8').trim();
  const expected = normalize(fs.readFileSync(expectedFile, 'utf8'));
  if (!expected) {
    console.error('expected fingerprint file is empty or unreadable: ' + expectedFile);
    process.exit(2);
  }

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tizentube-signing-'));
  const ksPath = path.join(dir, 'keystore');
  const passPath = path.join(dir, 'pass');
  try {
    fs.writeFileSync(ksPath, Buffer.from(b64, 'base64'));
    fs.writeFileSync(passPath, password);
    let out;
    try {
      out = execFileSync('keytool', [
        '-list', '-v',
        '-keystore', ksPath,
        '-storepass:file', passPath,
        '-storetype', 'PKCS12',
      ], { encoding: 'utf8' });
    } catch (e) {
      console.error('keytool failed to read the keystore (wrong password or corrupt file?): ' + e.message.split('\n')[0]);
      process.exit(1);
    }
    const m = out.match(/SHA256:\s*([0-9A-Fa-f:]+)/);
    if (!m) {
      console.error('could not find a SHA-256 certificate fingerprint in keytool output');
      process.exit(1);
    }
    const actual = normalize(m[1]);
    if (actual !== expected) {
      console.error('signing identity mismatch:');
      console.error('  expected fingerprint: ' + expected);
      console.error('  actual fingerprint:   ' + actual);
      process.exit(1);
    }
    console.log('signing identity verified: SHA-256 ' + actual);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

if (require.main === module) {
  main(process.argv.slice(2));
}

module.exports = { normalize };
