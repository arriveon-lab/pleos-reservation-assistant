# API v1 — 계약 1.1.0

[REC-12 로컬 catalog 계약](../docs/REC12_PUBLIC_CATALOG.md) — #48의 오프라인 코드 준비이며 제품 HTTP/DB/실제 발신 연결은 아닙니다.

별도 인수 초안: [REC-08 추천·질문 계획](recommendation-plan-draft.schema.json)과 [가상 예제](examples/recommendation-plan-mock.json)는 제품 HTTP API가 아닌 순수 평가기 결과입니다. `./gradlew -PincludeAndroid=false :server:recommendationPlan`은 외부 호출 없이 가상 결과를 출력합니다. `verify` 뒤 `python scripts/check-recommendation-plan.py`로 초안·예제·Java 실행 증거를 대조합니다. v1 경로/스키마/버전은 바꾸지 않았으며 #16/#26 화면 계약 채택 전입니다.

정의: [OpenAPI 3.1 JSON](openapi.json). 기본 URL `http://127.0.0.1:8080/api/v1`. 성공은 모두 HTTP 200, 요청 오류 400, 미인증 401, 권한 부족 403, 없음/타인 소유 404, 상태/중복 충돌 409. 오류 JSON은 `code`, `message`, `mode`입니다. 현재 모든 결과는 MOCK입니다.

요청의 boolean·정수 필드는 OpenAPI `required`에 따라 0/false도 명시합니다. null이나 누락을 암묵적으로 승인/기본값으로 바꾸지 않습니다. 알 수 없는 필드, 정수에 소수/문자열을 보내는 요청도 거부합니다.

| 메서드 | 경로 | 응답 |
| --- | --- | --- |
| GET | `/status` | 실행 모드·계약 버전·외부 발신 차단 상태 |
| POST | `/searches` | 검색과 가상 후보 3개 |
| GET | `/searches/{id}` | 만료를 재평가한 후보 |
| POST | `/searches/{id}/inquiries` | 모의 문의 작업 |
| GET | `/searches/{id}/calls` | 검색의 문의·예약·변경 작업 |
| POST | `/searches/{id}/reservations` | 예약 PENDING과 bookingCallId |
| GET | `/reservations/{id}` | 예약 상태 및 미해결 변경 참조 |
| POST | `/reservations/{id}/changes` | 원 예약과 변경 작업 |
| GET | `/calls/{id}` | 통화 목적·요청 시각·결과 |
| POST | `/mock/calls/{id}/outcomes` | 모의 결과 주입 |

## 인증과 소유권 — BE-02

주소는 `/api/v1`, 계약 표기는 1.1.0입니다. 첫 배포 전 합의에 따라 기존 API에 인증을 추가했습니다. 회원가입·로그인 화면 없이 서버가 **앱 설치별 익명 주체**를 발급합니다. 사람·차량 자체의 신원을 확인하는 방식이 아닙니다. 같은 설치를 여러 사람이 사용하면 같은 소유자로 취급합니다.

| 메서드 | 경로 | 처리 |
| --- | --- | --- |
| POST | `/anonymous-sessions` | `{}`로 새 주체·접근/갱신 토큰 발급 |
| POST | `/anonymous-sessions/refresh` | `{ "refreshToken": "<저장한 갱신 토큰>" }`와 Idempotency-Key로 토큰 교체 |
| DELETE | `/anonymous-sessions/current` | 유효한 접근 토큰으로 현재 인증 세션 전체 폐기 |

발급·갱신 POST에는 접근 토큰이 필요하지 않습니다. 갱신 요청에는 만료된 Authorization 헤더를 보내지 않습니다. 나머지 보호 API는 `Authorization: Bearer <accessToken>`을 요구합니다. 임의 사용자 헤더·JSON 주체 값은 인증이 아니며, 발급 요청에 주체/역할을 지정하면 400입니다. 상태 조회에는 인증이 필요하지 않습니다.

발급/갱신 응답은 `tokenType`, `accessToken`, `accessTokenExpiresAt`, `refreshToken`, `refreshTokenExpiresAt`, `mode`입니다. 응답 캐시는 금지합니다. 기본 접근 토큰은 1시간, 갱신 토큰은 90일입니다. `app.auth.access-ttl`/`app.auth.refresh-ttl` 설정으로 조정하며 각각 2분보다 길고 접근 토큰 수명보다 길어야 합니다. 갱신 성공 시 새 갱신 토큰의 유효기간이 다시 시작됩니다.

토큰은 256비트 난수에 기반하며 DB에는 SHA-256 해시만 저장합니다. 갱신 요청의 원문·토큰을 로그에 남기지 않습니다. 앱은 접근·갱신 토큰을 안전한 저장소에 함께 보관하고, 공유 서버 통신에는 HTTPS를 사용해야 합니다. Android의 실제 안전 저장·자동 갱신 연결은 별도 후속 작업이며 현재 진단용 probe에는 구현하지 않았습니다.

### 앱 재실행과 갱신 재시도

