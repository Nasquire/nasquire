import { useEffect, useState } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
type Api = <T>(path: string, method?: string, body?: unknown) => Promise<T>
export default function Oidc({ api }: { api: Api }) {
  const [settings, setSettings] = useState<Record<string, string | boolean>>()
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  async function load() { setSettings(await api('/oidc')) }
  useEffect(() => { void load().catch(e => setMessage(String(e))) }, [])
  async function run(action: () => Promise<unknown>) { setBusy(true); setMessage(''); try { await action(); await load(); setMessage(t('Done', '완료')) } catch (e) { setMessage(String(e)) } finally { setBusy(false) } }
  return <section className="card" id="oidc-settings"><h2>{t('External sign in', '외부 로그인')}</h2>
    <p>{t('HTTPS public URL and issuer required (loopback HTTP for development only). Local sign in stays available. Accounts with local TOTP must also enter their code.', 'HTTPS 공개 주소와 issuer가 필요합니다 (루프백 HTTP는 개발 전용). 로컬 로그인은 유지되며 로컬 TOTP 계정은 코드를 추가 입력합니다.')}</p>
    {settings && <><p>{String(settings.disabledReason)}</p><p>{t('Redirect URI', '콜백 주소')}: <code>{String(settings.redirectUri)}</code></p>
      <form key={JSON.stringify(settings)} onSubmit={e => { e.preventDefault(); const data = new FormData(e.currentTarget); const form = e.currentTarget; void run(async () => { await api('/oidc', 'PUT', Object.fromEntries(data)); form.reset() }) }}>
        {(['enabled', 'autoSignup'] as const).map((key, i) => <label key={key}>{[t('Enable external sign in', '외부 로그인 활성화'), t('Automatic sign up (USER, no sources)', '자동 가입 (USER, 소스 권한 없음)')][i]}<select name={key} defaultValue={String(settings[key])}><option value="false">{t('Off', '끔')}</option><option value="true">{t('On', '켬')}</option></select></label>)}
        {(['issuer', 'clientId', 'scopes', 'groupClaim', 'adminGroup', 'buttonLabel'] as const).map((key, i) => <label key={key}>{[t('Issuer URL', 'Issuer 주소'), t('Client ID', '클라이언트 ID'), t('Scopes', '스코프'), t('Group claim (optional)', '그룹 클레임 (선택)'), t('Administrator group (optional)', '관리자 그룹 (선택)'), t('Button label', '버튼 표시 이름')][i]}<input name={key} maxLength={2048} defaultValue={String(settings[key])} /></label>)}
        <label>{t('Initial account matching', '최초 계정 연결 기준')}<select name="linkBy" defaultValue={String(settings.linkBy)}><option value="email">email (email_verified=true)</option><option value="sub">sub</option></select></label>
        <label>{t('Client secret (blank keeps saved; issuer change discards saved secret)', '클라이언트 비밀 (빈 값 유지, issuer 변경 시 기존 비밀 폐기)')}<input name="clientSecret" type="password" autoComplete="new-password" maxLength={2048} placeholder={settings.hasSecret ? t('Saved; hidden', '저장됨·비공개') : ''} /></label>
        <button disabled={busy}>{t('Save external sign in', '외부 로그인 저장')}</button>
      </form>
      <button disabled={busy} onClick={() => void run(() => api('/oidc/test', 'POST'))}>{t('Test discovery only', 'Discovery 연결 시험')}</button>
      <button disabled={busy || !settings.hasSecret} onClick={() => void run(() => api('/oidc/secret', 'DELETE'))}>{t('Delete client secret', '클라이언트 비밀 삭제')}</button>
    </>}{message && <p role="status">{message}</p>}
  </section>
}
