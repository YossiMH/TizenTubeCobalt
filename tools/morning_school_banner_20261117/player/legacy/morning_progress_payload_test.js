'use strict';
// Pure production payload rules; built-in URLSearchParams remains real.
const assert = require('node:assert/strict');
const guard = require('../../../../x.js');
const s = guard.state;
const at = 1794421475372;
s.morningProgress = {EP2tuoBV184: 21, A2jqHRa7MIs: 0};
s.morningProgressAt = at;
s.morningPlaying = null;
let checks = 0;
function check(value) { assert(value); checks++; }
function payload(now) { return new URLSearchParams(guard.morningStatusPayload(now)); }
check(guard.morningProgressFresh(at));
check(guard.morningProgressFresh(at + 300000));
check(!guard.morningProgressFresh(at + 300001));
check(guard.morningProgressFresh(at - 1000));
check(!guard.morningProgressFresh(at - 1001));
check(payload(at + 70000).get('ms_progress_at') === String(at));
check(payload(at + 90000).get('ms_progress_at') === String(at));
check(JSON.parse(payload(at).get('ms_progress')).EP2tuoBV184 === 21);
check(!payload(at + 300001).has('ms_progress'));
check(!payload(at + 300001).has('ms_progress_at'));
check(payload(at).get('ms_state') === 'idle');
s.morningProgressAt = NaN;
check(!payload(at).has('ms_progress'));
s.morningProgressAt = 0;
check(!payload(at).has('ms_progress'));
s.morningProgressAt = at;
s.morningProgress = {};
check(!payload(at).has('ms_progress'));
console.log(checks + ' real morning-progress payload assertions passed.');
