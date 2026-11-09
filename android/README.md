# NaviHelper SDK probe

위치·사용자 음성의 제품 연결은 [SDK 대체·단계별 연동 계획](../docs/SDK_FALLBACK_PLAN.md)을 따릅니다. 이 probe의 진단 범위는 유지하며, 표준 위치/STT/TTS adapter 구현과 기기 가용성 검증은 별도 작업입니다. ArriveOn 콘솔에서 NaviHelper는 별도 신청 없이 사용 가능한 상태지만 기기 수신·ETA 시간 의미 확인은 여전히 필요합니다.

예약 앱 UI를 만들기 전 **실제 SDK 수신값과 시간 의미**를 확인하는 정차 개발용 앱입니다. 서버/전화/모델과 연결하지 않습니다. `measurementMode=SDK_PROBE`, `source=PLEOS`는 관측 경로 표시이며, 예약 시스템의 LIVE 모드나 기기 검증 성공을 의미하지 않습니다.

## 구성

- AGP 8.9.2 / Gradle 8.11.1 Wrapper / compileSdk·targetSdk 35 / minSdk 28
- Java 17 문법, JDK 17 이상(서버 개발 JDK 21로 Gradle 구동 가능)
- NaviHelper 3.0.5, NAVI_ROUTE 권한
- Gradle Wrapper JAR 및 배포 ZIP SHA-256 검증 설정

[AGP 공식 호환표](https://developer.android.com/build/releases/agp-8-9-0-release-notes)에 맞춘 초기 조합입니다. Pleos SDK의 기기별 호환성은 실제 빌드·실행으로 별도 검증해야 합니다.

기본 Gradle 설정에는 서버만 포함됩니다. [설치·연결 가이드](../docs/ANDROID_SETUP.md)를 따라 SDK를 설치하고 `python3 scripts/setup-android.py --configure`를 실행하면 Git 제외 `gradle-local.properties`의 `includeAndroid=true`로 IDE에서도 `:android:probe`가 포함됩니다. 루트 Wrapper와 버전 설정을 공유하며 android 하위 폴더에 별도 Wrapper를 두지 않습니다.

## 빌드와 설치

2026-11-04 이 Mac에서 SDK 설치·연결, Java 컴파일·DEX·debug APK, Lint 오류/경고 0을 확인했습니다. debug 서명도 검증했습니다. Android 단위 테스트는 아직 소스가 없어 NO-SOURCE이며, Pleos 이미지·앱 설치·콜백 수신은 미검증입니다. 자세한 코드 기준은 [CODE_CONVENTIONS](../docs/CODE_CONVENTIONS.md)를 따릅니다.

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
2. “NaviHelper 초기화 · 목적지 조회”를 누릅니다. `initialized`, `destinationInfo`를 확인합니다. 오류면 code와 권한 grant 상태를 기록합니다.
3. “현재 목적지 TripInfo 1회 요청”을 누르고 `tripRequested` → `tripInfo`를 관측합니다. `getTripInfo()`는 존재한다고 가정하지 않습니다.
4. rawEtaSeconds와 동일 시점 내비 화면의 남은 시간/도착 예정 시각을 비교합니다. 짧은/긴 경로와 60초 경과, 자정 경계에서 반복합니다. 소스 주석으로 단위 초는 확인했지만 **duration/epoch/time-of-day**는 아직 결정하지 않았습니다.
5. 경로 변경/취소/도착, 화면 전환, 앱 백그라운드/복귀를 각각 시험합니다. onStop 이후 리스너를 유지해 수신 여부를 관찰하지만 백그라운드 동작을 보장하지 않습니다. 앱 종료 시 같은 리스너 제거와 release를 수행합니다.
6. 관측 간격, 이벤트 순서, 누락·음수·0 값, 재초기화 동작을 기록합니다. 서버로 보내는 ETA 변환을 구현하려면 증거와 테스트를 먼저 추가합니다.
7. [프로젝트 API 가용성 기준](../docs/INTEGRATIONS.md#api-선택-정책과-가용성)에 따라 내비 서비스 미제공·목적지 미설정·권한 거부·서비스 단절을 확인합니다. 이 probe는 결과를 관측하는 개발 도구이며, 제품의 수동 입력 대체 흐름과 주행 UX 제한은 아직 구현하지 않았습니다.

로그는 이름/주소/위경도/POI ID를 출력하지 않으며, wall clock observedAt과 monotonic elapsedRealtimeMs를 같이 기록합니다. 수신한 목적지 정보는 요청을 위해 메모리에만 둡니다. 공유 캡처는 필요한 로그만 추출하고 저장소 제외 `captures/`에 둡니다. 원 SDK가 별도 로그를 출력할 수 있으므로 전체 logcat을 그대로 공유하지 않습니다.

## 기록 표

| 날짜·버전 | 시나리오 | rawEtaSeconds | 내비 표시 시간 | 간격·생명주기 | 기대/실제 | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 미실행 | 최초 조회 | — | — | — | — | 미검증 |

현행 결과와 차단 사유는 [STATUS](../docs/STATUS.md)에 기록합니다. APK 생성 성공과 emulator 실행 성공은 따로 판정합니다.
