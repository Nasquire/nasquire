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
import re
import secrets
import signal
import socket
import struct
import subprocess
import time
import traceback
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
    if await page.get_by_role('dialog').count():
        await page.keyboard.press('Escape')
    if name in ('Settings', 'Security', 'About', '환경설정', '보안', '정보'):
        await page.get_by_role('button', name='환경설정' if name in ('환경설정', '보안', '정보') else 'Settings', exact=True).click()
        await expect(page.get_by_role('dialog')).to_be_visible()
    else:
        await page.get_by_role('navigation').get_by_role('button', name=name, exact=True).click()


async def login(page, password):
    await page.locator('main.auth input[name=username]').fill('admin')
    await page.locator('main.auth input[name=password]').fill(password)
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


async def verify_scrollbars(page):
    # 실제 overflow 요소에서 공간·호버·포커스·휠·키보드·드래그·테마·강제 색을 검사한다.
    await page.evaluate("document.documentElement.style.overflow = 'hidden'")
    for tag in ('div', 'pre', 'textarea'):
        await page.evaluate(r"""tag => {
          const node = document.createElement(tag);
          node.id = 'scrollbar-probe'; node.tabIndex = 0;
          node.style.cssText = 'position:fixed;left:20px;top:20px;width:240px;height:140px;overflow:scroll;white-space:pre;z-index:100;background:var(--bg);margin:0;padding:0;border:0;resize:none';
          const text = ('long line '.repeat(80) + '\n').repeat(80);
          if (tag === 'textarea') node.value = text;
          else node.textContent = text;
          document.getElementById('root').append(node);
        }""", tag)
        probe = page.locator('#scrollbar-probe')
        await page.mouse.move(500, 400)
        await expect(page.locator('.scrollbar-overlay')).to_have_count(0)
        before = await probe.evaluate('(e) => [e.clientWidth,e.clientHeight,e.scrollWidth,e.scrollHeight]')
        assert before[:2] == [240, 140], '숨김 스크롤바 공간: ' + tag
        await probe.hover(position={'x':80,'y':60})
        await expect(page.locator('.scrollbar-y:visible')).to_have_count(1)
        assert await page.locator('.scrollbar-y').evaluate('(e) => e.getBoundingClientRect().width') == 6
        assert await probe.evaluate('(e) => [e.clientWidth,e.clientHeight,e.scrollWidth,e.scrollHeight]') == before, '호버 레이아웃 이동'
        await page.mouse.wheel(0, 240)
        await page.wait_for_function("document.getElementById('scrollbar-probe').scrollTop > 0")
        await page.mouse.wheel(240, 0)
        await page.wait_for_function("document.getElementById('scrollbar-probe').scrollLeft > 0")
        if tag == 'div':
            await probe.evaluate('(e) => { e.scrollTop = 0; e.scrollLeft = 0 }')
            touch = await page.context.new_cdp_session(page)
            await touch.send('Emulation.setTouchEmulationEnabled', {'enabled': True})
            await touch.send('Input.dispatchTouchEvent', {'type':'touchStart', 'touchPoints':[{'x':100,'y':130}]})
            for y in (110, 90, 70, 50):
                await touch.send('Input.dispatchTouchEvent', {'type':'touchMove', 'touchPoints':[{'x':100,'y':y}]})
                await page.wait_for_timeout(30)
            await touch.send('Input.dispatchTouchEvent', {'type':'touchEnd', 'touchPoints':[]})
            await page.wait_for_function("document.getElementById('scrollbar-probe').scrollTop > 0")
            await touch.send('Emulation.setTouchEmulationEnabled', {'enabled': False})
            await touch.detach()
        await probe.focus()
        await page.mouse.move(500, 400)
        await expect(page.locator('.scrollbar-y:visible')).to_have_count(1)
        await page.keyboard.press('Control+End' if tag == 'textarea' else 'End')
        await page.wait_for_function("document.getElementById('scrollbar-probe').scrollTop > 240")
        await probe.evaluate('(e) => { e.scrollTop = 0; e.scrollLeft = 0 }')
        await page.wait_for_timeout(80)
        thumb = page.locator('.scrollbar-y')
        box = await thumb.bounding_box()
        await page.mouse.move(box['x'] + 3, box['y'] + 4)
        await page.mouse.down()
        await page.mouse.move(box['x'] + 3, box['y'] + 60, steps=5)
        await page.mouse.up()
        assert await probe.evaluate('(e) => e.scrollTop') > 0, '스크롤바 드래그'
        colors = []
        for theme in ('light', 'dark'):
            await page.evaluate('(theme) => document.documentElement.dataset.theme = theme', theme)
            colors.append(await thumb.evaluate('(e) => getComputedStyle(e).backgroundColor'))
        assert colors[0] != colors[1] and all('0.5' in color for color in colors), '테마 반투명 색'
        await page.emulate_media(forced_colors='active')
        await expect(page.locator('.scrollbar-overlay')).to_have_count(0)
        assert await probe.evaluate('(e) => getComputedStyle(e).scrollbarWidth') == 'auto', '강제 색 기본 스크롤바'
        await page.emulate_media(forced_colors='none')
        await probe.evaluate('(e) => { e.blur(); e.remove() }')
        await page.mouse.move(500, 400)
        await expect(page.locator('.scrollbar-overlay')).to_have_count(0)
    await page.evaluate('delete document.documentElement.dataset.theme')
    await page.evaluate("document.documentElement.style.removeProperty('overflow')")


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
        assert axe.exists(), 'axe 검증 도구가 필요합니다'

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
            await verify_scrollbars(page)
            passed('스크롤바 공간·호버·포커스·휠·키보드·드래그·테마·강제 색')
            await audit(page, 'setup-en-desktop')
            password = secrets.token_urlsafe(24) + 'a1!'
            await login(page, password)
            await expect(page.get_by_role('navigation')).to_be_visible()
            passed('첫 설정·관리자 로그인')
            empty_state = await context.storage_state()
            for locale, message, button, heading in [
                ('en-US', 'There are no upload sources.', 'Manage sources', 'Your knowledge, connected'),
                ('ko-KR', '업로드 소스가 없습니다.', '소스 관리로 이동', '내 폴더를 지식으로')]:
                empty_context = await browser.new_context(locale=locale, storage_state=empty_state)
                empty_page = await empty_context.new_page()
                await empty_page.goto(BASE)
                await nav(empty_page, 'Files and shares' if locale == 'en-US' else '파일·공유')
                await expect(empty_page.get_by_text(message, exact=False)).to_be_visible()
                await expect(empty_page.locator('section.card').first.locator('select')).to_have_count(0)
                await audit(empty_page, 'uploads-empty-' + locale)
                await empty_page.get_by_role('button', name=button, exact=True).click()
                await expect(empty_page.get_by_role('heading', name=heading, exact=True)).to_be_visible()
                await empty_context.close()
            passed('업로드 소스 없음 한/영 안내·소스 관리 이동')
            upload = OUT / 'uploads'
            upload.mkdir(exist_ok=True)
            await add_source(page, 'Sample documents', ROOT / 'build/samples')
            await add_source(page, 'Uploads', upload, 'upload')
            await expect(page.get_by_text('All supported formats', exact=True).first).to_be_visible()
            await expect(page.locator('textarea[name=includes]')).not_to_have_attribute('placeholder', '**/*.pdf')
            formats = await api(page, '/api/supported-extensions')
            assert 'hwpx' in formats and ('png' in formats) == (os.environ.get('VERIFY_FULL') == 'true')
            await page.get_by_role('button', name='Scan now', exact=True).first.click()
            passed('지원 형식·빈 glob 안내·지금 검사')
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
            result = page.locator('section.card').filter(has=page.get_by_role('button', name='Read page')).first
            await expect(result.locator('code')).to_contain_text('Sample documents edited · ')
            display_path = await result.locator('code').inner_text()
            assert not any(s['id'] in display_path for s in sources)
            await expect(result.locator('mark').first).to_have_text(re.compile('ocean', re.I))
            assert len(await result.locator('.result-text').inner_text()) <= 362
            if await result.get_by_role('button', name='Show more', exact=True).count():
                await result.get_by_role('button', name='Show more', exact=True).click()
                assert len(await result.locator('.result-text').inner_text()) > 360
                await result.get_by_role('button', name='Show less', exact=True).click()
            await page.get_by_role('button', name='Read page').first.click()
            await expect(page.get_by_role('heading', name='Document text')).to_be_visible()
            await audit(page, 'document-en-desktop')
            passed('검색·문서 보기')
            # 화면 표시만 고정 응답으로 검증하고 실제 문서 API 검증은 위에서 유지한다.
            sample_id = next(s['id'] for s in sources if s['kind'] == 'directory')
            fixture_text = ('prefix ' * 100) + '\n\n  OcEaN 한글 <img src=x onerror=alert(1)> ' + ('suffix ' * 100)
            async def mock_search(route):
                await route.fulfill(content_type='application/json', body=json.dumps([{'source': sample_id, 'sourceName': 'Sample documents edited',
                    'path': sample_id + ':nested/sample.txt', 'page': 1, 'text': fixture_text, 'score': 1.0}]))
            await page.route('**/api/search?*', mock_search)
            await page.locator('[name=query]').fill('ocean 한글 .*')
            await page.get_by_role('button', name='Search', exact=True).click()
            await expect(page.locator('.result-text')).to_have_count(1)
            await expect(page.locator('.result-text')).to_contain_text('OcEaN 한글')
            result = page.locator('section.card').filter(has=page.get_by_role('button', name='Read page')).first
            await expect(result.locator('code')).to_have_text('Sample documents edited · nested/sample.txt')
            assert await result.locator('mark').all_text_contents() == ['OcEaN', '한글']
            excerpt = await result.locator('.result-text').inner_text()
            assert excerpt.startswith('…') and excerpt.endswith('…') and len(excerpt) <= 362
            assert not re.search(r'\s{2,}', excerpt)
            await expect(result.locator('img,script')).to_have_count(0)
            await result.get_by_role('button', name='Show more', exact=True).click()
            await expect(result.locator('.result-text')).to_have_text(re.sub(r'\s+', ' ', fixture_text).strip())
            await audit(page, 'search-expanded-en-desktop')
            await result.get_by_role('button', name='Show less', exact=True).click()
            await audit(page, 'search-excerpt-en-desktop')
            await page.locator('[name=query]').fill('no-match')
            await page.get_by_role('button', name='Search', exact=True).click()
            await expect(result.locator('mark')).to_have_count(0)
            assert len(await result.locator('.result-text').inner_text()) <= 362
            await page.unroute('**/api/search?*', mock_search)
            passed('검색 발췌·다중 질의·대소문자·한글 강조·공백 축약·펼치기·XSS 텍스트 처리')

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
            await page.locator('#security-settings').get_by_label('Current password', exact=True).fill(password)
            await page.get_by_role('button', name='Set up authenticator').click()
            await expect(page.locator('#security-settings code').first).to_be_visible()
            secret = await page.locator('#security-settings code').first.inner_text()
            enrolled_step = int(time.time() // 30)
            await page.locator('#security-settings').get_by_label('Authenticator code', exact=True).fill(totp(secret, enrolled_step))
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
            await page.get_by_role('button', name='Delete', exact=True).click()
            await expect(page.get_by_text('Revoked', exact=True)).to_have_count(0)
            for name in ('expired', 'revoked'):
                token = await api(page, '/api/tokens', 'POST', {'name':name, 'expiresAt':int(time.time()*1000)+1000 if name == 'expired' else int(time.time()*1000)+600000})
                if name == 'revoked':
                    await api(page, '/api/tokens/' + token['id'], 'DELETE')
            await asyncio.sleep(1.2)
            await page.reload()
            await nav(page, 'API tokens')
            await expect(page.get_by_role('button', name='Delete all revoked and expired tokens')).to_be_enabled()
            await page.get_by_role('button', name='Delete all revoked and expired tokens').click()
            await expect(page.get_by_role('button', name='Delete all revoked and expired tokens')).to_be_disabled()
            passed('API 토큰 발급·숨김·철회·개별/일괄 삭제·OAuth 관리 화면')
            await nav(page, 'Settings')
            await expect(page.get_by_role('heading', name='External sign in', exact=True)).to_be_visible()
            await expect(page.locator('#oidc-settings')).to_contain_text('HTTPS')
            await expect(page.get_by_role('button', name='Delete client secret', exact=True)).to_be_disabled()
            await page.get_by_role('button', name='Save external sign in', exact=True).click()
            await expect(page.locator('#oidc-settings [role=status]')).to_have_text('Done')
            # 브라우저 버튼은 저장 설정으로 활성화하되 discovery/실제 IdP 접속은 하지 않는다.
            await api(page, '/api/oidc', 'PUT', {'issuer':'https://idp.example.com','clientId':'example-client','clientSecret':'example-secret','enabled':'true'})
            oidc_context = await browser.new_context(locale='en-US')
            oidc_page = await oidc_context.new_page()
            await oidc_page.goto(BASE)
            await expect(oidc_page.get_by_role('link', name='OpenID Connect', exact=True)).to_have_attribute('href', '/oidc/login')
            await audit(oidc_page, 'oidc-login-en-desktop')
            await oidc_context.close()
            await api(page, '/api/oidc/secret', 'DELETE')
            passed('외부 로그인 관리자 설정·비밀 삭제·로그인 버튼 (IdP 접속 없음)')
            await expect(page.get_by_label('Scan interval (minutes)', exact=True)).to_be_visible()
            await page.get_by_label('Scan interval (minutes)', exact=True).fill('1')
            await page.get_by_label('Maximum file size (MiB)', exact=True).fill('32')
            await page.get_by_role('button', name='Save indexing settings', exact=True).click()
            assert (await api(page, '/api/indexing'))['scanMillis'] == 60000
            passed('색인 주기·크기 설정 저장')
            await page.locator('#embedding-form').get_by_role('button', name='Save settings', exact=True).click()
            await expect(page.get_by_text('Embeddings off (keyword search)', exact=True)).to_be_visible()
            assert await page.locator('[name=provider] option[value=builtin]').count() == (1 if os.environ.get('VERIFY_FULL') == 'true' else 0)
            await page.locator('[name=provider]').select_option('remote')
            await expect(page.locator('[name=baseUrl]')).to_be_visible()
            await audit(page, 'embedding-remote-en-desktop')
            await page.locator('[name=provider]').select_option('off')
            passed('임베딩 설정 off 저장·원격 설정 표시')
            if os.environ.get('VERIFY_FULL') == 'true':
                full = page.locator('#full-settings')
                await full.get_by_role('button', name='Save settings', exact=True).click()
                await expect(full.get_by_role('status')).to_have_text('Settings saved')
                await full.locator('[name=reranker]').select_option('remote')
                await expect(full.locator('[name=baseUrl]')).to_be_visible()
                await audit(page, 'full-remote-en-desktop')
                await full.locator('[name=reranker]').select_option('builtin')
                await expect(full.locator('[name=localModelPath]')).to_be_visible()
                await audit(page, 'full-builtin-en-desktop')
                await full.locator('[name=reranker]').select_option('off')
                await full.locator('[name=ocr]').select_option('tesseract')
                await expect(full.locator('[name=ocrMemoryMiB]')).to_be_visible()
                await full.locator('[name=ocr]').select_option('off')
            else:
                await expect(page.locator('#full-settings')).to_have_count(0)
                await expect(page.locator('[name=reranker], [name=ocr]')).to_have_count(0)
            passed('통합 환경설정 저장·lite/full 가용성·공급자 입력')
            await nav(page, 'About')
            await expect(page.get_by_text('Build: ' + summary['build'], exact=True)).to_be_visible()
            assert await page.get_by_role('link', name='GitHub').get_attribute('href') == 'https://github.com/Nasquire/nasquire'
            for name in ('GNU AGPL v3.0', 'Additional terms'):
                link = page.get_by_role('link', name=name)
                assert (await context.request.get(BASE + await link.get_attribute('href'))).status == 200
            await page.get_by_role('button', name='View licenses').click()
            licenses = page.get_by_role('dialog', name='Licenses', exact=True)
            for file in ('LICENSE.txt', 'LICENSE-EXCEPTIONS.md', 'react-LICENSE.txt'):
                await licenses.get_by_role('tab', name=file, exact=True).click()
                await expect(licenses.get_by_role('tabpanel')).not_to_have_text('Loading…')
                assert len(await licenses.get_by_role('tabpanel').inner_text()) > 100
            panel = licenses.get_by_role('tabpanel')
            await panel.focus()
            await expect(licenses.locator('.scrollbar-y:visible')).not_to_have_count(0)
            await expect(page.locator('body > .scrollbar-overlay')).to_have_count(0)
            dimensions = await panel.evaluate('(e) => [e.clientWidth,e.clientHeight]')
            await page.keyboard.press('End')
            await page.wait_for_function("document.getElementById('license-text').scrollTop > 0")
            assert await panel.evaluate('(e) => [e.clientWidth,e.clientHeight]') == dimensions
            await audit(page, 'licenses-en-desktop')
            await page.keyboard.press('Escape')
            await expect(page.get_by_role('button', name='View licenses')).to_be_focused()
            await page.keyboard.press('Escape')
            await expect(page.get_by_role('button', name='Settings', exact=True)).to_be_focused()
            await page.get_by_role('button', name='Settings', exact=True).click()
            # 모달의 처음·마지막 컨트롤 사이 Tab 순환과 배경 클릭 닫기를 확인한다.
            await page.get_by_role('dialog').get_by_role('button', name='Close', exact=True).focus()
            await page.keyboard.press('Shift+Tab')
            await expect(page.get_by_role('button', name='Sign out', exact=True)).to_be_focused()
            await page.keyboard.press('Tab')
            await expect(page.get_by_role('dialog').get_by_role('button', name='Close', exact=True)).to_be_focused()
            await page.mouse.click(5, 5)
            await expect(page.get_by_role('dialog')).to_have_count(0)
            passed('정보·라이선스 탭 fetch·중첩 모달·Esc·바깥 클릭·포커스 가둠/복귀')
            # 계정 폼은 TOTP를 해제한 뒤 새 비밀번호·사용자명으로 로그인까지 확인한다.
            await nav(page, 'Security')
            security = page.locator('#security-settings')
            await expect(security.get_by_role('button', name='Disable two-step verification')).to_be_visible()
            await security.get_by_label('Current password', exact=True).fill(password)
            await security.locator('[name=code]').fill(totp(secret, int(time.time() // 30) + 1))
            await security.get_by_role('button', name='Disable two-step verification').click()
            await expect(security.get_by_role('button', name='Set up authenticator')).to_be_visible()
            await page.keyboard.press('Escape')
            await nav(page, 'Settings')
            changed_password = secrets.token_urlsafe(24) + 'b2!'
            form = page.locator('#account-password')
            await form.locator('[name=currentPassword]').fill(password)
            await form.locator('[name=newPassword]').fill(changed_password)
            await form.locator('[name=confirmation]').fill(changed_password)
            await form.get_by_role('button', name='Change password', exact=True).click()
            await expect(page.locator('#account-settings [role=status]')).to_contain_text('Account updated')
            form = page.locator('#account-username')
            await form.locator('[name=currentPassword]').fill(changed_password)
            await form.locator('[name=username]').fill('updated-admin')
            await form.get_by_role('button', name='Change username', exact=True).click()
            await expect(page.locator('#account-settings')).to_contain_text('Username: updated-admin')
            await audit(page, 'account-updated-en-desktop')
            await page.get_by_role('button', name='Sign out', exact=True).click()
            await page.locator('main.auth input[name=username]').fill('updated-admin')
            await page.locator('main.auth input[name=password]').fill(changed_password)
            await page.locator('form button.primary').click()
            await expect(page.get_by_role('navigation')).to_be_visible()
            passed('계정 비밀번호·사용자명 변경·갱신 세션 CSRF·변경 계정 로그인')
            names = {'users':('Users','사용자'), 'sources':('Sources','소스 관리'), 'files':('Files and shares','파일·공유'), 'settings':('Settings','환경설정'), 'security':('Security','보안'), 'tokens':('API tokens','API 토큰'), 'search':('Search playground','검색 시험'), 'about':('About','정보')}
            # 언어는 제품의 기존 navigator.language 정책에 맞춰 새 브라우저 컨텍스트로 전환한다.
            state = await context.storage_state()
            for locale, lang_index in [('en-US',0), ('ko-KR',1)]:
                localized = await browser.new_context(locale=locale, storage_state=state)
                screen = await localized.new_page()
                await screen.goto(BASE)
                for width in (1280,390):
                    await screen.set_viewport_size({'width':width,'height':900})
                    for theme in ('light','dark','auto'):
                        if await screen.get_by_role('dialog').count():
                            await screen.keyboard.press('Escape')
                        await screen.get_by_role('group', name=('Theme' if lang_index == 0 else '테마'), exact=True).get_by_role('button', name=({'auto':('Auto','자동'),'light':('Light','라이트'),'dark':('Dark','다크')}[theme][lang_index]), exact=True).click()
                        await screen.emulate_media(color_scheme='dark' if theme == 'auto' else theme)
                        for key, labels in names.items():
                            await nav(screen, labels[lang_index])
                            if key == 'files':
                                await screen.locator('section.card').first.locator('select').first.select_option(upload_id)
                            if key == 'settings':
                                await expect(screen.get_by_role('heading', name=('External sign in','외부 로그인')[lang_index], exact=True)).to_be_visible()
                                await screen.locator('[name=provider]').select_option('remote')
                            if key == 'settings' and os.environ.get('VERIFY_FULL') == 'true':
                                await screen.locator('[name=reranker]').select_option('remote')
                            await audit(screen, f'{key}-{locale}-{width}-{theme}')
                    if await screen.get_by_role('dialog').count():
                        await screen.keyboard.press('Escape')
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
            # ADMIN 관리 화면과 USER의 제한된 메뉴·계정·토큰을 두 언어로 검증한다.
            await nav(page, 'Users')
            await audit(page, 'users-admin-en')
            reader_password = 'reader-password-123'
            created = await api(page, '/api/users', 'POST', {'username':'reader','password':reader_password,'role':'USER'})
            for locale, index in [('en-US',0), ('ko-KR',1)]:
                user_context = await browser.new_context(locale=locale)
                user_page = await user_context.new_page()
                user_page.on('pageerror', lambda error: summary['consoleErrors'].append(type(error).__name__))
                user_page.on('console', lambda msg: summary['consoleErrors'].append('console.error') if msg.type == 'error' else None)
                user_page.on('requestfailed', lambda req: summary['failedRequests'].append({'path':req.url.split('?')[0].replace(BASE, ''),'failure':req.failure}))
                user_page.on('response', lambda res: summary['httpErrors'].append({'path':res.url.split('?')[0].replace(BASE, ''),'status':res.status}) if res.status >= 400 else None)
                await user_page.goto(BASE)
                await user_page.locator('main.auth input[name=username]').fill('reader')
                await user_page.locator('main.auth input[name=password]').fill(reader_password)
                await user_page.locator('form button.primary').click()
                await expect(user_page.get_by_role('navigation')).to_be_visible()
                await expect(user_page.get_by_role('navigation').get_by_role('button')).to_have_count(2)
                for width in (1280,390):
                    await user_page.set_viewport_size({'width':width,'height':900})
                    await nav(user_page, ('Search playground','검색 시험')[index])
                    await audit(user_page, f'user-search-{locale}-{width}')
                    await nav(user_page, ('API tokens','API 토큰')[index])
                    await expect(user_page.locator('[name=sources] option')).to_have_count(0)
                    await audit(user_page, f'user-tokens-{locale}-{width}')
                    await nav(user_page, ('Settings','환경설정')[index])
                    await expect(user_page.locator('#account-settings')).to_be_visible()
                    await expect(user_page.locator('#embedding-form')).to_have_count(0)
                    await expect(user_page.locator('#oidc-settings')).to_have_count(0)
                    await expect(user_page.locator('[name=scanMinutes]')).to_have_count(0)
                    await expect(user_page.get_by_role('heading', name=('About','정보')[index], exact=True)).to_be_visible()
                    await audit(user_page, f'user-settings-{locale}-{width}')
                    await user_page.keyboard.press('Escape')
                await user_context.close()
            await api(page, '/api/users/' + str(created['id']), 'DELETE')
            passed('ADMIN 사용자 관리·USER 한/영 데스크톱/모바일 메뉴·검색·내 토큰·환경설정')
            assert not summary['consoleErrors'] and not summary['failedRequests'] and not summary['httpErrors'], '브라우저 오류 수집 결과 확인'
            summary['exitCode'] = 0
        except Exception as error:
            summary['failureType'] = type(error).__name__
            summary['failureLines'] = [frame.lineno for frame in traceback.extract_tb(error.__traceback__) if frame.filename == __file__]
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
