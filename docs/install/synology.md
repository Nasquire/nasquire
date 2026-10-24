# Synology Container Manager

Container Manager를 지원하는 Linux amd64/arm64 모델이 대상이다. DSM/모델별 지원 여부와 화면 이름을 확인한다. NAS 실기·arm64 실행은 미검증이다. [공통 설치·운영](generic-docker.md)을 먼저 적용한다. 프로젝트는 [Synology 공식 설명](https://help.synology.com/developer-guide/resource_acquisition/docker-project.html)처럼 Compose 기반이다.

1. File Station에서 앱 전용 데이터 폴더와 별도 일반 문서 공유를 준비한다. 일반 문서는 읽기 ACL만, `/data` 폴더는 실행 계정에 쓰기 ACL을 준다. UID/GID를 확인해 PUID/PGID로 지정하고 compose의 `user` 매핑을 유지한다. root/관리자 권한 컨테이너로 우회하지 않는다.
2. `deploy/docker-compose.yml`을 프로젝트 폴더로 복사한다. 배포 이미지를 쓸 때 `build` 항목을 제거한다. Container Manager → 프로젝트 → 생성에서 YAML을 선택한다. `.env` 지원이 다른 버전에서는 `${...}`를 실제 일반 경로/숫자로 바꾸되 비밀값을 YAML에 넣지 않는다. `/data`는 준비한 호스트 폴더, 일반 원본은 `/sources/documents:ro`다. 업로드는 별도 `/uploads:rw` 폴더만 추가하고 UI에서 업로드 종류로 등록한다.
3. lite 이미지 `ghcr.io/nasquire/nasquire:lite`, 메모리 512 MiB로 시작한다. full은 `docker-compose.full.yml` 내용을 합치고 이미지 `:full`, 한도 2 GiB를 출발점으로 쓴다. 기존 JVM 실측은 lite 최대 281.9 MiB, 내장 E5 855.7 MiB, ML 리랭커 약 620~632 MiB, 두 모델 ML 1047.8 MiB다. OCR 자식·Tesseract·파일 캐시 포함 cgroup 보장값은 아니다. full/내장 모델/리랭커를 512 MiB에서 켜지 않는다.
4. DSM의 HTTPS 리버스 프록시 또는 별도 프록시로 `https://nas.example.com`을 제공한다. 대상은 기본 호스트 `127.0.0.1:8080`이다. `NASQUIRE_PUBLIC_URL`과 Secure 쿠키를 설정하고 `NASQUIRE_TRUSTED_PROXIES`에는 앱에 연결하는 프록시의 정확한 IP만 쓴다. 앱 포트 외부 직접 접근을 차단한다. 공유 HTTP Basic과 관리자 쿠키 때문에 HTTPS가 필수다.
5. DSM 프록시와 앞단 장비의 로그에서 공유 `/s/**` 경로, Authorization, 다운로드 쿼리를 제외한다. UI로 제외가 불가능하면 [공통 Nginx 예시](generic-docker.md#https프록시로그)의 별도 프록시를 사용한다. 관리자 최초 설정 뒤 `/sources/documents`를 등록하고 색인·검색을 확인한다.

정지 상태에서 `/data` 전체(암호화 키·DB·설정 포함)를 백업한다. 업로드/원본과 선택 Qdrant는 별도 백업한다. 업그레이드는 정지·백업·고정 버전 이미지로 재생성 순서다. 모델/차원/저장소 변경 또는 Qdrant 세대(`vector.epoch`) 불일치·재색인 필요 표시가 있으면 전체 재색인한다. 자세한 자원 한계는 [배포 검증](../verification/deploy.md)을 따른다.

관리자 복구는 프로젝트를 정지한 뒤 프로젝트 폴더에서 실행한다(환경 파일 이름을 맞추고 full이면 override도 추가).

```sh
docker compose --env-file .env -f docker-compose.yml run --rm --no-deps nasquire --recover-admin --data=/data
```

동일 이미지·데이터·UID/GID를 사용한다. 비밀번호는 터미널 입력으로만 전달하며 TOTP/복구 코드가 해제된다. 복구 컨테이너 종료 뒤 프로젝트를 시작한다. 실행 중 컨테이너의 콘솔에서 복구하지 않는다.
