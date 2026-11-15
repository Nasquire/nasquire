import { useEffect, useState, type FormEvent } from 'react'
import RemoteServers, { type RemoteServer } from './Remote'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
/** 리랭커 검색 설정. 원격은 lite·full 공통, 내장 ONNX는 full에서만. */
export default function Reranker({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, unknown>>()
  const [reranker, setReranker] = useState('off')
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { const next = await api<Record<string, unknown>>('/reranker'); setSettings(next); setReranker(String(next.reranker)) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); const data: Record<string, unknown> = Object.fromEntries(new FormData(e.currentTarget))
    if (typeof data.servers === 'string') data.servers = JSON.parse(data.servers)
    setBusy(true); setError(''); setStatus('')
    try { await api('/reranker', 'PUT', data); await refresh(); setStatus(t('Settings saved', '설정을 저장했습니다')) }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  const builtin = Boolean(settings?.builtinAvailable)
  return <section className="card" id="reranker-settings">
    <p>{t('Optional reranking of the top results. Search requests must opt in (REST rerank=true, MCP rerank: true). If every remote server fails, the original order is kept.', '상위 결과를 다시 정렬하는 선택 기능입니다. 검색 요청에서 별도로 선택합니다(REST rerank=true, MCP rerank: true). 원격 서버가 모두 실패하면 원래 순서를 유지합니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{status}</p>
    {settings && <>
      {Boolean(settings.disabledReason) && <p className="error" role="alert">{t('Reranker disabled: ', '리랭커가 비활성화되었습니다: ')}{String(settings.disabledReason)}</p>}
      {Boolean(settings.keyError) && <p className="error" role="alert">{t('A saved key could not be decrypted. Restore embedding.key or enter the key again.', '저장된 키를 복호화할 수 없습니다. embedding.key를 복원하거나 키를 다시 입력하세요.')}</p>}
      <form key={JSON.stringify(settings)} onSubmit={save}>
        <fieldset className="stack"><legend>{t('Reranker', '리랭커')}</legend><label>{t('Provider', '공급자')}<select name="reranker" defaultValue={String(settings.reranker)} onChange={e => setReranker(e.target.value)}><option value="off">{t('Off', '없음')}</option><option value="remote">{t('Remote (/rerank)', '원격 (/rerank)')}</option>{builtin && <option value="builtin">{t('Built-in ONNX', '내장 ONNX')}</option>}</select></label>
        {reranker === 'remote' && <><label>{t('Model', '모델')}<input name="model" required defaultValue={String(settings.model)} /></label>
          <RemoteServers api={api} feature="reranker" saved={(settings.servers ?? []) as RemoteServer[]} defaultTimeout={Number(settings.timeoutSeconds)} maxTimeout={30} />
          <p className="notice">{t('Document content is sent to these servers. Plain HTTP sends keys and text unencrypted.', '문서 내용이 설정한 서버로 전송됩니다. 평문 HTTP는 키와 본문을 암호화하지 않습니다.')}</p>
          <label>{t('Plain HTTP to non-loopback hosts', '루프백이 아닌 주소의 평문 HTTP')}<select name="allowInsecureHttp" defaultValue={String(settings.allowInsecureHttp)}><option value="false">{t('Blocked (default)', '차단 (기본)')}</option><option value="true">{t('Allowed (trusted network only)', '허용 (신뢰하는 네트워크에서만)')}</option></select></label>
          {Boolean(settings.insecureHttp) && <p className="error" role="alert">{t('Warning: a remote URL uses plain HTTP.', '경고: 원격 주소가 평문 HTTP입니다.')}</p>}</>}
        {builtin && reranker === 'builtin' && <><p className="notice">{t('Measured RSS exceeds 512 MiB. Model files must be inside the data folder.', '실측 RSS는 512 MiB를 초과합니다. 모델 파일은 데이터 폴더 안에 두세요.')}</p>
          {(['localModelPath', 'localTokenizerPath'] as const).map((key, i) => <label key={key}>{[t('Model path (empty = default)', '모델 경로 (빈 값=기본)'), t('Tokenizer path (empty = default)', '토크나이저 경로 (빈 값=기본)')][i]}<input name={key} defaultValue={String(settings[key])} /></label>)}
          <label>{t('Download missing model files', '없는 모델 파일 다운로드')}<select name="localDownload" defaultValue={String(settings.localDownload)}><option value="false">{t('Disabled', '끔')}</option><option value="true">{t('Enabled', '켬')}</option></select></label></>}
        {reranker !== 'off' && <div className="row">{(['topN', 'timeoutSeconds'] as const).map((key, i) => <label key={key}>{[t('Top N', '상위 N개'), t('Total timeout seconds', '전체 타임아웃 (초)')][i]}<input name={key} type="number" required min="1" max={[100, 30][i]} defaultValue={String(settings[key])} /></label>)}</div>}
        </fieldset><button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form></>}
  </section>
}
