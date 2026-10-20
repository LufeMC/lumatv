#!/usr/bin/env python3
"""Measure home startup on one explicitly selected TV without deleting application data."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import statistics
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='ADB device address, including port')
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--runs', type=int, default=3)
args = parser.parse_args()
if not 1 <= args.runs <= 10:
    parser.error('--runs must be between 1 and 10')

def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.serial, *parts], text=True, timeout=45)

result = {'time_utc': datetime.now(timezone.utc).isoformat(), 'serial': args.serial,
          'model': adb('shell', 'getprop', 'ro.product.model').strip(), 'cold_starts': []}
for number in range(args.runs):
    previous = set(adb('shell', 'logcat', '-d', '-v', 'epoch', '-s', 'ActivityTaskManager:I').splitlines())
    adb('shell', 'am', 'force-stop', 'io.freemytv')
    deadline = time.monotonic() + 8
    displayed = []
    launched = False
    sample = {}
    while not displayed and time.monotonic() < deadline:
        lines = adb('shell', 'logcat', '-d', '-v', 'epoch', '-s', 'ActivityTaskManager:I').splitlines()
        displayed = [line for line in lines if line not in previous and 'Displayed io.freemytv/.HomeActivity:' in line]
        if not displayed and not launched and deadline - time.monotonic() < 5:
            text = adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', '-n', 'io.freemytv/.HomeActivity')
            sample = {key: int(value) for key, value in re.findall(r'^(ThisTime|TotalTime|WaitTime): (\d+)', text, re.M)}
            launched = True
        if not displayed:
            time.sleep(0.1)
    if not displayed:
        raise RuntimeError('Android did not return a new first-display measurement')
    duration = re.search(r'HomeActivity: \+([0-9a-z.]+)', displayed[-1]).group(1)
    units = {'ms': 1, 's': 1000, 'm': 60000}
    elapsed = sum(float(value) * units[unit] for value, unit in re.findall(r'(\d+(?:\.\d+)?)(ms|s|m)', duration))
    if elapsed <= 0:
        raise RuntimeError('Invalid first-display measurement')
    sample['am_total_ms'] = sample.get('TotalTime')
    sample['TotalTime'] = round(elapsed)
    sample['method'] = 'ActivityTaskManager Displayed'
    result['cold_starts'].append(sample)
    print(f"Cold start {number + 1}: {sample['TotalTime']} ms", flush=True)
    time.sleep(5)
time.sleep(5)
result['cold_median_ms'] = statistics.median(sample['TotalTime'] for sample in result['cold_starts'])
pid = adb('shell', 'pidof', 'io.freemytv:home').strip().split()[0]
result['memory'] = adb('shell', 'dumpsys', 'meminfo', pid)
result['graphics'] = adb('shell', 'dumpsys', 'gfxinfo', pid, 'framestats')
result['compiler'] = '\n'.join(line for line in adb('shell', 'dumpsys', 'package', 'io.freemytv').splitlines() if 'status=' in line)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2) + '\n')
print(f"Median cold startup: {result['cold_median_ms']} ms")
print('\n'.join(line for line in result['memory'].splitlines() if 'TOTAL PSS:' in line))
