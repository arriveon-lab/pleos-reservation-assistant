# Telkit·LiveKit 선택형 전화 시험

기본 MOCK 도구에 실제 SIP 음성을 연결하는 독립 adapter입니다. 제품 Spring 서버의 `app.mode=LIVE` 차단·공개 `/api/v1` 계약·Android는 변경하지 않습니다. 테스트용 예약 정보를 사용하고 실제 연락처는 비공개 설정에만 둡니다.

## 흐름

브라우저 예약 승인 → `/api/live/dial` → SQLite 통화 claim → LiveKit named agent dispatch → Telkit SIP 발신 → 식당 음성 → STT → LLM·예약 도구 → TTS → 식당으로 전달합니다. Telkit은 음성 통신을 담당하며 텍스트는 LiveKit STT가 생성합니다.

수신은 Telkit → LiveKit inbound trunk → 개별 room/agent dispatch → 허용한 발신자·trunk 확인 → 이름·날짜·기존 시간 식별 → 변경 요청 저장 순서입니다. 사용자 승인 전에는 원 예약을 바꾸지 않고, 승인 뒤 별도 실제 재확인 발신을 해야 변경을 적용합니다.

## 설치와 비공개 설정

Python 3.12 환경에서 확인했습니다. 저장소 루트에서 실행합니다.

```bash
python3.12 -m venv work/voice-venv
work/voice-venv/bin/pip install -r prototypes/phone_agent/voice/requirements.txt
cp prototypes/phone_agent/voice/.env.example work/phone-live.env
chmod 600 work/phone-live.env
```

실제 키는 채팅·Git에 넣지 않습니다. 환경 파일은 단순 `KEY=VALUE`만 읽으며 셸 표현식을 실행하지 않습니다. `PHONE_ENV_FILE`로 경로를 바꿀 수 있습니다. `work/`와 `data/`는 Git 제외입니다. 비밀값 파일은 권한 600, Telkit 파일 폴더는 700을 사용합니다.

- LiveKit Cloud 프로젝트의 WSS URL·전용 API key/secret·SIP URI를 입력합니다. 키 생성과 계정 약관은 사용자 승인 후 진행합니다.
- Telkit 본인인증·카드 등록과 번호 구매는 공식 계정 흐름에서 완료합니다. 구매 전 번호·30일 갱신 비용을 명시해 승인받습니다. 이 adapter는 계정 생성·번호 구매·카드 등록·SIP credential 회전을 하지 않습니다.
- 구매한 번호와 ID, Telkit API key, 발급한 SIP username/password를 설정합니다. 기존 로컬 `work/telkit-private/`의 `workspace-key.json`·`sip-credential.json`·`livekit-setup.json`도 읽을 수 있습니다.
- `PHONE_ALLOWED_NUMBERS`는 승인된 국내 테스트 번호의 E.164 목록이고, `PHONE_RESTAURANT_NUMBERS`는 식당 ID→허용 번호의 JSON 객체입니다. 임의 브라우저 입력으로 발신 대상을 정하지 않습니다. 실제 번호 예시는 문서·fixture에 넣지 않습니다.
- `PHONE_MAX_SECONDS`는 30–600, `PHONE_DAILY_CALLS`는 1–10이며 기본은 180초·하루 1회입니다. Telkit 자체 최대 통화 시간도 별도로 설정합니다. 첫 실제 시험에서는 두 경계를 60초로 제한했습니다.

## SIP 준비와 읽기 전용 확인

```bash
work/voice-venv/bin/python -m prototypes.phone_agent.voice doctor
work/voice-venv/bin/python -m prototypes.phone_agent.voice plan
work/voice-venv/bin/python -m prototypes.phone_agent.voice setup --apply
```

`doctor`는 키·번호 없이 준비 상태만, `plan`은 SIP 설정안과 선택한 번호를 표시합니다. 설정안은 공개 공유하지 않습니다. `setup --apply`만 외부 설정을 변경합니다. 이름으로 기존 자원을 대조해 inbound/outbound trunk·dispatch rule을 만들고 각 ID를 즉시 비공개 journal에 기록한 후 선택한 Telkit 번호의 연결을 갱신합니다. 기존 자원이 다르면 자동 덮어쓰지 않습니다. 중단 후 journal과 콘솔을 대조하고 재실행합니다. 동시 setup 프로세스는 지원하지 않습니다.

TLS·SRTP 필수, Telkit 공식 IP와 승인된 수신 번호를 제한합니다. setup 후 실제 번호 연결 상태를 조회해 반영을 확인해야 합니다. trunk 생성이나 SIP 설정 성공만으로 수신 통화 성공으로 판단하지 않습니다.

## 실제 시험 실행과 중단

대상·목적·횟수·비용·시간 상한을 승인받은 뒤에만 비공개 파일의 `PHONE_LIVE_ENABLED=true`를 설정하고 두 프로세스를 실행합니다. 음성 SDK가 설치된 같은 Python과 같은 DB를 사용해야 합니다.

```bash
work/voice-venv/bin/python -m prototypes.phone_agent --port 8765
work/voice-venv/bin/python -m prototypes.phone_agent.voice.worker start
```

