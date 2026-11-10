# #16 Docker MOCK 서버와 Pleos 앱 인수 검증

2026-11-10 KST. [서버 담당 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6756116918)에 따라 화면 담당이 자기 Mac에서 실행했다. **앱 측 실행 증거 제출이며, 서버 담당의 결과 검토/공동 인수 완료는 대기**다. #16/#26/#25/#39 OPEN과 PR #19/#46 Draft를 유지한다. 실제 장소·GPT·웹 수집·전화·유료 호출은 하지 않았다.

## 고정 버전과 격리

| 항목 | 실제 실행 기준 |
| --- | --- |
| 앱 소스 | cf35ead38408ab19d6cf2657ed597237d368d7af, #36/#44/#39 구현 보존 |
| 제품 APK SHA256 | bdd4f9312aa583275a28a01a6f21df8332eed95ff4b137b3a1901ef7f3183cc4 |
| 패키지 | ai.pleos.assistant.mock.jointvalidation (별도 설치) |
| 서버 소스 | d415ef74c0400cc49a353eea3132eedf899c59c4, 별도 detached worktree |
| 이미지 ID | sha256:c83f423444e53b415b0551e5d7229a505fb684624245e94ab10b987752df80dc |
| 실행 환경 | macOS arm64, Docker Engine29.6.2 / Compose5.3.1, 컨테이너 linux/arm64 |
| 계약 | 인증1.1.0 / 미리보기0.2.0-draft |
| 기기 | emulator-5554, Pleos Connect2.1.0/API34, user10, 2560×1440/density160, 앱 창1682×1210dp |
| 서버/인증 저장 | Compose project ux16-device, named volume ux16-device_preview-auth, host127.0.0.1:18082 |

현재 앱 소스로 새로 빌드해 hash를 직접 계산했다. 이전 d9ce757d APK를 후속 소스 증거로 재사용하지 않았다. Gradle init script는 applicationId만 jointvalidation으로 바꿨다. 기본/uxvalidation/previewvalidation 설치·기존 DB/Keystore는 지우거나 복사하지 않았다. 새 Docker DB에 대응하는 별도 시험 설치의 인증을 사용자가 누르는 버튼으로 처음 한 번 발급했다. 한도/실패를 우회하기 위한 인증 교체는 없다. 이후 네 fixture 전환·갱신·재실행은 같은 설치/volume이다.

## 재현 절차

1. 현재 작업 브랜치를 보존하고 `git worktree add --detach work/rec05-device-server d415ef74c0400cc49a353eea3132eedf899c59c4`로 서버를 분리한다. 기존 경로가 있으면 덮어쓰지 않는다.
2. 서버 checkout에서 `docker compose -p ux16-device up --build --detach --wait`. 실제 healthy와 `/api/v1/status`의 MOCK/1.1.0/externalCallsEnabled=false 확인. loopback18082 publish와 위 image ID/platform 기록.
3. 원래 reverse8080→18080을 기록한 뒤 `adb -s emulator-5554 reverse tcp:8080 tcp:18082`. 위 별도 APK 설치·실행, 실제 CarUx 초기 false/0 확인 후 명시 인증·미리보기 열기.
4. 각 fixture마다 `PREVIEW_FIXTURE=EMPTY docker compose -p ux16-device up --detach --wait`처럼 RESULTS/EMPTY/FILTERED/FAILED로 전환하고 명시 검색한다. `down --volumes`는 사용하지 않는다.
5. 상태 검사는 앱 기본 입력 성인2/아동1·대기15분·목적지A·반경1000m·주차/아동 필수·취향 한식으로 수행했다. 서버 인수표의 한식+양식 두 취향 조합과 차이가 있다. 두 취향/인원 상한20/10·거절21/11의 이번 기기 검사는 수행하지 않았으며 기존 서버/core 증거와 구분한다.
6. 네 상태 직접 검증 이후에만 host18083 loopback 프록시→Docker18082를 사용했다. 기본은 무변조 전달, fault 모드에서만 지연/취소503/응답 유실/3초 만료를 주입했다. 토큰·ID·요청 본문을 로그에 저장하지 않고 operation/status/state/count만 기록했다.

