# UX-03 조건별 미리보기 — 초안 기반 구현과 기기 검증

2026-11-09, #26 / Draft PR #36, `feat/26-app-auth-session`.
**초안 기반 로컬 검증이며 서버 담당과의 공동 인수 완료가 아니다.**
#5·#26 OPEN, #19·#27·#36 Draft를 유지한다. 다른 담당의 서버/API·차량·데이터 완료 조건은 변경하지 않는다.

## 기준과 구현

- 재확인한 #19 head는 `285087b96e54b3471b126831dfbad382ebfc9d5f`이다. PR 댓글·리뷰 0건, #16의 기존 인수안/앱 정적 검토 이후 새 합의 답변은 없었다.
- [서버 인수안](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/285087b/docs/REC05_UI_HANDOFF.md), 요청/응답 예제, `api/place-preview-draft.json`을 기준으로 한다. **인증 1.1.0과 미리보기 0.2.0-draft는 별개**이며 미병합 #19를 main의 API로 간주하지 않는다.
- 서버 소스는 Git 제외 `work/ux26-preview/server-src/`에 해당 커밋을 추출해 JAR를 빌드했다. 이 작업 브랜치에 서버 초안/공개 API 변경을 합치지 않았다. test fixture에 출처를 기록했다.
- 예약 화면과 분리된 메모리 전용 비교 화면: 성인1–20, 어린이0–10, 도착 시 최대 대기0–120분, 주차/어린이 동반 필수 조건, 선호 음식 최대8개, 반경1–20000m. 가상 목적지2개만 제공하며 실제 위치·가족 정보는 사용하지 않는다.
- 최대3개 요약과 상세, 추천 근거·누락 정보·UNKNOWN 질문·MOCK 출처·관측/만료 시각을 표시한다. 직선거리를 경로 거리/ETA로 해석하지 않는다. ordinal은 표시 순서이며 예약/전화 ID로 변환하지 않는다. 문의/예약 실행 버튼도 없다.
- 입력/목적지 변경·취소·뒤로 가기·onStop·모의 주행 제한에서 화면 ticket을 먼저 무효화한다. 결과는 저장하지 않고 독립 작업으로 cancel→close를 시도한다. 실패해도 이전 응답을 표시하지 않는다. 생성 응답이 늦게 도착해도 종료를 시도한다.
- 검색 버튼의 **명시적 클릭마다 새 previewId와 증가한 generation**을 사용한다. 완료 요청의 재생/GET 복구/자동 재검색은 없다. LOADING 중 중복 검색은 차단한다. 조건 변경 후 직접 클릭하는 새 검색은 이전 요청과 별개다. 예약 화면의 같은 키 재전송 규칙을 미리보기에 적용하지 않는다.
- AuthSession의 동일 인증 주체를 공유한다. 미리보기 IO는 갱신 lock 밖에서 실행하여 지연된 검색이 취소를 막지 않는다. 동시401은 직렬 갱신 후 같은 본문으로 한 번만 재시도한다. 일반 네트워크 실패에는 재검색/새 주체 발급을 하지 않는다.
- 서버의 조건 배열 정규화(집합 정렬)를 순서 불일치로 오판하던 문제를 실제 연동에서 발견하고 수정했다. 필수 조건·취향은 중복 없는 집합으로 비교하며, 다른 조건/세대/ID·발신/예약 허용·UNKNOWN 위반 응답은 거절한다.
- 벽시계 만료와 elapsed realtime을 함께 확인하며 시계가 뒤로 크게 이동하면 결과를 숨긴다. 짧은 주기로 화면 만료를 확인하고 앱 복귀에는 재조회 없이 결과를 비운다.

## 기기와 재현 환경

