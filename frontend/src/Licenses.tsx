import { useEffect, useState } from 'react'
import Modal from './Modal'
const ko = navigator.language.toLowerCase().startsWith('ko')
const files = ['LICENSE.txt', 'LICENSE-EXCEPTIONS.md', 'react-LICENSE.txt']
export default function Licenses({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState(0), [text, setText] = useState(''), [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController(); setText(''); setError('')
    void fetch('/' + files[tab], { signal: controller.signal }).then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text() }).then(setText).catch(e => { if (!controller.signal.aborted) setError(String(e)) })
    return () => controller.abort()
  }, [tab])
  return <Modal title={ko ? '라이선스' : 'Licenses'} onClose={onClose}>
    <div role="tablist" aria-label={ko ? '라이선스 파일' : 'License files'} className="license-tabs">{files.map((file, i) => <button key={file} id={'license-tab-' + i} role="tab" aria-selected={i === tab} aria-controls="license-text" tabIndex={i === tab ? 0 : -1} onClick={() => setTab(i)} onKeyDown={e => { const next = e.key === 'ArrowRight' ? (i + 1) % files.length : e.key === 'ArrowLeft' ? (i + files.length - 1) % files.length : e.key === 'Home' ? 0 : e.key === 'End' ? files.length - 1 : null; if (next !== null) { e.preventDefault(); setTab(next); document.getElementById('license-tab-' + next)?.focus() } }}>{file}</button>)}</div>
    <p><a href={'/' + files[tab]}>{ko ? '원본 파일' : 'Original file'}</a></p>
    {error && <p className="error" role="alert">{error}</p>}
    <pre id="license-text" role="tabpanel" aria-labelledby={'license-tab-' + tab} tabIndex={0}>{text || (ko ? '불러오는 중…' : 'Loading…')}</pre>
  </Modal>
}
