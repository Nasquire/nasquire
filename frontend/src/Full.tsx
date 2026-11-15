import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
/** full 전용 로컬 Tesseract 실행 제한. OCR 선택·쪽 상한은 OCR 설정(Ocr.tsx), 리랭커는 Reranker.tsx로 옮겼다. */
export default function Full({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, unknown>>()
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { setSettings(await api<Record<string, unknown>>('/full')) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const form = e.currentTarget; const data = Object.fromEntries(new FormData(form))
    setBusy(true); setError(''); setStatus('')
    try { await api('/full', 'PUT', data); form.reset(); await refresh(); setStatus(t('Settings saved', '설정을 저장했습니다')) }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  return <section className="card" id="full-settings">
    <p>{t('Limits for local Tesseract (full build). Choose Tesseract in the OCR settings above. Changing limits while Tesseract is active rebuilds all sources.', '로컬 Tesseract(full 빌드)의 실행 제한입니다. 위 OCR 설정에서 Tesseract를 선택하세요. Tesseract 사용 중 제한을 바꾸면 전체 소스를 재색인합니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{status}</p>
    {Boolean(settings?.available) && settings && <form key={JSON.stringify(settings)} onSubmit={save}>
        <fieldset className="stack"><legend>Tesseract</legend>
        <div className="row">{(['ocrTimeoutSeconds', 'ocrMemoryMiB'] as const).map((key, i) => <label key={key}>{[t('OCR timeout seconds', 'OCR 타임아웃 (초)'), t('OCR memory MiB', 'OCR 메모리 MiB')][i]}<input name={key} type="number" required min={[1, 128][i]} max={[60, 2048][i]} defaultValue={String(settings[key])} /></label>)}</div>
        </fieldset><button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form>}
  </section>
}
