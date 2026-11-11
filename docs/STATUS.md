# 구현·검증 현황

## 2026-11-11 서버 답변 반영·ETA 없는 목적지 추천 인수 구분

[새 서버 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6771962584)와9709887 인계 문서를 확인했습니다. [앱 인수안](VEH01_APP_HANDOFF.md)에 기존 Navigation의 source 거절 순서/INVALID_TIME/STALE_NAVIGATION/INVALID_REQUEST와 정확한 시간 경계(now<eta<=now+86400, now-300<=observedAt<=now+5)를 반영했습니다. 로컬 WorkflowService도 정적으로 대조했고 HTTP 실행으로 세지 않습니다.

#53 목적지 추천은 eta/observedAt 없는 별도 계약이므로 ETA 미검증 때문에 목적지 추천 검토까지 막지 않습니다. 목적지 현재 경로 연관성·view/generation 무효화는 여전히 앱 공동 인수이며 서버/UX 코드는 수정하지 않았습니다. ETA 사용하는 예약/지연 통보의 PLEOS 차단은 유지합니다.

[후속 검증 절차](VEH01_REMAINING_VALIDATION.md)에 도착·단절·미지원·늦은 응답별 필요한 환경/판정 조건과 가상 경계 예제를 정리했습니다. 공식 Console 안내에서 내비 좌표/경로 재생 절차를 확보하지 못했으며 실제 기능 부재라고 단정하지 않습니다. 현재 ADB0대이고 신규 기기/HTTP/빌드 실행은 없습니다. 이번 변경은 문서만이며 commit/push/외부 답변/PR·Issue 변경은 하지 않았습니다.


## 2026-11-10 PR #47 최신 main 2e873bc 반영

사용자 승인으로 feat/1-navi-probe에 origin/main2e873bc를 공유 이력 보존 merge로 반영했습니다. STATUS/HANDOFF 두 문서 충돌에서 양쪽 신규 기록을 모두 보존했고 미해결 충돌0/추가 행 보존/명명/diff 검사를 통과했습니다. 서버/API는 origin/main과 동일하며 main 자체나 기존 커밋은 수정하지 않습니다. ETA/권한 신규 근거의 고정 기준504b867과 링크는 계속 유효합니다.

통합 verifyAll 첫 오프라인 실행은 main의 고정 jsoup1.24.1 캐시 부재로 테스트 전 중단됐습니다. 버전/잠금 파일을 바꾸지 않고 의존성을 받은 재시도에서 서버313개 중312개 통과/1개 실패(오류0/skip0), 기존 PlacePreviewDemoTest의 Windows Files.setPosixFilePermissions 미지원 UnsupportedOperationException218행입니다. 서버 테스트/보안을 우회 수정하지 않았습니다. 별도 verifyAndroid15초 성공(54태스크 중53 UP-TO-DATE; 기존6개 테스트 결과 재사용), OpenAPI3.1/예제 검사 성공. 이번 기기/smoke는 재실행하지 않았습니다. 원격 CI는 새 head에서 별도 확인합니다.

이 변경은 네 작업 브랜치만 일반 Push하며 PR Draft/#1 미완료 유지 대상입니다. main PR 병합·Issue 변경/종료·리뷰·Draft 해제·force push는 하지 않습니다.


## 2026-11-10 화면·SDK 근접 관측과 공유 근거 정리

[신규 관측 요약](VEH01_OBSERVATIONS_20261110.md)과 비식별 docs/evidence/veh01-20261110.json에 오늘의 ETA·권한·버전 근거를 묶었습니다. 동일 스크립트의 수동2회 응답은 모두681초(86.199초 간격), 내비는12분/도착20:21→20:22였습니다. 기기 시각/초 해상도를 고려한 응답→캡처 구간은 첫0.437–4.437초 미만, 두 번째1.238–6.238초 미만입니다. 이전72–82초 지연보다 줄었지만 현재 경로 연관성/UI 반올림/시간 기준은 미확인으로 유지합니다. 이동 시간 계열 가설과 계약 확정을 구분하며 Instant 변환·PLEOS 차단 해제·제품 연결은 없습니다. 도착/단절/미지원·늦은 응답의 필요한 환경과 검증 계획을 함께 정리했습니다. 이번 변경은 문서/비식별 발췌만이며 사용자 승인으로 별도 docs(android) 커밋·일반 Push에 포함합니다. 아래 미커밋/공유 전 표기는 각 관측 당시의 이력이고, 최신 공유 기준은 이 문서와 비식별 JSON을 함께 포함한 커밋입니다.


## 2026-11-10 소영님 #1 코멘트 인계 형식 반영

[인계 표](VEH01_APP_HANDOFF.md)에 요청하신5개 열·검증 commit/버전·비식별 근거·다음 확인 항목/환경을 추가했습니다. 원격49115f6과 미커밋 신규 기기 관측을 분리하고 제품 adapter 미연결/PLEOS 차단 유지를 명시했습니다. 서버 담당의 source·시간 변환·freshness/오류 예제 검토 후 앱 인수 순서이며 CarUx 범위는 #25로 유지합니다. 이번 변경은 문서이고 GitHub에 해당 코멘트를 참조한 [확인/적용 결과 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6760697971)을 게시하고 본문 재조회로 확인했습니다. commit/push는 수행하지 않습니다.


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

GitHub API로 [추가 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6739718159)와 #16/#26 본문을 확인했습니다. [차량 데이터 앱 인수안](VEH01_APP_HANDOFF.md)에 목적지/ETA/관측 시각·가용성, 변경/취소/도착/오래됨/권한/미지원/단절/늦은 콜백 상태, Navigation 변환 차단 조건과 공동 인수 시나리오를 정리했습니다. 상태명·유효기간·제품 adapter는 협의/미구현이며 공통 계약을 변경하지 않았습니다. ETA 원시값 수신을 앱 연결 완료로 세지 않고 서버 PLEOS 차단·기존 예약/비용 승인 경계를 유지합니다.

실제 Navigation/probe 설치 버전 조회는 adb emulator-5554 부재로 실패했습니다. 내비 버전은 미확인, probe 버전은 Gradle 설정과 APK hash 근거로 구분했습니다. 기기를 새로 시작하거나 기존 데이터를 변경하지 않았습니다. ETA 기준/주기·도착·실패 조합·공동 기기 인수는 남아 #1 OPEN 유지 대상입니다. 이번 변경은 문서만이며 GitHub 댓글 게시·commit/push/PR/merge는 없습니다.


## 2026-11-09 공식 문의 초안 검토

[공식 문의 초안](../outputs/VEH01_PLATFORM_SUPPORT_DRAFT.md)을 최신 격리 시험과 대조해 정리했습니다. 안정 관측131초의 완료 콜백 미수신과 별도 Watchdog 시험을 분리하고, 후속 Trip 원시값666·ETA 기준·응답 연관성·Navigation 상태별 콜백 조건을 질문에 포함했습니다. 공식 Routes 문서를 재확인했지만 시간 기준/완료 보장을 추가로 확정하지 않았습니다. 일반 공식 연락처의 기술 지원 전용 여부는 미확인입니다. 외부 발송·제품 코드/공통 API 변경·테스트 재실행·commit/push/PR은 없습니다.


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
- 증거는 captures/veh01-reroute-recovery-summary.json, veh01-reroute-platform-instability.json, veh01-clean-data-recovery.json, veh01-reroute-user-started-20261109-live.jsonl(12건), work/veh01/reroute-request-result.png에 있습니다. [공식 문의 초안](../outputs/VEH01_PLATFORM_SUPPORT_DRAFT.md)은 미발송입니다. 명시적 요청은 부분 확인됐지만 완료·도착·자동 이탈 재탐색·ETA 기준/연관성·실패 조합 등은 미완료이며 #1 종료/commit/push/PR/merge는 하지 않았습니다.

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

## 2026-11-09 중단 후 재개

- 후속 직접 진행: boot_completed=1/activity 서비스 존재와 내비 Activity 실행 ok를 재확인했습니다. 초기 로딩 후 기본 지도/검색 입력 영역을 한 번 관측했으나 검색 조작 뒤 검은 화면이 반복됐고, 지원 geo Intent로 공개 목적지 검색을 열어도 검색 결과/경로 설정은 확인하지 못했습니다. 앱 종료 사유 조회에서는 원인을 확정할 항목을 얻지 못했습니다.
- 원인 분리를 위해 emulator 공식 `-help-gpu`를 확인하고, 테스트 기기를 정상 종료한 뒤 동일 AVD 데이터를 보존해 `-gpu software`로 한 번 재실행했습니다. 부팅과 내비 실행은 성공했으나 로딩 후 내비 영역이 빈 화면으로 남았습니다. 따라서 host GPU만의 문제로 확정하지 않습니다. 영구 GPU 설정/보안/계정/권한/앱 데이터는 변경하지 않았으며 새 경로/ETA 수집 성공으로 기록하지 않습니다.
- 로컬 Git 제외 증거: work/veh01/navigation-search.png, navigation-search-open.png, navigation-geo-search.png, software-navigation-ready.png 및 software-gpu.out.log/err.log. 현재 실행은 임시 software GPU 모드이며 기본 Start-Pleos.cmd의 host 설정은 그대로입니다. 다음은 내비 화면/검색 정상화 원인 확인입니다. SDK/API 변환이나 서버 코드를 이 현상 때문에 바꾸지 않았습니다.

