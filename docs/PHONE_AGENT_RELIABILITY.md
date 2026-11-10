# 전화예약 에이전트: 소음·예외 상황 대응 검토

확인일: 2026-11-10 (KST). 대상: #43의 Telkit·LiveKit 로컬 전화 프로토타입, `livekit-agents==1.8.11`. 아래 원 검토는 적용 전 상태를 기록한다. 같은 날 사용자 요청으로 일부 기능을 적용했으며 변경 범위는 다음 절과 [실행 안내](../prototypes/phone_agent/voice/README.md)에 구분한다. 실전화 검증은 추가하지 않았다.

## 후속 적용 범위

NC 입력 필터, adaptive 끼어들기, 입력 보존·짧은 소개, 발화 종료 대기 조정, 대기/무응답 감독, STT/LLM 1회 복구와 TTS 없는 종료 음성, 완료 발화만 상한 계수, 저장 작업 스레드 분리, SIP 실패 분류와 추가 진단을 적용했다. 기본 36개·음성 56개(총 92개) 로컬 검사가 통과했다. 기존 시간·비용·승인·UNKNOWN 경계는 유지한다.

유료 주화자 분리, LLM 기반 AMD와 자동 DTMF 메뉴 탐색, RTP 품질 계측, Telkit 종료 결과의 운영 자동 대조는 미적용이다. ARS는 제한된 문구 감지 시 미확인 종료만 한다. 코드 적용 단계에는 추가 외부 모델 요청·전화·요금제 변경이 없었다. 이후 일곱 번째 실전화에서 대기 처리와 STT 연결 단절 1회를 관측했다. 여덟 번째 EC2 시험은 SDK 오류 0개였지만 중단 메시지 3개와 청취 끊김이 남았다. 스피커폰 에코는 원인 후보이며 수화기 비교 전에는 확정하지 않는다. 입력 필터와 adaptive의 전체 청취 효과와 음질 문제의 구간별 원인은 미확정이다. 상세 관측은 [STATUS](STATUS.md)를 따르며 LIVE는 비활성 상태다.

## 판단과 우선순위

먼저 입력 소음 억제와 끼어들기 판단을 분리하고, 질문·대기·복구 상태를 명시해야 한다. 모델 교체만으로 이 문제들이 해결된다고 볼 근거는 없다. 다음 순서를 제안한다.

1. 배경 소음 억제 + 현재 VAD 민감도 비교 + 늦은 전사/처리 지연 계측.
2. 맞장구·실제 정정 구분, 중단한 질문의 복구, 대화 중 침묵/대기 처리.
3. STT/LLM/TTS 장애별 제한된 복구와 사전 준비 안내 음성, 통신 결과 대조.
4. 필요하면 통화용 주화자 분리와 IVR/음성사서함 분류를 추가 평가.

이 순서는 프로젝트에 대한 제안이다. 공급자가 보장한 최적 설정이나 한국어 식당 통화 성능 순위가 아니다.

## 적용 전 구현에서 확인한 점

