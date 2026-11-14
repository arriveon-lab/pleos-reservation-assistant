## 2026-11-11 음성 앱 실행 성공·간헐적 듣기 실패

사용자가 음성 앱 실행 성공을 확인했다. 기기 로그에서도 04:49:02.951(기기 시각)의 destinationvalidation MAIN 실행 호출 UID가 Gleo의 사용자10 UID와 일치했다. OPEN_ARRIVEON callback 경로가 아니라 Gleo 일반 앱 실행 경로의 성공이다. 사용자 보고상 간헐적 실패는 듣기 창 미표시/미인식이며, 정확한 원인과 반복 성공률은 미확인이다.

`scripts/open-gleo.command`를 추가했다. Finder에서 더블클릭하거나 저장소 루트에서 실행하면 기본 음성 서비스가 Gleo인지 확인한 뒤 듣기 세션을 요청한다. 창이 보인 뒤 “어라이브온 앱 열어줘”라고 말한다. 현재 Mac 입력 장치는 AirPods다. 이 도구는 ArriveOn 직접 실행·음성 주입·자동 재시도·예약 작업을 하지 않으며, STT나 호출어 인식 문제를 해결했다고 간주하지 않는다. 여러 기기는 ANDROID_SERIAL로 지정한다.

초기 macOS Bash 빈 배열 오류를 수정한 후 실행 exit0 확인. 셸 문법·diff 검사 통과. APK 코드 변경 및 Gradle 재검증 없음. 실제 음성 성공과 안정성 미검증을 구분한다.

# Gleo 인원 입력 → MOCK 추천 검색

2026-11-11, 사용자 요청 범위: 음성으로 성인/아이 수를 입력하고 기존 MOCK 추천 검색을 시작한다. 실제 전화·예약·실제 내비 목적지/ETA 연결은 포함하지 않는다.

## 앱 실행 이름·스키마 보완

기존 APK label은 `Pleos 예약 비서 · MOCK`, invokeWord는 `어라이브온`으로 서로 달랐다. 표시 이름을 `어라이브온 · MOCK`으로 맞추고 인원 없이 앱만 여는 `OPEN_ARRIVEON` action을 추가했다. 이름 불일치가 Gleo 장소 오류의 확정 원인이라고 단정하지 않는다. 기존 테스트/예약 설치들은 변경하지 않는다.

- bare/prefixed OPEN action은 화면만 열고 검색/예약 요청을 만들지 않는다. callback functionName=OPEN_ARRIVEON, success는 앱 열기만 의미한다.
- verifyAndroid PASS. 기기 GLEO fixture에 OPEN→검색 상태/예약 journal 불변 검사를 추가해 기존 검색/제한 검사와 함께 PASS.
- 새 APK SHA256 `4e57c8d9806d83e369f32e00ca79dc19431dc94fcea2b9a8b1e780237a6828af`. 설치 후 Gleo ami-sdk 로그에서 대상 패키지와 새 한글 이름이 함께 나타나는 것을 관측했다. 내장 등록 DB를 강제 수정하지 않았다.
- 이름/OPEN 수정 후 실제 음성에서는 같은 장소 정보 오류와 앱 실행 요청0을 확인했다. Gleo 프로세스만 재시작한 뒤 새 이름·OPEN/SEARCH 스키마 재로딩을 관측했다. 이후 재시험은 스키마 선택 목록만 확인됐고 앱 실행은 아직 미확인이다. 직접 명령 통과를 음성 통과로 취급하지 않는다.

## 구현과 계약

