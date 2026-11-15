# 임베딩과 혼합 검색

기본 공급자는 `off`다. 이때 모델·네이티브 라이브러리를 로드하지 않으며 기존 BM25 키워드 검색을 사용한다. 관리 화면 **임베딩 설정**에서 공급자를 선택하고 저장 → 연결 시험 → 전체 소스 재색인 순서로 적용한다. 모델 또는 차원을 바꾸면 API와 화면에 `reindexRequired`가 나타난다. 재색인 전에는 `mode=keyword`로 기존 색인을 조회할 수 있으며 벡터·혼합 검색은 HTTP 409를 반환한다. 재색인 중에는 새 색인이 완성될 때까지 벡터·혼합 검색을 막는다. 오류나 안정화 대기가 남으면 완료 처리하지 않고 재시도한다. 실패한 공급자 설정을 다시 저장하면 진행 중 재색인을 중단하고 재색인 필요 상태로 남긴다. 수정 후 전체 재색인으로 다시 시작한다.

## 원격 공급자

OpenAI 호환 `POST {서버 baseUrl}/embeddings`를 호출하며 `input` 배열, `model`, `encoding_format=float`를 보낸다. 응답의 `index`로 순서를 복원하고 차원·유한값·영벡터를 검사하여 정규화한다. 최대 응답은 2 MiB이며 리다이렉트는 따르지 않는다. 요청 전체에 타임아웃을 적용하며 서버 오류 본문·인증키를 예외나 로그에 포함하지 않는다.

| 설정 | 기본값 / 범위 |
| --- | --- |
| `provider` | `off`, `builtin`(full만), `remote` |
| `servers` | 원격 서버 목록 1~8개: `name`(영문·숫자·`._-` 1~64자), `baseUrl`(예: `https://api.example.com/v1`), `apiKey`(저장할 때만 입력, 빈 값은 같은 이름·주소의 기존 키 유지), `timeoutSeconds`(요청 1회 상한, 1~300) |
| `model` | `embedding-small` (예시). 모든 서버가 같은 모델이어야 함 |
| `dimensions` | 384, 1~1024; 모든 서버의 실제 출력과 일치해야 함 |
| `batchSize` | 2, 1~16 |
| `timeoutSeconds` | 30, 1~300. 페일오버를 포함한 요청 전체 상한 |

### 원격 서버 여러 개 / Multiple remote servers

임베딩·리랭커는 같은 공통 원격 연결 계층(core `com.bluesounds.nasquire.core.remote`)을 쓴다. 기능별 설정은 서로 분리한다.

- **분배·페일오버**: 요청마다 round_robin으로 시작 서버를 고른다. 연결 실패·시간 초과·HTTP 5xx·429일 때만 다음 서버로 넘기고(서버당 1회, 기능 전체 `timeoutSeconds` 상한), 그 밖의 4xx·잘못된 응답은 즉시 실패한다. 내장 모델로 대체하지 않으며 모두 실패하면 실패를 반환한다(리랭커는 원래 순서 유지).
- **서킷 브레이커**: 연속 실패 3회면 그 서버를 30초 제외하고, 제외 시간이 지나면 시험 요청 1건만 보내 성공 시 복귀한다(실패하면 다시 제외).
- **모델·차원 확인**: 저장할 때 각 서버의 `GET /models`(지원 시)에 설정 모델이 있는지, 시험 임베딩 1회의 차원이 `dimensions`와 같은지 확인한다. 다르면 HTTP 400으로 거부하고 서버 이름·불일치 항목만 알린다. 연결할 수 없는 서버는 거부하지 않고 `down`으로 저장한다. 시작할 때는 같은 확인을 별도 스레드에서 하며 불일치면 기능만 끄고 `disabledReason`에 표시한다.
- **상태**: `GET /api/health`(관리자)와 설정 화면에 기능별·서버별 이름, `unknown | up | down | open`, 최근 오류 종류를 표시한다. 주소·키는 넣지 않는다.
- **응답 헤더**: `GET /api/search`는 처리한 서버 이름을 `X-Nasquire-Embedding-Server`·`X-Nasquire-Rerank-Server`로, 앞선 서버 실패로 다른 서버가 처리했는지를 `X-Nasquire-Failover: true|false`로 알린다. 리랭크 요청에는 `X-Nasquire-Rerank: applied|original-order`를 붙인다. 주소는 노출하지 않는다.
- **이관**: 이전 단일 서버 설정(`baseUrl`·`encryptedKey`)은 시작할 때 `default` 서버 하나로 읽고, 다음 저장 때 `server.N.*` 형식으로 쓴다. 키를 다시 입력할 필요가 없고, 기존 색인의 모델 ID(`remote:주소/embeddings:모델`)를 모델을 바꿀 때까지 유지하므로 재색인하지 않는다. 새로 설정하는 원격 모델 ID는 주소와 무관한 `remote:모델`이라 서버를 추가·교체해도 재색인이 필요 없다. 이전 API 입력(`baseUrl`·`apiKey`)은 첫 서버를 바꾸는 입력으로 계속 받는다.

