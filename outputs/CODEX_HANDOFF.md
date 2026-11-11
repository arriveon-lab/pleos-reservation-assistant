# Pleos 차량용 AI 예약 비서 — 작업 인계

## 2026-11-11 main 286f2a1 문서 통합·PR47 충돌 해소

사용자 승인으로 최신 main286f2a1을 feat/1-navi-probe에 이력 보존 merge로 반영했습니다. STATUS/HANDOFF의 차량 관측·서버 인수 기록과 main의 전화 업체 검토 기록을 모두 보존했습니다. 양쪽 신규 행 보존 검사와 관련 로컬 링크/diff/명명 검사 통과. 이번 main 추가분은 문서뿐이므로 코드 빌드·서버/기기 테스트는 재실행하지 않았으며 과거 통과를 새 실행으로 세지 않습니다. main 자체/기존 커밋 재작성·force push 없이 작업 브랜치만 일반 Push하며 PR Draft/#1 미완료·PLEOS 차단을 유지합니다.


## 2026-11-11 서버 답변 반영·ETA 없는 목적지 추천 인수 구분

[새 서버 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6771962584)와9709887 인계 문서를 확인했습니다. [앱 인수안](../docs/VEH01_APP_HANDOFF.md)에 기존 Navigation의 source 거절 순서/INVALID_TIME/STALE_NAVIGATION/INVALID_REQUEST와 정확한 시간 경계(now<eta<=now+86400, now-300<=observedAt<=now+5)를 반영했습니다. 로컬 WorkflowService도 정적으로 대조했고 HTTP 실행으로 세지 않습니다.

#53 목적지 추천은 eta/observedAt 없는 별도 계약이므로 ETA 미검증 때문에 목적지 추천 검토까지 막지 않습니다. 목적지 현재 경로 연관성·view/generation 무효화는 여전히 앱 공동 인수이며 서버/UX 코드는 수정하지 않았습니다. ETA 사용하는 예약/지연 통보의 PLEOS 차단은 유지합니다.

[후속 검증 절차](../docs/VEH01_REMAINING_VALIDATION.md)에 도착·단절·미지원·늦은 응답별 필요한 환경/판정 조건과 가상 경계 예제를 정리했습니다. 공식 Console 안내에서 내비 좌표/경로 재생 절차를 확보하지 못했으며 실제 기능 부재라고 단정하지 않습니다. 현재 ADB0대이고 신규 기기/HTTP/빌드 실행은 없습니다. 이번 변경은 문서만이며 commit/push/외부 답변/PR·Issue 변경은 하지 않았습니다.


## 2026-11-10 PR #47 최신 main 2e873bc 반영

사용자 승인으로 feat/1-navi-probe에 origin/main2e873bc를 공유 이력 보존 merge로 반영했습니다. STATUS/HANDOFF 두 문서 충돌에서 양쪽 신규 기록을 모두 보존했고 미해결 충돌0/추가 행 보존/명명/diff 검사를 통과했습니다. 서버/API는 origin/main과 동일하며 main 자체나 기존 커밋은 수정하지 않습니다. ETA/권한 신규 근거의 고정 기준504b867과 링크는 계속 유효합니다.

통합 verifyAll 첫 오프라인 실행은 main의 고정 jsoup1.24.1 캐시 부재로 테스트 전 중단됐습니다. 버전/잠금 파일을 바꾸지 않고 의존성을 받은 재시도에서 서버313개 중312개 통과/1개 실패(오류0/skip0), 기존 PlacePreviewDemoTest의 Windows Files.setPosixFilePermissions 미지원 UnsupportedOperationException218행입니다. 서버 테스트/보안을 우회 수정하지 않았습니다. 별도 verifyAndroid15초 성공(54태스크 중53 UP-TO-DATE; 기존6개 테스트 결과 재사용), OpenAPI3.1/예제 검사 성공. 이번 기기/smoke는 재실행하지 않았습니다. 원격 CI는 새 head에서 별도 확인합니다.

이 변경은 네 작업 브랜치만 일반 Push하며 PR Draft/#1 미완료 유지 대상입니다. main PR 병합·Issue 변경/종료·리뷰·Draft 해제·force push는 하지 않습니다.


## 2026-11-10 화면·SDK 근접 관측과 공유 근거 정리

[신규 관측 요약](../docs/VEH01_OBSERVATIONS_20261110.md)과 비식별 docs/evidence/veh01-20261110.json에 오늘의 ETA·권한·버전 근거를 묶었습니다. 동일 스크립트의 수동2회 응답은 모두681초(86.199초 간격), 내비는12분/도착20:21→20:22였습니다. 기기 시각/초 해상도를 고려한 응답→캡처 구간은 첫0.437–4.437초 미만, 두 번째1.238–6.238초 미만입니다. 이전72–82초 지연보다 줄었지만 현재 경로 연관성/UI 반올림/시간 기준은 미확인으로 유지합니다. 이동 시간 계열 가설과 계약 확정을 구분하며 Instant 변환·PLEOS 차단 해제·제품 연결은 없습니다. 도착/단절/미지원·늦은 응답의 필요한 환경과 검증 계획을 함께 정리했습니다. 이번 변경은 문서/비식별 발췌만이며 사용자 승인으로 별도 docs(android) 커밋·일반 Push에 포함합니다. 아래 미커밋/공유 전 표기는 각 관측 당시의 이력이고, 최신 공유 기준은 이 문서와 비식별 JSON을 함께 포함한 커밋입니다.


## 2026-11-10 소영님 #1 코멘트 인계 형식 반영

[인계 표](../docs/VEH01_APP_HANDOFF.md)에 요청하신5개 열·검증 commit/버전·비식별 근거·다음 확인 항목/환경을 추가했습니다. 원격49115f6과 미커밋 신규 기기 관측을 분리하고 제품 adapter 미연결/PLEOS 차단 유지를 명시했습니다. 서버 담당의 source·시간 변환·freshness/오류 예제 검토 후 앱 인수 순서이며 CarUx 범위는 #25로 유지합니다. 이번 변경은 문서이고 GitHub에 해당 코멘트를 참조한 [확인/적용 결과 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6760697971)을 게시하고 본문 재조회로 확인했습니다. commit/push는 수행하지 않습니다.


## 2026-11-10 권한 미선언 실제 SDK 예외 검증

기기 dumpsys/pm 권한 정의에서 NAVI_ROUTE/NAVI_ROUTE_SEARCH 모두 normal, 제공 패키지 ai.pleos.playground.service.navi임을 확인했습니다. 런타임 권한 창 거부/pm revoke 시험으로 간주하지 않습니다. 기존 권한을 강제 변경하거나 시스템 서비스를 중지하지 않았습니다.

정상 probe Java 소스와 동일한 별도 진단 package ai.pleos.assistant.probe.denied를 Git 제외 work/veh01/no-permission-probe에 만들었습니다. 두 uses-permission 선언만 제외하고 applicationId를 분리했으며 고정 AGP8.9.2/SDK35/NaviHelper3.0.5를 유지했습니다. 첫 assemble은 복사본의 AndroidX 설정 누락으로 실패했고 원본 gradle.properties를 반영한 재시도47초에 성공했습니다. 이 fixture는 APK 빌드만 실행했으며 Lint/단위 테스트 통과를 주장하지 않습니다. 최종 merged manifest에 두 권한 없음과 Java 소스 동일을 검사했습니다. APK SHA256=f3541e1c378d563db4ac26ab11af44cd838bfd2467a898e33d62de8ad05bec05. 정상 APK/manifest/앱은 덮어쓰지 않았습니다.

기존 격리 에뮬레이터를 정상 종료 후 같은 데이터로 부팅해 설치 Success를 받았습니다. 초기 검은 화면은 SDK 결과로 쓰지 않고 표준 WAKEUP과 포커스/화면 재확인 후 버튼을 눌렀습니다. 같은 시험 세션에서 06:14:05.921867Z NAVI_ROUTE=false, 06:14:05.949898Z NAVI_ROUTE_SEARCH=false, 06:14:06.791312Z initialized, 06:14:06.801825Z destinationRequested, 06:14:06.819745Z sdkCallFailed/PERMISSION_DENIED를 받았습니다. routeRevision=1/목적지 사용가능=false, 화면에서 초기화/재조회/Trip/재탐색 버튼 비활성을 확인했습니다. 실패는 유효 목적지나 정상 빈 결과로 바뀌지 않았습니다. 이 경로는 코드상 SecurityException 분류입니다. 실패 처리의 listener 해제/SDK release 호출 구현은 있지만 SDK 내부 해제 완료 자체를 별도 계측하지 않았습니다.

시스템 시작횟수는 시험 중1회였습니다. 원시 근거는 Git 제외 captures/veh01-permission-fixture-20261110.json, veh01-permission-20261110-live.jsonl, 누적 snapshot158건, 화면 work/veh01/denied-result.png입니다. 정상 probe WARM/Status:ok 복귀를 확인했고, 증거 보존 후 이번 별도 .denied 시험 앱만 uninstall Success로 제거했습니다. 정상 앱과 원본 AVD는 보존했습니다. 권한 미선언 조회 실패 경로는 실제 확인했지만 개별 권한 조합/런타임 거부·서비스 미지원/단절·도착·늦은 유효 응답·ETA 기준은 아직 미검증입니다. 테스트 앱/생성 프로젝트/SDK는 공유 fixture에 추가하지 않았으며 제품 코드·commit/push·PR/Issue 변경은 없습니다.


## 2026-11-10 ETA 무요청 관측·수동 반복 조회

Navigation3.3.2-rc5-hotfix.3/probe0.1.0-probe/NaviHelper3.0.5, Asia/Seoul, 같은 세션에서 길안내 시작(05:49:50.220220Z), 명시적 목적지 VALID_FIELDS(05:50:37.363502Z)를 받았습니다. 첫 Trip 요청05:51:16.353738Z→응답05:51:17.293121Z/raw731초, 두 번째 수동 요청05:54:59.346307Z→응답05:54:59.779159Z/raw734초입니다. monotonic 응답 간격222.494초이고 원시값은3초 증가했습니다.

첫 응답 이후 두 번째 수동 요청 전 222.052초 구간에서 추가 tripInfo는0건입니다. 중간 STOPPED/내비 전면/같은 세션 HOT 복귀를 포함하며 listener는 유지됐습니다. 이 제한된 정차·경로 시험에서 자발 콜백을 관측하지 못한 것이며 SDK 자동 갱신 없음/주기나 단순 카운트다운을 확정하지 않습니다. 시스템 시작횟수는 전후1이었습니다. 현재 경로 연관성과 ETA 시간 기준은 계속 UNVERIFIED입니다.

내비 캡처 전후 UTC05:52:29–05:52:39에 시계14:52/남은13분/도착15:04/속도0kmh였습니다. 첫 SDK 응답보다 약72–82초 늦고 UI 분 단위/반올림 규칙도 미확정이므로 동시 일치나 절대 ETA 변환 근거로 쓰지 않습니다. 위치/이름/POI는 공유 문서에 기록하지 않았고 화면은 Git 제외 work/veh01/eta-ui-20261110.png에만 있습니다.

비식별 근거: captures/veh01-eta-20261110.json, veh01-followup-20261110-live.jsonl, 누적 snapshot146건. 도착/실패 조합/늦은 유효 응답·공동 인수는 여전히 미검증입니다. 이번 단계는 기기 관측/문서 갱신이며 코드·테스트·commit/push·PR/Issue 변경은 없습니다.


## 2026-11-10 응답 재확인 — SDK 초기화·invalid 목적지 수신

사용자 앱 정리 후 ADB/currentUser10/system_server 시작1회 유지를 확인했습니다. snapshot 수집으로 세션 cd07e027-65be-4462-b626-5041d40ca6a1의 초기화·목적지 MISSING_OR_INVALID 응답을 회수했습니다. 최초 제한600초 live 파일에는 시작1건만 남았으므로 그 파일만으로 SDK 무응답이라 판정하지 않습니다. snapshot 누적135건 중 이번10건을 추가했고 captures/veh01-followup-20261110-summary.json에 해당 세션을 분리 보존했습니다. 화면에서 재조회 활성/Trip·재탐색 비활성도 확인했습니다.

내비를 열고 사용자에게 공개 목적지 길안내 시작을 요청했습니다. ETA 자동 주기/기준/도착/실패 조합은 아직 미검증입니다. 반복 재탐색·권한 변경·제품 코드 수정·commit/push/PR 변경은 없습니다. 새 live 관측 창을 시작해 같은 안전 파일에 append/dedup합니다.


## 2026-11-10 후속 기기 검증 재개 — 설치 버전 확인

원본 AVD를 보존하고 기존 격리 데이터(VEH01_clean_data_20261109)로 cold boot했습니다. adb에서 boot_completed=1/system_server 시작1회/currentUser10을 확인했고, dumpsys package로 Navigation3.3.2-rc5-hotfix.3(code1/min31/target35), probe0.1.0-probe(code1/min28/target35)을 실제 확인했습니다. 기존 APK COLD 실행 Status:ok/약2974ms와 새 세션 화면을 확인했습니다. 버전 증거는 Git 제외 captures/veh01-versions-20261110.json입니다.

초기화 버튼을1회 입력한 뒤 ADB와 호스트 파일 조회 응답이 지연돼 초기화 성공/실패·목적지·TripInfo/ETA 주기·단절 시나리오는 이번 구간에서 아직 판정하지 않습니다. 반복 버튼 입력·재탐색·권한 강제 변경·원본 데이터 삭제는 하지 않았습니다. 사용자에게 사용하지 않는 메모리 다소비 앱을 닫도록 요청했고 에뮬레이터는 유지했습니다. 새 수집 파일은 captures/veh01-followup-20261110-live.jsonl이며 결과 확인 전 통과로 세지 않습니다. 제품 코드 변경·commit/push·PR/Issue 변경은 없습니다.


## 2026-11-10 PR #47 main 통합·문서 충돌 해소

사용자 요청으로 공유된 feat/1-navi-probe에 main abd9aa4를 통합했습니다. 기존487e55c를 재작성하지 않고 merge로 이력을 보존하며 force push는 사용하지 않습니다. android/README.md·STATUS·CODEX_HANDOFF의 충돌에서 양쪽 신규 기록을 모두 보존했고, API1.1.0 Navigation/Pleos 차단 조건을 다시 대조했습니다. 제품 adapter/ETA 변환은 추가하지 않았습니다.

