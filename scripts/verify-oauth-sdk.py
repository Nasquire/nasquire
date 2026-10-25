#!/usr/bin/env python3
"""공식 mcp 2.3.0 OAuthClientProvider + Streamable HTTP 상호운용 검증.

build/ 아래 venv에 mcp==2.3.0 playwright==1.64.1 설치 후 실행한다.
PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers \
  build/verification/venv/bin/python scripts/verify-oauth-sdk.py
다른 bootRun/빌드와 동시에 실행하지 않는다. 모든 인증값은 메모리에만 둔다.
"""
import asyncio
import importlib.metadata
import json
import logging
import pathlib
import runpy
import secrets
import time
import urllib.parse
import httpx2
from mcp import ClientSession
from mcp.client.auth import OAuthClientProvider
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.auth import OAuthClientMetadata, AuthorizationCodeResult
from playwright.async_api import async_playwright, expect

helpers = runpy.run_path(str(pathlib.Path(__file__).with_name('verify-browser.py')))
ROOT, BASE = helpers['ROOT'], helpers['BASE']
OUT = ROOT / 'build/verification/oauth-sdk'
logging.disable(logging.CRITICAL)  # SDK/HTTP 디버그 로그에 코드·토큰 URL이 포함될 수 있다.


class MemoryStorage:
    tokens = None
    client = None
    async def get_tokens(self): return self.tokens
    async def set_tokens(self, value): self.tokens = value
    async def get_client_info(self): return self.client
    async def set_client_info(self, value): self.client = value


