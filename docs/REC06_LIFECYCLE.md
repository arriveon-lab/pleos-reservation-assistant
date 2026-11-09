# REC-06 미리보기 자동 정리와 실행 관리

2026-11-09 충돌 해소: 인증 main `1b37bc2` 반영 후 서버239개(기반228+정리11)와 JAR 인증/계약/재시작/LIVE 차단을 검증했습니다. 아래 독립211개는 이전 기준 기록입니다. 전역 원장/보존 정책을 새로 구현하거나 합의한 것은 아닙니다.


작성: 2026-11-08 KST. 담당 `tiemo0708`, [#17](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/17). 브랜치 `feat/17-preview-lifecycle`, 기준 main `9518b6a`. 다른 팀원의 #21이나 미병합 #19 코드를 포함하지 않습니다.

## 완료한 단일 프로세스 수명 관리

`PlacePreviewRegistry(adapter, limits)`는 단조 시간으로 유휴 세션을 자동 정리합니다. 정리 간격은 idleTimeout/4를 기준으로 1ms~1초 사이입니다. fixed-delay 단일 daemon 작업이며 중복 실행하지 않습니다. JVM/OS 지연이 가능하므로 정확한 시각의 파기를 보장하는 실시간 타이머로 취급하지 않습니다. 요청 시의 기존 lazy 만료 검사도 유지합니다.

만료 시 세션을 제거하고 진행 결과를 CLOSED로 끝내며 worker에 취소를 요청합니다. 이미 전송된 요청의 취소·과금 환불을 보장하지 않습니다. 취소를 무시한 adapter가 실제로 반환하기 전에는 공유 슬롯을 유지합니다. 만료·취소·세션 재생성은 Registry 수명 누적 승인 예산을 환급하지 않습니다.

`Registry.close()`는 반복/경합 호출에 안전하며 scheduler와 세션 실행기를 종료합니다. 종료 후 주기/대기 작업을 다시 실행하지 않습니다. continuation을 상태 잠금 안에서 실행하거나 cleanup 스레드에서 자기 종료를 기다리지 않아 재진입 교착을 피합니다. continuation은 짧고 비차단이어야 하며 uncooperative IO를 강제 종료할 수 있다는 뜻은 아닙니다.

배경 정리에 RuntimeException이 발생하면 원문/owner/query를 로그에 넣지 않고 `hasCleanupFailed`를 남긴 뒤 Registry를 닫아 새 실행을 차단합니다. 타이머만 조용히 멈춘 채 계속 요청을 받지 않습니다. `isCleanupTerminated`는 내부 executor 종료 상태입니다.

Spring 수명 소유자는 Registry를 `destroyMethod=close`로 등록합니다. 현재 main에는 공개 미리보기 bean/endpoint를 추가하지 않습니다. #19의 기존 bean도 같은 실제 시간 2인자 생성자와 close를 사용하므로 통합 시 자동 정리를 적용할 수 있습니다. 별도 Spring context 종료 테스트로 close/타이머 해제를 검증했습니다.

3인자 synthetic ticker 생성자는 기존 결정적 테스트용이며 자동 타이머 없이 명시적 reap을 사용합니다. 4인자 생성자는 실제 타이머와 가상 단조 시간/간격의 경계·장애 테스트용입니다. 실제 수명 소유자는 기본 2인자 생성자를 사용해야 합니다.

## 검증

- `./gradlew -PincludeAndroid=false format verify`: 기존 main 200 + cleanup 11 = **211개 실행, 실패/오류/skip 0**.
- 신규 11개: 요청 없는 자동 만료, 단조 시간 갱신·정확한 유휴 경계, 취소 무시 작업의 슬롯 유지, 만료 후 예산 재사용 거절, callback의 Registry 종료 재진입, 타이머 실패 시 차단, 동시 종료/정리, Spring context 종료, 잘못된 간격 3종.
- JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단은 [STATUS](STATUS.md)의 실제 결과를 따릅니다. API·Model·의존성·migration 변경 없음. Android 기기·실제 공급자/전화/유료 실행 없음.
- 초기 테스트의 overloaded close 메서드 참조와 checked interruption 컴파일 오류를 수정한 뒤 전체 검증을 통과했습니다. 실패/성공 로그는 Git 제외 work/에 구분합니다.

## 실행 수의 의미

| 값 | 현재 의미 | 보장하지 않는 것 |
| --- | --- | --- |
| admission/dispatches | Registry가 승인한 adapter 실행 횟수. 실패/취소로 환급 없음 | 실제 HTTP/공급자 과금 횟수와 일치 |
| runningCalls | adapter가 아직 반환하지 않은 동시 작업 수 | 취소 응답과 공급자 처리 종료의 일치 |
| HTTP 전송 수 | transport에서 별도로 확인할 값 | 내부 admission으로 대체 불가 |
| 공급자 사용량/비용 | 공급자 콘솔/계약과 실행 기록의 대조 값 | 유료 설정·잔량 미확인 상태의 비용 보장 |

현재 예산은 **Registry 인스턴스 수명**입니다. 재생성 시 초기화되고 다른 서버와 공유되지 않습니다. 이 변경으로 재시작/다중 서버 exactly-once나 전역 비용 상한을 해결했다고 처리하지 않습니다. 제품 MOCK/LIVE 차단을 유지하며 실제 실행을 활성화하지 않습니다.

## 서버 담당자와 확정할 원장 제안

다음은 #4/#11 검토용이며 DB/운영 배포에 적용하지 않았습니다.

| 제안 메타데이터 | 목적과 제한 |
| --- | --- |
| approvalId·deploymentScope | 서버 발급 실행 승인/배포 범위. 인증 토큰·장소 ID·개인 식별자 재사용 없음 |
| maxDispatches·maxCostKRW | 명시적 실행/비용 상한. MOCK/무료 검증은 0원, 불명확하면 차단 |
| ordinal·admissionState | 원자적 예약/사용 기록. 전송 여부 미확인은 UNKNOWN, 자동 환급/재시도 없음 |
| admittedAt·finishedAt | 대조 시각. 보존/삭제 주기는 서버 담당과 합의 전 |
| supplierUsageObservation | 필요하면 승인 범위의 집계만 대조. 매장별 응답/사실 저장 없음 |

제안 순서: 원장에서 상한 예약 → DB 트랜잭션 밖 IO → 확인된 결과만 대조. crash/응답 유실은 UNKNOWN으로 남기고 새 승인을 자동 발급하지 않습니다. 시작 시 원장 확인 불가/불확실한 lease는 실제 실행을 차단합니다. 단일 인스턴스 MVP도 중복 시작과 재시작 예산을 강제하는 배포/저장 경계가 필요하며 문구만으로 제한했다고 처리하지 않습니다.

장소 응답·query 좌표·상호·연락처·키·토큰·검색 snapshot을 원장에 복사하지 않습니다. 영속 원장/분산 잠금/보존 정책은 구현·합의 전입니다.

## 남은 입력

#4의 배포 범위·원장/승인 수명·재시작/장애 경계, #11의 데이터 보유 조건, #19 main 통합 후 HTTP/Spring 수명 재검증이 남아 있습니다. #17은 OPEN/PR Draft로 유지하며 독립적으로 할 수 있는 자동 정리·종료·슬롯/예산 회귀 검증과 설계를 먼저 완료했습니다.

근거: [JDK 21 ScheduledThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html).
