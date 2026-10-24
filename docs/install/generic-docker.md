# Docker 공통 설치·운영

Linux amd64/arm64용 구성이다. arm64 실행·이미지 빌드와 NAS 실기는 아직 미검증이다. [배포 검증](../verification/deploy.md)의 한계를 확인한다. 이미지 주소는 `ghcr.io/nasquire/nasquire`이며 첫 릴리스 게시 전에는 pull이 실패할 수 있다. 이 단계에서는 이미지를 게시하지 않았다.

## 이미지와 자원

| 구성 | 이미지 태그 | 메모리 예시 / 기존 Linux amd64 실측 |
| --- | --- | --- |
| 키워드·원격 임베딩 | `lite`, `latest`, `<버전>-lite` | 512 MiB 목표, 최신 소형 표본 JVM 최대 281.9 MiB |
| OCR·내장 모델·리랭커 | `full`, `<버전>-full` | compose 한도 2 GiB부터 조정. 검색 서버 내장 E5 855.7 MiB, OCR 자식 JVM 단독 59.8 MiB |
| ML 전용(full 이미지) | `full`, `<버전>-full` | 리랭커 단독 약 620~632 MiB(후속 OCR 경로 검증 631.7 MiB), 임베딩 단독 627.1 MiB, 둘 함께 1047.8 MiB |

위 RSS는 기존 호스트 JVM 실측으로 컨테이너 전체 사용량·파일 캐시·Tesseract를 포함한 보장값이 아니다. 실제 OCR 정확도/속도/전체 RSS와 full cgroup 부하는 미실측이다. 2 GiB도 모든 모델·OCR 동시 부하의 보장값이 아니므로 부하를 측정해 늘린다. 512 MiB에서 내장 모델·로컬 리랭커를 켜지 않는다. 모델 파일은 이미지에 없으며 자동 다운로드는 기본 off다. [모델 설정](../embeddings.md)과 [full 실측](../verification/full.md)을 참고한다.

## 폴더와 PUID/PGID

호스트에서 실행 계정의 `id -u`, `id -g`로 숫자 UID/GID를 확인한다. 공개 이미지의 기본 계정은 1000:1000이다. PUID/PGID 환경변수만 넣어서는 공개 이미지의 사용자가 바뀌지 않는다. compose의 `user: "${PUID}:${PGID}"` 또는 `docker run --user UID:GID`로 매핑한다. 자체 빌드 시에는 같은 이름의 빌드 인자도 지원한다. 0(root)은 사용하지 않는다.

일반 문서 폴더는 읽기 권한·상위 경로 탐색 권한만 부여하고 `:ro`로 마운트한다. 데이터 폴더(`/data`)는 해당 UID/GID의 쓰기 권한이 필요하다. 업로드를 사용할 때만 앱 전용 폴더를 별도 `/uploads:rw`로 연결하고 UI에서 `kind=upload`로 등록한다. 데이터·일반 소스·업로드 폴더는 서로 겹치면 안 된다. NAS ACL도 확인하고 원본 전체를 재귀 chown하지 않는다. UID 변경 시 데이터와 업로드 폴더의 소유권/ACL을 먼저 조정한다. 앱은 시작 시 root로 권한을 고치지 않는다.

저장소를 받은 뒤 `deploy/.env`에 실제 공개 주소와 **이미 준비한** 폴더를 쓴다(예시는 일반 경로).

```dotenv
PUID=1000
PGID=1000
DATA_DIR=/srv/nasquire/data
SOURCE_DIR=/srv/documents
PUBLIC_URL=https://nas.example.com
# 앱이 실제로 연결을 받는 프록시 IP만 지정. DNS/CIDR은 지원하지 않는다.
TRUSTED_PROXIES=192.0.2.10
```

```sh
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/docker-compose.yml pull
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
```

로컬 빌드는 `pull` 대신 `build --pull`을 실행한다. 기본 타깃은 lite다. full은 모든 명령에 `-f deploy/docker-compose.full.yml`을 추가한다. `NASQUIRE_TAG`로 `1.2.3-lite` 또는 `1.2.3-full` 같은 게시된 버전을 고정한다. 다른 머신에 compose를 복사한 경우 로컬 빌드의 context/Dockerfile 경로를 맞추거나 `build` 항목을 제거한다.