원격 공급자를 사용하면 **문서 조각과 검색어가 설정한 서버로 전송된다. 외부 클라우드 주소라면 문서 내용이 외부로 전송됨**을 관리 화면에 경고한다. 신뢰하는 서버를 사용하고 외부 연결에는 HTTPS를 사용한다. 루프백(`localhost`, `127.0.0.0/8`, `[::1]`)이 아닌 평문 `http://` 주소(원격 임베딩·Qdrant)는 `allowInsecureHttp=true`를 명시해야 저장되며 화면에 경고한다. 이전 버전에서 저장한 비루프백 평문 설정은 시작은 하되 벡터 기능을 끄고 `disabledReason`으로 알린다. 서버 이름·주소를 바꾸면 새 키를 함께 입력하지 않는 한 저장된 키를 지우며, `DELETE /api/embeddings/key[?server=이름]`·`/api/embeddings/qdrant-key`(관리자 세션+CSRF)로 키를 삭제할 수 있다. 서버 키는 AES-GCM v2 + AAD `embedding`으로 `server.N.encryptedKey`에 저장한다. 키는 응답에 반환하지 않는다. `embedding.properties`에는 AES-256-GCM 암호문을, `embedding.key`에는 로컬 암호화 키를 저장한다. POSIX에서는 두 파일을 0600으로 제한한다. 두 파일을 포함한 전체 데이터 디렉터리를 함께 백업해야 한다. 같은 파일에 접근할 수 있는 운영자에 대한 암호화 장벽은 아니다.

## 내장 공급자

4단계 모듈 분리 이후 내장 공급자는 `./gradlew -Pfull build -q`로 만든 full 앱에서만 제공한다. 기본 lite 앱에는 ONNX Runtime·DJL·네이티브 모델 구현을 포함하지 않으며 선택하면 거부한다. 원격 임베딩은 lite에서도 사용할 수 있다. 내장 공급자를 끄고 lite로 전환한다. full 선택 빌드와 OCR/리랭커의 현재 범위는 [4단계 검증](verification/full.md)을 참고한다.

ONNX Runtime Java CPU로 int8 `multilingual-e5-small`을 실행한다. 모델 파일은 저장소·컨테이너 이미지에 포함하지 않는다. 모델 다운로드도 기본으로 꺼져 있다. 사전 준비한 파일을 읽거나 `download=true`를 명시하면 첫 연결 시험·임베딩 때 HTTPS로 내려받아 SHA-256을 확인한다. 기존 파일도 로드 전에 해시를 검사한다. 다운로드는 임시 파일 → 해시 검사 → 원자적 이동으로 게시하며 파일별 256 MiB 상한을 둔다. **자동 다운로드 대상은 데이터 디렉터리 안이어야 한다.** 외부 경로에 준비한 모델은 읽기만 한다. 원본 소스 볼륨은 항상 읽기 전용으로 마운트한다.

| 설정 | 기본값 |
| --- | --- |
| `modelPath` | `/data/models/multilingual-e5-small/model-int8.onnx` |
| `tokenizerPath` | `/data/models/multilingual-e5-small/tokenizer.json` |
| `download` | `false` |
| `modelUrl` | 아래 고정 리비전의 `onnx/model_quantized.onnx` |
| `tokenizerUrl` | 같은 리비전의 `tokenizer.json` |
| `modelSha256` | `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193` |
| `tokenizerSha256` | `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39` |

