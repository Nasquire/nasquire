package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final Storage storage;
    private final SearchIndex index;
    private final RerankerSettings reranker;
    public DocumentService(Storage storage, SearchIndex index, RerankerSettings reranker) { this.storage = storage; this.index = index; this.reranker = reranker; }
    public Set<String> scope(Storage.Identity identity, String source) {
        if (identity == null) identity = storage.sessionIdentity();
        var allowed = new HashSet<String>();
        for (Source s : storage.sources()) if (identity.sources().contains(s.id())) allowed.add(s.id());
        if (source != null && !source.isBlank()) {
            if (!allowed.contains(source)) throw new org.springframework.security.access.AccessDeniedException("소스 접근 불가");
            return Set.of(source);
        }
        return allowed;
    }
    public List<SearchIndex.Hit> search(Storage.Identity identity, String query, String source, int limit) throws IOException {
        return search(identity, query, source, limit, null);
    }
    public List<SearchIndex.Hit> search(Storage.Identity identity, String query, String source, int limit, String mode) throws IOException {
        return search(identity, query, source, limit, mode, false);
    }
    public List<SearchIndex.Hit> search(Storage.Identity identity, String query, String source, int limit, String mode, boolean rerank) throws IOException {
        return searchTraced(identity, query, source, limit, mode, rerank).hits();
    }
    /**
     * 검색 결과와 처리한 원격 서버 기록(기능 이름 → 서버 이름·페일오버). rerankApplied는 리랭크 요청 시 순위를 실제로 바꿨는지.
     * 서버 주소는 담지 않는다.
     */
    public record Searched(List<SearchIndex.Hit> hits, Map<String, com.bluesounds.nasquire.core.remote.RemoteTrace.Entry> remote, boolean rerankApplied) { }
    public Searched searchTraced(Storage.Identity identity, String query, String source, int limit, String mode, boolean rerank) throws IOException {
        // 소스 범위를 적용한 결과만 외부 공급자에 전달한다. 원격 응답은 순서만 바꿀 수 있다.
        com.bluesounds.nasquire.core.remote.RemoteTrace.begin();
        try {
            var reranked = reranker.rerank(query, index.search(query, AccessScope.sources(scope(identity, source)), limit, mode), rerank);
            return new Searched(reranked.hits(), com.bluesounds.nasquire.core.remote.RemoteTrace.end(), reranked.applied());
        } finally { com.bluesounds.nasquire.core.remote.RemoteTrace.end(); }
    }
    public Map<String, Object> document(Storage.Identity identity, String path, Integer from, Integer to, Integer offset, Integer length) throws IOException {
        if (path == null || path.length() > 4096 || !path.contains(":")) throw new IllegalArgumentException("소스 ID:상대경로가 필요합니다");
        if (from != null && from < 1 || to != null && to < 1 || from != null && to != null && to < from
                || offset != null && offset < 0 || length != null && (length < 1 || length > 100000)
                || (from != null || to != null) && (offset != null || length != null)) throw new IllegalArgumentException("문서 범위 오류");
        var hits = index.document(path, AccessScope.sources(scope(identity, path.substring(0, path.indexOf(':')))));
        if (hits.isEmpty()) throw new NoSuchElementException("문서 없음");
        int start = offset == null ? 0 : offset;
        int max = length == null ? 100000 : length;
        var text = new StringBuilder();
        var locations = new ArrayList<Map<String, Object>>();
        long total = 0;
        for (var hit : hits) {
            if (from != null && hit.page() < from || to != null && hit.page() > to) continue;
            String part = hit.text();
            int skip = Math.max(0, start - (int)total);
            if (skip < part.length() && text.length() < max) {
                locations.add(Map.of("offset", hit.offset(), "length", hit.text().length(), "location", hit.location()));
                text.append(part, skip, Math.min(part.length(), skip + max - text.length()));
            }
            total += part.length();
        }
        return Map.of("path", path, "text", text.toString(), "offset", start, "length", text.length(), "totalCharacters", total, "truncated", total > (long)start + text.length(), "locations", locations);
    }
}
