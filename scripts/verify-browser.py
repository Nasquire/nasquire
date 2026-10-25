#!/usr/bin/env python3
"""Chromium 스모크. build/verification/venv에 playwright를 설치하고 chromium을 준비한다.

PLAYWRIGHT_BROWSERS_PATH=build/verification/deps/browsers \
  build/verification/venv/bin/python scripts/verify-browser.py
선택 axe: npm install --prefix build/verification/deps @axe-core/playwright
스크린샷·로그·임시 데이터는 build/verification/browser 안에만 남긴다.
인증값은 메모리에만 보관하며 실패 시에도 URL·응답 본문을 출력하지 않는다.
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import pathlib
import secrets
import signal
import socket
import struct
import subprocess
import time
import urllib.request
import urllib.parse
from contextlib import contextmanager
from playwright.async_api import async_playwright, expect

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8104'
OUT = ROOT / 'build/verification' / ('browser-full' if os.environ.get('VERIFY_FULL') == 'true' else 'browser')


@contextmanager
def server(folder=OUT):
    folder.mkdir(parents=True, exist_ok=True)
    data = folder / 'data'
    assert not data.exists(), '새 데이터 폴더 필요 (기존 검증 자료를 다른 build 폴더로 옮기세요)'
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', 8104))
    with (folder / 'bootrun.log').open('w') as log:
        proc = subprocess.Popen(['./gradlew', *(['-Pfull'] if os.environ.get('VERIFY_FULL') == 'true' else []), ':app:bootRun', '--no-daemon', '-q', '--args=' + ' '.join([
            '--server.port=8104', f'--nasquire.data={data}', '--nasquire.oauth.enabled=true',
            f'--nasquire.public-url={BASE}', '--nasquire.scan-millis=300', '--nasquire.stable-millis=100'])],
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        application = None
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                for p in pathlib.Path('/proc').iterdir():
                    if not p.name.isdecimal():
                        continue
                    try:
                        cmd = (p / 'cmdline').read_bytes().split(b'\0')
                        if b'com.bluesounds.nasquire.app.NasquireApplication' in cmd and f'--nasquire.data={data}'.encode() in cmd:
                            application = int(p.name)
                    except (OSError, ProcessLookupError):
                        pass
                try:
                    with urllib.request.urlopen(BASE + '/api/auth', timeout=1) as response:
                        assert response.status == 200
                    break
                except OSError:
                    assert proc.poll() is None, 'bootRun 실패: bootrun.log 확인'
                    time.sleep(.2)
            else:
                raise AssertionError('bootRun 시간 초과')
            yield
        finally:
            if application:
                try:
                    os.kill(application, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                proc.wait(timeout=35)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=5)
            if application:
                deadline = time.monotonic() + 8
                while pathlib.Path(f'/proc/{application}').exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                if pathlib.Path(f'/proc/{application}').exists():
                    os.kill(application, signal.SIGKILL)
            with socket.socket() as sock:
                assert sock.connect_ex(('127.0.0.1', 8104)) != 0, '8104 종료 실패'
            print('PASS 서버 종료·8104 반환', flush=True)


def totp(secret, step=None):
    step = int(time.time() // 30) if step is None else step
    key = base64.b32decode(secret + '=' * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack('>Q', step), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f'{(struct.unpack(">I", digest[offset:offset+4])[0] & 0x7fffffff) % 1000000:06d}'


async def nav(page, name):
    await page.get_by_role('navigation').get_by_role('button', name=name, exact=True).click()


async def login(page, password):
    await page.locator('input[name=username]').fill('admin')
    await page.locator('input[name=password]').fill(password)
    await page.locator('form button.primary').click()


async def api(page, path, method='GET', body=None):
    return await page.evaluate('''async ({path, method, body}) => {
      const auth = await (await fetch('/api/auth')).json();
      const res = await fetch(path, {method, headers:{'Content-Type':'application/json', [auth.csrfHeader]:auth.csrfToken}, body:body == null ? undefined : JSON.stringify(body)});
      if (!res.ok) throw new Error('API status ' + res.status);
      const text = await res.text(); return text ? JSON.parse(text) : null;
    }''', {'path': path, 'method': method, 'body': body})


async def add_source(page, name, path, kind='directory'):
    await nav(page, 'Sources')
    form = page.locator('form').first
    await form.locator('[name=kind]').select_option(kind)
    await form.locator('[name=name]').fill(name)
    await form.locator('[name=path]').fill(str(path))
    await form.get_by_role('button', name='Save source', exact=True).click()
    await expect(page.get_by_role('heading', name=name, exact=True)).to_be_visible()


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    summary = {'build': 'full' if os.environ.get('VERIFY_FULL') == 'true' else 'lite', 'checks': [], 'audits': [], 'consoleErrors': [], 'failedRequests': [], 'httpErrors': []}
    def passed(name):
        summary['checks'].append(name)
        print('PASS ' + name, flush=True)
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        context = await browser.new_context(locale='en-US', viewport={'width': 1280, 'height': 900})
        # 외부 링크는 href만 검사하며 외부 서비스 요청을 차단한다.
        await context.route('**/*', lambda route: route.continue_() if route.request.url.startswith(BASE + '/') else route.abort())
        page = await context.new_page()
        page.on('pageerror', lambda error: summary['consoleErrors'].append(type(error).__name__))
        page.on('console', lambda msg: summary['consoleErrors'].append('console.error') if msg.type == 'error' else None)
        page.on('requestfailed', lambda req: summary['failedRequests'].append({'path':req.url.split('?')[0].replace(BASE, ''),'failure':req.failure}))
        page.on('response', lambda res: summary['httpErrors'].append({'path': res.url.split('?')[0].replace(BASE, ''), 'status': res.status}) if res.status >= 400 else None)
        axe = ROOT / 'build/verification/deps/node_modules/axe-core/axe.min.js'

        async def audit(target, label):
            await target.wait_for_timeout(100)
            result = await target.evaluate('''() => {
              const visible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
              const controls = [...document.querySelectorAll('input:not([type=hidden]),select,textarea')].filter(visible);
              return {overflow:document.documentElement.scrollWidth > innerWidth,
                scrollContainers:[...document.querySelectorAll('.main,.content,.card,form,.row,label,fieldset')].filter(visible).filter(e => e.scrollWidth > e.clientWidth + 2).map(e => e.tagName + '.' + e.className),
                unnamed:controls.filter(e => !e.labels?.length && !e.getAttribute('aria-label') && !e.getAttribute('aria-labelledby')).length,
                clipped:[...document.querySelectorAll('button,label,a,h1,h2')].filter(visible).filter(e => e.scrollWidth > e.clientWidth + 2 && ['hidden','clip'].includes(getComputedStyle(e).overflowX)).length};
            }''')
            if axe.exists():
                await target.evaluate(axe.read_text())
                violations = await target.evaluate('''async () => (await axe.run(document, {runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations.map(v => ({id:v.id, impact:v.impact, nodes:v.nodes.map(n => n.target)}))''')
                result['axe'] = violations
            else:
                result['axe'] = None
            result['screen'] = label
            summary['audits'].append(result)
            await target.screenshot(path=str(OUT / (label + '.png')), full_page=True)
            assert not result['overflow'] and not result['scrollContainers'] and not result['unnamed'] and not result['clipped'], '레이아웃/label: ' + label
            assert not result.get('axe'), '접근성: ' + label

        try:
            await page.goto(BASE)
            await expect(page.get_by_role('heading', name='Set up Nasquire')).to_be_visible()
            await audit(page, 'setup-en-desktop')
            password = secrets.token_urlsafe(24)
            await login(page, password)
            await expect(page.get_by_role('navigation')).to_be_visible()
            passed('첫 설정·관리자 로그인')
            upload = OUT / 'uploads'
            upload.mkdir(exist_ok=True)
            await add_source(page, 'Sample documents', ROOT / 'build/samples')
            await add_source(page, 'Uploads', upload, 'upload')
            sources = await api(page, '/api/sources')
            upload_id = next(s['id'] for s in sources if s['kind'] == 'upload')
            await page.get_by_role('button', name='Edit', exact=True).first.click()
            await page.locator('input[name=name]').fill('Sample documents edited')
            await page.get_by_role('button', name='Save source', exact=True).click()
            await expect(page.get_by_role('heading', name='Sample documents edited')).to_be_visible()
            passed('소스 등록·편집')
            await nav(page, 'Search playground')
            await page.locator('[name=query]').fill('ocean')
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                async with page.expect_response(lambda res: '/api/search?' in res.url):
                    await page.get_by_role('button', name='Search', exact=True).click()
                if await page.get_by_role('button', name='Read page').count():
                    break
                await asyncio.sleep(.5)
            await expect(page.get_by_role('button', name='Read page').first).to_be_visible()
            await page.get_by_role('button', name='Read page').first.click()
            await expect(page.get_by_role('heading', name='Document text')).to_be_visible()
            await audit(page, 'document-en-desktop')
            passed('검색·문서 보기')
            await nav(page, 'Files and shares')
            await page.locator('section.card').first.locator('select').first.select_option(upload_id)
            await page.locator('input[type=file]').set_input_files({'name':'browser-note.md','mimeType':'text/markdown','buffer':b'Browser searchable ocean document'})
            await page.get_by_role('button', name='Upload', exact=True).click()
            await expect(page.get_by_role('button', name='browser-note.md', exact=False)).to_be_visible()
            await page.get_by_label('New folder', exact=True).fill('folder')
            await page.get_by_role('button', name='Create folder', exact=True).click()
            await expect(page.get_by_role('button', name='folder/', exact=True)).to_be_visible()
            entry = page.locator('div.card').filter(has=page.get_by_role('button', name='browser-note.md', exact=False))
            await entry.get_by_role('button', name='Move to trash').click()
            await page.get_by_role('button', name='Restore', exact=True).click()
            await expect(page.get_by_role('button', name='browser-note.md', exact=False)).to_be_visible()
            passed('업로드·폴더 생성·휴지통·복원')
            for protected in (False, True):
                form = page.locator('#share-form')
                await form.locator('[name=source]').select_option(upload_id)
                await form.locator('[name=path]').fill('')
                share_password = secrets.token_urlsafe(16) if protected else ''
                await form.locator('[name=password]').fill(share_password)
                await form.get_by_role('button', name='Create link').click()
                notice = page.locator('[role=status]')
                await expect(notice.locator('pre')).to_be_visible()
                url = await notice.locator('pre').inner_text()
                public = await browser.new_context(http_credentials={'username':'guest','password':share_password} if protected else None)
                public_page = await public.new_page()
                if protected:
                    denied = await context.request.get(url, max_redirects=0)
                    assert denied.status == 401 and 'Basic' in denied.headers['www-authenticate']
                response = await public_page.goto(url)
                assert response.status == 200
                link = public_page.get_by_role('link', name='browser-note.md', exact=True)
                await expect(link).to_be_visible()
                download = await public.request.get(BASE + urllib.parse.urljoin(urllib.parse.urlsplit(url).path, await link.get_attribute('href')))
                assert download.status == 200 and b'ocean' in await download.body()
                assert not await public.cookies(), '공유 세션 쿠키 생성'
                await audit(public_page, 'share-' + ('basic' if protected else 'public'))
                await public.close()
                await notice.get_by_role('button', name='Dismiss').click()
            passed('공유 생성·공개 페이지·HTTP Basic challenge/자동 인증')
            await nav(page, 'Security')
            await page.get_by_label('Current password', exact=True).fill(password)
            await page.get_by_role('button', name='Set up authenticator').click()
            await expect(page.locator('code').first).to_be_visible()
            secret = await page.locator('code').first.inner_text()
            enrolled_step = int(time.time() // 30)
            await page.get_by_label('Authenticator code', exact=True).fill(totp(secret, enrolled_step))
            await page.get_by_role('button', name='Enable', exact=True).click()
            await expect(page.get_by_role('button', name='Dismiss')).to_be_visible()
            await page.get_by_role('button', name='Dismiss').click()
            await page.get_by_role('button', name='Sign out', exact=True).click()
            await login(page, password)
            await expect(page.locator('input[name=code]')).to_be_visible()
            # 서버의 재생 방지를 유지하며 다음 TOTP 단계로 진행한다(최대 30초).
            while int(time.time() // 30) <= enrolled_step:
                await asyncio.sleep(.5)
            await page.locator('input[name=code]').fill(totp(secret))
            await page.locator('form button.primary').click()
            await expect(page.get_by_role('navigation')).to_be_visible()
            passed('TOTP 등록·TOTP 로그인·복구 코드 숨김')
            await nav(page, 'API tokens')
            await page.get_by_label('Token name', exact=True).fill('Browser verification')
            await page.get_by_role('button', name='Issue token').click()
            await expect(page.locator('[role=status] pre')).to_be_visible()
            await page.get_by_role('button', name='Dismiss').click()
            await page.get_by_role('button', name='Revoke', exact=True).click()
            await expect(page.get_by_text('Revoked', exact=True)).to_be_visible()
            passed('API 토큰 발급·숨김·철회·OAuth 관리 화면')
            await nav(page, 'Embeddings')
            await page.get_by_role('button', name='Save settings', exact=True).click()
            await page.locator('[name=provider]').select_option('remote')
            await expect(page.locator('[name=baseUrl]')).to_be_visible()
            await audit(page, 'embedding-remote-en-desktop')
            await page.locator('[name=provider]').select_option('off')
            passed('임베딩 설정 off 저장·원격 설정 표시')
            await nav(page, 'Full settings')
            await page.get_by_role('button', name='Save settings', exact=True).click()
            await expect(page.get_by_role('status')).to_have_text('Settings saved')
            if os.environ.get('VERIFY_FULL') == 'true':
                await page.locator('[name=reranker]').select_option('remote')
                await expect(page.locator('[name=baseUrl]')).to_be_visible()
                await audit(page, 'full-remote-en-desktop')
                await page.locator('[name=reranker]').select_option('builtin')
                await expect(page.locator('[name=localModelPath]')).to_be_visible()
                await audit(page, 'full-builtin-en-desktop')
                await page.locator('[name=reranker]').select_option('off')
                await page.locator('[name=ocr]').select_option('tesseract')
                await expect(page.locator('[name=ocrMemoryMiB]')).to_be_visible()
                await page.locator('[name=ocr]').select_option('off')
            passed('full 설정 off 저장·가용성·공급자 입력')
            await nav(page, 'About')
            assert await page.get_by_role('link', name='GitHub').get_attribute('href') == 'https://github.com/Nasquire/nasquire'
            for name in ('GNU AGPL v3.0', 'Additional terms'):
                link = page.get_by_role('link', name=name)
                assert (await context.request.get(BASE + await link.get_attribute('href'))).status == 200
            passed('정보·저장소 href·라이선스 HTTP 링크')
            names = {'sources':('Sources','소스 관리'), 'files':('Files and shares','파일·공유'), 'embeddings':('Embeddings','임베딩 설정'), 'full':('Full settings','full 설정'), 'security':('Security','보안'), 'tokens':('API tokens','API 토큰'), 'search':('Search playground','검색 시험'), 'about':('About','정보')}
            # 언어는 제품의 기존 navigator.language 정책에 맞춰 새 브라우저 컨텍스트로 전환한다.
            state = await context.storage_state()
            for locale, lang_index in [('en-US',0), ('ko-KR',1)]:
                localized = await browser.new_context(locale=locale, storage_state=state)
                screen = await localized.new_page()
                await screen.goto(BASE)
                for width in (1280,390):
                    await screen.set_viewport_size({'width':width,'height':900})
                    for theme in ('light','dark','auto'):
                        await screen.get_by_role('combobox', name=('Theme' if lang_index == 0 else '테마'), exact=True).select_option(theme)
                        await screen.emulate_media(color_scheme='dark' if theme == 'auto' else theme)
                        for key, labels in names.items():
                            await nav(screen, labels[lang_index])
                            if key == 'files':
                                await screen.locator('section.card').first.locator('select').first.select_option(upload_id)
                            if key == 'embeddings':
                                await screen.locator('[name=provider]').select_option('remote')
                            if key == 'full' and os.environ.get('VERIFY_FULL') == 'true':
                                await screen.locator('[name=reranker]').select_option('remote')
                            await audit(screen, f'{key}-{locale}-{width}-{theme}')
                    # 연속 Tab이 실제 활성 컨트롤로 이동하는지 확인한다.
                    await screen.locator('body').click(position={'x':1,'y':1})
                    focused = []
                    for _ in range(6):
                        await screen.keyboard.press('Tab')
                        focused.append(await screen.evaluate("({tag:document.activeElement.tagName, outline:getComputedStyle(document.activeElement).outlineStyle})"))
                    assert all(item['tag'] in ('BUTTON','SELECT','A','INPUT','TEXTAREA') and item['outline'] != 'none' for item in focused), 'Tab 포커스 표시'
                    summary.setdefault('keyboard', []).append({'locale':locale,'width':width,'steps':len(focused)})
                await localized.close()
            passed('한/영 × 1280/390 × 라이트/다크/자동 · 전체 화면·axe·Tab')
            assert not summary['consoleErrors'] and not summary['failedRequests'] and not summary['httpErrors'], '브라우저 오류 수집 결과 확인'
            summary['exitCode'] = 0
        except Exception as error:
            summary['failureType'] = type(error).__name__
            summary['lastCheck'] = summary['checks'][-1] if summary['checks'] else None
            await page.screenshot(path=str(OUT / 'failure.png'), full_page=True, mask=[page.locator('input,code,pre,[role=status]')])
            raise
        finally:
            (OUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
            await browser.close()


if __name__ == '__main__':
    try:
        with server():
            asyncio.run(main())
    except Exception as error:
        print('FAIL ' + type(error).__name__ + ' (' + str(OUT.relative_to(ROOT)) + '/summary.json 및 bootrun.log 확인)', flush=True)
        raise SystemExit(1)