## 실제 실행 결과

| 절차 | 기대 결과 | 실제 결과 / 캡처 |
| --- | --- | --- |
| 명시 인증·기본 입력 | 실제 서버 토큰/Android Keystore, 계약 인원 범위 표시 | 연결 성공, 미리보기1–20/0–10과 기존 예약1–8/0–6 구분 / 01-conditions |
| 서버 RESULTS 직접 반환 | 최대3개, 문의/예약 실행 금지 | 후보1–3·추가 확인 필요·예약 요청 불가 / 02-results |
| 상세 펼치기·스크롤 | 근거·누락·UNKNOWN·출처/기한, NOW/ARRIVAL 분리 | 주소/전화 미확인·UNKNOWN 질문·현재 대기를 도착 대기로 해석하지 않는 안내 / 03-details |
| 서버 EMPTY 직접 반환 | 공급자0건 안내, 이전 후보 없음 | EMPTY/0 표시 / 04-empty |
| 서버 FILTERED 직접 반환 | 범위 밖을 EMPTY와 구분 | FILTERED/0 표시 / 05-filtered |
| 서버 FAILED 직접 반환 | 실패 안내, 이전 후보 없음 | FAILED 표시, 자동 재검색 없음 / 06-failed |
| 같은 사용자 인증 갱신 | 새 인증 주체 발급 없이 갱신 후 검색 | refresh200·명시 검색 RESULTS3, 관측 구간 anonymous-sessions0 / 07-refresh-results |
| 주차 필수 해제 | 즉시 이전 결과 제거, 자동 검색 없음 | EDITING·query 수 변화0, 명시 검색 후 RESULTS / 08-edited,09-edited-results |
| 목적지A→B | 기존 결과 선무효화 | EDITING·이전 후보 없음 / 10-destination-edited. 이후A로 복귀 |
| HOME·일반 설정 앱 열기 | 실제 Activity 상태에 따라 판정 | Pleos 분할창에서 앱 RESUMED/visible 유지. onStop 검증으로 세지 않음 |
| 전체 창 설정 앱 전환→복귀 | 실제 STOPPED에서 결과 제거, 명시 검색 대기 | dumpsys STOPPED, 복귀 CLOSED/후보 없음, 새 query 없음 / 11-before-background,12-resumed |
| 8초 늦은 응답+취소/종료503 | 실패 안내와 이전/늦은 후보 숨김 | 서버 RESULTS3을 받아도 취소 뒤 표시하지 않음 / 13-late-cancel-failure (프록시 주입) |
| query 응답 유실 | 정상0건 아닌 실패, 자동 재검색 없음 | FAILED·후보 없음 / 14-response-loss (프록시 주입) |
| 표시 유효기한3초 | 기한 이후 결과 제거 | EXPIRED·후보 없음 / 15-expired (프록시 주입, 실제5분 만료 검사는 아님) |
| force-stop→재실행 | 저장 인증 복구, 완료 미리보기 미복원 | 재검색 전 후보 없음, 명시 검색 후 RESULTS3 / 16-restarted,17-restart-search |
| 연속2회 클릭+8초 응답 지연 | 진행 중 중복 검색 차단 | LOADING·query 증가1회, 이후 RESULTS3 / 18-duplicate-loading,19-duplicate-result |
| 개발 추가 제한·조회 실패 | 이전 결과 제거·검색 불가 | search enabled=false, 주입임을 명시 / 20-restricted,21-unknown |
| 차량 플랫폼 재조회 | 실제 초기값 재조회 후 입력 복귀, 자동 검색 없음 | 입력 enabled=true·후보 없음 / 22-platform-recovered |

