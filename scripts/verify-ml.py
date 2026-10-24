#!/usr/bin/env python3
"""ML 전용 모드 실측: full bootJar → 고정 모델 준비 → java -jar(ML 모드) → curl 검증 → RSS·지연 측정 → 종료.

VERIFY_ML_FEATURES: reranker(기본) | embedding | both. 토큰은 임의 생성하여 환경변수로만 전달하고 출력하지 않는다.
결과는 무시되는 build/verification/ml/에 남긴다. 빌드와 동시에 실행하지 않는다.
"""
import json
import os
import pathlib
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
port = int(os.environ.get('VERIFY_PORT', '8102'))
if port not in range(8100, 8200) or port in (8110, 8120, 8130):
    raise SystemExit('Choose an available development port in 8100–8199')
features = os.environ.get('VERIFY_ML_FEATURES', 'reranker')
with socket.socket() as sock:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('127.0.0.1', port))
models = root / 'build/verification/models'
needed = {'reranker': ['reranker'], 'embedding': ['embedding'], 'both': ['embedding', 'reranker']}[features]
subprocess.run([sys.executable, 'scripts/fetch-verification-models.py', *needed], cwd=root, check=True)
subprocess.run(['./gradlew', '-Pfull', ':app:bootJar', '-q'], cwd=root, check=True)
artifacts = root / 'build/verification/ml'
artifacts.mkdir(parents=True, exist_ok=True)
token = secrets.token_urlsafe(32)
url = f'http://127.0.0.1:{port}'


def rss(pid):
    for line in pathlib.Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith('VmRSS:'):
            return int(line.split()[1])
    return 0


def call(path, body=None, auth=True, method=None, content='application/json'):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    request = urllib.request.Request(url + path, data=data, method=method or ('POST' if data is not None else 'GET'))
    if data is not None:
        request.add_header('Content-Type', content)
    if auth:
        request.add_header('Authorization', 'Bearer ' + (token if auth is True else auth))
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.loads(response.read() or b'{}')
    except urllib.error.HTTPError as error:
        return error.code, {}


with tempfile.TemporaryDirectory(prefix='ml-', dir=artifacts) as folder, (artifacts / f'ml-{features}.log').open('w') as log:
    env = os.environ.copy()
    env.update({'MALLOC_ARENA_MAX': '2', 'TOKENIZERS_PARALLELISM': 'false', 'DJL_CACHE_DIR': str(root / 'build/verification/djl-cache'),
                'NASQUIRE_ML_TOKEN': token})
    args = ['--nasquire.mode=ml', f'--server.port={port}', '--server.address=127.0.0.1', f'--nasquire.data={folder}/data']
    if features in ('embedding', 'both'):
        args += ['--nasquire.ml.embedding=builtin', f'--nasquire.ml.embedding-model-path={models}/model-int8.onnx',
                 f'--nasquire.ml.embedding-tokenizer-path={models}/tokenizer.json']
    if features in ('reranker', 'both'):
        reranker = models / 'mmarco-mMiniLMv2-L12-H384-v1'
        args += ['--nasquire.ml.reranker=builtin', f'--nasquire.ml.reranker-model-path={reranker}/model-qint8.onnx',
                 f'--nasquire.ml.reranker-tokenizer-path={reranker}/tokenizer.json']
    jvm = ['java', '--enable-native-access=ALL-UNNAMED', '-Xms32m', '-Xmx128m', '-XX:+UseSerialGC', '-XX:MaxMetaspaceSize=96m',
           '-XX:ReservedCodeCacheSize=32m', '-Xss512k', '-XX:MaxDirectMemorySize=16m', '-jar', 'app/build/libs/app-0.1.0.jar']
    started = time.monotonic()
    server = subprocess.Popen([*jvm, *args], cwd=root, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    peak = 0
    result = {'features': features, 'heapMaxMiB': 128, 'gc': 'SerialGC'}
    try:
        while True:
            if server.poll() is not None:
                raise RuntimeError('ML server exited; see build/verification/ml/ml-' + features + '.log')
            peak = max(peak, rss(server.pid))
            try:
                status, _ = call('/v1/models', auth=False)
                if status == 401:
                    break
            except OSError:
                pass
            if time.monotonic() - started > 180:
                raise RuntimeError('ML server startup timed out')
            time.sleep(0.05)
        result['startupSeconds'] = round(time.monotonic() - started, 2)
        result['startupRssKiB'] = rss(server.pid)
        checks = {}
        checks['noToken401'] = call('/v1/rerank', {'query': 'a', 'documents': ['b']}, auth=False)[0] == 401
        checks['wrongToken401'] = call('/v1/rerank', {'query': 'a', 'documents': ['b']}, auth='x' * 43)[0] == 401
        checks['searchApiAbsent404'] = call('/api/search?query=a')[0] in (400, 404) and call('/api/search')[0] == 404
        checks['adminAbsent404'] = call('/')[0] == 404 and call('/api/sources')[0] == 404 and call('/mcp', {})[0] == 404
        latencies = []
        if features in ('reranker', 'both'):
            documents = ['고양이는 집에서 키우는 동물이다.', '대한민국의 수도는 서울이다.', 'The capital of France is Paris.',
                         '서울은 한국에서 가장 큰 도시이며 수도이다.', 'Lucene은 검색 라이브러리다.'] * 4
            status, body = call('/v1/rerank', {'query': '한국의 수도는 어디인가?', 'documents': documents, 'top_n': 20})
            checks['rerankKoreanTop'] = status == 200 and body['results'][0]['index'] % 5 in (1, 3)
            status, body = call('/v1/rerank', {'query': 'What is the capital of France?', 'documents': documents[:5]})
            checks['rerankEnglishTop'] = status == 200 and body['results'][0]['index'] == 2
            for _ in range(10):
                begin = time.monotonic()
                status, _ = call('/v1/rerank', {'query': '한국의 수도는 어디인가?', 'documents': documents})
                latencies.append(time.monotonic() - begin)
                peak = max(peak, rss(server.pid))
                checks['rerankRepeated200'] = status == 200 and checks.get('rerankRepeated200', True)
            result['rerank20DocsSecondsMedian'] = round(sorted(latencies)[len(latencies) // 2], 3)
        if features in ('embedding', 'both'):
            status, body = call('/v1/embeddings', {'input': ['바다 ocean', '산 mountain'], 'model': 'multilingual-e5-small'})
            checks['embedding384'] = status == 200 and len(body['data'][0]['embedding']) == 384
            for _ in range(10):
                call('/v1/embeddings', {'input': ['문서 검색 시험 ' * 40] * 8})
                peak = max(peak, rss(server.pid))
        result['checks'] = checks
        result['peakRssKiB'] = max(peak, rss(server.pid))
        result['peakRssMiB'] = round(result['peakRssKiB'] / 1024, 1)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        (artifacts / f'measurement-{features}.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
        if not all(checks.values()):
            raise SystemExit('ML checks failed')
    finally:
        os.killpg(server.pid, signal.SIGTERM)
        try:
            server.wait(30)
        except subprocess.TimeoutExpired:
            os.killpg(server.pid, signal.SIGKILL)
            server.wait()
log_text = (artifacts / f'ml-{features}.log').read_text()
if token in log_text:
    raise SystemExit('token leaked into log')
