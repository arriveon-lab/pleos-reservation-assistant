# Pleos 차량용 AI 예약 비서 기능 명세서

버전: 0.1 · 소스 대조: 2026-11-06 · 공유본 갱신: 2026-11-07 KST · 상태: 팀 검토용 명세.

목적지·도착 예정 시각(ETA)·동행자의 필수 조건으로 식당을 비교하고, 부족한 정보를 문의한 뒤 사용자 승인과 매장의 명시적 수락을 거쳐 예약 결과를 안내합니다. 이 명세는 구현 담당자가 입력·출력·오류·상태·데이터·완료 조건을 함께 확인하기 위한 기준입니다.

현재 기준은 커밋 `f4f98732e243ddd7b806c3f91d0cef03e001aaec`, OpenAPI 계약 **1.0.0**, 2026-11-07 상태를 재확인한 GitHub 이슈입니다. 현재 실행 범위는 **로컬 단일 사용자 MOCK**입니다. 표의 목표 기능은 구현 완료를 의미하지 않습니다. 실제 DB는 6개 테이블이고 11개 ERD는 제안안입니다.

[이슈별 해설](ISSUE_GUIDE.md) · [ERD와 상세 필드](ERD.md) · [관계 시각화](ERD.md#2-핵심-관계) · [OpenAPI](../api/openapi.json) · [검증 현황](STATUS.md)

## 1. 범위와 용어

### 제품 범위

- 1차 목표: 한 지역의 식당, 최대 3개 비교 후보, 필수 동행 조건과 직접 입력한 취향, 누락 정보 문의, 명시적 선택/예약 승인, 매장 수락 대조, 결과 안내.
- 현재 구현: 가상 후보 3개, 모의 문의/예약/변경 작업, 결과 주입, 상태 전이·멱등성·H2 저장. 실제 장소 검색·음성 AI·전화·제품 UI는 미구현입니다.
- 현재 변경 API: 상태와 정책을 확인하는 **명시적 수동 MOCK 명령**입니다. 지속 ETA 지연 감지와 자동 변경은 후속 범위입니다.
- 범위 밖: 결제/취소/노쇼 처리, 회원 가입 UX의 구체 방식, 자동 개인화 학습, 사업자 간 장소 통합, 카페/체험 확장. 구현하려면 별도 계약과 이슈가 필요합니다.

### 서로 바꾸어 쓰면 안 되는 값

| 값 | 의미 |
| --- | --- |
| seatsAvailable | 현재 빈자리 사실. 예약 가능이나 예약 확정을 뜻하지 않음 |
| reservationAvailable | 해당 ETA에 예약을 받을 수 있다는 사실. 사용자의 예약이 확정되었다는 뜻은 아님 |
| Reservation.status=CONFIRMED | 해당 예약 요청에 대해 명시적 수락·시각·비용 일치를 확인한 상태 |
| eligible | 알려진 필수 조건 불충족이 없음. 미확인 정보가 있을 수 있음 |
| bookable | 현재 확인된 조건으로 예약 요청을 만들 수 있음. 예약 확정 상태는 아님 |
| NOW / ARRIVAL | 지금에 대한 관측 / 특정 ETA에 적용되는 관측·추정 |
| Fact.certainty=CONFIRMED | 개별 사실의 확인 수준. 예약 상태 CONFIRMED와 별개 |
| UNKNOWN | 결과를 확정할 수 없음. 실패/거절/성공 중 하나로 임의 치환하지 않음 |
| MOCK / SDK_PROBE / LIVE | 모의 업무 / SDK 진단 관측 / 실제 업무 연동. SDK_PROBE는 API의 Mode enum이 아님 |

매장 추가 비용(`additionalCostKrw`)과 전화/AI 서비스 이용 요금은 별도입니다. 현재 비용 필드는 예약 변경에 대한 매장 추가 비용이며 전화 업체 비용 상한을 대신하지 않습니다.

## 2. 기능 목록과 추적 관계

우선순위: P0=현재 모의 흐름 유지·제품 UI 통합에 필요, P1=실연동/다중 사용자 도입 전 필요, P2=후속. 현재 상태의 “구현”은 소스 확인이며, 마지막 실행 결과는 2026-11-04 STATUS의 기록을 인용합니다. 공유본의 정적 검사는 API·오류·링크·ERD 일치를 대조하며, 과거 제품 실행 결과의 날짜를 갱신하지 않습니다.

| ID | 기능 | 주 이슈 | 협업 | 우선순위 | 현재 상태 |
| --- | --- | --- | --- | --- | --- |
| FR-01 | 실행 모드와 기능 가용성 표시 | #4 | #5 | P0 | 상태 API·LIVE 시작 차단 구현, 제품 표시 미구현 |
| FR-02 | 목적지·ETA·여정 수집 | #1 | #4, #5 | P0 | MOCK/MANUAL 입력 구현, Pleos 변환·기기 미검증 |
| FR-03 | 동행 조건과 취향 입력 | #2 | #5 | P0 | 서버 계약 구현, 입력 화면 미구현 |
| FR-04 | 장소 후보 조회·평가·정렬 | #2 | #4, #5 | P0 | fixture 평가 구현, 실제 adapter 미구현 |
| FR-05 | 출처와 관측 사실 관리 | #2 | #3, #4 | P0 | JSON 내 사실 갱신 구현, 정규화 이력 테이블 제안 |
| FR-06 | 누락 정보 문의 | #3 | #4, #5 | P0 | 모의 작업·결과 주입 구현 |
| FR-07 | 예약 요청과 중복 차단 | #4 | #3, #5 | P0 | MOCK 구현 |
| FR-08 | 매장 결과 검증·예약 확정 | #4 | #3 | P0 | normalized MOCK outcome 구현 |
| FR-09 | 상태 조회·앱 복귀 | #5 | #4 | P0 | ID별 GET 구현, 앱 복귀 UX 미구현 |
| FR-10 | 예약 시각 변경과 비용 승인 | #4 | #3, #5 | P0 | 수동 MOCK 구현, 자동 지연 감지 미구현 |
| FR-11 | 명령·이벤트 멱등성과 동시성 | #4 | #3, #5 | P0 | 단일 사용자·전역 lock 구현 |
| FR-12 | 인증·소유권·승인 증빙 | #4 | #5 | P1 | 미구현, 논리 ERD 제안 |
| FR-13 | UNKNOWN 대조와 수동 해결 | #4 | #3, #5 | P1 | UNKNOWN 보존만 구현, 해결 기능 미구현 |
| FR-14 | 실제 전화와 webhook 처리 | #3 | #4 | P1 | 미구현, 사업자·승인 필요 |
| FR-15 | 차량 화면과 주행 제한 | #5 | #1 | P0 | 제품 UI·CarUxRestrictions 미구현 |
| FR-16 | DB 협업·migration·복구 | #4 | #2, #3 | P1 | 로컬 H2/Flyway 구현, 운영 DB/공유 환경 미결정 |
| FR-17 | 시연·검증·제출 산출물 | #5 | #1–4, #6 완료 기록 | P0 | CLI MOCK·probe debug APK 기록 있음, release·영상 미완료 |

각 이슈는 여러 기능에 연결됩니다. 기능 하나가 구현되거나 문서화되었다고 관련 이슈 전체를 완료 처리하지 않습니다. #6은 협업 기반 완료 기록이며 새 기능의 미배정 작업을 담는 이슈가 아닙니다.

## 3. 공통 입력·표시 규칙

| 항목 | 현재 계약/규칙 |
| --- | --- |
| 시각 | API는 RFC3339 절대 시각, 서버는 Instant. 화면에서 KST로 변환 |
| 예약/ETA/변경 요청 시각 | 현재 시각보다 미래이며 24시간 이내. 정확히 현재 시각은 거부 |
| navigation.observedAt | 서버 시각 기준 최근 5분 이내. 구현의 미래 시계 오차 허용은 최대 5초 |
| tripId | 1–100자. 같은 여정의 재검색에 동일 값. 현재 전역 문자열, 소유자별 내부 ID는 제안 |
| 목적지 | 이름 1–100자, 위도 -90..90, 경도 -180..180 |
| 동행 인원 | adults 1..20, children 0..10. 개인 실명·연락처 입력은 없음 |
| 최대 대기 | maxWaitMinutes 0..120분 |
| 필수 조건 | childFriendly / parking, 최대 2개. children > 0이면 childFriendly 자동 필수 |
| 취향 | preferredCuisines 최대 8개, 각 1–30자. 필수 조건보다 우선하지 않음 |
| 비용 | 정수 KRW. 변경 요청과 정책 상한 각각 0..100,000, 수락된 변경의 누적 상한도 검사 |
| 필수 boolean/정수 | OpenAPI required에 따라 false/0도 명시. 누락/null을 승인으로 해석하지 않음 |
| 알 수 없는 요청 필드 | 거부. 정수 자리에 문자열·소수를 보내는 요청도 거부 |
| 오류 | code/message/mode JSON. 사용자 입력 원문을 오류 메시지로 되돌리지 않음 |

변경되는 시간·금액·요청 대상에 대한 승인과 학습 동의는 각각 별도입니다. 날짜가 고정된 API 예제는 설명용이며 실행 시 현재 시각으로 생성해야 합니다.

## 4. 기능별 상세 명세

### FR-01 실행 모드와 가용성

- **주체/시점:** 앱 시작·재연결 시 #5가 `GET /status`를 조회합니다.
- **현재 출력:** contractVersion=1.0.0, mode=MOCK, externalCallsEnabled=false, pleosEtaVerified=false, dataSource=FICTIONAL_FIXTURES.
- **목표 UI:** 가상 장소와 모의 예약임을 후보·승인·결과 화면에서 알립니다. source=PLEOS라는 진단 로그만으로 LIVE라고 표시하지 않습니다.
- **실패:** 서버 연결 실패 시 마지막 화면을 현재 상태로 단정하지 않고 상태 확인 실패를 표시합니다. 명령 결과 미확인을 성공으로 처리하지 않습니다.
- **인수 기준:** AT-01. LIVE 설정은 시작 실패하며 외부 발신 작업이 발생하지 않습니다. 상태 조회가 실통화 가능성으로 오해되지 않아야 합니다.

### FR-02 목적지·ETA·여정

- **입력:** Navigation의 tripId, destination, eta, observedAt, source.
- **현재 동작:** MOCK/MANUAL 입력의 유효 시각을 검사합니다. PLEOS 입력은 `PLEOS_ETA_UNVERIFIED`로 거부합니다.
- **목표:** #1의 기기 증거로 시간 기준을 확인한 후 변환 adapter를 구현합니다. 서비스 미지원·목적지 미설정·권한 거부·단절을 서로 구분합니다. 허용된 수동 입력은 MANUAL로 표시합니다.
- **여정 규칙:** 같은 여정의 조건/ETA 재검색은 동일 tripId로 새 search를 만듭니다. 목적지 변경·여정 종료·새 여정 생성 정책은 #1/#4에서 결정합니다. 새 ID로 중복 예약 제한을 우회해서는 안 됩니다.
- **데이터:** 현재 Navigation은 검색 JSON에 저장됩니다. 목표 ERD는 trips 소유권과 searches 스냅샷을 분리합니다.
- **오류/인수:** INVALID_TIME, STALE_NAVIGATION, PLEOS_ETA_UNVERIFIED. AT-02와 기기 관측 기록을 충족해야 PLEOS 차단 해제를 검토합니다.

### FR-03 동행 조건과 취향

- **입력:** Party, maxWaitMinutes, requiredFeatures, preferredCuisines, learningConsent.
- **동작:** 아이가 한 명 이상이면 childFriendly를 자동 필수로 적용합니다. 필수 조건의 false는 제외 사유, 미확인/만료는 missing입니다. 취향은 필수 조건을 통과하는 후보 사이의 보조 정렬 요소입니다.
- **출력:** 검색 요청 스냅샷과 후보별 조건 평가. 입력 화면은 아직 없습니다.
- **동의:** learningConsent는 저장만 하며 학습을 수행하지 않습니다. true여도 예약 권한이 되지 않습니다.
- **인수 기준:** AT-03. 선호 음식이어도 필수 조건 불충족 후보는 예약 불가이며, false/0·미확인·미입력을 구분합니다.

### FR-04 장소 검색과 후보 평가

- **시작:** `POST /searches` + Idempotency-Key. 입력은 SearchRequest입니다.
- **현재 출력:** Search.id, mode, request, createdAt과 가상 Candidate 3개. candidateId는 mock-1 등으로 검색 간 반복되므로 searchId와 함께 해석합니다.
- **현재 정렬:** eligible=true 우선 → missing 개수 적은 순 → preferenceScore 내림차순 → candidateId 순. 예약 가능 여부는 조회와 예약 시점에 다시 평가합니다.
- **상태:** 알려진 필수 조건 불충족, 도착 대기 초과, 해당 ETA의 예약 불가이면 eligible=false. 필수 정보가 부족하면 bookable=false. 제외 후보는 이유와 함께 비교 목록에 남습니다.
- **목표 adapter:** 실제 결과 0–3개, 중복 제거·사업자 출처·사용 조건 보존. 조회 실패와 정상 0건을 구분하며 fixture로 조용히 대체하지 않습니다. 에러/페이지 정책은 #2/#4 계약에서 확정합니다.
- **데이터/인수:** searches, places, search_candidates 제안. AT-03/04/05. 현재는 searches.payload에 후보가 포함됩니다.

### FR-05 관측 사실과 유효기간

- **입력/출력:** childFriendly, parking, seatsAvailable, reservationAvailable, nowWaitMinutes, arrivalWaitMinutes를 Fact로 보존합니다.
- **필수 메타데이터:** value, mode, source, evidenceRef, observedAt, appliesAt, validUntil, certainty, scope. 저장 시각을 관측 시각으로 대체하지 않습니다.
- **현재 규칙:** 관측 후 5분까지 유효하며 validUntil과 현재가 같으면 만료입니다. 늦게 받은 오래된 관측으로 더 최신 사실을 덮어쓰지 않습니다. null/누락은 새 정보가 없다는 의미입니다.
- **적용 범위:** childFriendly/parking=GENERAL, seatsAvailable/nowWaitMinutes=NOW, reservationAvailable/arrivalWaitMinutes=ARRIVAL. ARRIVAL 값의 arrivalAppliesAt은 검색 ETA와 정확히 일치해야 합니다. 도착 대기는 ESTIMATED로 표시합니다.
- **구현과 제안:** 현재 후보 fact key별 최신 값만 JSON에 유지합니다. candidate_facts에 관측 이력을 append하는 구조는 새 제안이며 아직 없습니다.
- **인수 기준:** AT-04/05/06. 현재 빈자리만으로 bookable을 참으로 만들지 않고 NOW 대기를 ARRIVAL 대신 사용하지 않습니다.

### FR-06 누락 정보 문의

- **시작:** 사용자가 후보 문의를 선택하고 `POST /searches/{id}/inquiries`에 candidateId를 전송합니다. 제품에서 문의 목적·대상을 보여주는 UI는 #5 작업입니다.
- **전제:** 해당 검색의 후보이고 eligible=true, 검색 ETA가 유효한 미래 시각이어야 합니다. 현재 서버는 missing이 0개라는 이유만으로 문의를 거부하지 않습니다.
- **중복 제한:** 같은 search/candidate의 INQUIRY가 PENDING이면 차단합니다. 최근 요청 후 10분 미만이면 INQUIRY_COOLDOWN입니다. 실제 장소 단위의 재검색 간 횟수 제한은 #3/#4의 후속 설계입니다.
- **출력:** purpose=INQUIRY, status=PENDING인 CallTask. 현재는 모의 작업이며 전화하지 않습니다.
- **결과:** ANSWERED에서만 facts를 반영합니다. ACCEPTED·명시적 수락·예약 시각·비용을 문의 결과에 넣으면 거부합니다. REJECTED/UNKNOWN은 확인된 사실을 만들지 않습니다.
- **재시도/인수:** 자동 재발신은 없습니다. 현재 문의 UNKNOWN은 10분 후 별도의 명시적 요청이 가능하지만 예약/변경 UNKNOWN은 계속 차단됩니다. AT-06/07을 충족합니다.

### FR-07 예약 요청

- **입력:** candidateId, requestedTime, allowBooking=true, changePolicy. UI는 매장·인원·시각·변경 허용 범위를 승인 전에 보여줘야 합니다.
- **전제:** candidate.bookable=true, requestedTime=검색 ETA, 시각 유효, 정책 enabled이면 latestTime이 요청 시각 이상이어야 합니다.
- **중복 차단:** 동일 tripId에 PENDING/CONFIRMED/UNKNOWN 예약이 있으면 ACTIVE_RESERVATION입니다. REJECTED 이력만 있다면 새 키로 명시적 재요청이 가능합니다. 후보 사실과 ETA 조건도 다시 통과해야 합니다.
- **출력:** status=PENDING인 Reservation, originalRequestedTime, bookingCallId. CallTask 목적은 BOOKING이고 요청 추가 비용은 현재 0원입니다.
- **저장:** 예약·통화·명령 응답을 한 DB 트랜잭션에서 기록합니다. 현재 통화를 먼저 저장하므로 정규화 FK 적용 시 저장 순서를 변경해야 합니다.
- **인수 기준:** AT-08/09/10. HTTP 200은 요청 접수이지 매장 수락이 아닙니다. allowBooking boolean은 현재 로컬 모의 승인 값이며 인증 증빙이 아닙니다.

### FR-08 결과 검증과 예약 확정

- **현재 입력:** `POST /mock/calls/{id}/outcomes`의 OutcomeRequest. 운영 provider webhook이 아닙니다.
- **시간 검사:** observedAt은 CallTask.createdAt보다 이르면 안 되고 서버 현재보다 5초 초과 미래여도 안 됩니다. 유효한 모의 결과가 늦게 도착했다고 관측 시각을 바꾸지 않습니다.
- **수락:** outcome=ACCEPTED + explicitAcceptance=true + acceptedTime=requestedTime + 비용 일치, 네 조건이 모두 참이어야 합니다.
- **거절:** outcome=REJECTED + explicitAcceptance=false + acceptedTime=null + 추가 비용 0인 모순 없는 결과입니다. 그 밖의 ANSWERED·조건부 수락·시간/비용 불일치는 UNKNOWN입니다.
- **제약:** BOOKING/CHANGE outcome에는 facts를 함께 넣지 않습니다. 종료 통화는 다른 새 이벤트로 덮어쓸 수 없습니다. 동일 이벤트의 동일 재전송은 별도의 멱등 처리입니다.
- **인수 기준:** AT-08/09/12/13. 유효하지 않은 결과는 이벤트 저장·업무 상태 모두 롤백합니다. UI는 판정된 상태를 표시하고 원문만 보고 성공으로 바꾸지 않습니다.

### FR-09 조회와 앱 복귀

- **현재 API:** GET 검색, 예약, 통화 단건 및 검색별 통화 목록. 검색 GET은 만료를 재평가합니다.
- **목표 앱 동작:** 생성된 searchId/reservationId/callId와 미완료 명령 키를 최소한으로 보존하고 복귀 시 GET으로 최신 상태를 확인합니다. 재전송 응답이 과거 PENDING 스냅샷일 수 있으므로 최신 화면은 GET 기준입니다.
- **실패:** ID를 잃었다면 새 예약으로 복구하지 않습니다. 현재 인증 사용자별 최근 여정/예약 목록 API는 없으므로 복구 조회 방식은 #4/#5가 추가 설계합니다.
- **표시:** confirmedTime이 없으면 확정 시각을 만들어 표시하지 않습니다. 요청 시각과 확인된 시각을 구분합니다.
- **인수 기준:** AT-10/14. 서버 재시작 후 상태 조회와 앱 재진입 UX는 별도의 시험입니다. 서버 H2 재시작 성공만으로 앱 복귀 구현 완료를 주장하지 않습니다.

### FR-10 예약 변경과 추가 비용

- **시작/입력:** `POST /reservations/{id}/changes`, requestedTime, additionalCostKrw, approveAdditionalCost.
- **전제:** 예약 CONFIRMED, pendingChangeCallId=null, 정책 enabled, 요청 시각이 기존 confirmedTime보다 늦고 latestTime 이하, 누적 비용 상한 이내, 현재 이후 24시간 이내입니다.
- **추가 비용:** additionalCostKrw > 0이면 별도 approveAdditionalCost=true가 필요합니다. 정책 예산이 남았다고 현재 요청 금액에 대한 승인까지 대신하지 않습니다.
- **간격:** 최근 변경 요청 후 최소 10분. 거절된 요청도 간격에 포함합니다. 미해결 변경이 있으면 시간이 지나도 차단됩니다.
- **대기 중 출력:** 기존 status=CONFIRMED와 confirmedTime을 유지하고 pendingChangeCallId와 changeCallIds를 추가합니다. originalRequestedTime은 바꾸지 않습니다.
- **결과:** 수락 조건 일치 시 confirmedTime·누적 비용을 갱신하고 pending 참조를 해제합니다. 거절은 기존 시각 유지·참조 해제, UNKNOWN은 기존 시각·미해결 참조 모두 유지합니다.
- **정책 밖 요청:** OUTSIDE_CHANGE_POLICY. 현재 정책을 수정/재승인하는 API는 없으므로 UI에서 “승인했다”고 기존 경계를 우회하면 안 됩니다.
- **인수 기준:** AT-11/12. 자동 지연 감지·자동 통화는 이 기능의 현재 구현에 포함되지 않습니다.

### FR-11 멱등성과 동시성

- **명령 키:** 결과 주입을 제외한 POST에 Idempotency-Key 필수. `[A-Za-z0-9._:-]` 1–100자입니다.
- **현재 범위:** scope+key가 동일하고 본문도 같으면 최초 응답 스냅샷을 반환합니다. 본문이 다르면 KEY_REUSED. 재시도할 때 새 키를 만들어 같은 작업을 반복하지 않습니다.
- **이벤트:** eventId는 전체 통화에서 고유합니다. 같은 ID+같은 call/본문이면 상태를 재적용하지 않고 현재 CallTask를 반환합니다. 다른 내용/통화는 EVENT_REUSED입니다.
- **현재 잠금:** 명령·결과를 DB 전역 command_mutex와 트랜잭션으로 직렬화합니다. 다중 사용자/여러 서버 운영의 완성된 설계가 아닙니다.
- **목표:** 인증/소유권 검증 후 actor_id+mode+scope+key로 범위를 나눕니다. 여정/예약 단위 잠금과 DB 제약을 함께 검증한 후 전역 lock 전환 여부를 정합니다.
- **인수 기준:** AT-10/12/13/15. 다른 키를 동시에 보내도 활성 예약은 1개이며 중복 결과로 누적 비용이 두 번 증가하지 않아야 합니다.

### FR-12 인증·소유권·승인 증빙

- **현재 상태:** 인증·사용자 격리 미구현. API는 loopback의 단일 사용자 개발용입니다.
- **목표:** 신뢰할 수 있는 인증 주체 → users → trips → searches/candidates → reservations/calls 경로의 소유권을 서버에서 확인합니다. GET, 명령, cached response 모두 검사합니다.
- **승인 기록:** 예약의 대상·인원·시각·변경 정책·승인자·승인 시각을 남깁니다. 변경은 적용 정책/명시 승인 근거를 남기고 양수 비용은 그 금액에 대한 별도 증빙을 요구합니다.
- **비동의:** learningConsent=false여도 직접 입력한 조건의 검색은 가능해야 합니다. 학습 동의 true는 예약·변경·전화 비용·개인정보의 무제한 사용 권한이 아닙니다.
- **미정:** 인증 제공자·토큰 방식·세션 만료·인증 오류 HTTP 계약·승인 증빙 형태. 신규 URL이나 401/403 계약이 이미 있다고 가정하지 않습니다.
- **인수 기준:** AT-15. 사용자 A가 B의 객체/명령 키를 알아도 데이터 열람·변경·응답 재생이 차단되어야 합니다.

### FR-13 UNKNOWN 대조와 수동 해결

- **현재 동작:** 예약 UNKNOWN은 신규 예약을 막습니다. 변경 UNKNOWN은 기존 예약 정보와 pendingChangeCallId를 유지합니다. 수동 해결 API는 없습니다.
- **목표 절차:** 확인 필요 건 조회 → 권한 있는 담당자의 매장/provider 대조 → 증거·실제 수락 시각·비용 기록 → 명시적 해결 명령 → 트랜잭션 재검증 → 사용자 안내. 재조회/읽기는 자동 발신 허가가 아닙니다.
- **보존:** 요청 원본·종료 통화·원 이벤트를 수정하지 않고 별도 해결 기록을 남깁니다. 상태 변화·누적 비용 반영도 멱등해야 합니다.
- **안내:** 변경 미확인 화면은 “기존 예약 정보 유지 · 변경 결과 미확인, 매장 대조 필요”. 기존 시각의 예약이 여전히 유효하다고 다시 확인한 뜻은 아닙니다.
- **미정/인수:** 운영자 권한·해결 상태·감사 테이블·API·알림 방식은 #4/#3 설계 대상입니다. 11개 ERD만으로 감사 기능이 완성되지 않습니다. AT-16.

### FR-14 실제 전화·webhook·외부 실패

- **진입 조건:** 사용자/여정 소유권·승인 증빙, 허용 수신 대상, 목적별 횟수/비용 상한, kill switch, 전화 사업자 계약, 결과 정규화·서명 검증이 준비되어야 합니다.
- **실행 승인:** 실제 발신·유료 API·번호 구매 각각 대상·목적·예상 비용·상한을 명시해 사용자 승인을 받습니다. 기능 명세 작성이나 SDK 설치 동의는 발신 승인이 아닙니다.
- **발신 입력 보강:** 현재 CallTask에는 수신번호와 동행 인원이 직접 포함되지 않습니다. searchId의 요청 스냅샷과 candidateId의 장소 참조를 통해 승인 당시 인원·대상·연락처를 일관되게 조립하는 adapter 입력 계약이 필요합니다.
- **전송 목표:** DB에 작업과 outbox 기록 → 발송 worker가 안정적인 전송 식별자로 처리 → provider 결과 대조. 발신 응답이 끊기면 새 통화를 바로 만들지 않습니다.
- **수신 목표:** 서명·허용 시각·계정 범위 확인 → 내부 call/eventId 매핑 → 중복 확인 → FR-08 상태 판정. 인증 실패한 이벤트가 업무 데이터를 갱신하면 안 됩니다.
- **미정:** 사업자별 실제 API·서명 형식·요금·재시도 규칙·inbox/outbox 스키마·timeout 기준. 현재 MockCallAdapter에 이 기능이 없습니다.
- **인수 기준:** AT-17. 서명 실패·이벤트 중복·발송 중 단절·timeout 대조·kill switch 시나리오를 사업자 sandbox/승인된 PoC와 구분해 기록합니다.

### FR-15 차량 화면과 주행 제한

- **화면:** 조건 입력, 후보 비교, 문의 진행, 예약 승인, 예약 결과, 변경 승인, 변경 결과 미확인, 가용성/통신 오류.
- **후보 표시:** 음식 종류, 필수 조건, NOW/ARRIVAL 대기, 출처·확인 시각·미확인/만료, 제외 사유, MOCK 표시. 필수 조건 실패를 취향 추천 문구로 덮지 않습니다.
- **조작:** bookable=false면 예약 차단, 중복 명령 중에는 같은 작업을 반복 생성하지 않음, 예약·비용 승인은 독립된 확인 동작.
- **주행 제한:** CarUxRestrictions 초기 상태와 변경을 구독해 실제 허용 UX를 적용합니다. 조회 실패 시 복잡한 입력/확정 조작을 보류하는 정책은 프로젝트 목표이며 플랫폼 검증 완료가 아닙니다.
- **표현:** 운전 중 허용 동작·수동 입력 범위·음성 안내는 #1/#5의 기기 증거와 공식 플랫폼 규칙 확인 후 확정합니다. Gleo/STT/TTS는 현재 없습니다.
- **인수 기준:** AT-18. 정차/주행 제한/상태 미확인/단절/복귀에서 동작을 확인하고 debug probe를 제품 화면으로 제출하지 않습니다.

### FR-16 DB와 팀 개발 환경

- **현재:** H2 파일 DB, Flyway V1/V2, JSON payload, command_mutex. searches/reservations/calls/commands/events/command_mutex의 6개 테이블입니다.
- **목표:** [ERD](ERD.md)의 11개 논리 테이블을 검토한 뒤 DB 종류·물리 타입·인덱스·제약을 확정합니다. 기존 FK인 events.call_id → calls.id는 유지합니다.
- **협업:** 각자의 로컬 DB를 사용하고 migration·가상 seed·버전만 Git으로 공유합니다. 적용 이력이 있는 migration은 수정하지 않고 다음 버전을 추가합니다. 공용 DB 접속 정보·바이너리·실제 개인정보를 Git에 넣지 않습니다.
- **전환:** 기존 candidateId를 searchId와 함께 새 ID로 매핑하고 client tripId를 내부 trip ID로 연결합니다. JSON 응답은 API 계약을 유지하도록 조립합니다. command_mutex 제거는 동시성 검증 이후 결정합니다.
- **복구/인수:** 신규 DB 생성·migration·가상 seed·재시작·기존 데이터 이관 정책과 실패 복구 절차를 재현합니다. H2 통과만으로 선택한 운영 DB 호환을 단정하지 않습니다. AT-14/19.

### FR-17 시연과 산출물

- **현재 증거:** 2026-11-04 STATUS에 서버 테스트 25개·MOCK HTTP/계약·H2 재시작 통과, probe debug APK·Lint 통과가 기록되어 있습니다. Android 단위 테스트는 NO-SOURCE였고 기기 실행은 미검증입니다.
- **목표:** 조건 → 후보 → 문의 → 예약 요청 → 수락/거절/UNKNOWN → 변경 미확인을 재현하고 모의/실제 기능을 구분한 영상을 만듭니다.
- **완료 구분:** 소스 작성, 빌드, 테스트, APK 설치/기기 실행, 실전화, release 서명, 영상, 공모전 제출을 각각 기록합니다. 한 단계 성공으로 다른 단계를 완료로 표시하지 않습니다.
- **인수 기준:** AT-20. 다른 팀원이 명시된 환경과 가상 데이터로 재현하고, 링크·APK·시연의 실제 존재와 검증 수준이 일치해야 합니다.

## 5. 상태 전이와 화면 문구

### 예약

| 현재 상태/사건 | 결과 | 화면 의미 | 다음 허용 동작 |
| --- | --- | --- | --- |
| 예약 요청 접수 | PENDING, confirmedTime 없음 | 예약 요청 중, 아직 확정 아님 | GET으로 확인, 중복 예약 차단 |
| PENDING + 정확한 명시적 수락 | CONFIRMED, confirmedTime 기록 | 모의 매장 수락 확인. 현재 제품은 MOCK 표시 유지 | 정책 범위 내 변경 요청 |
| PENDING + 모순 없는 거절 | REJECTED | 예약 거절 | 조건을 다시 확인한 명시적 새 요청 |
| PENDING + ANSWERED/불명확/시간·비용 불일치 | UNKNOWN | 예약 결과 미확인 | 대조 필요, 새 예약 발신 차단 |
| UNKNOWN + 뒤늦은 새 outcome | 현재 API에서는 CALL_FINALIZED | 결과 덮어쓰기 불가 | 미구현인 별도 해결 절차 설계 |

### 변경

| 변경 작업 결과 | Reservation.status | confirmedTime | pendingChangeCallId | 비용/안내 |
| --- | --- | --- | --- | --- |
| 요청 직후 PENDING | CONFIRMED 유지 | 기존 값 유지 | 유지 | 추가 비용 미반영, 변경 요청 중 |
| 정확한 ACCEPTED | CONFIRMED | 수락된 새 시각 | 해제 | 수락 추가 비용 1회 누적 |
| 명확한 REJECTED | CONFIRMED | 기존 값 유지 | 해제 | 비용 미반영, 변경 거절 |
| UNKNOWN/불일치 | CONFIRMED | 기존 값 유지 | 유지 | 비용 미반영, 변경 결과 미확인·매장 대조 필요 |

originalRequestedTime은 변경 결과와 관계없이 최초 예약 요청 시각입니다. 현재 예약에는 별도 CHANGING 상태가 없습니다. 제안 ERD의 reservation_changes.status는 별도 변경 기록이며 현재 API에 이 테이블이 있다는 뜻은 아닙니다.

## 6. 현재 API 대응표

기본 URL: `http://127.0.0.1:8080/api/v1`. 아래 경로는 기본 URL 뒤에 붙입니다. **모두 현재 존재하는 경로**이며 인증/수동 해결/운영 webhook은 이 목록에 없습니다. 성공 응답은 모두 HTTP 200입니다.

| 메서드 | 경로 | 요청/응답 | 기능 | 관련 이슈 |
| --- | --- | --- | --- | --- |
| GET | `/status` | 실행 모드·계약·가용성 | FR-01 | #4, #5 |
| POST | `/searches` | SearchRequest → Search | FR-02/03/04/11 | #1, #2, #4, #5 |
| GET | `/searches/{id}` | Search, 조회 시 사실 만료 재평가 | FR-04/05/09 | #2, #4, #5 |
| POST | `/searches/{id}/inquiries` | InquiryRequest → CallTask | FR-06/11 | #3, #4, #5 |
| GET | `/searches/{id}/calls` | CallTask 목록 | FR-09 | #3, #4, #5 |
| POST | `/searches/{id}/reservations` | BookingRequest → Reservation | FR-07/11 | #3, #4, #5 |
| GET | `/reservations/{id}` | Reservation | FR-09/10 | #4, #5 |
| POST | `/reservations/{id}/changes` | ChangeRequest → 기존 정보와 변경 참조를 포함한 Reservation | FR-10/11 | #3, #4, #5 |
| GET | `/calls/{id}` | CallTask | FR-09 | #3, #4, #5 |
| POST | `/mock/calls/{id}/outcomes` | OutcomeRequest → CallTask | FR-05/08/11 | #3, #4 |

전체 required/null/enum/type 정의는 OpenAPI를 기준으로 합니다. 향후 계약 변경은 OpenAPI·예제·호출자·테스트를 함께 수정하고, 호환성을 깨면 버전 정책을 따릅니다. 목표 ERD에 테이블이 생긴다고 임의의 CRUD API를 모두 공개하지 않습니다.

## 7. 오류와 사용자 동작

아래는 현재 코드의 오류 코드입니다. 인증 실패나 provider 오류의 신규 코드는 추후 계약 대상입니다.

| HTTP | code | 조건 | 사용자/호출자 처리 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | 필수 필드·형식·헤더 검증 실패 | 입력 수정. 권한 boolean 누락/false도 요청 검증에서 거부될 수 있음 |
| 400 | INVALID_KEY | 키 형식/길이 위반 | 명령 단위의 올바른 키 생성 |
| 400 | INVALID_TIME | 현재 이전/현재 또는 24시간 초과 | 현재 기준 유효한 시각 재선택 |
| 400 | STALE_NAVIGATION | 관측이 오래되거나 허용 미래 오차 초과 | 내비 상태 재수집 |
| 400 | PLEOS_ETA_UNVERIFIED | 검증 전 PLEOS 입력 | 검증된 경로 사용, 임의 변환 금지 |
| 400 | INVALID_CANDIDATE | 검색에 속하지 않는 후보 | 해당 검색 결과 다시 조회 |
| 400 | BOOKING_PERMISSION_REQUIRED | 서비스에서 예약 권한 없음 | 명시적 예약 승인. HTTP 경계에서는 INVALID_REQUEST가 먼저 발생할 수 있음 |
| 400 | INVALID_CHANGE_POLICY | 활성 정책의 종료 시각 없음/너무 빠름 | 승인 전 정책 수정 |
| 400 | INVALID_OBSERVATION | 통화 생성 전/허용 미래 오차 초과 관측 | 원 관측 근거 확인, 현재 시각으로 조작하지 않음 |
| 400 | INVALID_INQUIRY_OUTCOME | 문의에 예약 수락·비용 등을 혼합 | 목적에 맞는 정규화 결과로 수정 |
| 400 | FACTS_WITHOUT_ANSWER | 문의 응답 확인 없이 사실 제공 | 확인된 사실만 전송 |
| 400 | UNEXPECTED_FACTS | 예약/변경 결과에 문의 사실 혼합 | 결과 목적 분리 |
| 400 | ARRIVAL_TIME_MISMATCH | 사실 적용 ETA와 검색 ETA 불일치 | 해당 ETA에 대한 재검색/문의 |
| 404 | NOT_FOUND | 검색/예약/통화 없음 | ID·조회 문맥 확인, 새 예약으로 복구하지 않음 |
| 409 | INELIGIBLE_CANDIDATE | 알려진 필수 조건 불충족 후보 문의 | 제외 사유 안내 |
| 409 | CANDIDATE_NOT_READY | 미확인/만료/불충족 후보 예약 | 상태 조회·조건 확인 후 명시적 진행 |
| 409 | BOOKING_TIME_MISMATCH | 검색 ETA와 예약 요청 시각 불일치 | 새 조건으로 재검색/문의 |
| 409 | ACTIVE_RESERVATION | 같은 여정에 활성 예약 존재 | 기존 예약 조회, 새 키로 우회 금지 |
| 409 | INQUIRY_COOLDOWN | 문의 진행 중/10분 이내 | 기존 작업 조회 또는 허용 시각까지 대기 |
| 409 | CHANGE_BLOCKED | 미확정 예약/미해결 변경 | 기존 상태 조회·미확인 결과 대조 |
| 409 | OUTSIDE_CHANGE_POLICY | 시각/누적 비용/정책 범위 밖 | 현재 API에서 진행 불가. 정책 재승인 계약은 후속 |
| 409 | COST_APPROVAL_REQUIRED | 양수 추가 비용 별도 승인 없음 | 금액 확인 후 명시적 승인 |
| 409 | CHANGE_COOLDOWN | 변경 요청 후 10분 미만 | 기존 상태 유지, 새 키로 간격 우회 금지 |
| 409 | KEY_REUSED | 같은 키에 다른 요청 | 동일 명령은 동일 키/본문, 새 의도만 새 키 |
| 409 | EVENT_REUSED | 같은 eventId에 다른 통화/본문 | provider 매핑·정규화 오류 확인 |
| 409 | CALL_FINALIZED | 종료 통화를 새 이벤트로 덮어쓰기 | 별도 대조/해결 절차 필요 |

오류 재시도는 업무 상태를 먼저 확인합니다. UNKNOWN을 오류 코드 하나로 단순 치환하거나 네트워크 timeout을 예약 거절로 처리하지 않습니다.

## 8. 데이터 모델과 불변 조건

| 데이터 경계 | 목표 ERD | 현재 저장 형태 | 연결 기능 |
| --- | --- | --- | --- |
| 사용자·여정 | users, trips | users/trips 테이블 없음. navigation 및 reservations.trip_id 문자열 | FR-02/12 |
| 검색과 장소 | searches, places, search_candidates | searches.payload에 요청과 후보 포함 | FR-03/04 |
| 관측 정보 | candidate_facts | 후보 facts map의 현재 값 | FR-05/06 |
| 예약/변경 | reservations, reservation_changes | reservations.payload와 통화 ID 이력 | FR-07/08/10/13 |
| 통화 결과 | calls, events | 각 payload, events.call_id FK 선언 있음 | FR-06/08/14 |
| 명령 중복 | commands | scope+idempotency_key PK와 최초 응답 | FR-11/12 |
| 현재 공통 잠금 | command_mutex | 1개 행의 FOR UPDATE | FR-11/16 |

팀 합의 및 구현 시 유지할 조건:

1. 활성 예약은 같은 여정에 PENDING/CONFIRMED/UNKNOWN 합쳐서 최대 1개입니다. `UNIQUE(trip_id,status)`만으로는 이 조건을 보장하지 못합니다.
2. 예약당 미해결 변경은 PENDING/UNKNOWN 합쳐서 최대 1개입니다. UNKNOWN은 해결 완료로 기록하지 않습니다.
3. 사용자·여정·검색·후보·예약·변경·통화가 같은 소유권 경로와 mode를 가져야 합니다. FK 두 개가 있다고 소속 일치가 자동 보장되는 것은 아닙니다.
4. original_requested_time, 검색 입력 스냅샷, 승인 당시 정책, 최초 명령 응답은 해당 기록의 원래 값을 보존합니다.
5. 예약·변경 상태와 수락 추가 비용은 같은 트랜잭션에서 갱신합니다. 외부 발신까지 그 트랜잭션이 원자적으로 보장하지는 않습니다.
6. 모의 데이터와 실제 데이터, 관측 시각과 저장 시각, 현재 대기와 도착 대기를 섞지 않습니다.
7. 현재 V1의 searches→reservations, searches→calls, calls→events FK를 이관 과정에서 보존/재검증합니다. JSON 내부 참조의 정규화는 별도 작업입니다.

컬럼의 PK/FK/nullable/UNIQUE 및 migration 순서는 [ERD 상세](ERD.md)를 따릅니다. auth·outbox·inbox·운영자 해결 감사·보관/삭제 정책은 논리 초안에 추가 설계가 필요합니다.

## 9. 인수 테스트 목록

상태의 “기존 테스트”는 현재 소스/2026-11-04 실행 기록에 근거합니다. “신규”는 작성·실행해야 할 기준입니다. 이 표의 20개 시나리오 수와 기존 자동 테스트 25개는 서로 다른 분류입니다.

| ID | 시나리오 | 기대 결과 | 연결 기능/이슈 | 검증 수준 |
| --- | --- | --- | --- | --- |
| AT-01 | MOCK 상태 조회, LIVE로 시작 | 모의 데이터·발신 차단 표시, LIVE 시작 실패 | FR-01 / #4, #5 | 서버 기존, UI 신규 |
| AT-02 | 오래된 내비 관측, 현재/24시간 초과 ETA, 미검증 PLEOS, 기기 시간 기준 | 입력 거부 또는 증거 기반 변환, 임의 ETA 생성 없음 | FR-02 / #1, #4 | 서버 경계 코드 확인, 상세 경계/기기 시험 보강 |
| AT-03 | 아이 동행+선호 음식 후보의 아동 불가/미확인 | 선호보다 필수 조건 우선, false와 unknown 구분 | FR-03/04 / #2, #5 | 기존 테스트, UI 신규 |
| AT-04 | 현재 빈자리=true, 예약 불가 또는 NOW 대기만 존재 | 예약 불가/미확인 유지, 확정으로 해석하지 않음 | FR-04/05 / #2, #3 | 기존 테스트 |
| AT-05 | TTL 경계, 늦은 관측, 조회 후 예약까지 시간 경과 | 만료 재평가, 오래된 관측 갱신 금지, 예약 차단 | FR-04/05 / #2, #4 | 기존 만료/지연 테스트, 정확한 경계 보강 |
| AT-06 | 문의 ANSWERED/REJECTED/UNKNOWN, 다른 ETA 사실 | 응답된 사실만 반영, ETA 불일치 거부 | FR-05/06 / #2, #3, #4 | 기존 관련 검증, 전체 조합 보강 |
| AT-07 | 문의 PENDING/10분 미만/정확히 10분 후 | 진행 중 차단, 간격 검사, 허용된 명시 요청만 생성 | FR-06 / #3, #4 | 경계 시나리오 보강 |
| AT-08 | 예약 승인 없음/학습 동의만 있음/시각 불일치/정확한 수락 | 승인·시각 검사, 네 조건 일치 때만 확정 | FR-07/08 / #3, #4, #5 | 기존 테스트 |
| AT-09 | ANSWERED·암묵 수락·시간/비용 불일치·거절 | UNKNOWN 또는 명확한 REJECTED, 성공 오표시 없음 | FR-07/08 / #3, #4 | 기존 테스트 |
| AT-10 | 같은 키 동시 재시도, 다른 키 같은 여정, 재검색 후 중복 예약 | 한 예약/통화만 생성, 같은 키 최초 응답 유지 | FR-07/09/11 / #4, #5 | 기존 테스트, 앱 복귀 신규 |
| AT-11 | 변경 대기·수락·거절·UNKNOWN | 원 요청 보존, 수락 전 기존 확정값 유지, UNKNOWN 재발신 차단 | FR-10 / #3, #4, #5 | 기존 테스트, UI 신규 |
| AT-12 | 비용 미승인·누적 상한 초과·중복 수락 이벤트 | 비용 승인 검사, 상한 유지, 비용 1회 누적 | FR-08/10/11 / #3, #4 | 기존 테스트 |
| AT-13 | eventId 변조/다른 call 재사용/잘못된 결과/동시 중복 | 충돌 또는 전체 롤백, 동일 재전송 무해 | FR-08/11 / #3, #4 | 기존 테스트 |
| AT-14 | 서버 종료·재시작, 앱 강제 종료·재진입 | 같은 예약/미해결 변경 조회, 복구로 새 예약 생성 없음 | FR-09/16 / #4, #5 | H2 재시작 기존, 앱 복귀 신규 |
| AT-15 | A 사용자가 B의 ID/동일 멱등 키 접근 | 조회·변경·cached response 유출 모두 차단 | FR-11/12 / #4 | 신규 |
| AT-16 | UNKNOWN을 대조로 해결, 해결 명령 재전송 | 증거·권한·감사 이력 보존, 원 이벤트 불변, 1회 상태 반영 | FR-13 / #3, #4, #5 | 신규, API 미구현 |
| AT-17 | 서명 오류·provider 중복·발송 응답 단절·timeout·kill switch | 미검증 이벤트 차단, 자동 중복 발신 없음, 대조 가능 | FR-14 / #3, #4 | 신규, 실실행 별도 승인 |
| AT-18 | 정차/주행 제한/초기 상태 없음/제한 조회 실패/서비스 단절 | 허용 동작만 제공, 미확인 상태에서 복잡한 조작 보류 | FR-15 / #1, #5 | 신규, 기기 검증 필요 |
| AT-19 | 새 개발 DB·migration·seed·동시 예약·이관 후 FK | 같은 스키마 재현, 고아 참조/활성 예약 중복 없음 | FR-16 / #2, #3, #4 | 현재 H2 일부 기존, 선택 DB 신규 |
| AT-20 | 다른 팀원 재현, release 설치, 영상·자료 대조 | 모의/실제/미검증 범위와 산출물 설명 일치 | FR-17 / #1–5 | CLI/빌드 기록, release·영상 신규 |

현재 자동 테스트 근거: [WorkflowTest](../server/src/test/java/ai/pleos/assistant/WorkflowTest.java). 예를 들어 fullFlowRequiresExplicitMatchingAcceptance, unknownChangeKeepsBothTimesAndBlocksRetry, concurrentDifferentKeysStillCreateOneBooking, cumulativeChangeCostCannotExceedPolicy가 핵심 시나리오를 다룹니다. 테스트 이름이 있다고 모든 기기·provider·사용자 조합까지 검증됐다고 간주하지 않습니다.

관련 구현 변경 후 실행할 기본 명령:

```bash
./gradlew verify
python3 scripts/smoke-test.py
# 개발 의존성을 설치한 Python 환경에서 계약까지 확인
python3 scripts/smoke-test.py --check-contract
# Android 변경이 있고 SDK가 준비된 경우
./gradlew -PincludeAndroid=true verifyAndroid
```

문서만 바뀐 이번 작업은 API/오류 코드/이슈/링크의 정적 대조로 검증합니다. 실제 발신이나 유료 서비스 호출은 위 로컬 검사에 포함하지 않습니다.

## 10. 미결정 사항과 다음 작업

| 결정 | 담당 이슈 | 결정 전 가능한 작업 | 결정/증거가 필요한 시점 |
| --- | --- | --- | --- |
| ETA 시간 기준·여정 변경/종료 | #1, #4 | raw probe·MOCK UI | PLEOS 입력 허용·중복 예약 경계 확정 전 |
| 지역·장소 사업자·저장 조건 | #2 | adapter 인터페이스·fixture 테스트 | 실제 장소 연결/캐시 전 |
| 전화 사업자·번호·요금·allowlist | #3, #4 | 대화/normalized outcome fixture | 실발신·유료 호출 전 |
| 인증·승인 증빙·운영자 권한 | #4 | 소유권 모델·실패 테스트 설계 | 다중 사용자/실예약 도입 전 |
| PostgreSQL/MySQL 등 DB 종류·개발/통합 환경 | #4, #2, #3 | 논리 ERD·데이터 경계 검토 | migration·운영 DB 통합 테스트 전 |
| UNKNOWN 해결·outbox/inbox·timeout | #4, #3 | 상태/이벤트 계약 설계 | 실전화 외부 실패 처리 전 |
| 예약 정책 재승인·최근 예약 복구 API | #4, #5 | 현재 API UI·제약 안내 | 정책 밖 변경/사용자 세션 복구 제공 전 |
| 주행 UX·기기·제품 모듈·release 서명 | #5, #1 | MOCK 화면 설계·API 호출자 | 기기 사용/배포 산출물 검증 전 |
| 보관·삭제·동의 철회·민감 로그 정책 | #4, #3 | 가상 데이터 개발 | 실제 개인정보/녹음 수집 전 |

후속 기능의 후보는 지속 ETA 지연 감지, 승인 범위 내 자동 변경, 개인화 학습입니다. 5분 지연·2분 지속 같은 값은 PLAN의 **실험 초기값**이며 현행 확정 기능/동작이 아닙니다. 해당 기능의 별도 GitHub 이슈는 아직 없으므로 가상 번호를 부여하지 않습니다.

기능별 완료는 구현·관련 테스트·OpenAPI 일치·STATUS 갱신까지이고, 실제 연동 완료는 추가로 기기/provider 증거가 필요합니다. 명세 확정·ERD 확정·코드 구현·기기 검증을 각각 관리합니다.

다중 방문 예약, 도착 후 주차·도보를 포함한 비교, 지연 감지와 변경 제안의 MVP 승격은 [설계 제안](DESIGN_PROPOSALS.md)입니다. 이 명세의 현행 예약 제한과 기능 우선순위를 변경하려면 별도 합의와 계약/구현 변경이 필요합니다.

## 11. 근거와 문서 변경 기록

- [GitHub #1](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1), [#2](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/2), [#3](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/3), [#4](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/4), [#5](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/5), [#6](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/6): 실제 목적·완료 조건·상태. 2026-11-07 게시 준비 시 상태와 미배정 여부를 GitHub API로 재확인했습니다.
- [PLAN](PLAN.md): 제품 범위와 역할. [STATUS](STATUS.md): 2026-11-04 실행 기록. [API README](../api/README.md)와 [OpenAPI](../api/openapi.json): 현재 계약.
- [Model](../server/src/main/java/ai/pleos/assistant/Model.java), [WorkflowService](../server/src/main/java/ai/pleos/assistant/WorkflowService.java), [RecommendationEngine](../server/src/main/java/ai/pleos/assistant/RecommendationEngine.java), [Store](../server/src/main/java/ai/pleos/assistant/Store.java): 현재 입력·상태·평가·저장 규칙.
- [Flyway V1](../server/src/main/resources/db/migration/V1__workflow.sql), [V2](../server/src/main/resources/db/migration/V2__reservation_trip.sql): 물리 테이블과 FK. [Android README](../android/README.md): probe와 기기 검증 범위.
- 2026-11-06 v0.1: 이슈와 기능·API·DB·인수 기준을 연결했습니다. 기존 ERD의 “events.call_id FK 없음” 설명은 V1의 실제 REFERENCES calls(id)에 맞게 수정했습니다. 소스·OpenAPI·DB 실행 동작은 변경하지 않았습니다.

- 2026-11-07 공유본: GitHub에서 읽을 수 있도록 문서 경로와 목차를 정리하고, ERDCloud 검증 결과·병행 작업·미확정 제안으로 연결했습니다.

이 명세와 해설서는 `docs/`에서 리뷰·버전 관리합니다. 현재 동작은 OpenAPI와 소스를, 마지막 실행 증거는 STATUS를 기준으로 합니다. 목표 기능·제안 ERD는 별도 구현과 검증이 필요합니다.