- Pleos Connect v2.1.0 / API34 / emulator-5554 / Android 사용자10, 2560×1440, density160. host GPU/Vulkan 비활성화 환경을 그대로 사용했다.
- 기존 앱과 인증/예약 데이터를 보존하기 위해 applicationId만 `ai.pleos.assistant.mock.previewvalidation`으로 바꾼 검증 APK를 별도 설치했다. 인증은 새 설치 버튼에서 명시적으로 발급했고 이후 동일 설치의 토큰/DB를 보존했다. 제품 소스의 패키지나 서버 보안 정책을 완화하지 않았다.
- 격리 서버: localhost:18082, `--app.place-preview.enabled=true`, MOCK·외부 호출 false. 실제 PR19 서버의 정상 응답으로 비교 화면을 실행했다.
- 예외 주입: localhost:18083의 로컬 프록시에서 **정상 응답을 EMPTY/FILTERED/FAILED로 변환**, 표시 수명3초, 응답8초 지연, 응답 유실, cancel/close503을 주입했다. 인증 헤더/토큰/본문은 로그에 남기지 않았다. 이 결과는 공급자 장애나 실제 네트워크 환경의 재현으로 주장하지 않는다.
- 설치/force-stop 시험에서 서버의 임시 세션이 남아 HTTP429를 실제 관찰했다. 앱은 한도를 안내하고 자동 재검색하지 않았다. 인증 DB를 유지한 채 **격리된 시험 서버만** 재시작해 남은 일시 세션을 정리했다. 비정상 종료 후 서버 TTL/운영 정리는 #17의 범위를 대체하지 않는다.
- Android UIAutomator의 실제 hierarchy와 screencap으로 확인했다. 초기 스크립트의 스크롤 위치 가정 실패는 제품 통과로 세지 않고 화면 위치를 확인해 재실행했다. 결과 도착 후 요약으로 이동하는 UI 보완도 수행했다. 목적지B에서 후보가 나올 것이라는 시험 스크립트 가정은 실제 FILTERED 결과와 달라 수정했고, 화면과 query1회 기록을 대조했다.

## 수행 결과

기기 결과는 아래 최종 실행 표와 원본 PNG/XML을 기준으로 한다. 이전 인증 단계 기록은 [UX26_AUTH_HANDOFF](UX26_AUTH_HANDOFF.md)에 별도로 보존한다.

| 절차 | 기대 결과 | 실제 결과·증거 |
| --- | --- | --- |
| 모의 정차 → 인증 → 미리보기 입력 | 명시 조건 입력, 초안/MOCK 표시 | `05-final-conditions`; 입력 24sp, 최소72dp 보완 |
| 정상 PR19 MOCK 검색 | 후보3개, 근거·누락·UNKNOWN, 예약/발신 불가 | `06-final-results`, `07-final-questions`; 요약·상세·스크롤 확인 |
| 공급자0건/범위 밖/실패 응답 주입 | EMPTY/FILTERED/FAILED 구분, 이전 후보 없음 | `08-empty`, `08-filtered`, `08-failed` 각각 확인 |
| 표시 수명3초 응답 주입 | 만료 후 후보 숨김, 자동 재검색 없음 | `09-expired` 확인 |
| 모의 주행 제한/UNKNOWN | 조건 입력·검색 차단, 이전 결과 숨김 | `10-restricted-controls`~`12-unknown-controls`; 비활성 hierarchy와 안내 확인 |
| 뒤로 가기 → 다시 열기 | 결과 없는 화면, 자동 조회 없음 | `13-back`, `14-reopen` 확인 |
| 검색 응답8초 지연 + cancel/close503 → 취소 | 결과 선무효화, 취소 실패 안내, 늦은 후보 무시 | `15-late-cancel-failure` 확인 |
| 응답 유실 → force-stop → 재실행 | 자동 재검색 없음, 이전 미리보기 미복원, UNKNOWN 시작 | `16-response-loss`, `17-restart`; 프록시 query1회 |
| 같은 설치 인증 갱신 → 명시 검색 | 새 사용자 발급 없이 정상 후보 표시 | `18-refresh-search` 확인. 별도 `18-session-limit`은 정리 실패·force-stop 뒤 실제429 안내 증거 |
| 대기15→20 입력 | 이전 결과 즉시 제거, 명시 검색 때만 새 조건 전송 | `19-edited`, `20-wait20`; 자동 query0회, 응답 질문의 최대20분 확인 |
| 목적지 A→B 변경 | 기존 결과를 지우고 명시 검색 대기 | `21-destination-change` 확인 |
| 응답8초 지연 중 연속2회 클릭 | query1회, 진행 중 버튼 비활성 | `22-loading`, `23-duplicate-result`; 프록시 delay query1회. B/1000m는 실제 PR19 MOCK의 FILTERED 응답이며 빈 후보로 표시 |

## 검사 수준과 증거

