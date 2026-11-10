# 차량 위치·음성 SDK 대체 및 단계별 연동 계획

결정일: 2026-11-09 KST. 추적: [#30](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/30). 사용자 결정은 **표준 API 우선 구현 → Pleos 환경 검증 → 미지원 기능은 모의 실행으로 명시 → SDK 제공 시 필요에 따라 추가 연동**입니다. 이 문서는 설계 결정이며 새 런타임 구현·기기 실행 결과가 아닙니다.

## 현재 권한과 검증 수준

2026-11-09 ArriveOn 프로젝트 콘솔에서 NaviHelper·Vehicle State는 사용 가능, FusedLocation·SpeechToText·TextToSpeech는 준비 중, API 신청 내역은 없음으로 확인했습니다. 프로젝트 생성은 완료했으며 SDK 권한을 따로 신청하거나 승인받은 기록은 없습니다. 콘솔 상태는 이후 달라질 수 있으므로 구현 착수 시 다시 확인합니다. 키·시크릿·계정 이메일은 공유 문서에 넣지 않습니다.

공식 [프로젝트 생성 및 SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project)은 NaviHelper·Vehicle State를 별도 신청 없이 사용할 수 있고, 준비 중인 기능은 신청 필요 상태로 바뀐 후 신청하도록 안내합니다. 따라서 현재 준비 중인 항목에 대한 승인 일정을 개발 완료의 전제로 잡지 않습니다. 지원 일정 문의는 문안을 준비할 수 있지만 사용자 지시 없이 발송하지 않습니다.

**사용 가능 표시는 기기 실행 성공이 아닙니다.** 현재 main에는 NaviHelper 3.0.5 진단용 probe가 있고 빌드 증거가 있습니다. 목적지·ETA 기기 수신과 시간 의미, 표준 위치·STT·TTS의 Pleos 기기 동작은 미검증입니다. 콘솔 준비 중 상태만으로 모든 온디바이스 실행까지 불가능하다고 단정하지 않습니다. Pleos STT의 ON_DEVICE/SERVER/HYBRID는 서로 다른 조건을 가지므로 [모드 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)를 대조해 별도 검증합니다.

## 기능별 구현 경로

[Pleos SDK 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)에 따라 같은 기능의 표준 API를 우선 검토합니다. 표준 API가 목표 기기에서 요구를 충족하면 계속 사용하며, SDK 공개 자체를 교체 의무로 삼지 않습니다.

| 기능 | 먼저 검증할 경로 | 미지원·실패 시 흐름 | Pleos 추가 조건 |
| --- | --- | --- | --- |
| 목적지·경로·ETA | 기존 NaviHelper probe 유지. 목적지와 Trip 콜백·ETA 의미 확인 | 자동 조회 불가 표시, UX 제한이 허용할 때 사용자가 목적지/도착 예정 시각 입력. 개발 빌드만 명시적 MOCK 선택 | 이미 사용 가능. #1의 기기 증거와 서버 계약 검토 후 제품 연결 |
| 현재 위치 | Android `LocationManager`의 provider·사용 설정·권한·관측값 확인 | 목적지 주변 검색은 검증된 목적지 좌표로 진행 가능. 현재 위치가 필요한 검색은 보류하거나 정차 입력으로 전환 | FusedLocation 제공 상태·권한·정확도/수명 요구를 검증한 뒤 별도 adapter 추가 |
| 사용자 음성 입력 | Android `SpeechRecognizer` 서비스 및 한국어 지원 확인, 명시적 시작/종료 | 미지원/권한 거부를 안내. 정차·UX 허용 시 입력, 개발 빌드는 가상 발화 선택 | Pleos STT 모드·등록 조건·실기기 품질 확인 후 추가 |
| 사용자 음성 안내 | Android `TextToSpeech` 초기화·한국어 음성·완료/오류 확인 | 허용된 화면에 상태 표시. 음성 안내가 필수인 흐름은 보류하며 실패를 숨기지 않음 | Pleos TTS 사용 조건·음성 품질·서비스 상태 확인 후 추가 |
| 매장 AI 전화 | 서버 전화 공급자 채널의 STT/대화/TTS, #3 범위 | 기본은 MOCK, 실제 전화는 승인된 PoC만 수행 | 차량 STT/TTS 권한과 독립. 차량 마이크로 매장 통화 음성을 얻는 구조를 전제로 하지 않음 |

목적지 좌표와 현재 위치는 별개입니다. 목적지 주변 검색에 현재 위치 권한을 무조건 요구하지 않습니다. 공개 시범 좌표는 개발용 입력이며 실제 차량 위치로 표시하거나 일반 사용자의 권한 거부를 우회하는 값으로 사용하지 않습니다. Google Play services 설치를 가정하거나 Google의 Fused Location과 Pleos FusedLocation을 같은 SDK로 취급하지 않습니다.

## 어댑터 경계와 상태 제안

다음 이름과 상태는 **향후 앱 내부 설계 제안**입니다. 현재 존재하는 클래스·공개 API 필드·서버 enum으로 해석하지 않습니다. 구현 PR에서 실제 제품 모듈·호출자·테스트와 함께 확정합니다. 진단용 `android/probe`에는 제품 음성/예약 흐름을 섞지 않습니다.

| 경계 제안 | 책임 | 구현체 후보 |
| --- | --- | --- |
| `NavigationContextAdapter` | 목적지·경로·ETA 관측, 변경 구독/해제 | NaviHelper, 사용자 입력, 개발 전용 MOCK |
| `CurrentLocationAdapter` | 현재 위치·정확도·관측 시각, 취소/해제 | Android LocationManager, 추후 Pleos, 개발 전용 MOCK |
| `UserSpeechInputAdapter` | 가용성 확인, 일회 발화 시작/취소, 중간/최종 결과 | Android SpeechRecognizer, 추후 Pleos STT, 개발 전용 MOCK |
| `UserSpeechOutputAdapter` | 언어 가용성, 발화/중단, 완료/오류 | Android TextToSpeech, 추후 Pleos TTS, 개발 전용 MOCK |

제품 ViewModel/Repository는 이 경계를 사용하고 SDK 객체를 예약·추천 로직에 전달하지 않습니다. 구현체 선택은 앱 구성에서 명시하며, 실행 중 오류가 나도 몰래 MOCK이나 다른 외부 서비스로 전환하지 않습니다. `CarUxRestrictions`의 초기 조회·변경 반영·미확인 처리는 #25 기준을 유지합니다.

- 상태는 가용성 확인 전 `UNKNOWN`, 사용 가능 `AVAILABLE`, 서비스 미지원 `UNSUPPORTED`, 권한 거부 `PERMISSION_DENIED`, 일시 단절 `UNAVAILABLE`, 요청 시간 초과 `TIMEOUT`, 취소 `CANCELLED`를 구분하는 안으로 시작합니다. 실패를 정상 빈 결과나 성공으로 바꾸지 않습니다.
- 유효 결과에 provider, 실제/사용자 입력/모의 구분, 관측 시각, 요청/세대 식별자를 보존합니다. 위치에는 정확도와 원 관측 시각을 유지하고 허용 나이·정확도 기준은 사용 사례별로 정해 테스트합니다. 마지막 위치를 새 관측으로 갱신하지 않습니다.
- 목적지 변경·요청 취소·화면 종료 뒤 도착한 이전 콜백은 폐기합니다. 같은 listener를 해제하고 녹음/위치 구독/음성 출력을 종료합니다. 세부 callback thread는 선택한 API 문서로 확인합니다.
- 중간 STT 결과는 예약·문의·비용 승인의 근거가 아닙니다. 최종 인식도 사용자 확인을 거치며, TTS 재생 완료를 매장 수락이나 예약 완료로 취급하지 않습니다.
- 앱 내부 provider 표기를 현재 서버의 `source` enum에 임의로 보내지 않습니다. 특히 서버의 `source=PLEOS` 거부와 LIVE 시작 차단은 유지합니다. 서버 연결 시 #1·#16·#26 및 API 담당이 기존 계약과 대조합니다.
- 실제 음성·좌표·연락처는 fixture/log/공개 캡처에 넣지 않습니다. 가용성 조회와 실제 마이크 활성화·네트워크 요청을 구분합니다. Android STT도 원격 인식 구현일 수 있으므로 데이터 전송 경로를 확인하고, 기기 지원 실패를 외부 유료 음성 API 자동 호출로 처리하지 않습니다.

## 구현 순서와 담당 경계

| 단계 | 담당/연결 이슈 | 완료 조건 |
| --- | --- | --- |
| 0. 공통 계획 공유 | 이번 문서 #30, tiemo0708 | 기능별 경로·상태·근거·인수 기준, PLAN/STATUS/HANDOFF 연결 |
| 1. 차량 경계 검증 | csjws, #1 | 기존 NaviHelper 기기 로그·ETA 시간 의미·권한/단절 확인. 현재 위치가 필요한 사용 사례와 표준 provider 확인은 별도 완료 조건으로 조율 |
| 2. 제품 경계 연결 | soyeong03, #25/#26, 관련 API 담당 | 주행 제한·명시적 실제/모의 표시, 기존 인증/미리보기 계약 유지. 새 내부 provider는 서버 계약 합의 후 연결 |
| 3. 사용자 음성 adapter 구현 | 신규 런타임 담당·실제 후속 이슈는 팀 배정 필요 | Android STT/TTS 가용성·한국어·권한·취소/늦은 콜백·개인정보 경로 검증. #3의 전화 업무를 자동 이관하지 않음 |
| 4. 매장 전화 PoC | zito-git, #3, 서버 #4 협업 | 공급자 선정·테스트 번호·승인·비용 상한·서명/중복/UNKNOWN 대조. 차량 SDK를 기다리지 않고 MOCK 계약 개발 가능 |
| 5. Pleos adapter 추가 | 해당 기능 담당, 별도 구현 이슈 | 제공 상태·필요 신청/승인·버전/모드 확인, 같은 계약 검사와 기기 비교 후 명시적 선택 |

이 문서는 기존 기능 이슈를 닫거나 담당자를 변경하지 않습니다. #30의 종료 범위는 설계 문서 공유까지이며 위 런타임 구현 완료를 포함하지 않습니다. 새 개발 브랜치는 실제 후속 이슈 번호로 만들고 `type/<번호>-<topic>`·Conventional Commits·PR/CI 규칙을 적용합니다.

## 인수 시나리오와 증거

아래는 **앞으로 실행할 검사**이며 통과 기록이 아닙니다. adapter 구현 시 단위 테스트를, 실제 연결 시 기기 증거를 추가합니다. MOCK 검사는 가상 입력만 쓰고 외부 호출 없이 실행합니다.

| ID | 조건 | 기대 동작 |
| --- | --- | --- |
| SDK-01 | SDK 신청 없이 사용 가능 표시 | 콘솔 상태로 기록. 기기 확인 전 성공 표기 없음 |
| SDK-02 | 위치 provider 없음/설정 꺼짐/권한 거부 | 원인 구분, 현재 위치 검색 보류, 가상 위치 자동 주입 없음 |
| SDK-03 | 과거 위치·부정확한 위치·목적지 변경 | 유효성 판정 후 거부/재확인, 이전 요청 결과 폐기 |
| SDK-04 | STT 서비스 없음/한국어 미지원/권한 거부 | 안내와 허용된 정차 입력, 외부 음성 API 자동 호출 없음 |
| SDK-05 | 중간 STT·사용자 취소·종료 후 최종 콜백 | 명령 실행 없음, 취소/오래된 결과 폐기 |
| SDK-06 | TTS 엔진/한국어 없음·재생 오류 | 표시/보류, 예약 성공이나 음성 전달 완료로 오인하지 않음 |
| SDK-07 | 주행 제한 활성·상태 미확인 | 제한 화면 잠금, 정차 입력 대체도 제한을 준수 |
| SDK-08 | 실제 기능과 개발 MOCK을 함께 시연 | 기능별 실제/모의 표시, 원 출처와 시각 유지, 자동 MOCK 전환 없음 |
| SDK-09 | Pleos 구현체 추가·권한 취소·서비스 단절 | 동일 내부 계약 유지, 설정된 구현체/오류 표시, 구독·자원 해제 |
| SDK-10 | 차량 음성과 전화 채널이 함께 동작 | 별도 세션/승인 유지, 매장 음성은 공급자 채널에서만 처리 |

실행 증거에는 날짜·앱/SDK/OS/이미지 버전·선택한 구현체·콘솔 상태·권한·시나리오·기대/실제·판정을 남깁니다. 빌드, MOCK 테스트, emulator 실행, 실제 차량 실행, 실제 통화를 각각 표시합니다. 제출 문구도 확인한 단계까지만 사용합니다.

## 공식 근거

모두 2026-11-09 확인했습니다. 아래 문서의 API 존재는 Pleos 목표 기기에서의 서비스 제공·한국어 지원·권한 부여를 보장하지 않습니다.

- [Pleos SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api): 표준 우선, 제조사 특화 확장.
- [Pleos SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project): 준비 중/사용 가능/신청 필요 구분.
- [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager): 플랫폼 위치 provider·설정/권한 확인.
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer): 서비스 가용성 확인, 마이크 권한, 원격 처리 가능성, 자원 해제. 항상 켜 둔 연속 인식 용도로 쓰지 않음.
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech): 초기화·언어 가용성·발화 완료/오류 확인.
- [Pleos STT 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro), [오디오 데이터 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data): 앱 소유 PCM 입력과 차량/통화 오디오 접근 권한을 혼동하지 않음.
