const assert = require('assert');
const mod = require('../x.js');

function reset() {
  mod.state.liked.clear();
  mod.state.subs.clear();
  mod.state.v2c.clear();
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

console.log('All TizenTube allowed-only unit tests passed.');
