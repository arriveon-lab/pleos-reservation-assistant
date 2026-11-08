# API v1 — 계약 1.0.0

정의: [OpenAPI 3.1 JSON](openapi.json). 기본 URL `http://127.0.0.1:8080/api/v1`. 성공은 모두 HTTP 200, 요청 오류 400, 없음 404, 상태/중복 충돌 409. 오류 JSON은 `code`, `message`, `mode`입니다. 현재 모든 결과는 MOCK입니다.

요청의 boolean·정수 필드는 OpenAPI `required`에 따라 0/false도 명시합니다. null이나 누락을 암묵적으로 승인/기본값으로 바꾸지 않습니다. 알 수 없는 필드, 정수에 소수/문자열을 보내는 요청도 거부합니다.

## 실험 중인 장소 미리보기 계약

2026-11-08: [#21 후보 인증 PR 연동](../docs/REC05_TOKEN_INTEGRATION.md)을 별도 임시 환경에서 검증했습니다. `scripts/preview-session-demo.py`는 통합된 계약 1.1.0의 loopback MOCK 서버용이며 현재 1.0.0 서버에서는 토큰 발급 전에 중단합니다. 이 브랜치에 #21 인증 구현을 포함하거나 main의 계약을 변경한 것은 아닙니다.

[#16의 OpenAPI 초안](place-preview-draft.json)은 `/api/experimental`의 별도 MOCK 계약입니다. 기본 비활성(404)이고 명시적으로 켜도 검증된 서버 Principal이 없으면 401입니다. 실제 로그인·차량 화면·navigation 연결은 미구현이며 위 v1 경로/버전 1.0.0은 유지합니다. [구현 범위와 화면 의미](../docs/REC05_PREVIEW_API.md), [가상 요청 예제](examples/place-preview-query.json)를 참고합니다. 이 초안을 팀 합의된 제품 API로 취급하지 않습니다.

`verify` 후 `python scripts/check-contract.py --preview-exchanges`로 테스트가 생성한 가상 HTTP 교환을 초안과 대조합니다. `smoke-test.py --check-contract`는 이 대조와 JAR의 기본 404/opt-in 무인증 401도 검사합니다. 기존 v1 POST의 Idempotency-Key/snapshot 의미를 실험 경로에 그대로 적용하지 않습니다.

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

## 차량 담당 계약

`Navigation`은 `tripId`, 목적지 이름/위경도, 절대 시각 `eta`, `observedAt`, `source`를 포함합니다. 현재 MOCK/MANUAL만 허용하며 PLEOS는 시간 기준 검증 전 400 `PLEOS_ETA_UNVERIFIED`입니다. 같은 여정의 재검색은 같은 tripId를 유지해야 중복 예약이 차단됩니다. 새 임의 tripId로 회피하지 않습니다. 관측은 최근 5분, 예약 시각은 현재 이후 24시간 이내입니다.

필수 조건은 `childFriendly`, `parking`. 아이 동행 시 childFriendly는 자동 필수입니다. 알려진 불충족 후보는 `eligible=false`와 `exclusionReasons`로 표시하되 비교 화면에 남깁니다. 미확인 조건은 `missing`에 남기고 `bookable=false`로 예약을 막습니다. 후보 셋은 장소 API의 실제 검색 결과가 아닙니다.

`Fact`의 `source`, `mode`, `evidenceRef`, `observedAt`, `appliesAt`, `validUntil`, `certainty`, `scope`를 보존합니다. appliesAt은 **적용 시각(effective time)**입니다. 현재 대기는 NOW, 도착 예상 대기는 ARRIVAL/ESTIMATED입니다. MVP 유효기간은 관측 후 5분입니다. 예약 가능 여부와 도착 대기는 해당 ETA의 `arrivalAppliesAt`이 있어야 반영합니다. 시간 변경으로 적용 시각이 달라지면 재검색·문의가 필요합니다.

## 전화 담당 계약

현재 발신 adapter는 네트워크 호출 없이 작업만 생성합니다. 통화 시작 고지문은 `CallTask.disclosure`에 있습니다. 모의 outcome은 provider 웹훅이 아니며 외부에 노출하지 않습니다.

문의는 ANSWERED/REJECTED/UNKNOWN. 문의의 ACCEPTED는 잘못된 요청입니다. 통화 완료/ANSWERED는 예약 확정의 근거가 아닙니다. BOOKING/CHANGE는 `outcome=ACCEPTED`, `explicitAcceptance=true`, `acceptedTime == requestedTime`, 비용 일치가 모두 있어야 수락입니다. 불명확하거나 모순되는 결과는 UNKNOWN으로 보존합니다.

예약 UNKNOWN이면 재발신 차단. 변경 UNKNOWN이면 예약 status는 기존 CONFIRMED, confirmedTime은 기존 시각, pendingChangeCallId는 유지합니다. 화면에서는 이 조합을 **“기존 예약 정보 유지 · 변경 결과 미확인, 매장 대조 필요”**로 안내해야 합니다. 기존 시각의 유효성을 다시 확인했다는 뜻은 아닙니다. 운영자 대조/해결 API는 후속 작업입니다. 종료한 통화는 새로운 이벤트로 덮어쓸 수 없습니다.

## 중복과 권한

결과 주입을 제외한 POST에는 `Idempotency-Key`가 필요합니다. 동일 명령의 네트워크 재시도에는 같은 키를 사용합니다. 동일 키+동일 본문은 최초 응답 snapshot을 반환합니다. 최신 상태는 GET으로 읽습니다. 동일 키+다른 본문은 KEY_REUSED입니다. `eventId`는 전체 통화에서 고유하고 동일 내용 재전송은 무해합니다. 다른 통화/내용에 재사용하면 EVENT_REUSED입니다.

추가 키를 만들어도 같은 tripId에 PENDING/CONFIRMED/UNKNOWN 예약을 다시 만들 수 없습니다. 문의 간격·변경 간격은 10분이며 미해결 변경은 시간이 지나도 재시도하지 않습니다. 추가 비용은 누적 정책 상한 이내이고 `approveAdditionalCost=true`여야 합니다. 이 boolean은 **로컬 단일 사용자 시뮬레이션 계약**이며 운영 인증/승인 증빙을 대신하지 않습니다. 학습 동의는 예약 승인과 무관합니다. 지속 ETA 지연 자동 감지는 아직 구현하지 않았습니다.

## 예제와 검증

`examples/search.json`은 고정 시각 설명용입니다. 실행은 현재 시각으로 요청을 만드는 `scripts/mock-demo.py`를 사용합니다. 서버는 오래된 예제를 그대로 보내면 거부합니다.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements-dev.txt
.venv/bin/python scripts/check-contract.py
.venv/bin/python scripts/smoke-test.py --check-contract
```

실행 중인 로컬 서버와 전체 요청/응답 스키마를 대조하려면:

```bash
.venv/bin/python scripts/check-contract.py --base-url http://127.0.0.1:8080
```

Windows에서는 `.venv\Scripts\python`을 사용합니다. 전화 업체별 raw 웹훅 → v1 normalized outcome 변환, 서명/재전송 시각 확인, outbox, timeout 대조는 LIVE 도입 전에 추가해야 합니다.
