# Pleos 주행 중 음성 예약 시험

별도 debug 패키지 `ai.pleos.assistant.drivingvoice`는 Gleo에서 **아리온**으로 호출한다. 기존 MOCK 추천·예약 API에서 가상 서울숲의 후보와 예약 결과를 받는다. 추가 시험 번호 등록은 필요 없다.

```sh
./gradlew -PincludeAndroid=true -PdrivingVoiceTest=true verifyAndroid
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8088 tcp:8088
```

[Pleos Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)의 기어를 D로 옮기고 가속해 주행 상태로 전환한다. Gleo의 듣기가 시작되면 아래 문장을 한 단계씩 말한다. 추천 목록과 선택 내역을 들은 뒤 다음 명령을 말한다.

1. “아리온 앱에서 어른 두 명, 아이 한 명으로 가상 식당을 찾아줘.”
2. “아리온 앱에서 두 번째 식당을 선택해 줘.”
3. “아리온 앱에서 두 번째 식당의 예약을 승인해 줘.”

정차한 뒤 앱 목록에서 **아리온**을 열면 음성 예약의 확정 결과가 표시된다. 화면은 음성 승인 처리기가 보관한 같은 가상 예약을 읽어 후보, 식당, 날짜·시각, 인원과 실제 발신 건수를 보여 준다. 새 APK를 기존 시험 앱 위에 설치해 앞서 녹화한 2번 식당의 16:37·3인 확정 기록이 화면에 표시되는 것도 확인했다.

시험 앱은 화면을 띄우지 않고 [Gleo 서비스 액션과 음성 응답](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)을 사용한다. 후보 선택은 현재 조회 ID와 후보 번호 및 제안한 시각에 연결된다. 유효한 후보를 선택한 뒤 같은 번호를 명시적으로 승인해야 기존 MOCK 예약 처리기가 요청을 저장하고 전송한다. 서버가 `CONFIRMED`를 반환한 경우에만 확정을 알려 준다.

2026-11-15 KST, Pleos Connect Emulator 2.1.0 / API 34에서 한국어 합성 음성을 에뮬레이터 마이크 입력으로 전달했다. Android 주행 상태 `MOVING`, 주행 제한 플래그 `80`에서 Gleo가 세 가지 액션을 모두 호출했다. 가상 후보 3곳, 2번 후보 선택, 성인 2명·아동 1명과 16:25의 가상 예약 확정, 실제 발신 0건을 확인했다. Gleo의 선택 재확인 및 확정 음성 응답 로그도 확인했다.

별도 명령 시험에서 후보 선택 전 승인, 선택과 다른 번호 승인, 중복 승인이 경로에서 차단되었다. 음성 요청에 “아리온 앱에서”가 빠진 한 시도는 Gleo가 일반 대화로 처리했다. 사람 발화에 대한 인식률, 실차 주행, 실제 추천·발신은 미검증이다. 제출 APK는 SHA-256이 기존 `83b8dee82e0898cf4d5ca6df96de6785957686b2739f22b81aeffcf04687f6c1`과 일치했다.
