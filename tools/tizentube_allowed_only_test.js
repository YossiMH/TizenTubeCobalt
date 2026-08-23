const assert = require('assert');
const mod = require('../x.js');

function reset() {
  mod.state.liked.clear();
  mod.state.subs.clear();
  mod.state.v2c.clear();
  mod.state.loggedIn = null;
  mod.state.ready = true;
  mod.state.stripped = 0;
}

reset();
mod.state.liked.add('liked1');
mod.state.subs.add('UCsub');

const feed = {contents:[
  {videoRenderer:{videoId:'liked1',ownerText:{runs:[{navigationEndpoint:{browseEndpoint:{browseId:'UCother'}}}]}}},
  {videoRenderer:{videoId:'sub1',ownerText:{runs:[{navigationEndpoint:{browseEndpoint:{browseId:'UCsub'}}}]}}},
  {videoRenderer:{videoId:'bad1',ownerText:{runs:[{navigationEndpoint:{browseEndpoint:{browseId:'UCbad'}}}]}}}
]};
const filtered = mod.filterTree(JSON.parse(JSON.stringify(feed)));
assert.strictEqual(filtered.contents.length,2);
assert.deepStrictEqual(filtered.contents.map(x=>x.videoRenderer.videoId).sort(),['liked1','sub1']);

const playerBad=mod.blockPlayerResponse({videoDetails:{videoId:'bad2',channelId:'UCbad'},streamingData:{formats:[1]},captions:{}});
assert.strictEqual(playerBad.playabilityStatus.status,'ERROR');
assert.strictEqual(playerBad.streamingData,undefined);
const playerSub=mod.blockPlayerResponse({videoDetails:{videoId:'ok2',channelId:'UCsub'},streamingData:{formats:[1]}});
assert.ok(playerSub.streamingData);

reset();
mod.collectLikedVideoIds({items:[
  {playlistVideoRenderer:{videoId:'a'}},
  {videoRenderer:{videoId:'b'}},
  {compactVideoRenderer:{videoId:'not-liked-sidebar'}}
]});
assert.deepStrictEqual([...mod.state.liked].sort(),['a','b']);

// Modern YouTube TV feeds use tileRenderer instead of the legacy video/grid
// renderers. The real signed-in TV response proved this shape reaches the app,
// while the original extractor returned null and left unauthorized tiles live.
reset();
mod.state.loggedIn = true;
mod.state.ready = true;
mod.state.liked.add('tileLiked');
mod.state.subs.add('UCtileSub');
const tileFeed = mod.filterTree(JSON.parse(JSON.stringify({ contents: [
  { tileRenderer: { style: 'TILE_STYLE_YTLR_ROUND', contentType: 'TILE_CONTENT_TYPE_VIDEO', contentId: 'tileLiked', onSelectCommand: { clickTrackingParams: 'video', watchEndpoint: { videoId: 'tileLiked' }, browseEndpoint: { browseId: 'UCownerLiked' } } } },
  { tileRenderer: { style: 'TILE_STYLE_YTLR_ROUND', contentType: 'TILE_CONTENT_TYPE_VIDEO', contentId: 'badTileVideo', onSelectCommand: { watchEndpoint: { videoId: 'badTileVideo' }, browseEndpoint: { browseId: 'UCother' } } } },
  { tileRenderer: { style: 'TILE_STYLE_YTLR_ROUND', contentType: 'TILE_CONTENT_TYPE_CHANNEL', contentId: 'UCtileSub', onSelectCommand: { browseEndpoint: { browseId: 'UCtileSub' } } } },
  { tileRenderer: { style: 'TILE_STYLE_YTLR_ROUND', contentType: 'TILE_CONTENT_TYPE_CHANNEL', contentId: 'UCnotSubscribed', onSelectCommand: { browseEndpoint: { browseId: 'UCnotSubscribed' } } } }
] })));
assert.strictEqual(tileFeed.contents.length, 2, 'modern feed must keep exactly the liked-video and subscribed-channel tiles');
assert.strictEqual(mod.firstVideoId(tileFeed.contents[0]), 'tileLiked', 'allowed modern video tile must expose its video ID');
assert.strictEqual(mod.state.stripped, 2, 'unauthorized modern video/channel tiles must be counted as stripped');

reset();
mod.collectLikedVideoIds({ items: [
  { tileRenderer: { contentType: 'TILE_CONTENT_TYPE_VIDEO', contentId: 'likedTileA', onSelectCommand: { watchEndpoint: { videoId: 'likedTileA' } } } },
  { tileRenderer: { contentType: 'TILE_CONTENT_TYPE_CHANNEL', contentId: 'UCignored', onSelectCommand: { browseEndpoint: { browseId: 'UCignored' } } } }
] });
assert.deepStrictEqual([...mod.state.liked], ['likedTileA'], 'likes collection must harvest modern video tiles without treating channel tiles as videos');
reset();
mod.collectAllChannelIds({contents:[
  {channelRenderer:{navigationEndpoint:{browseEndpoint:{browseId:'UCone'}}}},
  {gridChannelRenderer:{channelId:'UCtwo'}}
]});
assert.deepStrictEqual([...mod.state.subs].sort(),['UCone','UCtwo']);

