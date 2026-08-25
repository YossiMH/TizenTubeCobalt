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

const capturedPlaylistAggregationPage = {
  contents: {
    tvBrowseRenderer: {
      content: {
        tvSurfaceContentRenderer: {
          content: {
            sectionListRenderer: {
              contents: [{
                shelfRenderer: {
                  content: {
                    horizontalListRenderer: {
                      items: [
                        {
                          tileRenderer: {
                            contentType: 'TILE_CONTENT_TYPE_PLAYLIST',
                            contentId: 'PLcaptured-owned',
                            onSelectCommand: {
                              browseEndpoint: { browseId: 'VLPLcaptured-owned' },
                            },
                          },
                        },
                        {
                          tileRenderer: {
                            contentType: 'TILE_CONTENT_TYPE_PLAYLIST',
                            contentId: 'FLcaptured-owned',
                            onSelectCommand: {
                              browseEndpoint: { browseId: 'VLFLcaptured-owned' },
                            },
                          },
                        },
                        {
                          tileRenderer: {
                            contentType: 'TILE_CONTENT_TYPE_PLAYLIST',
                            contentId: 'RDcaptured-owned',
                          },
                        },
                      ],
                    },
                  },
                },
              }],
            },
          },
        },
      },
    },
  },
};

const capturedPlaylistVideoListPage = {
  contents: {
    tvBrowseRenderer: {
      content: {
        tvSurfaceContentRenderer: {
          content: {
            twoColumnRenderer: {
              rightColumn: {
                playlistVideoListRenderer: {
                  playlistId: 'PLcaptured-owned',
                  contents: [{ tileRenderer: {
                    contentType: 'TILE_CONTENT_TYPE_VIDEO',
                    contentId: 'playlist-captured-tile-video',
                    onSelectCommand: { watchEndpoint: {
                      videoId: 'playlist-captured-tile-video',
                      playlistId: 'PLcaptured-owned',
                    } },
                  } }],
                  continuations: [{
                    nextContinuationData: { continuation: 'playlist-captured-next' },
                  }],
                },
              },
            },
          },
        },
      },
    },
  },
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

const publicYourLovePage = {
  musicShelfRenderer: {
    title: { runs: [{ text: 'Your Love' }] },
    contents: [{ musicResponsiveListItemRenderer: {
      playlistItemData: { videoId: 'music-public-your-love' },
    } }],
  },
};

const accountLibrarySource = {
  ownership: 'account',
  provenance: 'account-library',
  endpoint: 'FElibrary',
};

const accountCollectionSource = {
  ownership: 'account',
  provenance: 'account-collection',
  endpoint: 'FElibrary',
  collection: 'artist-album',
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

const accountLibraryResponses = {
  'FElibrary|FEplaylist_aggregation': {
    contents: [{ tileRenderer: {
      contentType: 'TILE_CONTENT_TYPE_PLAYLIST',
      contentId: 'PLowned-integration',
      onSelectCommand: { browseEndpoint: { browseId: 'VLPLowned-integration' } },
    } }],
  },
  'VLPLowned-integration|': {
    status: 'ok',
    contents: [{ playlistVideoRenderer: { videoId: 'playlist-integration-1' } }],
  },
  'FEmusic_library|FEmusic_last_played': {
    status: 'ok',
    musicShelfRenderer: { contents: [{ musicTwoRowItemRenderer: {
      navigationEndpoint: { browseEndpoint: { browseId: 'VLPLowned-music-collection' } },
    } }] },
  },
  'VLPLowned-music-collection|': {
    status: 'ok',
    contents: [{ musicResponsiveListItemRenderer: {
      playlistItemData: { videoId: 'music-integration-1' },
    } }],
  },
  'FEstorefront|ogUCKAQ%3D': {
    status: 'ok',
    contents: [{ videoRenderer: { videoId: 'storefront-unpurchased' } }],
  },
};

const tests = {
  'account-library-source-requests': async () => {
    reset();
    const http = require('http');
    const requests = [];
    const server = http.createServer((request, response) => {
      let body = '';
      request.setEncoding('utf8');
      request.on('data', chunk => { body += chunk; });
      request.on('end', () => {
        const payload = JSON.parse(body);
        const key = payload.browseId + '|' + encodeURIComponent(payload.params || '');
        requests.push(key);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(accountLibraryResponses[key]));
      });
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (input, init) => originalFetch(input, init);
    try {
      const port = server.address().port;
      await mod.accountLibraries({ apiBase: 'http://127.0.0.1:' + port });
      assert.deepStrictEqual(requests.sort(), [
        'FElibrary|FEplaylist_aggregation',
        'FEmusic_library|FEmusic_last_played',
        'FEstorefront|ogUCKAQ%3D',
        'VLPLowned-integration|',
        'VLPLowned-music-collection|',
      ], 'account library traversal must use proven source descriptors');
      assert.strictEqual(mod.allowed('playlist-integration-1'), true,
        'an owned aggregation playlist must authorize its videos');
      assert.strictEqual(mod.allowed('music-integration-1'), true,
        'an owned Music collection must authorize its videos through the forwarded descriptor');
      assert.strictEqual(mod.allowed('storefront-unpurchased'), false,
        'unmarked storefront content must remain blocked');
    } finally {
      globalThis.fetch = originalFetch;
      server.close();
    }
  },
  'playlist-membership': () => {
    reset();
    const memberships = mod.collectPlaylistVideoIds(playlistPage);
    assert.deepStrictEqual([...memberships].sort(), ['playlist-custom-1', 'playlist-system-1']);
    assert.strictEqual(mod.allowed('playlist-custom-1'), true,
      'a video in an account-owned custom playlist must be allowed');
    assert.strictEqual(mod.allowed('unrelated-playlist-video'), false,
      'a video absent from account-owned playlists must remain blocked');
    const playlistIds = mod.collectPlaylistIds(capturedPlaylistAggregationPage);
    assert.deepStrictEqual([...playlistIds].sort(), [
      'VLFLcaptured-owned',
      'VLPLcaptured-owned',
      'VLRDcaptured-owned',
    ], 'captured playlist aggregation tiles must yield VL browse IDs for PL, FL, and RD');
    const capturedMemberships = mod.collectPlaylistVideoIds(capturedPlaylistVideoListPage, new Set());
    assert.deepStrictEqual([...capturedMemberships], ['playlist-captured-tile-video'],
      'captured playlistVideoListRenderer tile entries must contribute video membership');
    assert.deepStrictEqual(mod.continuationTokens(capturedPlaylistVideoListPage), ['playlist-captured-next'],
      'captured playlist video lists must expose nextContinuationData tokens');
  },
  'music-library-membership': () => {
    reset();
    assert.deepStrictEqual([...mod.collectMusicLibraryVideoIds(musicLibraryPage, new Set())], [],
      'music responses without an explicit account-library source must not authorize videos');
    const memberships = mod.collectMusicLibraryVideoIds(musicLibraryPage, new Set(), accountLibrarySource);
    assert.deepStrictEqual([...memberships].sort(), ['music-direct-1', 'music-direct-2']);
    assert.strictEqual(mod.allowed('music-direct-1'), true,
      'a video directly represented in the owned Music library must be allowed');
    assert.strictEqual(mod.allowed('music-recommendation-1'), false,
      'a generic Music recommendation must remain blocked');
  },
  'music-artist-album-expansion': () => {
    reset();
    const memberships = mod.collectMusicCollectionVideoIds(
      [artistLibraryPage, albumLibraryPage], new Set(), accountCollectionSource);
    assert.deepStrictEqual([...memberships].sort(), ['music-album-1', 'music-artist-1']);
    assert.strictEqual(mod.allowed('music-artist-1'), true,
      'a video in an owned artist collection must be allowed');
    assert.strictEqual(mod.allowed('music-album-1'), true,
      'a video in an owned album collection must be allowed');
    assert.strictEqual(mod.allowed('music-public-artist-video'), false,
      'a video from a public artist page must remain blocked');
    const publicMemberships = mod.collectMusicCollectionVideoIds(
      [publicYourLovePage], new Set());
    assert.deepStrictEqual([...publicMemberships], [],
      'a public artist track titled Your Love must not be mistaken for an owned collection');
    assert.strictEqual(mod.allowed('music-public-your-love'), false,
      'a public artist track must remain blocked without account-library provenance');
    const collectionCards = mod.collectMusicCollectionBrowseIds({
      musicShelfRenderer: {
        title: { runs: [{ text: 'Saved artists' }] },
        contents: [{ musicTwoRowItemRenderer: {
          navigationEndpoint: { browseEndpoint: { browseId: 'UCsaved-artist' } },
        } }],
      },
    }, new Set(), accountCollectionSource);
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
void (async () => {
  for (const [name, test] of Object.entries(selectedTests)) {
    try {
      await test();
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
})();
