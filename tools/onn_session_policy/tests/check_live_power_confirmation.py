"""Read-only actual combined-dumpsys confirmation; no token or power command."""
import argparse
import asyncio
import json
import time
from onn_session_policy.adapters import Android
from onn_session_policy.policy import parse_observation, restored_power_state


async def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb');parser.add_argument('--serial',default='emulator-5554')
    args=parser.parse_args();started=time.monotonic()
    observation=parse_observation(await Android(args.adb,args.serial).observation())
    age=time.monotonic()-started
    confirmed=age<=5 and restored_power_state(observation)
    print(json.dumps({'schema':1,'result':'confirmed' if confirmed else 'not_confirmed',
        'sample_duration_ms':round(age*1000),'onn_power':observation.onn_power.value,
        'tv_power':observation.tv_power.value,'activity':observation.activity.value}))
    return 0 if confirmed else 1


if __name__=='__main__':raise SystemExit(asyncio.run(main()))
