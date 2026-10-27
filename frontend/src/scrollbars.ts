// 네이티브 스크롤은 유지하고 표시만 겹쳐 그려 스크롤바 공간과 레이아웃 이동을 없앤다.
export function installScrollbars() {
  const forcedColors = matchMedia('(forced-colors: active)')
  const bars = new Map<Element, HTMLDivElement>()
  const sources = new WeakMap<Element, Element>()
  let hovered: Element | null = null
  let frame = 0
  let drag: { source: HTMLElement; axis: 'x' | 'y'; start: number; offset: number; ratio: number } | null = null

  function schedule() {
    if (!frame) frame = requestAnimationFrame(render)
  }

  function render() {
    frame = 0
    const active = new Set<Element>()
    if (!forcedColors.matches) {
      const modal = [...document.querySelectorAll('dialog[open]')].at(-1)
      const focus = document.activeElement === document.body ? null : document.activeElement
      for (const start of [hovered, focus, drag?.source]) {
        if (modal && start && !modal.contains(start)) continue
        for (let node = start; node; node = node.parentElement) {
          active.add(node)
          if (node === modal) break
        }
      }
    }
    const visible = new Set<Element>()
    for (const node of active) {
      if (!(node instanceof HTMLElement) || !node.isConnected) continue
      const root = node === document.scrollingElement
      const style = getComputedStyle(node)
      const overflowY = root && style.overflowY === 'visible' ? getComputedStyle(document.body).overflowY : style.overflowY
      const overflowX = root && style.overflowX === 'visible' ? getComputedStyle(document.body).overflowX : style.overflowX
      const vertical = node.scrollHeight > node.clientHeight && (/auto|scroll/.test(overflowY) || root && overflowY === 'visible')
      const horizontal = node.scrollWidth > node.clientWidth && (/auto|scroll/.test(overflowX) || root && overflowX === 'visible')
      if ((!vertical && !horizontal) || style.visibility === 'hidden' || !node.getClientRects().length) continue
      const rect = node.getBoundingClientRect()
      let left = root ? 0 : rect.left + node.clientLeft
      let top = root ? 0 : rect.top + node.clientTop
      let right = left + (root ? document.documentElement.clientWidth : node.clientWidth)
      let bottom = top + (root ? document.documentElement.clientHeight : node.clientHeight)
      // 중첩 스크롤·모달 밖으로 표시가 새지 않도록 조상과 뷰포트로 자른다.
      for (let parent = node.parentElement; parent && !root; parent = parent.parentElement) {
        const css = getComputedStyle(parent)
        const box = parent.getBoundingClientRect()
        if (/auto|scroll|hidden|clip/.test(css.overflowX)) { left = Math.max(left, box.left); right = Math.min(right, box.right) }
        if (/auto|scroll|hidden|clip/.test(css.overflowY)) { top = Math.max(top, box.top); bottom = Math.min(bottom, box.bottom) }
      }
      left = Math.max(0, left); top = Math.max(0, top)
      right = Math.min(innerWidth, right); bottom = Math.min(innerHeight, bottom)
      if (right <= left || bottom <= top) continue
      visible.add(node)
      let layer = bars.get(node)
      if (!layer) {
        layer = document.createElement('div')
        layer.className = 'scrollbar-overlay'
        layer.setAttribute('aria-hidden', 'true')
        for (const axis of ['x', 'y']) {
          const thumb = document.createElement('div')
          thumb.className = `scrollbar-thumb scrollbar-${axis}`
          thumb.dataset.axis = axis
          sources.set(thumb, node)
          layer.append(thumb)
        }
        bars.set(node, layer)
      }
      // dialog의 top layer 안에 표시해야 중첩 모달에서도 보인다.
      const host = node.closest('dialog[open]') ?? document.body
      if (layer.parentElement !== host) host.append(layer)
      const width = right - left, height = bottom - top
      Object.assign(layer.style, { left: `${left}px`, top: `${top}px`, width: `${width}px`, height: `${height}px` })
      for (const axis of ['x', 'y'] as const) {
        const thumb = layer.querySelector<HTMLElement>(`.scrollbar-${axis}`)!
        const show = axis === 'x' ? horizontal : vertical
        thumb.hidden = !show
        if (!show) continue
        const viewport = axis === 'x' ? node.clientWidth : node.clientHeight
        const total = axis === 'x' ? node.scrollWidth : node.scrollHeight
        const offset = axis === 'x' ? node.scrollLeft : node.scrollTop
        const track = Math.max(0, (axis === 'x' ? width : height) - 4 - (horizontal && vertical ? 8 : 0))
        const size = Math.min(track, Math.max(24, track * viewport / total))
        const position = 2 + (track - size) * offset / (total - viewport)
        Object.assign(thumb.style, axis === 'x' ? { width: `${size}px`, left: `${position}px` } : { height: `${size}px`, top: `${position}px` })
        thumb.dataset.ratio = String((total - viewport) / (track - size || 1))
      }
    }
    for (const [node, layer] of bars) {
      if (!visible.has(node)) { layer.remove(); bars.delete(node) }
    }
  }

  document.addEventListener('pointerover', event => {
    const target = event.target as Element
    hovered = sources.get(target) ?? target
    schedule()
  })
  document.addEventListener('pointerout', event => {
    const target = event.relatedTarget as Element | null
    hovered = target ? sources.get(target) ?? target : null
    schedule()
  })
  document.addEventListener('pointerdown', event => {
    const thumb = event.target as HTMLElement
    const source = sources.get(thumb) as HTMLElement | undefined
    if (!source) return
    event.preventDefault()
    const axis = thumb.dataset.axis as 'x' | 'y'
    drag = { source, axis, start: axis === 'x' ? event.clientX : event.clientY, offset: axis === 'x' ? source.scrollLeft : source.scrollTop, ratio: Number(thumb.dataset.ratio) }
    thumb.setPointerCapture(event.pointerId)
  })
  document.addEventListener('wheel', event => {
    const source = sources.get(event.target as Element) as HTMLElement | undefined
    if (!source) return
    event.preventDefault()
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? source.clientHeight : 1
    source.scrollBy(event.deltaX * unit, event.deltaY * unit)
  }, { passive: false })
  document.addEventListener('pointermove', event => {
    if (!drag) return
    const value = drag.offset + ((drag.axis === 'x' ? event.clientX : event.clientY) - drag.start) * drag.ratio
    if (drag.axis === 'x') drag.source.scrollLeft = value
    else drag.source.scrollTop = value
    schedule()
  })
  for (const name of ['pointerup', 'pointercancel'] as const) document.addEventListener(name, () => { drag = null; schedule() })
  for (const name of ['scroll', 'focusin', 'focusout', 'input'] as const) document.addEventListener(name, schedule, true)
  window.addEventListener('resize', schedule)
  forcedColors.addEventListener('change', schedule)
  // 내용 갱신·textarea 크기 조절은 포인터 이동 없이도 위치에 반영한다.
  const resize = new ResizeObserver(schedule)
  const observe = () => {
    resize.disconnect()
    resize.observe(document.documentElement)
    document.querySelectorAll('main,aside,dialog,textarea,pre,.content').forEach(node => resize.observe(node))
    schedule()
  }
  new MutationObserver(records => {
    if (records.some(record => !(record.target instanceof Element && record.target.closest('.scrollbar-overlay')) && (record.type === 'characterData' || [...record.addedNodes, ...record.removedNodes].some(node => !(node instanceof Element && node.matches('.scrollbar-overlay')))))) observe()
  }).observe(document.getElementById('root')!, { childList: true, subtree: true, characterData: true })
  observe()
}
