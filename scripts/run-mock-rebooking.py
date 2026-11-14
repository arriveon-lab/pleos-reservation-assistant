#!/usr/bin/env python3
"""Run the existing synthetic rebooking device check without changing app records.

This is not a production operator service or an authorization acceptance test.
Requires a connected, already authenticated isolated debug installation and its test APK.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--package', default='ai.pleos.assistant.mock.destinationvalidation')
    parser.add_argument('--adb', default=os.environ.get('ADB', shutil.which('adb')))
    args = parser.parse_args()
    if not args.adb:
        parser.error('Set ADB to the Android SDK platform-tools/adb path.')
    # Never run this helper against the original reservation installation.
    if not re.fullmatch(r'ai\.pleos\.assistant\.mock\.[a-z]+validation', args.package) or args.package == 'ai.pleos.assistant.mock.jointvalidation':
        parser.error('Use a separate *validation installation, not jointvalidation.')
    adb = [args.adb, '-s', args.serial]
    run(adb + ['get-state'], stdout=subprocess.DEVNULL)
    reverse = run(adb + ['reverse', '--list'], capture_output=True).stdout
    if any('tcp:8082' in line.split() for line in reverse.splitlines()):
        parser.error('Device port 8082 is already mapped; existing mapping left unchanged.')
    instrumentation = f'{args.package}.test/ai.pleos.assistant.app.DrivingDeviceChecks'
    installed = run(adb + ['shell', 'pm', 'list', 'instrumentation'], capture_output=True).stdout
    if instrumentation not in installed:
        parser.error('Install the matching debug app and androidTest APK first; see docs/MOCK_REBOOKING_RUNNER.md.')
    run(['./gradlew', '-PincludeAndroid=false', ':server:smokeClasspath'], cwd=ROOT)
    classpath = (ROOT / 'server/build/smoke-classpath.txt').read_text().strip()
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not java:
        parser.error('Java 21 is required.')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    # Keep the isolated DB/log for diagnostics, never reuse or delete an existing database.
    directory = Path(tempfile.mkdtemp(prefix='pleos-rebooking-'))
    print(f'Isolated MOCK test data: {directory}', flush=True)
    server = None
    mapped = False
    try:
        with (directory / 'server.log').open('w') as output:
            server = subprocess.Popen([
                java, '-cp', classpath, 'ai.pleos.assistant.OwnershipSmokeApplication',
                '--app.mode=MOCK', '--server.address=127.0.0.1', f'--server.port={port}'
            ], cwd=directory, stdout=output, stderr=subprocess.STDOUT)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(150):
            if server.poll() is not None:
                raise RuntimeError(f'Test server exited; inspect {directory / "server.log"}')
            try:
                with opener.open(f'http://127.0.0.1:{port}/api/v1/status', timeout=1) as response:
                    status = json.load(response)
                if status.get('mode') != 'MOCK' or status.get('externalCallsEnabled') is not False:
                    raise RuntimeError('Expected MOCK with externalCallsEnabled=false')
                break
            except (OSError, ValueError):
                time.sleep(0.2)
        else:
            raise RuntimeError('MOCK server startup timed out')
        run(adb + ['reverse', 'tcp:8082', f'tcp:{port}'], stdout=subprocess.DEVNULL)
        mapped = True
        result = run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'rebooking', 'true', instrumentation], capture_output=True, timeout=180)
        (directory / 'device.log').write_text(result.stdout + result.stderr)
        marker = 'PASS: synthetic HTTP rebooking UI flow; not joint authorization acceptance'
        if marker not in result.stdout or 'FAIL:' in result.stdout:
            raise RuntimeError(f'Device check did not pass; inspect {directory / "device.log"}')
        print(marker)
    finally:
        if mapped:
            subprocess.run(adb + ['reverse', '--remove', 'tcp:8082'], check=False)
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        print(f'Test server stopped; isolated evidence retained at {directory}')


if __name__ == '__main__':
    main()
