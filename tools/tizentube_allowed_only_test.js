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
// YouTube TV can report LOGGED_IN=false while sending a valid OAuth
// Authorization header. Captured credential evidence must win over that
// misleading config flag, and a later cfg() poll must not clear the allowlist.
{
  const savedYtcfg = globalThis.ytcfg;
  try {
    globalThis.ytcfg = { get(key) { return key === 'LOGGED_IN' ? false : undefined; } };
    mod.state.lastLoggedIn = true;
    mod.state.ctx = null;
    mod.state.key = null;
    mod.state.hdr = { authorization: 'Bearer real-account-token' };
    mod.configure();
    assert.strictEqual(mod.state.loggedIn, true, 'an Authorization header proves the TV session is signed in');
    assert.strictEqual(mod.statusText(), 'Restricted YouTube - Ready: 1 liked, 1 subscribed', 'a misleading LOGGED_IN=false poll cannot invalidate an authenticated allowlist');

    mod.state.ready = true;
    mod.state.booting = false;
    mod.state.errors.length = 0;
    mod.configure();
    assert.strictEqual(mod.state.loggedIn, true, 'a repeated misleading LOGGED_IN=false poll cannot demote authenticated state');
    assert.strictEqual(mod.state.liked.size, 1, 'repeated authentication polling must preserve loaded liked videos');
    assert.strictEqual(mod.state.subs.size, 1, 'repeated authentication polling must preserve loaded subscriptions');
  } finally {
    globalThis.ytcfg = savedYtcfg;
  }
}

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


// --- DOM sweep: unauthorized tiles in initial server-rendered HTML ---
// The network gate delays JS-initiated requests but cannot block the
// navigation response itself. After boot arms, sweepDom() must remove
// unauthorized ytlr-tile-renderer elements from the live document.
reset();
mod.state.loggedIn = true;
mod.state.ready = false;
mod.state.allowedNames.clear();
assert.strictEqual(typeof mod.collectChannelNames, 'function', 'collectChannelNames must be exported');
mod.collectChannelNames({ contents: [
  { title: { runs: [{ text: 'Sesame Street' }] }, channelId: 'UCsesame' },
  { title: { runs: [{ text: 'Adam Savage' }] }, browseEndpoint: { browseId: 'UCadam' } },
  { title: { simpleText: 'Ed Sullivan' }, externalChannelId: 'UCed' },
  { navigationEndpoint: { browseEndpoint: { browseId: 'UCsub' } }, title: { runs: [{ text: 'My Sub Channel' }] } },
  { videoRenderer: { videoId: 'someVideo', ownerText: { runs: [{ text: 'Owner Name' }] }, channelId: 'UCowner' } }
] });
assert.ok(mod.state.allowedNames.has('sesame street'), 'must collect Sesame Street from title runs');
assert.ok(mod.state.allowedNames.has('adam savage'), 'must collect Adam Savage from browse endpoint');
assert.ok(mod.state.allowedNames.has('ed sullivan'), 'must collect Ed Sullivan from simpleText');
assert.ok(mod.state.allowedNames.has('my sub channel'), 'must collect subscribed channel name');
assert.ok(mod.state.allowedNames.has('owner name'), 'must collect owner name from video renderer');

assert.strictEqual(typeof mod.tileShouldStay, 'function', 'tileShouldStay must be exported');
assert.strictEqual(mod.tileShouldStay('Sesame Street - Elmo World', mod.state.allowedNames), true,
  'a tile whose text contains an allowed channel name must stay');
assert.strictEqual(mod.tileShouldStay('Random Video Not From Any Sub', mod.state.allowedNames), false,
  'a tile with no matching allowed channel name must be removed');
assert.strictEqual(mod.tileShouldStay('', mod.state.allowedNames), false,
  'an empty-text tile with no match must be removed');

assert.strictEqual(typeof mod.sweepDom, 'function', 'sweepDom must be exported');

