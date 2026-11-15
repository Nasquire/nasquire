import { useEffect, useState, type FormEvent } from 'react'
import RemoteServers, { type RemoteServer } from './Remote'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
type Decision = { path: string; page: number; reason: string; result: string }
type Pages = { converted: number; failed: number; skipped: number; retryWaiting: number; recent: Decision[] }
/** 원격 Docling(docling-serve) 연결 설정(/api/docling). 연결되면 색인 때 표·그림 의심 쪽만 Docling으로 보낸다. 기준값·쪽 처리 집계도 여기서 본다. */
export default function Docling({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, unknown>>()
  const [docling, setDocling] = useState('off')
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { const next = await api<Record<string, unknown>>('/docling'); setSettings(next); setDocling(String(next.docling)) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const data: Record<string, unknown> = Object.fromEntries(new FormData(e.currentTarget))
    if (typeof data.servers === 'string') data.servers = JSON.parse(data.servers)
    setBusy(true); setError(''); setStatus('')
    try { await api('/docling', 'PUT', data); await refresh(); setStatus(t('Settings saved', '설정을 저장했습니다')) }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  return <section className="card" id="docling-settings">
    <p>{t('Optional connection to docling-serve for table and figure layout. While connected, indexing sends only PDF pages and single images that look like tables or figures, one page at a time; other pages keep the regular extraction. HWP/HWPX and Office files keep their own table structure. The API key is sent as X-Api-Key.', '표·그림 레이아웃 변환용 docling-serve 선택 연결입니다. 연결되면 색인 때 표·그림으로 보이는 PDF 쪽과 단일 이미지만 한 쪽씩 보내고 나머지 쪽은 기존 추출을 씁니다. HWP/HWPX·Office는 원본 표 구조를 씁니다. API 키는 X-Api-Key로 보냅니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{status}</p>
    {settings && <>
      {Boolean(settings.disabledReason) && <p className="error" role="alert">{t('Docling disabled: ', 'Docling이 비활성화되었습니다: ')}{String(settings.disabledReason)}</p>}
      {Boolean(settings.keyError) && <p className="error" role="alert">{t('A saved key could not be decrypted. Restore embedding.key or enter the key again.', '저장된 키를 복호화할 수 없습니다. embedding.key를 복원하거나 키를 다시 입력하세요.')}</p>}
      <form key={JSON.stringify(settings)} onSubmit={save}>
        <fieldset className="stack"><legend>Docling</legend><label>{t('Provider', '공급자')}<select name="docling" defaultValue={String(settings.docling)} onChange={e => setDocling(e.target.value)}><option value="off">{t('Off', '없음')}</option><option value="remote">{t('Remote (docling-serve)', '원격 (docling-serve)')}</option></select></label>
        {docling === 'remote' && <>
          <RemoteServers api={api} feature="docling" saved={(settings.servers ?? []) as RemoteServer[]} defaultTimeout={Math.min(Number(settings.timeoutSeconds), 300)} maxTimeout={Number(settings.maxServerTimeoutSeconds ?? 300)} />
          <p className="notice">{t('Document files are sent to these servers. Plain HTTP sends keys and files unencrypted.', '문서 파일이 설정한 서버로 전송됩니다. 평문 HTTP는 키와 파일을 암호화하지 않습니다.')}</p>
          <label>{t('Plain HTTP to non-loopback hosts', '루프백이 아닌 주소의 평문 HTTP')}<select name="allowInsecureHttp" defaultValue={String(settings.allowInsecureHttp)}><option value="false">{t('Blocked (default)', '차단 (기본)')}</option><option value="true">{t('Allowed (trusted network only)', '허용 (신뢰하는 네트워크에서만)')}</option></select></label>
          {Boolean(settings.insecureHttp) && <p className="error" role="alert">{t('Warning: a remote URL uses plain HTTP.', '경고: 원격 주소가 평문 HTTP입니다.')}</p>}
          <div className="row"><label>{t('Total timeout seconds', '전체 타임아웃 (초)')}<input name="timeoutSeconds" type="number" required min="1" max="600" defaultValue={String(settings.timeoutSeconds)} /></label>
            <label>{t('Maximum file size (MiB)', '파일 크기 상한 (MiB)')}<input name="maxFileMiB" type="number" required min="1" max="64" defaultValue={String(settings.maxFileMiB)} /></label></div>
          <fieldset className="stack"><legend>{t('Table and figure page detection', '표·그림 의심 쪽 판단')}</legend>
            <small>{t('Changes apply to files indexed next. Reindex a source to apply them to existing files.', '바꾼 기준은 다음에 색인하는 파일부터 적용됩니다. 기존 파일은 소스를 재색인하세요.')}</small>
            <div className="row"><label>{t('Minimum ruled lines (each direction)', '최소 직선 수 (가로·세로 각각)')}<input name="minGridLines" type="number" required min="2" max="50" defaultValue={String(settings.minGridLines)} /></label>
              <label>{t('Minimum aligned text columns', '최소 정렬 글자 열 수')}<input name="minColumns" type="number" required min="2" max="20" defaultValue={String(settings.minColumns)} /></label>
              <label>{t('Minimum aligned rows per column', '열마다 최소 정렬 줄 수')}<input name="minRows" type="number" required min="2" max="100" defaultValue={String(settings.minRows)} /></label></div>
            <div className="row"><label>{t('Minimum image area (% of page, 0 = off)', '최소 이미지 면적 (쪽 대비 %, 0=끔)')}<input name="minImagePercent" type="number" required min="0" max="100" defaultValue={String(settings.minImagePercent)} /></label>
              <label>{t('Maximum pages per file', '파일당 최대 쪽 수')}<input name="maxPages" type="number" required min="1" max="100" defaultValue={String(settings.maxPages)} /></label>
              <label>{t('Retries after busy or failed Docling', 'Docling 혼잡·실패 뒤 재시도 횟수')}<input name="maxRetries" type="number" required min="0" max="10" defaultValue={String(settings.maxRetries)} /></label></div>
          </fieldset></>}
        </fieldset><button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form>
      {settings.pages !== undefined && (() => { const pages = settings.pages as Pages; return <div id="docling-pages">
        <p>{t('Pages converted / failed / skipped since start', '시작 뒤 변환 / 실패 / 건너뛴 쪽')}: {pages.converted} / {pages.failed} / {pages.skipped} · {t('Files waiting for retry', '재시도 대기 파일')}: {pages.retryWaiting}</p>
        {pages.recent.length > 0 && <ul>{pages.recent.map((d, i) => <li key={i}><code>{d.path}</code> p.{d.page} · {d.reason} · {({ converted: t('replaced', '교체'), supplemented: t('supplemented', '보강'), kept: t('kept', '유지'), failed: t('failed, kept regular text', '실패·기존 유지'), retry: t('busy, retry later', '혼잡·나중에 재시도'), skipped: t('skipped', '건너뜀') } as Record<string, string>)[d.result] ?? d.result}</li>)}</ul>}
      </div> })()}</>}
  </section>
}
