'use strict';
const assert = require('assert');
const http = require('http');
const mod = require('../x.js');

function reset() {
  mod.state.liked.clear();
  mod.state.subs.clear();
  mod.state.v2c.clear();
  mod.state.ctx = { client: { clientName: 'test' } };
  mod.state.key = 'test-key';
  mod.state.fetch0 = null;
  mod.state.ready = false;
  mod.state.p = null;
  mod.state.errors = [];
  mod.state.retries = 0;
  mod.state.loggedIn = null;
}

function startServer(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler);
    srv.listen(0, '127.0.0.1', () => resolve({ srv, port: srv.address().port }));
  });
}

function closeServer(srv) {
  return new Promise((resolve) => srv.close(resolve));
}

const likedPayload = { contents: [{ playlistVideoRenderer: { videoId: 'L1' } }] };
const channelsPayload = { contents: [{ channelRenderer: { navigationEndpoint: { browseEndpoint: { browseId: 'UCsub' } } } }] };
const guidePayload = { items: [{ guideSubscriptionsSectionRenderer: { items: [{ guideEntryRenderer: { navigationEndpoint: { browseEndpoint: { browseId: 'UCguide' } } } }] } }] };
const emptyPayload = {};

// Reads the JSON body and answers per-endpoint like a real YouTube API surface.
function okHandler(req, res) {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    let parsed = null;
    try { parsed = JSON.parse(body || '{}'); } catch (e) { parsed = {}; }
    const url = req.url || '';
    res.setHeader('content-type', 'application/json');
    if (url.includes('/guide')) {
      res.end(JSON.stringify(guidePayload));
    } else if (parsed.browseId === 'VLLL') {
      res.end(JSON.stringify(likedPayload));
    } else if (parsed.browseId === 'FEchannels') {
      res.end(JSON.stringify(channelsPayload));
    } else {
      res.end(JSON.stringify({}));
    }
  });
}

// Fails the first N requests then succeeds: models a transient network outage.
function flakyHandler(failuresBeforeSuccess, handler) {
  let failures = 0;
  return (req, res) => {
    if (failures < failuresBeforeSuccess) {
      failures++;
      res.statusCode = 500;
      res.end('boom');
      return;
    }
    handler(req, res);
  };
}

function endpointFailureHandler(failingPath) {
  return (req, res) => {
    if ((req.url || '').includes('/' + failingPath)) {
      res.statusCode = 500;
      res.end('boom');
      return;
    }
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(emptyPayload));
    });
  };
}

async function testBootSucceedsOnFirstTry() {
  reset();
  const { srv, port } = await startServer(okHandler);
  try {
    const base = 'http://127.0.0.1:' + port;
    const result = await mod.bootWithRetry({ apiBase: base, maxAttempts: 3, baseDelayMs: 5 });
    assert.strictEqual(result.ok, true, 'boot must succeed when endpoints are healthy');
    assert.ok(mod.state.liked.has('L1'), 'liked video must be collected');
    assert.ok(mod.state.subs.has('UCsub'), 'subscribed channel must be collected');
    assert.ok(mod.state.subs.has('UCguide'), 'guide subscription must be collected');
    assert.strictEqual(mod.state.retries, 0, 'no retries needed on healthy boot');
  } finally {
    await closeServer(srv);
  }
}

async function testTransientFailureRecoversWithRetry() {
  reset();
  // First 3 requests fail (500), then succeed: transient outage at startup.
  const { srv, port } = await startServer(flakyHandler(3, okHandler));
  try {
    const base = 'http://127.0.0.1:' + port;
    const result = await mod.bootWithRetry({ apiBase: base, maxAttempts: 4, baseDelayMs: 5 });
    assert.strictEqual(result.ok, true, 'boot must recover from transient failure within retry budget');
    assert.ok(mod.state.liked.has('L1'), 'liked video must be collected after recovery');
    assert.ok(mod.state.subs.has('UCsub'), 'subscribed channel must be collected after recovery');
    assert.ok(mod.state.retries >= 1, 'retries must have occurred');
    assert.ok(mod.state.retries < 4, 'must not exhaust the retry budget');
  } finally {
    await closeServer(srv);
  }
}