{
  const savedDocument = globalThis.document;
  const tiles = [
    { getAttribute: () => null, innerText: 'Sesame Street - Elmo World' },
    { getAttribute: () => null, innerText: 'Random Video Not From Any Sub', removed: false,
      parentNode: { removeChild(node) { node.removed = true; } } }
  ];
  const documentStub = {
    querySelectorAll(selector) {
      if (selector !== 'ytlr-tile-renderer') throw new Error(`unexpected selector ${selector}`);
      return tiles;
    }
  };
  try {
    globalThis.document = documentStub;
    const savedNames = [...mod.state.allowedNames];
    mod.state.allowedNames.clear();
    mod.state.stripped = 0;

    // Network filtering has already accepted these live tiles. Before account
    // readiness is proven, an empty extraction set is not evidence that every
    // tile is unauthorized.
    assert.strictEqual(mod.sweepDom(), 0,
      'an empty extracted-name set must preserve network-gated tiles');
    assert.strictEqual(tiles.length, 2, 'bootstrap sweep cannot blank the TV UI');
    assert.strictEqual(mod.state.stripped, 0, 'preserved tiles are not filter removals');

    for (const name of savedNames) mod.state.allowedNames.add(name);
    mod.state.ready = true;
    assert.strictEqual(mod.sweepDom(), 1,
      'a known allowlist name must authorize its matching tile');
    assert.strictEqual(tiles[0].innerText, 'Sesame Street - Elmo World',
      'the authorized tile must remain attached');
    assert.strictEqual(tiles[1].removed, true,
      'the unauthorized tile must be removed when names are known');
    assert.strictEqual(mod.state.stripped, 1,
      'DOM sweep removals must count as stripped content');
  } finally {
    globalThis.document = savedDocument;
    mod.state.allowedNames.clear();
  }
}

  async function assertReadyZeroSweepRemovesAllTiles(loggedIn) {
    const savedDocument = globalThis.document;
    const tiles = [
      { getAttribute: () => null, innerText: 'Unauthorized Video One', removed: false,
        parentNode: { removeChild(node) { node.removed = true; } } },
      { getAttribute: () => null, innerText: '', removed: false,
        parentNode: { removeChild(node) { node.removed = true; } } },
      { getAttribute: () => 'button', innerText: 'Keep Controls', removed: false,
        parentNode: { removeChild(node) { node.removed = true; } } }
    ];
    globalThis.document = {
      querySelectorAll(selector) {
        if (selector !== 'ytlr-tile-renderer') throw new Error('unexpected selector ' + selector);
        return tiles;
      }
    };
    try {
      reset();
      mod.state.loggedIn = loggedIn;
      mod.state.ready = true;
      mod.state.errors.length = 0;
      mod.state.booting = false;
      mod.state.allowedNames.clear();
      mod.state.stripped = 0;

      const removed = mod.sweepDom();
      assert.strictEqual(removed, 2, 'ready authority permits an empty allowlist to remove content tiles');
      assert.strictEqual(tiles[0].removed, true, 'signed-in zero authority removes a named tile');
      assert.strictEqual(tiles[1].removed, true, 'ready authority removes an unnamed tile');
      assert.strictEqual(tiles[2].removed, false, 'ready authority preserves button controls');
      assert.strictEqual(mod.state.stripped, 2, 'each DOM removal must count exactly once');
    } finally {
      globalThis.document = savedDocument;
    }
  }

  await assertReadyZeroSweepRemovesAllTiles(true);
  await assertReadyZeroSweepRemovesAllTiles(false);

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

  // --- Media boundary: unauthorized direct watch routes cannot reach playback ---
  // Real prototype-backed host classes model the exact browser boundary
  // (HTMLMediaElement src/load/play plus URL.createObjectURL) that Cobalt
  // exposes, so the guard is proven at the same surface the app attacks.
  await (async function () {
    const failures = [];
    const check = (label, fn) => { try { fn(); } catch (e) { failures.push(label + ' -> ' + e.message); } };
    const checkAsync = async (label, fn) => { try { await fn(); } catch (e) { failures.push(label + ' -> ' + e.message); } };
    const savedLocation = globalThis.location;
    const savedMediaElement = globalThis.HTMLMediaElement;
    const savedCreateObjectURL = URL.createObjectURL;
    const savedConsoleLog = console.log;
    class MediaSourceFixture {}
    class HostMediaElement {
      constructor() { this._src = ''; }
      get src() { return this._src; }
      set src(value) { this._src = String(value); }
      load() {}
      play() { return Promise.resolve('played'); }
    }
    const setWatchRoute = (href) => { globalThis.location = new URL(href); };
    const resetSignedInReady = () => {
      reset();
      mod.state.loggedIn = true;
      mod.state.ready = true;
      mod.state.booting = false;
      mod.state.errors.length = 0;
    };
    try {
      let blobCounter = 0;
      const baseCreateObjectURL = function createObjectURL(input) {
        if (!(input instanceof Blob)) throw new TypeError('createObjectURL requires a Blob');
        blobCounter += 1;
        return 'blob:fixture-' + blobCounter;
      };
      URL.createObjectURL = baseCreateObjectURL;
      const logs = [];
      console.log = (...parts) => { logs.push(parts.map(String).join(' ')); };

      if (typeof mod.routeVideoId !== 'function') {
        failures.push('routeVideoId must be exported -> missing export');
      } else {
        const routes = [
          ['https://www.youtube.com/watch?v=FZpaYeCQO00&t=9', 'FZpaYeCQO00', 'search form'],
          ['https://www.youtube.com/watch/FZpaYeCQO00', 'FZpaYeCQO00', 'pathname form'],
          ['https://www.youtube.com/tv#/watch?v=FZpaYeCQO00', 'FZpaYeCQO00', 'hash query form'],
          ['https://www.youtube.com/tv#/watch/FZpaYeCQO00', 'FZpaYeCQO00', 'hash path form']
        ];
        for (const [href, expected, label] of routes) {
          setWatchRoute(href);
          check('routeVideoId resolves ' + label, () => assert.strictEqual(mod.routeVideoId(), expected));
        }
        setWatchRoute('https://www.youtube.com/');
        check('routeVideoId returns null off watch routes', () => assert.strictEqual(mod.routeVideoId(), null));
      }

      if (typeof mod.mediaDecision !== 'function') {
        failures.push('mediaDecision must be exported -> missing export');
      } else {
        check('mediaDecision reports pending while account lists are loading', () => {
          resetSignedInReady();
          mod.state.ready = false;
          mod.state.booting = true;
          assert.strictEqual(mod.mediaDecision('any'), 'pending');
        });
        check('mediaDecision reports closed for unauthorized signed-in-ready routes', () => {
          resetSignedInReady();
          assert.strictEqual(mod.mediaDecision('FZpaYeCQO00'), 'closed');
        });
        check('mediaDecision reports open only for current allowlist hits', () => {
          resetSignedInReady();
          mod.state.liked.add('gateOpenLiked');
          mod.state.v2c.set('gateOpenSub', 'UCgateSub');
          mod.state.subs.add('UCgateSub');
          assert.strictEqual(mod.mediaDecision('gateOpenLiked'), 'open');
          assert.strictEqual(mod.mediaDecision('gateOpenSub'), 'open');
        });
      }

      check('state.mediaGate is the persisted enum, never a boolean pair', () => {
        assert.strictEqual(typeof mod.state.mediaGate, 'string',
          'mediaGate must exist as a string, got ' + typeof mod.state.mediaGate);
        assert.ok(['pending', 'open', 'closed'].indexOf(mod.state.mediaGate) >= 0,
          'mediaGate must be one of pending|open|closed, got ' + JSON.stringify(mod.state.mediaGate));
        assert.strictEqual(mod.state.mediaBlocked, undefined, 'boolean mediaBlocked must not exist');
        assert.strictEqual(mod.state.mediaAllowed, undefined, 'boolean mediaAllowed must not exist');
      });

      globalThis.HTMLMediaElement = HostMediaElement;
      if (typeof mod.installMediaGuard !== 'function') {
        failures.push('installMediaGuard must be exported and installable -> missing export');
      }
      {
        if (typeof mod.installMediaGuard === 'function') mod.installMediaGuard();

        resetSignedInReady();
        setWatchRoute('https://www.youtube.com/watch?v=FZpaYeCQO00');
        const blockedEl = new HostMediaElement();
        blockedEl.src = 'about:blank';
        check('closed route refuses MediaSource object URLs with REASON', () => {
          let thrown = null;
          try { URL.createObjectURL(new MediaSourceFixture()); } catch (e) { thrown = e; }
          assert.ok(thrown, 'createObjectURL(MediaSource) must throw');
          assert.strictEqual(thrown.message, mod.BLOCK_REASON,
            'expected BLOCK_REASON, got ' + JSON.stringify(thrown && thrown.message));
        });
        check('closed route refuses blob src assignment and preserves prior src', () => {
          blockedEl.src = 'blob:unauthorized-attempt';
          assert.strictEqual(blockedEl.src, 'about:blank',
            'prior src must survive a refused blob assignment, got ' + JSON.stringify(blockedEl.src));
        });
        check('closed route refuses load() with REASON', () => {
          let thrown = null;
          try { blockedEl.load(); } catch (e) { thrown = e; }
          assert.ok(thrown, 'load() must throw on closed routes');
          assert.strictEqual(thrown.message, mod.BLOCK_REASON,
            'expected BLOCK_REASON, got ' + JSON.stringify(thrown && thrown.message));
        });
        await checkAsync('closed route rejects play() with REASON', async () => {
          let error = null;
          try { await blockedEl.play(); } catch (e) { error = e; }
          assert.ok(error, 'play() must reject on closed routes');
          assert.strictEqual(error.message, mod.BLOCK_REASON,
            'expected BLOCK_REASON, got ' + JSON.stringify(error && error.message));
        });
        check('one concise blocked event names the refused route video id', () => {
          assert.ok(logs.indexOf('[allowed-only] media blocked FZpaYeCQO00') >= 0,
            'expected block event in ' + JSON.stringify(logs));
        });

        resetSignedInReady();
        mod.state.liked.add('likedAuthPlay');
        setWatchRoute('https://www.youtube.com/watch?v=likedAuthPlay');
        const likedEl = new HostMediaElement();
        await checkAsync('authorized liked video passes the full media lifecycle through', async () => {
          const blobUrl = URL.createObjectURL(new Blob(['ok']));
          assert.strictEqual(blobUrl.indexOf('blob:'), 0, 'liked blob creation must pass through');
          likedEl.src = blobUrl;
          assert.strictEqual(likedEl.src, blobUrl);
          likedEl.load();
          assert.strictEqual(await likedEl.play(), 'played');
        });

        resetSignedInReady();
        mod.state.subs.add('UCauthPlaySub');
        mod.state.v2c.set('subAuthPlay', 'UCauthPlaySub');
        setWatchRoute('https://www.youtube.com/watch?v=subAuthPlay');
        const subEl = new HostMediaElement();
        await checkAsync('authorized subscribed video passes the full media lifecycle through', async () => {
          const blobUrl = URL.createObjectURL(new Blob(['ok']));
          subEl.src = blobUrl;
          assert.strictEqual(subEl.src, blobUrl);
          subEl.load();
          assert.strictEqual(await subEl.play(), 'played');
        });

        check('explicit mediaGate covers every transition without reload', () => {
          resetSignedInReady();
          mod.state.ready = false;
          mod.state.booting = true;
          assert.strictEqual(mod.mediaDecision('transition'), 'pending');
          assert.strictEqual(mod.state.mediaGate, 'pending');
          mod.state.booting = false;
          assert.strictEqual(mod.mediaDecision('transition'), 'closed');
          assert.strictEqual(mod.state.mediaGate, 'closed');
          mod.state.loggedIn = true;
          mod.state.ready = true;
          mod.state.liked.add('transitionOpen');
          assert.strictEqual(mod.mediaDecision('transitionOpen'), 'open');
          assert.strictEqual(mod.state.mediaGate, 'open');
        });

        check('installMediaGuard is idempotent with tagged single wrappers', () => {
          const playDesc = Object.getOwnPropertyDescriptor(HostMediaElement.prototype, 'play');
          assert.strictEqual(playDesc.value.__ttMedia, 1, 'wrapped play must carry the media tag');
          const srcDesc = Object.getOwnPropertyDescriptor(HostMediaElement.prototype, 'src');
          assert.strictEqual(srcDesc.set.__ttMedia, 1, 'wrapped src setter must carry the media tag');
          assert.strictEqual(URL.createObjectURL.__ttMedia, 1, 'wrapped createObjectURL must carry the media tag');
          mod.installMediaGuard();
          mod.installMediaGuard();
          assert.strictEqual(Object.getOwnPropertyDescriptor(HostMediaElement.prototype, 'play').value, playDesc.value,
            'repeat installs must not stack play wrappers');
          assert.strictEqual(URL.createObjectURL.__ttMedia, 1, 'repeat installs must keep one createObjectURL wrapper');
        });

        check('guard self-heal rewraps clobbered media surfaces exactly once', () => {
          Object.defineProperty(HostMediaElement.prototype, 'play', {
            configurable: true, writable: true,
            value: function () { return Promise.resolve('played'); }
          });
          Object.defineProperty(HostMediaElement.prototype, 'load', {
            configurable: true, writable: true, value: function () {}
          });
          Object.defineProperty(HostMediaElement.prototype, 'src', {
            configurable: true,
            get() { return this._src; },
            set(value) { this._src = String(value); }
          });
          URL.createObjectURL = baseCreateObjectURL;
          mod.guard();
          const healedPlay = Object.getOwnPropertyDescriptor(HostMediaElement.prototype, 'play').value;
          assert.strictEqual(healedPlay.__ttMedia, 1, 'self-heal must rewrap play');
          assert.strictEqual(URL.createObjectURL.__ttMedia, 1, 'self-heal must rewrap createObjectURL');
          mod.installMediaGuard();
          assert.strictEqual(Object.getOwnPropertyDescriptor(HostMediaElement.prototype, 'play').value, healedPlay,
            'self-healed wrappers must stay single-layered');
        });

        // Pending authorization must fail closed on every synchronous media
        // setup surface, with pristine counting originals proving the
        // underlying host APIs are never reached.
        let creatorCalls = 0;
        const countingCreateObjectURL = function createObjectURL(input) {
          creatorCalls += 1;
          if (!(input instanceof Blob)) throw new TypeError('createObjectURL requires a Blob');
          return 'blob:fixture-' + creatorCalls;
        };
        URL.createObjectURL = countingCreateObjectURL;
        let nativeLoadCalls = 0;
        Object.defineProperty(HostMediaElement.prototype, 'load', {
          configurable: true, writable: true,
          value: function () { nativeLoadCalls += 1; }
        });
        mod.installMediaGuard();
        resetSignedInReady();
        mod.state.ready = false;
        mod.state.booting = true;
        setWatchRoute('https://www.youtube.com/watch?v=pendingLiked');
        check('pending decision is explicit while account lists load', () => {
          assert.strictEqual(mod.mediaDecision('pendingLiked'), 'pending');
        });
        check('pending route refuses MediaSource object URLs without invoking the creator', () => {
          let thrown = null;
          try { URL.createObjectURL(new MediaSourceFixture()); } catch (e) { thrown = e; }
          assert.ok(thrown, 'pending createObjectURL(MediaSource) must throw');
          assert.strictEqual(thrown.message, mod.BLOCK_REASON,
            'expected BLOCK_REASON while pending, got ' + JSON.stringify(thrown && thrown.message));
          assert.strictEqual(creatorCalls, 0,
            'the underlying creator must never run while pending, ran ' + creatorCalls + ' time(s)');
        });
        check('pending route refuses blob src assignment and preserves prior source', () => {
          const pendingSrcEl = new HostMediaElement();
          pendingSrcEl.src = 'about:blank';
          pendingSrcEl.src = 'blob:pending-attempt';
          assert.strictEqual(pendingSrcEl.src, 'about:blank',
            'prior src must survive a pending blob refusal, got ' + JSON.stringify(pendingSrcEl.src));
        });
        check('pending route refuses load() without invoking the original method', () => {
          const pendingLoadEl = new HostMediaElement();
          let thrown = null;
          try { pendingLoadEl.load(); } catch (e) { thrown = e; }
          assert.ok(thrown, 'pending load() must throw');
          assert.strictEqual(thrown.message, mod.BLOCK_REASON,
            'expected BLOCK_REASON while pending, got ' + JSON.stringify(thrown && thrown.message));
          assert.strictEqual(nativeLoadCalls, 0,
            'the original load must never run while pending, ran ' + nativeLoadCalls + ' time(s)');
        });
      }
    } finally {
      globalThis.location = savedLocation;
      if (savedMediaElement === undefined) delete globalThis.HTMLMediaElement;
      else globalThis.HTMLMediaElement = savedMediaElement;
      URL.createObjectURL = savedCreateObjectURL;
      console.log = savedConsoleLog;
      reset();
    }
    if (failures.length) throw new Error('media boundary failures:\n' + failures.join('\n'));
  })();

  if (mod.state.statusWatcher) {
    clearInterval(mod.state.statusWatcher);
    mod.state.statusWatcher = null;
  }
  console.log('All TizenTube allowed-only unit tests passed.');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
