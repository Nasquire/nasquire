#!/usr/bin/env python3
"""새 bootRun 서버에서 curl OAuth 흐름을 검증한다. 인증값은 표준 입력/메모리에만 둔다."""
import html
import json
import os
import pathlib
import re
import signal
import socket
import subprocess
import tempfile
import time
import urllib.parse
import secrets
import hashlib
import base64

root = pathlib.Path(__file__).resolve().parents[1]
artifacts = root / 'build/verification'
artifacts.mkdir(parents=True, exist_ok=True)
port = int(os.environ.get('VERIFY_PORT', '8103'))
assert 8100 <= port <= 8199 and port not in (8110, 8120, 8130), '검증 포트 범위 오류'
base = f'http://127.0.0.1:{port}'
checks = []


def passed(name):
    checks.append(name)
    print('PASS ' + name, flush=True)


with tempfile.TemporaryDirectory(prefix='oauth-', dir=artifacts) as temporary, (artifacts / 'oauth-bootrun.log').open('w') as log:
    folder = pathlib.Path(temporary)
    cookies = folder / 'cookies'
    cookies.touch(mode=0o600)
    source = folder / 'source'
    source.mkdir()
    (source / 'notes.md').write_text('OAuth searchable ocean document', encoding='utf-8')
    csrf = ''

    def curl(path, method='GET', data=None, form=False, bearer=None, admin=False, expected=200):
        config = {'url': base + path, 'request': method, 'silent': None, 'show-error': None,
                  'max-time': '10', 'write-out': '\n%{http_code}', 'dump-header': '-'}
        if admin:
            config.update({'cookie': str(cookies), 'cookie-jar': str(cookies)})
        lines = [k if v is None else k + ' = ' + json.dumps(v) for k, v in config.items()]
        if csrf and admin:
            lines.append('header = ' + json.dumps('X-CSRF-TOKEN: ' + csrf))
        if bearer:
            lines.append('header = ' + json.dumps('Authorization: Bearer ' + bearer))
        if path == '/mcp':
            lines += ['header = "Accept: application/json, text/event-stream"', 'header = "MCP-Protocol-Version: 2025-11-25"']
        if data is not None:
            payload = urllib.parse.urlencode(data, doseq=True) if form else json.dumps(data)
            lines.append('header = ' + json.dumps('Content-Type: ' + ('application/x-www-form-urlencoded' if form else 'application/json')))
            lines.append('data = ' + json.dumps(payload))
        result = subprocess.run(['curl', '--config', '-'], input='\n'.join(lines) + '\n', text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError('curl 실행 실패 (종료 코드 ' + str(result.returncode) + ')')
        response, status = result.stdout.rsplit('\n', 1)
        if int(status) != expected:
            raise RuntimeError(method + ' ' + path.split('?')[0] + ' 상태 ' + status + ', 기대 ' + str(expected))
        headers, body = response.replace('\r\n', '\n').split('\n\n', 1)
        values = {k.lower(): v.strip() for line in headers.splitlines()[1:] if ':' in line for k, v in [line.split(':', 1)]}
        return body, values

    def json_call(*args, **kwargs):
        body, _ = curl(*args, **kwargs)
        return json.loads(body) if body else None

    def mcp(token, method, params=None):
        result = json_call('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params or {}}, bearer=token)
        assert 'result' in result, 'MCP 결과 없음'
        return result['result']

    server = subprocess.Popen(['./gradlew', ':app:bootRun', '--no-daemon', '-q', '--args=' + ' '.join([
        f'--server.port={port}', f'--nasquire.data={folder}/data', '--nasquire.oauth.enabled=true',
        f'--nasquire.public-url={base}', '--nasquire.scan-millis=100', '--nasquire.stable-millis=50'])],
        cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    application = None
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                json_call('/api/auth')
                break
            except (RuntimeError, ValueError):
                if server.poll() is not None:
                    raise RuntimeError('bootRun 시작 실패; oauth-bootrun.log 확인')
                time.sleep(.1)
        else:
            raise RuntimeError('bootRun 시작 시간 초과')
        for proc in pathlib.Path('/proc').iterdir():
            if not proc.name.isdecimal():
                continue
            try:
                command = (proc / 'cmdline').read_bytes().split(b'\0')
                if b'com.bluesounds.nasquire.app.NasquireApplication' in command and f'--nasquire.data={folder}/data'.encode() in command:
                    application = int(proc.name)
                    break
            except (FileNotFoundError, PermissionError, ProcessLookupError):
                pass
        assert application is not None, '기동한 JVM 식별 실패'
        meta = json_call('/.well-known/oauth-authorization-server')
        assert meta['code_challenge_methods_supported'] == ['S256']
        assert json_call('/.well-known/oauth-protected-resource/mcp')['resource'] == base + '/mcp'
        _, headers = curl('/mcp', expected=401)
        assert 'resource_metadata=' in headers['www-authenticate']
        passed('metadata and Bearer challenge')
        registration = json_call('/oauth/register', 'POST', {'client_name': 'OAuth verification', 'redirect_uris': ['https://client.example.com/callback'], 'token_endpoint_auth_method': 'none'}, expected=201)
        client_id = registration['client_id']
        passed('DCR without authorization')
        csrf = json_call('/api/auth', admin=True)['csrfToken']
        password = secrets.token_urlsafe(24)
        curl('/api/setup', 'POST', {'username': 'admin', 'password': password}, admin=True)
        curl('/api/login', 'POST', {'username': 'admin', 'password': password}, form=True, admin=True)
        password = None
        csrf = json_call('/api/auth', admin=True)['csrfToken']
        source_id = json_call('/api/sources', 'POST', {'name': 'OAuth source', 'path': str(source)}, admin=True)['id']
        verifier = secrets.token_urlsafe(32)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        authorize = '/oauth/authorize?' + urllib.parse.urlencode({'client_id': client_id, 'redirect_uri': 'https://client.example.com/callback', 'response_type': 'code', 'code_challenge': challenge, 'code_challenge_method': 'S256', 'resource': base + '/mcp', 'scope': 'mcp:read', 'state': 'verification-state'})
        page, headers = curl(authorize, admin=True)
        assert "frame-ancestors 'none'" in headers['content-security-policy']
        nonce = html.unescape(re.search(r'name="nonce" value="([^"]+)"', page).group(1))
        _, headers = curl('/oauth/authorize', 'POST', {'decision': 'approve', 'nonce': nonce, 'sources': [source_id]}, form=True, admin=True, expected=302)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(headers['location']).query)
        assert query['state'] == ['verification-state'] and query['iss'] == [base]
        code = query['code'][0]
        passed('administrator login, CSRF consent, state and issuer')
        body, headers = curl('/oauth/token', 'POST', {'grant_type': 'authorization_code', 'client_id': client_id, 'redirect_uri': 'https://client.example.com/callback', 'code': code, 'code_verifier': verifier, 'resource': base + '/mcp'}, form=True)
        assert headers['cache-control'] == 'no-store'
        issued = json.loads(body)
        assert len(issued['access_token']) == 43
        passed('authorization code and S256 token exchange')
        assert len(mcp(issued['access_token'], 'tools/list')['tools']) == 3
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            result = mcp(issued['access_token'], 'tools/call', {'name': 'search_documents', 'arguments': {'query': 'ocean'}})
            assert not result.get('isError'), '검색 도구 오류'
            hits = json.loads(result['content'][0]['text'])
            if hits:
                assert all(hit['source'] == source_id for hit in hits)
                break
            time.sleep(.1)
        else:
            raise RuntimeError('색인 검색 결과 시간 초과')
        curl('/api/search?query=ocean', bearer=issued['access_token'], expected=401)
        passed('MCP tools/list and scoped search; REST rejection')
        rotated = json_call('/oauth/token', 'POST', {'grant_type': 'refresh_token', 'client_id': client_id, 'refresh_token': issued['refresh_token'], 'resource': base + '/mcp'}, form=True)
        assert rotated['refresh_token'] != issued['refresh_token']
        mcp(rotated['access_token'], 'tools/list')
        passed('refresh rotation')
        curl('/oauth/revoke', 'POST', {'client_id': client_id, 'token': rotated['refresh_token']}, form=True)
        curl('/mcp', 'POST', {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}, bearer=rotated['access_token'], expected=401)
        passed('revocation and rejected access')
        summary = {'exitCode': 0, 'checks': checks, 'transport': 'loopback HTTP (development exception)', 'realExternalClients': False}
        (artifacts / 'oauth-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    finally:
        if application is not None:
            try:
                os.kill(application, signal.SIGTERM)
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
        if application is not None:
            deadline = time.monotonic() + 10
            while pathlib.Path(f'/proc/{application}').exists() and time.monotonic() < deadline:
                time.sleep(.1)
            if pathlib.Path(f'/proc/{application}').exists():
                os.kill(application, signal.SIGKILL)
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(('127.0.0.1', port))
        print('PASS bootRun stopped and port released', flush=True)
