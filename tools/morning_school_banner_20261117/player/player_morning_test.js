'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').resolve(__dirname, '../../../x.js'), 'utf8');
const A = 'EP2tuoBV184', B = 'A2jqHRa7MIs';
const KEY = 'ttMorningObservedProgressV1';
let checks = 0;
function check(value, message) { assert(value, message); checks++; }
function equal(actual, expected, message) { assert.equal(actual, expected, message); checks++; }
function storage(seed) {
  const data = Object.assign(Object.create(null), seed || {});
  return {data, writes: [], getItem(k) { return data[k] || null; },
    setItem(k, v) { data[k] = String(v); this.writes.push({key: k, value: String(v)}); }};
}
function harness(st = storage(), wall = 1794421475372) {
  const clock = {wall, elapsed: 1000};
  const timers = new Map(); let timerId = 0;
  const posts = [], allMedia = [], documentListeners = new Map();
  const document = {hidden: false,
    addEventListener(type, fn, capture) {
      const list = documentListeners.get(type) || []; list.push({fn, capture}); documentListeners.set(type, list);
    }, querySelectorAll() { return allMedia; }};
  class Media {
    constructor() {
      this.currentTime = 0; this.duration = 1000; this.paused = true; this.ended = false;
      this.readyState = 4; this.currentSrc = 'blob:original'; this.listeners = new Map();
      this.playCalls = 0; this.pauseCalls = 0; this.loadCalls = 0; this.tagName = 'VIDEO'; allMedia.push(this);
    }
    play() { this.playCalls++; this.paused = false; this.emit('play'); return Promise.resolve(); }
    pause() { this.pauseCalls++; this.paused = true; }
    load() { this.loadCalls++; }
    get src() { return this._src || ''; }
    set src(value) { this._src = value; this.currentSrc = value; }
    addEventListener(type, fn, capture = false) {
      const list = this.listeners.get(type) || []; list.push({fn, capture}); this.listeners.set(type, list);
    }
    emit(type) {
      const e = {type, target: this};
      for (const l of documentListeners.get(type) || []) if (l.capture) l.fn(e);
      for (const capture of [true, false]) for (const l of this.listeners.get(type) || []) if (!!l.capture === capture) l.fn(e);
    }
  }
  class ClockDate extends Date { static now() { return clock.wall; } }
  class LocalURL extends URL {}
  LocalURL.createObjectURL = () => 'blob:new';
  const context = vm.createContext({module: {exports: {}}, console: {log() {}}, URL: LocalURL,
    URLSearchParams, Date: ClockDate, performance: {now: () => clock.elapsed}, localStorage: st,
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, {fn, at: clock.elapsed + Number(delay)}); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return ++timerId; }, clearInterval() {},
    HTMLMediaElement: Media});
  vm.runInContext(source, context, {filename: 'player.js'});
  context.document = document;
  context.location = {href: 'https://www.youtube.com/tv#/watch?v=' + A};
  const api = context.module.exports, state = api.state;
  Object.assign(state, {ready: true, loggedIn: true, fetch0: async (url, init) => {
    assert.equal(url, 'http://localhost:8012/apps/YouTube', 'tests permit only local status publication');
    posts.push(new URLSearchParams(init.body)); return {ok: true};
  }});
  state.liked.add(A); state.liked.add(B); api.installMediaGuard();
  function route(v) { context.location.href = 'https://www.youtube.com/tv#/watch?v=' + v; }
  async function flush() { for (let i = 0; i < 4; i++) await Promise.resolve(); }
  async function advance(ms) {
    clock.wall += ms; clock.elapsed += ms;
    let count = 0;
    while (true) {
      const due = [...timers].filter(([, t]) => t.at <= clock.elapsed).sort((a, b) => a[1].at - b[1].at);
      if (!due.length) break;
      if (++count > 1000) throw new Error('unexpected timer loop');
      const [id, t] = due[0]; timers.delete(id); t.fn(); await flush();
    }
  }
  async function playing(v = A) {
    route(v); const m = new Media(); await m.play(); await flush(); return m;
  }
  function progress() { return JSON.parse(new URLSearchParams(api.morningStatusPayload(clock.wall)).get('ms_progress') || '{}'); }
  return {api, state, context, clock, timers, posts, st, Media, playing, route, flush, advance, progress};
}

