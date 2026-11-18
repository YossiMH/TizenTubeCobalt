'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
// In the repository this test lives at tools/morning_school_banner_20261118/player/.
const source = fs.readFileSync(process.argv[2] || require('node:path').resolve(__dirname, '../../../x.js'), 'utf8');
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
  async function flush() { for (let i = 0; i < 40; i++) await Promise.resolve(); }
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


function request(h, id='wake-run') {
  return {run_id:id, started_at_ms:h.clock.wall, deadline_ms:h.clock.wall+300000};
}
async function step(h, ms) { for(let n=0;n<ms;n+=250) await h.advance(Math.min(250,ms-n)); await h.flush(); }
(async function main(){
  {
    const h=harness();prepareFetch(h,[page([card(A,49),card(B,100)])]);
    await h.api.writeMorningProgress(envelope(h));const old=h.state.morningProgressAt;
    await step(h,10000);const run=request(h);
    equal(h.api.requestMorningProgress(run),true,'warm helper handshake accepted without a focus event');
    equal(h.api.morningHistoryForRun(run),null,'fresh-for-age snapshot predating this run is insufficient');
    await step(h,1000);
    const snapshot=h.api.morningHistoryForRun(run);
    check(snapshot&&snapshot.__youtube_history.captured_at_ms>=run.started_at_ms,'handshake returns new complete account capture');
    check(snapshot.__youtube_history.captured_at_ms>old,'cached progress was refetched rather than retimestamped');
    equal(snapshot.__youtube_history.progress[A],49,'actual account percentage preserved');
    snapshot.__youtube_history.progress[A]=0;
    equal(h.api.morningHistoryForRun(run).__youtube_history.progress[A],49,'bridge accessor returns an immutable copy');
    equal(h.api.morningHistoryForRun({...run,run_id:'other'}),null,'other run cannot read cached proof');
    equal(h.api.requestMorningProgress({...run,deadline_ms:run.deadline_ms+1}),false,'same run cannot extend its original deadline');
    const markerlessDial='<service><additionalData><screenId>ordinary</screenId></additionalData></service>';
    check(!markerlessDial.includes('ms_progress')&&h.api.morningHistoryForRun(run)!==null,'provider DIAL overwrite does not affect direct bridge delivery');
  }
  {
    const h=harness();h.state.ready=false;h.state.loggedIn=null;
    h.api.refreshMorningProgressOnFocus();await step(h,1000);
    check(h.state.morningHistoryWindow&&!h.state.morningHistoryWindow.closed,'foreground event survives pre-auth readiness');
    const calls=prepareFetch(h,[page([card(A,49)])]);h.state.ready=true;h.state.loggedIn=true;
    await step(h,1500);
    equal(calls.length,1,'authentication settling triggers bounded automatic refresh');
    equal(h.state.morningHistoryStatus.phase,'complete','warm wake finishes without a second focus event');
  }
  {
    const h=harness();const calls=prepareFetch(h,[Error('browse 401'),page([card(A,49)])]);
    h.api.requestMorningProgress(request(h));await step(h,1000);
    equal(h.state.morningHistoryStatus.reason,'morning_history_http_401','first failure has a safe diagnostic reason');
    h.state.hdr.authorization='rotated-authorized-session';
    await step(h,3000);
    equal(calls.length,2,'one-shot auth failure is retried after auth stabilizes');
    equal(h.state.morningHistoryStatus.phase,'complete','bounded retry recovers account history');
    equal(h.state.morningHistoryStatus.attempts,2,'attempt count records recovery');
  }
  {
    const h=harness();const calls=prepareFetch(h,[page([card(A,49)],'old-next'),()=>{
      h.state.hdr.authorization='changed-account';return {ok:true,json:async()=>page([card(B,100)])};
    },page([card(A,100)],'new-next'),page([card(B,10)])]);
    const run=request(h);h.api.requestMorningProgress(run);await step(h,1000);
    equal(h.state.morningHistoryStatus.reason,'morning_history_account_changed','mid-pagination auth rollover is classified');
    equal(h.api.morningHistoryForRun(run),null,'mixed-account partial pages never become proof');
    await step(h,3000);
    const history=h.api.morningHistoryForRun(run).__youtube_history;
    equal(calls.length,4,'rollover restarts exact playlist from page one');
    equal(history.progress[A],100,'stale first-account page was discarded');
    equal(history.progress[B],10,'new account partial percentage is authoritative');
  }
  {
    const h=harness();const calls=prepareFetch(h,Array(6).fill(Error('browse 503')));
    const run=request(h);h.api.requestMorningProgress(run);await step(h,65000);
    equal(calls.length,6,'network retry count is bounded');
    check(h.state.morningHistoryWindow.closed,'failed refresh window terminates');
    equal(h.api.morningHistoryForRun(run),null,'exhausted retries expose no account snapshot');
    h.api.requestMorningProgress(run);await step(h,5000);
    equal(calls.length,6,'repeated controller polling cannot reopen exhausted same-run window');
  }
  {
    const h=harness();h.state.ready=false;h.state.loggedIn=null;
    const run=request(h);h.api.requestMorningProgress(run);await step(h,61000);
    equal(h.state.morningHistoryStatus.reason,'morning_history_retry_deadline','pre-auth waiting is deadline bounded');
    equal(h.api.morningHistoryForRun(run),null,'no auth evidence means no history');
  }
  {
    const h=harness();prepareFetch(h,[page([card(A,49)])]);
    const fetch=h.state.fetch0;h.state.fetch0=(url,init)=>url==='http://localhost:8012/apps/YouTube'?new Promise(()=>{}):fetch(url,init);
    const run=request(h);h.api.requestMorningProgress(run);await step(h,6000);
    check(h.api.morningHistoryForRun(run)!==null,'hung optional DIAL writes cannot stall direct account delivery');
  }
  {
    const h=harness();const calls=prepareFetch(h,[page([card(A,49)])]);
    const run=request(h);h.context.document.hidden=true;
    equal(h.api.requestMorningProgress(run),false,'hidden player does not admit or consume a refresh window');
    equal(h.state.morningHistoryRequest,undefined,'hidden handshake does not bind the pending run');
    await step(h,65000);h.context.document.hidden=false;
    equal(h.api.requestMorningProgress(run),true,'first visible handshake retains a full bounded retry window');
    await step(h,1000);equal(calls.length,1,'no account request was spent while hidden');
    check(h.api.morningHistoryForRun(run),'visible snapshot is available');
    h.context.document.hidden=true;
    equal(h.api.morningHistoryForRun(run),null,'hidden player cannot furnish fresh foreground proof');
  }
  {
    const h=harness();const calls=prepareFetch(h,[page([card(A,49)]),page([card(A,100),card(B,10)])]);
    const run=request(h);h.api.requestMorningProgress(run);await step(h,1000);
    check(h.api.morningHistoryForRun(run),'first account snapshot completes');
    await step(h,70000);h.state.hdr.authorization='new-account-after-success';
    equal(h.api.morningHistoryForRun(run),null,'completed snapshot is invalid after account rotation');
    h.api.requestMorningProgress(run);await step(h,1000);
    const history=h.api.morningHistoryForRun(run).__youtube_history;
    equal(calls.length,2,'post-success account rotation refetches within original admitted deadline');
    equal(history.progress[A],100,'new account eligibility replaces previous account snapshot');
    equal(h.state.morningHistoryRequest.attempts,2,'post-success refresh retains cumulative attempt budget');
    equal(h.state.morningHistoryRequest.deadline_ms,run.deadline_ms,'post-success refresh cannot extend admitted deadline');
  }
  {
    const h=harness();prepareFetch(h,[page([card(A,49)]),page([card(B,10)])]);
    const fetch=h.state.fetch0;let release,held=false;
    h.state.fetch0=(url,init)=>{
      if(url==='http://localhost:8012/apps/YouTube'&&h.state.morningProgress&&!held){
        held=true;return new Promise(resolve=>{release=resolve});
      }
      return fetch(url,init);
    };
    const old=request(h,'old-run');h.api.requestMorningProgress(old);await step(h,1000);
    check(typeof release==='function','test holds final optional DIAL acknowledgement');
    h.clock.wall++;h.clock.elapsed++;
    const newer=request(h,'new-run');h.api.requestMorningProgress(newer);
    release({ok:true});await h.flush();
    check(h.state.morningHistoryStatus.phase!=='complete','superseded DIAL completion cannot overwrite new-run diagnostic');
    equal(h.api.morningHistoryForRun(newer),null,'superseded completion cannot provide new-run proof');
    await step(h,1500);check(h.api.morningHistoryForRun(newer),'new run eventually fetches its own complete evidence');
  }
  {
    const h=harness(),m=await h.playing();
    equal(h.api.setMorningStartupLease(h.clock.wall+12000,''),true,'warming lease accepts empty expected video');
    equal(m.paused,true,'warming pauses existing browser media');
    const other=new h.Media(),blocked=other.play();let settled=false;blocked.then(()=>{settled=true});
    await h.flush();equal(settled,false,'warming retains only the intercepted play request');
    equal(other.playCalls,0,'voice/autoplay attempt never reaches native play while warming');
    h.api.setMorningStartupLease(h.clock.wall+12000,A);await blocked;
    equal(other.playCalls,1,'selected candidate still passes normal media authorization');
    h.route(B);await assert.rejects(other.play(),/Morning episode is starting/);checks++;
    h.route(A);h.api.setSchoolBannerLease(h.clock.wall+12000,A,false);
    await assert.rejects(other.play(),/Morning school banner/);checks++;
    h.api.setSchoolBannerLease(0,A,false);h.api.setMorningStartupLease(0,'');
    const before=other.playCalls;await step(h,1000);equal(other.playCalls,before,'lease release never auto-resumes');
    for(const args of [[h.clock.wall+15001,A],[h.clock.wall+1000,null],[NaN,A],[h.clock.wall,'']]){
      equal(h.api.setMorningStartupLease(...args),false,'invalid startup lease rejected');
    }
  }
  {
    const h=harness(),m=await h.playing();
    equal(h.api.setSchoolBannerLease(h.clock.wall+12000,'',true),false,'awaiting-end lease cannot omit the selected episode');
    equal(h.api.setSchoolBannerLease(h.clock.wall+12000,'',false),true,'active fallback reminder explicitly accepts an unbound lease');
    equal(m.paused,true,'active fallback immediately pauses existing media');
    const other=new h.Media();await assert.rejects(other.play(),/Morning school banner/);checks++;
    equal(other.playCalls,0,'unbound active reminder blocks all attempted browser playback');
    h.api.setSchoolBannerLease(0,'',false);
    equal(other.playCalls,0,'dismissal never resumes media automatically');
  }
  {
    const h=harness();h.api.setMorningStartupLease(h.clock.wall+1000,'');await step(h,1001);
    const m=await h.playing();equal(m.playCalls,1,'expired startup lease fails open without replaying queued attempts');
    h.api.setMorningStartupLease(h.clock.wall+1000,'');const calls=m.playCalls;
    h.clock.wall-=100000;await step(h,1001);
    equal(m.playCalls,calls,'monotonic expiry never starts media');
    await m.play();equal(m.playCalls,calls+1,'wall-clock rollback cannot extend startup lease');
  }
  console.log(checks+' foreground/history handshake and startup lease assertions passed.');
})().catch(e=>{console.error(e);process.exitCode=1});
