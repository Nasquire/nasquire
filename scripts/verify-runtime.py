#!/usr/bin/env python3
"""새 데이터 폴더에서 bootRun → curl 검증 → JVM RSS 측정 → 서버 종료."""
import json
import os
import pathlib
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
port = int(os.environ.get('VERIFY_PORT', '8101'))
if port not in range(8100, 8200) or port in (8110, 8120, 8130):
    raise SystemExit('Choose an available development port in 8100–8199')
with socket.socket() as sock:
    sock.bind(('127.0.0.1', port))
subprocess.run(['./gradlew', ':core:generateSamples', '-q'], cwd=root, check=True)
artifacts = root / 'build' / 'verification'
artifacts.mkdir(parents=True, exist_ok=True)
(artifacts / 'measurement.json').unlink(missing_ok=True)
peak_rss = 0
pid = None
url = f'http://127.0.0.1:{port}'
with tempfile.TemporaryDirectory(prefix='runtime-', dir=artifacts) as folder, (artifacts / 'bootrun.log').open('w') as log:
    server = subprocess.Popen(['./gradlew', ':app:bootRun', '--no-daemon', '-q', '--args=' + ' '.join([
        f'--server.port={port}', f'--nasquire.data={folder}/data', '--nasquire.scan-millis=500', '--nasquire.stable-millis=200'])],
        cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            for proc in pathlib.Path('/proc').iterdir():
                if not proc.name.isdecimal():
                    continue
                try:
                    if b'com.bluesounds.nasquire.app.NasquireApplication' in (proc / 'cmdline').read_bytes().split(b'\0'):
                        pid = int(proc.name)
                        for line in (proc / 'status').read_text().splitlines():
                            if line.startswith('VmRSS:'):
                                peak_rss = max(peak_rss, int(line.split()[1]))
                except (FileNotFoundError, PermissionError, ProcessLookupError):
                    pass
            try:
                with urllib.request.urlopen(url + '/api/auth', timeout=1) as response:
                    assert response.status == 200
                break
            except OSError:
                if server.poll() is not None:
                    raise RuntimeError('bootRun failed; see build/verification/bootrun.log')
                time.sleep(0.05)
        else:
            raise RuntimeError('Server startup timed out')
        if pid is None:
            raise RuntimeError('Cannot identify the application JVM for RSS measurement')
        result = subprocess.run([sys.executable, 'scripts/smoke.py', url, 'build/samples', '--pid', str(pid)], cwd=root, text=True, capture_output=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        (artifacts / 'smoke.txt').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise SystemExit(result.returncode)
        workload = next(int(line.split('=')[1]) for line in result.stdout.splitlines() if line.startswith('PEAK_APPLICATION_RSS_KIB='))
        measurement = {'documents': 40, 'markdown': 20, 'pdf': 20, 'pdfPages': 40, 'heapMiB': 256, 'gc': 'SerialGC',
                       'startupPeakRssKiB': peak_rss, 'workloadPeakRssKiB': workload, 'peakRssMiB': round(max(peak_rss, workload) / 1024, 1),
                       'strictContainerLimit': False, 'curlSmokeExitCode': result.returncode}
        (artifacts / 'measurement.json').write_text(json.dumps(measurement, indent=2) + '\n')
        print(json.dumps(measurement, indent=2))
    finally:
        # Gradle은 작업 JVM을 별도 프로세스 그룹에 둘 수도 있으므로 직접 종료한다.
        if pid is not None:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        try:
            os.killpg(server.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            server.wait(timeout=35)
        except subprocess.TimeoutExpired:
            os.killpg(server.pid, signal.SIGKILL)
            server.wait(timeout=5)
        print('Application and bootRun process group stopped.')
