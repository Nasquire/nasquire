# lite 색인 메모리 측정 (2026-10-26, 기준 4b38f8c)

Synology x86 NAS 실기에서 lite 컨테이너(512 MiB)의 표시 메모리가 대기 약 175 MB에서 큰 PDF 18개 색인 중 약 420 MB로 증가한 뒤 유지됐다. 표시 단위·파일 캐시 포함 여부는 불명이다. 아래 수치는 별도 Linux amd64/JDK 25.0.4.1 환경에서 얻은 **MiB**이며 실기 수치와 직접 비교하지 않는다.

## 방법과 범위

`scripts/measure-memory.py`는 추가 패키지 없이 PDF를 생성하고 lite bootJar를 실행한다. 각 PDF는 360쪽이며 쪽마다 독립 RGB 이미지(128×128)와 약 2천 자의 텍스트를 포함한다. PDF 18개(합계 6,480쪽, 원본 약 309 MiB)와 기존 Office/HWP 8개를 단일 작업자로 색인한다. 모델·OCR은 off다. 26개 모두 색인, 오류·상한 초과 0을 성공 조건으로 검사한다.

- `/proc/<pid>/status`: 0.1초마다 RSS·RssAnon·RssFile·swap 수집.
- JDK `jcmd VM.native_memory summary`: 색인 전·도중 RSS 증가 시·완료 직후·20초 유휴 후 NMT committed/reserved 수집. 도중 값은 샘플 시점이며 정확한 순간 최대치는 아니다.
- 프로세스 소속 cgroup v2의 `memory.current`, `memory.stat` anon/file, `memory.max`, `memory.events`를 함께 수집한다.
- 인증은 루프백에서 임의 비밀번호·쿠키·CSRF를 메모리에만 보유한다. 결과·로그에 인증값을 쓰지 않고 종료 시 자식 JVM을 회수한다. 포트를 먼저 점검하고 full JAR는 거부한다.
- `--office-stress`는 각 약 7.97 MiB(8 MiB 원본 상한 직전)의 정상 DOCX/XLSX 2개를 추가한다. 각각 약 153만 자·1,000문단/행이며 ZIP은 비압축으로 기존 압축률·해제량·노드·근거 단위 상한 안에 둔다.

이 실행 환경은 **공유 cgroup, memory.max=max**다. cgroup 생성 권한과 Docker 실행기가 없어 단독 512 MiB 제한 시험은 하지 못했다. cgroup의 OOM 카운터 증가는 없었지만 이것으로 컨테이너 OOM 안전성을 증명할 수 없다. NMT 자체 비용(대기 약 6 MiB)·JIT·할당기 변동이 있어 수치는 단일 실행 예시이며 NAS 성능 보장값이 아니다.

## 동일 문서 비교

| lite 구성 | 색인 전 RSS | 최대 RSS | 완료 직후 RSS | 20초 유휴 RSS | 유휴 anon |
| --- | ---: | ---: | ---: | ---: | ---: |
| 기준: 힙 256 MiB, 기존 SerialGC | 264.9 | 426.4 | 426.4 | 426.4 | 397.9 |
| 컴팩트 헤더만 | 234.9 | 406.5 | 406.5 | 406.5 | 378.1 |
| 헤더 + 여유 힙 10/20% + 즉시 수축, 힙 256 | 246.3 | 400.0 | 400.0 | 400.0 | 371.7 |
| 위 옵션 + 힙 192, 자동 유휴 GC 없음 | 229.6 | 390.2 | 390.2 | 390.2 | 361.8 |
| 채택: 위 옵션 + 실제 추출 후 자동 유휴 GC | 249.3 | 410.9 | 410.8 | 362.4 | 334.0 |

모두 26/26 색인 성공, 실행 종료 0. 기준 대비 채택본 대기 RSS **64.0 MiB(15.0%)**, anon **63.9 MiB** 감소. 최대 RSS는 **15.5 MiB** 감소했으며 512에서 RSS만 뺀 산술 여유는 85.6→101.1 MiB다. 이는 cgroup 여유와 다르다. 후보별 최대 RSS 차이에 JIT·할당기 변동도 포함되므로 각 옵션의 효과를 단순 합산하지 않는다.

외부 `jcmd GC.run`을 한 번 요청하면 기준/헤더만 구성은 각각 426.4/406.5 MiB로 유지됐지만, 반환 비율 조정 구성은 400.0→352.7 MiB, 192 MiB 후보는 390.2→341.6 MiB가 됐다. 따라서 GC 요청만 추가하지 않고 여유 힙 정책·한 번에 수축을 함께 적용했다. 배포 구성은 `jcmd` 없이 앱 자체가 한 번 요청한다.

