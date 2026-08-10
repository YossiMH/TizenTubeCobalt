'use strict';
const assert = require('assert');
const mod = require('../x.js');
const S = mod.state;

function reset() {
  S.liked.clear();
  S.subs.clear();
  S.errors.length = 0;
  S.loggedIn = null;
  S.ready = false;
  S.booting = false;
  S.statusEl = null;
}

reset();
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Starting...');
S.loggedIn = false;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Signed out: all videos blocked');
S.loggedIn = true;
S.booting = true;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Loading your liked videos and subscriptions...');
S.booting = false;
S.ready = true;
S.liked.add('liked-1');
S.liked.add('liked-2');
S.subs.add('UCsub');
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Ready: 2 liked, 1 subscribed');
S.liked.clear();
S.subs.clear();
S.errors.push('allowlist unavailable');
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Protecting you: list unavailable, retrying');

const appended = [];
const body = {
  appendChild(el) {
    appended.push(el);
    el.parentNode = body;
  },
};
global.document = {
  body,
  createElement() {
    return {
      style: {},
      setAttribute(name, value) { this[name] = value; },
    };
  },
};

S.statusEl = null;
const rendered = mod.renderStatus();
assert.strictEqual(rendered, 'Restricted YouTube - Protecting you: list unavailable, retrying');
assert.strictEqual(appended.length, 1, 'status should be attached exactly once');
assert.strictEqual(appended[0].id, 'tt-allowed-only-status');
assert.strictEqual(appended[0].role, 'status');
assert.strictEqual(appended[0].textContent, rendered);
assert.ok(appended[0].style.cssText.includes('position:fixed'));
assert.ok(appended[0].style.cssText.includes('pointer-events:none'));
mod.renderStatus();
assert.strictEqual(appended.length, 1, 'rerender must reuse the same status element');

delete global.document;
console.log('All TizenTube visible status tests passed.');
