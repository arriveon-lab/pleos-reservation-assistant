# 연동 증거와 확보할 입력

2026-11-09 추가 결정: [표준 API 대체·단계별 SDK 연동](SDK_FALLBACK_PLAN.md)을 기준으로 위치·사용자 음성의 어댑터 경계와 기기 가용성 검사를 계획합니다. ArriveOn 프로젝트는 생성됐고 콘솔에서 NaviHelper·Vehicle State는 사용 가능, FusedLocation·STT·TTS는 준비 중으로 확인했습니다. 별도 신청 이력은 없으며 실제 기기 동작은 미검증입니다. 아래 2026-11-04/07 기록은 당시 검증 이력입니다.

기준일 2026-11-04 KST. 문서/소스 확인은 실기기·계정별 실행 성공을 의미하지 않습니다.

2026-11-07 장소 연동 추가 검토: [REC-01 실제 연동 검토](REC01_INTEGRATION.md)에 Kakao 저장 제한과 무료 앱 준비 절차를 기록했습니다. HTTP adapter는 loopback 가상 서버로 검증했으며 제품 검색은 MOCK입니다. 후속 사용자 동의로 ArriveOn 앱 생성·카카오맵 활성화·일간 무료 쿼터 제공·유료 API 사용 안 함을 확인했습니다. 키 값 복사·실제 데이터의 H2 저장·장소 API 요청은 수행하지 않았습니다.

## Pleos

2026-11-09 재탐색 계약 추가 확인: 고정 NaviHelper3.0.5 AAR와 공식 Routes/권한 문서에서 requestReRoute()와 NAVI_ROUTE_SEARCH 요구를 확인했습니다. 조회/requestTrip용 NAVI_ROUTE와 다르며 probe에 별도 선언·실행 시 확인·진단 버튼을 추가했습니다. 빌드/6개 테스트/Lint와 기기 설치·grant 이후 사용자 공개 길안내에서 명시적 재탐색 요청1회·상태 무효화·내비 전면 전환을 확인했습니다. 완료는 system_server Watchdog 재시작과 겹쳐 판정 불가입니다. 별도 데이터 비교/후속 설치 대기는 STATUS를 따릅니다. source=PLEOS 차단·ETA/응답 연관성 UNVERIFIED와 기존 공통 계약을 유지합니다.

2026-11-09 목적지 교체 관측: 같은 세션 공개 목적지 A→B 변경 후 probe 복귀에서 routeCancelled → invalid 목적지 → routeStarted를 받았습니다. 새 조회 전 Trip은 비활성, 조회 후 VALID_FIELDS/raw 648초(변경 전 4196초)를 확인했습니다. destinationChanged는 이 시험에서 발생을 확인하지 못했고 이 순서를 모든 SDK/기기에 일반화하지 않습니다. 늦은 이전 유효 응답은 미재현이며 응답 연관성/ETA 변환은 계속 미검증입니다.

2026-11-09 추가 관측: 긴 경로 rawEtaSeconds=3686, 이후 새 세션의 같은 공개 경로 반복 요청은 78.319초 간격으로 모두 4227초였습니다. 후자의 응답 약 5–10초 뒤 UI는 남은 68분으로 원시값(70분 27초)과 일치하지 않았습니다. requestTrip 결과와 현재 안내 경로의 연관성·시간 기준을 확정하지 않고 PLEOS 서버 차단을 유지합니다. 백그라운드 취소·목적지 무효화·복귀 후 Trip 비활성도 재확인했으며 도착/단절/늦은 유효 응답을 대신하지 않습니다. [최신 실행 기록](STATUS.md)을 우선합니다.

2026-11-08 실행 증거 추가: Pleos Connect v2.1.0/API 34에서 debug APK 설치·실행, NAVI_ROUTE granted, NaviHelper 3.0.5 초기화, 초기 invalid 목적지 콜백과 경로 설정 후 VALID_FIELDS 목적지, 최초 TripInfo rawEtaSeconds=1541을 확인했습니다. STOPPED 중 routeStarted도 1회 관측했습니다. 이는 아래 과거 정적 확인 기록에 추가된 첫 실행 결과이며 ETA 시간 기준·주기·도착/취소/단절·응답 연관성을 확정하지 않습니다. 상세 시각과 미검증은 [VEH-01 기록](VEH01_VALIDATION.md)과 [STATUS](STATUS.md)를 따릅니다. 서버 PLEOS 차단은 유지합니다.

