package com.bluesounds.nasquire.core;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.bluesounds.nasquire.core.AccessRule.Effect.*;
import static com.bluesounds.nasquire.core.AccessRule.Subject.*;

class AccessPolicyTest {
    @TempDir Path temp;
    private Source source() { return new Source("s", "Documents", temp, List.of(), List.of()); }
    private AccessRule rule(String path, AccessRule.Subject subject, AccessRule.Effect effect) {
        return new AccessRule("s", path, subject, effect);
    }
    private RuleAccessPolicy policy() {
        return new RuleAccessPolicy(List.of(rule("public", everyone(), ALLOW), rule("a/b", group("team"), ALLOW),
                rule("a/b/private", group("team"), DENY), rule("a/b/private/own", user("alice"), ALLOW),
                rule("a/b/conflict", group("other"), DENY), rule("a/b/conflict", user("alice"), ALLOW)));
    }
    @Test void decisionTable() {
        var alice = new Principal("alice", Set.of("team", "other"));
        var scope = policy().scopeFor(alice, List.of(source()));
        var expected = Map.of("public/x", true, "a/b/x", true, "a/b/private/x", false,
                "a/b/private/own/x", true, "a/b/conflict/x", false, "a/bc/x", false,
                "unknown/x", false, "a/b", true, "a/b/private", false);
        expected.forEach((path, allow) -> assertEquals(allow, scope.allows("s", "s:" + path), path));
        var guest = policy().scopeFor(new Principal("guest", Set.of()), List.of(source()));
        assertTrue(guest.allows("s", "s:public/x")); assertFalse(guest.allows("s", "s:a/b/x"));
        assertFalse(policy().scopeFor(alice, List.of()).allows("s", "s:public/x"));
        var root = new RuleAccessPolicy(List.of(rule("", everyone(), ALLOW), rule("secret", everyone(), DENY),
                rule("secret/open", user("alice"), ALLOW))).scopeFor(alice, List.of(source()));
        assertTrue(root.allows("s", "s:x")); assertFalse(root.allows("s", "s:secret/x"));
        assertTrue(root.allows("s", "s:secret/open/x"));
    }
    @Test void validationLimitsAndImmutableInputs() {
        for (String id : List.of("", " ", "a\n", "a\0", "a\u200b", "a".repeat(257)))
            assertThrows(IllegalArgumentException.class, () -> new Principal(id, Set.of()));
        var groups = new HashSet<>(Set.of(" team "));
        var principal = new Principal(" alice ", groups); groups.clear();
        assertEquals(Set.of("team"), principal.groupIds()); assertEquals("alice", principal.userId());
        assertEquals("한", new Principal("한", Set.of()).userId());
        assertThrows(IllegalArgumentException.class, () -> new AccessRule.Subject("invalid"));
        assertThrows(IllegalArgumentException.class, () -> new AccessRule.Subject("group: "));
        assertThrows(IllegalArgumentException.class, () -> rule("../x", everyone(), ALLOW));
        assertThrows(IllegalArgumentException.class, () -> new Principal("a", new HashSet<>(IntStream.range(0, 257).mapToObj(i -> "g" + i).toList())));
        assertThrows(IllegalArgumentException.class, () -> new RuleAccessPolicy(Collections.nCopies(4097, rule("", everyone(), ALLOW))));
        var rules = new ArrayList<>(List.of(rule("a", everyone(), ALLOW)));
        var immutable = new RuleAccessPolicy(rules); rules.clear();
        assertTrue(immutable.scopeFor(principal, List.of(source())).allows("s", "s:a/x"));
        var maximum = IntStream.range(0, 1024).mapToObj(i -> rule("p" + i, everyone(), ALLOW)).toList();
        assertDoesNotThrow(() -> new RuleAccessPolicy(maximum).scopeFor(principal, List.of(source())));
        var excessive = new ArrayList<>(maximum); excessive.add(rule("extra", everyone(), DENY));
        assertThrows(IllegalArgumentException.class, () -> new RuleAccessPolicy(excessive).scopeFor(principal, List.of(source())));
    }
    @Test void maximumCompiledPrefixesRemainSearchable() throws Exception {
        var rules = IntStream.range(0, 1024).mapToObj(i -> rule("p" + i, everyone(), ALLOW)).toList();
        var scope = new RuleAccessPolicy(rules).scopeFor(Principal.owner(), List.of(source()));
        try (var index = new SearchIndex(temp.resolve("maximum"))) {
            index.replace(source(), "p1023/yes.txt", 1, List.of(new SearchIndex.Chunk(1, 0, "ocean")));
            index.refresh();
            assertEquals(1, index.search("ocean", scope, 1, "keyword").size());
        }
    }
    @Test void compiledRulesInBm25HnswRrfAndDocument() throws Exception { verify(null, temp.resolve("local")); }
    @Test void compiledRulesInQdrantBeforeLimitAndUntrustedResponses() throws Exception {
        try (var fake = new FakeQdrant(null)) {
            verify(fake, temp.resolve("remote"));
            assertTrue(fake.requests("/points/query").stream().anyMatch(r -> r.body().contains("must_not")));
        }
    }
    private void verify(FakeQdrant fake, Path folder) throws Exception {
        var principal = new Principal("alice", Set.of("team", "other"));
        var scope = policy().scopeFor(principal, List.of(source()));
        var paths = List.of("a/b/private/no.txt", "a/b/conflict/no.txt", "a/bc/no.txt", "a/b/private/own/yes.txt");
        try (var index = new SearchIndex(folder)) {
            Providers.EmbeddingProvider provider = new Providers.EmbeddingProvider() {
                public String modelId() { return "test"; }
                public int dimensions() { return 2; }
                public float[] embed(String text) { return new float[]{1, 0}; }
            };
            index.configureVectors(provider, fake == null ? null : new QdrantVectorStore(fake.url(), "test", "", 2));
            index.requestRebuild(); index.beginRebuild();
            for (String path : paths) index.replace(source(), path, 1, List.of(new SearchIndex.Chunk(1, 0, "ocean")));
            index.finishRebuild(true); index.refresh();
            for (String mode : List.of("keyword", "vector", "hybrid")) {
                assertEquals(List.of("s:a/b/private/own/yes.txt"), index.search("ocean", scope, 1, mode).stream().map(SearchIndex.Hit::path).toList(), mode);
            }
            for (String path : paths) assertEquals(path.contains("yes") ? 1 : 0, index.document("s:" + path, scope).size(), path);
            if (fake != null) {
                fake.ignoreFilter = true;
                for (String mode : List.of("vector", "hybrid")) assertTrue(index.search("ocean", scope, 10, mode).stream()
                        .allMatch(h -> h.path().equals("s:a/b/private/own/yes.txt")));
            }
        }
    }
}
