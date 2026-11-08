# 연동 증거와 확보할 입력

기준일 2026-11-04 KST. 문서/소스 확인은 실기기·계정별 실행 성공을 의미하지 않습니다.

2026-11-07 장소 연동 추가 검토: [REC-01 실제 연동 검토](REC01_INTEGRATION.md)에 Kakao 저장 제한과 무료 앱 준비 절차를 기록했습니다. HTTP adapter는 loopback 가상 서버로 검증했으며 제품 검색은 MOCK입니다. 후속 사용자 동의로 ArriveOn 앱 생성·카카오맵 활성화·일간 무료 쿼터 제공·유료 API 사용 안 함을 확인했습니다. 키 값 복사·실제 데이터의 H2 저장·장소 API 요청은 수행하지 않았습니다.

## Pleos

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
| 차량 내 음성·위치 | 해당 Pleos SDK 가이드 및 지원 조건 대조 | 미지원/권한 거부/서비스 단절을 UI에 반영. 외부 전화 채널과 분리 |

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

## 전화 사업자 비교

2026-11-08 업체 문서·약관 검토는 [VOICE-01 업체 검토](calls/VENDOR_REVIEW.md)에 상세히 기록했습니다. 아래 표는 요약이며 계정 생성·발신은 하지 않았습니다.

| 후보 | 공식 자료로 확인한 범위 | 아직 확인할 사항 | 현재 판단 |
| --- | --- | --- | --- |
| Vox (주식회사 플릭) | 070 개통, `POST /v3/calls`, 서명 webhook, 통화 후 구조화 추출, `metadata` 조회 필터, 요금·약관·녹음 보관 설정 | 실제 식당 발신의 업체 확인, 녹음 해제 API 필드, 한국어 인식 품질, 발신 멱등 키 부재 대응 | PoC 1순위 후보. 계정/발신 안 함 |
| 한국AI통신 (팀러너스 주식회사) | 070 즉시 발급, `POST /v1/accounts/{accountId}/calls`, 서명 webhook, Agent SDK, 요금·약관 | 체험 기간 발신 불가, 외부 참조 조회 없음, webhook 재시도 규칙, 업체 검증 모델 범위 | 2순위 후보. 계정/발신 안 함 |
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
| 차량 | Pleos 프로젝트/SDK 신청, Emulator/Virtual Console, Android SDK 35 | probe 소스·계약 준비, 서버 MOCK |
| 장소 | 시범 지역, 공식 API 키, 저장/재표시 조건 | 가상 후보·평가 테스트 |
| 전화 | 업체 계정, 발신번호, 협의 수신번호, 예산/승인 | 모의 결과 정규화·실패 시나리오 |
| 서버 | 팀 DB 경험, 운영 환경, 인증 방식 | H2 트랜잭션·중복/상태 검증 |
| 통합 | 팀 GitHub ID, 조직/공개범위, APK 서명 주체 | CI·문서·시연 절차 |

## 출시 전 검토 범위

기획서의 개인정보·위치·아동/가족·국외 처리·AI 고지·녹음/전사·데이터 재이용 요구를 유지합니다. 현재 법률 검토를 완료하지 않았고 적법성 결론을 내리지 않습니다. 실제 업체와 데이터 흐름이 정해지면 국가법령정보센터의 현행 법령과 사업자 약관으로 별도 검토합니다. 이 개발용 MOCK 구현은 그 검토의 대체물이 아닙니다.
