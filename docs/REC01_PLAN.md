# REC-01 장소 검색 adapter 개발 계획

작성일: 2026-11-07 KST. 담당: `tiemo0708`. 연결 이슈: [#2 REC-01](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/2). 작업 브랜치: `feat/2-place-adapter`.

목표는 **목적지 주변 음식점 데이터를 공통 형태로 바꾸고, 기존 필수 조건·미확인 정보·만료 판단에 연결할 수 있는 검색 경계를 만드는 것**입니다. 사용자가 성수·서울숲 주변 음식점과 Kakao Local 우선 검토를 선택했습니다. MOCK 분리와 가상 응답 테스트를 먼저 끝내고, 실제 연동은 사용 조건과 서버 저장 경계를 확인한 뒤 진행합니다.

최신 진행은 [실제 연동 검토](REC01_INTEGRATION.md)를 따릅니다. 공식 저장 제한과 팀 앱 미생성 상태를 기록하고, HTTP adapter의 loopback 검증을 완료했습니다. 전체 테스트는 130개이며 Kakao 실제 요청은 없습니다.

착수 기준은 main `35561ee`입니다. 후속 구현으로 P1 MOCK 분리와 P2 오프라인 정규화·실패 검증을 완료했습니다. 아래 P3/P4의 실제 연동 계약·설정은 제안이며, 구현·검증 결과는 마지막 절과 [STATUS](STATUS.md)를 따릅니다.

## 범위와 선택 사항

| 항목 | 이번 기준 | 결정 상태 |
| --- | --- | --- |
| 시범 지역 | 성수·서울숲 주변 | 사용자 선택 완료. 테스트 목적지와 지역 경계는 P0에서 기록 |
| 업종 | 음식점 | 사용자 선택 완료. 카페·숙박·병원은 후속 |
| 장소 사업자 | Kakao Local 우선 검토 | 사용자 선택 완료. 사용 조건 확인 후 실제 채택 |
| 검색 중심 | 요청의 목적지 좌표 | MOCK query에 반영, 실제 주변 검색은 미실행 |
| 검색 반경 | 처음에는 1,000m, 자동 확대 없음 | 로컬 query 기본값. 실제 시범 검증 전 실험 초기값 |
| 수집과 표시 | 첫 페이지 최대 15곳 수집 → 중복 제거·평가 → 최대 3곳 반환 | 변환·선택 구현, 실제 수집 미실행. 0·1·2곳도 정상 결과 |
| 예약 판정 | 알려진 필수 조건 불충족은 제외 사유, 미확인은 예약 보류 | 기존 계약 유지 |
| 실행 | 기본 MOCK, 외부 네트워크 없이 검증 | 기존 프로젝트 규칙 |

지역명으로 모든 요청을 성수동에 강제 고정하지 않습니다. 시범 지역은 검증 범위이며 실제 검색 중심은 입력 좌표입니다. P0에서 고정 테스트 목적지 3개와 시범 범위를 정하고, 범위 밖 요청의 지원 여부를 #5와 합의합니다. 개인 이동 기록은 테스트 입력으로 사용하지 않습니다.

이번 #2에서 다루는 것은 사업자 선정 근거, adapter 설계, MOCK 교체 경계와 실패 테스트입니다. 실제 HTTP 연동·공개 API 확장·저장 트랜잭션 재설계가 커지면 별도 이슈로 분리합니다. 전화 발신, 사용자 인증, 운영 DB 선정, Android 화면 구현, 다중 예약, 경로 ETA 계산은 각 담당 작업입니다.

## Kakao를 먼저 검토하는 이유

Kakao는 좌표·반경·음식점 분류·거리순 검색을 지원하며 장소 ID와 전화번호 필드를 제공합니다. 네이버 지역 검색은 검색어 중심이고, 요청 파라미터에 좌표·반경이 없으며 최대 5건을 반환합니다. 전화번호 필드는 값을 반환하지 않습니다. 따라서 목적지 주변 후보 검색과 후속 문의 연결에는 Kakao가 더 직접적으로 맞습니다. 이는 API 적합성 판단이며 두 서비스의 실제 지역 데이터 품질 비교 결과는 아닙니다. [Kakao 검색 문서](https://developers.kakao.com/docs/ko/local/dev-guide), [네이버 지역 검색 문서](https://developers.naver.com/docs/serviceapi/search/local/local.md)

Kakao 후보 요청안은 `category_group_code=FD6`, `x=longitude`, `y=latitude`, `radius=1000`, `sort=distance`, `page=1`, `size=15`입니다. 반경과 페이지 수는 우리 프로젝트의 제한입니다. 반환된 거리 값은 중심좌표까지의 거리이며 주행 시간이나 도착 ETA로 변환하지 않습니다. [Kakao 요청·응답 정의](https://developers.kakao.com/docs/ko/local/dev-guide)

쿼터 문서에는 무료 제공 조건과 유료 추가 쿼터가 구분되어 있고, 카카오맵 무료 쿼터는 개발자 계정의 첫 활성화 앱 조건이 있습니다. 실제 팀 앱의 적용 상태를 확인하기 전에는 무료 실행을 단정하지 않습니다. API 결과의 저장·캐시·재표시 허용 범위는 별도로 확인합니다. [Kakao 쿼터 정책](https://developers.kakao.com/docs/ko/getting-started/quota)

| P0에서 남길 결정 | 필요한 근거 | 완료 기준 |
| --- | --- | --- |
| 지역과 반경 | 테스트 목적지 3개, 팀의 현장 확인 가능 여부 | 입력 좌표·반경·범위 밖 처리 방식 합의 |
| 사용 가능한 데이터 | 제공 필드와 빈 값 처리 | 아래 매핑 규칙 확정 |
| 저장·캐시·재표시 | 공식 약관/정책의 해당 조항, 확인일 | 검색 snapshot·장소 저장·화면 표시에 대한 허용 범위 기록 |
| 계정·키 | 관리 주체, 서버에서만 보관할 방법 | Git·Android APK·CI 로그에 키를 넣지 않는 설정 |
| 쿼터·비용 | 실제 앱의 무료 대상 여부, 사용량, 유료 설정 | 호출 상한과 과금 여부를 확인; 유료 호출은 별도 승인 |

지속 저장 조건을 확인하지 못하면 실제 응답을 현재 H2 snapshot에 연결하지 않습니다. MOCK 테스트는 계속 진행할 수 있습니다. 원문 응답을 Git fixture로 복사하지 않고 가상 상호·식별자·연락처 없는 JSON을 직접 작성합니다.

## 착수 기준과 수정 지점

| 파일 | 착수 시 상태 | 작업 범위 |
| --- | --- | --- |
| [RecommendationEngine](../server/src/main/java/ai/pleos/assistant/RecommendationEngine.java) | `fixtures()`가 가상 3곳 생성, `rank()`가 평가, `apply()`가 문의 사실 반영 | fixture 생성을 adapter로 이동하고 평가·사실 반영 책임 유지 |
| [WorkflowService](../server/src/main/java/ai/pleos/assistant/WorkflowService.java) | `search()`에서 생성·평가·저장을 수행 | 우선 MOCK adapter 주입으로 변경 |
| [Store](../server/src/main/java/ai/pleos/assistant/Store.java) | `command()`가 전역 DB mutex와 최초 응답 snapshot 관리 | P1에서는 유지. 실제 IO 경계는 #4와 설계 |
| [Model](../server/src/main/java/ai/pleos/assistant/Model.java) | 공개 Candidate에 이름·음식 분류·facts만 있고 provider ID·주소·좌표는 없음 | 내부 장소 타입부터 추가. 공개 DTO 변경은 #4·#5와 함께 |
| [WorkflowTest](../server/src/test/java/ai/pleos/assistant/WorkflowTest.java) | 필수 조건·예약 가능·만료·늦은 관측·idempotency 등 기존 검증 | 기존 가상 ID와 시연 흐름을 유지하며 회귀 확인 |
| [OpenAPI](../api/openapi.json) | 후보는 `maxItems: 3`, 최소 개수 제한 없음. cuisine은 null 불가 문자열 | 빈 결과는 이미 스키마상 허용. 새 오류·필드·모드 추가 시 계약 갱신 |

현재 `search()`는 `@Transactional`이며 MOCK adapter 호출 위치가 `Store.command()`의 잠금 안쪽입니다. 여기에서 네트워크를 호출하면 다른 예약·문의 명령도 기다릴 수 있습니다. HTTP 호출을 함수 밖으로 조금 옮기는 것만으로 바깥 트랜잭션이 사라지지는 않습니다. P1에는 네트워크 없는 MOCK만 연결하고, 실제 IO는 P3의 별도 경계 설계를 적용합니다.

## 구현한 내부 계약

다음 타입을 기존 `ai.pleos.assistant` 패키지에 구현했습니다. 공개 API 모델과 Gradle 의존성은 유지했습니다. WorkflowService는 IO 경계가 합의되기 전까지 구체적인 MOCK adapter만 주입받습니다.

| 타입 | 책임과 주요 값 |
| --- | --- |
| `PlaceSearchAdapter` | `search(PlaceQuery)`로 검색 결과 또는 명시적 실패 반환. 순위·예약 상태·DB 저장은 수행하지 않음 |
| `PlaceQuery` | 중심 위도·경도, 반경 m, 수집 상한. 현재 업종은 음식점으로 제한. 인원·개인 취향·연락처를 포함하지 않음 |
| `PlaceSummary` | provider, providerPlaceId, name, 정규화된 cuisine, latitude, longitude, nullable address/phone/placeUrl/distanceMeters |
| `PlaceSearchResult` | 성공 시 불변 장소 목록과 수집 시각·출처. 빈 목록 성공과 실패를 다른 결과로 표현 |
| `PlaceSearchFailure` | 인증/권한, 호출 제한, timeout, upstream 장애, 잘못된 응답, 잘못된 query 구분. upstream 원문·키 노출 금지 |
| `MockPlaceSearchAdapter` | 불변 가상 장소 목록과 조회 시각 제공. 후보·facts는 검색별 새 객체로 변환 |
| `MockCandidateFactory` | 기존 3곳의 가상 조건만 부여. KAKAO 출처는 거부해 MOCK 사실 혼합 차단 |
| `KakaoPlaceMapper` | 가상 Kakao JSON을 내부 장소로 변환하는 순수 경계 |
| `KakaoPlaceSearchAdapter` | 별도 생성하는 HTTP 경계. Spring 미등록, loopback에서 timeout·취소·크기 제한 검증. 제품 흐름 미연결 |

`PlaceSummary`는 정적인 장소 식별·표시용 정보입니다. 주차·아이 동반 같은 `Fact`를 여기서 추론하지 않습니다. 기존 가상 조건은 MOCK 전용 fixture 구성에 분리하고, 후보 생성 시 MOCK 경로에만 적용합니다. 공통 후보 변환은 기본적으로 빈 facts에서 시작합니다.

공개 `Candidate.id`는 검색 안의 후보 식별자이고, `providerPlaceId`는 사업자의 장소 식별자입니다. 서로 같은 개념으로 취급하지 않습니다. P1은 기존 `mock-1`~`mock-3`을 유지해 기존 시연을 보존합니다. 실제 후보의 ID 생성·내부 장소 참조·저장 snapshot은 P3에서 #4와 합의합니다.

### 필드와 사실을 바꾸는 규칙

| 입력/상태 | 정규화 규칙 |
| --- | --- |
| `id`, `place_name` | 비어 있으면 항목을 사용할 수 없다고 판정. ID를 상호에서 임의 생성하지 않음 |
| `x`, `y` | 경도·위도로 각각 변환. 숫자·범위·유한값 검사, 0으로 대체하지 않음 |
| 업종 코드 | 카테고리 검색 응답의 FD6을 요구. 누락·다른 업종은 불량 항목으로 처리 |
| 카테고리 | `음식점 > 한식/양식/중식/일식/분식`의 두 번째 단계만 매핑. 다른 분류는 미상, 취향 점수 0 |
| 분류 누락과 공개 cuisine | 내부 null 허용. MOCK 후보 변환은 빈 문자열, 평가 점수는 0. 실제 데이터 화면은 #5와 합의 |
| 주소·전화번호·URL | 부재는 부재로 유지. 전화번호 유무가 전화 승인·예약 가능을 뜻하지 않음 |
| 거리 | 숫자 m 또는 미상. 후보 도착 시각이나 차량 이동 시간으로 사용하지 않음 |
| 주차·아이 동반 | 장소 검색만으로 값을 만들지 않음. 인근 주차장 존재도 해당 식당의 parking=true 근거가 아님 |
| 빈자리·예약 가능·예약 확정 | 각각 별개. 장소 응답은 셋 중 어느 것도 확정하지 않음 |
| 시각 | `fetchedAt`은 조회 시각. 매장의 상황을 관측한 `observedAt`으로 재사용하지 않음 |
| 문의 facts | source/evidenceRef/observedAt/appliesAt/validUntil/certainty/scope/mode 보존. 늦게 받은 정보의 TTL을 다시 시작하지 않음 |

조회한 전체 목록에서 `(provider, providerPlaceId)`로 중복을 제거합니다. 같은 이름의 다른 지점은 합치지 않습니다. 일부 레코드가 잘못되면 제외 수·유형을 내부 결과에 남기고, 비어 있지 않은 원본이 전부 잘못되었다면 정상 0건 대신 잘못된 응답으로 처리합니다. 정상 빈 `documents`와 필수 구조 자체가 누락된 JSON도 구별합니다.

순위는 기존의 적격 여부 → 미확인 항목 수 → 취향 점수 → 안정적인 ID 순서를 유지합니다. `select()`는 입력 후보를 모두 평가한 뒤 최대 3곳만 선택합니다. 이후 문의에 따른 재평가는 저장된 후보 안에서 수행합니다. 검색 지역 전체의 최선 후보를 찾았다고 표현하지 않습니다. 부족한 결과를 가상 장소로 채우거나 자동으로 반경을 늘리지 않습니다.

## 단계별 작업과 완료 기준

아래 시간은 1명이 집중 작업할 때의 계획 추정치입니다. 팀 합의·약관 확인·계정 준비를 기다리는 시간은 별도이며, 날짜가 지나도 미검증을 완료로 바꾸지 않습니다.

| 단계 | 예상 작업량 | 산출물 | 완료 기준 |
| --- | --- | --- | --- |
| P0 결정 기록 | 2~3시간 | 이 문서의 지역·반경·사업자·사용 조건 결정 | 선택 근거와 미해결 항목에 담당·다음 행동 명시 |
| P1 MOCK 분리 | 4~6시간 | adapter 계약, MOCK 구현, 검색 서비스 주입, 회귀 테스트 | 기본 MOCK 3곳·기존 ID·facts·예약 흐름 유지, 외부 IO 없음 |
| P2 정규화와 실패 검증 | 4~6시간 | 가상 JSON, Kakao mapper, 결과/실패 모델, 경계 테스트 | 0~3곳·중복·누락·실패·잘못된 시각 처리가 자동 검증됨 |
| P3 실제 연동 설계 합의 | 2~3시간 | #4·#5와 API/저장/모드/오류 합의, 실제 조회 검증 절차 | 네트워크와 DB 잠금 분리안, idempotency·저장 허용 범위 명확 |
| P4 실제 조회 | 별도 추정 | 필요한 경우 새 이슈와 HTTP adapter·실행 증거 | 키·사용 조건·모드·호출 상한 준비 후 수행. 비용 발생은 별도 승인 |

처음 2~3작업일의 목표는 P0~P3입니다. P0의 외부 확인이 지연되어도 P1~P2는 가상 데이터로 진행할 수 있습니다. 외부 조회나 전화 없이도 설계와 회귀 테스트를 완료할 수 있습니다.

### P1을 시작하는 순서

1. 기존 `WorkflowTest`와 로컬 MOCK 실행을 기준선으로 확인합니다. 서버 작업에 Android SDK·Pleos 기기는 필요하지 않습니다.
2. adapter 입력/성공/실패 타입을 정의합니다. provider 고유 JSON을 WorkflowService로 넘기지 않습니다.
3. 가상 장소 생성과 MOCK 전용 사실 구성을 추천 엔진에서 분리합니다. `Clock`을 주입하고 반환 객체를 검색별로 분리합니다.
4. `WorkflowService.search()`가 MOCK adapter를 받도록 생성자 주입을 바꿉니다. 평가와 저장 순서는 유지합니다.
5. 기존 fixture ID·MOCK 라벨·확인된/미확인 조건·TTL을 회귀 테스트로 확인합니다.
6. 호출 실패의 내부 결과 타입을 테스트하되 아직 공용 HTTP 오류 코드를 임의로 추가하지 않습니다. 실제 서비스 연결 시 P3의 계약 변경을 함께 적용합니다.

P1에서는 기존 `Model` 공개 필드와 Flyway migration을 바꾸지 않습니다. 독립 단위 테스트를 새 파일로 추가해 #4의 `WorkflowTest` 수정과 충돌을 줄이고, 공통 서비스 파일 변경은 작은 diff로 유지합니다.

### P3에서 서버 담당자와 결정할 경계

실제 조회 흐름은 **요청 검증 → 중복 명령 확인/실행권 확보 → 트랜잭션 밖 장소 조회 → 짧은 트랜잭션에서 검증·저장 → 응답**으로 설계합니다. 현재 코드를 그대로 이 순서로 호출하면 충분하다는 뜻은 아닙니다.

| 합의 항목 | 반드시 보장할 동작 |
| --- | --- |
| 실행권과 재시도 | 같은 키·본문 재전송은 최초 snapshot 반환. 같은 키·다른 본문은 KEY_REUSED. 진행 중 요청도 중복 조회를 제한 |
| 외부 IO | DB mutex와 트랜잭션 밖에서 실행. 메서드 자기 호출로 `@Transactional` 경계가 적용되지 않는 구조를 피함 |
| 실패·프로세스 종료 | 진행 중 claim의 만료·대조·재시도 정책 정의. 영구 잠금이나 무제한 호출을 만들지 않음 |
| 시간 경과 | 조회 중 ETA·navigation freshness가 바뀌면 저장 전 재검증. 오래된 사실을 새 관측으로 기록하지 않음 |
| 응답 계약 | 정상 0건과 실패 구분. 인증·429·timeout·5xx·잘못된 응답의 HTTP 상태/코드를 OpenAPI에 함께 추가 |
| 보관 | 공개 DTO 밖의 provider ID·검색 snapshot·연락처 저장 위치와 보존 조건을 함께 결정 |
| 실제 장소와 모의 예약 | 현재 Search.mode=MOCK와 가상 지역 라벨로 실제 장소를 감추지 않음. 데이터 출처와 예약 실행 모드의 표현을 #5와 설계 |

성공한 검색은 기존처럼 저장 후 조회할 수 있어야 합니다. 실제 응답 저장 조건이 맞지 않으면 snapshot 방식을 다시 설계해야 하므로 단순 HTTP 교체를 먼저 배포하지 않습니다. `app.mode=LIVE` 시작 차단을 해제하는 작업은 #2의 자동 결과물이 아닙니다.

P4 초기 제안은 자동 재시도 0회, 검색당 요청 1회, 연결 timeout 2초·전체 요청 timeout 3초입니다. 이는 실험 설정으로 P3에서 확정합니다. 실패하면 오류를 표시하며 MOCK 후보를 대신 반환하지 않습니다. 로컬 HTTP stub으로 상태·지연·응답 크기 제한을 먼저 시험한 후 실제 조회를 검증합니다.

## 테스트 계획

고정 또는 변경 가능한 `Clock`, 가상 JSON, 가짜 adapter를 사용합니다. 현재 테스트는 키·외부 네트워크 없이 실행됩니다. 아래 목록은 단계별 인수 시나리오이며 테스트 메서드/파라미터 실행 수와는 다릅니다. 실제 HTTP timeout·잠금 분리는 P4에서 검증합니다.

| ID | 시나리오 | 기대 결과 | 단계 |
| --- | --- | --- | --- |
| REC-T01 | MOCK 기본 검색과 반복 검색 | 기존 3개 ID·표시·facts 유지, 검색 간 객체/사실 오염 없음 | P1 |
| REC-T02 | 정상 빈 결과·1개·2개 | 개수 그대로 반환, 가상 후보 보충 없음 | P2 |
| REC-T03 | 3개 초과 결과 | 중복 제거·평가 후 최대 3개, 선택 순서 재현 가능 | P2 |
| REC-T04 | 중복 ID와 동명 다른 지점 | 같은 provider ID만 제거, 다른 지점은 유지 | P2 |
| REC-T05 | 분류·주소·전화번호·거리 누락 | 허용된 누락 유지, 분류 미상 점수 0, 예외 없이 평가 | P2 |
| REC-T06 | ID/이름 부재·잘못된 좌표·잘못된 JSON | 불량 항목과 전체 응답 실패 구분, 정상 0건으로 위장하지 않음 | P2 |
| REC-T07 | 필수 조건 false·미상·만료 | 각각 제외 사유·missing·재문의 필요로 평가, 예약 불가 | 기존 회귀 + P2 |
| REC-T08 | 아이 동반 + 선호 음식 충돌 | 필수 childFriendly가 취향보다 우선 | 기존 회귀 |
| REC-T09 | NOW 대기와 ARRIVAL 대기 | NOW를 도착 대기로 사용하지 않음. ARRIVAL scope·해당 ETA 요구 | P2 |
| REC-T10 | 빈자리 있음 + 예약 불가 | bookable=false, 예약 확정 생성 없음 | 기존 회귀 |
| REC-T11 | `validUntil == now`와 늦은 관측 | 정확한 만료 경계, 원 관측 시각·유효기한 유지 | 기존 회귀 + P2 |
| REC-T12 | 문의 전후 재평가·다른 ETA | 해당 후보만 갱신, 다른 ETA의 사실은 현재 예약 근거가 아님 | 기존 회귀 + P2 |
| REC-T13 | 인증·권한·429·timeout·5xx | 빈 성공과 구분된 내부 실패. HTTP 매핑은 P3 합의 후 검증 | P2/P4 |
| REC-T14 | 동일 키 재전송·다른 본문·동시 검색 | 최초 snapshot·KEY_REUSED 유지, 실제 연동 시 중복 IO 제한 | 기존 회귀/P4 |
| REC-T15 | 조회가 느린 동안 다른 명령 실행 | 외부 IO가 전역 DB mutex를 점유하지 않음 | P4 |
| REC-T16 | 실제 장소 + 모의 예약 설정 | 출처/모드 표시 분리, 설정만으로 실전화 활성화되지 않음 | P3/P4 |

`rank()`에 ARRIVAL scope·일치하는 ETA·올바른 값 타입·만료 경계·미래 관측·모드 검사를 추가했습니다. 현재 MOCK 엔진에서 LIVE 사실은 예약 판단에 사용하지 않습니다. 잘못된 사실은 missing으로 남깁니다. 기존 관측 이후의 늦은 관측이 덮어쓰지 못하는 동작도 검증했습니다.

## 다른 담당자와 맞출 내용

| 이슈 | REC-01이 제공할 것 | 상대 담당자와 합의할 것 | 지금 독립 진행 가능한 것 |
| --- | --- | --- | --- |
| #1 VEH-01 | 목적지·UTC ETA 입력 요구사항 | 검증된 ETA 변환, 목적지 변경 의미 | MOCK/MANUAL 좌표로 검색 테스트 |
| #3 VOICE-01 | 후보 ID, 문의할 missing 목록, 사실의 적용 시각 | 문의 결과 출처·TTL·전화번호 취급. 전화번호 존재와 발신 승인은 별개 | 가상 InquiryFacts로 재평가 |
| #4 BE-01 | adapter 타입, 장소 ID·후보 ID 의미, 실패 종류 | 공개 DTO·idempotency·IO 경계·places/candidate_facts migration | 순수 mapper·MOCK 구현 |
| #5 UX-01 | 0·1·3개/실패/미확인/제외 사례 | 빈 결과와 실패 문구, 분류 미상, 출처/모드, 범위 밖 안내 | 현재 Candidate 계약으로 가상 화면 연동 |

현재 H2 JSON 저장을 유지하면서 내부 타입을 개발합니다. 제안 ERD의 `places`, `search_candidates`, `candidate_facts`는 #4가 migration을 조율합니다. 이 계획만으로 11개 테이블을 만들거나 ERDCloud 가져오기 SQL을 실행하지 않습니다.

## 브랜치와 PR 운영

계획과 P1~P3을 `feat/2-place-adapter`에서 작은 커밋으로 쌓습니다. 예시 제목은 모두 현재 scope 규칙에 맞습니다.

```text
docs(recommendation): REC-01 장소 adapter 개발 계획 정리
refactor(recommendation): 가상 장소 생성을 adapter로 분리
feat(recommendation): 장소 응답 정규화와 실패 모델 추가
test(recommendation): 빈 결과와 사실 만료 경계 검증
```

현 CI는 PR 본문에 브랜치 이슈와 일치하는 `Closes #2`를 요구합니다. 따라서 **계획만 작성한 PR을 병합해 #2를 조기 종료하지 않습니다.** #2 완료 기준까지 누적한 PR로 합치거나, 중간 단계를 먼저 병합해야 하면 실제 하위 이슈를 만들고 그 번호의 브랜치·PR을 사용합니다. 아직 생성되지 않은 이슈 번호를 예약하지 않습니다.

### 코드 변경 뒤 실행할 검증

저장소 루트에서 실행합니다. 포맷 수정이 필요한 경우에만 `format`을 먼저 사용합니다.

```bash
./gradlew -PincludeAndroid=false verify
# API·상태·영속화 변경 또는 검색 서비스 연결 변경을 포함할 때
.venv/bin/python scripts/smoke-test.py --check-contract
python3 scripts/check-conventions.py --branch feat/2-place-adapter
git diff --check
```

`.venv`가 없으면 [API 검증 환경](../api/README.md#예제와-검증)을 먼저 준비합니다. 테스트 결과의 실행 수·실패·skip과 UP-TO-DATE를 구분해 [STATUS](STATUS.md)에 남깁니다. Android를 수정하지 않는 이슈의 로컬 시작 조건으로 SDK 설치를 다시 요구하지 않습니다.

## 완료 체크리스트와 첫 작업

- [x] #2 담당자·최신 코드·협업 규칙 확인
- [x] 성수·서울숲 음식점과 Kakao 우선 검토에 대한 사용자 선택 기록
- [x] 코드 변경 지점·작업 순서·테스트·협업 의존성 문서화
- [x] 반경 1km·합성 목적지 3개와 공식 저장 제한 기록
- [ ] 팀 앱의 무료 적용 확인과 실제 예약 서비스의 보관 허용 범위 해결
- [x] P1 MOCK adapter 분리와 기존 동작 회귀 통과
- [x] P2 가상 응답 매핑·빈 결과·HTTP 상태별 내부 실패·시각/사실 경계 테스트 통과
- [ ] P3 #4·#5와 실제 연동 계약 합의, 남는 구현은 실제 후속 이슈로 연결
- [ ] #2의 사업자 선정 근거와 모든 완료 조건 확인, 검증 기록·PR 준비

**다음 작업은 팀 앱 준비와 P3 실제 연동 경계 합의입니다.** [공식 저장 제한](REC01_INTEGRATION.md)에 따라 현재 snapshot 구조에는 Kakao 결과를 연결하지 않습니다. #4와 저장 가능한 데이터 출처·IO/트랜잭션·idempotency·실패 응답, #5와 실시간 표시/모의 예약 구분을 결정합니다. #2는 현재 완료 처리하지 않습니다.

## 2026-11-07 P1과 P2 검증 기록

| 테스트 | 실행 수 | 검증한 행동 |
| --- | --- | --- |
| [MockPlaceSearchAdapterTest](../server/src/test/java/ai/pleos/assistant/MockPlaceSearchAdapterTest.java) | 11 | 기존 가상 사실·TTL, 객체 분리, 불변 snapshot, 잘못된 query, 실제 출처 거부 |
| [KakaoPlaceMapperTest](../server/src/test/java/ai/pleos/assistant/KakaoPlaceMapperTest.java) | 40 | 좌표·분류·주소, 누락·중복·불량 항목, 빈 결과, 구조/크기 제한, HTTP 오류 종류 |
| [RecommendationEngineTest](../server/src/test/java/ai/pleos/assistant/RecommendationEngineTest.java) | 23 | scope·ETA·타입·관측·만료 경계, 0~3곳 선택, 문의 후 재평가 |
| [PlaceSearchWorkflowTest](../server/src/test/java/ai/pleos/assistant/PlaceSearchWorkflowTest.java) | 5 | 실제 Spring/H2/MockMvc에서 0·1·2·5개 입력과 최대 3개 저장·응답, 최초 snapshot 재전송 |
| [WorkflowTest](../server/src/test/java/ai/pleos/assistant/WorkflowTest.java) | 25 | 기존 문의·예약·변경·동시성·권한/동의·비용 규칙 회귀 |

`./gradlew -PincludeAndroid=false format verify`에서 **104개 실행, 실패/오류/skip 0**을 확인했습니다. 현재 신규 테스트 실행은 79개이며 기존 25개도 재실행했습니다. 패키징 JAR의 MOCK HTTP 흐름·OpenAPI 대조·H2 재시작·LIVE 시작 차단도 통과했습니다. 공개 API/schema·migration·의존성 변경과 실제 장소 API 조회는 없습니다. TIMEOUT은 내부 실패 종류로 정의했으며 실제 transport timeout 처리는 P4의 HTTP 클라이언트 작업입니다.

후속 HTTP 검증에서 [KakaoPlaceSearchAdapterTest](../server/src/test/java/ai/pleos/assistant/KakaoPlaceSearchAdapterTest.java) 26개를 추가해 총 **130개**가 통과했습니다. 헤더/본문 timeout·취소·HTTP 오류·리다이렉트·수신 크기·UTF-8·합성 목적지 3개를 loopback에서 검증했습니다. TIMEOUT/CANCELLED transport 처리는 구현했으며 실제 TLS·계정·쿼터와 모든 장애의 요청 수 상한은 미검증입니다. 상세한 구분은 [실제 연동 검토](REC01_INTEGRATION.md)를 따릅니다.
