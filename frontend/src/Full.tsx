import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
export default function Full({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, unknown>>()
  const [reranker, setReranker] = useState('off')
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { const next = await api<Record<string, unknown>>('/full'); setSettings(next); setReranker(String(next.reranker)) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const form = e.currentTarget; const data = Object.fromEntries(new FormData(form))
    setBusy(true); setError(''); setStatus('')
    try { await api('/full', 'PUT', data); form.reset(); await refresh(); setStatus(t('Settings saved', '설정을 저장했습니다')) }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  return <section className="card"><h1>{t('Full settings', 'full 설정')}</h1>
    <p>{t('Optional reranking and OCR. OCR changes rebuild all sources. Search requests must opt in to reranking.', '선택 리랭커·OCR 설정입니다. OCR 변경 시 전체 소스를 재색인합니다. 리랭커는 검색 요청에서 별도로 선택합니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{status}</p>
    {settings && <>{!settings.available && <p className="notice">{t('The full module is unavailable in this lite build. Use a full build to enable these features.', 'lite 빌드에는 full 모듈이 없습니다. 기능을 켜려면 full 빌드를 사용하세요.')}</p>}
      {Boolean(settings.disabledReason) && <p className="error" role="alert">{t('Reranker disabled: use HTTPS or explicitly allow plain HTTP.', '리랭커가 비활성화되었습니다. HTTPS를 사용하거나 평문 HTTP 허용을 명시하세요.')}</p>}
      <form key={JSON.stringify(settings)} onSubmit={save}>
        <label>{t('Reranker', '리랭커')}<select name="reranker" defaultValue={String(settings.reranker)} onChange={e => setReranker(e.target.value)}><option value="off">{t('Off', '없음')}</option><option value="remote" disabled={!settings.available}>{t('Remote', '원격')}</option><option value="builtin" disabled={!settings.available}>{t('Built-in ONNX', '내장 ONNX')}</option></select></label>
        {reranker === 'remote' && <><label>{t('Base URL', '기본 주소')}<input name="baseUrl" type="url" required defaultValue={String(settings.baseUrl)} /></label><label>{t('Model', '모델')}<input name="model" required defaultValue={String(settings.model)} /></label>
          <label>{t('API key (blank keeps saved key; changing URL discards it)', 'API 키 (빈 값은 유지, 주소 변경 시 삭제)')}<input name="apiKey" type="password" autoComplete="new-password" /></label>
          <p className="notice">{t('Document content is sent to this server. Plain HTTP sends keys and text unencrypted.', '문서 내용이 설정한 서버로 전송됩니다. 평문 HTTP는 키와 본문을 암호화하지 않습니다.')}</p>
          <label>{t('Plain HTTP to non-loopback hosts', '루프백이 아닌 주소의 평문 HTTP')}<select name="allowInsecureHttp" defaultValue={String(settings.allowInsecureHttp)}><option value="false">{t('Blocked (default)', '차단 (기본)')}</option><option value="true">{t('Allowed (trusted network only)', '허용 (신뢰하는 네트워크에서만)')}</option></select></label></>}
        {reranker === 'builtin' && <><p className="notice">{t('Measured RSS exceeds 512 MiB. Model files must be inside the data folder.', '실측 RSS는 512 MiB를 초과합니다. 모델 파일은 데이터 폴더 안에 두세요.')}</p>
          {(['localModelPath', 'localTokenizerPath'] as const).map((key, i) => <label key={key}>{[t('Model path (empty = default)', '모델 경로 (빈 값=기본)'), t('Tokenizer path (empty = default)', '토크나이저 경로 (빈 값=기본)')][i]}<input name={key} defaultValue={String(settings[key])} /></label>)}
          <label>{t('Download missing model files', '없는 모델 파일 다운로드')}<select name="localDownload" defaultValue={String(settings.localDownload)}><option value="false">{t('Disabled', '끔')}</option><option value="true">{t('Enabled', '켬')}</option></select></label></>}
        {reranker !== 'off' && <div className="row">{(['topN', 'timeoutSeconds'] as const).map((key, i) => <label key={key}>{[t('Top N', '상위 N개'), t('Timeout seconds', '타임아웃 (초)')][i]}<input name={key} type="number" required min="1" max={[100, 30][i]} defaultValue={String(settings[key])} /></label>)}</div>}
        {Boolean(settings.hasKey) && <button type="button" disabled={busy} onClick={() => { setBusy(true); void api('/full/key', 'DELETE').then(refresh).catch(e => setError(String(e))).finally(() => setBusy(false)) }}>{t('Delete saved API key', '저장된 API 키 삭제')}</button>}
        <label>OCR<select name="ocr" defaultValue={String(settings.ocr)}><option value="off">{t('Off', '없음')}</option><option value="tesseract" disabled={!settings.available}>Tesseract (kor + eng)</option></select></label>
        {Boolean(settings.available) && <div className="row">{(['ocrTimeoutSeconds', 'ocrMemoryMiB', 'ocrMaxPages'] as const).map((key, i) => <label key={key}>{[t('OCR timeout seconds', 'OCR 타임아웃 (초)'), t('OCR memory MiB', 'OCR 메모리 MiB'), t('OCR maximum pages', 'OCR 최대 쪽 수')][i]}<input name={key} type="number" required min={[1, 128, 1][i]} max={[60, 2048, 100][i]} defaultValue={String(settings[key])} /></label>)}</div>}
        <button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form></>}
  </section>
}
