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

외부 프로세스 메모리는 JVM 밖이다. 이미지 디코딩/PDF 렌더링 자체는 full JVM에서 수행하므로 **OCR full은 lite의 512 MiB 목표를 보장하지 않는다.** 쪽당 상한이며 파일 전체 시간은 쪽 수에 비례한다. 악의적인 PDF/이미지의 디코더 CPU 시간·JVM 메모리를 별도 프로세스로 격리하는 것은 남은 작업이다. TIFF 코덱·회전·손글씨·표 구조·실제 한국어 인식 품질도 검증하지 않았다.

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
