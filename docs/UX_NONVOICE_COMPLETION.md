# 음성 제외 핵심 흐름 보완 (2026-11-11)

사용자 요청에 따라 음성/Gleo 추가 개발과 음식 취향 확장은 제외했다. 기본 MOCK과 기존 인증·예약·UNKNOWN 기록을 유지한다. 서버 담당자의 범위를 바꾸거나 미제공 계약을 추측하지 않는다.

## 구현

예약이 명시적으로 REJECTED인 경우에만 ‘다른 예약 준비하기’를 제공한다. 안내 확인 뒤 최신 예약/통화를 GET으로 대조하고, 같은 예약/검색/통화의 BOOKING REJECTED인지 재검증한다. 원 검색·예약·통화 참조와 요청 시각을 rejectedReservations에 먼저 저장한 뒤 활성 화면 참조를 비운다. 저장 실패는 기존 화면/기록을 유지한다. 서버 예약을 삭제·취소하는 연산이 아니다.

새 검색은 사용자가 다시 실행한다. 같은 trip/인증 주체를 유지하며 새 검색 키·새 문의와 명시적 예약 승인이 필요하다. 과거 조회 결과를 새 후보로 재사용하거나 자동 발신하지 않는다. 화면에는 보관된 거절 예약 건수와 최근 요청 시각을 표시한다. PENDING/UNKNOWN/CONFIRMED 및 변경 거절에는 새 예약 준비를 제공하지 않는다. 제한 상태에서는 실행할 수 없다.

## 검증 구분

- JVM HTTP: 새 준비 단계는 POST0, 원 거절 예약 서버 GET 유지, 새 검색/문의/명시적 승인/수락, 새 예약 응답 유실 뒤 같은 키·본문 재전송/재생성, 기록 보존 검사.
- 차단: PENDING/UNKNOWN/CONFIRMED에서 새 준비 불가, UNKNOWN 주행 제한 및 저장 실패 시 기존 기록 보존.
- 기기: 아래 최종 실행 기록 참조. 테스트 전용 서버는 OwnershipSmokeApplication이며 인증분리 공동 인수 근거가 아니다. 별도 APK/메모리 journal과 loopback8082→18086만 사용하며 원래 앱의 업무 기록을 수정하지 않는다.

## 최신 선행 조건 확인

- #4: jjin70 답변대로 일반 BOOKING/CHANGE UNKNOWN 해결·종료 API 없음. 조회/기록 보존을 유지하며 앱에서 임의 종료하지 않는다.
- #40: 담당자의 일반 앱 Bearer와 별도 운영자 구성/실행 문서 인계가 아직 없음. 기존 합의인 문의1건→별도 outcome→같은 앱 GET+일반 토큰403은 공동 인수 잔여다.
- #25: 현재 Pleos user build에서 `adb shell cmd car_service help`조차 SecurityException(non-user build 필요). 명령 권한 우회나 임의 차량 API 사용 없음. 현재 환경의 창/글꼴/실제 초기 CarUx 조회·추가 제한 주입 검증과 실제 moving/서비스 단절을 구분한다.
- #1/#47 및 #52: 실제 목적지의 현재 경로 응답 연관성, ETA 의미·freshness 선행 근거가 미완료. PLEOS 예약 입력 차단 유지. #53 목적지 추천에는 ETA가 필요 없지만 실제 목적지 연관성을 임의 보장하지 않는다.

#4/#40/#25/#1의 위 선행 조건은 이번 앱 개발로 완료 처리하지 않는다. 담당/Assignee와 OPEN, PR #46 Draft를 유지한다.

## 실행 결과와 증거

- format / verifyAll / MOCK smoke-test.py PASS. UX JVM121개 실패·오류·skip0(새5개), 서버313개는 UP-TO-DATE 결과. Android JVM NO-SOURCE는 건수 제외. Lint 오류0/기존13경고, 새 억제 없음.
- Pleos2.1.0/API34/user10, 동일 별도 APK로 재예약 UI+HTTP 흐름 PASS: 거절 준비 버튼/확인 → 새 검색 → 문의 → 응답 GET → 승인 대화상자 → 수락 GET → 이전 거절 기록 표시. 승인 전 새 예약이 없는 것도 확인. 테스트 서버의 합성 운영자와 메모리 journal을 사용하므로 공동 인수/배포 인증 검증은 아니다.
- 주간1682dp/100%, 야간840dp/150% dashboard PASS. 재예약은 BOOKING REJECTED에서만 표시, 취소하면 상태 불변, 버튼/입력72dp·문구 잘림 검사. 실기기 창은 shell task resize이며 OEM 모든 창 구성 인수는 아니다.
- 같은 APK의 실제 CarUx baseline 조회·재연결·Activity 재생성 PASS. 추가 제한/실패 주입에서 승인창 닫힘·오래된 승인 무효·POST 차단·종료 뒤 입력 차단 PASS. 실제 moving 콜백/차량 서비스 장애는 미검증.
- APK SHA256: `bcfcbb956f26b830e7160395db70b9089cdb3edca6e2cc341ca19ad9cab6804d`.
- 로컬 Git 제외 증거: `work/nonvoice/`의 rebooking-selected.log, dashboard-wide-final.log, dashboard-narrow-final.log, driving-final.log, verify-final.log, smoke.log. `captures/nonvoice/rebooking-confirmed.png`, `rejected-narrow.png`. PNG 직접 확인.
- 기존 jointvalidation journal 전후 SHA256 `beb02ab84b57d492514117bb1e0eb7d069c3f4036e0fd1a22e5f29967b1f13b3`, destinationvalidation 전후 `06988d590f89f54fb0f3c5603a891b72daf456f2f1604fba8277d6d5a6cc6561`로 각각 동일. 원 DB/인증을 삭제·교체하지 않았다.

초기 실패도 보존한다. Android JSONObject.similar 미지원으로 검사 컴파일 실패 후 문자열 스냅샷 비교로 수정했다. 기기 HTTP에서는 즉시 문의 facts 적용을 전제한 예약이 차단되어 GET 대조 대기를 추가했고, 정렬된 후보에서 선택된 candidateId 대신 mock-1을 고정 대조하던 검사를 수정했다. dashboard는 버튼 contentDescription null 처리와 CarUx 초기 상태 대기를 보완했고, 반복 검사에서 유지된 입력값이 상한일 수 있으므로 증가 검사 시작값을 2로 고정한 뒤 원래 값으로 복원하도록 수정/재검사했다. 이는 테스트 절차 보완이며 권한·예약 조건을 완화하지 않았다.

야간840dp/150%에서 새 예약 준비 버튼까지 스크롤한 후 전체 버튼 영역이 실제 viewport에 보이는 검사도 PASS. `work/nonvoice/dashboard-scroll-final.log`, `captures/nonvoice/rebooking-action-narrow.png`에 기록했다. 검사 후 font_scale1.0/night=no/1682dp를 복원하고 테스트 전용8082 reverse와18086 서버만 종료한다. 기본8080/8081 연결과 기존 서버/DB는 유지한다.

재현: 테스트 classpath의 OwnershipSmokeApplication을 별도 작업 디렉터리·loopback18086에서 MOCK으로 실행하고 `adb reverse tcp:8082 tcp:18086` 후 `adb shell am instrument -w -e rebooking true ai.pleos.assistant.mock.destinationvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`. 기존 인증된 별도 검증 설치 및 정상 CarUx baseline이 필요하다. 테스트는 저장된 사용자 journal 대신 메모리 journal을 사용하며 종료 시 원 repository/snapshot을 복원한다. 기기 기록에는 원문 인증정보를 남기지 않는다.