reset();
mod.collectGuideSubscriptionIds({guideSubscriptionsSectionRenderer:{items:[
  {guideEntryRenderer:{navigationEndpoint:{browseEndpoint:{browseId:'UCsubA'}}}}
]},otherSection:{browseEndpoint:{browseId:'UCnotSub'}}});
assert.deepStrictEqual([...mod.state.subs],['UCsubA']);

assert.deepStrictEqual(mod.continuationTokens({contents:[
  {continuationItemRenderer:{continuationEndpoint:{continuationCommand:{token:'t1'}}}},
  {continuationItemRenderer:{continuationEndpoint:{continuationCommand:{token:'t2'}}}}
]}),['t1','t2']);


// Phone-remote-control surfaces: queue (cast/up-next) and mobile playback responses
// must go through the same allowlist filter as browse/player responses.
reset();
mod.state.liked.add('liked1');
mod.state.subs.add('UCsub');
const queueResp = {contents:[
  {playlistPanelVideoRenderer:{videoId:'liked1',navigationEndpoint:{watchEndpoint:{videoId:'liked1'}}}},
  {playlistPanelVideoRenderer:{videoId:'badQ',navigationEndpoint:{watchEndpoint:{videoId:'badQ'}}}}
]};
const filteredQueue = mod.filterTree(JSON.parse(JSON.stringify(queueResp)));
assert.strictEqual(filteredQueue.contents.length,1);
assert.strictEqual(filteredQueue.contents[0].playlistPanelVideoRenderer.videoId,'liked1');

const mobilePlaybackBad = mod.blockPlayerResponse({videoDetails:{videoId:'badM',channelId:'UCbad'},streamingData:{formats:[1]}});
assert.strictEqual(mobilePlaybackBad.playabilityStatus.status,'ERROR');
assert.strictEqual(mobilePlaybackBad.streamingData,undefined);
const mobilePlaybackLiked = mod.blockPlayerResponse({videoDetails:{videoId:'liked1',channelId:'UCother'},streamingData:{formats:[1]}});
assert.ok(mobilePlaybackLiked.streamingData);


// Phone remote control must keep working: pairing/registration endpoints pass
// through unfiltered, while cast queue and mobile playback responses are filtered.
const pkg = require('../x.js');
const pairing = new URL('https://www.youtube.com/youtubei/v1/pairing/create_pairing_code');
const queueCast = new URL('https://www.youtube.com/youtubei/v1/queue/add');
const mobilePlayback = new URL('https://www.youtube.com/youtubei/v1/get_mobile_playback');
assert.strictEqual(pkg.filterTree !== undefined, true);


// Phone remote control must keep working: pairing/registration endpoints pass
// through unfiltered, while cast queue and mobile playback responses are filtered.
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/pairing/create_pairing_code')), false, 'pairing code creation must pass through');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/pairing/get_pairing_code')), false, 'pairing code polling must pass through');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/queue/add')), true, 'cast queue must be filtered');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/get_mobile_playback')), true, 'mobile playback must be filtered');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/browse')), true, 'browse must be filtered');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/youtubei/v1/player')), true, 'player must be filtered');
assert.strictEqual(mod.filtapi(new URL('https://www.youtube.com/not_an_api')), false, 'non-API pages must pass through');

