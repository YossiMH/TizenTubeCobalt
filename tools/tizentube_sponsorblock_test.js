'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

function reset() {
  S.features = mod.defaultFeatures();
  S.sponsorCategories = mod.defaultSponsorCategories();
  S.sponsorSegments = new Map();
}

void (async () => {
  assert.strictEqual(typeof mod.defaultSponsorCategories, 'function');
  assert.strictEqual(typeof mod.parseSponsorSegments, 'function');
  assert.strictEqual(typeof mod.maybeSkipSponsor, 'function');
  assert.strictEqual(typeof mod.setSponsorCategory, 'function');
  assert.strictEqual(typeof mod.sponsorBlockUrl, 'function');

  reset();
  const defaults = mod.defaultSponsorCategories();
  ['sponsor','intro','outro','interaction','selfpromo','preview','filler','music_offtopic'].forEach(category => {
    assert.strictEqual(defaults[category], 'on', `${category} must default to automatic skip`);
  });

  const parsed = mod.parseSponsorSegments([
    { segment: [10, 20], category: 'sponsor', actionType: 'skip', UUID: 'a' },
    { segment: [25, 30], category: 'intro', actionType: 'skip', UUID: 'b' },
    { segment: [40, 39], category: 'sponsor', actionType: 'skip', UUID: 'bad-range' },
    { segment: [45, 50], category: 'poi_highlight', actionType: 'skip', UUID: 'highlight' },
    { segment: ['x', 55], category: 'sponsor', actionType: 'skip', UUID: 'bad-type' },
  ], defaults);
  assert.deepStrictEqual(parsed.map(s => [s.start, s.end, s.category]), [
    [10, 20, 'sponsor'],
    [25, 30, 'intro'],
  ], 'only valid enabled automatic-skip segments may survive parsing');

  S.sponsorCache = new Map([['cached-video', [{ start: 1, end: 2 }]]]);
  S.sponsorSegments = new Map([['cached-video', [{ start: 1, end: 2 }]]]);
  assert.strictEqual(mod.setSponsorCategory('intro', 'off'), 'off');
  assert.strictEqual(S.sponsorCache.size, 0,
    'changing SponsorBlock categories must invalidate cached API results');
  assert.strictEqual(S.sponsorSegments.size, 0,
    'changing SponsorBlock categories must invalidate active parsed segments');
  const parsedWithoutIntro = mod.parseSponsorSegments([
    { segment: [10, 20], category: 'sponsor', actionType: 'skip' },
    { segment: [25, 30], category: 'intro', actionType: 'skip' },
  ], S.sponsorCategories);
  assert.deepStrictEqual(parsedWithoutIntro.map(s => s.category), ['sponsor'],
    'category configuration must isolate SponsorBlock categories');
  assert.throws(() => mod.setSponsorCategory('intro', 'maybe'), /invalid sponsor category value/);
  assert.throws(() => mod.setSponsorCategory('unknown', 'on'), /unknown sponsor category/);

  mod.setSponsorCategory('intro', 'on');
  S.sponsorSegments.set('vid1', [
    { start: 5, end: 8, category: 'sponsor' },
    { start: 20, end: 24, category: 'intro' },
  ]);
  const media = { currentTime: 6, duration: 100 };
  assert.strictEqual(mod.maybeSkipSponsor('vid1', media), 8,
    'SponsorBlock must jump to the end of the active configured segment');
  assert.strictEqual(media.currentTime, 8);

  media.currentTime = 6;
  mod.setFeature('sponsorblock', 'off');
  assert.strictEqual(mod.maybeSkipSponsor('vid1', media), null,
    'disabling SponsorBlock must stop automatic skips');
  assert.strictEqual(media.currentTime, 6);
  mod.setFeature('sponsorblock', 'on');

  const endpoint = mod.sponsorBlockUrl('AbC-123_x');
  assert.ok(endpoint.startsWith('https://sponsor.ajay.app/api/skipSegments?'));
  assert.ok(endpoint.includes('videoID=AbC-123_x'));
  assert.ok(endpoint.includes('categories='), 'request must carry configured categories');

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
    'SponsorBlock support must not weaken allow-only filtering');

  console.log('All TizenTube SponsorBlock tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
