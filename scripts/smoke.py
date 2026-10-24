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
parser.add_argument('--upload-root', type=pathlib.Path)
parser.add_argument('--embedding', choices=['off', 'builtin', 'remote'], default='off')
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

    def curl(path, method='GET', body=None, token=None, form=None, csrf_enabled=True, expected=200, multipart=None):
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
        if multipart is not None:
            lines.append('form = ' + json.dumps('file=@' + str(multipart)))
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
        if status['indexed'] == 48 and status['pending'] == 0:
            break
        time.sleep(0.25)
    assert status['indexed'] == 48 and status['errors'] == 0, status
    print('PASS 48 documents indexed: 20 Markdown, 20 two-page PDF, 8 Office/HWP')
    query = '/api/search?' + urllib.parse.urlencode({'query': '한국어', 'limit': 100})
    hits = curl(query)
    assert len(hits) >= 20
    assert all(hit['page'] == 1 for hit in hits)
    pdf_hits = curl('/api/search?' + urllib.parse.urlencode({'query': 'Second', 'limit': 100}))
    pdf_hits = [hit for hit in pdf_hits if hit['path'].endswith('.pdf')]
    assert len(pdf_hits) == 20 and all(hit['page'] == 2 for hit in pdf_hits)
    print(f'PASS Korean query: {len(hits)} chunks; English PDF query: 20 page-2 results')
    issued = curl('/api/tokens', 'POST', {'name': 'smoke-client', 'expiresAt': int(time.time() * 1000) + 600000, 'sources': [source_id]})
    token = issued['value']
    assert len(curl(query, token=token, csrf_enabled=False)) >= 20
    curl('/api/tokens', token=token, csrf_enabled=False, expected=403)
    curl('/mcp', 'POST', {}, expected=401)
    initialize = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'nasquire-smoke', 'version': '1'}}}, token=token, csrf_enabled=False)
    assert 'result' in initialize
    tools = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list'}, token=token, csrf_enabled=False)
    assert {tool['name'] for tool in tools['result']['tools']} == {'search_documents', 'get_document', 'download_file'}
    result = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call', 'params': {'name': 'search_documents', 'arguments': {'query': '한국어', 'source': source_id, 'limit': 5}}}, token=token, csrf_enabled=False)
    assert not result['result'].get('isError')
    results = json.loads(result['result']['content'][0]['text'])
    assert len(results) == 5
    result = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call', 'params': {'name': 'get_document', 'arguments': {'path': hits[0]['path'], 'offset': 0, 'length': 100}}}, token=token, csrf_enabled=False)
    assert not result['result'].get('isError')
    assert len(json.loads(result['result']['content'][0]['text'])['text']) == 100
    print('PASS read-only token, MCP initialize/tools/list/search_documents/get_document')
    for extension in ('docx', 'doc', 'pptx', 'ppt', 'xlsx', 'xls', 'hwp', 'hwpx'):
        found = curl('/api/search?' + urllib.parse.urlencode({'query': 'format' + extension, 'mode': 'keyword'}), token=token, csrf_enabled=False)
        assert any(hit['path'].endswith('sample.' + extension) for hit in found), extension
        evidence = next(hit['location'] for hit in found if hit['path'].endswith('sample.' + extension))
        if extension.startswith('ppt'):
            assert evidence['slide'] == 2
        elif extension.startswith('xls'):
            assert evidence['sheet'] == '자료' and evidence['rowFrom'] == 5 and evidence['rowTo'] == 5
        else:
            assert evidence['paragraph'] >= 1
        read = curl('/api/document?' + urllib.parse.urlencode({'path': source_id + ':sample.' + extension}), token=token, csrf_enabled=False)
        assert 'format' + extension in read['text'] and read['locations']
    print('PASS 8 new formats: keyword search and get_document location evidence')
    link = curl('/api/download?' + urllib.parse.urlencode({'path': hits[0]['path']}), token=token, csrf_enabled=False)
    assert 0 < link['expiresAt'] - time.time() * 1000 <= 300000
    def fetch_download(url, expected):
        # 서명 주소는 curl 인자·실패 메시지·아티팩트에 기록하지 않는다.
        result = subprocess.run(['curl', '--config', '-'], input='url = ' + json.dumps(url) + '\nsilent\nshow-error\nmax-time = 30\nwrite-out = "\\n%{http_code}"', text=True, capture_output=True, check=True)
        body, status = result.stdout.rsplit('\n', 1)
        assert int(status) == expected, 'Signed download response status mismatch'
        return body
    assert fetch_download(link['url'], 200) == (args.samples / hits[0]['path'].split(':', 1)[1]).read_text()
    fetch_download(link['url'] + 'x', 403)
    downloaded = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 5, 'method': 'tools/call', 'params': {'name': 'download_file', 'arguments': {'path': source_id + ':sample.docx'}}}, token=token, csrf_enabled=False)
    assert not downloaded['result'].get('isError')
    mcp_link = json.loads(downloaded['result']['content'][0]['text'])
    # 바이너리도 원본 바이트와 동일한지 curl로 확인한다.
    result = subprocess.run(['curl', '--config', '-'], input=('url = ' + json.dumps(mcp_link['url']) + '\nsilent\nshow-error\nmax-time = 30').encode(), capture_output=True, check=True)
    assert result.stdout == (args.samples / 'sample.docx').read_bytes()
    print('PASS REST/MCP download_file: anonymous curl original bytes, expiry metadata, forged signature rejected')

    upload_root = args.upload_root or pathlib.Path(folder) / 'uploads'
    upload_root.mkdir()
    uploaded = curl('/api/sources', 'POST', {'name': 'Upload smoke', 'path': str(upload_root.resolve()), 'kind': 'upload'})
    upload_id = uploaded['id']
    upload_base = '/api/uploads/' + upload_id
    curl(upload_base + '/folder', 'POST', {'path': 'folder'}, csrf_enabled=False, expected=403)
    curl(upload_base + '/folder', 'POST', {'path': 'folder'}, token=token, csrf_enabled=False, expected=403)
    curl(upload_base + '/folder', 'POST', {'path': 'folder'})
    upload_file = pathlib.Path(folder) / 'upload.md'
    upload_file.write_text('uploadgalaxy runtime verification')
    curl(upload_base + '?path=folder/upload.md', 'POST', multipart=upload_file)
    large = pathlib.Path(folder) / 'large.bin'
    with large.open('wb') as file:
        file.truncate(16 * 1024 * 1024)
    curl(upload_base + '?path=large.bin', 'POST', multipart=large)
    assert (upload_root / 'large.bin').stat().st_size == 16 * 1024 * 1024
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if curl('/api/search?' + urllib.parse.urlencode({'query': 'uploadgalaxy', 'source': upload_id})):
            break
        time.sleep(0.25)
    else:
        raise AssertionError('Uploaded file was not indexed')
    shared = curl('/api/shares', 'POST', {'source': upload_id, 'path': 'folder', 'maxDownloads': 1})
    share_url = args.url.rstrip('/') + shared['url']
    listing = fetch_download(share_url, 200)
    assert 'upload.md' in listing and upload_id not in listing and str(upload_root) not in listing
    fetch_download(share_url + '?path=..', 404)
    assert fetch_download(share_url + '?path=upload.md&download=true', 200) == upload_file.read_text()
    fetch_download(share_url, 404)
    revoked = curl('/api/shares', 'POST', {'source': upload_id, 'path': 'folder/upload.md'})
    curl('/api/shares/' + revoked['id'], 'DELETE')
    fetch_download(args.url.rstrip('/') + revoked['url'], 404)
    trashed = curl(upload_base + '/trash', 'POST', {'path': 'folder/upload.md'})
    assert not (upload_root / 'folder/upload.md').exists()
    curl(upload_base + '/restore/' + trashed['id'], 'POST')
    assert (upload_root / 'folder/upload.md').read_text() == upload_file.read_text()
    audit_text = json.dumps(curl('/api/audit'))
    assert shared['url'][3:] not in audit_text and revoked['url'][3:] not in audit_text
    curl('/api/sources/' + upload_id, 'DELETE')
    print('PASS upload (16 MiB streamed), immediate index/search, CSRF/token denial, trash/restore, anonymous share curl, scope/count/revocation, secret-free audit')

    if args.embedding != 'off':
        mock = None
        if args.embedding == 'builtin':
            models = pathlib.Path('build/verification/models').resolve()
            configuration = {'provider': 'builtin', 'modelPath': str(models / 'model-int8.onnx'),
                             'tokenizerPath': str(models / 'tokenizer.json'), 'download': 'false'}
        else:
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            class Embeddings(BaseHTTPRequestHandler):
                def do_POST(self):
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    response = json.dumps({'data': [{'index': i, 'embedding': [1, 0, 0]} for i, _ in enumerate(body['input'])]}).encode()
                    self.send_response(200); self.send_header('Content-Length', str(len(response))); self.end_headers(); self.wfile.write(response)
                def log_message(self, *unused):
                    pass
            mock = ThreadingHTTPServer(('127.0.0.1', 8108), Embeddings)
            threading.Thread(target=mock.serve_forever, daemon=True).start()
            configuration = {'provider': 'remote', 'baseUrl': 'http://127.0.0.1:8108/v1', 'model': 'verification-fake',
                             'apiKey': 'verification-only', 'dimensions': '3', 'batchSize': '2', 'timeoutSeconds': '5'}
        try:
            curl('/api/embeddings', 'PUT', configuration)
            assert curl('/api/embeddings')['status']['reindexRequired']
            assert curl('/api/embeddings/test', 'POST')['ok']
            curl('/api/embeddings/reindex', 'POST')
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                state = curl('/api/embeddings')['status']
                if not state['reindexRequired']:
                    break
                time.sleep(0.25)
            assert not state['reindexRequired'], state
            for mode in ('keyword', 'vector', 'hybrid'):
                found = curl('/api/search?' + urllib.parse.urlencode({'query': '한국어 문서', 'mode': mode, 'limit': 5}), token=token, csrf_enabled=False)
                assert len(found) == 5 and all(h['path'].startswith(source_id + ':') and h['page'] >= 1 for h in found)
            found = curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 20, 'method': 'tools/call', 'params': {'name': 'search_documents', 'arguments': {'query': '한국어', 'mode': 'hybrid', 'limit': 5}}}, token=token, csrf_enabled=False)
            assert not found['result'].get('isError')
            for _ in range(30):
                assert curl(query, token=token, csrf_enabled=False)
            print('PASS ' + args.embedding + ' provider: connection, 48-document reindex, curl keyword/vector/hybrid, MCP hybrid, path/page evidence')
        finally:
            if mock:
                mock.shutdown(); mock.server_close()
    for _ in range(30 if args.embedding == 'off' else 0):
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
