import { useState } from 'react'
type Theme = 'auto' | 'light' | 'dark'
export function useTheme() {
  const [theme, update] = useState<Theme>(() => {
    try { const t = localStorage.getItem('nasquire.theme'); return t === 'light' || t === 'dark' ? t : 'auto' } catch { return 'auto' }
  })
  function setTheme(value: Theme) {
    update(value)
    if (value === 'auto') delete document.documentElement.dataset.theme
    else document.documentElement.dataset.theme = value
    try { localStorage.setItem('nasquire.theme', value) } catch { /* 저장소 접근 제한 */ }
  }
  return { theme, setTheme }
}
