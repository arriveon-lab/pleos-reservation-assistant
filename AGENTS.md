# Pleos 예약 비서 작업 지침

- 먼저 README.md, CONTRIBUTING.md, docs/STATUS.md를 읽고 검증 수준을 유지한다.
- 팀 문서 목차는 docs/README.md. 기능은 docs/FUNCTIONAL_SPEC.md, 역할은 docs/ISSUE_GUIDE.md, 제안 스키마는 docs/ERD.md를 참고한다. docs/DESIGN_PROPOSALS.md의 미확정 제안을 현행 API·구현으로 취급하지 않는다.
- 코드 규칙은 docs/CODE_CONVENTIONS.md, 검증 환경은 docs/AGENT_HARNESS.md를 따른다. android/ 또는 server/ 수정 전 해당 디렉터리의 AGENTS.md도 읽는다.
- 브랜치를 만들거나 바꾸기 전 이 파일, 기여 가이드, 로컬·원격 브랜치 이름을 확인한다. 기존 관례를 따르며 codex/ 접두사를 임의로 붙이지 않는다.
- 기본 브랜치는 main. 작업 브랜치는 type/<GitHub 이슈번호>-<topic>, 커밋/PR 제목은 type(scope): 설명. 상세 규칙은 CONTRIBUTING.md.
- Java 21 + Spring Boot. 서버 테스트: `./gradlew verify`.
- 전체 포맷: `./gradlew -PincludeAndroid=false format`. Android: `./gradlew -PincludeAndroid=true verifyAndroid`, 전체: `verifyAll`. 실행/영속화 검증: `python3 scripts/smoke-test.py`.
- API 변경은 api/openapi.json·예제·호출자·테스트를 함께 갱신한다. API 계약 검증 절차는 api/README.md.
- docs/STATUS.md를 현재 상태 기준으로 삼고 인계 시 outputs/CODEX_HANDOFF.md도 갱신한다.
- 실제 전화 발신·유료 API·번호 구매는 사용자에게 대상, 목적, 예상 비용을 제시하고 명시적 승인 후 실행한다.
- 기본 실행은 MOCK. 실통화 번호나 키를 fixture에 넣지 않는다. SDK API를 추측하지 않는다.
- 차량 API 선택은 docs/INTEGRATIONS.md의 AAOS 표준 우선·실행 시 가용성 확인 기준을 따른다. 차종명으로 지원 여부를 단정하지 않는다.
- 문서상 지원, 빌드/테스트 확인, 기기 실행 확인, 미검증을 구분한다.
- 빈자리, 예약 가능, 예약 확정은 별도 상태다. 불명확한 결과는 UNKNOWN으로 보존하고 자동 재발신하지 않는다.
- 변경 요청 중 원 예약 시간을 유지한다. 명시적 수락과 시간·비용 일치가 확인되어야 변경한다.
- 출처·관측 시각·적용 시각·유효기한·확실성을 보존한다. NOW 대기를 ARRIVAL 대기로 바꾸지 않는다.
- 세션별 필수 동행 조건을 취향보다 우선한다. 학습 동의와 예약/변경 권한은 별도다.
- 테스트에 실제 가족/위치/연락처/녹음 정보를 사용하지 않는다.
