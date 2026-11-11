# #52 목적지 추천 MOCK 화면 인수 기록

## 2026-11-11 차량용 선택·비교 화면 단순화

사용자 피드백에 따라 키보드/드롭다운 중심 폼을 큰 선택 버튼으로 교체했다. 목적지2개·인원±·필수 조건 토글, 아래 한식/일식/중식/양식/분식 다중 선택 및 전체 해제를 제공한다. 결과가 오면 입력 폼을 숨기고 후보 비교로 전환한다. 넓은 창은 후보3개 가로 배치, 좁은 창 또는 확대 글꼴은 세로 배치다. 상세 근거는 별도 보기와 비교 복귀로 분리했다. 선택값 변경은 이전 결과를 폐기하며 기본 MOCK·주행 차단·기존 예약 journal을 유지한다.

공식 [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button)의 명확한 동작/비활성 상태와 [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout)의 창 크기 대응을 참고했다. 공식 전체 적합성 인증을 뜻하지 않는다. 선택 시 색상 재설정이 초기 disabled 배경을 남기던 문제는 현재 선택/제한 상태에 따라 명시적으로 색을 적용해 수정했다.

**음식 취향의 현재 범위:** 앱은 `preferredCuisines` 배열에 선택값을 전달한다. #53 cc393399의 Controller는 이 필드를 접수하지만 WEB_SEARCH 서비스가 만드는 WebRecommendation.Request에는 음식 취향이 전달되지 않는다. 따라서 **선택/해제·요청 전달 완료와 실제 추천 반영은 별도**다. 선택 화면과 결과 화면에 현재 가상 후보에는 미반영임을 표시한다. 실제 필터/정렬 완료를 주장하지 않으며 #52 서버 담당에게 반영 경로를 요청한다. 주차/아기의자 같은 필수 조건을 음식 취향으로 대체하지 않는다. API 필드 추가나 서버 담당 코드 수정은 하지 않았다.

| 검사 | 실제 결과 |
| --- | --- |
| 동일 APK, 주간1682dp/글꼴100% | 조건 선택→한식+양식→HTTP→후보3곳 가로 비교→상세/복귀 PASS |
| 동일 APK, 야간840dp/글꼴150% | 세로 배치·스크롤·상세/복귀 PASS. 측정된 버튼72dp 이상/텍스트 잘림 없음 검사 PASS |
| 음식 선택/해제 | 두 선택값 유지, ‘상관없음’으로 전체 해제, 재선택 PASS. 로컬 전달 프록시에서 `preferredCuisines=[한식,양식]`, `requiredFeatures=[parking,highChair]` 실제 요청 확인 |
| 입력 변경·취소·제한·재실행 | 이전 카드 폐기·주행 제한/UNKNOWN 개발 주입에서 입력 차단·journal 동일 PASS |
| 자동 검사 | format/verifyAll/MOCK smoke PASS. UX107개 결과(음식 목록 복사·경계·요청 필드 회귀1개 추가), 실패0. 서버/기존 검사는 캐시·UP-TO-DATE 포함. 기존 Lint4개 유지 |

제품 APK SHA256 `2d868d088720e1a30eaaa56192272e033f208acc9a1292c7f4871aa6165b4036`, 별도 destinationvalidation에 install-r. 원래 jointvalidation XML은 이전 해시와 전체 동일하다. 증거는 `captures/destination-ui/wide-*.png`, `narrow-night-*.png`, `manifest.json`; 로그는 `work/destination-ui/device-*.log`, `requests.log`. 요청 관측은 가상 조건만 기록했고 토큰/개인정보를 출력하지 않았다.

초기840dp resize 직후 진입 검사는 `destination entry missing`으로 실패했다. 동일 창이 안정된 상태에서 다시 실행해 PASS를 확인했으며 초기 실패 로그도 보존한다. 이 결과를 OEM 전체 분할창/실제 주행 검증으로 확대하지 않는다. 최종 주간/글꼴100%로 복원하고 새 선택 화면을 사용자 확인용으로 열었다. 새 APK에서 앞선7개 서버 fixture 전체를 다시 실행한 것은 아니며 이번에는 THREE와 음식 선택·두 창 조합을 검사했다. 실제5분/프로세스 kill/실제 음식별 결과 변화·공동 인수는 남는다.

## 범위와 고정 기준 (2026-11-11)

