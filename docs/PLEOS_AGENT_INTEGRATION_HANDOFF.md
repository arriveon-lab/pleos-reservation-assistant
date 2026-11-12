# Pleos 추천·전화 연결 인계

2026-11-12 기준. 기존 [음성 PR #45](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/45)와 [추천 PR #53](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/53)을 이어받은 개발 상태다. 이 문서는 제품 연결을 위한 현재 계약과 남은 구현을 구분한다. `main` 병합·실서비스 개방을 뜻하지 않는다.

## 대조한 기존 코드

| 영역 | 기준 | 확인한 경계 |
| --- | --- | --- |
| 전화 | #45 `f97c572` 이후 최신 변경 | 전용 번호·기존 예약 소유권, 발신/수신 제한, UNKNOWN 보존 위에 음성 품질·시험 화면·통화 중 변경 승인 추가 |
| 추천 | #53 `cc39339` 이후 최신 변경 | 기존 Bearer/작업 ID/화면 세대를 유지하고 업종·음식 취향·근거 순위·시험 연결 추가 |
| 차량 앱 | #46 `cbe8f26`의 `DestinationController`, `UX_NAVIGATION_BACKEND_HANDOFF.md` | 기존 POST/GET/close와 `result.webRecommendations` 사용. 음식 취향은 현재 빈 배열이고 실제 내비는 UNVERIFIED에서 검색 차단 |
| 내비 probe | #47 `bbfb184` | 현재 경로와 목적지 응답의 연관성, ETA 시간 기준 미검증. 원시 초를 예약 시각으로 변환하지 않음 |

추천 PR은 이전부터 여러 미병합 기능을 포함한 통합 브랜치다. 이번에도 음성 변경은 #45에서 커밋하고 #53이 그 커밋을 통합해 함께 검증한다. #45에는 추천 Java 구현을 복제하지 않는다. 선행 PR의 완료·병합 여부는 각각 별도다.

## 지금 실행할 수 있는 흐름

1. **제품 앱과 맞춰볼 추천 API**: Bearer 인증 후 `POST /api/experimental/destination-recommendations`, `GET /{id}`로 폴링한다. 기존 `viewId`, `generation`, `destination`, `party`, `maxWaitMinutes`, `requiredFeatures`, `preferredCuisines`, `userRequest`를 유지한다. `userRequest`의 음식 종류는 로컬에서 추출하고 외부 모델에는 정규화한 종류만 전달한다. 같은 화면의 세대 변경·close·만료 결과는 폐기한다.
2. **주소 입력 웹 시험**: Python 시험 UI는 `POST /api/experimental/destination-recommendations/web`에 도로명주소, 필수 조건, 선택적 업종·음식 취향·`arrivalAt`을 전달한다. `arrivalAt`은 한국 현지 시각의 예약 희망 날짜와 시간이다. 차량 앱이 쓰는 목적지 POST의 ETA 계약은 실기에서 검증한다.
3. **오프라인 예약 API 통합**: 가상 공공 catalog 결과에만 `POST /{id}/phone-reservations`가 가능하다. 후보 선택·승인 → 조건 확인 → 예약 가능 확인 → 예약 요청 → 수락/거절/UNKNOWN을 검증한다. `nextCandidate`는 명시적 거절 때만 제시하며, 다음 POST에는 새 승인·새 멱등 키·직전 `previousAttemptId`가 필요하다. UNKNOWN·통신 장애로 자동 재발신하지 않는다.
4. **실제 음성 시험**: 별도 Python 시험 화면에서 웹 후보 선택 → 시험 예약 생성 → 승인 → 등록/입력한 시험 휴대폰으로 발신한다. 카드에 표시한 실제 식당으로 전화한 것이 아니다. SQLite에 저장한 결과를 같은 세션이 조회한다. 이 경로는 제품 Spring 예약 DB 및 앱 인증 주체와 아직 통합되지 않았다.

추천 상태 `RESULTS`는 검색 완료다. 후보의 `bookable=false`, `inquiryDispatchAllowed=false`는 유지한다. 영업시간·브레이크타임·주차 안내와 후기 근거는 예약 가능한 자리/도착 시 대기/매장의 예약 확정을 증명하지 않는다.

## 화면과 데이터 매핑

| 값 | 현재 계약/표시 | 제품 연결 시 처리 |
| --- | --- | --- |
| 후보 | `webRecommendations[].ordinal/name/address/identitySourceUrl` | 순번은 해당 작업 안에서만 유효. 검증된 지점 ID·연락처를 서버가 별도 결합 |
| 업종·음식 | `venueCategory`, `preferredCuisines`, `businessDetails.CUISINE` | 식당/카페를 구분하고 인용된 음식 근거로 필터링. 모호한 취향은 재질문 |
| 조건 | `assessments`, `remainingQuestions`, `reviewSignals` | UNKNOWN/CONFLICT 보존. 확인이 필요한 조건을 표시 |
| 추천 순위 | 충족 근거 → 후기 주의·상충 적음 → 긍정 조건 많음 → 확인된 방문자 리뷰 수 | 선택형 도로명주소 검색에서 출처와 집계일을 확인한 네이버 방문자 리뷰 수를 사용 |
| 매장 안내 | `businessDetails.OPENING_HOURS/BREAK_TIME/LAST_ORDER`, PARKING 근거 | 출처·관측/만료 시각 표시. 웹 시험에서는 방문 시간에 맞춰 매일 적용되는 일정으로 확인된 후보를 거름 |
| 네이버 | `naverPlaceUrl` 및 공개 출처 | 범용 자동 플레이스/리뷰 수집 아님. 구미 30일·성수 7일 한정 보완 자료는 원 관측 시각 유지 |
| 결과 | 전화 `CONFIRMED/DECLINED/UNKNOWN/FAILED`, 통화/예약 참조 | 제품 정규화 결과와 매핑하고 저장된 결과만 안내. 통화 종료를 예약 확정으로 표시하지 않음 |

구미·성수 외 주소, 0/1/2개 후보, 조건 상충, 출처 부족도 정상적인 결과로 처리한다. 세 곳을 채우기 위해 미확인 후보를 적합으로 바꾸지 않는다. 과거 시연 자료는 갱신하지 않은 채 관측 시각만 새로 찍지 않는다.

## 실제 서비스 연결 전에 구현할 항목

- **인증·소유권**: Spring의 안정적인 사용자/여정 ID를 전화 작업과 연결하고 내부 서비스 인증으로만 전달한다. 브라우저 쿠키·임의 `X-Recommendation-Owner`를 제품 인증으로 사용하지 않는다. 현재 내부 헤더는 공유 서비스 토큰과 가상 catalog 검사를 통과한 MOCK 전용이다.
- **실전화 작업 계약**: 조건 문의/예약/변경/지연 안내를 분리하고 승인 snapshot, 지점/번호, 예약 시각·시간대, 동승 조건, 비용 상한, 멱등 키, 작업 ID를 영속화한다. 웹 출처 URL이나 ordinal에서 전화번호를 추측하지 않는다. 사용자 입력 시험 번호도 운영에서는 소유권 검증이 필요하다.
- **비동기 처리와 복구**: 발신 요청을 durable job/outbox로 처리하고 통화 결과의 인증·중복 이벤트·재시작 대조를 구현한다. 응답이 유실되면 기존 작업 GET으로 대조하며 새 발신을 만들지 않는다. 현재 메모리 추천 작업/프로세스별 검색 예산은 다중 인스턴스 운영용이 아니다.
- **차량 연결**: #46/#47의 현재 경로 연관성과 ETA 기준을 공동 인수한다. 목적지/인원/조건 변경 시 승인 snapshot을 폐기한다. 검증 전에는 실제 ETA 예약을 활성화하지 않는다. 앱은 추가 필드와 음식 취향 입력·UNKNOWN 안내를 인수한다.
- **수신 변경**: 같은 사용자·식당·예약을 식별하고 원 예약을 보존한다. 사용자가 승인한 뒤 식당 수락까지 확인해야 변경한다. 브라우저의 20초 승인 대기는 실제 Pleos 알림·재진입·응답 유실 처리로 연결해야 한다.
- **운영 설정**: TLS와 제품 인증, 확인된 시험번호 allowlist, 공유 영속 DB, 일일 횟수/비용 한도, kill switch, 원문 보관/삭제 정책을 갖춘다. 기본 MOCK을 유지하고 실전화/유료 검색 실행은 별도 승인된 범위로만 연다.

## 재현과 인수 순서

Java 21과 `scripts/requirements-dev.txt`의 Python 의존성을 준비한다. 음성 테스트는 `prototypes/phone_agent/voice/requirements.txt`도 설치한다. 명령은 저장소 루트에서 실행한다.

```bash
./gradlew -PincludeAndroid=false verify
python scripts/smoke-test.py --check-contract
python scripts/check-web-recommendation.py
python -m unittest discover -s prototypes/phone_agent -t .
python scripts/mock-agent-integration.py --verify
```

마지막 명령은 임시 DB·가상 catalog·loopback 서버만 사용한다. 실제 번호/공급자 키가 필요 없고 외부 발신은 0회다. 승인 누락, 조건 불충족, 거절 후 다음 후보 재승인, UNKNOWN 재발신 차단, 멱등 재시도·동시 요청, 타 사용자 접근, 닫힌 화면을 검사한다.

다음 실제 기기 인수는 검증된 목적지 → 동일 세션 추천 → 후보/조건/시각 승인 → 허용 휴대폰 1회 시험 → 저장 결과 화면 표시 순서로 진행한다. 추가 비용/필수 조건 불가/무응답/중복 승인/재접속/식당 회신을 포함하고, 실제 식당 발신은 이후 별도 승인한다. 기존 시험의 성공을 제품 앱 인수 완료로 표시하지 않는다.

2026-11-12 로컬 인수 결과: Java 460개·Python 167개, 계약 smoke, 웹추천 HTTP20/CLI5, 독립 전화 명세 및 통합 스키마 일치, 두 프로세스 MOCK 검증 모두 통과했습니다. 첫 후보 DECLINED → 별도 승인한 다음 후보 CONFIRMED, UNKNOWN 재발신 차단을 확인했습니다. 실제 전화·유료 API 0회이며 GitHub CI 결과는 해당 PR의 최신 head에서 별도로 확인합니다.