`/data` 외 루트는 읽기 전용, 임시 폴더 `/tmp`만 제한된 tmpfs다. 큰 PDF에서 scratch 용량 부족은 색인 오류가 될 수 있다. 소스는 UI에서 `/sources/documents`로 등록한다. 업로드 마운트는 기본 compose의 주석을 해제하고 `UPLOAD_DIR`을 설정한다. 처음에는 외부 접근을 막고 신뢰하는 경로에서 HTTPS로 관리자 계정을 만든다.

## HTTPS·프록시·로그

공유 비밀번호는 HTTP Basic이며 관리자 로그인은 쿠키를 사용한다. **HTTPS 리버스 프록시가 필수**다. `PUBLIC_URL=https://nas.example.com`, `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`를 유지한다. 앱 포트는 기본 호스트 루프백에만 공개한다. 별도 컨테이너 프록시라면 전용 Docker 네트워크로 연결하고 포트 공개를 제거하거나 방화벽으로 프록시만 허용한다. 앱에 직접 접근할 수 있는 외부 경로를 열지 않는다.

프록시는 클라이언트의 전달 헤더를 신뢰하지 말고 덮어쓴다. `NASQUIRE_TRUSTED_PROXIES`는 앱이 관찰한 실제 연결 프록시 IP만 등록한다. Docker NAT 때문에 호스트 프록시 IP가 127.0.0.1과 다를 수 있다. 기본 `SERVER_FORWARD_HEADERS_STRATEGY=none`을 유지한다. scheme 처리가 꼭 필요할 때의 제한된 Tomcat 신뢰 설정은 [프록시 경계](../verification/upload-share.md#프록시-선택-설정)를 따른다.

Nginx 예시(인증서를 별도 준비하고 주소를 맞춘다):

```nginx
server {
    listen 443 ssl;
    server_name nas.example.com;
    ssl_certificate /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    # 공유 토큰 경로·Authorization·다운로드 쿼리가 기록되지 않게 한다.
    access_log off;
    error_log /dev/null crit;
    client_max_body_size 33m;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Authorization $http_authorization;
    }
}
```

`/s/**`는 경로 자체가 비밀이며 `/download` 쿼리는 접근 티켓이다. 프록시·NAS·WAF·CDN의 접근/오류/디버그/추적 로그에서도 **공유 경로, Authorization, 다운로드 쿼리**를 제외한다. 위 예시는 해당 가상 호스트의 로그 전체를 끈다. 프록시 자체 Basic 인증이 앱의 공유 Basic/Bearer를 덮어쓰지 않게 한다. 플랫폼 UI에서 로그 제외를 설정할 수 없으면 가능한 별도 프록시를 둔다. 평문 HTTP로 Secure 쿠키 로그인을 시험하지 않는다.

## 백업·업그레이드·복구

한 데이터 폴더에는 인스턴스 하나만 실행한다. 앱을 정지한 뒤 `/data` 전체(H2, Lucene, `embedding.key`, 임베딩/full 설정, 모델 포함)를 일관된 백업으로 보관한다. 키만 빠져도 TOTP·저장된 공급자 키를 복호화하지 못한다. 백업에는 비밀값이 있으므로 접근을 제한한다. 원본과 별도 `/uploads`도 독립적으로 백업한다. 원격 Qdrant를 쓴다면 컬렉션 스냅샷/접속 설정도 별도로 보관한다.

업그레이드 순서는 정지 → 백업 → 고정 버전 pull → 재생성 → 건강 상태·로그인·소스 상태·검색 확인이다. 모델·차원·벡터 저장소·Qdrant 컬렉션이 바뀌거나 `reindexRequired`가 표시되면 전체 재색인한다. Qdrant는 영속 `vector.epoch` 세대만 검색하므로 이전 세대 점만 있는 컬렉션/백업 불일치에서도 재색인이 필요하다. Qdrant 컬렉션 삭제로 우회하지 않는다. full에서 lite로 전환하기 전 내장 임베딩·리랭커·OCR을 끈다. 활성 full 설정이 남으면 시작이 거부될 수 있다. DB 마이그레이션 후 구버전 호환을 가정하지 말고 롤백은 정지 후 전체 백업과 맞는 이미지로 수행한다.

관리자 복구(앱을 정지하고 **같은 데이터·이미지·UID/GID** 사용, 비밀번호는 대화형 표준 입력으로만):

```sh
docker compose --env-file deploy/.env -f deploy/docker-compose.yml stop nasquire
docker compose --env-file deploy/.env -f deploy/docker-compose.yml run --rm --no-deps nasquire --recover-admin --data=/data
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
```

full은 세 명령 모두 full override를 추가한다. 복구는 관리자 비밀번호를 바꾸고 TOTP/복구 코드를 해제한다. `embedding.key` 누락은 원본 백업 복원이 우선이며 복구 명령이 공급자 키를 복원하지 않는다. 실행 중인 DB에 복구 명령을 겹쳐 실행하지 않는다.

## ML 전용 모드

같은 full 이미지에 명령 인자를 붙인다. 검색 서버와 별도 데이터 폴더를 쓰며 UI·MCP·검색·DB는 없다. `deploy/docker-compose.ml.yml`은 OCR만 켠 예시다. 외부 OCR 호출을 검색 서버에 연결하는 클라이언트는 아직 미구현이다.

```sh
# 호스트에서 ML_DATA_DIR 폴더와 UID/GID 권한을 먼저 준비한다.
# 토큰 파일은 실행 UID가 읽을 수 있는 0600 파일로 만들고 출력하지 않는다.
umask 077
openssl rand -hex 32 > /srv/nasquire/ml-token
export ML_DATA_DIR=/srv/nasquire/ml-data
export ML_TOKEN_FILE=/srv/nasquire/ml-token
export PUID=1000 PGID=1000
docker compose -f deploy/docker-compose.ml.yml up -d
```

핵심 인자는 `--nasquire.mode=ml --server.address=0.0.0.0 --server.port=8080 --nasquire.ml.ocr=tesseract`다. 환경변수 `NASQUIRE_MODE=ml`도 함께 지정해야 기본 헬스체크가 ML 경로를 선택한다. 이 헬스체크는 `/v1/models`의 무인증 401로 리스너·인증 경로를 확인하며 추론 정확도 검사는 아니다. 검색 모드는 `/` 200을 검사한다.

모델 파일을 `/data/models` 아래 준비한 뒤 command에 `--nasquire.ml.embedding=builtin`, `--nasquire.ml.reranker=builtin`을 추가할 수 있다. 명시한 `--nasquire.ml.download=true`만 다운로드를 허용한다. 2 GiB 예시의 사용량은 실제 부하에서 확인한다. 토큰은 명령줄에 쓰지 않고 파일(32~512자 ASCII)로 전달한다. TLS 프록시를 통해 `https://ml.example.com/v1`로 제공하며 프록시 로그에서 Authorization을 제외한다. 검색 서버의 원격 공급자 URL·키에 이 주소와 ML 토큰을 설정한다. ML 데이터/토큰도 백업하고 회전 시 클라이언트 설정을 함께 바꾼다.

## CI·이미지 생성

push/PR은 lite/full Gradle 빌드와 amd64/arm64 Buildx 빌드만 수행한다. `v1.2.3` 같은 `v*` 태그만 `:lite`, `:full`, `:1.2.3-lite`, `:1.2.3-full`, `:latest`(lite)를 GHCR에 게시한다. 게시 job에만 `packages:write`를 부여하며 액션은 커밋 SHA로 고정한다. SBOM/프로비넌스는 [Docker 공식 방식](https://docs.docker.com/build/ci/github-actions/attestations/)으로 켠다. PR 빌드는 레지스트리에 출력하지 않아 게시된 증명도 없다. 이 작업에서는 워크플로를 실제 실행하거나 이미지 로그인·게시를 하지 않았다.
