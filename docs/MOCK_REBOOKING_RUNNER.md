# 운영자 시스템 없이 로컬 MOCK 개발하기

`run-mock-rebooking.py`는 기존 앱 재예약 기기 검사를 재현하는 개발 도구다. 제품 서버나 운영자 관리 화면을 추가하지 않는다. 일반 앱의 인증·권한 구현은 바꾸지 않는다.

## 준비

- Java21, Android SDK adb, Pleos 에뮬레이터.
- 현재 코드의 별도 debug 앱과 androidTest APK를 설치한다. 원래 jointvalidation은 사용하지 않는다.
- 별도 앱은 기존 로컬 MOCK 서버 연결·인증과 정상 CarUx baseline이 준비되어 있어야 한다. 도구는 새 사용자 발급, 데이터 초기화, 기본8080/8081 연결 변경을 하지 않는다.
- 두 APK의 applicationId/testApplicationId가 일치해야 한다. `--package` 기본값은 `ai.pleos.assistant.mock.destinationvalidation`이다. 이 저장소의 RebookingDeviceChecks가 들어 있는 androidTest APK가 필요하다.

별도 설치 빌드 예: 임시 Gradle init 파일에 아래 내용을 넣고 `./gradlew -PincludeAndroid=true :android:app:assembleDebug :android:app:assembleDebugAndroidTest -I <init파일>`을 실행한다. 이후 두 APK를 `adb install -r`로 설치한다. 기존 앱 데이터는 지우지 않는다.

```groovy
allprojects { afterEvaluate { project ->
    if (project.path == ':android:app')
        project.android.defaultConfig.applicationId = 'ai.pleos.assistant.mock.destinationvalidation'
}}
```

## 실행

저장소 루트에서 JAVA_HOME을 Java21로 설정하고 실행한다.

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb" python3 scripts/run-mock-rebooking.py
```

다른 기기는 `--serial`, 다른 별도 검증 설치는 `--package`로 지정한다. 8082 reverse가 이미 있으면 덮어쓰지 않고 종료한다.

도구는 테스트 classpath를 준비하고 새 임시 디렉터리/DB·임의 loopback 포트에서 기존 OwnershipSmokeApplication을 실행한다. 상태가 MOCK/외부 호출false임을 확인한 다음 기기8082만 연결한다. 검사가 끝나거나 실패하면 자신이 만든 연결과 서버만 종료하며 DB/log는 출력한 임시 경로에 보존한다. 서버 제품 JAR에는 이 테스트 주체가 포함되지 않는다.

## 확인되는 것과 확인되지 않는 것

검사는 메모리 journal을 사용해 예약 거절 → 기록 보관 → 새 검색 → 후보 문의 → 별도 예약 승인 → 수락 GET/화면을 실행한다. 원 앱 repository/snapshot은 복원한다. 기기 로그에 명시적 PASS가 있어야 성공이며 adb 프로세스 exit code만으로 통과 처리하지 않는다.

**OwnershipSmokeApplication은 모든 요청에 합성 테스트 주체/역할을 부여한다. 이 도구는 일반 앱 Bearer와 운영자 권한 분리의 검증 수단이 아니다.** #40의 일반 토큰 outcome403, 별도 결과 입력 권한, 공동 인수는 여전히 별도다. 실제 전화·유료 호출·음성 예약·실제 ETA 연결도 실행하지 않는다. 실제 PENDING/UNKNOWN 기록을 새 주체로 우회하는 도구로 사용하지 않는다.

## 2026-11-11 실행 기록

새 도구를 현재 Pleos의 별도 destinationvalidation 설치에서 실행해 명시적 PASS를 확인했다. Gradle 테스트 classpath 준비 성공(UP-TO-DATE), 실제 synthetic HTTP 재예약 UI 흐름 PASS, 서버 프로세스 종료 및8082 reverse 제거 확인. 기존8080→18085/8081→18085는 유지됐다. APK/제품 코드는 변경하지 않았다.

로컬 실행 로그와 임시 DB: `/var/folders/6m/y9659t4j22j1d2dtg_cycdm80000gn/T/pleos-rebooking-n7j_v5nz`의 server.log/device.log. 임시 데이터는 Git에 포함하지 않는다. 이번 검사는 운영자 인증분리·공동 인수·실제 주행 검증이 아니다.
