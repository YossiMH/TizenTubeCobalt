# Morning Sesame version 6

The existing 07:00 Sunday-Friday schedule, Diaspora Yom Tov exclusions, videos longer than 30 minutes, account progress preference, and local-history fallback remain unchanged.

Install the helper with a TizenSub+ APK pinned to the matching x.js revision. A source commit alone does not update an installed APK. Preserve sign-in and app data; never uninstall or clear data to make an update fit.

Completion requires two fresh observations of the selected authorized video with increasing timestamps and playback position. A resolved play promise, an opened app, a paused or ended video, a frozen position, or stale evidence is not success. Brief buffering and pauses retain observation. Progress and playback evidence share one status payload. Old callbacks cannot publish into a later playback generation. Only confirmed playback updates played history. Direct/manual starts also initialize the run and schedule confirmation.

Run node tools/tizentube_morning_playback_test.js plus the other tools/tizentube_*test.js suites. Compile PlaybackEvidence.java with tests/PlaybackEvidenceTest.java and run dev.yossi.morningsesame.PlaybackEvidenceTest. These are code tests, not proof of physical-TV warm or cold startup.

Live acceptance: verify installed APK hashes; test a real alarm from TV sleep with TizenSub+ stopped; repeat with TizenSub+ already open and with a prior episode paused or ended. Require fresh Playback confirmed logs, independently advancing media position, and visible video. Restore and verify the regular morning alarm. Do not remove account filtering or the Key Guard/PIN safeguard.

When adbd is stopped and the UI controller is unreachable, the Termux application UID cannot enable USB debugging or force-stop protected apps. Record this as a live installation and acceptance blocker, not completion.