async def main():
    summary = {'sdk': 'mcp', 'version': importlib.metadata.version('mcp'), 'checks': [], 'http': []}
    def passed(name):
        summary['checks'].append(name)
        print('PASS ' + name, flush=True)
    password = secrets.token_urlsafe(24)
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        context = await browser.new_context(locale='en-US', viewport={'width':1280,'height':900})
        page = await context.new_page()
        store = MemoryStorage()
        try:
            await page.goto(BASE)
            await helpers['login'](page, password)
            await expect(page.get_by_role('navigation')).to_be_visible()
            source_dir = OUT / 'source'
            source_dir.mkdir(exist_ok=True)
            (source_dir / 'notes.md').write_text('OAuth searchable ocean document', encoding='utf-8')
            await helpers['add_source'](page, 'SDK source', source_dir)
            sources = await helpers['api'](page, '/api/sources')
            source_id = sources[0]['id']
            private_dir = OUT / 'private-source'
            private_dir.mkdir(exist_ok=True)
            (private_dir / 'private.md').write_text('Private ocean document', encoding='utf-8')
            await helpers['add_source'](page, 'Unapproved SDK source', private_dir)
            sources = await helpers['api'](page, '/api/sources')
            private_id = next(s['id'] for s in sources if s['name'] == 'Unapproved SDK source')
            # 일반 관리 세션과 별개 컨텍스트에서 OAuth 로그인 화면도 실측한다.
            consent_context = await browser.new_context(locale='en-US')
            consent = await consent_context.new_page()
            summary['browserHttp'] = []
            summary['browserConsole'] = []
            consent.on('response', lambda res: summary['browserHttp'].append({'origin':urllib.parse.urlsplit(res.url).netloc,'path':urllib.parse.urlsplit(res.url).path,'status':res.status,'csp':res.headers.get('content-security-policy','')}))
            consent.on('console', lambda msg: summary['browserConsole'].append(msg.text.split('?')[0]) if msg.type == 'error' else None)
            callback = {}
            callback_received = asyncio.Event()
            # 추가 리스너 없이 실제 루프백 302를 받는 기존 공개 경로를 사용한다.
            redirect_uri = 'http://localhost:8104/LICENSE.txt'
            def receive(request):
                if request.url.startswith(redirect_uri + '?'):
                    callback.update({k:v[0] for k,v in urllib.parse.parse_qs(urllib.parse.urlsplit(request.url).query).items()})
                    callback_received.set()
            consent.on('request', receive)
            async def redirect_handler(url):
                parsed = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
                assert parsed['resource'] == [BASE + '/mcp'], '인가 resource 불일치'
                assert parsed['code_challenge_method'] == ['S256'], '인가 S256 불일치'
                summary['stage'] = 'browser consent'
                await consent.goto(url)
                await consent.locator('input[name=username]').fill('admin')
                await consent.locator('input[name=password]').fill(password)
                await consent.locator('#login button').click()
                await expect(consent.locator('input[name=sources]').first).to_be_visible()
                await consent.locator(f'input[name=sources][value="{source_id}"]').check()
                # 코드·state는 스크린샷에 남기지 않는다.
                axe = ROOT / 'build/verification/deps/node_modules/axe-core/axe.min.js'
                summary['consentAudits'] = []
                for width in (1280, 390):
                    await consent.set_viewport_size({'width':width,'height':900})
                    for scheme in ('light','dark'):
                        await consent.emulate_media(color_scheme=scheme)
                        violations = None
                        if axe.exists():
                            await consent.evaluate(axe.read_text())
                            violations = await consent.evaluate("async () => (await axe.run(document, {runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations.map(v => v.id)")
                        overflow = await consent.evaluate('document.documentElement.scrollWidth > innerWidth')
                        summary['consentAudits'].append({'width':width,'scheme':scheme,'axe':violations,'overflow':overflow})
                        await consent.screenshot(path=str(OUT / f'consent-{width}-{scheme}.png'), full_page=True)
                        assert not violations and not overflow, '승인 화면 접근성/넘침'
                await consent.set_viewport_size({'width':1280,'height':900})
                await consent.emulate_media(color_scheme='light')
                await consent.locator('button[value=approve]').click()
                summary['stage'] = 'approve clicked'
                await asyncio.wait_for(callback_received.wait(), timeout=10)
                await consent.wait_for_url(lambda url: url.startswith(redirect_uri + '?'))
                assert callback.get('iss') == BASE, 'callback issuer 누락/불일치'
                passed('관리자 브라우저 로그인·선택 소스 승인·루프백 callback 코드/state/iss 수신')
            async def callback_handler():
                return AuthorizationCodeResult(**callback)
            provider = OAuthClientProvider(server_url=BASE + '/mcp', client_metadata=OAuthClientMetadata(
                client_name='Official SDK verification', redirect_uris=[redirect_uri],
                token_endpoint_auth_method='none', grant_types=['authorization_code','refresh_token'],
                response_types=['code'], scope='mcp:read'), storage=store,
                redirect_handler=redirect_handler, callback_handler=callback_handler)
            async def record(response):
                path = response.request.url.path
                summary['http'].append({'path':path, 'status':response.status_code})
                if path == '/oauth/token':
                    form = urllib.parse.parse_qs(response.request.content.decode())
                    assert form['resource'] == [BASE + '/mcp']
            async with httpx2.AsyncClient(auth=provider, event_hooks={'response':[record]}, timeout=30) as http:
                async with streamable_http_client(BASE + '/mcp', http_client=http) as (read, write):
                    async with ClientSession(read, write, read_timeout_seconds=30) as session:
                        await session.initialize()
                        assert store.tokens and store.client
                        passed('SDK 401→PRM→AS 메타데이터→DCR→S256 token→initialize')
                        tools = await session.list_tools()
                        assert {tool.name for tool in tools.tools} == {'search_documents','get_document','download_file'}
                        deadline = time.monotonic() + 20
                        while time.monotonic() < deadline:
                            searched = await session.call_tool('search_documents', {'query':'ocean'})
                            assert not searched.is_error
                            hits = json.loads(searched.content[0].text)
                            if hits:
                                break
                            await asyncio.sleep(.2)
                        assert hits and all(hit['source'] == source_id for hit in hits)
                        forbidden = await session.call_tool('search_documents', {'query':'ocean','source':private_id})
                        assert forbidden.is_error
                        forbidden = await session.call_tool('download_file', {'path':private_id + ':private.md'})
                        assert forbidden.is_error
                        passed('미승인 소스 검색·다운로드 거부·읽기 전용 도구 목록')
                        downloaded = await session.call_tool('download_file', {'path':hits[0]['path']})
                        assert not downloaded.is_error
                        ticket = json.loads(downloaded.content[0].text)['url']
                        # 티켓 다운로드는 OAuth Bearer 없이 수행한다.
                        async with httpx2.AsyncClient() as plain:
                            response = await plain.get(ticket)
                            assert response.status_code == 200 and 'ocean' in response.text
                        passed('공식 SDK tools/list·소스 범위 search_documents·download_file·티켓 HTTP 다운로드')
                        prior_access, prior_refresh = store.tokens.access_token, store.tokens.refresh_token
                        # 서버의 1시간 수명은 변경하지 않고 SDK의 로컬 만료 시각만 앞당긴다.
                        provider.context.token_expiry_time = time.time() - 1
                        await session.list_tools()
                        assert store.tokens.access_token != prior_access and store.tokens.refresh_token != prior_refresh
                        passed('SDK 로컬 만료→자동 refresh·회전 토큰으로 tools/list')
                        async with httpx2.AsyncClient() as plain:
                            revoked = await plain.post(BASE + '/oauth/revoke', data={'client_id':store.client.client_id,'token':store.tokens.refresh_token})
                            assert revoked.status_code == 200
                            denied = await plain.post(BASE + '/mcp', headers={'Authorization':'Bearer ' + store.tokens.access_token}, json={'jsonrpc':'2.0','id':1,'method':'tools/list'})
                            assert denied.status_code == 401
                            stale_ticket = await plain.get(ticket)
                            assert stale_ticket.status_code == 403
                        passed('revoke→실제 MCP 401·기발급 다운로드 403')
            await helpers['nav'](page, 'API tokens')
            await expect(page.get_by_text('Official SDK verification', exact=True).first).to_be_visible()
            await page.screenshot(path=str(OUT / 'management.png'), full_page=True)
            paths = {(item['path'], item['status']) for item in summary['http']}
            for item in [('/mcp',401),('/.well-known/oauth-protected-resource/mcp',200),('/.well-known/oauth-authorization-server',200),('/oauth/register',201),('/oauth/token',200)]:
                assert item in paths, 'SDK 실제 HTTP 단계 누락'
            summary['serverExpiryNote'] = 'SDK 로컬 만료만 모의. 실제 서버 만료는 Clock 기반 Java 회귀 테스트로 검증.'
            summary['callbackNote'] = '8104 기존 공개 라이선스 경로로 실제 302 이동. Playwright 요청 이벤트에서 코드 수신. 별도 클라이언트 리스너/포트 없음.'
            summary['exitCode'] = 0
        except Exception as error:
            # 오류에는 URL이 포함될 수 있어 타입과 마지막 성공 단계만 기록한다.
            summary['failureType'] = type(error).__name__
            def describe(e):
                if isinstance(e, BaseExceptionGroup):
                    return [describe(child) for child in e.exceptions]
                return type(e).__name__
            summary['failureTypes'] = describe(error)
            raise
        finally:
            (OUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
            await browser.close()


if __name__ == '__main__':
    try:
        with helpers['server'](OUT):
            asyncio.run(main())
    except Exception as error:
        print('FAIL ' + type(error).__name__ + ' (build/verification/oauth-sdk/summary.json 확인)', flush=True)
        raise SystemExit(1)
