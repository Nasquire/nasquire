#!/usr/bin/env python3
"""큰 자체 생성 PDF·Office 색인의 RSS/NMT/cgroup 측정. 인증값은 저장하지 않는다."""
import argparse
import http.cookiejar
import json
import os
import pathlib
import random
import secrets
import socket
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import zlib
import hashlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/verification/memory'
JAVA = pathlib.Path(os.environ.get('JAVA_HOME', '/opt/jdk/jdk25')) / 'bin/java'
BASE = ['--enable-native-access=ALL-UNNAMED', '-Xms64m', '-Xmx256m', '-XX:+UseSerialGC',
        '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=48m', '-Xss512k', '-XX:MaxDirectMemorySize=32m']


def generate(folder, pages):
    office = [p for p in (ROOT / 'build/samples').glob('sample.*')
              if p.suffix in ('.docx', '.doc', '.pptx', '.ppt', '.xlsx', '.xls', '.hwp', '.hwpx')]
    if len(office) != 8:
        raise RuntimeError('먼저 ./gradlew :core:generateSamples -q 를 실행하세요')
    folder.mkdir(parents=True, exist_ok=True)
    # 쪽마다 별개 이미지·텍스트 스트림을 둔다. 외부 문서·폰트·Python 패키지가 필요 없다.
    for doc in range(18):
        path = folder / f'manual-{doc:02}.pdf'
        objects = [b'', b'<< /Type /Catalog /Pages 2 0 R >>', b'',
                   b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
        kids = []
        rng = random.Random(doc)
        for page in range(pages):
            n = len(objects)
            kids.append(f'{n} 0 R')
            objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> /XObject << /Im {n+2} 0 R >> >> /Contents {n+1} 0 R >>'.encode())
            lines = [f'Memory manual {doc} page {page} searchable reference {line} alpha beta gamma delta.' for line in range(28)]
            content = ('q 120 0 0 120 400 650 cm /Im Do Q BT /F1 9 Tf 12 TL 40 740 Td ' +
                       ' Tj 0 -12 Td '.join('(' + line + ')' for line in lines) + ' Tj ET').encode()
            compressed = zlib.compress(content)
            objects.append(f'<< /Length {len(compressed)} /Filter /FlateDecode >>\nstream\n'.encode() + compressed + b'\nendstream')
            pixels = zlib.compress(rng.randbytes(128 * 128 * 3))
            objects.append(f'<< /Type /XObject /Subtype /Image /Width 128 /Height 128 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length {len(pixels)} /Filter /FlateDecode >>\nstream\n'.encode() + pixels + b'\nendstream')
        objects[2] = f'<< /Type /Pages /Count {pages} /Kids [{" ".join(kids)}] >>'.encode()
        with path.open('wb') as f:
            f.write(b'%PDF-1.4\n')
            offsets = [0]
            for i, obj in enumerate(objects[1:], 1):
                offsets.append(f.tell())
                f.write(f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n')
            start = f.tell()
            f.write(f'xref\n0 {len(objects)}\n0000000000 65535 f \n'.encode())
            for offset in offsets[1:]:
                f.write(f'{offset:010} 00000 n \n'.encode())
            f.write(f'trailer\n<< /Size {len(objects)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    for path in office:
        shutil.copyfile(path, folder / path.name)



def office_stress(folder):
    # 8 MiB 직전의 DOCX/XLSX: 작은 예제만 통과하는 힙 축소를 거르기 위한 보충 부하.
    for extension, entry, content in [
        ('docx', 'word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
         ''.join('<w:p><w:r><w:t>' + ('manual reference ' * 90) + '</w:t></w:r></w:p>' for _ in range(1000)) + '</w:body></w:document>'),
        ('xlsx', 'xl/worksheets/sheet1.xml', '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' +
         ''.join(f'<row r="{i}"><c r="A{i}" t="inlineStr"><is><t>' + ('manual reference ' * 90) + '</t></is></c></row>' for i in range(1, 1001)) + '</sheetData></worksheet>')]:
        with zipfile.ZipFile(folder / ('sample.' + extension)) as original:
            entries = {n: original.read(n) for n in original.namelist()}
        entries[entry] = content.encode()
        entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace(b'</Types>', b'<Default Extension="bin" ContentType="application/octet-stream"/></Types>')
        entries['padding.bin'] = b'x' * (8 * 1024 * 1024 - sum(len(v) for v in entries.values()) - 32768)
        with zipfile.ZipFile(folder / ('large.' + extension), 'w', compression=zipfile.ZIP_STORED) as target:
            for n, data in entries.items():
                target.writestr(n, data)


def sample(pid):
    result = {'time': time.monotonic()}
    for line in pathlib.Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith(('VmRSS:', 'RssAnon:', 'RssFile:', 'VmSwap:')):
            result[line.split(':')[0] + 'KiB'] = int(line.split()[1])
    relative = pathlib.Path(f'/proc/{pid}/cgroup').read_text().split('0::', 1)[1].strip()
    cg = pathlib.Path('/sys/fs/cgroup') / relative.lstrip('/')
    if not (cg / 'memory.current').exists():
        cg = pathlib.Path('/sys/fs/cgroup')
    try:
        result['cgroupCurrentBytes'] = int((cg / 'memory.current').read_text())
        stats = dict(line.split() for line in (cg / 'memory.stat').read_text().splitlines())
        result['cgroupAnonBytes'] = int(stats['anon'])
        result['cgroupFileBytes'] = int(stats['file'])
        result['cgroupLimit'] = (cg / 'memory.max').read_text().strip()
        result['cgroupEvents'] = dict(line.split() for line in (cg / 'memory.events').read_text().splitlines())
    except (OSError, KeyError):
        result['cgroupUnavailable'] = True
    return result


def run(name, flags, folder, port, probe_gc, idle):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', port))
    dest = OUT / name
    dest.mkdir(parents=True, exist_ok=True)
    data = dest / ('data-' + secrets.token_hex(4))
    jar = ROOT / 'app/build/libs/app-0.1.0.jar'
    with zipfile.ZipFile(jar) as archive:
        if any(n.startswith('BOOT-INF/lib/full-') for n in archive.namelist()):
            raise RuntimeError('lite bootJar를 먼저 빌드하세요')
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    env = os.environ.copy()
    env.pop('JAVA_TOOL_OPTIONS', None)
    env['MALLOC_ARENA_MAX'] = '2'
    stop = threading.Event()
    readings = []
    with (dest / 'server.log').open('w') as log:
        process = subprocess.Popen([str(JAVA), *flags, '-XX:NativeMemoryTracking=summary',
                                    '-Xlog:gc*:file=' + str(dest / 'gc.log'), '-jar', str(jar),
                                    f'--server.port={port}', '--server.address=127.0.0.1', f'--nasquire.data={data}',
                                    '--nasquire.scan-millis=3600000', '--nasquire.stable-millis=0'],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        def monitor():
            while not stop.wait(0.1):
                try:
                    readings.append(sample(process.pid))
                except (OSError, ProcessLookupError):
                    pass
        thread = threading.Thread(target=monitor)
        thread.start()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        csrf = ''
        def api(path, body=None, form=False):
            payload = (urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()) if body is not None else None
            headers = {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json', 'X-CSRF-TOKEN': csrf}
            with opener.open(urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=payload, headers=headers), timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        def snapshot(label):
            value = sample(process.pid)
            cmd = subprocess.run([str(JAVA.with_name('jcmd')), str(process.pid), 'VM.native_memory', 'summary', 'scale=KB'], capture_output=True, text=True, check=True, timeout=20)
            (dest / f'nmt-{label}.txt').write_text(cmd.stdout)
            return value
        try:
            deadline = time.monotonic() + 120
            while True:
                try:
                    auth = api('/api/auth')
                    break
                except OSError:
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('서버 시작 실패: server.log 확인')
                    time.sleep(0.2)
            csrf = auth['csrfToken']
            password = secrets.token_urlsafe(24) + 'a1!'
            api('/api/setup', {'username': 'admin', 'password': password})
            api('/api/login', {'username': 'admin', 'password': password}, form=True)
            csrf = api('/api/auth')['csrfToken']
            time.sleep(3)
            before = snapshot('before')
            source = api('/api/sources', {'name': 'Memory samples', 'path': str(folder)})
            api(f'/api/sources/{source["id"]}/scan', {})
            deadline = time.monotonic() + 900
            peak_nmt = 0
            while True:
                state = api(f'/api/sources/{source["id"]}/status')
                rss = sample(process.pid)['VmRSSKiB']
                if rss > peak_nmt + 16384:
                    snapshot('indexing')
                    peak_nmt = rss
                if state['state'] in ('ready', 'error') and state['pending'] == 0:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('색인 시간 초과')
                time.sleep(0.3)
            after = snapshot('after')
            time.sleep(idle)
            waiting = snapshot('idle')
            result = {'name': name, 'jarSha256': jar_hash, 'flags': flags, 'pagesPerPdf': args.pages, 'pdfs': 18,
                      'documents': 28 if args.office_stress else 26, 'status': state,
                      'before': before, 'after': after, 'idle': waiting,
                      'peakRssKiB': max(r['VmRSSKiB'] for r in readings),
                      'peakAnonKiB': max(r.get('RssAnonKiB', 0) for r in readings),
                      'isolatedCgroup': False, 'idleSeconds': idle}
            if probe_gc:
                subprocess.run([str(JAVA.with_name('jcmd')), str(process.pid), 'GC.run'], capture_output=True, check=True, timeout=20)
                time.sleep(3)
                result['forcedGc'] = snapshot('forced-gc')
            (dest / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            assert state['indexed'] == result['documents'] and state['errors'] == 0 and state['oversized'] == 0, state
            print(json.dumps({'name': name, 'peakRssMiB': round(result['peakRssKiB']/1024, 1),
                              'idleRssMiB': round(waiting['VmRSSKiB']/1024, 1), 'indexed': state['indexed']}), flush=True)
        finally:
            stop.set()
            thread.join()
            (dest / 'samples.json').write_text(json.dumps(readings) + '\n')
            process.terminate()
            try:
                process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='baseline')
    parser.add_argument('--pages', type=int, default=360)
    parser.add_argument('--flags', default='')
    parser.add_argument('--probe-gc', action='store_true')
    parser.add_argument('--idle', type=int, default=20)
    parser.add_argument('--generate', action='store_true')
    parser.add_argument('--office-stress', action='store_true')
    args = parser.parse_args()
    port = int(os.environ.get('VERIFY_PORT', '8104'))
    if port not in range(8100, 8200) or port in (8110, 8120, 8130):
        raise SystemExit('개발 포트 범위 오류')
    OUT.mkdir(parents=True, exist_ok=True)
    folder = OUT / (f'documents-{args.pages}' + ('-office' if args.office_stress else ''))
    if args.generate or not folder.exists() or len(list(folder.iterdir())) != (28 if args.office_stress else 26):
        generate(folder, args.pages)
        if args.office_stress:
            office_stress(folder)
    import shlex
    if not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.name):
        raise SystemExit('측정 이름은 영문·숫자·하이픈·밑줄만 허용합니다')
    run(args.name, BASE + shlex.split(args.flags), folder, port, args.probe_gc, args.idle)
