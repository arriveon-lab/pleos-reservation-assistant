# REC-05 · 화면 공동 인수 기준과 MOCK fixture

## 2026-11-10 최신 서버 검토와 복구 계약 (이전 절차보다 우선)

[앱26e80cf의 최신 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6759209219)을 검토했습니다. 보고된 두 취향·인원 경계·실제300초 만료/후보 제거·자동검색0은 **화면 담당의 기기 실행 증거**로 접수합니다. 이번 서버 담당이 원 PNG/실기기를 새로 검사했다는 뜻은 아닙니다. 기존 INVALID_TIME pending 보존,404/PREVIEW_NOT_FOUND의 정리 미확인 안내는 타당합니다.

새 서버의 [원 요청 대조·영속 종료와 화면 연결 기준](REC05_SEARCH_RECOVERY.md)을 추가했습니다. 최신 앱26e80cf의 세 core 파일을 수정 없이 직접 컴파일해4fixture HTTP52+schema48을 재검증했고, 패키징 JAR와 실제 Docker에서 대조 응답/종료 기록의 H2 재시작 보존을 확인했습니다. 새 복구 UI는 아직 연결되지 않았습니다. 아래 cf35ead/d415ef7/07b0a43/APK hash는 당시 증거이며 새 조합으로 대입하지 않습니다.

인수용 최신 서버는 이번 PR #19의 검증된 최종 commit을 사용합니다. main2e873bc를 반영해 충돌을 해결했고 이후 변경은 PR의 최종 head/CI로 확인합니다. 같은 인증 volume·원 journal을 유지하고 새 서버 commit/image와 최종 앱 APK SHA를 다시 고정해야 합니다. 기기 기존 요청 복구·journal 저장 실패/응답 유실·재시작의 화면 연결을 확인하기 전에는 공동 인수/병합 완료로 체크하지 않습니다. #1 ETA·#11 데이터·#17 운영 수명·#40 전체 예약 시연은 별도 범위입니다.


2026-11-10 KST. 서버 #16/PR #19, 화면 #26 및 후속 #39/PR #46. [화면 담당의 공동 시험 제안](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6741208185)에 대한 서버 측 확인입니다. **계약/서버 재현 준비와 공동 기기 인수는 구분합니다.** 이 문서가 양측 실행·기기 검증 완료를 뜻하지 않습니다.

## 실제 진행 방식: 각자 PC의 로컬 서버와 비동기 검토

권장 전달 방식은 [Docker Compose 실행](REC05_DOCKER.md)입니다. Docker 설정을 포함한 전달 commit에서 각자 빌드/실행하고, 기기8080→자기 PC18082→컨테이너18082로 연결합니다. 이전07b0a43에는 Docker 구성이 없으므로 Docker 실행에 그대로 사용하지 않습니다. 아래는 기존 Gradle 직접 실행의 재현 기준입니다.

현재 서버는 각 PC의127.0.0.1에만 열립니다. 공유 개발 서버·터널·원격 접속은 구성하지 않았습니다. 추천 담당 Mac을 계속 켜 두거나 두 사람이 동시에 접속할 필요는 없습니다. 화면 담당이 자기 PC에서 같은 서버 commit을 실행하고, 그 PC에 연결한 에뮬레이터/APK로 확인한 뒤 서버 담당이 증거를 검토하는 방식으로 진행합니다. 앱을 시험하는 동안 해당 PC의 서버는 실행 중이어야 합니다.

1. **버전 고정:** 서버 실행 기준은 `07b0a43ab818fe21eaa93b6a634136605128355d`, 앱 소스 검토 기준은cf35ead입니다. 화면 담당이 최종 검증 APK 한 개와 SHA256을 기록합니다. 같은 소스를 서로 다른 debug 키로 빌드하면 APK hash가 다를 수 있으므로 소스 commit만으로 동일 APK라고 기록하지 않습니다.
2. **화면 담당 PC에서 서버 실행:** 현재 앱 작업 폴더를 전환하지 않고 별도 checkout을 사용합니다. 먼저 저장소 AGENTS/기여 지침과 본인 작업 상태를 확인합니다. 아래 경로가 이미 있으면 다른 경로를 선택하며 기존 작업을 지우지 않습니다.

   ```bash
   git fetch origin
   git worktree add --detach work/rec05-server 07b0a43ab818fe21eaa93b6a634136605128355d
   cd work/rec05-server
   ./gradlew -PincludeAndroid=false verify
   ./gradlew -PincludeAndroid=false :server:previewAcceptance -PpreviewFixture=RESULTS
   ```