- `android/app/src/main/gleo/schema.json` 템플릿을 빌드 시 실제 applicationId로 치환해 APK의 `assets/schema.json`에 포함한다. 별도 검증 설치도 패키지가 일치한다.
- `invokeWord=어라이브온`, function `SEARCH_MOCK_RESTAURANTS`, activity 방식, adults/children number 필수. 설명은 영어, 예시 없음. 앱은 정수 성인1–20/아이0–10만 허용한다. 누락·문자열 숫자·소수·범위 밖·추가 JSON 필드는 거부한다.
- 공식 예제의 ACTION_PARAM JSON과 본문에 설명된 개별 extras를 받는다. 두 형식 동시 제공은 거부한다. 실제 Gleo4.2.0.15는 action을 `<packageName>.SEARCH_MOCK_RESTAURANTS`로 보내는 것을 관측해 bare/prefixed action 모두 수신한다.
- 인증·플랫폼 입력 허용 확인 후 화면의 가상 목적지/기존 필수 조건과 함께 기존 #53 MOCK 경로를 사용한다. 앱 시작 준비는 최대5초(공식 callback timeout7초 안)만 기다리며 화면 종료/실패 시 폐기한다. 인증 자동 생성·기존 예약 journal 변경 없음.
- 요청 중 중복과 같은 인원15초 중복을 거부한다. Activity 재생성 시 소비된 Intent를 재실행하지 않는다. 입력 제한/UNKNOWN에서는 검색하지 않으며 전송 직전 기존 transport의 제한 검사도 유지한다.
- callback은 공식 APP_ACTION_RESPONSE 형식, success/fail을 사용한다. success는 **검색 시작**만 뜻하며 결과 확정/문의/예약을 뜻하지 않는 문구로 응답한다. 기존 Gleo 패키지에만 callback을 보낸다.
- 외부 Activity 호출은 Gleo 전용 인증으로 간주하지 않는다. 공개 Intent로도 호출할 수 있으므로 동일 입력·주행·MOCK 검사만 허용하고 예약 권한으로 확장하지 않는다.

## 공식 자료와 기기 관측의 구분

