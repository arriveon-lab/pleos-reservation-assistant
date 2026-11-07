# REC-03 미리보기 실행 경계 — 첫 구현

작성: 2026-11-07 KST. 이슈: [#12](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/12). 브랜치: `feat/12-place-preview`. 시작 기준: [PR #10](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/10)의 squash merge `a55de3a`.

**내부 세션 실행기를 가상 adapter로 구현·검증한 단계입니다.** 공개 REST API·사용자 인증·차량 화면·프로세스 재시작 복구는 아직 구현하지 않았습니다. [서버·화면 계약 초안](REC01_CONTRACT_DRAFT.md)의 검토·OpenAPI 확정과 [#11 데이터 이용 조건](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11)이 남아 있습니다.

## 적용한 경계

[PlacePreviewSession](../server/src/main/java/ai/pleos/assistant/PlacePreviewSession.java)은 명시적으로 생성하고 `close()`하는 package-private 내부 타입이며 Spring bean이나 제품 검색 경로에 등록하지 않았습니다. 현재 테스트는 네트워크 없는 가상 adapter를 주입합니다. 기존 `WorkflowService`와 `Store.command()`의 H2 저장·idempotency snapshot은 변경하지 않았습니다.

한 세션은 하나의 화면 수명에 대응하는 초기안입니다. 요청 세대 `generation`은 1부터 증가하며 현재 목적지/조회 의도가 바뀌면 다음 값을 사용합니다. 동일 세대에서 좌표·반경·상한이 바뀌면 다른 요청으로 판정합니다. 서버가 신뢰할 수 있는 소유자·세션 ID·세대 부여와 HTTP 전송 방식은 #4/#5 검토 후 결정해야 합니다. 이 타입은 기존 Idempotency-Key를 대체하는 공개 계약이 아닙니다.

| 상황 | 현재 내부 동작 |
| --- | --- |
| 진행 중 같은 세대·같은 query | 하나의 adapter 실행 결과에 합류. 호출자에게는 공유 결과를 취소/변조할 수 없는 CompletionStage view 제공 |
| 같은 세대·다른 query | REQUEST_CONFLICT, 추가 조회 없음 |
| 이미 완료한 세대 재전송 | ALREADY_FINISHED, 장소 응답 캐시나 자동 재조회 없음. 새 조회 의도는 새 세대로 표현 |
| 더 오래된 세대 | STALE_GENERATION, 추가 조회 없음 |
| 새 세대 도착 | 이전 작업에 interrupt 요청, 이전 결과는 SUPERSEDED. 대기 중 구세대는 제거하고 최신 세대만 대기 |
| 취소 | CANCELLED. 먼저 도착한 취소는 늦게 들어온 같은 세대의 start도 차단. 오래된 취소는 새 요청에 영향 없음 |
| 제한 시간 경과 | TIMED_OUT. 실행 대기부터 결과까지 최대 설정 시간(3초 이하). 큐에 대기하던 요청도 시간 초과 후 실행하지 않음 |
| 정상 응답 | RETURNED + 기존 성공/실패 타입. 0건·부분 불량 수·출처·조회 시각과 실패 종류 보존 |
| adapter 예외/null | 안전한 UPSTREAM_UNAVAILABLE/INVALID_RESPONSE. 원문 예외·장소 정보·키 로그 없음 |
| 세션 종료 | CLOSED, 대기/진행 작업 취소 요청과 실행기 종료. 이후 start도 CLOSED |

반환 결과에는 원래 세대가 포함됩니다. 화면 적용 시에도 현재 세대와 대조해야 합니다. 새 세대보다 먼저 정상 완료가 결정된 응답까지 이미 전달된 뒤 소급 취소할 수 있다는 뜻은 아닙니다.

## 잠금·실행 수와 보유 데이터

- Spring 트랜잭션 또는 transaction synchronization 안에서 `start()`를 호출하면 adapter 실행 전에 거부합니다. 단순 offload로 호출자가 DB 잠금을 잡은 채 기다리는 구조를 숨기지 않습니다.
- adapter는 독립 worker에서 실행하며 `Store`·DB·추천 facts를 참조하지 않습니다. 상태 변경의 짧은 메모리 잠금과 실제 adapter 실행·호출자 continuation을 분리했습니다.
- 세션당 worker 1개, 최신 대기 작업 1개입니다. 취소를 무시하는 adapter가 남아 있으면 새 adapter 호출을 병렬로 열지 않습니다. 대기 작업의 제한 시간도 시작 시점부터 흐릅니다.
- interrupt는 종료 시도이며 이미 전송한 요청의 취소나 쿼터 환불을 보장하지 않습니다. 무시하는 adapter를 강제 종료하지 못하므로 실제 transport에도 독립적인 시간/연결 제한이 필요합니다.
- 완료한 장소 응답은 세션에 캐시하지 않습니다. 마지막 세대·query 한 개는 중복/충돌 검사용으로 `close()`까지 메모리에 남고 결과 객체는 호출자가 받습니다. 현재 가상 데이터만 사용하며, 이 보유 구조가 실제 Kakao 데이터에 허용됐다고 가정하지 않습니다.
- 세션 종료/프로세스 재시작 뒤 이전 실행을 기억하지 않습니다. 영속 claim·분산 중복 방지·전체 서비스 호출 상한·인증/소유권·세션 수 상한은 후속입니다. 이번 내부 세션만으로 운영상 exactly-once 호출을 보장하지 않습니다.

## 실행한 검증

| 검사 | 실행 수 / 확인 사항 |
| --- | --- |
| [PlacePreviewSessionTest](../server/src/test/java/ai/pleos/assistant/PlacePreviewSessionTest.java) | 22개: 동시 합류·외부 view 취소 격리·충돌·구세대·최신 큐·취소/선행 취소·늦은 응답·실행/대기 timeout·8종 실패·예외·종료·부분 불량·입력 경계 |
| [PlacePreviewTransactionTest](../server/src/test/java/ai/pleos/assistant/PlacePreviewTransactionTest.java) | 3개: 실제 H2 트랜잭션 안 start 거부, 조회 대기 중 기존 검색 명령 진행 및 DB 미저장, 다른 트랜잭션의 DB mutex 점유 중에도 preview 완료 |
| 전체 서버 | 기존 144 + 신규 25 = 169개 실행, 실패/오류/skip 0 |
| 패키징 서버 | MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단 통과 |

재현: `./gradlew -PincludeAndroid=false verify`, `work/contract-venv/bin/python scripts/smoke-test.py --check-contract`. Python 환경이 없으면 [API 검증 안내](../api/README.md#예제와-검증)를 따릅니다. 로그·XML 요약은 Git 제외 `work/rec03-preview-*`에 있습니다. Android 기기·실제 장소 API 검증은 이번 결과에 포함하지 않습니다.

## 다음 구현과 검토

1. #4/#5와 세션 소유·요청 세대·진행/완료 재전송·실패 복구·공개 오류/화면 상태를 확정합니다. navigation freshness와 ETA의 시작/반환 시점 재검증을 함께 정의합니다.
2. 인증·리소스 수명·서비스 전체 호출/세션 상한과 재시작 시 중복 실행 정책을 설계합니다. 장소 데이터를 무단 저장하는 claim/snapshot을 만들지 않습니다.
3. 확정한 공개 계약을 OpenAPI·예제·호출자·테스트에 함께 반영합니다. 제품 UI는 #5에서 구현하며 실제 장소와 모의 예약을 구분합니다.
4. #11의 사용 조건을 충족한 다음 #13에서 실제 TLS/키/쿼터·지역 품질을 검증합니다. 현재 실제 Kakao 조회·전화·유료 실행은 0회이며 LIVE 시작 차단을 유지합니다.

#12는 OPEN으로 유지하며 이번 첫 구현을 이슈 전체 완료나 실제 서비스 연동으로 처리하지 않습니다.
