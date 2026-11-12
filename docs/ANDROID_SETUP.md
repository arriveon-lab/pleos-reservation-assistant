# Android SDK와 Pleos 개발 환경

확인일 2026-11-04. 이 Mac(arm64)에 사용자의 Google SDK 약관 동의를 받은 뒤 **빌드용 SDK 설치와 프로젝트 연결을 완료**했습니다. 경로는 `~/Library/Android/sdk`, Command-line Tools 22.0, Platform 35 rev 2, Build-Tools 35.0.0, Platform-Tools 37.0.1입니다. Android Studio·Emulator·Pleos 이미지는 아직 설치하지 않았습니다. APK/Lint 실행 결과는 [STATUS](STATUS.md)에 별도로 기록합니다.

## 2026-11-08 Windows D드라이브 설치

위 Mac 기록과 별도로, 사용자의 D드라이브 설치 요청 및 Pleos/Google SDK 약관 명시 동의 후 다음 환경을 준비했습니다. 사용 통계 선택은 사용자가 직접 완료했습니다.

| 항목 | 설치 위치·확인 결과 |
| --- | --- |
| JDK | `D:\Java\temurin-21.0.13+9`, Java 21.0.13+9 실행 확인 |
| Android SDK | `D:\Android\Sdk`, Platform 35 / Build Tools 35.0.0 / Platform-Tools 37.0.1 |
| Emulator | D드라이브 SDK의 37.2.13. AEHD 2.2 가용성 확인, Windows 보안/가상화 설정 변경 없음 |
| Pleos 이미지 | `D:\Android\Sdk\system-images\android-34\android-automotive\x86_64` |
| AVD | `D:\Android\Avd\Pleos_Connect_v2.1.0.avd`, Cold boot / RAM 4096MB / 4 core / host GPU |
| Studio | 기존 Android Studio 2026.2.2 Patch 1 초기 설정 완료. 새 캐시/로그는 `D:\Android\Studio` |
| 실제 부팅 | ADB device 연결, boot_completed=1, Android 14/API 34, `RELEASE.release_connect_v2.1.0 test-keys`, 42dot |

공식 Windows 이미지 ZIP은 약 4.09GB, 압축 해제 후 약 18.29GiB입니다. `D:\Android\Downloads\crp_car_emulator.zip`의 크기 4,087,751,964 bytes와 공식 배포 SHA-1 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`를 대조했습니다. 다운로드 중 멈춤은 파일을 지우지 않고 이어받아 해결했습니다. 배포 XML의 package path/revision(`pleos_car_emulator`/2)과 ZIP 원본 source.properties(`android-automotive`/1)가 달라, 최종 설치는 Studio가 해석한 ZIP 식별자의 경로를 사용합니다. 원 이미지와 source.properties는 수정하지 않았습니다. Studio 목록에는 일반 Automotive 이름으로 표시되지만 실제 image.sysdir과 부팅 버전으로 Pleos임을 확인했습니다.

로컬 전용 `local.properties`는 D드라이브 SDK, `gradle-local.properties`는 Android opt-in, `.gradle/config.properties`는 D드라이브 JDK로 연결했습니다. 세 파일은 Git 제외입니다. 사용자 환경변수 ANDROID_HOME/ANDROID_AVD_HOME/STUDIO_PROPERTIES도 D드라이브 경로를 가리킵니다. 시스템 PATH/JAVA_HOME, C드라이브 기존 SDK·다른 사용자의 AVD는 변경하지 않았습니다. 변경 전 사용자 환경변수는 Git 제외 `work/veh01/user-environment.before.json`에 기록했습니다.

다음 파일을 더블클릭하면 새 로그인 전에도 D드라이브 환경이 적용됩니다.

- `D:\Android\Start-Pleos.cmd`: Pleos Cold boot 실행
- `D:\Android\Open-Android-Studio.cmd`: D드라이브 SDK/AVD/캐시·Gradle JDK를 적용해 기존 Studio 실행

부팅 확인 후 emulator는 종료했습니다. 첫 실행의 ADB 자동 경로 감지 문제는 재실행 시 D드라이브 platform-tools를 PATH에 명시해 해결했고, 두 번째 실행에서는 ADB device와 약 40초 부팅 로그를 확인했습니다. 보안 승인이나 ADB 키 복사로 우회하지 않았습니다. 화면 캡처 도구 시간 초과로 그래픽 홈 화면은 시각 검증하지 못했습니다. APK 설치·앱 실행·NaviHelper 목적지/ETA 수신은 별도 후속 단계입니다. 아래 공통 설치 절차와 기존 Mac 검증 이력은 계속 참고할 수 있습니다.

## 팀 공통 기준

| 항목 | 프로젝트 기준 | 역할 |
| --- | --- | --- |
| JDK | 21 | 서버와 Gradle 실행. Android Java 소스 수준은 17 |
| Gradle / AGP | 8.11.1 / 8.9.2 | 저장소 Wrapper 사용, IDE의 자동 업그레이드는 별도 PR |
| compileSdk / targetSdk | 35 / 35 | 컴파일 및 앱 대상 API |
| minSdk | 28 | 최소 설치 API |
| Build-Tools | 35.0.0 | APK 빌드 도구 |
| Pleos 실행 이미지 | 공식 가이드의 API 34, Pleos Connect v2.1.0 | 실제 SDK 동작을 확인할 emulator |

**컴파일 SDK 35와 실행 이미지 API 34는 다른 설정입니다.** minSdk 28 앱을 API 34에서 실행하는 구성으로 준비하며, 실제 호환성은 빌드/기기 실행으로 확인합니다. [AGP 호환표](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [Pleos 개발 환경](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

서버 전담자는 Android SDK 없이 `./gradlew verify`를 실행할 수 있습니다. 차량/통합 담당자는 아래 환경을 준비합니다.

## 1. Android Studio와 SDK 설치

이 Mac은 IDE 없이 공식 Command-line Tools로 설치했습니다. 새 팀원은 아래 Studio 방식 또는 공식 [Command-line Tools 다운로드](https://developer.android.com/studio#command-line-tools-only)를 사용할 수 있습니다. CLI ZIP을 `SDK/cmdline-tools/latest/` 아래 bin/lib가 오도록 풀고 공식 SHA-256을 대조합니다. SDK 패키지 설치 단계에서 본인의 라이선스를 검토·수락합니다.

```bash
# macOS 설치 예시; 본인의 SDK 경로로 바꿉니다.
"$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager" \
  --sdk_root="$HOME/Library/Android/sdk" \
  "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

