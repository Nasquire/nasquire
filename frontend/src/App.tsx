import { useEffect, useState, type FormEvent } from 'react'
import { useTheme } from './theme'
import Files from './Files'
import Users from './Users'
import Modal from './Modal'
import Account from './Account'
import Licenses from './Licenses'
import Security from './Security'
import OAuth from './OAuth'
import Oidc from './Oidc'
import Full from './Full'
import SearchExcerpt from './SearchExcerpt'

const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
const passwordHint = t('Use at least 8 characters, including an ASCII letter, digit and printable ASCII punctuation; at most 72 UTF-8 bytes. Both letter cases are not required.', '8자 이상, 영문자·숫자·출력 가능한 ASCII 특수문자 포함, UTF-8 72바이트 이하. 대소문자를 각각 넣을 필요는 없습니다.')
type Auth = { role: 'ADMIN' | 'USER'; configured: boolean; totpRequired: boolean; authenticated: boolean; csrfToken: string; csrfHeader: string }
type Source = { kind: string; id: string; name: string; path: string; includes: string[]; excludes: string[]; status: { state: string; indexed: number; pending: number; errors: number; oversized: number; recentOversized: string[]; message: string } }
type Token = { id: string; name: string; expiresAt: number; sources: string[]; revoked: boolean; ownerId: number; ownerName: string }
type Hit = { source: string; sourceName: string; path: string; page: number; text: string; score: number }
const pages = ['sources', 'files', 'tokens', 'search', 'users'] as const
type Page = typeof pages[number]
const titles = { users: t('Users', '사용자'), security: t('Security', '보안'), files: t('Files and shares', '파일·공유'), settings: t('Settings', '환경설정'), sources: t('Sources', '소스 관리'), tokens: t('API tokens', 'API 토큰'), search: t('Search playground', '검색 시험'), about: t('About', '정보') }

