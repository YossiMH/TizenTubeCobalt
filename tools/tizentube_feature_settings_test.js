'use strict';

const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

const SETTINGS_KEY = 'ttAllowedOnlyFeatures';

function makeStorage() {
  const entries = new Map();
  return {
    getItem(key) { return entries.has(key) ? entries.get(key) : null; },
    setItem(key, value) { entries.set(key, String(value)); },
    removeItem(key) { entries.delete(key); },
    raw: entries,
  };
}

class TestElement {
  constructor(tagName) {
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.attributes = new Map();
    this.textContent = '';
    this.parentNode = null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) || null;
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
}

function makeDocument() {
  const body = new TestElement('body');
  return {
    body,
    createElement(tagName) { return new TestElement(tagName); },
  };
}

function withEnvironment(run) {
  const saved = {
    localStorage: globalThis.localStorage,
    document: globalThis.document,
  };
  globalThis.localStorage = makeStorage();
  globalThis.document = makeDocument();
  return Promise.resolve().then(async () => {
    try {
      await run();
    } finally {
      if (saved.localStorage === undefined) delete globalThis.localStorage;
      else globalThis.localStorage = saved.localStorage;
      if (saved.document === undefined) delete globalThis.document;
      else globalThis.document = saved.document;
      if (S.settingsEl && S.settingsEl.parentNode) S.settingsEl.parentNode.removeChild(S.settingsEl);
      S.settingsEl = null;
    }
  });
}

function resetFeatureState() {
  S.features = mod.defaultFeatures();
  S.settingsLoaded = false;
}

