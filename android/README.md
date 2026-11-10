# NaviHelper SDK probe

2026-11-09 최신 기기 검증: 정상 목적지·TripInfo 수신, 같은 경로 반복 조회, 백그라운드 시작/취소, 공개 목적지 교체 후 기존 상태 무효화와 명시적 새 조회를 확인했습니다. 목적지 교체는 이번 관측에서 취소/invalid/새 시작 콜백으로 나타났고 재조회 전 Trip 버튼은 비활성이었습니다. ETA 시간 기준·응답 연관성·변경 중 늦은 유효 응답·도착/재탐색 완료·실패 조합은 미검증입니다. 아래 날짜별 문장은 당시 단계의 이력이며 현재 상태는 [STATUS](../docs/STATUS.md)를 우선합니다.

예약 앱 UI를 만들기 전 **실제 SDK 수신값과 시간 의미**를 확인하는 정차 개발용 앱입니다. 서버/전화/모델과 연결하지 않습니다. `measurementMode=SDK_PROBE`, `source=PLEOS`는 관측 경로 표시이며, 예약 시스템의 LIVE 모드나 기기 검증 성공을 의미하지 않습니다.

2026-11-08 Issue #1 작업: [검증 기록과 팀 인계](../docs/VEH01_VALIDATION.md)에 실제 SDK 계약, Windows 환경, 완료 조건, 기기 시나리오를 정리했습니다. SDK 수신·시간 기준 검증은 여전히 미완료입니다. 최신 자동 검사 결과는 [STATUS](../docs/STATUS.md)의 2026-11-08 기록을 따릅니다.

