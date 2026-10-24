# 임베딩과 혼합 검색

기본 공급자는 `off`다. 이때 모델·네이티브 라이브러리를 로드하지 않으며 기존 BM25 키워드 검색을 사용한다. 관리 화면 **임베딩 설정**에서 공급자를 선택하고 저장 → 연결 시험 → 전체 소스 재색인 순서로 적용한다. 모델 또는 차원을 바꾸면 API와 화면에 `reindexRequired`가 나타난다. 재색인 전에는 `mode=keyword`로 기존 색인을 조회할 수 있으며 벡터·혼합 검색은 HTTP 409를 반환한다. 재색인 중에는 새 색인이 완성될 때까지 벡터·혼합 검색을 막는다. 오류나 안정화 대기가 남으면 완료 처리하지 않고 재시도한다. 실패한 공급자 설정을 다시 저장하면 진행 중 재색인을 중단하고 재색인 필요 상태로 남긴다. 수정 후 전체 재색인으로 다시 시작한다.

## 원격 공급자

OpenAI 호환 `POST {baseUrl}/embeddings`를 호출하며 `input` 배열, `model`, `encoding_format=float`를 보낸다. 응답의 `index`로 순서를 복원하고 차원·유한값·영벡터를 검사하여 정규화한다. 최대 응답은 2 MiB이며 리다이렉트는 따르지 않는다. 요청 전체에 타임아웃을 적용하며 서버 오류 본문·인증키를 예외나 로그에 포함하지 않는다.

| 설정 | 기본값 / 범위 |
| --- | --- |
| `provider` | `off`, `builtin`, `remote` |
| `baseUrl` | `https://api.example.com/v1` (예시, 실제 서버로 변경) |
| `model` | `embedding-small` (예시) |
| `apiKey` | 빈 값, 저장할 때만 입력; 빈 입력은 기존 키 유지 |
| `dimensions` | 384, 1~1024; 실제 서버 출력과 일치해야 함 |
| `batchSize` | 2, 1~16 |
| `timeoutSeconds` | 30, 1~300 |

원격 공급자를 사용하면 **문서 조각과 검색어가 설정한 서버로 전송된다. 외부 클라우드 주소라면 문서 내용이 외부로 전송됨**을 관리 화면에 경고한다. 신뢰하는 서버를 사용하고 외부 연결에는 HTTPS를 사용한다. 키는 응답에 반환하지 않는다. `embedding.properties`에는 AES-256-GCM 암호문을, `embedding.key`에는 로컬 암호화 키를 저장한다. POSIX에서는 두 파일을 0600으로 제한한다. 두 파일을 포함한 전체 데이터 디렉터리를 함께 백업해야 한다. 같은 파일에 접근할 수 있는 운영자에 대한 암호화 장벽은 아니다.

## 내장 공급자

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
| `GET /api/embeddings` | 키를 제외한 설정, `hasKey`, 현재/색인 모델 ID·차원, 재색인 필요·진행 상태 |
| `PUT /api/embeddings` | 문자열 값 객체로 설정 저장 (`{"provider":"remote", ...}`) |
| `POST /api/embeddings/test` | 저장된 공급자로 한/영 문자열 임베딩, 성공·차원 반환 |
| `POST /api/embeddings/reindex` | 전체 소스의 벡터 필드·모델 메타데이터 재구축 예약 |

초기 설정은 `nasquire.embedding.<위 설정 이름>` Spring 속성으로도 지정한다. 예: `nasquire.embedding.provider=remote`, `nasquire.embedding.baseUrl=https://api.example.com/v1`. 관리 화면에서 저장한 설정이 다음 실행에서도 우선한다. API 키는 명령줄 인자에 쓰지 말고 비밀 설정 파일로 전달한다.

`GET /api/search?query=...&mode=keyword|vector|hybrid` 및 MCP `search_documents(query, source?, limit?, mode?)`가 같은 검색 구현을 사용한다. 생략/빈 mode는 공급자 없으면 keyword, 공급자 있으면 hybrid다. RRF는 각 검색의 상위 100조각을 가져와 `1/(60+순위)`를 합산한다. 결과 수는 1~100이다. 문서 경로·쪽·조각 offset을 식별자로 사용해 같은 파일의 다른 조각을 유지한다. BM25·KNN 모두 토큰의 소스 범위를 적용한 뒤 검색한다.

Lucene의 같은 문서에 `KnnFloatVectorField`(cosine, HNSW)를 추가한다. commit user data에 `embedding.model`, `embedding.dimensions`, `embedding.incomplete`를 저장한다. 모델 ID에는 원격 endpoint·모델 이름 또는 내장 모델/토크나이저 SHA-256·풀링 버전이 들어간다. 같은 원격 모델 이름으로 서버 내부 가중치가 바뀌는 경우는 자동 감지할 수 없으므로 관리자가 재색인해야 한다. 차원 변경 시 전체 `deleteAll`로 필드 스키마도 재설정한다. 재색인이 중단되면 incomplete 상태를 읽어 벡터 검색을 차단하며 전체 재색인으로 복구한다.

메모리 실측·512 MiB 목표 충족 여부와 재현 명령은 [2단계 검증 기록](verification/vector.md)에 남긴다.
