# REC-11 · 웹 상세정보 보강과 GPT 출처 검색 (#37)

2026-11-09. 담당 tiemo0708. 관련 #16/#26 제품 연결, #11 이용 조건, #34 독립 장소 품질 검증.

업체 목록의 이름·주소만으로 알 수 없는 영업시간·메뉴·가격대·일반 예약 정책을 보충하는 **내부 모듈**입니다. 사용자는 GPT 웹검색 API **연동 코드 준비**를 선택했습니다. 실제 OpenAI 호출·키 읽기·매장 페이지 조회·전화는 이번 작업에서 하지 않았습니다. 제품은 MOCK을 유지하며 새 클래스는 Spring bean이나 공개 HTTP endpoint로 등록하지 않았습니다.

## 구현된 흐름

1. `GptPlaceSourceDiscovery`: 사용 가능한 출처의 매장명·주소와 모델·기한을 고정한 일회성 Permit이 있어야 검색 요청을 구성합니다. 응답의 완료된 웹검색과 `url_citation`에서 최대3개 HTTPS URL만 반환합니다. 생성된 설명·평가·빈자리 주장은 채택하지 않습니다.
2. 검색 URL은 **출처 후보**입니다. GPT 인용이나 검색 순위가 매장 일치·수집 허용·예약 권한을 증명하지 않습니다. 운영자가 별도로 이용 조건과 정확한 URL/host를 검토해 `Source`를 구성해야 페이지를 읽을 수 있습니다.
3. `PlaceDetailEnricher`와 `JsonLdPlaceDetailParser`: 페이지의 schema.org Restaurant/FoodEstablishment/CafeOrCoffeeShop 구조화 데이터에서 **이름과 주소 모두** 일치할 때만 필드를 추출합니다. 지점명은 제거하지 않습니다. 공백·NFKC 정규화 수준만 지원하며 유사 이름·주소 추정은 하지 않습니다.
4. `PlaceDetailRecommendationService`: 기존 평가기의 후보 최대3개에 결과별 ordinal로 상세정보와 남은 질문을 결합합니다. 공급자 ID는 내부 출처 조회에만 사용합니다. 동일 이름/주소에 여러 다른 공급자 레코드가 있으면 상세정보를 붙이지 않습니다.
5. 목적지·화면 세대의 current 검사가 실패하거나 기한을 넘기면 전체 결과를 숨깁니다. 호출자는 기존 #16처럼 화면 identity를 먼저 무효화하고 `current`를 false로 전환해야 합니다. 현재 내부 모듈은 실행 중 네트워크를 즉시 중단하는 작업 취소 API는 없고, 제한 시간 내 종료 후에도 늦은 결과를 반환하지 않습니다.

이 순서는 현재 MOCK 예제와 분리된 호출 지점으로 준비했습니다. 실제 검색→자동 수집을 연결한 제품 오케스트레이터·승인 UI·운영 저장소는 없습니다.

## 필드와 판단 한계

| 필드 | 지원되는 웹 보고 | 제품 판단 |
| --- | --- | --- |
| OPENING_HOURS | openingHours 또는 검증된 openingHoursSpecification | 웹 표기만 제공. 휴무·라스트오더·ETA 영업 여부 계산 안 함 |
| MENU_URL | hasMenu/menu의 안전한 HTTPS 링크 | 메뉴 원문/가격표를 추가로 내려받지 않음 |
| PRICE_RANGE / CUISINE | priceRange / servesCuisine | 출처의 표기, 확정 견적·재고 아님 |
| RESERVATION_POLICY | acceptsReservations의 boolean/Yes/No/URL | 일반 예약 정책. 도착 시각 예약 가능·매장 수락 아님 |
| PARKING / CHILD_FRIENDLY | 명시적 amenityFeature boolean | 웹 보고. 일반 설명에서 추론하지 않음 |

필드별 `REPORTED / UNKNOWN / CONFLICT`를 반환합니다. 서로 다른 출처가 다르면 최종 값을 null로 남기고 양쪽 근거를 보존합니다. 반복되는 동일 값은 근거를 합칩니다. 미지원 일반 HTML 문장·PDF·이미지·JavaScript 페이지·커스텀 JSON-LD context는 추론으로 메우지 않습니다. 매장 타입도 현재 음식점 계열이며 다른 예약 업종은 후속 확장입니다.