같은 날 후속 환경 설치: 사용자 약관 동의 후 Windows D드라이브에 JDK 21·SDK·Pleos API 34 이미지·AVD를 준비했습니다. 실제 ADB에서 `sys.boot_completed=1`, Android 14/API 34, `RELEASE.release_connect_v2.1.0 test-keys`, 제조사 42dot을 확인했습니다. 부팅과 SDK 목적지/TripInfo 수신은 별도이며 후자는 여전히 미검증입니다. [D드라이브 경로와 실행](../docs/ANDROID_SETUP.md#2026-11-08-windows-d드라이브-설치)을 참고합니다. 화면 캡처 도구 응답 지연으로 홈 화면의 시각 검증은 완료하지 못했고 부팅 확인 후 emulator를 종료했습니다.

같은 날 사용자 재실행 후 **실제 probe APK 설치·COLD/HOT 실행·권한 granted·SDK 초기화·목적지 콜백·TripInfo 수신**을 확인했습니다. 공개 테스트 경로의 명시적 목적지 재조회는 VALID_FIELDS였고, 최초 rawEtaSeconds는 1541(21:34:07 KST)입니다. STOPPED 상태의 routeStarted와 복귀 STARTED도 관측했습니다. 내비 표시와의 대조/ETA 시간 기준·주기·도착/취소/단절은 아직 확인 전이며 서버 변환을 허용하지 않습니다. 아래 이전 단계의 미검증 표현은 당시 기록이고, 최신 증거는 [STATUS](../docs/STATUS.md)의 최초 SDK 수신 항목을 따릅니다.

## 명시적 재탐색 진단

2026-11-09 추가: 공식 requestReRoute()를 쓰는 `활성 길안내 재탐색 1회 요청 · 진단용` 버튼입니다. 공개 테스트 길안내를 먼저 시작하고 목적지를 조회합니다. 버튼은 유효 필드와 NAVI_ROUTE_SEARCH 허용이 있어야 활성화되며 호출 직전 기존 목적지를 무효화합니다. naviRouteSearchGranted/reRouteRequested/reRouteEnded와 후속 목적지 상태를 각각 확인합니다. SDK 콜백 직접 호출이나 GPS 이동 재생 버튼이 아닙니다.

자동 검사: 상태 테스트 6개 통과, Lint 0건, APK 생성 성공. 기기 설치/권한 granted/초기화와 목적지 없을 때 버튼 비활성은 확인했지만 최초 시도는 내비 로딩 실패로 미실행이었고, 후속 사용자 공개 길안내에서 요청1회·상태 무효화·내비 전면 전환을 확인했습니다. 완료 콜백은 플랫폼 재시작 때문에 판정 불가입니다. 상세 명령/실패 이력/바이너리 해시는 STATUS를 따릅니다.

## 구성

- AGP 8.9.2 / Gradle 8.11.1 Wrapper / compileSdk·targetSdk 35 / minSdk 28
- Java 17 문법, JDK 17 이상(서버 개발 JDK 21로 Gradle 구동 가능)
- NaviHelper 3.0.5, 조회용 NAVI_ROUTE 및 재탐색 진단용 NAVI_ROUTE_SEARCH 권한
- Gradle Wrapper JAR 및 배포 ZIP SHA-256 검증 설정

[AGP 공식 호환표](https://developer.android.com/build/releases/agp-8-9-0-release-notes)에 맞춘 초기 조합입니다. Pleos SDK의 기기별 호환성은 실제 빌드·실행으로 별도 검증해야 합니다.

기본 Gradle 설정에는 서버만 포함됩니다. [설치·연결 가이드](../docs/ANDROID_SETUP.md)를 따라 SDK를 설치하고 `python3 scripts/setup-android.py --configure`를 실행하면 Git 제외 `gradle-local.properties`의 `includeAndroid=true`로 IDE에서도 `:android:probe`가 포함됩니다. 루트 Wrapper와 버전 설정을 공유하며 android 하위 폴더에 별도 Wrapper를 두지 않습니다.

## 빌드와 설치

2026-11-04 Mac에서 SDK 설치·연결, Java 컴파일·DEX·debug APK, Lint 오류/경고 0을 확인했습니다. debug 서명도 당시 검증했습니다. 당시 단위 테스트는 NO-SOURCE였으며, 2026-11-08에는 SDK 비의존 상태 경계 테스트를 추가했습니다. Pleos 이미지·앱 설치·콜백 수신은 미검증입니다. 자세한 코드 기준은 [CODE_CONVENTIONS](../docs/CODE_CONVENTIONS.md)를 따릅니다.

빌드에는 Android SDK 35/build-tools 35.0.0, 기기 실행에는 별도로 Pleos Emulator/Virtual Console을 준비합니다. SDK 라이선스·Pleos 계정 및 사용 신청은 담당자가 확인합니다. `ANDROID_HOME` 또는 저장소 루트의 Git 제외 `local.properties`에 `sdk.dir`를 설정합니다.

```bash
# 저장소 루트에서 실행
./gradlew -PincludeAndroid=true verifyAndroid
adb install -r android/probe/build/outputs/apk/debug/probe-debug.apk
adb logcat -s PleosNaviProbe:I '*:S'
```

이 debug probe는 공모전 제출용 release 예약 앱이 아닙니다. 기본 프로젝트에는 배포 서명이나 주행 중 distractionOptimized 선언을 넣지 않았습니다. CarUxRestrictions를 구현·검증한 제품 화면이 나오기 전 차량 주행 중 사용하지 않습니다.

표시 문구는 문자열 리소스, 크기는 dp/sp를 사용합니다. 앱 아이콘과 Android 12+ cloud/device-transfer 데이터 제외 규칙을 추가했고, 기존 allowBackup=false도 유지합니다([공식 백업 설정](https://developer.android.com/identity/data/autobackup)). 이 설정의 기기별 동작 확인은 기기 검증에 포함합니다.

## 기기 검증 순서

1. 팀 공개 테스트 목적지를 설정합니다. 실제 가족·집·개인 동선을 테스트 데이터에 쓰지 않습니다. SDK/OS/에뮬레이터 이미지/앱 버전을 기록합니다.
2. “NaviHelper 초기화 · 목적지 조회”를 누릅니다. `permission`, `initialized`, `destinationInfo`를 확인합니다. 오류면 code와 권한 grant 상태를 기록합니다. 실패 후 재초기화는 앱 종료·재실행으로 세션을 분리합니다. 무응답이면 정상 빈 목적지라고 판정하지 않습니다.
3. “현재 목적지 TripInfo 1회 요청”을 누르고 `tripRequested` → `tripInfo`를 관측합니다. `getTripInfo()`는 존재한다고 가정하지 않습니다.
4. rawEtaSeconds와 동일 시점 내비 화면의 남은 시간/도착 예정 시각을 비교합니다. 짧은/긴 경로와 60초 경과, 자정 경계에서 반복합니다. 소스 주석으로 단위 초는 확인했지만 **duration/epoch/time-of-day**는 아직 결정하지 않았습니다.
5. 경로 변경/취소/도착, 화면 전환, 앱 백그라운드/복귀를 각각 시험합니다. onStop 이후 리스너를 유지해 수신 여부를 관찰하지만 백그라운드 동작을 보장하지 않습니다. 앱 종료 시 같은 리스너 제거와 release를 수행합니다.
6. 관측 간격, 이벤트 순서, 누락·음수·0 값, 재초기화 동작을 기록합니다. 서버로 보내는 ETA 변환을 구현하려면 증거와 테스트를 먼저 추가합니다.
7. [프로젝트 API 가용성 기준](../docs/INTEGRATIONS.md#api-선택-정책과-가용성)에 따라 내비 서비스 미제공·목적지 미설정·권한 거부·서비스 단절을 확인합니다. 이 probe는 결과를 관측하는 개발 도구이며, 제품의 수동 입력 대체 흐름과 주행 UX 제한은 아직 구현하지 않았습니다.

콜백 진입 시각을 먼저 채집하고 상태/UI는 main thread에서 처리합니다. 목적지 변경·경로 탐색 완료·재탐색 완료 시 기존 목적지를 무효화하고 재조회하며, 취소·도착·경로 탐색 시작·SDK 오류 시에는 무효화만 합니다. “목적지 다시 조회”로 명시적 재관측이 가능합니다. SDK에 응답 연관 ID가 없어 재조회 뒤의 구응답도 완전히 구분할 수 없습니다. `responseAssociation=UNVERIFIED`를 유지하고 TripInfo를 현재 목적지의 확정 ETA로 사용하지 않습니다.

로그의 `probeSessionId`는 Activity별 진단 식별자이며 서버 tripId가 아닙니다. `routeRevisionAtProcessing`, `foregroundAtProcessing`은 처리 시점 상태입니다. `rawEtaSign`은 부호만 나타내며 0/음수 의미는 미검증입니다. 종료 뒤 `ignoredAfterClose=true` 콜백은 상태/UI를 바꾸지 않습니다. SecurityException은 PERMISSION_DENIED, 그 밖의 초기화/호출 실패는 UNAVAILABLE_UNKNOWN이며 미지원·단절을 임의로 구분하지 않습니다.

로그는 이름/주소/위경도/POI ID를 출력하지 않으며, wall clock observedAt과 monotonic elapsedRealtimeMs를 같이 기록합니다. 수신한 목적지 정보는 요청을 위해 메모리에만 둡니다. 공유 캡처는 필요한 로그만 추출하고 저장소 제외 `captures/`에 둡니다. 원 SDK가 별도 로그를 출력할 수 있으므로 전체 logcat을 그대로 공유하지 않습니다.

## 기록 표

| 날짜·버전 | 시나리오 | rawEtaSeconds | 내비 표시 시간 | 간격·생명주기 | 기대/실제 | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-11-08 / SDK3.0.5 / API34 | 최초 TripInfo | 1541 | 사용자 보고: 기기04:37, 이동25분, 도착05:02 AM | 응답21:34:07 KST | UI와 동일 순간 대조 아님 | 원시 수신 확인, 시간 기준 미확정 |
| 같은 세션 | 두 번째 수동 TripInfo | 1538 | 추가 동시 UI 관측 없음 | 첫 응답에서412.901초 후 | 경과시간과 원시값 감소가 같지 않음 | 자동 주기/countdown 미확정 |
| 같은 세션 | 길안내 종료 | — | 사용자 종료 확인 | 백그라운드 routeCancelled 21:42:45 KST | 목적지 무효화·복귀 후 Trip 버튼 disabled | 이 취소 시나리오 확인 |
| 같은 세션 / 짧은 경로 | 재조회 후 수동 TripInfo | 603 | 직접 화면: 기기 05:09, 이동 11분, 도착 05:20 AM | 응답 22:08:29 KST, 캡처 구간은 약 58–130초 뒤 | 수치상 10분 3초로 이동 시간 계열과 유사 | 시간 기준·동시 대조 미확정 |

기기 시간대는 America/Los_Angeles이며 API34 emulator의 설정을 그대로 두었습니다. 화면 시각과 로그 observedAt(UTC)/보고 KST를 혼동하지 않습니다. 실제 증거20건과 비식별 요약은 Git 제외 captures/에 있고 다른 경로·자정·도착·권한 거부·미지원·단절·늦은 유효 응답은 후속입니다.

로그 보존 정정: 초기 원시 20건은 수집 당시 기록이며, 이후 스냅샷 덮어쓰기/logcat 순환으로 전체 원본은 남아 있지 않습니다. 첫 두 샘플 요약은 보존됐고 새 원시 스냅샷 11건과 짧은 경로 메타데이터를 별도 보존했습니다. 이후 수집은 중복 제거·누적 기록으로 변경했습니다. 상세 파일과 보존 한계는 [VEH-01 기록](../docs/VEH01_VALIDATION.md#원시-로그-보존-한계)을 따릅니다. 화면 원본은 로컬 work/에만 있으며 공개하지 않습니다.

현행 결과와 차단 사유는 [STATUS](../docs/STATUS.md)에 기록합니다. APK 생성 성공과 emulator 실행 성공은 따로 판정합니다.

## 2026-11-09 격리 두 번째 부팅 — 재탐색 완료 콜백 미관측

사용자 수동 설치 Success 후 앱 실행 Status: ok, 두 권한 허용과 SDK 초기화를 확인했습니다. 공개 길안내 시작 후 routeStarted(11:03:44.037310Z), 명시적 조회 VALID_FIELDS(11:04:10.363953Z), reRouteRequested 1회(11:04:41.361257Z)를 같은 세션에서 수신했습니다. 요청 시 revision=2/목적지 사용 가능=false로 무효화됐고 약1.323초 뒤 STOPPED/내비 전면 전환, 11:05:16.535357Z에 HOT 복귀했습니다. 복귀 화면에서 Trip/재탐색 버튼은 비활성이었습니다.

요청 전후 system_server 시작 횟수는1로 유지됐고 요청 후 최소131초 관측 창에 reRouteEnded는0건입니다. 앞선 Watchdog로 중단된 원본 데이터 시험과 구분합니다. 완료/SDK 실패/미지원으로 단정하지 않습니다. 11:06:56.771035Z 수동 재조회 후 11:06:57.806926Z VALID_FIELDS로 회복했습니다. UIAutomator와 재탐색 반복 요청은 사용하지 않았습니다. ETA 시간 기준/응답 연관성과 도착·자동 이탈 재탐색은 계속 미검증이며 #1은 미완료입니다.

비식별 증거: captures/veh01-clean-second-reroute-20261109-live.jsonl, veh01-clean-second-reroute-result.json. 화면은 Git 제외 work/veh01/clean-reroute-return.png에만 보관했습니다. 코드 변경/테스트 재실행/commit/push/PR/외부 문의 발송은 없습니다.

2026-11-09 Issue #1의 앱 인수 코멘트 반영: [차량 데이터 앱 인수안](../docs/VEH01_APP_HANDOFF.md)에 #16/#26 전달 정보·오류/수명 상태·Navigation 변환 차단·공동 시나리오를 정리했습니다. 협의용 문서이며 제품 adapter 구현/공통 계약 변경/앱 연결 완료를 뜻하지 않습니다.
