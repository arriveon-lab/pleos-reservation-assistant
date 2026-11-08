# REC-05 미리보기 HTTP 계약 초안과 MOCK 통합

**2026-11-08 후속:** [#21 인증 PR 연동 검토](REC05_TOKEN_INTEGRATION.md)에서 실제 서버 발급 토큰을 사용하는 개발용 호출자를 추가했습니다. #19 단독 244개·Python 7개와 #21 임시 통합 271개·토큰 HTTP 16건을 구분합니다. #21은 아직 미병합이며 아래 단독 브랜치의 기본 비활성/인증 계층 미포함 상태는 유지합니다.

작성: 2026-11-07 KST. 담당: `tiemo0708`, [#16](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16). 브랜치: `feat/16-place-preview-integration`, 시작 main `9518b6a`.

**#16의 첫 단계입니다.** 내부 세션·Registry를 HTTP 요청에 연결하고 가상 인증 주체로 검증했습니다. 실제 로그인·차량 화면·navigation freshness/ETA와 팀 계약 합의는 아직 없습니다. 인증 프로토콜을 임의로 정하거나 #4/#5의 인수를 완료로 처리하지 않습니다. 기존 제품 `/api/v1` 계약 1.0.0과 MOCK/LIVE 차단을 유지합니다.

## 실행 범위

기본 `app.place-preview.enabled=false`에서는 컨트롤러·Registry·인증 경계 필터를 등록하지 않습니다. 실험 경로는 404입니다. 명시적으로 켜도 전용 가상 adapter만 생성하며 카카오 키나 다른 `PlaceSearchAdapter` 설정을 사용하지 않습니다. 장소 응답을 Store/DB에 연결하지 않습니다.

실험 경계는 Servlet `Principal`의 이름을 owner scope로 사용합니다. HTTP JSON·X-Owner·X-User-Id·Authorization 문자열을 직접 인증으로 바꾸지 않습니다. 현재 앱에는 Principal을 검증·발급하는 인증 계층이 없으므로 실제 HTTP에서 인증 없이 접근하면 401입니다. 정상 흐름의 증거는 **MockMvc가 가상 Principal을 주입한 테스트**이며 실제 로그인 성공 증거가 아닙니다.

#4가 인증을 연결할 때 인증된 안정적 고유 subject, 익명 사용자 거절, 인증 필터 순서, 세션/토큰 수명과 CSRF/CORS 정책을 확정해야 합니다. 단순히 Principal 이름이 형식에 맞는 것만으로 실제 사용자 인증이 완료됐다고 해석하지 않습니다. 이 상태로 공용 배포하지 않습니다.

## HTTP 초안

정의: [별도 OpenAPI 3.1 초안](../api/place-preview-draft.json), 버전 `0.1.0-draft`. 아래 경로는 `/api/experimental` 기준입니다. `/api/v1`의 확정 경로로 승격하지 않았으며 #4/#5 검토 대상입니다.

| POST 경로 | 요청 | 응답 / 의미 |
| --- | --- | --- |
| `/place-previews` | `{}` | `previewId`, `mode=MOCK`. 장소 호출 없이 임시 세션 생성 |
| `/place-previews/{id}/queries` | `generation`, `query` | 비동기 HTTP 응답으로 결과 또는 세션 처리 상태 반환. polling/결과 캐시 없음 |
| `/place-previews/{id}/cancel` | `generation` | `CANCEL_REQUESTED`, `cancelledWork`. 아직 실행하지 않은 세대도 지연 요청 차단 |
| `/place-previews/{id}/close` | `{}` | `CLOSED`, 세션 제거·진행 작업 취소 요청. 재종료는 미존재와 같은 404 |

[가상 검색 요청 예제](../api/examples/place-preview-query.json)는 공개 시범 중심 37.544/127.055·반경 1km·최대 3곳입니다. 실제 위치 추적이나 실제 데이터 fixture가 아닙니다. generation은 1부터 JavaScript 안전 정수 최댓값까지, 반경은 1~20,000m, 표시 상한은 1~3입니다. null·누락·문자열/소수 정수·알 수 없는 필드는 거부합니다. 이 API에는 ETA 입력/검증을 추가하지 않았습니다.

기존 저장 명령과 달리 이 실험 API는 `Idempotency-Key`나 DB snapshot을 사용하지 않습니다. 진행 중 같은 세대/query만 합류하고 완료 세대 재전송은 `ALREADY_FINISHED`이며 장소를 재전달하거나 다시 조회하지 않습니다. 생성은 멱등하지 않으므로 네트워크 오류 시 자동 생성 재시도를 하지 않습니다. 세션 전체 8개·owner당 2개, 동시 가상 adapter 2개·Registry 수명 누적 승인 32회의 제한이 있습니다. 이는 **MOCK sandbox 설정**이며 실제 API 쿼터/비용 승인이나 재시작을 넘는 원장이 아닙니다.

## 화면이 적용할 의미

| 상태 | 의미 / 다음 처리 |
| --- | --- |
| RESULTS | 유효 가상 장소 1~3개. 응답의 previewId·generation을 현재 화면과 대조한 뒤 표시 |
| EMPTY | 정상 0건. 임의 후보 보충/반경 확대 없음. 불량 항목만 남은 응답은 FAILED |
| FAILED | 사업자 실패 또는 잘못된 응답. 실패 종류를 보존하고 정상 0건으로 바꾸지 않음 |
| SUPERSEDED / CANCELLED / TIMED_OUT | 이전 의도/취소/제한 시간 종료. 장소 없음, 자동 재요청 없음 |
| STALE_GENERATION / REQUEST_CONFLICT / ALREADY_FINISHED / CLOSED | 요청 수명·세대 충돌. 캐시 재표시나 자동 세대 증가로 우회하지 않음 |

HTTP 200은 위 처리 결과를 전달했다는 뜻이며 예약 성공이 아닙니다. 잘못된 입력은 400, 검증된 서버 Principal 없음은 401, 다른 owner·미존재·만료 세션은 같은 404, 세션 수 제한은 429, 종료된 Registry는 503입니다. HTTP 성공/오류 모두 활성화된 실험 경계에서 no-store/no-referrer를 적용합니다.

`placeSource`, `reservationMode`, `mode`는 이번 구현에서 모두 MOCK입니다. 장소의 주차·아이 동반·예약 가능은 UNKNOWN, bookable은 false입니다. 전화번호·사업자 ID·원문 좌표·예약 참조를 노출하지 않습니다. fetchedAt은 adapter 시각을 보존하고 expiresAt은 그 시각으로부터 5분입니다. 미래/만료 응답·다른 출처·개수 초과는 INVALID_RESPONSE로 거부합니다. 이 5분은 초기 MOCK 표시 계약이며 실제 데이터 보유 허가나 차량 ETA 유효성을 뜻하지 않습니다.

목적지 변경은 새 generation으로 표현하고 이전 세대의 늦은 결과를 새 화면에 표시하지 않습니다. 뒤로 가기/화면 종료는 취소·종료 요청에 연결해야 하며 실제 Android 연결은 아직 없습니다. HTTP 연결이 끊겼다는 이유만으로 공급자 작업이 종료됐다고 가정하지 않습니다. 여러 요청이 합류할 수 있어 개별 연결 종료를 무조건 공용 작업 취소로 바꾸지 않으며 명시적 취소와 최대 3초의 내부 deadline을 사용합니다. 자동 유휴 정리·재시작/다중 서버 실행 보호는 #17입니다.

## 재현과 검증 증거

```bash
./gradlew -PincludeAndroid=false verify
work/contract-venv/bin/python scripts/check-contract.py --preview-exchanges
work/contract-venv/bin/python scripts/smoke-test.py --check-contract
```

새 환경의 Python 의존성은 [API 안내](../api/README.md#예제와-검증)를 따릅니다. 가상 인증을 사용하는 HTTP 통합 테스트만 실행하려면 `./gradlew -PincludeAndroid=false :server:test --tests '*PlacePreviewApiTest'`를 사용합니다.

- 전체 **244개 실행, 실패/오류/skip 0**: 기존 200 + HTTP 계약 43 + 기본 비활성 1.
- 신규 검증: 출처/모의 예약·UNKNOWN 표시, 정상 0건/전체 불량/실패 8종, DB 4개 테이블 row 수 불변, owner 위조·foreign 접근, JSON/UUID/세대 정수 경계, 세션 수 제한, 동시 합류/충돌, 취소·목적지 변경·늦은 응답·deadline, 응답 시각/출처/개수와 비노출 필드.
- MockMvc의 **가상 HTTP 교환 96건·4개 연산**을 테스트 산출물 `server/build/contract-exchanges/place-preview.json`에 기록하고 Python JSON Schema로 초안과 대조했습니다. 가상 입력/응답만 포함하며 실제 장소 데이터가 아닙니다. build/는 Git 제외입니다.
- JAR smoke는 기본 404·opt-in 무인증/위조 헤더 401과 기존 MOCK HTTP/OpenAPI/H2 재시작/LIVE 시작 차단을 함께 검사합니다. 실제 실행 결과는 [STATUS](STATUS.md)에 기록합니다.
- [로컬 경계 확인용 호출자](../scripts/preview-contract-probe.py)는 127.0.0.1만 사용하고 redirect/proxy를 사용하지 않습니다. 기본 서버에 `python3 scripts/preview-contract-probe.py`, 명시적 sandbox 서버에는 `--enabled`로 401을 확인합니다. 인증을 만들거나 장소 조회를 수행하는 도구가 아닙니다.

초기 컴파일의 FilterRegistrationBean 제네릭 추론 오류를 명시적 타입으로 수정했습니다. 최초 실패 로그와 최종 검증 로그는 Git 제외 work/에 분리합니다. 의존성·migration·기존 API 버전 변경은 없습니다. Android 기기·실제 로그인·실제 장소/전화/AI 추가 실행은 없습니다.

## 남은 #16 완료 조건

1. #4의 실제 인증 주체와 owner 매핑·접근 정책을 합의하고 검증합니다.
2. #5가 생성/결과/취소/종료·오류·출처/예약 모드·만료 상태 계약을 검토하고 실제 화면에 연결합니다.
3. #1의 기기 증거에 맞춰 목적지/ETA freshness를 정의하고 시작/반환 시점에 확인합니다. 원시 ETA를 추측해 변환하지 않습니다.
4. #11에서 실제 데이터 수명/보유·저장·제공 조건을 확인한 뒤 허용 범위만 연결합니다. 추가 실제 품질 검증은 #18과 분리합니다.

#16은 OPEN, PR은 Draft로 유지합니다. 기존 #12/#13의 기반 종료를 이 제품 통합 완료로 해석하지 않습니다.

구현 근거: [Spring MVC 6.2 비동기 요청](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html), [Servlet Principal 인자](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-methods/arguments.html). HTTP 비동기 응답이 인증·제품 계약·저장 허가를 대신하지 않습니다.
