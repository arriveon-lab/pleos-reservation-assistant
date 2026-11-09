# Pleos 차량용 AI 예약 비서 — 작업 인계

## 2026-11-09 통화 품질 피드백과 공식 레퍼런스 반영 (#43)

- 세 번째 시험의 사용자 피드백은 중간 끊김·긴 응답·부자연스러운 발음과 이름 재확인 보정 필요입니다. 왕복 대화가 됐다는 관측과 청취 품질 통과는 구분합니다. 통화 녹음이 없으므로 원래 발음의 객관적 비교는 할 수 없습니다. STT 텍스트에서는 김미학→김예약 재확인을 관측했습니다.
- [LiveKit 음성 프롬프트](https://docs.livekit.io/agents/start/prompting/)에 따라 기본 한 문장·질문 하나, 틀린 항목만 재질문, 일시/인원 반복과 복합 질문 축소를 반영했습니다. [AssemblyAI 파라미터](https://docs.livekit.io/agents/models/stt/assemblyai/)의 keyterms_prompt에 해당 통화에서 승인·식별된 이름 한 개만 전달합니다. 등록 이름으로 텍스트를 강제 치환하지 않으며 명시적 수락/근거 검사를 유지합니다. [과도한 keyterms의 편향 위험](https://www.assemblyai.com/blog/when-keyterm-prompting-backfires) 때문에 전역 이름 목록을 만들지 않습니다.
- [LiveKit 재생 완료 대기](https://docs.livekit.io/agents/multimodality/audio/)에 따라 종료 도구가 직전 발화를 기다린 뒤 종료 안내·room 삭제를 수행하게 했습니다. 중간 끊김의 원인을 모두 설명하는 증거는 아니며 interrupted 메시지 계수를 추가했습니다. [대화 턴 조정](https://docs.livekit.io/agents/logic/turns/tuning/)의 VAD·interrupt·endpointing은 단계별 비교 후보입니다. 새 유료 모델이나 adaptive 옵션은 켜지 않았습니다.
- [Cartesia 발음 사전](https://docs.cartesia.ai/build-with-cartesia/capability-guides/custom-pronunciations)은 IPA/sounds-like를 지원하지만 [phoneme 안내](https://docs.cartesia.ai/build-with-cartesia/capability-guides/phonemes)의 표는 영어 예시 중심입니다. 한국어 이름 IPA를 임의로 넣지 않았습니다. 재확인 때 실제 이름의 한글 음절을 짧게 읽는 지침만 추가했고 발음 사전/후보 음성 A/B는 별도 청취 검증 항목입니다.
- 로컬 SDK/가짜 client 26개·MOCK/HTTP 36개 통과. 추가 실제 전화·음성 합성·인식 호출 없이 코드와 문서만 보강했습니다. 이 변경 후 품질은 미검증이고 실전화 실행은 꺼 둔 상태입니다.

## 2026-11-09 진단 포함 세 번째 실전화 (#43)

- 사용자 재시험 요청으로 같은 승인 대상에 1회 추가 발신했습니다. 최대 60초·Telkit 72원(VAT 별도)·LiveKit 무료 범위를 유지했습니다. 실제 연결 50초·60원 차감, 잔액 2,832원, 누적 3회·통신비 168원입니다. agent_finished로 종료했고 활성 prototype room 0개·실제 실행 비활성화·worker 종료를 확인했습니다.
- 이번에는 양쪽 SIP/agent 음성 트랙과 입력 966프레임(48.3초), STT 중간 6개·최종 5개, 모델 오류/전사 timeout 0개를 확인했습니다. 첫인사 후 대화가 이어지고 잘못 인식한 예약자 이름을 다시 물어 정확한 이름을 받았습니다. 실제 입력 진단이 동작했으며 원본 통화 음성은 녹음하지 않았습니다.
- 상대의 명시적인 예약 확정 발화가 없어 결과는 UNKNOWN입니다. 대화 왕복을 예약 성공으로 세지 않습니다. 두 번째 통화의 media-timeout은 이번에 재현되지 않았지만 별도 통신 설정을 수정한 것은 아니므로 근본 원인 해결·재발 방지·전체 품질 통과로 판단하지 않습니다.
- 제품 코드 변경은 없고 기존 로컬 MOCK 36개·음성 SDK 24개 통과 증거를 유지합니다. 이번에는 실전화 관측·잔액·실행 중단과 문서 일치만 확인했습니다. 실제 수신/변경 재확인은 미검증이며 추가 발신 승인은 남아 있지 않습니다.

## 2026-11-09 재시험 실패와 다음 진단 (#43)

- 사용자 “다시 테스트” 요청으로 기존 대상·목적·60초/72원/무료 LiveKit 범위에서 1회 추가 발신했습니다. Telkit 31초·48원, 잔액 2,892원입니다. 누적 2회·통신비 108원. 추가 발신 승인 소비 후 다시 LIVE=false·worker 종료·활성 room 0개를 확인했습니다.
- 첫인사는 들렸으나 상대 말에 응답하지 않았다는 피드백입니다. LIVE event는 agent 1개·restaurant 0개. Cloud SIP 상세는 FAILED / media-timeout이며 단순 SDK disconnect는 수신자 종료 증거가 아닙니다. 두 번째 로컬 통화 기록에 providerReview를 붙이고 sip_media_timeout으로 정정했으며 예약 UNKNOWN을 유지했습니다. 실제 상대 RTP 미도착 원인은 아직 해결하지 못했습니다.
- 전화 없이 무료 범위의 가상 한국어 TTS→STT 1회는 정상: 음성 2.61초, 김예약을 포함한 최종 문장 일치. 새 Jiwoo와 STT 자체 경로가 동작함을 확인했으며 실제 전화망 복구나 전체 음질 통과로 확대하지 않습니다. 이 진단은 모델 호출 0회가 아닙니다. 실제 통화 녹음은 하지 않았습니다.
- 입력 프레임/음량·STT 이벤트 수·timeout/오류·RTC 종료 코드만 저장하는 진단과 인식 없음 안내 1회를 추가했습니다. 원본 음성·중간 발화는 진단 저장 대상이 아닙니다. 가짜 SDK 24개·기본 36개 통과. 이 보강의 실제 전화 검증은 미실행입니다.
- 다음은 새 승인 범위를 준비한 뒤 SIP 미디어 경로 확인입니다. provider RTP/SDP 관측 없이 무조건 시간 제한을 늘리거나 SRTP를 끄지 않습니다. allowed_addresses IP 제한은 Telkit 공식 안내상 LiveKit 프로젝트에서 지원팀 활성화 전에는 저장만 되므로 강제 적용됐다고 가정하지 않습니다. 기존 수신 caller allowlist·trunk ID·예약 식별 검사를 유지합니다. 외부 지원 문의는 보내지 않았습니다.

## 2026-11-09 Telkit·LiveKit 선택형 연결 인계 (#43)

- 같은 `feat/43-phone-agent-prototype` / Draft PR #45에 선택형 음성 adapter를 추가했습니다. [실행/중단·비공개 설정](../prototypes/phone_agent/voice/README.md)을 따릅니다. SDK는 Git 제외 `work/voice-venv`, 설정은 권한 600 `work/phone-live.env`·`work/telkit-private/`, 로컬 DB는 `data/`입니다. 비밀값·번호를 공개 문서나 테스트로 옮기지 않습니다.
- 사용자 승인 후 Telkit 본인인증/카드·070 번호 1개 구매·SIP credential, LiveKit 무료 Build 프로젝트·전용 key·SIP trunk/dispatch를 연결했습니다. 번호 구매 크레딧 7,000원·추가 카드 0원, 30일 갱신입니다. 사용자 승인 대상 1회·60초/72원 상한 시험에서 실제 연결 46초·60원, 잔액 2,940원입니다. 계정 재생성·번호 재구매·credential 회전은 하지 않습니다.
- 한국어 통화와 결과 저장은 확인했으나 최초 CONFIRMED 결과는 이름 인식 불일치로 UNKNOWN 정정했습니다. 식당 직원 호칭·임의 회신 번호·음질 문제도 발견했습니다. 이름 대조·통화별 evidence·handoff 처리, 한국어 지침과 Jiwoo 음성 설정을 보강했습니다. 추가 합성/실전화 없이 자동 테스트 21개를 검증하며 변경 후 실제 청취는 미검증입니다.
- 승인 1회는 소비했습니다. `PHONE_LIVE_ENABLED=false`, worker 종료 상태입니다. HTTP 화면은 SDK venv로 실행하며 DB의 실제 결과를 조회할 수 있습니다. 추가 실발신·수신 시험은 대상·비용·횟수·시간 승인을 준비해야 합니다. 자동 재발신이나 상시 서버 배포를 수행하지 않았습니다.
- 실제 수신·사용자 변경 승인 후 재확인 통화는 미실행입니다. 호출자/trunk/예약 식별과 변경 도구는 가짜 client 테스트까지만 확인했습니다. 제품 인증/outbox·공급자 상태 대조·자동 보관 만료·알림·Pleos 통합은 후속이고 #3/#4/#26은 OPEN으로 유지합니다. 기본 MOCK 36개 테스트와 제품 LIVE 차단을 유지합니다.

## 2026-11-09 전화예약 프로토타입 최초 단계 인계 (#43)

- `feat/43-phone-agent-prototype`는 main abd9aa4에서 분리했습니다. [전화예약 프로토타입](../prototypes/phone_agent/README.md)을 `python3 -m prototypes.phone_agent --port 8765`로 실행합니다. 외부 패키지 없이 가상 데이터와 로컬 DB를 사용하며 제품 서버·타 담당 코드를 가져오지 않았습니다.
- 사용자 요청은 빠르게 계획을 테스트할 수 있는 버전을 만드는 것입니다. 계정·번호는 준비 전이며 Telkit llms/phone 안내를 확인했으나 실제 가입·구매·실전화는 수행하지 않았습니다. 번호 구매 전 LiveKit SIP 수신 환경과 구체적 비용/실행 범위를 준비해야 합니다.
- 36개 행동/HTTP 테스트, Python 컴파일·JS 문법 검사와 브라우저 자동 시연·수동 UNKNOWN 흐름을 확인했습니다. 원격 CI 결과는 PR에서 확인합니다. 새 독립 프로토타입 CI는 외부 API를 실행하지 않습니다.
- 다음은 #3/#4/#26과 제품 계약·실행 범위 합의, 실제 LiveKit 음성 에이전트·STT/LLM/TTS 및 SIP 연결입니다. 현재 도구는 결정된 가상 응답이며 AI 음성 성능을 검증하지 않습니다. 자동 만료·변경 UNKNOWN 해결·운영 인증/outbox·기기 알림은 별도 구현입니다. 계획 PR #42의 인수 전체 완료나 기존 이슈 #3/#4/#26 종료로 세지 않습니다.

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
5. 장소 사업자와 시범 지역, 전화 업체 선택. Twilio/CLOVA/KT 비교와 검증 계획은 INTEGRATIONS. 계정·협의 번호·예상 비용/상한·명시적 승인 없이는 실발신 금지.
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
