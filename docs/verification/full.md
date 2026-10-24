# 4단계 full 모듈: 부분 구현·검증

검증일: 2026-10-24, 기준 커밋 `a4bb16b`(1차)·`0d2ff95`(나머지 구현). **4단계 전체는 미완료**다. 1차 범위는 선택 full 모듈, 원격 리랭커, Tesseract OCR 호출·추출 경로다. Qdrant·ML 전용 모드·로컬 ONNX 리랭커와 lite RSS 원인 분석은 문서 끝의 [나머지 구현](#나머지-구현-기준-0d2ff95) 절에 있다. PaddleOCR 구현과 실제 OCR 비교는 남아 있다.

## 빌드와 기본 off

- `./gradlew build -q`: 기본 lite 앱. core/app에서 ONNX Runtime·DJL·full 구현을 제외한다. `:full` 라이브러리의 독립 빌드·테스트는 전체 build에 포함되지만 lite 앱 런타임에는 들어가지 않는다. `:app:verifyLiteClasspath`가 유입을 차단한다.
- `./gradlew -Pfull build -q`, `./gradlew -Pfull :app:bootRun`: full 구현을 앱 런타임에 추가한다. 기존 내장 E5 임베딩도 full로 이동했다. 원격 임베딩은 lite에서 유지한다. 관리 화면에서 lite의 내장 임베딩 선택은 비활성화한다.
- 두 구성의 앱 JAR 경로는 현재 동일한 `app/build/libs/app-0.1.0.jar`다. 마지막 빌드 구성을 사용하며 동시 빌드는 금지한다. 5단계의 별도 이미지·배포 산출물 명명은 미완료다. 기존 Dockerfile은 기본 lite다. full 이미지나 외부 OCR 실행 파일을 추가하지 않았다.
- full에서도 리랭커·OCR은 모두 기본 `off`. 모델·학습 데이터 자동 다운로드는 추가하지 않았다. 모델은 저장소·이미지에 넣지 않는다. E5의 기존 해시 검증·데이터 폴더 내 다운로드 제한은 유지한다.
- `/api/full` GET/PUT는 기존 관리자 세션·헤더 CSRF 규칙을 그대로 따른다. 읽기 전용 Bearer 토큰으로 설정할 수 없다. 설정 UI는 이번 범위에 포함하지 않았으며 관리자 API를 사용한다.

## 설정 API

`GET /api/full`은 `available`(full 구현 포함), `hasKey`, 비밀값을 제외한 설정을 제공한다. `PUT /api/full`은 아래 문자열 필드를 받는다. 빈 `apiKey`는 기존 값을 유지한다.

| 필드 | 기본값 / 허용 범위 |
| --- | --- |
| `reranker` | `off`, `remote` |
| `baseUrl` | `https://api.example.com/v1` (실제 서버로 변경) |
| `model` | `reranker` (실제 서버 모델로 변경) |
| `apiKey` | 빈 값, 저장할 때만 입력 |
| `topN` | 20, 1~100 |
| `timeoutSeconds` | 5, 1~30 |
| `ocr` | `off`, `tesseract` |
| `ocrTimeoutSeconds` | 쪽당 15초, 1~60 |
| `ocrMemoryMiB` | 외부 프로세스 주소 공간 512 MiB, 128~2048 |
| `ocrMaxPages` | 파일당 OCR 최대 20쪽, 1~100 |

저장은 데이터 폴더의 `full.properties`에 원자적으로 게시한다. 키는 기존 `embedding.key`를 공유하며 `v2:` AES-GCM + AAD `reranker`로 암호화한다. POSIX에서는 설정 파일을 0600으로 제한한다. 기존 TOTP·임베딩·리랭커 암호문 중 하나라도 남아 있으면 공유 키 누락 시 새 키를 만들지 않는다. full 설정 키를 복호화할 수 없으면 시작을 거부한다. 데이터 폴더와 키를 함께 백업하고 키 파일을 복원해야 한다. full 기능/내장 임베딩을 끈 뒤 lite 산출물로 전환한다. 활성 설정을 유지한 채 lite로 바꾸면 시작이 실패한다.

저장 뒤 모든 소스를 재색인하도록 예약한다. 적용과 색인 완료 사이에는 기존 OCR 결과가 잠시 검색될 수 있다. OCR 활성화가 기존 벡터 모델 식별자를 바꾸지는 않는다.

## 원격 리랭커

관리자가 신뢰하는 일반 rerank HTTP API의 `POST {baseUrl}/rerank`를 사용한다. 원격 문서 전송이므로 외부 서비스로 설정하면 문서 조각과 검색어가 외부로 나간다. 외부 연결은 HTTPS를 사용한다. OpenAI embeddings 엔드포인트와 별개의 프로토콜이며 모든 OpenAI 호환 서버가 rerank를 제공한다고 가정하지 않는다.

```json
{"model":"reranker","query":"검색어","documents":["조각 A","조각 B"],"top_n":2}
```

응답은 `{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.1}]}` 형식이다. 전달된 모든 문서의 중복 없는 정수 index와 유한 점수가 필요하다. 점수 내림차순, 동점은 기존 순서다. 경로·쪽·offset·location·원래 검색 score를 그대로 유지한다. score는 리랭크 점수가 아니므로 반환 순서를 사용한다.

- REST `GET /api/search?query=...&rerank=true`, MCP `search_documents`의 `rerank: true`로 요청마다 선택한다. 생략은 false다. 공급자가 off면 원래 검색 결과다.
- 기존 검색이 소스 권한을 적용한 뒤 반환한 최대 limit개 중 상위 `min(topN, limit)`만 보낸다. limit 밖 후보를 추가로 검색하지 않는다. 조각당 최대 1200자, 응답 최대 64 KiB다.
- 응답 본문 수신까지 전체 시간 상한, HTTP 오류·잘못된 응답·상한 초과·중단 시 입력 순서로 폴백한다. 외부 응답으로 새 조각이나 경로를 삽입할 수 없다.
- 공급자별 동시 외부 호출 1개, 추가 요청은 대기열 없이 원래 순서를 사용한다. 리다이렉트를 따르지 않는다. 서버 응답 본문·인증키를 로그/오류에 남기지 않는다.
- 로컬 다국어 cross-encoder는 1차에서 미구현이었다. 나머지 구현 절에서 `reranker=builtin`으로 추가했다.

## OCR 경로·제한

텍스트 없는 PDF 쪽만 PDFBox 150 DPI로 렌더링한다. 텍스트 있는 쪽은 기존 추출기를 유지한다. PNG/JPEG/TIFF/BMP/GIF는 ImageIO 프레임별로 처리한다. OCR 결과의 `location.type`은 `ocr`, `location.page`와 기존 `page`는 원래 쪽/이미지 프레임 번호다. 텍스트 PDF의 근거는 기존 `page` 타입이다. OCR 때문에 일반 소스를 수정하지 않는다.

`/usr/bin/prlimit`가 `/usr/bin/tesseract`를 직접 실행하며 셸을 사용하지 않는다. 언어는 `kor+eng`, `--psm 3`, `OMP_THREAD_LIMIT=1`이다. 운영자가 Tesseract와 두 언어의 traineddata를 미리 준비해야 한다. 소스 경로 대신 데이터 폴더의 `ocr-tmp` 임시 이미지 경로만 전달한다. 임시 디렉터리는 요청 종료 시 정리한다.

쪽당 시간·CPU 초, 주소 공간(RLIMIT_AS), 출력 파일(RLIMIT_FSIZE 1 MiB) 상한을 둔다. 주소 공간 상한은 RSS 실측값이 아니다. 페이지당 최대 16백만 픽셀·변 길이 8192(보안 검토 반영에서 6백만 픽셀·부분 샘플링으로 강화), 전달 PNG 최대 16 MiB, 최종 텍스트는 기존 추출 문자 상한을 따른다. 다중 이미지 프레임/스캔 쪽 수가 상한을 넘으면 파일 추출 오류이며 일부 쪽만 성공으로 게시하지 않는다. 시간 초과 시 살아 있는 프로세스와 자손을 종료한다. 외부 OCR 실패는 파일 색인 오류로 남는다. 원본 파일 크기·경로·안정화 검사는 기존 색인기를 사용한다.

외부 프로세스 메모리는 JVM 밖이다. 이미지 디코딩/PDF 렌더링 자체는 full JVM에서 수행하므로(재검토 반영에서 자식 JVM으로 격리, 아래 절) **OCR full은 lite의 512 MiB 목표를 보장하지 않는다.** 쪽당 상한이며 파일 전체 시간은 쪽 수에 비례한다. 악의적인 PDF/이미지의 디코더 CPU 시간·JVM 메모리를 별도 프로세스로 격리하는 것은 남은 작업이다. TIFF 코덱·회전·손글씨·표 구조·실제 한국어 인식 품질도 검증하지 않았다.

## OCR 후보 비교와 판단한 것

| 항목 | Tesseract CLI | PaddleOCR ONNX |
| --- | --- | --- |
| 이번 구현 | Providers.OcrProvider 구현 + PDF/이미지 추출 연결 | 기존 Providers.OcrProvider 확장 계약만 유지, 구현 없음 |
| 실제 실행 준비 | 실행 파일 없음 (`tesseract --version` 종료 127), 언어 데이터 미준비 | 고정 모델·토크나이저/사전·전후처리 미준비 |
| 정확도·속도·RSS | 측정하지 못함 | 측정하지 못함 |
| 이번 선택 근거 | 외부 프로세스 시간/주소 공간/출력 상한과 기본 lite 분리가 용이 | 모델 출처·변환·전후처리·한국어 모델 해시 확인 후 비교 필요 |

Tesseract를 **구현 기본 후보**로 택했으며 정확도/성능 우위를 입증한 선택은 아니다. 기본 기능 값은 여전히 off다. 시스템 패키지나 모델을 임의로 설치하지 않았다. 가짜 OCR의 고정 응답과 가짜 프로세스 시간은 실제 인식 정확도/속도/RSS 자료로 사용하지 않았다.

[Tesseract 공식 CLI 문서](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)는 언어 지정과 `+` 조합을 설명한다. [PaddleOCR 공식 ONNX 문서](https://paddlepaddle.github.io/PaddleOCR/main/en/version3.x/deployment/obtaining_onnx_models.html)는 모델 변환 경로를 제공한다. 향후 같은 자체 작성 한국어/영문 스캔 쪽과 이미지로 정규화 문자 오류율(CER), 쪽당 지연, 외부 프로세스와 JVM 각각의 최대 RSS를 수집해야 한다. 모델 고정 리비전·SHA-256·라이선스를 확인하고 데이터 폴더에만 준비한다.

## 검증 결과

최종 lite/full 빌드와 기본 실행 검증이 종료 코드 0으로 통과했다. core 11개·app 15개·full 4개, 합계 30개 테스트에서 실패·생략은 없다. full 앱의 실제 HTTP 통합 결과는 무시되는 `build/verification/stage4/full-tests/`에도 보존했다.

| 명령 | 종료 코드 | 결과 |
| --- | --- | --- |
| `./gradlew -Pfull build -q` | 0 | full 공급자와 REST/MCP 통합 테스트 통과 |
| `./gradlew :full:test -q` | 0 | 리랭커·OCR 4개 테스트 통과 |
| `./gradlew clean build -q`, 최종 `./gradlew build -q` | 각각 0 | lite 산출물·클래스 경로 차단 검사·30개 테스트·프런트엔드 빌드 |
| `VERIFY_PORT=8124 python3 scripts/verify-runtime.py` | 0 | 기본 lite·48문서·업로드/공유·REST/MCP·RSS·프로세스/포트 정리 |
| 기준 커밋 별도 추출본: `VERIFY_PORT=8125 python3 scripts/verify-runtime.py` | 0 | 변경 전과 동일한 표본·JVM 옵션으로 비교 |
| `VERIFY_PORT=8126 python3 scripts/verify-runtime.py` | 0 | 현재 lite RSS 재현·서버/포트 정리 |
| Python ZIP 검사 | 0 | lite 실행 JAR 91,823,167바이트, 라이브러리 85개; full·ONNX·DJL·core의 ONNX 클래스 없음 |
| `git diff --check` | 0 | 공백 오류 없음 |
| `tesseract --version` | 127 | 실행 파일 없음, 실제 OCR 실측 불가 |

검증 항목:

- 가짜 rerank HTTP 서버로 상위 N만 전달, 순서 변경·근거 유지, 중복/소수 index·NaN·HTTP 오류·응답 상한·본문 지연·동시 호출 폴백.
- 실제 REST/MCP 경로의 opt-in과 기본 미적용, 범위 밖 문서 미전송, 관리자/CSRF 없는 설정 거부, API/설정 파일 키 비노출, 재로딩과 다른 AAD 복호화 거부, 다른 공급자 암호문을 남긴 상태의 키 재생성 차단.
- 가짜 실행 파일을 실제 prlimit 아래 실행하여 인자·언어, 오류·시간 초과·임시 파일 정리를 확인.
- 자체 작성 PNG·이미지 전용 2쪽 PDF의 쪽/ocr 표시와 Lucene 저장·검색, 텍스트 PDF의 OCR 미호출, 페이지·픽셀·문자 상한 거부.
- 기본 lite 런타임 의존성 검사와 full 미포함 시 활성화 거부. 기존 보안·검색·업로드·공유·TOTP 회귀 테스트 유지.

## 기본 lite RSS 비교

기존 검증 스크립트의 자체 작성 48문서·PDF 40쪽·16 MiB 업로드/공유·검색을 사용했다. 기본 embedding/OCR/reranker는 off이고 full 모듈은 lite 런타임에 없다. JVM 옵션은 기존과 동일하다. 기준 커밋은 git 메타데이터를 변경하지 않고 무시되는 임시 폴더에 추출했으며 빌드/실행을 순서대로 수행했다.

| 실행 | 시작 최대 KiB | 작업 최대 KiB | 최대 MiB |
| --- | ---: | ---: | ---: |
| 기준 `a4bb16b`, 별도 추출본 | 174004 | 289572 | 282.8 |
| 현재 lite, 첫 실행 | 173968 | 317384 | 309.9 |
| 현재 lite, 재실행 | 174328 | 317264 | 309.8 |

**현재 lite RSS는 기준보다 약 27 MiB(9.6%) 높다.** 1차에서는 증가 원인을 확정하지 못했다(나머지 구현 절의 NMT·GC 분석 참고). full/ONNX/DJL의 런타임 포함이나 네이티브 추론 활성화는 산출물 검사로 배제했지만, 이것만으로 GC·클래스 경로 변경 등 원인을 특정할 수 없다. 두 현재 실행 모두 JVM RSS는 512 MiB 아래이나 실제 cgroup·파일 캐시·컨테이너 전체 제한 검증은 아니다. 기본 구성 메모리 회귀 분석이 남았다. 유리한 실행만 선택하지 않고 두 결과와 기준 결과를 모두 보존했다.

실행 중 표본 생성기의 Log4j provider 경고가 있었으나 앱 빌드·실행·검증은 성공했다. 기준 비교를 처음 요청할 때 임시 작업 디렉터리 생성 전이라 도구가 프로세스 실행을 거부했다(종료 코드 없음). 디렉터리를 만든 뒤 위 기준 비교를 정상 실행했다.

## 남은 작업·아티팩트

- (1차 기준) Qdrant·ML 전용 서버·로컬 ONNX 리랭커는 나머지 구현 절에서 구현했다.
- PaddleOCR와 실제 OCR 비교 실측, OCR 운영용 실행 파일/언어 데이터 고정·배포, full 설정 화면, 실제 외부 서비스 품질/호환 검증: 미완료.
- Docker/cgroup·arm64·대규모/동시 부하·실제 브라우저 검증은 하지 못했다. lite JVM RSS만으로 컨테이너 전체 메모리를 보장하지 않는다.

공개 커밋에서 제외되는 상세 경로:

- `build/verification/stage4/`: `lite-build.log`, `full-build.log`, `lite-runtime.txt`, `lite-runtime-repeat.txt`, `baseline-runtime.txt`, `measurement-*.json`, `lite-artifact.json`, `*-test-summary.json`, `full-tests/`, `tesseract-version.txt`. 로그/테스트 XML은 환경 경로를 포함할 수 있어 공개 커밋에 넣지 않는다.
- `build/verification/measurement.json`, `smoke.txt`, `bootrun.log`: lite 실제 실행 측정·API 검증.
- `full/build/reports/tests/test/index.html`, `app/build/reports/tests/test/index.html`, `core/build/reports/tests/test/index.html`: 자동 테스트 보고서(마지막 실행 구성).

## 나머지 구현 (기준 0d2ff95)

검증일: 2026-10-24. Qdrant 선택 저장소, ML 전용 모드, 로컬 ONNX 리랭커, lite RSS 원인 분석을 추가했다. **4단계 전체는 여전히 미완료**다(PaddleOCR·실제 OCR 비교·실제 Qdrant 서버 검증 남음).

### Qdrant 선택 벡터 저장소

- 위치: core `VectorStore` 계약과 `QdrantVectorStore`(JDK HTTP 클라이언트+Gson, 새 의존성 없음). 원격 서비스 클라이언트이므로 lite에서도 쓸 수 있다. 기본값은 `vectorStore=lucene`(기존 HNSW)이고 임베딩이 꺼져 있으면 Qdrant에 접속하지 않는다.
- 설정: `/api/embeddings`의 `vectorStore`(`lucene`/`qdrant`), `qdrantUrl`(기본 `http://127.0.0.1:6333`), `qdrantCollection`(`[A-Za-z0-9_-]{1,64}`, 기본 `nasquire`), `qdrantApiKey`, `qdrantTimeoutSeconds`(1~60, 기본 10). 변경은 기존 관리자 세션+헤더 CSRF만 허용하고 읽기 전용 토큰은 403이다. 관리 화면 임베딩 설정에 입력란을 추가했다.
- 키: `embedding.key` 공유, AES-GCM `v2:` + AAD `qdrant`, `embedding.properties`의 `encryptedQdrantKey`. GET 응답에는 `hasQdrantKey`만 있다. 다른 용도(AAD) 암호문으로 바꿔 쓸 수 없고, 키 파일이 없을 때 기존 암호문이 있으면 재생성하지 않는 기존 규칙에 Qdrant 암호문도 포함했다. 요청 헤더 `api-key`로만 전송하며 예외·응답에 키와 Qdrant 응답 본문을 남기지 않는다. 리다이렉트를 따르지 않고 응답은 4 MiB 상한이다.
- 저장 내용: 점 ID는 `key + offset`의 이름 기반 UUID, payload는 `source`·`key`·`offset`뿐이다. 문서 본문은 보내지 않는다. 컬렉션 생성 시 `source`·`key` keyword payload 색인을 만든다.
- 검색 범위: 소스 범위를 `filter.must[{key:"source", match:{any:[...]}}]`로 서버 측 적용한다. 범위가 비면 Qdrant를 호출하지 않는다. 응답 점은 앱에서 소스 범위·`key` 접두사를 다시 검사하고, 본문·경로는 Lucene `chunk` ID 조회에 같은 소스 범위 필터를 걸어서만 얻는다. 손상·악의적 서버가 필터를 무시해도 범위 밖 조각이 REST/MCP로 나가지 않는다(가짜 서버 `ignoreFilter` 회귀 테스트).
- 재색인 정합성: 색인 커밋 메타 `vector.store`(Lucene 또는 `qdrant:<주소>/<컬렉션>`)를 모델·차원과 함께 기록한다. 저장소·주소·컬렉션·모델·차원이 바뀌면 재색인 전 vector/hybrid 검색은 409이고 keyword는 유지한다. 재색인 시작 때 컬렉션을 삭제·재생성하며 실패하면 요청을 유지하고 다음 검사에서 재시도한다. 기존 컬렉션 차원이 다르면 쓰기를 거부한다.
- 동기화: 파일 교체 때 같은 key의 점을 지우고 새 점을 넣은 뒤 Lucene을 게시한다. 외부 저장 실패 시 그 파일은 Lucene에도 게시하지 않고 파일 오류로 남는다. 파일 삭제·소스 삭제·등록 해제는 Lucene 삭제 후 Qdrant 필터 삭제를 수행하며, Qdrant 삭제가 실패하면 검사 오류로 남고 다음 검사에서 재시도한다. 남은 점은 Lucene 조회에서 버려지므로 노출되지 않는다.
- 한계: 실제 Qdrant 서버(버전 호환·TLS·인증·대용량 컬렉션·성능)는 이 환경에 없어 검증하지 못했다. REST 경로는 Qdrant 공식 API(`/collections/{name}`, `/index`, `/points`, `/points/delete`, `/points/query`) 기준이며 가짜 서버로만 확인했다. 당시에는 검색 요청마다 Lucene 잠금 안에서 Qdrant를 호출했다(보안 검토 반영에서 잠금 밖으로 이동, 아래 절).

### ML 전용 모드

- 실행: full 빌드에서 `--nasquire.mode=ml` 또는 `NASQUIRE_MODE=ml`. `NasquireApplication.main`이 Spring보다 먼저 분기하여 JDK HTTP 서버만 띄운다. 관리 화면·정적 파일·소스·검색·문서·다운로드·공유·업로드·MCP·H2 DB가 없다. lite 빌드에서는 `full 빌드가 필요합니다`로 시작을 거부한다. Spring 컨텍스트에서는 계속 `search`만 허용한다.
- 인증: 모든 경로(없는 경로 포함)가 먼저 `Authorization: Bearer <토큰>`을 검사한다. 토큰의 SHA-256을 `MessageDigest.isEqual`로 비교한다(고정 길이 상수 시간). 없음·형식 오류·틀림은 401과 `WWW-Authenticate: Bearer`. 토큰은 `nasquire.ml.token`(설정 파일 `--nasquire.ml.config=` 또는 `NASQUIRE_ML_CONFIG`, 환경변수 `NASQUIRE_ML_TOKEN`)이나 `nasquire.ml.token-file`(`NASQUIRE_ML_TOKEN_FILE`)에서만 읽는다. 명령행 `--nasquire.ml.token=`은 프로세스 목록 노출을 막기 위해 거부한다. 32~512자 ASCII가 아니거나 미설정이면 시작 거부. 토큰·요청 본문은 로그에 쓰지 않으며 `verify-ml.py`가 로그에 토큰이 없는지 검사한다.
- 기능: `nasquire.ml.embedding=builtin`(E5 int8, 고정 해시), `nasquire.ml.reranker=builtin`(아래 로컬 리랭커), `nasquire.ml.ocr=tesseract`. 모두 기본 off이며 하나도 켜지 않으면 시작 거부. 모델은 `nasquire.data/models/...` 기본 경로 또는 `*-model-path` 설정, `nasquire.ml.download=true`일 때만 데이터 폴더 안으로 받는다. 시작 때 한 번 추론하여 모델·해시 오류면 시작 거부한다.
- API: `POST /v1/embeddings`(OpenAI 형식: `input` 문자열/배열 1~64개·각 8192자, `encoding_format=float`, `model`은 설정 이름과 같아야 함), `POST /v1/rerank`(기존 원격 리랭커 형식: `query`, `documents` 1~100개·각 4096자, `top_n` → `results[{index, relevance_score}]`), `POST /v1/ocr`(단일 프레임 이미지 본문 16 MiB·픽셀 상한 → `{"text":...}`, kor+eng), `GET /v1/models`. JSON 1 MiB 상한(413), 쿼리 문자열 거부, 기능별 동시 추론 1개·최대 10초 대기 후 503(보안 검토 반영에서 대기 없이 즉시 503), 처리 스레드 기본 4개, JDK HTTP 서버 요청 60초·응답 300초 상한. 입력 오류 400, 추론 실패 500(원인 미포함).
- 일반 Nasquire와 연동: 원격 임베딩/원격 리랭커의 `baseUrl`을 ML 서버의 `/v1`로, API 키를 ML 토큰으로 지정한다. 가짜 공급자를 넣은 실제 `MlServer`에 기존 `RemoteEmbeddingProvider`·`RemoteRerankerProvider`를 연결한 테스트로 확인했다. 검색 서버에서 ML 서버 OCR을 호출하는 원격 OCR 클라이언트는 미구현이다.

### 로컬 ONNX 리랭커

| 항목 | 값 |
| --- | --- |
| 모델 | [cross-encoder/mmarco-mMiniLMv2-L12-H384-v1](https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1), 모델 카드 라이선스 Apache-2.0 (AGPL 호환) |
| 고정 커밋 | `1427fd652930e4ba29e8149678df786c240d8825` |
| 모델 파일 | `onnx/model_qint8_arm64.onnx` 118,620,017바이트, SHA-256 `1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4` (같은 커밋의 `qint8_avx512`·`avx512_vnni`와 동일 해시, 범용 qint8로 amd64에서 실행 확인) |
| 토크나이저 | `tokenizer.json` 17,082,660바이트, SHA-256 `62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626` |
| 기본 경로 | `<데이터 폴더>/models/mmarco-mMiniLMv2-L12-H384-v1/model-qint8.onnx`, `tokenizer.json` |

- 학습 데이터 mMARCO는 MS MARCO 번역본이며 데이터셋 이용 조건이 모델 라이선스와 별도다. 상업적 사용 전 운영자가 검토해야 한다. 모델 파일은 저장소·이미지에 넣지 않았다(검증용 사본은 무시되는 `build/verification/models/`).
- 검색 서버: `/api/full`의 `reranker=builtin`, `localModelPath`·`localTokenizerPath`(빈 값=기본 경로), `localDownload`(`true`/`false`, 기본 false). 기존 `topN`·`timeoutSeconds`를 사용한다. 질의·조각 쌍을 최대 256토큰으로 잘라 한 쌍씩 추론하고 동시 호출 1개, 혼잡·시간 초과·모델 없음·해시 불일치 때 원래 순서를 유지한다. 처음 사용할 때 모델을 읽는다. 저장소 밖 다운로드·해시 불일치 모델은 로드하지 않는다(테스트).
- ONNX Runtime 옵션은 기존 E5와 같다(스레드 1, 메모리 패턴·CPU 아레나 off). `BASIC_OPT`·`NO_OPT`·`session.disable_prepacking` 비교에서 RSS 차이가 없어(615.3 / 617.4 / 622.9 MiB) `NO_OPT`를 유지했다.

`VERIFY_PORT=8102 python3 scripts/verify-ml.py` (Linux amd64, JDK 25, `-Xmx128m` Serial GC, `MALLOC_ARENA_MAX=2`, 실제 모델, 서버 종료·포트 정리 포함):

| ML 서버 기능 | 시작(초) | 시작 후 RSS MiB | 최대 RSS MiB | 20문서 리랭크 중앙값 |
| --- | ---: | ---: | ---: | ---: |
| 리랭커만 (최종, 서버 시간 상한 추가 후) | 5.9 | 616.0 | 619.9 | 0.142초 |
| 리랭커만 (`NO_OPT` 재실행) | 6.01 | 630.7 | 632.4 | 0.139초 |
| 리랭커만 (`BASIC_OPT` 첫 실행) | 6.06 | 613.8 | 615.3 | 0.142초 |
| 내장 E5 임베딩만 | 5.75 | 613.7 | 627.1 | - |
| 임베딩+리랭커 | 6.92 | 1045.6 | 1047.8 | 0.145초 |

- 한국어 질의 "한국의 수도는 어디인가?"는 수도 관련 문장이 1위, 영어 질의 "What is the capital of France?"는 Paris 문장이 1위였다(정확도 벤치마크가 아닌 동작 확인). 토큰 없음·틀린 토큰 401, `/`·`/api/sources`·`/api/search`·`/mcp` 404 확인.
- **로컬 리랭커·ML 서버는 512 MiB 목표를 초과한다.** 모델 하나당 JVM 기본 대비 약 450 MiB가 증가한다. 리랭커를 검색 서버(full)에서 켜면 기존 내장 임베딩과 마찬가지로 512 MiB 컨테이너에서 쓰지 말아야 한다. 메모리 절감(작은 어휘 모델·외부 프로세스 분리)은 미완료다.

### PaddleOCR 판단

PaddleOCR ONNX는 이번에도 구현하지 않고 Tesseract CLI 경로를 유지한다. 이유:

- PaddleOCR 자체는 Apache-2.0으로 라이선스는 호환되지만, ONNX 경로는 검출(DB)·방향 분류·인식(CTC) 모델 3종과 한국어 사전, 이미지 전처리·DB 후처리(다각형 확장)·CTC 디코딩을 Java로 다시 구현해야 한다. 공식 배포는 Paddle 형식이며 ONNX는 paddle2onnx 변환물이어서 고정 리비전·SHA-256·변환 재현성을 함께 확인해야 한다.
- 비교 기준인 Tesseract도 이 환경에 없어(`tesseract --version` → command not found) 실제 정확도·속도·RSS를 비교할 수 없다. 비교 없이 두 번째 엔진을 추가하면 메모리·보안 표면만 늘어난다.
- Tesseract는 별도 프로세스로 시간·주소 공간·출력 상한을 걸 수 있어 JVM 메모리와 격리된다. ONNX OCR은 JVM 프로세스 안에서 모델 메모리를 추가로 쓴다(위 리랭커 실측상 모델당 수백 MiB).

향후 같은 자체 작성 스캔 쪽 표본으로 CER·쪽당 지연·외부 프로세스/JVM RSS를 측정한 뒤 결정한다.

### lite RSS 증가 원인 조사

같은 검증 스크립트(48문서·16 MiB 업로드/공유)를 기준 `a4bb16b` 추출본과 `0d2ff95` 추출본에서 순서대로 실행하고, 최대 RSS 시점에 NMT·`/proc/<pid>/status`·smaps·힙 정보를 수집했다(측정 도구는 무시되는 `build/verification/stage4/rss/`).

| 항목 | 기준 a4bb16b | 0d2ff95 |
| --- | ---: | ---: |
| 검증 스크립트 최대 RSS (NMT 켬, 1/2/3/5회) | 289.5 / 289.5 / 290.4 / 290.7 MiB | 315.7 / 315.9 / 316.5 / 316.5 MiB |
| NMT 커밋 합계 (1회) | 292,305 KB | 292,037 KB |
| NMT Java Heap 커밋 (1회) | 120,680 KB | 119,544 KB |
| NMT Metaspace / Class / Code / Other 커밋 (1회) | 67,180 / 12,363 / 19,483 / 27,342 KB | 67,243 / 12,384 / 19,917 / 27,340 KB |
| RssAnon / RssFile (2회) | 264,940 / 30,168 KB | 292,408 / 30,032 KB |
| 최대 시점 Tenured 사용/커밋 (2회) | 51,823 / 80,376 KB (64%) | 80,720 / 80,824 KB (99%) |
| 최대 시점 byte[] 바이트(도달 불가 포함, 5회) | 36,587,976 | 50,308,592 |
| 실행 JAR 라이브러리 / 크기 | 88개 / 163,907,968 B (ONNX·DJL·JNA 포함) | 84개 / 91,838,731 B (해당 4개만 제거, 추가 없음) |

- full·ONNX·DJL 클래스는 현재 lite JAR에 없다(`lite-jar-compare.json`). 0d2ff95는 기준 대비 라이브러리를 추가하지 않고 4개를 제거만 했다.
- NMT 커밋 메모리·파일 매핑 RSS는 같고, 차이(약 27 MiB)는 전부 익명 메모리의 Java 힙에서 실제로 건드린 구세대 페이지다. GC 로그에서 Full GC(Metadata 임계) 직후의 첫 Young GC가 기준은 78→48 MB, 0d2ff95는 105→78 MB로, 그 시점 살아 있던 큰 일시 객체(byte[])가 생존 공간 부족으로 구세대에 조기 승격되고 이후 Full GC가 없어 남았다.
- JFR을 켜서 타이밍을 바꾸면 두 커밋 모두 같은 GC 순서와 305.7 / 306.0 MiB가 되었다. 즉 코드·의존성의 상주 메모리 증가가 아니라 Serial GC 승격 시점 차이다. 두 경우 구세대 커밋 크기(약 80 MB)는 같으므로 같은 작업량의 최악 RSS 상한도 같다.
- 이번 변경 후 최종 lite 실측: `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` 종료 0, 시작 최대 174,096 KiB, 작업 최대 316,616 KiB(**309.2 MiB**, 직전 실행 309.3 MiB). 원인이 의존성이 아니므로 기존 282.8 MiB 수준으로 되돌리는 코드 변경은 하지 않았다. JVM 옵션(생존 공간 비율 등) 조정은 다른 작업량의 영향 검증 없이 바꾸지 않았다. 실제 cgroup 제한 검증은 미완료다.

### 나머지 구현 검증 결과

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew -Pfull build -q` (최종) | 0 | core 14·app 16·full 10 테스트, full 앱 통합(원격·로컬 리랭커 폴백 포함) |
| `./gradlew build -q` (최종, 마지막 구성 lite) | 0 | core 14·app 16·full 10, 실패·생략 0, `verifyLiteClasspath` 통과 |
| `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` | 0 | lite 48문서·업로드/공유·REST/MCP, 309.2 MiB(2회 중 최종), 서버·포트 정리 |
| `VERIFY_PORT=8102 python3 scripts/verify-ml.py` (리랭커, 5회) | 각각 0 | 위 표 |
| `VERIFY_ML_FEATURES=embedding`, `both` | 각각 0 | 위 표 |
| `python3 scripts/fetch-verification-models.py reranker` | 0 | 두 파일 SHA-256 일치 |
| 기준/0d2ff95 RSS 비교 도구 5회씩 | 각각 0 | 위 표 |
| `tesseract --version` | 127 | 실행 파일 없음 |

추가 회귀 테스트: 가짜 Qdrant의 필터 전송 내용·필터 무시 시 범위 밖 미노출(core·REST·MCP vector/hybrid), 빈 범위 무호출, 본문 미전송, 키 헤더·잘못된 키·응답 본문 비노출, 키 파일 암호화(`encryptedQdrantKey=v2:`)·API 비공개·재로딩 복호화, 저장소/컬렉션/차원 변경 차단과 재색인, 파일 교체·삭제·소스 삭제 동기화, 외부 실패 시 Lucene 미게시, ML 서버 9개 경로×10개 잘못된 인증값 401·관리/검색 경로 404·405·쿼리 거부·입력 수/길이/JSON 크기 상한·비활성 기능 404, 명령행 토큰 거부·토큰 미설정/짧음/기능 없음 시작 거부, 모델 없을 때 다운로드 없는 시작 거부, lite에서 ML 모드 거부.

남은 작업: 실제 Qdrant 서버·TLS·대규모 검증, 원격 OCR 클라이언트, PaddleOCR 구현과 실제 OCR 비교, full 설정 UI(리랭커·OCR), ML 서버 이미지·arm64·cgroup, 로컬 모델 메모리 절감. 상세 아티팩트(공개 커밋 제외): `build/verification/ml/measurement-*.json`, `build/verification/ml/ml-*.log`, `build/verification/stage4/rss/{base,head}[1-5]/`, `build/verification/stage4/rss/lite-jar-compare.json`, `build/verification/stage4/final-*-build.log`, `build/verification/stage4/final-lite-runtime.txt`.

## 보안 검토 반영 (기준 4de7837)

검증일: 2026-10-24. 4단계 보안 교차 검토의 중간 5개·낮음 6개 항목을 반영했다. 기존 보안 동작(관리자 세션+CSRF, 소스 범위 재검사, 키 비공개·AAD, 기본 off)은 그대로다.

| 항목 | 처리 | 남은 한계 |
| --- | --- | --- |
| M1 OCR 메모리 부족 시 색인 정지 | 주기 작업이 `Throwable`을 잡아 `_system` 상태에 기록(메모리 부족 구분)하고 다음 주기를 계속한다. 파일 추출 중 `OutOfMemoryError`·`StackOverflowError`는 그 파일만 오류로 센다. OCR 결과 픽셀 상한 16M→6M, 디코딩 래스터(가로×세로×밴드×바이트) 24 MiB, PDF 쪽 자원의 내장 이미지 원본 래스터 64 MiB·변 16384. PDF는 `ImageType.GRAY`, 큰 쪽은 픽셀 상한에 맞춰 DPI를 낮추고(최소 50 DPI 미만이면 거부), 이미지는 헤더 크기로 `ImageReadParam` 부분 샘플링 비율을 정한다. ML 서버 `/v1/ocr`도 같은 함수를 쓰며 상한 위반은 400. | 디코더 내부 작업 메모리는 코덱에 따라 래스터보다 클 수 있다. 중첩 Form XObject·인라인 이미지는 사전 검사하지 않고 시간 제한에 맡긴다. full OCR은 여전히 512 MiB를 보장하지 않는다. |
| M2 렌더링·디코딩 시간 제한 | PDF 쪽 렌더링과 이미지 프레임 디코딩만 별도 데몬 스레드에서 쪽/프레임당 20초로 제한한다(Tesseract는 기존 쪽당 외부 프로세스 상한). 초과·메모리 부족은 그 파일 추출 실패이며 색인 작업자는 즉시 다음 파일로 간다. 렌더 작업자는 대기열 없이 최대 2개다. | 중단을 무시하는 렌더러가 끝나지 않으면 스레드가 남는다(최대 2개). 2개가 모두 남으면 그동안 OCR 파일은 "이미지 작업자 사용 불가"로 실패하고 다음 검사에서 재시도한다. 별도 프로세스 격리는 미구현. 텍스트 추출(PDFBox 파싱) 자체의 시간 제한은 기존과 같다. |
| M3 Qdrant 호출의 공통 잠금 점유 | `SearchIndex`가 Qdrant·임베딩 호출을 잠금 밖에서 하고, 결과를 쓰기 전 잠금 안에서 벡터 구성 세대(공급자·저장소 교체, 재색인 시작 때 증가)를 확인한다. 세대가 바뀌면 검색은 409, 색인 게시는 파일 오류로 다음 검사에서 재시도한다. Lucene 삭제를 먼저 게시하고 외부 점 삭제는 잠금 밖에서 한다. 외부 결과는 기존처럼 잠금 안에서 범위 필터를 건 Lucene `chunk` 조회로만 본문을 얻는다. 교체된 공급자·저장소는 사용 중인 작업이 끝난 뒤 닫는다. keyword 검색은 잠금 없이 SearcherManager로 수행한다. `QdrantVectorStore`는 작업 전체 시간 상한(검색 = 설정 시간, 쓰기·초기화 = 4배, 본문 지연 포함)과 연속 3회 실패 시 30초 회로 차단(원격 요청 없이 즉시 실패)을 둔다. | 차단 중에는 vector/hybrid 검색과 Qdrant 쓰기가 실패한다(쓰기 실패 파일은 재시도). 실제 Qdrant 서버 지연 분포로 상한을 조정하지 않았다. |
| M4 ML 서버 대기열 | 고정 크기 `ThreadPoolExecutor`+`SynchronousQueue`, 포화 시 `Retry-After: 1`과 503. 인증 검사는 수신 스레드에서 SHA-256 1회로 끝내고 실패는 작업자를 쓰지 않고 401. 기능별 슬롯도 대기 없이 503. 연결 수 `jdk.httpserver.maxConnections=64`, 유휴 16, 요청 60초·응답 300초 기본값(JVM 옵션으로 지정한 값 우선). | JDK HTTP 서버의 수신 스레드는 1개다. 대량 연결 공격 방어는 리버스 프록시의 연결/속도 제한이 필요하다. |
| M5 평문 HTTP 인증정보 | 원격 임베딩 `baseUrl`·Qdrant `qdrantUrl`·원격 리랭커 `baseUrl`이 루프백(`localhost`, `127.0.0.0/8`, `[::1]`, DNS 조회 없음)이 아닌 `http://`면 저장 거부(400). `allowInsecureHttp=true`(임베딩 설정·full 설정 각각)일 때만 허용하고 응답 `insecureHttp`로 관리 화면에 경고한다. 기존 설정 파일에 비루프백 평문이 있으면 시작은 하고 임베딩·Qdrant(또는 원격 리랭커)만 끈 뒤 `disabledReason`을 표시한다(keyword 검색 유지, 키 삭제 가능). ML 서버 기본 바인드는 `127.0.0.1`. | 외부 노출은 `--server.address=0.0.0.0` 등 명시 설정과 앞단 TLS 리버스 프록시(HTTPS 종료, 연결/속도 제한, Authorization 미기록)로 구성한다. 시작 시 끈 임베딩은 재색인이 필요할 수 있다. |
| L1 OCR 임시 파일 | OCR 공급자 생성(시작·설정 저장) 때 데이터 폴더 `ocr-tmp`의 24시간 지난 `page-*` 디렉터리를 링크를 따르지 않고 정리한다. | 진행 중 작업과 겹치지 않게 24시간 기준이다. |
| L2 주소 변경 시 키 재사용 | 리랭커·Qdrant·원격 임베딩 주소가 바뀌고 새 키를 함께 입력하지 않으면 저장된 키를 지운다. | 경로만 바뀐 같은 호스트도 키를 지운다(안전한 쪽). |
| L3 full 저장 재색인 | `PUT /api/full`은 `ocr`·`ocrTimeoutSeconds`·`ocrMemoryMiB`·`ocrMaxPages`가 실제로 바뀐 경우에만 전체 재색인한다. | - |
| L4 tesseract 환경 | 자식 프로세스 환경을 비우고 `PATH=/usr/bin:/bin`, `LANG=C.UTF-8`, `OMP_THREAD_LIMIT=1`, 운영자가 지정한 `TESSDATA_PREFIX`만 전달한다. | - |
| L5 키 삭제 | `DELETE /api/embeddings/key`, `DELETE /api/embeddings/qdrant-key`, `DELETE /api/full/key`(관리자 세션+헤더 CSRF, 감사 기록). 메모리와 설정 파일 암호문을 모두 지운다. `PUT`의 `clearKey`/`clearQdrantKey=true`도 같다. 임베딩 화면에 삭제 버튼을 추가했다. | full 설정 UI가 없어 리랭커 키 삭제는 API로만 한다. |
| L6 (선택) | 미구현. 스캔 PDF는 텍스트 추출과 렌더링에 두 번 로드하며, OCR 한 쪽 실패는 기존처럼 파일 전체 실패(일부 쪽만 게시하지 않음)다. | 향후 쪽 단위 실패 상태 기록과 단일 로드를 검토한다. |

회귀 테스트: core `ReviewFixesTest`(Throwable 뒤 다음 주기 실행, 파일 단위 메모리 부족, 느린 가짜 Qdrant 중 keyword 검색·상태 조회 500 ms 이내와 세대 변경 시 이전 결과 거부, 회로 차단 중 원격 요청 없음·작업 전체 상한, 루프백 판정), app `ReviewSettingsTest`(비루프백 http 거부·명시 허용 경고·기존 설정 시작 시 비활성, 주소 변경 시 키 폐기, 키 삭제·재로딩, OCR 필드 변경 시에만 재색인), app 통합 테스트(키 삭제 API의 세션·CSRF·토큰 거부, Qdrant 평문 주소 400), full `ReviewFixesFullTest`(렌더 시간 초과 시 호출자 해제·다음 작업 처리, 부분 샘플링·상한 거부·ML 상한, DPI 축소, ML 서버 포화 503·포화 중 401 즉시 응답).

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew -Pfull build -q` | 0 | core 19·app 20·full 13 테스트, 실패·생략 0 |
| `./gradlew build -q` (최종, 마지막 구성 lite) | 0 | 같은 52개 테스트, `verifyLiteClasspath` 통과 |
| `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` | 0 | lite 48문서·업로드/공유·REST/MCP, 시작 최대 174,088 KiB, 작업 최대 316,288 KiB(308.9 MiB), 서버·포트 정리 |
| `VERIFY_PORT=8102 python3 scripts/verify-ml.py` | 0 | 리랭커 ML 서버(127.0.0.1 바인드), 401·404·한/영 순위·반복 200, 최대 623.3 MiB |
| `git diff --check` | 0 | 공백 오류 없음 |

못 한 검증: 실제 Tesseract(실행 파일 없음)·실제 Qdrant 서버·TLS 리버스 프록시 앞 ML 서버·악성 PDF/이미지 코퍼스 퍼징·cgroup 메모리 제한. 상세 아티팩트(공개 커밋 제외): `build/verification/stage4-review/`(`full-build.log`, `lite-build.log`, `lite-build-final.log`, `lite-runtime.txt`, `ml-runtime.txt`, `exit.txt`).

## 재검토 반영 (기준 23a4c61)

검증일: 2026-10-24. 위 보안 검토 반영에 대한 재검토의 중간 2개·낮음 2개 항목을 반영했다. 기존 보안 동작(관리자 세션+CSRF, 소스 범위 재검사, 키 비공개·AAD, 기본 off, 소스 읽기 전용)은 그대로다.

| 항목 | 처리 | 남은 한계 |
| --- | --- | --- |
| R1 OCR 렌더링·디코딩이 같은 JVM 스레드(검사 일부는 시간 제한 밖, 잔류 스레드로 풀 영구 소진) | `OcrSandbox`가 파일마다 같은 클래스 경로의 java 자식(`OcrWorker`)을 `prlimit`(주소 공간 2 GiB·CPU 초·파일 크기 256 MiB·core 0) 아래 `-Xmx192m`·SerialGC·C1·프로세서 1개로 실행한다. 환경은 `PATH`·`LANG`·`MALLOC_ARENA_MAX`만, 표준 입력 `/dev/null`, 출력 폐기. 실행 JAR(`java -jar`)이면 앱 main이 첫 인자 `--nasquire.mode=ocr-worker`로 Spring보다 먼저 분기하고, 그 밖에는 `-cp`로 실행한다(클래스 경로·작업 폴더 절대 경로). 부모는 원본을 마지막 요소 NOFOLLOW·읽기 전용으로 열어(일반 파일·크기 재확인) 데이터 폴더 `ocr-tmp/job-*`(0700)에 복사하고 자식은 이 폴더만 읽고 쓴다(`java.io.tmpdir`·PDFBox 글꼴 캐시도 작업 폴더). 파일 열기·이미지 헤더/`getNumImages`·PDF 내장 이미지/색 공간 검사·렌더링·디코딩·PNG 인코딩이 모두 자식에서 실행된다. 파일 단위 전체 시간 = 15초 + 대상 쪽(이미지는 최대 쪽 수)×10초, 최대 300초. 초과·중단 시 자손→자식 순으로 `destroyForcibly` 후 회수하며 정상 종료 뒤에도 자손을 정리한다. 자식 실행은 프로세스 전체에서 동시에 1개(공정 세마포어, 대기도 시간 상한). 결과 PNG는 쪽당 16 MiB·합계 256 MiB 상한이며 부모가 NOFOLLOW·일반 파일·크기를 다시 확인해 읽는다. 종료 코드 2(상한·입력 오류)는 ML 서버에서 400, 그 밖의 실패는 그 파일만 색인 오류다. 기존 렌더 스레드 풀(`limited`)은 제거했다. ML 서버 `/v1/ocr`도 같은 경로(단일 프레임). | Tesseract 호출은 기존처럼 부모가 쪽마다 별도 `prlimit` 프로세스로 실행한다(자식 JVM의 주소 공간 상한과 운영자 `ocrMemoryMiB` 상한이 충돌하지 않게 분리). 파일마다 JVM을 새로 띄우므로 OCR 대상 파일당 시작 비용이 있다. 부모의 텍스트 PDF 파싱(PDFBox, 기존 lite와 같은 경로)은 격리 대상이 아니다. 자식 JVM 메모리(아래 실측)는 컨테이너 전체 메모리에 더해진다. |
| R2 `SearchIndex.close()` 수명 | 종료 플래그로 신규 작업 거부 → 세대 증가 → 잠금 밖 활성 작업 완료를 최대 10초 대기 → 초과 시 그 스레드들을 중단(원격 HTTP 호출 취소)하고 다시 최대 10초 대기 → 교체 대기 객체·공급자·저장소·Lucene 순으로 닫는다. 종료 중·후 호출은 `IllegalStateException("색인이 종료되었습니다")`, 종료와 겹친 게시는 하지 않는다. | 중단에도 응하지 않는 공급자는 두 번째 대기 뒤 사용 중에 닫힐 수 있다(공급자 쪽 자체 시간 상한에 의존). |
| R3 삭제와 원격 쓰기 경쟁·이전 세대 점 | 진행 중 replace를 목록으로 두고 `delete(key)`·`deleteSource(source)`가 같은 key·source를 무효로 표시한다. 게시 직전 잠금 안에서 종료·삭제·세대를 검사하고 무효면 게시하지 않으며 이미 쓴 Qdrant 점을 `deleteKey`로 지운다(쓰기 도중 실패해도 삭제가 끼어들었으면 정리). 외부 점 payload에 영속 색인 세대 `epoch`(Lucene 커밋 `vector.epoch`, 재색인 시작 때 증가)를 쓰고 Qdrant 검색 필터·앱 재검사 모두 현재 세대만 허용한다. 세대 기록이 없는 기존 Qdrant 색인은 시작 시 재색인 필요로 표시한다. | 기존 Qdrant 사용 설치는 업그레이드 후 1회 전체 재색인이 필요하다. 같은 key를 여러 스레드가 동시에 replace하는 경우는 색인 작업자 단일 구조에 의존한다. |
| R4 OCR 임시 폴더 정리 | 앱 시작(`UploadService` 시작 정리)에서 OCR 사용 여부와 무관하게 `ocr-tmp`의 24시간 지난 `page-*`·`job-*`를 링크를 따르지 않고(링크 자체만) 깊이 4까지 지운다. OCR·ML 서버 생성 때도 같은 정리를 한다. | 24시간 이내 잔여물은 다음 시작까지 남는다. |

회귀 테스트: core `RereviewFixesTest`(원격 임베딩 중 close 시 완료 전 미종료·사용 중 닫기 없음·종료 후 예외, 대기 초과 시 중단 후 닫기, 임베딩 대기 중 key/source 삭제 → 삭제 유지·Qdrant 점 없음, 이전 세대 점 무시·서버 필터 무시 시 앱 재검사, 세대 없는 기존 Qdrant 색인 재색인 요구, `ocr-tmp` 정리의 링크 미추적), full `ReviewFixesFullTest`(손자 프로세스를 띄우고 중단을 무시하는 무한 루프 가짜 자식 → 1.5초 상한 뒤 시간 초과, 관찰한 자식·손자 프로세스 모두 종료·잔존 0, 다음 파일 실제 `OcrWorker` OCR 정상, 작업 폴더 정리, 상대 경로 데이터 폴더, 손상 이미지·소스 링크 실패, 부분 샘플링·상한·ML 단일 프레임), 기존 OCR/ML 테스트는 실제 자식 프로세스로 실행.

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew -Pfull build -q` | 0 | core 24·app 20·full 13, 합계 57개 테스트, 실패·생략 0 |
| `VERIFY_PORT=8102 python3 scripts/verify-ml.py` (첫 실행) | 1 | `ocrChildDecodes` 실패: 실행 JAR 경로가 상대 경로라 작업 폴더를 작업 디렉터리로 쓰는 자식이 JAR를 찾지 못함. 클래스 경로·작업 폴더 절대 경로화로 수정 |
| `VERIFY_PORT=8102 python3 scripts/verify-ml.py` (수정 후) | 0 | 실행 JAR ML 서버(리랭커+OCR): 기존 검사 + `/v1/ocr` 자식 디코딩 성공(Tesseract 없음 → 500), 픽셀 상한·손상 이미지 400, 서버의 자식 프로세스 잔존 0, `ocr-tmp` 비어 있음. 최대 628.7 MiB |
| `./gradlew build -q` | 0 | lite, `verifyLiteClasspath` 통과 |
| `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` | 0 | lite 48문서·업로드/공유·REST/MCP·TOTP, 시작 최대 174,804 KiB, 작업 최대 290,160 KiB(283.4 MiB), 서버·포트 정리 |
| 실행 JAR 자식 단독(자체 생성 4000×3000 회색조 PNG, 위와 같은 prlimit·JVM 옵션) | 0 | 0.28초, 자식 최대 RSS 61,252 KiB(부분 샘플링 결과 1쪽) |

정리 회귀 테스트·문서 추가 뒤 `./gradlew -Pfull build -q`와 최종 `./gradlew build -q`(마지막 구성 lite)를 다시 실행해 각각 0이었고(57개 테스트, 실패·생략 0), `git diff --check`도 0이다. 런타임 실측 뒤에는 테스트·문서만 바뀌었다. 못 한 검증: 실제 Tesseract(실행 파일 없음)·실제 Qdrant 서버·악성 PDF/이미지 코퍼스 퍼징·큰 스캔 PDF에서 자식 JVM 메모리·cgroup 제한. 상세 아티팩트(공개 커밋 제외): `build/verification/stage4-rereview/`.

## 최종 재확인 반영 (기준 f28cf43)

검증일: 2026-10-24. 중간 3개·낮음 1개 잔여 항목을 보완했다. 위 재검토 R1·R2의 원본 열기·종료 한계는 이 절의 동작으로 대체한다. 4단계 전체의 기존 미완료 항목은 유지한다.

| 항목 | 최종 처리 |
| --- | --- |
| S1 OCR 원본 FIFO 고착 | core `SecureFiles`의 `O_NOFOLLOW\|O_NONBLOCK` 열기와 열린 fd의 `fstat`으로 일반 파일만 허용한다. 원본 복사는 자식 실행과 같은 파일 단위 시간 상한을 사용하고 복사 전·각 버퍼·완료 때 시간/중단을 검사한다. FIFO로 바뀐 원본은 즉시 실패하고 슬롯·작업 폴더를 반환한다. |
| S2 중간 디렉터리 링크 교체 | `/`부터 각 요소를 `openat`·`O_DIRECTORY\|O_NOFOLLOW`로 고정한다. 색인기는 파일 식별자·크기·수정 시각을 확인하고 텍스트·PDF·Office·OCR 모두 고정 fd 경로(`/proc/self/fd/N`)에서 읽는다. 확장자 판별용 이름은 별도로 전달한다. 업로드·다운로드도 같은 `SecureFiles`를 쓰며 기존 app `SecureDirectories`는 제거했다. 미지원 환경에서 경로 기반 읽기로 폴백하지 않는다. |
| S3 중단 무시 공급자 사용 중 닫기 | 두 번째 대기 뒤에도 활성 작업이 있으면 공급자·저장소 폐기를 마지막 `exit()`까지 미룬다. 실제 공급자 `close()`는 색인 잠금 밖에서 호출하므로 내장 ONNX처럼 추론/닫기에 같은 synchronized 잠금을 쓰는 공급자와 잠금 순환을 만들지 않는다. 종료 뒤 결과 게시·신규 작업은 거부한다. 영구 미종료 공급자는 그만큼 폐기도 보류한다. |
| S4 자식·출력·직접 실행 | `setsid`로 새 프로세스 그룹을 만들고 자손 목록을 확보한 뒤 그룹·자손·자식을 종료한다. 고아 손자까지 실행 종료를 확인한 뒤 출력 검사·슬롯 반환·실패 작업 폴더 정리를 한다. 확인이 늦으면 백그라운드 회수자가 슬롯을 보유한다. 부모도 열린 fd에서 일반 파일·쪽당 16 MiB·합계 256 MiB를 검사하고 실제 읽기에도 상한을 적용한다. 작업자 직접 실행은 실경로 `ocr-tmp/job-*`·현재 사용자 소유·0700 폴더만 허용한다. |

회귀 테스트: core `FinalReviewFixesTest` 3개(추출 도중 중간 폴더를 외부 링크로 바꾸고 사후 검사 전 복구해도 외부 본문 미색인·원래 본문 유지, FIFO/중간 링크 열기 실패와 fd 고정, 중단 무시 synchronized 공급자의 두 번째 대기 후 반환·작업 종료 전 미폐기·종료 결과 미게시), full `FinalReviewFullTest` 5개(FIFO 원본 실패·슬롯 반환·중간 링크 거부, 자식이 먼저 종료한 고아 손자 회수, 작업 폴더 형식·소유권·권한 검사, 희소 PNG 17개로 합계 256 MiB 초과 거부·출력 FIFO 거부, 만료된 복사 시간 상한 거부). 기존 무한 루프 자식/손자 시간 초과·다음 실제 OCR 작업 회귀 테스트도 통과했다.

못 한 검증·남은 위험: 실제 Tesseract/한국어·영어 언어 데이터가 없어 실제 OCR 인식 비교는 수행하지 못했다. 실제 Qdrant·arm64·NAS/NFS/SMB·악성 코퍼스 퍼징·512 MiB cgroup 부하 검증도 미수행이다. 시간 검사는 일반 파일의 버퍼 읽기/쓰기 사이에서 수행하며 커널/원격 파일시스템 자체의 무기한 I/O 지연을 강제로 해소한다고 보장하지 않는다. 자식 회수는 Linux `/proc`·`setsid`를 전제로 하며 좀비는 실행 종료로 판정한다. 내장 모델의 기존 512 MiB 초과와 full UI·PaddleOCR 미완료는 유지한다.

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew build -q` (패치 적용 직후·보강 뒤 최종 실행) | 각각 0 | 최종 core 27·app 20·full 18, 합계 65개 테스트 실패·오류·생략 0, lite 클래스 경로 검사 통과 |
| `./gradlew -Pfull build -q` | 0 | full 구성 빌드·통합 테스트 통과 |
| `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` | 0 | lite 48문서·16 MiB 업로드/공유·REST/MCP·TOTP, 시작 최대 173,964 KiB·작업 최대 288,068 KiB(281.3 MiB), 서버·bootRun 종료와 포트 반환 |
| `VERIFY_PORT=8102 python3 scripts/verify-ml.py` | 0 | 실행 JAR ML 서버에서 한/영 리랭크·인증·경로 격리·OCR 자식 디코딩·상한/손상 입력 거부, 자식 잔존 없음·임시 폴더 정리, 최대 646,812 KiB(631.7 MiB) |
| `git diff --check` | 0 | 공백 오류 없음 |

이번 위임 실행에서 위 필수 검증의 실패는 없었다. 빌드/bootRun은 순차 실행했고 마지막 ML 검증이 full bootJar를 만들었으므로 현재 앱 JAR는 full 구성이다. 실제 OCR 실행 파일이 없어서 정상 이미지 디코딩 뒤 OCR 호출은 예상한 500 응답으로 검증했다(실제 인식 성공을 뜻하지 않는다).

상세 아티팩트(공개 커밋 제외): `build/verification/stage4-final/`의 빌드·런타임 로그, `test-summary.json`, 모듈별 `*-tests/`, `measurement.json`, `smoke.txt`, `ml-measurement.json`, `exit.txt`, `changed-files.txt`.

## 최종 확인 (기준 cd23e83)

검증일: 2026-10-24. 4단계 기본 구현과 이번 보안 잔여 수정은 완료했고, 아래의 미구현·미실측 항목은 별도로 남긴다.

공유 파일은 실제 전송할 fd의 `fstat` dev/ino를 기존 `fileKey` 형식과 대조하고 같은 fd로만 전송한다. 경로에서 식별자를 확인한 뒤 다른 일반 파일로 바뀌면 거부하며, fd 확인 뒤 교체되면 원래 inode만 전송한다. 폴더 공유는 `SecureFiles.openDirectory`로 연 루트 핸들의 식별자를 확인하고 그 핸들을 유지한 채 `openat`·NOFOLLOW로 하위 파일을 연다. 목록도 확인한 루트 핸들에서 NOFOLLOW 디렉터리 스트림을 열어 읽는다. 루트 교체 전후·일반 파일 교체 전후·하위 링크/FIFO 거부를 `ShareRaceTest` 5개로 검증한다.

OCR 종료 확인은 `/proc` 열거·stat 읽기·파싱 실패를 종료 미확인으로 처리한다. `/proc`가 정상적으로 존재하고 pid 디렉터리가 확실히 사라진 경우와 좀비/종료 상태만 종료로 본다. 미확인일 때는 회수자가 슬롯·작업 폴더를 계속 보유하고, 존재를 확인하지 못한 그룹에 반복 신호를 보내지 않는다. 가짜 proc 트리로 열거 실패·stat 누락/읽기 실패/손상·좀비·사라진 pid를 검사하고, 종료된 직접 자식도 그룹 열거에 실패하면 `kill`이 false를 반환하는 회귀 테스트를 추가했다.

### app 간헐 실패 조사

조사 중 첫 10회는 통과했다(보안 수정 작업을 병행한 탐색 실행). 이후 `./gradlew :app:cleanTest :app:test -q` 10회 중 8번째에서 app 25개 중 1개가 자연 재현됐다. 실패 테스트는 `ApplicationIntegrationTest.setupSessionTokensScopesAndMcpEndToEnd`, 실패 지점은 `verifyRemoteEmbedding`의 키워드 검색 단언(수정 전 526줄), 기대 1건·실제 0건이다. 해당 Gradle 종료 코드는 1이며 나머지 9회는 0이다. 새 실패 로그·XML은 `build/verification/final/app-final-08.log`, `app-final-08/app/TEST-com.bluesounds.nasquire.app.ApplicationIntegrationTest.xml`, 요약은 `reproduced-failure.txt`에 보존했다. 과거 TOTP 검증의 같은 증상과 일치한다(`historical-totp-review-build-run1-failed.log`).

원인은 같은 통합 테스트 안에서 소스 정의를 공유한 데 있다. `verifyUploadsShares`가 뒤의 임베딩 테스트에서도 사용하는 읽기 전용 소스의 `notes.md`를 잠시 제외했다가 복원한다. 100ms 주기 색인기가 이 제외 상태를 관찰하면 기존 문서를 지우며, 복원 후 다시 안정화·색인되기 전에 `verifyRemoteEmbedding`이 공급자를 remote로 바꾸면 재색인 필요 상태가 되어 일반 색인이 중지된다. 그 뒤 재색인 요청보다 먼저 수행하는 키워드 검색에서 0건이 나온다. 서버 포트는 기존부터 RANDOM_PORT/가짜 HTTP 포트 0을 사용한다.

수정은 공유 제외 정책 검사에 전용 소스·파일을 사용하도록 분리한다. 원래 검색/임베딩 소스의 정의를 바꾸지 않으며, 제외 정의를 실제 색인기에 한 번 적용해도 원래 검색 결과가 유지되는지 검사한다. 대기 시간을 늘리거나 실패 단언을 재시도하는 방식으로 덮지 않는다. 최종 수정본 10회 반복 결과는 아래 표에 기록한다.

### 수용한 한계·미실측

**NAS/NFS 일반 파일 I/O가 반환하지 않으면 복사·추출은 시간 제한을 보장하지 못한다.** 복사의 시간/중단 검사는 버퍼 읽기·쓰기 사이에 수행되므로 커널에서 반환하지 않는 일반 파일 열기·읽기·쓰기를 강제로 끝내지 못한다. 일반 텍스트 추출도 같은 한계다. 운영자는 soft/intr 마운트·타임아웃 설정을 권장하며, 옵션의 지원 여부와 실제 중단 동작은 사용하는 클라이언트/파일시스템에서 확인해야 한다. 이번 작업은 이 한계를 수용했으며 NAS 장애 실측을 완료했다는 뜻이 아니다.

실제 Tesseract/언어 데이터가 준비되지 않아 OCR 정확도·속도·RSS 비교는 미수행이다. 실제 Qdrant 서버·TLS·대규모 컬렉션, arm64, NAS/NFS/SMB 장애, 악성 PDF/이미지 코퍼스, 512 MiB cgroup 부하 검증도 미수행이다. PaddleOCR·원격 OCR 클라이언트·full 설정 UI는 미구현이고 내장 모델의 512 MiB 초과도 그대로다. `/proc` 접근 문제가 계속되면 OCR 슬롯을 계속 보유하므로 운영자가 접근 문제를 해소해야 한다.

### 검증 결과

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew :app:test --tests '*ShareRaceTest' :full:test --tests '*FinalReviewFullTest' -q` | 0 | 표적 회귀 app 5개·full 6개 통과. 이후 proc 루트 누락 검사와 재신호 조건도 보강하고 아래 최종 빌드에서 전체 재검증 |
| `./gradlew :app:cleanTest :app:test -q` (원인 수정 전 조사 10회) | 8번째 1, 나머지 0 | `verifyRemoteEmbedding` 키워드 검색 0건 실패를 보존. 첫 탐색 10회와 별도 실행 |
| `./gradlew :app:cleanTest :app:test -q` (원인 수정 후 10회) | 10회 모두 0 | 매회 app 25개, 합계 250개 테스트 실패·오류·생략 0 |
| `./gradlew build -q` | 0 | 최종 core 27·app 25·full 19, 합계 71개 테스트 결과 실패·오류·생략 0, lite 클래스 경로 검사 통과 |
| `./gradlew -Pfull build -q` | 0 | full 구성 빌드와 통합 테스트 통과, 최종 테스트 결과 동일 |
| `VERIFY_PORT=8101 python3 scripts/verify-runtime.py` | 0 | lite 48문서·16 MiB 업로드/공유·REST/MCP·TOTP. 시작 최대 174,160 KiB·작업 최대 288,636 KiB(281.9 MiB). 서버·bootRun 종료와 포트 반환 확인 |
| `git diff --check` | 0 | 공백 오류 없음 |

빌드와 bootRun은 순차 실행했다. 마지막 bootJar는 full 구성이고 런타임 검증은 lite bootRun이다. 필수 완료 기준은 모두 통과했다. 위 조사 중 실패 1회는 수정 전 증상이며 실패 근거를 삭제하지 않았다. 실제 OCR·외부 서비스·다른 아키텍처/파일시스템·cgroup 검증은 앞 절의 이유로 미수행이다.

상세 아티팩트(공개 커밋 제외): `build/verification/final/`의 `results.json`(원인 수정 전 조사·빌드), `stable-results.json`(수정 후 10회·최종 빌드·런타임의 명령/종료 코드/시간), `app-final-08.log`·`app-final-08/app/`·`reproduced-failure.txt`(자연 재현), `app-stable-01`~`app-stable-10`의 로그·XML, `stable-lite-build.log`·`stable-full-build.log`와 모듈별 XML, `test-summary.json`, `runtime.log`·`measurement.json`·`smoke.txt`·`bootrun.log`, `changed-files.txt`. 커밋 메시지는 저장소 루트 `.ai-commit-msg`에 작성했으며 git add/commit/push는 수행하지 않았다.