- 앱 종료·재실행, 정상 갱신, DB가 보존되는 서버 재시작은 주체를 바꾸지 않습니다.
- 앱은 갱신 요청마다 충분히 무작위인 Idempotency-Key(권장 UUID, 16~100자)를 만들고 **요청 전에 저장**합니다. 한 번에 하나의 갱신만 진행하며, 응답 토큰 쌍을 함께 저장한 뒤 기존 토큰·요청 키를 정리합니다.
- 같은 이전 갱신 토큰과 같은 키는 최초 성공부터 120초 동안 동일한 응답을 재생합니다. 다른 키 재사용, 120초 초과, 후속 갱신 토큰 사용 후 재시도는 409 `REFRESH_ALREADY_USED`입니다. 주체를 새로 발급하지 않습니다.
- 재시도 응답은 이전 갱신 비밀과 요청 키에 HMAC-SHA256을 적용하여 재구성합니다. 접근/갱신을 구분한 입력을 사용하고, DB에는 후속 토큰 해시와 만료·재시도 메타데이터만 저장합니다. 토큰 원문 snapshot이나 별도 영속 암호화 키는 없습니다.
- 갱신 전 접근 토큰은 원래 만료 시각까지 유효합니다. 인증 세션 폐기는 모든 세대의 접근/갱신 토큰을 차단합니다. 이미 처리 중인 요청을 취소하는 기능은 아닙니다.
- 응답 유실은 재시도 창 안에서 복구할 수 있습니다. 토큰 쌍을 잃고 창도 지난 경우, 재설치·앱 데이터 삭제·갱신 만료·폐기 후에는 계정 기반 복구가 없습니다. 앱은 401/409를 새 사용자 생성으로 조용히 처리하지 않아야 합니다. 재설치 시 복원되지 않도록 자격 증명을 백업에서 제외하는 것도 앱 후속 범위입니다.

### 사용자 격리와 #16 연결

검증 필터가 서버의 안정적인 UUID 주체를 Principal로 설정합니다. #16과 동일하게 `Principal.getName()`이 소유 범위이며, 토큰 교체로 이 값이 바뀌지 않습니다. 공통 인증 필터는 v1과 `/api/experimental/place-previews/*`에 적용하고 두 접근 guard보다 먼저 실행합니다. #16의 비동기 응답, 기본 비활성화, 세션 만료·메모리 수명은 유지합니다. 주체 유지가 미리보기 세션의 재시작 복구를 의미하지 않습니다.

주체는 내부 `app_users.id`와 연결되고, 여정은 사용자·MOCK 모드·클라이언트 tripId 조합으로 식별합니다. 예약·통화는 검색을 통해 소유자를 확인합니다. 조회·변경·명령 응답 재생 전에 권한을 확인하고 타인 소유와 없는 ID 모두 404로 처리합니다.

모의 결과 입력은 MOCK_OPERATOR 역할과 통화 소유권이 모두 필요합니다. 발급 토큰은 이 역할을 갖지 않으며, 헤더·본문·기존 요청 역할로 승격되지 않습니다. 실전화 웹훅 인증과는 별개입니다. OpenAPI는 실제 opaque Bearer 인증 방식을 명시합니다.

### 저장과 발급 제한

V3는 기존 검색을 `journey_id=NULL`로 보존하여 접근을 차단하고, 기존 명령을 `legacy_commands`에 보존하여 재생하지 않습니다. V4는 인증 세션·토큰 해시·만료·폐기·갱신 재시도 정보를 추가합니다. 기존 migration과 데이터 payload를 수정하거나 첫 사용자에게 배정하지 않습니다.

발급은 단일 서버 기준 직접 접속 IP당 분당 10회, 전체 분당 100회로 제한하며 초과 시 429입니다. X-Forwarded-For를 신뢰하지 않습니다. 현재 제한은 메모리 고정 윈도 방식으로 재시작 시 초기화되며, 프록시/다중 서버 제한과 만료 자격 증명의 정기 정리는 운영 배포 전 후속 작업입니다. 여러 설치를 만들 수 있으므로 사람별 신원 확인·차량별 제한·전역 실전화 예산을 대신하지 않습니다.

## 차량 담당 계약

`Navigation`은 `tripId`, 목적지 이름/위경도, 절대 시각 `eta`, `observedAt`, `source`를 포함합니다. 현재 MOCK/MANUAL만 허용하며 PLEOS는 시간 기준 검증 전 400 `PLEOS_ETA_UNVERIFIED`입니다. 같은 여정의 재검색은 같은 tripId를 유지해야 중복 예약이 차단됩니다. 새 임의 tripId로 회피하지 않습니다. 관측은 최근 5분, 예약 시각은 현재 이후 24시간 이내입니다.

필수 조건은 `childFriendly`, `parking`. 아이 동행 시 childFriendly는 자동 필수입니다. 알려진 불충족 후보는 `eligible=false`와 `exclusionReasons`로 표시하되 비교 화면에 남깁니다. 미확인 조건은 `missing`에 남기고 `bookable=false`로 예약을 막습니다. 후보 셋은 장소 API의 실제 검색 결과가 아닙니다.

