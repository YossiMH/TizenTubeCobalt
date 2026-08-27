'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

function resetFilterState() {
  S.allowedNames.clear();
  S.errors.length = 0;
  S.loggedIn = true;
  S.ready = true;
  S.booting = false;
  S.stripped = 0;
}

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

  setAttribute(name, value) {
    this.attributes.set(name, value);
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

function createHistory(locationHref) {
  const location = new URL(locationHref);
  const calls = [];
  return {
    location,
    calls,
    pushState(state, title, href) {
      calls.push(['pushState', href]);
      location.href = new URL(href, location.href).href;
    },
    replaceState(state, title, href) {
      calls.push(['replaceState', href]);
      location.href = new URL(href, location.href).href;
    },
  };
}

function withEnvironment(history, run) {
  const saved = {
    document: globalThis.document,
    history: globalThis.history,
    location: globalThis.location,
    MutationObserver: globalThis.MutationObserver,
    fetch: globalThis.fetch,
    XMLHttpRequest: globalThis.XMLHttpRequest,
    HTMLMediaElement: globalThis.HTMLMediaElement,
  };
  const root = new TestParent();
  globalThis.document = createDocument(root);
  globalThis.history = history;
  globalThis.location = history.location;
  const observers = [];
  globalThis.MutationObserver = class {
    constructor(callback) {
      this.callback = callback;
      this.target = null;
      this.options = null;
    }

    observe(target, options) {
      this.target = target;
      this.options = options;
      observers.push(this);
    }

    emit(records) {
      this.callback(records, this);
    }
  };
  return Promise.resolve().then(async () => {
    try {
      await run({ root, observers });
    } finally {
      if (S.statusWatcher) clearInterval(S.statusWatcher);
      S.statusWatcher = null;
      globalThis.fetch = saved.fetch;
      globalThis.document = saved.document;
      globalThis.history = saved.history;
      globalThis.location = saved.location;
      globalThis.MutationObserver = saved.MutationObserver;
      globalThis.XMLHttpRequest = saved.XMLHttpRequest;
      if (saved.HTMLMediaElement === undefined) delete globalThis.HTMLMediaElement;
      else globalThis.HTMLMediaElement = saved.HTMLMediaElement;
    }
  });
}

void (async () => {
  assert.strictEqual(typeof mod.isWatchRoute, 'function',
    'watch-route detection must be exported');
  assert.strictEqual(typeof mod.installRouteObserver, 'function',
    'route observer installation must be exported');
  assert.strictEqual(typeof mod.onRouteChanged, 'function',
    'route-change handling must be exported');

  await withEnvironment(createHistory('https://www.youtube.com/'), async ({ root }) => {
    resetFilterState();
    globalThis.location.href = 'https://www.youtube.com/watch?v=suggested1';
    assert.strictEqual(mod.isWatchRoute(), true, '/watch must be recognized');
    globalThis.location.hash = '#/watch?v=suggested2';
    assert.strictEqual(mod.isWatchRoute(), true, 'hash watch routes must be recognized');
    globalThis.location.hash = '';
    globalThis.location.href = 'https://www.youtube.com/results?search_query=test';
    assert.strictEqual(mod.isWatchRoute(), false, 'search must not be treated as a watch route');

    const authorized = new TestNode('ytlr-tile-renderer', 'Liked Video');
    const suggested = new TestNode('ytlr-watch-next-renderer', 'Autoplay Suggestion');
    authorized.setAttribute('role', 'button');
    authorized.attach(root);
    suggested.attach(root);
    assert.strictEqual(mod.onRouteChanged(), 1,
      'a watch-route change must sweep autoplay suggestion tiles');
    assert.strictEqual(authorized.parentNode, root, 'controls must remain');
    assert.strictEqual(suggested.parentNode, null, 'unauthorized suggestions must be removed');
    assert.strictEqual(S.stripped, 1, 'suggestion removals must count exactly once');
  });

  await withEnvironment(createHistory('https://www.youtube.com/'), async ({ root }) => {
    resetFilterState();
    const suggested = new TestNode('ytlr-watch-next-renderer', 'End-screen Suggestion');
    suggested.attach(root);
    assert.strictEqual(mod.installRouteObserver(), 'on',
      'observer state uses an explicit enum string');
    assert.strictEqual(mod.installRouteObserver(), 'on',
      'installation must be idempotent');

    history.pushState({}, '', '/watch?v=next-video');
    assert.strictEqual(suggested.parentNode, null,
      'history navigation to watch must remove unauthorized end-screen suggestions');
  });

  await withEnvironment(createHistory('https://www.youtube.com/watch?v=current'), async ({ root, observers }) => {
    resetFilterState();
    mod.installRouteObserver();
    assert.strictEqual(observers.length, 1, 'exactly one DOM observer may be installed');
    assert.deepStrictEqual(observers[0].options, { childList: true, subtree: true },
      'the DOM observer must cover dynamically inserted suggestions');

    const suggested = new TestNode('ytlr-watch-next-renderer', 'Automatic Next Video');
    suggested.attach(root);
    observers[0].emit([{ addedNodes: [suggested] }]);
    assert.strictEqual(suggested.parentNode, null,
      'suggestions inserted when playback ends must be swept without a URL change');
  });

  await withEnvironment(createHistory('https://www.youtube.com/'), async () => {
    resetFilterState();
    const savedInstalled = S.installed;
    const savedFetch0 = S.fetch0;
    S.installed = false;
    try {
      mod.install();
      assert.strictEqual(S.routeObserver, 'on', 'install must arm route observation');
    } finally {
      S.installed = savedInstalled;
      S.fetch0 = savedFetch0;
    }
  });

  await withEnvironment(createHistory('https://www.youtube.com/'), async () => {
    resetFilterState();
    S.liked.add('allowed-name-source');
    mod.filterTree({ contents: [
      { videoRenderer: {
        videoId: 'allowed-name-source',
        channelId: 'UCallowedName',
        ownerText: { runs: [{ text: 'Allowed Name Source' }] },
      } },
      { videoRenderer: {
        videoId: 'blocked-name-source',
        channelId: 'UCblockedName',
        ownerText: { runs: [{ text: 'Blocked Name Source' }] },
      } },
    ] });
    assert.ok(S.allowedNames.has('allowed name source'),
      'filtered allowed video responses must populate the DOM-safe channel-name cache');
    assert.strictEqual(S.allowedNames.has('blocked name source'), false,
      'blocked video responses must never populate the DOM-safe channel-name cache');
  });

  await withEnvironment(createHistory('https://www.youtube.com/watch?v=guest-current'), async ({ root }) => {
    resetFilterState();
    S.loggedIn = false;
    S.ready = true;
    S.allowedNames.add('formerly allowed channel');
    const staleAuthorizedLooking = new TestNode('ytlr-watch-next-renderer', 'Formerly Allowed Channel');
    staleAuthorizedLooking.attach(root);
    assert.strictEqual(mod.onRouteChanged(), 1,
      'guest mode must ignore stale channel-name cache entries after sign-out');
    assert.strictEqual(staleAuthorizedLooking.parentNode, null,
      'signed-out watch suggestions must remain fail-closed even when stale names survive');
  });

  await withEnvironment(createHistory('https://www.youtube.com/watch?v=current'), async ({ root }) => {
    resetFilterState();
    S.liked.clear();
    S.subs.clear();
    S.v2c.clear();
    S.allowedNames.add('trusted-looking text');

    const poisoned = new TestNode('ytlr-watch-next-renderer', 'Trusted-Looking Text');
    poisoned.setAttribute('href', '/watch?v=blockedNext123');
    poisoned.attach(root);
    assert.strictEqual(mod.elementVideoId(poisoned), 'blockedNext123',
      'watch-card video ids must be read from ordinary watch hrefs');
    assert.strictEqual(mod.onRouteChanged(), 1,
      'a known blocked watch-card id must override permissive text fallback');
    assert.strictEqual(poisoned.parentNode, null,
      'a random automatic-next card must be removed even when its text matches an allowed channel name');

    S.liked.add('likedNext123');
    const allowed = new TestNode('ytlr-watch-next-renderer', 'Anything');
    allowed.setAttribute('href', '/watch?v=likedNext123');
    allowed.attach(root);
    assert.strictEqual(mod.onRouteChanged(), 0,
      'an explicitly allowed watch-card id must survive the sweep');
    assert.strictEqual(allowed.parentNode, root,
      'liked automatic-next cards remain available');
  });

  console.log('All TizenTube watch suggestion tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
