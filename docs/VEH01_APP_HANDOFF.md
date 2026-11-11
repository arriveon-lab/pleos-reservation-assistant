# VEH-01 차량 데이터 앱 인수안

## 2026-11-10 소영님 요청 형식의 인계 표

[소영님 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6760336481) 회신 기준입니다. [게시한 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6760697971)에서 신규 비식별 발췌를 확인할 수 있습니다. 구현 기준 commit은487e55c, main 통합/CI 확인 기준은49115f6(PR47)입니다. 아래 오늘 기기 관측·문서 보강은 아직 미커밋이며 원격49115f6에 포함됐다고 주장하지 않습니다. 제품 adapter/API 스키마 변경이나 #46 앱의 실제 차량 입력 채택은 없습니다.

환경: Pleos Connect v2.1.0/API34 x86_64, NaviHelper3.0.5/navi-data3.0.4, Navigation3.3.2-rc5-hotfix.3/code1, probe0.1.0-probe/code1. 실제 설치 버전은2026-11-10 dumpsys package 확인, 일반 probe APK SHA256=05222c17a137ec5277563b5178a181d801ad4a90a31fff4dbc177fdf9534b864입니다.

| 입력 항목 | 원시값의 단위·시간 기준 | 관측·갱신 시점 | 미지원·오래됨·단절 상태 | 확인 수준 |
| --- | --- | --- | --- | --- |
| 목적지 | SDK 좌표·이름/POI 유효성 검사. 목적지 원문은 로그/인계 예제에 제외 | 명시적 getDestinationInfo 응답; 05:50:37.363502Z VALID_FIELDS | 변경/취소 시 기존 값 무효화, 재조회 필요. invalid 응답만으로 없음/미지원/단절을 단정하지 않음 | 실제 수신·변경/취소 일부 기기 확인. 응답과 현재 경로의 강한 연관성 미확인 |
| TripInfo ETA | rawEtaSeconds=731/734, 단위 초. 남은 시간/절대 시각 등 시간 기준 미확인 | 수동 두 요청의 응답05:51:17.293121Z/05:54:59.779159Z; monotonic222.494초 간격. 추가 요청 없는222.052초 구간 추가TripInfo0 | 오래됨 임계값 미합의. 임의 Instant 변환·현재+1시간 대체 금지 | 실제 원시값 수신 확인. 자동 갱신 주기/현재 목적지 연관성 미확인 |
| 관측 시각 | observedAt은 UTC/RFC3339 앱 콜백 진입 시각, elapsedRealtimeMs는 기기 monotonic ms | SDK 원본 생성 시각/적용 시각이 아님. 화면 시각은 별도 | 새 수신시각만으로 데이터 최신성 보장 금지. 프로세스/부팅 경계 고려 | 로그 구현/기기 수신 확인, 제품 TTL 정책 미합의 |
| 가용성/권한 | 권한 boolean·PERMISSION_DENIED, 원인 불명 실패는 UNAVAILABLE_UNKNOWN | normal 권한 두 개 미선언 별도 동일 Java 앱에서 초기화 성공 후 목적지 조회06:14:06.819745Z 실패 | 실패 시 목적지 무효화·조회/Trip/재탐색 비활성. 런타임 거부 창 시험과 구분 | 권한 미선언 조회 실패 실제 확인. 미지원/서비스 단절·개별 권한 조합은 미확인 |
| 수명/늦은 결과 | 진단 session/revision/ignoredAfterClose; 서버 tripId/앱 검색 세대와 다름 | 변경/취소/도착 처리·종료 방어. 목적지 변경 시 새 명시적 조회 | 이전 결과로 현재 추천 승격 금지. 새 조회 중 과거 응답 판별은 SDK 식별자 부재로 보장 못함 | 상태6개 단위 테스트, 백그라운드 일부 기기 확인. 실제 도착/늦은 유효 응답은 미확인 |

### 비식별 근거와 다음 순서

