# UX-01 Pleos 에뮬레이터 실행 검증

검증: 2026-11-08~09 KST, #5 / Draft PR #27 / `feat/5-mock-vehicle-ui`. 실제 adb 입력·UI hierarchy·화면 캡처와 loopback 서버 요청을 대조했습니다. CI/문서만으로 기기 통과를 판정하지 않았습니다. 모든 데이터는 가상이며 실제 장소 API·전화·예약·유료 호출은 0회입니다.

## 환경과 증거 보존

- Pleos Connect v2.1.0 AVD, API 34, arm64, RAM 4096MB, cold boot, 2560×1440/160dpi. 앱 `ai.pleos.assistant.mock`, debug APK를 `adb install -r`로 갱신했습니다. 기존 앱 데이터와 서버는 삭제하지 않았습니다.
- 실제 실행 코드: `852326e`. 설치된 APK와 로컬 빌드의 hash 일치를 확인했습니다. 실제 실행 APK SHA-256: `9bfbed18f4406eb2b186c9c48f44523a837496cfdb618380dec290b46465709f`.
- 서버 `127.0.0.1:18080`의 `/api/v1/status`: MOCK, externalCallsEnabled=false, contractVersion=1.0.0, FICTIONAL_FIXTURES. 기본 연결은 `adb reverse tcp:8080 tcp:18080`입니다.
- 기본 GPU 실행은 종료 코드 139, swiftshader는 시스템 DrivingView 충돌/검은 화면으로 실패했습니다. `-gpu host -feature -Vulkan -no-snapshot-load -no-snapshot-save -memory 4096 -no-audio`로 실행한 환경에서 아래 검증을 수행했습니다. AVD 영구 설정은 바꾸지 않았습니다. [공식 GPU 문제 해결 안내](https://developer.android.com/studio/run/emulator-troubleshooting).
- 에뮬레이터 시스템 시간대는 America/Los_Angeles, 앱 예약 표시는 KST입니다. epoch는 호스트와 약 1초 이내로 일치했고 UI 시간대 차이를 서버 시각 오류로 처리하지 않았습니다.
- 원본 PNG와 같은 시점 UI XML: Git 제외 `captures/ux01-emulator/`. 파일별 hash는 그 폴더의 `manifest.json`. 빌드·smoke·요청 메타데이터·adb 도구: Git 제외 `work/ux01-emulator/`. 이 로컬 증거는 GitHub에 자동 첨부되지 않으며 다른 클론에는 없습니다. APK·SDK·AVD·화면 바이너리를 소스에 넣지 않았습니다.

## 절차·기대 결과·실제 결과

아래 파일명은 `captures/ux01-emulator/` 기준이며 확장자는 `.png`(화면), `.xml`(UI)입니다. 01~05는 기존 UI 진단, 06~10은 중간 수정본, 11 이후는 위 최종 APK입니다. UNKNOWN 변경이 남은 상태를 임의 초기화하지 않아 최종 APK에서 새 여정을 다시 만들지는 않았습니다.

| 절차 | 기대 결과 | 실제 결과와 증거 |
| --- | --- | --- |
| 초기 실행 → UNKNOWN → 모의 정차 → 서버 연결 | UNKNOWN 입력/승인 차단, MOCK 연결 확인 후 조작 | 초기 차단 확인(01), 모의 정차/연결 후 검색 성공(04). 최종 연결 상태 문구와 복귀 UNKNOWN은 22 |
| 조건 성인 3·아동 1·주차 필수 → 검색·후보 비교 | 요청 인원 반영, 후보 3개와 누락/예약 가능 구분 | 3개 후보 스크롤 확인(04), 변경 인원이 승인창에 표시(09~11). 가상 서울숲 파스타 선택 |
| 누락 정보 문의 → 모의 ANSWERED → 최신 상태 GET | 수락/예약과 분리된 사실 갱신 | 문의 뒤 GET으로 missing 없음/bookable 예(09). 오래 대기해 만료된 첫 문의는 08에 기록, 최신으로 위장하지 않고 새 검색/문의 수행 |
| 예약 내용 확인 | 매장·인원·시각·0원 정책 별도 승인 | 성인 3/아동 1, 11-09 01:03 KST, 최초+1시간/비용 상한 0원, 수락 전 미확정 표시(11) |
| 승인 더블 탭 + 예약 POST 응답 한 번 유실 | 새 요청 중복 없음, 미완료 요청 보존 | 서버는 200 처리, 앱 응답만 끊음. 사용자 재전송 전 POST 1회, pending 안내(12) |
| 응답 유실 후 force-stop·재실행 | 새 예약 없이 pending 유지, UNKNOWN 차단 | pending 복원·재전송 비활성(13), 자동 복귀 요청은 GET만 발생 |
| 모의 정차 → 보관한 동일 요청 재전송 | 같은 키/본문/예약으로 복구 | 두 POST의 keyHash/bodyHash/resultId/responseHash 전부 동일. PENDING 복구(14), 아직 확정 아님 |
| 별도 모의 수락 | PENDING과 CONFIRMED 구분 | 수락 버튼을 별도로 눌러 01:03 확정/비용 0원(15) |
| 변경 승인창 → 설정 앱 → 복귀 | 승인창 닫기·UNKNOWN 전환, POST 없음 | 16 → 17a → 17b 확인. HOME 키만 보낸 17은 앱을 가리지 않아 검증 통과로 세지 않음 |
| 모의 정차 → 15분 변경 승인 더블 탭 | 0원·원 시각 보존, 변경 명령 1회 | 변경 POST 1회, 기존 01:03/요청 01:18/비용 0원(18) |
| 모의 주행 제한 → 비활성 수락 버튼 터치 | 입력/승인 차단, 상태/POST 불변 | 버튼 enabled=false(19), 실제 좌표 터치 후 그대로(20), 추가 POST 없음 |
| 모의 정차 → 모의 결과 미확인 | UNKNOWN 보존, 원 예약과 요청 병기, 재변경 차단 | 01:03 유지·01:18 결과 미확인·0원·변경 버튼 없음(21) |
| force-stop·재실행 → 자동 GET | 동일 예약/미확인 변경 복구, 새 POST 없음 | UNKNOWN 주행 상태, 기존 시각/미확인 변경 그대로(22) |
| 사실 상세 펼침·스크롤·접기 | 출처/관측/적용/만료 접근 가능, 요약 복귀 | 최종 APK의 상세 정보와 스크롤/접기 확인(23~24) |

### 응답 유실 실험

로컬 테스트 프록시 127.0.0.1:18081 → 18080만 사용했고 시작 시 MOCK/외부 호출 차단/1.0.0을 확인했습니다. 예약 POST를 서버에 전달하고 200 본문을 받은 뒤 최초 한 번만 앱 연결을 끊었습니다. POST마다 1초 지연을 주고 승인 버튼을 연속 두 번 눌렀습니다. 시험 중에만 reverse 8080→18081로 연결했고 종료 후 8080→18080으로 복구했습니다.

`work/ux01-emulator/proxy-events.jsonl`에서 POST는 총 5개: 최초 예약(응답 유실), 명시적 동일 예약 재전송, 모의 예약 수락, 변경, 모의 UNKNOWN입니다. 비활성 버튼 터치·설정 앱 복귀·두 차례 force-stop 복귀는 추가 POST를 만들지 않았습니다. 재전송 전후 예약 ID는 동일하며 다음 hash도 같습니다.

- keyHash: `52daaba54163e1c36077e758902ef61750c01d3c23a49d8c98b6f7cdda7bd0f0`
- bodyHash: `c31d95f78d29c18217f462b9bb6a825bfb8326d74af08000aa2c199f2597f335`
- responseHash: `e4b85b87ef3dc782768eae41453e600dae714d9c7aa6b7754c0dd8cc17628c37`

## 발견과 수정

- 작은 회색 본문/승인창과 세로로 긴 개발 버튼: 본문 24sp, 제목 32sp, 사실 20sp, 버튼 22sp/최소 72dp, 승인창 제목 28sp/본문 24sp로 조정하고 대비를 높였습니다. 모의 상태 버튼은 가로 배치했습니다. 현재 Pleos 창에서 주요 문구와 승인 항목이 잘리지 않음을 확인했습니다. 실제 차량 거리/눈부심/다른 해상도 가독성 인증은 아닙니다.
- 문의 결과 조작이 긴 후보 목록 밑에 숨어 있고 매번 맨 위로 돌아가던 문제: 현재 작업/예약 결과를 위에, 조건/사실을 접는 구조로 바꾸고 같은 단계 갱신에서는 스크롤을 보존합니다. 단계 전환은 결과를 찾기 쉽도록 위로 이동합니다.
- 연결 여부·상태 코드가 불명확: MOCK 연결 안내와 한국어 작업/상태/사실 이름, NOW/ARRIVAL·빈자리/예약 가능 차이를 표시합니다.
- 재검색에 이전 문의가 남는 문제: 성공한 새 검색은 완료된 문의 연결을 제거합니다. PENDING/UNKNOWN 문의 중에는 재검색·추가 문의·예약 버튼을 차단합니다. core 회귀 테스트에서 동일 trip/갱신 인원 유지와 미해결 문의 우회 차단을 확인했습니다. 이 마지막 재검색 수정은 core 테스트 증거이며 최종 APK에서 새 여정 반복 검증은 하지 않았습니다.

## 빌드·자동 검사

- `./gradlew -PincludeAndroid=true format verifyAndroid` 및 최종 `format verifyAll` 성공. 최종 UX 테스트 **14개 실제 실행**, 실패/오류/skip 0. 기존 서버 200개는 UP-TO-DATE이며 이 변경에서 재실행했다고 세지 않습니다. Android 단위 테스트는 NO-SOURCE; 실제 UI 조작은 위 표와 구분합니다.
- `python3 scripts/smoke-test.py` 성공: 패키징 JAR MOCK HTTP·Flyway·H2 재시작 영속화·LIVE 시작 차단. 선택적 `--check-contract`는 이번 실행에 포함하지 않았습니다. 공개 API/서버 변경 없음.
- 로컬 Lint: 앱 오류 0/AndroidX 갱신 경고 2, probe 오류 0/경고 0. 이전 원격 Lint는 앱 경고 3/probe 경고 1(targetSdk 포함)이었으며 환경별 결과를 구분합니다. 버전/억제/baseline 변경 없음. 최신 원격 결과는 PR에 기록합니다.

### 최신 main과 원격 CI 차단 (2026-11-09)

원격 main은 `1b37bc2`이며 [인증 PR #21](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/21)이 병합돼 계약 1.1.0이 됐습니다. 이 실행 검증은 **작업 브랜치의 기존 1.0.0 서버/APK 조합**에 한정하며 최신 main 통합 검증이 아닙니다. 앱은 1.1.0을 의도적으로 거절합니다. #26의 인증 호출자 통합 선행 조건은 충족됐지만 앱 통합 자체는 미완료입니다.

PR #27은 `CONFLICTING`/`DIRTY`이고 `git merge-tree`로 `docs/STATUS.md`, `outputs/CODEX_HANDOFF.md` 충돌을 확인했습니다. 새 커밋의 check/run은 생성되지 않았습니다. **원격 CI 통과나 실패 결과가 아니라 미실행/차단**이며 이전 커밋의 성공을 재사용하지 않습니다. CONTRIBUTING의 공유 브랜치 force push 금지·rebase 조율 규칙을 유지했고 main/다른 담당자 코드를 덮어쓰거나 인증을 우회하지 않았습니다. #26에 양쪽 문서 이력 보존·브랜치 통합 방식 조율·1.1.0 호출자/복구 통합 후 최신 main CI와 에뮬레이터 재검증을 연결합니다. 충돌 해소만으로 인증 호환 완료가 되지는 않습니다.

## 미검증과 후속

- #25: **실제 CarUxRestrictions 미연동**. 위 UNKNOWN/RESTRICTED는 앱 개발용 모의 정책 검증입니다. 실제 차량 상태 초기값/콜백/서비스 단절·listener 해제·회전/늦은 callback·실차/다른 해상도·release 서명/설치·영상은 미검증입니다. 화면 캡처를 영상으로 세지 않습니다.
- #26: 인증 1.1.0/토큰·미리보기·유료 변경·저장 손상/유실·거절 요청 종료 UX는 미구현/미검증입니다. 현재 MOCK 1.0.0 복구 결과를 인증 복구 통과로 확대하지 않습니다.
- 별도 예약의 거절/예약 UNKNOWN, 변경 명시적 수락 후 새 시각 확정, 회전, OS에 의한 메모리 회수, 서버 장애/타임아웃 조합은 이번 에뮬레이터에서 검증하지 않았습니다. 응답 유실은 예약 응답 1회에 한정됩니다.
- #5는 OPEN, PR #27은 Draft 유지. 원래 전체 완료 조건을 충족하지 않았으며 병합/자동 병합하지 않습니다. 기존 가상 예약과 UNKNOWN 변경은 보존하므로 앱을 새 예약으로 초기화하지 않습니다.
