import { useEffect, useState, type FormEvent } from 'react'
import { useTheme } from './theme'

const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Auth = { configured: boolean; authenticated: boolean; csrfToken: string; csrfHeader: string }
type Source = { id: string; name: string; path: string; includes: string[]; excludes: string[]; status: { state: string; indexed: number; pending: number; errors: number; message: string } }
type Token = { id: string; name: string; expiresAt: number; sources: string[]; revoked: boolean }
type Hit = { source: string; sourceName: string; path: string; page: number; text: string; score: number }
const pages = ['sources', 'tokens', 'search', 'about'] as const
type Page = typeof pages[number]
const titles = { sources: t('Sources', '소스 관리'), tokens: t('API tokens', 'API 토큰'), search: t('Search playground', '검색 시험'), about: t('About', '정보') }

export default function App() {
  const [auth, setAuth] = useState<Auth>()
  const [error, setError] = useState('')
  const [page, setPage] = useState<Page>('sources')
  const [sources, setSources] = useState<Source[]>([])
  const [tokens, setTokens] = useState<Token[]>([])
  const [audits, setAudits] = useState<Record<string, unknown>[]>([])
  const [secret, setSecret] = useState('')
  const [hits, setHits] = useState<Hit[]>([])
  const [searched, setSearched] = useState(false)
  const [document, setDocument] = useState('')
  const [edit, setEdit] = useState<Source>()
  const [busy, setBusy] = useState(false)
  const { theme, setTheme } = useTheme()
  async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (auth) headers[auth.csrfHeader] = auth.csrfToken
    const res = await fetch('/api' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    if (!res.ok) {
      if (res.status === 401) void refreshAuth()
      throw new Error(t('Request failed', '요청 실패') + ` (${res.status})`)
    }
    const text = await res.text()
    return text ? JSON.parse(text) as T : undefined as T
  }
  async function refreshAuth() { const res = await fetch('/api/auth'); if (!res.ok) throw new Error(`HTTP ${res.status}`); const state = await res.json() as Auth; setAuth(state); return state }
  async function refresh() {
    const [s, tok, log] = await Promise.all([api<Source[]>('/sources'), api<Token[]>('/tokens'), api<Record<string, unknown>[]>('/audit')])
    setSources(s); setTokens(tok); setAudits(log)
  }
  async function run(action: () => Promise<unknown>) { setError(''); setBusy(true); try { await action() } catch (e) { setError(String(e)) } finally { setBusy(false) } }
  useEffect(() => { void refreshAuth().catch(e => setError(String(e))) }, [])
  useEffect(() => {
    if (!auth?.authenticated) return
    void refresh().catch(e => setError(String(e)))
    const timer = setInterval(() => { void refresh().catch(e => setError(String(e))) }, 5000)
    return () => clearInterval(timer)
  }, [auth?.authenticated])
  useEffect(() => { globalThis.document.documentElement.lang = ko ? 'ko' : 'en' }, [])

  function submit(e: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<unknown>) {
    e.preventDefault(); const form = e.currentTarget; const data = new FormData(form)
    void run(() => action(data))
  }
  if (!auth) return <div className="auth">Nasquire — {error || t('Loading…', '불러오는 중…')}</div>
  if (!auth.authenticated) return <main className="auth">
    <div className="brand"><img className="logo" src="/icon.svg" alt="" />Nasquire</div>
    <h1>{auth.configured ? t('Welcome back', '로그인') : t('Set up Nasquire', 'Nasquire 첫 설정')}</h1>
    <p>{t('Search the knowledge in your folders.', '폴더 속 지식을 검색하세요.')}</p>
    <form onSubmit={e => submit(e, async data => {
      if (!auth.configured) await api('/setup', 'POST', Object.fromEntries(data))
      const res = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', [auth.csrfHeader]: auth.csrfToken }, body: new URLSearchParams(data as unknown as Record<string, string>) })
      if (!res.ok) throw new Error(t('Login failed', '로그인 실패'))
      await refreshAuth()
    })}>
      <label>{t('Username', '사용자명')}<input name="username" required minLength={3} maxLength={100} pattern="[A-Za-z0-9_.\-]+" autoComplete="username" /></label>
      <label>{t('Password', '비밀번호')}<input name="password" type="password" required minLength={auth.configured ? 1 : 12} autoComplete={auth.configured ? 'current-password' : 'new-password'} /></label>
      {!auth.configured && <small>{t('Use at least 12 characters (up to 72 UTF-8 bytes). This creates the only administrator.', '12자 이상, UTF-8 72바이트 이하. 단일 관리자 계정을 생성합니다.')}</small>}
      <button className="primary" disabled={busy}>{auth.configured ? t('Sign in', '로그인') : t('Create administrator', '관리자 생성')}</button>
    </form><p className="error" role="alert">{error}</p>
  </main>

  return <div className="app"><aside className="sidebar">
    <div className="sidebar-top"><img className="logo" src="/icon.svg" alt="" /><span className="brand">Nasquire</span></div>
    <nav className="new-menu" aria-label={t('Navigation', '메뉴')}>{pages.map(p => <button key={p} className={p === page ? 'active' : ''} aria-current={p === page ? 'page' : undefined} onClick={() => { setPage(p); setSecret(''); setError('') }}>{titles[p]}</button>)}</nav>
    <div className="sidebar-bottom"><select aria-label={t('Theme', '테마')} value={theme} onChange={e => setTheme(e.target.value as typeof theme)}><option value="auto">{t('System theme', '자동 테마')}</option><option value="light">{t('Light', '라이트')}</option><option value="dark">{t('Dark', '다크')}</option></select><button onClick={() => void run(async () => { await api('/logout', 'POST'); setSecret(''); setHits([]); setDocument(''); await refreshAuth() })}>{t('Sign out', '로그아웃')}</button></div>
  </aside><main className="main"><header>{titles[page]}</header><div className="content">
    {error && <p className="error" role="alert">{error}</p>}
    {page === 'sources' && <><h1>{t('Your knowledge, connected', '내 폴더를 지식으로')}</h1><p>{t('Files stay in place. Nasquire only reads your sources.', '원본은 제자리에. Nasquire는 소스를 읽기만 합니다.')}</p>
      <section className="card"><h2>{edit ? t('Edit source', '소스 편집') : t('Add a source', '소스 등록')}</h2><form key={edit?.id ?? 'new'} onSubmit={e => submit(e, async data => {
        const globs = (key: string) => String(data.get(key)).split('\n').map(v => v.trim()).filter(Boolean)
        await api('/sources' + (edit ? '/' + edit.id : ''), edit ? 'PUT' : 'POST', { name: data.get('name'), path: data.get('path'), includes: globs('includes'), excludes: globs('excludes') }); setEdit(undefined); await refresh()
      })}>
        <div className="row"><label>{t('Name', '이름')}<input name="name" required maxLength={200} defaultValue={edit?.name} /></label><label>{t('Folder in container', '컨테이너 안 폴더')}<input name="path" required placeholder="/sources/documents" defaultValue={edit?.path} /></label></div>
        <div className="row"><label>{t('Include globs (one per line, empty = all)', '포함 glob (줄마다 하나, 빈 값=전체)')}<textarea name="includes" defaultValue={edit?.includes.join('\n')} placeholder="**/*.pdf" /></label><label>{t('Exclude globs (one per line)', '제외 glob (줄마다 하나)')}<textarea name="excludes" defaultValue={edit?.excludes.join('\n')} placeholder="private/**" /></label></div>
        <div className="row"><button className="primary" disabled={busy}>{t('Save source', '저장')}</button>{edit && <button type="button" onClick={() => setEdit(undefined)}>{t('Cancel', '취소')}</button>}</div>
      </form></section>
      {sources.length === 0 && <p className="notice">{t('Add a folder to start indexing PDF, text and Markdown files.', '폴더를 등록하면 PDF·텍스트·마크다운 색인을 시작합니다.')}</p>}
      {sources.map(s => <section className="card" key={s.id}><div className="row"><h2>{s.name}</h2><span className="pill">{({ ready: t('Ready', '준비'), waiting: t('Waiting', '대기'), scanning: t('Indexing', '색인 중'), error: t('Error', '오류') } as Record<string, string>)[s.status.state] ?? s.status.state}</span></div><code>{s.path}</code><p>{t('Indexed / Pending / Errors', '색인 / 안정화 대기 / 오류')}: {s.status.indexed} / {s.status.pending} / {s.status.errors}</p>{s.status.errors > 0 && <p className="error">{t('Some files could not be processed. Check folder permissions and extraction limits.', '일부 파일을 처리하지 못했습니다. 폴더 권한과 추출 상한을 확인하세요.')}</p>}<div className="row"><button onClick={() => setEdit(s)}>{t('Edit', '편집')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}/reindex`, 'POST'); await refresh() })}>{t('Reindex', '재색인')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}`, 'DELETE'); await refresh() })}>{t('Remove source', '등록 해제')}</button></div></section>)}
    </>}
    {page === 'tokens' && <><h1>{t('Read-only access', '읽기 전용 접근')}</h1><p>{t('Issue a separate token for each client. Values appear once.', '클라이언트마다 토큰을 발급하세요. 값은 한 번만 표시합니다.')}</p>
      <form className="card" onSubmit={e => submit(e, async data => {
        const issued = await api<{ value: string }>('/tokens', 'POST', { name: data.get('name'), expiresAt: new Date(String(data.get('expiry'))).getTime(), sources: data.getAll('sources') }); setSecret(issued.value); await refresh()
      })}>
        <label>{t('Token name', '토큰 이름')}<input name="name" required maxLength={200} /></label>
        <label>{t('Expires', '만료')}<input name="expiry" type="datetime-local" required defaultValue={new Date(Date.now() + 30 * 86400000 - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16)} /></label>
        <label>{t('Source scope (select none = all current and future sources)', '소스 범위 (미선택=현재와 미래의 전체 소스)')}<select name="sources" multiple>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <button className="primary" disabled={busy}>{t('Issue token', '토큰 발급')}</button>
      </form>
      {secret && <div className="card notice" role="status"><strong>{t('Copy now. This value will not be shown again.', '지금 복사하세요. 다시 표시되지 않습니다.')}</strong><pre>{secret}</pre><button onClick={() => setSecret('')}>{t('Dismiss', '닫기')}</button></div>}
      {tokens.map(tok => <section className="card" key={tok.id}><h2>{tok.name} {tok.revoked && <span className="pill">{t('Revoked', '폐기됨')}</span>}</h2><p>{new Date(tok.expiresAt).toLocaleString()} · {tok.sources.length ? tok.sources.map(id => sources.find(s => s.id === id)?.name ?? id).join(', ') : t('All sources', '전체 소스')}</p><button disabled={tok.revoked || busy} onClick={() => void run(async () => { await api('/tokens/' + tok.id, 'DELETE'); await refresh() })}>{t('Revoke', '폐기')}</button></section>)}
      <section className="card"><h2>{t('Recent audit events', '최근 사용 기록')}</h2>{audits.map((a, i) => <p key={i}><small>{new Date(Number(a.OCCURRED_AT)).toLocaleString()} · {String(a.ACTOR)} · {String(a.ACTION)} · {String(a.STATUS)}</small></p>)}</section>
    </>}
    {page === 'search' && <><h1>{t('Ask your NAS', '내 NAS에 물어보세요')}</h1><p>{t('Keyword search with Korean and English analysis.', '한국어·영문 분석 기반 키워드 검색')}</p><form className="card" onSubmit={e => submit(e, async data => { setDocument(''); setHits(await api<Hit[]>('/search?' + new URLSearchParams({ query: String(data.get('query')), source: String(data.get('source')) }))); setSearched(true) })}>
      <label>{t('Query', '검색어')}<input name="query" required maxLength={1000} /></label><label>{t('Source', '소스')}<select name="source"><option value="">{t('All sources', '전체 소스')}</option>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label><button className="primary" disabled={busy}>{t('Search', '검색')}</button></form>
      {searched && <p>{hits.length} {t('results', '개 결과')}</p>}
      {hits.map((hit, i) => <section className="card" key={i}><small>{hit.sourceName} · {t('Page', '쪽')} {hit.page} · {hit.score.toFixed(3)}</small><p><code>{hit.path}</code></p><p className="result-text">{hit.text}</p><button onClick={() => void run(async () => { const d = await api<{ text: string }>('/document?' + new URLSearchParams({ path: hit.path, page_from: String(hit.page), page_to: String(hit.page) })); setDocument(d.text) })}>{t('Read page', '쪽 읽기')}</button></section>)}
      {document && <section className="card"><h2>{t('Document text', '문서 텍스트')}</h2><pre>{document}</pre></section>}
    </>}
    {page === 'about' && <section className="card"><img className="logo" src="/icon.svg" alt="" /><h1>Nasquire</h1><p>Ask your NAS.</p><p>Copyright (c) 2026 Bluesounds</p><p><a href="https://github.com/Nasquire/nasquire">GitHub · Nasquire/nasquire</a></p><p><a href="/LICENSE.txt">GNU AGPL v3.0</a> · <a href="/LICENSE-EXCEPTIONS.md">{t('Additional terms', '추가 허가와 조건')}</a></p><p>{t('Keyword search MVP · PDF, text, Markdown · MCP over Streamable HTTP at /mcp', '키워드 검색 MVP · PDF·텍스트·마크다운 · /mcp Streamable HTTP')}</p></section>}
  </div></main></div>
}