네 상태는 서버 fixture 직접 반환이며 프록시가 만든 EMPTY/FILTERED/FAILED가 아니다. FAILED는 가상 공급자 UPSTREAM_UNAVAILABLE이고 실제 공급자 장애가 아니다. 출처 필드 집합/정렬의 기존 서버·core 검증과 Android 화면/HTTP/Keystore 실행을 구분한다. 예약 GET 복구는 이번 별도 미리보기 시험에서 새 예약을 만들지 않아 재검증하지 않았다. force-stop 후 완료 미리보기는 GET으로 복원하지 않는다.

## 관측한 경계와 검사 수정

- 첫 fixture 전환 전에 열린 세션은 컨테이너 재시작으로 사라졌다. 다음 검색에서 이전 cancel/close 확인 실패 안내가 표시됐지만 이전 후보를 다시 표시하지 않았다. 이 안내는 과거 정리 실패를 보존하므로 뒤의 정상/빈 결과 화면에도 남을 수 있다. 서버 재시작이 메모리 preview를 복구하지 않는 계약과 연결한다.
- 취소503 시험으로 정리되지 않은 세션이 남은 뒤 새 preview 생성429를 실제 관측했다. 주체별 한도2와 비동기 정리/생성 시점에 해당하며, 기록상 이전 close200 뒤에도 경쟁 중 생성은429를 받았다. 한도 안내를 숨기거나 새 인증/서버 재시작으로 우회하지 않았다. 같은 인증으로 명시 재실행/검색은 이후 성공했다. 만료 검사는 최초429 시도를 통과로 세지 않고 기존 검색을 명시 취소·정리한 뒤 다시 실행했다. 운영 정리/한도 경계는 기존 #17 범위다.
- HOME 뒤 후보가 남아 초기 검사 assertion이 실패했다. dumpsys에서 실제 RESUMED임을 확인한 뒤, 전체 창 전환으로 STOPPED를 발생시켜 다시 검사했다. HOME을 종료로 가정하는 잘못된 기대를 수정한 것이며 제품 수명 코드를 변경하지 않았다.
- 임시 UI 도구의 상세 버튼/조회 실패 기대 문구가 현행 UI와 달랐던 항목은 실제 문자열로 대조 후 재실행했다. 실패한 도구 시도는 성공 증거로 세지 않는다.
- 실제 moving/권한 거부/서비스 단절·늦은 CarUx callback은 미검증이다. 개발 제한·조회 실패를 차량 신호 검증으로 보고하지 않는다.

## 검사·증거·종료

- 현재 앱 소스에서 `:android:app:assembleDebug` (격리 applicationId) 성공, 위 hash의 APK 실제 설치/실행.
- `./gradlew -PincludeAndroid=true verifyAll` 성공:112 tasks,6 executed/8 cache/98 up-to-date. 기존 JVM 테스트 캐시/UP-TO-DATE를 이번 신규 실행 건수로 세지 않는다. Lint 기존 경고와 NO-SOURCE도 이전 기록대로 구분한다.
- `python3 scripts/smoke-test.py` 성공: 패키징 인증·MOCK/UNKNOWN·H2 재시작·LIVE 차단. 이 smoke는 앱 공동 예약 시연이 아니다.
- #19 전체329개/서버 자동 Docker 검사를 이번에 다시 실행한 것은 아니다. 이번에는 고정 이미지 빌드/healthy와 실제 Android 연결로 확인했다.
- PNG/XML22쌍: 로컬 Git 제외 `captures/ux16-joint/`, 해시/버전은 `manifest.json`. 실제 존재하는 파일 목록이 기준이며 최종 PNG는 01–22, 12는12-resumed이다. 원본을 GitHub에 업로드한 것은 아니다.
- APK·빌드/검사 로그·비식별 proxy-events·시나리오 도구는 `work/ux16-joint/`. 서버 checkout `work/rec05-device-server/`. 실패 시도 로그도 보존했다.
- 검증 후 테스트 앱·프록시 종료, Compose down(인증 volume 유지), 설정 앱은 원래 multi-window 요청으로 복귀, reverse8080→18080 복원. 기존18080 서버 MOCK/외부 호출OFF 확인. Docker Desktop은 설치된 것을 실행했으며 시스템/기존 앱 데이터 삭제 없음.

