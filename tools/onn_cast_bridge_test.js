'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const manifest = fs.readFileSync(
  path.join(root, 'tools', 'onn_key_guard', 'AndroidManifest.xml'), 'utf8');
const source = fs.readFileSync(
  path.join(root, 'tools', 'onn_key_guard', 'src', 'dev', 'yossi', 'onnkeyguard', 'CastLaunchActivity.java'), 'utf8');
const service = fs.readFileSync(
  path.join(root, 'tools', 'onn_key_guard', 'src', 'dev', 'yossi', 'onnkeyguard', 'KeyGuardService.java'), 'utf8');

const count = (text, needle) => text.split(needle).length - 1;

assert.strictEqual(count(manifest, 'android:name=".CastLaunchActivity"'), 1,
  'CastLaunchActivity must be declared exactly once');
assert.strictEqual(count(manifest, 'com.google.android.gms.cast.tv.action.LAUNCH'), 2,
  'the bridge must mirror the stock receiver generic and YouTube-URL Cast launch filters');
assert.strictEqual(count(manifest, 'android.permission.WAKE_LOCK'), 1,
  'the Cast wake permission must be declared exactly once');
assert.strictEqual(count(manifest, 'android.permission.READ_LOGS'), 0,
  'the Cast guard must not depend on privileged cross-process log access');
assert.strictEqual(count(manifest, 'android.permission.MODIFY_AUDIO_SETTINGS'), 1,
  'the web-receiver bridge must be able to stop the unrestricted Cast media session');
assert.ok(manifest.includes('android:scheme="https" android:host="www.youtube.com"'),
  'the URL-specific Cast filter must remain limited to https://www.youtube.com');
assert.ok(manifest.includes('android:name=".MorningPlaybackReceiver"'),
  'the existing Morning Sesame receiver must remain installed');
assert.ok(manifest.includes('android:permission="dev.yossi.onnkeyguard.permission.MORNING_PLAY"'),
  'the Morning Sesame receiver must keep its signature-protected permission');

assert.ok(source.includes('"io.gh.yossim.tizentube.cobalt"'),
  'native Cast launches must target the protected TizenSub+ package');
assert.ok(source.includes('"dev.cobalt.app.MainActivity"'),
  'native Cast launches must target TizenSub+ MainActivity explicitly');
assert.ok(source.includes('ACTION_CAST_LAUNCH.equals(intent.getAction())'),
  'the exported native bridge must reject non-Cast actions');
assert.ok(source.includes('"https".equalsIgnoreCase(data.getScheme())'),
  'the native bridge must enforce HTTPS when Cast provides a URL');
assert.ok(source.includes('"www.youtube.com".equalsIgnoreCase(data.getHost())'),
  'the native bridge must reject non-YouTube Cast URLs');
assert.ok(source.includes('incoming.getExtras()'),
  'native Cast extras must be forwarded to TizenSub+');
assert.ok(source.includes('PowerManager.ACQUIRE_CAUSES_WAKEUP'),
  'a native Cast launch must be able to wake the TV');

assert.ok(service.includes('"com.google.android.apps.mediashell"'),
  'the runtime bridge must recognize the Google Cast web shell');
assert.ok(service.includes('"CastWebContentsActivity"'),
  'the runtime bridge must be limited to the Cast web contents activity');
assert.ok(service.includes('"YouTube on TV"'),
  'the runtime bridge must verify that the Cast web receiver is YouTube');
assert.ok(service.includes('KEYCODE_MEDIA_STOP'),
  'the unrestricted Cast media session must be stopped before handoff');
assert.ok(service.includes('GLOBAL_ACTION_BACK'),
  'the unrestricted Cast UI must be dismissed before handoff');
assert.ok(service.includes('"io.gh.yossim.tizentube.cobalt"'),
  'the runtime Cast bridge must target the protected TizenSub+ package');
assert.ok(service.includes('new Intent(Intent.ACTION_MAIN)'),
  'web-receiver fallback must land in TizenSub+ fail-closed instead of replaying an untrusted Cast URL');
assert.ok(service.includes('fail-closed home'),
  'the web-receiver fallback must remain explicitly fail-closed');

console.log('Onn Cast bridge static regression test passed.');
