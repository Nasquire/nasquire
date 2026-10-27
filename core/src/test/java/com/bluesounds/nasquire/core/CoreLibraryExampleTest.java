package com.bluesounds.nasquire.example;

import com.bluesounds.nasquire.core.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoreLibraryExampleTest {
    @TempDir Path temp;
    @Test void exampleAndAllEvents() throws Exception {
        Path documents = Files.createDirectories(temp.resolve("documents/team"));
        Path file = Files.writeString(documents.resolve("guide.txt"), "ocean guide");
        var events = new ArrayList<IndexEvent>();
        var source = new Source("docs", "Documents", documents.getParent(), List.of("**/*.txt"), List.of());
        try (var core = NasquireCore.builder(temp.resolve("data"))
                .sources(() -> List.of(source))
                .limits(8 * 1024 * 1024, 1_000_000, 0)
                .listener(events::add).build()) {
            core.scan();
            assertEquals(1, core.search("ocean", 10).size());
            assertEquals(1, core.document("docs:team/guide.txt").size());
            var alice = new Principal("alice", Set.of("team"));
            var rules = new RuleAccessPolicy(List.of(
                    new AccessRule("docs", "team", AccessRule.Subject.group("team"), AccessRule.Effect.ALLOW),
                    new AccessRule("docs", "team/private", AccessRule.Subject.everyone(), AccessRule.Effect.DENY),
                    new AccessRule("docs", "team/private/own", AccessRule.Subject.user("alice"), AccessRule.Effect.ALLOW)));
            core.setAccessPolicy(rules);
            assertTrue(core.search("ocean", 10).isEmpty());
            assertEquals(1, core.search("ocean", alice, 10).size());
            assertEquals(1, core.document("docs:team/guide.txt", alice).size());
            assertTrue(core.document("docs:team/guide.txt").isEmpty());
            core.setAccessPolicy(new RuleAccessPolicy(List.of()));
            assertTrue(core.search("ocean", alice, 10).isEmpty());
            core.setAccessPolicy(AccessPolicy.open());
            assertEquals(1, core.search("ocean", 10).size());
            var scope = new AccessScope(Set.of("docs"), Map.of("docs", List.of("team")));
            assertEquals(1, core.search("ocean", scope, 10, "keyword").size());
            Files.writeString(file, "ocean updated guide"); core.scan();
            Files.delete(file); core.scan();
            assertTrue(core.search("ocean", scope, 10, "keyword").isEmpty());
        }
        assertEquals(List.of(IndexEvent.Kind.ADDED, IndexEvent.Kind.UPDATED, IndexEvent.Kind.DELETED),
                events.stream().map(IndexEvent::kind).toList());
        for (var event : events) {
            assertEquals("docs", event.sourceId()); assertEquals("team/guide.txt", event.relativePath());
            assertTrue(event.size() > 0); assertTrue(event.mtime() > 0); assertFalse(event.versionKey().isBlank());
        }
        Files.writeString(file, "failure");
        TextExtractor failing = new TextExtractor() {
            public boolean supports(Path path) { return true; }
            public void extract(Path path, PageConsumer sink, int limit) throws IOException { throw new IOException("test"); }
        };
        try (var core = NasquireCore.builder(temp.resolve("failure-data")).sources(() -> List.of(source))
                .limits(1024, 1024, 0).extractors(List.of(failing)).listener(events::add).build()) { core.scan(); }
        assertEquals(IndexEvent.Kind.FAILED, events.getLast().kind());
    }
    @Test void configuredVectorsBuildOnFirstScan() throws Exception {
        var root = Files.createDirectory(temp.resolve("source")); Files.writeString(root.resolve("x.txt"), "ocean");
        Providers.EmbeddingProvider provider = new Providers.EmbeddingProvider() {
            public String modelId() { return "test"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { return new float[] {1, 0}; }
        };
        try (var core = NasquireCore.builder(temp.resolve("data")).sources(() -> List.of(new Source("s", "Test", root, List.of(), List.of())))
                .limits(1024, 1024, 0).vectors(provider, null).build()) {
            core.scan(); assertEquals(1, core.search("ocean", AccessScope.sources(Set.of("s")), 10, "vector").size());
        }
    }
    @Test void customOpenerAndRemovedSourceEvents() throws Exception {
        var root = Files.createDirectory(temp.resolve("source"));
        Files.writeString(root.resolve("x.txt"), "ocean");
        var definitions = new java.util.concurrent.atomic.AtomicReference<>(
                List.of(new Source("s", "Test", root, List.of(), List.of())));
        var events = new ArrayList<IndexEvent>();
        var opens = new java.util.concurrent.atomic.AtomicInteger();
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        OriginalOpener opener = path -> {
            opens.incrementAndGet();
            var pinned = OriginalOpener.secure().open(path);
            return new OriginalOpener.Opened() {
                public Path path() { return pinned.path(); }
                public void close() throws IOException { pinned.close(); closes.incrementAndGet(); }
            };
        };
        try (var core = NasquireCore.builder(temp.resolve("data")).sources(definitions::get)
                .limits(1024, 1024, 0).originalOpener(opener).listener(events::add).build()) {
            core.scan(); definitions.set(List.of()); core.scan();
            assertTrue(core.search("ocean", AccessScope.sources(Set.of("s")), 10, "keyword").isEmpty());
        }
        assertEquals(1, opens.get()); assertEquals(1, closes.get());
        assertEquals(List.of(IndexEvent.Kind.ADDED, IndexEvent.Kind.DELETED), events.stream().map(IndexEvent::kind).toList());
        assertEquals(events.getFirst().versionKey(), events.getLast().versionKey());
    }
    @Test void listenerExceptionsDoNotFailIndexing() throws Exception {
        var root = Files.createDirectory(temp.resolve("source")); Files.writeString(root.resolve("x.txt"), "ocean");
        try (var core = NasquireCore.builder(temp.resolve("data")).sources(() -> List.of(new Source("s", "Test", root, List.of(), List.of())))
                .limits(1024, 1024, 0).listener(e -> { throw new IllegalStateException(); }).build()) {
            core.scan(); assertEquals(1, core.search("ocean", AccessScope.sources(Set.of("s")), 10, "keyword").size());
        }
    }
}
