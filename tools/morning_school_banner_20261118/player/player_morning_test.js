'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').resolve(__dirname, '../../../x.js'), 'utf8');
const A = 'aaaaaaaaaaa', B = 'bbbbbbbbbbb';
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

const PLAYLIST = 'PLxKlPmCFYxy9y498dCB1tIddaxZz3OQCA';
function envelope(h, progress = {[A]: 49, [B]: 100}) {
  return {__youtube_history: {schema: 1, source: 'youtube_playlist', playlist_id: PLAYLIST,
    captured_at_ms: h.clock.wall, signed_in: true, complete: true,
    covered_ids: Object.keys(progress), progress}};
}
function card(id, percent) {
  const overlays = [{thumbnailOverlayTimeStatusRenderer: {text: {simpleText: '58:37'}}}];
  if (percent !== undefined) overlays.unshift({thumbnailOverlayResumePlaybackRenderer: {percentDurationWatched: percent}});
  return {tileRenderer: {contentType: 'TILE_CONTENT_TYPE_VIDEO', contentId: id,
    header: {tileHeaderRenderer: {thumbnailOverlays: overlays}},
    onSelectCommand: {watchEndpoint: {videoId: id, playlistId: PLAYLIST}}}};
}
function page(cards, token) {
  return {responseContext: {serviceTrackingParams: [{params: [
    {key: 'logged_in', value: '1'}, {key: 'browse_id', value: 'VL' + PLAYLIST}]}]},
    contents: {playlistVideoListRenderer: {contents: cards,
      continuations: token ? [{nextContinuationData: {continuation: token}}] : []}}};
}
function prepareFetch(h, responses) {
  h.state.ctx = {client: {clientName: 'TVHTML5'}};
  h.state.hdr = {authorization: 'synthetic-test-value'};
  const calls = [];
  h.state.fetch0 = async (url, init) => {
    if (url === 'http://localhost:8012/apps/YouTube') {
      h.posts.push(new URLSearchParams(init.body)); return {ok: true};
    }
    calls.push(JSON.parse(init.body));
    if (!responses.length) throw Error('unexpected request');
    const response = responses.shift();
    if (response instanceof Error) throw response;
    if (typeof response === 'function') return response();
    return {ok: true, json: async () => response};
  };
  return calls;
}