[로컬 화면](http://127.0.0.1:8765)에서 예약 생성·조건 승인 후 **실제 테스트 전화 걸기**를 누릅니다. 가상 통화 버튼은 외부 전화를 걸지 않습니다. HTTP 화면과 worker health endpoint는 loopback에 바인딩합니다. 이 컴퓨터와 worker가 실행되는 동안만 AI가 연결되며 Cloud 상시 배포는 아닙니다.

중단할 때 worker에 Ctrl+C를 보내고 환경 파일을 `PHONE_LIVE_ENABLED=false`로 되돌립니다. 실행 중 통화는 worker shutdown과 provider 최대 시간으로 종료를 제한합니다. 승인 1회를 실행한 뒤 자동 반복·재발신하지 않습니다. 로컬 DB의 일일 상한은 UTC 날짜 기준이며 DB 삭제·복제나 다른 클라이언트 발신까지 포함하는 사업자 과금 상한이 아닙니다. 사업자 콘솔의 잔액·한도도 확인합니다.

## 기록과 결과 판정

- 승인된 예약 사본·버전·소유 세션·식당 번호 mapping을 검증하고 원자적으로 통화 작업을 선점합니다. 외부 API는 DB transaction 밖에서 호출합니다.
- worker는 room·일회성 nonce·SIP 참가자를 확인합니다. inbound는 허용 번호와 정확한 trunk를 확인한 뒤 한 예약을 식별해야 합니다.
- 모델에는 확인된 예약 이름·일시·필수 조건만 전달합니다. dispatch metadata에 사용자 조건·키를 넣지 않습니다.
- 실제 발화는 LIVE event와 call ID로 로컬 저장합니다. SDK handoff는 대화 근거로 취급하지 않습니다. 원본 음성 녹음은 생성하지 않습니다.
- 결과는 해당 통화의 최근 식당 발화 인용, 이름·일시·인원·추가 비용 0원·필수 조건을 대조합니다. 이름도 실제 인식된 발화에 있어야 합니다. 발화 의미의 해석은 STT/LLM에 의존하므로 완전한 의미 검증을 보장하지 않습니다.
- 미확인·타임아웃·단절은 UNKNOWN이고 자동 재발신하지 않습니다. 실제 결과를 가상 버튼으로 확정하지 않습니다. 진행 중 전체 삭제를 차단합니다.
- 수신 통화의 변경 요청은 사용자 확인 대기이며, 승인 후 실제 재확인 결과가 일치해야 새 시간을 적용합니다.
- 로컬 텍스트·예약은 세션 전체 삭제로 지울 수 있지만 공급자 보관 데이터 삭제와 별개입니다. 공급자 보관 정책·운영 알림·자동 만료·crash 이후 외부 통화 대조·outbox는 후속입니다.

## 모델과 검증 상태

의존성은 `livekit-agents[silero]==1.8.11`, `livekit-api==1.2.2`입니다. LiveKit Inference의 AssemblyAI Universal 3.6 Pro(ko) → Google Gemma 4 31B → Cartesia Sonic 3(ko)를 사용합니다. OpenAI API 키는 이 경로에 필요하지 않습니다. 공급자와 모델·음성 ID는 `.env.example`에서 바꿀 수 있습니다.

2026-11-09 실제 발신 1회: Telkit 연결 46초·60원 차감, 잔액 2,940원·추가 카드 결제 0원. 번호 1개 구매에는 기존 크레딧 7,000원이 사용됐으며 30일마다 갱신됩니다. LiveKit은 무료 Build 프로젝트로 시험했고 유료 플랜 전환은 하지 않았습니다. 시험 후 LiveKit 콘솔의 Build 플랜·다음 청구액 $0.00을 확인했습니다. 사용량 집계는 지연될 수 있습니다.

한국어 음성 왕복과 도구 결과 저장을 확인했지만 사용자 청취 피드백은 어색함·발음 개선 필요였습니다. 실제 기록에 예약자 이름 인식 불일치·식당 직원 호칭 오류·임의 회신 번호가 있어 최초 CONFIRMED 시험 결과를 UNKNOWN으로 정정했습니다. 이름 대조·통화별 근거를 보강하고 날짜/시간 읽기·짧은 응답·회신 번호 생성 금지 지침을 수정했습니다. 기본 목소리는 한국어 원어민 Jiwoo로 조정했으며 **변경 후 실제 청취·모델 호환은 아직 미검증**입니다. 추가 전화나 합성 요청을 실행하지 않았습니다.

실제 수신·변경 재확인과 전체 계획 인수는 미검증입니다. 제품 연동·운영 배포·Pleos 기기 검증과 분리합니다. 모델 품질을 가상 SDK 테스트 통과로 대체하지 않습니다.

```bash
work/voice-venv/bin/python -m unittest prototypes.phone_agent.voice.test_voice -v
```

CI는 고정 SDK 설치 뒤 가짜 client·임시 DB로 21개 테스트를 실행합니다. 실제 전화·모델 호출·공급자 자원 생성은 하지 않습니다. 기본 MOCK의 36개 테스트는 SDK 없이 계속 실행됩니다.

공식 근거: [Telkit LiveKit 연결](https://telkit.io/docs/connect/livekit.md), [번호·요금](https://telkit.io/docs/numbers/pricing.md), [LiveKit 음성 에이전트](https://docs.livekit.io/agents/start/voice-ai/), [SIP 참가자](https://docs.livekit.io/reference/telephony/sip-participant/), [Cartesia 한국어 Jiwoo](https://www.cartesia.ai/voices/library/jiwoo-service-specialist-15628352-2ede-4f1b-89e6-ceda0c983fbc).
