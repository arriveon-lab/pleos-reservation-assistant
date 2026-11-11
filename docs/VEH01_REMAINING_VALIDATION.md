# VEH-01 남은 검증 절차와 서버 인수 경계

기준일: 2026-11-11 KST. 현재 작업 브랜치 feat/1-navi-probe/HEAD25b28a6, PR47 OPEN/Draft를 API로 확인했습니다. 이번 시작 시 ADB 연결 기기는0대입니다. 이 문서의 예정 시험은 통과 기록이 아닙니다. 반복 정차 표본으로 ETA 의미를 확정하지 않고 [기존 관측](VEH01_OBSERVATIONS_20261110.md)을 유지합니다.

## 이번 새 확인

- [서버 인수 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6771962584)과 고정 [9709887 문서](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/9709887/docs/REC02_TEAM_HANDOFF.md#navigation)를 정적으로 대조했습니다. 정확한 입력 시간 경계는 [앱 인수안](VEH01_APP_HANDOFF.md)에 반영했습니다. 새로운 서버 정책을 제안하거나 구현하지 않았습니다.
- Draft PR53 목적지 추천은 기존 `/api/v1/searches`와 다르며 eta/observedAt를 요구하지 않는 계약입니다. 앱 호출자/서버 코드는 다른 담당 범위이고 이번 브랜치에 가져오지 않았습니다.
- [공식 Virtual Console 사용 안내](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)에서 기어·브레이크·누적거리·조향·크루즈 설정을 확인했습니다. 이 문서에서는 내비 경로 재생·차량 좌표 주입·도착 이벤트 재생 절차를 확인하지 못했습니다. 문서에 없다는 사실을 실제 도구의 기능 부재로 단정하지 않습니다. 기어/누적거리·크루즈 목표속도를 바꾼 것만으로 내비 이동·도착을 검증하지 않습니다.
- [설정 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)의 접속은 emulator2.1.0 이상에서 `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`입니다. 이번에는 에뮬레이터/콘솔을 실행하거나 포워딩하지 않았습니다. CarUx/주행 제한 구현은 #25 담당 범위로 유지합니다.

## 재현 준비가 필요한 시험

| 항목 | 필요한 환경/조작 | 성공/판정 기준 | 현재 수준 |
| --- | --- | --- | --- |
| ETA 의미 | 공개 경로·동기화된 UTC/monotonic/화면, SDK 기준 설명 및 실제 내비 이동이 확인되는 주행 재생 | 같은 시점 값의 단위/기준/표시 반올림·현재 경로 관계를 근거로 확정. 단순 수치 유사성은 불충분 | 정차 수초 이내 대조2회 확보, 시간 기준 미확인 |
| 도착 | 내비 차량 위치/경로 진행·목적지 진입을 재현하는 지원된 방법 | 화면 도착·실제 onDestinationArrived·기존 값 무효화·앱 후보/예약 동작을 같은 타임라인에서 확인 | 실제 도착 미검증; callback 합성으로 통과시키지 않음 |
| 서비스 단절 | 원본과 분리된 환경·서비스 소유자와 합의한 중단/복구 수단 | 실제 disconnect/오류와 무효화·복귀·재조회·이전 결과 차단 확인 | 격리 AVD의 단절·자동 재연결·같은 세션 재조회 확인. 단절 중 요청·무효화·이전 응답 차단 미검증 |
| 미지원 | SDK 지원 조건에 해당하지 않는 별도 기기/이미지와 공식 연결 계약 | 권한 실패·연결 timeout·미지원의 원인을 구분해 기록. 모르면 UNAVAILABLE_UNKNOWN 유지 | 별도 환경 없음. 정상 Pleos의 권한 미선언 결과로 대체 불가 |
| 늦은 유효 응답 | 실제 SDK 응답 지연·목적지 A→B 순서를 재현할 지원된 환경 | 발신/수신/변경 시각·모든 응답 기록. 식별 불가 한계 명시, 현재 결과로 승격 금지 | 식별자 없음, 실제 늦은 응답 미재현. fake 단위 결과는 실기기 증거 아님 |
| 개별 권한 조합 | normal 권한 선언을 하나씩 제외한 별도 시험 package, 정상 앱/이미지 보존 | 선언·실제 grant·초기화/조회/재탐색을 분리 기록 | 동시 미선언과 NAVI_ROUTE 단독 미선언 조회 PERMISSION_DENIED 확인. NAVI_ROUTE_SEARCH 단독 미선언의 재시도에서 유효 목적지와 Trip 원시값 수신, probe 재탐색 버튼 비활성 확인 |
| 갱신·복귀 | 동일 경로·전면/백그라운드·수동 요청 없는 관측 창·프로세스/부팅 경계 | 자발 콜백과 수동 응답을 구분. 누락/시간창을 명시해 자동 주기를 과장하지 않음 | 제한된222.052초 창 추가Trip0, 주기 미확인 |

원본 userdata 삭제·권한 강제 부여·계정/보안 우회·가짜 SDK 이벤트는 사용하지 않습니다. 실제 길안내·위치 정보는 공개 테스트 목적지만 사용하고 공유 증거에는 좌표/이름/POI를 넣지 않습니다. 화면과 전체 SDK 바이너리는 work/에만 둡니다.

## NaviHelper 3.0.5 단절 시험 판정 기준

13:42:32 KST에 격리 AVD의 사용자 10 내비 서비스에 일회성 Android 프로세스 장애를 주입했습니다. 서비스 PID는 1931에서 13888로 바뀌었고 시스템 시작 횟수는 1로 유지됐습니다. 13:42:32.932~32.943에 단절 로그, 13:42:33.243~33.249에 연결 로그를 관측했습니다. 같은 probe 세션은 13:42:34.281에 유효 목적지 콜백을 받았고, 13:44:17.575 수동 재조회에도 13:44:19.536에 유효 목적지로 응답했습니다.

14:33:32 KST에 같은 probe 세션에서 복구 후 Trip을 한 번 수동 조회했고, 약 453ms 뒤 `rawEtaSeconds=701`을 받았습니다. ETA 시간 기준과 요청 연관성은 UNVERIFIED입니다. 관측 시점은 서비스 재연결 이후입니다.

약 0.3초의 단절 중 요청, 목적지 무효화, 이전 응답 차단은 미검증입니다. 복구 직후 화면 캡처는 시간 초과됐으며 콜백과 재조회 판정은 비식별 로그에 근거합니다.

단위 테스트에서는 `ProbeState.disconnect()`가 기존 목적지를 지우고, 재연결 후 새 조회 전 들어오는 목적지 콜백을 거부하는지 확인합니다. 새 조회를 시작하면 SDK 콜백에 요청 식별자가 없어 그때 들어온 이전 응답과 새 응답을 구분할 수 없습니다. 실제 서비스 단절에 이 상태 전이가 호출되는지는 별도 기기 검증 대상입니다.

재연결 직후 13:42:34.281 KST 콜백 앞에는 같은 세션의 새 `destinationRequested` 기록이 없었습니다. 콜백 전후 `routeRevisionAtProcessing=0`, `destinationUsableAtProcessing=true`였습니다. `destinationValidation=VALID_FIELDS`는 콜백 필드 검사 결과이며 목적지 상태에 채택됐는지는 이 로그만으로 판정할 수 없습니다.

고정 AAR의 공개 NaviHelper 및 NaviHelperEventListener를 javap로 확인했습니다. 공개 리스너에 서비스 단절 전용 콜백이 없으며, 초기 연결 실패는 initialize의 onFailed로 받습니다. Navigation 처리 오류는 onError로 받습니다. navi-data 3.0.4 소스의 오류 열거형은 배터리, 경로 탐색, Trip 검색 오류를 정의합니다. [공식 오류 처리 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/error-handling)

onNavigationStatus의 INACTIVE, INITIALIZING, FREE_DRIVE, ROUTE_DRIVE는 내비 동작 상태입니다. INACTIVE만으로 바인더 연결 소실을 판정할 수 없습니다. AAR 내부에서는 Android ServiceConnection의 onServiceDisconnected와 onBindingDied 처리를 확인했습니다. 내부 구현을 앱이 호출할 API로 사용하려면 공급자가 지원하는 계약이 필요합니다.

단절 중 동작은 격리 기기에서 요청을 보낼 수 있는 길이의 단절 구간을 확보한 뒤 검증합니다. 같은 앱 세션에서 단절 전 유효 목적지 수신, 서비스 연결 변화, 단절 중 요청과 응답 또는 무응답, 복구 후 새 조회를 순서대로 기록합니다. SDK 내부 연결 기록과 앱의 sdkCallFailed·initializeFailed·sdkError 및 목적지 사용 가능 상태를 같은 시각으로 대조합니다. 무응답이면 요청 시각과 관측 종료 시각을 남기고 원인이 불명확하면 UNAVAILABLE_UNKNOWN으로 기록합니다. Watchdog 재시작이 겹치면 플랫폼 재시작 증거를 함께 기록하고 단절 경로 판정을 보류합니다.

## Pleos 기술 지원 문의 초안

Pleos Connect Emulator 2.1.0과 NaviHelper 3.0.5로 앱의 목적지·ETA 처리 흐름을 검증 중입니다. 격리 에뮬레이터에서 내비 서비스 단절 후 약 0.3초 만에 자동 재연결됐고, 같은 앱 세션에서 목적지와 Trip을 다시 조회했습니다. 다음 동작을 재현하거나 판정할 수 있는 공식 방법이 있다면 안내 부탁드립니다.

1. 내비 서비스 연결을 정해진 시간 동안 끊었다가 복구할 수 있는 시험 이미지, 도구 또는 지원 절차가 있나요? 연결이 끊긴 동안 `getDestinationInfo()` 또는 `requestTrip()`을 호출하면 앱이 어떤 반환, 오류 또는 콜백을 기대해야 하나요?
2. 목적지 A의 조회 응답을 늦춰 목적지 B 전환 뒤 받게 할 수 있나요? 전환 뒤 받은 `onDestinationInfo()`와 `onTripInfo()`가 현재 경로에 해당하는지 판별할 수 있는 식별자 또는 순서 보장이 있나요?
3. `TripInfo.eta`의 초 값은 어떤 시각이나 구간을 기준으로 하나요? 앱이 값을 갱신하려면 어느 이벤트에서 다시 조회해야 하나요?

검토한 공개 문서에서 위 시험 조작이나 순서 보장에 대한 안내를 확인하지 못했습니다. [Virtual Console 사용 안내](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide), [NaviHelper 이벤트 리스너](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/event-listeners)

## 서버의 기존 시간 경계 예제 — 실제 HTTP 호출 결과 아님

가상 now=`2026-11-11T03:00:00Z`, 필수 필드와 형식이 올바른 기존 MOCK/MANUAL Navigation을 전제로 한 시간 검사 표입니다. 인증/소유권·전체 요청 검증까지 성공한다는 뜻이 아닙니다.

| 항목 | 값 | 시간 검사 기대 |
| --- | --- | --- |
| eta 하한 | 03:00:00Z | INVALID_TIME |
| eta 유효 | 03:00:01Z | eta 검사 범위 내 |
| eta 상한 | 다음날03:00:00Z | eta 검사 범위 내 |
| eta 상한 초과 | 다음날03:00:01Z | INVALID_TIME |
| observedAt 하한 | 02:55:00Z | 관측시각 검사 범위 내 |
| observedAt 하한 초과 | 02:54:59Z | STALE_NAVIGATION |
| observedAt 상한 | 03:00:05Z | 관측시각 검사 범위 내 |
| observedAt 상한 초과 | 03:00:06Z | STALE_NAVIGATION |
| source=PLEOS | 유효 형식의 요청/시각 | 시간 검사 전에 PLEOS_ETA_UNVERIFIED |

서버300초/+5초는 차량 데이터 TTL이 아닙니다. 앱 수신 시각을 원천 시각으로 바꾸거나 실제 차량 입력을 MOCK/MANUAL로 위장하지 않습니다. #53 목적지 추천에 위 Navigation 예제나 임의 ETA를 끼워 넣지 않습니다.

## 다음 공동 인수 순서

1. 소영님은 본인 브랜치에서 #53 목적지-only 계약/실제·가상 표시·view/generation 무효화를 검토할 수 있습니다. 현재 목적지 필드의 유효성과 응답 연관성 미확인을 함께 전달합니다. 이 문서 자체가 앱 연결 승인이거나 구현 완료는 아닙니다.
2. 차량 담당은 위 표의 지원된 환경을 확보한 시험만 실행하고 비식별 시각·버전·commit·실제 결과/미검증을 추가합니다. ETA 의미가 확정되기 전 PLEOS 차단을 유지합니다.
3. #16/#4/#26이 검증된 변환·거절·화면을 같은 계약으로 공동 확인합니다. ETA 변화가 기존 예약을 자동 변경하지 않고 실제 전화/유료 호출을 유발하지 않는지 확인합니다.

이번 정리는 문서/정적 확인만 수행했습니다. 에뮬레이터 신규 시험·빌드/서버 테스트·HTTP·장소/AI/전화/유료 호출·외부 메시지·GitHub 변경은0회입니다. source 문서 조회는 실행 통과나 PR/Issue 완료가 아닙니다.
