# 5a 배포 검증

검증일: 2026-10-24, 작업 기준 `3b10669`. 배포 파일·CI·설치 안내의 구현과 아래 대체 검증은 완료했다. 실제 이미지 빌드/기동·메모리 제한 충족을 검증한 결과는 아니다.

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

lite 512 MiB는 목표다. [기존 full 검증](full.md)의 최신 lite JVM 최대 RSS 281.9 MiB는 호스트 JVM만의 수치다. [내장 E5 검색 서버](vector.md)는 855.7 MiB, ML 리랭커 약 620~632 MiB(후속 OCR 경로 631.7 MiB), ML E5 627.1 MiB, ML 두 모델 1047.8 MiB였다. OCR 자식 단독 표본 61,252 KiB(59.8 MiB)도 별도다. Tesseract·자식·파일 캐시를 합친 cgroup 전체 실측이 없으므로 **full compose의 2 GiB도 부하 보장값이 아니다**. 실제 NAS에서 측정해 한도를 늘려야 한다. 모델·로컬 리랭커는 512 MiB에서 사용하지 않는다.

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
- 이미지 실행·실제 HEALTHCHECK·컨테이너 메모리 제한·arm64·Actions 실행은 여전히 미검증이다. 전달된 PASS/성공 결과 외 원본 로그·종료 코드 아티팩트는 이 저장소에 제공되지 않았으며 별도 숫자 코드를 추정하지 않는다.