- 이전 짧은 경로 603초 비교·보존 한계 문서가 저장돼 있음을 확인했습니다. 원시 스냅샷 11건은 중복/민감 필드가 없었고 이전 비교 JSON과 화면은 별도 로컬 복사본으로 보존했습니다.
- 연결이 없어 기존 D드라이브 AVD를 재실행했습니다. boot_completed=1, probe COLD/Status ok, 새 세션 SDK initialized와 목적지 MISSING_OR_INVALID를 확인했습니다. Trip 버튼 비활성으로 요청 스크립트가 중단됐고 새 ETA를 임의 생성하지 않았습니다. 누적 수집기는 7건을 추가해 18건을 보존했습니다.
- 이어 공개 목적지 검색을 열려 했으나 activity 서비스 없음/boot completed 이전 broadcast 오류가 발생했습니다. Android 프레임워크 준비 상태가 다시 불안정해 새 경로 설정 성공으로 기록하지 않습니다. 이전 603초는 11월 8일 증거이며 새 세션 결과가 아닙니다. 기기 안정화 후 재검증해야 합니다. commit/push/PR/merge 없음.

## 2026-11-08 짧은 경로 추가 비교·로그 보존 수정

- 사용자의 직접 진행 요청 후 현재 내비에 표시된 10분 경로를 추가 비교 대상으로 사용했습니다. 초기 화면은 기기 04:58/도착 05:08 AM/이동 10분, 속도 0 km/h였습니다. 새 목적지를 생성했다고 기록하지 않으며 이미 열려 있던 공개 테스트 경로를 재조회했습니다. Android 패키지 정보로 실제 내비 Activity를 확인하고 실행했습니다. 계정·권한·기기 시각은 변경하지 않았습니다.
- 같은 probe 세션에서 목적지 재조회 VALID_FIELDS(22:08:10 KST), Trip 요청(22:08:28.538) 후 **rawEtaSeconds=603**을 받았습니다(22:08:29.182). 이후 내비 직접 화면은 05:09/이동 11분/도착 05:20 AM/속도 0 km/h였습니다. 603초는 수치상 10분 3초로 앞선 25분대 원시값 1541/1538보다 작으며 이동 시간 계열 가설에 부합하는 관측입니다. 시간 기준·분 단위 반올림 규칙·현재 경로와 TripInfo 응답 연관성을 확정하지 않습니다.
- 기기 응답 지연으로 화면 캡처 전후 시각은 05:09:27–05:10:39-0800이었습니다. SDK 관측보다 약 58–130초 뒤의 캡처 구간이며 동일 순간의 정확한 대조 성공으로 세지 않습니다. 원시값·관측 시각·UI 표시 시각을 각각 보존했습니다. SDK 코드를 수정하거나 서버 PLEOS 차단을 해제하지 않았습니다.
- **보존 한계와 수정:** logcat 순환 후 스냅샷 수집기가 같은 JSONL을 덮어써 최초 원시 로그 20건의 전체본이 남아 있지 않습니다. 첫 두 Trip 샘플과 당시 이벤트 요약은 `captures/veh01-20261108-summary.json`에 남아 있습니다. 새 스냅샷 11건은 `captures/veh01-20261108-short-route-snapshot.jsonl`, 짧은 경로 메타데이터는 `captures/veh01-short-route-observation.json`으로 별도 보존했습니다. 수집기는 이후 중복 제거·추가 기록으로 바꿨으며 이미 logcat에서 사라진 원본을 복구했다고 보고하지 않습니다. 장시간 관측은 실시간 수집이 추가로 필요합니다. 아래 “최종 로그 20건 보존”은 당시 수집 시점 기록이며 현재 보존 상태는 이 항목이 우선합니다.
- 위치·주소·POI·이름은 공유 로그/문서에 추가하지 않았습니다. 내비 화면 원본은 로컬 Git 제외 work/에만 있고 공개 산출물이 아닙니다. 이번 단계는 기존 APK 실행·관측·문서 갱신이며 빌드/단위 테스트를 새로 실행한 결과가 아닙니다. #1의 시간 기준·도착/재탐색·자정·실제 거부/미지원/단절·늦은 유효 응답 검증은 남아 있습니다.

## 2026-11-08 Pleos probe 설치·실행·최초 SDK 수신

- 사용자가 Pleos 홈 화면과 공개 테스트 목적지 길안내 시작을 확인했습니다. 연결 대상은 emulator-5554, Android 14/API 34/Pleos Connect v2.1.0입니다. 기존 debug APK(SHA-256 `bf6f2870b29978ea173f186e62a9f4ee33a87ca482dda1e5aff53d15fe3c8e9e`)를 `adb install -r`로 설치해 Success, `am start -W`의 COLD/Status ok와 probe STARTED 로그를 확인했습니다. 앱 복귀는 HOT이며 같은 probeSessionId를 유지했습니다.
- **실제 SDK 수신:** NAVI_ROUTE granted=true(21:28:43 KST), NaviHelper 3.0.5 initialized(21:28:45), 목적지 콜백 MISSING_OR_INVALID. 길안내 시작 후 명시적 재조회에서 VALID_FIELDS와 TripInfo 버튼 활성화를 확인했습니다(21:33:25). 초기 콜백이 유효하지 않았다는 사실만 확인했으며 구체적인 누락 필드나 원인은 단정하지 않습니다.
- **백그라운드 관측:** onStop에 해당하는 STOPPED 21:30:32 이후 `routeStarted` 21:31:19가 foregroundAtProcessing=false로 도착했고, 복귀 STARTED 21:32:39를 확인했습니다. 이 한 시나리오가 장시간/프로세스 종료 후 백그라운드 수신을 보장하지 않습니다. 이 구간에서 목적지 변경/탐색 완료 콜백을 자동 갱신 근거로 확보하지 못해 명시적으로 재조회했습니다.
- **최초 TripInfo:** 21:34:06.930 KST 요청, 21:34:07.530 수신, `rawEtaSeconds=1541`, POSITIVE. ETA 시간 기준·응답 연관성은 UNVERIFIED이고 서버 절대 시각으로 변환하지 않았습니다. 아래에서 두 번째 샘플·사용자 UI 보고·취소를 추가 확인했으나, 동시 UI 대조·다른 길이의 경로·자정 경계·도착·거부/단절/늦은 유효 응답은 추가 검증 대상입니다. #1은 아직 미완료입니다.
- 개인정보 없는 필드만 추출한 로컬 증거는 Git 제외 `captures/veh01-20261108-probe.jsonl`입니다. 이름/주소/좌표/POI/다른 앱 로그를 포함하지 않습니다. logcat 접두 시각은 기기 시간대에 의존하므로 기록은 JSON observedAt(UTC)과 monotonic elapsedRealtimeMs를 기준으로 합니다. 코드 변경 없이 APK 설치·실행과 실제 관측을 수행했으며 이번 단계에서 단위 테스트를 새로 재실행한 결과는 아닙니다. 아래 “APK/SDK 수신 미검증”은 이전 단계 이력입니다.
- **비교·두 번째 샘플:** 사용자 보고는 기기 화면 04:37, 이동 25분, 도착 05:02 AM입니다. ADB로 `persist.sys.timezone=America/Los_Angeles`와 04:39:22-0800을 확인했고 시각 설정은 바꾸지 않았습니다. 두 번째 수신은 21:41:00.431 KST에 rawEtaSeconds=1538, 첫 응답에서 412.901초 후였습니다. UI는 동일 순간의 직접 관측이 아니며 경과 412.901초에 원시값은 3초 감소했으므로 단순 카운트다운/시간 기준을 확정하지 않습니다. 명시적 수동 요청/응답 2회이며 자동 갱신 주기의 증거가 아닙니다.
- **취소 기기 확인:** 사용자가 길안내 종료 후 21:42:45.548 KST에 foregroundAtProcessing=false인 routeCancelled를 받았고 목적지를 무효화했습니다. 이어 MISSING_OR_INVALID 목적지 콜백을 받았으며, 21:43:38 HOT 복귀 후에도 TripInfo 버튼 disabled를 확인했습니다. 취소 후 새 Trip 요청은 하지 않았습니다. 최종 비식별 로그 20건과 `captures/veh01-20261108-summary.json`을 보존했습니다. ETA 시간 기준·다른 경로·자정·변경 중 늦은 응답·도착·권한 거부·미지원·단절은 아직 미검증이며 #1을 닫지 않습니다.

