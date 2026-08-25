'use strict';

const assert = require('assert');
const mod = require('../x.js');

function reset() {
  mod.state.liked.clear();
  mod.state.subs.clear();
  mod.state.v2c.clear();
  mod.state.errors.length = 0;
  mod.state.loggedIn = true;
  mod.state.ready = true;
  mod.state.booting = false;
  for (const stateName of ['playlist', 'music', 'purchased', 'accountVideos']) {
    const memberships = mod.state[stateName];
    if (memberships && typeof memberships.clear === 'function') memberships.clear();
  }
}

const playlistPage = {
  contents: [
    { playlistVideoRenderer: { videoId: 'playlist-custom-1' } },
    { playlistVideoRenderer: { videoId: 'playlist-system-1' } },
  ],
};

const musicLibraryPage = {
  contents: [
    { musicResponsiveListItemRenderer: { playlistItemData: { videoId: 'music-direct-1' } } },
    { musicResponsiveListItemRenderer: { playlistItemData: { videoId: 'music-direct-2' } } },
  ],
};

const artistLibraryPage = {
  musicShelfRenderer: {
    title: { runs: [{ text: 'Saved artist songs' }] },
    contents: [
      { musicResponsiveListItemRenderer: { playlistItemData: { videoId: 'music-artist-1' } } },
    ],
  },
};

const albumLibraryPage = {
  musicShelfRenderer: {
    title: { runs: [{ text: 'Saved album songs' }] },
    contents: [
      { musicResponsiveListItemRenderer: { playlistItemData: { videoId: 'music-album-1' } } },
    ],
  },
};

const purchasePage = {
  contents: [
    { videoRenderer: {
      videoId: 'purchased-1',
      badges: [{ metadataBadgeRenderer: { label: 'Purchased' } }],
    } },
    { videoRenderer: { videoId: 'not-purchased-1', badges: [] } },
  ],
};

const failedPlaylistSource = {
  status: 'failed',
  contents: [{ playlistVideoRenderer: { videoId: 'failed-source-video' } }],
};

const unknownPlaylistSource = {
  status: 'unknown',
  contents: [{ playlistVideoRenderer: { videoId: 'unknown-source-video' } }],
};

const tests = {
  'playlist-membership': () => {
    reset();
    const memberships = mod.collectPlaylistVideoIds(playlistPage);
    assert.deepStrictEqual([...memberships].sort(), ['playlist-custom-1', 'playlist-system-1']);
    assert.strictEqual(mod.allowed('playlist-custom-1'), true,
      'a video in an account-owned custom playlist must be allowed');
    assert.strictEqual(mod.allowed('unrelated-playlist-video'), false,
      'a video absent from account-owned playlists must remain blocked');
  },
  'music-library-membership': () => {
    reset();
    const memberships = mod.collectMusicLibraryVideoIds(musicLibraryPage);
    assert.deepStrictEqual([...memberships].sort(), ['music-direct-1', 'music-direct-2']);
    assert.strictEqual(mod.allowed('music-direct-1'), true,
      'a video directly represented in the owned Music library must be allowed');
    assert.strictEqual(mod.allowed('music-recommendation-1'), false,
      'a generic Music recommendation must remain blocked');
  },
  'music-artist-album-expansion': () => {
    reset();
    const memberships = mod.collectMusicCollectionVideoIds([artistLibraryPage, albumLibraryPage]);
    assert.deepStrictEqual([...memberships].sort(), ['music-album-1', 'music-artist-1']);
    assert.strictEqual(mod.allowed('music-artist-1'), true,
      'a video in an owned artist collection must be allowed');
    assert.strictEqual(mod.allowed('music-album-1'), true,
      'a video in an owned album collection must be allowed');
    assert.strictEqual(mod.allowed('music-public-artist-video'), false,
      'a video from a public artist page must remain blocked');
    const collectionCards = mod.collectMusicCollectionBrowseIds({
      musicShelfRenderer: {
        title: { runs: [{ text: 'Saved artists' }] },
        contents: [{ musicTwoRowItemRenderer: {
          navigationEndpoint: { browseEndpoint: { browseId: 'UCsaved-artist' } },
        } }],
      },
    });
    assert.deepStrictEqual([...collectionCards], ['UCsaved-artist'],
      'owned Music artist and album cards must seed authenticated collection expansion');
  },
  'purchased-ownership': () => {
    reset();
    const memberships = mod.collectPurchasedVideoIds(purchasePage);
    assert.deepStrictEqual([...memberships], ['purchased-1']);
    assert.strictEqual(mod.allowed('purchased-1'), true,
      'an explicitly purchased video must be allowed');
    assert.strictEqual(mod.allowed('not-purchased-1'), false,
      'generic player or browse visibility must not establish purchase ownership');
    const searchPurchase = mod.collectPurchasedVideoIds({
      searchQuery: 'Purchased',
      contents: [{ videoRenderer: {
        videoId: 'search-purchased',
        badges: [{ metadataBadgeRenderer: { label: 'Purchased' } }],
      } }],
    }, new Set());
    assert.deepStrictEqual([...searchPurchase], [],
      'a Purchased search filter must never establish ownership');
    const previewPurchase = mod.collectPurchasedVideoIds({
      contents: [{ videoRenderer: {
        videoId: 'preview-purchased',
        badges: [{ metadataBadgeRenderer: { label: 'Purchased' } }],
        menuRenderer: { label: 'Preview' },
      } }],
    }, new Set());
    assert.deepStrictEqual([...previewPurchase], [],
      'a preview menu must never establish ownership');
  },
  'continuation-traversal': () => {
    const response = {
      contents: [
        { continuationItemRenderer: {
          continuationEndpoint: { continuationCommand: { token: 'command-token' } },
        } },
        { continuationItemRenderer: {
          continuationEndpoint: { nextContinuationData: { continuation: 'next-data-token' } },
        } },
      ],
    };
    assert.deepStrictEqual(mod.continuationTokens(response), ['command-token', 'next-data-token'],
      'account source traversal must follow both continuation response shapes');
  },
  'failed-unknown-source-fail-closed': () => {
    reset();
    const failedMemberships = mod.collectPlaylistVideoIds(failedPlaylistSource);
    const unknownMemberships = mod.collectPlaylistVideoIds(unknownPlaylistSource);
    assert.deepStrictEqual([...failedMemberships], [],
      'failed account source evidence must contribute no playlist memberships');
    assert.deepStrictEqual([...unknownMemberships], [],
      'unknown account source evidence must contribute no playlist memberships');
    assert.strictEqual(mod.allowed('failed-source-video'), false,
      'failed source data must not authorize playback');
    assert.strictEqual(mod.allowed('unknown-source-video'), false,
      'unknown source data must not authorize playback');
  },
};

const selectedName = process.argv[2];
const selectedTests = selectedName ? { [selectedName]: tests[selectedName] } : tests;
if (selectedName && !selectedTests[selectedName]) {
  throw new Error('unknown library allowlist test: ' + selectedName);
}

const failures = [];
for (const [name, test] of Object.entries(selectedTests)) {
  try {
    test();
    console.log('PASS ' + name);
  } catch (error) {
    failures.push(name + ' -> ' + error.message);
    console.error('FAIL ' + name + ' -> ' + error.message);
  }
}
if (failures.length) {
  console.error('Library allowlist baseline failures:\n' + failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log('All TizenTube library allowlist tests passed.');
}
