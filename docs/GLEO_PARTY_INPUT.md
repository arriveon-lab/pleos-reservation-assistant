# Gleo 음성 인원 반영 (2026-11-11)

SET_PARTY_SIZE를 추가해 Gleo에서 성인/아이 수를 추천 입력 화면에 반영한다. 기존 OPEN_ARRIVEON과 SEARCH_MOCK_RESTAURANTS를 유지하며 공식 최대3개 function 범위다. 인원 설정은 검색·문의·예약을 시작하지 않는다. 기존 결과는 입력 변경으로 무효화하고 사용자가 검색을 요청한다. 예약 승인 권한을 부여하지 않는다.

## 계약

- 공식 [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)·[Callback](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)를 확인했다. activity function, ACTION_PARAM JSON/개별 extras, bare 및 패키지 접두사 action을 지원한다.
- 기존 추천 계약의 성인1–20/아이0–10 정수만 허용한다. 두 필드 필수이며 누락값을 0으로 추정하지 않는다. 기존 일반 예약 입력1–8/0–6과 별도 추천 입력을 혼동하지 않는다.
- 입력 파서/연결·CarUx 제한 확인, 준비 대기5초, 종료 시 폐기, Intent 소비 후 재생성 재실행 방지를 유지한다. 인원 입력 자체는 반복해도 검색 POST를 만들지 않는다.
- callback functionName=SET_PARTY_SIZE, success는 인원 반영만 의미한다. 영어 문구에 검색/전화/예약 없음과 화면 확인을 명시한다. 주행 제한·잘못된 입력은 fail이며 기존 숫자/예약 journal을 유지한다.

## 실제 검증

format/verifyAll PASS. UX121개 기존 결과는 UP-TO-DATE 포함이며 새 실행 건수로 세지 않는다. 같은 별도 APK의 GLEO instrumentation PASS: 입력 전용3/1→EDITING/후보0, 아이 수 누락 시 입력 불변, 기존 검색2/2→MOCK3곳, 중복/잘못된 입력/제한/UNKNOWN 차단, journal 불변. 직접 Intent 검사는 실제 발화와 구분한다.

사용자가 Gleo에 “어라이브온에서 성인 두 명, 아이 두 명으로 인원을 설정해줘”라고 말한 뒤 성공을 보고했다. 기기 시각05:08:53.157 COMMAND_RECEIVED,05:08:53.548 PARTY_APPLIED를 확인했고 화면의 성인2/아이2와 검색 전 입력 상태를 직접 확인했다. 이 시각은 앞선05:06–05:07 계측 검사와 구분한다. **실제 Gleo 발화→인원 반영 1회 성공**이며 모든 발화/반복 인식 안정성이나 배포 승인을 의미하지 않는다.

- Pleos2.1.0/API34/user10, ai.pleos.assistant.mock.destinationvalidation.
- APK SHA256 `bebfd0d2f3f445252350a37ee7966bd989c910d89a1d504fb0fe4181950dbc63`.
- 해당 설치 journal SHA256 전후 `cd53323c4c0e83e38df13804d60091ab6ff4da8c6658a567023d64524590f60d` 동일.
- 로컬 Git 제외: captures/gleo-party/input-only.png(계측3/1), actual-voice.png(실제2/2), work/gleo-party/device.log, app-events.log. 음성 원문/녹음/토큰을 파일에 보관하지 않았다.
- Gleo 재시작 직후 첫 showSession은 false였고 재연결 후 mShown=true를 확인했다. 사용자가 듣기 재개를 요청해 hide/show 후 실제 발화 성공. 첫 로그 시작시각 포맷 오류는 최근 앱 태그 로그로 재조회했다.

## 남은 범위

음성 예약 승인은 이번 구현에 포함하지 않는다. 별도 추천 후보→실제 문의/예약 계약 연결, 실제 목적지·ETA, 운영자 인증분리 공동 인수는 별도다. 이전 문서의 음성 미확인 이력은 당시 기록이며 이 입력 전용 명령1회 성공과 구분한다. 현재 UI를 그대로 두어 인원 확인 후 버튼으로 MOCK 후보 검색을 이어갈 수 있다.