각 근거는 URL, sourceType=WEB_JSON_LD, observedAt, sourceUpdatedAt, validUntil, certainty, scope, appliesAt을 포함합니다. sourceUpdatedAt/TZ가 없으면 null이고 조회 시각으로 덮어쓰지 않습니다. 웹 보고는 GENERAL/appliesAt=null이며, 표시 기한은 목록의 기한과 페이지 관측 후5분 중 짧은 값입니다. 이5분은 화면 표시 수명이며 원 정보의 최신성을 보증하지 않습니다. future/stale 관측은 채택하지 않습니다.

주차·아동 질문은 명시적 필수 조건에 따라 만들고 아동 동행이면 아동 조건을 추가합니다. 웹 보고가 true인 일반 조건은 상세정보의 `remainingQuestions`에서 제외할 수 있지만 `ARRIVAL_WAIT_MINUTES`와 `ARRIVAL_RESERVATION_AVAILABLE`은 항상 남습니다. false·충돌·미확인 필수 조건은 남습니다. 기존 평가기의 questions는 기초 계획으로 유지하며 caller가 상세정보의 남은 질문과 구분해 표시해야 합니다. 모든 결과의 bookable·inquiryDispatchAllowed는 false입니다.

## GPT API 코드와 실행 제한

공식 [웹검색 가이드](https://developers.openai.com/api/docs/guides/tools-web-search)와 [Responses 생성 명세](https://developers.openai.com/api/reference/python/resources/responses/methods/create)를 확인했습니다. `GptWebSearchHttpTransport`는 `POST https://api.openai.com/v1/responses`에만 전송하며, 별도 loopback endpoint는 package-local 테스트 생성자에 한정합니다.

| 설정 | 현재 코드 |
| --- | --- |
| 모델 | Permit에 명시한 모델 필수. 기본 모델·자동 대체 없음 |
| 도구 | web_search, search_context_size=low, tool_choice=auto |
| 상한 | Permit당 API 요청 최대1회, max_tool_calls=1, max_output_tokens=1024 |
| 데이터 | 이용 허용된 이름·주소만 input으로 전송. 가족·전화·좌표·토큰은 포함하지 않음 |
| 응답 | completed 검색+assistant 인용 필요. 원문·오류 본문 출력/저장 없음 |
| 시간/크기 | 요청/전체 응답 최대30초와 승인·조회 기한 중 최솟값, 응답1MiB |
| 오류 | 자동 재시도·redirect 없음. 시도 후 실패해도 Permit 소비, 추가 시도는 새 승인 필요 |

Permit은 신뢰된 호출자가 주입하는 **메모리상의 일회성 제한**입니다. 서명된 비용 승인 증빙·사용자 인증·서버 재시작/다중 인스턴스의 전역 원장·금액 상한을 구현한 것은 아닙니다. 토큰/도구 상한이 원화 비용을 보장하지 않으며 API 실패도 과금되지 않았다고 단정할 수 없습니다. 실제 실행 전 모델의 도구 지원/가격, 대상·전송 데이터·목적·횟수·비용 상한을 확인하고 사용자에게 명시적 승인을 받아야 합니다. 이번 코드는 실제 실행 가능한 자동 환경변수 로딩이나 LIVE 실행 명령을 제공하지 않습니다.

요청의 `store:false`는 Responses 저장 설정입니다. 서비스 전체의 무보존·처리 로그 삭제를 보증하는 설정으로 안내하면 안 됩니다. 실제 도입 전 계정 데이터 처리 설정도 확인해야 합니다.

HTTP transport는 JVM 시작 옵션 `-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.redirects.retrylimit=1`을 요구하고 HTTP client 로깅이 켜져 있으면 거부합니다. 키는 호출자가 주입하는 Supplier로만 받으며 채팅·Git·예제에 넣지 않습니다. 실제 TLS/계정 권한/모델 호환/청구 내역은 미검증입니다.

## 출처·수집 경계

- KAKAO·UNVERIFIED 출처 이름/주소는 GPT 전송을 차단합니다. 재입력·요약·출처 라벨 변경으로 #11 조건을 우회하지 않습니다. APPROVED_PUBLIC/DIRECT_MERCHANT도 라벨만으로 허가가 생기지 않으며 운영자가 실제 조건을 검토해야 합니다. MOCK은 가상 transport/fixture 검증용입니다.
- READ_ONLY_WEB 페이지 보강은 검토된 DIRECT_MERCHANT/APPROVED_PUBLIC만 허용합니다. 독립 서울 품질 도구가 제품 PlaceSearchAdapter로 채택된 것은 아닙니다. 기존 제품 공급자 enum도 임의로 늘리지 않았습니다.
- `ApprovedPlacePageAdapter`는 승인 host/URL, HTTPS, redirect 거부, HTML MIME,512KiB, 최대5초 네트워크 시간 제한을 둡니다. DNS의 사설·loopback 주소를 사전 거부하지만 **DNS pinning은 없고 DNS 조회 전체 시간은 강제 중단하지 못합니다**. 운영 공개 전 egress/host allowlist, 재해석·DNS 변경 방어, robots/약관과 수집 권한 검토가 필요합니다. 사용자 임의 URL endpoint로 노출하지 않습니다. 이 adapter의 실제 외부 수집은 미실행입니다.
- parser용 jsoup은 [공식 다운로드](https://jsoup.org/download)에서 확인한1.24.1을 고정하고 lockfile을 갱신했습니다. JSON-LD context·외부 스크립트·메뉴 링크를 추가 실행/조회하지 않습니다.

## #16/#26 연결 제안

현행 v1 인증 계약1.1.0과 PR #19의 미리보기0.2.0-draft 요청·응답은 변경하지 않았습니다. 이 모듈은 합의 전 내부 결과이며 새 공개 API 필드로 취급하지 않습니다.

- 기존 `recommendationPlan`은 기초 추천 근거/질문, `candidateDetails[ordinal]`은 웹 보고와 남은 질문입니다. ordinal은 해당 응답 안의 순서이며 예약 candidateId로 변환하지 않습니다.
- 내부 결과의 generation과 `displayValidUntil`을 확인합니다. 상세정보 포함 표시 기한은 각 페이지 기한까지 반영한 최솟값이므로 기초 계획의 더 긴 기한으로 대체하면 안 됩니다.
- #26과 필드/출처 링크·시각/UNKNOWN·충돌·읽기 실패·질문 병합 표시를 검토한 뒤에만 별도 공개 스키마/예제/호출자를 함께 변경합니다. API의 인용은 화면에서 클릭 가능한 출처 링크로 표시해야 합니다([공식 가이드](https://developers.openai.com/api/docs/guides/tools-web-search)).
- #16 기존 계약의 정적 검토는 [화면 담당의 기록](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008)으로 확인했습니다. #19 head5526f92는 최신 mainabd9aa4 반영/충돌 해소·CI3개 성공 상태입니다. #26 구현·공동 기기·#1 ETA·#11 인수는 남아 #16 OPEN/#19 Draft를 유지합니다.

## 재현과 검증 범위

```bash
./gradlew -PincludeAndroid=false format verify
./gradlew -PincludeAndroid=false :server:placeDetails
work/contract-venv/bin/python scripts/smoke-test.py --check-contract
```

`placeDetails`는 가상 GPT 인용, 독립적으로 승인된 가상 출처, JSON-LD 대조, 후보3개/상세정보/남은 질문을 출력합니다. 실제 transport·키·DB·HTTP 서버를 생성하지 않습니다. 공용 MOCK adapter의 미확인 주소는 그대로 유지하고 이 예제에서만 명시적 가상 주소를 만듭니다.

로컬 Java313개(기반268+신규45)·실패/오류/skip0, 패키징 JAR 인증·기존 MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 검증했습니다. 신규45개는 parser/일치/출처·충돌/시각·데이터 제한24개, GPT 요청·인용·일회 승인·동시 실행10개, loopback HTTP 경계5개, 후보/화면 수명 결합·가상 전체 예제6개입니다. 실제 API/웹 수집·Android 화면/기기·#16 새 계약 연결 검증은 아닙니다. 원격 CI는 PR의 실제 결과로 구분합니다.

다음 단계는 코드 리뷰/통합, #26의 필드 협의, #11의 허용 출처 결정입니다. 실제 GPT 검증은 모델·키·비용·전송 데이터 승인 후 별도 제한 실행으로 진행합니다.
