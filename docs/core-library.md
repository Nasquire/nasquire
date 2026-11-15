# Core 라이브러리 / Core library

Nasquire core는 Spring 없이 Java 25 앱에서 문서를 읽기 전용으로 색인한다.
Nasquire core indexes read-only documents in Java 25 applications without Spring.

## 개인용/내부용(open) 빠른 시작 / Personal/internal open quick start

기본 정책은 `OpenAccessPolicy`다. 사용자·그룹·규칙 없이 등록된 모든 소스를 검색한다.
Default `OpenAccessPolicy` searches all registered sources without users, groups or rules.
Prepare a document containing “ocean” in `/sources/documents`; keep data outside sources.

```java
var source = new Source("docs", "Documents", Path.of("/sources/documents"),
        List.of("**/*.txt"), List.of());
try (var core = NasquireCore.builder(Path.of("/data/search"))
        .sources(() -> List.of(source)).limits(8 * 1024 * 1024, 1_000_000, 0).build()) {
    core.scan();
    var hits = core.search("ocean", 10);
    var chunks = core.document("docs:team/guide.txt");
}
```

`com.bluesounds.nasquire.core.*`, `java.nio.file.Path`, `java.util.*`를 import한다.
Import those packages. `AccessPolicy.open()` explicitly selects open mode if desired.

## 그룹 권한(rule) / Group access rules

```java
var policy = new RuleAccessPolicy(List.of(
    new AccessRule("docs", "team", AccessRule.Subject.group("editors"), AccessRule.Effect.ALLOW),
    new AccessRule("docs", "team/private", AccessRule.Subject.everyone(), AccessRule.Effect.DENY),
    new AccessRule("docs", "team/private/own", AccessRule.Subject.user("alice"), AccessRule.Effect.ALLOW)
));
var principal = new Principal("alice", Set.of("editors"));
// 빌더에 .accessPolicy(policy) 또는 실행 중 교체 / Configure on builder or replace at runtime:
core.setAccessPolicy(policy);
var hits = core.search("ocean", principal, 10, "keyword");
var chunks = core.document("docs:team/private/own/guide.txt", principal);
```

`user:<id>`, `group:<id>`, `everyone` 중 해당 주체의 규칙만 계산한다.
가장 긴 접두사가 이기며 같은 길이에서는 DENY 우선, 하위 경로는 상속하고 규칙이 없으면 거부한다.
위 예제에서 editors는 team을 읽고 private은 거부되지만 alice는 private/own을 다시 읽는다.
빈 접두사 `""`는 소스 전체다. `a/b`는 `a/bc`에 적용되지 않는다.
Only matching users, current groups and everyone participate. The longest prefix wins; DENY wins ties.
Descendants inherit; no matching rule denies access. The example grants editors the team folder,
blocks private, and reopens private/own for alice. Empty prefixes cover the source; matching uses directory boundaries.

정책 규칙은 불변 복사이며 최대 4096개, 컴파일한 AccessScope 접두사는 전체 소스 합계 최대 1024개다.
초과 시 `IllegalArgumentException`이다. Principal은 NFC·앞뒤 공백 정규화, ID 1~256 UTF-16 단위,
그룹 최대 256개이며 빈 값·제어 문자·포맷 문자를 거부한다. 인증과 최신 멤버십 공급은 호출 앱 책임이며
core는 DB나 그룹 저장소를 갖지 않는다. 멤버십 변경은 다음 요청에 새 Principal을 전달한다.
`setAccessPolicy`는 volatile로 정책을 원자 교체하며 진행 중인 요청은 취득한 정책 스냅샷을 사용한다.
open→rule 전환·규칙 변경은 색인이나 데이터를 바꾸지 않고 다음 요청부터 적용된다.
Policies copy up to 4096 rules; compiled scopes allow at most 1024 prefixes across sources, otherwise
`IllegalArgumentException`. IDs are NFC-normalized and stripped, 1–256 UTF-16 units, with at most 256 groups;
blank IDs, control and format characters are rejected. The caller authenticates and supplies current membership;
core has no database or group store. Pass a fresh Principal after membership changes.
Policy replacement is atomic; in-flight requests use their captured policy. Switching open to rule or changing
rules takes effect on the next request without reindexing or data migration.

Principal 없는 간편 API는 `Principal.owner()`를 사용하며 현재 정책을 그대로 적용한다.
AccessScope 직접 전달 API는 호출 앱의 자체 정책용이며 설정한 정책을 추가로 적용하지 않는다.
The convenience APIs use `Principal.owner()` with the current policy. Explicit AccessScope APIs use the
caller's own policy and do not additionally intersect the configured policy.