3. **자기 PC와 자기 기기 연결:** 앱은 기기의127.0.0.1:8080을 사용합니다. 별도 터미널에서 `adb devices -l`로 대상 시리얼을 고르고, 기존 reverse를 확인·기록한 뒤 아래처럼 연결합니다. SERIAL은 실제 대상 시리얼로 바꿉니다.

   ```bash
   adb -s SERIAL reverse --list
   adb -s SERIAL reverse tcp:8080 tcp:18082
   ```

   이는 기기8080 → **그 기기에 adb로 연결한 PC의**18082입니다. 다른 사람 Mac의 서버로 연결하는 명령이 아닙니다. 시험 종료 후 기존8080 매핑이 있었다면 그 목적 포트로 복구하고, 없었다면 `adb -s SERIAL reverse --remove tcp:8080`으로 이번 매핑만 제거합니다. 다른 매핑을 한꺼번에 삭제하지 않습니다. 이 문서 갱신 과정에서는 실제 기기/연결을 변경하지 않았습니다.
4. **화면 담당 확인:** RESULTS → EMPTY → FILTERED → FAILED를 아래 명령의 fixture만 바꿔 실행하고 표시·목적지/조건 변경·화면 이탈·취소/만료·인증/입력 제한 결과를 기록합니다. 같은 개발 인증 DB를 유지하고 기존 앱/Keystore를 자동 삭제하지 않습니다. 최종 APK 변경 시 변경된 조합으로 다시 확인합니다.
5. **결과 인수:** #16에 서버 commit·APK SHA·기기/이미지·fixture·기대/실제·증거·미검증을 남기고 양측이 검토합니다. 각 PC의 토큰·세션 ID·조회 시각은 서로 달라도 정상입니다. 계정/토큰/DB를 공유하는 대신 계약 상태·후보/질문·수명 판정을 비교합니다. 별도 실행 기록만으로 공동 인수 완료를 자동 체크하지 않습니다.

추천 담당의 서버/core 검증은 이미 제공돼 있습니다. 현재 필요한 다음 증거는 화면 담당의 위 기기 실행과 양측 결과 검토입니다. 그동안 추천 담당은 #17 등 별도 이슈를 진행할 수 있습니다. 원격 개발 서버가 실제로 필요한 시점에는 인증/접근/배포 조건을 따로 설계하며 이번 절차에서는 공개 바인딩이나 터널을 사용하지 않습니다.

## 서버 측 필드 확인