통합 후 verifyAll은 서버268개 중267개 통과/1개 실패로 종료했습니다. 기존 PlacePreviewDemoTest의 Files.setPosixFilePermissions Windows 미지원 UnsupportedOperationException(218행)이며 서버 코드를 바꾸거나 테스트를 비활성화하지 않았습니다. 별도 verifyAndroid는19초 성공(대부분 UP-TO-DATE, 이전 실제 실행6개 테스트 결과 재사용), Lint0건입니다. OpenAPI 계약 검사는 기존 venv/PYTHONUTF8=1로 성공했고 최초 기본 Python의 jsonschema 누락/기본 cp949 읽기 실패와 구분합니다. 양쪽 문서 추가 행 보존 검사·명명·diff 검사 통과. 기기/smoke는 재실행하지 않았습니다.

이 통합은 PR47 작업 브랜치만 갱신하며 main 병합·Issue 종료·Draft 해제·리뷰가 아닙니다. #1 미완료와 기기/ETA 제한은 유지하고 원격 필수 CI 결과는 별도 확인합니다.


## 2026-11-10 승인 전 검증·PR 정리

현재 feat/1-navi-probe/HEAD9518b6a, 새 커밋0개이며 변경은 미커밋입니다. 원격 API의 main abd9aa4는4커밋 앞서 있으나 작업을 보존하기 위해 통합하지 않았습니다. Android verifyAndroid는 첫 daemon 연결 실패 후 호스트 권한 재시도에서22초 성공했습니다(대부분 UP-TO-DATE). 별도 testDebugUnitTest --rerun-tasks는24초/6개/실패0/오류0/skip0로 실제 재실행 성공, Lint XML0건/APK 해시 동일을 확인했습니다. 서버·기기는 이번에 재실행하지 않았습니다.

기존 #1/#16/#26/#25/#39/#40 및 관련 PR을 대조해 신규 Issue는0개를 권고합니다. #1은 미완료여서 Draft PR의 필수 Closes #1 연결이 완료 선언이 되지 않도록 병합 금지·미검증을 명시했습니다. 제목/브랜치·diff 검사 통과. 승인용 PR 본문과 전체 보고는 Git 제외 outputs/VEH01_PR_DRAFT.md, outputs/VEH01_REVIEW_REPORT.md에 준비했습니다. commit/stage/push/PR/Issue 변경·리뷰·병합·브랜치 삭제 없이 승인을 기다립니다.


## 2026-11-09 Issue #1 앱 인수 코멘트 반영

GitHub API로 [추가 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6739718159)와 #16/#26 본문을 확인했습니다. [차량 데이터 앱 인수안](../docs/VEH01_APP_HANDOFF.md)에 목적지/ETA/관측 시각·가용성, 변경/취소/도착/오래됨/권한/미지원/단절/늦은 콜백 상태, Navigation 변환 차단 조건과 공동 인수 시나리오를 정리했습니다. 상태명·유효기간·제품 adapter는 협의/미구현이며 공통 계약을 변경하지 않았습니다. ETA 원시값 수신을 앱 연결 완료로 세지 않고 서버 PLEOS 차단·기존 예약/비용 승인 경계를 유지합니다.

실제 Navigation/probe 설치 버전 조회는 adb emulator-5554 부재로 실패했습니다. 내비 버전은 미확인, probe 버전은 Gradle 설정과 APK hash 근거로 구분했습니다. 기기를 새로 시작하거나 기존 데이터를 변경하지 않았습니다. ETA 기준/주기·도착·실패 조합·공동 기기 인수는 남아 #1 OPEN 유지 대상입니다. 이번 변경은 문서만이며 GitHub 댓글 게시·commit/push/PR/merge는 없습니다.


## 2026-11-09 공식 문의 초안 검토

[공식 문의 초안](VEH01_PLATFORM_SUPPORT_DRAFT.md)을 최신 격리 시험과 대조해 정리했습니다. 안정 관측131초의 완료 콜백 미수신과 별도 Watchdog 시험을 분리하고, 후속 Trip 원시값666·ETA 기준·응답 연관성·Navigation 상태별 콜백 조건을 질문에 포함했습니다. 공식 Routes 문서를 재확인했지만 시간 기준/완료 보장을 추가로 확정하지 않았습니다. 일반 공식 연락처의 기술 지원 전용 여부는 미확인입니다. 외부 발송·제품 코드/공통 API 변경·테스트 재실행·commit/push/PR은 없습니다.


## 2026-11-09 격리 두 번째 부팅 — 재탐색 완료 콜백 미관측

사용자 수동 설치 Success 후 앱 실행 Status: ok, 두 권한 허용과 SDK 초기화를 확인했습니다. 공개 길안내 시작 후 routeStarted(11:03:44.037310Z), 명시적 조회 VALID_FIELDS(11:04:10.363953Z), reRouteRequested 1회(11:04:41.361257Z)를 같은 세션에서 수신했습니다. 요청 시 revision=2/목적지 사용 가능=false로 무효화됐고 약1.323초 뒤 STOPPED/내비 전면 전환, 11:05:16.535357Z에 HOT 복귀했습니다. 복귀 화면에서 Trip/재탐색 버튼은 비활성이었습니다.

요청 전후 system_server 시작 횟수는1로 유지됐고 요청 후 최소131초 관측 창에 reRouteEnded는0건입니다. 앞선 Watchdog로 중단된 원본 데이터 시험과 구분합니다. 완료/SDK 실패/미지원으로 단정하지 않습니다. 11:06:56.771035Z 수동 재조회 후 11:06:57.806926Z VALID_FIELDS로 회복했습니다. UIAutomator와 재탐색 반복 요청은 사용하지 않았습니다. ETA 시간 기준/응답 연관성과 도착·자동 이탈 재탐색은 계속 미검증이며 #1은 미완료입니다.

비식별 증거: captures/veh01-clean-second-reroute-20261109-live.jsonl, veh01-clean-second-reroute-result.json. 화면은 Git 제외 work/veh01/clean-reroute-return.png에만 보관했습니다. 코드 변경/테스트 재실행/commit/push/PR/외부 문의 발송은 없습니다.


## 2026-11-09 재탐색 요청 1회·플랫폼 Watchdog 비교 — 격리 기기 설치·SDK 초기화 확인

- 사용자가 직접 공개 목적지 길안내를 시작한 기기에서 새 세션을 초기화했습니다. 목적지 VALID_FIELDS(18:53:07.386 KST), 두 권한 허용과 재탐색 버튼 활성 후 reRouteRequested 1회(18:53:16.701 KST / 09:53:16.701Z)를 확인했습니다. 요청 직전 목적지 사용 가능=false/revision=1, 약1.058초 뒤 STOPPED/내비 전면 전환이 관측됐습니다. 이후 직접 화면은18:54/남은12분/3.1km/도착19:05/속도0km/h였습니다. 요청 후 probe 버튼 목록이 빈 것은 다른 화면 전환으로 구분하며 완료/버튼 비활성의 기기 증거로 쓰지 않습니다.
- 복귀 시험에서 UIAutomator137/UiAutomation 연결 timeout, Activity 없음이 발생했습니다. 시스템 사용자0 RUNNING_LOCKED/사용자10 미실행, system_server 시작 횟수5→6→종료직전9, 반복 boot_progress를 확인했습니다. Watchdog 헤더는 main 스레드66–67초 지연으로 시스템 서버 종료를 명시했고 최신 해당 스택은 CountDownLatch.await → UserManagerService$LocalService.getBootUser → HsumBootUserInitializer.systemRunning이었습니다. SystemUI DeadSystemException은 후속 증상입니다. 원인을 GPU/HVAC/SDK 재탐색으로 확정하지 않으며 완료 콜백0은 플랫폼 재시작 때문에 판정 불가입니다.
- 원본 데이터는 보존하고 공식 -datadir/-data 옵션으로 D:/Android/Avd/VEH01_clean_data_20261109를 분리했습니다. 첫 격리 부팅은 시작1회/사용자10 준비 후2회로 증가했고 android.fg73초 지연 Watchdog가 있었습니다. UIAutomator 없이 probe 설치도 Package Manager Broken pipe(32)로 실패했고 SDK 초기화는 하지 못했습니다. 이후 약3분 이상2회를 유지해 초기화된 같은 데이터의 두 번째 부팅을1회 추가 확인했습니다. 마지막 관측은 boot_completed=1/시작1회/사용자10 RUNNING_UNLOCKED/currentUser10입니다. 이 두 번째 부팅의 장기 안정성은 미확정입니다.
- 두 번째 격리 부팅의 자동 준비 조회는 timeout으로 설치 전 중단됐고, 직접 APK 설치 명령과 허용된1회 재시도는 모두 자동 승인 검토 deadline 초과로 실행되지 않았습니다. 이를 기기 설치 실패나 안전성 거절로 세지 않습니다. 이후 사용자가 PowerShell 설치 Success를 확인했고, adb 실행 Status: ok/currentUser10/system_server 시작1회를 확인했습니다. 격리 두 번째 부팅에서 두 권한 허용과 NaviHelper 초기화(11:00:13.799958Z), 목적지 MISSING_OR_INVALID 응답을 실제 수신했습니다. 새 안전 로그는 captures/veh01-clean-second-reroute-20261109-live.jsonl이며 길안내·재탐색 완료와 장기 안정성은 아직 미검증입니다.
- 진단 정정: ADB 미연결/Activity 없음만으로 qemu 종료나 앱 삭제를 단정할 수 없습니다. 실제 프로세스·로컬 포트·AAOS 사용자 상태를 대조했습니다. am start는 종료코드0에도 Error type3을 출력할 수 있어 로컬 도우미가 stdout/stderr와 Status:ok를 검사하도록 수정했습니다. 해상도 축소는 도움말 확인에서 중단돼 적용되지 않았고 AVD 잠금/사용자 계정/권한을 우회하지 않았습니다.
- 원본 userdata3개 파일의 크기·수정시각은 격리 시험 후에도 동일합니다(내용 전체 해시 비교는 아님). 원본 삭제/-wipe-data/잠금 삭제/공통 API·서버 변경은 없습니다. 이번 단계에서 제품 코드·빌드/단위 테스트를 다시 변경/실행하지 않았으며 이전6개 테스트/Lint0/APK 성공과 구분합니다. 비식별 스냅샷 누적111건. 실제 경로/위치/이름을 공유 자료에 추가하지 않았습니다.
- 증거는 captures/veh01-reroute-recovery-summary.json, veh01-reroute-platform-instability.json, veh01-clean-data-recovery.json, veh01-reroute-user-started-20261109-live.jsonl(12건), work/veh01/reroute-request-result.png에 있습니다. [공식 문의 초안](VEH01_PLATFORM_SUPPORT_DRAFT.md)은 미발송입니다. 명시적 요청은 부분 확인됐지만 완료·도착·자동 이탈 재탐색·ETA 기준/연관성·실패 조합 등은 미완료이며 #1 종료/commit/push/PR/merge는 하지 않았습니다.

## 2026-11-09 공식 재탐색 API 확인·진단 버튼 추가 — 기기 완료 미검증

- 공식 [Routes](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/routes)와 NaviHelper3.0.5 AAR javap에서 requestReRoute()를 확인했습니다. [권한 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)와 AAR @RequiresPermission은 NAVI_ROUTE_SEARCH를 요구합니다. 표준 GPS 이동 재생과 별도로 명시적 재탐색 요청 경로가 있으므로 미확인 API를 만들지 않고 probe에 버튼을 추가했습니다.
- Activity/strings/manifest만 기능 변경했습니다. 목적지 유효 필드·helper·수명·NAVI_ROUTE_SEARCH 허용을 확인한 경우에만 개발자가 1회 요청할 수 있습니다. 호출 전 목적지/Trip/재탐색 버튼을 무효화하고 reRouteRequested를 기록하며 기존 reRouteEnded의 재조회 경로를 유지합니다. 권한 상태 naviRouteSearchGranted도 비식별 로그에 추가했습니다. 권한 승인 UI/pm grant는 실행하지 않았습니다. SDK 타입은 Activity 안에 유지하고 서버/API 계약은 바꾸지 않았습니다.
- 첫 verifyAndroid는 NAVI_ROUTE_SEARCH 선언 누락을 Lint가 검출해 실패했습니다. manifest와 실행 시 권한 확인을 보완한 뒤 D드라이브 JDK21/SDK/작업 전용 Gradle 캐시에서 `gradlew.bat -PincludeAndroid=true --offline --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" spotlessApply verifyAndroid`가 24초에 성공했습니다. 상태 단위 테스트 6개 실패/오류/skip=0, Lint 0건, debug APK 생성 확인. 새 APK SHA256: 05222c17a137ec5277563b5178a181d801ad4a90a31fff4dbc177fdf9534b864. 초기 실패/수정 후 로그는 work/veh01/verify-reroute.log, verify-reroute-permission.log에 따로 보존합니다.
- 기기 install -r는 Success, NAVI_ROUTE와 NAVI_ROUTE_SEARCH 모두 granted=true였습니다. host 4GB/임시 2GB 헤드리스 시도는 ColorBuffer 오류/프로세스 소실로 시험이 이어지지 않았고, 임시 SwiftShader/Vulkan 비활성/2GB/헤드리스에서는 새 APK initialized(07:33:48.448231Z), 초기 MISSING_OR_INVALID와 두 요청 버튼 비활성을 확인했습니다. 기존 공개 경로의 계속 안내를 선택했으나 내비가 빈 화면/로딩에 머물렀습니다. 후속 destinationRequested 이후 유효 응답을 얻지 못해 스크립트가 비활성 재탐색 버튼에서 중단됐습니다. reRouteRequested=0/reRouteEnded=0이며 API 실행 성공이나 권한 거부 시험으로 기록하지 않습니다.
- 현재 기기 시간대는 다시 America/Los_Angeles로 관측했습니다. 이전 Asia/Seoul 기록은 당시 GPS 주입 이후 관측이며 이후 실행까지 고정하지 않습니다. 임시 메모리/렌더러/창 옵션은 영구 AVD/실행 파일에 반영하지 않았고 시험 후 정상 종료했습니다. 2GB 실행을 권장 4GB 환경 안정성 검증으로 세지 않습니다.
- 로컬 Git 제외 증거: captures/veh01-sdk-reroute-20261109-summary.json, veh01-sdk-reroute-20261109-live.jsonl(12건), work/veh01/sdk-reroute-ready.png/route.png/blocked.png 및 실행 로그. 비식별 스냅샷 누적 99건. 다음은 정상 활성 경로 확보 후 명시적 재탐색 1회와 실제 완료/오류/무응답 관측입니다. 도착/이탈에 따른 자동 재탐색용 공식 위치 재생, ETA 기준/연관성, 늦은 이전 응답·실패 조합도 남았습니다. 문의 초안만 갱신했으며 외부 발송·commit/push/PR/merge는 없습니다.

## 2026-11-09 도착·재탐색 재현 시도 — 미확인

