#!/usr/bin/env python3
"""새 데이터 폴더에서 bootRun → curl 검증 → JVM RSS 측정 → 서버 종료."""
import json
import os
import pathlib
import signal
import shutil
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
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('127.0.0.1', port))
if os.environ.get('VERIFY_EMBEDDING') == 'builtin':
    subprocess.run([sys.executable, 'scripts/fetch-verification-models.py'], cwd=root, check=True)
subprocess.run(['./gradlew', ':core:generateSamples', '-q'], cwd=root, check=True)
artifacts = root / 'build' / 'verification'
artifacts.mkdir(parents=True, exist_ok=True)
(artifacts / 'measurement.json').unlink(missing_ok=True)
(artifacts / 'smoke.txt').unlink(missing_ok=True)
peak_rss = 0
pid = None
url = f'http://127.0.0.1:{port}'
with tempfile.TemporaryDirectory(prefix='runtime-', dir=artifacts) as folder, (artifacts / 'bootrun.log').open('w') as log:
    runtime_env = os.environ.copy()
    runtime_env['MALLOC_ARENA_MAX'] = '2'
    runtime_env['TOKENIZERS_PARALLELISM'] = 'false'
    runtime_env['DJL_CACHE_DIR'] = str(artifacts / 'djl-cache')
    server = subprocess.Popen(['./gradlew', ':app:bootRun', '--no-daemon', '-q', '--args=' + ' '.join([
        f'--server.port={port}', f'--nasquire.data={folder}/data', '--nasquire.scan-millis=500', '--nasquire.stable-millis=200'])],
        cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True, env=runtime_env)
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            for proc in pathlib.Path('/proc').iterdir():
                if not proc.name.isdecimal():
                    continue
                try:
                    command = (proc / 'cmdline').read_bytes().split(b'\0')
                    if b'com.bluesounds.nasquire.app.NasquireApplication' in command and f'--server.port={port}'.encode() in command:
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
        result = subprocess.run([sys.executable, 'scripts/smoke.py', url, 'build/samples', '--upload-root', str(pathlib.Path(folder) / 'uploads'), '--pid', str(pid), '--embedding', os.environ.get('VERIFY_EMBEDDING', 'off')], cwd=root, text=True, capture_output=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        (artifacts / 'smoke.txt').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise SystemExit(result.returncode)
        workload = next(int(line.split('=')[1]) for line in result.stdout.splitlines() if line.startswith('PEAK_APPLICATION_RSS_KIB='))
        measurement = {'embedding': os.environ.get('VERIFY_EMBEDDING', 'off'), 'documents': 48, 'officeHwp': 8, 'markdown': 20, 'pdf': 20, 'pdfPages': 40, 'heapMiB': 256, 'gc': 'SerialGC',
                       'startupPeakRssKiB': peak_rss, 'workloadPeakRssKiB': workload, 'peakRssMiB': round(max(peak_rss, workload) / 1024, 1),
                       'uploadBytes': 16777216, 'uploadShareCurl': True, 'strictContainerLimit': False, 'curlSmokeExitCode': result.returncode}
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
        mode = os.environ.get('VERIFY_EMBEDDING', 'off')
        for name in ('measurement.json', 'smoke.txt', 'bootrun.log'):
            path = artifacts / name
            if path.exists():
                shutil.copyfile(path, path.with_name(path.stem + '-' + mode + path.suffix))
        with socket.socket() as check:
            check.settimeout(1)
            if check.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError('Development server port is still listening')
        print('Application and bootRun process group stopped; port closed.')
