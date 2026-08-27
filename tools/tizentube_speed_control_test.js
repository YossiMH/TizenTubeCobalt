'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

void (async () => {
  assert.strictEqual(typeof mod.normalizePlaybackSpeed, 'function');
  assert.strictEqual(typeof mod.setPlaybackSpeed, 'function');
  assert.strictEqual(typeof mod.getPlaybackSpeed, 'function');
  assert.strictEqual(typeof mod.applyPlaybackSpeed, 'function');

  S.videoSpeed = '1';
  assert.strictEqual(mod.normalizePlaybackSpeed(1.25), '1.25');
  assert.strictEqual(mod.normalizePlaybackSpeed('2'), '2');
  assert.strictEqual(mod.normalizePlaybackSpeed('1.0001'), '1.0001',
    'upstream stutter-fix playback rate must remain available');
  assert.throws(() => mod.normalizePlaybackSpeed('0'), /invalid playback speed/);
  assert.throws(() => mod.normalizePlaybackSpeed('5.25'), /invalid playback speed/);
  assert.throws(() => mod.normalizePlaybackSpeed('1.13'), /invalid playback speed/,
    'ordinary rates must stay on the quarter-speed grid');

  assert.strictEqual(mod.setPlaybackSpeed('1.75'), '1.75');
  assert.strictEqual(mod.getPlaybackSpeed(), 1.75);

  const video = { playbackRate: 1 };
  assert.strictEqual(mod.applyPlaybackSpeed(video), 1.75);
  assert.strictEqual(video.playbackRate, 1.75);

  mod.setPlaybackSpeed('1.0001');
  assert.strictEqual(mod.applyPlaybackSpeed(video), 1.0001);

  S.liked.clear();
  S.liked.add('allowed1');
  S.subs.clear();
  S.loggedIn = true;
  S.ready = true;
  const filtered = mod.filterTree({contents:[
    {videoRenderer:{videoId:'allowed1'}},
    {videoRenderer:{videoId:'blocked1'}}
  ]});
  assert.deepStrictEqual(filtered.contents.map(x => x.videoRenderer.videoId), ['allowed1'],
    'speed controls must not weaken allow-only filtering');

  console.log('All TizenTube speed-control tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