## 상한 근처 보충 시험

| 채택 옵션·추가 부하 | 색인 | 오류/OOM | 최대 RSS MiB | 20초 유휴 RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| 360쪽 PDF×18 + 기존 Office/HWP 8 + 큰 DOCX/XLSX 2 | 28/28 | 0/0 | 412.3 | 346.7 |
| 600쪽 PDF×18 + 같은 Office 부하 | 28/28 | 0/0 | 414.8 | 336.8 |
| 600쪽·Office 부하, 힙 160 MiB 후보 | 28/28 | 0/0 | 395.7 | 351.4 |

모두 측정 명령 종료 0. 600쪽 PDF는 각각 약 28.53 MiB로 기본 원본 상한 32 MiB 안이며 전체 원본은 약 529.6 MiB다. 160 MiB도 이 부하는 통과했지만 유휴 메모리는 개선되지 않았고 문서 객체를 위한 힙 여유가 32 MiB 줄어, 기본값은 192 MiB로 정했다. 어떤 힙 크기도 임의의 모든 허용 문서를 보장하지 않는다.

## NMT: 유휴 committed

| 항목 | 기준 MiB | 채택 MiB |
| --- | ---: | ---: |
| Java Heap | 148.2 | 82.8 |
| Metaspace | 68.5 | 62.7 |
| Class | 11.7 | 17.6 |
| Code | 28.0 | 26.4 |
| Thread | 2.5 | 2.5 |
| NMT Other | 26.7 | 26.7 |
| 나머지(Symbol·NMT·공유 클래스·GC 등) | 37.8 | 37.9 |
| 전체 | 323.4 | 256.6 |

주요 반환은 Java Heap의 약 65.4 MiB다. reserved 주소 공간은 물리 사용량이 아니며, NMT committed 합계도 RSS와 같지 않다. 추적 밖의 네이티브 메모리·라이브러리 매핑·할당기가 보유한 페이지 등이 차이에 영향을 준다. 큰 PDF 처리 후 살아 있는 모든 객체가 누수라는 근거는 없으며, 직후 힙/할당기 고수위와 회수 가능한 파일 캐시를 구분해야 한다.

## cgroup과 파일 캐시

| 공유 그룹의 유휴 값 | 기준 MiB | 채택 MiB |
| --- | ---: | ---: |
| memory.current | 2603.3 | 2651.2 |
| memory.stat anon | 1084.7 | 1116.8 |
| memory.stat file | 1447.7 | 1462.1 |

다른 프로세스와 생성 문서·JAR·색인의 캐시가 포함되므로 이 표의 증감을 앱의 효과로 해석하지 않는다. 원시 시계열에는 색인 전·중·후 값이 모두 있다. `memory.current`는 anon/file 이외 커널·소켓 등도 포함한다. RSS의 RssFile은 앱에 매핑된 파일 페이지이고 cgroup file은 그룹 전체 파일 캐시이므로 서로 대체할 수 없다.

일반 디스크의 page cache는 메모리 압력 시 회수 가능하다. 다만 더러운 페이지는 쓰기 완료가 필요하며 tmpfs/shmem은 일반 디스크 캐시처럼 단순 폐기되지 않는다. Container Manager 그래프는 캐시를 포함할 수 있다. 운영 시 앱 RSS/RssAnon, cgroup anon/file, memory.events를 함께 확인하고 캐시를 전부 누수나 즉시 사용 가능한 여유로 간주하지 않는다. 시스템 캐시를 강제로 비우는 작업은 하지 않았다.

## 채택과 제외

- JDK 25 `-XX:+UseCompactObjectHeaders`: 동일 부하에서 대기 약 20 MiB 감소해 lite/full 기본값에 채택.
- `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:-ShrinkHeapInSteps`: SerialGC가 한 번의 유휴 GC에서 힙을 반환하도록 채택. `-Xms64m` 유지.
- 실제 추출이 있었던 검사 뒤 단일 색인 작업자 큐에서 5초 뒤 GC 요청. 연속 검사에는 병합하고 최소 5분 간격. 변경 없는 검사에는 새 요청 없음. 파일 실패 뒤에도 임시 객체 반환 대상이다. 종료 시 취소하며 G1 등 다른 GC에는 요청하지 않는다. 명시적 해제는 `-Dnasquire.indexing.idle-gc=false`, JVM의 `DisableExplicitGC`도 존중한다. 잠시 전체 GC 정지가 발생할 수 있다.
- lite `-Xmx192m` 채택. full/ML은 모델·OCR의 별도 부하 때문에 256 MiB 유지. 낮춘 값이 모든 허용 문서의 성공을 보장하지는 않는다.
- PDFBox는 이미 쪽 단위 텍스트·디스크 stream cache, Lucene은 RAM 8 MiB·주기 끝 commit/refresh다. 강제 병합·파일마다 commit·추출 결과 게시 규칙 변경은 하지 않았다. Tomcat 16개/최소 2개 스레드·H2 연결 2개도 이미 제한돼 있다. 이들 추가 축소는 독립적인 효과·기능 유지 근거가 없어 채택하지 않았다. 추출·보안 상한은 변경하지 않았다.

