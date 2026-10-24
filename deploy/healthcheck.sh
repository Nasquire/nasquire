#!/bin/sh
set -eu
# ML은 모델 준비 뒤 리스너를 열며 인증 없는 요청을 401로 거부한다.
# 토큰을 헬스체크 인자·로그에 전달하지 않는다.
if [ "${NASQUIRE_MODE:-search}" = ml ]; then
    path=/v1/models
    expected=401
else
    path=/
    expected=200
fi
status=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 4 \
    "http://127.0.0.1:${PORT:-8080}${path}")
[ "$status" = "$expected" ]