## 2026-11-08 D드라이브 환경 설치·Pleos 부팅

- 사용자의 직접 설치 요청과 Pleos/Google SDK 약관 각각의 명시 동의를 받아 JDK 21을 `D:\Java\temurin-21.0.13+9`, SDK/Pleos 이미지/AVD를 `D:\Android`에 준비했습니다. 통계 전송 선택은 사용자가 직접 처리했습니다. [설치 기록·실행 파일](ANDROID_SETUP.md#2026-11-08-windows-d드라이브-설치)을 참고합니다.
- 공식 Pleos ZIP 4,087,751,964 bytes/SHA-1 대조 후 설치했고, Studio에서 Pleos 하드웨어 프로필의 AVD를 생성했습니다. API 34, Cold boot, RAM 4GB, 4 core, host GPU입니다. 이미지와 AVD 실제 경로가 D드라이브임을 config.ini에서 확인했습니다. 배포 XML과 ZIP 원본의 package path/revision 차이는 위 설치 기록에 남겼습니다.
- 기존 SDK 구성은 보존하고 D드라이브 복사본을 사용합니다. Studio 초기 설정에서 Emulator 37.2.13/Platform-Tools 37.0.1을 설치했습니다. 새 Studio 캐시/로그·실행 임시폴더와 AVD 저장소는 D드라이브로 설정했습니다. 프로젝트 로컬 SDK/JDK 파일은 Git 제외이며 공통 Gradle/API/서버 소스 변경은 없습니다.
- **실제 기기 부팅 확인:** `adb devices -l`의 emulator-5554=device, `sys.boot_completed=1`, Android 14/API 34, `RELEASE.release_connect_v2.1.0 test-keys`, 제조사 42dot. 재실행 부팅 로그는 약 40초입니다. 화면 캡처 도구는 반복 시간 초과여서 그래픽 홈 화면을 시각 확인한 결과로 세지 않습니다. 부팅 확인 후 emulator를 종료했고 D드라이브 실행 파일을 준비했습니다.
- APK 설치·probe 앱 실행·목적지/TripInfo·ETA 시간 기준·백그라운드·실제 권한/단절 시나리오는 여전히 미검증이며 #1은 미완료입니다. 첫 실행의 ADB 경로/unauthorized 상태와 재실행 후 device/부팅 확인을 구분합니다. ADB 키 복사·보안 승인 우회·Windows 보안 설정 변경은 없습니다. 아래 “Pleos AVD 없음”은 설치 전 이력입니다.
- D드라이브 JDK/SDK에서 최종 `gradlew.bat --offline -PincludeAndroid=true verifyAndroid` 성공(28초, 54개 태스크 중 5개 실행/49개 UP-TO-DATE). Lint 오류/경고 0, APK와 기존 테스트 6개 성공 산출물을 확인했으며 테스트 태스크 자체는 UP-TO-DATE입니다. 최초 제한 환경의 daemon 접속 실패 후 승인된 실행으로 재검증했습니다. 로그: `work/veh01/verify-d-drive-approved.log`. 재실행 부팅 로그: `work/veh01/pleos-emulator-final.out.log`. native emulator 원 로그에는 호스트 식별자 등이 있어 그대로 공개 공유하지 않습니다.

## 2026-11-08 VEH-01 Windows 작업

- 실제 GitHub #1 본문을 확인했습니다. 깨끗한 main `9518b6a`와 fetch한 origin/main이 동일했고 `feat/1-navi-probe`를 생성했습니다. commit/push/PR/merge는 실행하지 않았습니다.
- [VEH-01 검증 기록](VEH01_VALIDATION.md)에 SDK 계약·환경·완료 조건·팀 인계·기기 시나리오를 정리했습니다. NaviHelper 3.0.5 AAR/javap, navi-data 3.0.4 sources와 실제 Gradle 의존성을 대조했습니다. eta는 int/초, 시간 기준과 응답 연관성은 미확인입니다. deprecated `onRouteEnded`는 여정 종료로 해석하지 않고 `onRoutingCompleted`를 사용합니다.
- 콜백 진입 시각 채집, main thread 상태 처리, 목적지 필드 검증, 변경/탐색/도착/취소 시 무효화, 종료 후 콜백 방어, 권한/불명확 실패 로그, 명시적 목적지 재조회와 상태 단위 테스트를 추가했습니다. SDK 식별자가 없어 새 조회 뒤 구응답을 완전히 식별할 수 없으며 모든 응답의 `responseAssociation=UNVERIFIED`를 유지합니다. Instant 변환·서버 전송·PLEOS 허용은 추가하지 않았습니다.
- 기본 Java 17 대신 Git 제외 작업 폴더에 Temurin 21.0.13+9를 체크섬 대조 후 확보해 실행 셸에만 적용했습니다. SDK Platform 35/Build Tools 35.0.0/Emulator 실행 파일은 발견했습니다. 현재 사용자 AVD/연결 기기는 없고 SDK 내 Pleos 이미지도 발견하지 못했습니다. Studio Sync, APK 설치/실행, 실제 callback·권한 거부·미지원·단절·백그라운드·ETA 시간 기준은 **미검증**입니다.
- `gradlew.bat -PincludeAndroid=false format` 성공. 최종 `gradlew.bat -PincludeAndroid=true verifyAndroid` 성공: **Android 단위 테스트 6개, 실패/오류/skip 0**, Lint 오류/경고 0, debug APK 생성. 최종 54개 태스크 중 14개 실행/40개 UP-TO-DATE. 이전 Mac의 NO-SOURCE 이력과 구분합니다.
- `gradlew.bat -PincludeAndroid=true verifyAll`은 **실패**: 서버 200개 중 199개 통과/1개 실패, 실패는 기존 `PlacePreviewDemoTest.privateKeyFilesMustBeOwnerOnlyAndCannotBeSymlinkedOrEchoTheirContents`의 `Files.setPosixFilePermissions` Windows 미지원(`UnsupportedOperationException`, 218행)입니다. Android 검증은 위 별도 실행으로 완료했으며 다른 담당자 서버 코드는 수정하지 않았습니다.
- `smoke-test.py --check-contract`는 **실패**: UTF-8/작업 폴더 설정 후 MOCK HTTP 흐름과 교환 스키마 대조는 PASS였으나 재시작 후 예약 GET이 HTTP 404였습니다. Windows 프로세스 종료/DB 영속화 원인은 미확정이며 #4 확인이 필요합니다. 재시작 영속화/LIVE 차단 단계까지 통과한 결과로 세지 않습니다. 앞선 실패는 sandbox 임시폴더 권한과 기본 cp949 JSON 디코딩이었고 셸 설정으로 재실행했습니다.
- 로컬 증거는 Git 제외 `work/veh01/verify-all.log`, `verify-android.log`(의존성 포함), `verify-android-final.log`, `smoke-contract-final.log` 및 Gradle XML/HTML 보고서입니다. 기존 Mac 통합 검증 기록은 아래에 보존합니다. **#1은 기기 증거 부족으로 미완료**입니다. 실제 장소/전화/예약·유료 API 실행은 없습니다.
- 별도 `check-contract.py`는 OpenAPI 3.1 문서/검색 예제 PASS, `check-conventions.py --branch feat/1-navi-probe`와 `git diff --check`도 PASS입니다. 최종 APK SHA-256은 `bf6f2870b29978ea173f186e62a9f4ee33a87ca482dda1e5aff53d15fe3c8e9e`입니다. debug APK 생성은 기기 실행·release 검증이 아닙니다.

## 2026-11-10 #35/#38 통합과 #42 CI·문서 이력 복구

- 사용자 승인으로 #35는4d65c01, #38은9ea227d에 squash merge됐고 #34/#37은 독립 검증/코드 준비 범위로 종료됐습니다. 실제 공급자 채택·기기/ETA·전화/유료 실행 인수는 별도입니다.
- #42에 최신 main과 REC-08 Gradle cache 산출물 수정을 반영했습니다. 문서 충돌 정리 중 누락된 PLAN/STATUS/HANDOFF 이력은 원본4d65c01/a22745a/6e48416과3-way 재대조해 복구했습니다. 세 원본의 모든 비어 있지 않은 행 보존을 확인했습니다. 전화 계획과 문서 복구만 변경하며 런타임 구현을 추가하지 않습니다.
- 통합 기반 Java313개·JAR 인증/MOCK/UNKNOWN·OpenAPI/H2 재시작/LIVE 차단을 확인했습니다. REC-08 가상 파일 제거 후 FROM-CACHE 동일 hash 복원·추천 계약2개를 재현했습니다. 실제 외부 장소/GPT/전화/유료 호출0회입니다. 아래 실패/미병합 기록은 당시 이력이며 최신 CI는 해당 PR head를 따릅니다.


## 2026-11-10 #35 병합 이후 #38 통합 검증

- 사용자 승인으로 PR #35를4d65c01에 squash merge했고 #34는 종료됐습니다. #38은 이 main을 공유 이력 보존으로 반영하며 PLAN/STATUS/HANDOFF의 충돌에서 양쪽 기록을 보존했습니다. 품질 도구40개 검사와 GPT/웹 상세정보 코드 준비 범위를 함께 유지합니다.
- 이 기록은 최신 main 반영 단계이며 #16/#26 기기·#11 데이터 인수나 실제 GPT/웹 수집/전화/유료 호출을 완료로 세지 않습니다. #38은 코드 준비/가상 검증 범위로 통합하며 실제 실행은 별도 승인 후입니다. 아래 #35 미병합/#34 OPEN 표기는 당시 이력입니다.
- 최신 통합본 format/verify·Java313개/실패·오류·skip0, 가상 품질40개, 패키징 JAR 인증/MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. 원격 CI는 이 통합 head의 결과를 별도 확인합니다.

## 2026-11-09 전화예약 에이전트 개발 계획

- [전화예약 에이전트 계획](PHONE_RESERVATION_AGENT_PLAN.md)에 Telkit·LiveKit 기반 예약 발신, 회신 식별, 변경 승인·식당 재확인, UNKNOWN·중복 복구와 P01~P11의 일정·인수 기준을 정리했습니다. 15영업일은 역할별 병행 개발을 가정한 예상치입니다.
- 사용자 요청으로 프로필·여정별 동승자 조건·예약 당시 조건 사본을 분리하고, 재사용 저장 선택·예약 승인·학습 동의 구분, 최소 정보 전달, 수정·삭제·만료 기준을 추가했습니다. P01/P05/P06/P08과 인수 시나리오 9개를 보완했으며 새 DB/API 구현은 아닙니다.
- 계획의 함수·API·상태·저장 모델은 팀 검토용 제안이며 현행 계약·소스·DB·MOCK/LIVE 동작을 변경하지 않았습니다. #3/#4/#26의 구현·인수 상태와 담당을 유지합니다.
- 이번 변경은 문서 4개·로컬 링크 65개·작업 ID 11개·형식·게시 정보·명명 규칙·diff 정적 검사를 통과했습니다. 로컬 Gradle·기기 테스트를 새로 실행한 결과가 아니며 실제 전화·계정 생성·번호 구매·유료 API 실행은 없습니다.
## 2026-11-09 REC-11 #37 웹 상세정보·GPT 코드 준비

- 사용자 선택에 따라 [GPT 출처 검색·웹 상세정보 모듈](REC11_PLACE_DETAILS.md)을 최신 mainabd9aa4 기반 `feat/37-place-detail-enrichment`에 분리했습니다. GPT 인용 URL 후보 → 별도 이용 조건/host 검토 → 이름+주소 일치 → 구조화 필드/출처·관측/표시 기한·충돌/UNKNOWN → 후보 최대3개의 남은 질문을 제공합니다. #19/#26 코드나 공개 v1/미리보기 계약은 변경하지 않았습니다.
- Responses/web_search의 일회성 Permit·명시적 모델·도구1회/출력1024토큰·최대30초/1MiB·재시도/redirect 차단을 구현했습니다. Permit은 메모리상의 제한이며 운영 인증/전역 비용 원장이 아닙니다. 실제 실행 전 데이터·모델·횟수·비용 상한 승인이 별도로 필요합니다. HTTP/페이지 adapter는 Spring 미등록·제품 미연결입니다.
- Java313개(기반268+신규45), 실패/오류/skip0. 가상 JSON-LD·GPT 응답·loopback HTTP로 지점/주소·출처 충돌·시각·취소/만료·중복 실행·응답 한도와 후보 결합을 검증했습니다. `:server:placeDetails`의 전체 예제도 MOCK입니다. 패키징 JAR 인증·기존 MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. jsoup1.24.1과 lockfile을 추가했습니다.
- 웹 일반 보고는 GENERAL이며 도착 시각 빈자리·대기·예약 가능이나 권한으로 전환하지 않습니다. #11의 KAKAO/미검토 출처 GPT 전송을 차단하며 검토된 공공 출처의 제품 채택도 아직 아닙니다. 일반 문장/PDF/JS-only/다른 업종과 실제 TLS/모델/청구/매장 페이지·Android 기기는 미검증입니다. 이번 실제 GPT·웹 수집·Kakao·전화·유료 호출0회입니다. 원격 CI 결과는 PR에서 따로 확인합니다.
- #16 별도 브랜치는 mainabd9aa4와의 충돌을 공유 이력 보존으로 해결하고 #26 담당의 [정적 검토](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008)를 반영해 head5526f92로 push했습니다. Java329·Python7/15·가상HTTP137·토큰HTTP16·CI3개 통과. 계약 추가 차단 필드는 없었고 완료 preview 재사용 금지·app token의 MOCK_OPERATOR 미보유를 확인했습니다. 실제 #26 구현/공동 기기·#1 ETA·#11 인수는 남아 #16 OPEN/#19 Draft입니다. 이 #37 모듈은 이후 필드 합의 후 별도 연결합니다.
## 2026-11-09 REC-10 #34 공식 상태·HTTPS 실제 입력 확인

- [REC-10](REC10_SEOUL_PILOT_VALIDATION.md)의 최신 결과가 아래 초기 진단보다 우선합니다. 공공데이터 제공 표준20차의127)일반음식점 원문에서 행정01–04·상세01/02 코드/명칭·폐업일 의미를 확인했습니다. 기존 가정 표기를 공식 사전 기반 판정으로 바꾸고 코드/명칭·상태 충돌을 구분했습니다. 범용 표준EPSG2097과 데이터셋EPSG5174의 차이를 기록하고 데이터셋별5174를 유지합니다.
- 키 없는 공식 웹 Sheet HTTPS 경로를 확인해 영업상태01 첫 페이지를 읽었습니다. 최초1회는 레거시 형식 파싱 실패, 사용자 승인 추가1회는1000행 집계 성공입니다. 총2회·자동 재시도/추가 페이지0·0원이며 원문은 메모리에서만 재분석하고 폐기했습니다. 키 있는OpenAPI 인증 성공이나 안정적인 운영 API 채택은 아닙니다.
- [실제 집계](quality/rec10-public-sheet-2026-11-09.json): 정상1000행, 좌표누락82·유효918, 전화누락719(71.9%)·기본형식273·placeholder1·미인식7, 도로명주소누락3, ID중복/충돌0입니다. 교차 단계는1000→918→반경1000m433→전화형식116→상호/도로명주소115행입니다. 1000–1030m8행은 제외합니다. 수신1000/보고된필터전체3959의 제한 범위이며 서로 다른 실제 매장115곳이나 예약 가능을 뜻하지 않습니다. pageSize/batch/version/TZ는UNKNOWN입니다.
- 식별자 키/끝 쉼표만 정규화하는 비실행 파서와 명시적 로컬 sheet-json 입력을 추가했습니다. 가상40개(기존29+신규11)·MOCK CLI·Gradle format/verify 통과(Java UP-TO-DATE). 실제 행 fixture·새 의존성·제품 API/DB/Android 변경 없음, CI는 가상 검사만 수행합니다.
- #34 범위는 제한된 독립 검증이며 PR 병합 전OPEN입니다. 운영 경로/팀 계정·원본 최신성/CRS 차이·저장/전달·공급자 채택은 기존 #11에 남깁니다. 생산 후보 수null·추천/채택/예약/발신false와 제품MOCK/LIVE차단 유지. 키 전송·전화/Kakao/AI/유료 호출0회이며 아래 초기 실제 조회0회/상태 사전 미확보 표기는 당시 이력입니다.

