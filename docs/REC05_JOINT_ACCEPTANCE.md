# REC-05 · 화면 공동 인수 기준과 MOCK fixture

2026-11-10 KST. 서버 #16/PR #19, 화면 #26 및 후속 #39/PR #46. [화면 담당의 공동 시험 제안](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6741208185)에 대한 서버 측 확인입니다. **계약/서버 재현 준비와 공동 기기 인수는 구분합니다.** 이 문서가 양측 실행·기기 검증 완료를 뜻하지 않습니다.

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
