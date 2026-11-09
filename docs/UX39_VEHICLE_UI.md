# UX-04 차량 화면 개선·동일 APK 검증 (#39 / #25)

2026-11-09. 구현 `9ce4571`(요약)·`762f5a3`(UI)·`27d2cf1`(기기 검사). #36 `7e24482` 인증·미리보기와 #44 `86cd1db` 실제 차량 제한 연결을 보존했다. 기본 MOCK, 일반 앱 토큰의 운영자 권한 없음, 별도 미리보기의 문의/예약 전환 없음은 그대로다. 이번 기록은 **로컬 Pleos 검증**이며 #40 공동 예약 시연이나 #16/#26 공동 인수 완료가 아니다.

## 공식 기준과 적용

- [기본 원칙](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro): 핵심 정보·다음 동작을 먼저 표시하고, 응답 대기는 로딩/입력 차단으로 안내한다. 긴 근거·출처는 상세 영역으로 분리했다.
- [레이아웃](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout)·[Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window): 가로/세로 창 변화에 대응한다. 시스템 GNB·상태 표시줄·윈도 바·키패드를 앱 안에 복제하지 않는다. 숨김과 프로세스 종료를 같은 것으로 취급하지 않는다.
- [색상](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors)·[Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography): 의미별 주야간 리소스, 본문22–24sp/제목28–32sp, 중립적인 시스템 sans를 사용한다. 이 색상은 앱 자체 팔레트이며 OEM 색상 토큰의 픽셀 단위 복제나 공식 인증을 의미하지 않는다. 물리 거리·mm 가독성은 미검증이다.
- [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button)·[Icon button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/icon-button): 주·보조·비활성·눌림 상태를 구분하고 중요한 승인에는 텍스트를 유지했다. 버튼/입력 최소72dp와 줄바꿈을 사용한다. 진동/음성 피드백은 이번 인수 범위가 아니다.
- [App Icon](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/app-icon): 위치 핀과 확인 표시를 조합한 고유 foreground/background adaptive icon을 적용했다. 기본 달력 아이콘을 교체했으며 시스템 App library를 구현하지 않았다.

## 구현 범위

| 영역 | 변경 후 동작 | 보존한 경계 |
| --- | --- | --- |
| 첫 화면 | 가상 목적지·실제 ETA 미연결·연습 도착 시각·동행 조건·현재 단계 우선 표시 | #1 ETA 검증 전 실제 차량 시각으로 변환하지 않음 |
| 개발 조작 | 연결·인증 갱신·제한 주입을 접힌 개발 진단에 배치 | 필요한 사용자 인증/복구·차량 제한 실패 안내는 본문 유지 |
| 기존 예약 후보 | 넓은 창은 3개 카드 나란히, 좁은 창은 세로 배치. 주차/아동/도착 대기/예약 순서 통일 | eligible은 검토 대상, bookable은 요청 가능. 예약 확정과 구분 |
| 후보 사실 | UNKNOWN·false·만료·추정 구분, 도착 대기는 ARRIVAL과 해당 ETA 일치 확인 | NOW를 ARRIVAL로 대체하지 않음. 상세 출처/관측/적용/만료/확실성 보존 |
| 결과·승인 | 상태 변경 시 상단 결과·다음 행동, 접수/확정/거절/UNKNOWN·변경 미확인 안내 | 승인 창과 POST 전 차량 제한 재검사, 원 예약 시간/0원 경계 유지 |
| 별도 미리보기 | 검색 후 조건을 접고 후보 비교 우선, 근거/누락/UNKNOWN 질문/출처 펼치기 | ordinal을 예약 ID로 사용하지 않음. 입력/종료/제한/테마 재생성 시 이전 결과 무효화, 자동 재검색 없음 |
| 테마·입력 | 시스템 uiMode에 따른 values/values-night, 입력 draft 저장, 키패드 전체 추출 화면 방지·완료 시 닫기 | 새 인증/예약을 자동 생성하지 않음 |

## 동일 APK 기기 검증