## 2026-11-09 REC-10 #34 인증키와 교차 검사

- 사용자 진행 지시로 필수 CI3개·최신 main·충돌 상태를 확인하고 #33을 `abd9aa4`로 squash merge했습니다. #32 CLOSED, 다른 Draft/담당 이슈는 유지합니다. 이 main에서 `feat/34-seoul-pilot-validation`을 만들었습니다.
- [REC-10](REC10_SEOUL_PILOT_VALIDATION.md)의 개인 개발용 인증키 발급/정상 UI 상태를 확인했고 로컬 Git 제외0700/0600으로 보관합니다. 팀 공유·실제 API 인증 성공·운영 키 승인은 아닙니다. 이전 REC-09의 키 미발급 기록은 당시 이력입니다.
- 품질 도구에 선택적 `--pilot-screening`을 추가했습니다. 동일 행 교집합·모든 중복 행 제외·상세 상태/폐업일·1000m와1030m 경계·연락처·기본 정보를 집계합니다. **상태 튜플은 코드 사전 확보 전 실험 가정**이고 생산 후보 수는 null, 추천/채택/예약/발신 권한은 false입니다.
- Python29개(기존15+신규14)·MOCK CLI 통과. 원격 CI의 기존 REC-08 계약 파일 캐시 누락을 발견해 Gradle test 산출물로 선언했습니다. 수정 후 format/verify·Java268개 재실행(실패/오류/skip0), 생성 파일 제거 후 FROM-CACHE 복원·계약2개 통과를 확인했습니다. CI에 가상 옵션 실행만 추가했습니다. 실제 행/키 fixture·새 의존성·서버 런타임/Android/API/DB 변경은 없습니다. Gradle 테스트 산출물 선언만 보강했습니다. 기기/실데이터 검증이 아닙니다.
- 키 없는 TLS443 연결1회는 timeout, 애플리케이션 요청0회·키 전송0회입니다. 다른 서비스의 공식 HTTPS 미제공 답변은 확인했으나 OA-18655 전체 지원 결론으로 확대하지 않습니다. 상태 사전·batch/TZ·안전한 공급 경로/실제 지역 품질은 미검증이며 #34 OPEN, #11 공급자 채택과 #3/#4/#26 인수는 별도입니다. 전화·Kakao·AI·유료 호출0회, 제품 MOCK/LIVE 차단 유지.

