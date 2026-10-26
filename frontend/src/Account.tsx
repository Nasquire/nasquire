import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
export default function Account({ api, refreshAuth, passwordHint }: { api: <T>(path: string, method?: string, body?: unknown) => Promise<T>; refreshAuth: () => Promise<unknown>; passwordHint: string }) {
  const [account, setAccount] = useState<{ username: string; totpEnabled: boolean }>()
  const [error, setError] = useState(''), [message, setMessage] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => { void api<{ username: string; totpEnabled: boolean }>('/account').then(setAccount).catch(e => setError(String(e))) }, [])
  async function submit(e: FormEvent<HTMLFormElement>, kind: string) {
    e.preventDefault(); const form = e.currentTarget; const data = Object.fromEntries(new FormData(form))
    setBusy(true); setError(''); setMessage('')
    try {
      if (kind === 'password' && data.newPassword !== data.confirmation) throw new Error(t('Password confirmation does not match', '새 비밀번호 확인이 일치하지 않습니다'))
      await api('/account/' + kind, 'PUT', data); await refreshAuth(); form.reset()
      if (kind === 'username') setAccount(previous => previous && { ...previous, username: String(data.username) })
      setMessage(t('Account updated. Other sessions have been signed out.', '계정을 변경했습니다. 다른 세션은 로그아웃됩니다.'))
    } catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  return <section className="card" id="account-settings"><h2>{t('Account', '계정')}</h2><p>{t('Username', '사용자명')}: {account?.username}</p>
    {error && <p className="error" role="alert">{error}</p>}<p role="status">{message}</p>
    {(['password', 'username'] as const).map(kind => <form key={kind} id={'account-' + kind} onSubmit={e => void submit(e, kind)}>
      <h3>{kind === 'password' ? t('Change administrator password', '관리자 비밀번호 변경') : t('Change username', '사용자명 변경')}</h3>
      <label>{t('Current password', '현재 비밀번호')}<input name="currentPassword" type="password" required autoComplete="current-password" /></label>
      {kind === 'password' ? <><p>{passwordHint}</p><label>{t('New password', '새 비밀번호')}<input name="newPassword" type="password" required minLength={8} autoComplete="new-password" /></label><label>{t('Confirm new password', '새 비밀번호 확인')}<input name="confirmation" type="password" required minLength={8} autoComplete="new-password" /></label></> : <label>{t('New username (3–100 ASCII letters, digits or _.-)', '새 사용자명 (영문·숫자·_.- 3~100자)')}<input name="username" required minLength={3} maxLength={100} pattern="[A-Za-z0-9_.\-]+" autoComplete="username" /></label>}
      {account?.totpEnabled && <label>{t('Authenticator code', '인증 앱 코드')}<input name="code" required pattern="[0-9]{6}" inputMode="numeric" autoComplete="one-time-code" /></label>}
      <button className="primary" disabled={busy || !account}>{kind === 'password' ? t('Change password', '비밀번호 변경') : t('Change username', '사용자명 변경')}</button>
    </form>)}
  </section>
}