- 앱 구현: `427bcb9` / PR #46 / `feat/39-vehicle-ui`. 기존 #36 인증·복구/#44 주행 제한/#39 예약 화면을 보존하고 별도 추천 화면을 추가했다.
- main `286f2a1`의 문서 변경과 기존 앱 검증 이력을 함께 보존해 충돌을 해결했다. 공유 이력을 재작성하거나 force push하지 않았다.
- 서버: 미병합 Draft [#53 / cc3933999c11d14336f841ec04354b6712144fa9](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/53), [0.1.0-draft 계약](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/cc3933999c11d14336f841ec04354b6712144fa9/api/destination-recommendation-draft.json). 이 서버 코드의 인증·Controller·Service·파서를 실행했고 WebGateway만 가상 응답으로 교체했다. main 서버에 새 API가 존재한다고 해석하지 않는다.
- 입력: 가상 목적지 2개 중 명시 선택, 성인1–20/아동0–10, 주차 필수, 선택 아기의자/무계단, 대기15분. 사용자 실제 위치/가족/연락처 없음. 실제 ETA·NaviHelper·Gleo 입력은 이번에 연결하지 않았다.
- 정책: [#11 회신](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11#issuecomment-6771961108). 현재 화면 메모리에만 보관하고 가장 이른 job/근거 만료와 로컬 최대300초를 적용한다. journal/백업/해시에 추천 데이터를 저장하지 않는다. #54는 미구현/not planned 유지.

## 계약 대조와 화면 동작

1. `POST /api/experimental/destination-recommendations` → `taskId` → 같은 사용자 Bearer로 GET 폴링. 목적지/party/requiredFeatures를 보낸다. ETA는 만들지 않는다. POST의 Idempotency-Key 재전송 보장은 없으므로 응답 유실 때 자동 POST 재시도하지 않는다.
2. `source.provider=WEB_SEARCH`, `result.webState`, `result.webRecommendations` **배열**을 읽는다. 기존 설명의 `.candidates` 객체나 `matches`만 읽는 구현을 사용하지 않는다. 유효한 후보2곳도 RESULTS다.
3. 이름/주소, 주차·선택 조건의 YES/NO/UNKNOWN/CONFLICT, 추가 확인 조건, 출처·후기·관측/원문 갱신/유효기한을 표시한다. 카드 요약과 펼침 상세로 분리했다. GENERAL 근거를 도착 시점 보장으로 바꾸지 않는다. 숫자 평점·정확한 거리순을 만들지 않는다.
4. `identityVerified/bookable/inquiryDispatchAllowed=false`를 검증하고 미확인·예약 불가를 표시한다. ordinal은 화면 순서이며 전화/예약 ID가 아니다. 추천 화면에 발신·예약 승인 버튼을 붙이지 않았다. 기존 MOCK 문의/예약 흐름은 별도다.
5. 입력 변경/취소/뒤로가기/onStop/주행 제한/만료에서 화면 식별자를 먼저 폐기한 뒤 `/views/{viewId}/close`를 호출한다. 늦은 POST/GET이 돌아와도 이전 결과를 되살리지 않는다. 취소 실패는 별도 안내하며 이전 결과를 표시하지 않는다. 재실행 시 자동 검색/복원 없음.
6. GET404는 조회 불가로 후보를 제거한다. EMPTY/FILTERED/FAILED/EXPIRED/승인 필요를 별도로 표시한다. 동일 생성의 중복 클릭은 앱에서 차단한다. 다음 검색은 명시 입력으로 새 viewId/증가 generation을 사용한다.

### MOCK 실행 보호

#53은 예약 `app.mode=MOCK`만으로 유료 웹 호출이 차단되는 서버가 아니다. 따라서 앱의 새 경로는 **8081의 별도 로컬 검증 서버**만 사용하고, 매 요청 전 `/mock-capabilities`의 `destinationRecommendationsMode=MOCK`, `externalCallsEnabled=false`를 확인한다. 없거나 다르면 요청을 보내지 않는다. 이 표식은 **이번 검증 harness 전용이며 공개 #53 API 계약을 추가한 것이 아니다.** #53 운영 서버에 표식만 임의로 추가해 우회하지 않는다.

인증/기존 예약은8080을 사용한다. 새 테스트 설치에서 두 포트를 같은 격리 서버로 연결해 같은 주체를 사용했다. 기존 설치의 UNKNOWN을 새 주체로 우회하지 않았다. 공급자 bean은 가상 구현이고 `app.destination-recommendations.web.enabled=false`로 유료 구현을 끈다. 외부 장소/GPT/전화/유료 호출0.

## 실제 Pleos 실행 결과

Pleos Connect2.1.0 API34 / emulator-5554 / user10 / 주간·기본 글꼴·1682dp 창. 별도 `ai.pleos.assistant.mock.destinationvalidation` 설치, 고유 Keystore와 독립 H2 DB를 사용했다. 기존 `jointvalidation` APK/인증/DB를 교체하거나 삭제하지 않았다.

- 동일 제품 APK: `db11287e87cf233e508d7b9a5b92da4c57ea9cbad9cf6222233dce05a90f1757`
- instrumentation APK: `bfd4099b24ecb6a7d634dcf8c6bf7ba7cbc26714a98b8776e41f4af4e8d80400`
- 로컬 APK: `work/destination53/tested.apk`, `tested-test.apk`
- 화면 증거: `captures/ux52-destination/`의 PNG와 `manifest.json` SHA256 목록. 실행 로그: `work/destination53/device-*.log`, `server-*.log`. 가상 응답 외 실제 후보/인증 값은 캡처하지 않았다.

| 절차 | 기대 결과 | 실제 결과·검증 수준 |
| --- | --- | --- |
| THREE: 화면 버튼→가상 목적지/아기의자→추천→GET | 후보3곳·주차 근거/아기의자 UNKNOWN·상세 펼침 | PASS, 실제 앱/HTTP. 입력 변경·제한 복구·취소 뒤 각각 명시 재검색 포함 POST4/GET4/close4 |
| TWO: 공급자1곳 주소UNKNOWN | 서버 필터 뒤 RESULTS2, 3곳 고정 가정 없음 | PASS, 실제 앱/HTTP. 명시 재검색 포함 POST4/GET4/close4 |
| EMPTY: 가상 공급자0건 | 0건 안내·카드 없음 | PASS, POST1/GET1 |
| FAILED: 가상 공급자 예외 | 실패 안내, 빈 결과와 구분 | PASS, POST1/GET1 |
| LATE: 공급자5초 지연 중 인원 변경 | 이전 화면 즉시 폐기, 6.5초 뒤에도 카드 없음 | PASS, POST1/GET1/close1. 서버 작업 취소/화면 세대 폐기 함께 적용 |
| EXPIRED: 근거유효기한3초 | 결과 뒤 EXPIRED/카드 제거, 자동 재검색 없음 | PASS, POST1/GET1/close1. **3초 fixture이며 실제5분 대기 검사는 아님** |
| NO_MARKER: MOCK 표식404 | 목적지 추천 요청 자체 차단 | PASS, 실패 안내·추천 POST/GET0 |
| THREE/TWO의 개발 제한·조회 실패 주입 | 결과 폐기·입력/검색 비활성, 재연결 뒤 명시 검색만 | PASS. 초기 CarUxRestrictions 플랫폼 허용값/재연결은 실제 수신, 주행/장애는 개발 주입이며 실제 moving 검증 아님 |
| THREE/TWO 종료 후 새 Activity 실행 | 후보 복원·자동 검색 없음 | PASS, 새 화면 IDLE/카드0. 이번에는 Activity 종료·재실행이며 프로세스 kill 별도 검사는 아님 |
| 모든 fixture 전후 journal 대조 | 예약 journal 변경 없음 | PASS, 테스트 설치 journal 문자열 동일 |
| 원래 설치 journal 전후 대조 | 기존 예약/UNKNOWN/인증 연결 기록 보존 | PASS, 전체 XML SHA256 `beb02ab84b57d492514117bb1e0eb7d069c3f4036e0fd1a22e5f29967b1f13b3` 일치 |

실패/수정 이력: 최초 Android Lint에서 minSdk28에 없는 `InputStream.readNBytes`를 발견해 제한 길이 스트림 읽기로 수정했다. 첫 기기 실행은 시스템 UI ANR/검은 화면으로 완료하지 못해 저장 데이터 삭제 없이 reboot했다. 이후 기기 PASS 출력에도 로컬 수집기가 raw `INSTRUMENTATION_CODE` 문자열만 찾던 오판을 수정했다. 최종 위7개 fixture는 같은 최종 APK로 다시 실행했다. 최초 길어진 후보 상세는 접기/펼치기로 바꾼 뒤 다시 캡처했다.

기기 검사가 끝난 뒤 검증 Java 서버를 정지하고 기존 `8080→18082`로 복원,8081 reverse 제거, 기존 jointvalidation 앱을 다시 열었다. 새 테스트 설치는 보존했다. 기존 UNKNOWN 기록을 종료/수락으로 바꾸지 않았다.

## 자동 검사

- `./gradlew -PincludeAndroid=false format`: PASS.
- `./gradlew -PincludeAndroid=true verifyAll`: PASS. UX106개 결과(신규 DestinationController12개), 실패/오류/skip0. 기존 서버313개 결과는 UP-TO-DATE이며 재실행313건으로 세지 않는다. 빌드 캐시/UP-TO-DATE 포함.
- `python3 scripts/smoke-test.py`: PASS, MOCK HTTP/Flyway/영속화 재시작·LIVE 거절. #53 실검색 시험이 아니다.
- Android 제품/테스트 APK 생성·설치·위7개 실제 instrumentation: PASS. Android Lint 오류0, 기존 PrivateApi/GradleDependency2/ObsoleteSdkInt 경고4 유지, 새 억제 없음.
- JVM 회귀:2/3가변 후보, 복사 격리, 중복 클릭, 늦은 응답+close 실패, 응답 유실 무재시도, monotonic/가장 이른 근거 만료, 제한, 잘못된 ID/권한, EMPTY/FILTERED/FAILED/승인 상태, 재생성, 입력 경계·계약 필드, GET404. 응답 유실/404/close 실패는 이번 기기 직접 주입으로 주장하지 않는다.
- 원격 필수 CI는 PR #46 최신 head의 실제 결과를 확인한다. 과거 CI를 이번 head의 성공으로 세지 않는다.

## 재현 절차 (유료 호출 없음)

Java21/Android SDK 환경에서 아래처럼 #53의 **고정 커밋을 별도 work 폴더에 추출**한다. 기존 서버 checkout/volume에 fixture를 덮어쓰지 않는다.

```sh
mkdir -p work/destination53/server
git archive cc3933999c11d14336f841ec04354b6712144fa9 | tar -x -C work/destination53/server
cp scripts/fixtures/DestinationMockApplication.java work/destination53/server/server/src/test/java/ai/pleos/assistant/
./gradlew -p work/destination53/server -PincludeAndroid=false :server:smokeClasspath
cd work/destination53/server
java -cp "$(cat server/build/smoke-classpath.txt)" ai.pleos.assistant.DestinationMockApplication THREE 18085
```

THREE/TWO/EMPTY/FAILED/LATE/EXPIRED/NO_MARKER를 각각 서버 재시작으로 선택한다. 동일 독립 `work/destination-auth` DB는 보존한다. 표식은 `http://127.0.0.1:18085/mock-capabilities`에서 확인한다. 이 test class는 제품 JAR에 포함하지 않는다.

별도 init.gradle에서 `:android:app`의 applicationId만 `ai.pleos.assistant.mock.destinationvalidation`로 지정하고 `assembleDebug assembleDebugAndroidTest` 빌드/설치한다. 원래 패키지에 새 인증을 만들지 않는다. 테스트할 때만 `adb reverse tcp:8080 tcp:18085`와8081→18085를 설정한다. 앱에서 **새 테스트 설치의 명시 인증 생성** 후 ‘목적지 주변 추천 · MOCK’을 선택한다. 기기 자동 검사는 다음과 같다.

```sh
adb shell am instrument --user 10 -w -e destinationFixture THREE ai.pleos.assistant.mock.destinationvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks
```

다른 fixture명도 서버와 일치시킨다. runner는 이 별도 패키지만 허용한다. 종료 후 원래 연결을 복원한다. 서버를 다른 버전으로 바꾸면 고정 APK/계약 인수 결과를 재사용하지 않는다.

## 미완료·협업 범위

- #52: 이번 결과는 **초안 기반 로컬 MOCK 앱 검증**이며 서버 담당과 공동 인수 완료가 아니다. @jjin70에게 배열/권한/생명주기 매핑과 결과 제출, #53 병합/계약 갱신 후 재대조가 필요하다.
- #1/@csjws: 제품의 현재 경로와 목적지 응답 연관성·변경/취소·늦은 응답 인수. ETA 의미/갱신 검증은 예약·지연 통보에 별도 필요. 이번 목적지 MOCK 추천에 ETA를 필수화하지 않았다.
- #52: Gleo 스키마/STT·TTS/제품 목적지 SDK 전달·완료 음성은 미연결. 가상 입력의 UI 접수·폴링을 Gleo 완료로 세지 않는다.
- #43/@tiemo0708 및 서버: VERIFY_FEATURES/BOOK/DELAY_NOTICE 작업 계약·멱등/조회/UNKNOWN/권한·비용 경계 후 연결. 현재 추천 결과에 전화번호·실행 권한이 없으며 phone-tasks를 만들어 호출하지 않는다.
- #25/#39: 새 화면의 OEM 전체 창/분할·야간·확대 글꼴·실제 moving/단절 신호는 별도 인수. 이번 주간1682dp 실행을 기존 다른 화면의5조합 결과로 확대하지 않는다.
- #4→#26 일반 UNKNOWN 종료 계약, #40 공동 문의→승인→예약 시연은 기존 미완료. 기존 요청·기록은 그대로다. #5/#25/#26/#39/#52를 전체 완료/종료로 표시하지 않는다. PR #46/#53 Draft·병합/자동 병합 없음.