## 다음 인수

1. #16 서버 담당이 동일 버전 결과·기본 입력 차이·429/정리 실패 안내를 검토하고 양측 인수표를 갱신한다. 화면 담당 단독 실행을 공동 완료로 자동 체크하지 않는다.
2. 두 취향 조합/인원 경계 기기 확인, 실제5분 만료, 실제 주행/장애 조합은 추가 인수 범위로 남긴다. #1 ETA·#11 이용 조건·#17 운영 수명은 해당 담당 범위를 유지한다.
3. #40 일반 앱/별도 운영자 문의→승인→예약 결과 시연은 별도다. 이번 Docker 이미지에는 운영자 주입 경로가 없어 #40 준비 완료를 뜻하지 않는다.
4. PR #38 웹 필드는 현행 계약에 추가하지 않았다. 공개 필드 합의 후 별도 구현한다. 제품/API 수정·병합·자동 병합 없음.

## 최신 main 통합과 CI 재개

문서 push c14cf47 이후 원격 PR은 새 main4d65c01과 충돌해 pull_request CI가 생성되지 않았다. 공유 이력을 보존한 merge로 STATUS/HANDOFF 두 충돌에서 양측 기록을 모두 유지했다. main의 품질 도구/가상 검사/CI와 서버 test 산출물 선언을 보존했으며 Android·UX core·루트 빌드 설정은 기기 검증 소스cf35ead 대비 diff가 없다. APK는 여전히 위 cf35ead 빌드 증거이며 merge 뒤 새 APK를 재설치했다고 주장하지 않는다.

통합 후 verifyAll 성공(112 tasks,7 executed/105 up-to-date), MOCK smoke 성공. Python 품질 검사는 최초 pyproj 미설치로 import 실패했고, 격리 `work/ux16-joint/quality-venv`에 requirements-quality를 설치한 뒤40개 가상 테스트와 MOCK pilot-screening 성공. 전역 Python 변경·실제 데이터 추가 조회는 없다. 로그는 integrated-verifyAll.log/integrated-smoke.log/integrated-quality-retry.log다. 원격 최신 head CI는 PR에서 별도로 보고하며 이전 cf35ead 성공을 최신 성공으로 표시하지 않는다.

통합 작업 중 main이 다시2e873bc로 갱신돼 #38 내부 웹 상세정보 모듈(9ea227d)과 전화 개발 계획 문서가 추가됐다. 두 번째 merge에서도 STATUS/HANDOFF 양측 이력을 보존했다. 최신 통합 후 verifyAll(112 tasks,13 executed/99 up-to-date)·MOCK smoke 성공. Android/UX·루트 빌드 설정은 여전히 cf35ead 대비 변경 없다. #38 모듈은 main에 들어왔지만 미리보기 공개 필드/제품 앱 채택 합의와 실제 GPT 호출은 별개이며 이번에 연결하지 않았다. 로그 latest-main-verifyAll.log/latest-main-smoke.log.

## 2026-11-10 두 취향·인원 경계·실제 만료 추가 검증

이전 미검증 중 두 취향/인원 경계와 실제5분 만료를 추가 검사했다. 제품 코드/API 변경 없이 `PreviewBoundaryChecks`와 runner의 `-e previewBoundary true` 모드를 추가했다. Android instrumentation이 실제 EditText에 가상 값을 설정하고 화면의 검색 버튼 listener를 실행한다. 실제 Android transport/Keystore/서버 응답/화면을 사용하며 수동 한글 키패드 입력 품질 검사와 구분한다.

