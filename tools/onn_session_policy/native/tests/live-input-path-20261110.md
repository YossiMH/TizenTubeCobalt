# Actual input-path check — 2026-11-10

The bounded integration check could not generate a physical-source event through ordinary ADB shell: the first `sendevent /dev/input/event7 1 108 1` returned exit 1 and `Permission denied`. The path was freshly discovered from the helper's exact physical identity before the attempt. The phone device was independently identified as `virtual-remote`, event3 at that moment.

The test stopped at that denial. No phone key, volume key, PIN digit, Enter, or Power was sent, and no alternate injection route was attempted. Therefore this attempt does not establish actual physical suppression, smartphone navigation, or volume behavior end to end.

The actual helper did arm, create its volume device, and grab the physical source. Cleanup immediately sent DISARM and QUIT; the helper reported successful ungrab (`ioctl_result: 0`) and exited 0. A subsequent real probe successfully grabbed/released the physical device and created/destroyed its volume device without keypresses. A final `pidof onn_input_guard` found no process. Total test duration was 2.891 seconds.

The screen remained at Google PIN entry with focus visibly on digit 3 before and after. Screenshot hashes differ, so no claim of byte-identical rendering is made. Screenshots contain account details and are retained only in scoped device scratch, outside repository test evidence.

The machine report is `live-input-path-20261110.json`; the reproducible coordinated test is `test_input_path.py`. Do not describe this as a passing physical/phone/volume input test. Those checks still require real hardware and smartphone inputs during an explicitly coordinated test window.

