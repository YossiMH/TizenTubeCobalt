'use strict';
// Independent black-box regression for the actual-alarm startup/play handoff.
// The only player operations exercised are exported lease APIs and media methods.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const vm = require('node:vm');
const sourcePath = process.argv[2] || path.resolve(__dirname, '../../../x.js');
const source = fs.readFileSync(sourcePath, 'utf8');
const A = 'aaaaaaaaaaa', B = 'bbbbbbbbbbb';
const DENIED = 'UC' + 'd'.repeat(22);
const ALLOWED = 'UC' + 'e'.repeat(22);
let checks = 0;
const results = [];
function equal(actual, expected, message) { assert.equal(actual, expected, message); checks++; }
function check(value, message) { assert(value, message); checks++; }
function outcome(promise) {
  const result = {status: 'pending'};
  Promise.resolve(promise).then(value => { result.status = 'resolved'; result.value = value; },
    error => { result.status = 'rejected'; result.error = String(error); });
  return result;
}
function harness() {
  const clock = {wall: 1795029193719, elapsed: 1000};
  const timers = new Map(), windowListeners = new Map(), documentListeners = new Map();
  const allMedia = [], posts = [];
  let timerId = 0;
  function listen(map, type, fn, capture) {
    const entries = map.get(type) || []; entries.push({fn, capture: !!capture}); map.set(type, entries);
  }
  function emit(map, type, target, onlyCapture = false) {
    for (const entry of map.get(type) || []) if (!onlyCapture || entry.capture) entry.fn({type, target});
  }
  const document = {hidden: false,
    addEventListener(type, fn, capture) { listen(documentListeners, type, fn, capture); },
    querySelectorAll() { return allMedia; }};
  class Media {
    constructor() {
      this.currentTime = 0; this.duration = 3000; this.paused = true; this.ended = false;
      this.readyState = 4; this.currentSrc = 'blob:original'; this.tagName = 'VIDEO';
      this.listeners = new Map(); this.playCalls = 0; this.pauseCalls = 0; this.loadCalls = 0;
      this.deferNativePlay = false; this.nativePending = null; allMedia.push(this);
    }
    play() {
      this.playCalls++; this.paused = false;
      let result = Promise.resolve();
      if (this.deferNativePlay) result = new Promise((resolve, reject) => { this.nativePending = {resolve, reject}; });
      this.emit('play');
      return result;
    }
    pause() {
      const wasPlaying = !this.paused;
      this.pauseCalls++; this.paused = true;
      if (this.nativePending) {
        const pending = this.nativePending; this.nativePending = null;
        pending.reject(new Error('Native play interrupted by pause'));
      }
      if (wasPlaying) this.emit('pause');
    }
    load() { this.loadCalls++; }
    get src() { return this._src || ''; }
    set src(value) { this._src = value; this.currentSrc = String(value); }
    addEventListener(type, fn, capture) { listen(this.listeners, type, fn, capture); }
    emit(type) {
      emit(documentListeners, type, this, true);
      emit(this.listeners, type, this);
    }
    settleNativePlay() {
      assert(this.nativePending, 'fixture has unresolved native play');
      const pending = this.nativePending; this.nativePending = null;
      pending.resolve(); this.emit('playing');
    }
  }
  class ClockDate extends Date { static now() { return clock.wall; } }
  class LocalURL extends URL {}
  LocalURL.createObjectURL = () => 'blob:new';
  const storage = new Map();
  const context = vm.createContext({module: {exports: {}}, console: {log() {}}, URL: LocalURL,
    URLSearchParams, Date: ClockDate, performance: {now: () => clock.elapsed},
    localStorage: {getItem(k) { return storage.get(k) || null; }, setItem(k, v) { storage.set(k, String(v)); }},
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, {fn, at: clock.elapsed + Number(delay)}); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return ++timerId; }, clearInterval() {},
    addEventListener(type, fn, capture) { listen(windowListeners, type, fn, capture); },
    HTMLMediaElement: Media});
  vm.runInContext(source, context, {filename: 'player.js'});
  context.document = document;
  context.location = {href: 'https://www.youtube.com/tv#/watch?v=' + A};
  const api = context.module.exports, state = api.state;
  Object.assign(state, {ready: true, loggedIn: true, fetch0: async (url, init) => {
    assert.equal(url, 'http://localhost:8012/apps/YouTube', 'fixture forbids external network');
    posts.push(new URLSearchParams(init.body)); return {ok: true};
  }});
  state.liked.add(A); state.liked.add(B); api.installMediaGuard();
  async function flush() { for (let i = 0; i < 40; i++) await Promise.resolve(); }
  async function advance(ms, wallRate = 1) {
    for (let remaining = ms; remaining > 0;) {
      const delta = Math.min(100, remaining); remaining -= delta;
      clock.wall += delta * wallRate; clock.elapsed += delta;
      let budget = 1000;
      while (true) {
        const due = [...timers].filter(([, t]) => t.at <= clock.elapsed).sort((a, b) => a[1].at - b[1].at);
        if (!due.length) break;
        if (--budget < 0) throw Error('unexpected timer loop');
        const [id, timer] = due[0]; timers.delete(id); timer.fn(); await flush();
      }
      await flush();
    }
  }
  function route(v) {
    context.location.href = 'https://www.youtube.com/tv#/watch?v=' + v;
    emit(windowListeners, 'hashchange', context);
  }
  function hidden(value) { document.hidden = value; emit(documentListeners, 'visibilitychange', document); }
  function lease(video = '', ttl = 12000) { return api.setMorningStartupLease(clock.wall + ttl, video); }
  return {api, state, context, clock, Media, posts, flush, advance, route, hidden, lease};
}
async function queued(h) {
  equal(h.lease(), true, 'empty startup lease is accepted');
  const media = new h.Media(), result = outcome(media.play());
  await h.flush();
  equal(media.playCalls, 0, 'startup-only request does not start native playback');
  equal(result.status, 'pending', 'startup-only request retains its original promise');
  return {media, result};
}
async function test(name, fn) {
  const start = checks;
  try { await fn(); results.push({name, status: 'passed', assertions: checks - start}); }
  catch (error) { results.push({name, status: 'failed', assertions: checks - start, error: error.message}); }
}
async function cancellation(name, cancel, restore = async () => {}) {
  await test(name, async () => {
    const h = harness(), {media, result} = await queued(h);
    await cancel(h, media); await h.advance(200);
    equal(result.status, 'rejected', name + ' rejects the retained request');
    await restore(h, media); h.route(A); h.lease(A); await h.advance(300);
    equal(media.playCalls, 0, name + ' cannot resurrect the old request');
  });
}
(async function main() {
  await test('exact candidate dispatches once after repeated empty renewals', async () => {
    const h = harness(), {media, result} = await queued(h);
    for (let n = 0; n < 3; n++) { await h.advance(200); h.lease(); await h.flush(); }
    equal(result.status, 'pending', 'warmup renewals retain the deferred request');
    equal(media.playCalls, 0, 'warmup renewals never dispatch');
    h.lease(A); await h.advance(200);
    equal(media.playCalls, 1, 'positive exact candidate dispatches once');
    equal(result.status, 'resolved', 'original deferred play resolves');
    const pauses = media.pauseCalls;
    for (let n = 0; n < 3; n++) { await h.advance(100); h.lease(A); await h.flush(); }
    equal(media.playCalls, 1, 'candidate renewals never duplicate play');
    equal(media.pauseCalls, pauses, 'candidate renewals do not pause established media');
  });
  await test('candidate renewal preserves unresolved native play', async () => {
    const h = harness(), {media, result} = await queued(h); media.deferNativePlay = true;
    h.lease(A); await h.advance(100);
    equal(media.playCalls, 1, 'candidate starts native play once');
    equal(result.status, 'pending', 'native promise is deliberately unresolved');
    check(media.nativePending, 'native play remains unresolved before renewal');
    const pauses = media.pauseCalls;
    for (let n = 0; n < 4; n++) { h.lease(A); await h.advance(100); }
    equal(media.pauseCalls, pauses, 'renewal does not abort an authorized native play promise');
    equal(result.status, 'pending', 'renewal leaves original promise pending');
    media.settleNativePlay(); await h.flush();
    equal(result.status, 'resolved', 'original play completes after native confirmation');
    equal(media.playCalls, 1, 'native completion does not cause a second play');
  });
  await test('direct candidate native play survives renewal before tracking exists', async () => {
    const h = harness(); equal(h.lease(A), true, 'selected lease is installed before the direct request');
    const media = new h.Media(); media.deferNativePlay = true;
    const result = outcome(media.play()); await h.flush();
    equal(media.playCalls, 1, 'direct authorized native play starts once');
    equal(result.status, 'pending', 'native startup remains unresolved before tracking publication');
    const pauses = media.pauseCalls;
    h.lease(A); await h.flush();
    equal(media.pauseCalls, pauses, 'renewal cannot pause the unresolved direct native request');
    equal(result.status, 'pending', 'direct native promise remains pending after renewal');
    media.settleNativePlay(); await h.flush();
    equal(result.status, 'resolved', 'direct play resolves after native confirmation');
    equal(media.playCalls, 1, 'renewal never reissues direct native play');
  });
  await test('lease updates cannot start media without a retained request', async () => {
    const h = harness(), media = new h.Media(); h.lease(); h.lease(A); await h.advance(300);
    equal(media.playCalls, 0, 'candidate selection alone never invokes play');
    h.api.setMorningStartupLease(0, ''); await h.advance(300);
    equal(media.playCalls, 0, 'lease release alone never invokes play');
  });
  await cancellation('wrong candidate cancels', async h => h.lease(B));
  await cancellation('explicit lease release cancels', async h => h.api.setMorningStartupLease(0, ''));
  await cancellation('elapsed lease expiry cancels', async h => h.advance(12001));
  await cancellation('monotonic expiry with frozen wall clock cancels', async h => h.advance(12001, 0));
  await cancellation('hidden page cancels', async h => h.hidden(true), async h => h.hidden(false));
  await cancellation('route change cancels', async h => h.route(B));
  await cancellation('source replacement cancels', async (h, media) => { media.src = 'blob:replacement'; });
  await cancellation('explicit load cancels', async (h, media) => media.load());
  await cancellation('explicit parent pause cancels', async (h, media) => media.pause());
  await cancellation('school reminder takes precedence', async h => {
    equal(h.api.setSchoolBannerLease(h.clock.wall + 12000, '', false), true, 'fallback school lease accepted');
  }, async h => h.api.setSchoolBannerLease(0, '', false));
  await test('denied channel cannot obtain playback through candidate lease', async () => {
    const h = harness(); h.state.liked.delete(A); h.state.v2c.set(A, DENIED);
    h.lease(); const media = new h.Media(), result = outcome(media.play()); await h.flush();
    h.lease(A); await h.advance(300);
    equal(media.playCalls, 0, 'candidate identity is insufficient without positive allowlist permission');
    equal(result.status, 'rejected', 'known denial rejects the original play');
  });
  await test('unknown ownership waits for positive channel evidence', async () => {
    const h = harness(); h.state.liked.delete(A); h.state.subs.add(ALLOWED);
    const {media, result} = await queued(h); h.lease(A); await h.advance(300);
    equal(media.playCalls, 0, 'unknown owner is insufficient');
    equal(result.status, 'pending', 'the exact candidate still awaits allowlist proof');
    h.state.v2c.set(A, ALLOWED); await h.advance(200);
    equal(media.playCalls, 1, 'positive channel evidence permits exactly one dispatch');
    equal(result.status, 'resolved', 'deferred promise resolves after positive evidence');
  });
  await test('late denied ownership cancels the pending exact candidate', async () => {
    const h = harness(); h.state.liked.delete(A);
    const {media, result} = await queued(h); h.lease(A); await h.advance(200);
    h.state.v2c.set(A, DENIED); await h.advance(200);
    equal(result.status, 'rejected', 'late negative permission cancels');
    h.state.liked.add(A); h.lease(A); await h.advance(200);
    equal(media.playCalls, 0, 'subsequent allowlist change cannot revive the cancelled request');
  });
  await test('wrong candidate cancels an unresolved native play', async () => {
    const h = harness(), {media, result} = await queued(h); media.deferNativePlay = true;
    h.lease(A); await h.advance(100); h.lease(B); await h.flush();
    equal(result.status, 'rejected', 'different candidate interrupts pending native play');
    check(media.paused, 'different candidate leaves media paused');
    h.api.setMorningStartupLease(0, ''); await h.advance(200);
    equal(media.playCalls, 1, 'release never restarts interrupted native play');
  });
  const receipt = {schema: 1, source_sha256: crypto.createHash('sha256').update(source).digest('hex'),
    status: results.every(r => r.status === 'passed') ? 'passed' : 'failed', assertions: checks,
    tests: results, scope: 'Independent black-box startup-only deferred play and unresolved native-play renewal boundaries',
    limits: ['Host VM with deterministic media/timer fixtures.', 'Native pause rejects unresolved play as on-device race reproduction requires.',
      'No device, external account history, or production source is mutated.']};
  if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(receipt, null, 2) + '\n');
  process.stdout.write(JSON.stringify(receipt, null, 2) + '\n');
  if (receipt.status !== 'passed') process.exitCode = 1;
})();