현재 Command-line Tools는 sdkmanager에 deprecated 경고를 출력합니다. 이 프로젝트의 설치는 해당 호환 명령으로 완료했으며, 새 [Android CLI](https://developer.android.com/tools/agents/android-cli)의 `android sdk` 명령도 제공됩니다. 사용 시 `--no-metrics`로 도구 통계 수집을 끌 수 있습니다. SDK 설치가 CLI의 모델 호출/인증/클라우드 기기 서비스 사용을 뜻하지 않습니다.

이번 Apple Silicon 배포물은 `commandlinetools-mac_arm64-15859902_latest.zip`, 공식 SHA-256은 `835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e`로 다운로드 파일과 일치했습니다. 플랫폼 패키지는 공식 SDK Manager로 설치했습니다. 향후 새 ZIP을 받으면 그 버전의 공식 체크섬으로 다시 확인합니다.

1. [공식 Android Studio](https://developer.android.com/studio/install)에서 이 Mac에 맞는 Apple Silicon 버전을 설치합니다. 팀의 Windows/Linux 사용자는 해당 OS 배포물을 사용합니다.
2. 초기 Setup Wizard를 완료하고 **Tools → SDK Manager**를 엽니다. 환영 화면에서는 More Actions → SDK Manager를 사용할 수 있습니다.
3. SDK Platforms에서 **Android 15 / API 35의 Android SDK Platform**을 설치합니다.
4. SDK Tools에서 다음 항목을 설치합니다. Show Package Details를 켜면 Build-Tools 버전을 고를 수 있습니다.
   - Android SDK Build-Tools **35.0.0**
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools (latest)
   - Android Emulator
5. 설치 UI에 표시되는 라이선스를 검토하고 진행합니다. 이 저장소의 스크립트는 라이선스 수락이나 SDK 다운로드를 자동 실행하지 않습니다.

SDK Manager의 Android SDK Location 값을 사용합니다. macOS 기본값은 `/Users/<사용자>/Library/Android/sdk`, Windows 기본값은 `%LOCALAPPDATA%\Android\Sdk`, Linux는 보통 `~/Android/Sdk`입니다. 실제 SDK Manager 표시 경로를 우선합니다. [SDK Manager 안내](https://developer.android.com/studio/intro/update#sdk-manager)

## 2. 프로젝트 연결

저장소 루트에서 실행합니다.

```bash
# 설치 상태만 점검: 파일을 변경하지 않음
python3 scripts/setup-android.py

# SDK가 설치된 후 프로젝트에 연결
python3 scripts/setup-android.py --sdk "$HOME/Library/Android/sdk" --configure
```

필수 SDK 파일과 JDK 21을 찾은 경우에만 다음 로컬 파일을 만듭니다.

```properties
# local.properties
sdk.dir=/Users/본인계정/Library/Android/sdk
```

```properties
# gradle-local.properties
includeAndroid=true
```

두 파일 모두 Git 제외입니다. 개인 절대경로를 공통 gradle.properties에 넣지 않습니다. 수동 설정 시 `gradle-local.properties.example`을 복사할 수 있습니다. 기존 파일의 다른 설정은 스크립트가 유지합니다. 경로에 일반적인 영문/공백을 포함한 macOS·Windows 형식을 지원하며, 특수한 Java properties escape를 직접 작성했다면 `--sdk`로 명시합니다.

Android Studio에서 **저장소 루트**를 열고 Gradle Sync를 수행합니다. `android/`만 별도 프로젝트로 열지 않습니다. Settings → Build, Execution, Deployment → Build Tools → Gradle에서 Gradle JDK를 설치된 **JDK 21**로 선택합니다. `gradle-local.properties` 덕분에 IDE에서도 Android 모듈을 포함합니다. 일시적으로 서버만 빌드할 때는 `./gradlew -PincludeAndroid=false verify`로 덮어쓸 수 있습니다.

터미널에서 adb/sdkmanager를 사용할 때만 아래 환경 변수를 셸 설정에 추가합니다. ANDROID_SDK_ROOT는 새로 설정하지 않습니다. [Android 환경 변수](https://developer.android.com/tools/variables)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
```

## 3. APK 빌드부터 확인

```bash
./gradlew -PincludeAndroid=true verifyAndroid
```

결과 파일: `android/probe/build/outputs/apk/debug/probe-debug.apk`. 이 명령은 기기 없이 포맷·Lint·단위 테스트 태스크·APK를 확인합니다. 현재 테스트 소스가 없으면 `NO-SOURCE`로 표시되며 실행 테스트 수로 세지 않습니다. `verifyAll`은 서버 검사도 함께 수행합니다. 오류가 나오면 로그를 남기고 STATUS를 갱신합니다.

## 4. Pleos emulator 설치

SDK Manager → SDK Update Sites → `+`에서 **Pleos Connect System Image**를 추가합니다. 이 Mac에서 사용할 공식 경로는 다음과 같습니다.

```text
https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml
```

Windows/Linux용 URL과 상세 화면은 [공식 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다. SDK Platforms에서 Show Package Details를 켜고 API 34의 Pleos Connect system image를 설치한 뒤 Studio를 재시작합니다.

Device Manager에서 Automotive → Pleos Connect v2.1.0 → API 34 Pleos 이미지를 선택합니다. 공식 가이드 기준 RAM 4GB, **Cold boot**로 설정합니다. Quick boot는 VHAL 동작에 제약이 있습니다. [Pleos 설정 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)

## 5. 기기에서 probe 실행

```bash
adb devices
# 여러 기기가 있으면 adb -s <serial> ... 로 대상 지정
adb install -r android/probe/build/outputs/apk/debug/probe-debug.apk
adb logcat -s PleosNaviProbe:I '*:S'
```

Virtual Console은 emulator 실행 후 `adb forward tcp:8088 tcp:8088`로 연결하고 브라우저에서 `http://127.0.0.1:8088/`을 엽니다. [공식 Virtual Console 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)

검증 시나리오와 기록 항목은 [probe README](../android/README.md)를 따릅니다. 일반 Android emulator에서 APK가 실행됐다는 사실만으로 Pleos 권한/서비스/이벤트가 검증됐다고 보고하지 않습니다. 목적지 조회, ETA 시간 기준, 이벤트 주기, 백그라운드, 도착 이벤트를 따로 기록합니다.

## 완료 기준

SDK 파일 확인 → APK 생성 → Pleos emulator 실행 → 실제 NaviHelper 콜백 수신을 각각 구분합니다. 첫 단계만으로 Android 세팅 전체 완료라고 표시하지 않습니다. 실기기 검증 전 source=PLEOS 서버 입력 차단은 유지합니다.

### 2026-11-09 검증 환경 추가 관측

현재 호스트는 MX450 2GB/Intel Iris Xe, RAM 약 8GB입니다. [최신 공식 구성 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)의 최소 GPU 예시는 RTX 3060 Laptop 6GB 또는 RX 6600M/RX 6700S 8GB 이상입니다. 현재 환경에서 초기화/목적지/TripInfo 수신은 관측했으나 그래픽·장기 실행 안정성을 보장하지 않습니다. GPU 부족을 모든 오류의 원인으로 확정하지도 않습니다. 추가 설치/구매는 진행하지 않았습니다.

표준 geo fix 주입은 Android GPS에 반영됐지만 이번 내비 도착/재탐색 시도에서는 위치 이동을 확인하지 못했습니다. 공식 Pleos 재생 경로 확인이 필요합니다. 주입 이후 기기 시간대는 Asia/Seoul로 관측됐으며 수동 시각/시간대 변경은 없었습니다. 새 시험 전에 persist.sys.timezone과 date를 읽고 UTC 관측 시각과 UI 시각을 분리합니다. 상세 증거/한계는 STATUS를 따릅니다.
