import { useEffect, useState, type FormEvent } from 'react'
import { useTheme } from './theme'
import Files from './Files'
import Security from './Security'

const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Auth = { configured: boolean; totpRequired: boolean; authenticated: boolean; csrfToken: string; csrfHeader: string }
type Source = { kind: string; id: string; name: string; path: string; includes: string[]; excludes: string[]; status: { state: string; indexed: number; pending: number; errors: number; message: string } }
type Token = { id: string; name: string; expiresAt: number; sources: string[]; revoked: boolean }
type Hit = { source: string; sourceName: string; path: string; page: number; text: string; score: number }
const pages = ['sources', 'files', 'embeddings', 'security', 'tokens', 'search', 'about'] as const
type Page = typeof pages[number]
const titles = { security: t('Security', '보안'), files: t('Files and shares', '파일·공유'), embeddings: t('Embeddings', '임베딩 설정'), sources: t('Sources', '소스 관리'), tokens: t('API tokens', 'API 토큰'), search: t('Search playground', '검색 시험'), about: t('About', '정보') }

export default function App() {
  const [embedding, setEmbedding] = useState<Record<string, unknown>>()
  const [provider, setProvider] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [connection, setConnection] = useState('')
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
    const [s, tok, log, emb] = await Promise.all([api<Source[]>('/sources'), api<Token[]>('/tokens'), api<Record<string, unknown>[]>('/audit'), api<Record<string, unknown>>('/embeddings')])
    setSources(s); setTokens(tok); setAudits(log); setEmbedding(emb)
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
      if (auth.totpRequired) { await api('/login/totp', 'POST', Object.fromEntries(data)); await refreshAuth(); return }
      if (!auth.configured) await api('/setup', 'POST', Object.fromEntries(data))
      const res = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', [auth.csrfHeader]: auth.csrfToken }, body: new URLSearchParams(data as unknown as Record<string, string>) })
      if (!res.ok) throw new Error(t('Login failed', '로그인 실패'))
      await refreshAuth()
    })}>
      {auth.totpRequired ? <label>{t('Authenticator or recovery code', '인증 앱 또는 복구 코드')}<input name="code" required autoComplete="one-time-code" autoFocus maxLength={40} /></label> : <><label>{t('Username', '사용자명')}<input name="username" required minLength={3} maxLength={100} pattern="[A-Za-z0-9_.\-]+" autoComplete="username" /></label>
      <label>{t('Password', '비밀번호')}<input name="password" type="password" required minLength={auth.configured ? 1 : 12} autoComplete={auth.configured ? 'current-password' : 'new-password'} /></label>
      </>}
      {!auth.configured && <small>{t('Use at least 12 characters (up to 72 UTF-8 bytes). This creates the only administrator.', '12자 이상, UTF-8 72바이트 이하. 단일 관리자 계정을 생성합니다.')}</small>}
      <button className="primary" disabled={busy}>{auth.configured ? t('Sign in', '로그인') : t('Create administrator', '관리자 생성')}</button>
      {auth.totpRequired && <button type="button" disabled={busy} onClick={() => void run(async () => { await api('/login/totp', 'DELETE'); await refreshAuth() })}>{t('Cancel and use another account', '취소하고 비밀번호 단계로')}</button>}
    </form><p className="error" role="alert">{error}</p>
  </main>

  return <div className="app"><aside className="sidebar">
    <div className="sidebar-top"><img className="logo" src="/icon.svg" alt="" /><span className="brand">Nasquire</span></div>
    <nav className="new-menu" aria-label={t('Navigation', '메뉴')}>{pages.map(p => <button key={p} className={p === page ? 'active' : ''} aria-current={p === page ? 'page' : undefined} onClick={() => { setPage(p); setSecret(''); setError('') }}>{titles[p]}</button>)}</nav>
    <div className="sidebar-bottom"><select aria-label={t('Theme', '테마')} value={theme} onChange={e => setTheme(e.target.value as typeof theme)}><option value="auto">{t('System theme', '자동 테마')}</option><option value="light">{t('Light', '라이트')}</option><option value="dark">{t('Dark', '다크')}</option></select><button onClick={() => void run(async () => { await api('/logout', 'POST'); setSecret(''); setHits([]); setDocument(''); await refreshAuth() })}>{t('Sign out', '로그아웃')}</button></div>
  </aside><main className="main"><header>{titles[page]}</header><div className="content">
    {error && <p className="error" role="alert">{error}</p>}
    {page === 'security' && <Security api={api} />}
    {page === 'files' && <Files sources={sources} api={api} csrfHeader={auth.csrfHeader} csrfToken={auth.csrfToken} />}
    {page === 'sources' && <><h1>{t('Your knowledge, connected', '내 폴더를 지식으로')}</h1><p>{t('Standard sources are read-only. Enable an upload source in a separate writable folder.', '일반 소스는 읽기 전용입니다. 별도 쓰기 가능 폴더를 업로드 소스로 등록할 수 있습니다.')}</p>
      <section className="card"><h2>{edit ? t('Edit source', '소스 편집') : t('Add a source', '소스 등록')}</h2><form key={edit?.id ?? 'new'} onSubmit={e => submit(e, async data => {
        const globs = (key: string) => String(data.get(key)).split('\n').map(v => v.trim()).filter(Boolean)
        await api('/sources' + (edit ? '/' + edit.id : ''), edit ? 'PUT' : 'POST', { name: data.get('name'), path: data.get('path'), kind: data.get('kind'), includes: globs('includes'), excludes: globs('excludes') }); setEdit(undefined); await refresh()
      })}>
        <label>{t('Source kind (fixed after registration)', '소스 종류 (등록 후 변경 불가)')}<select name="kind" defaultValue={edit?.kind ?? 'directory'}><option value="directory">{t('Read-only folder', '읽기 전용 폴더')}</option><option value="upload">{t('Upload (separate writable folder)', '업로드 (별도 쓰기 가능 폴더)')}</option></select></label>
        <div className="row"><label>{t('Name' , '이름')}<input name="name" required maxLength={200} defaultValue={edit?.name} /></label><label>{t('Folder in container', '컨테이너 안 폴더')}<input name="path" required placeholder="/sources/documents" defaultValue={edit?.path} /></label></div>
        <div className="row"><label>{t('Include globs (one per line, empty = all)', '포함 glob (줄마다 하나, 빈 값=전체)')}<textarea name="includes" defaultValue={edit?.includes.join('\n')} placeholder="**/*.pdf" /></label><label>{t('Exclude globs (one per line)', '제외 glob (줄마다 하나)')}<textarea name="excludes" defaultValue={edit?.excludes.join('\n')} placeholder="private/**" /></label></div>
        <div className="row"><button className="primary" disabled={busy}>{t('Save source', '저장')}</button>{edit && <button type="button" onClick={() => setEdit(undefined)}>{t('Cancel', '취소')}</button>}</div>
      </form></section>
      {sources.length === 0 && <p className="notice">{t('Add a folder to start indexing PDF, text and Markdown files.', '폴더를 등록하면 PDF·텍스트·마크다운 색인을 시작합니다.')}</p>}
      {sources.map(s => <section className="card" key={s.id}><div className="row"><h2>{s.name}</h2><span className="pill">{({ ready: t('Ready', '준비'), waiting: t('Waiting', '대기'), scanning: t('Indexing', '색인 중'), error: t('Error', '오류') } as Record<string, string>)[s.status.state] ?? s.status.state}</span></div><code>{s.path}</code><p>{t('Indexed / Pending / Errors', '색인 / 안정화 대기 / 오류')}: {s.status.indexed} / {s.status.pending} / {s.status.errors}</p>{s.status.errors > 0 && <p className="error">{t('Some files could not be processed. Check folder permissions and extraction limits.', '일부 파일을 처리하지 못했습니다. 폴더 권한과 추출 상한을 확인하세요.')}</p>}<div className="row"><button onClick={() => setEdit(s)}>{t('Edit', '편집')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}/reindex`, 'POST'); await refresh() })}>{t('Reindex', '재색인')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}`, 'DELETE'); await refresh() })}>{t('Remove source', '등록 해제')}</button></div></section>)}
    </>}
    {page === 'embeddings' && embedding && <><h1>{t('Embedding provider', '임베딩 공급자')}</h1>
      <p>{t('Off uses keyword search. Save settings, test the connection, then rebuild all sources after changing the model or dimensions.', '없음은 키워드 검색을 사용합니다. 설정 저장·연결 시험 후 모델이나 차원을 바꾸면 전체 재색인하세요.')}</p>
      <form id="embedding-form" className="card" key={String(embedding.provider)} onSubmit={e => submit(e, async data => {
        const form = globalThis.document.getElementById('embedding-form') as HTMLFormElement; await api('/embeddings', 'PUT', Object.fromEntries(data)); const keyInput = form.elements.namedItem('apiKey') as HTMLInputElement | null; if (keyInput) keyInput.value = ''; setConnection(''); await refresh()
      })}>
        <label>{t('Provider', '공급자')}<select name="provider" defaultValue={String(embedding.provider)} onChange={e => { setProvider(e.target.value); setConnection('') }}>
          <option value="off">{t('Off', '없음')}</option><option value="builtin" disabled={!embedding.builtinAvailable}>{t('Built-in ONNX (full)', '내장 ONNX (full)')}</option><option value="remote">{t('Remote (OpenAI compatible)', '원격 (OpenAI 호환)')}</option>
        </select></label>
        {((provider || embedding.provider) === 'remote') && <>
          <label>{t('Base URL', '기본 주소')}<input name="baseUrl" type="url" required defaultValue={String(embedding.baseUrl)} onChange={e => setBaseUrl(e.target.value)} /></label>
          <p className="notice" role="alert">{t('Document content is sent to the configured server. External cloud: document content is transmitted externally.', '문서 내용이 설정한 서버로 전송됩니다. 외부 클라우드 주소 사용 시 문서 내용이 외부로 전송됨.')}{baseUrl.startsWith('http:') && t(' HTTP sends data without encryption.', ' HTTP는 암호화하지 않습니다.')}</p>
          <label>{t('Model', '모델')}<input name="model" required defaultValue={String(embedding.model)} /></label>
          <label>{t('API key (blank keeps saved key)', 'API 키 (빈 값은 기존 키 유지)')}<input name="apiKey" type="password" autoComplete="new-password" placeholder={embedding.hasKey ? t('Saved; hidden', '저장됨·비공개') : ''} /></label>
          <div className="row">{(['dimensions', 'batchSize', 'timeoutSeconds'] as const).map((f, i) => <label key={f}>{[t('Dimensions', '차원'), t('Batch size (1–16)', '배치 크기 (1–16)'), t('Timeout seconds', '타임아웃 (초)')][i]}<input name={f} type="number" required min="1" max={[1024, 16, 300][i]} defaultValue={String(embedding[f])} /></label>)}</div>
        </>}
        {((provider || embedding.provider) === 'builtin') && <>
          <p className="notice">{t('Measured built-in RSS: about 856 MiB. Do not enable it with a 512 MiB limit.', '내장 모델 실측 RSS는 약 856 MiB입니다. 512 MiB 제한에서 켜지 마세요.')}</p>
          <p>{t('Int8 multilingual E5, 384 dimensions, one chunk per inference. Model files are separate from the image. Downloads must target the data directory.', 'int8 다국어 E5, 384차원, 한 조각씩 추론. 모델 파일은 이미지와 별도로 관리합니다. 다운로드 대상은 데이터 폴더 안이어야 합니다.')}</p>
          {(['modelPath', 'tokenizerPath', 'modelSha256', 'tokenizerSha256', 'modelUrl', 'tokenizerUrl'] as const).map((f, i) => <label key={f}>{[t('Model path', '모델 경로'), t('Tokenizer path', '토크나이저 경로'), t('Model SHA-256', '모델 SHA-256'), t('Tokenizer SHA-256', '토크나이저 SHA-256'), t('Model HTTPS URL', '모델 HTTPS 주소'), t('Tokenizer HTTPS URL', '토크나이저 HTTPS 주소')][i]}<input name={f} required defaultValue={String(embedding[f])} /></label>)}
          <label>{t('Download missing files on first use (verifies SHA-256)', '첫 사용 시 없는 파일 다운로드 (SHA-256 확인)')}<select name="download" defaultValue={String(embedding.download)}><option value="false">{t('Disabled', '끔')}</option><option value="true">{t('Enabled', '켬')}</option></select></label>
        </>}
        <button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form>
      <section className="card"><p role="status">{Boolean((embedding.status as Record<string, unknown>)?.reindexRequired) ? t('Reindex required', '재색인 필요') : t('Index model is current', '색인 모델 일치')} · {Boolean((embedding.status as Record<string, unknown>)?.rebuilding) ? t('Rebuilding', '재색인 중') : ''}</p>
        <p style={{ overflowWrap: 'anywhere' }}>{t('Model / dimensions', '모델 / 차원')}: {String((embedding.status as Record<string, unknown>)?.modelId)} / {String((embedding.status as Record<string, unknown>)?.dimensions)}</p>
        <div className="row"><button disabled={busy || embedding.provider === 'off'} onClick={() => void run(async () => { const result = await api<{ dimensions: number }>('/embeddings/test', 'POST'); setConnection(t('Connected: ', '연결 성공: ') + result.dimensions) })}>{t('Test saved connection', '저장된 연결 시험')}</button>
        <button disabled={busy} onClick={() => void run(async () => { await api('/embeddings/reindex', 'POST'); await refresh() })}>{t('Reindex all sources', '전체 소스 재색인')}</button></div><p role="status">{connection}</p>
        {sources.map(s => <p key={s.id}>{s.name}: {s.status.state === 'scanning' ? t('Indexing', '색인 중') : s.status.state} · {s.status.indexed} / {s.status.pending} / {s.status.errors}</p>)}
      </section></>}
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
    {page === 'search' && <><h1>{t('Ask your NAS', '내 NAS에 물어보세요')}</h1><p>{t('Keyword, vector and hybrid search with source evidence.', '소스 근거를 유지하는 키워드·벡터·혼합 검색')}</p><form className="card" onSubmit={e => submit(e, async data => { setDocument(''); setHits(await api<Hit[]>('/search?' + new URLSearchParams({ query: String(data.get('query')), source: String(data.get('source')), mode: String(data.get('mode')) }))); setSearched(true) })}>
      <label>{t('Mode', '검색 방식')}<select name="mode" defaultValue=""><option value="">{t('Automatic', '자동')}</option><option value="keyword">{t('Keyword', '키워드')}</option><option value="vector">{t('Vector', '벡터')}</option><option value="hybrid">{t('Hybrid', '혼합')}</option></select></label><label>{t('Query', '검색어')}<input name="query" required maxLength={1000} /></label><label>{t('Source', '소스')}<select name="source"><option value="">{t('All sources', '전체 소스')}</option>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label><button className="primary" disabled={busy}>{t('Search', '검색')}</button></form>
      {searched && <p>{hits.length} {t('results', '개 결과')}</p>}
      {hits.map((hit, i) => <section className="card" key={i}><small>{hit.sourceName} · {t('Page', '쪽')} {hit.page} · {hit.score.toFixed(3)}</small><p><code>{hit.path}</code></p><p className="result-text">{hit.text}</p><button onClick={() => void run(async () => { const d = await api<{ text: string }>('/document?' + new URLSearchParams({ path: hit.path, page_from: String(hit.page), page_to: String(hit.page) })); setDocument(d.text) })}>{t('Read page', '쪽 읽기')}</button></section>)}
      {document && <section className="card"><h2>{t('Document text', '문서 텍스트')}</h2><pre>{document}</pre></section>}
    </>}
    {page === 'about' && <section className="card"><img className="logo" src="/icon.svg" alt="" /><h1>Nasquire</h1><p>Ask your NAS.</p><p>Copyright (c) 2026 Bluesounds</p><p><a href="https://github.com/Nasquire/nasquire">GitHub · Nasquire/nasquire</a></p><p><a href="/LICENSE.txt">GNU AGPL v3.0</a> · <a href="/LICENSE-EXCEPTIONS.md">{t('Additional terms', '추가 허가와 조건')}</a></p><p>{t('Hybrid search · PDF, text, Markdown · MCP over Streamable HTTP at /mcp', '혼합 검색 · PDF·텍스트·마크다운 · /mcp Streamable HTTP')}</p></section>}
  </div></main></div>
}
