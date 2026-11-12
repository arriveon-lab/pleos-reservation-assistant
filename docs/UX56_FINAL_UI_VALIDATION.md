# 최종 Android UI 적용·재실행 복구 검증 (#56 / #39)

2026-11-12 KST. [최종 화면 설계](UX56_FINAL_APP_DESIGN.md)를 기존 Native Android View 구조에 적용했다. HTML 자체를 WebView로 넣거나 인증/예약 저장소를 교체하지 않았다. 기존 #46/#56 기능, 실제조회 전송 차단, UNKNOWN 보존, 음성 인원 명령 수신부는 유지한다. 실제 전화·실예약·외부 추천 호출은 0회다.

## 적용 범위

- ArriveOn 헤더와 MOCK 안내, 단계 표시: 조건 확인 → 후보 선택 → 승인 → 결과.
- 입력 제목·인원 증감·필수 조건을 유지하고, 후보 수는 서버 반환 개수대로 표시한다. 넓은 창에서는 카드 비교, 좁은 창에서는 세로 카드로 전환한다. 주소/조건 상세·실제조회 내비 선택 기능을 삭제하지 않았다.
- 후보의 중복 '누락 조건' 나열을 줄이고, 주차/동행 조건의 미확인 표시는 유지한다. 개발용 응답 선택은 접힌 시험 설정으로 이동한다.
- 승인은 별도 큰 확인창으로 구성한다. 상호·시각/KST·인원·필수 조건·가상 예약자/0원·MOCK 의미를 표시하고 명시 승인 전에는 전송하지 않는다.
- 결과는 상호와 시각/인원을 분리하고 확정시간을 강조한다. 진행/거절/UNKNOWN·기존 결과 조회 행동은 유지한다. 대화는 화자별 카드의 별도 상세 상태로 분리하고 결과로 돌아올 수 있다.
- 주간 녹색/야간 민트 액센트와 기존 semantic 대비·주행 제한 처리를 사용한다. 긴 대화는 입력 제한 시 숨긴다. 72dp 버튼, 20–36sp 계층과 수동 스크롤을 사용한다.
- 전용 `mockUiValidation=true`는 EC2 MOCK의 별도 applicationId `ai.pleos.assistant.mock.uivalidation`만 선택한다. 실제 구현/영속 저장소를 사용하며 기존 설치의 미확인 요청을 지우거나 복구한 것으로 세지 않는다. 기본 옵션은 false다.

## 실행 절차·기대·실제

환경: Pleos Connect2.1.0/API34/emulator-5554, Android 사용자10. EC2 HTTPS `/pleos-mock`의 가상 데이터/모의 전화만 사용. 음성 테스트는 수행하지 않았다.

| 절차 | 기대 | 실제 |
| --- | --- | --- |
| 별도 검증 설치 PHONE_FULL | 가상 서울숲 버튼→목적지 확인→후보→취소→재선택·승인→CONFIRMED | PASS, 취소 시 예약 요청 없음, 실제 앱 SharedPreferences에 승인/키/결과 저장 |
| 서버 대화 대조 | 받은 모든 conversation.message 표시, externalCalls=0 | PASS, 화자별 카드·상세/결과 전환 |
| 업데이트 설치·force-stop 후 PHONE_RESTORE | 기존 attemptId/요청/키 보존, 신규 POST 없이 같은 서버 GET 성공 | PASS. 성공 GET 정확히1회이며 같은 attemptId 반환을 전송 경계에서 확인. 단순 로컬 CONFIRMED 표시만으로 통과하지 않음 |
| 재실행 후 대화 | 저장된 대화와 서버 대화 유지 | PASS, attemptId/taskId/key hash/status/대화개수 전후 동일 |
| 야간840dp PHONE_REPEAT | 단일 열 비교·승인창 버튼 접근·글자 잘림 없음 | 최종 PASS. 아래 초기 실패 수정 포함 |
| 주간1682dp PHONE_UNKNOWN | 미확인 상태·추가 승인 차단·GET 동일 작업 | PASS. 시험 요청은 메모리로 격리해 위 영속 확정 예약을 보존 |
| 기존 EC2 앱 install-r·force-stop·PHONE_RESTORE | 기존 인증·예약 보존 및 실제 GET 성공 | PASS. 이전 예약의 대화도 화면에 표시 |
| 기존3설치 journal 전후 비교 | ec2validation/realplacevalidation/realplacefiltervalidation 원 기록 유지 | 일반·전화 journal 해시 모두 동일 |

테마는 시험 전 `no`(주간)를 기록하고 야간 검사 후 다시 주간으로 복원했다. 창은840dp 검사 후1682dp로 복원했다. 기존 EC2 앱은 새 UI로 업데이트한 상태다. 새 검증 설치는 원 기록을 보존한 별도 시험 설치다.

### 발견·수정한 문제

초기 분할창에서 내부 텍스트/높이 검사는 통과했지만 승인창 우측 버튼이 앱 창 밖으로 잘렸다. 캡처를 확인해 전체 displayMetrics.widthPixels 대신 실제 Activity decorView 너비를 사용하도록 수정했다. 승인 버튼의 화면상 좌표가 앱 창 경계 안에 있는 검사를 추가했다. 수정 후 같은 야간840dp 시나리오 재실행·캡처 확인 PASS. 초기 캡처는 `initial-clipped-approval.png`로 보존하며 성공 증거로 사용하지 않는다.

## 검사와 증거

- `./gradlew -PincludeAndroid=false format` PASS.
- `./gradlew -PincludeAndroid=true -PnavigationDemo=true -PmockPhoneDemo=true -Pec2MockDemo=true -PmockUiValidation=true verifyAll :android:app:assembleDebugAndroidTest` PASS. 캐시/UP-TO-DATE 포함.
- 기존 설치 대상 `mockUiValidation` 없는 `verifyAndroid`/test APK 빌드 PASS.
- `python3 scripts/smoke-test.py` PASS(가상 인증/HTTP/DB 재시작/LIVE 차단). 이번 수정은 서버/API 변경이 아니다.
- Lint0오류/22경고. 기존 PrivateApi·의존성/리소스/문자열 경고와 UI 전환 후 사용하지 않는 이전 문구 리소스가 포함된다. 억제 추가 없음.
- 최종 기존 EC2 APK SHA256 `f31b06b32a1c419426cb7c86e2320e72658eab82ac911efb8d705e1dc48dfef7`.
- 로그: `work/ux56-final-ui/{build,existing-build,smoke,full,night-narrow,unknown,restore,existing-restore}.log`.
- 기록 비교: 같은 폴더 `before.json`, `after.json`, `new-reservation-before.json`, `new-reservation-after.json`. 토큰/키 원문 없음.
- 화면: `captures/ux56/final-ui/`의 후보/승인/대화, `night-narrow-*`, `restored*`, `existing-restored*`. full 접두사 최초 화면보다 마지막 restored/야간 재검사 화면을 최종 근거로 사용한다.

## 남은 경계

실제 WEB_SEARCH 후보의 전화 예약 연결, 실차, 실제 주행 이벤트, ETA·SDK 응답 연관성, 음성 예약, 공동 인수·release 서명/제출은 완료로 표시하지 않는다. 이번 주행 제한은 기존 구현을 보존했으며 실차 주행을 새로 검증하지 않았다. 기존 예약 변경/운영자 공동 시연을 이번 가상 예약 재실행 검사로 대체하지 않는다. #56 OPEN, PR #59 Draft 유지.