| 항목 | 현재 상태와 의미 |
| --- | --- |
| 입력 처리 | [worker](../prototypes/phone_agent/voice/worker.py)의 Silero VAD `activation_threshold=0.3`. 설치 플러그인 기본값 0.5보다 낮다. 작은 목소리 감지와 오감지 사이의 교환 관계를 시험해야 하며, 이것이 실제 중단 원인이라는 증거는 없다. 전용 소음 필터는 미연결이다. |
| 끼어들기 | production worker 로그에서 adaptive 비활성화를 확인했다. VAD 중단 기본 기준은 0.5초, 최소 단어 수 0이다. 전사 없는 오감지 뒤 재생 복구는 기본 활성화되어 있다. |
| 발화 종료 판단 | 끼어들기와 다른 기능이다. 설치 SDK `AgentSession`은 미지정 시 `inference.TurnDetector()`를 사용한다. 공식 문서상 `start` 실행은 로컬 `v1-mini`가 기본이다. 따라서 전체 대화 처리가 단순 VAD뿐이라고 설명하면 부정확하다. 실제 예측 성공률은 계측하지 않았다. [Turn detector](https://docs.livekit.io/agents/logic/turns/turn-detector/) |
| 첫 안내 | 날짜·시간·인원을 포함한 첫 발화를 중단 불가로 설정했다. SDK의 `discard_audio_if_uninterruptible=True` 기본값은 그동안 들어온 음성을 버릴 수 있다. 안내 보호가 긴 정정 발화 누락으로 이어지지 않는지 검증해야 한다. [중단 옵션](https://docs.livekit.io/agents/logic/turns/) |
| 침묵 | [diagnostics](../prototypes/phone_agent/voice/diagnostics.py)는 첫 최종 전사 전 12초 동안 응답이 없을 때 한 번 재안내한다. 대화 중 무응답, 명시적 대기 요청, 보류 음악을 각각 처리하는 상태는 없다. |
| 장애 | 모델 오류·전사 timeout 횟수만 센다. 구성요소별 복구 분기·백업 모델·TTS 없이 재생할 오류 안내는 없다. |
| 질문 횟수 | assistant 메시지 8개에 통화를 종료한다. 중단된 발화 조각도 계수에 포함될 수 있으므로, 소음 때문에 업무가 끝나기 전에 상한을 소비하는지 확인해야 한다. 시간/비용 상한 자체는 유지한다. |
| 예약 결과 | [workflow](../prototypes/phone_agent/voice/workflow.py)에 같은 통화 근거·승인 조건 대조, UNKNOWN 보존, 중복 발신 제한이 있다. 저장 상태에 맞춘 종료 음성도 구현됐지만 최근 실전화는 그 결과 저장 경로까지 진행되지 않았다. |

최근 여섯 번째 통화에는 AI 발화 중단 4회 외에 **전사가 턴 확정 뒤 도착했다는 경고**, **이벤트 루프 약 210ms 정체 경고**, **발화 스케줄링 일시 정지 중 입력 건너뜀 경고**가 있었다. 이는 조사할 단서이며 잡음·네트워크·우리 DB 코드 중 하나를 원인으로 확정하지 않는다. 원시 로그와 통화 내용은 비공개 파일에 유지한다.

## 상황별 대응 제안과 근거

| 상황 | 우리 서비스의 대응 제안 | 공식 근거·적용 한계 |
| --- | --- | --- |
| 차량·환풍기·식당 잡음 | agent 입력단에 배경 소음 억제를 먼저 적용해 비교한다. 무음에 가까운 입력을 임의 문장으로 해석해 예약 도구를 호출하지 않는다. | LiveKit의 Krisp NC/QUAIL_L은 비음성 잡음 억제용이다. 전화에는 브라우저 마이크 설정 대신 agent/SIP 입력 처리가 필요하다. [Noise cancellation](https://docs.livekit.io/transport/media/noise-cancellation/) |
| 옆 사람의 말 | 통화용 주화자 분리를 후보로 둔다. 직원이 다른 직원에게 수화기를 넘길 때 새 화자를 제거하지 않는지도 시험한다. | Krisp VIVA telephony 등은 경쟁 음성을 줄이는 기능이다. 화자 분리는 본인 인증이 아니며 완전한 분리를 보장하지 않는다. 같은 [공식 문서](https://docs.livekit.io/transport/media/noise-cancellation/) |
| AI 도중 짧은 “네/응” | adaptive로 중첩 발화를 판별한다. 질문을 마친 직후의 “네”는 실제 답으로 처리한다. 문자열 목록으로 모든 “네”를 버리지 않는다. | acoustic 기반 판별과 발화 시작/끝 경계 보호가 제공된다. 한국어 가능으로 안내하지만 한국어 예약 통화 품질은 별도 실험이 필요하다. [Adaptive interruption](https://docs.livekit.io/agents/logic/turns/adaptive-interruption-handling/) |
| “잠깐만요, 일곱 시 말고 여덟 시요” | AI를 멈추고 정정 전체를 듣는다. 승인된 시간과 다르면 변경 제안으로 보존하고 사용자 승인 없이 수락하지 않는다. | 중단 감도만 낮추면 실제 정정도 늦게 듣는다. 최소 발화 길이/단어 수는 지연과 함께 평가한다. [Turn tuning](https://docs.livekit.io/agents/logic/turns/tuning/), 승인 경계는 프로젝트 규칙 |
| 질문이 잡음에 잘림 | 전사 없는 오감지는 기존 재생 복구를 사용한다. 실제 발화가 있으면 이미 받은 답을 먼저 반영하고, 아직 답이 없는 질문만 짧게 이어간다. | 기본 false-interruption 복구는 일정 시간 **전사가 없을 때** 적용된다. 주변 말이 실제 단어로 전사된 경우까지 해결하지 않는다. [Turn tuning](https://docs.livekit.io/agents/logic/turns/tuning/) |
| 말을 천천히 하거나 이름 중간에 쉼 | 발화 종료 판단과 STT 도착 시간을 함께 측정한다. `endpointing.min_delay` 조정으로 누락과 응답 지연을 비교한다. | LiveKit audio turn detector는 한국어를 지원한다. 미니 모델은 로컬 CPU를 사용하므로 단순히 새 모델을 켜는 것보다 현재 실행·지연을 먼저 확인한다. [Turn detector](https://docs.livekit.io/agents/logic/turns/turn-detector/) |
| “잠시만요”, 담당자 확인·보류 음악 | WAITING 상태에 들어가 반복 질문을 멈춘다. 상대가 돌아오면 미확인 항목부터 재개한다. 대기가 길어지면 시간 상한 내에서 한 번 확인하고 미확인으로 마친다. | `user_state_changed`/`away`로 비활동을 관찰할 수 있지만 대기 요청의 업무 처리는 앱이 정해야 한다. 음악을 수락으로 취급하지 않는다. [Events](https://docs.livekit.io/reference/agents/events/) |
| 이름·시간 오인식 | 현재 통화의 승인 이름 keyterm을 유지하고, 불일치한 항목만 재확인한다. 음절로 다시 말할 때도 원래 이름을 바꾸지 않는다. | 현재 `assemblyai/universal-3-6-pro`는 한국어 지원이다. 이전 Universal-3의 6개 언어 설명과 구분한다. 대화 문맥 전달도 SDK 지원 기능이므로 새로 없는 기능이라고 가정하지 않는다. [LiveKit AssemblyAI](https://docs.livekit.io/agents/models/stt/assemblyai/), [공급자 최신 언어 목록](https://www.assemblyai.com/blog/universal-3-6-pro-realtime-supported-languages) |
| STT/LLM 장애·429·timeout | 구성요소와 `recoverable`을 기록한다. SDK 재시도와 앱 재시도가 겹치지 않게 제한한다. 승인된 백업이 없거나 복구 실패면 예약을 미확인으로 마친다. | `ErrorEvent`와 `FallbackAdapter`를 제공한다. LLM이 일부 텍스트/도구 호출을 내보낸 뒤에는 기본적으로 응답을 다시 시작하지 않는다. 이를 무시한 재시도는 중복 부작용 위험이 있다. [Error handling](https://docs.livekit.io/reference/agents/events/), [Fallback](https://docs.livekit.io/agents/logic/fallback-strategies/) |
| TTS 장애 | “연결 문제로 예약 확정을 확인하지 못했습니다” 같은 짧은 음성을 미리 준비하고 음성 프레임으로 재생한다. 이미 저장된 확정/거절/미확인 상태에 맞는 안내를 고른다. | TTS 장애 때 텍스트만 `say()`에 넘기면 같은 TTS에 의존한다. 사전 준비 음성 재생 경로가 필요하다. SIP 오디오 경로까지 끊겼으면 이 안내도 전달할 수 없다. [사전 준비 오디오 fallback](https://docs.livekit.io/reference/agents/events/#pre-recorded-audio-fallback) |
| 첫인사만 들리거나 한쪽 음성 없음 | 오디오 프레임 도착 → STT 결과 → LLM 생성 → TTS 재생을 구분해서 조사한다. SIP 성공을 음성 왕복 성공으로 간주하지 않는다. | SDP 주소·코덱·RTP를 확인한다. LiveKit Cloud 사용 중에는 공급자의 미디어 전달을 확인하며, 로컬 SIP 서버용 NAT 설정을 우리 Mac에 그대로 적용하지 않는다. [SIP troubleshooting](https://docs.livekit.io/reference/telephony/troubleshooting/) |
| 지직거림·로봇 음성·순간 끊김 | 중단 이벤트와 패킷 손실·jitter·처리 정체를 따로 측정한다. 음성 필터만으로 네트워크 손실이 복구된다고 가정하지 않는다. | SIP/RTP 품질 진단 지침을 사용한다. 현재 로그의 210ms 정체와 사용자 청취 끊김의 인과 관계는 미확정이다. [SIP troubleshooting](https://docs.livekit.io/reference/telephony/troubleshooting/) |
| 통화 중·부재·거절·코덱 불일치 | 통화 연결 상태와 예약 상태를 분리한다. 486 통화 중, 408 응답 시간 초과, 488 음성 조건 불일치 등을 구분하고 자동 재발신하지 않는다. | Telkit 기록의 구조화된 필드를 사용한다. `ended_by=null`이면 누가 끊었는지 모르는 것이다. G.711 PCMA/PCMU와 암호화 협상도 점검 대상이다. [Telkit 결과](https://telkit.io/docs/operate/call-events.md), [SIP 연결](https://telkit.io/llms/phone.txt) |
| ARS·음성사서함 | 사람/ARS/음성사서함/불명확을 분기한다. ARS라면 승인된 예약 메뉴만 DTMF로 탐색하고, 메시지를 남겼다고 예약을 확정하지 않는다. | LiveKit AMD는 첫 응답을 분류하는 기능이다. 분류가 불명확하면 인간 상대처럼 진행하는 것이 문서 기본이며, 정확도·지연·LLM 사용량을 따로 평가해야 한다. 현재 미연결. [AMD](https://docs.livekit.io/telephony/features/answering-machine-detection/), [DTMF](https://docs.livekit.io/telephony/features/dtmf/) |
| 결과 저장 timeout·중복 요청·연결 단절 | 같은 실행의 저장 상태를 먼저 조회하고 한 번만 반영한다. 확정 저장 후 안내가 실패한 경우와, 저장 자체가 안 된 경우를 구분한다. 변경 미확인 중 원 예약은 보존한다. | 기존 workflow의 상태/중복 제어를 유지한다. 모델 fallback은 예약 DB의 멱등성이나 외부 예약 대조를 대신하지 않는다. [Fallback의 부분 출력 제한](https://docs.livekit.io/agents/logic/fallback-strategies/) + 프로젝트 설계 |
| 식당 회신·예약 식별 실패 | 이미 허용된 상대라도 이름·일시 대조 실패 시 다른 예약 목록을 읽지 않는다. 직원 교대·동명이인·정정 요청을 시험하고, 실제 수신 운영 전 식별 보강이 필요하다. | 현 프로토타입의 도구 검증 기준이다. 전화 발신자 표시나 음성 화자 분리만으로 예약자 본인/변경 권한을 인증하지 않는다. 실수신은 아직 미검증이다. |

## 비용·버전·문서의 함정

- 배경 소음 억제 모델은 LiveKit Cloud에 포함되지만 **주화자 분리 모델은 별도 요금**이다. 새 필터를 무료라고 한꺼번에 묶어 안내하지 않는다. [가격 구분](https://docs.livekit.io/transport/media/noise-cancellation/)
- 현재처럼 로컬 worker를 쓰면 adaptive는 프로젝트별 월 40,000회, full audio turn detector는 월 7,500회 개발 허용량이 안내되어 있다. 요청 수는 통화 수가 아니다. Build에서는 한도 후 차단/기능별 fallback이 적용되고, 유료 플랜은 초과 과금 규칙이 다르다. 사용 전 실제 프로젝트 잔여량을 확인한다. [한도](https://docs.livekit.io/deploy/admin/quotas-and-limits/)
- 최신 소음 문서는 `livekit-plugins-krisp`의 VIVA telephony를 제시하지만 일부 turn tuning 예제는 `BVCTelephony()`를 사용한다. 서로 다른 문서의 패키지와 생성자를 섞지 않고, 선택한 버전을 고정한 뒤 import·실제 입력 경로를 확인한다.
- **Telkit `call.ended` 웹훅은 현재 문서상 준비 중**이다. 통화 기록이 종료 후 약 30초 뒤 생길 수 있으므로 종료 이벤트만 기다리지 말고, 한정된 조회로 기록과 비용을 대조해야 한다. 최신 한 건을 무조건 이번 통화로 간주하지 않는다. [Telkit 통화 결과](https://telkit.io/docs/operate/call-events.md)
- Telkit의 잔액 부족·동시 통화 한도는 새 수신에도 영향을 준다. 번호가 있다는 사실만으로 식당의 회신을 항상 받을 수 있는 것은 아니다. Mac worker의 실행 상태와 잔액/한도를 운영 준비 조건으로 둔다. [한도와 동시 통화](https://telkit.io/docs/numbers/limits.md)

## 제안하는 구현 경계

다음은 설계 개념도이며 동일 이름의 enum을 도입한 것은 아니다. 후속 구현은 `CallSupervisor`의 대기·복구·종료 상태로 표현한다.

`LISTENING → SPEAKING → WAITING / RECOVERING → RESULT_SAVED → CLOSING`

현재 질문, 확인한 항목, 미확인 항목, 정정 요청, 재안내 횟수를 통화 상태로 관리한다. 발화가 중단되었다는 이유만으로 질문을 처음부터 되풀이하지 않는다. 전체 질문 횟수 대신 같은 미확인 항목의 반복을 계수하되 전체 통화 시간·비용·발신 횟수 상한은 별도로 유지한다. 첫인사 보호는 짧은 소개에만 제한하는 후보를 시험하고, 날짜/인원 질문 중 실제 정정이 들어왔을 때 누락되지 않게 한다.

구현 위치는 `worker.py`의 session 생성·입력 필터·이벤트 처리, `diagnostics.py`의 원인별 계측, `workflow.py`의 결과/변경 검증이다. 공통 오류 문자열을 LLM에게 맡기지 않고 저장된 상태와 복구 여부로 결정한다.

## 다음 검증 묶음

아래 수치·시나리오는 **초기 실험 제안**이다. 검증 전 성능 보장이나 공급자 공식 권장값으로 사용하지 않는다.

1. 동일한 가상 한국어 입력으로 조용함, 생활 잡음, 다른 사람 대화, AI 음성 되울림을 비교한다. 다른 사람 목소리와 주화자가 비슷한 크기인 경우, 직원 교대도 포함한다.
2. AI 중간의 “네”, 질문 직후의 “네”, “아니요”, “잠깐만요”, “일곱 시 말고 여덟 시”를 따로 시험한다. 인사 중 정정, 말끝의 짧은 응답, 늦은 최종 전사도 포함한다.
3. 현재 VAD 0.3과 기본 0.5를 먼저 비교한다. 필터·adaptive·endpointing을 한꺼번에 바꾸지 않는다. 단어 수를 무작정 2개 이상으로 높여 한 단어 정정을 잃지 않게 한다.
4. 무응답, 명시적 대기, 보류 음악, 답변 후 긴 침묵을 구분한다. 반복 재안내 없이 제한 시간 안에 끝나고 예약 상태를 오판하지 않아야 한다.
5. STT/LLM/TTS 각각의 timeout·429·복구 불가 오류, 부분 음성 재생 후 오류, 저장 성공 직후 연결 단절을 가짜 client로 주입한다. 무한 재시도·중복 저장·자동 재발신이 없어야 한다.
6. 예약 수락/가능 여부/거절/추가 비용/다른 시간/이름 정정, ARS/음성사서함, 잘못된 회신 식별을 별도 사례로 검사한다. UNKNOWN의 성공 안내와 미승인 변경은 모든 사례에서 0건이어야 한다.
7. 기록할 지표는 잘못된 중단 비율, 실제 정정 누락, 이름/일시/인원 정확 일치, 같은 질문 반복, 사용자 말끝→첫 응답 지연(p50/p95), RTP/전사/모델별 장애, 통화 시간·비용이다. 시험 음성/조건별 분모를 함께 남긴다.

기존 67개 로컬 테스트는 예약/SDK 경계 검사다. 오디오·한국어 대화 품질 통과를 의미하지 않는다. LiveKit의 LLM 평가 도구도 실제 모델을 호출할 수 있으므로 기존 무네트워크 CI와 분리한다. 음성 재생 실험과 승인된 실전화에서 청취를 확인해야 한다. [LiveKit 평가 문서](https://docs.livekit.io/testing/unit-tests/)

원 검토 당시에는 코드·의존성을 변경하지 않았다. 이후 같은 날의 기능 적용 범위는 문서 첫 절을 기준으로 한다. 추가 모델 호출·실전화 없이 무료 Build 설정과 실전화 비활성 상태를 유지한다.