export default function App() {
  const [oidc, setOidc] = useState<{ enabled: boolean; buttonLabel: string }>()
  const [accountRevision, setAccountRevision] = useState(0)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [licensesOpen, setLicensesOpen] = useState(false)
  const [fullAvailable, setFullAvailable] = useState<boolean>()
  const [query, setQuery] = useState('')
  const [embedding, setEmbedding] = useState<Record<string, unknown>>()
  const [provider, setProvider] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [connection, setConnection] = useState('')
  const [auth, setAuth] = useState<Auth>()
  const isAdmin = auth?.role === 'ADMIN'
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
  const [indexing, setIndexing] = useState<{ scanMillis: number; maxFileBytes: number }>()
  const [extensions, setExtensions] = useState<string[]>([])
  async function refresh() {
    if (!isAdmin) {
      const [s, tok] = await Promise.all([api<Source[]>('/allowed-sources'), api<Token[]>('/tokens')])
      setSources(s); setTokens(tok); setAudits([]); setEmbedding(undefined); setIndexing(undefined); setFullAvailable(undefined); return
    }
    setIndexing(await api<{ scanMillis: number; maxFileBytes: number }>('/indexing')); setExtensions(await api<string[]>('/supported-extensions'))
    const [s, tok, log, emb, full] = await Promise.all([api<Source[]>('/sources'), api<Token[]>('/tokens'), api<Record<string, unknown>[]>('/audit'), api<Record<string, unknown>>('/embeddings'), api<{ available: boolean }>('/full')])
    setSources(s); setTokens(tok); setAudits(log); setEmbedding(emb); setFullAvailable(full.available)
  }
  async function run(action: () => Promise<unknown>) { setError(''); setBusy(true); try { await action() } catch (e) { setError(String(e)) } finally { setBusy(false) } }
  useEffect(() => { void refreshAuth().catch(e => setError(String(e))); void fetch('/oidc/status').then(r => r.json()).then(setOidc).catch(() => {}) }, [])
  useEffect(() => {
    if (!auth?.authenticated) return
    if (!isAdmin) setPage('search')
    void refresh().catch(e => setError(String(e)))
    const timer = setInterval(() => { void refresh().catch(e => setError(String(e))) }, 5000)
    return () => clearInterval(timer)
  }, [auth?.authenticated, auth?.role])
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
      if (!auth.configured) {
        const password = String(data.get('password') ?? '')
        if (password.length < 8 || new TextEncoder().encode(password).length > 72 || !/[A-Za-z]/.test(password) || !/[0-9]/.test(password) || !/[\x21-\x2F\x3A-\x40\x5B-\x60\x7B-\x7E]/.test(password)) throw new Error(passwordHint)
        await api('/setup', 'POST', Object.fromEntries(data))
      }
      const res = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', [auth.csrfHeader]: auth.csrfToken }, body: new URLSearchParams(data as unknown as Record<string, string>) })
      await res.text()
      if (!res.ok) throw new Error(t('Login failed', '로그인 실패'))
      await refreshAuth()
    })}>
      {auth.totpRequired ? <label>{t('Authenticator or recovery code', '인증 앱 또는 복구 코드')}<input name="code" required autoComplete="one-time-code" autoFocus maxLength={40} /></label> : <><label>{t('Username', '사용자명')}<input name="username" required minLength={3} maxLength={100} pattern="[A-Za-z0-9_.\-]+" autoComplete="username" /></label>
      <label>{t('Password', '비밀번호')}<input name="password" type="password" required minLength={auth.configured ? 1 : 8} autoComplete={auth.configured ? 'current-password' : 'new-password'} /></label>
      </>}
      {!auth.configured && <small>{passwordHint} {t('This creates the first administrator.', '첫 관리자 계정을 생성합니다.')}</small>}
      <button className="primary" disabled={busy}>{auth.configured ? t('Sign in', '로그인') : t('Create administrator', '관리자 생성')}</button>
      {auth.totpRequired && <button type="button" disabled={busy} onClick={() => void run(async () => { await api('/login/totp', 'DELETE'); await refreshAuth() })}>{t('Cancel and use another account', '취소하고 비밀번호 단계로')}</button>}
    </form>{auth.configured && !auth.totpRequired && oidc?.enabled && <a className="button" href="/oidc/login">{oidc.buttonLabel}</a>}<p className="error" role="alert">{error}</p>
  </main>

  return <div className="app"><aside className="sidebar">
    <div className="sidebar-top"><img className="logo" src="/icon.svg" alt="" /><span className="brand">Nasquire</span></div>
    <nav className="new-menu" aria-label={t('Navigation', '메뉴')}>{pages.filter(p => isAdmin || p === 'tokens' || p === 'search').map(p => <button key={p} className={p === page ? 'active' : ''} aria-current={p === page ? 'page' : undefined} onClick={() => { setPage(p); setSecret(''); setError('') }}>{titles[p]}</button>)}</nav>
    <div className="sidebar-bottom"><button type="button" className="settings-trigger" aria-label={t('Settings', '환경설정')} title={t('Settings', '환경설정')} aria-haspopup="dialog" aria-expanded={settingsOpen} onClick={() => { setError(''); setSettingsOpen(true) }}><svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><path d="m9 3-1 3-3 1-2 5 2 5 3 1 1 3h6l1-3 3-1 2-5-2-5-3-1-1-3Z"/><circle cx="12" cy="12" r="3"/></svg></button><div role="group" aria-label={t('Theme', '테마')}>{(['auto', 'light', 'dark'] as const).map((value, i) => <button key={value} aria-pressed={theme === value} onClick={() => setTheme(value)}>{[t('Auto', '자동'), t('Light', '라이트'), t('Dark', '다크')][i]}</button>)}</div></div>
  </aside><main className="main"><header>{titles[page]}</header><div className="content">
    {error && <p className="error" role="alert">{error}</p>}
    {isAdmin && page === 'files' && <Files onSources={() => setPage('sources')} sources={sources} api={api} csrfHeader={auth.csrfHeader} csrfToken={auth.csrfToken} />}
    {isAdmin && page === 'users' && <Users api={api} sources={sources} />}
    {isAdmin && page === 'sources' && <><h1>{t('Your knowledge, connected', '내 폴더를 지식으로')}</h1><p>{t('Standard sources are read-only. Enable an upload source in a separate writable folder.', '일반 소스는 읽기 전용입니다. 별도 쓰기 가능 폴더를 업로드 소스로 등록할 수 있습니다.')}</p>
      <section className="card"><h2>{edit ? t('Edit source', '소스 편집') : t('Add a source', '소스 등록')}</h2><form key={edit?.id ?? 'new'} onSubmit={e => submit(e, async data => {
        const globs = (key: string) => String(data.get(key)).split('\n').map(v => v.trim()).filter(Boolean)
        await api('/sources' + (edit ? '/' + edit.id : ''), edit ? 'PUT' : 'POST', { name: data.get('name'), path: data.get('path'), kind: data.get('kind'), includes: globs('includes'), excludes: globs('excludes') }); setEdit(undefined); await refresh()
      })}>
        <label>{t('Source kind (fixed after registration)', '소스 종류 (등록 후 변경 불가)')}<select name="kind" defaultValue={edit?.kind ?? 'directory'}><option value="directory">{t('Read-only folder', '읽기 전용 폴더')}</option><option value="upload">{t('Upload (separate writable folder)', '업로드 (별도 쓰기 가능 폴더)')}</option></select></label>
        <div className="row"><label>{t('Name' , '이름')}<input name="name" required maxLength={200} defaultValue={edit?.name} /></label><label>{t('Folder in container', '컨테이너 안 폴더')}<input name="path" required placeholder="/sources/documents" defaultValue={edit?.path} /></label></div>
        <div className="row"><label>{t('Include globs (one per line, empty = all)', '포함 glob (줄마다 하나, 빈 값=전체)')}<textarea name="includes" defaultValue={edit?.includes.join('\n')} /><small>{t('Leave empty to index all supported formats', '비워 두면 지원 형식 전체를 색인')}: {extensions.map(x => '.' + x).join(', ')}</small></label><label>{t('Exclude globs (one per line)', '제외 glob (줄마다 하나)')}<textarea name="excludes" defaultValue={edit?.excludes.join('\n')} placeholder="private/**" /><small>{t('Automatically excluded NAS system files', 'NAS 시스템 폴더·파일 자동 제외')}: @eaDir, #recycle, #snapshot, .DS_Store, Thumbs.db</small></label></div>
        <div className="row"><button className="primary" disabled={busy}>{t('Save source', '저장')}</button>{edit && <button type="button" onClick={() => setEdit(undefined)}>{t('Cancel', '취소')}</button>}</div>
      </form></section>
      {sources.length === 0 && <p className="notice">{t('Add a folder to start indexing PDF, text and Markdown files.', '폴더를 등록하면 PDF·텍스트·마크다운 색인을 시작합니다.')}</p>}
      {sources.map(s => <section className="card" key={s.id}><div className="row"><h2>{s.name}</h2><span className="pill">{({ ready: t('Ready', '준비'), waiting: t('Waiting', '대기'), scanning: t('Indexing', '색인 중'), error: t('Error', '오류') } as Record<string, string>)[s.status.state] ?? s.status.state}</span></div><code>{s.path}</code><p>{s.includes.length ? s.includes.join(', ') : t('All supported formats', '전체 지원 형식')}</p><p>{t('Oversized', '크기 초과')}: {s.status.oversized ?? 0}</p>{s.status.recentOversized?.map(path => <p key={path}><code>{path}</code></p>)}<p>{t('Indexed / Pending / Errors', '색인 / 안정화 대기 / 오류')}: {s.status.indexed} / {s.status.pending} / {s.status.errors}</p>{s.status.errors > 0 && <p className="error">{t('Some files could not be processed. Check folder permissions and extraction limits.', '일부 파일을 처리하지 못했습니다. 폴더 권한과 추출 상한을 확인하세요.')}</p>}<div className="row"><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}/scan`, 'POST'); await refresh() })}>{t('Scan now', '지금 검사')}</button><button onClick={() => setEdit(s)}>{t('Edit', '편집')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}/reindex`, 'POST'); await refresh() })}>{t('Reindex', '재색인')}</button><button disabled={busy} onClick={() => void run(async () => { await api(`/sources/${s.id}`, 'DELETE'); await refresh() })}>{t('Remove source', '등록 해제')}</button></div></section>)}
    </>}
    {settingsOpen && <Modal title={t('Settings', '환경설정')} onClose={() => setSettingsOpen(false)}>{error && <p className="error" role="alert">{error}</p>}{indexing && <section className="card"><h2>{t('Indexing', '색인')}</h2><form onSubmit={e => { e.preventDefault(); const data = new FormData(e.currentTarget); void run(async () => { await api('/indexing', 'PUT', { scanMillis: Number(data.get('scanMinutes')) * 60000, maxFileBytes: Number(data.get('maxMiB')) * 1048576 }); await refresh() }) }}><label>{t('Scan interval (minutes)', '검사 주기(분)')}<input name="scanMinutes" type="number" min="1" max="60" step="1" required defaultValue={indexing.scanMillis / 60000} /></label><label>{t('Maximum file size (MiB)', '파일 크기 상한(MiB)')}<input name="maxMiB" type="number" min="1" max="512" step="1" required defaultValue={indexing.maxFileBytes / 1048576} /></label><p>{t('Office/HWP originals retain a separate 8 MiB limit. Settings override environment defaults.', 'Office/HWP 원본은 별도 8 MiB 상한을 유지합니다. 화면 설정은 환경 기본값보다 우선합니다.')}</p><button disabled={busy}>{t('Save indexing settings', '색인 설정 저장')}</button></form></section>}<Account key={accountRevision} api={api} refreshAuth={refreshAuth} passwordHint={passwordHint} /><Security api={api} onChange={() => setAccountRevision(value => value + 1)} />{isAdmin && <Oidc api={api} />}{embedding && <><h2>{t('Search', '검색')}</h2>
      <p>{t('Off uses keyword search. Save settings, test the connection, then rebuild all sources after changing the model or dimensions.', '없음은 키워드 검색을 사용합니다. 설정 저장·연결 시험 후 모델이나 차원을 바꾸면 전체 재색인하세요.')}</p>
      <form id="embedding-form" className="card" key={String(embedding.provider)} onSubmit={e => submit(e, async data => {
        const form = globalThis.document.getElementById('embedding-form') as HTMLFormElement; await api('/embeddings', 'PUT', Object.fromEntries(data)); for (const name of ['apiKey', 'qdrantApiKey']) { const keyInput = form.elements.namedItem(name) as HTMLInputElement | null; if (keyInput) keyInput.value = '' } setConnection(''); await refresh()
      })}>
        <label>{t('Provider', '공급자')}<select name="provider" defaultValue={String(embedding.provider)} onChange={e => { setProvider(e.target.value); setConnection('') }}>
          <option value="off">{t('Off', '없음')}</option>{fullAvailable && <option value="builtin">{t('Built-in ONNX', '내장 ONNX')}</option>}<option value="remote">{t('Remote (OpenAI compatible)', '원격 (OpenAI 호환)')}</option>
        </select></label>
        {((provider || embedding.provider) === 'remote') && <>
          <label>{t('Base URL', '기본 주소')}<input name="baseUrl" type="url" required defaultValue={String(embedding.baseUrl)} onChange={e => setBaseUrl(e.target.value)} /></label>
          <p className="notice" role="alert">{t('Document content is sent to the configured server. External cloud: document content is transmitted externally.', '문서 내용이 설정한 서버로 전송됩니다. 외부 클라우드 주소 사용 시 문서 내용이 외부로 전송됨.')}{baseUrl.startsWith('http:') && t(' HTTP sends data without encryption.', ' HTTP는 암호화하지 않습니다.')}</p>
          <label>{t('Model', '모델')}<input name="model" required defaultValue={String(embedding.model)} /></label>
          <label>{t('API key (blank keeps saved key; changing the URL discards it)', 'API 키 (빈 값은 기존 키 유지, 주소를 바꾸면 기존 키 삭제)')}<input name="apiKey" type="password" autoComplete="new-password" placeholder={embedding.hasKey ? t('Saved; hidden', '저장됨·비공개') : ''} /></label>
          {Boolean(embedding.hasKey) && <button type="button" disabled={busy} onClick={() => void run(async () => { await api('/embeddings/key', 'DELETE'); await refresh() })}>{t('Delete saved API key', '저장된 API 키 삭제')}</button>}
          <div className="row">{(['dimensions', 'batchSize', 'timeoutSeconds'] as const).map((f, i) => <label key={f}>{[t('Dimensions', '차원'), t('Batch size (1–16)', '배치 크기 (1–16)'), t('Timeout seconds', '타임아웃 (초)')][i]}<input name={f} type="number" required min="1" max={[1024, 16, 300][i]} defaultValue={String(embedding[f])} /></label>)}</div>
        </>}
        {(fullAvailable && (provider || embedding.provider) === 'builtin') && <>
          <p className="notice">{t('Measured built-in RSS: about 856 MiB. Do not enable it with a 512 MiB limit.', '내장 모델 실측 RSS는 약 856 MiB입니다. 512 MiB 제한에서 켜지 마세요.')}</p>
          <p>{t('Int8 multilingual E5, 384 dimensions, one chunk per inference. Model files are separate from the image. Downloads must target the data directory.', 'int8 다국어 E5, 384차원, 한 조각씩 추론. 모델 파일은 이미지와 별도로 관리합니다. 다운로드 대상은 데이터 폴더 안이어야 합니다.')}</p>
          {(['modelPath', 'tokenizerPath', 'modelSha256', 'tokenizerSha256', 'modelUrl', 'tokenizerUrl'] as const).map((f, i) => <label key={f}>{[t('Model path', '모델 경로'), t('Tokenizer path', '토크나이저 경로'), t('Model SHA-256', '모델 SHA-256'), t('Tokenizer SHA-256', '토크나이저 SHA-256'), t('Model HTTPS URL', '모델 HTTPS 주소'), t('Tokenizer HTTPS URL', '토크나이저 HTTPS 주소')][i]}<input name={f} required defaultValue={String(embedding[f])} /></label>)}
          <label>{t('Download missing files on first use (verifies SHA-256)', '첫 사용 시 없는 파일 다운로드 (SHA-256 확인)')}<select name="download" defaultValue={String(embedding.download)}><option value="false">{t('Disabled', '끔')}</option><option value="true">{t('Enabled', '켬')}</option></select></label>
        </>}
        {(provider || embedding.provider) !== 'off' && <fieldset><legend>{t('Vector store', '벡터 저장소')}</legend>
          <label>{t('Store', '저장소')}<select name="vectorStore" defaultValue={String(embedding.vectorStore)}><option value="lucene">{t('Built-in Lucene HNSW (default)', '내장 Lucene HNSW (기본)')}</option><option value="qdrant">Qdrant (REST)</option></select></label>
          <p>{t('Qdrant stores only vectors, source IDs, paths and offsets. Text stays in the local index. Changing the store requires a full reindex.', 'Qdrant에는 벡터·소스 ID·경로·offset만 저장하고 본문은 로컬 색인에 둡니다. 저장소를 바꾸면 전체 재색인이 필요합니다.')}</p>
          <div className="row"><label>{t('Qdrant URL', 'Qdrant 주소')}<input name="qdrantUrl" type="url" defaultValue={String(embedding.qdrantUrl)} /></label><label>{t('Collection', '컬렉션')}<input name="qdrantCollection" pattern="[A-Za-z0-9_\-]{1,64}" defaultValue={String(embedding.qdrantCollection)} /></label><label>{t('Timeout seconds', '타임아웃 (초)')}<input name="qdrantTimeoutSeconds" type="number" min="1" max="60" defaultValue={String(embedding.qdrantTimeoutSeconds)} /></label></div>
          <label>{t('Qdrant API key (blank keeps saved key; changing the URL discards it)', 'Qdrant API 키 (빈 값은 기존 키 유지, 주소를 바꾸면 기존 키 삭제)')}<input name="qdrantApiKey" type="password" autoComplete="new-password" placeholder={embedding.hasQdrantKey ? t('Saved; hidden', '저장됨·비공개') : ''} /></label>
          {Boolean(embedding.hasQdrantKey) && <button type="button" disabled={busy} onClick={() => void run(async () => { await api('/embeddings/qdrant-key', 'DELETE'); await refresh() })}>{t('Delete saved Qdrant key', '저장된 Qdrant 키 삭제')}</button>}
        </fieldset>}
        {(provider || embedding.provider) !== 'off' && <label>{t('Plain HTTP to non-loopback hosts (sends keys unencrypted)', '루프백이 아닌 주소의 평문 HTTP (키가 암호화 없이 전송됨)')}<select name="allowInsecureHttp" defaultValue={String(embedding.allowInsecureHttp ?? 'false')}><option value="false">{t('Blocked: HTTPS required (default)', '차단: HTTPS 필요 (기본)')}</option><option value="true">{t('Allowed (trusted network only)', '허용 (신뢰하는 네트워크에서만)')}</option></select></label>}
        {Boolean(embedding.insecureHttp) && <p className="error" role="alert">{t('Warning: a remote URL uses plain HTTP. API keys and document text are sent unencrypted.', '경고: 원격 주소가 평문 HTTP입니다. API 키와 문서 내용이 암호화 없이 전송됩니다.')}</p>}
        {Boolean(embedding.disabledReason) && <p className="error" role="alert">{t('Vector features were disabled at startup: plain HTTP to a non-loopback host. Switch to HTTPS or allow plain HTTP explicitly, then save.', '시작 시 벡터 기능을 껐습니다: 루프백이 아닌 평문 HTTP 주소입니다. HTTPS로 바꾸거나 평문 허용을 명시한 뒤 저장하세요.')}</p>}
        <button className="primary" disabled={busy}>{t('Save settings', '설정 저장')}</button>
      </form>
      <section className="card"><p role="status">{embedding.provider === 'off' ? t('Embeddings off (keyword search)', '임베딩 꺼짐(키워드 검색)') : Boolean((embedding.status as Record<string, unknown>)?.reindexRequired) ? t('Reindex required', '재색인 필요') : t('Index model is current', '색인 모델 일치')}{Boolean((embedding.status as Record<string, unknown>)?.rebuilding) && <> · {t('Rebuilding', '재색인 중')}</>}</p>
        {embedding.provider !== 'off' && <p style={{ overflowWrap: 'anywhere' }}>{t('Model / dimensions', '모델 / 차원')}: {String((embedding.status as Record<string, unknown>)?.modelId)} / {String((embedding.status as Record<string, unknown>)?.dimensions)}</p>}
        <div className="row"><button disabled={busy || embedding.provider === 'off'} onClick={() => void run(async () => { const result = await api<{ dimensions: number }>('/embeddings/test', 'POST'); setConnection(t('Connected: ', '연결 성공: ') + result.dimensions) })}>{t('Test saved connection', '저장된 연결 시험')}</button>
        <button disabled={busy} onClick={() => void run(async () => { await api('/embeddings/reindex', 'POST'); await refresh() })}>{t('Reindex all sources', '전체 소스 재색인')}</button></div><p role="status">{connection}</p>
        {sources.map(s => <p key={s.id}>{s.name}: {s.status.state === 'scanning' ? t('Indexing', '색인 중') : s.status.state} · {s.status.indexed} / {s.status.pending} / {s.status.errors}</p>)}
      </section></>}{fullAvailable && <Full api={api} />}     <section className="card"><img className="logo" src="/icon.svg" alt="" /><h2>{t('About', '정보')}</h2><p>Nasquire · {t('Version', '버전')} 0.1.0</p><p>Ask your NAS.</p>{fullAvailable !== undefined && <small>{t('Build', '빌드')}: {fullAvailable ? 'full' : 'lite'}</small>}<p>Copyright (c) 2026 Bluesounds</p><p><a href="https://github.com/Nasquire/nasquire">GitHub · Nasquire/nasquire</a></p><p><a href="/LICENSE.txt">GNU AGPL v3.0</a> · <a href="/LICENSE-EXCEPTIONS.md">{t('Additional terms', '추가 허가와 조건')}</a></p><p>{t('Hybrid search · PDF, text, Markdown · MCP over Streamable HTTP at /mcp', '혼합 검색 · PDF·텍스트·마크다운 · /mcp Streamable HTTP')}</p><button onClick={() => setLicensesOpen(true)}>{t('View licenses', '라이선스 보기')}</button></section><div className="settings-signout"><button className="primary" onClick={() => void run(async () => { await api('/logout', 'POST'); setSettingsOpen(false); setSecret(''); setHits([]); setDocument(''); await refreshAuth() })}>{t('Sign out', '로그아웃')}</button></div>{licensesOpen && <Licenses onClose={() => setLicensesOpen(false)} />}</Modal>}
    {page === 'tokens' && <>{isAdmin && <OAuth api={api} />}<h1>{t('Read-only access', '읽기 전용 접근')}</h1><p>{t('Issue a separate token for each client. Values appear once.', '클라이언트마다 토큰을 발급하세요. 값은 한 번만 표시합니다.')}</p>
      <form className="card" onSubmit={e => submit(e, async data => {
        const issued = await api<{ value: string }>('/tokens', 'POST', { name: data.get('name'), expiresAt: new Date(String(data.get('expiry'))).getTime(), sources: data.getAll('sources') }); setSecret(issued.value); await refresh()
      })}>
        <label>{t('Token name', '토큰 이름')}<input name="name" required maxLength={200} /></label>
        <label>{t('Expires', '만료')}<input name="expiry" type="datetime-local" required defaultValue={new Date(Date.now() + 30 * 86400000 - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16)} /></label>
        <label>{t('Source scope (select none = all sources currently allowed to you)', '소스 범위 (미선택=현재 허용된 전체 소스)')}<select name="sources" multiple>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <button className="primary" disabled={busy}>{t('Issue token', '토큰 발급')}</button>
      </form>
      {secret && <div className="card notice" role="status"><strong>{t('Copy now. This value will not be shown again.', '지금 복사하세요. 다시 표시되지 않습니다.')}</strong><pre>{secret}</pre><button onClick={() => setSecret('')}>{t('Dismiss', '닫기')}</button></div>}
      <button disabled={busy || !tokens.some(tok => tok.revoked || tok.expiresAt <= Date.now())} onClick={() => void run(async () => { await api('/tokens/inactive', 'DELETE'); await refresh() })}>{t('Delete all revoked and expired tokens', '폐기·만료 토큰 모두 삭제')}</button>
      {tokens.map(tok => <section className="card" key={tok.id}><h2>{tok.name} {isAdmin && <small>{tok.ownerName || String(tok.ownerId)}</small>} {tok.revoked && <span className="pill">{t('Revoked', '폐기됨')}</span>}</h2><p>{new Date(tok.expiresAt).toLocaleString()} · {tok.sources.length ? tok.sources.map(id => sources.find(s => s.id === id)?.name ?? id).join(', ') : t('All sources', '전체 소스')}</p><button disabled={tok.revoked || busy} onClick={() => void run(async () => { await api('/tokens/' + tok.id, 'DELETE'); await refresh() })}>{t('Revoke', '폐기')}</button>{(tok.revoked || tok.expiresAt <= Date.now()) && <button disabled={busy} onClick={() => void run(async () => { await api('/tokens/' + tok.id + '/record', 'DELETE'); await refresh() })}>{t('Delete', '삭제')}</button>}</section>)}
      {isAdmin && <section className="card"><h2>{t('Recent audit events', '최근 사용 기록')}</h2>{audits.map((a, i) => <p key={i}><small>{new Date(Number(a.OCCURRED_AT)).toLocaleString()} · {String(a.ACTOR)} · {String(a.ACTION)} · {String(a.STATUS)}</small></p>)}</section>}
    </>}
    {page === 'search' && <><h1>{t('Ask your NAS', '내 NAS에 물어보세요')}</h1><p>{t('Keyword, vector and hybrid search with source evidence.', '소스 근거를 유지하는 키워드·벡터·혼합 검색')}</p><form className="card" onSubmit={e => submit(e, async data => { setDocument(''); setHits(await api<Hit[]>('/search?' + new URLSearchParams({ query: String(data.get('query')), source: String(data.get('source')), mode: String(data.get('mode')) }))); setQuery(String(data.get('query'))); setSearched(true) })}>
      <label>{t('Mode', '검색 방식')}<select name="mode" defaultValue=""><option value="">{t('Automatic', '자동')}</option><option value="keyword">{t('Keyword', '키워드')}</option><option value="vector">{t('Vector', '벡터')}</option><option value="hybrid">{t('Hybrid', '혼합')}</option></select></label><label>{t('Query', '검색어')}<input name="query" required maxLength={1000} /></label><label>{t('Source', '소스')}<select name="source"><option value="">{t('All sources', '전체 소스')}</option>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label><button className="primary" disabled={busy}>{t('Search', '검색')}</button></form>
      {searched && <p>{hits.length} {t('results', '개 결과')}</p>}
      {hits.map((hit, i) => <section className="card" key={i}><small>{hit.sourceName} · {t('Page', '쪽')} {hit.page} · {hit.score.toFixed(3)}</small><p><code>{sources.find(s => s.id === hit.source)?.name ?? hit.sourceName} · {hit.path.startsWith(hit.source + ':') ? hit.path.slice(hit.source.length + 1) : hit.path}</code></p><SearchExcerpt key={query + ':' + hit.path + ':' + i} text={hit.text} query={query} /><button onClick={() => void run(async () => { const d = await api<{ text: string }>('/document?' + new URLSearchParams({ path: hit.path, page_from: String(hit.page), page_to: String(hit.page) })); setDocument(d.text) })}>{t('Read page', '쪽 읽기')}</button></section>)}
      {document && <section className="card"><h2>{t('Document text', '문서 텍스트')}</h2><pre>{document}</pre></section>}
    </>}

  </div></main></div>
}