- `./gradlew -PincludeAndroid=false verify`, `./gradlew -PincludeAndroid=true format verifyAll` 성공. UX **43개** 실행·실패/오류/skip0: 기존 인증/업무26 + 미리보기 상태14 + 인증 동시성3. 서버268개 기존 결과는 UP-TO-DATE이며 이번에 새로 268개 실행했다고 세지 않는다.
- 최종 UI(KST 표시·선택 항목24sp/72dp) 보완 뒤 `./gradlew -PincludeAndroid=true format verifyAndroid`도 성공. debug APK 생성과 실제 설치/실행을 구분한다. Activity 테스트 NO-SOURCE를 통과 건수로 세지 않는다.
- `python3 scripts/smoke-test.py` 성공: 인증·동일 요청/소유권·권한·H2 재시작·기존 예약 흐름·LIVE 시작 차단. 운영자 테스트 하네스의 예약/수락 통과를 일반 앱의 운영자 권한으로 해석하지 않는다.
- 로컬 Lint: 앱 error0/warning2(고정 AndroidX GradleDependency), probe0/0. 새 억제/baseline 없음. 원격의 SDK/의존성 경고 차이는 PR CI 결과에서 따로 기록한다.
- 최초 테스트 작성 중 fixture의 요청/응답 객체 공유로 불일치 검사가 실패한 부분을 deep copy로 수정했다. 테스트의 Java17 ExecutorService 종료 호환성 컴파일 오류도 수정했다. 위 성공은 수정 후 결과다.
- 원본 PNG/XML: Git 제외 `captures/ux26-preview/`, SHA-256 목록 `manifest.json`. 로그·통제된 주입/기기 조작 도구: Git 제외 `work/ux26-preview/`. 토큰/헤더를 캡처나 이 문서에 저장하지 않았다. GitHub에 이미지가 업로드됐다고 주장하지 않는다.
- 최종 검증 APK: `work/ux26-preview/validated-preview.apk`, SHA-256 `6add17aea61be90559e97fa5b218137601ff6558c46db339faf4038ec681322e`. applicationId만 분리했고 원래 앱 데이터는 보존했다.
- 원격 CI는 이 문서의 존재나 이전 성공으로 통과 판정하지 않는다. **최종 push의 실제 세 체크 결과/링크는 PR #36에 기록**한다.

## 남은 공동 확인·개발

- #16/#26: 초안 필드·상태·오류 합의와 서버 담당 동행 기기 인수. 현재의 후보→예약/전화 변환 API 부재, 새 검색의 명시적 세션 생성, 정상0건/필터/실패·취소·늦은 응답 처리 경계를 공동 확인해야 한다.
- 서버의 고정 MOCK은 공급자0건/장애를 직접 생성하지 않으므로 EMPTY/FAILED 기기 사례는 프록시 주입이다. FILTERED는 주입과 가상 목적지B/1000m의 실제 MOCK 응답을 모두 확인했다. 실제 공급자 장애, 장시간 실제 네트워크 단절, 실제5분 경과 만료 기기 시험은 미검증이다. 5분 경계는 JVM 시계 검사, 기기는 단축 수명 주입으로 구분했다.
- #25: 실제 CarUxRestrictions callback/권한·차량 상태, 회전/다른 화면 크기·release·영상·운전자 사용성 인수. 이번 UI는 개발용 **모의 정책**이며 실제 주행 사용 승인 근거가 아니다. 기본 내비게이션 지도 표시 문제는 이 예약 앱의 미리보기 범위가 아니다.
- #26/#4: 유료 변경 별도 승인, 안전한 서버 대조·확정 거절 종료, 손상/유실된 인증·복구 기록의 완전 복구. 0원 제한과 UNKNOWN 보존을 유지한다.
- #1 ETA 의미와 #11 데이터 조건, #17 임시 세션의 운영 정리는 기존 담당 범위다. #19 서버 브랜치·체크리스트는 변경하지 않았다.
- 외부 장소·전화·AI·유료 호출 **0회**. 병합/자동 병합 없음.

재현 시 기존 DB/앱 데이터를 지우지 말고 PR19의 격리 JAR와 별도 설치를 사용한다. 실제 MOCK 서버를 직접 확인하려면 서버18082를 실행한 상태에서 `adb reverse tcp:8080 tcp:18082`로 연결한다. 오류 주입 결과를 재현하려면18083 프록시가 별도로 필요하며 제품 서버 기능으로 간주하지 않는다.
