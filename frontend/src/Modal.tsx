import { useEffect, useRef, type ReactNode } from 'react'

// 네이티브 모달로 배경을 비활성화하고 Tab 순환·중첩 닫기·포커스 복귀를 보완한다.
export default function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const trigger = document.activeElement as HTMLElement | null
    const dialog = ref.current!
    dialog.showModal()
    return () => { dialog.close(); trigger?.focus() }
  }, [])
  return <dialog ref={ref} className="settings-modal" role="dialog" aria-modal="true" aria-label={title} onKeyDown={e => {
    if (e.key !== 'Tab') return
    e.stopPropagation()
    const controls = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],[tabindex]')).filter(node => node.tabIndex >= 0 && !node.matches(':disabled') && node.getClientRects().length > 0)
    const first = controls[0], last = controls[controls.length - 1]
    if (!first) { e.preventDefault(); return }
    if (e.shiftKey && (document.activeElement === first || document.activeElement === e.currentTarget)) { e.preventDefault(); last.focus() }
    else if (!e.shiftKey && (document.activeElement === last || document.activeElement === e.currentTarget)) { e.preventDefault(); first.focus() }
  }} onCancel={e => { e.preventDefault(); e.stopPropagation(); onClose() }} onClick={e => { if (e.target === e.currentTarget) { const rect = e.currentTarget.getBoundingClientRect(); if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) onClose() } }}>
    <div className="modal-header"><h1>{title}</h1><button type="button" aria-label={navigator.language.toLowerCase().startsWith('ko') ? '닫기' : 'Close'} onClick={onClose}>×</button></div>
    <div className="modal-content">{children}</div>
  </dialog>
}
