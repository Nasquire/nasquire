import { useState } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
export type RemoteServer = { name: string; baseUrl: string; timeoutSeconds: number | string; hasKey?: boolean; insecureHttp?: boolean; state?: string; lastError?: string; apiKey?: string; clearKey?: boolean }
const states: Record<string, string> = { up: t('Up', '정상'), down: t('Down', '연결 실패'), open: t('Excluded (retrying later)', '제외 중 (잠시 후 시험 요청)'), unknown: t('Not contacted yet', '아직 호출 없음'), off: t('Not saved', '저장 전') }

const sameServer: Record<string, string> = {
  ocr: t('Every server must run the same OCR engine.', '모든 서버는 같은 OCR 엔진이어야 합니다.'),
  docling: t('Every server should run the same docling-serve version.', '모든 서버는 같은 docling-serve 버전이어야 합니다.'),
}
const placeholders: Record<string, string> = { docling: 'https://docling.example.com' }

/** 원격 서버 목록 편집. 저장은 상위 폼이 hidden servers 값(JSON)으로 보낸다. 시험·키 삭제는 저장된 서버만. OCR·Docling도 같은 화면을 쓴다. */
export default function RemoteServers({ api, feature, saved, defaultTimeout, maxTimeout }: { api: Api; feature: 'embeddings' | 'reranker' | 'ocr' | 'docling'; saved: RemoteServer[]; defaultTimeout: number; maxTimeout: number }) {
  const [rows, setRows] = useState<RemoteServer[]>(() => saved.length ? saved.map(s => ({ ...s, apiKey: '' })) : [{ name: 'default', baseUrl: '', timeoutSeconds: defaultTimeout, apiKey: '' }])
  const [result, setResult] = useState('')
  const [busy, setBusy] = useState(false)
  const update = (i: number, patch: Partial<RemoteServer>) => setRows(rows.map((row, j) => j === i ? { ...row, ...patch } : row))
  const payload = rows.map(({ name, baseUrl, timeoutSeconds, apiKey }) => ({ name, baseUrl, timeoutSeconds: String(timeoutSeconds), ...(apiKey ? { apiKey } : {}) }))
  async function run(action: () => Promise<string>) {
    setBusy(true); setResult('')
    try { setResult(await action()) } catch (e) { setResult(String(e)) } finally { setBusy(false) }
  }
  return <fieldset className="stack"><legend>{t('Remote servers', '원격 서버')}</legend>
    <p>{t('Requests are spread round robin. Connection failures, timeouts, 5xx and 429 move to the next server; other errors fail at once. Every server must serve the same model and dimensions.', '요청을 순서대로 나눠 보냅니다. 연결 실패·시간 초과·5xx·429만 다음 서버로 넘기고 그 밖의 오류는 바로 실패합니다. 모든 서버는 같은 모델·차원이어야 합니다.')}</p>
    <input type="hidden" name="servers" value={JSON.stringify(payload)} />
    {rows.map((row, i) => {
      const stored = saved.find(s => s.name === row.name && s.baseUrl === row.baseUrl)
      return <div className="card" key={i}>
        <div className="row"><label>{t('Name', '이름')}<input required pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,63}" value={row.name} onChange={e => update(i, { name: e.target.value })} /></label>
          <label>{t('Base URL', '기본 주소')}<input type="url" required placeholder={placeholders[feature] ?? 'https://api.example.com/v1'} value={row.baseUrl} onChange={e => update(i, { baseUrl: e.target.value })} /></label>
          <label>{t('Timeout seconds', '타임아웃 (초)')}<input type="number" required min="1" max={maxTimeout} value={String(row.timeoutSeconds)} onChange={e => update(i, { timeoutSeconds: e.target.value })} /></label></div>
        <label>{t('API key (blank keeps saved key; changing the name or URL discards it)', 'API 키 (빈 값은 기존 키 유지, 이름·주소를 바꾸면 기존 키 삭제)')}<input type="password" autoComplete="new-password" placeholder={stored?.hasKey ? t('Saved; hidden', '저장됨·비공개') : ''} value={row.apiKey ?? ''} onChange={e => update(i, { apiKey: e.target.value })} /></label>
        {stored && <p role="status"><span className="pill">{states[stored.state ?? 'off'] ?? stored.state}</span>{stored.lastError ? ` · ${stored.lastError}` : ''}</p>}
        {row.baseUrl.startsWith('http:') && <p className="notice">{t('HTTP sends keys and document text without encryption.', 'HTTP는 키와 문서 내용을 암호화하지 않습니다.')}</p>}
        <div className="row">
          {stored && <button type="button" disabled={busy} onClick={() => void run(async () => { const r = await api<{ result: string; detail: string }>(`/${feature}/test?server=${encodeURIComponent(row.name)}`, 'POST'); return `${row.name}: ${({ ok: t('Connected', '연결 성공'), down: t('Unreachable', '연결 불가'), mismatch: feature === 'docling' ? t('Version differs', '버전 불일치') : feature === 'ocr' ? t('OCR engine differs', 'OCR 엔진 불일치') : t('Model or dimensions differ', '모델·차원 불일치'), error: t('Error response', '오류 응답') } as Record<string, string>)[r.result] ?? r.result}${r.detail ? ` (${r.detail})` : ''}` })}>{t('Test this server', '이 서버 연결 시험')}</button>}
          {stored?.hasKey && <button type="button" disabled={busy} onClick={() => void run(async () => { await api(`/${feature}/key?server=${encodeURIComponent(row.name)}`, 'DELETE'); update(i, { hasKey: false }); return t('Key deleted. Reopen settings to refresh.', '키를 삭제했습니다. 설정을 다시 열면 갱신됩니다.') })}>{t('Delete saved API key', '저장된 API 키 삭제')}</button>}
          {rows.length > 1 && <button type="button" onClick={() => setRows(rows.filter((_, j) => j !== i))}>{t('Remove server', '서버 삭제')}</button>}
        </div>
      </div>
    })}
    {rows.length < 8 && <button type="button" onClick={() => setRows([...rows, { name: `server-${rows.length + 1}`, baseUrl: '', timeoutSeconds: defaultTimeout, apiKey: '' }])}>{t('Add server', '서버 추가')}</button>}
    <p role="status">{result}</p>
  </fieldset>
}
