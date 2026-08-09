# Live-API filter verification (2026-08-09)

How the allowed-only filter was verified against real YouTube API data, in addition to the permanent unit/integration suites.

## Method

1. Fetched a live signed-out INNERTUBE context + API key from the real youtube.com page.
2. Called the real `youtubei/v1/search` endpoint (query: "cooking recipes") with that context.
3. Ran the real committed `x.js` filterTree over the real 1.5 MB response in Node.

## Results

| Scenario | Video references before | After filter |
|---|---|---|
| Guest / signed out (empty allowlist) | 270 | **0** (all stripped, counter 124 dropped renderer nodes) |
| Signed-in allowlist (1 liked video + 1 subscribed channel) | 270 | Only videos from the allowed channel survive (3 videos, all from channel UC4tAgeVdaNB5vD_mBoxg50w); every other video stripped |

The kept videos were all from the allowed channel - exactly the product rule (a video is allowed when it is liked or its channel is subscribed).

## On-device (Onn 4K Pro) activation proof

Cold-start logcat from the installed build:

```
[allowed-only] filter active
[allowed-only] signed out: everything blocked (sign in to watch)
[allowed-only] boot ready (0 liked, 0 subscribed)
```

After sign-in, the same log lines report the account allowlist size, and each API response logs how many videos it stripped.