(async function main() {
  {
    const h = harness(storage({[KEY]: JSON.stringify({schema: 1, entries: {[A]: {percent: 100, updated_at_ms: 1794421400000}}})}));
    const m = await h.playing();
    await h.api.writeMorningProgress(envelope(h, {[A]: 0, [B]: 49}));
    equal(h.progress().__youtube_history.progress[A], 0, 'actual account progress is authoritative over legacy local history');
    equal(h.progress().__youtube_history.progress[B], 49, 'partial account progress is preserved exactly');
    equal(Object.keys(h.progress()).join(','), '__youtube_history', 'only authenticated envelope is published');
    m.currentTime = 800; m.emit('timeupdate'); await h.api.writeMorningPlaying(A);
    equal(h.progress().__youtube_history.progress[A], 0, 'measured playback cannot change account history');
    equal(h.st.writes.length, 0, 'player no longer persists substitute viewing history');
    m.currentTime = 1000; m.ended = true; m.emit('ended');
    equal(h.state.morningLastEnded.video_id, A, 'actual completion still drives school reminder');
    equal(h.progress().__youtube_history.progress[A], 0, 'completion does not fabricate a YouTube percentage');
    const at = h.state.morningProgressAt;
    await h.advance(300001);
    equal(h.api.morningProgressFresh(h.clock.wall), false, 'account history expires after five minutes');
    equal(h.state.morningProgressAt, at, 'playback never retimestamps account evidence');
    equal(Object.keys(h.progress()).length, 0, 'expired history is omitted from status');
    equal(await h.api.writeMorningProgress({[A]: 0, __local_observed: {[A]: 100}}), false, 'legacy flat/local payload is rejected');
  }
  {
    const h = harness(); const good = envelope(h).__youtube_history;
    check(h.api.validMorningHistory(good, h.clock.wall), 'complete fresh envelope accepted');
    for (const patch of [{complete: false}, {signed_in: false}, {source: 'local'}, {playlist_id: 'other'},
      {captured_at_ms: h.clock.wall + 1}, {captured_at_ms: h.clock.wall - 300001},
      {covered_ids: [A, A]}, {covered_ids: [A]}, {progress: {[A]: -1, [B]: 100}},
      {progress: {[A]: '49', [B]: 100}}, {progress: {[A]: 49, [B]: 101}}])
      equal(h.api.validMorningHistory({...good, ...patch}, h.clock.wall), false, 'invalid/incomplete envelope rejected');
    const payload = envelope(h); await h.api.writeMorningProgress(payload);
    payload.__youtube_history.progress[A] = 0;
    equal(h.progress().__youtube_history.progress[A], 49, 'published snapshot is immutable to caller mutation');
  }
  {
    const h = harness(); const data = h.api.collectMorningProgress(page([card(A, 49), card(B)]));
    equal(data[A], 49, 'recognized account marker parsed');
    equal(data[B], 0, 'recognized signed-in scoped card with no marker is unstarted');
    for (const percent of [null, '49', NaN, -1, 101]) {
      assert.throws(() => h.api.collectMorningProgress(page([card(A, percent)]))); checks++;
    }
    const signedOut = page([card(A)]); signedOut.responseContext.serviceTrackingParams[0].params[0].value = '0';
    assert.throws(() => h.api.collectMorningProgress(signedOut)); checks++;
    const wrongScope = page([card(A)]); wrongScope.responseContext.serviceTrackingParams[0].params[1].value = 'VLother';
    assert.throws(() => h.api.collectMorningProgress(wrongScope)); checks++;
    const wrongCard = page([card(A)]); wrongCard.contents.playlistVideoListRenderer.contents[0].tileRenderer.onSelectCommand.watchEndpoint.playlistId = 'other';
    assert.throws(() => h.api.collectMorningProgress(wrongCard)); checks++;
    const futureRenderer = page([card(A)]); futureRenderer.contents.playlistVideoListRenderer.contents[0].tileRenderer.resumePlaybackProgressViewModel = {percent: 80};
    assert.throws(() => h.api.collectMorningProgress(futureRenderer)); checks++;
    assert.throws(() => h.api.collectMorningProgress(page([card(A, 10), card(A, 90)]))); checks++;
    assert.throws(() => h.api.collectMorningProgress(page([]))); checks++;
  }
  {
    const h = harness(); const calls = prepareFetch(h, [page([card(A, 49)], 'next'), page([card(B, 100)])]);
    const result = await h.api.publishMorningProgress({apiBase: 'https://www.youtube.com'});
    equal(calls.length, 2, 'all continuation pages requested');
    equal(calls[0].browseId, 'VL' + PLAYLIST, 'exact user playlist requested');
    equal(calls[1].continuation, 'next', 'continuation token consumed');
    equal(result.covered_ids.length, 2, 'complete snapshot contains every page');
    equal(result.progress[A], 49, 'account progress survives publication');
    equal(h.state.morningHistoryStatus.phase, 'complete', 'completed retrieval reported');
    equal(h.posts.length, 2, 'refresh publishes invalidation then one complete snapshot');
    equal(h.posts[0].get('ms_progress'), '{}', 'refresh immediately invalidates previous bridge evidence');
    h.state.hdr.authorization = 'another-account';
    h.api.statusTick(); await h.flush();
    equal(h.api.morningProgressFresh(h.clock.wall), false, 'account switch invalidates an already completed snapshot');
    equal(h.posts.at(-1).get('ms_progress'), '{}', 'idle status tick clears persisted bridge evidence after account switch');
    equal(Object.keys(h.progress()).length, 0, 'account switch clears bridge progress');
    equal(h.state.morningHistoryStatus.phase, 'unavailable', 'account switch requires fresh retrieval');
  }
  {
    const h = harness(); await h.api.writeMorningProgress(envelope(h));
    prepareFetch(h, [page([card(A, 49)], 'next'), Error('network failure')]);
    equal(await h.api.publishMorningProgress({apiBase: 'https://www.youtube.com'}), null, 'failed continuation rejects whole snapshot');
    equal(h.state.morningHistoryStatus.phase, 'unavailable', 'retrieval failure remains explicit');
    equal(Object.keys(h.progress()).length, 0, 'partial/failing history cannot appear unwatched');
    equal(h.posts.at(-1).get('ms_progress'), '{}', 'failure clears previous bridge selection snapshot');
  }
  {
    const h = harness(); prepareFetch(h, [page([card(A)], 'repeat'), page([card(B)], 'repeat')]);
    equal(await h.api.publishMorningProgress({apiBase: 'https://www.youtube.com'}), null, 'repeated continuation is incomplete, never success');
    const changed = harness(); prepareFetch(changed, [() => { changed.state.hdr.authorization = 'changed-account'; return {ok: true, json: async () => page([card(A)])}; }]);
    equal(await changed.api.publishMorningProgress({apiBase: 'https://www.youtube.com'}), null, 'account change during fetch invalidates evidence');
  }
  {
    const h = harness(); await h.api.writeMorningProgress(envelope(h));
    let release;
    prepareFetch(h, [() => new Promise(resolve => {release = () => resolve({ok: true, json: async () => page([card(A, 49)])});})]);
    const pending = h.api.publishMorningProgress({apiBase: 'https://www.youtube.com'});
    await h.flush();
    equal(h.posts.at(-1).get('ms_progress'), '{}', 'bridge is already invalidated while network refresh is pending');
    release(); await pending;
    check(JSON.parse(h.posts.at(-1).get('ms_progress')).__youtube_history.complete, 'only completed refresh restores bridge selection');
  }
  {
    for (const continuation of [
      {continuationItemRenderer: {continuationEndpoint: {continuationCommand: {token: 12}}}},
      {continuationItemRenderer: {continuationEndpoint: {}}},
      {continuationItemViewModel: {token: 'unrecognized'}},
      {nextContinuationData: {continuation: ''}},
      {reloadContinuationData: {continuation: 'unexpected'}}
    ]) {
      const h = harness(), broken = page([card(A, 49)]);
      broken.contents.playlistVideoListRenderer.contents.push(continuation);
      prepareFetch(h, [broken]);
      equal(await h.api.publishMorningProgress({apiBase: 'https://www.youtube.com'}), null, 'unreadable continuation cannot falsely complete history');
      equal(h.posts.at(-1).get('ms_progress'), '{}', 'malformed pagination clears persisted bridge snapshot');
    }
  }
  {
    const h = harness(); const m = await h.playing();
    m.currentSrc = 'blob:replaced'; m.ended = true; m.currentTime = 1000; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'replaced media cannot fabricate episode completion');
    m.currentSrc = 'blob:original'; h.state.loggedIn = false; m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'signed-out source cannot report completion');
    h.state.loggedIn = true; const next = await h.playing(); m.emit('ended');
    equal(h.state.morningLastEnded, undefined, 'superseded media generation cannot report completion');
    const audio = new h.Media(); audio.tagName = 'AUDIO';
    equal(h.api.publishMorningPlaying(A, audio), false, 'audio cannot replace selected episode tracker');
    next.currentTime = 1000; next.ended = true; next.emit('ended');
    equal(h.state.morningLastEnded.video_id, A, 'current actual ended event remains authoritative for reminder');
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
  console.log(`${checks} account history / school media gate assertions passed.`);
})().catch(error => { console.error(error); process.exitCode = 1; });
