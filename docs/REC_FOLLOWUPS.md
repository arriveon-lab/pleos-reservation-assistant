# 추천·장소 기반 통합 범위와 후속 작업

정리: 2026-11-07 KST. 사용자가 PR #14와 #15의 완료 범위를 통합하고 미완료 작업을 후속 이슈로 분리하도록 승인했습니다. 이 문서와 각 이슈의 최신 범위를 이전 진행 기록보다 우선합니다. 실제 병합 상태는 GitHub를 기준으로 확인합니다.

## 이번 통합 범위

| 이슈 / PR | 검증한 완료 범위 | 포함하지 않는 제품 기능 |
| --- | --- | --- |
| [#12](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/12) / [#14](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/14) | 내부 세션·소유 scope 대조·유휴 만료·동시/누적 실행 상한·트랜잭션 밖 IO, 가상 adapter와 H2 검증 188개 | 로그인 인증·공개 API·차량 화면·자동 정리·재시작/다중 서버 실행 관리 |
| [#13](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/13) / [#15](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/15) | 기본 MOCK의 독립 미저장 시연, 실제 Kakao 1회 성공·3곳 일시 표시·쿼터 1회 집계, 도구 경계 테스트 12개 | 지역 전체 품질·실제 IP 거절 검증·제품 API/화면·예약 연결 |

원래 #12는 서버·화면 계약과 IO 경계 전반, #13은 실제 API와 지역 품질 전반을 포함했습니다. 이번 종료 범위를 위 기반 구현으로 좁히고 아래 미완료 조건을 실제 이슈로 이관했습니다. 기존 요구를 완료로 표시하거나 삭제한 것이 아닙니다. #14는 `Closes #12`, #15는 `Closes #13`만 사용하며 후속 이슈는 `Refs`로 연결합니다.

## 미완료 작업

모두 담당자는 `tiemo0708`이며 기존 #1/#3/#4/#5 담당 범위를 변경하지 않습니다. 새 구현은 통합된 main에서 해당 실제 이슈 번호의 브랜치로 시작합니다. #16은 `feat/16-place-preview-integration`에서 [HTTP 계약 초안·MOCK 통합](REC05_PREVIEW_API.md)을 구현·검증했습니다(서버 244개, 실제 로그인/화면/차량 미연결). 나머지 아래 브랜치는 아직 만들지 않은 계획입니다.

| 이슈 | 이관한 작업 / 협업 | 완료 조건 / 계획 브랜치 |
| --- | --- | --- |
| [#11 REC-02](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11) | 데이터 수명·보관·재사용·전화/음성 공급자 제공·공급자 최종 결정 / #3/#4/#5 | 허용·금지·미확인별 공식 근거와 팀 계약. `docs/11-place-data-policy` |
| [#16 REC-05](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16) | #12/#13의 실제 인증·공개 API·화면·navigation freshness/ETA 연결 / #1/#4/#5 | 신뢰된 인증 주체, OpenAPI·예제·호출자·통합 검증, 실제/모의 표시·수명 계약. `feat/16-place-preview-integration` |
| [#17 REC-06](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/17) | #12의 자동 정리·프로세스 종료·재시작/다중 서버 실행·비용 상한 / #4 | 정리/종료·취소 무시·장애 시 차단 및 실행 메타데이터 계약 검증. `feat/17-preview-lifecycle` |
| [#18 REC-07](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/18) | #13의 추가 지역 표본·누락/중복/0건·실제 IP 제한과 배포 조건 / #4/#5 | 추가 실행 범위/상한 확정, 공급자 집계 대조, 원문 미저장 품질 요약과 제품 인수. `feat/18-place-quality-validation` |

#16은 #4의 인증 계약·#1의 기기 증거·#11의 허용 데이터 범위와 맞춰 진행합니다. #17은 제품 실제 실행 전에 필요한 배포 범위와 실행 보호를 정합니다. #18의 일반 미저장 실시간 조회는 #11 전체 문의 답변이나 #16 전체 완료를 일괄 선행 조건으로 두지 않되, 실행 범위와 무료/유료 상태를 먼저 확인합니다.

## 검증과 실행 경계

PR #14의 188개와 PR #15의 최초 main 기반 156개는 서로 다른 브랜치 결과입니다. PR #14가 통합된 main `8cbe890`과 #15를 함께 검증해 기반 144 + 세션/Registry/H2 44 + 시연 12 = **200개 실행, 실패/오류/skip 0**을 확인했습니다. `verifyAll`의 Android Lint/APK 검사와 JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단도 통과했습니다. Android 단위 테스트는 NO-SOURCE이며 기기 실행 결과가 아닙니다. 실제 통합 검증 기록은 [STATUS](STATUS.md)를 따릅니다.

실제 Kakao 호출은 앞서 승인된 **1회**가 전부입니다. 이번 범위 정리와 병합은 추가 실제 검색·IP/키 설정 변경·전화·예약·AI·유료 작업을 실행하지 않습니다. 제품 서버는 MOCK과 LIVE 시작 차단을 유지하고 실제 결과를 H2 snapshot에 연결하지 않습니다. 장소 응답·키·실제 연락처·결과 캡처는 Git/로그/fixture에 넣지 않습니다.