async function testPersistentFailureExhaustsBudgetAndReportsErrors() {
  reset();
  const { srv, port } = await startServer(flakyHandler(1000, okHandler));
  try {
    const base = 'http://127.0.0.1:' + port;
    const result = await mod.bootWithRetry({ apiBase: base, maxAttempts: 3, baseDelayMs: 5 });
    assert.strictEqual(result.ok, false, 'boot must report failure after exhausting retries');
    assert.strictEqual(mod.state.retries, 3, 'all attempts must be used');
    assert.ok(mod.state.errors.length >= 3, 'each failure must be recorded');
  } finally {
    await closeServer(srv);
  }
}

async function testRetryDelaySchedule() {
  // The delay schedule must back off exponentially and never exceed the cap.
  const sched = mod.retryDelaySchedule(5, 1000, 60000);
  assert.deepStrictEqual(sched, [1000, 2000, 4000, 8000]);
  const capped = mod.retryDelaySchedule(8, 1000, 10000);
  assert.deepStrictEqual(capped, [1000, 2000, 4000, 8000, 10000, 10000, 10000]);
  assert.ok(capped.every((d) => d <= 10000), 'delays must never exceed the cap');
}

async function testHealthyZeroAllowlistReachesReady() {
  reset();
  const { srv, port } = await startServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify(emptyPayload));
  });
  try {
    const result = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + port,
      maxAttempts: 2,
      baseDelayMs: 5,
    });
    assert.strictEqual(result.ok, true, 'healthy API responses are authoritative even when both lists are empty');
    assert.strictEqual(mod.state.loggedIn, true, 'successful account APIs prove the session is signed in');
    assert.strictEqual(mod.state.ready, true, 'a genuine zero allowlist is ready enforcement');
    assert.strictEqual(mod.state.liked.size, 0, 'the account has zero liked videos');
    assert.strictEqual(mod.state.subs.size, 0, 'the account has zero subscriptions');
    assert.strictEqual(mod.state.errors.length, 0, 'genuine zero must not be recorded as an error');
  } finally {
    await closeServer(srv);
  }
}

async function testPartialEmptyEndpointFailureStillRetries() {
  reset();
  const { srv, port } = await startServer(endpointFailureHandler('guide'));
  try {
    const result = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + port,
      maxAttempts: 3,
      baseDelayMs: 5,
    });
    assert.strictEqual(result.ok, false, 'one failed relevant endpoint leaves readiness unproven');
    assert.strictEqual(mod.state.ready, false, 'partial account proof must remain not ready');
    assert.strictEqual(mod.state.retries, 3, 'partial failure must consume the retry budget');
    assert.ok(mod.state.errors.some((message) => message.includes('guide')), 'the failed endpoint must be recorded');
  } finally {
    await closeServer(srv);
  }
}