- 고정 리비전: [Xenova/multilingual-e5-small, 761b726d](https://huggingface.co/Xenova/multilingual-e5-small/tree/761b726dd34fb83930e26aab4e9ac3899aa1fa78). 가중치 118,308,185바이트, 토크나이저 17,082,730바이트. [상위 intfloat 모델](https://huggingface.co/intfloat/multilingual-e5-small)은 MIT다. ONNX 변환본은 해당 모델을 출처로 명시하며 별도 비상업 조건을 추가하지 않는다. 사용자 지정 파일도 MIT 또는 Apache-2.0 모델·토크나이저만 사용한다.
- 토크나이저는 Apache-2.0인 DJL Hugging Face Tokenizers/Rust tokenizers다. E5의 `passage: ` / `query: ` 접두사, 최대 256토큰 자르기, attention mask 평균 풀링, L2 정규화를 적용한다. 임베딩은 384차원이다. 다른 임의 ONNX 구조는 지원하지 않는다.
- ONNX Runtime은 MIT다. 사용한 `onnxruntime:1.31.0` JAR에 Linux `linux-x64`와 `linux-aarch64` JNI·공유 라이브러리가, `tokenizers:0.39.0` JAR에 `linux-x86_64`와 `linux-aarch64` CPU 라이브러리가 모두 포함됨을 확인했다. Linux amd64에서 실제 실행했으며 **arm64 실행은 아직 검증하지 않았다.** glibc 기반 이미지를 사용한다.
- Docker는 `DJL_CACHE_DIR=/data/models/.djl-cache`에 토크나이저 네이티브 파일을 추출한다. 직접 JVM을 실행할 때도 쓰기 가능한 DJL 캐시 경로를 지정한다. ONNX 네이티브 라이브러리는 JVM 임시 폴더를 사용한다. Java 25에서는 `--enable-native-access=ALL-UNNAMED`를 사용한다.
- 한 파일씩·한 조각씩 내장 추론하며 ONNX intra/inter-op 스레드 각각 1, CPU arena·memory pattern은 끈다. ONNX 그래프 최적화를 끄고 모델 세션을 먼저 로드한다. 추론은 색인과 검색이 직렬화된다. Docker와 실측 스크립트는 `MALLOC_ARENA_MAX=2`, `TOKENIZERS_PARALLELISM=false`도 설정한다.

## API와 색인

관리자 세션·CSRF가 필요한 API:

| 메서드·경로 | 역할 |
| --- | --- |
| `GET /api/embeddings` | 키를 제외한 설정, `servers`(이름·주소·`hasKey`·상태), 현재/색인 모델 ID·차원, 재색인 필요·진행 상태 |
| `PUT /api/embeddings` | 설정 저장 (`{"provider":"remote","model":"...","servers":[{"name":"a","baseUrl":"https://a.example.com/v1","apiKey":"..."}], ...}`). `servers`는 목록 전체 교체 |
| `POST /api/embeddings/test[?server=이름]` | 저장된 공급자로 임베딩 1회(성공·차원) 또는 지정 서버의 모델·차원 확인(`ok·down·mismatch·error`) |
| `GET /api/health` | 임베딩·리랭커·OCR·Docling의 기능별·서버별 상태(주소·키 없음) |
| `POST /api/embeddings/reindex` | 전체 소스의 벡터 필드·모델 메타데이터 재구축 예약 |

초기 설정은 `nasquire.embedding.<위 설정 이름>` Spring 속성으로도 지정한다. 예: `nasquire.embedding.provider=remote`, `nasquire.embedding.baseUrl=https://api.example.com/v1`(이전 단일 서버 형식, `default` 서버로 이관). 서버 여러 개는 관리 화면·API로 설정한다. 관리 화면에서 저장한 설정이 다음 실행에서도 우선한다. API 키는 명령줄 인자에 쓰지 말고 비밀 설정 파일로 전달한다.

`GET /api/search?query=...&mode=keyword|vector|hybrid` 및 MCP `search_documents(query, source?, limit?, mode?)`가 같은 검색 구현을 사용한다. 생략/빈 mode는 공급자 없으면 keyword, 공급자 있으면 hybrid다. RRF는 각 검색의 상위 100조각을 가져와 `1/(60+순위)`를 합산한다. 결과 수는 1~100이다. 문서 경로·쪽·조각 offset을 식별자로 사용해 같은 파일의 다른 조각을 유지한다. BM25·KNN 모두 토큰의 소스 범위를 적용한 뒤 검색한다.

Lucene의 같은 문서에 `KnnFloatVectorField`(cosine, HNSW)를 추가한다. commit user data에 `embedding.model`, `embedding.dimensions`, `embedding.incomplete`를 저장한다. 모델 ID에는 원격 모델 이름(이관한 단일 서버 설정은 endpoint 포함) 또는 내장 모델/토크나이저 SHA-256·풀링 버전이 들어간다. 같은 원격 모델 이름으로 서버 내부 가중치가 바뀌는 경우는 자동 감지할 수 없으므로 관리자가 재색인해야 한다. 차원 변경 시 전체 `deleteAll`로 필드 스키마도 재설정한다. 재색인이 중단되면 incomplete 상태를 읽어 벡터 검색을 차단하며 전체 재색인으로 복구한다.

메모리 실측·512 MiB 목표 충족 여부와 재현 명령은 [2단계 검증 기록](verification/vector.md)에 남긴다.

## 선택 Qdrant 벡터 저장소

기본 벡터 저장소는 로컬 Lucene HNSW다. 임베딩 설정의 `vectorStore=qdrant`를 선택하면 운영자가 준비한 Qdrant의 REST API(`qdrantUrl`, `qdrantCollection`)에 벡터를 저장한다. API 키는 `qdrantApiKey`로 저장하며 `embedding.key`의 AES-GCM v2 + AAD `qdrant`로 암호화하고 응답에는 `hasQdrantKey`만 표시한다. 저장소·컬렉션을 바꾸면 모델·차원 변경과 같이 전체 재색인 전 벡터 검색을 막는다. Qdrant에는 벡터와 소스 ID·문서 key·offset만 보내고 본문은 로컬 색인에 둔다. 검색 범위는 Qdrant 필터와 앱 재검사로 이중 적용한다. 상세는 [4단계 검증](verification/full.md)을 참고한다.

## ML 전용 서버를 원격 공급자로 사용

full 빌드의 ML 전용 모드는 OpenAI 호환 `POST /v1/embeddings`와 `POST /v1/rerank`를 제공한다. 일반 Nasquire의 원격 임베딩 서버와 원격 리랭커 서버 주소에 `https://ml.example.com/v1`처럼 지정하고, API 키에 ML 서버 토큰을 넣는다. 모델 이름은 ML 서버의 `nasquire.ml.embedding-model`(기본 `multilingual-e5-small`, 384차원)·`nasquire.ml.reranker-model`과 같아야 한다. OpenAI 형식에는 질의/문서 구분이 없어 ML 서버는 입력을 문서(`passage:`)로 임베딩한다. 확장 필드 `input_type=query`를 보내는 클라이언트만 질의 접두사를 사용한다.

### ML remote 모드(게이트웨이) / ML remote mode

기능별 방식은 임베딩·리랭커 `off | builtin | remote`, OCR `off | tesseract | remote`, Docling `off | remote`다. `remote`이면 ML 서버가 원격 공급자 앞의 게이트웨이가 된다. 클라이언트는 ML 서버 주소·토큰 하나만 알고, 공급자 주소·키는 ML 서버 밖으로 나가지 않는다(응답·오류·로그·`/v1/models`·`/v1/status`에는 서버 이름만). 원격 서버 묶음은 일반 Nasquire와 같은 core 원격 계층(서버 1~8개, round_robin, 연결 실패·시간 초과·5xx·429만 다음 서버로·서버당 1회·기능 전체 시간 상한, 4xx 즉시 실패, 서킷 브레이커 연속 3회·30초)이며 내장 기능으로 대체하지 않는다.

- 설정: 기존 ML 설정과 같이 설정 파일(`nasquire.ml.config`)·환경변수로 받는다. 기능 `<f>`는 `embedding`·`reranker`·`ocr`·`docling`.
  - `nasquire.ml.<f>-servers=이름,...`(환경변수 `NASQUIRE_ML_<F>_SERVERS`), 서버별 `nasquire.ml.<f>-server.<이름>.url`·`.key`·`.key-file`·`.timeout-seconds`(환경변수 `NASQUIRE_ML_<F>_SERVER_<이름>_URL`·`_KEY`·`_KEY_FILE`·`_TIMEOUT_SECONDS`). 환경변수로 줄 서버 이름은 영문 소문자·숫자로 한다.
  - 키는 설정 파일·환경변수의 `key` 또는 `key-file`(파일 내용, 앞뒤 공백 제거) 중 하나만. 명령행의 `...key=`·토큰은 거부하며 `key-file` 경로는 명령행에서도 받는다. 키가 없으면 인증 헤더를 보내지 않는다.
  - 비루프백 평문 http는 기능별 `nasquire.ml.<f>-allow-insecure-http=true`(`NASQUIRE_ML_<F>_ALLOW_INSECURE_HTTP`)일 때만 허용한다. 같은 클러스터 안 서비스 주소처럼 평문 내부망을 쓰는 기능에만 켠다.
  - 임베딩: `embedding-model`(원격 모델 이름, 필수)·`embedding-dimensions`(필수, 1~8192)·`embedding-timeout-seconds`(30, 최대 300). 리랭커: `reranker-model`(필수)·`rerank-timeout-seconds`(30, 최대 120). OCR: `ocr-engine`(기본 `tesseract`)·`ocr-timeout-seconds`(원격 60, 최대 300). Docling: `docling-timeout-seconds`(300, 최대 600). 서버별 `timeout-seconds`의 기본값은 기능 전체 상한(최대 300)이다.
- 중계: 임베딩은 OpenAI 호환 `POST {url}/embeddings`에 설정된 원격 모델 이름으로 입력을 그대로 보내고 벡터를 정규화 없이 돌려준다(차원·유한값만 검사, `input_type`은 보내지 않음). 리랭크는 `POST {url}/rerank`(`{model, query, documents, top_n}`)를 보내고 공급자 응답(Cohere·Infinity·Jina의 `results[{index, relevance_score}]`, `data[...]`, TEI의 최상위 `[{index, score}]`)을 ML 서버 `/v1/rerank` 형식 `{model, results[{index, relevance_score}]}`(점수 내림차순·`top_n`)으로 통일한다. OCR은 이미지를 격리 자식 프로세스에서 검사·디코딩한 뒤 PNG만 `POST {url}/ocr`로 보낸다(Nasquire ML 서버 호환). Docling은 아래 문서 추출 경로의 표·그림 의심 쪽에만 쓴다.
- 실패: 다음 서버로 넘길 수 있는 실패가 모두 소진되면 503(`Retry-After: 1`), 4xx·잘못된 응답은 502. 공급자 상태·본문·주소는 전달하지 않는다.
- 응답 헤더: `X-Nasquire-Embedding-Server`·`X-Nasquire-Rerank-Server`·`X-Nasquire-Ocr-Server`·`X-Nasquire-Docling-Server`(처리 서버 이름)·`X-Nasquire-Failover`(true/false).
- `GET /v1/models`: `data[]`의 `id`에 기능별 실제 이름(임베딩 모델·리랭커 모델·OCR 엔진·`docling-serve`)과 `feature`·`mode`, 임베딩 `dimensions`, Docling `version`(시작 확인에서 얻은 경우)을 보고한다. `GET /v1/status`: 기능별 방식·모델과 원격 서버별 `name`·`state`(unknown·up·down·open)·`lastError`·`consecutiveFailures`, Docling은 문서 추출에서 보낸 쪽 누적 `pages`(`converted`·`failed`·`skipped`). 둘 다 Bearer가 필요하다.
- 시작 확인: 서버마다 `GET /models`(지원 시)의 모델 이름과 시험 호출 1건으로 모델·차원(임베딩)·모델(리랭커)·엔진(OCR)을, Docling은 `/version`과 작은 변환 1건으로 버전을 확인한다. 불일치가 하나라도 있으면 시작을 거부하고, 연결 불가·확인 불가는 서버 이름·실패 종류만 경고로 남기고 시작한다.

```properties
# ml.properties 예시 (주소는 예시 값)
nasquire.ml.token-file=/run/secrets/ml-token
nasquire.ml.embedding=remote
nasquire.ml.embedding-model=multilingual-e5-large
nasquire.ml.embedding-dimensions=1024
nasquire.ml.embedding-servers=gpu1,gpu2
nasquire.ml.embedding-server.gpu1.url=https://embed1.example.com/v1
nasquire.ml.embedding-server.gpu1.key-file=/run/secrets/embed-key
nasquire.ml.embedding-server.gpu2.url=https://embed2.example.com/v1
nasquire.ml.embedding-server.gpu2.key-file=/run/secrets/embed-key
nasquire.ml.reranker=remote
nasquire.ml.reranker-model=bge-reranker-v2-m3
nasquire.ml.reranker-servers=infinity
nasquire.ml.reranker-server.infinity.url=http://infinity.example.com:7997
nasquire.ml.reranker-allow-insecure-http=true
nasquire.ml.ocr=tesseract
nasquire.ml.docling=remote
nasquire.ml.docling-servers=docling
nasquire.ml.docling-server.docling.url=https://docling.example.com
nasquire.ml.docling-server.docling.key-file=/run/secrets/docling-key
```

### 문서 추출 / Document extraction (`/v1/extract`, `PUT /process`)

`/v1/ocr`·`/v1/extract`·`PUT /process`는 같은 문서 처리 파이프라인(full `MlPipeline`)을 탄다.

- 형식별 경로: HWP/HWPX·Office(docx·doc·pptx·ppt·xlsx·xls)·텍스트(txt·md)는 색인기와 같은 기존 추출기(원본 표 구조, Docling 미사용), PDF 글자 층 쪽은 PDFBox(Office/HWP 추출과 PDF 글자 층 추출·쪽 판단은 OCR과 같은 방식의 격리 자식 프로세스에서 한다. 텍스트만 서버 JVM에서 처리), 글자 없는 스캔 쪽·이미지(png·jpg·tif·bmp·gif)는 ML 설정의 OCR(`tesseract|remote`, 원본 열기·렌더링·디코딩은 일반 소스와 같은 격리 자식 프로세스), 표·그림 의심 쪽은 Docling(`nasquire.ml.docling=remote`일 때만). 의심 쪽 판단·변환·교체/보강 규칙은 아래 "표·그림 의심 쪽 판단"과 같다(기준값은 기본값, 문서당 Docling 쪽 수는 `nasquire.ml.docling-max-pages`, 기본 10·최대 100).
- Docling 실패·혼잡: 요청 단위 재시도 없이 1차 결과로 응답하고 `metadata.docling`에 표시한다(`off` 미사용, `none` 의심 쪽 없음, `converted`, `partial`, `failed` 4xx 등, `unavailable` 혼잡·연결 실패·5xx·429). 쪽별 결과는 `metadata.doclingPages`(`converted`·`supplemented`·`failed`·`retry`·`skipped`).
- 형식 판별: `X-Filename` 확장자가 지원 형식이면 그것, 아니면 `Content-Type`(예: `application/pdf`, `image/png`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/x-hwp`, `application/hwp+zip`, `text/plain`). 둘 다 아니면 415. `X-Filename`은 퍼센트 인코딩된 UTF-8 이름이며(`+`는 공백으로 바꾸지 않음) 경로 부분은 버리고 NFC로 정규화한다. 잘못된 인코딩은 400.
- 상한: 본문 PDF 32 MiB·이미지 16 MiB(기존 `/v1/ocr`)·Office/HWP 8 MiB·텍스트 8 MiB(초과 413, `Content-Length`가 넘으면 본문을 받기 전에 거절), 추출 글자 200만 자·OCR 쪽 `nasquire.ml.ocr-max-pages`(기본 20, 최대 100)·OCR 픽셀 6M(초과 413). 413은 추출기·격리 자식의 상한 위반 종류(`ExtractionLimit`)로만 판단하며 손상·암호·잘못된 이미지는 400이다.
- 시간: 요청 하나에 단일 마감 `nasquire.ml.document-timeout-seconds`(기본 120, 최대 600, 본문 수신 뒤 처리 시작부터)를 둔다. 격리 자식(추출·렌더링, 격리 자식 자체 상한 300초 이하)·OCR 공급자(Tesseract 쪽 대기·원격 OCR 서버 묶음)·Docling(서버 묶음 전체·서버당 상한과 `document_timeout`)에는 남은 시간만 넘긴다. 추출·OCR이 마감을 넘기면 격리 자식과 자손을 강제 종료하고 종료를 확인한 뒤 슬롯을 돌려주며 504로 응답한다. Docling 단계가 마감에 닿으면 남은 쪽을 보내지 않고 1차 결과(`metadata.docling`=`unavailable`, 쪽별 `retry`)로 응답한다. 색인기의 Docling·OCR 시간 상한은 바뀌지 않는다(쪽마다 기존 상한). 동시 처리는 `/v1/ocr`을 포함해 문서 1건이며 처리 중이면 본문을 받기 전에 기다리지 않고 503(`Retry-After: 1`), 본문 수신 실패도 슬롯을 돌려준다. 본문은 데이터 폴더 `ml-tmp`(0700)의 임시 파일(0600)로 받고 처리 뒤 지운다(강제 종료로 남은 24시간 지난 파일은 시작 때 정리). 파일 이름·본문은 로그에 남기지 않는다.
- `/v1/extract`·`/process`는 OCR·Docling이 꺼져 있어도 PDF 글자 층·Office·텍스트를 처리한다(ML 서버 자체는 기능 하나 이상이 켜져 있어야 시작한다). 이미지는 OCR이 꺼졌으면 404, `layout=force`는 Docling이 연결되지 않았으면 400.

`POST /v1/extract?layout=auto|off|force` — 쪽 단위 추출. `auto`(기본)는 의심 쪽만 Docling, `off`는 Docling을 쓰지 않음, `force`는 PDF·단일 이미지의 모든 쪽을 Docling으로(문서당 쪽 수 상한 안). Office·텍스트는 `force`여도 기존 추출기다.

```bash
curl -sS -X POST 'https://ml.example.com/v1/extract?layout=auto' \
  -H "Authorization: Bearer $(cat /run/secrets/ml-token)" \
  -H 'Content-Type: application/pdf' -H 'X-Filename: %EB%B3%B4%EA%B3%A0%EC%84%9C.pdf' \
  --data-binary @report.pdf
```

```json
{"pages":[{"page":1,"text":"...","source":"pdfbox"},{"page":2,"text":"| 항목 | 금액 |\n|---|---|\n...","source":"docling"},{"page":3,"text":"...","source":"ocr"}],
 "metadata":{"type":"pdf","pages":3,"layout":"auto","docling":"converted","doclingPages":{"2":"converted"},"filename":"보고서.pdf"}}
```

`source`는 `pdfbox`·`ocr`·`docling`·`extractor`(Office·HWP·텍스트)다. 쪽은 PDF·이미지는 쪽(프레임), 슬라이드 형식은 슬라이드, 시트 형식은 시트 순서이며 그 밖의 문서는 한 쪽이다.

`PUT /process` — Open WebUI `external` 문서 추출 규격(layout=auto). 요청은 `Authorization: Bearer <ML 토큰>`, `Content-Type`(파일 MIME), `X-Filename`(퍼센트 인코딩된 원본 이름), 본문=파일 바이트. 응답은 쪽별 목록이며 빈 쪽은 뺀다.

```json
[{"page_content":"...","metadata":{"source":"보고서.pdf","page":0,"page_number":1,"total_pages":3,"extraction":"pdfbox","type":"pdf","docling":"converted"}},
 {"page_content":"...","metadata":{"source":"보고서.pdf","page":1,"page_number":2,"total_pages":3,"extraction":"docling","type":"pdf","docling":"converted"}}]
```

- 근거: Open WebUI `backend/open_webui/retrieval/loaders/external_document.py`(`ExternalDocumentLoader`)는 파일 바이트를 `PUT {url}/process`로 보내며 헤더는 `Content-Type`(MIME), `Authorization: Bearer <키>`, `X-Filename`(`urllib.parse.quote`한 파일 이름)이다. 응답이 객체면 문서 하나, 목록이면 항목마다 `page_content`·`metadata`로 문서를 만든다. 빈 응답·오류 상태는 실패로 처리한다.
- `metadata.page`는 0부터다. Open WebUI 인용 화면은 정수 `metadata.page`에 1을 더해 "page N"으로 표시하고 PDF 링크에 `#page=page+1`을 붙인다(LangChain PDF 로더와 같은 관례). 1부터 센 번호는 `page_number`다. 쪽이 없는 형식(DOCX·HWP·텍스트)에는 `page`를 넣지 않고, 슬라이드·시트처럼 여러 항목이면 `part`(1부터)를 넣는다. 메타데이터 값은 벡터 DB에 저장할 수 있게 단순 값만 쓴다. Open WebUI는 `source`·`name` 등을 자기 값으로 덧붙일 수 있다.
- Open WebUI 연결 예: 관리자 설정 → 문서(Documents) → 콘텐츠 추출 엔진(Content Extraction Engine) `External`, 주소 `https://ml.example.com`(끝에 `/process`를 붙이지 않는다), API 키 = ML 토큰. Open WebUI가 사용자 정보 헤더를 함께 보내도 ML 서버는 무시한다. Open WebUI의 문서 추출 엔진은 하나만 고를 수 있으므로 PDFBox·OCR·Docling 분기는 ML 서버 안에서 한다.
- `/v1/ocr`(이미지 바이트 → `{"text"}`)는 그대로 호환한다. 내부는 같은 파이프라인의 이미지 경로(같은 슬롯·상한·격리 자식)이며 Docling을 쓰지 않는다. 단어 위치(hOCR/TSV) 출력은 제공하지 않는다(ROADMAP 보류 후보).

## 원격 리랭커 / Remote reranker

리랭커는 검색 설정(`GET/PUT /api/reranker`, 관리자 세션+헤더 CSRF)으로 옮겼으며 원격은 lite·full 모두에서 쓸 수 있다. `reranker=off|remote|builtin`(builtin은 full의 로컬 ONNX만), `model`, `topN`(20, 1~100), `timeoutSeconds`(5, 1~30, 페일오버 포함 전체 상한), `servers`, `allowInsecureHttp`. 일반 `POST {서버 baseUrl}/rerank`(Cohere·Jina·Infinity 호환: `{model, query, documents, top_n}` → `results[{index, relevance_score}]`)를 호출하며 소스 범위를 적용한 상위 `topN`만 보내고 응답 64 KiB·동시 1건 상한을 둔다. 혼잡·실패하면 원래 순서를 유지한다. 저장 때 `GET /models`(지원 시)의 모델과 시험 리랭크 1회로 확인한다. 키는 `reranker.properties`(0600)에 AES-GCM v2 + AAD `reranker`로 저장하고 `DELETE /api/reranker/key[?server=이름]`으로 지운다. 서버 연결 시험은 `POST /api/reranker/test?server=이름`. 이전 `full.properties`의 리랭커 설정은 첫 시작 때 암호문 그대로 `reranker.properties`의 `default` 서버로 옮기며, 다음 OCR 설정 저장 때 이전 항목을 지운다. `/api/full`은 OCR만 받는다.

## 원격 OCR / Remote OCR

OCR은 일반 설정(`GET/PUT /api/ocr`, 관리자 세션+헤더 CSRF)이다. `ocr=off|remote|tesseract`(tesseract는 full의 로컬 Tesseract만) 중 하나만 쓰며 서로 대체하지 않는다. lite에서도 `remote`를 쓸 수 있다. 쪽 렌더링·이미지 디코딩은 두 경우 모두 core의 격리 자식 프로세스(`core.ocr.OcrSandbox`·`OcrWorker`)가 맡고, 공급자에는 PNG만 넘긴다. 원본 파일과 파일 이름은 원격 서버로 보내지 않는다.

- 원격은 Nasquire ML 서버 호환 `POST {서버 baseUrl}/ocr`(예: `https://ml.example.com/v1`)이다. 본문은 이미지 바이트, `Content-Type`은 이미지 형식, 키는 `Authorization: Bearer`, 응답은 `{"text": "..."}`다.
- 설정: `engine`(기본 `tesseract`, 저장 시 `GET /models`(지원 시)에 있는지 확인), `maxPages`(파일당 20쪽, 1~100), `timeoutSeconds`(쪽 하나의 페일오버 포함 전체 상한 60초, 1~300), `servers`, `allowInsecureHttp`. 서버 묶음·round_robin·페일오버(연결 실패·시간 초과·5xx·429)·4xx 즉시 실패·서킷 브레이커·평문 http 명시 허용은 임베딩과 같다.
- `ocr`·`engine`·`maxPages`가 바뀌면 전체 소스를 재색인한다. 서버 목록·키·시간 상한만 바뀌면 재색인하지 않는다.
- 키는 `ocr.properties`(0600)에 AES-GCM v2 + AAD `ocr`로 저장하고 `DELETE /api/ocr/key[?server=이름]`으로 지운다. 서버 연결 시험은 `POST /api/ocr/test?server=이름`(작은 흰 PNG 1건).
- 이관: 이전 `full.properties`의 `ocr`·`ocrMaxPages`는 첫 시작 때 `ocr.properties`의 `ocr`·`maxPages`로 옮기고 다음 full 설정 저장 때 지운다. 이전 `PUT /api/full`의 `ocr`·`ocrMaxPages` 입력도 계속 받아 OCR 설정으로 넘긴다. `/api/full`에는 로컬 Tesseract 실행 제한(`ocrTimeoutSeconds`·`ocrMemoryMiB`)만 남는다. lite에서 `tesseract`가 저장돼 있으면 시작을 거부하므로 full에서 OCR을 끄거나 원격으로 바꾼 뒤 전환한다.
- 원격 OCR이 켜져 있으면 lite의 `/api/supported-extensions`에도 이미지 형식이 들어간다.

## 원격 Docling / Remote Docling

`GET/PUT /api/docling`(관리자 세션+헤더 CSRF)으로 docling-serve(기준 1.38.2) 서버 묶음을 연결한다. `docling=off|remote`, lite·full 공통. 연결되면 색인기가 PDF·단일 이미지의 **표·그림 의심 쪽만** Docling으로 보낸다(아래 "표·그림 의심 쪽 판단"). 꺼져 있거나 비활성이면 판단 코드가 돌지 않아 비용이 없다.

- 요청: 동기 `POST {서버 주소}/v1/convert/file`(서버 주소는 `/v1` 없는 루트, 예: `https://docling.example.com`), multipart `files`(원본 이름 대신 `document.<확장자>`), `to_formats=md`(선택 `json`), `target_type=inbody`, `image_export_mode=placeholder`, `include_images=false`, `abort_on_error=false`, `document_timeout`(요청 남은 시간). 쪽 범위는 `page_range`(1부터, 양 끝 포함) 두 값으로 보내며 core `RemoteDoclingClient.convert(내용, 이름, PageRange, json)`이 제공한다. 결과는 `document.md_content`(선택 `json_content`)·`status`·오류 수만 읽는다.
- 인증은 `X-Api-Key` 헤더다(Bearer 아님). 키는 `docling.properties`(0600)에 AES-GCM v2 + AAD `docling`으로 저장하고 `DELETE /api/docling/key[?server=이름]`으로 지운다.
- 상한: `timeoutSeconds`(페일오버 포함 전체 300초 = docling-serve 기본 동기 대기 상한, 1~600), 서버 하나는 최대 300초, `maxFileMiB`(32, 1~64 = docling-serve 기본 업로드 상한), 응답 32 MiB, 동시 변환 1건.
- 연결 시험(`POST /api/docling/test?server=이름`)은 `GET /version`의 docling-serve 버전(서버 사이 불일치면 거부)과 작은 Markdown 변환 1건을 확인한다. 서버 묶음·페일오버·서킷 브레이커·평문 http 규칙은 임베딩과 같다.

### 표·그림 의심 쪽 판단 / Table and figure page detection

- 조건: Docling이 연결(`remote`, 비활성 아님)됐을 때만 색인기가 파일마다 판단 객체(core `layout.LayoutCheck`)를 만들어 추출기에 넘긴다. 연결되지 않으면 null을 넘겨 신호 측정·전송을 하지 않는다. 대상은 PDF와 단일 프레임 이미지(png·jpg·tif·bmp, OCR이 켜져 있을 때)이며 HWP/HWPX·Office·텍스트는 원본 표 구조나 원문을 쓰므로 대상이 아니다.
- 글자 층 PDF 쪽(PDFBox, 렌더링 없음, 같은 문서 열기 안): ① 벡터 격자 — 그은 선분·사각형 변과 칠한 얇은 사각형(3pt 이하) 중 가로·세로인 것을 위치(2pt)별로 모아 누적 길이 36pt 이상인 위치 수를 센다. 가로·세로 모두 `minGridLines`(기본 3) 이상이면 `grid hN vM`. ② 글자 열 정렬 — 같은 기준선의 글자를 한 줄로 묶어 글자 크기 1.5배를 넘는 간격에서 나누고, 둘 이상으로 나뉜 줄의 조각 시작 x(3pt 단위)에 `minRows`(기본 4)줄 이상 정렬된 열이 `minColumns`(기본 3)개 이상이면 `columns N rows M`(선 없는 표). 일반 문단·2단 편집은 걸리지 않는다. ③ 큰 이미지 — 가장 큰 이미지가 쪽 면적의 `minImagePercent`(기본 40, 0=끔)% 이상이면 `image N%`. 쪽의 90% 이상을 덮는 이미지(스캔 배경·검색 가능 PDF)는 그림으로 보지 않는다.
- 스캔 쪽(OCR 대상): OCR 격리 자식이 이미 렌더링한 래스터에서 가로(너비 1/5 이상)·세로(높이 1/12 이상) 직선을 세어(끊김 2px 허용, 붙은 행·열은 하나) 둘 다 `minGridLines` 이상이면 `raster hN vM`. 부모 JVM은 자식이 쓴 숫자만 읽는다. 현재 원격 `/ocr`·로컬 Tesseract는 글자만 돌려주므로 단어 위치 정렬은 쓰지 않는다(위치 출력은 3차 검토). OCR이 꺼져 있으면 글자 없는 쪽은 판단하지 않는다.
- 소스별 "항상 Docling"(`alwaysDocling`, 소스 설정 체크박스, 기존 소스는 false): 신호를 재지 않고 모든 쪽을 대상으로 한다. 옵션만 바꾸면 기존 결과를 지우지 않고 그 소스를 재색인한다. API에서 값을 생략하면 기존 소스는 저장값을 유지한다.
- 전송: 의심 쪽만 `page_range`로 한 쪽씩 보낸다(쪽별 결과를 정확히 나누기 위해 범위를 합치지 않는다). 파일당 `maxPages`(기본 10, 1~100)쪽까지이며 넘는 쪽과 `maxFileMiB`를 넘는 파일은 1차 결과를 유지한다(건너뜀). 이미지 파일은 쪽 범위 없이 보낸다.
- 반영: Docling Markdown의 글자(공백 제외) 수가 1차 결과의 절반 이상이면 그 쪽의 1차 결과를 **교체**하고, 더 짧으면 1차 결과를 남긴 채 Docling 결과를 **보강**으로 덧붙인다(글자 누락 방지). 근거는 `location.type=docling`과 쪽 번호이고 `<!-- image -->` 자리표시는 지운다. 반영 뒤 글자 합계가 추출 문자 상한을 넘는 쪽은 1차 결과를 유지한다.
- 실패: 혼잡·일시 실패(연결·시간 초과·5xx·429·서킷 열림·동시 변환 중)면 그 파일의 남은 쪽은 보내지 않고 1차 결과로 색인을 계속한 뒤 재시도를 예약한다. 재시도는 메모리의 파일 상태에 횟수·시각을 두고 대기(10분, 이후 두 배) 뒤 첫 주기 검사에서 그 파일을 다시 추출·전송하며 `maxRetries`(기본 3, 0~10)회를 넘으면 1차 결과로 둔다. 파일이 바뀌면 횟수는 0부터다. 4xx·잘못된 응답·빈 결과는 재시도하지 않는다. 재시작하면 기존 정리 경로대로 전체를 다시 색인하므로 의심 쪽도 다시 보낸다.
- 기준값을 바꿔도 자동 재색인하지 않는다(다음에 색인하는 파일부터, 기존 파일은 소스 재색인). Docling을 새로 켰을 때도 같다.
- 상태: `GET /api/health`의 `docling.pages`에 시작 뒤 변환·실패·건너뜀 쪽 수와 재시도 대기 파일 수(주소 없음), `GET /api/docling`의 `pages`에 최근 판단 10건(소스 상대 경로·쪽·근거·결과)을 둔다. 관리 화면 Docling 절에서 기준값·집계·최근 판단을, 소스 편집에서 "항상 Docling"을 고른다.
