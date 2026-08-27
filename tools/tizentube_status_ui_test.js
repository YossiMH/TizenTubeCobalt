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
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Protecting you: list unavailable, retrying', 'an unloaded allowlist must never look like a guest terminal state');
S.loggedIn = false;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Protecting you: list unavailable, retrying', 'provisional signed-out state must keep probing');
S.ready = true;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Signed out: all videos blocked');
S.loggedIn = true;
S.booting = true;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Loading your liked videos and subscriptions...');
S.booting = false;
S.ready = true;
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Ready: 0 liked, 0 subscribed', 'a healthy authenticated zero account must report honest readiness');
S.liked.add('liked-1');
S.liked.add('liked-2');
S.subs.add('UCsub');
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Ready: 2 liked, 1 subscribed');
S.liked.clear();
S.subs.clear();
S.errors.push('allowlist unavailable');
S.subs.add('UCpartial');
assert.strictEqual(mod.statusText(), 'Restricted YouTube - Protecting you: list unavailable, retrying', 'partial allowlists must never look ready');
S.subs.clear();

const appended = [];
const styles = [];
const body = {
  appendChild(el) {
    appended.push(el);
    el.parentNode = body;
  },
};
const head = {
  appendChild(el) {
    styles.push(el);
    el.parentNode = head;
  },
};
global.document = {
  body,
  head,
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
assert.strictEqual(styles.length, 1, 'CSP-safe status styling should be attached once in the document head');
assert.strictEqual(styles[0].id, 'tt-allowed-only-style');
assert.ok(styles[0].textContent.includes('position:fixed'));
assert.ok(styles[0].textContent.includes('pointer-events:none'));
assert.strictEqual(appended[0].id, 'tt-allowed-only-status');
assert.strictEqual(appended[0].role, 'status');
assert.strictEqual(appended[0].textContent, rendered);
assert.strictEqual(appended[0].style.cssText, undefined, 'status must not rely on CSP-blocked inline style');
mod.renderStatus();
assert.strictEqual(appended.length, 1, 'rerender must reuse the same status element');
assert.strictEqual(styles.length, 1, 'rerender must reuse the same style element');

delete global.document;
console.log('All TizenTube visible status tests passed.');
