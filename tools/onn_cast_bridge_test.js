'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const manifest = fs.readFileSync(
  path.join(root, 'tools', 'onn_key_guard', 'AndroidManifest.xml'), 'utf8');
const source = fs.readFileSync(
  path.join(root, 'tools', 'onn_key_guard', 'src', 'dev', 'yossi', 'onnkeyguard', 'CastLaunchActivity.java'), 'utf8');

const count = (text, needle) => text.split(needle).length - 1;

assert.strictEqual(count(manifest, 'android:name=".CastLaunchActivity"'), 1,
  'CastLaunchActivity must be declared exactly once');
assert.strictEqual(count(manifest, 'com.google.android.gms.cast.tv.action.LAUNCH'), 2,
  'the bridge must mirror the stock receiver generic and YouTube-URL Cast launch filters');
assert.strictEqual(count(manifest, 'android.permission.WAKE_LOCK'), 1,
  'the Cast wake permission must be declared exactly once');
assert.ok(manifest.includes('android:scheme="https" android:host="www.youtube.com"'),
  'the URL-specific Cast filter must remain limited to https://www.youtube.com');
assert.ok(manifest.includes('android:name=".MorningPlaybackReceiver"'),
  'the existing Morning Sesame receiver must remain installed');
assert.ok(manifest.includes('android:permission="dev.yossi.onnkeyguard.permission.MORNING_PLAY"'),
  'the Morning Sesame receiver must keep its signature-protected permission');

assert.ok(source.includes('"io.gh.yossim.tizentube.cobalt"'),
  'Cast launches must target the protected TizenSub+ package');
assert.ok(source.includes('"dev.cobalt.app.MainActivity"'),
  'Cast launches must target TizenSub+ MainActivity explicitly');
assert.ok(source.includes('ACTION_CAST_LAUNCH.equals(intent.getAction())'),
  'the exported bridge must reject non-Cast actions');
assert.ok(source.includes('"https".equalsIgnoreCase(data.getScheme())'),
  'the bridge must enforce HTTPS when Cast provides a URL');
assert.ok(source.includes('"www.youtube.com".equalsIgnoreCase(data.getHost())'),
  'the bridge must reject non-YouTube Cast URLs');
assert.ok(source.includes('incoming.getExtras()'),
  'Cast extras must be forwarded to TizenSub+');
assert.ok(source.includes('PowerManager.ACQUIRE_CAUSES_WAKEUP'),
  'a real Cast launch must be able to wake the TV');

console.log('Onn Cast bridge static regression test passed.');
