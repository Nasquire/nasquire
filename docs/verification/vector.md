# 2단계 벡터 검색 검증

검증일: 2026-10-24. Java 25, Gradle 9.8.1, Linux amd64. 자체 작성 문서만 사용했다. **기능 구현·검색 검증은 통과했지만 내장 모델의 512 MiB 목표는 미완료다.**

## 실행 결과

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew build -q` | 0 | core/app 테스트, React TypeScript/Vite, 실행 JAR 생성 |
| `python3 scripts/fetch-verification-models.py` | 0 | int8 모델·토크나이저 SHA-256 일치; 내장 실측 스크립트가 자동 실행 |
| `VERIFY_PORT=8121 VERIFY_EMBEDDING=builtin python3 scripts/verify-runtime.py` | 0 | 실제 ONNX·토크나이저 연결, 40문서 재색인, curl keyword/vector/hybrid·MCP hybrid, RSS 측정, 서버 종료·포트 닫힘 |
| `VERIFY_PORT=8123 VERIFY_EMBEDDING=remote python3 scripts/verify-runtime.py` | 0 | 가짜 HTTP 공급자로 같은 검색·재색인·RSS·서버 종료 흐름 통과 |
| `git diff --check` | 0 | 공백 오류 없음 |

자동 테스트는 core 9개, app 실제 HTTP 통합 테스트 1개다. app 테스트는 여러 인증·검색·MCP·관리 흐름을 한 테스트 안에서 검증한다.

- 원격 POST·Bearer·모델/배치 전달, 응답 순서 복원·정규화, HTTP 오류·차원 불일치·영벡터/NaN 거부, 배치 상한, 응답 본문 지연 타임아웃.
- 모델 변경·같은 모델의 차원 변경 시 재색인 필요, Lucene HNSW 2→3차원 재구축, 모델/차원 메타 영속성, 중단된 재색인의 incomplete 상태 복원.
- RRF 순위별 점수 합산, 동일 문서 다른 offset의 조각 유지, 쪽·경로 근거, 벡터와 BM25의 소스 범위 필터.
- 공급자 off 기본 키워드 검색·공급자 해제 후 키워드 검색 유지, 지원하지 않는 mode 거부, 재색인 전 vector/hybrid 거부·keyword 유지.
- REST/MCP mode 스키마·keyword/vector/hybrid 동작, 토큰 소스 제한, 관리자 세션/CSRF 없는 임베딩 설정 거부, API 키 비노출·암호문 저장·재로딩.
- 모델 해시 누락/불일치·없는 모델 거부, 데이터 폴더 밖/심볼릭 링크를 통한 원본 소스 다운로드 차단.
- 1단계 추출·파일 변경/삭제·읽기 범위·인증·토큰·감사 회귀 테스트 유지.

## 실제 모델·curl 확인

모델은 `Xenova/multilingual-e5-small` 고정 리비전의 `onnx/model_quantized.onnx`(118,308,185바이트), 토크나이저는 `tokenizer.json`(17,082,730바이트)을 사용했다. SHA-256·출처·라이선스는 [임베딩 설정](../embeddings.md)에 기록했다. 모델을 실제로 내려받았으며 가짜 서버를 내장 검증의 대체물로 사용하지 않았다. 가짜 HTTP 공급자는 원격 경로를 별도로 검증하는 데 사용했다. 유료 외부 API와 비밀키는 사용하지 않았다.

```text
PASS builtin provider: connection, 40-document reindex, curl keyword/vector/hybrid, MCP hybrid, path/page evidence
PASS 30 additional searches, token revocation, audit log
PASS logout invalidates administrator session
PEAK_APPLICATION_RSS_KIB=876256
PEAK_APPLICATION_RSS_MIB=855.7
Application and bootRun process group stopped; port closed.
```

원격 가짜 서버 실행은 `PEAK_APPLICATION_RSS_KIB=286212`, `PEAK_APPLICATION_RSS_MIB=279.5`였다. 해당 서버는 3차원 고정 벡터를 반환하므로 프로토콜·색인·권한·혼합 결과를 확인한 것이며 원격 모델의 검색 품질을 측정한 것은 아니다. 실제 내장 모델 검색의 관련성도 정량 평가하지 않았다.

## RSS와 미달한 목표

1단계와 같은 자체 작성 Markdown 20개(각 60문단), 2쪽 PDF 20개, 합계 40파일·PDF 40쪽이다. 임베딩 off 상태의 최초 색인·검색 후 공급자를 켜고 연결 시험 → 전체 재색인 → REST 세 mode와 MCP hybrid → 추가 검색 30회까지 수행했다.

JVM 설정은 `-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m`이다. Linux `/proc/<application-pid>/status`의 VmRSS를 50ms마다 수집했다. Gradle·Node·검증 Python·가짜 HTTP 서버는 JVM RSS에 포함하지 않는다. 네이티브 ONNX와 Rust 토크나이저 메모리는 JVM RSS에 포함되며 Java heap 상한만으로 제한되지 않는다.

| 구성 | 시작 중 최대 KiB | 색인·검색 중 최대 KiB | 최대 MiB |
| --- | ---: | ---: | ---: |
| 내장, 최초 설정 | 167048 | 881932 | 861.3 |
| 내장, ONNX 그래프 최적화 끄기·세션 먼저 로드 | 167576 | 881124 | 860.5 |
| 내장, 추가로 `MALLOC_ARENA_MAX=2`, `TOKENIZERS_PARALLELISM=false` | 166964 | 876256 | 855.7 |
| 원격, 가짜 HTTP 서버 | 166828 | 286212 | 279.5 |

단일 낮은 우선순위 색인 작업자·파일 하나씩·내장 배치 1·ONNX 스레드 각각 1·CPU arena/memory pattern 끄기를 적용했지만 **내장 모델은 512 MiB를 초과했다.** 기본 off 또는 원격 구성이 현재 lite 메모리 목표에 더 적합하다. 512 MiB compose에서 내장 모델을 활성화하지 않는다. 더 작은 다국어 모델이나 토크나이저/모델 로딩의 네이티브 메모리 개선 및 더 큰 컨테이너 제한에서의 검증이 남았다. 1 GiB 등의 다른 제한도 이번에 실증한 보장은 아니다.

실제 cgroup·파일 캐시·전체 컨테이너 사용량 검증이 아니다. 큰 파일·많은 파일·동시 요청의 최대 사용량도 보장하지 않는다.

## 실패·생략 이력

- 내장 512 MiB 목표 미달: 위 세 번의 유효한 실측에서 모두 초과했다. 숨기지 않고 남은 작업으로 유지한다.
- 튜닝 재실행의 포트 8117 사전 검사 종료 코드 1: 이전 연결의 포트 재사용 상태로 bind가 실패했다. 다른 빈 포트를 사용하고 사전 검사 소켓에 SO_REUSEADDR을 적용했다.
- 포트 8118 튜닝 시도 종료 코드 1: 빌드와 bootRun을 같은 클래스 출력 디렉터리에서 동시에 수행하여 `SearchIndex$EmbeddingStatus`를 찾지 못했다. 동시에 실행하던 빌드도 Gradle daemon 소실로 종료 코드 1이었다. 정확한 daemon 소실 원인은 확인하지 못했다. 클래스 출력 공유 충돌을 피하도록 이후 빌드와 런타임 검증을 순서대로 실행했고 모두 재검증했다. 실패한 실행의 수치는 실측에 사용하지 않았다.
- Docker 명령이 없어 이미지 빌드·512 MiB 실제 cgroup·비루트 파일 권한·다중 아키텍처 컨테이너 실행은 생략했다.
- ONNX Runtime 1.31.0과 DJL Tokenizers 0.39.0 JAR의 Linux amd64·arm64 네이티브 파일 포함을 확인했으나 arm64 하드웨어에서 실행하지 못했다. 상위 ONNX Java 문서의 플랫폼 표만으로 arm64 실행을 보장하지 않는다.
- 실제 브라우저 자동화 도구가 없어 한/영·반응형·접근성 시각 검증은 생략했다. TypeScript/Vite 빌드·정적 자산 HTTP 제공은 검증했다.
- ONNX 환경 생성 때 telemetry 식별자 저장 실패 경고가 관찰됐다. 생성 직후 `setTelemetry(false)`를 호출한다. 추론을 위한 외부 API 호출은 없지만 실제 네트워크 패킷 캡처 검증은 하지 않았다.
- 모델의 첫 사용 자동 다운로드 구현은 HTTPS·SHA-256 검사를 제공한다. 실제 모델 취득은 Python 검증 스크립트로 수행한 뒤 Java 공급자가 기존 파일 해시를 재확인했다. Java의 HTTPS 다운로드 경로 자체를 실제 모델로 종단 검증하지는 않았다.

## 아티팩트·재현

```sh
./gradlew build -q
VERIFY_PORT=8101 VERIFY_EMBEDDING=builtin python3 scripts/verify-runtime.py
VERIFY_PORT=8102 VERIFY_EMBEDDING=remote python3 scripts/verify-runtime.py
```

내장 검증은 고정 모델 파일을 SHA-256 확인 후 다운로드하므로 최초 실행은 인터넷이 필요하다. 원격 검증은 가짜 서버용 포트 8108도 사용한다. 빌드와 런타임 검증은 동시에 실행하지 않는다. 서버는 finally에서 종료하고 사용한 애플리케이션 포트가 닫혔는지 확인한다.

다음은 git에서 무시되는 로컬 상세 자료다. 원시 실행 로그·JUnit XML에는 실행 환경 경로가 포함될 수 있으므로 공개 커밋에 넣지 않는다.

- `build/verification/measurement-builtin.json`, `measurement-remote.json`: 환경 식별값 없는 최종 측정.
- `build/verification/measurement-builtin-initial.json`, `measurement-builtin-tuned.json`: 초기·튜닝 비교.
- `build/verification/smoke-builtin.txt`, `smoke-remote.txt`: 인증값 없는 curl 검증.
- `build/verification/bootrun-builtin.log`, `bootrun-remote.log`: 공개하지 않는 원시 실행 로그.
- `build/verification/models/`: 외부 int8 모델·토크나이저, 이미지·저장소에 포함하지 않음.
- `build/verification/djl-cache/`: 검증용 토크나이저 네이티브 라이브러리.
- `core/build/reports/tests/test/index.html`, `app/build/reports/tests/test/index.html`: 자동 테스트 보고서.
- `app/build/libs/app-0.1.0.jar`: 관리 화면을 포함한 실행 JAR.
