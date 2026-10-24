# 4단계 full 모듈: 부분 구현·검증

검증일: 2026-10-24, 기준 커밋 `a4bb16b`. **4단계 전체는 미완료**다. 이번 범위는 선택 full 모듈, 원격 리랭커, Tesseract OCR 호출·추출 경로다. Qdrant·ML 전용 모드·로컬 ONNX 리랭커·PaddleOCR 구현과 실제 OCR 비교는 남아 있다.

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
- 로컬 다국어 cross-encoder는 미구현이다. 리비전·SHA-256·라이선스를 검증한 모델도 선정하지 않았으며, 로컬 리랭커가 있다고 주장하지 않는다.

## OCR 경로·제한

텍스트 없는 PDF 쪽만 PDFBox 150 DPI로 렌더링한다. 텍스트 있는 쪽은 기존 추출기를 유지한다. PNG/JPEG/TIFF/BMP/GIF는 ImageIO 프레임별로 처리한다. OCR 결과의 `location.type`은 `ocr`, `location.page`와 기존 `page`는 원래 쪽/이미지 프레임 번호다. 텍스트 PDF의 근거는 기존 `page` 타입이다. OCR 때문에 일반 소스를 수정하지 않는다.

`/usr/bin/prlimit`가 `/usr/bin/tesseract`를 직접 실행하며 셸을 사용하지 않는다. 언어는 `kor+eng`, `--psm 3`, `OMP_THREAD_LIMIT=1`이다. 운영자가 Tesseract와 두 언어의 traineddata를 미리 준비해야 한다. 소스 경로 대신 데이터 폴더의 `ocr-tmp` 임시 이미지 경로만 전달한다. 임시 디렉터리는 요청 종료 시 정리한다.

쪽당 시간·CPU 초, 주소 공간(RLIMIT_AS), 출력 파일(RLIMIT_FSIZE 1 MiB) 상한을 둔다. 주소 공간 상한은 RSS 실측값이 아니다. 페이지당 최대 16백만 픽셀·변 길이 8192, 전달 PNG 최대 16 MiB, 최종 텍스트는 기존 추출 문자 상한을 따른다. 다중 이미지 프레임/스캔 쪽 수가 상한을 넘으면 파일 추출 오류이며 일부 쪽만 성공으로 게시하지 않는다. 시간 초과 시 살아 있는 프로세스와 자손을 종료한다. 외부 OCR 실패는 파일 색인 오류로 남는다. 원본 파일 크기·경로·안정화 검사는 기존 색인기를 사용한다.

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

**현재 lite RSS는 기준보다 약 27 MiB(9.6%) 높다. 기존 수준과 동등하다고 완료 처리하지 않았다.** 증가 원인은 확정하지 못했다. full/ONNX/DJL의 런타임 포함이나 네이티브 추론 활성화는 산출물 검사로 배제했지만, 이것만으로 GC·클래스 경로 변경 등 원인을 특정할 수 없다. 두 현재 실행 모두 JVM RSS는 512 MiB 아래이나 실제 cgroup·파일 캐시·컨테이너 전체 제한 검증은 아니다. 기본 구성 메모리 회귀 분석이 남았다. 유리한 실행만 선택하지 않고 두 결과와 기준 결과를 모두 보존했다.

실행 중 표본 생성기의 Log4j provider 경고가 있었으나 앱 빌드·실행·검증은 성공했다. 기준 비교를 처음 요청할 때 임시 작업 디렉터리 생성 전이라 도구가 프로세스 실행을 거부했다(종료 코드 없음). 디렉터리를 만든 뒤 위 기준 비교를 정상 실행했다.

## 남은 작업·아티팩트

- Qdrant REST 클라이언트·암호화 API 키·컬렉션 모델/차원 메타·불일치 차단/재색인·소스 범위 필터·가짜 Qdrant 테스트: 전부 미구현. 벡터 저장소는 계속 Lucene HNSW다.
- ML 전용 서버·Bearer 강제·소스/관리 화면 부재·임베딩/리랭크/OCR HTTP 호환·크기/동시성 제한: 미구현. `nasquire.mode=ml`을 주면 시작을 거부하여 일반 검색 서버를 ML 서버로 오인하지 않게 했다. 현재 지원 모드는 `search`뿐이다.
- 로컬 ONNX 리랭커·PaddleOCR와 실제 비교 실측, OCR 운영용 실행 파일/언어 데이터 고정·배포, full 설정 화면, 실제 외부 서비스 품질/호환 검증: 미완료.
- Docker/cgroup·arm64·대규모/동시 부하·실제 브라우저 검증은 하지 못했다. lite JVM RSS만으로 컨테이너 전체 메모리를 보장하지 않는다.

공개 커밋에서 제외되는 상세 경로:

- `build/verification/stage4/`: `lite-build.log`, `full-build.log`, `lite-runtime.txt`, `lite-runtime-repeat.txt`, `baseline-runtime.txt`, `measurement-*.json`, `lite-artifact.json`, `*-test-summary.json`, `full-tests/`, `tesseract-version.txt`. 로그/테스트 XML은 환경 경로를 포함할 수 있어 공개 커밋에 넣지 않는다.
- `build/verification/measurement.json`, `smoke.txt`, `bootrun.log`: lite 실제 실행 측정·API 검증.
- `full/build/reports/tests/test/index.html`, `app/build/reports/tests/test/index.html`, `core/build/reports/tests/test/index.html`: 자동 테스트 보고서(마지막 실행 구성).