### 기존 요청 보존과 사용자 승인

기존 jointvalidation 설치에 `/searches` pending이 있어 새 명령이 차단됐다. 초기 instrumentation은 사전조건 시간 초과로 종료했다. 같은 키/본문 재전송에서401→동일 주체 refresh200 후400 INVALID_TIME을 확인했다. 기존 기록/인증/요청을 삭제하거나 시간·키를 바꿔 재전송하지 않았다. 사용자에게 이 상태를 설명했고 **“별도 검증 설치로 진행하고 기존 요청은 보존”** 승인을 받은 뒤에만 boundaryvalidation을 별도 설치·명시 인증했다. 기존 요청이 복구됐다고 보고하지 않는다. 과거 시각으로 확정 거절된 검색의 사용자 복구 흐름은 #26 후속으로 남는다.

### 고정 조합과 방법

- 제품 소스5dc9cb1(기존 cf35ead 대비 제품 Android/UX 변경 없음), 별도 `ai.pleos.assistant.mock.boundaryvalidation`.
- 실제 APK SHA256 `d67bdfb43814f07eb11c9b3c64bbad584b3928f0a07ffd2db1fe152d4af4e3bf`.
- 서버d415ef7, image `sha256:c83f423444e53b415b0551e5d7229a505fb684624245e94ab10b987752df80dc`, linux/arm64, 기존 ux16-device 인증 volume 유지. RESULTS fixture.
- 같은 Pleos2.1.0/API34/user10/1682×1210dp 창. 전화/실예약/외부 장소/GPT/유료 호출0.
- host18083의 무변조 loopback 중계→Docker18082. HTTP 본문·시각·기한을 수정하지 않고 토큰/ID 없이 operation/status/가상 인원·취향/원본 fetchedAt·expiresAt만 관측했다.
- 성인/아동·취향 외에는 목적지A/반경1000m/최대대기15분/주차·아동 필수다. 두 취향은 `한식,양식`이며 응답 배열은 순서가 아닌 집합으로 비교한다.

| 입력/절차 | 기대 결과 | 실제 결과·증거 |
| --- | --- | --- |
| 성인2/아동1·한식+양식 | 두 취향 수신, 후보3 | 응답 요구조건 일치·후보3·bookable/inquiryDispatchAllowed=false / 01-two-preferences |
| 성인21/아동10 | 상한 초과를 앱에서 차단 | INVALID_INPUT·결과null·검색 HTTP0 / 02-adults21 |
| 성인20/아동11 | 아동 상한 초과 차단 | INVALID_INPUT·결과null·검색 HTTP0 / 03-children11 |
| 성인0/아동0 | 성인 하한 미달 차단 | INVALID_INPUT·결과null·검색 HTTP0 / 04-adults0 |
| 성인1/아동0·두 취향 | 정상 하한 수용 | 요구조건1/0·후보3 / 05-lower-boundary |
| 성인20/아동10·두 취향 | 정상 상한 수용 | 요구조건20/10·후보3 / 06-upper-boundary |
| 실제 서버 유효기간 | fetchedAt→expiresAt 정확히300초 | fetchedAt=2026-11-10T04:25:30.132010261Z, expiresAt=2026-11-10T04:30:30.132010261Z. UTC 원본이며 시각 변경 없음 |

만료 약15초 전(대기 시작 후284.196초)에 실제 RESULTS 화면을 확인·캡처했다. 만료 후 약2초 시점(대기 시작 후301.905초)의 실제 UI에서 EXPIRED 안내와 이전 결과 제거를 확인했다. `previewView()` 호출로 만료를 유발하기 전에 먼저 렌더된 UI 문구를 검사했다. 이후5초에도 EXPIRED를 유지했고 추가 query는0회였다. 서버 원본 시각/기한·시스템 시계·경과 시간을 조작하지 않았다. 테스트 중 화면 켜짐 flag만 임시 적용하고 finally에서 제거했다. 증거07-before-real-expiry/08-real-expired.

