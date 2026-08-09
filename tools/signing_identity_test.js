// Permanent behavior tests for release signing identity verification.
// Uses the real keytool executable and real temporary keystores - no mocks.
// Run with: node tools/signing_identity_test.js
'use strict';
const assert = require('assert');
const { execFileSync, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { normalize } = require('./verify_signing_identity.js');

function makeKeystore(dir) {
  const ks = path.join(dir, 'test.keystore');
  const pass = 'test-pass-123';
  execFileSync('keytool', [
    '-genkeypair',
    '-keystore', ks,
    '-storepass', pass,
    '-keypass', pass,
    '-alias', 'test',
    '-keyalg', 'RSA',
    '-keysize', '2048',
    '-validity', '10000',
    '-dname', 'CN=Test,O=Test,C=US',
    '-storetype', 'PKCS12',
  ], { stdio: 'pipe' });
  return { ks, pass };
}

function runVerifier(b64File, passFile, expectedFile) {
  return spawnSync(process.execPath, [
    path.join(__dirname, 'verify_signing_identity.js'),
    b64File, passFile, expectedFile,
  ], { encoding: 'utf8' });
}

function testNormalization() {
  assert.strictEqual(normalize('SHA256: F8:82:1E:41'), 'F8821E41');
  assert.strictEqual(normalize('SHA256:F8:82:1E:41'), 'F8821E41');
  assert.strictEqual(normalize('f8:82:1e:41'), 'F8821E41');
  assert.strictEqual(normalize('sha-256: f8:82:1e:41'), 'F8821E41');
  assert.strictEqual(normalize(''), '');
}

function testFingerprintAcceptAndReject() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tizentube-sign-test-'));
  try {
    const { ks, pass } = makeKeystore(dir);
    const out = execFileSync('keytool', ['-list', '-v', '-keystore', ks, '-storepass', pass, '-storetype', 'PKCS12'], { encoding: 'utf8' });
    const m = out.match(/SHA256:\s*([0-9A-Fa-f:]+)/);
    assert.ok(m, 'keytool output must contain a SHA-256 fingerprint');
    const fp = m[1];

    const b64File = path.join(dir, 'key.b64');
    const passFile = path.join(dir, 'pass.txt');
    fs.writeFileSync(b64File, fs.readFileSync(ks).toString('base64'));
    fs.writeFileSync(passFile, pass);

    const okFile = path.join(dir, 'ok-fp.txt');
    fs.writeFileSync(okFile, fp);
    const ok = runVerifier(b64File, passFile, okFile);
    assert.strictEqual(ok.status, 0, 'correct fingerprint must verify: ' + ok.stderr);
    assert.ok(/signing identity verified/.test(ok.stdout));

    const badFile = path.join(dir, 'bad-fp.txt');
    fs.writeFileSync(badFile, 'SHA256: 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00');
    const bad = runVerifier(b64File, passFile, badFile);
    assert.notStrictEqual(bad.status, 0, 'wrong fingerprint must fail');
    assert.ok(/identity mismatch/.test(bad.stderr));

    const wrongPassFile = path.join(dir, 'wrong-pass.txt');
    fs.writeFileSync(wrongPassFile, 'not-the-password');
    const wrongPass = runVerifier(b64File, wrongPassFile, okFile);
    assert.notStrictEqual(wrongPass.status, 0, 'wrong keystore password must fail');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

testNormalization();
testFingerprintAcceptAndReject();
console.log('All release signing identity tests passed.');