- 공개 테스트 목적지로 길안내를 시작하고 초기 목적지 VALID_FIELDS/TripInfo rawEtaSeconds=648(07:02:35.284734Z)을 받았습니다. 기본 host/Vulkan 비활성 임시 실행이며 SDK 코드는 변경하지 않았습니다.
- 공식 [Android 콘솔](https://developer.android.com/studio/run/emulator-console?hl=en)의 geo fix와 [Pleos Virtual Console 안내](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)를 확인했습니다. Android GPS와 Pleos 차량 위치 전달 경로를 동일하다고 가정하지 않습니다. 공식 Virtual Console 가이드에서는 내비 도착/재탐색용 위치 재생 절차를 확인하지 못했습니다.
- 공공 위치 자료에서 만든 합성 경로 이탈 지점과 공개 역 인근 지점에 geo fix를 총 7회 보냈고 콘솔 OK를 확인했습니다. 마지막 지점은 Android dumpsys location의 GPS 좌표와 반올림 허용 범위에서 일치했습니다. 단순 문자열 검색은 반올림 때문에 latitude=false였으므로 공급자 미반영 근거로 쓰지 않고 숫자 비교 결과를 따릅니다. 실제 사용자 위치 기록/주행 경로는 사용하지 않았습니다.
- 그럼에도 내비는 기존 80m 안내·남은 11분/3.1km·속도 0 km/h를 유지했고 새 위치로 이동했는지 확인하지 못했습니다. probe 복귀/명시적 목적지 재조회 후에도 VALID_FIELDS였고 destinationArrived/reRouteEnded/routingCompleted는 수집 구간에 0건이었습니다. 도착·재탐색 성공/SDK 미지원/콜백 버그로 단정하지 않습니다. 연속 도로 주행 재생이나 정확한 목적지 진입 조건을 검증한 시험도 아닙니다.
- 위치 주입 전 기기 표시 23:03에서 이후 16:05로 바뀌었고 07:07:02Z 조회에서 persist.sys.timezone=Asia/Seoul, 기기 2026-11-09T16:07:02+0900을 확인했습니다. 시각/시간대 설정을 수동 변경하지 않았으며 자동 위치 기반 변경 가능성만 기록합니다. 이후 시험은 America/Los_Angeles로 고정 가정하지 말고 시간대를 다시 읽어야 합니다. UTC observedAt/단조 시간과 UI 시각을 분리하고 이 표시 변경을 ETA 의미 검증으로 세지 않습니다.
- 호스트 GPU는 Win32 조회에서 NVIDIA GeForce MX450 2GB/Intel Iris Xe였습니다. [최신 공식 구성 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)의 최소 예시는 RTX 3060 Laptop 6GB 또는 RX 6600M/RX 6700S 8GB 이상입니다. 현재 하드웨어가 이 예시보다 낮음을 기록하되 기존 ColorBuffer 오류/내비 위치 정체의 원인으로 확정하지 않습니다.
- Git 제외 증거: captures/veh01-arrival-gps-attempt.json, veh01-arrival-reroute-20261109-summary.json, veh01-arrival-reroute-20261109-live.jsonl(17건), veh01-arrival-baseline-20261109-observation.json, work/veh01/arrival-gps-after.png. 스냅샷 누적 87건이며 이전 자료는 보존했습니다. AVD 정상 종료, 제품/서버/API·권한/보안 변경 없음, 빌드/단위 테스트 재실행·commit/push/PR/merge 없음. 도착/재탐색은 계속 미검증이고 공급자 확인 초안은 VEH01_VALIDATION에만 있으며 외부 발송하지 않았습니다.

## 2026-11-09 목적지 변경 직접 검증

- 같은 AVD를 host/Vulkan 비활성 임시 옵션으로 실행해 같은 probe 세션을 유지한 채 공개 테스트 목적지 A에서 B로 변경했습니다. 실제 화면의 목적지 교체와 안내 시작을 확인했고 위치·이름·주소·POI는 공유 로그에 보존하지 않았습니다.
- 변경 전 목적지 VALID_FIELDS, TripInfo rawEtaSeconds=4196(06:48:15.528781Z)을 받았습니다. 이후 캡처 구간은 기기 22:48:20–22:48:23-0800으로 약 4–8초 뒤이며 화면은 남은 1시간 8분/도착 23:55/시계 22:48/속도 0 km/h였습니다. 화면의 분 단위 시각과 정확한 일치를 선언하지 않습니다.
- 두 번째 목적지 선택 후 probe 복귀에서 STARTED(06:52:48.264319Z), routeCancelled(06:52:48.271795Z), MISSING_OR_INVALID 목적지(06:52:48.289233Z), routeStarted(06:52:48.527935Z)를 관측했습니다. 취소 시 사용 가능=false/revision=2, 새 시작 시 false/revision=3이었고 수동 재조회 전 실제 Trip 버튼 disabled를 확인했습니다. 이번 변경에서는 destinationChanged/routingCompleted/reRouteEnded 콜백을 관측하지 않았습니다. 콜백은 복귀 후 진입한 기록이며 UI 변경 즉시/백그라운드 전달로 해석하지 않습니다.
- 명시적 새 조회 후 VALID_FIELDS(06:53:53.419357Z), TripInfo rawEtaSeconds=648(06:54:03.902082Z)을 받았습니다. 응답 지연 약 225ms. 캡처 구간 기기 22:54:21–22:54:42-0800은 응답 약 17–38초 뒤이고 화면은 남은 11분/도착 23:05/시계 22:54/속도 0 km/h였습니다. 이전 목적지 정보로 추가 요청하는 버튼은 차단됐지만 새 SDK 응답과 현재 목적지의 강한 연관성을 증명하지 않습니다.
- 이전 Trip 응답은 변경 전에 이미 수신된 상태였습니다. 따라서 변경 중 지연된 이전 유효 응답/요청 중첩은 재현하지 못했습니다. 이 항목과 ETA 시간 기준·도착·재탐색·권한 거부/미지원/단절 등은 계속 미검증이며 #1은 미완료입니다. responseAssociation/etaTimeBasis UNVERIFIED와 서버 PLEOS 차단을 유지합니다.
- Git 제외 증거: captures/veh01-destination-change-20261109-live.jsonl, veh01-destination-change-20261109-summary.json, veh01-change-before-20261109-observation.json, change-after 대응 파일, veh01-change-controls-before-refresh.json. 스냅샷 누적은 70건이며 기존 자료는 보존했습니다. AVD는 시험 후 정상 종료했고 영구 설정·제품 코드·API·서버는 변경하지 않았습니다. 빌드/단위 테스트 재실행·commit/push/PR/merge 없음.

## 2026-11-09 같은 경로 반복 조회·취소 재검증

- 중단 후 기기가 없어 기존 AVD를 재실행했습니다. host 프로세스 종료와 ColorBuffer/texture 오류를 관측했고, SwiftShader/Vulkan 비활성 시험에서는 부팅·SDK 초기화 후 내비 전환 시간 초과가 발생했습니다. 호스트 여유 RAM 281MB/총 8GB를 확인해 정상 종료했습니다. 사용자가 다른 앱을 닫은 뒤 여유 약 3.7GB에서 host/Vulkan 비활성으로 재실행했습니다. 부팅 약 37초, 부팅 후 여유 약 920MB였으며 ColorBuffer 오류는 남아 있어 원인 해결/장기 안정화로 판단하지 않습니다. 영구 AVD/실행 파일 설정은 변경하지 않았습니다.
- 새 세션에서 초기 목적지 MISSING_OR_INVALID와 Trip 비활성을 확인한 후 공개 테스트 목적지를 직접 설정했습니다. 같은 세션 목적지 VALID_FIELDS 후 수동 TripInfo 두 번은 06:36:52.118095Z와 06:38:10.437564Z에 모두 rawEtaSeconds=4227이었습니다. 단조 시간 간격 78.319초이며 응답 지연은 각각 약 466/467ms입니다. 자동 콜백 주기를 측정한 결과가 아닙니다.
- 각 응답 약 5–10초 뒤 직접 캡처는 기기 22:36/남은 1시간 8분/도착 23:44, 22:38/남은 1시간 8분/도착 23:46, 속도 0 km/h였습니다. 원시 4227초는 수치상 70분 27초로 UI 68분과 일치하지 않습니다. 시간 기준·현재 안내 경로와 requestTrip 계산 결과 연관성을 확정하지 않고 서버 PLEOS 차단을 유지합니다. 정차 관측을 주행 검증으로 세지 않습니다.
- 안내 종료를 직접 누른 뒤 06:39:20.022802Z 백그라운드 routeCancelled에서 목적지 사용 가능=false/revision=2, 약 50ms 뒤 MISSING_OR_INVALID 콜백을 확인했습니다. 같은 세션 STARTED 복귀와 실제 Trip 버튼 disabled/목적지 재조회 enabled도 확인했습니다. 취소 후 늦은 유효 응답·도착·재탐색·권한 거부/단절은 이번에 검증하지 않았습니다.
- 로컬 Git 제외 증거는 captures/veh01-followup-route-a-20261109-observation.json, route-b 대응 파일, veh01-followup-cancel-controls.json, veh01-followup-20261109-summary.json입니다. 실시간 비식별 JSONL을 추가 기록했고 스냅샷 누적은 48건입니다. 이전 3686/603 자료는 덮어쓰지 않았습니다. 내비 화면은 work/veh01/에만 보존합니다.
- 시험 후 AVD를 정상 종료해 메모리를 회복했습니다. 다음 실행은 기본 host 설정이며 Vulkan 비활성은 이번 명령의 임시 옵션입니다. 제품 코드/서버/API 변경·빌드/단위 테스트 재실행·commit/push/PR/merge는 없습니다. #1은 ETA 기준/응답 연관성·실패/도착 등 미검증 때문에 계속 미완료입니다.

## 2026-11-09 내비 복구 후 긴 경로 추가 관측

- 임시 software GPU 실행에서 crash 버퍼의 SystemUI HVAC 처리 `CarPropertyConfig.getAreaType()` NullPointerException 반복을 확인했습니다. 빈 화면과의 인과관계는 미확정입니다. 기기 가용 메모리 약 1.8GB, 호스트 여유 약 700MB/총 8GB를 관측했으며 메모리 부족을 원인으로 확정하지 않습니다.
- 내비 앱만 force-stop/relaunch한 뒤 AVD를 정상 종료하고 기존 데이터 그대로 원래 host GPU로 재실행했습니다. boot_completed=1, 검색·공개 역 검색 결과·길안내 화면을 직접 확인했습니다. 재부팅 후 조회한 crash 버퍼에서 당시 새 충돌 출력은 없었으나 장기 안정성을 보증하지 않습니다. 현재 실행은 host GPU이며 아래 software 실행/새 경로 미확보 기록은 복구 전 이력입니다.
- 새 공개 테스트 경로를 직접 설정했습니다. 새 probe 세션에서 목적지 VALID_FIELDS(2026-11-09T04:41:27.045258Z), Trip 요청(04:41:43.518116Z), rawEtaSeconds=3686(04:41:45.334648Z)을 확인했습니다. SDK 응답까지 약 1.817초였습니다.
- 내비 캡처 구간은 기기 2026-11-08T20:42:05–20:42:24-0800으로 SDK 관측 약 20–39초 후입니다. 화면은 현재 20:42/남은 1시간 0분/도착 21:42/속도 0 km/h였습니다. 원시 3686초는 수치상 61분 26초이며 UI와 정확 일치하지 않습니다. 이전 짧은 경로 603초와 구분되는 관측으로만 보존하고 etaTimeBasis/responseAssociation UNVERIFIED, 서버 PLEOS 차단을 유지합니다.
- Git 제외 증거: captures/veh01-long-route-20261109-observation.json, work/veh01/navigation-long-after-trip-20261109.png, host-recovery.out.log/err.log. 누적 비식별 로그는 덮어쓰기 없이 8건 추가해 26건입니다. 새 수집 스크립트는 고유 파일명과 수집 시작 이후 Trip 콜백 검사로 이전 결과 재사용을 막았습니다.
- 이번 단계는 기존 APK 기기 실행·관측과 문서 갱신입니다. 빌드/단위 테스트 재실행은 없고 앱/AVD 데이터 삭제, 권한·계정·보안 변경, commit/push/PR/merge도 없습니다. 남은 검증은 시간 기준/동일 시점 대조, 변경 중 지연 응답, 도착·재탐색·자정·단절 등이며 #1 완료로 처리하지 않습니다.

## 2026-11-09 재개 지점

- 후속 직접 조작에서도 검색 후 검은 화면이 반복됐습니다. host와 임시 software GPU 모드를 비교했고 둘 다 내비 실행 ok/부팅 완료 이후 정상 검색·경로 화면을 확보하지 못했습니다. 원인을 GPU로 확정하지 않으며 앱 데이터 초기화·권한/계정/보안 변경은 하지 않았습니다. 현재 AVD는 software GPU로 실행 중이고 영구 host 설정/실행 파일은 보존했습니다.
- 새 경로·새 ETA는 미확보입니다. 다음 작업은 내비 화면 정상화 원인 확인이며, 화면 증거와 software-gpu 로그는 Git 제외 work/veh01/에 있습니다. 이전 603초 비교 자료를 새 실행 결과로 재사용하지 않습니다.

- 중단 전 문서와 603초 비교 자료를 확인·보존했습니다. 기존 AVD를 재실행해 boot_completed=1/probe 실행/새 SDK 초기화를 확인했으나 목적지가 invalid라 Trip 요청은 차단됐습니다. 누적 로그 18건이며 이전 세션과 구분합니다.
- 공개 목적지 검색 시 activity 서비스 없음/부팅 완료 전 broadcast 오류가 나서 새 경로는 설정되지 않았습니다. 프레임워크 안정화 후 내비 재실행부터 이어갑니다. 이전 비교 복사본: captures/veh01-short-route-observation-20261108-preserved.json. #1 미완료, commit/push/PR/merge 없음.

## 2026-11-08 후속: 짧은 경로 직접 비교·보존 정정

- 사용자 직접 진행 요청 후 현재 내비에 이미 표시된 10분 공개 경로를 재조회했습니다. 새 목적지 생성으로 보고하지 않습니다. 목적지 VALID_FIELDS, 22:08:29 KST rawEtaSeconds=603을 받았고 이후 직접 화면은 기기 05:09/이동 11분/도착 05:20 AM/속도 0 km/h였습니다. 캡처 전후는 05:09:27–05:10:39-0800으로 SDK보다 약 58–130초 늦어 동일 순간 대조가 아닙니다. 시간 기준/반올림/응답 연관성 UNVERIFIED와 서버 PLEOS 차단 유지입니다.
- 초기 원시 로그 20건은 수집기의 스냅샷 덮어쓰기와 logcat 순환 뒤 전체본이 남아 있지 않습니다. 첫 두 Trip 샘플·당시 이벤트 요약은 captures/veh01-20261108-summary.json에 보존됐습니다. 새 스냅샷 11건은 captures/veh01-20261108-short-route-snapshot.jsonl, 짧은 경로 메타데이터는 captures/veh01-short-route-observation.json입니다. 수집기는 중복 제거·추가 기록으로 고쳤으며 장시간 시험에는 실시간 수집을 준비해야 합니다. 사라진 초기 원본을 복구/재구성했다고 보고하지 않습니다.
- 내비 원본 화면은 Git 제외 work/에만 있고 공유 로그/문서에는 위치·주소·POI·이름을 넣지 않았습니다. 현재 내비 경로는 유지했습니다. 이번 단계는 기존 APK 실행·문서 변경이며 빌드/단위 테스트를 새로 재실행하지 않았고 commit/push/PR/merge도 하지 않았습니다. #1의 나머지 조건은 STATUS/VEH01_VALIDATION을 따릅니다.

## 2026-11-08 후속: 최초 실제 SDK 콜백

- 사용자 홈 화면 확인 뒤 기존 debug APK를 emulator-5554에 설치(Success), COLD/Status ok/STARTED를 확인했습니다. NAVI_ROUTE granted=true, NaviHelper initialized, 최초 목적지 MISSING_OR_INVALID 관측 후 사용자가 공개 테스트 경로 길안내를 시작했습니다. 권한 강제 부여나 보안 승인 우회는 하지 않았습니다.
- onStop 뒤 foregroundAtProcessing=false인 routeStarted를 받았고 HOT/같은 세션으로 복귀했습니다. 목적지는 명시적 재조회에서 VALID_FIELDS였으며 Trip 버튼이 활성화됐습니다. 자동 목적지 변경/탐색 완료·지속 갱신은 이 관측으로 보장하지 않습니다.
- 최초 TripInfo는 21:34:07 KST rawEtaSeconds=1541, 요청부터 약 600ms였습니다. ETA 시간 기준/응답 연관성 UNVERIFIED와 서버 PLEOS 차단 유지입니다. 사용자에게 내비의 남은 시간/도착 예정 시각 대조를 요청했습니다. 60초 경과·다른 경로·자정·도착/취소·거부/단절/늦은 콜백은 추가 검증해야 하며 #1을 아직 닫지 않습니다.
- 개인정보 제외 증거: Git 제외 captures/veh01-20261108-probe.jsonl. 상세 시각/판정은 STATUS와 VEH01_VALIDATION을 따릅니다. 이번 단계는 기존 APK의 실제 실행·문서 갱신이며 단위 테스트를 새로 재실행한 결과가 아닙니다. 아래 설치/수신 미실행 표현은 이전 단계 기록입니다. commit/push/PR/merge 없음.
- 후속 관측: 사용자 UI 보고는 기기04:37/이동25분/도착05:02 AM, ADB 확인 시간대는 America/Los_Angeles입니다. 두 번째 rawEtaSeconds=1538(21:41:00 KST), 두 응답 간격412.901초이며 감소3초입니다. 동일 순간 UI 대조가 아니므로 시간 기준/countdown/자동 주기를 확정하지 않습니다.
- 사용자의 길안내 종료 뒤 백그라운드 routeCancelled(21:42:45 KST), 목적지 무효화·invalid 콜백·HOT 복귀 후 Trip 버튼 disabled를 확인했습니다. 최종 비식별20건과 captures/veh01-20261108-summary.json 보존. 다른 경로·자정·도착·거부/미지원/단절·늦은 유효 응답은 후속이며 #1 미완료를 유지합니다.

## 2026-11-08 후속: D드라이브 설치와 부팅

- 사용자 요청으로 안정 경로 `D:\Java\temurin-21.0.13+9`와 `D:\Android\Sdk`/`Avd`를 준비했습니다. Pleos/Google SDK 약관 각각 명시 동의 후 설치했고 개인정보 선택은 사용자가 직접 했습니다. C드라이브 기존 SDK·다른 사용자 AVD는 보존했습니다.
- Pleos 공식 ZIP 크기/SHA-1 대조, 이미지 설치, Studio 초기 설정·Emulator 37.2.13/Platform-Tools 37.0.1, Cold boot/RAM 4GB/host GPU AVD 생성까지 완료했습니다. 실제 ADB device·boot_completed=1, Android 14/API 34, release_connect_v2.1.0/42dot 확인. 이미지 식별자 불일치와 최종 경로는 [설치 기록](../docs/ANDROID_SETUP.md#2026-11-08-windows-d드라이브-설치)을 봅니다.
- 프로젝트 로컬 SDK/JDK 연결 및 사용자 ANDROID_HOME/ANDROID_AVD_HOME/STUDIO_PROPERTIES는 D드라이브입니다. 새 Studio 캐시/로그/실행 임시폴더도 D드라이브를 사용합니다. 사용자 환경변수 변경 전 값은 Git 제외 work/veh01/user-environment.before.json에 있습니다. 시스템 PATH/JAVA_HOME은 바꾸지 않았습니다.
- 실행 파일: `D:\Android\Start-Pleos.cmd`, `D:\Android\Open-Android-Studio.cmd`. 부팅 확인 후 emulator는 종료했습니다. 창 캡처 도구가 반복 시간 초과여서 홈 화면 시각 검증은 미확인입니다. APK 설치/앱 실행과 목적지·TripInfo 수신은 아직 하지 않았고 #1은 미완료입니다.
- SDK_PROBE/LIVE 구분과 PLEOS_ETA_UNVERIFIED 차단을 유지합니다. 이번 후속은 환경·문서 변경이며 commit/push/PR/merge는 하지 않았습니다. 아래 초기 환경 부재 기록은 당시 이력입니다.
- 최종 D드라이브 `verifyAndroid --offline`도 성공했습니다(28초, 5개 실행/49개 UP-TO-DATE; 테스트 태스크 UP-TO-DATE). 상세는 STATUS와 Git 제외 work/veh01/verify-d-drive-approved.log를 따릅니다. 앞선 sandbox daemon 접속 실패를 최종 성공과 구분합니다.

## 2026-11-08 VEH-01 인계

- 작업 브랜치 `feat/1-navi-probe`, 시작 main/origin/main `9518b6a`, 시작 시 tracked 변경 없음. commit/push/PR/merge 없음. 현재 변경을 검토한 뒤 사용자의 명시적 승인 없이는 게시하지 않습니다.
- Android probe의 기존 구조를 유지하며 SDK 비의존 ProbeState와 단위 테스트 6개를 추가했습니다. 목적지 무효화·main thread 직렬화·진입 시각·종료 후 콜백·권한/오류·재조회 로그를 보강했습니다. 모든 응답 연관성과 ETA 시간 기준은 UNVERIFIED이며 서버 PLEOS 차단 유지입니다.
- 실제 SDK 모델/메서드, 환경, #1 완료 조건, #4/#5/#2 인계와 기기 명령은 [VEH01_VALIDATION](../docs/VEH01_VALIDATION.md)을 읽습니다. `onRouteEnded()`는 탐색 완료의 deprecated 별칭이며 여정 종료가 아닙니다.
- Temurin JDK 21.0.13+9를 Git 제외 `work/veh01/temurin21/`에 확보했습니다. 셸 JAVA_HOME과 GRADLE_USER_HOME=`work/veh01/gradle`만 지정해 실행했고 전역 Java/Git 설정·기존 SDK 경로는 바꾸지 않았습니다. Studio에서는 개발자가 JDK 21을 선택해야 합니다.
- format 및 최종 verifyAndroid 성공: 테스트 6개 실패/오류/skip 0, Lint 오류/경고 0, debug APK 생성. verifyAll은 서버 기존 POSIX 권한 테스트 1개 실패(200개 실행). 계약 smoke는 MOCK 흐름 PASS 후 재시작 예약 GET 404로 실패. 전체 성공으로 보고하거나 관련 검사를 skip하지 않습니다. 상세는 [STATUS](../docs/STATUS.md)의 2026-11-08 기록과 `work/veh01/` 로그입니다.
- 현재 사용자 Pleos AVD/연결 기기 없음. 개발자가 Studio SDK Manager에서 공식 Windows Pleos 사이트/API 34 이미지를 설치하고 라이선스·계정·SDK 이용 권한을 확인한 뒤 현재 사용자 Automotive AVD를 생성해야 합니다. 다른 사용자의 Pixel AVD를 이동하지 않습니다.
- 이어서 정상/목적지 없음·시간 기준(짧은/긴 경로·60초·자정)·변경/탐색/취소/도착·권한 거부·미지원/단절·늦은 응답·백그라운드/복귀를 관측합니다. #1을 아직 닫지 않습니다. 서버 Windows 실패 2건은 #4와 협의하고 Android 범위에서 우회하지 않습니다.

## 2026-11-10 VOICE-01 Telkit 채택 (#3)

- 전화 회선은 Telkit으로 결정했습니다. 연동 조건·한도·요금·약관과 서버 계약 영향은 [업체 검토](../docs/calls/VENDOR_REVIEW.md#0-결정-telkit-채택-2026-11-10), 흐름은 [전화예약 에이전트 계획](../docs/PHONE_RESERVATION_AGENT_PLAN.md)을 따릅니다.
- 다음 작업은 Telkit 계정 준비(본인인증·카드 등록·070 번호, 계정당 1개)와 LiveKit 프로젝트 설정의 실행 제안 작성입니다. 실제 번호 구매·발신은 대상·목적·예상 비용·상한 승인 후에만 합니다. 발신 표시 번호와 `telkit_call_id` 연결 방법을 PoC에서 확인합니다.
- 이번 변경은 문서만입니다. 계정·번호·발신·비용 0회, Gradle 재실행 없음.

## 2026-11-10 #35/#38 통합과 #42 CI·문서 이력 복구

- 사용자 승인으로 #35는4d65c01, #38은9ea227d에 squash merge됐고 #34/#37은 독립 검증/코드 준비 범위로 종료됐습니다. 실제 공급자 채택·기기/ETA·전화/유료 실행 인수는 별도입니다.
- #42에 최신 main과 REC-08 Gradle cache 산출물 수정을 반영했습니다. 문서 충돌 정리 중 누락된 PLAN/STATUS/HANDOFF 이력은 원본4d65c01/a22745a/6e48416과3-way 재대조해 복구했습니다. 세 원본의 모든 비어 있지 않은 행 보존을 확인했습니다. 전화 계획과 문서 복구만 변경하며 런타임 구현을 추가하지 않습니다.
- 통합 기반 Java313개·JAR 인증/MOCK/UNKNOWN·OpenAPI/H2 재시작/LIVE 차단을 확인했습니다. REC-08 가상 파일 제거 후 FROM-CACHE 동일 hash 복원·추천 계약2개를 재현했습니다. 실제 외부 장소/GPT/전화/유료 호출0회입니다. 아래 실패/미병합 기록은 당시 이력이며 최신 CI는 해당 PR head를 따릅니다.


## 2026-11-10 #35 병합 이후 #38 통합 검증

- 사용자 승인으로 PR #35를4d65c01에 squash merge했고 #34는 종료됐습니다. #38은 이 main을 공유 이력 보존으로 반영하며 PLAN/STATUS/HANDOFF의 충돌에서 양쪽 기록을 보존했습니다. 품질 도구40개 검사와 GPT/웹 상세정보 코드 준비 범위를 함께 유지합니다.
- 이 기록은 최신 main 반영 단계이며 #16/#26 기기·#11 데이터 인수나 실제 GPT/웹 수집/전화/유료 호출을 완료로 세지 않습니다. #38은 코드 준비/가상 검증 범위로 통합하며 실제 실행은 별도 승인 후입니다. 아래 #35 미병합/#34 OPEN 표기는 당시 이력입니다.
- 최신 통합본 format/verify·Java313개/실패·오류·skip0, 가상 품질40개, 패키징 JAR 인증/MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. 원격 CI는 이 통합 head의 결과를 별도 확인합니다.

## 2026-11-09 전화예약 에이전트 계획 인계 (#41)

- [개발 계획](../docs/PHONE_RESERVATION_AGENT_PLAN.md)을 팀 문서 목차에 연결했습니다. 서비스 전용 070 번호, 예약·문의 발신, 식당 회신, 변경 승인과 재확인을 하나의 흐름으로 제안합니다.
- 후속 보완으로 프로필과 이번 동승자 조건, 승인된 예약 사본을 분리했습니다. 프로필 수정으로 기존 예약을 덮어쓰지 않으며 재사용 저장을 선택하지 않아도 예약은 가능합니다. P01에서 정보별 보관·삭제 기간을 확정하고 P05/P06/P08에서 저장·최소 전달·앱 선택을 구현하도록 계획했습니다. 추가 인수 시나리오 9개는 실행 전 조건입니다.
- 다음 작업은 #3 전화 담당, #4 서버 담당, #26 앱 담당의 계약·범위·일정 검토입니다. P01~P11을 합의 후 후속 이슈로 나누며, 기존 구현 이슈를 문서 게시만으로 종료하지 않습니다.
- Telkit의 종료 웹훅은 현재 준비 중이므로 실시간 상태는 LiveKit·에이전트에서 처리하고 통화 기록은 사후 대조에 사용하도록 계획했습니다. 실제 설정·양방향 통화·한국어 품질·운영 배포는 미검증입니다.
- 이번 변경은 계획·목차·상태·인계 문서만 포함합니다. 로컬 검증은 문서 정적 검사이며 서버/Android 테스트 재실행·계정 생성·실제 전화·번호 구매·유료 실행은 없습니다. 실제 실행은 기존 협업 규칙의 구체적 승인 범위를 따릅니다.
## 2026-11-09 REC-11 #37과 #16 정적 합의 반영

- 사용자가 GPT 웹검색 API 연동 코드 준비를 선택했습니다. mainabd9aa4 기반 `feat/37-place-detail-enrichment`에 [REC-11](../docs/REC11_PLACE_DETAILS.md)의 내부 모듈과 MOCK 실행 task를 추가했습니다. #19/#26 미병합 코드는 가져오지 않았으며 공개 v1/미리보기 계약·DB·Android는 변경하지 않았습니다.
- GptPlaceSourceDiscovery/GptWebSearchHttpTransport는 명시적 모델과 대상/기한에 묶인 일회성 Permit으로 Responses/web_search 요청을 구성합니다. 키 Supplier만 있고 자동 키 로딩/LIVE 실행 명령/제품 bean은 없습니다. 실제 GPT·웹 수집·전화·Kakao·유료 호출0회. Permit은 전역 비용 승인 원장이 아니며 다음 실제 실행 전 모델/가격·출처·전송 데이터·횟수·비용 상한의 명시적 승인을 받아야 합니다.
- PlaceDetailEnricher/JsonLdPlaceDetailParser는 schema.org 매장명+주소를 대조해 영업시간/메뉴 링크/가격/음식/일반 예약/명시적 주차·아동 보고와 출처/시각/기한/충돌·UNKNOWN을 보존합니다. 페이지는 독립 검토된 URL/host만 받습니다. KAKAO/UNVERIFIED 입력의 GPT 전송과 미검토 identity의 READ_ONLY_WEB을 차단합니다. 웹 정보는 GENERAL이며 ARRIVAL 질문·예약/발신 차단을 유지합니다.
- PlaceDetailRecommendationService는 기존 최대3개 평가에 ordinal별 상세정보와 남은 질문을 결합하고 전체 generation/current/최단 표시 기한을 적용합니다. 기존 추천 질문은 기초 계획으로 유지합니다. 신규 필드가 #16/#26 합의된 API라는 뜻은 아니며 공개 스키마/화면 연결은 후속입니다. 페이지 HTTP adapter의 DNS pinning/전체 DNS 제한은 운영 전 보완해야 하며 실제 외부 수집은 미검증입니다.
- `./gradlew -PincludeAndroid=false format verify :server:placeDetails`: Java313개(기반268+신규45), 실패/오류/skip0. GPT와 페이지는 가상, transport만 loopback HTTP로 검사했습니다. `work/contract-venv/bin/python scripts/smoke-test.py --check-contract`: JAR 인증·기존 MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단 통과. 의존성은 jsoup1.24.1 추가, lockfile 대조. 로그는 Git 제외 work/rec11-verify.log·rec11-smoke.log입니다. 원격 CI는 해당 PR에서 별도 확인합니다.
- #16 브랜치는 mainabd9aa4를 merge해 충돌 해소했고, #26 담당의 정적 검토를 문서에 반영한 head5526f92를 push했습니다. 서버329·Python7/15·가상HTTP137·실제 로컬 서버 토큰HTTP16·CI3개 성공. [담당 검토](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008)는 현행0.2.0-draft 필드 정적 합의이며 app 구현/기기 완료가 아닙니다. #26의 화면 수명/토큰 보관/UNKNOWN, #1 ETA·#11 데이터 인수가 남아 #16 OPEN/#19 Draft를 유지합니다. #35와 다른 PR을 임의 병합하지 않습니다.
## 2026-11-09 REC-10 후속: 공식 사전·키 없는 HTTPS1000행

- 최신 [REC-10](../docs/REC10_SEOUL_PILOT_VALIDATION.md)과 docs/quality/rec10-status-reference.json·rec10-public-sheet-2026-11-09.json을 우선합니다. 제공 표준20차127)일반음식점 원문을 확보해 행정01–04·상세01/02·폐업일 의미를 확인했습니다. 가정 튜플이 아닌 공식 코드/명칭 판정이며 실제 입력1000행의 정상 조합 일치를 확인했습니다. 범용EPSG2097과 데이터셋EPSG5174의 차이는 출력하고5174를 유지합니다.
- 공식 Sheet 메타데이터에 있는 키 없는 HTTPS 표 경로를1회/1000행/2MiB로 읽었으나 레거시 객체 형식으로 JSON 해석에 실패했습니다. 사용자 추가1회 승인을 받아 다시 읽고 그 응답을 메모리에서만 재분석했습니다. 총데이터요청2회·추가 페이지/자동 네트워크 재시도0·0원입니다. 원문/행/연락처/ID·좌표·캡처는 보관하지 않고 집계만 저장했습니다. 첫 로컬 Python import 실패는 네트워크 전이므로 데이터 요청0회입니다.
- 실제1000행: 정상1000, 좌표누락82/유효918, 전화누락719/형식273/placeholder1/미인식7, 도로명주소누락3, ID중복/충돌0. 교차1000→918→1000m433→전화형식116→기본정보115행, 경계1000–1030m8행 제외. 첫 페이지·필터총3959의 제한 범위이며 유일한 매장115곳/현재 영업·예약 가능으로 세지 않습니다. pageSize없음·batch/version/TZ UNKNOWN 유지, 분석 시각과request시작/snapshot을 구분합니다.
- 가상40개(기존29+신규11)와MOCK·Gradle format/verify(Java UP-TO-DATE)를 통과했습니다. JSON/레거시 객체 키·끝 쉼표만 정규화하며 eval/스크립트·주석·함수·중복 키·크기/행수/메타데이터 오류를 거부합니다. 실제 행 fixture나 새 의존성은 없습니다. 독립 도구는 네트워크/키 로딩 없이 로컬 입력만 처리합니다. 공개 실제 집계와MOCK3행/통과0을 구분합니다.
- #34 인수는 독립 명세/접근·제한된 품질·도구/검증/한계 기록입니다. batch/TZ확인값이 없어UNKNOWN을 유지했고, 키 있는OpenAPI인증은 키 없는 대체 경로에서 불필요해 미실행입니다. 운영 경로/팀 계정·원본 최신성/CRS 차이·추가 품질·보관/제공·공급자 채택은 기존 #11에 남깁니다. #35 병합 전#34 OPEN, 다른 담당 이슈/PR 유지. 제품 추천/채택/예약/발신false·LIVE차단 유지합니다.
- 이번 키 전송·전화/Kakao/AI/유료 호출0회. 추가승인 사용 표식은 Git제외 work/rec10-sheet-additional.used이며 소비됐습니다. 추가 실제 조회를 자동 재개하지 않습니다. 로컬 증거 work/rec10-standard20-tests.log·standard20-mock.json·standard20-gradle.log·sheet-aggregate.json·sheet-additional-aggregate.json은 가상/실제를 구분합니다. 아래 이전 사전미확보·실제조회0회는 당시 단계 기록입니다.

## 2026-11-09 REC-10 #34 인증키 준비·교차 진단

- #33은 사용자 진행 지시로 `abd9aa4`에 squash merge했고 #32 CLOSED입니다. 현재 `feat/34-seoul-pilot-validation`은 이 main에서 분기했습니다. 다른 미완료 PR/담당 범위는 유지합니다.
- [REC-10](../docs/REC10_SEOUL_PILOT_VALIDATION.md)을 우선합니다. 개인 개발용 키는 명시 동의 후1개 발급했고 정상 상태/로컬 Git 제외0700/0600을 확인했습니다. 키는 팀/CI에 공유하지 않습니다. 인증 조회나 운영 주체 승인은 미완료입니다.
- 독립 도구의 `--pilot-screening`은 가정 튜플·명시적으로 빈 폐업일·유일 MGTNO·유효 좌표·반경1000m·전화형식·상호/도로명주소를 같은 행에서 집계합니다. 모든 중복 그룹 제외,1000–1030m 경계 제외와 UNKNOWN을 보존합니다. **상태 사전 미확보**를 출력에 명시하며 생산 후보 수 null·추천/채택/예약/발신 false입니다. 공급자 adapter/API/DB에 연결하지 않았습니다.
- Python29개(기존15+신규14)·MOCK 통과. 초기 결측 테스트의 batch 스키마 전제 오류를 수정하고 재검증했습니다. 초기 Gradle은 Java FROM-CACHE였으나 원격 CI에서 기존 REC-08 계약 파일 캐시 누락을 확인했습니다. test 산출물 선언 후 format/verify·Java268개를 재실행해 실패/오류/skip0, 파일 제거 후 FROM-CACHE 복원·계약2개 통과를 확인했습니다. CI에는 가상 옵션만 추가하며 서버 런타임 변경은 없습니다. 실제 행 fixture·새 런타임 의존성·Android 변경 없음.
- 키 없이TLS443 연결1회가 timeout이어서 애플리케이션 요청/키 전송0회입니다. 다른 서비스의 공식 HTTPS 미제공 답변을 해당 API의 확정 결론으로 확대하지 않습니다. 다음은 공식 상태 사전·batch/TZ·안전한 공급 경로/접근과 제한된 실제 지역 품질 검증입니다. 원문 저장 없이 집계만 보존하며 기존 sample 상한은 재사용하지 않습니다. #34 OPEN/#11 채택 대기와 #3/#4/#26의 인수를 유지합니다.
- 이번 실제 음식점 조회·Kakao·전화·AI·유료 호출0회. 로컬 증거는 Git 제외 work/rec10-tests.log·mock-screening.json·transport-check.json·cache-output-verify.log·cache-restore.log·java-test-counts.json, 공개 상태는 docs/quality/rec10-validation-state.json입니다. 계정 정보·키 값·상태 화면을 Git에 올리지 않습니다.

## 2026-11-09 REC-09 #32 성동구 데이터 명세·품질 확인

- 최신 main35e22a8에서 feat/32-seoul-place-quality로 분리했습니다. #11의 검증 후보를 실제 제품으로 채택하지 않고 [독립 품질 도구/인수 기록](../docs/REC09_SEOUL_DATA_VALIDATION.md)을 준비했습니다. #23/#19의 미병합 코드를 가져오지 않았고 다른 담당 코드는 변경하지 않았습니다.
- 공식 Open API 탭의 srvType=A 명세에서 LOCALDATA_072404_SD·39개 필드·SITETEL/X/Y/LASTMODTS/UPDATEDT·공개 sample최대5건을 확인했습니다. 필드/요청 메타데이터는 docs/quality/rec09-source-contract.json입니다.
- pyproj3.7.2 기반 로컬 XML 품질 도구·가상 테스트15개·CI 가상 실행을 추가했습니다. 투영/축·누락·중복 충돌·시각 경계·정상0건/실패·XML 한도·비노출을 확인합니다. 행 수정일·조회 시각을 snapshot 기준일로 바꾸지 않으며 없으면 UNKNOWN입니다. Python품질 환경은3.11+이고 Java/Android 런타임·API/DB/실제 공급자 설정은 변경하지 않았습니다.
- 공개 sample경로를 개인키·결제 없이1회/5건 읽고 원문을 저장하지 않았습니다. 전화 형식5/5, 좌표 누락2/5, 주소 누락3/5, 행정상 영업/정상1/5·폐업4/5입니다. 첫5건의 제한 표본이며 모집단 품질·번호 유효·예약 가능을 뜻하지 않습니다. 집계는 docs/quality/rec09-public-sample-2026-11-09.json, 가상 테스트와 구분합니다. 추가 실제 요청은 하지 않았고 이1회 상한은 소비했습니다.
- 남은 제품 인수: 일반키/접근·batch/version/TZ, 현행 영업·해당 지역·전화/좌표의 추가 품질, 출처표시/저장/삭제/전달, #3 발신 승인·번호allowlist·비용, #26 표시·기기 확인. #11 OPEN과 #16–18/다른 담당 범위를 유지합니다. 전화/유료·Kakao/AI 실행·계정/키/IP 설정 변경은 없습니다.
- 가상 Python15개와 문서5개/링크78개·명세/집계JSON·diff/명명 검사를 통과했습니다. CI는 가상 검사만 수행하고 실제 sample을 재호출하지 않습니다. 로컬 Java/기기 검증은 이번에 재실행하지 않았습니다. 증거는 Git 제외 work/rec09-tests.log·mock-profile.json·public-sample-results.json이며 공개 집계와 원문을 혼동하지 않습니다.

## 2026-11-09 SDK 준비 중 기능의 개발 경로 (#30)

- [SDK 대체·단계별 연동 계획](../docs/SDK_FALLBACK_PLAN.md)을 우선합니다. 사용자 결정: 표준 API 우선 → Pleos 환경 검증 → 미지원 기능은 명시적 모의 실행 → 필요 시 Pleos adapter 추가. 기능별 상태·출처/시각·수명주기·주행 제한·SDK-01–10 인수 조건을 문서화했습니다. 새 adapter 구현이나 기기 검증 완료가 아닙니다.
- ArriveOn 프로젝트는 생성됐습니다. 콘솔상 NaviHelper·Vehicle State 사용 가능(별도 신청 불필요), FusedLocation·STT·TTS 준비 중, API 신청 내역 없음입니다. 공식 문서와 모드별 조건을 대조하고 실제 기기에서 다시 확인해야 합니다. 카테고리는 미설정, 팀원 초대는 미실행입니다. 비밀 키/계정 이메일은 공유 문서에 넣지 않습니다.
- 차량 #1은 NaviHelper 기기 증거·ETA 의미 확인을 유지합니다. 현재 위치가 필요한 사용 사례와 표준 API 가용성 검증은 차량 담당과 조율합니다. UX #25/#26의 화면/주행 제한·인증 연결, #3의 서버 전화 공급자 채널은 기존 담당 그대로입니다. 사용자 음성 adapter 런타임은 별도 담당·후속 이슈 배정 후 제품 모듈에서 구현합니다. probe에 제품 음성/예약 기능을 섞지 않습니다.
- `1b37bc2`에서 분기한 문서 PR #31에 사용자 승인으로 먼저 병합한 추천 PR #29의 main `c482baa`를 반영했습니다. 상태/인계 충돌은 양쪽 기록을 보존해 해소했고 main 대비 문서만 변경합니다. #28은 종료됐으며 나머지 Draft PR/기능 이슈는 유지합니다. 아래 BE-02 미병합 표기는 당시 이력입니다.
- 실제 SDK 권한 신청·약관 동의·외부 문의 발송·전화·장소 조회·유료 호출은 이번에 실행하지 않았습니다. 서버 LIVE 차단과 source=PLEOS 거부를 유지합니다. 다음 담당자는 이 문서의 단계별 배정/가용성 검사를 먼저 조율하고, 구현 PR에서 테스트·계약·STATUS를 갱신합니다.
- 최신 main 반영 후 문서 7개·로컬 링크 79개·계획 인수 ID 10개·개행/코드 블록·개인정보 패턴·명명 규칙·diff 정적 검사를 통과했습니다. 런타임 코드는 변경하지 않았고 Gradle/기기 테스트는 로컬에서 새로 실행하지 않았습니다. 기존 OPEN PR의 검증과 이번 문서 검사를 구분합니다.

**REC-08 #28 (2026-11-09):** [후보 추천·질문 계획](../docs/REC08_RECOMMENDATION_PLAN.md)의 순수 평가기·MOCK 실행 예제·별도 JSON Schema를 추가했습니다. 최대3개 후보에 추천 근거·누락 정보·UNKNOWN 질문을 반환하고 bookable/발신 승인은 false입니다. Java268개(기반228+신규40)·계약3문서·부적합변형8개·JAR 인증/MOCK/H2 재시작/LIVE 차단 검증을 통과했습니다. 당시 검증 기준은 인증 #21이 병합된 main 1b37bc2입니다. #19의 main 충돌은 2026-11-09 별도 작업에서 해소했고 #16/#26 제품 화면 연결은 별도 인수입니다. 추가 실제 검색/전화/AI/비용 작업0회이며 #11/#16–18의 완료를 의미하지 않습니다. #29는 사용자 승인 후 c482baa로 main에 병합됐고 #28은 종료됐습니다.

## BE-02 #20 작업 브랜치 — 2026-11-08 (PR 제출·미병합)

- `/api/v1`에 앱 설치별 익명 주체·토큰 발급/검증/갱신/폐기를 구현했다. 사용자는 로그인하지 않는다. 갱신 후 주체는 유지하고, 같은 갱신 토큰+요청 키는 120초 내 동일 응답을 재생한다. 토큰 원문을 DB에 저장하지 않는다.
- V3 소유권 격리를 유지하고 V4 인증 저장 구조를 추가했다. 기존 데이터는 미확인 소유권으로 보존하며 자동 배정하지 않는다. 보호 API는 실제 발급한 접근 토큰으로 호출할 수 있다. 일반 토큰은 MOCK_OPERATOR 권한이 없다.
- #16은 동일 Principal·필터 순서로 연결한다. 소스는 작업 브랜치에 합치지 않고 임시 통합 검증한다. 계약 1.1.0, URL v1을 유지한다. 제품 Android 토큰 저장·자동 갱신은 별도 후속 작업이다.
- 사용자 승인에 따라 기능별 6개 커밋으로 분리했다. PR 검증 후에도 사용자 지시 없이 병합하지 않는다. #20 범위는 토큰 인증까지 확대했고 #4의 UNKNOWN 해결·승인 증빙·outbox·운영 DB는 후속 범위다.
- 발급 제한은 단일 서버 메모리 기준이다. 운영 프록시/분산 제한·만료 토큰 정리·앱의 안전 저장·복구 UX는 후속 작업이다. 실전화·실제 장소·유료 호출은 없다.

- 최종 검증: 서버 227개, #16 `6f286f8` 전체 통합본 271개 테스트 실행·실패/오류/skip 0. 실제 발급 토큰의 HTTP·갱신 응답 재시도·폐기·H2 재시작과 기존 예약/UNKNOWN 스모크 통과. 통합본의 미리보기 계약 96건 및 실제 토큰 HTTP 16건(생성/검색/취소/종료·타인 접근·갱신 후 소유권·폐기) 통과. Android·원격 CI·실전화는 미검증이다.
- #16 통합 시 문서 2개와 스모크 스크립트 충돌을 양쪽 내용 보존으로 해결하고, PlacePreviewDisabledTest의 계약 기대값을 1.1.0으로 맞췄다. 인증 필터가 조기 거절할 때 #16 보안 헤더가 누락되는 문제를 발견해 원본 토큰 필터에서 수정하고 회귀 검사했다. 실제 #16 브랜치는 변경하지 않았다.

아래는 기존 main 및 이전 작업의 검증 이력이다.


**통합 범위와 검증 기준 (2026-11-07):** [PR #14](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/14)를 `8cbe890`으로 squash merge했고 #12는 CLOSED입니다. #15는 이 main을 반영한 상태에서 통합 검증했습니다. #13의 종료 범위는 독립 미저장 시연·최초 실제 검색 검증입니다. 미완료 제품 인증/API/화면·navigation 계약은 #16, 자동 정리·재시작/다중 서버 실행 관리는 #17, 지역 품질·실제 IP 제한 검증은 #18로 이관했고 #11 정책 검토는 OPEN입니다. [통합 범위와 후속 조건](../docs/REC_FOLLOWUPS.md)을 우선합니다.

**통합 검증:** `./gradlew -PincludeAndroid=true verifyAll` 성공. 서버 200개 실행·실패/오류/skip 0, Android Lint/APK 검사 통과(일부 UP-TO-DATE, 단위 테스트 NO-SOURCE). `work/contract-venv/bin/python scripts/smoke-test.py --check-contract`로 JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단을 확인했습니다. 기기 실행과 실제 예약은 검증하지 않았습니다.

**실제 실행 증거:** [독립 시연](../docs/REC04_LIVE_SEARCH.md)의 Kakao 1회 성공·3곳 일시 표시·쿼터 1 / 100,000, 비용 0원·전화/예약/AI 0회입니다. 이번 병합 중 추가 실제 호출은 없으며 제품 MOCK/LIVE 차단·응답 미저장을 유지합니다. 앞선 브랜치별 188개/156개 기록과 통합 결과를 구분합니다. 아래 OPEN/Draft·병합 대기 표현은 당시 이력이며 최신 병합 상태는 GitHub가 기준입니다.

**VOICE-01 (#3, 2026-11-08):** 전화 업체 문서·약관 검토를 [업체 검토](../docs/calls/VENDOR_REVIEW.md)에 기록했습니다. 다음 작업은 MOCK 기준 outcome 정규화 시나리오와 outbox·대조 설계(#4 협의)이며, 실제 발신은 대상·목적·비용 상한 승인 전 실행하지 않습니다. 발신·계정·비용 0회입니다.

문서 갱신: 2026-11-07 KST. 기존 제품 실행 기록은 2026-11-04이며, 현재 검증 상태의 기준 문서는 `docs/STATUS.md`입니다.

## 작업 위치와 사용자의 최신 결정

- 프로젝트: 이 저장소 루트. 개인 작업 디렉터리는 공유 문서에 기록하지 않습니다.
- 최초의 빌드 전 초안에서 로컬 MOCK 서버와 협업 기반을 구현·검증했습니다.
- **사용자의 후속 요청에 따라 Maven에서 Gradle로 통일했습니다.** 루트 Gradle 8.11.1 Wrapper, Kotlin DSL, Java 21, Spring Boot 3.5.16. Android는 AGP 8.9.2/Java 17 소스 수준.
- 기본 브랜치 `main`. 사용자 요청과 약관 동의 후 GitHub Free 조직 `arriveon-lab`, 공개 저장소 `pleos-reservation-assistant`를 생성하고 첫 커밋 `81f0b40`을 업로드했습니다. 원격 URL·협업 설정은 `docs/GITHUB_SETUP.md`. 초기 이슈 #1–5, 설정 기록 #6을 생성했고 담당자는 미배정입니다.
- 후속 요청으로 공식 웹 레퍼런스 기반 코드 규칙과 영역별 AGENTS/검증 환경을 추가했습니다. 사용자가 Google SDK 약관에 동의해 이 Mac의 빌드용 SDK 설치·프로젝트 연결·debug APK/Lint까지 확인했습니다.
- 실제 외부 전화·번호 구매·유료 API 실행은 없었고, 실행 승인을 받은 적도 없습니다.
- 이전 인계 원문과 Maven 설정은 로컬 Git 제외 `work/CODEX_HANDOFF.initial.md`, `work/legacy-maven/`에 보존했습니다. 현재 빌드에는 사용하지 않습니다.

## 먼저 읽을 것

`AGENTS.md` → `README.md` → `CONTRIBUTING.md` → `docs/STATUS.md` → `api/README.md`.
팀 일정은 `docs/PLAN.md`, 계정/업체/SDK 증거는 `docs/INTEGRATIONS.md`, 조직 준비는 `docs/GITHUB_SETUP.md`. 코드 규칙은 `docs/CODE_CONVENTIONS.md`, 검증 환경은 `docs/AGENT_HARNESS.md`, 수정 영역의 `android/AGENTS.md` 또는 `server/AGENTS.md`도 읽습니다.

## 재현 명령

저장소 루트에서:

```bash
./gradlew verify
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar
# 별도 터미널
python3 scripts/mock-demo.py
```

임시 DB·임의 포트에서 패키지 실행/재시작/LIVE 차단 검증:

```bash
python3 scripts/smoke-test.py
```

계약까지 대조:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements-dev.txt
.venv/bin/python scripts/smoke-test.py --check-contract
```

전체 포맷 `./gradlew -PincludeAndroid=false format`. 서버 의존성 변경 시 `./gradlew -PincludeAndroid=false verify --write-locks` 후 `server/gradle.lockfile` 리뷰.

Android 환경은 이 Mac의 `~/Library/Android/sdk`에 설치했습니다. 루트 Git 제외 local.properties/gradle-local.properties가 연결되어 있습니다. 새 팀원은 SDK 설치 후 `python3 scripts/setup-android.py --configure`. Android 확인은 `./gradlew -PincludeAndroid=true verifyAndroid`, 서버까지 함께는 `./gradlew -PincludeAndroid=true verifyAll`입니다.

## 이번에 구현한 것

- v1 REST API: 검색/후보, 모의 문의 작업, 결과 주입, 예약 요청/조회, 변경 요청/조회
- `WorkflowService` 트랜잭션, `Store` DB mutex, 요청 키+본문 해시, eventId 중복 방지
- 같은 tripId의 PENDING/CONFIRMED/UNKNOWN 예약 중복 차단. 같은 여정 재검색은 같은 tripId 유지
- 명시적 수락·시간·비용 일치 때만 CONFIRMED. ANSWERED나 통화 완료만으로는 확정하지 않음
- 변경 UNKNOWN이면 기존 CONFIRMED/confirmedTime과 pendingChangeCallId를 함께 보존; 자동 재시도 차단
- 추가 비용 사용자 확인·누적 정책 한도, 재문의/변경 10분 간격
- 가족 필수 조건·미확인 정보·만료 처리, NOW 대기와 ARRIVAL 예상 대기 분리
- 출처/mode/evidenceRef/observedAt/appliesAt/validUntil/certainty/scope. 늦게 온 관측은 원 관측 시각 보존
- `MockCallAdapter`에는 네트워크 클라이언트/번호/키가 없음. MOCK 외 설정은 시작 실패
- OpenAPI 3.1 `api/openapi.json` 계약 1.0.0 및 실제 HTTP 스키마 검사
- 로컬 시연·JAR 기동·Flyway/H2 재시작 검증 스크립트
- Gradle Wrapper/의존성 lock/Google Java Format/EditorConfig/Git 속성/CI/Issue·PR 템플릿/협업 문서
- Android NaviHelper 원시값 probe 소스·manifest·선택 모듈 구성과 로그 검증 절차

## 실행 결과

- **Gradle clean 빌드·포맷 검사·테스트 25개 통과**, 실패/오류/skip 0
- 패키징 JAR HTTP 흐름 및 H2 재시작 후 확정/미해결 변경 유지 통과
- OpenAPI 문서·예제·실제 시연 요청/응답 대조 통과
- LIVE 시작 차단 확인
- GitHub 첫 main의 `Server and contract`, `Android checks` 성공. 초기 설정 PR #7은 실제 #6 이슈 브랜치를 사용하고 PR naming을 포함한 세 검사를 통과했습니다. main 보호 규칙과 최신 결과는 GITHUB_SETUP 및 원격 설정을 확인합니다.
- 이전 SDK 부재 오류는 해결. **verifyAll 및 최종 verifyAndroid 성공**, Java/DEX/debug APK 생성·debug 서명 검증, Lint **오류 0/경고 0**. Android 테스트 소스는 없어 NO-SOURCE이고 기기 실행은 미검증
- 코드 규칙: Java GJF 1.28.0, Kotlin/Gradle ktfmt 0.54 kotlinlangStyle, Spotless 7.0.4. 서버만 포함해도 Android 소스 포맷을 검사. Android 비활성화 상태의 verifyAndroid는 명확히 실패함을 확인
- SDK CLI 22.0 / Platform 35 rev 2 / Build-Tools 35.0.0 / Platform-Tools 37.0.1. Studio·Emulator·Pleos 이미지 미설치. 전역 셸/Codex 설정 변경 없음
- APK: `android/probe/build/outputs/apk/debug/probe-debug.apk`. 현재 SHA-256과 Lint report는 STATUS 참조
- 기존 로그: Git 제외 `work/gradle-verify.log`, `work/smoke-contract.log`. 최신 환경 검증: `work/conventions-verify.log`, `work/android-verify.log`, `work/android-verify-final.log`. 서버 동작 변경은 없고 기존 25개 테스트 성공 결과를 UP-TO-DATE로 확인

## SDK에 관해 새로 확인한 사실

- NaviHelper 3.0.5와 navi-data 3.0.4 공식 AAR/classes.jar에 대해 javap 확인
- `getDestinationInfo()`, `requestTrip(TripRequest)`, `onDestinationInfo`, `onTripInfo` 존재. `getTripInfo()`를 만들지 말 것
- 공식 sources JAR의 `TripInfo.kt`: eta는 int이며 **단위 seconds**라는 주석 확인
- `DestinationInfo.kt`: remainTime은 남은 이동 시간, 초라는 설명 확인
- TripInfo eta의 남은 시간/epoch/자정 기준 등 **시간 기준은 아직 불명확**. 임의 Instant 변환 금지. 서버 source=PLEOS는 검증 전 거부
- sources JAR 공식 URL과 버전 증거는 `docs/INTEGRATIONS.md`, 실제 probe 절차는 `android/README.md`
- probe 로그는 개인정보 위치 대신 관측 시각·monotonic 시각·rawEtaSeconds·상태를 기록. backend 연결 없음

## 다음 우선 작업

사용자가 추가 공유한 SDK API 정책과 연결된 가용성/주행 규제 문서는 `docs/INTEGRATIONS.md`의 API 선택 정책 절에 반영했습니다. 다음 차량 구현은 이 기준을 먼저 읽고 기능 미지원·권한 거부·서비스 단절 대체 흐름을 포함합니다. 이번 반영은 문서/계획 변경이며 기기 검증 완료를 뜻하지 않습니다.

1. 팀원에게 README 기준 재현 확인을 받고 실제 GitHub ID·역할·백업 owner·라이선스 결정. 조직/공개 원격은 생성했으며 이슈 #1–5의 담당자를 배정합니다. 조직 2FA 필수 정책은 적용했으며 CODEOWNERS는 팀원 초대 후 실제 담당자로 등록. 유료 플랜/Actions 비용은 승인 없이 실행하지 않음.
2. 빌드 SDK와 debug APK는 준비됨. Pleos 계정/SDK 신청, Android Studio와 Pleos Emulator/Virtual Console 확보 후 probe 설치·실행. 실제 콜백/권한·초기화 실패 후 재실행·종료 중 늦은 콜백 등 수명 경계도 기기에서 확인.
3. ETA 단위는 확인됐으므로 시간 기준·갱신 주기·권한·백그라운드·도착 이벤트 증거 확보, 변환 adapter+테스트.
4. Android 제품 UI/후보 선택/명시적 예약 승인/변경 미확인 안내, CarUxRestrictions, Gleo 또는 STT/TTS. 현재 probe는 제출용 앱 아님.
5. 장소 사업자와 시범 지역 선택. 전화 회선은 Telkit으로 결정(2026-11-10), 조건은 docs/calls/VENDOR_REVIEW.md. 계정·협의 번호·예상 비용/상한·명시적 승인 없이는 실발신 금지.
6. LIVE 전 인증/사용자 소유권·승인 증빙·allowlist·비용/횟수 제한·웹훅 서명·outbox/timeout·UNKNOWN 대조 해결 구현.
7. 운영 DB는 팀 경험 확인 후 결정. 지속 ETA 지연 감지/자동 변경/학습은 후속 범위. 현재 변경 API는 모의 수동 명령.
8. release APK·시연 영상·접수양식/개별 서명·심사용 접근 확인. 현재 진단 debug APK만 있고 release 앱/영상/서명/제출 없음.

## 유지할 규칙과 한계

- MOCK과 LIVE를 API·화면·저장 데이터에서 구분. 가상 성수동 3곳은 실제 장소 추천이 아님.
- 예약 UNKNOWN은 대조 전 재발신 금지. 변경 미확인은 기존 예약 정보를 유지하지만 매장의 현재 상태를 재확인했다는 뜻은 아님.
- API는 단일 사용자 localhost 개발용. 승인 boolean은 운영 인증 증빙이 아님. 공개 배포하지 말 것.
- 모든 boolean/정수 요청 필드는 OpenAPI required에 맞게 0/false도 명시. null/누락/알 수 없는 필드/타입 강제변환 거부.
- 명령 재전송은 같은 Idempotency-Key. replay는 최초 response snapshot, 최신 상태는 GET. outcome은 eventId로 전역 중복 방지.
- 실제 가족/개인 위치/연락처/녹음·서명·키는 테스트나 Git에 넣지 않음.
- 현재 법률/사업자 약관 검토는 미완료이며 출시 적법성 확정 금지.
- 브랜치 전 지침과 기존 이름을 확인. main에서 type/<GitHub 이슈번호>-<topic>으로 작업하고 PR→squash merge. dev-1 별도 순번이나 사람별 번호가 아님. 커밋/PR은 type(scope): 설명, 72자 이내. CONTRIBUTING의 허용 type/scope를 따름.
- scripts/setup-git.py로 로컬 훅/템플릿 설정. PR naming CI는 브랜치·제목·Closes #번호를 검사. 첫 main의 원격 Verify는 성공했으며 최신 PR 검사는 GitHub에서 확인.
- Android 설치 절차는 docs/ANDROID_SETUP.md. SDK 설치 후 scripts/setup-android.py --configure로 local.properties와 gradle-local.properties 연결. -PincludeAndroid가 로컬 설정보다 우선.

공식 접수 마감 2026-11-15, PT 12-03~04를 기준으로 한 내부 계획은 `docs/PLAN.md`. 팀원 포함 최대5인 요건은 공식 안내 확인, 개인 자격/참가부문/정확한 마감 시각은 추가 확인이 필요합니다.

## 2026-11-06 ERDCloud 설계 초안

- 사용자 요청으로 비공개 ERDCloud 도표를 생성: https://www.erdcloud.com/d/GLtbN3YfiazAMYi6X
- 11 테이블, 107 컬럼, 11 PK, 15 FK를 가져오고 SQL 내보내기로 이름·타입·NULL·키 관계를 대조했다.
- 사용자→trip→검색→후보, 예약→변경→통화→이벤트 흐름으로 테이블을 수동 재배치해 겹침과 교차선을 줄였다. 새로고침 후 위치와 메모 유지 확인.
- ERDCloud 가져오기에서 UNIQUE 5개가 누락되어 도표 메모에 명시했다. 실제 마이그레이션에는 원본 ERDCLOUD_IMPORT.sql의 UNIQUE 및 업무별 조건부 제약을 별도로 구현해야 한다.
- 로컬 원본·대조 자료는 outputs/erd/에 보관. 팀 공유 관계도·컬럼·검증 요약은 docs/ERD.md, 가져오기 SQL은 docs/erd/ERDCLOUD_IMPORT.sql, Mermaid 원본은 docs/erd/ERD.mmd를 사용한다.
- 설계 초안이며 현재 DB/API에 적용하지 않았다. MySQL 문법은 ERD 가져오기용이며 운영 DB 선정 결정이 아니다. 실제 전화·유료 API 실행 없음.


## 2026-11-07 팀 공유 문서 정리

- 읽는 순서와 문서 상태는 docs/README.md, 상세 기능은 docs/FUNCTIONAL_SPEC.md, 이슈별 역할·병행 작업은 docs/ISSUE_GUIDE.md로 정리했다. 기존 outputs의 검토본보다 docs의 공유본을 우선한다.
- docs/ERD.md와 docs/erd/의 SQL·Mermaid는 11개 테이블의 설계 제안이다. 107컬럼·15FK·5UNIQUE 표기를 공유하지만 DB migration으로 실행하지 않았다. ERDCloud는 비공개이며 링크 게시가 팀원 권한을 바꾸지 않는다.
- 다중 방문 예약, 아이 동반 운전자 타깃, 주차·도보·대기 비교, 지연 감지/변경 제안의 MVP 승격은 docs/DESIGN_PROPOSALS.md에 미확정 제안으로 기록했다. 현행 활성 예약 제한·OpenAPI·구현 동작을 변경하지 않았다.
- #1–5는 2026-11-07 GitHub API 확인 시 OPEN·미배정, #6은 CLOSED다. 문서 게시 이슈는 기능 완료와 별도로 관리한다.
- 제품 실행/기기/실통화 상태는 기존 STATUS 기록과 구분한다. 실제 전화·번호 구매·유료 API 실행 없음.
- 문서 게시 추적은 GitHub #8, 브랜치 docs/8-team-design-docs. 기능 이슈 #1–5는 이 PR에서 닫지 않는다. 정적 대조 12항목과 naming/diff 검사 통과, 서버 verify 성공(15개 태스크 UP-TO-DATE)으로 검증했다. 로컬 대조 상세는 Git 제외 work/team-docs-validation.json에 있다.


## 2026-11-07 리뷰 승인 선택 정책

- 사용자 명시 요청으로 main의 required_approving_review_count를 1에서 0으로 변경했다. 리뷰 없이 필수 CI 통과 후 squash merge할 수 있다.
- 필수 CI 3개와 strict, enforce_admins, 선형 이력, 대화 해결, force push/삭제 금지는 유지됨을 API로 확인했다. PR #9의 REVIEW_REQUIRED 차단 해제 확인.
- CONTRIBUTING·GITHUB_SETUP·PLAN·STATUS를 새 정책에 맞춰 갱신했다. 이 작업은 PR을 자동으로 병합하거나 CI를 우회하는 설정이 아니다.

## 2026-11-07 REC-01 상세 착수 계획

- PR #9는 main `35561ee`로 squash merge되었고 문서 게시 이슈 #8은 종료되었다. REC-01은 별도 OPEN 이슈 #2이며 담당자는 `tiemo0708`로 확인했다.
- 사용자 선택: 성수·서울숲 주변 음식점, Kakao Local 우선 검토. 공식 API 적합성 비교와 실제 앱 쿼터·저장/재표시 조건 확인 항목을 `docs/REC01_PLAN.md`에 기록했다. 사업자 실제 채택과 사용 조건 검토는 남아 있다.
- 저장소 지침·기존 브랜치·원격 main을 확인하고 `feat/2-place-adapter`를 생성했다. 상세 계획과 목차·이슈 안내·PLAN·STATUS를 갱신했으며, 이번 변경의 commit/push/PR은 아직 수행하지 않았다.
- 다음 작업은 P1 MOCK adapter 분리다. 기존 mock-1~mock-3, facts와 TTL, 공개 API·저장 구조를 유지한 뒤 P2 가상 JSON 매핑·실패 테스트로 확장한다. 코드 구현·테스트 실행은 아직 하지 않았다.
- 실제 HTTP를 현재 `WorkflowService.search()`의 `Store.command()` 안에 넣지 않는다. #4와 트랜잭션 밖 IO·중복 요청·저장 snapshot, #5와 실제 장소/모의 예약 표시를 합의한다.
- PR naming은 `Closes #2`를 요구하므로 계획만으로 #2를 닫지 않는다. 완료 조건까지 누적하거나 먼저 병합할 범위는 별도 실제 이슈로 분리한다.
- 실제 장소 API 조회·전화 발신·번호 구매·유료 API 실행 없음. 외부 전화·비용 발생 작업은 기존의 사전 승인 규칙을 유지한다.
- 문서 6개의 로컬 링크 77개·계획 테스트 ID 16개·코드 블록·개인 절대 경로와 브랜치/제목 규칙·diff 공백 검사를 통과했다. Gradle이나 제품 실행 테스트를 새로 수행한 결과는 아니다.

## 2026-11-07 REC-01 MOCK 분리와 오프라인 검증

- 후속 사용자 요청에 따라 P1과 P2 오프라인 범위를 구현했다. `RecommendationEngine.fixtures()`를 제거하고 `MockPlaceSearchAdapter`와 `MockCandidateFactory`로 조회/가상 사실 생성을 분리했다. WorkflowService는 구체적인 MOCK adapter만 받으며 외부 IO를 추가하지 않았다.
- 내부 계약은 `PlaceQuery`, `PlaceSummary`, `PlaceSearchResult.Success/Failure`, `PlaceSearchFailure`. 실패 enum은 공개 HTTP 오류 코드가 아니다. `KakaoPlaceMapper`는 HTTP 응답 문자열을 정규화하는 순수 변환기이며 Spring bean·HTTP 클라이언트가 아니다.
- 가상 Kakao JSON은 `server/src/test/resources/places/kakao-fictional.json`. FD6·필수 식별자·유한한 좌표·숫자·응답 구조/크기·최대 15개를 검사하고 provider ID 중복을 제거한다. 일부 불량 항목은 계수하고 전부 불량인 응답은 정상 0건과 구분한다. 실제 연락처·API 키 없음.
- 추천 엔진에 scope·ETA·모드·관측/만료 시각·값 타입 검사를 추가했다. `select()`는 전체 후보 평가 후 최대 3곳을 선택하며 조회 결과를 가상 장소로 채우지 않는다. 기존 mock-1~3과 가상 facts/TTL, 공개 API·schema·migration·의존성은 유지했다.
- `./gradlew -PincludeAndroid=false format verify` 성공. 5개 suite의 **104개 테스트 실행, 실패/오류/skip 0**. 기존 WorkflowTest 25개 + 신규 79개. 리포트는 `server/build/test-results/test/`, 로컬 요약은 Git 제외 `work/rec01-tests.json`.
- `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 성공. 패키징 JAR의 MOCK HTTP·OpenAPI 대조·Flyway/H2 재시작·LIVE 시작 차단을 확인했다. Git 제외 `work/rec01-smoke.log`에 기록했다. Android와 원격 CI를 이번에 실행한 결과는 없다.
- `docs/REC01_PLAN.md`의 구현 상태·테스트 연결과 STATUS·PLAN·ISSUE_GUIDE·FUNCTIONAL_SPEC·문서 목차를 갱신했다. 현재 로컬 작업 브랜치는 `feat/2-place-adapter`, 이번 변경의 commit/push/PR은 아직 하지 않았다.
- 다음 작업: P0 저장/재표시·실제 앱 무료 적용 조건 확인, P3 #4와 IO/잠금·중복 실행·실패 응답·저장 snapshot, #5와 실제 장소/모의 예약 표시 합의. `TIMEOUT`은 실패 종류만 정의했으며 transport 처리는 P4에서 구현·검증한다. #2 완료 처리 전 이 남은 항목을 확인한다.
- 실제 장소 API 조회·전화 발신·번호 구매·유료 API 실행 없음. 실전화와 비용 발생 작업은 대상·목적·예상 비용·상한을 제시해 별도 명시적 승인을 받는다.

## 2026-11-07 REC-01 HTTP 경계와 정책 검토

- `docs/REC01_INTEGRATION.md`에 공식 Kakao FAQ와 2026-08-20 지도/로컬 담당 답변의 응답·가공 데이터 저장 제한을 기록했다. 일반 캐시 조항으로 예외를 가정하지 않는다. 현재 H2 검색 payload와 commands 응답 snapshot에는 실제 Kakao 결과를 연결하지 않는다.
- 사용자는 팀 Kakao 앱이 아직 없다고 확인했다. 앱 생성/무료 배지·쿼터 확인 절차, #4·#5 검토안과 사업자 문의 문안을 준비했으며 앱 생성·약관 동의·키 발급·문의 발송은 수행하지 않았다.
- `KakaoPlaceSearchAdapter`를 구현했다. Spring 미등록·실제 workflow 미연결이며 고정 Kakao HTTPS endpoint 또는 package-local loopback 테스트 endpoint만 허용한다. 연결/전체 응답 timeout, 취소, redirect 차단, 수신 중 256KiB 제한, UTF-8 검증과 내부 실패 분류를 제공한다. 새 의존성은 없다.
- `KakaoPlaceSearchAdapterTest` 26개에서 합성 목적지 3개, 정상/빈 응답, 상태 오류, header/body timeout, 진행 중 취소·인터럽트 보존, 연결 실패, 크기·인코딩·키/endpoint 설정을 127.0.0.1 가상 서버로 검증했다. 총 **130개 테스트 실행, 실패/오류/skip 0**, 6개 suite. `format verify`와 패키징 JAR의 MOCK HTTP/계약·H2 재시작·LIVE 차단도 통과했다.
- 로컬 증거는 Git 제외 `work/rec01-http-verify.log`, `work/rec01-http-tests.json`, `work/rec01-http-smoke.log`. Android·실제 TLS·키·IP 제한·무료 쿼터·지역 데이터 품질을 확인한 결과가 아니다.
- 앱 수준의 재시도/페이지 확장은 없지만 JDK transport의 연결 장애 재전송은 별도로 통제·검증해야 한다. 이를 모든 장애에서 정확히 1건만 과금되는 보장으로 취급하지 않는다. 전역 JVM·셸 설정은 변경하지 않았다.
- 작업 브랜치는 `feat/2-place-adapter`. 현재 범위는 초안 PR로 검토하며 #2 완료나 main 병합으로 간주하지 않는다. 남은 작업은 팀 앱 준비, 보관·제3자 제공 범위 해결, #4·#5 실제 연동 계약, transport 재전송 제어와 실제 환경 검증이다.
- 실제 Kakao 요청·전화 발신·번호 구매·유료 API 실행은 0회다. 실제 비용 발생과 발신은 기존 사전 승인 규칙을 따른다.

## 2026-11-07 Kakao 앱 생성과 무료 쿼터 설정

- 사용자 개인 계정 로그인을 확인한 뒤 앱 이름 ArriveOn, 운영 단체 ArriveOn Lab, 자동차/교통수단 카테고리로 생성했다. 생성 화면의 운영정책 준수 확인과 앱 생성 시 키 발급에 대해 사용자 명시 동의를 받았다.
- 카카오맵 활성화 시 첫 무료 쿼터 대상이 변경 불가하다는 경고를 제시하고 사용자 별도 동의 후 ON으로 설정했다. 이 앱에 일간 무료 쿼터가 제공된다는 안내·배지를 확인했다.
- 유료 API 화면에서 카카오맵·카카오톡 공유 모두 사용 안 함을 확인했다. 비즈월렛·결제 설정 없음. 일간 쿼터 화면에는 조회 기간 내 호출 없음이 표시됐다. 키 값은 열람·복사·환경 파일 저장하지 않았다.
- 비밀 키 없는 앱 ID/관리 URL·설정 요약은 로컬 Git 제외 work/kakao-setup-result.json, 화면은 work/kakao-setup-complete.png 및 work/kakao-paid-disabled.png에 있다. 개인 계정 이메일·다른 앱 정보·캡처·키는 공개 저장소에 넣지 않는다.
- 앞선 구현은 4931126으로 커밋·업로드했고 Draft PR #10을 생성했다. 서버/계약·Android·PR naming CI 3개가 통과했으며 #2는 OPEN이다. 이번 설정 결과는 같은 작업 브랜치의 문서 변경으로 이어간다.
- 다음은 보관·재사용·제3자 제공 범위 해결, #4/#5 실제 연동 계약, transport 재전송 제어와 실제 환경 검증이다. 앱 생성/활성화가 데이터 저장 허가나 서버 연동 성공을 뜻하지 않는다. 외부 문의는 초안만 준비했으며 보내지 않았다.
- 제품 코드·API·migration 변경 없이 문서만 갱신했다. 실제 장소 API 요청·전화 발신·유료 작업은 0회다.

## 2026-11-07 Kakao 공개 문의 등록과 내비게이션 역할

- 사용자에게 정확한 공개 대상·앱 이름/App ID·기능 설명·질문 4개를 제시하고 ‘문의 문안 그대로 등록’ 승인을 받은 뒤 DevTalk에 게시했다: https://devtalk.kakao.com/t/local-api/152647 . 게시 제목·본문이 승인 문안과 일치함을 확인했다. 키·계정 이메일·실제 연락처·첨부파일은 포함하지 않았다.
- 공개 문의는 답변 대기이며 저장·제3자 제공 허가를 받은 상태가 아니다. 후속 답변의 적용 범위를 확인한 뒤 실제 연동 경계를 결정한다. 공개 화면은 Git 제외 work/kakao-policy-posted.png에 보관했다.
- 사용자가 Pleos 내부 지도 사용 여부를 물어 공식 NaviHelper 3.0.5 안내와 배포 SDK 공개 메서드를 재확인했다. 내비게이션·목적지·ETA는 Pleos, 주변 식당 후보는 별도 장소 adapter라는 현재 설계를 INTEGRATIONS에 명시했다. Kakao 지도 SDK/지도 화면 교체는 구현하지 않았다. 일반 음식점 후보 목록 검색 메서드는 이번 NaviHelper 확인 범위에서 찾지 못했으며, Pleos 전체 기능 부재로 단정하지 않는다.
- 앱 설정 문서는 f4a1001으로 업로드됐고 서버·Android·PR naming CI가 통과했다. 이번 후속 문서도 같은 Draft PR #10에 이어가며 #2는 OPEN이다. 소스·API·DB 변경, 실제 장소 요청·전화·유료 작업은 없다.


## 2026-11-07 후속 인계: 재전송 제어와 P3 계약 검토

- 같은 `feat/2-place-adapter` / Draft PR #10에서 진행했습니다. 실제 adapter 생성 시 두 JVM 시작 옵션(`-Djdk.httpclient.disableRetryConnect=true`, `-Djdk.httpclient.redirects.retrylimit=1`)을 검사하며 HTTP/1.1을 명시합니다. 속성을 늦게 추가하는 방식은 거부합니다. 기본 MOCK 서버는 이 adapter를 생성하지 않으므로 기존 실행 명령을 유지합니다.
- `KakaoTransportStartupTest` 신규 14개: 시작 옵션 누락/오류/충돌·늦은 변경·정상 생성, 정상 빈 응답·응답 전/본문 중단·TCP 연결 재사용 후 단절. 모두 별도 JVM과 127.0.0.1 가상 서버이며, 기존 130개와 합해 총 144개 실패/오류/skip 0입니다. verify와 JAR smoke/계약·H2 재시작 검증 통과. 실제 TLS/사업자 요청 수 보장으로 확대 해석하지 않습니다.
- `docs/REC01_CONTRACT_DRAFT.md`는 #3/#4/#5 검토용 제안입니다. 외부 미리보기/저장 예약 분리, IO·중복·목적지 변경·출처/모드·오류/화면 상태·인수 조건을 작성했으나 API/DB에 적용하지 않았습니다.
- 정책 문의는 새로고침 후에도 답변 없음. Kakao 검색·비용·전화 0회. 다음은 사업자 답변과 #4/#5 계약 검토를 반영한 실행 경계 구현이며 #2를 완료하거나 PR을 병합하지 않습니다. 실제 호출 전 목적/횟수/상한, 무료 잔량과 유료 꺼짐을 다시 확인합니다.


## 2026-11-07 이슈·브랜치·PR 범위 정리

- 사용자 요청으로 #2 제목을 “REC-01: 장소 adapter 기반 구현과 MOCK 검증”으로 조정했습니다. 원래의 미완료 조건은 #11(정책/공급자), #12(계약·IO/중복/복구), #13(실계정 API 검증)으로 실제 생성해 이관했습니다. 세 이슈 모두 tiemo0708 담당이며 OPEN입니다. #4/#5의 기존 범위/배정은 바꾸지 않았습니다.
- 현재 브랜치 `feat/2-place-adapter`를 유지합니다. PR #10은 “feat(recommendation): 장소 adapter 기반과 MOCK 검증 추가”, `Closes #2`와 `Refs #11/#12/#13`으로 정리했습니다. 이번 작업은 머지가 아니며 PR은 Draft입니다.
- 후속 브랜치 계획: `docs/11-place-data-policy`, `feat/12-place-preview`, `feat/13-place-api-validation`. 아직 생성하지 않았습니다. #2 통합 뒤 최신 main에서 해당 번호의 브랜치를 만듭니다.
- 후속 이관 시 완료 범위·원래 범위·담당/의존성·Refs 연결을 함께 기록하도록 CONTRIBUTING과 PR 템플릿을 보강했습니다. 기존 Closes 규칙·CI·main 보호는 변경하지 않았습니다.
- 제품 코드 변경은 없습니다. 기존 144개 테스트·JAR smoke 증거를 유지하며 이번에는 명명·문서 링크·diff 정적 검사를 수행합니다. 실제 Kakao 조회·전화·비용 0회와 MOCK/LIVE 차단을 유지합니다.


## 2026-11-07 REC-04 독립 실제 장소 검색 1회

- 사용자가 저장 없는 실제 검색 시연 진행을 지시했고, 일반 실시간 검색을 #11 전체 문의 답변이나 #12 전체 완료와 분리했습니다. `feat/13-place-api-validation`은 main `a55de3a` 기준입니다. 추천/장소 담당의 검색·정규화·품질 검증 범위이며 제품 UI #5·전화 #3·인증/DB #4는 별도로 유지합니다.
- `PlacePreviewDemo`와 `:server:placePreview`를 추가했습니다. 기본 MOCK, 명시적 live 모드에서만 기존 Kakao adapter를 사용합니다. 127.0.0.1의 임의 경로 첫 GET에만 query(FD6·위도37.544/경도127.055·1km·거리순·page1·size3)를 실행하며 새로고침/동시 열기/잘못된 경로/POST/cross-site/prefetch가 추가 호출을 만들지 않습니다.
- 무료 대상/카카오맵 ON·유료 꺼짐·조회 전 사용량·기존 REST 키/IP 설정을 확인했습니다. 기존 REST 키 값을 출력하지 않고 Git 제외 로컬 파일(0600/부모0700)에 설정했습니다. 새 키 생성/재발급·IP/유료/월렛 설정 변경은 없습니다.
- 실제 HTTPS/키 인증 조회 1회 성공, 실제 식당 3곳을 브라우저에 일시 표시했습니다. 공급자 콘솔 카테고리 검색이 호출 없음 → 1 / 100,000으로 바뀐 것을 확인했습니다. 비용 0원, 전화/예약/AI 호출 0회. 응답 원문·장소 ID/주소/연락처·결과 캡처를 파일/DB/로그/fixture에 저장하지 않았습니다. 검증 상태/개수/실행 수 메타데이터만 기록했습니다.
- no-store/no-referrer/CSP·문자열 escape, 출처/오류/정상0건과 미확인 조건 구분, 5분 표시 종료를 적용했습니다. 첫 응답 후 프로세스가 종료되며 API 결과 캐시나 제품 검색/예약 연결은 없습니다. 제품 app.mode=LIVE 시작 차단과 기존 H2 명령 snapshot은 유지합니다.
- main의 기존 144개 + 도구 HTTP/키 검증 12개 = 156개 실패/오류/skip 0, format/verify와 패키징 JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단 통과. 별도 PR #14의 미병합 188개 테스트를 줄이거나 덮어쓴 것이 아닙니다. 새 의존성·migration·공개 API/Model 변경 없음.
- 다음: 다른 시범 입력/실데이터 품질·IP 제한 사례의 실행 범위 정리, #5 화면/#12 API 계약 연결, #11 예약 보관/전화 제공 조건. 최초 1회 성공을 전체 지역 품질·실예약 완료로 세지 않고 #13은 OPEN/Draft PR로 관리합니다. 실행 메타데이터는 Git 제외 work/rec04-first-live-result.json, 검증은 work/rec04-preview-*에 있습니다.

## 2026-11-07 후속 인계: 기반 merge와 REC-03 첫 구현

- 사용자 명시 지시로 PR #10 squash merge 완료: `a55de3a`. #2 CLOSED, #11/#12/#13 OPEN. main 보호·리뷰 0명·필수 CI·squash-only를 유지했고 원격 기반 브랜치는 삭제됐습니다.
- 현재 작업 브랜치 `feat/12-place-preview`는 merge commit을 받은 main에서 생성했습니다. `docs/REC03_PREVIEW.md`가 현재 단계의 계약/한계/테스트 안내입니다.
- `PlacePreviewSession`을 가상 adapter로 검증했습니다. 동일 진행 세대 합류, 완료 세대 자동 재호출 없음, 충돌/구세대, 새 목적지·취소·deadline·늦은 결과와 최신 큐를 처리합니다. 세션당 worker 1개/대기 1개, 결과 캐시 없음, 마지막 query 1개는 close까지 메모리 보유입니다. 실제 Kakao 데이터의 메모리 보유 허가는 #11에서 확인해야 합니다.
- 신규 25개 + 기존 144개 = 169개 실패/오류/skip 0, format/verify 및 JAR smoke/OpenAPI/H2 재시작/LIVE 차단 통과. 가상 작업 대기 중 기존 H2 명령이 진행되고 preview는 DB를 쓰지 않으며 트랜잭션 안 start는 거부됩니다.
- Spring bean·public endpoint·WorkflowService 연결은 하지 않았고 공개 API/Model·migration·의존성 변경도 없습니다. #12는 팀 검토, 실제 세션 소유권/상한/재시작 복구, navigation freshness, 공개 계약/화면이 남아 OPEN입니다. 신규 PR은 초안으로 유지하며 사용자 지시는 기존 PR #10 머지와 #12 착수입니다.
- 실제 Kakao 조회·전화·비용 0회. #11 답변 확인, #12 계약/소유권/복구 구현, #13 조건부 실제 검증 순서로 이어갑니다. 외부 문의 추가 발송이나 신규 실제 호출은 수행하지 않았습니다.


## 2026-11-07 REC-03 소유 scope·세션 수명과 실행 상한

- 같은 `feat/12-place-preview` / Draft PR #14에서 `PlacePreviewRegistry`를 추가했습니다. 서버 생성 UUID, 신뢰된 호출자 owner scope 대조, owner별/전체 세션 상한, 단조 경과 시간의 유휴 만료를 구현했습니다. foreign/미존재/만료 세션은 같은 NOT_FOUND로 처리하고 비정상 요청은 수명을 갱신하지 않습니다.
- 여러 세션의 동시 실행 슬롯·Registry 수명 누적 승인 예산을 공유합니다. 취소/만료를 무시하는 adapter가 실제로 반환하기 전까지 슬롯을 유지하고 실패·취소나 세션 재생성으로 누적 예산을 돌려주지 않습니다. 삭제/재시작된 UUID를 자동 복원하거나 재조회하지 않습니다.
- Registry 18개 + H2 추가 1개를 더해 총 **188개 실행, 실패/오류/skip 0**입니다. 기존 H2 비차단/DB 미저장 테스트도 Registry 경로에서 통과했습니다. format/verify와 JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단 통과. 로컬 증거는 Git 제외 `work/rec03-registry-results.json`, `work/rec03-registry-verify.log`, `work/rec03-registry-smoke.log`에 있습니다. 초기 테스트 helper 컴파일 오류는 수정했고 초기 로그도 따로 보관했습니다.
- 로그인 인증·외부 요청의 owner 검증은 아직 미구현이며, Owner는 trusted caller가 공급할 내부 scope입니다. 만료는 요청/명시적 reap 시 정리하고 자동 정리 스케줄은 아직 없습니다. Registry 재생성/다중 인스턴스를 넘는 누적 예산·영속 claim·불확실한 실행 대조도 미완료입니다.
- 공개 REST API·Spring bean·기존 WorkflowService 연결·Model/migration/의존성 변경 없음. navigation freshness/ETA 재검증, #4/#5 계약·공개 API/화면, 실제 인증/복구는 다음 작업입니다. #12는 OPEN·PR #14는 Draft로 유지하며 실제 Kakao 조회·전화·비용은 0회입니다.

후속 복구 확인: 11:07:51.902254Z에 TripInfo rawEtaSeconds=666/POSITIVE를 수신했습니다. 이는 원시값 수신 확인이며 남은 시간/도착 시각으로 변환하지 않습니다.
