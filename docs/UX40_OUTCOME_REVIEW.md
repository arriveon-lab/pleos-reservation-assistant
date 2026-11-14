# MOCK 결과 계약과 앱 대조 (2026-11-11)

기준: [zito-git의 #40 outcome 인수안](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/40#issuecomment-6792997963), main `286f2a1` 계약1.1.0. 서버 계약/API는 변경하지 않았다. PR #46에서 앱 결과 처리만 보완한다.

## 발견과 수정

서버가 변경 수락·거절 후 pendingChangeCallId를 비우면 앱 refresh가 원래 BOOKING 통화를 선택해 CHANGE 결과를 잃었다. 변경 응답/검증된 GET에서 lastChangeCallId를 로컬 journal에 추가 보존하고, 완료 후에도 같은 CHANGE 통화를 조회한다. id·reservationId·searchId·purpose 대조와 전체 GET/저장 성공 후 화면 반영을 유지한다. 새 변경의 pending 참조가 이전 완료 참조보다 우선한다.

화면도 변경 수락은 변경된 시각/비용 확인, 변경 거절은 기존 예약 유지/재요청 가능 시각 확인으로 구분한다. UNKNOWN은 조회·대조만 안내하며 자동 재발신하지 않는다. 일반 UNKNOWN 종료 API를 추가하거나 검색 reconcile로 우회하지 않았다.

기존 journal에 필드를 덧붙일 수 있으나 기존 식별자·pending 본문/키는 삭제하지 않는다. 예전 앱에서 이미 완료되어 참조가 사라진 과거 변경 이력을 임의로 추정·복원하지 않는다.

## 대조 범위

| 사례 | 결과와 검증 수준 |
|---|---|
| 문의 ANSWERED → facts 반영 → 명시적 예약 승인 | 기존 MockRepositoryTest의 로컬 HTTP 흐름 재실행 PASS |
| 예약 ACCEPTED/REJECTED/UNKNOWN | 로컬 HTTP 검사 PASS. 미수락을 확정으로 바꾸지 않음 |
| 0원 변경 ACCEPTED/REJECTED/UNKNOWN | 새 3개 HTTP 검사 PASS. 원 요청 시각 보존, 수락만 확정 시각 변경, 거절은 cooldown, UNKNOWN은 pending 유지. GET·Repository 재생성 후 CHANGE 통화 유지 |
| 예약/변경 수락의 시간+1분 또는 비용100원 불일치 | 새 4개 HTTP 검사 PASS. UNKNOWN, 기존 확정 시각 보존 |
| 동일 eventId/본문 재전달 | 위 4개에서 실제 HTTP 재전달 응답 동일 확인 |
| 다른 본문/통화로 eventId 재사용, 최종 통화 새 이벤트, 잘못된 문의 facts | 기존 서버 계약 테스트로 보호. 이번 서버 테스트는 UP-TO-DATE이며 새 실행으로 세지 않음 |
| GET404/참조 불일치/저장 실패 | 기존 UnknownRefreshTest 재실행 PASS. 기존 상태와 요청 보존 |

로컬 HTTP 검사는 임시 DB의 OwnershipSmokeApplication을 사용한다. 운영자와 앱 인증이 분리된 공동 인수 증거가 아니다. 앱에 outcome 입력 기능을 추가하지 않았다.

## 검사 및 기기 증거

- format, verifyAll, MOCK smoke-test.py PASS. UX JVM116개 실패/오류/skip0, 서버313개는 기존 UP-TO-DATE 결과. Android JVM NO-SOURCE는 건수 제외.
- Lint 오류0, 기존 경고13개. 새로운 억제 없음.
- 기기 검증 결과는 아래 최종 기록을 따른다. 첫 새 fixture는 render 직후 치수 검사로 Input target too short가 발생했다. UI layout 완료 후 검사하도록 도구를 수정했다.

## 남는 범위

- 예약 REJECTED 후 새 명시적 예약은 서버 계약상 가능하지만 앱은 현재 재예약을 차단한다. 기존 예약 이력 보존과 별도 승인을 갖춘 재시도 화면은 아직 미구현이다. REJECTED를 UNKNOWN으로 바꾸지는 않는다.
- #40의 앱 Bearer 문의 → 별도 운영자 outcome → 같은 앱 GET 및 앱 outcome403은 담당자의 분리 실행환경 인계 후 공동 검증한다. 이번 로컬 검사로 공동 인수 완료 처리하지 않는다.
- #4의 일반 BOOKING/CHANGE UNKNOWN 해결·종료 계약, #25 실제 차량 신호 검증은 남는다. #40 OPEN/PR #46 Draft 유지.

## 최종 기기 기록

Pleos Connect2.1.0/API34/user10의 별도 destinationvalidation 설치에서 dashboard instrumentation PASS. 예약4상태·변경 UNKNOWN/수락/거절·cooldown·제한 안내를 렌더하고 입력/버튼72dp 및 잘림을 검사했다. 수락/거절 PNG도 직접 확인했다. 이는 화면 fixture이며 기기에서 운영자 outcome을 주입한 공동 시연이 아니다. JVM HTTP 흐름 검증과 구분한다.

- 명령: `adb shell am instrument -w -e dashboard true ai.pleos.assistant.mock.destinationvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`
- APK SHA256: `39638a4c07550d58a99f2ade78e993e5100b863fd13bc075a475a0a7c464f1cb`
- 로컬 증거: `captures/outcome-review/change-accepted.png`, `change-rejected.png`; `work/outcome-review/device-final.log`, `verify.log`, `smoke.log`. Git 제외이며 원격 업로드하지 않았다.
- 원래 jointvalidation journal SHA256 전후 동일: `beb02ab84b57d492514117bb1e0eb7d069c3f4036e0fd1a22e5f29967b1f13b3`. 원 설치 APK/DB/인증은 변경하지 않았다.
