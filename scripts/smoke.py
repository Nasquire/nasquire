#!/usr/bin/env python3
"""curl 기반 실제 서버 검증. 새 데이터 폴더에서 실행하며 인증값을 출력/저장하지 않는다."""
import argparse
import json
import pathlib
import secrets
import re
import urllib.request
import subprocess
import tempfile
import threading
import time
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('url', help='Example: http://127.0.0.1:8101')
parser.add_argument('samples', type=pathlib.Path)
parser.add_argument('--pid', type=int, help='Application JVM PID for Linux RSS measurement')
args = parser.parse_args()
if args.pid:
    assert pathlib.Path(f'/proc/{args.pid}/exe').resolve().name == 'java', 'RSS target must be the application JVM'
peak_rss = 0
stop = threading.Event()


def sample_memory():
    global peak_rss
    while not stop.is_set():
        try:
            status = pathlib.Path(f'/proc/{args.pid}/status').read_text()
            for line in status.splitlines():
                if line.startswith('VmRSS:'):
                    peak_rss = max(peak_rss, int(line.split()[1]))
        except FileNotFoundError:
            return
        stop.wait(0.05)


thread = threading.Thread(target=sample_memory, daemon=True) if args.pid else None
if thread:
    thread.start()

with tempfile.TemporaryDirectory(prefix='nasquire-smoke-') as folder:
    cookies = str(pathlib.Path(folder) / 'cookies')
    csrf = ''

    def curl(path, method='GET', body=None, token=None, form=None, csrf_enabled=True, expected=200):
        # 토큰/비밀번호는 프로세스 인자나 로그 대신 curl의 표준 입력으로 전달한다.
        config = {'url': args.url.rstrip('/') + path, 'request': method, 'cookie': cookies, 'cookie-jar': cookies,
                  'silent': None, 'show-error': None, 'max-time': '30', 'write-out': '\n%{http_code}'}
        lines = []
        for key, value in config.items():
            lines.append(key if value is None else key + ' = ' + json.dumps(value))
        if csrf and csrf_enabled:
            lines.append('header = ' + json.dumps('X-CSRF-TOKEN: ' + csrf))
        if token:
            lines.append('header = ' + json.dumps('Authorization: Bearer ' + token))
        if path == '/mcp':
            lines.append('header = "Accept: application/json, text/event-stream"')
            lines.append('header = "MCP-Protocol-Version: 2025-11-25"')
        if body is not None:
            lines.append('header = "Content-Type: application/json"')
            lines.append('data = ' + json.dumps(json.dumps(body)))
        if form is not None:
            lines.append('header = "Content-Type: application/x-www-form-urlencoded"')
            lines.append('data = ' + json.dumps(urllib.parse.urlencode(form)))
        response = subprocess.run(['curl', '--config', '-'], input='\n'.join(lines), text=True, capture_output=True, check=True).stdout
        text, status = response.rsplit('\n', 1)
        assert int(status) == expected, f'{method} {path}: HTTP {status}, expected {expected}'
        return json.loads(text) if text else None

    with urllib.request.urlopen(args.url.rstrip('/') + '/') as response:
        html = response.read().decode()
        assert response.status == 200 and '<title>Nasquire</title>' in html
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', html)
    assert len(assets) >= 2
    for asset in assets + ['/icon.svg', '/LICENSE.txt', '/LICENSE-EXCEPTIONS.md', '/react-LICENSE.txt']:
        with urllib.request.urlopen(args.url.rstrip('/') + asset) as response:
            assert response.status == 200 and response.read()
    print('PASS static UI bundle, icon and license resources')
    auth = curl('/api/auth')
    assert not auth['configured'], 'Use a fresh data directory for this verification'
    csrf = auth['csrfToken']
    password = secrets.token_urlsafe(24)
    curl('/api/setup', 'POST', {'username': 'admin', 'password': password}, csrf_enabled=False, expected=403)
    curl('/api/setup', 'POST', {'username': 'admin', 'password': password})
    curl('/api/login', 'POST', form={'username': 'admin', 'password': password})
    auth = curl('/api/auth')
    assert auth['authenticated']
    csrf = auth['csrfToken']
    print('PASS setup, CSRF protection, administrator login')
    source = curl('/api/sources', 'POST', {'name': 'Original samples', 'path': str(args.samples.resolve())})
    source_id = source['id']
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        status = curl('/api/sources/' + source_id + '/status')
        if status['indexed'] == 40 and status['pending'] == 0:
            break
        time.sleep(0.25)
    assert status['indexed'] == 40 and status['errors'] == 0, status
    print('PASS 40 documents indexed: 20 Markdown, 20 two-page PDF')
    query = '/api/search?' + urllib.parse.urlencode({'query': '한국어', 'limit': 100})
    hits = curl(query)
    assert len(hits) >= 20
    assert all(hit['page'] == 1 for hit in hits)
    pdf_hits = curl('/api/search?' + urllib.parse.urlencode({'query': 'Second', 'limit': 100}))
    assert len(pdf_hits) == 20 and all(hit['page'] == 2 for hit in pdf_hits)
    print(f'PASS Korean query: {len(hits)} chunks; English PDF query: 20 page-2 results')
    issued = curl('/api/tokens', 'POST', {'name': 'smoke-client', 'expiresAt': int(time.time() * 1000) + 60000, 'sources': [source_id]})
    token = issued['value']
    assert len(curl(query, token=token, csrf_enabled=False)) >= 20
    curl('/api/tokens', token=token, csrf_enabled=False, expected=403)
    curl('/mcp', 'POST', {}, expected=401)
    initialize = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'nasquire-smoke', 'version': '1'}}}, token=token, csrf_enabled=False)
    assert 'result' in initialize
    tools = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'}, token=token, csrf_enabled=False)
    assert {tool['name'] for tool in tools['result']['tools']} == {'search_documents', 'get_document'}
    result = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call', 'params': {'name': 'search_documents', 'arguments': {'query': '한국어', 'source': source_id, 'limit': 5}}}, token=token, csrf_enabled=False)
    assert not result['result'].get('isError')
    results = json.loads(result['result']['content'][0]['text'])
    assert len(results) == 5
    result = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call', 'params': {'name': 'get_document', 'arguments': {'path': hits[0]['path'], 'offset': 0, 'length': 100}}}, token=token, csrf_enabled=False)
    assert not result['result'].get('isError')
    assert len(json.loads(result['result']['content'][0]['text'])['text']) == 100
    print('PASS read-only token, MCP initialize/tools/list/search_documents/get_document')
    for _ in range(30):
        assert curl(query, token=token, csrf_enabled=False)
    curl('/api/tokens/' + issued['id'], 'DELETE')
    curl(query, token=token, csrf_enabled=False, expected=401)
    assert any('mcp search_documents' in event['ACTION'] for event in curl('/api/audit'))
    print('PASS 30 additional searches, token revocation, audit log')
    curl('/api/logout', 'POST', expected=204)
    curl(query, csrf_enabled=False, expected=401)
    print('PASS logout invalidates administrator session')
stop.set()
if thread:
    thread.join()
    print(f'PEAK_APPLICATION_RSS_KIB={peak_rss}')
    print(f'PEAK_APPLICATION_RSS_MIB={peak_rss / 1024:.1f}')