`Fact`의 `source`, `mode`, `evidenceRef`, `observedAt`, `appliesAt`, `validUntil`, `certainty`, `scope`를 보존합니다. appliesAt은 **적용 시각(effective time)**입니다. 현재 대기는 NOW, 도착 예상 대기는 ARRIVAL/ESTIMATED입니다. MVP 유효기간은 관측 후 5분입니다. 예약 가능 여부와 도착 대기는 해당 ETA의 `arrivalAppliesAt`이 있어야 반영합니다. 시간 변경으로 적용 시각이 달라지면 재검색·문의가 필요합니다.

## 전화 담당 계약

현재 발신 adapter는 네트워크 호출 없이 작업만 생성합니다. 통화 시작 고지문은 `CallTask.disclosure`에 있습니다. 모의 outcome은 provider 웹훅이 아니며 외부에 노출하지 않습니다.

문의는 ANSWERED/REJECTED/UNKNOWN. 문의의 ACCEPTED는 잘못된 요청입니다. 통화 완료/ANSWERED는 예약 확정의 근거가 아닙니다. BOOKING/CHANGE는 `outcome=ACCEPTED`, `explicitAcceptance=true`, `acceptedTime == requestedTime`, 비용 일치가 모두 있어야 수락입니다. 불명확하거나 모순되는 결과는 UNKNOWN으로 보존합니다.

예약 UNKNOWN이면 재발신 차단. 변경 UNKNOWN이면 예약 status는 기존 CONFIRMED, confirmedTime은 기존 시각, pendingChangeCallId는 유지합니다. 화면에서는 이 조합을 **“기존 예약 정보 유지 · 변경 결과 미확인, 매장 대조 필요”**로 안내해야 합니다. 기존 시각의 유효성을 다시 확인했다는 뜻은 아닙니다. 운영자 대조/해결 API는 후속 작업입니다. 종료한 통화는 새로운 이벤트로 덮어쓸 수 없습니다.

## 중복과 권한

결과 주입을 제외한 POST에는 `Idempotency-Key`가 필요합니다. 동일 명령의 네트워크 재시도에는 같은 키를 사용합니다. 같은 사용자·모드·작업 대상 범위에서 동일 키+동일 본문은 최초 응답 snapshot을 반환합니다. 최신 상태는 GET으로 읽습니다. 동일 키+다른 본문은 KEY_REUSED입니다. `eventId`는 전체 통화에서 고유하고 동일 내용 재전송은 무해합니다. 다른 통화/내용에 재사용하면 EVENT_REUSED입니다.

추가 키를 만들어도 같은 사용자의 같은 tripId에 PENDING/CONFIRMED/UNKNOWN 예약을 다시 만들 수 없습니다. 문의 간격·변경 간격은 10분이며 미해결 변경은 시간이 지나도 재시도하지 않습니다. 추가 비용은 누적 정책 상한 이내이고 `approveAdditionalCost=true`여야 합니다. 이 boolean은 **MOCK 시뮬레이션의 요청 의사 표현**이며 운영 인증/승인 증빙을 대신하지 않습니다. 학습 동의는 예약 승인과 무관합니다. 지속 ETA 지연 자동 감지는 아직 구현하지 않았습니다.

## 예제와 검증

`examples/search.json`은 고정 시각 설명용입니다. 현재 시각 요청 생성은 `scripts/mock-demo.py`가 담당합니다. 일반 API의 실제 발급 토큰 흐름과 테스트 운영자 권한이 필요한 결과 주입 흐름을 아래 `smoke-test.py`에서 구분하여 검증합니다. 서버는 오래된 예제를 그대로 보내면 거부합니다.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements-dev.txt
.venv/bin/python scripts/check-contract.py
.venv/bin/python scripts/smoke-test.py --check-contract
```

모의 결과 입력 권한을 공급하는 테스트 전용 로컬 서버가 이미 실행 중일 때만 기존 전체 예약 시연을 대조할 수 있습니다(일반 발급 토큰은 결과 입력 403):

```bash
.venv/bin/python scripts/check-contract.py --base-url http://127.0.0.1:8080
```

Windows에서는 `.venv\Scripts\python`을 사용합니다. 전화 업체별 raw 웹훅 → v1 normalized outcome 변환, 서명/재전송 시각 확인, outbox, timeout 대조는 LIVE 도입 전에 추가해야 합니다.

스모크 테스트는 배포 JAR의 무인증·위조 헤더 차단, 실제 토큰 발급·소유권·권한·갱신 재시도·폐기·재시작을 검증합니다. 이어 `src/test` 전용 운영자 주체로 예약 수락·UNKNOWN·변경 흐름을 검증합니다. 이 테스트 권한은 배포 JAR에 포함되지 않습니다. `verify`가 테스트 실행 classpath 파일을 생성하므로 스모크 전에 실행합니다.

#16 통합 후 실제 Bearer 토큰으로 미리보기를 검증하려면 opt-in 루프백 서버에서 `.venv/bin/python scripts/preview-token-smoke.py --base-url http://127.0.0.1:8080`을 실행합니다. #16의 별도 초안 명세가 필요하며, 발급·갱신·미리보기 4개 연산·타인 접근·폐기 응답을 검사합니다. 테스트 토큰은 로그나 파일로 출력하지 않습니다.
