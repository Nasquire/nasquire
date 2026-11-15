import { useEffect, useState, type FormEvent } from 'react'
import RemoteServers, { type RemoteServer } from './Remote'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
/** OCR 일반 설정(/api/ocr). lite는 없음·원격, full은 Tesseract도 선택할 수 있다(서로 대체하지 않음). Tesseract 실행 제한은 Full.tsx. */
export default function Ocr({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, unknown>>()
  const [ocr, setOcr] = useState('off')
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { const next = await api<Record<string, unknown>>('/ocr'); setSettings(next); setOcr(String(next.ocr)) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const data: Record<string, unknown> = Object.fromEntries(new FormData(e.currentTarget))
    if (typeof data.servers === 'string') data.servers = JSON.parse(data.servers)
    setBusy(true); setError(''); setStatus('')
    try { await api('/ocr', 'PUT', data); await refresh(); setStatus(t('Settings saved', '설정을 저장했습니다')) }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  const full = Boolean(settings?.fullAvailable)
  return <section className="card" id="ocr-settings">
    <p>{t('Optional OCR for images and PDF pages without a text layer. Changing the provider, engine or page limit rebuilds all sources. If every remote server fails, the file fails; there is no local fallback.', '이미지와 글자 층이 없는 PDF 쪽을 위한 선택 OCR입니다. 공급자·엔진·쪽 상한을 바꾸면 전체 소스를 재색인합니다. 원격 서버가 모두 실패하면 해당 파일은 실패하며 로컬로 대체하지 않습니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{status}</p>
    {settings && <>
      {Boolean(settings.disabledReason) && <p className="error" role="alert">{t('OCR disabled: ', 'OCR이 비활성화되었습니다: ')}{String(settings.disabledReason)}</p>}
      {Boolean(settings.keyError) && <p className="error" role="alert">{t('A saved key could not be decrypted. Restore embedding.key or enter the key again.', '저장된 키를 복호화할 수 없습니다. embedding.key를 복원하거나 키를 다시 입력하세요.')}</p>}
      <form key={JSON.stringify(settings)} onSubmit={save}>
        <fieldset className="stack"><legend>OCR</legend><label>{t('Provider', '공급자')}<select name="ocr" defaultValue={String(settings.ocr)} onChange={e => setOcr(e.target.value)}><option value="off">{t('Off', '없음')}</option><option value="remote">{t('Remote (Nasquire ML /ocr)', '원격 (Nasquire ML /ocr)')}</option>{full && <option value="tesseract">Tesseract (kor + eng)</option>}</select></label>
        {ocr === 'remote' && <><label>{t('Engine', '엔진')}<input name="engine" required defaultValue={String(settings.engine)} /></label>
          <RemoteServers api={api} feature="ocr" saved={(settings.servers ?? []) as RemoteServer[]} defaultTimeout={Number(settings.timeoutSeconds)} maxTimeout={Number(settings.maxServerTimeoutSeconds ?? 300)} />
          <p className="notice">{t('Rendered page images are sent to these servers. Plain HTTP sends keys and images unencrypted.', '렌더링한 쪽 이미지가 설정한 서버로 전송됩니다. 평문 HTTP는 키와 이미지를 암호화하지 않습니다.')}</p>
          <label>{t('Plain HTTP to non-loopback hosts', '루프백이 아닌 주소의 평문 HTTP')}<select name="allowInsecureHttp" defaultValue={String(settings.allowInsecureHttp)}><option value="false">{t('Blocked (default)', '차단 (기본)')}</option><option value="true">{t('Allowed (trusted network only)', '허용 (신뢰하는 네트워크에서만)')}</option></select></label>
          {Boolean(settings.insecureHttp) && <p className="error" role="alert">{t('Warning: a remote URL uses plain HTTP.', '경고: 원격 주소가 평문 HTTP입니다.')}</p>}</>}
        {ocr !== 'off' && <div className="row"><label>{t('OCR maximum pages', 'OCR 최대 쪽 수')}<input name="maxPages" type="number" required min="1" max="100" defaultValue={String(settings.maxPages)} /></label>
          {ocr === 'remote' && <label>{t('Total timeout per page (seconds)', '쪽당 전체 타임아웃 (초)')}<input name="timeoutSeconds" type="number" required min="1" max="300" defaultValue={String(settings.timeoutSeconds)} /></label>}</div>}
        </fieldset><button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form></>}
  </section>
}
