# 미리보기 초안 fixture

`recommendation-result.json`은 PR #19 `285087b96e54b3471b126831dfbad382ebfc9d5f`의
`api/examples/place-preview-recommendation-result.json`을 복사한 가상 응답입니다.
공개 v1 계약의 변경/확정이 아니라 별도 **0.2.0-draft** 클라이언트 회귀 검사입니다.

테스트는 식별자·세대·명시 조건을 각 요청에 맞춰 교체하고 고정 시계를 사용합니다.
UNKNOWN·발신/예약 불가, 취소 이후 늦은 응답, 만료와 오류를 검사합니다.
실제 서버/기기 인수와 공동 확인 기록은 `docs/UX26_PREVIEW_VALIDATION.md`에서 구분합니다.