void (async () => {
  assert.strictEqual(typeof mod.loadSettings, 'function', 'settings loading must be exported');
  assert.strictEqual(typeof mod.saveSettings, 'function', 'settings saving must be exported');
  assert.strictEqual(typeof mod.setFeature, 'function', 'feature toggling must be exported');
  assert.strictEqual(typeof mod.featureEnabled, 'function', 'feature reads must be exported');
  assert.strictEqual(typeof mod.defaultFeatures, 'function', 'defaults must be exported');
  assert.strictEqual(typeof mod.openSettings, 'function', 'settings overlay open must be exported');
  assert.strictEqual(typeof mod.closeSettings, 'function', 'settings overlay close must be exported');

  await withEnvironment(() => {
    resetFeatureState();
    const loaded = mod.loadSettings();
    assert.deepStrictEqual(loaded, { adblock: 'on', sponsorblock: 'on', dearrow: 'on' },
      'missing persistence must fall back to enabled defaults');
    assert.strictEqual(mod.featureEnabled('adblock'), true);
    assert.strictEqual(mod.featureEnabled('sponsorblock'), true);
    assert.strictEqual(mod.featureEnabled('dearrow'), true);
    assert.strictEqual(typeof S.features.adblock, 'string', 'feature state must stay enum-valued');
    const persisted = JSON.parse(globalThis.localStorage.getItem(SETTINGS_KEY));
    assert.deepStrictEqual(persisted, loaded, 'defaults must be persisted immediately');
  });

  await withEnvironment(() => {
    resetFeatureState();
    globalThis.localStorage.setItem(SETTINGS_KEY, '{{not json at all');
    const loaded = mod.loadSettings();
    assert.deepStrictEqual(loaded, { adblock: 'on', sponsorblock: 'on', dearrow: 'on' },
      'corrupted persistence must recover to defaults instead of crashing boot');
    const rewritten = JSON.parse(globalThis.localStorage.getItem(SETTINGS_KEY));
    assert.deepStrictEqual(rewritten, loaded, 'recovered defaults must be written back');
  });

  await withEnvironment(() => {
    resetFeatureState();
    globalThis.localStorage.setItem(SETTINGS_KEY, '{"adblock":"maybe","sponsorblock":"off"}');
    const loaded = mod.loadSettings();
    assert.strictEqual(loaded.adblock, 'on', 'invalid enum values must normalize to defaults');
    assert.strictEqual(loaded.sponsorblock, 'off', 'valid stored values must survive normalization');
  });

  await withEnvironment(() => {
    resetFeatureState();
    mod.loadSettings();
    mod.saveSettings({ adblock: 'off' });
    assert.strictEqual(mod.featureEnabled('adblock'), false);
    assert.strictEqual(mod.featureEnabled('sponsorblock'), true, 'saving one feature must not touch others');
    assert.strictEqual(mod.featureEnabled('dearrow'), true, 'saving one feature must not touch others');
    assert.strictEqual(JSON.parse(globalThis.localStorage.getItem(SETTINGS_KEY)).adblock, 'off',
      'saves must reach durable storage');
  });

  await withEnvironment(() => {
    resetFeatureState();
    mod.loadSettings();
    assert.strictEqual(mod.setFeature('sponsorblock', 'off'), 'off');
    assert.strictEqual(mod.featureEnabled('adblock'), true, 'toggle isolation must hold for adblock');
    assert.strictEqual(mod.featureEnabled('dearrow'), true, 'toggle isolation must hold for dearrow');
    assert.strictEqual(mod.setFeature('sponsorblock', 'on'), 'on');

    assert.throws(() => mod.setFeature('adblock', 'yes'), /invalid feature value/,
      'non-enum values must be rejected');
    assert.strictEqual(mod.featureEnabled('adblock'), true, 'rejected toggles must not mutate state');
    assert.throws(() => mod.setFeature('unknown', 'on'), /unknown feature/,
      'unknown features must be rejected');
  });

  await withEnvironment(() => {
    resetFeatureState();
    assert.strictEqual(mod.featureEnabled('allowedOnly'), true,
      'allow-only enforcement is always on');
    assert.throws(() => mod.setFeature('allowedOnly', 'off'),
      /cannot be disabled/, 'allow-only enforcement must refuse disabling');
    mod.state.liked.add('kept1');
    mod.state.subs.clear();
    mod.state.loggedIn = true;
    mod.state.ready = true;
    mod.state.stripped = 0;
    const filtered = mod.filterTree({ contents: [
      { videoRenderer: { videoId: 'kept1' } },
      { videoRenderer: { videoId: 'blocked1' } },
    ] });
    assert.deepStrictEqual(filtered.contents.map(x => x.videoRenderer.videoId), ['kept1'],
      'feature settings must never weaken allow-list filtering');
  });

  await withEnvironment(() => {
    resetFeatureState();
    mod.loadSettings();
    assert.strictEqual(mod.openSettings(), 'open', 'the overlay must open when DOM exists');
    const overlay = S.settingsEl;
    assert.ok(overlay, 'openSettings must create the overlay element');
    if ('innerHTML' in overlay) {
      assert.strictEqual(overlay.innerHTML, '', 'overlay construction must never set innerHTML');
    }
    const featureRows = overlay.children.filter(c => c.getAttribute('data-feature'));
    assert.deepStrictEqual(featureRows.map(r => r.getAttribute('data-feature')).sort(),
      ['adblock', 'dearrow', 'sponsorblock'], 'each feature needs an overlay row');
    featureRows.forEach((row) => {
      assert.strictEqual(row.textContent.indexOf('<') < 0, true,
        'overlay text must be built with textContent only');
    });
    assert.strictEqual(mod.closeSettings(), 'closed', 'closeSettings must close the overlay');
    assert.strictEqual(S.settingsEl, null, 'closing must drop the cached overlay');
  });

  await withEnvironment(() => {
    resetFeatureState();
    mod.loadSettings();
    const savedLocation = globalThis.location;
    globalThis.location = new URL('https://www.youtube.com/tv#/settings');
    try {
      S.settingsRouteShown = 'off';
      assert.strictEqual(typeof mod.isSettingsRoute, 'function',
        'settings-route detection must be exported');
      assert.strictEqual(mod.isSettingsRoute(), true,
        'ordinary YouTube TV Settings must be recognized as the discoverable TizenTube settings entry point');
      mod.onRouteChanged();
      assert.ok(S.settingsEl,
        'entering ordinary YouTube TV Settings must expose the TizenTube settings overlay without a special remote key');
      assert.strictEqual(S.settingsRouteShown, 'on');
      mod.closeSettings();
    } finally {
      if (savedLocation === undefined) delete globalThis.location;
      else globalThis.location = savedLocation;
    }
  });

  console.log('All TizenTube feature settings tests passed.');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
