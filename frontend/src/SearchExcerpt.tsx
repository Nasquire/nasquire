import { useState } from 'react'

const ko = navigator.language.toLowerCase().startsWith('ko')

export default function SearchExcerpt({ text, query }: { text: string; query: string }) {
  const [expanded, setExpanded] = useState(false)
  const compact = text.replace(/\s+/gu, ' ').trim()
  // 사용자 질의의 문자·숫자 토큰만 사용하며 정규식 메타문자는 포함하지 않는다.
  const tokens = [...new Set(query.match(/[\p{L}\p{N}\p{M}]+/gu) ?? [])].sort((a, b) => b.length - a.length)
  const pattern = tokens.length ? new RegExp(tokens.join('|'), 'giu') : undefined
  const first = pattern ? compact.search(pattern) : -1
  const start = Math.max(0, Math.min(first < 0 ? 0 : first - 100, compact.length - 360))
  const end = Math.min(compact.length, start + 360)
  const excerpt = expanded ? compact : compact.slice(start, end)
  const matches = pattern ? [...excerpt.matchAll(pattern)] : []
  let cursor = 0
  const content = matches.flatMap((match, i) => {
    const from = match.index
    const nodes = [<span key={`text-${i}`}>{excerpt.slice(cursor, from)}</span>, <mark key={`match-${i}`}>{match[0]}</mark>]
    cursor = from + match[0].length
    return nodes
  })
  return <>
    <p className="result-text">{!expanded && start > 0 && '…'}{content}{excerpt.slice(cursor)}{!expanded && end < compact.length && '…'}</p>
    {(start > 0 || end < compact.length) && <button type="button" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? (ko ? '접기' : 'Show less') : (ko ? '더 보기' : 'Show more')}</button>}
  </>
}
