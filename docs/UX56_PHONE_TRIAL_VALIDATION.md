# UX-56 별도 전화 시험 세션

2026-11-12. 기존 MOCK 앱 인증과 별도로 전화 시험 서버에 접속하는 Android 개발 화면이다. 통합 세션 계약의 제품 인수 완료가 아니다.

- `https://43.201.115.170/phone-test/docs/`는 문서 경로다. 배포 Nginx와 OpenAPI의 `servers: /`를 대조했으며 실제 호출은 같은 호스트의 `/api/state`, `/api/action`, `/api/live/callback-test-target`, 명시적 opt-in `/api/live/dial`이다.
- 별도 Basic 인증과 서버 쿠키를 `phone-trial-v1` Keystore 키·`phone-trial.enc`에 암호화 저장한다. 기존 `pleos-auth-v1`·MOCK journal을 읽거나 교체하지 않는다. 로그인·번호는 Git/로그에 남기지 않는다.
- 시험 번호 START는 동일 세션 소유다. 타 브라우저 활성 등록은 `CALLBACK_TEST_ACTIVE`로 거절되며 강제로 종료하지 않는다. 사용자가 기존 등록을 종료한 뒤 앱 등록 성공을 확인했다.
- 사용자 요청에 따라 실제 매장이 아닌 합성 `test-arriveon-fixture` 한 곳을 별도 서버 directory override로 준비했다. 이름·주소·연락처·출처는 합성 표시 자료이며 실제 식당 정보로 검증된 것이 아니다. 디렉터리의 번호는 발신 대상이 아니며 시험 번호 입력 모드를 유지했다.
- 배포 전 활성 통화 0 확인, 서버 DB 보호 백업. 다른 담당자의 `pleos-paired-phone-v1` 배포가 관측돼 새 이미지와 compose 파일을 유지하고 시험 directory override만 추가했다. 기존 데이터를 삭제하지 않았다. 원격 override는 `/opt/arriveon-integration/phone-trial/compose.phone-trial.yml`, 백업은 같은 보호 디렉터리다. 복원 시 현재 배포 구성에서 이 override만 제외해야 하며 과거 이미지를 덮어쓰면 안 된다.
- 가상 시험자·2명·11월 13일 19:00 시험 예약 생성과 READY 승인을 실제 앱/EC2에서 확인했다. 사용자의 별도 비용 승인 후 앱에서 발신 작업 **1건** 실행, 최대 시간 종료(`max_duration`)·예약 UNKNOWN·확정 시각 없음. 재발신하지 않았다. 이전 HTTP502 시점에는 작업 0건이었다. 총 청구 비용은 미확인이다.
- 앱은 발신 전 같은 소유자 등록·전화 준비/최대60초·예약 ID/버전/READY를 대조한다. `dialAttempt`를 암호화 저장한 다음 요청하며, 이후 재발신하지 않고 GET으로 대조한다. 기본 빌드는 `phoneTrialDial=false`이며 실제 발신은 opt-in 빌드와 확인창이 필요하다.
- 합성 기기 검사: 등록 거절 기록 보존, 같은 키 재전송, 재구성 후 쿠키/요청 복구, 승인/결과 UI, 발신 응답 유실 시 재발신 차단, 제한 상태 입력 차단 PASS. 합성 결과를 실제 예약 확정 성공으로 세지 않는다.
- `verifyAll`, `verifyAndroid`, 계측 APK 빌드, `scripts/smoke-test.py` PASS. Lint 오류 0·기존 경고22. 기존 세 설치의 예약/인증 및 별도 시험 journal 업데이트 보존 비교 PASS(로컬 `work/ux56-phone-trial`).

남은 범위: 통합 세션 최신 계약 적용, 실제 후보 전달·CONFIRMED 저장, 식당 재전화/변경 공동 인수, 실제 비용 대조. #56 OPEN·Draft 유지. API 연동을 완료한 최종 앱으로 표시하지 않는다.
