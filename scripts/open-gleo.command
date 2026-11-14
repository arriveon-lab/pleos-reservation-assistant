#!/bin/bash
# Local emulator helper: opens Gleo listening, never launches ArriveOn directly.
set -euo pipefail

adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then
  adb_bin="$(command -v adb || true)"
fi
if [[ -z "$adb_bin" || ! -x "$adb_bin" ]]; then
  echo 'Android SDK의 adb를 찾지 못했습니다. ANDROID_HOME을 설정해 주세요.' >&2
  exit 1
fi

# Require an explicit serial when more than one device is connected.
run_adb() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$adb_bin" -s "$ANDROID_SERIAL" "$@"
  else
    "$adb_bin" "$@"
  fi
}
run_adb get-state >/dev/null
active_user="$(run_adb shell am get-current-user | tr -d '\r')"
assistant_service="$(run_adb shell settings --user "$active_user" get secure voice_interaction_service | tr -d '\r')"
if [[ "$assistant_service" != ai.umos.ambientai/* ]]; then
  echo '현재 사용자의 기본 음성 서비스가 Gleo가 아닙니다. 설정을 확인해 주세요.' >&2
  exit 1
fi

echo '에뮬레이터 화면을 옆에 두고 준비해 주세요.'
echo 'Enter: Gleo 듣기 시작 / q + Enter: 종료'
echo '듣는 중 표시가 뜨면 “어라이브온 켜줘”라고 말해 주세요.'
echo '이 도구는 앱 명령·검색·전화·예약을 직접 실행하지 않습니다.'
while true; do
  printf '\n준비되면 Enter를 누르세요: '
  IFS= read -r choice || break
  [[ "$choice" == "q" || "$choice" == "Q" ]] && break
  run_adb shell cmd voiceinteraction hide
  run_adb shell cmd voiceinteraction show
  echo '듣기를 요청했습니다. 끝났다면 Enter로 다시 시작할 수 있습니다.'
done