만료 후 cancel/close는404 PREVIEW_NOT_FOUND를 반환했고 앱은 정리 확인 실패 안내도 표시했다. 결과는 계속 숨겼으며 이를 서버 정리 성공이나 실제 공급자 장애로 보고하지 않는다. #16/#17의 수명·정리 안내 검토에 연결한다.

유효하지 않은 인원은 앱에서 전송 전에 거절하므로 이번 기기 시험에서 서버400 응답을 받았다는 뜻은 아니다. 서버의20/10 수용·21/11 거절은 이전 서버 담당 HTTP 증거와 구분한다. 정상 검색 HTTP는2/1·1/0·20/10 총3회, 각 취향2개·RESULTS3이다.

최초 동일 설치 검사 실패는 기존 pending 사전조건 문제다. 실행 중 별도 uiautomator dump는 instrumentation과 충돌해137로 종료돼 성공 증거에서 제외했다. 기기 검사 프로세스와 HTTP 관측은 유지됐고, 유효 캡처는 instrumentation에서 생성한 PNG만 사용한다.

### 검증 수준과 남은 범위

- format·테스트 APK 빌드·verifyAll·MOCK smoke 성공. 기존 JVM cache/UP-TO-DATE와 Android JVM NO-SOURCE를 새 실행 건수로 세지 않는다. 기기 instrumentation은 CI가 아닌 로컬 Pleos에서 실행한다.
- PNG/해시·원본 TTL·요청 집계는 로컬 `captures/ux16-boundary/`, 로그/무변조 관측 도구/APK는 `work/ux16-boundary/`. 이미지 GitHub 업로드와 구분한다.
- 이 결과도 앱 측 실행이며 서버 담당 검토/공동 인수 완료는 별도다. 실제 moving/권한 거부/서비스 단절, #40 전체 예약 시연, #1 실제 ETA/#11 데이터 인수와 기존 jointvalidation의 INVALID_TIME pending은 남는다.

재현 명령: `adb shell am instrument -w -e previewBoundary true ai.pleos.assistant.mock.boundaryvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`. 별도 RESULT fixture와 사전에 명시 인증된 설치가 필요하며 실행은 약5분 이상 걸린다. 정상/잘못된 입력의 실제 HTTP 수는 별도 무변조 관측 로그로 대조한다. 기기 테스트를 CI에서 실행했다고 주장하지 않는다.


## 2026-11-10 오래된 검색 요청·정리404 앱 안내 보완 (#26)

### 확인한 범위와 변경

#16 전체 댓글을 재확인했으며 서버 담당 최신 답변은 6756116918(Docker 전달, 2026-11-10 01:18 UTC)이다. 앞선 두 취향/실제5분 결과에 대한 새 답변은 없으며 #19 head는 d415ef7 그대로다. #17에는 별도404 합의 댓글이 없다. 다른 담당 서버·공개 API를 변경하지 않았다.

- Android HTTP 경계에서 오류 본문을 최대4096바이트로 제한하고 INVALID_TIME/STALE_NAVIGATION/PREVIEW_NOT_FOUND 코드만 전달한다. 서버 message/임의 코드/토큰을 표시하거나 저장하지 않는다. 없거나 깨진/큰 본문은 HTTP 상태만 유지한다.
- `/searches` 재전송의400 INVALID_TIME/STALE_NAVIGATION은 pending에 비식별 recoveryCode만 추가한다. 원 path/body/key/target/authBinding을 유지하며 재시작 후에도 오래된 요청 안내와 새 검색 차단을 유지한다. 동일 요청의 명시 재전송은 원본 그대로다. 연결 성공을 요청 해결로 표시하지 않는다.
- 미리보기 cancel/close의404 + PREVIEW_NOT_FOUND는 “서버에서 찾지 못해 정리 완료 미확인”으로 구분한다. 만료·재시작 원인을 추측하거나 성공으로 처리하지 않는다. 다른404/통신 실패는 기존 실패 안내다. 결과 선제 제거·자동 재검색 금지는 유지한다.

