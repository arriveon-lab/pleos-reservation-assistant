# 제공 HTML 디자인의 Android MOCK 연결

2026-11-12, `feat/56-mock-phone-integration`의 로컬 변경. 이슈 게시·커밋·push 및 실제 발신은 수행하지 않았다.

## 적용 범위

사용자가 전달한 `ArriveOn 차량 UI 재설계.html`의 파란 강조색, 주야간 테마, 목적지 요약/단계별 본문, 큰 인원 조절 버튼, 후보 카드, 전체 화면 승인, 확정 시간 및 대화 내역을 Android native View로 구현했다. 새 런처는 `FinalReservationActivity`다. 기존 `ReservationViewModel`, 인증 저장소 및 `MockPhoneController`를 재사용하고 기존 화면은 개발 도구에서 접근한다. 연한 테두리는 저장소 대비 검사 기준에 맞춰 보정했다.

연결은 EC2 `/pleos-mock`의 기존 MOCK 계약이다. 후보 선택만으로 승인 요청을 보내지 않으며 명시적 승인 시 후보 ordinal과 예약 시각을 보낸다. 대화/시간/인원/조건 결과는 서버 응답에서 표시한다. 예약금 필드가 없어 금액을 만들어내지 않고 미확인으로 표시한다. 변경 동의·거절 버튼은 비활성이며 API를 호출하지 않는다.

현재 서울숲, 남은 25분, 내일 19:00 도착은 명시된 MOCK 시연값이다. 여유 시간 0/10/20분이 예약 희망 시각에 반영된다. 실제 내비 ETA 연동으로 간주하지 않는다. 주차 필수와 인원·유아 의자·계단 없는 접근은 기존 검색 계약을 따른다. 반려견은 API 미지원으로 비활성이다. 실제 식당/전화 세션 통합은 이번 연결 범위가 아니다.

## 실제 실행 결과

Pleos Connect 2.1 / API 34, emulator-5554, user 10에서 실행했다. 새 검증 설치 `ai.pleos.assistant.mock.designvalidation`에 영속 MOCK 기록을 남겼으며 기존 설치의 미확인 요청을 지우거나 대체하지 않았다.

| 절차 | 기대 결과 | 관측 결과 |
| --- | --- | --- |
| 조건 입력 후 후보 검색 | 서버 후보를 화면에 표시 | PASS, 실제 MOCK API 및 native 버튼 실행 |
| 후보 선택 후 뒤로 가기 | 승인/예약 기록 생성 없음 | PASS, 선택·뒤로 가기 직후 hasRequest=false |
| 다시 선택하고 명시적 승인 | 요청 인원·시각으로 확정 | PASS, CONFIRMED, 4명, 19:10 |
| 대화 내역 열기 | 서버 conversation 전체 문구 표시 | PASS, 응답의 각 message와 View 트리 대조 |
| force-stop 후 재실행·조회 | 저장 예약 복구, 새 승인 없음 | PASS, 기존 요청에서 GET 복구 후 CONFIRMED·대화 표시 |
| 변경 동의·거절 | 호출 불가 | 구현상 두 버튼 disabled, 동의 버튼 계측 검사 PASS |
| 주간 전체/야간 840dp 결과 화면 | 결과와 하단 조회 버튼 표시 | 직접 캡처 확인, 좁은 화면 본문 스크롤 |
| 기존 ec2validation 앱 install -r 후 실행 | 기존 예약 보존 | 기존 3명·19:00 확정 기록 표시 |
| 기존 3개 설치 기록/인증 비교 | 파일 손실·변경 없음 | 존재 파일 SHA-256 동일, 없던 phone journal은 계속 없음 |

새 예약 결과의 `externalCalls=0`을 검사했다. 실제 전화, 외부 추천 및 유료 API를 호출하지 않았다.

## 자동 검사 및 증거

- `./gradlew -PincludeAndroid=true -PnavigationDemo=true -PmockPhoneDemo=true -Pec2MockDemo=true -PmockDesignValidation=true verifyAll :android:app:assembleDebugAndroidTest` PASS (포맷, 서버/코어, Android Lint/빌드, 주야간 대비 검사 포함; 캐시된 작업은 재실행 건수로 세지 않음).
- `python3 scripts/smoke-test.py` PASS.
- `FinalFlowChecks` FULL/RESTORE PASS. FULL 이후 헤더/카운터 폭과 정렬만 보정하고 다시 verifyAll 및 RESTORE를 실행했다.
- 일반 ec2validation APK 빌드·기존 설치 업데이트 PASS.
- 로컬 증거: `work/final-design/full.log`, `restore.log`, `build.log`, `smoke.log`, `before-records.json`, `after-records.json`.
- 화면: `design-conditions.png`, `design-candidates.png`, `design-approval.png`, `design-result-FULL.png`, `design-conversation-FULL.png`는 최초 FULL 캡처. 헤더 수정 후 최종 결과 화면은 `current.png`, `narrow-night.png`, `existing-app-updated.png`다.

## 미검증 / 의도적 제외

실제 전화와 실제 식당 추천, 실제 내비 ETA, 식당 재연락의 변경 동의·거절, 음성 입력의 새 화면 연결, 실차/공동 인수는 이번 통과 범위가 아니다. 새 화면의 UNKNOWN·네트워크 유실·주행 전환 전체 회귀는 별도 미검증이다. 기존 컨트롤러의 차단 규칙은 재사용하지만 이전 화면 검사만으로 새 화면 검증을 통과 처리하지 않는다. 원격 CI는 실행하지 않았다.
