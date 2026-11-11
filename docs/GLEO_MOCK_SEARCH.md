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

## 2026-11-11 입력 전용 명령 실제 발화 성공

SET_PARTY_SIZE 추가: 실제 Gleo 발화→성인2/아이2 반영을 사용자 보고·앱 수신/반영 로그·화면으로 확인했다. 검색/예약 자동 실행 없음. 기존 검색/열기 명령 유지. [새 검증·고정 APK·잔여](GLEO_PARTY_INPUT.md). 이전 음성 미확인은 당시 이력이며 이번 입력 전용 성공과 구분한다.