서버 검토 기준5526f92와 앱 소스 검토 기준cf35ead(PR #46)을 대조했습니다. 인증1.1.0 / 미리보기0.2.0-draft를 사용하며 추가 필드 변경은 필요하지 않습니다. 앱 담당의 제안 당시216ca25 APK hash는 이후 소스의 기기 증거로 대신할 수 없습니다. 공동 실행 때 최종 서버 commit·APK SHA256·기기/이미지·실행 시각을 다시 고정합니다.

| 항목 | 서버 측 확인 |
| --- | --- |
| 미리보기 인원 | adults1–20 / children0–10, 경계 포함. 초과는400 |
| 기존 예약 연습 인원 | 앱의 별도1–8 /0–6 정책. 미리보기 계약을 축소하지 않음 |
| 조건 배열 | requiredFeatures/preferredCuisines는 중복 없는 집합. 배열 순서 변경만으로 REQUEST_CONFLICT가 되지 않음 |
| 후보 | 최대3개, places/추천 순서·거리 일치. ordinal은 해당 결과의 순서이며 예약/통화 ID 아님 |
| 근거/질문 | 추천 근거·누락 정보·UNKNOWN. bookable/inquiryDispatchAllowed=false, 문의/예약 실행 없음 |
| 완료 세대 | 동일 조건을 다시 보내도 ALREADY_FINISHED, 변경 조건은 REQUEST_CONFLICT. 자동 재검색/결과 재전달 없음 |
| 인증/복구 | refresh 후 같은 주체의 preview 소유권 유지. 완료 preview의 저장·GET 재표시 없음. 기존 예약 GET 복구는 별도 경로 |
| 화면 이탈 | 목적지/조건 변경·뒤로 가기/onStop에서 결과/identity를 먼저 비우고 cancel/close. 정리 실패가 늦은 결과 표시를 허용하지 않음 |

## 재현 가능한 네 상태

`PreviewAcceptanceApplication`은 **src/test 전용** 실행체입니다. 실제 익명 토큰 발급/갱신/접근 필터를 통과하며 가상 Principal이나 MOCK_OPERATOR를 주입하지 않습니다. 배포 bootJar에 들어가지 않고 기본 제품 설정을 바꾸지 않습니다. 공개 API/스키마·Android 코드는 변경하지 않았습니다.

| fixture | 서버가 직접 반환하는 내용 | expected state / 후보 |
| --- | --- | --- |
| RESULTS | 기존 가상 공급자3곳, 요청 중심37.544/127.055·반경1000m·상한3 | RESULTS /3 |
| EMPTY | 정상 공급자0건 | EMPTY /0 |
| FILTERED | 요청 중심에서 위도1도 떨어진 가상 매장1개. 허용 최대반경20km 밖 | FILTERED /0 |
| FAILED | 가상 공급자의 UPSTREAM_UNAVAILABLE | FAILED /0, recommendationPlan=null |

모두 HTTP200의 정상 계약 상태입니다. FAILED도 실제 Kakao/서울시/GPT 장애가 아니라 **서버 내부 가상 공급자 실패**입니다. 앱의 프록시503·3초 만료·늦은 응답 주입 기록과 구분합니다. 상호·주소·연락처를 실제 데이터로 채우지 않습니다.

```bash
./gradlew -PincludeAndroid=false verify
# 이 터미널에서 유지. 별도 개발 인증 DB를 사용하고 127.0.0.1만 수신합니다.
./gradlew -PincludeAndroid=false :server:previewAcceptance -PpreviewFixture=RESULTS
# 종료 Ctrl+C 후 같은 명령의 fixture만 EMPTY / FILTERED / FAILED로 바꿉니다.
```

기본 포트18082, DB `work/rec05-acceptance/auth`입니다. 필요하면 `-PpreviewPort=18083 -PpreviewDatabase=work/rec05-acceptance/another-auth`처럼 지정합니다. 실행체는1024–65535 포트와 work 아래 DB만 받으며 JDBC 옵션/임의 Spring 인자는 받지 않습니다. 이미 포트가 사용 중이면 기존 서버를 종료하거나 설정을 바꾸지 않고 실패합니다. 앱 담당이 자신의 검증용 package/인증 저장과 DB 조합을 명시적으로 선택해야 합니다. 기존 앱 데이터·Keystore를 자동 삭제하지 않습니다.

fixture 전환은 별도 시험을 위한 서버 재시작입니다. 같은 DB의 인증은 유지하지만 메모리 preview는 복구되지 않습니다. 실제429/미해결 요청을 새 주체·서버 재시작으로 우회하는 제품 동작으로 채택하면 안 됩니다. 기존 운영/개발 DB를 이 실행체에 지정하지 않습니다. adb reverse나 설치·기기 조작은 앱 담당의 격리 시험 환경에서 수행하며 이 task는 자동 수행하지 않습니다.

공동 시험의 기본 요청은 [기존 조건 포함 예제](../api/examples/place-preview-recommendation-query.json)의 query/generation을 쓰고, requiredFeatures=[childFriendly,parking], preferredCuisines=[한식,양식]로 고정합니다. 화면 인원2/1·대기15분, 정상 fixture 중심37.544/127.055입니다. 예제는 가상 설명용이며 ETA·실제 위치/매장 문의 승인이 아닙니다.

## 자동 서버 대조

```bash
work/contract-venv/bin/python scripts/check-preview-acceptance.py
```

Python 환경은 `scripts/requirements-dev.txt`가 필요합니다. 기존 개발 venv 또는 별도 venv를 사용합니다. 검사는 각 fixture를 임의 loopback 포트·별도 임시 DB에서 시작하고 종료 후 임시 파일을 정리합니다. 원래18082 서버나 앱 인증 DB는 건드리지 않습니다. 토큰·세션 ID·장소 원문을 출력/증거 파일에 보존하지 않고 상태/개수만 집계합니다.

네 상태 외에 무인증401, 성인21/아동11의400, 상한20/10 수용, 배열 순서를 바꾼 완료 세대ALREADY_FINISHED, 같은 세대 조건 변경REQUEST_CONFLICT, 동일 주체refresh 후 close·인증 폐기를 실제 HTTP와 두 OpenAPI로 대조합니다. 완료 반복/충돌은 재검색이 아니며 새로운 사용자 의도로 보낸 세대2의 경계 인원 요청만 별도 가상 검색입니다. 진행 중 합류·취소/경쟁·구세대·deadline은 기존 Java/API 테스트 증거와 함께 봅니다.

이 검사는 기기 화면 표시·onStop·실제5분 경과·CarUx moving/단절을 실행하지 않습니다. 서버가 직접 반환한 상태의 근거이며 실제 공급자 장애나 공동 인수 성공으로 세지 않습니다.

로컬 실행 결과: Java329개/실패·오류·skip0, 위 계약 HTTP48건/4fixture 통과. 기존 패키징 JAR의 인증/MOCK·UNKNOWN·H2 재시작·LIVE 차단과 preview 토큰HTTP16건도 재검증했습니다. 원격 CI는 최신 PR #19 결과를 따로 확인합니다. 실제 장소/GPT/웹 수집/전화/유료 호출은0회입니다.

## 양측 인수표

### 2026-11-10 고정 앱 클라이언트의 직접 호환 검증

화면 담당의 기기 답변을 기다리는 동안 앱 `cf35ead38408ab19d6cf2657ed597237d368d7af`의 실제 `AuthSession`, `MockRepository`, `PreviewController` 소스를 변경 없이 Java17 대상으로 컴파일하고 서버4fixture에 직접 연결했습니다. 서버 제품 소스 기준은50ce344이며 공개 계약은1.1.0/0.2.0-draft 그대로입니다.

| fixture | 앱 클라이언트 요청 | 확인한 내용 |
| --- | --- | --- |
| RESULTS |13 | 추천3개/조건 집합·UNKNOWN·권한 false를 정상 수신 |
| EMPTY |13 | 정상0건 수신과 빈 결과 처리 |
| FILTERED |13 | 범위 밖0건과 기초 추천 계획 처리 |
| FAILED |13 | 실패를 FAILED로 받고 결과를 비움 |

각 행은 앱의 명시적 인증 발급1회, 검색2회(인원2/1 및 상한20/10), 토큰 갱신 뒤 같은 주체로 기존 preview 정리, `allow(false)`/편집에서 즉시 결과 제거, cancel/close 완료, 인증 폐기를 포함합니다. 앱 입력의 배열 순서와 서버 정렬이 다른 실제 응답도 통과했습니다. **앱 HTTP52건 + 기존 schema HTTP48건**이며 기동 polling은 합계에 넣지 않았습니다.

host JVM에서 memory vault와 제한된 HTTP wire를 사용합니다. 앱 코드 세 파일을 읽어 사용하지만 Android의 실제 `LoopbackTransport`, Keystore, UI/Activity, CarUx, APK를 실행하지 않았습니다. 이 결과는 동일 서버 응답을 앱 core가 수용하는 호환 증거이며 **공동 기기 인수 성공이 아닙니다**. 현재 adb 기기가 없는 상태에서 검증했으며 다른 담당의 소스·브랜치·앱 저장소·adb 연결을 변경하지 않았습니다.

```bash
# 원본/다른 담당 브랜치를 전환하지 않는 별도 읽기용 checkout
git worktree add --detach work/rec05-app-source cf35ead38408ab19d6cf2657ed597237d368d7af
./gradlew -PincludeAndroid=false verify
work/contract-venv/bin/python scripts/check-preview-app-compatibility.py --app-root work/rec05-app-source
# 해당 경로에 본인 변경이 없을 때만 정상 제거
git worktree remove work/rec05-app-source
```

검사는 checkout HEAD와 대상 세 소스의 미수정 상태를 확인합니다. app core의 기존 JVM 검사용 org.json20240303을 별도 `previewClientJson` configuration으로 고정하고 lockfile을 갱신했습니다. 서버/일반 Spring test runtime에 추가하지 않으며 앱 소스/해당 JSON JAR의 배포 JAR 제외도 검사합니다. 토큰은 메모리에서만 쓰고 출력·응답 오류 본문에 노출하지 않습니다. CI도 같은 commit을 별도 경로에 checkout해 기존48건과 함께 재현합니다. 앱 변경을 자동으로 따라가지 않으며 **새 앱 commit을 검토·pin 갱신·재실행하기 전에는 후속 APK의 호환 증거로 사용하지 않습니다**.

같은 작업에서 서버 테스트329개를 다시 실행해 실패/오류/skip0을 확인했습니다. 기존 미리보기 계약 JSON도 Gradle test 산출물로 명시했습니다. 생성된 가상 JSON만 제거한 뒤 `verify`가 FROM-CACHE로137건을 동일 hash로 복원했고, 계약 검사도 통과했습니다. 이 수정은 캐시 복원 뒤 증거 파일이 없어지는 경로를 막으며 제품 API나 테스트 결과를 조작하지 않습니다.

JSON 의존성의 [20240303 배포 POM](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.pom)은 라이선스를 Public Domain으로 표기합니다. 여기서는 앱의 고정 JVM 검사 환경 재현에만 사용합니다.

| 항목 | 서버 담당 | 화면 담당/공동 실행 |
| --- | --- | --- |
| 필드/인원/조건 집합 | 소스 대조·서버 HTTP 경계 확인 | 최종 APK의 입력/표시와 이 기준 대조 대기 |
| RESULTS/EMPTY/FILTERED/FAILED | 위4 fixture와 실제 계약 응답 검사 | 같은 fixture/버전으로 화면 표시·증거 대기 |
| 진행 중 합류/조건 충돌/취소·늦은 결과 | 기존 단위/MockMvc 검사, 완료 반복/충돌 HTTP 추가 대조 | 화면 선무효화·cancel503/late·뒤로/onStop 공동 대조 대기 |
| refresh/복구 | 실제 발급 토큰의 같은 preview 소유권 검사 | 앱 토큰 보관/GET 복구·명시 새 검색 대기 |
| CarUx | 서버가 실제 차량 상태를 만들어 내지 않음 | 초기값/재연결과 개발 주입을 구분. 실제 moving/단절 미검증 유지 |

양측 실행 행에는 서버 commit, APK SHA, fixture, 기대/실제, 증거 위치, 미검증을 함께 기록합니다. 서로 다른 버전의 별도 실행을 같은 조합의 공동 실행으로 합치지 않습니다. 현재 문서는 서버 준비 결과이며 #16/#26 OPEN·관련 Draft를 유지합니다. #1 실제 목적지/ETA·#11 데이터 이용 조건·#17 운영 수명은 별도입니다.

## 웹 상세정보의 다음 필드 협의

PR #38의 [REC-11 내부 모듈](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/a22745a/docs/REC11_PLACE_DETAILS.md)은 미병합이며 현행 미리보기 필드가 아닙니다. 먼저 위 현행 계약 공동 인수를 확인한 뒤 아래를 #26과 검토합니다. 이 문서만으로 API0.3이나 화면 구현을 합의한 것으로 보지 않습니다.

- 상세정보는 현재 generation과 ordinal에만 결합합니다. sourceType=WEB_JSON_LD·URL·관측/수정/적용/만료 시각·확실성을 링크/문구로 표시합니다.
- 필드 상태REPORTED/UNKNOWN/CONFLICT와 읽기 실패를 구분합니다. 상충하는 값을 임의 선택하거나 영업시간/일반 예약 정책을 ARRIVAL 빈자리·매장 수락으로 바꾸지 않습니다.
- 기초 추천 질문과 상세정보의 remainingQuestions를 구분해 주차/아동 웹 보고·추가 질문을 표시합니다. ARRIVAL 대기/예약 가능 질문과 bookable/발신 차단은 유지합니다.
- 전체 displayValidUntil은 상세정보를 포함한 최솟값을 사용하고 화면/목적지 변경·취소·만료 시 함께 제거합니다. 출처/동명이점 불일치는 상세정보를 붙이지 않습니다.
- 필드 채택 후 별도 공개 스키마·예제·서버 호출자·앱 테스트를 같은 범위로 갱신합니다. 실제 GPT/웹 수집·데이터 제공/비용 승인은 별도이며 이번 시험에서 실행하지 않습니다.
