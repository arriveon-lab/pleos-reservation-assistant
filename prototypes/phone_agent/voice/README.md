# Telkit·LiveKit 선택형 전화 시험

기본 MOCK 도구에 실제 SIP 음성을 연결하는 독립 adapter입니다. 제품 Spring 서버의 `app.mode=LIVE` 차단·공개 `/api/v1` 계약·Android는 변경하지 않습니다. 테스트용 예약 정보를 사용하고 실제 연락처는 비공개 설정에만 둡니다.

단일 Linux 서버 배포는 [배포 안내](../deploy/README.md)를 따릅니다. API·worker가 같은 영속 DB를 쓰며 기본은 MOCK, worker는 명시적인 LIVE 검증 후 실행합니다.

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

TLS·SRTP 필수, Telkit 공식 IP와 승인된 수신 번호를 설정합니다. 다만 Telkit 공식 안내상 LiveKit의 IP 제한은 지원팀이 프로젝트에서 활성화하기 전에는 저장만 되므로 강제 적용을 가정하지 않습니다. 승인된 caller allowlist·worker의 trunk/예약 식별 검사는 별도로 유지합니다. setup 후 실제 번호 연결 상태를 조회해 반영을 확인해야 합니다. trunk 생성이나 SIP 설정 성공만으로 수신 통화 성공으로 판단하지 않습니다.

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

의존성은 `livekit-agents[silero]==1.8.11`, `livekit-api==1.2.2`, `livekit-plugins-noise-cancellation==0.3.3`입니다. NC 플러그인은 LiveKit Cloud용이며 패키지 라이선스는 LiveKit 서비스 약관을 참조합니다. 공급자 바이너리를 저장소에 복사하지 않습니다. LiveKit Inference의 AssemblyAI Universal 3.6 Pro(ko) → Google Gemma 4 31B → Cartesia Sonic 3(ko)를 사용합니다. OpenAI API 키는 이 경로에 필요하지 않습니다. 공급자와 모델·음성 ID는 `.env.example`에서 바꿀 수 있습니다.

2026-11-09 실제 발신 1회: Telkit 연결 46초·60원 차감, 잔액 2,940원·추가 카드 결제 0원. 번호 1개 구매에는 기존 크레딧 7,000원이 사용됐으며 30일마다 갱신됩니다. LiveKit은 무료 Build 프로젝트로 시험했고 유료 플랜 전환은 하지 않았습니다. 시험 후 LiveKit 콘솔의 Build 플랜·다음 청구액 $0.00을 확인했습니다. 사용량 집계는 지연될 수 있습니다.

한국어 음성 왕복과 도구 결과 저장을 확인했지만 사용자 청취 피드백은 어색함·발음 개선 필요였습니다. 실제 기록에 예약자 이름 인식 불일치·식당 직원 호칭 오류·임의 회신 번호가 있어 최초 CONFIRMED 시험 결과를 UNKNOWN으로 정정했습니다. 이름 대조·통화별 근거를 보강하고 날짜/시간 읽기·짧은 응답·회신 번호 생성 금지 지침을 수정했습니다. 기본 목소리는 한국어 원어민 Jiwoo로 조정했으며 **당시 변경 후 실제 청취·모델 호환은 미검증**입니다. 추가 전화나 합성 요청을 실행하지 않았습니다.

이후 재시험 1회를 실행했지만 첫인사 뒤 사용자 발화가 인식되지 않았고 LiveKit SIP 상세가 `media-timeout`으로 종료됐습니다. Telkit 31초·48원, 잔액 2,892원, 누적 통신비 108원입니다. 실제 예약은 UNKNOWN을 유지합니다. 상대 RTP 미수신의 구체 원인과 복구는 아직 해결하지 못했습니다.

추가 전화 없이 가상 한국어 음성을 직접 TTS→STT로 1회 처리한 결과, 새 목소리 2.61초가 생성되고 김예약을 포함한 문장이 정확히 인식됐습니다. 모델 자체 경로의 확인이며 SIP 전화 복구 증거가 아닙니다. 무료 Build 범위에서 실행했고 추가 결제·유료 전환은 없습니다.

