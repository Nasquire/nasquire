package com.bluesounds.nasquire.core;

import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;

public record Source(String id, String name, Path root, List<String> includes, List<String> excludes, String kind) {
    public Source(String id, String name, Path root, List<String> includes, List<String> excludes) {
        this(id, name, root, includes, excludes, "directory");
    }
    public Source {
        if (!Set.of("directory", "upload").contains(kind)) throw new IllegalArgumentException("소스 종류 오류");
        Objects.requireNonNull(id);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("소스 이름이 필요합니다");
        root = root.toAbsolutePath().normalize();
        includes = includes == null ? List.of() : List.copyOf(includes);
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
        for (String glob : includes) FileSystems.getDefault().getPathMatcher("glob:" + glob);
        for (String glob : excludes) FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    public static String normalized(Path path) {
        return Normalizer.normalize(path.toString().replace('\\', '/'), Normalizer.Form.NFC);
    }

    public boolean accepts(Path relative) {
        for (Path component : relative) {
            String n = component.toString();
            if (n.startsWith(".nasquire-") || Set.of("@eaDir", "#recycle", "#snapshot", ".DS_Store", "Thumbs.db").contains(n)
                    || n.startsWith("~$") || n.startsWith(".~") || n.endsWith("~")
                    || n.matches("(?i).*\\.(tmp|temp|part|swp|swo|crdownload)$")) return false;
        }
        String normalized = normalized(relative);
        Path candidate = Path.of(normalized);
        return (includes.isEmpty() || includes.stream().anyMatch(g -> matches(g, candidate)))
                && excludes.stream().noneMatch(g -> matches(g, candidate));
    }

    public boolean acceptsDirectory(Path relative) {
        // 포함 glob은 파일에 적용하며 기본/사용자 제외는 디렉터리에도 적용한다.
        return new Source(id, name, root, List.of(), excludes).accepts(relative);
    }

    private boolean matches(String glob, Path path) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(path)
                || (glob.startsWith("**/") && FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)).matches(path));
    }
}