## 2026-11-09 REC-09 #32 공공데이터 독립 검증

- [REC-09](REC09_SEOUL_DATA_VALIDATION.md)에 OA-18655의 공식 명세39필드·공개 sample1–5 제한·EPSG:5174·라이선스를 확인했습니다. 개인 인증키 발급/로그인/약관·결제·IP 설정 변경은 없습니다.
- scripts/seoul-place-quality.py는 기본 네트워크 없는 MOCK 또는 허용된 로컬 XML의 품질만 집계합니다. pyproj3.7.2·PROJ 네트워크OFF, axis order/ballpark·입력 한도·개인정보 비노출을 검사합니다. Java/Android 의존성·공개 API·DB·예약·LIVE 경계는 변경하지 않았습니다.
- 가상 데이터 테스트15개 통과. 투영 원점·파생 시범점·축 교환/불량 좌표, 누락/중복 충돌, 정상0건/실패·XML 경계, 행 시각과 snapshot 최신성 분리, 출력 비노출을 검증했습니다. 원본 행·연락처를 테스트 fixture에 넣지 않았습니다.
- 실제 공개 sample을1회/5건/0원으로 읽고 자동 재시도·추가 페이지 없이 [집계만](quality/rec09-public-sample-2026-11-09.json) 보존했습니다. 전화 기본형식5/5, 좌표 누락2/5·변환가능3/5, 도로명주소 누락3/5, 행정상 영업/정상1/5·폐업4/5, ID결측/중복/충돌0/5입니다. 첫5건은 지역/현행 영업점 대표 표본이 아니며 실제 번호 유효/발신 동의는 미검증입니다. snapshot 기준일은 UNKNOWN이고 조회 시각/오래된 행 수정일로 대체하지 않습니다.
- CI에는 품질 의존성 설치·가상 테스트15개·MOCK 실행만 연결했습니다. CI에서 실제 표본을 재조회하지 않습니다. #32 범위는 독립 도구/명세/표본 증거이며 #11/#16–18·다른 담당의 제품/기기 인수를 완료로 세지 않습니다. 이번 실제 장소 API는 공개 sample1회이며 Kakao·전화·AI·유료 호출은0회입니다.
- 문서5개·로컬 링크78개와 명세/집계JSON·명명/diff 정적 검사를 통과했습니다. Java/Android 코드를 변경하지 않아 로컬 서버/기기 테스트를 새로 실행하지 않았고, 원격 필수 CI는 이 PR의 실제 결과로 구분합니다.

## 2026-11-09 SDK 대체·단계별 연동 계획 (#30)

- 사용자 결정에 따라 [위치·음성 SDK 대체 계획](SDK_FALLBACK_PLAN.md)을 작성했습니다. 표준 API 우선·기능별 adapter·명시적 개발 MOCK·Pleos 구현체 추가 조건과 SDK-01–10 인수 시나리오를 정의했습니다. 새 런타임 구현/테스트 결과가 아닙니다.
- ArriveOn 프로젝트 생성 후 콘솔에서 NaviHelper·Vehicle State 사용 가능, FusedLocation·STT·TTS 준비 중, 신청 내역 없음을 확인했습니다. 공식 신청/표준 API 문서를 대조했으며 권한 신청·문의 발송은 수행하지 않았습니다. 프로젝트 카테고리는 미설정이며 팀원 초대는 계정 이메일 확보 후 별도 진행해야 합니다. 키·시크릿은 공개 문서에 저장하지 않았습니다.
- 사용자 승인에 따라 추천 PR #29를 `c482baa`로 main에 squash merge했고 #28은 종료됐습니다. #31에 이 main을 반영하면서 상태/인계 충돌을 양쪽 내용 보존으로 해소했습니다. #31의 main 대비 차이는 문서뿐이며 다른 Draft PR은 병합하지 않습니다. 아래 BE-02 “PR 제출·미병합” 제목과 이전 main 값은 당시 이력입니다.
- 차량 #1·전화 #3·UX #25/#26의 담당과 미완료 상태를 유지합니다. 사용자 음성 런타임은 별도 담당/이슈 배정이 필요합니다. 표준 위치·STT·TTS의 Pleos 기기 실행, 외부 전화/예약 연동은 미검증입니다. 이번 작업의 실제 장소 검색·전화·유료 API 실행은 0회입니다.
- 최신 main 반영 후 문서 7개의 로컬 링크 79개·SDK-01–10의 고유성·코드 블록/개행·신규 계획의 개인정보 패턴·브랜치/제목 규칙·diff 공백 검사를 통과했습니다. 문서만 변경했으므로 Gradle/기기 테스트를 새로 실행하지 않았으며 인수 시나리오 통과로 세지 않습니다.

**REC-08 #28 (2026-11-09):** [후보 추천·질문 계획](REC08_RECOMMENDATION_PLAN.md)의 순수 평가기·MOCK 실행 예제·별도 JSON Schema를 추가했습니다. 최대3개 후보에 추천 근거·누락 정보·UNKNOWN 질문을 반환하고 bookable/발신 승인은 false입니다. Java268개(기반228+신규40)·계약3문서·부적합변형8개·JAR 인증/MOCK/H2 재시작/LIVE 차단 검증을 통과했습니다. 당시 검증 기준은 인증 #21이 병합된 main 1b37bc2입니다. #19의 main 충돌은 2026-11-09 별도 작업에서 해소했고 #16/#26 제품 화면 연결은 별도 인수입니다. 추가 실제 검색/전화/AI/비용 작업0회이며 #11/#16–18의 완료를 의미하지 않습니다. #29는 사용자 승인 후 c482baa로 main에 병합됐고 #28은 종료됐습니다.

## BE-02 #20 작업 브랜치 — 2026-11-08 (PR 제출·미병합)

- `/api/v1`에 앱 설치별 익명 주체·토큰 발급/검증/갱신/폐기를 구현했다. 사용자는 로그인하지 않는다. 갱신 후 주체는 유지하고, 같은 갱신 토큰+요청 키는 120초 내 동일 응답을 재생한다. 토큰 원문을 DB에 저장하지 않는다.
- V3 소유권 격리를 유지하고 V4 인증 저장 구조를 추가했다. 기존 데이터는 미확인 소유권으로 보존하며 자동 배정하지 않는다. 보호 API는 실제 발급한 접근 토큰으로 호출할 수 있다. 일반 토큰은 MOCK_OPERATOR 권한이 없다.
- #16은 동일 Principal·필터 순서로 연결한다. 소스는 작업 브랜치에 합치지 않고 임시 통합 검증한다. 계약 1.1.0, URL v1을 유지한다. 제품 Android 토큰 저장·자동 갱신은 별도 후속 작업이다.
- 사용자 승인에 따라 기능별 6개 커밋으로 분리했다. PR 검증 후에도 사용자 지시 없이 병합하지 않는다. #20 범위는 토큰 인증까지 확대했고 #4의 UNKNOWN 해결·승인 증빙·outbox·운영 DB는 후속 범위다.
- 발급 제한은 단일 서버 메모리 기준이다. 운영 프록시/분산 제한·만료 토큰 정리·앱의 안전 저장·복구 UX는 후속 작업이다. 실전화·실제 장소·유료 호출은 없다.

- 최종 검증: 서버 227개, #16 `6f286f8` 전체 통합본 271개 테스트 실행·실패/오류/skip 0. 실제 발급 토큰의 HTTP·갱신 응답 재시도·폐기·H2 재시작과 기존 예약/UNKNOWN 스모크 통과. 통합본의 미리보기 계약 96건 및 실제 토큰 HTTP 16건(생성/검색/취소/종료·타인 접근·갱신 후 소유권·폐기) 통과. Android·원격 CI·실전화는 미검증이다.
- #16 통합 시 문서 2개와 스모크 스크립트 충돌을 양쪽 내용 보존으로 해결하고, PlacePreviewDisabledTest의 계약 기대값을 1.1.0으로 맞췄다. 인증 필터가 조기 거절할 때 #16 보안 헤더가 누락되는 문제를 발견해 원본 토큰 필터에서 수정하고 회귀 검사했다. 실제 #16 브랜치는 변경하지 않았다.