같은 세션의 두 번째 수동 응답은412.901초 후 rawEtaSeconds=1538이었고, 사용자 UI 보고는 기기04:37/이동25분/도착05:02 AM입니다. 기기 시간대 America/Los_Angeles를 ADB로 확인했으며 동시 관측이 아니므로 duration 가설을 확정하지 않습니다. 길안내 종료 시 백그라운드 routeCancelled 수신과 목적지 무효화·복귀 후 Trip 버튼 disabled도 확인했습니다. 취소1회 성공과 도착/단절/늦은 유효 응답·다른 경로/자정 검증은 구분합니다.

### 내비게이션과 장소 검색의 역할

현재 설계는 Pleos 내장 내비게이션을 길안내·목적지·ETA 연동에 사용하고, 목적지 주변 음식점 후보 데이터는 별도 장소 adapter로 받습니다. 카카오맵 활성화는 Local REST API 준비이며 차량 지도를 Kakao 지도 화면으로 교체하는 구현이 아닙니다.

| 기능 | 담당 경계 | 현재 검증 수준 |
| --- | --- | --- |
| 길안내·목적지·ETA | Pleos Navi Service와 NaviHelper | 3.0.5 probe 빌드·공개 API 확인. 기기 실행·ETA 시간 기준 미검증 |
| 주변 음식점 후보 목록 | REC-01 장소 adapter, Kakao Local 우선 검토 | 계정 무료 설정·loopback HTTP 검증. 실제 장소 요청·제품 연결 전 |
| 우리 앱의 화면 | 차량 조건 비교·명시적 승인·예약 결과 | 제품 UI 미구현. Android Kakao 지도 SDK/지도 렌더러는 도입하지 않음 |

공식 [NaviHelper 3.0.5 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)는 목적지·경유지·경로·내비 정보·Navigation 화면 요청을 설명합니다. 같은 버전의 배포 SDK 공개 메서드도 대조했으며 일반 음식점 후보 목록 검색 메서드는 이번 확인 범위에서 찾지 못했습니다. 새로운 공식 POI API가 확인되면 사용 조건·가용성을 검토해 장소 adapter의 공급자로 비교할 수 있습니다.

### API 선택 정책과 가용성

사용자가 공유한 [SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)을 2026-11-04 확인했습니다. 같은 기능의 AAOS 표준이 있으면 이를 우선하고, 표준으로 제공되지 않는 특화 영역에 Pleos SDK를 사용합니다. SDK 전용 API가 표준에 편입돼도 기존 API는 최소 2개 메이저 버전까지 호환성을 유지한다는 정책이며, 모든 차종의 기능 제공이나 무기한 호환을 뜻하지 않습니다.

[API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)은 NaviHelper를 **내비게이션 탑재 차량**에 한해 호환으로 안내합니다. TTS/STT는 전체 호환으로 안내하지만 실제 권한·서비스·음성 동작은 별도 검증합니다. Vehicle/FusedLocation은 가용성을 확인하며, AAOS property는 차종명 분기 대신 실제 설정/접근 상태를 확인합니다. 도메인별 `checkXXXCapabilities`는 통칭이므로 그대로 API 이름을 만들어 호출하지 않습니다. 특수 property 직접 구현 전 기술 지원 검토가 필요합니다.

아래는 해당 정책과 기능별 문서를 적용한 **프로젝트 설계 결정**이며, 구현/기기 검증 완료 표가 아닙니다.

