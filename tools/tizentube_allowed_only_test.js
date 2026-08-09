const assert = require('assert');
const mod = require('../x.js');

function reset() {
  mod.state.liked.clear();
  mod.state.subs.clear();
  mod.state.v2c.clear();
  mod.state.loggedIn = null;
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
  // Guest/signed-out mode must fail closed: no account, no allowlist, nothing plays.
  reset();
  mod.state.loggedIn = false;
  const guestBoot = await mod.bootWithRetry({ maxAttempts: 2, baseDelayMs: 1 });
  assert.strictEqual(guestBoot.ok, true, 'guest boot must succeed instantly (fail closed)');
  assert.strictEqual(guestBoot.guest, true, 'guest boot must report guest mode');
  assert.strictEqual(mod.state.ready, true, 'guest filter must be ready');
  assert.strictEqual(mod.state.liked.size, 0, 'guest must not collect a liked allowlist');
  assert.strictEqual(mod.state.subs.size, 0, 'guest must not collect a subscription allowlist');
  const guestFeed = mod.filterTree({ contents: [
    { videoRenderer: { videoId: 'any1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCany' } } }] } } }
  ] });
  assert.strictEqual(guestFeed.contents.length, 0, 'guest feed must show no videos');
  const guestPlayer = mod.blockPlayerResponse({ videoDetails: { videoId: 'any2', channelId: 'UCany' }, streamingData: { formats: [1] } });
  assert.strictEqual(guestPlayer.playabilityStatus.status, 'ERROR', 'guest player must be blocked');
  assert.strictEqual(guestPlayer.streamingData, undefined, 'guest player must have no streams');

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

  console.log('All TizenTube allowed-only unit tests passed.');
})().catch((e) => { console.error(e); process.exit(1); });