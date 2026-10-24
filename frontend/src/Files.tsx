import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Source = { id: string; name: string; kind: string }
type Entry = { name: string; path: string; directory: boolean; size: number }
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
export default function Files({ sources, api, csrfHeader, csrfToken }: { sources: Source[]; api: Api; csrfHeader: string; csrfToken: string }) {
  const [source, setSource] = useState('')
  const [path, setPath] = useState('')
  const [entries, setEntries] = useState<Entry[]>([])
  const [trash, setTrash] = useState<Record<string, unknown>[]>([])
  const [shares, setShares] = useState<Record<string, unknown>[]>([])
  const [secret, setSecret] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const base = '/uploads/' + source
  const join = (name: string) => path ? path + '/' + name : name
  async function refresh() {
    setShares(await api<Record<string, unknown>[]>('/shares'))
    if (source) {
      setEntries(await api<Entry[]>(base + '?' + new URLSearchParams({ path })))
      setTrash(await api<Record<string, unknown>[]>(base + '/trash'))
    } else { setEntries([]); setTrash([]) }
  }
  async function run(action: () => Promise<unknown>) {
    setBusy(true); setError('')
    try { await action(); await refresh() } catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  function submit(e: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<unknown>) {
    e.preventDefault(); const data = new FormData(e.currentTarget); void run(() => action(data))
  }
  useEffect(() => { void refresh().catch(e => setError(String(e))) }, [source, path])
  return <><h1>{t('Uploads and sharing', '업로드·외부 공유')}</h1>
    <p>{t('Only upload sources allow changes. API tokens and MCP remain read-only.', '업로드 소스에서만 파일을 변경합니다. API 토큰과 MCP는 읽기 전용입니다.')}</p>
    {error && <p className="error" role="alert">{error}</p>}
    <section className="card"><h2>{t('Upload files', '파일 관리')}</h2>
      <label>{t('Upload source', '업로드 소스')}<select value={source} onChange={e => { setSource(e.target.value); setPath('') }}><option value="">{t('Select source', '소스 선택')}</option>{sources.filter(s => s.kind === 'upload').map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      {source && <><p><code>{path || '/'}</code></p><button disabled={busy || !path} onClick={() => setPath(path.split('/').slice(0, -1).join('/'))}>{t('Parent folder', '상위 폴더')}</button>
        <form onSubmit={e => submit(e, async data => {
          const file = data.get('file') as File
          const body = new FormData(); body.set('file', file)
          const res = await fetch('/api' + base + '?' + new URLSearchParams({ path: join(file.name), overwrite: String(data.get('overwrite') === 'on') }), { method: 'POST', headers: { [csrfHeader]: csrfToken }, body })
          if (!res.ok) throw new Error(t('Upload failed', '업로드 실패') + ` (${res.status})`)
        })}><label>{t('File', '파일')}<input name="file" type="file" required /></label><label><input name="overwrite" type="checkbox" />{t('Replace existing file', '기존 파일 덮어쓰기')}</label><button disabled={busy}>{t('Upload', '업로드')}</button></form>
        <form onSubmit={e => submit(e, data => api(base + '/folder', 'POST', { path: join(String(data.get('name'))) }))}><label>{t('New folder', '새 폴더')}<input name="name" required /></label><button disabled={busy}>{t('Create folder', '폴더 만들기')}</button></form>
        {entries.map(entry => <div className="card" key={entry.path}><button disabled={!entry.directory || busy} onClick={() => setPath(entry.path)}>{entry.name}{entry.directory ? '/' : ` · ${entry.size} B`}</button>
          <form onSubmit={e => submit(e, data => api(base + '/move', 'POST', { path: entry.path, destination: data.get('destination'), overwrite: data.get('overwrite') === 'on' }))}>
            <label>{t('New path from source root', '소스 루트 기준 새 경로')}<input name="destination" required defaultValue={entry.path} /></label><label><input name="overwrite" type="checkbox" />{t('Replace existing file', '기존 파일 덮어쓰기')}</label><button disabled={busy}>{t('Rename / move', '이름 변경·이동')}</button></form>
          <button disabled={busy} onClick={() => void run(() => api(base + '/trash', 'POST', { path: entry.path }))}>{t('Move to trash', '휴지통으로')}</button></div>)}
        <h3>{t('Trash (retained for 30 days by default)', '휴지통 (기본 30일 보존)')}</h3>{trash.map(item => <p key={String(item.ID)}>{String(item.ORIGINAL)} <button disabled={busy} onClick={() => void run(() => api(base + '/restore/' + item.ID, 'POST'))}>{t('Restore', '복원')}</button></p>)}
      </>}
    </section>
    <section className="card"><h2>{t('Create external share', '외부 공유 생성')}</h2>
      <form onSubmit={e => submit(e, async data => {
        const issued = await api<{ url: string }>('/shares', 'POST', { source: data.get('source'), path: data.get('path'), expiresAt: new Date(String(data.get('expires'))).getTime(), password: data.get('password'), maxDownloads: data.get('maximum') ? Number(data.get('maximum')) : null })
        setSecret(new URL(issued.url, window.location.origin).href)
        const form = globalThis.document.getElementById('share-form') as HTMLFormElement
        const password = form.elements.namedItem('password') as HTMLInputElement; password.value = ''
      })} id="share-form">
        <label>{t('Source', '소스')}<select name="source" required>{sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <label>{t('File or folder path (empty = source root)', '파일·폴더 경로 (빈 값=소스 루트)')}<input name="path" /></label>
        <label>{t('Expires', '만료')}<input name="expires" type="datetime-local" required defaultValue={new Date(Date.now() + 7 * 86400000 - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16)} /></label>
        <label>{t('Optional password (8 characters minimum)', '선택 비밀번호 (8자 이상)')}<input name="password" type="password" minLength={8} autoComplete="new-password" /></label>
        <label>{t('Maximum downloads (empty = unlimited)', '다운로드 횟수 (빈 값=무제한)')}<input name="maximum" type="number" min={1} max={1000000} /></label>
        <button className="primary" disabled={busy}>{t('Create link', '링크 생성')}</button>
      </form>
      <p>{t('Password-protected links use the browser password prompt: leave username empty. Use HTTPS.', '비밀번호 링크는 브라우저 인증 창을 사용합니다. 사용자명은 비워 두세요. HTTPS로 제공하세요.')}</p>
      {secret && <div className="notice" role="status"><p>{t('Copy now. The link is shown once.', '지금 복사하세요. 링크는 한 번만 표시합니다.')}</p><pre>{secret}</pre><button onClick={() => setSecret('')}>{t('Dismiss', '닫기')}</button></div>}
    </section>
    {shares.map(share => <section className="card" key={String(share.ID)}><p>{sources.find(s => s.id === share.SOURCE_ID)?.name} · {String(share.PATH) || '/'} · {new Date(Number(share.EXPIRES_AT)).toLocaleString()}</p><p>{t('Downloads', '다운로드')}: {String(share.DOWNLOADS)} / {Number(share.MAX_DOWNLOADS) || '∞'} · {share.PROTECTED ? t('Password', '비밀번호') : ''}</p><button disabled={busy || Boolean(share.REVOKED)} onClick={() => void run(() => api('/shares/' + share.ID, 'DELETE'))}>{share.REVOKED ? t('Revoked', '철회됨') : t('Revoke', '철회')}</button></section>)}
  </>
}
