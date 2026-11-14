# UX-02 실제 차량 제한 연결과 로컬 검증 (#25)

2026-11-09. PR #36의 인증·미리보기 기반 위에 구현했다. #19 최신 `5526f92` 대조와 재검증은 [미리보기 기록](UX26_PREVIEW_VALIDATION.md)을 참고한다. **로컬 MOCK 검증이며 공동 인수 완료가 아니다. #25/#5는 OPEN, PR은 Draft를 유지한다.**

## 구현·공식 API 근거

- compileSdk 35의 `optional/android.car.jar` 공개 선언을 `javap`로 확인했다. `Car.createCar(Context, Handler, long, CarServiceLifecycleListener)`, `CAR_WAIT_TIMEOUT_DO_NOT_WAIT`, `CarUxRestrictionsManager.getCurrentCarUxRestrictions/registerListener/unregisterListener`, `Car.disconnect`를 사용한다. 실행 이미지는 Pleos Connect v2.1.0/API34다.
- [Pleos 주행 제한 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [AAOS 제한 소비 가이드](https://source.android.com/docs/automotive/driver_distraction/consume), [Car API](https://developer.android.com/reference/android/car/Car), [제한 Manager API](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager.html)를 기준으로 삼았다. 속도·기어를 읽어 정차를 추정하지 않는다.
- 자동차 feature와 클래스 가용성을 확인하고 비동기로 연결한다. 첫 상태는 UNKNOWN이다. `requiresDistractionOptimization=false`와 flags=0이 함께 확인된 경우에만 `PLATFORM_UNRESTRICTED`다. 다른 값·미지원·접근 거부·조회 실패·서비스 단절·10초 연결 시간 초과·정리 실패는 입력/승인을 차단한다. 5초 상태 확인과 listener를 병행한다.
- Activity의 onStart/onStop에 연결/해제를 묶고 세대 번호로 종료 후 callback을 무시한다. callback 도착 시 현재 값을 다시 읽어 오래 대기한 payload로 입력을 풀지 않는다. 화면 종료·제한 시 미리보기 식별자/결과를 무효화한다.
- 수동 모의 정차 해제 버튼을 제거했다. Debug 버튼은 추가 제한/실패만 주입하며 해제는 새 플랫폼 조회로만 가능하다. core harness의 SIMULATED_PARKED는 제품 앱에서 선택하지 않는다.
- 승인 창은 제한 발생 시 닫히고 positive callback에서도 재검사한다. 저장 후 요청 전, 인증 상태 조회 후 신규 발급 전, HTTP POST 전에도 제한을 확인한다. GET 복구·취소/종료·동일 주체 토큰 갱신은 가능하다. 이미 서버로 보낸 요청은 취소된 것으로 단정하지 않고 기존 동일 요청/GET 복구 규칙을 유지한다. 플랫폼 변경과 네트워크 전송의 분산 원자성은 보장하지 않는다.
- 공개 API 계약·서버 코드·#1 probe는 변경하지 않았다. release의 MOCK transport 차단은 유지하며 배포 사용 가능을 선언하지 않는다.

## 실행 환경·증거

- Pleos Connect v2.1.0, API34, RAM4GB, cold boot, host GPU/Vulkan 비활성, 2560×1440, density160. automotive feature와 car_service를 실제 확인했다.
- 기존 앱과 데이터 보존을 위해 `ai.pleos.assistant.mock.uxvalidation` 별도 패키지로 설치했다. 서버는 #19 `5526f92`의 격리 JAR, MOCK/preview opt-in, host18082 → emulator8080이다. 가상 데이터만 사용했다.
- 최종 검증 APK SHA-256: `fe20f944a90418e2f9e5f078943e4e18e6baab196925a62ec5c532d5b3decc50`. 기본 패키지 배포용 APK의 해시와 혼동하지 않는다.
- 원본 PNG/XML·해시 목록은 Git 제외 `captures/ux25-driving/`와 `manifest.json`, 실행 로그는 `work/ux25/`다. GitHub 이미지 업로드/영상 생성은 하지 않았다. 캡처는 기본·제한·실패·미리보기 화면이며 마지막 APK에서는 사용하지 않던 helper 제거만 달라졌고 최종 기기 instrumentation을 다시 실행했다.

| 절차 | 기대 결과 | 실제 결과 / 검증 수준 |
| --- | --- | --- |
| 설치·실행 → 초기 CarUx 조회 | 값 확인 전 UNKNOWN, 확인 후 정책 적용 | 실제 `INITIAL requiresDo=false flags=0`, 플랫폼 제한 없음 안내. `01-platform-initial`, `02-authenticated`. 정차 추정/실차 검증이 아님 |
| 명시 MOCK 인증 → 조건 입력 | 인증과 플랫폼 허용 모두 필요 | 실제 조건 입력 활성화, 신규 가상 인증 성공 |
| 추가 제한 버튼 → 입력/승인 | 입력 비활성화, 승인 창 닫힘, 이전 승인 클릭도 차단 | `03-injected-restriction`; 기기 instrumentation의 no-op 승인 callback 호출 0회. **제한 주입**, 실제 moving callback 아님 |
| 조회 실패 버튼 → 재확인 | UNKNOWN 차단, 새 플랫폼 확인 전 해제 금지 | `04-injected-failure`; 입력 비활성화. 재확인 후 실제 초기값으로 복구. **오류 주입**, 서비스 실제 장애 아님 |
| finish → 재진입 / Activity.recreate | listener 해제, 재연결, 캐시만으로 해제 금지 | instrumentation PASS, 실제 `DETACHED/INITIAL` 로그. 물리 회전·창 크기 변경과 구분 |
| 제한 중 POST 호출 | 네트워크 전송 전 INPUT_BLOCKED | 기기 instrumentation PASS. 호출자 입력과 전송 경계 모두 검사 |
| 최신 #19 정상 미리보기 → 추가 제한 | 후보3개·UNKNOWN, 제한 시 결과 폐기·입력 차단 | `05-preview-platform`, `06-preview-results`, `07-preview-restricted`; 실제 MOCK 응답 + 제한 주입 |
| 늦은 callback·모든 flags·단절/권한 오류·저장 중 제한 변경 | 오래된 허용 거부, pending 보존/전송0 | JVM controller/repository 테스트. 실제 기기 서비스 단절로 확대 해석 금지 |

기기 instrumentation은 `DrivingDeviceChecks`이며 실제 automotive baseline + 명시 인증한 MOCK 검증 설치가 선행 조건이다. 다른 환경에서 무조건 통과하는 자동화가 아니다. 별도 applicationId로 앱/테스트 APK를 빌드·설치한 뒤 `adb shell am instrument -w ai.pleos.assistant.mock.uxvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`를 실행했다. CI는 기기가 없어 이 검사를 실행하지 않는다.

## 실행 검사와 한계

- `./gradlew -PincludeAndroid=false format`: 성공.
- `./gradlew -PincludeAndroid=true verifyAll`: 성공. UX58개 실행, 실패/오류/skip0. 서버268개 기존 통과 결과는 UP-TO-DATE이며 이번 새 실행 건수로 세지 않는다. Android Lint/debug APK 성공, JVM Android 단위 테스트 NO-SOURCE는 건수로 세지 않는다.
- 최종 기기 테스트 변경 후 `verifyAndroid`를 다시 실행해 성공했다. 원격 CI는 PR 최신 head에서 별도로 확인한다.
- `python3 scripts/smoke-test.py`: MOCK HTTP/Flyway/소유 H2 재시작/LIVE 차단 PASS, 외부 호출0. #19 서버 전체 테스트를 이번에 재실행했다는 의미는 아니다.
- 신규 테스트 초기 실행에서 fixture 초기화 전 callback NPE가 있었고 초기 저장과 검사 시점을 구분해 수정한 뒤 58개 모두 통과했다.
- 앱 Lint 오류0, 경고3: AndroidX 갱신2건, 공개 `android.car.Car` 가용성 확인용 `Class.forName`에 대한 PrivateApi 경고1건. 고정 SDK 공개 stub를 확인했으며 hidden method 호출은 없다. 억제하지 않았다. 의존성/target SDK 갱신 검토는 #25에 남긴다.
- **미검증:** 실제 moving→제한 변경 callback, 실제 권한 거부/서비스 단절/시간 초과, 물리 회전·화면 크기 변화, 실차/다른 이미지, release 서명·설치, 시연 영상, 서버 담당과 공동 기기 인수. user 이미지에서 `cmd car_service help`는 non-user build 요구로 거부됐다. root/보호 해제 없이 안전한 상태 전환 수단을 확보한 후 실제 전환을 검증해야 한다. 개발 버튼 결과로 이 조건을 체크하지 않는다.

#25 완료 조건은 위 미검증 항목까지 확인한 뒤 갱신한다. PR #36을 기반으로 한 공유 선행 이력이 포함되어 있으므로 통합 순서·main 반영을 검토해야 하며 다른 공유 브랜치를 강제 갱신하지 않는다.
