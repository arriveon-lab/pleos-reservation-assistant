# 서버 작업 지침

- 루트 지침, docs/CODE_CONVENTIONS.md, api/README.md를 읽는다. 명령은 저장소 루트에서 실행한다.
- `./gradlew -PincludeAndroid=false verify`. API/상태/영속화 변경은 `python3 scripts/smoke-test.py --check-contract`까지 실행한다(개발 Python 의존성 필요).
- Controller / WorkflowService / Store / RecommendationEngine의 책임을 지킨다. API 변경은 계약·예제·테스트·호출자를 함께 갱신한다.
- 트랜잭션, 요청 idempotency와 전역 eventId 중복 검사, 원 예약/미확인 변경 보존을 유지한다.
- LIVE/실발신/유료 API는 구체적 사용자 승인과 실연동 보호장치 전까지 활성화하지 않는다. 테스트는 MOCK과 가상 데이터만 사용한다.
- Flyway 적용 이력이 있는 migration을 바꾸지 않고 새 버전을 추가한다. 의존성 변경은 server/gradle.lockfile을 갱신한다.

## Code Review Rules

- 명시적 매장 수락·시간·비용 일치 없이 CONFIRMED가 되는 경로, UNKNOWN 재발신, 비용 승인 누락을 확인한다.
- 상태 검증과 저장의 경쟁 조건, 중복 이벤트로 인한 이중 적용, 실패 시 부분 저장을 확인한다.
- 원 관측 시각을 서버 수신 시각으로 덮어쓰거나 NOW 사실을 ARRIVAL 사실로 취급하는 변경을 지적한다.