## 의존성 / Dependency

Maven 좌표 / Coordinates: `com.bluesounds.nasquire:nasquire-core:0.1.0`.
공개 Maven Central 게시를 의미하지 않는다. 직접 빌드하거나 조직에서 지정한 저장소에 게시한다.
These coordinates do not imply publication to Maven Central; build locally or publish to your chosen repository.

```groovy
// build.gradle
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation 'com.bluesounds.nasquire:nasquire-core:0.1.0' }
```

```xml
<dependency>
  <groupId>com.bluesounds.nasquire</groupId>
  <artifactId>nasquire-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Composite build는 artifact 이름과 프로젝트 이름이 달라 명시적으로 치환한다.
For a composite build, explicitly substitute the artifact with the core project:

```groovy
// settings.gradle of the consuming application
includeBuild('../nasquire') {
    dependencySubstitution {
        substitute module('com.bluesounds.nasquire:nasquire-core') using project(':core')
    }
}
```

`./gradlew :core:publishToMavenLocal -q`는 일반 로컬 Maven 저장소에 게시한다.
홈 대신 무시되는 빌드 폴더에 같은 작업을 검증하려면
`./gradlew :core:publishToMavenLocal -Dmaven.repo.local="$PWD/build/maven-local" -q`를 사용한다.
Gradle Maven Local publisher가 이 시스템 속성을 해석한다.
Use the system property above to keep local publication inside the ignored build directory.
`./gradlew :core:publishCorePublicationToBuildRepoRepository -q`도 `build/repo`에 게시한다.
Sources/Javadoc JAR과 POM/Gradle metadata를 포함하고 test fixtures는 게시하지 않는다.
Publication includes sources, Javadoc, POM and Gradle metadata, excluding test fixtures.

원격 저장소는 `NASQUIRE_MAVEN_URL` Gradle 속성 또는 환경변수가 있을 때만 등록한다.
사용자와 토큰도 `NASQUIRE_MAVEN_USER`, `NASQUIRE_MAVEN_TOKEN` 속성/환경변수로만 받는다.
주소·인증값은 코드/설정 파일에 저장하지 않는다. 원격 게시는 운영자의 별도 작업이다.
A remote repository is registered only when `NASQUIRE_MAVEN_URL` is supplied as a Gradle property
or environment variable. Supply user/token through `NASQUIRE_MAVEN_USER`/`NASQUIRE_MAVEN_TOKEN`.
Never store repository endpoints or credentials in this repository.

## 최소 예제 / Minimal example

아래 블록은 `CoreLibraryExampleTest`의 실행 예제와 같다. `documents`는 문서 폴더,
`data`는 소스와 겹치지 않는 색인 저장 폴더다. 소스 파일 작성은 호출 앱이 예제를 준비하는 단계이며 core는 쓰지 않는다.
The executable equivalent is `CoreLibraryExampleTest`. Prepare a `team/guide.txt` document containing
“ocean guide” in a source directory. Keep the data directory outside all sources.

```java
import com.bluesounds.nasquire.core.*;
import java.nio.file.Path;
import java.util.*;

Path documents = Path.of("/sources/documents");
var events = new ArrayList<IndexEvent>();
var source = new Source("docs", "Documents", documents,
        List.of("**/*.txt"), List.of());