- 기기: Pleos Connect v2.1.0 / API34 / 2560×1440 / density160 / user10 / RAM4GB, host GPU·Vulkan 비활성 cold-boot 환경.
- 패키지: `ai.pleos.assistant.mock.uxvalidation`. 기존 사용자 앱/DB를 보존한 별도 검증 설치다. 모든 최종 시나리오에서 같은 제품 APK를 사용했다.
- 최종 APK SHA-256: `d9ce757dc6f57f3233a08d9cfbb1f68faebda966c8db5a193931e39f61ae9b52`.
- 서버: #19 `5526f92` 격리 JAR, MOCK/미리보기 opt-in, host18082 → emulator8080. 기존 인증 DB·가상 조건을 재사용했다. 실제 전화/예약·외부 장소·AI·유료 호출0.
- 실제 앱 창 1682×1210dp 가로, 840×1210dp 세로형 경계에서 실행했다. 좁은 창은 `am task resize`를 사용하고 Activity의 실제 `screenWidthDp=840`을 검사했다. **Connect-S/W의 모든 1:1·2:0·2:1·1:2 시스템 조합을 실행한 것은 아니다.**
- 주야간 전환은 실제 `cmd uimode night yes/no`로 확인했다. 차량 전조등/조도 신호 자체를 검증한 것은 아니다.

| 절차 | 기대 결과 | 실제 결과·증거 |
| --- | --- | --- |
| 수정 전 #44와 동일 설치/조건 비교 | 인증·검색 보존, 개발 조작에 가려진 후보 개선 | `before-home`, `before-candidates` ↔ `final-day-candidates`. 같은 검색/가상 조건이며 시간 경과에 따른 만료·서버 재정렬은 픽셀 차이로만 해석하지 않음 |
| 주간 넓은 창 후보 조회 | 3개 후보와 주요 동작 비교 | 실제 서버 GET 복구·3개 카드 표시. 가로 스크롤 없음 |
| 야간 840dp 창 승인창 | 긴 한국어·취소/승인 버튼이 창 안에 표시 | `final-approval-narrow-night`, 실제 측정된 text layout의 잘림/ellipsis 없음, 버튼≥72dp |
| 주간 승인창 → 제한 발생 | 창 종료, 오래된 승인 클릭 무효 | `final-approval-wide-day`, instrumentation PASS. 제한은 주입, 실제 moving callback 아님 |
| 종료/재진입·Activity 재생성 | CarUx 새 조회, 입력 draft 보존 | 실제 INITIAL/DETACHED 연결 경로 + 성인 입력3 보존 검사 PASS. 물리 회전과 구분 |
| 키패드 표시 | 입력 중 앱 정보 유지, 주요 동작 접근 | `final-keyboard`: NO_EXTRACT_UI 적용 후 전체 화면 대체 해소. 키패드는 시스템 영역으로 유지 |
| 미리보기 검색·펼치기 | 후보3개·UNKNOWN·출처 접근 | `final-day-preview`, 상세 펼치기 기기 실행. 문의/예약 버튼 없음 |
| 미리보기 중 주야간 전환 | 이전 결과 숨김, 명시 검색 대기 | `final-preview-after-theme` → 명시 검색 → `final-night-preview` |
| 미리보기 추가 제한 | 이전 결과 삭제·입력/검색 차단 | `final-preview-restricted`, 주입 제한임을 화면에 명시 |
| 실제 MOCK 문의 버튼 → 대기 | 일반 앱은 응답 대기, 자체 수락 금지 | `final-inquiry-wait`: 실제 가상 문의 PENDING·최신 상태 조회 표시 |
| 접수/확정/거절/UNKNOWN·변경 미확인 | 상태와 기존 예약 안내 구분 | 기기 instrumentation의 **화면 snapshot 주입** 검사. 일반 앱에서 매장 수락을 생성하거나 #40 전체 HTTP 공동 시연을 완료한 것이 아님 |

