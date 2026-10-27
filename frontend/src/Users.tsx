import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type User = { id: number; username: string; role: string; active: boolean; sources: string[]; totpEnabled: boolean; oidcMatch: string; oidcLinked: boolean; oidcIdentity: { issuer?: string; subject?: string } }
type Source = { id: string; name: string }
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
export default function Users({ api, sources }: { api: Api; sources: Source[] }) {
  const [users, setUsers] = useState<User[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function load() { setUsers(await api<User[]>('/users')) }
  useEffect(() => { void load().catch(e => setError(String(e))) }, [])
  async function run(action: () => Promise<unknown>) { setError(''); setBusy(true); try { await action(); await load() } catch (e) { setError(String(e)) } finally { setBusy(false) } }
  function submit(e: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<unknown>) { e.preventDefault(); const form = e.currentTarget; const data = new FormData(form); void run(async () => { await action(data); form.reset() }) }
  return <><h1>{t('Users', '사용자')}</h1>{error && <p className="error" role="alert">{error}</p>}
    <form className="card" onSubmit={e => submit(e, data => api('/users', 'POST', Object.fromEntries(data)))}>
      <h2>{t('Create user', '사용자 생성')}</h2><label>{t('Username', '사용자명')}<input name="username" required minLength={3} maxLength={100} pattern="[A-Za-z0-9_.\-]+" autoComplete="off" /></label>
      <label>{t('Initial password', '초기 비밀번호')}<input name="password" type="password" required minLength={8} autoComplete="new-password" /></label><small>{t('8+ characters with an ASCII letter, digit and punctuation; at most 72 UTF-8 bytes.', '8자 이상, 영문자·숫자·ASCII 특수문자 포함, UTF-8 72바이트 이하.')}</small>
      <label>{t('Role', '역할')}<select name="role"><option>USER</option><option>ADMIN</option></select></label><button disabled={busy}>{t('Create user', '사용자 생성')}</button>
    </form>
    {users.map(user => <section className="card" key={user.id}><h2>{user.username}</h2><p>{user.role} · {user.active ? t('Active', '활성') : t('Disabled', '비활성')} · TOTP {user.totpEnabled ? t('On', '켜짐') : t('Off', '꺼짐')}</p>
      <p>{t('External identity', '외부 연결')}: {user.oidcLinked ? t('Linked', '연결됨') : t('Not linked', '연결 없음')}</p>{user.oidcLinked && <p><code>{user.oidcIdentity.issuer} · {user.oidcIdentity.subject}</code></p>}
      <form onSubmit={e => submit(e, data => api('/users/' + user.id + '/oidc', 'PUT', { match: data.get('match') }))}><label>{t('Initial external email or subject (configured issuer)', '최초 외부 email 또는 sub (설정된 issuer)')}<input name="match" defaultValue={user.oidcMatch} maxLength={320} required /></label><button disabled={busy}>{t('Save external match', '외부 연결 기준 저장')}</button></form>
      <button disabled={busy} onClick={() => void run(() => api('/users/' + user.id + '/oidc', 'DELETE'))}>{t('Unlink external identity', '외부 연결 해제')}</button>
      <form key={JSON.stringify(user)} onSubmit={e => submit(e, data => api('/users/' + user.id, 'PUT', { role: data.get('role'), active: data.get('active') === 'true', sources: data.getAll('sources') }))}>
        <label>{t('Role', '역할')}<select name="role" defaultValue={user.role}><option>USER</option><option>ADMIN</option></select></label>
        <label>{t('Account status', '계정 상태')}<select name="active" defaultValue={String(user.active)}><option value="true">{t('Active', '활성')}</option><option value="false">{t('Disabled', '비활성')}</option></select></label>
        <label>{t('Allowed sources (USER: none = no access; ADMIN: all)', '허용 소스 (USER 미선택=권한 없음, ADMIN=전체)')}<select name="sources" multiple defaultValue={user.sources}>{sources.map(s => <option value={s.id} key={s.id}>{s.name}</option>)}</select></label>
        <button disabled={busy}>{t('Save user', '사용자 저장')}</button>
      </form>
      <form onSubmit={e => submit(e, data => api('/users/' + user.id + '/password', 'POST', { password: data.get('password') }))}><label>{t('New password', '새 비밀번호')}<input name="password" type="password" required minLength={8} autoComplete="new-password" /></label><button disabled={busy}>{t('Reset password', '비밀번호 재설정')}</button></form>
      <button disabled={busy} onClick={() => { if (confirm(t('Reset two-factor authentication?', '2단계 인증을 초기화할까요?'))) void run(() => api('/users/' + user.id + '/totp-reset', 'POST')) }}>{t('Reset TOTP', 'TOTP 초기화')}</button>
      <button disabled={busy} onClick={() => { if (confirm(t('Delete this user?', '사용자를 삭제할까요?'))) void run(() => api('/users/' + user.id, 'DELETE')) }}>{t('Delete user', '사용자 삭제')}</button>
    </section>)}
  </>
}
