# Pleos 예약 비서 이슈별 상세 해설

**최신 범위 조정 (2026-11-07):** #12/PR #14는 내부 세션·Registry 기반, #13/PR #15는 독립 시연·첫 실제 검색 검증을 종료 대상으로 정리했습니다. 미완료 제품 연결은 #16, 수명/운영 관리는 #17, 지역 품질/IP는 #18에 이관했습니다. [후속 이슈별 상세와 담당](REC_FOLLOWUPS.md)을 우선하며 #11 정책 검토와 기존 #1/#3/#4/#5 역할을 유지합니다. 아래 OPEN/Draft는 작성 당시 이력입니다.

소스 대조: 2026-11-06 · 공유본 갱신: 2026-11-07 KST. 대상: 5명 개발팀. 초기 공유 시 GitHub #1–5는 OPEN·미배정, #6은 CLOSED였으며 소스 `f4f98732e243ddd7b806c3f91d0cef03e001aaec`를 기준으로 정리했습니다. 이후 #2는 OPEN·`tiemo0708` 배정으로 재확인했습니다. 다른 이슈의 최신 배정은 GitHub에서 확인합니다. 아래 세부 작업과 역할 분리는 이슈가 완료되었다는 뜻이 아닙니다.

[기능 명세서](FUNCTIONAL_SPEC.md) · [ERD와 컬럼 정의](ERD.md) · [ERD 관계 개요](ERD.md#2-핵심-관계) · [GitHub 이슈 목록](https://github.com/arriveon-lab/pleos-reservation-assistant/issues)

이 문서와 기능 명세서는 `docs/`에서 리뷰·버전 관리하는 팀 검토본입니다. 이슈의 담당자·상태는 GitHub를 기준으로 하며, 이 문서 게시가 기능 이슈의 완료를 뜻하지 않습니다.

## 1. 번호와 담당 범위

| GitHub 이슈 | 계획 코드 | 담당 역할 | 핵심 질문 | DB 관여 | 현재 상태 |
| --- | --- | --- | --- | --- | --- |
| [#1](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1) | VEH-01 | 차량/Pleos | 목적지와 ETA를 신뢰할 수 있는 값으로 받을 수 있는가? | 여정·검색 입력 계약 참여, 직접 DB 접속 없음 | probe APK 빌드·Lint 기록 있음, 기기 미검증 |
| [#2](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/2) | REC-01 | 추천/장소 | 어떤 후보를 제시하고 어떤 정보가 부족한지 판단하는가? | 장소·검색 후보·관측 정보 정의 | PR #10 통합·CLOSED, 실연동은 #11–13 |
| [#3](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/3) | VOICE-01 | AI 전화 | 전화로 확인한 말을 검증 가능한 결과로 바꾸는가? | 통화·이벤트·문의 사실 정의 | 네트워크 없는 MockCallAdapter만 구현 |
| [#4](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/4) | BE-01 | 서버/예약 | 누구의 요청인지 확인하고 중복·모순 없이 예약을 저장하는가? | ERD·migration·트랜잭션·무결성 총괄 | 단일 사용자 MOCK 상태 처리 구현, 운영 경계 미구현 |
| [#5](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/5) | UX-01 | 차량 UI/통합/시연 | 사용자가 조건·승인·결과를 주행 상황에 맞게 이해하는가? | API로 읽고 쓰기, 직접 DB 접속 없음 | 제품 UI 미구현, CLI 시연만 있음 |
| [#6](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/6) | 초기 설정 | 저장소/협업 기반 | 팀이 같은 규칙으로 빌드하고 리뷰할 수 있는가? | 공통 개발 환경 규칙 | 완료 기록, 후속 기능의 포괄 이슈가 아님 |

`#4`는 GitHub 실제 번호이고 `BE-01`은 계획 분류 코드입니다. `FR-01` 등은 기능 명세 내부 식별자로 GitHub 번호가 아닙니다. `dev-1`이나 팀원별 고정 번호는 사용하지 않습니다. 브랜치는 `feat/1-navi-probe`, `feat/2-place-adapter`, `feat/3-call-normalizer`, `feat/4-owner-validation`, `feat/5-vehicle-ui`처럼 **실제 이슈 번호**를 사용합니다. `feat/2-place-adapter`는 REC-01 착수용 로컬 브랜치로 생성했으며 나머지는 명명 예시입니다.

## 2. #1 차량 목적지와 ETA 검증

원제: **VEH-01: Pleos 기기에서 목적지·ETA probe 검증**. 주 담당: 차량/Pleos 1명. 협업: #4 입력 계약, #5 화면·주행 제한. 연결 기능: FR-02, FR-15.

### 해야 하는 이유와 경계

내비게이션의 `eta` 정수를 잘못 해석하면 식당에 잘못된 예약 시각을 전달합니다. SDK 소스에서 단위가 초인 것은 확인했지만 남은 시간인지, epoch인지, 하루 기준 시각인지는 기기 증거가 없습니다. 현재 서버는 `source=PLEOS`를 거부합니다. 이 이슈의 우선 결과물은 **관측 증거와 변환 계약**입니다. APK 빌드만으로 완료 처리하지 않습니다.

### 세부 작업

1. Pleos Emulator/Virtual Console 또는 허용된 기기에서 SDK·OS·이미지·앱 버전을 기록합니다.
2. 목적지 조회와 TripInfo 요청/콜백을 관측합니다. `getTripInfo()`라는 미확인 API를 만들지 않습니다.
3. 짧은/긴 경로, 60초 경과, 자정 경계, 목적지 변경·취소·도착에서 원시 ETA와 내비 화면을 대조합니다.
4. 권한 거부, 서비스 미지원, 연결 단절, 늦은 콜백, 백그라운드/복귀의 결과를 따로 기록합니다.
5. 확인된 시간 기준으로만 절대 `Instant` 변환을 설계합니다. 같은 여정의 재검색에서 `tripId`를 유지하는 기준을 #4와 정합니다.
6. 기기 검증 후 변환 adapter 구현은 해당 범위를 명시한 하위 작업으로 나눕니다. 계약·테스트가 갖춰지기 전 PLEOS 입력 차단을 해제하지 않습니다.

**입력 → 출력:** SDK 원시값·관측 시각·서비스 상태 → 검증 로그·가용성 상태·Navigation 변환 규칙. 현재 서버 Navigation은 `tripId`, 목적지, 절대 `eta`, `observedAt`, `source`를 받습니다. 서비스 가용성 상태를 추가할 API는 아직 없습니다.

**관련 DB:** 제안 ERD의 `trips`, `searches`. #1은 의미와 생명주기를 정의하고 #4가 저장합니다. 원시 위치 전체나 개인 경로를 Git에 기록하지 않습니다.

**주요 파일:** [probe 소스](../android/probe/src/main/java/ai/pleos/assistant/probe/ProbeActivity.java), [기기 절차](../android/README.md), [연동 근거](INTEGRATIONS.md), [Navigation 계약](../api/openapi.json).

### 완료 조건

- [ ] 기기/이미지 버전과 정상·실패 관측 기록이 존재한다.
- [ ] ETA 단위·시간 기준·갱신 주기·앱 생명주기별 증거를 구분한다.
- [ ] 미확인 항목은 미확인으로 남기고 임의 변환하지 않는다.
- [ ] `STATUS`와 Android 문서에 실제 결과·차단 사유를 갱신한다.

SDK 실행 환경이 없으면 로그 양식과 MOCK 입력으로 #2/#5 개발을 지원할 수 있지만 #1의 기기 검증 완료를 대신하지 못합니다.

## 3. #2 장소 adapter 기반과 후속 연동

현재 제목: **REC-01: 장소 adapter 기반 구현과 MOCK 검증**. 원제는 “시범 지역과 장소 데이터 adapter 설계”입니다. 담당: `tiemo0708`. 협업: #3 문의 사실, #4 저장·계약, #5 후보 화면. 연결 기능: FR-03–05.

### 범위 분리와 현재 상태

2026-11-07 사용자 요청으로 기존 #2를 검증된 기반 구현 범위로 명확히 하고, 원래의 미완료 조건은 아래 후속 이슈로 이관했습니다. 범위 정리 후 사용자 승인으로 PR #10을 squash merge했고 #2는 CLOSED입니다. 실제 연동 완료를 뜻하지 않으며 #11/#12/#13은 OPEN입니다.

| 이슈 | 담당 / 범위 | 선행 조건과 브랜치 |
| --- | --- | --- |
| [#2 REC-01](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/2) | `tiemo0708` / adapter·정규화·추천·MOCK/loopback 검증 | PR #10 MERGED·#2 CLOSED, 원격 작업 브랜치 삭제 |
| [#11 REC-02](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11) | `tiemo0708` / 데이터 보관·재사용·제공 조건, 공급자 최종 결정 | 정책 답변·대안 근거, `docs/11-place-data-policy` |
| [#12 REC-03](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/12) | `tiemo0708`, #4/#5 협업 / 계약·트랜잭션 밖 IO·중복·복구 | 기반 main 통합 후 가상 구현 가능, 실제 연결은 #11 조건 필요. `feat/12-place-preview` |
| [#13 REC-04](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/13) | `tiemo0708` / 실제 TLS/키/IP/쿼터·지역 품질 검증 | #11과 실행 경계·호출 상한 확인 후. `feat/13-place-api-validation` |

#12는 최신 main에서 `feat/12-place-preview`를 생성해 [내부 세션 실행 경계](REC03_PREVIEW.md)를 구현·검증했습니다. 소유 scope 대조·세션 만료/개수 상한·Registry 동시/누적 실행 상한을 보강해 총 188개가 통과했습니다. 실제 로그인 인증·공개 API/화면·자동 정리 스케줄·재시작/다중 서버 복구와 비용 관리는 남아 있습니다. #11/#13 브랜치는 아직 생성하지 않았습니다. #4/#5의 기존 담당 범위나 이슈 배정을 변경하지 않았습니다. #12는 계약/서버 경계를 맡고 제품 UI 자체는 #5가 담당합니다.

### #2에서 구현·검증한 것

- 성수·서울숲 음식점과 Kakao 우선 검토 선택, 반경 1km·합성 목적지 3개 및 공식 저장 제한 기록
- 조회 adapter·가상 사실 생성 분리, 가상 Kakao JSON 정규화·실패 분류
- 전체 후보 평가 후 최대 3곳 선택, 필수 조건·미확인·만료·NOW/ARRIVAL·ETA 경계
- HTTP timeout·취소·redirect·응답 크기/UTF-8·JVM 시작 옵션과 연결 단절 시 재전송 제한의 loopback 검증
- 총 144개 테스트 실패/오류/skip 0, JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 차단 통과

현재 제품은 MOCK만 주입받고 공개 API/Model·migration·의존성 변경은 없습니다. 기본 fixture는 가상 3곳이며 0·1·2·5개 입력에서 최대 3개 반환도 검증했습니다. 실제 장소에 MOCK facts를 붙이지 않습니다.

**입력 → 출력:** 목적지 좌표·ETA·동행 조건·취향 → 최대 3개 가상 후보, 적격/미확인/제외 사유와 출처. 실제 Kakao 식당 결과는 아직 연결하지 않았습니다.

**관련 DB:** 현재 `searches.payload`와 `commands` 응답 snapshot을 유지합니다. 제안 `places`·`search_candidates`·`candidate_facts` migration과 실제 데이터 보관은 #4/#11/#12가 조율합니다.

**주요 문서:** [상세 계획](REC01_PLAN.md), [연동 검토](REC01_INTEGRATION.md), [서버·화면 계약 초안](REC01_CONTRACT_DRAFT.md). 주요 코드: [추천 엔진](../server/src/main/java/ai/pleos/assistant/RecommendationEngine.java), [장소 adapter](../server/src/main/java/ai/pleos/assistant/PlaceSearchAdapter.java).

### 이번 완료 범위와 실행 제한

#2는 위 기반 구현·검증·제약 기록과 후속 이슈 이관으로 범위를 정리하고 PR #10으로 통합했습니다. PR #10은 `Closes #2`만 사용하고 #11/#12/#13은 `Refs`로 연결합니다. 정책 확정·실제 연결·실계정 검증을 완료로 바꾸지 않았으며, 각 후속 이슈에 별도의 완료 조건이 있습니다.

ArriveOn 앱·무료 쿼터 설정과 [정책 문의](https://devtalk.kakao.com/t/local-api/152647) 등록은 완료했고 답변 확인은 #11에서 이어갑니다. 키 값 복사·실제 Kakao 조회·전화·유료 실행 0회입니다. Pleos 내비·ETA와 외부 식당 데이터의 역할은 [INTEGRATIONS](INTEGRATIONS.md#내비게이션과-장소-검색의-역할)를 따릅니다.

## 4. #3 AI 전화와 결과 정규화

원제: **VOICE-01: 한국어 전화 업체 검토와 승인된 PoC 준비**. 주 담당: AI 전화 1명. 협업: #2 사실 스키마, #4 상태 반영·웹훅, #5 승인·안내. 연결 기능: FR-05, FR-06, FR-08, FR-13, FR-14.

### 해야 하는 이유와 경계

“전화가 연결됐다”, “자리가 있다”, “그 시간에 예약을 받았다”는 다른 결과입니다. 전화 담당은 통화 내용을 정규화하고, **최종 예약 상태 변경은 서버가 검증**합니다. 지금은 작업 생성과 모의 outcome 주입만 가능하며 실제 음성/전화 모델은 없습니다.

### 세부 작업

1. 한국 발신, 한국어 음성, 번호/계정 조건, 비용과 실패 대조 기능의 근거를 비교합니다.
2. 문의/예약/변경 대화를 구분하고 AI 고지·대리 요청·인원·시각·추가 비용의 전달 형식을 정합니다.
3. 수락·부정 표현·조건부 답변·시간 오인식·금액 오인식·응답 단절을 outcome으로 정규화합니다.
4. BOOKING/CHANGE 결과에는 `outcome`, `explicitAcceptance`, `acceptedTime`, `additionalCostKrw`, `observedAt`을 제공합니다. INQUIRY의 사실은 `facts`로 분리합니다.
5. webhook 서명·재전송 시각·eventId 범위·provider call ID와 내부 call ID 매핑을 #4와 설계합니다.
6. allowlist, 승인 증빙, 횟수/비용 상한, kill switch, outbox/timeout 대조가 갖춰진 뒤 승인된 테스트 범위에서만 PoC를 실행합니다.

**입력 → 출력:** CallTask(목적·후보·요청 시각·비용·고지) → 검증 가능한 normalized outcome 또는 UNKNOWN. 현재 CallTask에는 수신번호·동행 인원이 직접 없으므로 검색 스냅샷과 장소 참조에서 승인 당시 정보를 조립하는 입력 계약도 필요합니다. 업체 원본 이벤트는 현재 `/mock/.../outcomes`에 직접 보내는 운영 계약이 아닙니다.

**관련 DB:** `calls`, `events`, 문의의 `candidate_facts`; 변경 관련 필드는 `reservation_changes`와 협의합니다. `reservations` 상태는 #4의 트랜잭션을 통해서만 갱신합니다.

**주요 파일:** [MockCallAdapter](../server/src/main/java/ai/pleos/assistant/MockCallAdapter.java), [outcome 처리](../server/src/main/java/ai/pleos/assistant/WorkflowService.java), [전화 API 계약](../api/README.md).

### 완료 조건

- [ ] 업체 선정 근거와 승인 전/후 실행 범위를 구분한다.
- [ ] 명시적 수락·시간·비용 불일치·부정 표현·단절 시나리오가 준비되어 있다.
- [ ] 서명 검증·이벤트 중복·UNKNOWN 유지 방식이 #4와 일치한다.
- [ ] 실제 PoC를 수행했다면 대상·목적·예산 승인과 실행 결과가 별도로 있다.

PoC 준비 완료와 실통화 성공은 다른 상태입니다. 실제 발신·번호 구매·유료 API는 대상, 목적, 예상 비용과 상한에 대한 명시적 승인 전 실행하지 않습니다.

## 5. #4 서버 소유권과 예약 무결성

원제: **BE-01: 사용자 소유권과 UNKNOWN 대조 흐름 설계**. 주 담당: 서버/예약 1명. 협업: #1 여정 의미, #2/#3 데이터, #5 승인 화면. 연결 기능: FR-01, FR-02, FR-07–FR-14, FR-16.

### 해야 하는 이유와 경계

단일 사용자 로컬 시연에서 안전했던 요청도 여러 사용자가 접근하면 다른 사람의 예약 조회나 중복 발신으로 이어질 수 있습니다. 이 이슈는 인증·소유권·승인 증빙·UNKNOWN 대조·운영 DB 선택을 다룹니다. **ERD 반영만으로 인증이나 실전화 준비가 완료되는 것은 아닙니다.**

### 세부 작업

1. 인증 주체에서 users를 결정하고 모든 검색·예약·통화 접근에 소유권을 확인합니다. 본문 user_id나 승인 boolean을 인증 증거로 믿지 않습니다.
2. 여정 소유권과 같은 여정 재검색 규칙을 정합니다. 사용자·mode별 명령 키 범위를 적용합니다.
3. 예약/변경 승인 당시 대상·시각·정책·비용·승인자를 보존합니다. 학습 동의와 구분합니다.
4. 11개 논리 테이블과 업무 제약을 #2/#3과 검토하고 DB 종류를 선택합니다. DB 선택 근거·migration·seed·롤백/복구 계획을 작성합니다.
5. UNKNOWN은 자동 재발신하지 않고 매장/provider 대조·증거·운영자 기록으로 해결하도록 설계합니다. 종료 통화의 outcome 덮어쓰기를 해결 절차로 사용하지 않습니다.
6. 외부 요청과 DB 기록 사이의 실패를 outbox/inbox·전송 식별자·timeout 대조로 다룹니다. DB 트랜잭션만으로 외부 발신을 취소할 수 있다고 가정하지 않습니다.
7. 기존 중복/동시성/롤백 테스트와 API 호환성을 유지하고 선택한 실제 DB에서도 검증합니다.

**입력 → 출력:** 인증된 사용자 명령·정규화 이벤트 → 소유권이 검증된 예약/변경 상태, 멱등 응답, 감사 가능한 결과. 인증·수동 해결·운영 웹훅의 신규 API 경로와 오류 계약은 아직 미확정입니다.

**관련 DB:** 전체 ERD와 migration을 총괄하며 특히 `users`, `trips`, `reservations`, `reservation_changes`, `commands`를 책임집니다. `searches`, `calls`, `events`의 저장·트랜잭션도 서버 소유입니다. 현재 `command_mutex`는 제거 전까지 유지합니다.

**주요 파일:** [WorkflowService](../server/src/main/java/ai/pleos/assistant/WorkflowService.java), [Store](../server/src/main/java/ai/pleos/assistant/Store.java), [V1](../server/src/main/resources/db/migration/V1__workflow.sql), [V2](../server/src/main/resources/db/migration/V2__reservation_trip.sql), [회귀 테스트](../server/src/test/java/ai/pleos/assistant/WorkflowTest.java).

### 완료 조건

- [ ] 사용자 A가 B의 검색·예약·통화·멱등 응답을 읽거나 변경할 수 없다.
- [ ] 같은 여정의 활성 예약 1개, 예약당 미해결 변경 1개를 동시 요청에서도 보장한다.
- [ ] UNKNOWN 대조·수동 해결·timeout/outbox의 상태와 증거가 정의되어 있다.
- [ ] 선택 DB의 제약·migration과 기존 API 응답 변환을 검증한다.
- [ ] OpenAPI·예제·호출자·테스트·상태 문서를 함께 갱신한다.

범위가 크므로 인증/소유권, ERD·DB 전환, UNKNOWN 해결, outbox/webhook을 **새 하위 이슈로 분리하는 안**을 권장합니다. 아직 해당 이슈 번호는 없습니다. 이 문서만 작성하고 #4 전체를 닫으면 안 됩니다.

## 6. #5 차량 화면과 통합 시연

원제: **UX-01: MOCK 차량 화면과 주행 제한 흐름 구현**. 주 담당: UX/통합/시연 1명. 협업: #1 차량 상태, #2 후보, #3/#4 결과 의미. 연결 기능: FR-01–FR-04, FR-06, FR-07, FR-09, FR-10, FR-12, FR-13, FR-15, FR-17.

### 해야 하는 이유와 경계

사용자는 후보 정보가 미확인인지, 예약이 접수된 것인지, 매장이 실제 수락한 것인지 구분할 수 있어야 합니다. 현재 SDK probe는 개발 진단 화면이고 제품 예약 UI가 아닙니다. #5는 공통 API를 사용하며 DB에 직접 연결하지 않습니다.

### 세부 작업

1. 조건 입력 → 후보 비교 → 누락 정보 문의 → 예약 내용 확인/승인 → 결과 화면을 만듭니다.
2. `eligible`/`bookable`/`missing`/`exclusionReasons`를 그대로 표현하고 불가능한 버튼을 차단합니다.
3. 승인 전 매장·인원·예약 시각·변경 정책을 보여줍니다. 비용 발생 변경은 금액을 따로 확인받습니다.
4. PENDING/CONFIRMED/REJECTED/UNKNOWN을 다른 문구로 표시합니다. 변경 UNKNOWN에는 기존 예약 정보와 미확인 변경 요청을 함께 표시합니다.
5. 통신 재시도는 같은 Idempotency-Key를 유지하고 최신 상태는 GET으로 읽습니다. 앱 복귀 시 새 예약을 생성해 복구하지 않습니다.
6. CarUxRestrictions의 초기/변경/조회 실패를 반영합니다. 제한 상태가 확인되지 않으면 복잡한 입력·확정 조작을 보류하는 제품 정책을 적용합니다.
7. MOCK 배지·가상 장소 고지·기기/실통화 검증 수준을 포함해 시연하고 release 설치·서명·영상 결과를 기록합니다.

**입력 → 출력:** 서버 응답·사용자 명시적 선택·주행 제한 → 허용된 화면과 명령. 상태 갱신 간격·앱 복귀 저장 방식은 정해야 하며 현재 목록/사용자 세션 복구 API는 없습니다.

**관련 DB:** `searches`, `candidate_facts`, `reservations`, `reservation_changes`, `calls`의 API 표현을 읽습니다. 스키마나 SQL을 차량 앱에 내장하지 않습니다.

**주요 파일:** 제품 화면 모듈은 아직 없음. [Android 구성](../android/README.md), [API 계약](../api/openapi.json), [모의 시연](../scripts/mock-demo.py)을 시작점으로 사용합니다.

### 완료 조건

- [ ] 조건·후보·승인·결과·미확인 변경 화면이 MOCK API에 연결된다.
- [ ] 주행 제한 초기값·상태 변경·조회 실패에서 허용 동작을 검증한다.
- [ ] 통신 실패·재진입·중복 클릭에도 새로운 예약을 임의 생성하지 않는다.
- [ ] APK 생성, 실제 설치/실행, 영상 생성 여부를 각각 기록한다.

## 7. #6 초기 설정 완료 기록

원제: **GitHub 초기 설정과 원격 CI 검증 기록**. CLOSED. 완료 근거는 [PR #7](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/7)과 이슈 본문의 CI 링크입니다. #7은 PR 번호이고 신규 기능 이슈 번호가 아닙니다.

완료 기록에는 main/원격 연결, 세 필수 검사, squash merge, 리뷰·보호 규칙, 협업 문서가 포함됩니다. **이번 확인은 #6의 원문·상태 확인이며 모든 GitHub 설정을 새로 감사한 것은 아닙니다.** 담당자 배정·CODEOWNERS·라이선스·실연동은 이 이슈 완료에 포함되지 않습니다. 후속 환경 변경은 새 이슈로 관리합니다.

## 8. ERD 테이블과 이슈 대응

모든 테이블은 서버가 접근하고, #4가 물리 스키마·migration을 조율합니다. 아래 “주 정의”는 데이터 의미를 정하는 역할입니다. 대상은 11개 테이블 **제안안**입니다.

| 테이블 | 주 정의 | 협업/소비 | 저장 내용과 검토 포인트 |
| --- | --- | --- | --- |
| users | #4 | #5 승인 화면 | 인증 제공자가 확인한 사용자, 임의 user_id 금지 |
| trips | #4 | #1, #5 | 사용자·mode·client_trip_id, 여정 재검색과 종료 기준 |
| searches | #4 | #1, #2, #5 | 검색 당시 목적지·ETA·동행 조건 스냅샷 |
| places | #2 | #4 | 사업자 장소 ID, 저장/재표시 가능한 기본 정보 |
| search_candidates | #2 | #4, #5 | 특정 검색의 후보, 검색별 고유 candidateId |
| candidate_facts | #2 | #3, #4, #5 | 관측값·출처·적용 시각·유효기간·확실성 |
| reservations | #4 | #3, #5 | 최초 요청, 확정 결과, 승인 정책, 누적 비용 |
| reservation_changes | #4 | #3, #5 | 변경별 기존/요청 시각, 비용, 결과, 미해결 여부 |
| calls | #3 | #4, #5 | INQUIRY/BOOKING/CHANGE 작업과 정규화된 결과 |
| events | #3, #4 | #2 사실 반영 | 이벤트 중복 방지, call 연결, 결과 원문 스냅샷 |
| commands | #4 | 모든 API 호출자 | 사용자·mode·scope·key, 최초 응답 보존 |

현재 물리 DB는 6개 테이블입니다. V1에 `events.call_id → calls.id` FK가 이미 있고, 후보·예약의 JSON 내부 참조는 별도 FK가 아닙니다. `command_mutex`는 현재 공통 잠금 테이블이며 정규화 초안의 11개 목록과 별개로 전환 검증까지 유지합니다.

## 9. 동시에 시작하는 방법

#1–5는 기존 OpenAPI와 MOCK 서버를 기준으로 동시에 착수할 수 있습니다. 담당자는 각 이슈에 1명씩 팀에서 배정합니다. #1은 SDK probe, #5는 제품 화면을 나누고 Android 공통 구성은 먼저 조율합니다.

| 이슈 | 지금 독립적으로 시작할 일 | 실제 연동 전 필요한 입력 |
| --- | --- | --- |
| #1 | Pleos emulator 준비·raw 목적지/ETA 관측 | SDK 이용 권한, 실제 콜백/시간 기준 증거 |
| #2 | 장소 adapter 경계·후보 평가·만료 테스트 | 시범 지역·사업자·키·저장 조건 |
| #3 | 업체 비교·대화/결과 fixture·정규화 | 사업자·허용 대상·발신번호·비용/실행 승인 |
| #4 | 인증·소유권·ERD·무결성 검토 | DB 선택·여정/예약 정책 합의 |
| #5 | MOCK 조건·후보·승인·결과 화면 | 기기 주행 제한 확인, 제품 모듈·서명 계획 |

실제 통합은 차량 입력 → 검색/추천 → 문의/예약 → 결과 UI 순서로 맞춥니다. 공통 DTO·OpenAPI·migration 변경은 #4와 영향받는 담당자가 검토합니다. 각 담당의 첫 PR은 하나의 검증 가능한 결과로 작게 나눕니다. 다중 예약·지연 감지의 MVP 포함 여부는 [설계 제안](DESIGN_PROPOSALS.md)의 미결정 항목입니다.

### 계약 공유와 통합 순서

1. #4가 현재 OpenAPI와 ERD 검토본을 계약 기준으로 공유하고 #1/#2/#3/#5가 필드 의미를 확인합니다. DB 종류·물리 설계 확정은 실제 migration 시작 전 필요합니다.
2. #1은 기기 증거, #2는 장소 adapter, #3은 정규화 fixture/PoC 준비, #5는 MOCK UI를 병행합니다. 실전화 승인이 없어도 이 작업들은 진행할 수 있습니다.
3. #4가 인증·DB 전환·상태 처리 경계를 보강합니다. #2/#3의 저장 변경을 같은 schema 변경 계획에 반영합니다.
4. #5가 통합 실패 시나리오를 재현하고 #1 기기 검증과 #3 승인된 PoC 결과를 별도로 붙입니다.

DB는 바이너리 파일을 공유하지 않고 **migration·seed·스키마 버전**을 공유합니다. 지금은 각자의 로컬 H2로 개발할 수 있습니다. 운영 DB 선택 후 같은 종류/버전의 로컬 테스트 환경과 통합 환경을 구성하는 안이며, 공유 개발 DB는 아직 설치·배포하지 않았습니다. 구조 변경은 migration PR, 데이터 초기화는 본인의 로컬 DB 범위로 제한합니다.

한 사람이 같은 파일을 통째로 여러 이슈에서 수정하지 않도록 `Model.java`·`WorkflowService.java`·OpenAPI·migration 변경은 #4와 먼저 작업 경계를 정합니다. 담당자는 초대된 계정이라는 이유만으로 임의 배정하지 않습니다.
