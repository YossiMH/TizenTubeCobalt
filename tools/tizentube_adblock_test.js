'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

class TestNode {
  constructor(tagName, text) {
    this.tagName = tagName.toUpperCase();
    this.textContent = text;
    this.innerText = text;
    this.parentNode = null;
    this.attributes = new Map();
  }

  getAttribute(name) {
    return this.attributes.get(name) || null;
  }

  attach(parent) {
    this.parentNode = parent;
    parent.children.push(this);
  }

  detach() {
    if (!this.parentNode) return;
    const index = this.parentNode.children.indexOf(this);
    if (index >= 0) this.parentNode.children.splice(index, 1);
    this.parentNode = null;
  }
}

class TestParent {
  constructor() {
    this.children = [];
  }

  removeChild(node) {
    node.detach();
  }
}

function createDocument(root) {
  return {
    body: root,
    querySelectorAll(selector) {
      const wanted = selector.toUpperCase();
      return root.children.filter(node => node.tagName === wanted);
    },
  };
}

function withEnvironment(run) {
  const saved = { document: globalThis.document };
  const root = new TestParent();
  globalThis.document = createDocument(root);
  return Promise.resolve().then(async () => {
    try {
      await run(root);
    } finally {
      if (saved.document === undefined) delete globalThis.document;
      else globalThis.document = saved.document;
    }
  });
}

function resetFeatureState() {
  S.features = mod.defaultFeatures ? mod.defaultFeatures() : S.features;
  S.fetch0 = null;
}

void (async () => {
  assert.strictEqual(typeof mod.shouldBlockAdRequest, 'function',
    'ad request gating must be exported');
  assert.strictEqual(typeof mod.sanitizeAdPayload, 'function',
    'ad payload sanitization must be exported');

  await withEnvironment(() => {
    resetFeatureState();
    assert.strictEqual(mod.shouldBlockAdRequest(new URL('https://www.youtube.com/pagead/interaction')), true,
      'pagead endpoints must be recognized as ad traffic when the feature is on');
    assert.strictEqual(mod.shouldBlockAdRequest(new URL('https://googleads.g.doubleclick.net/pagead/id')), true,
      'doubleclick hosts must be recognized as ad traffic');
    assert.strictEqual(mod.shouldBlockAdRequest(new URL('https://www.youtube.com/youtubei/v1/next?key=k')), false,
      'content API endpoints must never be treated as ads');

    mod.setFeature('adblock', 'off');
    assert.strictEqual(mod.shouldBlockAdRequest(new URL('https://www.youtube.com/pagead/interaction')), false,
      'disabling adblock must stop endpoint blocking');
    mod.setFeature('adblock', 'on');
  });

  await withEnvironment(() => {
    resetFeatureState();
    const feed = {
      videoDetails: { videoId: 'liked1' },
      streamingData: { formats: [1] },
      adPlacements: [{ adPlacementRenderer: {} }],
      playerAds: [{ playerLegacyDesktopYpcAdRenderer: {} }],
      adSlots: [],
      contents: [
        { richItemRenderer: { content: { videoRenderer: { videoId: 'liked2' } } } },
        { richItemRenderer: { content: { adSlotRenderer: {} } } },
      ],
    };
    const cleaned = mod.sanitizeAdPayload(JSON.parse(JSON.stringify(feed)));
    assert.ok(cleaned.videoDetails && cleaned.streamingData, 'sanitization must keep real video data');
    assert.strictEqual(cleaned.adPlacements, undefined, 'top-level ad placements must be removed');
    assert.strictEqual(cleaned.playerAds, undefined, 'player ads must be removed');
    assert.strictEqual(cleaned.adSlots, undefined, 'ad slots must be removed');
    assert.strictEqual(cleaned.contents.length, 1, 'nested ad-only renderers must be dropped');
    assert.deepStrictEqual(
      cleaned.contents[0].richItemRenderer.content.videoRenderer.videoId, 'liked2');

    mod.setFeature('adblock', 'off');
    const untouched = JSON.parse(JSON.stringify(feed));
    assert.notStrictEqual(mod.sanitizeAdPayload(untouched).adPlacements, undefined,
      'payloads must be untouched while adblock is off');
    mod.setFeature('adblock', 'on');

    mod.state.liked.add('liked1');
    mod.state.loggedIn = true;
    mod.state.ready = true;
    mod.state.stripped = 0;
    const filtered = mod.filterTree({ contents: [
      { videoRenderer: { videoId: 'liked1' } },
      { videoRenderer: { videoId: 'bad1' } },
      { adPlacementRenderer: {} },
    ] });
    assert.deepStrictEqual(filtered.contents.map(x => x.videoRenderer.videoId), ['liked1'],
      'allow-list filtering must keep working alongside ad sanitization');
  });

  await withEnvironment((root) => {
    resetFeatureState();
    const promoted = new TestNode('ytlr-promoted-video-renderer', 'Sponsored');
    promoted.attach(root);
    assert.strictEqual(mod.sweepDom(), 1,
      'promoted suggestion containers must be swept when adblock is on');
    assert.strictEqual(promoted.parentNode, null);

    const keptWhenOff = new TestNode('ytlr-promoted-video-renderer', 'Sponsored');
    keptWhenOff.attach(root);
    mod.setFeature('adblock', 'off');
    assert.strictEqual(mod.sweepDom(), 0,
      'promoted containers must remain visible while adblock is off');
    assert.strictEqual(keptWhenOff.parentNode, root);
    mod.setFeature('adblock', 'on');
  });

  {
    const savedFetch = globalThis.fetch;
    const savedDocument = globalThis.document;
    let nativeFetchCalls = 0;
    globalThis.fetch = async function () {
      nativeFetchCalls++;
      return new Response('ok', { status: 200 });
    };
    if (savedDocument === undefined) delete globalThis.document;
    S.installed = false;
    S.fetch0 = null;
    if (S.statusWatcher) clearInterval(S.statusWatcher);
    S.statusWatcher = null;
    try {
      mod.install();
      const blocked = await globalThis.fetch('https://googleads.g.doubleclick.net/pagead/id');
      assert.strictEqual(blocked.status, 204,
        'the installed fetch interceptor must synthesize a no-content response for ad endpoints');
      assert.strictEqual(nativeFetchCalls, 0,
        'blocked ad requests must never reach the native network fetch');
      const ordinary = await globalThis.fetch('https://example.com/content');
      assert.strictEqual(ordinary.status, 200);
      assert.strictEqual(nativeFetchCalls, 1,
        'ordinary non-ad traffic must continue through the native fetch');
    } finally {
      if (S.statusWatcher) clearInterval(S.statusWatcher);
      S.statusWatcher = null;
      S.installed = false;
      S.fetch0 = null;
      globalThis.fetch = savedFetch;
      if (savedDocument === undefined) delete globalThis.document;
      else globalThis.document = savedDocument;
    }
  }

  console.log('All TizenTube ad blocker tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