아래는 기존 main 및 이전 작업의 검증 이력이다.


**통합 범위와 검증 기준 (2026-11-07):** [PR #14](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/14)를 `8cbe890`으로 squash merge했고 #12는 CLOSED입니다. #15는 이 main을 반영한 상태에서 통합 검증했습니다. #13의 종료 범위는 독립 미저장 시연·최초 실제 검색 검증입니다. 미완료 제품 인증/API/화면·navigation 계약은 #16, 자동 정리·재시작/다중 서버 실행 관리는 #17, 지역 품질·실제 IP 제한 검증은 #18로 이관했고 #11 정책 검토는 OPEN입니다. [통합 범위와 후속 조건](REC_FOLLOWUPS.md)을 우선합니다.

**통합 검증:** `./gradlew -PincludeAndroid=true verifyAll` 성공. 서버 200개 실행·실패/오류/skip 0, Android Lint/APK 검사 통과(일부 UP-TO-DATE, 단위 테스트 NO-SOURCE). `work/contract-venv/bin/python scripts/smoke-test.py --check-contract`로 JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단을 확인했습니다. 기기 실행과 실제 예약은 검증하지 않았습니다.

**실제 실행 증거:** [독립 시연](REC04_LIVE_SEARCH.md)의 Kakao 1회 성공·3곳 일시 표시·쿼터 1 / 100,000, 비용 0원·전화/예약/AI 0회입니다. 이번 병합 중 추가 실제 호출은 없으며 제품 MOCK/LIVE 차단·응답 미저장을 유지합니다. 앞선 브랜치별 188개/156개 기록과 통합 결과를 구분합니다. 아래 OPEN/Draft·병합 대기 표현은 당시 이력이며 최신 병합 상태는 GitHub가 기준입니다.

갱신: **2026-11-07 KST**. 기준 환경: macOS arm64, Corretto OpenJDK 21.0.6, Gradle 8.11.1, Spring Boot 3.5.16, Python 3. 본 문서는 실제 실행 결과와 미검증을 구분합니다.

REC-01 서버 재검증: **2026-11-07 KST**. Android·SDK 기록은 2026-11-04입니다. ERD·기능 명세의 문서/정적 검증을 제품 재실행이나 실제 연동 성공으로 세지 않습니다.

## 공유한 설계·협업 문서

- [문서 목차](README.md), [이슈별 상세 작업](ISSUE_GUIDE.md), [기능 명세](FUNCTIONAL_SPEC.md)를 Git에서 리뷰할 수 있도록 정리했습니다. 문서 공유 당시 #1–5는 OPEN·담당자 미배정이었고, 이후 확인한 #2의 배정 상태는 아래 착수 기록을 따릅니다.
- [ERD](ERD.md)는 현재 H2 6개 테이블과 구분한 11개 테이블 제안입니다. ERDCloud의 107컬럼·15FK 대조 기록과 UNIQUE 5개 누락, 가져오기용 SQL을 공유하며 DB에는 적용하지 않았습니다.
- [다중 예약·차별화·지연 대응](DESIGN_PROPOSALS.md)은 미확정 제안입니다. 기존 활성 예약 제한과 자동 지연 감지의 미구현 상태를 유지합니다.
- 공유본 정적 대조 12항목 통과: 기능 17개·인수 시나리오 20개·API 10개·오류 26개·ERD 키/관계·로컬 링크 등을 확인했습니다. `./gradlew -PincludeAndroid=false verify`도 성공했으며 15개 태스크는 모두 UP-TO-DATE였습니다. 제품 테스트를 새로 실행한 결과로 세지 않습니다.

## 2026-11-07 REC-01 P1과 P2 검증 기록

- GitHub #2는 OPEN, 담당자 `tiemo0708`임을 확인했습니다. 사용자 선택은 성수·서울숲 음식점, Kakao Local 우선 검토입니다.
- [상세 계획](REC01_PLAN.md)에 변경할 코드, adapter 내부 계약, P0~P4 순서, 테스트 16개 시나리오와 #3·#4·#5 협업 경계를 작성했습니다. 반경 1km·수집 최대 15곳 등은 실험 초기안입니다.
- `PlaceSearchAdapter`·query/summary/성공/실패 타입, `MockPlaceSearchAdapter`·`MockCandidateFactory`, 오프라인 `KakaoPlaceMapper`를 구현했습니다. 공개 API·migration·의존성 변경은 없습니다.
- 추천 엔진에서 가상 데이터 생성을 제거하고 scope·ETA·값 타입·관측/만료 시각·모드를 검증합니다. 입력 후보 전체를 평가한 뒤 최대 3곳을 선택하며 결과가 적으면 그대로 반환합니다.
- 실제 Spring/H2/MockMvc에서 빈 결과·1·2·5개 입력, 저장 후 조회, 원 응답 snapshot 재전송과 중복 adapter 호출 방지를 검증했습니다. 기존 가상 ID·facts·TTL·예약/변경 시연도 유지됩니다.
- `./gradlew -PincludeAndroid=false format verify` 성공. 테스트는 **104개 실행, 실패/오류/skip 0**입니다. 기존 WorkflowTest 25개와 신규 79개를 재실행했습니다. 시작 전 verify는 15개 태스크 UP-TO-DATE였으며 이 결과와 구분합니다.
- `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 성공: 패키징 JAR·MOCK HTTP 흐름·OpenAPI·H2 재시작·LIVE 시작 차단. 로컬 증거는 Git 제외 `work/rec01-tests.json`, `work/rec01-smoke.log`에 있습니다.
- 현재 서비스는 네트워크 없는 MOCK adapter만 주입받습니다. 실제 HTTP·transport timeout·DB 잠금 밖 IO·사용 조건 검토·실데이터/모의 예약 표시는 후속 작업입니다. 실제 장소 API 조회·전화 발신·비용 발생 작업은 없습니다. Android 검증과 원격 CI는 이번에 새로 실행하지 않았습니다.

## 2026-11-07 REC-01 HTTP 경계와 정책 검토

- 공식 FAQ와 2026-08-20 지도/로컬 담당 답변에서 응답·가공 데이터의 별도 저장 제한을 확인했습니다. 근거와 H2 snapshot/commands 저장의 충돌, 팀 앱 생성 절차, #4·#5 검토안은 [실제 연동 검토](REC01_INTEGRATION.md)에 기록했습니다. 사용자는 팀 앱이 아직 없다고 확인했습니다.
- `KakaoPlaceSearchAdapter`를 추가해 127.0.0.1 가상 서버에서 목적지 3개·정상/빈 결과·오류·redirect·headers/body timeout·취소·수신 크기·UTF-8을 검증했습니다. Spring 미등록이며 제품 검색 서비스에 연결하지 않았습니다.
- 최종 `format verify` 성공. **130개 실행, 실패/오류/skip 0**, 6개 suite: 기존 업무 25개 + 장소/추천 79개 + HTTP 경계 26개. 로컬 기록은 Git 제외 `work/rec01-http-verify.log`, `work/rec01-http-tests.json`입니다.
- 최종 패키징 JAR에 대해 `smoke-test.py --check-contract`로 MOCK HTTP·OpenAPI·H2 재시작·LIVE 차단을 다시 확인했습니다. 로컬 기록은 `work/rec01-http-smoke.log`입니다.
- 공개 API·Model·migration·의존성 변경 없음. 실제 TLS/키/무료 쿼터·IP 제한·실데이터 품질·연결 장애의 과금 요청 수는 미검증입니다. JDK transport 내부 재전송 제어를 실제 실행 전에 검토합니다. Kakao 실제 요청·전화·유료 작업은 0회입니다.

## 2026-11-07 Kakao 개발자 앱 설정

- 사용자 개인 Kakao 계정으로 로그인 후, 앱 이름 ArriveOn·운영 단체 ArriveOn Lab·자동차/교통수단 카테고리로 앱을 생성했습니다. 운영정책 확인과 앱 생성은 사용자 명시 동의 후 실행했습니다.
- 첫 활성화 앱의 무료 쿼터 대상이 이후 변경 불가하다는 안내를 사용자에게 제시하고 동의 후 카카오맵을 ON으로 설정했습니다. 이 앱의 일간 무료 쿼터 제공 문구와 배지를 확인했습니다.
- 유료 API 화면에서 카카오맵·카카오톡 공유 모두 사용 안 함을 확인했습니다. 비즈월렛 연결·결제 설정은 하지 않았습니다. 일간 쿼터 화면에는 조회 기간 내 호출 없음이 표시되었습니다.
- 설정 확인 화면은 로컬 Git 제외 `work/kakao-setup-complete.png`, `work/kakao-paid-disabled.png`, 비밀 키 없는 설정 요약은 `work/kakao-setup-result.json`에 있습니다. 개인 계정 이메일·다른 앱 정보·앱 키는 공개 문서에 기록하지 않습니다.
- 이번 단계는 실제 계정 설정과 문서 갱신입니다. 제품 코드·API·DB 변경은 없고 로컬 Gradle 테스트는 다시 실행하지 않았습니다. 앞선 코드 검증은 130개 통과이며 PR #10의 서버·Android·naming CI도 통과했습니다.
- 실제 장소 API 호출·키 값 복사/저장·전화·비용 발생은 없습니다. 데이터 보관 허용 범위와 실제 IO/중복 실행·화면 계약은 여전히 후속 작업입니다.

## 2026-11-07 정책 문의 등록과 지도 역할 확인

- 사용자 승인 문안 그대로 [Kakao DevTalk 정책 문의](https://devtalk.kakao.com/t/local-api/152647)를 공개 등록하고 제목·본문 일치를 확인했습니다. 원문 목록의 실시간 사용/세션 보유, 전화 공급자 전달, 예약 이력 보관, 별도 계약 가능성을 문의했습니다. 현재 답변 대기이며 보관 허가를 받은 상태가 아닙니다.
- [INTEGRATIONS](INTEGRATIONS.md#내비게이션과-장소-검색의-역할)에 Pleos 내비게이션/목적지/ETA와 외부 식당 후보 데이터의 역할을 정리했습니다. 카카오맵 설정은 장소 REST API 준비이며 차량 지도 교체가 아닙니다. NaviHelper 3.0.5 공식 안내·배포 SDK 공개 메서드를 대조했습니다.
- 앱 설정 문서 커밋 f4a1001의 서버·Android·PR naming CI가 통과했습니다. 이번 추가 변경도 문서만 포함하며 제품 테스트를 로컬 재실행한 결과가 아닙니다. 실제 장소 요청·전화·유료 작업은 없습니다.

## 확인된 결과

| 항목 | 상태 | 근거/한계 |
| --- | --- | --- |
| Gradle 통일 | 빌드 확인 | 루트 Wrapper + Kotlin DSL, 서버 기본/Android 선택 포함. Maven 실행 파일·POM은 현재 빌드에서 제거 |
| 서버 컴파일·포맷·패키징 | 2026-11-07 통과 | `./gradlew -PincludeAndroid=false format verify` |
| 자동 테스트 | **130개 통과, 실패/오류/skip 0** | `server/build/test-results/test/TEST-*.xml` 6개 suite, 기존 WorkflowTest 25개 포함 |
| 실제 HTTP 모의 시나리오 | 통과 | 패키징 JAR에서 검색→문의→갱신→예약 수락→변경 UNKNOWN→재시도 차단 |
| 영속화/재시작 | 통과 | 임시 파일 H2, Flyway V1/V2, 서버 종료·재시작 후 확정/미해결 변경 유지 |
| OpenAPI 3.1 | 통과 | 문서/예제 및 시연 HTTP 요청·응답을 JSON Schema로 대조 |
| LIVE 차단 | 통과 | `--app.mode=LIVE` 시작 실패와 LIVE_DISABLED 확인; 발신 클라이언트 없음 |
| 협업 문서·CI | **원격 Verify 통과** | 공개 조직/저장소·이슈 #1–6 생성. 첫 main Verify 및 초기 설정 PR #7의 세 검사 성공; 설정/실행 링크는 GITHUB_SETUP |
| Android SDK | **설치·연결 확인** | 사용자 약관 동의 후 ~/Library/Android/sdk에 CLI 22.0 / Platform 35 rev 2 / Build-Tools 35.0.0 / Platform-Tools 37.0.1, local.properties·IDE 모듈 opt-in 연결 |
| Android probe | **debug APK 빌드 확인** | verifyAll 및 최종 verifyAndroid 성공. Java/DEX/APK 생성, apksigner debug v2 서명 검증; 기기 실행 미검증 |
| Android Lint | **오류 0 / 경고 0** | 문자열 리소스·dp, 앱 아이콘·백업 제외 규칙 반영. android/probe/build/reports/lint-results-debug.html |
| Android 단위 테스트 | 미작성·미실행 | testDebugUnitTest NO-SOURCE. 실행 테스트 통과로 세지 않음 |
| 코드 규칙·에이전트 환경 | 로컬 검증 | CODE_CONVENTIONS / 영역별 AGENTS / format·verify·verifyAndroid·verifyAll. Android 꺼진 verifyAndroid의 명시적 실패도 확인 |
| Pleos API/ETA | 문서·소스·바이트코드 확인 | eta int/초 확인. 시간 기준·이벤트 주기·백그라운드·실행 미검증 |
| SDK 정책·가용성 | 문서 확인·설계 반영 | 사용자 공유 문서와 연결 문서를 INTEGRATIONS에 정리. 기능별 지원 확인·대체 흐름·주행 UX 제한은 구현/기기 검증 전 |
| 실제 장소/AI/전화/예약 | 미구현·미실행 | 가상 후보3개와 모의 outcome만 구현 |

계약 검증 명령: 개발 의존성 설치 후 `python scripts/smoke-test.py --check-contract`. 이번 실행은 Git 제외 `work/contract-venv`에서 수행했습니다. 로그는 `work/gradle-verify.log`, `work/smoke-contract.log`, `work/android-build.log`에 있습니다. 팀 공유 기준은 이 문서와 재현 명령이며 로그 원본을 자동 업로드하지 않습니다.

SDK/컨벤션 보강 후 로그는 `work/conventions-verify.log`, `work/android-verify.log`, `work/android-verify-final.log`입니다. 이전 android-build.log의 SDK 부재는 해결했습니다. 서버 동작 코드는 이번 보강에서 변경하지 않았으며 verify의 테스트 태스크는 기존 25개 성공 결과를 UP-TO-DATE로 확인했습니다. GitHub 첫 main에서도 Server and contract·Android checks 성공을 확인했습니다.

최종 debug APK: `android/probe/build/outputs/apk/debug/probe-debug.apk`, SHA-256 `66a7b62196afbe4fa5a77525f92001b092425cf428d44e639a9e88e56477da75`. 개발용 서명·SDK_PROBE이며 release 산출물이 아닙니다. 이후 빌드 결과가 바뀌면 해시도 다시 기록합니다.

## 구현 범위

- 검색/조회/문의/예약/변경/모의 결과 API, 전역 DB mutex를 이용한 서비스 트랜잭션
- 요청 키·본문 해시, 이벤트 ID 중복 방지, 동일 tripId 활성 예약 중복 차단
- 명시적 매장 수락·시간·비용 일치 확인 후 확정, 그 외 UNKNOWN 보존
- 변경 진행/미확인 중 기존 confirmedTime 유지, pendingChangeCallId 보존, 10분 재요청 간격
- 추가 비용 별도 승인과 누적 상한, 필수 가족 조건 우선, 정보 만료, NOW/ARRIVAL 구분
- UNKNOWN 대조 전 재발신 차단, 원래 요청 시각과 변경 이력 보존

기존 업무 테스트 25개는 동시 같은 키/다른 키 예약, 동시 같은 이벤트, 중복·변조 이벤트, 실패 시 롤백, 수락 시간·금액 불일치, 예약/변경 UNKNOWN, 거절, 가족 필수 조건, 예약 불가/빈자리 구분, 만료·늦은 관측, 허용 변경·추가 비용·누적 비용·재통화 간격, 입력 검증, 동의/권한 분리 등을 다룹니다. 신규 장소/추천 테스트 79개를 더한 총 실행 수는 104개입니다.

Android Studio/SDK 설치·경로 연결·Pleos 이미지 준비는 [ANDROID_SETUP](ANDROID_SETUP.md)를 따릅니다. `python3 scripts/setup-android.py`는 설치 상태 점검, `--configure`는 설치 확인 후 Git 제외 로컬 경로와 IDE 모듈 설정만 작성합니다.

## 알려진 한계와 다음 순서

1. Android Studio·Pleos Emulator/Virtual Console 확보 후 기기 probe. 빌드 SDK와 debug APK는 준비됨. `./gradlew -PincludeAndroid=true verifyAndroid`로 재현하고 기기 실행은 별도로 확인.
2. 실제 ETA 시간 기준·콜백 주기·권한·백그라운드·목적지 변경을 확인한 뒤 변환 adapter와 테스트 추가. 지금 source=PLEOS는 서버에서 거부.
3. 제품 Android 화면, CarUxRestrictions, Gleo/STT/TTS 연동, release 서명·시연 영상은 미구현.
4. 성수·서울숲 시범 범위 구체화, Kakao 사용 조건 확인과 장소 adapter 구현, 전화 업체 선정. 계정·번호·허용 대상·예산 승인 전 실발신 없음.
5. 실제 통화 도입 전 사용자 인증/소유권·승인 증빙, webhook 서명, outbox/timeout 대조, UNKNOWN 수동 해결 추가. 현재 API는 loopback 단일 사용자용이며 공개 배포 대상이 아님.
6. 지속 ETA 지연 감지·자동 변경은 후속 범위. 현재는 명시적 모의 변경 명령만 지원. 학습 동의는 저장만 하며 학습 미구현.
7. 팀 경험 확인 후 운영 DB 결정. H2 전역 lock은 단일 서버 MVP 한계가 있음.

Git: **main**을 기본으로 [arriveon-lab/pleos-reservation-assistant](https://github.com/arriveon-lab/pleos-reservation-assistant)에 첫 커밋 `81f0b40`을 공개했습니다. 작업 브랜치는 실제 이슈 번호, 커밋/PR은 type(scope) 규칙입니다. 초기 설정은 `chore/6-github-bootstrap`의 PR #7에서 기록합니다. 조직 멤버 2FA를 필수로 설정했고, main은 사용자 요청에 따라 2026-11-07 필수 승인 0명으로 변경했습니다. 필수 CI 3개·최신 main 반영·대화 해결·관리자 포함 보호 규칙을 적용합니다. 설정과 원격 실행 근거는 [GITHUB_SETUP](GITHUB_SETUP.md)을 확인합니다.

이 Mac에는 저장소 로컬 commit-msg 훅과 메시지 템플릿을 활성화했습니다. 정상/잘못된 제목·브랜치·PR 이슈 번호 검사와 훅 실행을 확인했습니다. 전역 Git 설정은 바꾸지 않았습니다. Android 설정 점검은 설치된 SDK/JDK를 확인하고 Git 제외 local.properties와 gradle-local.properties를 작성했습니다. 개인 셸 설정이나 전역 Codex 설정은 변경하지 않았습니다.

실제 전화 발신 **0회**, 번호 구매 **0건**, 유료 API 실행 **0회**. SDK/빌드 도구/공식 문서 다운로드와 localhost 테스트만 수행했습니다. GitHub 무료 조직/공개 저장소 생성과 소스 업로드는 사용자 요청·약관 동의 후 수행했습니다. 공모전 제출·팀원 초대는 수행하지 않았습니다.

GitHub 업로드 직전 로컬 `verifyAll` 및 `smoke-test.py --check-contract`도 통과했습니다. 로그는 Git 제외 `work/github-bootstrap-verify.log`, `work/github-bootstrap-smoke.log`입니다.


## 2026-11-07 REC-01 재전송 제어 후속 검증

- `KakaoPlaceSearchAdapter`는 HTTP/1.1을 사용하고 `jdk.httpclient.disableRetryConnect=true`, `jdk.httpclient.redirects.retrylimit=1`이 JVM 시작 인수와 현재 속성에 모두 설정돼야 생성됩니다. 실행 중 뒤늦게 설정한 값이나 잘못된 옵션을 거부합니다. 전역 JVM/셸 설정은 변경하지 않았고 Gradle 테스트 JVM에만 옵션을 전달합니다.
- 새 JVM에서 시작 설정과 정상 생성을 검증했습니다. loopback의 응답 전 단절·불완전 본문·재사용 연결 단절에서 추가 요청이 없으며 정상 빈 결과는 성공했습니다. 신규 14개, 기존 130개를 재실행해 **144개 실행, 실패/오류/skip 0**입니다.
- `./gradlew -PincludeAndroid=false format verify` 및 `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 통과. 로컬 증거: Git 제외 `work/rec01-retry-verify.log`, `work/rec01-retry-tests.json`, `work/rec01-retry-smoke.log`.
- [P3 계약 초안](REC01_CONTRACT_DRAFT.md)에 저장 검색과 미리보기 분리, IO/중복 처리, 출처·예약 모드와 화면 상태, #3/#4/#5 검토·인수 조건을 작성했습니다. 팀 합의·공개 API·DB 적용은 아직 없습니다.
- 정책 문의 페이지를 새로고침해 운영자 답변이 없는 상태를 확인했습니다. 실제 Kakao 요청·키 값 복사·전화·유료 작업 0회, 제품 MOCK 주입과 LIVE 차단 유지. 실제 TLS·프록시·사업자 호출/과금 집계는 미검증입니다. Android는 이번 로컬 변경/검사 대상이 아닙니다.


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

## 2026-11-07 PR #10 통합과 REC-03 첫 구현

- 사용자가 머지와 후속 착수를 승인해 PR #10을 Ready 전환 후 필수 CI·충돌·미해결 대화·현재 head를 확인하고 squash merge했습니다. merge commit: `a55de3aaa6e0b715f77495984ae96ca9ddc30b06`. 원격 `feat/2-place-adapter`는 삭제됐으며 #2 CLOSED, #11/#12/#13 OPEN을 확인했습니다. main 보호나 Closes 규칙 우회는 없습니다.
- 최신 main에서 `feat/12-place-preview`를 생성했습니다. package-private `PlacePreviewSession`은 진행 중 동일 세대/query 합류, 세대 충돌/구세대 거부, 목적지 변경·취소·제한 시간·늦은 응답, 세션당 실제 실행 1개+최신 대기 1개를 구현합니다. Spring bean·공개 endpoint·기존 workflow에는 등록하지 않았습니다.
- 단위 22개와 실제 Spring/H2 통합 3개를 추가했습니다. 조회 지연 중 기존 검색 진행, preview DB 미저장, DB mutex 점유와 preview 독립, 트랜잭션 안 start 거부를 검증했습니다. 기존 144개를 재실행해 **169개 실행, 실패/오류/skip 0**입니다.
- `./gradlew -PincludeAndroid=false format verify`와 `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 통과. 로컬 Git 제외 증거: `work/rec03-preview-verify.log`, `work/rec03-preview-tests.json`, `work/rec03-preview-smoke.log`.
- [REC-03 구현 범위](REC03_PREVIEW.md)에 세션 메모리 수명, 완료 결과 재조회/캐시 없음, 취소/응답 세대, 재시작 후 중복 기억 없음과 후속 계약을 기록했습니다. #12의 팀 검토·인증/세션 수·전체 호출 상한·재시작 복구·navigation freshness·공개 API/화면은 미완료입니다.
- 공개 API/Model·migration·의존성 변경 없음. 실제 Kakao 조회·키 값 복사·전화·유료 실행 0회, 제품 MOCK과 LIVE 차단 유지. Android 기기 검증이나 실제 서비스 연결 성공으로 세지 않습니다.


## 2026-11-07 REC-03 소유 scope·세션 수명과 실행 상한

- 같은 `feat/12-place-preview` / Draft PR #14에서 `PlacePreviewRegistry`를 추가했습니다. 서버 생성 UUID, 신뢰된 호출자 owner scope 대조, owner별/전체 세션 상한, 단조 경과 시간의 유휴 만료를 구현했습니다. foreign/미존재/만료 세션은 같은 NOT_FOUND로 처리하고 비정상 요청은 수명을 갱신하지 않습니다.
- 여러 세션의 동시 실행 슬롯·Registry 수명 누적 승인 예산을 공유합니다. 취소/만료를 무시하는 adapter가 실제로 반환하기 전까지 슬롯을 유지하고 실패·취소나 세션 재생성으로 누적 예산을 돌려주지 않습니다. 삭제/재시작된 UUID를 자동 복원하거나 재조회하지 않습니다.
- Registry 18개 + H2 추가 1개를 더해 총 **188개 실행, 실패/오류/skip 0**입니다. 기존 H2 비차단/DB 미저장 테스트도 Registry 경로에서 통과했습니다. format/verify와 JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단 통과. 로컬 증거는 Git 제외 `work/rec03-registry-results.json`, `work/rec03-registry-verify.log`, `work/rec03-registry-smoke.log`에 있습니다. 초기 테스트 helper 컴파일 오류는 수정했고 초기 로그도 따로 보관했습니다.
- 로그인 인증·외부 요청의 owner 검증은 아직 미구현이며, Owner는 trusted caller가 공급할 내부 scope입니다. 만료는 요청/명시적 reap 시 정리하고 자동 정리 스케줄은 아직 없습니다. Registry 재생성/다중 인스턴스를 넘는 누적 예산·영속 claim·불확실한 실행 대조도 미완료입니다.
- 공개 REST API·Spring bean·기존 WorkflowService 연결·Model/migration/의존성 변경 없음. navigation freshness/ETA 재검증, #4/#5 계약·공개 API/화면, 실제 인증/복구는 다음 작업입니다. #12는 OPEN·PR #14는 Draft로 유지하며 실제 Kakao 조회·전화·비용은 0회입니다.

후속 복구 확인: 11:07:51.902254Z에 TripInfo rawEtaSeconds=666/POSITIVE를 수신했습니다. 이는 원시값 수신 확인이며 남은 시간/도착 시각으로 변환하지 않습니다.