(async function main() {
  {
    const h = harness(); const m = await h.playing();
    equal(h.st.getItem(KEY), null, 'resolved play is not watched evidence');
    await h.api.writeMorningPlaying(A);
    equal(h.st.getItem(KEY), null, 'unchanged position is not watched evidence');
    m.currentTime = 250; m.emit('timeupdate');
    equal(JSON.parse(h.st.getItem(KEY)).entries[A].percent, 25, 'exactly 25% is recorded');
    const saves = h.st.writes.length;
    m.currentTime = 250.1; m.emit('timeupdate');
    check(h.st.writes.length > saves, 'first position above 25% persists immediately');
    check(JSON.parse(h.st.getItem(KEY)).entries[A].percent > 25, 'threshold survives immediate process loss');
    await h.api.writeMorningProgress({[A]: 0, [B]: 12});
    check(h.progress()[A] > 25, 'stale account zero cannot erase local observation');
    check(h.progress().__local_observed[A] > 25, 'independent local provenance is published');
    equal(h.progress()[B], 12, 'account progress remains available');
    m.currentTime = 100; m.emit('timeupdate');
    m.currentTime = 101; m.emit('timeupdate');
    check(h.progress()[A] > 25, 'rewind and later low progress cannot decrease observation');
    await h.api.writeMorningProgress({[A]: 80});
    equal(h.progress()[A], 80, 'higher account evidence wins');
    check(h.progress().__local_observed[A] < 80, 'account progress is never relabeled as local');
    const reloaded = harness(h.st, h.clock.wall + 1);
    await reloaded.api.writeMorningProgress({[A]: 0});
    check(reloaded.progress()[A] > 25, 'local evidence survives a page restart');
  }
  {
    const h = harness(); const m = await h.playing();
    for (const invalid of [NaN, Infinity, 0, -1]) {
      m.duration = invalid; m.currentTime += 20; m.emit('timeupdate');
    }
    equal(h.st.getItem(KEY), null, 'invalid duration produces no history');
    m.duration = 1000; m.paused = true; m.currentTime = 400; m.emit('timeupdate');
    equal(h.st.getItem(KEY), null, 'paused seek produces no history');
    m.paused = false; m.readyState = 1; m.currentTime = 410; m.emit('timeupdate');
    equal(h.st.getItem(KEY), null, 'unready media produces no history');
    m.readyState = 4; h.route(B); m.currentTime = 450; m.emit('timeupdate');
    equal(h.st.getItem(KEY), null, 'a stale video route cannot advance history');
    h.route(A); m.currentSrc = 'blob:different'; m.currentTime = 460; m.emit('timeupdate');
    equal(h.st.getItem(KEY), null, 'replaced media source cannot advance old tracker');
    m.ended = true; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'replaced source cannot fabricate completion');
    m.currentSrc = 'blob:original'; h.state.loggedIn = false; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'signed-out source cannot produce completion');
  }
  {
    const h = harness(); const m = await h.playing();
    m.currentTime = 999.99; m.emit('timeupdate');
    equal(h.state.morningLastEnded, undefined, 'position near duration is not ended');
    m.paused = true; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'ended event without media.ended is not completion');
    m.ended = true; m.currentTime = 1000; m.emit('ended');
    equal(h.state.morningLastEnded.playing, 'ended', 'actual ended flag plus event records completion');
    equal(h.state.morningLastEnded.video_id, A, 'completion retains video identity');
    equal(JSON.parse(h.st.getItem(KEY)).entries[A].percent, 100, 'completion persists 100% immediately');
    h.route(B);
    equal(h.state.morningLastEnded.video_id, A, 'autoplay route change cannot erase completion evidence');
    const saves = h.st.writes.length; m.emit('ended');
    equal(h.st.writes.length, saves, 'stale duplicate end does not rewrite history');
  }
  {
    const h = harness(); const m = await h.playing();
    m.currentTime = 260; m.emit('timeupdate');
    m.currentTime = 1000; m.emit('timeupdate');
    check(JSON.parse(h.st.getItem(KEY)).entries[A].percent < 100, 'ordinary progress writes are batched');
    m.ended = true; m.emit('ended');
    equal(JSON.parse(h.st.getItem(KEY)).entries[A].percent, 100, 'ended flushes an already-observed 100%');
  }
  {
    const entries = {};
    for (let i = 0; i < 2200; i++) entries[String(i).padStart(11, '0')] = {percent: 100, updated_at_ms: 1794421000000 + i};
    entries.bad = {percent: 100, updated_at_ms: 1794421000000};
    entries[A] = {percent: -1, updated_at_ms: 1794421000000};
    entries[B] = {percent: 100, updated_at_ms: 9999999999999};
    const h = harness(storage({[KEY]: JSON.stringify({schema: 1, entries})}));
    await h.api.writeMorningProgress({[A]: 0});
    equal(Object.keys(h.progress().__local_observed).length, 2048, 'loaded history is bounded');
    check(!Object.hasOwn(h.progress().__local_observed, '00000000000'), 'oldest observations are evicted');
    check(Object.hasOwn(h.progress().__local_observed, '00000002199'), 'latest observation is retained');
    check(!Object.hasOwn(h.progress().__local_observed, A), 'negative stored percentage is rejected');
    check(!Object.hasOwn(h.progress().__local_observed, B), 'future stored evidence is rejected');
    const corrupt = harness(storage({[KEY]: '{broken json'}));
    await corrupt.api.writeMorningProgress({[A]: 12});
    equal(corrupt.progress()[A], 12, 'corrupt local storage does not break account progress');
  }
  {
    const h = harness(); const m = await h.playing();
    await h.api.writeMorningProgress({[B]: 12});
    await h.advance(300001);
    m.currentTime = 300; m.emit('timeupdate');
    const p = h.progress();
    equal(p[A], 30, 'new local progress remains publishable');
    check(!Object.hasOwn(p, B), 'new local progress does not refresh expired account evidence');
  }
  {
    const h = harness(); const m = await h.playing();
    m.currentTime = 0.02; m.emit('timeupdate');
    m.currentTime = 0.04; m.emit('timeupdate');
    m.currentTime = 0.06; m.emit('timeupdate');
    check(h.progress().__local_observed[A] > 0, 'frequent small movement accumulates into observed progress');
    const audio = new h.Media(); audio.tagName = 'AUDIO';
    equal(h.api.publishMorningPlaying(A, audio), false, 'audio cannot replace the selected video tracker');
    const next = await h.playing();
    m.currentTime = 1000; m.ended = true; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'a superseded media generation cannot report completion');
    next.currentTime = 260; next.emit('timeupdate');
    check(h.progress()[A] >= 26, 'replacement current media keeps recording progress');
  }
  {
    const h = harness(); h.state.ready = false;
    const m = new h.Media();
    equal(h.context.URL.createObjectURL({}), 'blob:new', 'pending authorization permits inert object URL allocation');
    m.src = 'blob:new'; m.load();
    const played = m.play();
    equal(m.playCalls, 0, 'pending authorization does not call real play');
    equal(m.loadCalls, 0, 'pending authorization defers real load');
    h.state.ready = true;
    await h.advance(100); await played;
    equal(m.playCalls, 1, 'positive authorization still releases queued playback');
    equal(m.loadCalls, 1, 'positive authorization still releases queued load');
    equal(h.state.morningTrackedVideo, A, 'queued playback still becomes the tracked video');
  }
  {
    const h = harness(); h.state.ready = false;
    const m = new h.Media();
    const attempt = m.play().then(() => 'played', () => 'cancelled');
    h.api.setSchoolBannerLease(h.clock.wall + 1000, A, false);
    h.state.ready = true;
    await h.advance(100);
    equal(await attempt, 'cancelled', 'active banner rejects queued play before original method executes');
    equal(m.playCalls, 0, 'authorization queue cannot bypass active banner gate');
    await h.advance(1001);
    equal(m.playCalls, 0, 'releasing banner never resurrects previously queued playback');
  }
  {
    const h = harness(); const m = await h.playing();
    equal(h.api.setSchoolBannerLease(h.clock.wall + 15000, A, true), true, '15-second prearm accepted');
    equal(h.state.schoolBannerLease.active, false, 'prearm allows current episode');
    await m.play();
    m.currentTime = 1000;
    m.emit('ended');
    equal(h.state.schoolBannerLease.active, false, 'prearm does not trigger without actual ended');
    let nextAttempt;
    m.addEventListener('ended', () => {
      h.route(B); const next = new h.Media(); nextAttempt = next.play().then(() => false, () => true);
    });
    m.ended = true; m.emit('ended');
    equal(await nextAttempt, true, 'capture-phase gate rejects autoplay before target end handler');
    equal(h.state.schoolBannerLease.active, true, 'matching actual end activates gate');
    check(m.paused, 'actual end pauses media under active gate');
    equal(h.state.morningLastEnded.video_id, A, 'stored completion survives target route mutation');
    equal(h.api.setSchoolBannerLease(h.clock.wall + 12000, A, true), true, 'awaiting renewal accepted');
    equal(h.state.schoolBannerLease.active, true, 'awaiting renewal cannot undo triggered gate');
    const n = new h.Media(); n.paused = false; n.emit('playing');
    check(n.paused, 'native autoplay playing events are paused too');
    const calls = m.playCalls;
    equal(h.api.setSchoolBannerLease(0, '', false), true, 'explicit zero clears gate');
    equal(h.state.schoolBannerLease, null, 'gate is released immediately');
    equal(m.playCalls, calls, 'release does not resume paused media');
    await n.play();
    check(!n.paused, 'a later explicit play is allowed after release');
  }
  {
    const h = harness(); const m = await h.playing();
    for (const args of [[NaN,A,true],[Infinity,A,true],[-1,A,true],[h.clock.wall,A,true],
      [h.clock.wall+15001,A,true],[h.clock.wall+1000,'invalid',true],[h.clock.wall+1000,A,'true']])
      equal(h.api.setSchoolBannerLease(...args), false, 'invalid or expired lease rejected');
    equal(h.state.schoolBannerLease, undefined, 'invalid leases do not activate a gate');
    equal(h.api.setSchoolBannerLease(h.clock.wall + 1000, A, false), true, 'active lease accepted');
    check(m.paused, 'active lease pauses current media synchronously');
    const calls = m.playCalls;
    await h.advance(1001);
    equal(h.state.schoolBannerLease, null, 'lease expires without controller renewal');
    equal(m.playCalls, calls, 'expiry never resumes playback');
    await m.play();
    check(!m.paused, 'explicit play after expiry works');
    h.api.setSchoolBannerLease(h.clock.wall + 1000, A, false);
    h.clock.wall -= 60000; h.clock.elapsed += 1001;
    await m.play();
    check(!m.paused, 'wall-clock rewind cannot extend a lease beyond monotonic deadline');
  }
  {
    const h = harness(); const m = await h.playing();
    h.api.setSchoolBannerLease(h.clock.wall + 12000, B, true);
    m.currentTime = 1000; m.ended = true; m.emit('ended');
    equal(h.state.schoolBannerLease.active, false, 'wrong-video end cannot activate prearm');
    h.api.setSchoolBannerLease(0);
    await h.advance(5001);
    h.api.setSchoolBannerLease(h.clock.wall + 12000, A, true);
    equal(h.state.schoolBannerLease.active, false, 'old ended receipt cannot activate a new prearm');
    const reloaded = harness(h.st, h.clock.wall + 1);
    equal(reloaded.state.schoolBannerLease, undefined, 'lease does not survive page reload');
    equal(reloaded.state.morningLastEnded, undefined, 'completion receipt does not survive page reload');
  }
  console.log(`${checks} player history / school media gate assertions passed.`);
})().catch(error => { console.error(error); process.exitCode = 1; });
