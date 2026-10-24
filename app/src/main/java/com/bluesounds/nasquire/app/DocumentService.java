package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final Storage storage;
    private final SearchIndex index;
    public DocumentService(Storage storage, SearchIndex index) { this.storage = storage; this.index = index; }
    public Set<String> scope(Storage.Identity identity, String source) {
        var allowed = new HashSet<String>();
        for (Source s : storage.sources()) if (identity == null || identity.sources().isEmpty() || identity.sources().contains(s.id())) allowed.add(s.id());
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
        return index.search(query, scope(identity, source), limit, mode);
    }
    public Map<String, Object> document(Storage.Identity identity, String path, Integer from, Integer to, Integer offset, Integer length) throws IOException {
        if (path == null || path.length() > 4096 || !path.contains(":")) throw new IllegalArgumentException("소스 ID:상대경로가 필요합니다");
        if (from != null && from < 1 || to != null && to < 1 || from != null && to != null && to < from
                || offset != null && offset < 0 || length != null && (length < 1 || length > 100000)
                || (from != null || to != null) && (offset != null || length != null)) throw new IllegalArgumentException("문서 범위 오류");
        var hits = index.document(path, scope(identity, path.substring(0, path.indexOf(':'))));
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