`DrivingDeviceChecks`에 실제 화면 크기·텍스트 layout·버튼/입력 높이·입력 보존·결과 상태 표시를 추가했다. 기존 실제 CarUx 초기값/재연결·추가 제한/실패·오래된 승인·POST 차단 검사를 유지했다. 좁은 창 검사는 `adb shell am instrument -w -e narrow true ai.pleos.assistant.mock.uxvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`로 실행한다. 일반 검사는 narrow 인자를 생략한다. 실제 automotive baseline + 명시 인증한 MOCK 설치가 필요하며 CI의 기기 없는 검사와 구분한다.

## 검사·발견한 문제·증거 위치

- `./gradlew -PincludeAndroid=false format`, `./gradlew -PincludeAndroid=true verifyAll`, `python3 scripts/smoke-test.py`: PASS. UX62개 실패/오류/skip0(기존58+표시 의미4). 서버268개는 기존 UP-TO-DATE 결과이고 새 실행으로 세지 않는다. Android JVM NO-SOURCE는 실행 건수로 세지 않는다.
- `python3 scripts/check-ui-contrast.py`: 주야간/눌림/비활성 등 20개 대비 검사 PASS. 최소4.52:1. verifyAndroid에 연결해 원격 CI에서도 실행한다. 실제 물리 거리·눈부심·운전 중 사용 허가를 의미하지 않는다.
- 실제 화면에서 키패드 전체 추출 화면, singleLine 적용 후 입력 높이 축소, 야간 native dialog 버튼의 투명 배경 문제를 발견해 수정하고 최종 APK로 다시 검사했다.
- Lint 오류0, 경고4: 기존 공개 Car 클래스 확인 PrivateApi1·AndroidX 갱신2, adaptive icon v26 폴더 경고1. v26 제거 시 AAPT가 아이콘을 찾지 못해 clean 재시도 후 검증된 v26 구성으로 복구했다. 경고 억제 없음; SDK/리소스 도구 갱신 호환 검토는 #25에 남긴다.
- 원본 PNG/XML은 Git 제외 `captures/ux39/`, 최종 파일/해시는 `final-manifest.json`, 도구/로그는 `work/ux39/`다. 최종 증거는 `final-*`와 위 고정 APK 해시를 기준으로 한다. 중간 개발 캡처와 구분한다. GitHub에 이미지 업로드·시연 영상 생성을 완료했다고 주장하지 않는다.
- 시작 환경 확인 후 원래 주야간 설정·키패드 설정·8080→18080 연결로 복원하고 이번 격리 서버만 종료한다.

## 남은 인수

- #25: Connect-W 등 다른 이미지/창 조합, 실제 조도/차량 신호, 물리 회전·거리 가독성, 실제 moving callback/서비스 장애, release 서명·설치·영상.
- #40: 일반 앱과 별도 운영자 응답 환경의 문의→승인→예약 결과 전체 공동 시연. 이번 결과 화면 주입을 공동 시연 완료로 세지 않는다.
- #1: 실제 목적지/ETA 의미·가용성 인수. #16/#26: 최신 초안 기반 공동 기기 인수.
- PR #36 → #44 → #39 순서의 선행 이력을 포함한다. 공유 브랜치를 덮어쓰거나 병합하지 않았다. 최신 main 반영/통합 순서는 별도 검토한다. #39와 #25는 OPEN, PR은 Draft 유지.

## 2026-11-09 추가 기기 검사와 공동 인수 준비

#39 UI 인수를 위한 #25 추가 검사이며 기존 `feat/39-vehicle-ui` / Draft #46에서 기록한다. 제품 APK는 위 `d9ce757d…` 그대로이고 테스트 APK만 교체했다. 기존 일반 앱/인증 DB/PENDING 문의를 보존했으며 운영자 수락이나 새 예약을 실행하지 않았다.

