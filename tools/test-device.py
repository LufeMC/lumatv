#!/usr/bin/env python3
"""Run serial instrumentation suites on one explicitly selected TV."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--timeout', type=int, default=240)
parser.add_argument('suites', nargs='+')
args = parser.parse_args()
if not 30 <= args.timeout <= 900:
    parser.error('--timeout must be between 30 and 900 seconds')
if any(not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', name) for name in args.suites):
    parser.error('Use instrumentation class names without a package prefix')
repo = Path(__file__).resolve().parent.parent
sha = hashlib.sha256((repo / 'FreeMyTV.apk').read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True, exist_ok=True)
for suite in args.suites:
    subprocess.run(['adb', '-s', args.serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=True, timeout=15)
    started = time.monotonic()
    code = -1
    try:
        run = subprocess.run(['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w',
                              'io.freemytv.tests/io.freemytv.' + suite], capture_output=True, text=True, timeout=args.timeout)
        output, code = run.stdout + run.stderr, run.returncode
    except subprocess.TimeoutExpired:
        output = 'FAILED: The test exceeded its time limit.\n'
    output = re.sub(r'https?://\S+', '[address]', output)
    passed = code == 0 and 'Passed ' in output and not any(
        label in output for label in ['FAILED', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_CODE: 0'])
    result = dict(suite=suite, apk_sha256=sha, utc=datetime.now(timezone.utc).isoformat(),
                  seconds=round(time.monotonic() - started, 2), passed=passed, output=output)
    fd = os.open(args.output, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'a') as file:
        os.fchmod(file.fileno(), 0o600)
        file.write(json.dumps(result) + '\n')
    print(suite + ': ' + output.strip(), flush=True)
    if not passed:
        raise SystemExit(1)
    time.sleep(5)
