# REC-08: 미저장 후보 추천 근거와 추가 확인 계획

기준: 2026-11-09 KST. 담당 [#28](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/28), tiemo0708. 최신 인증 main `1b37bc2`에서 `feat/28-recommendation-inquiry-plan`으로 분리했습니다. 순수 평가기와 가상 호출 예제의 구현이며 제품 화면·실제 식당/전화 연결 완료가 아닙니다.

## 무엇을 반환하는가

`PlaceRecommendationPlanner.evaluate(provider, query, result, requirements)`는 이미 조회한 결과를 받아 최대 3개 후보를 반환합니다. adapter 호출·DB·캐시·Spring 등록·인증/예약 업무 호출을 하지 않습니다. requirements는 성인/아동 인원, 최대 대기, 필수 childFriendly/parking, 음식 취향입니다.

| 결과 | 의미 |
| --- | --- |
| RESULTS | 기본 장소 정보로 후보를 고름. 적합성·예약 가능 확인 전 |
| EMPTY | 공급자가 성공적으로 0건을 반환함 |
| FILTERED | 반환은 있었지만 검색 범위 내 후보가 없음. 공급자 0건과 구분 |
| FAILED | 공급자 실패, 만료/미래 시각·출처 불일치·불량 결과. 0건으로 바꾸지 않음 |

후보마다 이름·정규화 cuisine·주소·계산한 직선거리, 구조화된 추천 근거, 기본 정보 누락, 질문 목록이 있습니다. 출처와 원래 listingFetchedAt, displayValidUntil, 거절/중복/범위 밖 수는 루트에 둡니다. provider ID·전화번호 값·좌표·placeUrl은 결과에 싣지 않습니다. 후보 ordinal은 해당 결과의 순번일 뿐 기존 candidateId나 예약 식별자가 아닙니다.

## 선정 규칙과 한계

1. 공급자와 입력 상한, 수신 시각을 검증합니다. 원 조회 시각을 현재 시각으로 바꾸지 않습니다. 수신 후 5분의 표시 유효성 경계를 검사하며 정확한 만료 시각도 거절합니다. 이 시간은 식당 영업/주차/빈자리 최신성이나 5분 저장 허가가 아닙니다.
2. 같은 공급자 ID의 완전히 동일한 중복은 한 번 평가하고 횟수를 남깁니다. 같은 ID의 필드가 충돌하면 입력 순서로 하나를 고르지 않고 INVALID_RESPONSE를 반환합니다.
3. 요청 중심과 좌표의 Haversine 직선거리로 반경+30m 밖을 제외합니다. 30m는 REC-07과 같은 진단 허용 오차입니다. WITHIN_SEARCH_WINDOW는 이 범위이며 엄밀히 요청 반경 이내라고 단정하지 않습니다. 공급자 distanceMeters를 도로 거리나 ETA로 해석하지 않습니다.
4. 남은 전체 입력에서 음식 취향 일치 → 계산한 직선거리 → 내부 ID 순으로 정렬해 최대 3개를 고릅니다. 기본 정보만으로 생성한 shortlist이며 필수 조건을 확인한 최종 추천 순위가 아닙니다. 이름/음식 취향/전화번호 존재로 아동 동반·주차 가능을 추론하지 않습니다.
5. 모든 후보는 NEEDS_CONFIRMATION, bookable=false, inquiryDispatchAllowed=false입니다. 확인된 업무 facts를 평가하는 기존 RecommendationEngine이나 예약 검증을 대체하지 않습니다. 필수 조건이 확인되면 해당 엔진에서 다시 평가하는 제품 연결이 필요합니다.

주소·cuisine·전화가 없으면 각각 ADDRESS/CUISINE/PHONE을 표시합니다. CUISINE 미확인은 upstream 분류 필드 자체의 누락과 동일하지 않습니다. PHONE이 없다고 번호를 생성하거나 다른 공급자/GPT 검색을 자동 실행하지 않습니다. 번호가 있다는 사실도 제공 권리·발신 동의가 아닙니다.

## 질문 계약

| key | scope | 생성 조건 |
| --- | --- | --- |
| childFriendly | GENERAL | 아동이 있거나 명시적으로 필수 |
| parking | GENERAL | 주차를 필수 조건으로 선택 |
| arrivalWaitMinutes | ARRIVAL | 항상 문의 계획에 포함. 인원과 최대 대기 제시 |
| reservationAvailable | ARRIVAL | 항상 예약 가능 여부만 문의. 예약 실행 요청이 아님 |

모든 질문의 certainty는 UNKNOWN입니다. 이는 답변 facts가 아니며 전화 담당의 결과 정규화와 명시적 수락을 대체하지 않습니다. ARRIVAL 질문은 arrivalTimeRequired=true로, #1의 검증된 ETA와 해당 시각 적용 확인 전에는 발신할 수 없습니다. 현재 대기(NOW)로 도착 시 대기(ARRIVAL)를 채우지 않습니다.

## 외부 호출 없는 재현

저장소 루트에서 실행합니다.

```bash
./gradlew -PincludeAndroid=false format verify
./gradlew -PincludeAndroid=false :server:recommendationPlan
# Python 개발 의존성 설치 환경에서
python scripts/check-recommendation-plan.py
python scripts/smoke-test.py --check-contract
```

모의 실행은 고정 가상 장소 adapter에 성인2/아동1, 주차 필수, 한식 선호, 최대15분 입력을 사용합니다. MOCK의 세 후보와 주차·아동 동반·도착 시 대기·예약 가능 질문을 출력합니다. LIVE 모드·키 인자를 받지 않으며 외부 검색·전화·AI 호출은 0회입니다. 결과 출력 접두사는 RECOMMENDATION_PLAN입니다. 모의 실행 결과가 실제 Kakao 표본이 아닙니다.

[가상 예제](../api/examples/recommendation-plan-mock.json)와 [JSON Schema 초안](../api/recommendation-plan-draft.schema.json)을 함께 제공합니다. 스키마는 공개 HTTP API를 추가하지 않습니다. 검사기는 예제와 Java 테스트 생성 결과, 선택적인 --file 입력을 대조하고 질문 key/scope·UNKNOWN·false 승인·시각·추천 근거를 검사합니다. CI의 Server and contract에도 연결했습니다.

## 검증 증거

- format verify: Java **268개**, 실패/오류/skip0(이 main의 기반228 + 신규40). 다른 미병합 브랜치의 244/299와 합산하지 않습니다.
- 전체 후보 평가 후 최대3개, 0/1/2/3/5건, 동률/입력 순서, 아동 자동 필수, 연락처 있어도 미확인 유지, 누락·중복 충돌·반경 경계·실패8종·시각 경계·출처 혼합·불변성을 가상 데이터로 검증했습니다.
- 예제/Java 결과/현재 모의 CLI의 3개 문서를 계약 대조했습니다. 승인 true·원문 ID 추가·NOW 대체·확인 완료 사칭·질문 누락·불량 시각·0건 상태 사칭 등 부적합 변형8개를 거절했습니다.
- JAR MOCK HTTP/OpenAPI 1.1.0, 실제 서버 발급 테스트 토큰·소유권/권한·갱신/폐기·H2 재시작과 LIVE 차단을 통과했습니다. 외부 전화/AI/장소 호출은 없습니다.
- 로컬 증거는 Git 제외 work/rec08-*.log/json, Java 실행 예제는 server/build/rec08/mock-recommendation-plan.json입니다. 원격 CI는 실제 실행 결과를 PR에서 확인합니다.

## 후속 인수와 현재 PR 상태

- **#16/#26:** 이번 DTO·질문 의미를 합의하고 인증·세션·세대·취소·만료와 함께 화면에 연결해야 합니다. #19의 인증 main 충돌은 2026-11-09 별도 #16 작업에서 양쪽 검증을 보존해 해소했습니다(서버272/Python7/토큰HTTP16/호출자8). #19 자체는 main에 미병합이고 이번 평가기의 제품 HTTP/화면 연결은 별도입니다. #21은 이미 main에 병합됐으므로 이전 문서의 인증 병합 대기는 이력입니다.
- **#3:** 질문 key/scope와 party/maxWait를 인수합니다. 실제 번호 조회/제공 권한·도착 시각·발신 승인·횟수/비용 상한·서명/중복 처리가 필요합니다. 후보 ordinal로 기존 문의/예약 endpoint를 호출하지 않습니다.
- **#11:** 실제 기본 데이터의 표시·메모리 처리와 공급자 전달 허용 범위는 별도입니다. 평가기 추가가 권한 확인을 대신하지 않으며 실제 POI 결과를 기존 H2 payload에 연결하지 않습니다.
- **#17/#18:** 운영 원장·실제 IP 제한·0건 실증 등 기존 미완료 조건을 그대로 유지합니다. 기존 추가6회 승인은 소비했고 이번에 새 조회는 하지 않았습니다. [PR #24](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/24)의 실제 검색 증거와 구분합니다.

실제 식당 조회부터 화면·전화·예약까지의 end-to-end 연동은 미완료입니다. 이 이슈의 종료 범위는 위 순수 평가기·가상 검증·인수 초안에 한합니다.
