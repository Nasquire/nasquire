import { useEffect, useState, type FormEvent } from 'react'
const ko = navigator.language.toLowerCase().startsWith('ko')
const t = (en: string, kr: string) => ko ? kr : en
export default function Security({ api }: { api: <T>(path: string, method?: string, body?: unknown) => Promise<T> }) {
  const [enabled, setEnabled] = useState(false)
  const [enrollment, setEnrollment] = useState<{ secret: string; uri: string }>()
  const [codes, setCodes] = useState<string[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { void api<{ enabled: boolean }>('/totp').then(s => setEnabled(s.enabled)).catch(e => setError(String(e))) }, [])
  async function run(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() } catch (e) { setError(String(e)) } finally { setBusy(false) }
  }
  function submit(e: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<void>) {
    e.preventDefault(); const form = e.currentTarget; const data = new FormData(form)
    void run(async () => { await action(data); form.reset() })
  }
  return <section className="card"><h1>{t('Two-step verification', '2단계 인증')}</h1>
    <p>{enabled ? t('Enabled', '켜짐') : t('Disabled', '꺼짐')}</p>
    <p>{t('Use an authenticator app. Each code can be used once. Keep recovery codes somewhere safe outside this browser.', '인증 앱을 사용하세요. 각 코드는 한 번만 사용할 수 있습니다. 복구 코드는 브라우저 밖의 안전한 곳에 보관하세요.')}</p>
    {error && <p className="error" role="alert">{error}</p>}
    {!enabled && !enrollment && <button disabled={busy} onClick={() => void run(async () => { setEnrollment(await api('/totp/enroll', 'POST')); setCodes([]) })}>{t('Set up authenticator', '인증 앱 등록')}</button>}
    {enrollment && <><p>{t('Enter this secret in your authenticator, then confirm one code within 10 minutes.', '인증 앱에 비밀을 입력하고 10분 안에 코드 한 번을 확인하세요.')}</p>
      <p style={{ overflowWrap: 'anywhere' }}><code>{enrollment.secret}</code></p><p style={{ overflowWrap: 'anywhere' }}><code>{enrollment.uri}</code></p>
      <form onSubmit={e => submit(e, async data => { const result = await api<{ recoveryCodes: string[] }>('/totp/enable', 'POST', Object.fromEntries(data)); setCodes(result.recoveryCodes); setEnrollment(undefined); setEnabled(true) })}>
        <label>{t('Authenticator code', '인증 앱 코드')}<input name="code" required pattern="[0-9]{6}" inputMode="numeric" autoComplete="one-time-code" /></label><button disabled={busy}>{t('Enable', '활성화')}</button><button type="button" disabled={busy} onClick={() => setEnrollment(undefined)}>{t('Cancel', '취소')}</button>
      </form></>}
    {codes.length > 0 && <div role="status"><strong>{t('Copy now. These ten single-use recovery codes will not be shown again.', '지금 복사하세요. 일회용 복구 코드 10개는 다시 표시되지 않습니다.')}</strong><pre>{codes.join('\n')}</pre><button onClick={() => setCodes([])}>{t('Dismiss', '닫기')}</button></div>}
    {enabled && <form onSubmit={e => submit(e, async data => { await api('/totp/disable', 'POST', Object.fromEntries(data)); setEnabled(false); setCodes([]) })}>
      <label>{t('Current password', '현재 비밀번호')}<input name="password" type="password" required autoComplete="current-password" /></label>
      <label>{t('Unused authenticator code (recovery codes cannot disable TOTP)', '사용하지 않은 인증 앱 코드 (복구 코드로 해제 불가)')}<input name="code" required pattern="[0-9]{6}" inputMode="numeric" autoComplete="one-time-code" /></label>
      <button disabled={busy}>{t('Disable two-step verification', '2단계 인증 해제')}</button></form>}
  </section>
}
