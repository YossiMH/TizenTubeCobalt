#!/usr/bin/env python3
"""Observe one existing <=60-second diagnostic, then verify complete media release."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import acceptance as a


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    args.adb, args.seconds = 'adb', 60
    client = a.Client(args.state_dir)
    receipt = a.Receipt(args.receipt)
    a.start(client, args, receipt)
    deadline = receipt.value['diagnostic_deadline_ms']
    samples, captured, previous = [], {}, None
    while a.now_ms() < deadline + 6000:
        state = client.state()
        banner = state['canonical']['school_banner']
        media = banner.get('reminder_media')
        if not banner.get('diagnostic'):
            break
        if media:
            sample = dict(media, wall_ms=a.now_ms(), protection_ready=banner['protection_ready'],
                          audio_focus=banner['audio_focus'], native_lease=state['controller']['native_lease'],
                          native_grabs=state['controller']['native_physical_grabs'])
            samples.append(sample)
            phase = media.get('phase')
            if phase != previous:
                print(json.dumps({'phase': phase, 'cycle': media.get('cycle'),
                                  'music_position_ms': media.get('music_position_ms'),
                                  'video_position_ms': media.get('video_position_ms')}), flush=True)
                previous = phase
            elapsed = media.get('phase_elapsed_ms', 0)
            label = ('first-frame-message' if phase == 'hold' and media.get('cycle') == 0 and elapsed > 2000 else
                     'video' if phase == 'video' and elapsed > 2000 else
                     'return-crossfade' if phase == 'return' and 9000 < elapsed < 10500 else None)
            if label and label not in captured:
                path = args.receipt.parent / (label + '.png')
                with path.open('xb') as image:
                    subprocess.run(['adb', '-s', args.serial, 'exec-out', 'screencap', '-p'],
                                   stdout=image, stderr=subprocess.DEVNULL, timeout=5, check=True)
                captured[label] = path.name
        time.sleep(0.4)
    released = a.confirm_release(client, receipt, 'expired')
    active = [x for x in samples if x.get('ready')]
    video = [x for x in active if x.get('phase') == 'video' and x.get('video_playing')]
    returning = [x for x in active if x.get('phase') == 'return' and x.get('music_playing')]
    music_positions = [x['music_position_ms'] for x in video]
    checks = {
        'no_media_error': bool(active) and all(not x.get('error') for x in samples),
        'all_phases_observed': {'hold', 'fade_out', 'video', 'return'} <= {x.get('phase') for x in active},
        'next_cycle_started': any(x.get('phase') == 'hold' and x.get('cycle', 0) >= 1 for x in active),
        'music_paused_during_video': bool(video) and all(not x['music_playing'] and x['music_volume'] == 0 for x in video),
        'paused_position_retained': bool(music_positions) and max(music_positions) - min(music_positions) <= 100,
        'music_resumed_from_pause': bool(returning) and bool(music_positions)
            and 0 <= returning[0]['music_position_ms'] - music_positions[-1] < 1500,
        'no_audio_overlap': all(not (x.get('music_playing') and x.get('video_playing')) for x in active),
        'frame_crossfade_observed': any(0.05 < x.get('frame_crossfade_alpha', 0) < 0.95 for x in returning),
        'text_hidden_during_video': bool(video) and all(x.get('text_alpha') == 0 for x in video),
        'protection_held': all(x['protection_ready'] and x['native_lease'] == 'armed' and x['native_grabs'] for x in active),
        'media_released_at_expiry': 'reminder_media' not in released['canonical']['school_banner']
            and released['canonical']['school_banner']['audio_focus'] == 'released',
    }
    receipt.record('rich_media_cycle', checks=checks, screenshots=captured, samples=samples)
    print(json.dumps({'ok': all(checks.values()), 'checks': checks,
                      'samples': len(samples), 'screenshots': captured, 'receipt': str(args.receipt)}), flush=True)
    a.require(all(checks.values()), 'rich_media_acceptance_failed')


if __name__ == '__main__':
    main()