### 실제 실행과 증거

| 수준 | 절차 / 기대 결과 | 실제 결과 |
| --- | --- | --- |
| JVM 회귀 | 시간 거절2종 → 재시작 → 원 요청·인증 보존, 새 검색 차단, 명시 동일 재전송 | PASS; 새 회귀5건 포함 UX67개 실패/오류/skip0 |
| JVM 미리보기 | 만료 후 정확한404/코드와 일반404 구분, cancel/close 시도·후보 제거·query1 유지 | PASS. 테스트 시계 기반이며 실제5분 재실행 아님 |
| Pleos HTTP 주입 | 실제 LoopbackTransport로400/INVALID_TIME,404/PREVIEW_NOT_FOUND, 깨진404본문,4096바이트 초과503 | PASS. loopback18084 test fixture이며 서버#19 직접 인수 아님 |
| Pleos UI 주입 | 메모리 snapshot에 시간 거절을 주입해 안내 문자열·쓰기 차단·화면 캡처 | PASS. 실제 기존 요청의 복구 성공 아님 |
| 보존 | jointvalidation/boundaryvalidation journal 파일 검사 전후SHA256 비교 | 둘 다 byte-for-byte 일치. 원 요청 파일 내용 출력/수정 없음 |
| 로컬 빌드/실행 | format, verifyAll, smoke-test.py | PASS. 서버 UP-TO-DATE와 JVM cache는 새 실행 수로 세지 않음 |

기기: Pleos Connect2.1.0/API34/emulator-5554/user10, 별도 boundaryvalidation 기존 설치를 `install -r`로 업데이트했다. 검증 APK SHA256 `fec1442cd35400eaf0c3e5b46acb6a82698afccfb18acde144854cfd8cda4440`. 소스는 이번 복구 안내 수정본이며 이전5분 검증 APK와 다르다. `captures/ux26-recovery/recovery-time-rejected.png`와 manifest.json, `work/ux26-recovery/device.log`, build/verifyAll/smoke.log에 증거를 보관했다. 기기 검사 명령은 `am instrument -w -e recoveryFailure true ai.pleos.assistant.mock.boundaryvalidation.test/ai.pleos.assistant.app.DrivingDeviceChecks`이며 **전용 오류 fixture가 필요**하다. 캡처는 시간 거절 안내 화면1장이고404 화면 캡처/실제 서버 재검증은 수행하지 않았다.

종료 후 오류 fixture·별도 앱을 종료하고8080→18080 연결을 복원했다. 기존 jointvalidation 설치/인증/요청을 그대로 두었다. MOCK만 실행했고 외부 장소/전화/예약/유료 호출0회다.

### 서버 확인이 필요한 후속 / 완료하지 않은 것

- #16/#26: 검색 명령이 INVALID_TIME으로 거절됐을 때 동일 인증·키로 처리 여부를 확정하고 원 요청을 보존하면서 종료할 공식 절차/응답이 필요하다. 앱은 키·시각 변경, pending 삭제, 새 주체 발급으로 우회하지 않는다. 이번 변경은 진단/안내 개선이며 기존 요청 해결·새 입력 해제는 미완료다.
- #16/#17: 만료 후 PREVIEW_NOT_FOUND가 정리 완료와 같은 의미인지, 없어진/다른 소유자/재시작을 구분할 수 있는지 계약 확인이 필요하다. 합의 전 성공 처리하지 않는다.
- 이번 제품 변경으로 실제300초·실제 서버 공동 시나리오를 다시 수행하지 않았다. 앞선 기록은 당시 APK의 증거다. 실제 차량 moving/장애, #40 시연, #1/#11 및 공동 인수는 계속 미완료다. 이슈 OPEN/PR Draft 유지, 병합 없음.