(async function () {
  // Guest/signed-out enforcement is fail-closed; terminal guest status itself
  // requires genuine account-API proof and is covered by the live HTTP suite.
  reset();
  mod.state.loggedIn = false;
  mod.state.ready = true;
  const guestFeed = mod.filterTree({ contents: [
    { videoRenderer: { videoId: 'any1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCany' } } }] } } }
  ] });
  assert.strictEqual(guestFeed.contents.length, 0, 'guest feed must show no videos');
  const guestPlayer = mod.blockPlayerResponse({ videoDetails: { videoId: 'any2', channelId: 'UCany' }, streamingData: { formats: [1] } });
  assert.strictEqual(guestPlayer.playabilityStatus.status, 'ERROR', 'guest player must be blocked');
  assert.strictEqual(guestPlayer.streamingData, undefined, 'guest player must have no streams');

  // Until account data is loaded, discovery cannot trust cached or hydrated
  // tiles. A not-ready filter is therefore indistinguishable from an empty
  // allowlist at every visible surface.
  reset();
  mod.state.loggedIn = true;
  mod.state.ready = false;
  mod.state.liked.add('cachedLiked');
  mod.state.subs.add('UCcachedSub');
  const loadingFeed = mod.filterTree({ contents: [
    { videoRenderer: { videoId: 'cachedLiked', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCcachedSub' } } }] } } },
    { gridVideoRenderer: { videoId: 'uncached' } }
  ] });
  assert.strictEqual(loadingFeed.contents.length, 0, 'not-ready discovery must strip every video');
  const loadingPlayer = mod.blockPlayerResponse({ videoDetails: { videoId: 'cachedLiked', channelId: 'UCcachedSub' }, streamingData: { formats: [1] } });
  assert.strictEqual(loadingPlayer.playabilityStatus.status, 'ERROR', 'not-ready playback must be blocked');

  // A second load of the script (e.g. when both the native redirect and the DOM
  // loader inject it) must reuse the same module instead of double-installing.
  const firstState = mod.state;
  const resolved = require.resolve('../x.js');
  delete require.cache[resolved];
  const mod2 = require('../x.js');
  assert.strictEqual(mod2.state, firstState, 'second load must reuse the same module instance');

  // Every stripped video must be counted so device logs can prove blocking.
  reset();
  mod.state.stripped = 0;
  mod.state.liked.add('liked1');
  const stripFeed = mod.filterTree({ contents: [
    { videoRenderer: { videoId: 'liked1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCa' } } }] } } },
    { videoRenderer: { videoId: 'badX', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCb' } } }] } } }
  ] });
  assert.strictEqual(stripFeed.contents.length, 1, 'allowed video stays');
  assert.strictEqual(mod.state.stripped, 1, 'one disallowed video must be counted as stripped');
  const stripPlayer = mod.blockPlayerResponse({ videoDetails: { videoId: 'badY', channelId: 'UCb' }, streamingData: { formats: [1] } });
  assert.strictEqual(stripPlayer.playabilityStatus.status, 'ERROR');
  assert.strictEqual(mod.state.stripped, 2, 'blocked player must be counted as stripped');

  // Real YouTube TV search responses nest video results deep inside
  // sectionListRenderer/itemSectionRenderer (the shape captured from the Onn
  // box). Signed-in search must keep ONLY liked videos and subscribed-channel
  // videos; signed-out/guest search must show nothing at all.
  reset();
  mod.state.loggedIn = true;
  mod.state.liked.add('likedSearch1');
  mod.state.subs.add('UCsearchSub');
  const searchResp = { contents: { twoColumnSearchResultsRenderer: { primaryContents: { sectionListRenderer: { contents: [
    { itemSectionRenderer: { contents: [
      { videoRenderer: { videoId: 'likedSearch1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCother' } } }] } } },
      { videoRenderer: { videoId: 'badSearch1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCbad' } } }] } } },
      { videoRenderer: { videoId: 'subSearch2', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCsearchSub' } } }] } } },
      { channelRenderer: { channelId: 'UCsearchSub' } }
    ] } }
  ] } } } } };
  const fSearch = mod.filterTree(JSON.parse(JSON.stringify(searchResp)));
  const ids = [];
  (function walk(x) { if (x && typeof x === 'object') { if (typeof x.videoId === 'string') ids.push(x.videoId); for (const k in x) walk(x[k]); } })(fSearch);
  assert.strictEqual(ids.sort().join(','), 'likedSearch1,subSearch2', 'signed-in search must keep only liked + subscribed results');

  reset();
  mod.state.loggedIn = false;
  const gSearch = mod.filterTree(JSON.parse(JSON.stringify(searchResp)));
  const gids = [];
  (function walk(x) { if (x && typeof x === 'object') { if (typeof x.videoId === 'string') gids.push(x.videoId); for (const k in x) walk(x[k]); } })(gSearch);
  assert.strictEqual(gids.length, 0, 'signed-out/guest search must show no videos');

  // Guard/self-heal plumbing: the filter must expose a guard that re-wraps
  // fetch/XHR if the page ever clobbers the interceptor, and it must never
  // throw when run repeatedly (YouTube TV can replace window.fetch and the
  // XHR prototype mid-session).
  assert.strictEqual(typeof mod.guard, 'function', 'guard must be exported');

  assert.strictEqual(mod.state.armFail, 0, 'guard must start with zero failures');
  mod.install();
  assert.strictEqual(mod.state.installed, true, 'install() must mark the filter installed');
  assert.ok(mod.state.fetch0, 'install() must capture the original fetch');
  // Running the guard repeatedly must be idempotent and never throw.
  mod.guard();
  mod.guard();
  assert.strictEqual(typeof mod.guard(), 'undefined', 'guard must run without throwing');
  // The guard must restore a clobbered fetch wrapper (page replaced window.fetch).
  if (typeof globalThis.fetch === 'function') {
    const saved = mod.state.fetch0;
    try {
      Object.defineProperty(globalThis, 'fetch', { configurable: true, writable: true, value: saved });
      mod.state.fetch0 = null;            // simulate wrapper loss
      delete globalThis.fetch.__tt;       // simulate interceptor tag loss
      mod.guard();
      assert.ok(mod.state.fetch0, 'guard must re-install the fetch wrapper after clobbering');
      assert.strictEqual(globalThis.fetch.__tt, 1, 'fresh wrapper must be tagged');
    } finally {
      mod.install();
    }
  }

  if (mod.state.statusWatcher) {
    clearInterval(mod.state.statusWatcher);
    mod.state.statusWatcher = null;
  }
  console.log('All TizenTube allowed-only unit tests passed.');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