입력 프레임 수·음량 최대값·STT 중간/최종 이벤트 수·timeout/오류·RTC 종료 코드를 통화 작업의 로컬 진단에 기록합니다. 진단에는 원본 음성·발화·개인 연락처를 넣지 않습니다. 첫인사 뒤 12초 동안 최종 인식이 없고 상대가 말하는 중이 아니면 재발화 안내 1회를 합니다. 이 진단/안내 추가 후 실제 전화는 하지 않았습니다. [media timeout 공식 진단](https://docs.livekit.io/reference/telephony/troubleshooting/)에 따라 RTP·SIP와 모델 오류를 구분하며 단순 참가자 disconnect를 상대방의 종료로 단정하지 않습니다.


세 번째 실전화에서는 양방향 대화와 이름 재확인을 확인했습니다. 입력 966프레임/48.3초, STT 최종 5개·모델 오류 0개, 통화 50초·60원, 잔액 2,832원입니다. 상대의 명시적 예약 확정 발화가 없어 UNKNOWN을 유지했습니다. 중간 media-timeout은 이번에 재현되지 않았으나 통신 설정을 수정해 근본 원인을 해결한 상태는 아닙니다. 누적 발신 3회·통신비 168원, 실제 실행은 다시 비활성화·worker 종료 상태입니다.

네 번째 실전화에서는 사용자 발화의 이름을 정확히 인식했고, 전반적으로 괜찮지만 첫 소개 뒤 예약 요청이 끊겨 잘 들리지 않았다는 피드백을 받았습니다. 입력 1,094프레임/54.7초·STT 최종 7개·오류/timeout 0개·중단된 AI 메시지 1개를 기록했습니다. 조건 확인 전 시간 상한으로 종료돼 UNKNOWN입니다. Telkit 56초·72원, 잔액 2,760원, 누적 4회·통신비 240원이며 실행은 다시 비활성화하고 worker를 종료했습니다. 첫 소개·예약 질문만 중단되지 않도록 하고 이름 전달/잘 못 들었을 때의 재안내 지침을 보강했습니다. 이 후속 보강은 자동 검사까지이며 실제 전화로 재검증하지 않았습니다. OpenAI 연결/A/B 비교는 아직 실행하지 않았습니다.

다섯 번째 실전화는 첫 안내가 잘 들렸다는 피드백을 받았지만 약한 끊김과 반복 질문이 남았습니다. 입력 1,030프레임/51.5초·STT 최종 6개·오류/timeout 0개·중단 메시지 1개, Telkit 53초·72원, 잔액 2,688원·누적 5회/312원입니다. 도구의 UNKNOWN과 음성의 예약 완료 안내가 달라 실제 결과는 UNKNOWN을 유지했습니다. 같은 통화의 이름 포함 예약 요청 직후 명시적 수락을 제한적으로 인정하고, 결과 저장 뒤 고정 종료 음성과 StopResponse로 후속 모델 안내를 대신하도록 수정했습니다. 로컬 67개 테스트를 통과했으며 후속 수정의 실전화 효과는 미검증입니다. LIVE 비활성화·worker 종료 상태이며 자동 재발신하지 않았습니다.

2026-11-10 여섯 번째 시험은 Telkit 39초·48원, 잔액 2,640원·누적 6회/360원입니다. 첫 안내·이름 전달 후 비용 질문이 중단됐고 입력 767프레임/38.35초·STT 최종 10개·모델 오류/전사 timeout 0개·중단 메시지 4개입니다. 조건 확인과 결과 도구 호출 전 SIP 연결이 종료돼 UNKNOWN입니다. 따라서 문맥 수락/저장 결과 기반 종료의 실전화 효과는 미검증입니다. 이 여섯 번째 통화 당시에는 production VAD 기반 중단이었고 전용 소음 억제/주화자 분리와 adaptive 기능을 추가하기 전이었습니다. 실행은 다시 비활성화했고 worker·활성 room을 종료했습니다.

실제 수신·변경 재확인과 전체 계획 인수는 미검증입니다. 제품 연동·운영 배포·Pleos 기기 검증과 분리합니다. 모델 품질을 가상 SDK 테스트 통과로 대체하지 않습니다.

```bash
work/voice-venv/bin/python -m unittest prototypes.phone_agent.voice.test_voice prototypes.phone_agent.voice.test_resilience -v
```

CI는 고정 SDK 설치 뒤 가짜 client·임시 DB로 59개 테스트를 실행합니다. 실제 전화·모델 호출·공급자 자원 생성은 하지 않습니다. 기본 MOCK의 36개 테스트는 SDK 없이 계속 실행됩니다.

공식 근거: [Telkit LiveKit 연결](https://telkit.io/docs/connect/livekit.md), [번호·요금](https://telkit.io/docs/numbers/pricing.md), [LiveKit 음성 에이전트](https://docs.livekit.io/agents/start/voice-ai/), [SIP 참가자](https://docs.livekit.io/reference/telephony/sip-participant/), [Cartesia 한국어 Jiwoo](https://www.cartesia.ai/voices/library/jiwoo-service-specialist-15628352-2ede-4f1b-89e6-ceda0c983fbc).

## 자연스러움·이름·끊김 보강 근거

세 번째 통화에서도 사용자는 긴 응답·중간 끊김·발음 어색함을 보고했습니다. 아래 보강을 네 번째 통화에서 시험했고 전반적인 청취 피드백은 괜찮았으나 첫 예약 요청의 끊김이 남았습니다. 이후 추가한 첫 발화 재생 보호와 이름 전달 지침은 아직 실전화 미검증입니다.

| 항목 | 공식 근거 | 반영/검증 상태 |
| --- | --- | --- |
| 답변 길이 | [LiveKit prompting](https://docs.livekit.io/agents/start/prompting/) | 기본 한 문장·질문 하나, 틀린 항목만 재확인·반복 축소 지침 |
| 이름 인식 | [AssemblyAI keyterms](https://docs.livekit.io/agents/models/stt/assemblyai/), [편향 주의](https://www.assemblyai.com/blog/when-keyterm-prompting-backfires) | 현재 통화의 승인된 이름 1개만 STT 힌트. 이름 강제 치환·전역 목록 없음 |
| 종료 시 끊김 | [Speech playout](https://docs.livekit.io/agents/multimodality/audio/) | 도구 이전 발화 재생 완료→종료 안내 완료→room 삭제 순서, 가짜 SDK 순서 검사 |
| 중간 끊김 | [Turn tuning](https://docs.livekit.io/agents/logic/turns/tuning/) | 중단·오감지·응답 지연 계측. 2026-11-10 후속 적용은 아래 항목 참고 |
| AI 이름 발음 | [Cartesia 사전](https://docs.cartesia.ai/build-with-cartesia/capability-guides/custom-pronunciations), [IPA 범위](https://docs.cartesia.ai/build-with-cartesia/capability-guides/phonemes) | 요청 시 실제 이름의 한글 음절 재확인. 한국어 IPA/사전 효과는 미검증, 사전 생성·추가 합성 없음 |

원본 통화 음성을 보관하지 않아 실제 발음과 STT 오류를 녹음으로 비교할 수는 없습니다. 진단/인식 텍스트만으로 모든 중간 끊김의 원인을 단정하지 않습니다. 테스트 상한은 다음 실전화 승인 시에도 별도로 유지합니다.


## 문맥 수락과 종료 음성

반복 확인을 줄이기 위해 새 예약에서 직전의 이름 포함 예약 요청과 식당의 제한된 명시적 수락을 함께 대조합니다. 맥락 없는 '네', 가능 여부만 확인한 답, 조건부/정정/다른 이름, 이전 통화 근거는 확정하지 않습니다. 변경 재확인은 기존 명시적 발화 기준을 유지합니다. 날짜·시간·인원·비용·필수 조건의 최종 해석은 여전히 STT/LLM의 영향을 받습니다.

결과 도구가 저장을 마치면 저장 상태에 맞는 짧은 고정 음성→재생 완료→종료 순서를 사용합니다. [LiveKit StopResponse](https://docs.livekit.io/reference/python/livekit/agents/llm/)로 도구 뒤 모델이 UNKNOWN을 성공으로 바꾸어 안내하는 경로를 차단합니다. 저장 실패는 재확인할 수 있게 반환하고 자동 종료하지 않습니다. 다섯 번째 통화 후 코드/가짜 SDK 검증까지이며 실제 효과는 다음 승인된 시험에서 확인해야 합니다.

## 소음·대기·장애 처리 — 2026-11-10 적용

- 입력에 Krisp **NC**를 연결하고 Silero 기준을 0.3에서 플러그인 기본 0.5로 조정했습니다. `PHONE_NOISE_SUPPRESSION=false`로 필터 없이 비교할 수 있습니다. 유료 주화자 분리 모델은 사용하지 않습니다.
- `PHONE_INTERRUPTION_MODE=adaptive`를 기본으로 명시합니다. 맞장구/끼어들기는 공급자 판별을 사용하고, 단순 “네” 문자열을 버리지 않습니다. `vad`로 비교·복귀할 수 있습니다. 현재 Build 프로젝트의 개발 허용량 안에서 시험하며 유료 플랜으로 자동 전환하지 않습니다. [한도](https://docs.livekit.io/deploy/admin/quotas-and-limits/)를 실행 전에 확인해야 합니다.
- 최소 중단 발화 0.5초/최소 단어 0개, 무전사 오감지 복구 2초, 발화 경계 보호를 명시합니다. 발화 종료 판단의 audio TurnDetector와 끼어들기는 별개입니다. endpointing 최소 0.5초/최대 3초로 늦은 전사를 기다리고, 선행 생성을 끄고 사용자 턴 검사 후 응답을 만듭니다. 이 값의 실제 효과는 아직 미검증입니다.
- 짧은 AI 소개만 중단 불가로 하고 입력 음성은 보존합니다. 예약 질문은 중단할 수 있어 정정을 들을 수 있습니다. 중단된 조각은 완료 발화 상한에 포함하지 않고, 완료 발화 최대 12개와 기존 통화 시간/비용 상한을 함께 적용합니다.
- 완결된 “잠시만요/확인하고 올게요” 요청에는 모델 응답을 막고 한 번 대기 안내합니다. 20초 후 한 번 확인하고 이후 12초 무응답이면 종료합니다. 정정이 섞인 발화는 이 필터로 버리지 않습니다. 일반 무응답은 12초 후 한 번 재안내하고 다음 무응답에서 종료합니다. 말하는 중에는 재안내하지 않습니다.
- STT/LLM/TTS SDK 재시도는 최대 1회로 제한합니다. 최종 STT/LLM 오류에 대해 통화당 한 번만 복구합니다. STT는 세션의 agent를 갱신해 스트림을 다시 만들고, LLM은 부분 응답이나 도구를 재실행하지 않고 다시 말해 달라고 안내합니다. 두 번째 최종 오류나 TTS 오류는 사전 준비 음성으로 종료합니다.
- [안내 WAV](audio/README.md)는 개인정보 없는 로컬 합성 음성입니다. TTS 없이 PCM을 재생하며, 확정/실패를 임의로 선언하지 않습니다. 재생 실패에도 room 종료를 시도하고 저장된 예약 결과는 보존합니다. 미디어 연결 자체가 끊기면 안내 전달은 보장할 수 없습니다.
- 명확한 음성사서함/ARS 문구와 통화 거부 표현은 제한된 문장 규칙으로 감지해 미확인 종료합니다. **LLM 기반 AMD·자동 DTMF 메뉴 탐색 구현은 아닙니다.** 직원의 정상 문장을 오분류하지 않는지 한국어 실전화 검증이 필요합니다.
- 발화 저장·결과 도구의 SQLite 작업을 단일 작업 스레드에 순서대로 보내 오디오 이벤트 루프에서 분리했습니다. 식당 발화 저장 뒤 결과 검증이 실행되고 종료 작업은 대기 중 저장 뒤에 정리합니다. 통신 실패는 구조화된 SIP 코드만 남기며 통화 중/무응답/음성 조건 불일치/공급자 한도를 구분합니다. 원문 오류·번호·헤더를 진단에 저장하지 않습니다.
- 진단에 구성요소별 오류·복구 시도·대기·무응답 안내·오감지·응답 지연을 추가했습니다. RTP 패킷 손실·지터 분석, 운영 공급자 결과 자동 대조는 여전히 후속입니다.

검증: 기본 36개 + 선택형 음성 56개, 총 92개 로컬 테스트를 통과했습니다. 실제 SDK 생성자·NC 옵션·WAV 디코딩도 네트워크 없이 확인했습니다. CI는 두 음성 테스트 모듈을 실행합니다. **이 구현 검증 단계에는 추가 실전화/모델 호출이 없었습니다.** 이후 일곱 번째 실전화 결과는 아래에 기록합니다. 소음 제거 효과·한국어 맞장구 정확도와 전체 음질/지연은 아직 통과하지 못했고 LIVE는 비활성 상태입니다.

[소음·예외 대응 검토](../../../docs/PHONE_AGENT_RELIABILITY.md)는 적용 전 근거와 후속 검증 시나리오를 함께 보존합니다.


2026-11-10 일곱 번째 시험은 소음/adaptive 적용 버전으로 55초·72원, 잔액 2,568원·누적 7회/432원입니다. 대기 요청/후속 확인을 각각 1회 관측했지만 사용자는 음질 끊김과 발화 미인식을 보고했습니다. STT WebSocket 연결 단절과 SDK 재시도 1회, 입력 1,075프레임/53.75초, STT 최종 4개를 확인했습니다. 60초 상한으로 결과 저장 전 종료돼 UNKNOWN입니다. 음질 끊김의 Telkit/전화망/LiveKit/로컬 구간별 원인은 미확정입니다. worker 중단·활성 room 0개·LIVE 비활성 상태이며 추가 발신은 없습니다.


2026-11-10 첫 EC2/여덟 번째 실전화는 36초·48원, 잔액 2,520원·누적 8회/480원입니다. SDK STT/LLM/TTS 오류 0개·입력 34.8초·STT 최종 5개·AI 중단 메시지 3개, agent_finished 종료와 UNKNOWN 저장을 확인했습니다. 사용자는 스피커폰에서 발화 중/조용히 들을 때 모두 끊김을 보고했습니다. 에코는 원인 후보이며 수화기 비교는 아직 하지 않았습니다. 컨테이너 수명 전체 최대 메모리 약 807.5MiB·OOM kill 0회였지만 CPU/네트워크/출력 지연을 배제하지 않습니다. 시험 후 LIVE=false·worker 중단이며 추가 발신은 없습니다. 상세 근거는 docs/STATUS.md의 여덟 번째 시험 기록을 따릅니다.
