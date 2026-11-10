# VEH-01 목적지·ETA probe 검증 기록

기준일: 2026-11-09 KST. [GitHub #1](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1)의 실제 본문을 GitHub API로 확인했습니다. 이 문서는 Android/Pleos 담당의 관측 준비와 후속 기기 검증 기록이며 **기기 검증 완료 보고가 아닙니다**.

## 범위와 기존 구조

- 루트 Wrapper/설정과 `android/probe` 단일 Java Activity 진단 앱을 유지합니다. `ProbeState`만 SDK 비의존 상태 경계로 분리했습니다. 제품 ViewModel/Repository 앱이나 SDK 모델의 공통 DTO 노출은 추가하지 않습니다.
- `server/`는 Java 21 Spring Boot MOCK 업무, `api/`는 v1 계약/예제, `scripts/`는 환경·계약·smoke 검사, `docs/`는 명세/계획/증거, `.github/`는 CI입니다.
- 필수 문서와 Android 하위 지침, 실제 소스·Gradle·manifest·리소스·CI·API 계약을 대조했습니다. `DESIGN_PROPOSALS`와 ERD는 현행 API가 아닙니다.
- 시작 시 tracked 변경 없음. fetch 후 `main`과 `origin/main`은 `9518b6a`로 동일했습니다. `feat/1-navi-probe`를 생성했습니다. 저장소 소유자가 현재 사용자와 달라 Git 명령마다 `-c safe.directory=D:/pleos-reservation-assistant`만 적용했고 전역 설정은 바꾸지 않았습니다. commit/push/PR/merge는 수행하지 않습니다.

기기 검증의 최신 추가 관측은 [STATUS](STATUS.md)의 2026-11-09 내비 복구 기록을 따릅니다. 아래 SDK 표의 미검증은 최초 정적 분석 시점의 기록입니다. 이후 초기화·목적지·TripInfo 수신 및 짧은/긴 경로 관측을 확인했지만 시간 기준·응답 연관성은 미검증입니다.

## 실제 SDK 계약 근거

공식 [NaviHelper 3.0.5 AAR](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5.aar)의 `classes.jar`를 javap로, [navi-data 3.0.4 sources](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/navi-data/3.0.4/navi-data-3.0.4-sources.jar)를 직접 읽었습니다. 원본은 Git 제외 `work/veh01/`에만 있습니다.

| 항목 | 정적 확인 | 기기 확인 |
| --- | --- | --- |
| 초기화/해제 | `initialize(onConnected, onFailed)` 후 동일 listener 등록, 제거 후 `release()` | 미검증 |
| 목적지 | `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` | 미검증 |
| Trip 요청 | `requestTrip(TripRequest)` → `onTripInfo(TripInfo)`; `getTripInfo()` 없음 | 미검증 |
| TripRequest | latitude/longitude Double, poiId/poiName String, poiSubId nullable String | 입력 유효성/Navigation 수락 미검증 |
| DestinationInfo | 좌표, 이름/주소, remainDistance, remainTime, tollFare, POI 식별자 등. remainTime 설명은 남은 이동 시간(초) | 미검증; TripInfo.eta와 동일 의미로 취급하지 않음 |
| TripInfo | distance Int, eta Int, estimatedSocAtDest Int, energyConsumption Double, summary TripSummary | eta 주석의 단위는 seconds. duration/epoch/time-of-day, 0/음수 의미 미검증 |
| 응답 연관성 | TripInfo에 요청 ID/목적지 ID/관측 시각이 없음 | 순서/누락/중복/늦은 응답 미검증 |
| 이벤트 | `onRouteStarted(RouteStartInfo)`, `onRouteCancelled()`, `onRoutingCompleted()`, `onReRouteEnded()`, 목적지 변경·도착 메서드 존재 | 발생 조건·주기 미검증 |

SHA-256: NaviHelper AAR `15fadd11c7c611b1abdf7ebf9ce91f2a6ce2728e60b4e38b4343e11bb9c12e97`, sources JAR `4da974b6b0050662c4b346d3fca96073c8c1f241ce10c7d667cfef64cbceedaf`.

공식 [초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/error-handling)를 재확인했습니다. 초기 연결 실패와 Navigation 오류는 다른 경로입니다. 일반 오류를 미지원/단절로 단정할 수 없습니다. capability/disconnect API를 추측해 추가하지 않습니다.

AAR의 deprecated annotation은 `onRouteEnded()` 대신 `onRoutingCompleted()`를 사용하도록 명시합니다. 이름만으로 여정 종료라고 해석하면 안 됩니다. probe는 `onRouteEnded()`를 재정의하지 않고 탐색 시작/완료, 취소, 도착을 각각 관측합니다. Gradle `debugRuntimeClasspath`에서도 NaviHelper 3.0.5 → navi-data 3.0.4 해석을 확인했습니다. 테스트 전용 JUnit 4.13.2(EPL-1.0)를 추가했으며 앱 런타임 의존성은 변경하지 않았습니다.

## 변경한 관측 동작과 한계

1. 초기화 → 권한 grant 관측 → 연결 성공 → listener 등록 → 목적지 요청 → 필드 검증 → 명시적 Trip 요청 흐름입니다. 실패 시 목적지와 요청 버튼을 무효화하고 listener 제거/release를 시도합니다. SecurityException만 PERMISSION_DENIED, 나머지는 UNAVAILABLE_UNKNOWN으로 기록합니다. 오류 메시지 원문은 기록하지 않습니다.
2. callback 진입 시 wall clock `observedAt`과 monotonic `elapsedRealtimeMs`를 함께 채집합니다. 이후 상태/UI/SDK 호출을 main thread로 직렬화합니다. `foregroundAtProcessing`과 `routeRevisionAtProcessing`은 처리 시점 상태이며 콜백 발생 시점이나 SDK의 원 관측 시각이 아닙니다.
3. 목적지 변경·경로 탐색 완료·재탐색 완료는 기존 목적지를 비우고 재조회합니다. 도착·취소·경로 탐색 시작·SDK 오류는 비우기만 합니다. 이후 요청하지 않은 목적지 콜백은 상태에 반영하지 않습니다. 재조회 버튼은 목적지 응답이 없을 때도 개발자가 명시적으로 다시 관측할 수 있게 합니다.
4. 새 조회 뒤 도착한 이전 목적지 응답은 SDK 계약만으로 식별할 수 없습니다. **모든 응답의 responseAssociation은 UNVERIFIED**이며 목적지와 ETA가 일치하는 서버 입력을 만들지 않습니다. routeRevision이나 probeSessionId를 SDK 요청 ID 또는 업무 tripId로 사용하면 안 됩니다.
5. Trip 콜백은 목적지 상태를 바꾸지 않고 rawEtaSeconds와 NEGATIVE/ZERO/POSITIVE/MISSING 부호를 보존합니다. 0을 도착, 음수를 특정 SDK 오류라고 해석하지 않습니다. Instant 변환·자동 예약·전화·서버 전송은 없습니다.
6. onStop 이후 listener를 유지해 백그라운드 수신을 관측합니다. 재진입 시 자동 업무 요청은 없고 필요하면 목적지를 다시 조회합니다. onDestroy 이후 콜백은 `ignoredAfterClose=true`로 관측하되 상태/UI를 바꾸지 않습니다. 초기화 지연 콜백은 종료/해제된 helper를 다시 활성화하지 않습니다.
7. 실패 후 같은 Activity에서 재초기화하지 않습니다. 세션 ID 없는 이전 콜백과 새 초기화를 섞지 않도록 앱을 종료·재실행합니다. SDK 내부 단절을 확실하게 감지하는 기능, 무응답 timeout, 지속 갱신/백그라운드 보장은 없습니다. 무응답을 정상 빈 목적지로 판정하지 않습니다.

로그는 probeSessionId, 버전, 이벤트, 시각, 원시 ETA, 비식별 상태만 기록합니다. 이름/주소/좌표/POI/예외 메시지를 출력하지 않습니다. 진단 UI와 로그는 제품 UX가 아니며 주행 사용을 허용하지 않습니다.

## Windows 환경 점검 — 최초 관측

| 항목 | 이번 환경의 관측 |
| --- | --- |
| JDK | 기본 PATH는 Oracle 17.0.10. Git 제외 작업 폴더에 Temurin 21.0.13+9 ZIP 확보·배포 체크섬 대조. 실행 셸에서만 JAVA_HOME 지정 |
| Gradle / AGP | Wrapper 8.11.1 + 배포 SHA-256 설정 / AGP 8.9.2 고정 |
| SDK | 기존 local.properties가 가리키는 SDK Platform 35, Build Tools 35.0.0, platform-tools, emulator 실행 파일 발견 |
| SDK 도구 | Command-line Tools latest 없음. setup-android.py는 검사만 실행, 라이선스 동의·설치·기존 로컬 설정 변경 없음 |
| Studio | 설치 디렉터리 2개 발견. 하나의 jbr/java.exe 실행은 접근 거부, 다른 것은 JDK 25.0.4. GUI 열기/Gradle Sync 성공은 미확인 |
| Pleos | SDK 내 system-images에서 Google API/Play 이미지 확인, Pleos 이미지 발견 못함. 현재 사용자 `emulator -list-avds` 출력 없음. 다른 사용자 폴더의 Pixel AVD는 사용하지 않음 |
| adb | 기본 제한 환경에서는 daemon 시작 실패, 권한 승인 후 `adb devices -l`은 빈 목록. 설치/앱 실행/콜백 수신 미실행 |

사용자별 SDK/AVD 홈이 다릅니다. 기존 SDK 경로의 사용자와 현재 실행 사용자가 다르므로 다른 사람의 AVD·계정·설정을 이동하거나 덮어쓰지 않습니다. 일반 Google 이미지로 Pleos 실행 성공을 대신하지 않습니다.

같은 날 사용자 요청으로 [D드라이브 설치](ANDROID_SETUP.md#2026-11-08-windows-d드라이브-설치)를 완료했습니다. Temurin JDK는 `D:\Java`, SDK/Pleos 이미지/AVD는 `D:\Android`에 있습니다. Pleos/Google 약관에 각각 명시적 동의를 받았고 개인정보 설정은 사용자가 직접 선택했습니다. 실제 Pleos AVD 생성과 ADB 부팅 완료·Android 14/API 34·release_connect_v2.1.0·42dot까지 확인했습니다. 이미지 원본은 공식 체크섬과 대조했습니다. 그래픽 홈 화면 캡처는 도구 시간 초과로 미확인이고 부팅 확인 후 emulator를 종료했습니다. 최초 환경 표의 “없음”은 설치 전 이력입니다. 목적지/TripInfo SDK 콜백은 아직 수신 검증하지 않았습니다.

## 2026-11-08 실제 probe 실행

사용자가 홈 화면 및 공개 테스트 목적지 길안내 시작을 확인한 후 adb로 APK 설치/실행, Android UI Automator로 probe 버튼의 활성 상태를 확인하고 명시적 조회/Trip 요청 버튼을 눌렀습니다. 시스템 권한을 자동 승인하거나 SDK 권한을 강제 부여하지 않았습니다. 모든 시각은 JSON observedAt의 UTC를 KST로 표시한 것입니다.

| 시각 KST | 실제 관측 | 해석 범위 |
| --- | --- | --- |
| 21:27:14 | install Success, COLD/Status ok, STARTED | APK 설치와 Activity 실행 확인 |
| 21:28:43–45 | NAVI_ROUTE granted=true, initialized, 목적지 MISSING_OR_INVALID | 서비스 연결·콜백 확인. 초기 누락 필드/원인은 미확정 |
| 21:30:32 | STOPPED | probe를 떠나 내비에서 경로 설정 |
| 21:31:19 | routeStarted, foregroundAtProcessing=false | 이 세션의 백그라운드 이벤트 수신 1건 |
| 21:32:39 | HOT 복귀, STARTED, 같은 probeSessionId | 재초기화 없이 같은 Activity 복귀 |
| 21:33:24–25 | 명시적 재조회 → VALID_FIELDS, Trip 버튼 활성 | 당시 목적지 필드가 요청에 사용 가능한 상태 |
| 21:34:06.930–07.530 | tripRequested → tripInfo, rawEtaSeconds=1541 | 약 600ms 콜백 간격 1회. 시간 기준/응답 연관성 UNVERIFIED |
| 21:41:00.431 | 두 번째 tripInfo, rawEtaSeconds=1538 | 첫 응답에서 monotonic 412.901초 경과, 원시값 3초 감소 |
| 21:42:45.548 | routeCancelled, foregroundAtProcessing=false | 사용자의 길안내 종료 뒤 기존 목적지 무효화 |
| 21:42:45.617 | 목적지 MISSING_OR_INVALID 콜백 | 취소 후 목적지로 다시 활성화되지 않음 |
| 21:43:38 | HOT 복귀, Trip 버튼 disabled | 취소 후 이전 목적지로 요청하지 못함 |

초기 invalid 콜백을 정상 빈 목적지라는 SDK 계약으로 확정하지 않습니다. 경로 설정 중 자동 destinationChanged/routingCompleted를 확보하지 못했으며 명시적 조회로 진행했습니다. 백그라운드에서 시작/취소를 받은 이 한 시나리오로 지속 수신·주기를 보장하지 않습니다. 로컬 Git 제외 `captures/veh01-20261108-probe.jsonl`은 허용된 진단 필드만 포함합니다. 내비 표시 시간은 별도 대조가 필요하고 위치·목적지 이름은 기록하지 않습니다.

사용자 보고 내비 화면은 기기 04:37, 이동 25분, 도착 05:02 AM이었습니다. ADB의 기기 시간대는 America/Los_Angeles, 읽은 시각은 04:39:22-0800입니다. SDK 샘플과 동일 순간의 UI 관측이 아니며 단순 일치로 시간 기준을 확정하지 않습니다. 1541초/1538초의 크기는 UI 이동 시간 계열에 가깝지만, 이는 가설을 지지하는 관측에 그칩니다. 경과 412.901초 동안 원시값은 3초만 감소했고 차량 이동/경로 상태를 동시에 기록하지 않았으므로 단순 카운트다운도 보장하지 않습니다. 두 TripInfo는 모두 명시적 수동 요청의 응답이며 자동 주기는 미검증입니다. 설정/표시 시간대와 API의 절대 시각 의미는 별도입니다. 기기 시간대는 변경하지 않았습니다.

최종 로그는 20건이며 요약은 Git 제외 `captures/veh01-20261108-summary.json`입니다. 취소 시나리오에서는 백그라운드 취소 수신·목적지 무효화·복귀 후 버튼 비활성화를 확인했습니다. 실제 늦은 유효 응답/도착/권한 거부/미지원/단절은 이번에 재현하지 않았습니다.

## Issue #1 완료 조건

2026-11-09 후속: 같은 공개 경로의 수동 두 요청은 78.319초 간격으로 모두 4227초였고, 약 5–10초 뒤 UI는 남은 68분이었습니다. 안내 종료의 백그라운드 routeCancelled·목적지 무효화·복귀 후 Trip 비활성을 재확인했습니다. 상세 시각/환경/자료는 STATUS의 같은 경로 반복 조회 항목을 따릅니다. 자동 갱신 주기·시간 기준·경로 연관성은 여전히 미확정입니다.


### 짧은 경로 추가 관측

사용자의 직접 진행 요청 후 현재 내비에 이미 표시된 공개 테스트 경로를 사용했습니다. 새 경로를 만들었다고 기록하지 않습니다. 초기 직접 화면은 기기 04:58/이동 10분/도착 05:08 AM/속도 0 km/h였습니다. 실제 설치된 내비 Activity와 probe 버튼 상태를 Android 패키지/UI 정보로 확인해 재조회·Trip 요청을 수행했습니다.

| 항목 | 실제 결과 | 한계 |
| --- | --- | --- |
| 목적지 재조회 | 22:08:10 KST VALID_FIELDS | SDK 응답과 목적지의 강한 식별자 연관성은 없음 |
| Trip 요청/응답 | 22:08:28.538 → 22:08:29.182 KST, rawEtaSeconds=603 | 약 644ms. 시간 기준/응답 연관성 UNVERIFIED |
| 이후 직접 내비 표시 | 시계 05:09, 이동 11분, 도착 05:20 AM, 속도 0 km/h | UI 분 단위 표시와 반올림 규칙 미확정 |
| 캡처 전후 기기 시각 | 05:09:27–05:10:39-0800 | SDK 관측보다 약 58–130초 늦음. 동일 순간 비교 아님 |

603초라는 크기는 수치상 10분 3초이며 이전 25분대 표시/1541·1538 원시값과 비교해 이동 시간 계열 가설에 부합합니다. 이는 현재 TripInfo가 정확한 남은 시간이며 observedAt에 더해도 된다는 확정 계약이 아닙니다. 차량 이동 시뮬레이션을 수행하지 않았고 시간대/권한/계정 설정도 변경하지 않았습니다. API 변환과 PLEOS 입력 차단을 유지합니다.

### 원시 로그 보존 한계

수집기가 logcat 스냅샷을 같은 파일에 덮어써, 최초 20건의 원시 전체본은 이후 logcat 순환 뒤 남아 있지 않습니다. 첫 두 Trip 샘플·간격과 당시 이벤트 요약은 `captures/veh01-20261108-summary.json`에 남아 있습니다. 최초 20건 전체를 현재도 보존한다고 해석하지 않습니다. 새 스냅샷 11건은 `captures/veh01-20261108-short-route-snapshot.jsonl`, 짧은 경로 원시값·시각·UI 수치는 `captures/veh01-short-route-observation.json`에 보존했습니다. 이후 수집은 중복 제거 후 추가 기록으로 변경했습니다. 이미 사라진 원본을 재구성하거나 복구했다고 주장하지 않으며 장시간 시험에는 실시간 수집이 필요합니다. 모두 Git 제외 로컬 증거이고 공유 문서에는 위치/주소/POI/이름을 기록하지 않습니다.

### 목적지 변경 추가 관측

2026-11-09 같은 세션에서 공개 목적지를 직접 변경했습니다. 콜백은 probe 복귀 후 routeCancelled → MISSING_OR_INVALID 목적지 → routeStarted였으며 기존 목적지 무효화/수동 재조회 전 Trip 비활성, 새 조회 후 VALID_FIELDS와 raw 648초(변경 전 4196초)를 확인했습니다. destinationChanged·routingCompleted·reRouteEnded는 이번에 관측하지 않았고 지연된 이전 유효 응답은 미재현입니다. SDK 응답 연관성과 시간 기준을 확정하지 않습니다. 시각·환경·로컬 증거는 STATUS의 목적지 변경 항목을 따릅니다.

### 현재 완료 조건 판정

| 실제 이슈 조건 | 현재 판정 |
| --- | --- |
| 실제 기기/에뮬레이터 목적지·TripInfo 로그 | 최초 정상 목적지/TripInfo 수신 확보. 전체 정상·실패 조합은 추가 필요 |
| ETA 시간 기준·단위·주기·백그라운드·도착 기록 | 단위 소스 확인, raw 1541/1538/603/3686/4227·UI 보고/직접 관측·412.901초 및 78.319초 간격, 백그라운드 시작/취소 관측. 동시 대조/시간 기준/자동 주기/도착 미검증 |
| 권한 거부·미지원·단절·늦은 콜백 동작 확인 | 상태 경계 단위 테스트와 방어 코드 추가. SDK/기기 시나리오 미검증 |
| 확인 전 rawEtaSeconds 보존·임의 Instant 변환 금지 | 코드에서 유지 |
| STATUS와 Android 문서 증거·한계 갱신 | 이번 작업에 반영 |

**#1은 종료할 수 없습니다.** 빌드·자동 테스트 결과는 STATUS의 2026-11-08 기록을 따릅니다. 이전 Mac의 NO-SOURCE/빌드 기록은 당시 이력이며 이번 Windows 실행과 구분합니다. CONTRIBUTING의 “이슈가 아직 없다”는 초기 예시 설명은 실제 GitHub #1 및 최신 ISSUE_GUIDE보다 오래된 설명입니다.

## 팀 인계와 합의할 계약

| 담당 | 지금 전달할 내용 | 합의/증거가 필요한 부분 |
| --- | --- | --- |
| #4 서버 | 현행 Navigation: tripId, destination(name/latitude/longitude), 절대 eta, observedAt, source. PLEOS_ETA_UNVERIFIED 차단 유지 | ETA 기준/보정/유효기간, 원 SDK 관측 시각 부재, 목적지 변경·종료·재검색의 동일 tripId 정책, 가용성 계약 |
| #5 UX | 목적지 필드 미확인/미응답, 권한 grant 관측, PERMISSION_DENIED, UNAVAILABLE_UNKNOWN, 원 SDK 오류 code, 종료 후 무시 여부 | unsupported/disconnected는 현재 확정 감지 불가. loading/empty/error 구분, 허용된 MANUAL 입력·주행 제한은 제품 화면에서 협의 |
| #2 추천 | 현재 MOCK/MANUAL의 목적지·절대 ETA·observedAt·source 계약 사용. probe raw 값은 추천의 ARRIVAL 시각으로 쓰지 않음 | 검증된 변환/신선도/연관성 계약 후 실제 PLEOS 입력 연결. NOW와 ARRIVAL 유지 |

공통 API/서버/추천/UX 코드는 변경하지 않았습니다. rawEtaSeconds·가용성·세션 revision을 API에 추가하려면 #4/#2/#5와 영향 범위를 먼저 합의하고 OpenAPI·예제·호출자·테스트를 함께 변경해야 합니다.

## 다음 기기 검증

공식 [Windows/Linux Pleos 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)의 SDK Update Site는 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`입니다. Studio SDK Manager에서 API 34 Pleos 이미지와 필요한 도구를 설치하고 라이선스는 개발자가 검토합니다. Device Manager에서 Automotive/Pleos Connect v2.1.0 AVD를 현재 사용자로 생성합니다. SDK 사용 신청/계정 승인이 필요하면 담당자가 진행합니다.

루트 프로젝트를 Studio에서 열고 Gradle JDK 21을 선택합니다. 로컬 JAVA_HOME/SDK 설정 후:

```powershell
python scripts/setup-android.py --configure
.\gradlew.bat -PincludeAndroid=true verifyAndroid
adb devices -l
adb -s <serial> install -r android/probe/build/outputs/apk/debug/probe-debug.apk
adb -s <serial> shell am start -n ai.pleos.assistant.probe/.ProbeActivity
adb -s <serial> logcat -s PleosNaviProbe:I '*:S'
```

adb가 PATH에 없다면 Studio SDK Location의 `platform-tools/adb.exe`를 사용합니다. 앱 실행 전 공개 테스트 목적지·권한 상태를 준비하고 OS/API, 이미지/Navigation/SDK/앱 버전을 기록합니다. `captures/`만 사용하며 전체 logcat/실사용 경로/개인 위치를 공유하지 않습니다.

| 시나리오 | 기대/관측할 항목 | 이번 실제 결과 |
| --- | --- | --- |
| 정상 최초/목적지 없음 | initialized, destinationRequested, destinationInfo, tripRequested, tripInfo 또는 오류/무응답 | 초기 invalid/경로 후 valid 목적지, TripInfo2회 확인. invalid 필드별 원인은 미확정 |
| 짧은/긴 경로·60초 후·자정 경계 | rawEtaSeconds와 같은 시점 내비 남은 시간/도착 시각, 수동 요청 횟수와 자발 콜백 간격 구분 | 최초 경로412.901초 간격 수동2회, 짧은 경로603초, 긴 경로3686초 관측. 직접 UI 대조는 시간 차가 있어 동일 시점/자정 미검증 |
| 변경 중 기존 응답 지연 | 이전 목적지 무효화, raw 응답 연관성 UNVERIFIED, 현재 결과로 승격 없음 | A→B 직접 변경 시 취소/invalid/새 시작·재조회 전 Trip 비활성·재조회 후 valid/648초 확인. 이전 요청은 이미 응답 완료여서 지연 유효 응답은 미재현 |
| 취소·도착·경로 탐색·재탐색 | 이벤트 순서/반복/누락, 버튼 상태, 새 조회 여부 | routeStarted/routeCancelled·취소 후 버튼 비활성 확인. 도착/재탐색은 GPS 주입 7회/Android 반영 확인 후에도 내비 이동·해당 이벤트 미확인. 계속 미검증 |
| 백그라운드·복귀·종료·초기화 중 종료 | lifecycle/콜백 시간 차, 종료 뒤 상태 변경 없음, listener 해제 | STOPPED 중 시작/취소·HOT 복귀 확인. 종료/지연 초기화 기기 검증은 미실행 |
| 권한 거부·미지원·연결 실패/중도 단절 | 실제 grant/code/예외 분류 또는 무응답, 정상 empty로 오인하지 않음 | 미실행 |
| 0·음수·누락·중복 | 원시값 보존, 의미 추정/자동 변환 없음 | 미실행 |

기록에는 날짜·버전·시나리오·관측 로그 위치·내비 표시와의 차이·기대/실제·판정·미확인 사유를 남깁니다. ETA 시간 기준이 확인된 뒤에만 별도 변환 작업의 범위와 테스트를 정하고 PLEOS 허용을 협의합니다.

## 도착·재탐색 재현 제한과 공급자 확인 초안 — 미발송

2026-11-09 표준 Android geo fix 7회는 OK였고 마지막 합성 지점이 Android GPS 공급자에 반영됐지만, Pleos 내비는 기존 11분/3.1km 안내를 유지했습니다. probe 복귀 후에도 목적지 VALID_FIELDS, 도착/재탐색 콜백 0건이었습니다. 이는 SDK 기능 미지원이나 고장 증명이 아닙니다. 실차/연속 도로 주행·정확한 목적지 진입은 검증하지 않았고 도착/재탐색은 여전히 미완료입니다. 현재 기기 시간대는 Asia/Seoul로 관측됐으므로 이후 시각 비교 전에 다시 확인합니다. 위치 원문/개인 경로는 공유하지 않습니다.

공급자에게 확인할 항목(아래는 로컬 초안이며 문의를 보내지 않았습니다):

1. Connect v2.1.0/API34와 NaviHelper3.0.5에서 표준 Android emulator geo fix가 Navigation의 위치 입력으로 사용되는가? Android GPS에는 반영되지만 내비 위치가 유지되는 경우 필요한 공식 설정은 무엇인가?
2. 이 버전에서 공개 테스트 경로를 연속 재생해 destinationArrived와 reRouteEnded를 관측하는 공식 절차는 무엇인가? 차량 상태·위치 입력·시나리오 자료가 별도로 필요한가?
3. 해당 이미지/SDK 조합의 지원 GPU/메모리 조건과 알려진 Windows ColorBuffer 문제가 있는가? 현재 GPU는 MX450 2GB이고 공식 최신 문서의 최소 예시보다 낮다.
4. TripInfo.eta의 초 단위 외에 시간 기준은 무엇이며 requestTrip 결과가 현재 안내 경로와 달라질 수 있는가? 목적지/요청 식별자가 없는 응답의 연관성 확인 방법은 무엇인가?

지원 답변 전 미확인 SDK 메서드 호출/이벤트 강제 호출, 차량 위치 서비스 교체, 권한·보안 우회 또는 서버 PLEOS 차단 해제로 이 시험을 통과시키지 않습니다.

## 명시적 재탐색 요청 경로 추가 확인

2026-11-09 공식 Routes와 고정 AAR에서 requestReRoute()/NAVI_ROUTE_SEARCH 계약을 확인해 probe 진단 버튼을 추가했습니다. 위치 이탈에 의한 자동 재탐색이나 도착 재생과는 다른 시험입니다. 목적지·권한을 확인하고 기존 상태 무효화 후 요청하며 완료 이벤트는 기존 listener로만 관측합니다. 빌드/상태 테스트6개/Lint0과 설치·권한 grant·초기 목적지 없음 버튼 비활성은 확인했습니다. 내비 빈 화면/로딩으로 유효 목적지를 확보하지 못해 요청 0회이며 완료 콜백은 미검증입니다. 최신 증거/환경 한계는 STATUS를 따릅니다.

문의 초안의 2번은 **도착 및 위치 이탈에 의한 자동 재탐색을 재현할 공식 위치·주행 재생 방법**에 대한 질문입니다. 명시적 requestReRoute() API 존재 자체는 확인됐으므로 존재 여부를 미확인이라고 문의하지 않습니다. 외부 문의는 발송하지 않았습니다.

## 2026-11-09 재탐색 요청 실측과 플랫폼 재시작 비교

사용자가 시작한 공개 길안내에서 목적지/권한 확인 후 requestReRoute 요청1회(18:53:16.701 KST), 요청 전 상태 무효화와 내비 전면 전환을 확인했습니다. 완료 콜백은0건이었지만, 이 구간은 system_server Watchdog 재시작과 겹쳐 SDK 완료/실패를 판정하지 않습니다. 원본은 시작횟수9까지 증가했고 main의 getBootUser 대기 스택이 있었습니다. 별도 새 데이터 첫 부팅에서도 UIAutomator/성공한 probe 실행 없이 foreground thread Watchdog와 시작1→2가 관측됐습니다. 초기화 후 두 번째 부팅은 사용자10 준비/시작1회까지 확인했지만 설치 명령은 자동 승인 검토 시간 초과로 미실행이며 수동 설치 결과 대기입니다. 세 구간을 혼합하지 않습니다.

원본 userdata3개 파일 크기/mtime이 동일한 것을 확인했으며 기존 데이터 삭제나 wipe-data는 하지 않았습니다. 새 데이터는 D:/Android/Avd/VEH01_clean_data_20261109에만 있습니다. 현재 완료 조건은 **재탐색 요청 부분 확인, 완료 이벤트 판정 불가**입니다. 최신 실행/대기 상태는 STATUS와 CODEX_HANDOFF를 따릅니다. 공유 가능한 [공식 문의 초안](../outputs/VEH01_PLATFORM_SUPPORT_DRAFT.md)을 마련했고 외부로 발송하지 않았습니다.

## 2026-11-09 격리 두 번째 부팅 — 재탐색 완료 콜백 미관측

사용자 수동 설치 Success 후 앱 실행 Status: ok, 두 권한 허용과 SDK 초기화를 확인했습니다. 공개 길안내 시작 후 routeStarted(11:03:44.037310Z), 명시적 조회 VALID_FIELDS(11:04:10.363953Z), reRouteRequested 1회(11:04:41.361257Z)를 같은 세션에서 수신했습니다. 요청 시 revision=2/목적지 사용 가능=false로 무효화됐고 약1.323초 뒤 STOPPED/내비 전면 전환, 11:05:16.535357Z에 HOT 복귀했습니다. 복귀 화면에서 Trip/재탐색 버튼은 비활성이었습니다.

요청 전후 system_server 시작 횟수는1로 유지됐고 요청 후 최소131초 관측 창에 reRouteEnded는0건입니다. 앞선 Watchdog로 중단된 원본 데이터 시험과 구분합니다. 완료/SDK 실패/미지원으로 단정하지 않습니다. 11:06:56.771035Z 수동 재조회 후 11:06:57.806926Z VALID_FIELDS로 회복했습니다. UIAutomator와 재탐색 반복 요청은 사용하지 않았습니다. ETA 시간 기준/응답 연관성과 도착·자동 이탈 재탐색은 계속 미검증이며 #1은 미완료입니다.

비식별 증거: captures/veh01-clean-second-reroute-20261109-live.jsonl, veh01-clean-second-reroute-result.json. 화면은 Git 제외 work/veh01/clean-reroute-return.png에만 보관했습니다. 코드 변경/테스트 재실행/commit/push/PR/외부 문의 발송은 없습니다.

후속 복구 확인: 11:07:51.902254Z에 TripInfo rawEtaSeconds=666/POSITIVE를 수신했습니다. 이는 원시값 수신 확인이며 남은 시간/도착 시각으로 변환하지 않습니다.

2026-11-09 Issue #1의 앱 인수 코멘트 반영: [차량 데이터 앱 인수안](VEH01_APP_HANDOFF.md)에 #16/#26 전달 정보·오류/수명 상태·Navigation 변환 차단·공동 시나리오를 정리했습니다. 협의용 문서이며 제품 adapter 구현/공통 계약 변경/앱 연결 완료를 뜻하지 않습니다.
