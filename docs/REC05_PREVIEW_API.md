# REC-05 미리보기 HTTP 계약 초안과 MOCK 통합

최신: 2026-11-09 KST. 담당 `tiemo0708`, [#16](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16), 브랜치 `feat/16-place-preview-integration`, [Draft PR #19](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/19). 인증·추천 평가기·SDK 계획이 병합된 main `35e22a8`을 반영했습니다. **#16은 OPEN이며 제품 화면·기기 ETA·실제 데이터 조건 인수는 남아 있습니다.**

화면 담당이 먼저 읽을 문서는 [#26 화면 인수안](REC05_UI_HANDOFF.md)입니다. 요청/응답 예제·필드·세대·취소·만료·공동 확인 항목을 포함합니다. 계약은 팀 검토 중인 실험 초안이며 `/api/v1` 경로로 승격하지 않았습니다.

## 실행·인증 경계

기본 `app.place-preview.enabled=false`에서는 실험 경로가 404입니다. 명시적으로 켜도 전용 가상 adapter만 생성하며 Kakao 키나 다른 장소 공급자 설정을 사용하지 않습니다. 장소 결과·추천 계획을 Store/DB·명령 snapshot에 연결하지 않습니다. 기존 제품 계약 1.1.0과 LIVE 시작 차단을 유지합니다.

main의 익명 세션 발급/검증 필터가 실제 서버 발급 Bearer 토큰을 검증하고, 안정적인 Servlet Principal을 owner scope로 사용합니다. 갱신 후에도 같은 소유권을 유지합니다. HTTP JSON·X-Owner·X-User-Id·임의 Authorization 문자열로 주체를 만들지 않습니다. 다른 owner·미존재·만료 세션은 동일하게 404입니다. 앱의 안전한 토큰 보관·자동 갱신 연결은 #26 범위입니다.

## HTTP 초안 0.2.0-draft

정의: [별도 OpenAPI 3.1](../api/place-preview-draft.json). 아래 경로는 `/api/experimental` 기준입니다. 기존 0.1.0-draft의 목록 조회 요청을 유지하면서 명시적 조건과 추천 계획을 추가했습니다. 변경은 공개 v1 명세의 별도 초안 metadata에도 연결합니다.

| POST 경로 | 요청 | 응답 / 의미 |
| --- | --- | --- |
| `/place-previews` | `{}` | `previewId`, `mode=MOCK`. 장소 호출 없이 임시 세션 생성 |
| `/place-previews/{id}/queries` | `generation`, `query`, 선택적 `requirements` | 비동기 결과/처리 상태, 조건을 명시하면 추천 계획 포함. polling/결과 캐시 없음 |
| `/place-previews/{id}/cancel` | `generation` | CANCEL_REQUESTED, cancelledWork. 아직 실행하지 않은 세대도 지연 요청 차단 |
| `/place-previews/{id}/close` | `{}` | CLOSED, 세션 제거·진행 작업 취소 요청. 재종료는 미존재와 같은 404 |

[기존 가상 요청](../api/examples/place-preview-query.json), [조건 포함 요청](../api/examples/place-preview-recommendation-query.json), [추천 포함 가상 응답](../api/examples/place-preview-recommendation-result.json)을 함께 제공합니다. 공개 시범 중심·반경 1km·최대3개인 설명용 입력이며 실제 개인 위치/장소 응답이 아닙니다.

generation은 1부터 JavaScript 안전 정수 최댓값까지, 반경은 1–20,000m, 표시 상한은 1–3입니다. requirements가 있으면 인원·최대 대기·필수 조건·취향을 모두 명시해야 합니다. 누락/null은 기존 목록 조회로 처리하고 recommendationPlan=null을 반환합니다. 부분 조건·중복 항목·알 수 없는 필드는 거부합니다. 인원이나 ETA를 추측하지 않습니다.

진행 중 같은 세대/query/**동등한 requirements**만 한 가상 조회에 합류합니다. 같은 세대에 조건을 바꾸면 REQUEST_CONFLICT입니다. 완료 세대 재전송은 ALREADY_FINISHED이며 결과를 재전달하거나 다시 조회하지 않습니다. 조건 변경은 새 사용자 의도의 세대를 사용합니다. 생성은 멱등하지 않으므로 실패 후 세션을 자동 재생성하지 않습니다.

세션 전체8개·owner당2개, 동시 가상 adapter2개·Registry 수명 누적 승인32회, 요청 deadline 최대3초의 MOCK sandbox입니다. 재시작을 넘는 원장·자동 유휴 정리·다중 서버 보호는 #17입니다. 실제 API 쿼터·비용 승인으로 해석하지 않습니다.

## 응답·화면 의미

| 상태 | 의미 / 다음 처리 |
| --- | --- |
| RESULTS | 가상 후보1–3개. 현재 previewId/generation/화면 수명과 유효기간 확인 후 표시 |
| EMPTY | 공급자가 정상0건 반환. 임의 후보 보충·반경 확대 없음 |
| FILTERED | 응답은 있었으나 계산한 검색 범위에 후보 없음. 공급자0건과 구분 |
| FAILED | 실패 종류 보존. 실패를0건으로 바꾸지 않음 |
| SUPERSEDED / CANCELLED / TIMED_OUT | 이전 의도/취소/시간 초과. 장소·추천 계획 없음, 자동 재요청 없음 |
| STALE_GENERATION / REQUEST_CONFLICT / ALREADY_FINISHED / CLOSED | 수명/세대 충돌. 캐시 표시·자동 세대 증가로 우회하지 않음 |

HTTP200은 처리 결과 전달이며 예약 성공이 아닙니다. 입력400, 인증401, 다른 owner/미존재/만료404, 세션 상한429, Registry 종료503입니다. 성공·오류 모두 활성 실험 경계에서 no-store/no-referrer를 적용합니다.

현재 placeSource/reservationMode/mode는 모두 MOCK입니다. 주차·아동 동반·예약 가능은 UNKNOWN, bookable=false입니다. 전화번호·사업자 ID·좌표·예약 참조를 응답에 싣지 않습니다. fetchedAt과 expiresAt은 원 조회 시각과 그로부터5분이며 미래/만료/출처 불일치·상한 초과를 거부합니다. 표시 유효기간이 실제 데이터 보관 허가나 매장 사실/차량 ETA의 최신성을 뜻하지 않습니다.

조건을 명시한 응답은 [추천 평가기](REC08_RECOMMENDATION_PLAN.md)의 Plan을 recommendationPlan에 연결합니다. places와 candidates는 같은 후보·순서이며, 이 경로의 표시 거리는 계산한 직선거리입니다. 근거·누락 정보·UNKNOWN 질문을 포함하지만 모든 후보는 NEEDS_CONFIRMATION, bookable/inquiryDispatchAllowed=false입니다. ordinal은 예약/전화 ID가 아닙니다. FILTERED에는 빈 계획이 있고 FAILED·수명 종료 상태에는 null입니다.

목적지/조건 변경·뒤로 가기·화면 종료 시 앱은 먼저 기존 응답을 무효화하고 취소/종료 요청을 보냅니다. HTTP 단절만으로 공급자 작업 종료를 가정하지 않습니다. Android lifecycle·표시·토큰 보관·주행 제한은 #25/#26의 별도 검증 대상입니다.

## 재현과 검증

```bash
./gradlew -PincludeAndroid=false format verify
work/contract-venv/bin/python scripts/check-contract.py --preview-exchanges
work/contract-venv/bin/python scripts/check-recommendation-plan.py
python3 -m unittest discover -s scripts/tests -p 'test_preview_session_demo.py'
work/contract-venv/bin/python scripts/smoke-test.py --check-contract
```

새 환경의 Python 의존성은 [API 안내](../api/README.md#예제와-검증)를 따릅니다. 신규 검사는 조건 검증·추천/목록 정렬·UNKNOWN·비노출·DB 불변·같은 세대 조건 충돌·새 세대 변경·화면 종료/취소/늦은 결과·만료를 포함합니다. 실제 실행 건수·JAR 검증 결과는 [STATUS](STATUS.md)에서 확인합니다.

과거 검증은 첫 HTTP 초안244개, 인증 main 반영272개이며 이번 추천 연결의 테스트 수와 구분합니다. 모든 장소는 가상 데이터입니다. 기본404/opt-in401, 실제 서버 발급 토큰의 소유권·갱신·폐기와 기존 JAR MOCK/UNKNOWN·H2 재시작·LIVE 차단을 유지합니다. 실제 외부 장소·전화·AI 호출은 없습니다.

## 남은 인수

1. #26에서 초안0.2.0의 요청·응답·오류·화면 필드를 검토하고 합의 결과를 #16에 남깁니다.
2. #25/#26에서 인증 보관·주행 제한·세대/취소/만료를 기기 화면에 연결하고 공동 확인합니다.
3. #1의 기기 증거로 navigation freshness/ETA 의미를 정의합니다. 서버의 source=PLEOS 거부를 임의로 해제하지 않습니다.
4. #11의 실제 데이터 수명/저장/제공 조건을 인수하고 허용 경로만 연결합니다. 운영 수명은 #17, 실제 IP/품질은 #18입니다.

구현 근거: [Spring MVC6.2 비동기 요청](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html), [Servlet Principal 인자](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-methods/arguments.html). HTTP·토큰 검증이 기기 실행이나 데이터 이용 허가를 대신하지 않습니다.
