import { useEffect, useState } from 'react'

type Client = { ID: string; NAME: string; REDIRECTS: string; APPROVED: boolean }
type Grant = { ID: string; CLIENT_ID: string; SOURCES: string; EXPIRES: number; REVOKED: boolean }
type State = { enabled: boolean; disabledReason: string; clients: Client[]; grants: Grant[] }
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
export default function OAuth({ api }: { api: <T>(path: string, method?: string, body?: unknown) => Promise<T> }) {
  const [state, setState] = useState<State>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function refresh() { setState(await api<State>('/oauth')) }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [])
  async function revoke(kind: string, id: string) {
    setBusy(true); setError('')
    try { await api('/oauth/revoke', 'POST', { kind, id }); await refresh() }
    catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  return <section className="card"><h2>MCP OAuth</h2>
    {error && <p role="alert" className="error">{error}</p>}
    {state && <><p>{state.enabled ? t('Enabled · read only · MCP only', '활성 · 읽기 전용 · MCP 전용') : state.disabledReason}</p>
      <p>{t('Enable with nasquire.oauth.enabled=true and an HTTPS nasquire.public-url. Each approval grants only selected sources for up to 30 days. REST requires an API token.', 'nasquire.oauth.enabled=true와 HTTPS nasquire.public-url로 켭니다. 승인별 선택 소스만 최대 30일 허용합니다. REST에는 API 토큰이 필요합니다.')}</p>
      <h3>{t('Registered clients', '등록 클라이언트')}</h3>
      {state.clients.map(c => <div key={c.ID}><strong>{c.NAME}</strong><p style={{ overflowWrap: 'anywhere' }}>{c.ID} · {c.REDIRECTS}</p><p>{c.APPROVED ? t('Approved', '승인 이력 있음') : t('No approval (expires after 24 hours)', '미승인 (24시간 뒤 만료)')}</p><button disabled={busy} onClick={() => void revoke('client', c.ID)}>{t('Remove client and revoke all grants', '클라이언트 삭제·전체 승인 철회')}</button></div>)}
      <h3>{t('Approvals', '승인 목록')}</h3>
      {state.grants.map(g => <div key={g.ID}><p>{state.clients.find(c => c.ID === g.CLIENT_ID)?.NAME ?? g.CLIENT_ID} · {g.SOURCES || t('No sources', '소스 없음')} · {new Date(g.EXPIRES).toLocaleString()}</p><button disabled={busy || g.REVOKED} onClick={() => void revoke('grant', g.ID)}>{g.REVOKED ? t('Revoked', '철회됨') : t('Revoke approval', '승인 철회')}</button></div>)}
    </>}
  </section>
}