try (var core = NasquireCore.builder(Path.of("/data/search"))
        .sources(() -> List.of(source))
        .limits(8 * 1024 * 1024, 1_000_000, 0)
        .listener(events::add).build()) {
    core.scan();
    var scope = new AccessScope(Set.of("docs"),
            Map.of("docs", List.of("team")));
    var hits = core.search("ocean", scope, 10, "keyword");
    // events: sourceId + relativePath로 호출 앱의 파일 ID를 조회한다.
    // Resolve the caller's file ID using event.sourceId()/relativePath().
}
```

`limits`의 마지막 값은 안정화 대기 ms다. 예제는 즉시 색인하며 기본값은 1000ms다.
기본 추출기는 PDF·텍스트·Office/HWP다. `extractors`는 목록 교체, `originalOpener`는 원본 열기,
`vectors`는 임베딩/벡터 저장소 공급자를 설정한다. 소스 제공자는 매 검사 시 최신 목록을 반환한다.
The final limit is the stability delay in milliseconds (default 1000; zero for the example).
Use `extractors`, `originalOpener`, and `vectors` to replace extraction, opening and vector providers.
The source supplier is evaluated on every scan. Low-level `SearchIndex`/`IndexingService` remain public extension APIs;
use `indexFolder` to choose a separate index location.

## 권한과 색인 형식 / Access and index format

`AccessScope`는 불변 복사다. 허용 소스가 없으면 결과가 없다. 소스별 접두사 목록이 없거나 비면
소스 전체를 허용한다. 접두사 `a/b`는 `a/b`와 `a/b/...`만 포함하며 `a/bc`는 제외한다.
절대 경로·드라이브 경로·역슬래시·빈 요소·`.`·`..`·제어문자·NFC 미정규화 입력은 거부한다.
권한은 정책으로 조회 시점에 계산하거나 호출 앱이 AccessScope로 요청마다 전달한다.
AccessScope의 decisions는 접두사별 ALLOW/DENY를 보존하며 가장 긴 접두사·DENY 우선으로 판정한다.
기존 2인자 생성자와 sources 팩토리는 유지한다. decisions에 지정한 소스는 해당 결정 목록으로 판정한다.
AccessScope copies its inputs. Empty sources deny all; an absent or empty prefix list grants the whole source.
Prefixes compare at directory boundaries. Noncanonical, absolute, traversal and non-NFC inputs are rejected.
The caller authenticates users and supplies current membership or an explicit scope on each request.
AccessScope decisions preserve longest-prefix ALLOW/DENY decisions with DENY winning ties.
The existing two-argument constructor and sources factory remain available; per-source decisions override legacy prefixes.

BM25·HNSW·외부 조각 조회는 Lucene 필터를 후보 선정 전에 적용하며 RRF 전과 최종 결과를 재검사한다.
Qdrant도 소스/경로/세대를 payload로 필터링하고 응답을 재검사한다. 리랭커에는 이 검색 결과만 전달한다.
Nasquire 앱은 기존 소스 집합을 접두사 없는 AccessScope로 바꿔 동작을 유지한다.
Lucene filters precede BM25/HNSW candidate selection and external chunk retrieval; results are checked before RRF
and on return. Qdrant filters source/path/epoch and validates responses. Only scoped hits may be sent to rerankers.
The Nasquire app preserves source-only permissions with empty prefix lists.

Lucene는 기존 색인된 `key=sourceId:relativePath`를 사용하므로 새 경로 필드가 필요 없다.
Qdrant에는 `access` 배열(파일 key와 각 상위 디렉터리 key)을 추가했다. 커밋 메타 `access.format=2`를
기록하며 이전 Qdrant 색인은 다음 검사에서 자동 전체 재색인한다. 재색인 전 벡터 검색은 차단한다.
기존 Lucene 키워드/HNSW 색인은 그대로 읽는다. 사용자 VectorStore는 새 AccessScope 오버로드를 구현해야
경로 제한 검색을 지원한다. 기본 구현은 경로 제한 요청을 거부한다.
Lucene reuses its indexed key field. Qdrant adds an `access` array of file and ancestor keys; format 2 metadata
triggers a full rebuild of older Qdrant indexes and blocks vector search until rebuilt.
Existing Lucene indexes remain readable. Custom VectorStore implementations must implement scoped search;
the default rejects path-restricted requests.

## 수명·이벤트·보안 / Lifecycle, events and security

`start(intervalMillis)`는 단일 저우선순위 작업자를 사용하며 `scan()`도 직렬화된다.
원본은 별도의 읽기 전용 폴더/마운트로 준비하고 소스와 데이터 위치를 중첩하지 않는다.
기본 opener는 SecureFiles의 NOFOLLOW·일반 파일 확인·고정 fd 경로를 유지한다.
Linux 지원 환경과 `--enable-native-access=ALL-UNNAMED`가 필요하다.
대체 opener는 같은 안전성과 파일 메타데이터를 보장해야 한다. core가 임의 URL을 열지는 않는다.
The periodic scanner uses one low-priority worker; synchronous scans are serialized as well.
Mount originals read-only and keep source/data folders separate. The default opener pins regular files with NOFOLLOW.
It requires supported Linux and native access. Custom openers must preserve this security and metadata contract.

Lucene RAM 8 MiB·파일 크기·문자 수·Office 압축/노드 상한·PDF 디스크 scratch를 유지한다.
내장 모델은 별도 full 구현이 필요하고 512 MiB를 보장하지 않는다. 네트워크 파일 I/O 자체의 무한 대기는
core가 중단을 보장하지 못한다. `close()`는 작업자를 종료한 뒤 SearchIndex를 닫는다.
SearchIndex는 활성 요청을 기다리고 소유한 공급자/벡터 저장소와 Lucene 자원을 종료한다.
종료 중 새 요청을 시작하지 않고 호출 앱의 검색 요청을 먼저 정리한다.
Lucene uses an 8 MiB RAM buffer and bounded extraction; PDFs use disk scratch. Built-in models require full
and do not guarantee 512 MiB operation. Stalled filesystem I/O cannot be guaranteed interruptible.
Stop incoming requests first; close stops the worker, then closes SearchIndex, which waits for active requests
and closes its owned providers/vector store and Lucene resources. Do not share owned providers
with another core instance or close them independently.

이벤트는 파일 추가·갱신·삭제·추출/색인 실패를 검사 스레드에서 동기 호출한다.
소스 ID·정규화 상대 경로·크기·mtime·`size:mtime` 버전 키를 제공한다. 내용 해시는 아니므로 같은
크기/mtime 변경을 식별하지 못한다. 삭제는 이전 메타데이터, 실패는 관찰한 메타데이터다.
리스너는 짧게 처리하거나 자체 큐로 넘긴다. RuntimeException은 색인을 중단하지 않는다.
이벤트는 DB와 원자적이지 않고 refresh/commit 전 발생할 수 있다. 재시작·재색인은 추가 이벤트를
다시 만들 수 있어 호출 앱은 멱등 upsert와 상태 재조정을 구현한다.
Events are synchronous on the scanning thread, carry source/path/size/mtime and a `size:mtime` version key,
and are not content hashes. Deletions carry previous metadata. Keep listeners brief; RuntimeExceptions are isolated.
Events are not transactional with the caller's DB or index refresh. Rebuild/restart can repeat additions;
consumers need idempotent upserts and reconciliation.

## API·버전·라이선스 / API, version and license

원격 연결 계층 `com.bluesounds.nasquire.core.remote`(`RemoteServer`, `RemotePool`, `RemoteHttp`, `RemoteCheck`, `RemoteException`, `RemoteTrace`)도 공개 API다. `RemoteEmbeddingProvider`·`RemoteRerankerProvider`에 `RemotePool`을 넘기면 round_robin·페일오버(연결 실패·시간 초과·5xx·429만)·서킷 브레이커를 쓴다. Spring 의존이 없다.
The remote layer `com.bluesounds.nasquire.core.remote` is public as well: pass a `RemotePool` to `RemoteEmbeddingProvider` or `RemoteRerankerProvider` for round-robin, failover (connection errors, timeouts, 5xx, 429 only) and a circuit breaker, without Spring.

표·그림 의심 쪽 Docling 변환은 `IndexingService.docling(new DoclingPages(연결된 RemoteDoclingClient 공급자, 기준값 공급자, 재시도 상한, 첫 재시도 대기))`로 켠다. 공급자가 비어 있으면 판단하지 않는다. 판단 객체·신호는 `com.bluesounds.nasquire.core.layout`(`LayoutCheck`, `PageSignals`, `PdfLayout`, `RasterGrid`)이고 `Source`의 마지막 값 `alwaysDocling`(기존 생성자는 false)으로 소스별 전체 변환을 고른다.
Enable table/figure page conversion with `IndexingService.docling(new DoclingPages(...))`; an empty client supplier disables detection entirely. Detection types live in `com.bluesounds.nasquire.core.layout`, and `Source.alwaysDocling` (false in the existing constructors) sends every page of a source.

공개 API는 `com.bluesounds.nasquire.core`의 public 타입이며 `.internal`과 package-private 구현은
호환 대상이 아니다. SemVer를 따르며 0.x 동안 마이너 버전에서 호환이 깨질 수 있다.
현재 JPMS descriptor는 제공하지 않는다. POI/HWP 및 선택 full ServiceLoader/네이티브 구현의 기존
classpath 구성을 유지하고 전이 자동 모듈 이름에 공개 모듈 계약을 결합하지 않기 위해서다.
패키지/Javadoc으로 경계를 명시하며 JVM exports 강제는 제공하지 않는다.
Public types in `com.bluesounds.nasquire.core` are the compatibility surface; `.internal` and package-private
implementations are excluded. SemVer applies; minor releases may break compatibility during 0.x.
JPMS is deferred to preserve the existing POI/HWP, optional full ServiceLoader and native classpath setup
without making transitive automatic-module names part of the contract. Boundaries are documented, not enforced by exports.

[AGPL-3.0](../LICENSE) + [추가 허가 / additional permissions](../LICENSE-EXCEPTIONS.md).
별도 앱에 포함해도 라이선스 의무를 검토해야 한다. / Review these terms when embedding in another application.
