# Unraid Docker

[Unraid 공식 컨테이너 관리 안내](https://docs.unraid.net/go/docker-management/)와 [공통 설치·운영](generic-docker.md)을 함께 따른다. NAS 실기와 arm64는 미검증이며 Unraid 호스트/버전의 지원 아키텍처를 먼저 확인한다. Community Applications 등록은 수행하지 않았다.

1. `deploy/unraid/nasquire.xml`은 수동 템플릿이다. NAS 관리자가 [공식 사용자 템플릿 경로](https://docs.unraid.net/community-applications/)인 `/boot/config/plugins/dockerMan/templates-user/`에 `my-nasquire.xml`로 두고 Docker → Add Container → 사용자 템플릿에서 선택한다.
2. 템플릿 기본 데이터 폴더 `/mnt/user/appdata/nasquire`를 준비하고 실제 실행 계정 UID/GID에 쓰기 권한을 준다. 기본 `--user=99:100`은 예시다. **PUID/PGID 환경값과 Extra Parameters의 `--user=UID:GID`를 함께** 맞춘다. 공개 이미지가 환경값만으로 사용자를 변경하지는 않는다. root/Privileged는 사용하지 않는다.
3. 일반 문서 `/mnt/user/documents` → `/sources/documents`는 Read Only다. 업로드가 필요하면 일반 문서·데이터와 겹치지 않는 앱 전용 폴더 → `/uploads`만 Read/Write로 추가하고 `kind=upload`로 등록한다. `/data` 외 루트 파일시스템은 Read Only, `/tmp`는 제한된 tmpfs를 유지한다.
4. 기본 `:lite`와 `--memory=512m --memory-swap=512m`를 사용한다. full은 Repository를 `:full`로, 두 메모리 인자를 `2g`로, tmpfs를 256m로 바꾼다. lite 기존 최대 JVM RSS 281.9 MiB, 내장 E5 855.7 MiB, ML 리랭커 약 620~632 MiB, 두 모델 ML 1047.8 MiB다. full 2 GiB는 부하 시험 출발값이며 OCR 자식·Tesseract·파일 캐시 포함 보장이 아니다. 내장 모델·리랭커에 512 MiB를 사용하지 않는다.
5. 템플릿은 호스트 `127.0.0.1:8080`에만 바인딩한다. HTTPS 프록시가 다른 컨테이너라면 전용 네트워크로 연결하고 앱 포트 직접 공개를 제거한다. `NASQUIRE_PUBLIC_URL=https://nas.example.com`, Secure 쿠키, 정확한 연결 IP의 `NASQUIRE_TRUSTED_PROXIES`를 맞춘다. 공유 HTTP Basic·관리 쿠키 때문에 HTTPS는 필수다. 프록시·WAF 로그에서 공유 경로 `/s/**`, Authorization, 다운로드 쿼리를 제외한다. [Nginx 예시](generic-docker.md#https프록시로그)를 참고한다.

Docker 탭에서 앱을 정지한 후 `/data` 전체(키·DB·설정·모델)를 백업한다. 업로드/원본과 원격 Qdrant는 별도 백업 대상이다. 업그레이드는 백업 후 버전 태그를 고정해 재생성한다. 모델/차원/벡터 저장소 변경과 Qdrant `vector.epoch` 세대 불일치·`reindexRequired` 발생 시 전체 재색인한다. full → lite는 활성 full 기능을 먼저 끈다.

관리자 복구는 Docker 탭에서 앱을 정지한 뒤 터미널에서 **현재 이미지·실제 데이터 경로·UID/GID**로 실행한다.

```sh
docker run --rm -it --user 99:100 --read-only --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -v /mnt/user/appdata/nasquire:/data \
  ghcr.io/nasquire/nasquire:lite --recover-admin --data=/data
```

full/버전 고정 배포라면 같은 태그를 쓴다. 비밀번호는 표준 입력에서만 받고 TOTP/복구 코드를 해제한다. 복구 종료 뒤 Docker 탭에서 앱을 시작한다. [검증 한계](../verification/deploy.md)를 확인한다.