| 필요한 기능 | 선택할 경로 | 미지원·미확인 시 계획 |
| --- | --- | --- |
| 목적지·ETA | 기존 NaviHelper probe 유지, 기능별 권한/응답 검증 | 자동 조회 불가 표시. UX 제한이 허용하는 상황에서 MANUAL 입력, 또는 기능 보류. MOCK으로 몰래 대체하지 않음 |
| 주행 중 UI 제한 | AAOS `CarUxRestrictionsManager`와 플랫폼 제한 상태 | 상태 미확인 시 제한이 필요한 화면을 보수적으로 잠그는 방안 검증. 속도 하나로 제한 상태 추측 금지 |
| 속도·기어 등 표준 차량 정보 | 실제 요구가 생길 때 AAOS Car API 검토 | 기능별 지원/접근 상태 확인. 현재 MVP에 불필요한 수집·권한 추가 안 함 |
| 차량 내 음성·현재 위치 | Android 표준 API 가용성 우선 검증, [SDK 대체 계획](SDK_FALLBACK_PLAN.md)에 따라 Pleos 구현체 추가 검토 | 미지원/권한 거부/단절 안내, 허용된 정차 입력 또는 명시적 개발 MOCK. 외부 전화 채널과 분리 |

주행 UX는 [Pleos 주행 규제 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)의 `CarUxRestrictions`/`ActiveRestrictions`를 기준으로 설계합니다. 초기 상태 조회와 변경 구독, 해제 수명주기는 [AAOS CarUxRestrictionsManager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager)의 실제 API로 검증합니다. 제조사 전용 제어가 필요하지 않은 예약 MVP에서 Vehicle SDK 제어 권한을 추가할 이유는 아직 없습니다.

### 현재 확인한 SDK 증거