- 공유 가능한 원격 기준: [구현487e55c](https://github.com/arriveon-lab/pleos-reservation-assistant/commit/487e55c), [통합49115f6](https://github.com/arriveon-lab/pleos-reservation-assistant/commit/49115f6), [검증 이력](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/49115f6/docs/VEH01_VALIDATION.md), [기존 인수안](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/49115f6/docs/VEH01_APP_HANDOFF.md). 신규 수치의 비식별 발췌는 GitHub 회신 본문에도 포함해 로컬 경로만 공유하지 않습니다.
- 신규 로컬 원시 근거: captures/veh01-versions-20261110.json, veh01-eta-20261110.json, veh01-permission-fixture-20261110.json. 개인정보 없는 허용 필드만 보존, 전체 logcat/화면/AVD/SDK 배포물은 공유하지 않습니다. 최초 로그 전체본 일부 소실 한계는 기존 기록대로 유지합니다.
- 다음 실행 가능 항목: 메모리 여유가 있는 동일 격리 Pleos 환경에서 개별 권한 누락 조합, 더 긴 전면/백그라운드 무요청 관측과 동시 UI 대조. 단절/미지원은 원본 시스템 서비스를 임의 중지하지 않는 격리 시험 환경, 도착/늦은 응답은 지원되는 차량 주행 재생·지연 재현 수단이 필요합니다. 현재 재현 수단/지원 조건은 미확인입니다.
- 서버 담당 #16/#4 검토 요청: 현재 증거로 허용 가능한 source, 시간 기준 검증 선행 조건, freshness 거절/요청·오류 예제를 확정해주세요. 기존 PLEOS 차단 해제를 요청하지 않으며 숫자 TTL이나 공통 오류 enum을 여기서 새로 확정하지 않습니다.
- 이후 #26/#46과 합의된 실제/가상 표시·오래된 값·목적지 변경 후 이전 후보 제거를 공동 검증합니다. ETA 변경은 기존 예약 자동 변경 권한이 아닙니다. CarUx는 #25에서 유지합니다.


기준일: 2026-11-09 KST. [#1 추가 코멘트](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6739718159)를 반영한 #16/#26 검토용 인수안입니다. **제품 adapter·공통 DTO·서버 계약에 적용되거나 공동 인수된 상태가 아닙니다.** 기존 #1 담당·probe 검증 범위를 유지합니다.

## 2026-11-11 목적지 추천과 ETA 의존 업무 인수 구분

[서버 담당 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6771962584)에서 [Draft PR #53](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/53)의 목적지 추천은 eta/observedAt 없는 별도 계약으로 확인했습니다. **ETA 미검증이 목적지 추천 계약 검토 전체를 막는 것은 아닙니다.** #53 호출자·필드 구현은 소영님/서버 담당 범위로 두고 여기서는 임의 DTO나 API 변경을 추가하지 않습니다. 로컬 main에 #53 코드가 통합됐다는 뜻도 아닙니다.

| 작업 경로 | 차량 담당이 넘길 조건 | 여전히 막아야 할 것 |
| --- | --- | --- |
| 목적지 기반 후보 추천 #52/#53 | 목적지 유효성·현재 경로 확인 수준·변경/취소 무효화 근거. 앱 view/generation 변경으로 이전 후보 폐기 | 추천을 위해 미확인 ETA를 생성하거나 이전 목적지의 결과를 현재 후보로 표시 |
| ETA 사용하는 검색/예약/지연 통보 | 시간 기준·동시 관측·응답 연관성·갱신/오래됨/복귀 근거를 갖춘 뒤 #1/#16/#4/#26 공동 인수 | source=PLEOS 기존 차단 해제, raw+now 변환, MOCK/MANUAL 위장, 기존 예약 자동 변경 |

현재 경로와 응답의 강한 연관성은 SDK 식별자 부재로 미확인입니다. 목적지 필드 유효성을 현재 경로 확인 완료로 확대하지 않습니다. 후보 추천은 실제/가상 출처·실패/UNKNOWN 구분과 세대 무효화를 합의한 범위에서만 연결합니다. 추천 선택은 문의/전화/예약·비용 승인과 별개이며 이번 실제 외부 실행은0회입니다.

실행 전 확인할 환경과 남은 검증은 [후속 검증 절차](VEH01_REMAINING_VALIDATION.md)를 따릅니다.

## 현재 근거와 인수 차단 조건

| 항목 | 확인/한계 |
| --- | --- |
| 기기/이미지 | Pleos Connect v2.1.0, Android14/API34 x86_64, RELEASE.release_connect_v2.1.0 test-keys, 제조사42dot; 실제 AVD 관측 |
| SDK | NaviHelper3.0.5, navi-data3.0.4; 고정 AAR/소스 계약 확인 |
| probe | versionName0.1.0-probe/versionCode1은 Gradle 설정 확인. 사용한 APK SHA256=05222c17a137ec5277563b5178a181d801ad4a90a31fff4dbc177fdf9534b864, 실제 설치/실행 확인. 2026-11-10 dumpsys package에서 설치 버전0.1.0-probe/code1 재확인 |
| Navigation 앱 | ai.umos.maps.android.navigation.app 실행 확인. 2026-11-10 dumpsys package에서 versionName3.3.2-rc5-hotfix.3/versionCode1, minSdk31/targetSdk35 확인 |
| 목적지/TripInfo | 최신 격리 시험에서 VALID_FIELDS(11:04:10.363953Z), TripInfo raw666초(11:07:51.902254Z). 정확한 관측은 UTC/monotonic과 함께 로컬 captures에 보존 |
| ETA 단위/기준/주기 | 단위 초는 소스로 확인. 시간 기준·현재 경로 연관성·자동 갱신 주기 미확인. 수동 조회 간격을 자동 주기로 선언하지 않음 |
| 재탐색 | 안정 관측 최소131초 중 명시적 요청1회/완료 콜백0. 시스템 시작횟수1 유지. 별도 Watchdog 시험과 구분하며 성공/실패 판정 유보 |

실제 목적지·Trip 수신을 제품 화면 연결 완료로 세지 않습니다. [검증 기록](VEH01_VALIDATION.md), [최신 현황](STATUS.md)이 원본이며 최초 로그 일부 소실 한계도 유지합니다.

## 앱에서 전달할 정보 — 협의용 의미, 신규 API 필드 아님

| 정보 | 현재 제공 범위 | 앱/추천 사용 경계 |
| --- | --- | --- |
| 목적지 | 좌표 유효성·이름·POI 필드 검사 후 사용 가능 여부. 실제 데이터는 SDK 경계에서만 취급 | 명시적 조회 당시 목적지 후보. 현재 경로 응답 연관성 보장으로 해석하지 않음; 이름/좌표/POI 로그 금지 |
| 도착 예상 시각 | 현재 검증된 PLEOS 절대 시각 없음 | 미확인 표시. rawEtaSeconds를 현재 시각에 더하거나 Unix 시각으로 해석하지 않음 |
| 관측 시각 | 콜백 진입 UTC observedAt + elapsedRealtimeMs | SDK 데이터 생성 시각이 아닌 앱 수신 시각. 화면 확인 시각과 별도; 미래 시각/시계 점프/프로세스 재시작 고려 |
| 출처/확실성 | source=PLEOS, measurementMode=SDK_PROBE, etaTimeBasis/responseAssociation=UNVERIFIED | 출처가 실제 SDK라는 사실과 업무에 사용 가능한 검증을 구분. 가상 미리보기는 MOCK 표기를 유지 |
| 수명/가용성 | probeSessionId, routeRevisionAtProcessing, destinationUsableAtProcessing, 실패 코드 | 앱의 예약/미리보기 세대 ID나 서버 tripId와 동일시하지 않음. SDK 타입은 제품 공통 계층으로 전파하지 않음 |

관측 시각만 새롭다고 데이터가 최신이라고 보장하지 않습니다. 차량 입력의 유효기간/오래됨 판정 임계값과 재조회 정책은 #1/#16/#26이 시간 기준·주기 검증 후 합의해야 하며 현재 임의 상수를 제품에 추가하지 않습니다.

## 상태 전달안과 실제 구현/기기 증거

아래 상태명은 앱 협의용 표현이며 현행 서버 enum이 아닙니다. 목적지 가용성과 ETA 확실성은 별도 축으로 표시해야 합니다.

| 원인 | 전달 의미/화면 제안 | 현재 근거·제약 |
| --- | --- | --- |
| 초기화·조회 중 | 조회 중, 이전 목적지/ETA로 새 요청 금지 | probe의 기존 목적지 무효화·버튼 제어 구현 |
| 유효 목적지 + raw ETA | 목적지 있음 / ETA 미확인 | 기기 수신 확인. 제품 READY 또는 확정 도착 시각으로 승격 금지 |
| 목적지 변경 | 이전 값 사용 중단, 새 목적지 확인 필요 | 같은 세션 A→B에서 취소→invalid→시작 관측. 모든 기기 순서로 일반화 금지 |
| 경로 취소 | 길안내 종료, 이전 값 사용 중단 | 백그라운드 취소·복귀 후 버튼 비활성 기기 확인 |
| 목적지 도착 | 도착 이벤트 수신 시 이전 값 무효화 | callback 처리 코드만 있음; 실제 도착 미검증. 목적지 null만으로 도착 추론 금지 |
| 누락/잘못된 목적지 | 목적지 확인 불가 | MISSING_OR_INVALID 관측. 목적지 없음·오류·미지원 중 원인을 단정하지 않음 |
| 오래된 값·재탐색 중 | 최신 정보 확인 필요, 기존 값 사용 중단 | revision 무효화 구현/일부 기기 확인. 업무 TTL 미합의, 완료 콜백 미관측 |
| 권한 거부 | 권한 필요, 차단 상태 안내 | SecurityException→PERMISSION_DENIED. 2026-11-10 별도 package/동일 Java/두 manifest 권한 미선언 상태에서 초기화 후 목적지 조회 실패·버튼 비활성 실제 확인. 개별 권한 조합/런타임 거부는 미검증; 자동 권한 부여 금지 |
| 미지원/연결 실패/단절 | 차량 정보 사용 불가 또는 원인 미확인 | initializeFailed/sdkCallFailed→UNAVAILABLE_UNKNOWN, sdkError 코드 보존. 서로 다른 원인을 임의 분류하지 않음; 기기 조합 미검증 |
| 백그라운드/복귀 | 수명·최신성 재확인; 자동 업무 재전송 금지 | STOPPED 시작/취소와 HOT 복귀 관측. 장기 수신 보장 아님 |
| 늦은/종료 후 콜백 | 이전 결과로 현재 화면·업무 상태 변경 금지 | closed/조회 대기 방어·단위 테스트. 새 조회 중 도착한 이전 응답은 SDK 식별자 부재로 판별 보장 없음; 실제 늦은 유효 응답 미재현 |

## 서버 Navigation 변환 조건

2026-11-11 [서버 담당 확인](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6771962584)과 [9709887의 고정 인계 문서](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/9709887/docs/REC02_TEAM_HANDOFF.md#navigation)를 반영했습니다. 로컬25b28a6의 WorkflowService.search/requireFuture와도 시간 경계를 정적으로 대조했습니다. #19/a5ae15e의 기존 `/api/v1/searches` Navigation은 tripId/destination/절대 RFC3339 eta/observedAt/source를 요구합니다.

| 입력 | 서버 기준 / HTTP400 오류 |
| --- | --- |
| source=PLEOS | 시간 검사보다 먼저 PLEOS_ETA_UNVERIFIED. 필수 필드/형식 검증을 건너뛴다는 뜻은 아님 |
| source=MOCK/MANUAL의 eta | `now < eta <= now+86400초`; 위반 INVALID_TIME |
| observedAt | `now-300초 <= observedAt <= now+5초`; 위반 STALE_NAVIGATION |
| 필수 입력/형식 오류 | INVALID_REQUEST |

300초/+5초는 기존 서버 입력 거절 기준이며 SDK의 실제 갱신 주기/생성 시각/최신성을 보증하지 않습니다. 앱 수신 시각으로 원본 생성 시각을 대체하지 않습니다. #16/#26의 최신 draft와 로컬 기준은 다를 수 있으므로 인수 시 정확한 계약 버전/commit을 대조합니다. 위 내용은 정적 대조이며 이번 HTTP/기기 재검증 결과가 아닙니다.

현재 PLEOS 변환 결과는 **전송 불가**입니다. 누락 ETA를 0·현재+1시간·null·MOCK/MANUAL로 바꾸어 같은 실제 차량 입력인 것처럼 보내지 않습니다. 가상 미리보기와 사용자가 명시적으로 제공한 MANUAL 입력은 별도 출처와 기존 계약으로만 다룹니다.

후속 adapter 착수 전 다음을 합의합니다.

1. ETA 시간 기준/변환 근거, 현재 목적지 응답 연관성, 갱신·신선도·시계 변경 처리.
2. SDK 비의존 내부 모델과 가용성/확실성·유효기간. 원시 SDK 모델을 공통 API로 전파하지 않음.
3. 같은 여정 tripId 유지와 목적지 변경 시 여정/미리보기 세대 정책. probeSessionId/revision을 서버 식별자로 재사용하지 않음.
4. #4/#16 서버 정책과 #26 호출자·화면을 함께 대조한 후 OpenAPI·예제·테스트·호출자 동시 변경. PLEOS 차단 해제는 이 문서만으로 승인되지 않음.

제품 adapter 구현·서버/추천/UX 코드 수정은 이번 적용 범위에 포함하지 않습니다.

## #16/#26 공동 인수 시나리오 — 모두 예정, 실행 통과 아님

| 시나리오 | 인수 기대 |
| --- | --- |
| 검증된 목적지/ETA 입력 | 목적지가 후보 조건에 반영되고 ARRIVAL 질문은 검증된 도착 시각/관측 근거를 사용. NOW 대기 정보는 ARRIVAL로 바꾸지 않음 |
| 목적지 있음/ETA 미확인 | 목적지는 별도로 표시 가능하되 확정 도착·도착 시 대기·예약 시각 산출에 미확인 ETA 사용 금지. 필요한 입력을 안내 |
| 목적지 변경·취소·오래됨 | 이전 세대 추천/늦은 결과 노출 차단, 새 명시적 조회 전 이전 차량 값으로 업무 요청 금지. 기존 예약은 보존 |
| ETA 갱신·예약 보유 | 도착 변화와 기존 예약 시각을 구분. 변경 제안만 하고 자동 변경·전화 금지. 사용자 권한/추가 비용 승인, 명시적 수락 및 시각·비용 일치 전 원 예약 유지 |
| 권한 거부·단절·복귀 | 실제 가용성/미확인을 표시하고 가상 입력으로 조용히 대체하지 않음. 복귀가 예약 재생성/자동 재전송을 유발하지 않음 |
| 도착·재탐색·늦은 콜백 | 기기 이벤트·화면 상태·서버 요청 여부를 같은 타임라인으로 대조. 로그만으로 제품 adapter 인수 완료 처리 금지 |

공동 실행 전 #16 계약 commit, #26 APK hash, Pleos 이미지/내비/SDK 버전, 가상/실제 입력 구분과 기대 결과를 고정하고 실행 후 실제 결과·미검증·승인 경계를 남깁니다. #25는 실제 CarUxRestrictions/기기 UI, #39는 화면 구성, #40은 MOCK 공동 시연 범위로 유지합니다. AOSP 실행·가상 목적지 UI는 Pleos 실연동 증거로 대체하지 않습니다.

## 코멘트 반영 판정 및 담당자 전달

| 코멘트 요구 | 이번 반영 | 남은 조건 |
| --- | --- | --- |
| 버전·콜백·ETA 검증 | 기존 근거와 APK 식별/Navigation 버전 누락을 명시 | 내비 버전·ETA 기준/주기·실패/도착 기기 검증 |
| 상태 전달 | 원인별 상태·기기 증거/제안 구분 | #16/#26 상태/TTL 합의와 adapter 기기 검증 |
| 앱 정보·Navigation 변환 | 필드 의미·전송 차단·영향/구현 경계 명시 | 최신 계약 대조·공동 인수 |
| 후보·질문·예약 연결 | 공동 시나리오·자동 예약 변경 금지 명시 | 검증된 차량 입력으로 공동 실행 |

#4: PLEOS 차단 유지와 변환 조건. #2/#16: 목적지·ETA 확실성/최신성 및 NOW/ARRIVAL 경계. #5/#26: 가용성/미확인 표시·수명·늦은 결과 처리. 이 문서는 인계 준비이며 팀 합의·외부 게시·#1 완료를 의미하지 않습니다.

2026-11-10 근접 시점 ETA 대조 추가: [공유 관측 요약](VEH01_OBSERVATIONS_20261110.md)에 비식별 발췌·시각 구간·연관성/갱신 한계·다음 검증 환경을 정리했습니다. 수동2회 raw681/화면12분은 가설 근거이며 시간 기준 확정이나 제품 입력 허용은 아닙니다.
