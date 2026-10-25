# 5a 배포 검증

검증일: 2026-10-24, 작업 기준 `3b10669`. 배포 파일·CI·설치 안내의 구현과 아래 대체 검증은 완료했다. 당시에는 실제 이미지 빌드/기동·메모리 제한을 검증하지 못했다. 이후 운영자 실측은 아래 2026-10-25 절을 따른다.

## 변경과 정적 검토

- Temurin 25 JDK 빌드 단계는 `$BUILDPLATFORM`에서 lite/full JAR를 따로 생성하고, glibc 기반 `25-jre-noble` 런타임은 대상 플랫폼에서 구성한다. Buildx 대상은 `linux/amd64,linux/arm64`다. 기본 마지막 타깃은 lite이며 full은 `-Pfull`로 빌드한다. 각 단계에서 런타임 JAR만 가져온다. 모델·캐시·비밀 환경 파일은 빌드 컨텍스트에서도 제외한다.
- 두 이미지 모두 비루트 사용자, PUID/PGID 빌드 인자·compose 숫자 사용자 매핑, native access, `/data`, OCI 소스 URL·AGPL 라벨을 제공한다. 일반 소스의 읽기 전용 마운트와 `/data` 쓰기는 compose에서 설정한다. 호스트 폴더 권한은 운영자가 준비하며 환경변수만으로 공개 이미지 사용자 변경/소유권 수정은 하지 않는다.
- full은 `tesseract-ocr`, `tesseract-ocr-kor`, `tesseract-ocr-eng`, `util-linux`(`prlimit`, `setsid`), fontconfig/DejaVu 글꼴을 설치하도록 작성했다. OCR 자식은 같은 실행 JAR/JRE와 쓰기 가능한 `/data/ocr-tmp`를 사용하며 Linux `/proc` 접근이 필요하다. 실제 이미지 안의 실행 파일·언어 목록 확인은 미수행이다.
- 검색 헬스체크는 `/` 200, ML은 `/v1/models` 무인증 401을 확인한다. ML은 모델 준비 뒤 리스너를 열지만 이 검사는 추론 품질을 보장하지 않는다. ML 명령 인자와 함께 `NASQUIRE_MODE=ml`을 설정한다. 기본 포트는 8080이며 포트를 바꾸면 헬스체크용 `PORT`도 맞춘다.
- CI는 push/PR에 lite/full Gradle 빌드·캐시·실패 시에도 테스트 보고서 업로드를 수행한다. 이미지 workflow는 브랜치/PR에서 push/login 없이 Buildx 빌드, `v*` 태그에서만 GHCR 게시 job을 실행한다. 이 job에만 `packages:write`가 있다. `v1.2.3`은 `lite`, `full`, `1.2.3-lite`, `1.2.3-full`, `latest=lite` 태그로 변환된다. 유효하지 않은 버전 문자열은 거부한다.
- 액션은 공개 Git 저장소의 태그를 `git ls-remote`로 조회해 확인한 **커밋 SHA**로 고정했다(주석은 major tag). Gradle actions의 annotated tag는 peeled commit으로 고정했다. GitHub/GitLab API는 호출하지 않았다. SBOM과 `mode=max` 프로비넌스를 켰다([Docker 공식 문서](https://docs.docker.com/build/ci/github-actions/attestations/)). 실제 GHCR 게시/증명 생성은 미수행이다.

## 실행한 검증

| 명령 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew build -q` | 0 | lite 빌드·클래스 경로 검사·테스트 통과 |
| `./gradlew -Pfull build -q` | 0 | full 빌드·테스트 통과. 마지막 앱 JAR는 full 구성 |
| `python3 build/verification/deploy/check-static.py` | 0 | PyYAML 6.0.3으로 workflow 2개·compose 3개 파싱, SHA 고정·권한/게시 경계·두 아키텍처·태그 생성/잘못된 버전 거부·XML·마운트·로컬 문서 링크 등 64개 확인 |
| `sh -n deploy/healthcheck.sh`와 추출한 workflow 스크립트의 `bash -n` | 0 | 셸 문법 통과(위 정적 검증에 포함) |
| 헬스체크 스크립트 + Python 모의 HTTP 서버 | 0 | 검색 200·ML 401 성공, 리스너 종료 후 스크립트 실패(예상된 비정상 코드)를 확인. 포트/서버 종료 완료 |
| `rg -n -i '<내부 정보 패턴>' README.md CLAUDE.md Dockerfile docs deploy .github` | 1 | 내부 도메인·서버/계정 이름·사내 절대 경로 패턴 일치 없음. rg의 1은 오류가 아닌 무일치 |
| `git diff --check` | 0 | 공백 오류 없음 |

최종 테스트 XML 합계는 core 27·app 25·full 19, 총 71개이며 실패·오류·생략 모두 0이다. lite/full 빌드는 순서대로 실행했고 bootRun을 병행하지 않았다. 필수 빌드와 정적 검증 실패는 없었다. 잘못된 태그와 종료된 리스너의 실패는 의도한 거부 동작이다.

## 못 한 검증과 남은 위험

`docker`, `hadolint`, `actionlint` 모두 PATH에 없다. 패키지는 설치하지 않았다. 따라서 아래는 실행하지 못했다.

- `docker build --platform linux/amd64 --target lite ...` / `--target full ...`: Docker 없음. 베이스 이미지 pull·패키지 설치·레이어 구성·실제 도구 포함·전체 이미지 크기는 미검증이다.
- lite 컨테이너 기동·Docker HEALTHCHECK·`--memory=512m --memory-swap=512m` cgroup 측정: Docker 없음. 모의 HTTP 검사로 대체한 범위를 컨테이너 실측으로 해석하지 않는다. PUID/PGID 볼륨 ACL·read-only 루트·OCR의 prlimit/setsid·/proc도 실컨테이너 확인이 남는다.
- hadolint/actionlint: 도구 없음. Dockerfile은 정적 검토, YAML은 PyYAML 구문 파싱·주요 정책 단언·셸 파싱으로 대체했다. Actions 스키마/실행 환경 전체나 Compose의 실제 병합/환경변수 해석을 검증한 것은 아니다.
- arm64 이미지/네이티브 라이브러리 실행, NAS별 UI·파일시스템 ACL, 실제 OCR 정확도·속도·전체 RSS, 실제 Qdrant·TLS 프록시·동시 부하: 환경/실기 없음. GHCR 로그인·이미지 게시·Actions 원격 실행은 이번 작업에서 하지 않았다.

당시 lite 512 MiB는 목표였고, 이후 운영자가 아래 범위에서 cgroup 제한을 검증했다. [기존 full 검증](full.md)의 최신 lite JVM 최대 RSS 281.9 MiB는 호스트 JVM만의 수치다. [내장 E5 검색 서버](vector.md)는 855.7 MiB, ML 리랭커 약 620~632 MiB(후속 OCR 경로 631.7 MiB), ML E5 627.1 MiB, ML 두 모델 1047.8 MiB였다. OCR 자식 단독 표본 61,252 KiB(59.8 MiB)도 별도다. Tesseract·자식·파일 캐시를 합친 cgroup 전체 실측이 없으므로 **full compose의 2 GiB도 부하 보장값이 아니다**. 실제 NAS에서 측정해 한도를 늘려야 한다. 모델·로컬 리랭커는 512 MiB에서 사용하지 않는다.

## 재검증과 아티팩트

Docker 가능한 환경에서 폴더/권한과 HTTPS 경계를 준비하고 다음을 실행한다. 아래는 **실행하지 않은 후속 명령**이다.

```sh
docker build --platform linux/amd64 --target lite -t nasquire:verify-lite .
docker build --platform linux/amd64 --target full -t nasquire:verify-full .
# DATA_DIR와 SOURCE_DIR는 실제 준비된 폴더로 설정한다.
docker tag nasquire:verify-lite ghcr.io/nasquire/nasquire:verify-lite
NASQUIRE_TAG=verify-lite docker compose -f deploy/docker-compose.yml up --no-build -d
docker compose -f deploy/docker-compose.yml ps
docker stats --no-stream
```

실제 검증에서는 `docker inspect`의 Health/State/OOMKilled와 cgroup `memory.peak`, 부하 중 `docker stats`를 수집하고, 등록/색인/검색 뒤 종료한다. 잠깐 유휴 stats만으로 512 MiB 충족을 판단하지 않는다. full은 `tesseract --list-langs`, `prlimit --version`, `setsid --version`, OCR 스캔 PDF/이미지의 실제 인식과 프로세스 회수도 확인한다.

상세 로컬 아티팩트(공개 커밋 제외): `build/verification/deploy/`의 `lite-build.log`, `full-build.log`, `*-build.exit`, `test-summary.json`, `check-static.py`, `static-results.json`, `static.log`, `static.exit`, `internal-scan.log`, `internal-scan.exit`. 스캔 패턴에는 내부 값이 있으므로 원문은 공개 문서에 복사하지 않았다. git add/commit/push와 이미지 게시는 수행하지 않았으며 커밋 메시지는 루트 `.ai-commit-msg`다.


## 운영자 추가 실측 기록 (2026-10-25)

아래는 운영자가 직접 수행하여 전달한 결과이며 이번 OAuth 수정본을 재배포한 결과는 아니다.

- Linux amd64 cgroup v2에서 `systemd-run --scope -p MemoryMax=512M -p MemorySwapMax=0`으로 lite bootJar를 실행했다. Dockerfile과 같은 `JAVA_TOOL_OPTIONS`를 사용하고 `scripts/smoke.py`의 48문서 색인·검색·MCP·다운로드·16 MiB 업로드·공유·TOTP·토큰 철회가 전 단계 PASS였다. cgroup `memory.peak` 344 MiB, 프로세스 최대 RSS 352.6 MiB, `memory.events`의 `max`·`oom`·`oom_kill`은 모두 0이었다. 서로 다른 계측 범위/표본의 수치를 동일 지표로 비교하지 않는다.
- 이는 JVM 스코프의 512 MiB 제한 실측이며 **컨테이너 이미지 자체를 512 MiB로 실행한 결과는 아니다**. 전체 full 부하 보장도 아니다.
- 기준 커밋 `ce94b82`에서 Kubernetes 일회성 파드의 Kaniko executor `--no-push`로 amd64 `--target lite`와 `--target full` 이미지 빌드가 모두 성공했다. Dockerfile 이미지 빌드 미검증 항목 중 amd64 빌드만 해소했다.
- 이 절의 amd64 결과만으로는 이미지 실행·arm64를 검증하지 못했다. 이후 arm64 결과는 다음 절을 따른다. 전달된 PASS/성공 결과 외 원본 로그·종료 코드 아티팩트는 제공되지 않았으며 별도 숫자 코드를 추정하지 않는다.

## arm64 컨테이너 운영자 실측 (2026-10-25, 기준 0bb5463)

운영자가 Apple Silicon 호스트의 arm64 Linux VM(Colima vz, cgroup v2)에서 측정해 전달한 결과다. 아래 값은 이번 변경본을 재배포한 결과가 아니다. 원본 로그·개별 종료 코드 아티팩트는 제공되지 않았으며 PASS를 숫자 종료 코드로 추정하지 않는다.

| 구성·메모리 제한 | 검증 결과 | cgroup memory.peak | OOM |
| --- | --- | ---: | ---: |
| lite arm64 Docker 512 MiB | `scripts/smoke.py` 전 단계 PASS | 320 MiB | 0 |
| lite arm64 Docker 384 MiB | 같은 스모크 전 단계 PASS | 294 MiB | 0 |
| lite arm64 Docker 320 MiB | 같은 스모크 전 단계 PASS | 294 MiB | 0 |
| lite amd64 JAR 512 MiB cgroup | 같은 스모크 PASS | 344 MiB | 0 |
| full arm64 Docker 1 GiB | PNG OCR 1건 성공·영문/한글 검색·`location.type=ocr` | 259 MiB | PDF 재현에서도 OOM 없음 |

lite 컨테이너는 스왑 없음·읽기 전용 루트·`cap_drop: ALL`·비루트 사용자 조건이었다. 약 9초에 healthy였고 arm64 업로드 네이티브 `mkdirat`/`renameat2`도 동작했다. 384/320 MiB 성공은 소규모 표본이며 지원 최소값은 512 MiB로 유지한다. 원격 임베딩·대규모 문서의 메모리 보장으로 확대하지 않는다.

arm64 이미지 크기는 lite 638 MB, full 899 MB였다. amd64 lite/full은 Kaniko 빌드 성공을 전달받았다. 실제 NAS 장비·대규모 문서·Actions 실행·GHCR 게시·전체 full 동시 부하는 여전히 미검증이다.

### 이미지 전용 PDF 실패와 이번 진단 보강

운영자 full 기본 `JAVA_TOOL_OPTIONS`에서 `ocr=tesseract`, `ocrTimeoutSeconds=30`, `ocrMemoryMiB=512`, `ocrMaxPages=20`을 설정했다. Pillow로 만든 1400×500 RGB/JPEG 단일 쪽·텍스트 없는 PDF는 약 25초 뒤 추출 실패했다. 메모리 1/2 GiB·읽기 전용 루트 여부와 무관했고 OOM은 없었다. Tesseract 5.3.4와 kor/eng 데이터는 존재했고 PNG는 성공했다. 기존 로그에는 원인이 없었다.

코드상 렌더 작업자는 부모 환경을 이미 비우고 별도 `-Xmx192m`·주소 공간 2048 MiB로 실행한다. `ocrMemoryMiB=512`는 Tesseract의 주소 공간 제한이다. 한 쪽 렌더링의 기존 파일 단위 시간 상한은 15+10=25초이므로 관찰 시간은 렌더 시간 초과와 부합하지만 **원인 확정 근거는 아니다**. Dockerfile 기본 옵션은 변경하지 않았다. 이번 변경은 자식 종료 코드·시간 초과·예외 종류만 안전한 진단으로 남기고, 색인 WARN에는 소스 상대 경로만 기록한다. stderr·원문·예외 메시지·스택은 노출하지 않는다. 관리자 세션 전용 소스 상태 API에 최근 실패 최대 5건의 상대 경로와 안전한 사유를 추가했다. 실패 목록은 메모리에만 있으며 다음 검사/재시작 때 갱신된다.

Linux amd64에서 PDFBox 생성 JPEG PDF 및 운영자 방식으로 Pillow 12.1.1·Noto Sans CJK 44pt로 생성한 1400×500 L → RGB PDF(`full/src/test/resources/scan-pillow.pdf`) → 격리 자식 JVM → PNG → 가짜 Tesseract → 한/영 검색·OCR 쪽 근거 경로를 검증했다. 실행 JAR와 Dockerfile 기본 `JAVA_TOOL_OPTIONS`에서도 검사한다. 실제 Tesseract 정확도 검증이 아니다. 당시 Docker·arm64 실행기·Tesseract가 없어 운영자 실패를 재현하지 못했다. 이후 원래 이름 누락을 확정·수정했으며 아래 후속 수정 절을 따른다. 수정 후 arm64 재측정은 운영자가 수행한다. 주소 공간·힙·시간·환경 초기화·0700 작업 폴더·자손 회수 제한은 유지했다.

재현 명령(빌드와 순차 실행):

```sh
./gradlew build -q
./gradlew -Pfull build -q
NASQUIRE_OCR_TEST_JAR="$PWD/app/build/libs/app-0.1.0.jar" \
JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m' \
./gradlew :full:test --tests '*FullProviderTest.jpegOnlyPdfThroughWorkerAndTesseract' --rerun-tasks -q
VERIFY_PORT=8104 python3 scripts/verify-runtime.py
```

### 소규모 디스크 예시

이번 lite 키워드 스모크의 자체 생성 48문서(40 PDF쪽 포함)는 원본 파일 논리 크기 합계 226,813바이트(약 221.5 KiB), 스모크 종료 시 Lucene 색인 파일 합계 28,668바이트(약 28.0 KiB)였다. 압축률이 높은 반복 텍스트의 작은 표본이며 일반적인 원본 대비 색인 비율로 사용하지 않는다. DB·파일시스템 블록 할당·16 MiB 업로드·휴지통·백업·모델은 이 색인 크기에 포함하지 않는다. 원본 텍스트 양·벡터 수와 운영 방식에 따라 별도 여유가 필요하다. `scripts/verify-runtime.py`가 `sampleFileBytes`와 `indexFileBytes`를 기록하도록 보강했다.

상세 아티팩트는 공개 커밋에서 제외되는 `build/verification/ocr-pdf/`에 있다. 이번 스모크 종료 코드는 0이고 JVM 최대 RSS는 279.7 MiB였으며 서버·8104 포트 종료를 확인했다. cgroup/컨테이너 실측은 아니다. 최종 빌드·회귀 테스트·내부 정보 검사 결과는 같은 폴더의 로그·종료 코드 파일을 따른다.

이번 변경 최종 결과

| 검증 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `./gradlew build -q` | 0 | lite 빌드·전체 테스트 통과 |
| `./gradlew -Pfull build -q` | 0 | full 빌드·전체 테스트 통과 |
| 위 실행 JAR·Docker 기본 JVM 환경 PDF 회귀 명령 | 0 | PDFBox/Pillow PDF 2건, 가짜 OCR·한/영 검색·OCR 쪽 근거·임시 폴더 정리 통과 |
| `VERIFY_PORT=8104 python3 scripts/verify-runtime.py` | 0 | 48문서 스모크·디스크 계측·서버/포트 종료 |
| 내부 정보 패턴 `rg` | 1 | 일치 0건(무일치 종료 코드) |
| `git diff --check` | 0 | 공백 오류 없음 |

전체 빌드 테스트는 core 28·app 44·full 21개, 실패/오류/생략 모두 0이었다. 이후 실행 JAR 회귀 2건도 실패/오류/생략 0이다. 필수 빌드 실패는 없었다. 당시 운영자 arm64 실패를 로컬에서 재현하지 못했다. 이후 원인 확정·코드 수정은 아래 후속 절에 반영했으며 수정 후 컨테이너 재측정은 미완료다.

### PDF OCR 이름 보존 후속 수정 (기준 b0bdf4e)

운영자가 arm64 컨테이너에서 원인을 확정했다. `FullSettings.extractor()`가 이름·내용 분리 `extractLocated(name, content, ...)`를 재정의하지 않아 기본 구현이 원래 PDF 이름을 버렸다. 색인기의 확장자 없는 SecureFiles 고정 fd 경로가 image 모드로 전달되어 `OcrWorker.image()`의 지원하지 않는 이미지 오류(`stage=render exit=2`)가 났다. 운영자의 pdf 모드·쪽 1 수동 실행은 정상이며 PNG는 원래 image 모드라 성공했다. 관찰된 25초만으로 시간 초과를 확정하지 않는다.

위임은 OCR 공급자와 OCR off 시 PDF 폴백 모두 원래 이름을 사용하고 내용은 고정 fd에서 읽도록 수정했다. 다른 운영 추출기/래퍼도 점검했다. 회귀 테스트는 FullSettings와 실제 IndexingService 경로를 거쳐 격리 자식 PDF 렌더링·가짜 OCR 공급자·한/영 검색의 `location.type=ocr`와 쪽 1을 확인한다. 기존 주소 공간·힙·시간·환경 초기화·0700 폴더·자손 회수 제한은 유지했다. 상세 결과는 [full 검증](full.md#pdf-ocr-원래-이름-보존-수정-기준-b0bdf4e)에 기록한다.

수정 후 arm64 컨테이너·실제 Tesseract PDF OCR 재측정은 운영자가 후속 수행한다. 로컬 테스트는 실제 OCR 정확도·속도·RSS 및 full cgroup 메모리 보장의 근거가 아니다.


이번 수정 검증은 `./gradlew build -q`·`./gradlew -Pfull build -q` 각각 종료 0이며 최종 core 28·app 47·full 21개 실패/오류/생략 0이다. 기준 FullSettings로 새 회귀를 실행하면 3건 실패·종료 1로 증상을 재현한다. 내부 정보 검사 일치 0건(`rg` 종료 1), 공백 검사 종료 0이다. 아티팩트는 `build/verification/ocr-name/`에 보존했다. 개발 서버·컨테이너는 시작하지 않았다.

## arm64 컨테이너 재측정 (이름 전달 수정 후, 기준 d8e83a5)

Apple Silicon 호스트의 arm64 Linux VM(Colima vz, cgroup v2)에서 운영자가 lite·full 이미지를 다시 빌드해 확인했다. 메모리 제한은 스왑 없이 적용했고 읽기 전용 루트·`cap_drop ALL`·`no-new-privileges`·비루트 사용자로 실행했다.

| 이미지 | 메모리 제한 | 확인 | cgroup memory.peak | OOM |
| --- | --- | --- | --- | --- |
| full | 1 GiB | OCR 켜기 후 이미지 전용 PDF·PNG 색인 오류 0, 영문·한글 키워드가 두 파일 모두 `location.type=ocr`로 검색 | 297 MiB | 0 |
| full | 1 GiB | `scripts/smoke.py` 전 단계 3회 중 2회 통과, 1회는 스모크 스크립트의 TOTP 단계 계산이 30초 경계를 넘은 실패(서버 ±1 허용 밖 코드 생성) → 스크립트 수정 | 336–345 MiB | 0 |
| lite | 512 MiB | `scripts/smoke.py` 전 단계 통과, 헬스 체크 약 9초 | 300 MiB | 0 |

OCR 시험 문서는 Pillow로 만든 텍스트 레이어 없는 단일 쪽 이미지 PDF와 같은 내용의 PNG 두 개뿐이다. 실제 스캔 문서의 정확도·속도, 큰 문서·많은 쪽 OCR의 메모리는 여전히 미실측이다.