| 절차 | 기대 결과 | 실제 결과 |
| --- | --- | --- |
| user10 font_scale=1.5, 주간, shell resize840dp | 실제 Activity 배율1.5, 긴 승인창/버튼 표시 | 배율 assertion·측정 layout·버튼72dp 검사 PASS. 최종 PNG 직접 확인 |
| user10 font_scale=1.3, 야간, shell resize840dp | 실제 Activity 배율1.3, 야간 승인창 표시 | 동일 검사 PASS. 최종 PNG 직접 확인 |
| 두 설정에서 종료/재진입/Activity 재생성 | CarUx 초기값 재조회, 입력3 보존 | instrumentation PASS. 물리 회전이나 주행 전환 아님 |
| 두 설정에서 주입 제한/실패·오래된 승인 | 승인창 닫힘, 이전 클릭 무효, POST 차단 | PASS. 제한/실패는 개발 주입, 결과 상태는 화면 fixture |

재현: 설정 변경을 마친 후 `adb shell am instrument -w -e narrow true -e fontScale 1.5 ai.pleos.assistant.mock.uxvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks` (야간은1.3). 새 선택 인자는 Activity Configuration의 실제 배율을 검사한다. 캡처 전500ms 렌더 대기·창 표시 재검사를 추가했다. main queue idle만으로 compositor 표시가 끝났다고 가정하지 않는다. 캡처는 반드시 열어 승인창 유무를 확인한다.

초기150% 실행은 화면 fixture의 PENDING 문구 assertion으로 instrumentation 프로세스가 종료됐다. 설정 전환 후 재실행에서는 같은 제품 APK로 통과했고 제품 결함으로 재현되지 않았다. 설정 전환/Activity 수명 타이밍 가능성이 있으나 정확한 원인을 확정하지 않았다. 최초130% 캡처에는 승인창이 없어 최종 증거에서 제외했다. 이 실패와 한계를 숨기지 않고 `work/ux25-extra/font150-day.log`, `font150-day-retry.log`에 보존했다. 최종 성공은 배율 검사를 추가한 `final-font150-day.log`, `final-font130-night.log`와 직접 확인한 PNG 두 장만 기준으로 한다.

증거: 로컬 Git 제외 `captures/ux25-extra/final-manifest.json`, `final-font150-day.png`, `final-font130-night.png`; 실행 로그 `work/ux25-extra/`. 이미지 원본은 GitHub에 업로드하지 않았다. format·verifyAll·smoke-test.py PASS. verifyAll의 기존 JVM 테스트는 UP-TO-DATE/캐시를 포함하며 이번 새 실행 건수로 세지 않는다. 제품/API 변경 없음, Lint 기존 경고4개 유지. 테스트 코드는 별도 APK이며 원격 CI가 실제 기기 검사를 실행하는 것은 아니다.

글자 설정은 원래 user10 키 없음(null)으로 삭제 복원, night=no·8080→18080 복원, 테스트 앱 종료·이번18082 서버만 종료했다. 기존18080 서버와 원래 앱 데이터는 유지했다. 실제 차량 신호/물리 거리·전체 Connect-S/W 조합·release/서명/영상은 여전히 미검증이다.

공동 인수 선행 자료를 다음 이슈에 게시했다. 완료 체크나 담당 배정은 변경하지 않았다.

- [#40 시연 환경 준비](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/40#issuecomment-6741207611): 기존 OwnershipSmokeApplication은 모든 요청에 같은 운영자 주체를 주입하므로 일반 앱 인증과 운영자 분리를 그대로 검증할 수 없다. 서버 담당의 별도 테스트 운영자 연결·callId/eventId 대조·outcome 예제·시작/종료 명령이 선행한다. 문의→명시 승인→PENDING→수락→GET·UNKNOWN/중복/백그라운드/0원 변경 공동 표를 전달했다.
- [#16 공동 인수 항목](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6741208185): 인원 범위 문서 정합성·집합 비교·UNKNOWN/ordinal·상태별 MOCK fixture·취소/늦은 응답·동일 주체 갱신을 함께 확인한다. 서버 담당의 계약/fixture 확인과 양측 동일 버전 실행이 선행한다. 로컬 초안 검증을 공동 인수 완료로 세지 않는다.
