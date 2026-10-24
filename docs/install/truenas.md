# TrueNAS SCALE 커스텀 앱

Docker 기반 Apps를 제공하는 SCALE 릴리스가 대상이다. 구형 Kubernetes 기반 Apps와 CORE의 jail 설치 안내가 아니다. [공식 Custom Apps 안내](https://apps.truenas.com/managing-apps/installing-custom-apps/)와 [공통 설치·운영](generic-docker.md)을 따른다. NAS 실기·arm64 실행은 미검증이다.

1. 앱 풀에 데이터 dataset(`/mnt/tank/apps/nasquire` 예시)을 만들고 실행 UID/GID에 쓰기 ACL을 준다. 일반 문서 dataset은 읽기/탐색 ACL만 준다. 앱 전용 업로드 dataset은 데이터·일반 문서와 별도 경로로 준비한다. 기존 문서 ACL을 일괄 변경하지 않는다.
2. Apps → Discover Apps → Custom App에서 `ghcr.io/nasquire/nasquire:lite`를 지정하거나, 지원 화면의 Install via YAML에서 `deploy/docker-compose.yml`을 붙여 넣는다. YAML은 로컬 `build`를 제거하고 `${...}`를 실제 경로/숫자로 치환한다. UI 이름은 릴리스별로 다를 수 있다. User/Group ID를 PUID/PGID에 맞춰 지정하거나 YAML의 `user: "UID:GID"`를 사용한다. 환경변수만으로 공개 이미지의 사용자는 바뀌지 않는다. root/Privileged는 불필요하다.
3. host path 데이터 → `/data`(쓰기), 일반 문서 → `/sources/documents`(Read Only)를 지정한다. 업로드는 별도 전용 폴더 → `/uploads`만 쓰기 허용하고 `kind=upload`로 등록한다. 루트 파일시스템 read-only, `/tmp` tmpfs와 no-new-privileges, capabilities 제거 설정을 유지한다.
4. lite 메모리 한도 512 MiB로 시작한다. full은 `:full`과 full override를 반영하고 2 GiB부터 부하에 맞춰 조정한다. 기존 JVM RSS는 lite 최대 281.9 MiB, 내장 E5 855.7 MiB, ML 리랭커 약 620~632 MiB, 두 모델 ML 1047.8 MiB다. OCR 자식·Tesseract·캐시까지 포함한 full cgroup 실측은 없으므로 2 GiB 보장도 없다. 512 MiB에서 내장 모델/리랭커를 켜지 않는다.
5. HTTPS 리버스 프록시를 별도로 두고 `https://nas.example.com`만 제공한다. `NASQUIRE_PUBLIC_URL`, Secure 쿠키, `NASQUIRE_TRUSTED_PROXIES`의 실제 연결 프록시 IP를 설정한다. 호스트 프록시는 루프백 바인딩, 별도 프록시 앱은 전용 네트워크/방화벽으로 연결해 앱 포트 외부 직접 접근을 막는다. 공유 Basic 인증·쿠키 때문에 HTTPS가 필수다. 프록시/앞단 로그에서 공유 `/s/**` 경로·Authorization·다운로드 쿼리를 제외한다. [공통 프록시 예시](generic-docker.md#https프록시로그)를 참고한다.

앱을 정지한 상태에서 `/data` dataset 전체(키·DB·설정 포함)를 스냅샷/백업하고 별도 장치에 복제한다. 실행 중 DB의 비일관 스냅샷을 정상 백업으로 가정하지 않는다. 업로드와 일반 원본·선택 Qdrant 컬렉션도 별도로 백업한다. 업그레이드는 정지·백업·고정 버전 이미지로 재생성 순서다. 모델·차원·저장소·컬렉션이 바뀌거나 Qdrant `vector.epoch` 세대 불일치/재색인 필요 상태면 전체 재색인한다. full 기능을 끄기 전 lite로 전환하지 않는다.

관리자 복구는 Apps에서 해당 앱을 정지하고 NAS 관리자가 Docker CLI를 사용할 수 있는 셸에서 실행한다(경로·UID/GID·이미지를 실제 배포값에 맞춘다).

```sh
docker run --rm -it --user 1000:1000 --read-only --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -v /mnt/tank/apps/nasquire:/data \
  ghcr.io/nasquire/nasquire:lite --recover-admin --data=/data
```

셸 실행이 제한된 환경에서는 같은 이미지·host path·사용자에 복구 인자를 넣은 임시 커스텀 앱과 대화형 표준 입력을 사용한다. 비밀번호를 YAML/인자/환경/로그로 넘기지 않는다. TOTP/복구 코드를 해제한 뒤 임시 앱을 종료하고 원래 앱을 시작한다. [배포 검증](../verification/deploy.md)에 실제 검증 여부를 기록한다.