- [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Intent 및 callback 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [callback 상태와7초 timeout](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [스키마 전달·승인](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)

문서상 스키마 승인 후 통합 활성화 절차가 있다. 로컬 에뮬레이터에서 명령 생성이 관측된 것을 배포 승인 또는 공동 인수 완료로 해석하지 않는다.

## 검증 기록

- Pleos Connect2.1.0/API34/4GB/Cold Boot, Gleo4.2.0.15. 기존 실행의 `-no-audio`를 제거한 뒤 음성 최종 결과·기본 질문 정상 응답을 확인했다.
- verifyAll PASS, UX109개(신규 파서2개 포함), Android Lint 오류0/경고13. 서버 기존 태스크 UP-TO-DATE와 신규 UX 실행을 구분한다.
- 별도 `ai.pleos.assistant.mock.destinationvalidation`에 설치. 주입 Intent→성인2/아이2→MOCK 후보3곳, 중복·소수·제한/UNKNOWN 주입 차단·테스트 예약 journal 불변 PASS. 이 검사는 실제 Gleo 발화 검증과 별개다.
- `captures/gleo/destination-GLEO-valid-counts-results.png`, `destination-GLEO-restricted.png`에 화면 증거. 원 설치 journal SHA256은 기존 `beb02ab84b57d492514117bb1e0eb7d069c3f4036e0fd1a22e5f29967b1f13b3`와 일치.
- 실제 음성 첫 시도: Gleo 최종 인식→명령 생성→패키지 접두사 action으로 startActivity까지 관측. 처음 bare action 필터만으로는 앱 수신 미확인. 접두사 수신 수정 후 재검증 결과는 아래에 기록한다.

- 접두사 수정 후 verifyAndroid PASS, 최종 APK SHA256 `639fc4e94e62c62c73253e990157106b960966d166fa910df2b5c043d6a8ec4d`. 동일 기기 GLEO fixture 재실행 PASS. 실제 외부 `am start --user 10` 접두사 action으로 성인2/아이2·후보3곳 UI 확인 PASS(직접 주입).
- MOCK smoke PASS. 원본 실행 결과는 Git 제외 work/gleo-check/에 보관한다. 수정 후 음성 재시험에서는 최종 인식 이벤트만 관측됐으며 앱 수신/검색 완료는 아직 확인하지 못했다.
- 실제 음성 시험에서 선택된 발화나 인증 원문은 증거 파일에 저장하지 않았다. Gleo 자체 로그에는 민감 정보가 있을 수 있어 전체 logcat을 공유하지 않는다.

## 미완료

실제 Gleo 호출의 최종 검색 화면은 미확인. 접두사 수정 후 사용자도 앱이 열리지 않았다고 보고했으며, 추가 시도에서도 인식 이벤트만 확인되어 실제 음성 E2E 통과로 표시하지 않는다. 스키마 공식 승인·공동 인수·실차 주행·실제 내비 목적지/ETA·전화/예약 음성 승인·release 검증은 미완료. 이 변경으로 기존 이슈를 닫지 않는다.


## 2026-11-11 저녁 재점검 — 음성 입력 단계 미완료

- 연결된 Pleos Connect 2.1.0/API34 사용자10에서 destinationvalidation APK의 assets/schema.json을 직접 읽어 invokeWord=어라이브온, OPEN_ARRIVEON/SEARCH_MOCK_RESTAURANTS와 실제 패키지 일치를 재확인했다. 다른 테스트 설치에는 스키마가 없어 동일 invokeWord 중복은 관측되지 않았다.
- PackageManager에서 패키지 접두사 OPEN 명령이 ReservationActivity로 해석됨을 확인했다. 이번 확인은 resolve 검사이며 실제 음성 앱 실행 성공이 아니다.
- Gleo 4.2.0.15의 RECORD_AUDIO 허용, 기본 voice_interaction_service 연결, 에뮬레이터 hw.audioInput=yes 및 -no-audio 미사용을 확인했다. 배포된 emulator_controller.proto의 getMicrophoneState로 realAudioEnabled=true를 확인했다. Mac 입력 장치는 AirPods였다.
- Gleo 세션 정리 중 IntentReceiverLeaked 로그를 관측해 Gleo 프로세스만 재시작했다. 이 오류를 음성 실패의 확정 원인으로 단정하지 않는다. cmd voiceinteraction show 후 mShown=true와 세션 연결을 확인했다. 사용자 화면의 마이크 버튼 위치는 확인하지 못했으므로 버튼이 있다고 안내하지 않는다.
- 재시작 이후 최종 인식 이벤트는 있었지만 요청한 앱 이름/열기 의도와 ArriveOn OPEN_COMMAND_RECEIVED/APP_OPENED는 관측하지 못했다. 사용자는 음성 인식 실패를 보고했다. 호스트 전달 설정 확인을 실제 발화 인식 또는 음성 실행 통과로 표시하지 않는다.
- APK·제품 코드·예약 데이터·인증·권한 변경 없음. 빌드/테스트는 재실행하지 않았다. 실제 전화·예약·유료 API 실행 없음. 다음 확인은 Gleo 창이 사용자에게 보이는지, 해당 세션 동안 발화가 정확히 인식되는지부터 진행한다.

## 2026-11-11 입력 전용 명령 실제 발화 성공

SET_PARTY_SIZE 추가: 실제 Gleo 발화→성인2/아이2 반영을 사용자 보고·앱 수신/반영 로그·화면으로 확인했다. 검색/예약 자동 실행 없음. 기존 검색/열기 명령 유지. [새 검증·고정 APK·잔여](GLEO_PARTY_INPUT.md). 이전 음성 미확인은 당시 이력이며 이번 입력 전용 성공과 구분한다.


### 2026-11-12 새 UI Gleo 연결 및 시연 초기화

OPEN/SET_PARTY_SIZE/SEARCH_MOCK_RESTAURANTS의 bare·패키지 접두사 action을 FinalReservationActivity로 이동했다. singleTop 새 Intent 수신, 재생성 시 재검색 방지, 5초 준비 대기, 정차/연결/미확인 요청/중복 검색 차단, 범위 성인1–8·아이0–6을 적용했다. 검색은 현재 새 UI의 명시된 MOCK 서울숲 시연값이며 실제 내비 목적지/실제 식당 성공으로 표시하지 않는다. EC2 `/pleos-mock/api/v1/status`는 MOCK/FICTIONAL_FIXTURES/externalCallsEnabled=false다. 실제 전화 `/phone-test` 별도 화면은 그대로이며 새 UI의 실제 발신 연결로 간주하지 않는다.

사용자 지시에 따라 시연의 ‘처음부터 다시 시작’은 완료(CONFIRMED/DECLINED) MOCK phone journal을 비우고 입력값을 초기화하며 인증은 유지한다. UNKNOWN/진행 중/저장 실패는 차단한다. core 재시작·초기화·UNKNOWN/저장 실패 테스트 추가, verifyAll PASS. 후속 결과화면 연결/기존 pending reconcile 버튼 추가 후 verifyAndroid PASS. EC2 앱 업데이트, 기존 pending을 같은 인증으로 reconcile, 초기화 버튼 실행, 외부 명령 주입 성인3/아이1→새 UI MOCK 후보3 확인 PASS. 실제 발신/승인 실행0. 증거 `work/gleo-final/injected-search.png`.

Gleo 듣기 창은 열었으며 실제 발화 결과는 아직 대기 중이다. Intent 주입 통과와 실제 음성 통과를 구분한다. 이슈/PR 게시·push 없음.


### 2026-11-12 음성 명령 분리

사용자의 발화가 듣기 종료 전에 잘리는 현상에 맞춰 OPEN은 입력 화면만 열고, 다음 별도 SEARCH/SET_PARTY 명령을 받도록 안내·스키마·응답을 정리했다. 앱 열기만으로 검색하지 않는다. 화면에 두 단계와 짧은 발화 예시를 표시했다. verifyAndroid PASS, 업데이트 설치 후 OPEN→별도 SEARCH(3/1) Intent 주입으로 새 UI 후보3 확인 PASS. 실제 발화 두 단계는 아직 미검증이며 Gleo 듣기 시간 변경 또는 자동 연속 듣기 구현으로 간주하지 않는다. 실제 전화/예약 승인 없음.


## 2026-11-12 Gleo 듣기 직후 종료 진단

사용자가 “운전석에서 듣는 중” 직후 종료를 보고했다. 현재 기기 로그에서 Gleo Spokesman 초기화가 `InvalidConfigError` / `ErrorNotExists`로 반복 실패하며 앱 내부 `files/assets.zip/SpeechRecognitionConfig.dat`를 열지 못하는 것을 확인했다. 기기 시각 05:11:48–05:29:49 사이 동일 오류 10,207건을 관측했다(기기는 PST를 출력하므로 이 시각을 KST로 해석하지 않는다).

최근 세션은 기기 시각 05:28:09.045 ready → 05:28:14.010 closeStream → 05:28:15.013 onHide로 약5초 후 닫혔다. STT timeout reset 로그가 있지만 timeout 발생 자체는 확인하지 못했으므로 무음 시간초과로 단정하지 않는다. 모델 설정 누락은 확인된 이상이며, 해당 세션 종료와의 직접 인과는 미확인이다. 앞선 일반 MAIN 경로 음성 앱 실행 성공은 유지한다.

제품 APK/권한/계정/데이터 변경·초기화 없음. Gleo 모델 자산 준비/설치 상태를 공식 복구 절차와 대조하는 것이 다음 진단이다. 임의 모델 파일 주입이나 앱 데이터 삭제는 수행하지 않았다. 원문 발화·인증 로그는 증거 문서에 보관하지 않는다.


## 2026-11-12 Gleo 모델 복구 점검 — 재실행 검증 대기

- Gleo4.2.0.15는 시스템 앱이며 모델 제공 앱4.1.21-coral-aosp가 별도로 설치되어 있다. 모델 제공 APK 안의 공식 spokesman1.17.1 ZIP에서 SpeechRecognitionConfig.dat(4827bytes)을 확인했다. 원본 부재가 아니라 사용자10의 Gleo 로딩 경로에서 파일을 못 여는 상태다. 저장공간은 약6.9GB 여유였다.
- 모델 제공 서비스 시작/프로세스 재생성과 설치 APK에서 확인한 MODEL_PROVIDER_START receiver를 통한 재배포 요청을 수행했다. 공식 spokesman URI 읽기 권한 전달은 관측했지만 Gleo 설치 완료는 미확인. 별도 Speech Service는 기존 버전보다 낮다며1.17.1 갱신을 건너뛰었다. 버전 제한을 우회하지 않았다.
- 이미 공유된 공식 spokesman URI/원본 버전으로 Gleo 수신 receiver 호출을 시도했으나 ADB 연결 종료로 결과 미확인. 모델 설치 성공으로 표시하지 않는다. 계정/앱 데이터 삭제, root, 권한 변경, 임의 모델 수정은 없었다.
- 22:38:35 KST macOS crash report에서 에뮬레이터 HangDetector 종료(EXC_BAD_ACCESS),22:38:41에는 qemu 직접 실행의 라이브러리 로딩 실패를 확인했다. 이 오류와 모델 복구 요청의 인과는 미확인. 사용자에게 다른 작업의 종료/재시작 여부를 확인 중이며 다음은 공식 emulator 실행기로 재기동 후 모델 로딩과 음성 확인이다.


## 2026-11-12 Gleo 모델 전달 차단 원인 확인 — 복구 미완료

공식 실행기로 기존 AVD를 Cold Boot해 boot_completed=1/user10까지 복원했다. 재부팅 이후 `dumpsys activity broadcasts`에서 모델 제공 앱→Gleo의 SHARE_SPEECH_FILE 전달이 `skipped by policy at enqueue: Permission Denial`로 차단되는 것을 확인했다. 수신 Activity가 아니라 `ai.umos.ambientai/.AmbientAiReceiver`의 `ai.umos.gleo.permission.GLEO_RECEIVER` 요구사항이다. 제공 앱 Manifest에는 이 권한 요청이 없다. shell로 동일 수신 요청을 해도 같은 권한 거부이며 브로드캐스트 명령의 result=0은 실제 수신 성공이 아니다.

설치 조합: Gleo4.2.0.15 / 모델 제공4.1.21-coral-aosp / 내장 spokesman1.17.1 / Pleos Connect2.1.0 arm64 revision2. 모델 ZIP에는 SpeechRecognitionConfig.dat 원본이 있다. 따라서 모델 전달 실패의 직접 원인은 설치된 패키지 간 권한 불일치로 확인됐으며, 모든 간헐적 음성 세션 종료를 이 하나의 원인으로 확정하지 않는다.

공식 SDK 배포 XML의 최신 arm64 이미지도 revision2로 현재 설치본과 동일했다. Python 인증서 저장소 오류 후 시스템 curl의 인증서 검증을 유지해 조회했다. 업데이트로 적용할 새 revision은 확인하지 못했다. 제공 서비스 재시작·공식 재배포·Cold Boot 후에도 초기화 오류가 남아 완전 복구는 미완료다. 제조사 서명된 수정 모델 제공 APK 또는 수정 이미지가 필요하다. 임의 재서명/권한 우회/모델 파일 강제 주입/앱 데이터 삭제는 수행하지 않았다.

공급자에 전달할 최소 재현: 위 버전의 사용자10 Cold Boot → 모델 제공 앱의 SHARE_SPEECH_FILE → GLEO_RECEIVER Permission Denial → Gleo Spokesman SpeechRecognitionConfig.dat ErrorNotExists 반복. 정상 기대는 공식 제공자가 권한을 갖고 모델을 전달해 Gleo 초기화가 성공하는 것이다. 이 문서는 문의용 근거이며 외부 문의를 전송한 것은 아니다. 증거 원본 APK·검사 출력·배포 XML은 Git 제외 work/gleo-repair에 보관한다. 기존 작업 파일은 보존했고 제품 코드·빌드 변경은 없다.
