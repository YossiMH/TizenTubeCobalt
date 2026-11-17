# Morning playback history and school-banner media lease

Base: the actual installed Guard player asset, SHA-256
`1fe2154764b819ad81036d04882cca104f3c28787b789b8b465cdfc23546a3a3`.
The repository root `x.js` retains the existing allowlist, authorization, pending-media startup,
playback confirmation, and feature code. The tests in this directory import that single source. Deployment is pending live acceptance.

## Controller interface

```javascript
window.__ttAllowedOnly.setSchoolBannerLease(deadlineMs, expectedVideoId, awaitingEnd)
```

Returns `true` only for accepted calls. A nonzero deadline must be finite, in the
future, and at most 15 seconds away. Video ID must contain exactly 11 URL-safe
YouTube ID characters; `awaitingEnd` must be a boolean. The controller renews
the lease while its authorized banner session is waiting or active.

- `awaitingEnd=true`: leaves the episode running and activates only upon an
  actual matching media `ended` event. A matching genuine receipt from the last
  five seconds permits late arming. Renewal does not demote an already active
  lease for the same video.
- `awaitingEnd=false`: pauses tracked and document media immediately; subsequent
  play calls and native play/playing events are blocked until release/expiry.
- `deadlineMs=0`: releases immediately; other arguments are ignored.
- Expiry checks both wall and monotonic time. Neither release nor expiry resumes
  media or revives rejected/queued play calls. Leases are never persisted.

A document capture listener records true end before target autoplay handlers:

```javascript
window.__ttAllowedOnly.state.morningLastEnded
// {video_id, position_seconds, duration_seconds, playing:'ended', sample_wall_ms}
```

The controller must enforce the five-second freshness window. The receipt
remains available when autoplay changes the route. Pause, near-end position,
unready media, stale route/source/generation, and signed-out playback do not
create an ended receipt.

## Progress protocol

`ms_progress` keeps numeric video-ID entries and adds a reserved
`__local_observed` numeric map. Each top-level percentage is the greater of fresh
account progress and locally observed progress. Local provenance is never
derived from account markers. Account observations expire after five minutes;
newer local observations do not refresh their age.

Local storage key: `ttMorningObservedProgressV1`, schema 1. Up to 2,048 entries
are retained by last observation time. Only advancing authorized current video
or actual completion records progress. Values never decrease. Crossing from
at most 25% to more than 25%, the first observation, and actual completion are
persisted synchronously; other increases are batched at 15 seconds. Actual
completion flushes 100% even when the last timeupdate already reached 100%.

## Verification

```sh
node player_morning_test.js
node legacy/media_startup_policy_test.js
node legacy/morning_progress_payload_test.js
node legacy/tizentube_morning_playback_test.js
```

The new tests execute this actual production source in Node VM contexts with
explicit clocks, localStorage, media objects, and event ordering. They cover
history boundaries, reload, monotonicity, invalid/stale sources, bounded storage,
stale account expiry, capture-before-autoplay, lease validation/renewal/expiry,
clock rewind, release without resume, and the existing pending-authorization
queue. No external network requests are made. Legacy tests retain their original
assertions; only the module import points at this candidate.