async function testPartialFailureRetriesUntilLikedFeedLoads() {
  reset();
  // The liked-videos endpoint fails on the first 2 attempts while
  // subscriptions and guide succeed: partial allowlist must not be accepted.
  let likedFailures = 0;
  const partialHandler = (req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      let parsed = null;
      try { parsed = JSON.parse(body || '{}'); } catch (e) { parsed = {}; }
      const url = req.url || '';
      res.setHeader('content-type', 'application/json');
      if (url.includes('/guide')) {
        res.end(JSON.stringify(guidePayload));
      } else if (parsed.browseId === 'VLLL') {
        if (likedFailures < 2) {
          likedFailures++;
          res.statusCode = 500;
          res.end('boom');
        } else {
          res.end(JSON.stringify(likedPayload));
        }
      } else if (parsed.browseId === 'FEchannels') {
        res.end(JSON.stringify(channelsPayload));
      } else {
        res.end(JSON.stringify({}));
      }
    });
  };
  const { srv, port } = await startServer(partialHandler);
  try {
    const base = 'http://127.0.0.1:' + port;
    const result = await mod.bootWithRetry({ apiBase: base, maxAttempts: 4, baseDelayMs: 5 });
    assert.strictEqual(result.ok, true, 'boot must retry until the liked feed loads');
    assert.ok(mod.state.liked.has('L1'), 'liked video must be collected after retry');
    assert.ok(mod.state.subs.has('UCsub'), 'subscribed channel must be collected');
    assert.ok(mod.state.retries >= 1, 'partial failure must trigger retries');
  } finally {
    await closeServer(srv);
  }
}
async function testProvisionalSignedOutRecoversWithRealAccountProof() {
  reset();
  mod.state.loggedIn = false;
  let requests = 0;
  const countingHandler = (req, res) => {
    requests++;
    okHandler(req, res);
  };
  const { srv, port } = await startServer(countingHandler);
  try {
    const result = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + port,
      maxAttempts: 2,
      baseDelayMs: 5,
    });
    assert.strictEqual(result.ok, true, 'successful account data must recover from an early false flag');
    assert.strictEqual(result.guest, undefined, 'real account proof must not report guest mode');
    assert.ok(requests > 0, 'provisional signed-out state must probe the real account');
    assert.strictEqual(mod.state.loggedIn, true, 'successful account data must authenticate the session');
    assert.strictEqual(mod.state.ready, true, 'recovered account data must enable enforcement');
    assert.ok(mod.state.liked.has('L1'), 'liked videos must load after recovery');
    assert.ok(mod.state.subs.has('UCsub'), 'subscriptions must load after recovery');
    assert.ok(mod.state.subs.has('UCguide'), 'guide subscriptions must load after recovery');

    const recoveredFeed = mod.filterTree({ contents: [
      { videoRenderer: { videoId: 'L1', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCother' } } }] } } },
      { videoRenderer: { videoId: 'badRecovered', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCbad' } } }] } } }
    ] });
    assert.deepStrictEqual(recoveredFeed.contents.map((x) => x.videoRenderer.videoId), ['L1']);
  } finally {
    await closeServer(srv);
  }
}

async function testTrueGuestFailsClosedAfterGenuineUnauthenticatedProof() {
  reset();
  mod.state.loggedIn = false;
  const { srv, port } = await startServer((req, res) => {
    res.statusCode = 401;
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ error: { message: 'Login Required' } }));
  });
  try {
    const result = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + port,
      maxAttempts: 2,
      baseDelayMs: 5,
    });
    assert.strictEqual(result.ok, false, 'genuine unauthenticated account APIs must not pretend to succeed');
    assert.strictEqual(result.guest, true, 'HTTP 401 is terminal guest proof');
    assert.strictEqual(mod.state.loggedIn, false, 'guest mode must remain signed out');
    assert.strictEqual(mod.state.ready, true, 'guest fail-closed enforcement must be active');
    assert.strictEqual(mod.state.liked.size, 0, 'guest must never collect liked videos');
    assert.strictEqual(mod.state.subs.size, 0, 'guest must never collect subscriptions');

    const guestFeed = mod.filterTree({ contents: [
      { videoRenderer: { videoId: 'guestVideo', ownerText: { runs: [{ navigationEndpoint: { browseEndpoint: { browseId: 'UCguest' } } }] } } }
    ] });
    assert.strictEqual(guestFeed.contents.length, 0, 'guest discovery must show zero videos');
    const guestPlayer = mod.blockPlayerResponse({ videoDetails: { videoId: 'guestVideo', channelId: 'UCguest' }, streamingData: { formats: [1] } });
    assert.strictEqual(guestPlayer.playabilityStatus.status, 'ERROR', 'guest playback must be blocked');
    assert.strictEqual(guestPlayer.streamingData, undefined, 'guest playback must expose no streams');
  } finally {
    await closeServer(srv);
  }
}
async function testSignInTransitionRebuildsAllowlist() {
  reset();
  mod.state.loggedIn = false;
  const { srv, port } = await startServer((req, res) => {
    res.statusCode = 401;
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ error: { message: 'Login Required' } }));
  });
  try {
    const failedBoot = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + port,
      maxAttempts: 2,
      baseDelayMs: 5,
    });
    assert.strictEqual(failedBoot.ok, false, 'the signed-out phase must fail closed');
    assert.strictEqual(failedBoot.guest, true, 'genuine HTTP unauthentication must establish guest mode');
    assert.strictEqual(mod.state.loggedIn, false, 'guest mode must preserve the account boundary');
    assert.strictEqual(mod.state.ready, true, 'guest mode must enforce an empty allowlist');
    assert.strictEqual(mod.state.liked.size, 0, 'guest mode must not retain liked videos');
    assert.strictEqual(mod.state.subs.size, 0, 'guest mode must not retain subscriptions');
  } finally {
    await closeServer(srv);
  }

  reset();
  const healthy = await startServer(okHandler);
  try {
    const recoveredBoot = await mod.bootWithRetry({
      apiBase: 'http://127.0.0.1:' + healthy.port,
      maxAttempts: 2,
      baseDelayMs: 5,
    });
    assert.strictEqual(recoveredBoot.ok, true, 'a subsequent authenticated boot must succeed');
    assert.strictEqual(recoveredBoot.guest, undefined, 'real account data must clear the guest result');
    assert.strictEqual(mod.state.loggedIn, true, 'real account data must mark sign-in');
    assert.strictEqual(mod.state.ready, true, 'recovery must enable enforcement');
    assert.ok(mod.state.liked.has('L1'), 'sign-in must rebuild liked videos');
    assert.ok(mod.state.subs.has('UCsub'), 'sign-in must rebuild subscriptions');
    assert.ok(mod.state.subs.has('UCguide'), 'sign-in must rebuild guide subscriptions');
  } finally {
    await closeServer(healthy.srv);
  }
}