| 항목 | 확인 내용 | 확인 수준 |
| --- | --- | --- |
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5`와 공식 저장소 | 문서·AAR 정적 확인 |
| 목적지 | `getDestinationInfo()` → `onDestinationInfo(DestinationInfo)` | 문서·javap 확인 |
| Trip | `requestTrip(TripRequest)` → `onTripInfo(TripInfo)`; `getTripInfo()` 사용 안 함 | javap 확인 |
| ETA | `TripInfo.eta`는 int, navi-data 3.0.4 공식 소스 주석의 단위는 **seconds** | 소스 확인 |
| ETA 시간 기준 | 남은 시간/epoch/자정 기준 등 의미 구분 | 미확인; 서버 Instant 변환 차단 |
| 목적지 remaining time | `DestinationInfo.remainTime`은 남은 이동 시간, 초라는 소스 설명 | 소스 확인, 기기 미검증 |
| 권한 | NAVI_ROUTE가 정보 조회/requestTrip에 안내됨 | 문서 확인; 실제 grant 미검증 |
| 이벤트 | 목적지 변경·도착·Trip 콜백, Navigation 앱 시나리오 의존 | 문서 확인; 갱신/백그라운드 미검증 |
| Gleo/STT/TTS/주행 제한 | 가이드 경로 존재, 제품 통합 없음 | 미구현/기기 미검증 |

근거: [의존성](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [공식 navi-data 3.0.4 sources JAR](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/navi-data/3.0.4/navi-data-3.0.4-sources.jar).

재현용 다운로드/추출물은 Git 제외 `work/research/`에 있고 의존성 원본은 커밋하지 않습니다. 상세 ETA 웹 페이지는 본문 설명을 추출하지 못했으나 sources JAR의 `ai/pleos/playground/navi/data/TripInfo.kt`와 `DestinationInfo.kt`를 직접 확인했습니다. 단위 확인만으로 시간 기준을 추측하지 않습니다.

Android probe 사용·기기 로그 수집 방법: [android/README.md](../android/README.md). 다음 단계는 공개 테스트 목적지를 설정한 emulator에서 rawEtaSeconds를 실제 UI의 남은 시간/예상 시각과 비교하는 것입니다. 현재 서버는 source=PLEOS를 거부합니다.

2026-11-08 재대조: NaviHelper 3.0.5 AAR의 공개 API와 navi-data 3.0.4 소스를 직접 읽었습니다. TripInfo에는 요청/목적지 ID와 관측 시각이 없으므로 목적지 변경 후 응답의 연관성을 보장할 수 없습니다. probe는 모든 응답에 `responseAssociation=UNVERIFIED`를 유지하며 변경·도착·취소 시 기존 목적지를 무효화합니다. 메인 스레드 상태 처리·종료 후 콜백 방어와 단위 테스트는 기기 동작 증거를 대신하지 않습니다. [VEH-01 기록](VEH01_VALIDATION.md)에 실제 계약·환경·협업 경계와 남은 관측 표를 정리했습니다. ETA 시간 기준·미지원/단절·백그라운드는 미검증이며 PLEOS 차단을 유지합니다.

## 전화 사업자 비교

**2026-11-10 팀 결정: 전화 회선은 Telkit을 사용합니다.** Telkit 070 번호와 SIP 트렁크를 LiveKit에 연결하고, 음성 대화는 LiveKit 에이전트가 맡습니다. 확인한 연동 조건·한도·요금·약관은 [VOICE-01 업체 검토](calls/VENDOR_REVIEW.md#0-결정-telkit-채택-2026-11-10), 흐름은 [전화예약 에이전트 계획](PHONE_RESERVATION_AGENT_PLAN.md)에 있습니다. 계정 생성·번호 구매·발신은 하지 않았습니다.

아래 표의 Telkit 외 행은 2026-11-08 비교 기록입니다.

| 후보 | 공식 자료로 확인한 범위 | 아직 확인할 사항 | 현재 판단 |
| --- | --- | --- | --- |
| Telkit (주식회사 플릭) | 070 번호(계정당 1개), LiveKit SIP 수신·발신(TLS 5061, PCMA/PCMU), 통화 기록 API, 동시 4통·발신 하루 500통, 요금·약관 | 발신 표시 번호·발신번호 등록 조건, `telkit_call_id`와 LiveKit 참가자 연결, `call.ended` 웹훅 공개(현재 준비 중, 발신 미적용), 한국어 품질 | **채택(2026-11-10 팀 결정).** 계정/발신 안 함 |
| Vox (주식회사 플릭) | 070 개통, `POST /v3/calls`, 서명 webhook, 통화 후 구조화 추출, `metadata` 조회 필터, 요금·약관·녹음 보관 설정 | 실제 식당 발신의 업체 확인, 녹음 해제 API 필드, 한국어 인식 품질, 발신 멱등 키 부재 대응 | 미채택(11-08 PoC 1순위 후보였음) |
| 한국AI통신 (팀러너스 주식회사) | 070 즉시 발급, `POST /v1/accounts/{accountId}/calls`, 서명 webhook, Agent SDK, 요금·약관 | 체험 기간 발신 불가, 외부 참조 조회 없음, webhook 재시도 규칙, 업체 검증 모델 범위 | 미채택 |
| Twilio Programmable Voice | 한국 PSTN 발신 reachability 표, Toll-Free 발신 제한, Caller ID 조건. Gather 한국어 옵션 | 한국 번호 발급 여부, 국내 발신 Caller ID 비보장의 실제 표시, 모델 조합, 자유 대화 지연, 녹음/전사, 요금·번호 구매 | 보류. 선정/실통화 안 함 |
| NAVER CLOVA AiCall | inbound/outbound AI call bot, 한국어 대응 사업자 후보. outbound 최소 3채널·캠페인형 고정 메시지·외부 솔루션 연계 안내 | 외부 매장 대행 허용, 자유 목적지 발신 API, 계약 소요, 최소 고정비·종량비, 데이터 저장 | 매장별 자유 대화와 맞지 않아 보류. 계정/계약/발신 검증 안 함 |
| KT AICC/A’Cen | 공식 소개자료에 음성 인식·대화·합성과 inbound/outbound 구성 | 스타트업 PoC 개통, 외부 API, 한국 발신 정책, 과금·녹음·콜백 | 국내 후보. 계약/SDK/발신 검증 안 함 |

근거: [Twilio 한국 가이드](https://www.twilio.com/en-us/guidelines/kr/south-korea-voice-guidelines---twilio), [Gather](https://www.twilio.com/docs/voice/twiml/gather), [CLOVA AiCall](https://guide.ncloud-docs.com/docs/en/clovaaicall-aicall-1-1), [KT 공식 AICC 자료](https://enterprise.kt.com/resource/files/DX_Webinar_Oct_02.pdf). 가격 수치는 확인되지 않았으므로 비용을 0원이나 임의 단가로 가정하지 않습니다. STT 한국어 지원과 한국 전화망 발신은 서로 다른 검증입니다.

## 실제 통화 PoC 절차

1. 전화 담당이 업체별 대상 국가/번호·외부 예약 대행 허용·필수 서비스·예상 비용을 기록합니다. 사업자 계정과 발신번호 소유/검증은 별도로 준비합니다.
2. 팀 소유 또는 사전 협의 매장 번호만 allowlist에 등록합니다. 실제 번호는 저장소 밖의 비밀 저장소에 둡니다.
3. 실행 제안에는 업체/계정, 수신 대상, 목적(음성품질·문의·예약 중 무엇인지), 최대 통화 수·시간, 예상 비용과 상한, 승인 유효기간을 포함합니다. **명시적 승인 전 발신·번호 구매·유료 모델 호출을 실행하지 않습니다.**
4. LIVE adapter 도입 PR에 인증·사용자 소유권·승인 증빙·allowlist·총비용/횟수 제한·kill switch·웹훅 서명/중복·outbox를 구현합니다. 현재 startup 차단은 이 PR과 검증 없이 제거하지 않습니다.
5. 첫 통화는 AI·생성 음성 고지와 단순 문의부터 합니다. 한국어 숫자/시간/부정 표현/잡음/중도 단절을 테스트합니다. 저장할 녹음·전사 범위는 적용 법률·사업자 조건·동의를 검토하고 결정합니다.
6. 매장 명시 수락/시간/금액 일치, 거절, UNKNOWN을 각각 기록합니다. 미확인 예약은 상태 대조 후 해결하며 자동 재발신하지 않습니다.

검증 기록 양식: 날짜·승인 reference·환경/버전·모의/실제·목적·기대 결과·실제 결과·관측 시각·비용 합계·로그 위치·판정. 개인정보와 원 음성은 공개 보고서에 넣지 않습니다.

## 필요한 계정·자료

| 담당 | 필요 입력 | 현재 없이 가능한 일 |
| --- | --- | --- |
| 차량 | ArriveOn 프로젝트 생성 완료. NaviHelper 별도 신청 불필요. Emulator/Virtual Console·기기 검증 필요, 준비 중 SDK는 상태 변경 후 신청 검토 | probe 소스·계약, 표준 위치/음성 가용성 검증 계획, 서버 MOCK |
| 장소 | 시범 지역, 공식 API 키, 저장/재표시 조건 | 가상 후보·평가 테스트 |
| 전화 | Telkit 계정(본인인증·카드 등록)·070 번호·SIP 자격, LiveKit 프로젝트, 협의 수신번호, 예산/승인 | 모의 결과 정규화·실패 시나리오 |
| 서버 | 팀 DB 경험, 운영 환경, 인증 방식 | H2 트랜잭션·중복/상태 검증 |
| 통합 | 팀 GitHub ID, 조직/공개범위, APK 서명 주체 | CI·문서·시연 절차 |

## 출시 전 검토 범위

기획서의 개인정보·위치·아동/가족·국외 처리·AI 고지·녹음/전사·데이터 재이용 요구를 유지합니다. 현재 법률 검토를 완료하지 않았고 적법성 결론을 내리지 않습니다. 실제 업체와 데이터 흐름이 정해지면 국가법령정보센터의 현행 법령과 사업자 약관으로 별도 검토합니다. 이 개발용 MOCK 구현은 그 검토의 대체물이 아닙니다.

## 2026-11-09 격리 두 번째 부팅 — 재탐색 완료 콜백 미관측

사용자 수동 설치 Success 후 앱 실행 Status: ok, 두 권한 허용과 SDK 초기화를 확인했습니다. 공개 길안내 시작 후 routeStarted(11:03:44.037310Z), 명시적 조회 VALID_FIELDS(11:04:10.363953Z), reRouteRequested 1회(11:04:41.361257Z)를 같은 세션에서 수신했습니다. 요청 시 revision=2/목적지 사용 가능=false로 무효화됐고 약1.323초 뒤 STOPPED/내비 전면 전환, 11:05:16.535357Z에 HOT 복귀했습니다. 복귀 화면에서 Trip/재탐색 버튼은 비활성이었습니다.

요청 전후 system_server 시작 횟수는1로 유지됐고 요청 후 최소131초 관측 창에 reRouteEnded는0건입니다. 앞선 Watchdog로 중단된 원본 데이터 시험과 구분합니다. 완료/SDK 실패/미지원으로 단정하지 않습니다. 11:06:56.771035Z 수동 재조회 후 11:06:57.806926Z VALID_FIELDS로 회복했습니다. UIAutomator와 재탐색 반복 요청은 사용하지 않았습니다. ETA 시간 기준/응답 연관성과 도착·자동 이탈 재탐색은 계속 미검증이며 #1은 미완료입니다.

비식별 증거: captures/veh01-clean-second-reroute-20261109-live.jsonl, veh01-clean-second-reroute-result.json. 화면은 Git 제외 work/veh01/clean-reroute-return.png에만 보관했습니다. 코드 변경/테스트 재실행/commit/push/PR/외부 문의 발송은 없습니다.

2026-11-09 Issue #1의 앱 인수 코멘트 반영: [차량 데이터 앱 인수안](VEH01_APP_HANDOFF.md)에 #16/#26 전달 정보·오류/수명 상태·Navigation 변환 차단·공동 시나리오를 정리했습니다. 협의용 문서이며 제품 adapter 구현/공통 계약 변경/앱 연결 완료를 뜻하지 않습니다.

## 2026-11-10 ETA 무요청 관측·수동 반복 조회

Navigation3.3.2-rc5-hotfix.3/probe0.1.0-probe/NaviHelper3.0.5, Asia/Seoul, 같은 세션에서 길안내 시작(05:49:50.220220Z), 명시적 목적지 VALID_FIELDS(05:50:37.363502Z)를 받았습니다. 첫 Trip 요청05:51:16.353738Z→응답05:51:17.293121Z/raw731초, 두 번째 수동 요청05:54:59.346307Z→응답05:54:59.779159Z/raw734초입니다. monotonic 응답 간격222.494초이고 원시값은3초 증가했습니다.

첫 응답 이후 두 번째 수동 요청 전 222.052초 구간에서 추가 tripInfo는0건입니다. 중간 STOPPED/내비 전면/같은 세션 HOT 복귀를 포함하며 listener는 유지됐습니다. 이 제한된 정차·경로 시험에서 자발 콜백을 관측하지 못한 것이며 SDK 자동 갱신 없음/주기나 단순 카운트다운을 확정하지 않습니다. 시스템 시작횟수는 전후1이었습니다. 현재 경로 연관성과 ETA 시간 기준은 계속 UNVERIFIED입니다.

내비 캡처 전후 UTC05:52:29–05:52:39에 시계14:52/남은13분/도착15:04/속도0kmh였습니다. 첫 SDK 응답보다 약72–82초 늦고 UI 분 단위/반올림 규칙도 미확정이므로 동시 일치나 절대 ETA 변환 근거로 쓰지 않습니다. 위치/이름/POI는 공유 문서에 기록하지 않았고 화면은 Git 제외 work/veh01/eta-ui-20261110.png에만 있습니다.

비식별 근거: captures/veh01-eta-20261110.json, veh01-followup-20261110-live.jsonl, 누적 snapshot146건. 도착/실패 조합/늦은 유효 응답·공동 인수는 여전히 미검증입니다. 이번 단계는 기기 관측/문서 갱신이며 코드·테스트·commit/push·PR/Issue 변경은 없습니다.

## 2026-11-10 권한 미선언 실제 SDK 예외 검증

기기 dumpsys/pm 권한 정의에서 NAVI_ROUTE/NAVI_ROUTE_SEARCH 모두 normal, 제공 패키지 ai.pleos.playground.service.navi임을 확인했습니다. 런타임 권한 창 거부/pm revoke 시험으로 간주하지 않습니다. 기존 권한을 강제 변경하거나 시스템 서비스를 중지하지 않았습니다.

정상 probe Java 소스와 동일한 별도 진단 package ai.pleos.assistant.probe.denied를 Git 제외 work/veh01/no-permission-probe에 만들었습니다. 두 uses-permission 선언만 제외하고 applicationId를 분리했으며 고정 AGP8.9.2/SDK35/NaviHelper3.0.5를 유지했습니다. 첫 assemble은 복사본의 AndroidX 설정 누락으로 실패했고 원본 gradle.properties를 반영한 재시도47초에 성공했습니다. 이 fixture는 APK 빌드만 실행했으며 Lint/단위 테스트 통과를 주장하지 않습니다. 최종 merged manifest에 두 권한 없음과 Java 소스 동일을 검사했습니다. APK SHA256=f3541e1c378d563db4ac26ab11af44cd838bfd2467a898e33d62de8ad05bec05. 정상 APK/manifest/앱은 덮어쓰지 않았습니다.

기존 격리 에뮬레이터를 정상 종료 후 같은 데이터로 부팅해 설치 Success를 받았습니다. 초기 검은 화면은 SDK 결과로 쓰지 않고 표준 WAKEUP과 포커스/화면 재확인 후 버튼을 눌렀습니다. 같은 시험 세션에서 06:14:05.921867Z NAVI_ROUTE=false, 06:14:05.949898Z NAVI_ROUTE_SEARCH=false, 06:14:06.791312Z initialized, 06:14:06.801825Z destinationRequested, 06:14:06.819745Z sdkCallFailed/PERMISSION_DENIED를 받았습니다. routeRevision=1/목적지 사용가능=false, 화면에서 초기화/재조회/Trip/재탐색 버튼 비활성을 확인했습니다. 실패는 유효 목적지나 정상 빈 결과로 바뀌지 않았습니다. 이 경로는 코드상 SecurityException 분류입니다. 실패 처리의 listener 해제/SDK release 호출 구현은 있지만 SDK 내부 해제 완료 자체를 별도 계측하지 않았습니다.

시스템 시작횟수는 시험 중1회였습니다. 원시 근거는 Git 제외 captures/veh01-permission-fixture-20261110.json, veh01-permission-20261110-live.jsonl, 누적 snapshot158건, 화면 work/veh01/denied-result.png입니다. 정상 probe 화면으로 복귀 요청했습니다. 권한 미선언 조회 실패 경로는 실제 확인했지만 개별 권한 조합/런타임 거부·서비스 미지원/단절·도착·늦은 유효 응답·ETA 기준은 아직 미검증입니다. 테스트 앱/생성 프로젝트/SDK는 공유 fixture에 추가하지 않았으며 제품 코드·commit/push·PR/Issue 변경은 없습니다.

2026-11-10 근접 시점 ETA 대조 추가: [공유 관측 요약](VEH01_OBSERVATIONS_20261110.md)에 비식별 발췌·시각 구간·연관성/갱신 한계·다음 검증 환경을 정리했습니다. 수동2회 raw681/화면12분은 가설 근거이며 시간 기준 확정이나 제품 입력 허용은 아닙니다.

2026-11-11 서버 Navigation 답변 반영: [앱 인수안](VEH01_APP_HANDOFF.md)에 정확한 기존 시간 경계·오류 코드를 정리했습니다. #53 목적지 추천은 ETA 없는 별도 계약이며, ETA를 사용하는 예약/지연 통보 PLEOS 차단은 유지합니다. [후속 시험 환경·조건](VEH01_REMAINING_VALIDATION.md)을 참고합니다. 공식 Console 문서 확인을 내비 주행/도착 재현 성공으로 세지 않습니다.
