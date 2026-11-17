'use strict';
// Pure production decision rules. No external APIs or timers are replaced.
const assert = require('node:assert/strict');
const guard = require('../../../../x.js');
const state = guard.state;
const video = 'EP2tuoBV184';
const owner = 'UC7DM7591Xkt1M2D3fs_wAlg';

Object.assign(state, {ready: false, loggedIn: false, booting: true});
assert.equal(guard.mediaDecision(video), 'pending', 'early login config is not a finished permission decision');
Object.assign(state, {ready: true, loggedIn: true, booting: false});
assert.equal(guard.mediaDecision(video), 'pending', 'ready account plus unknown owner must not abort player construction');
state.v2c.set(video, owner);
assert.equal(guard.mediaDecision(video), 'closed', 'known unsubscribed owner remains blocked');
state.subs.add(owner);
assert.equal(guard.mediaDecision(video), 'open', 'positive subscribed-owner evidence permits media');
state.loggedIn = false;
assert.equal(guard.mediaDecision(video), 'closed', 'signed out account cannot use previous owner evidence');
state.loggedIn = true;
state.subs.clear();
state.liked.add(video);
assert.equal(guard.mediaDecision(video), 'open', 'positive liked-video membership still permits media');
state.ready = false;
assert.equal(guard.mediaDecision(video), 'pending', 'old membership cannot permit media while refreshing');
console.log('Seven real media-startup decision assertions passed.');