async function testMediaGateTransitionsAcrossBootRetryGuestAndRebuild() {
  const savedLocation = globalThis.location;
  const savedMediaElement = globalThis.HTMLMediaElement;
  const savedCreateObjectURL = URL.createObjectURL;
  class HostMediaElement {
    constructor() { this._src = ''; }
    get src() { return this._src; }
    set src(value) { this._src = String(value); }
    load() {}
    play() { return Promise.resolve('played'); }
  }
  const failures = [];
  const check = (label, fn) => { try { fn(); } catch (e) { failures.push(label + ' -> ' + e.message); } };
  const checkAsync = async (label, fn) => { try { await fn(); } catch (e) { failures.push(label + ' -> ' + e.message); } };
  const stateAtStart = mod.state;
  try {
    let blobCounter = 0;
    URL.createObjectURL = function createObjectURL(input) {
      if (!(input instanceof Blob)) throw new TypeError('createObjectURL requires a Blob');
      blobCounter += 1;
      return 'blob:fixture-' + blobCounter;
    };
    globalThis.HTMLMediaElement = HostMediaElement;
    if (typeof mod.installMediaGuard !== 'function') {
      failures.push('installMediaGuard must be exported -> missing export');
    }
    if (typeof mod.installMediaGuard === 'function') mod.installMediaGuard();

    // Pending: a slow boot holds every watch route fail-closed while the
    // allowlist is still loading.
    reset();
    const { srv: slowSrv, port: slowPort } = await startServer((req, res) => {
      setTimeout(() => {
        res.statusCode = 500;
        res.end('boom');
      }, 40);
    });
    try {
      globalThis.location = new URL('https://www.youtube.com/watch?v=retryProbe');
      const retryingBoot = mod.bootWithRetry({
        apiBase: 'http://127.0.0.1:' + slowPort,
        maxAttempts: 2,
        baseDelayMs: 5,
      });
      check('boot-in-flight route reports pending', () => {
        assert.strictEqual(mod.state.booting, true);
        assert.strictEqual(mod.mediaDecision('retryProbe'), 'pending');
        assert.strictEqual(mod.state.mediaGate, 'pending');
      });
      await checkAsync('pending boot rejects playback without touching native play', async () => {
        const pendingEl = new HostMediaElement();
        let error = null;
        try { await pendingEl.play(); } catch (e) { error = e; }
        assert.ok(error, 'play must reject while pending');
        assert.strictEqual(error.message, mod.BLOCK_REASON,
          'expected BLOCK_REASON, got ' + JSON.stringify(error && error.message));
      });
      const exhausted = await retryingBoot;
      check('exhausted boot retry lands closed without reload', () => {
        assert.strictEqual(exhausted.ok, false);
        assert.strictEqual(mod.state.ready, false);
        assert.strictEqual(mod.state.booting, false);
        assert.strictEqual(mod.mediaDecision('retryProbe'), 'closed');
        assert.strictEqual(mod.state.mediaGate, 'closed');
      });
    } finally {
      await closeServer(slowSrv);
    }

    // Genuine guest: terminal 401 proof keeps every route closed even though
    // ready flips on for fail-closed enforcement.
    reset();
    mod.state.loggedIn = false;
    const { srv: guestSrv, port: guestPort } = await startServer((req, res) => {
      res.statusCode = 401;
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ error: { message: 'Login Required' } }));
    });
    try {
      globalThis.location = new URL('https://www.youtube.com/watch?v=guestVideo');
      const guest = await mod.bootWithRetry({
        apiBase: 'http://127.0.0.1:' + guestPort,
        maxAttempts: 1,
        baseDelayMs: 5,
      });
      check('genuine guest stays closed at the media boundary', () => {
        assert.strictEqual(guest.guest, true);
        assert.strictEqual(mod.state.loggedIn, false);
        assert.strictEqual(mod.state.ready, true);
        assert.strictEqual(mod.mediaDecision('guestVideo'), 'closed');
        assert.strictEqual(mod.state.mediaGate, 'closed');
      });
    } finally {
      await closeServer(guestSrv);
    }

    // Successful authenticated rebuild flips the same live module to open for
    // authorized IDs with no page reload in between.
    reset();
    const { srv: healthySrv, port: healthyPort } = await startServer(okHandler);
    try {
      const rebuilt = await mod.bootWithRetry({
        apiBase: 'http://127.0.0.1:' + healthyPort,
        maxAttempts: 2,
        baseDelayMs: 5,
      });
      assert.strictEqual(rebuilt.ok, true);
      assert.ok(mod.state.liked.has('L1'));
      setWatchLocationForRebuild();
      await checkAsync('rebuilt authorization opens liked playback immediately', async () => {
        assert.strictEqual(mod.mediaDecision('L1'), 'open');
        assert.strictEqual(mod.state.mediaGate, 'open');
        const openEl = new HostMediaElement();
        const blobUrl = URL.createObjectURL(new Blob(['ok']));
        openEl.src = blobUrl;
        assert.strictEqual(openEl.src, blobUrl);
        openEl.load();
        assert.strictEqual(await openEl.play(), 'played');
      });
      check('the rebuilt gate reuses one live module state with no reload', () => {
        assert.strictEqual(mod.state, stateAtStart, 'no reload may replace the module instance');
      });
    } finally {
      await closeServer(healthySrv);
    }
  } finally {
    globalThis.location = savedLocation;
    if (savedMediaElement === undefined) delete globalThis.HTMLMediaElement;
    else globalThis.HTMLMediaElement = savedMediaElement;
    URL.createObjectURL = savedCreateObjectURL;
  }
  function setWatchLocationForRebuild() {
    globalThis.location = new URL('https://www.youtube.com/watch?v=L1');
  }
  if (failures.length) throw new Error('media gate transition failures:\n' + failures.join('\n'));
}
(async function main() {
  await testRetryDelaySchedule();
  await testHealthyZeroAllowlistReachesReady();
  await testPartialEmptyEndpointFailureStillRetries();
  await testBootSucceedsOnFirstTry();
  await testTransientFailureRecoversWithRetry();
  await testPersistentFailureExhaustsBudgetAndReportsErrors();
  await testPartialFailureRetriesUntilLikedFeedLoads();
  await testProvisionalSignedOutRecoversWithRealAccountProof();
  await testTrueGuestFailsClosedAfterGenuineUnauthenticatedProof();
  await testSignInTransitionRebuildsAllowlist();
  await testMediaGateTransitionsAcrossBootRetryGuestAndRebuild();
  console.log('All TizenTube boot recovery tests passed.');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
