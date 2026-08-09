// Permanent behavior tests for allowlist boot recovery (self-healing).
// No mocks: real Node HTTP server + real fetch + real timers.
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

(async function main() {
  await testRetryDelaySchedule();
  await testBootSucceedsOnFirstTry();
  await testTransientFailureRecoversWithRetry();
  await testPersistentFailureExhaustsBudgetAndReportsErrors();
  console.log('All TizenTube boot recovery tests passed.');
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