## 재현

JDK 25의 `java`·`jcmd`, Python 3, 빌드 도구가 필요하다. 빌드·다른 검증과 순차 실행한다. 기본 JAVA_HOME이 다르면 지정한다. 인증값을 명령 인자나 환경변수에 넣지 않는다.

```sh
./gradlew :app:bootJar :core:generateSamples -q
VERIFY_PORT=8104 python3 scripts/measure-memory.py --name improved \
  --flags='-XX:+UseCompactObjectHeaders -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10 -XX:-ShrinkHeapInSteps -Xmx192m'
VERIFY_PORT=8104 python3 scripts/measure-memory.py --name improved-office-fixed --office-stress \
  --flags='-XX:+UseCompactObjectHeaders -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10 -XX:-ShrinkHeapInSteps -Xmx192m'
```

기준 커밋의 lite JAR에서 `--name lite-baseline --probe-gc`로 기준을 측정했다. 현행 코드에서 자동 GC를 끄고 후보를 측정하려면 `--flags='-Dnasquire.indexing.idle-gc=false ...'`로 지정한다. 결과는 `build/verification/memory/<name>/result.json`(JAR SHA-256 포함), `samples.json`, `nmt-*.txt`, `gc.log`, `server.log`다. 원본은 같은 부모 폴더의 `documents-*`에 생성하며 인증 쿠키 파일은 만들지 않는다.

## 실패·한계

초기 빌드는 기존 생성물 소유권 때문에 nodeSetup 삭제, 이어 bootJar MANIFEST 읽기가 실패했다(종료 1). 생성물을 무시 대상 build 아래 보존·재생성해 해결했다. 진단용 Gradle 초기화 스크립트 지정 오류도 종료 1로 수정했다. 처음 남아 있던 JAR는 full이어서 `baseline`/`compact` 예비 측정은 채택하지 않고 lite로 다시 측정했으며 스크립트에 full 거부 검사를 추가했다.

동일 포트 사용으로 HTTP 409와 bind 실패(종료 1)가 있었고 순차 재실행으로 해결, 시작 전 포트 점검을 추가했다. 초기 추가 Office 생성물은 padding.bin의 OPC Content-Type 선언 누락으로 2개가 IOException이었다(192/160 MiB 후보 모두 종료 1). 진단에서 InvalidFormatException을 확인하고 생성기를 수정했다. 이 실패는 OOM 근거로 사용하지 않는다.

단독 512 MiB cgroup, 개선 후 Synology 실기, 실제 큰 외부 문서, full 모델/OCR 동시 부하는 미검증이다. 공유 cgroup OOM 0이나 lite 성공을 full·모든 허용 문서 보장으로 확대하지 않는다.

## 최종 검증

| 명령·검사 | 종료 코드·결과 |
| --- | --- |
| `./gradlew build -q` | 0; lite app 65건 중 full 전용 2건 생략, 실패/오류 0 |
| `./gradlew -Pfull build -q` | 0; core 31·app 65·full 21건, 실패/오류/생략 0 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0; 48문서·16 MiB 업로드/공유·인증/MCP 통과, 최대 RSS 266.7 MiB, 서버/bootRun 종료·포트 반환 |
| Compose YAML·Docker JVM 옵션 비교 및 JDK 25 유효성 | 0; lite 192/full·ML 256 MiB, 헤더·반환 옵션 일치 |
| Python 문법·`--help`·`git diff --check` | 각각 0 |
| 공개 파일 전체 내부 식별자/장비명 `rg` | 최종 1(무일치); 초기 기존 문서의 모델명 1곳을 일반 표현으로 수정 |

채택본의 각 큰 부하 GC 로그에서 `System.gc()` 전체 GC는 1회씩이었다. 생략된 단독 cgroup·실기·full 부하는 위 한계를 따른다. 최종 로그·종료 코드 요약은 `build/verification/memory/summary.json`, 빌드 로그 `build-lite.log`/`build-full.log`, `runtime.log`, 초기 진단 `runlogs/`에 남겼다. 초기 소유권·생성기·포트 오류를 최종 성공으로 덮어 해석하지 않는다. 커밋·이미지 빌드·배포는 수행하지 않았다.
