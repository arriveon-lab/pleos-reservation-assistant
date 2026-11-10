# VOICE-01 한국어 전화 업체 검토

작성: 2026-11-08 KST, Telkit 채택 반영: 2026-11-10 KST. 담당: `zito-git`. 이슈: [#3](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/3). 브랜치: `feat/3-call-normalizer`.

**문서 확인 단계입니다.** 공개 웹페이지·약관·API 문서를 읽고 정리했습니다. 업체 계정 생성·번호 구매·실제 발신·유료 API 호출은 **0회**입니다. 가격·약관은 확인일 기준이며 변경될 수 있습니다. 법률 검토를 완료한 결과가 아니고 적법성 결론을 내리지 않습니다.

## 0. 결정: Telkit 채택 (2026-11-10)

팀이 전화 회선으로 **Telkit**(주식회사 플릭)을 사용하기로 결정했습니다. 결정은 사용자가 2026-11-10에 전달했고, 아래 내용은 같은 날 Telkit 공개 문서·요금·약관을 다시 읽어 정리했습니다. 계정 생성·본인인증·카드 등록·번호 구매·발신은 하지 않았습니다. 구성은 [전화예약 에이전트 계획](../PHONE_RESERVATION_AGENT_PLAN.md)의 Telkit SIP + LiveKit Agents 구조를 따릅니다.

Telkit은 음성 AI가 아니라 **070 번호와 SIP 트렁크**를 제공합니다. STT·LLM·TTS와 대화 진행은 LiveKit 에이전트가 맡습니다. 2026-11-08에 1순위로 본 Vox도 같은 운영사의 음성 에이전트 플랫폼이지만, 이번 결정은 회선만 Telkit에서 받고 에이전트는 직접 구성하는 방식입니다. 아래 1~7절은 당시 비교 기록으로 보존합니다.

### Telkit 확인 사항

| 항목 | 공개 문서 기준 | 우리 설계에 주는 영향 |
| --- | --- | --- |
| 운영사 | 주식회사 플릭. 기간통신사업자·특수한 유형의 부가통신사업자 등록번호 표기(정부 등록부 미대조) | — |
| 번호 | 070 번호 임대(수신·발신). **계정당 번호 1개**. 02·010·대표번호 옵션은 문서에서 찾지 못함 | 서비스 번호 1개로 발신·회신을 함께 운영 |
| 발신 방식 | 별도 "전화 걸기" REST API 없음. LiveKit outbound trunk → `sip.telkit.io:5061`, **TLS만**, `REGISTER` 미지원(통화마다 인증) | 발신은 LiveKit `CreateSIPParticipant`로 구현 |
| 발신 자격 | `GET /v1/sip-trunk`로 번호 상태(`card_required`·`preparing`·`auth_required`) 확인, `POST /v1/numbers/{id}/sip-credential`의 `username`/`password` 사용. 비밀번호는 한 번만 표시되고 1~2분 뒤 사용 가능, `rotate` 제공 | 서버 비밀값 저장소에 보관. Git·앱·로그에 넣지 않음 |
| 코덱·암호화 | `PCMA`·`PCMU`만 수락(Opus만 제안하면 거절). SDES SRTP 권장, 평문 RTP도 수락 | LiveKit trunk `media.encryption`은 `SIP_MEDIA_ENCRYPT_ALLOW` |
| 수신 연결 | `PUT /v1/numbers/{id}/connection`에 `sip_uri`(`sip:{subdomain}.sip.livekit.cloud`)·`transport`·`srtp`·`number_format`(`e164`). 저장 후 약 1분 뒤 반영 | dispatch rule로 통화마다 room·에이전트 배정 |
| 수신 신호 | Telkit → LiveKit: TLS 5061, UDP·TCP 5080, RTP UDP 30000~40000, 인증 정보 없음. 발신 IP `34.64.81.145`, `34.47.68.103`을 LiveKit `allowed_addresses`에 넣되 LiveKit 지원팀이 프로젝트에서 켜야 적용됨 | 활성화 전까지 IP 제한이 적용되지 않음을 전제로 운영 |
| 번호 형식 | 보내는 번호 `07012345678`·`827012345678`·`+827012345678`, 받는 번호 `+821012345678`·`821012345678`·`01012345678` 모두 허용 | 서버에서 E.164로 정규화해 전달 |
| 한도 | 동시 통화 수신+발신 **4통**, 발신 **하루 500통·월 10,000통**(KST 자정·매월 1일 초기화, 받지 않은 시도 포함), 초당 새 발신 제한(값 미기재), 최대 통화 시간 기본 60분(번호별 60초~4시간) | 발신 동시성 1, 우리 쪽 일일·통화 시간 상한을 더 낮게 설정 |
| 통화 기록 | `GET /v1/calls/{telkit_call_id}`, `GET /v1/numbers/{id}/calls`. `sip_code`·`sip_reason`·`ended_by`·`reason`·`billable_seconds` 등. 종료 후 약 30초 뒤 생성 | 실시간 상태가 아닌 사후 비용·연결 대조용 |
| 웹훅 | `call.ended`만 정의, **준비 중**(`PUT /v1/webhook-endpoint`는 `503 not_available`). 공개되면 Standard Webhooks(HMAC-SHA256, `webhook-id`·`webhook-timestamp`·`webhook-signature`), 최대 8회 재시도. **SIP 트렁크 발신에는 웹훅을 보내지 않음** | 발신 결과를 Telkit 웹훅에 의존하지 않음 |
| 녹음·전사 | API 없음. 약관·개인정보처리방침상 통화 음성을 녹음·저장하지 않음 | 근거 발화는 우리 에이전트 전사로만 확보 |
| API 인증 | `https://api.telkit.io`, `Authorization: Bearer`. 첫 연결 시 사람이 본인인증·약관 동의. "API 키"와 "워크스페이스 액세스 토큰" 표기가 문서마다 다름 | 실제 발급 절차는 계정 준비 시 확인 |
| 요금(VAT 별도) | 번호 30일당 7,000원, 수신 10초당 2원, 휴대폰 발신 10초당 12원, 유선·070·대표번호 발신 분당 15원. 통화마다 단위 올림, 미연결 0원. 본인인증+카드 등록 시 10,000원 크레딧 1회(환불 불가) | 통화 요금 외 LiveKit·STT·LLM·TTS 비용은 별도 |
| 잔액·결제 | 잔액 0원 이하면 새 수신·발신 중단(진행 중 통화는 유지). 번호 결제 실패 시 7일 보관 후 회수, 회수 번호는 복구 불가 | 잔액·번호 상태를 운영 지표로 관측 |

예시 요금(요금 문서): 1분 5초 통화는 휴대폰 발신 84원, 유선 발신 30원, 3분 통화는 휴대폰 발신 216원, 유선 발신 45원입니다. 전체 통화당 비용은 PoC에서 AI·LiveKit 사용료까지 합산해 측정합니다.

### Telkit 약관·정책

- 사용 권한이 있는 발신번호만 쓰고 발신번호를 바꾸어 표시하지 않습니다(이용약관 9절, 전기통신사업법 제84조의2).
- 금지 행위: 명의 도용, 불법 스팸·사기, 서비스 방해, 번호 재판매·양도(10절). 112·119 등 긴급 신고 용도로 제공되지 않습니다(6절).
- 연결한 외부 서비스가 통화를 처리하는 데 필요한 동의와 법적 의무는 회원이 갖춥니다(9절). 광고성 발신은 회원이 사전 동의·표시·수신거부·야간 제한을 지킵니다. 매장 문의·예약 발신을 명시적으로 허용하거나 금지하는 조항은 찾지 못했습니다.
- Telkit은 불법 스팸 방지를 위해 통화 음성을 AI로 자동 분석하고 음성은 분석 직후 삭제, 분석 결과는 30일 보관합니다. 통화 기록은 1년, 통신사실확인자료는 12개월 보관합니다(개인정보처리방침 2·3절).
- 국외 이전: Google LLC가 실시간 통화 음성을 연결·분석에 사용하며, 그 밖에 미국 수탁사가 있습니다(6절). 개인정보처리방침·약관 시행일은 2026-11-01입니다.
- 책임 상한은 최근 12개월 요금 총액과 10만 원 중 큰 금액입니다(13절).

### 서버 계약에 미치는 영향 (Telkit 기준)

5절의 제안을 Telkit 구조에 맞게 고쳐 씁니다. 현재 API·DB에는 적용하지 않았습니다.

1. **발신 결과의 출처:** 발신 연결·종료는 LiveKit 참가자·room 이벤트로 판단합니다. Telkit 웹훅은 발신에 오지 않으므로 대기하지 않습니다.
2. **중복 발신 방지:** Telkit·LiveKit 모두 발신 멱등 키를 문서화하지 않았습니다. outbox에 내부 call ID를 먼저 기록하고, `CreateSIPParticipant` 응답 유실 시 room·참가자를 대조하며 즉시 새 발신을 하지 않습니다.
3. **사후 대조:** 종료 약 30초 뒤 Telkit 통화 기록으로 연결 여부·`billable_seconds`·종료 사유를 대조합니다. 내부 call ID를 Telkit 기록에 넣는 방법은 찾지 못했으므로, `telkit_call_id` 수집 경로를 PoC에서 확인하고 확보되지 않으면 방향·번호·시각 대조를 보조로만 씁니다. 애매하면 `UNKNOWN`을 유지합니다.
4. **예약 판정:** Telkit의 연결 성공 기록은 식당의 예약 수락 근거가 아닙니다. 에이전트가 제출한 `explicitAcceptance`·`acceptedTime`·`additionalCostKrw`와 근거 발화를 서버의 기존 일치 판정에 그대로 적용합니다.
5. **한도:** Telkit 한도(동시 4통, 하루 500통)보다 낮은 우리 쪽 상한과 kill switch를 둡니다. 잔액 부족·번호 정지는 새 작업 차단 사유로 저장합니다.
6. **웹훅 공개 이후:** `call.ended`가 열리면 수신 통화에 한해 서명 검증·`webhook-id` 중복 제거를 추가합니다.

### Telkit 남은 확인 사항

- 발신 시 상대 화면에 표시되는 번호(발신번호 등록 API `/v1/caller-ids`의 등록 조건·서류 포함)
- 초당 새 발신 한도의 실제 값, `/v1/sip-trunk/ip-allowlist`의 용도
- LiveKit 지원팀의 `allowed_addresses` 활성화 절차
- `telkit_call_id`를 LiveKit 참가자와 연결하는 방법
- `call.ended` 웹훅 공개 시점
- 매장 대상 비광고 발신에 대한 업체 서면 확인과 법률 검토
- 한국어 숫자·시간·금액 인식 품질(STT·LLM·TTS 선택 후 실행 검증)

## 1. 요약 (2026-11-08 비교 기록)

| 순위 | 후보 | 판단 근거 | 차단·미확인 |
| --- | --- | --- | --- |
| 1 | Vox (주식회사 플릭) | 070 개통, 발신 REST API, 서명 webhook, 통화 후 구조화 추출, `metadata`로 통화 조회 가능 | 일반 매장 발신의 명시 허용 없음, 녹음 기본 무기한 보관, 발신 멱등 키 없음 |
| 2 | 한국AI통신 (팀러너스 주식회사) | 070 즉시 발급, Agent SDK로 대화 흐름 직접 제어, 기간통신사업자 등록번호 표기(정부 등록부 미대조) | **체험 기간 발신 불가**, 발신 멱등 키·외부 참조 조회 없음, 업체 검증 모델은 OpenAI Realtime만 |
| 보류 | Twilio Programmable Voice | 한국 발신 reachability 표 | 한국 번호 발급 여부 미확인, 국내 발신 Caller ID 비보장 |
| 보류 | NAVER CLOVA AiCall | 한국어 inbound/outbound 콜봇 | outbound 최소 3채널·캠페인형 고정 메시지·외부 솔루션 연계로 매장별 자유 대화와 맞지 않음 |
| 미조사 | KT AICC/A’Cen | 기존 [INTEGRATIONS](../INTEGRATIONS.md#전화-사업자-비교) 기록 유지 | 이번에 새로 조사하지 않음 |

Vapi·Retell 등 해외 음성 플랫폼은 한국 070 직접 지원이 없다는 블로그 내용만 확인했고 공식 근거로 확인하지 않았습니다. SIP 트렁크로 국내 번호와 조합하는 구성은 후속 검토 대상입니다.

## 2. 기능·가격 비교

| 항목 | Vox | 한국AI통신 |
| --- | --- | --- |
| 번호 | 070 에이전트 번호, 1588류 대표번호(070 착신, 영업일 2~3일) | 070 즉시 발급, 대표번호, 기업용 010(법인·통신사 심사) |
| 발신 API | `POST /v3/calls` (`from_number`, `to_number`, `agent`, `metadata`, `dynamic_variables`, `presentation_number`) | `POST /v1/accounts/{accountId}/calls` (`To`, `From`, `AgentId`/`CallFlowId`/`Url`, `StatusCallback`, `Timeout` 등) |
| 발신 응답 의미 | 201과 `status`(`queued` 등) | 201 `queued`는 연결 성공이 아니라 큐 등록 |
| 발신 멱등 키 | 없음 | 없음 |
| 우리 ID로 조회 | `GET /v3/calls?metadata=key:value` 정확 일치 필터 | 없음. `to`·`since` 등 목록 필터만 |
| webhook 서명 | `X-Webhook-Timestamp` + `X-Webhook-Signature`, HMAC-SHA256(`{timestamp}.{raw_body}`), 5분 허용, 고정 IP | `X-Signature` HMAC-SHA256, Status/Message/계정 이벤트에 `Timestamp`(VoiceML 요청에는 없음), 고정 IP |
| webhook 재전송 | 연결 5초 초과 시에만 즉시 1회 재전송. 연결 후 무응답·4xx·5xx는 재전송 없음 | at-least-once, 재시도 시 원래 발생 시각 유지. 횟수·간격 미기재 |
| 중복 제거 키 | (`event`, `call_id`) 등 | `CallId` 권장 |
| 결과 구조화 | 통화 종료 후 전사 기반 추출(문자열·숫자·불리언·열거형) → `call_analysis.custom_analysis_data` | 요약 JSON webhook(10원/분) 또는 SDK로 직접 처리 |
| 한국어 음성 | 업체 소개 기준 지원, 품질 미검증 | Deepgram·ElevenLabs·OpenAI Realtime·Gemini `ko` 설정 예시. 업체 검증 완료는 OpenAI Realtime만 |
| 요금 기준 | VAT 별도. Free 0원(가입 시 10,000 크레딧), AI 통화 132원/분 + 모델비, 발신 휴대폰 12원/10초·유선/070 15원/분·건당 20원, 070 월 7,000원 | VAT 포함. Individual 프로모션 월 9,900원(발신 70분), 초과 발신 31원/분, 전사 10원/분. LLM 비용은 고객 키로 별도 |
| 한도 | Free: 하루·시간당 100건, 동시 3 | 요금제별 동시 통화(문서 간 Individual 표기 불일치), 분당 발신 상한 미기재 |
| 무료 체험 | Free 크레딧. 번호·결제 수단 등록은 별도 필요 | 7일 체험. **체험 기간에는 발신·문자 불가** |

## 3. 약관·정책 확인

| 항목 | Vox | 한국AI통신 |
| --- | --- | --- |
| 광고가 아닌 매장 문의·예약 발신 | 명시적 허용·금지 없음. 무단 로보콜·스팸 금지, 발신 대상 동의·발신 근거 자료 보관 의무 | 명시적 허용·금지 없음. 광고성 전화에만 수신자 사전 동의 요구 |
| 동의·법령 책임 | 고객이 발신 행위의 최종 책임, 위반 시 고객이 면책 보상 | 약관 동의만으로 통화 상대방 처리 동의가 확보되지 않음, 이용자가 관계 법령상 책임 |
| AI 고지 | AI 생성·응답임을 숨기면 안 됨, AI 기본법 제31조 언급. 적용 범위는 고객이 법률 자문으로 확인 | 명시 의무 미발견. AI 응답 오류 대비 사람 확인 절차 요구 |
| 녹음 기본값 | 모든 통화 자동 녹음, 보관 기본 무기한(즉시 삭제·기간 지정 선택 가능) | 녹음 기능을 켜면 전체 녹음, 보관 기본 90일(7일·30일 선택) |
| 녹음 끄기 | 에이전트 보안 설정. 통화 단위·API 필드 미발견 | 콘솔 계정 단위. 통화 단위 미발견 |
| 녹음 고지·동의 | 고객이 상대방에게 사전 고지하고 동의 | 이용자와 회사가 각자 지위에 따라 고지·동의, 녹음 법령 준수 책임은 고객사 |
| 삭제 | `DELETE /v3/calls/{call_id}`(녹음·대화록·분석·메타데이터) | 녹음 삭제 API, 즉시·복구 불가 |
| 처리 지위·학습 | Vox는 수탁자, 고객 콘텐츠를 모델 학습에 쓰지 않음 | 일반 모델 학습에는 별도 법적 근거·동의 필요 |
| 국외 이전 | OpenAI·Google·Anthropic·Deepgram 등(미국), Soniox(일본), LiveKit(일본·싱가포르) | OpenAI·ElevenLabs(보유 2년)·Anthropic·xAI 등(미국) |
| 개인 가입 | 본인인증, 서류 없음, 070·대표번호 각 1개 | 휴대폰 본인확인, 070 개통 확인 절차 |
| 정지·해지 | 불법 사용 시 즉시 해지 가능, 책임 상한은 최근 12개월 결제액과 10만 원 중 큰 금액 | 불법스팸·보이스피싱·발신번호 변작 확인 시 즉시 정지, 단순 의심만으로 즉시 영구 해지하지 않음 |

한국AI통신 개인정보처리방침은 2026-11-12 시행 개정판이 예고되어 국외 이전·전사 보관 기간이 바뀝니다. PoC 전에 그때의 현행판으로 다시 확인합니다.

## 4. 법령 확인 사항

법률 자문이 아니며 실제 운영 전 전문가 검토가 필요합니다.

- **녹음:** 대화 당사자의 녹음은 통신비밀보호법 제3조 위반이 아니라는 판례가 있습니다(대법원 2006도4981). 대화에 참여하지 않은 제3자의 녹음은 원칙적으로 위반입니다(대법원 2020도1007). AI 플랫폼의 녹음이 어느 쪽에 해당하는지 직접 다룬 자료는 찾지 못했습니다.
- **AI 고지:** AI 기본법은 2026-01-22 시행됐고 제31조가 사전 고지·생성물 표시를 규정합니다. 전화 상대방인 매장에 대한 고지 방식 기준은 찾지 못했습니다. 현재 `CallTask.disclosure`의 첫 인사 고지를 유지합니다.
- **발신번호:** 전기통신사업법 제84조의2가 송신인 번호의 거짓 표시를 금지합니다. 업체가 발급하거나 심사한 번호만 사용합니다.
- **장소 연락처 전달:** Kakao에서 받은 매장 전화번호를 전화 업체에 전달하는 것은 [#11](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11) 정책 문의의 답변을 받기 전까지 진행하지 않습니다.

## 5. 서버 계약에 미치는 영향

[FR-14](../FUNCTIONAL_SPEC.md#fr-14-실제-전화webhook외부-실패)와 [#4](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/4) 설계 시 반영할 제안입니다. 현재 API·DB에는 적용하지 않았습니다.

1. **발신 전 기록:** 두 업체 모두 발신 멱등 키가 없습니다. 발신 요청 전 outbox에 내부 call ID를 기록하고, 응답 단절·timeout은 새 발신 없이 대조 대상으로 남깁니다.
2. **대조 방법:** Vox는 `metadata`에 내부 call ID를 넣고 목록 필터로 대조할 수 있습니다. 한국AI통신은 외부 참조가 없어 대조가 확정되지 않으면 `UNKNOWN`을 유지합니다.
3. **webhook 유실:** Vox는 서버 오류에 재전송하지 않으므로 webhook만으로 결과를 받는다고 가정하지 않습니다. 일정 시간 후 조회 API로 대조합니다.
4. **중복 이벤트:** 업체 이벤트 키를 내부 eventId 범위와 매핑해 중복 적용을 막습니다. 서명·허용 시각 검증 실패 이벤트는 업무 데이터를 갱신하지 않습니다.
5. **추출 결과 검증:** 업체의 통화 후 추출은 LLM이 전사를 해석한 값입니다. `explicitAcceptance`·`acceptedTime`·`additionalCostKrw`로 변환한 뒤 서버의 기존 수락·시간·비용 일치 판정을 그대로 적용하고 근거 전사 위치를 보존합니다.
6. **녹음과 추출의 관계:** 녹음·전사 저장을 끄면 추출에 필요한 데이터도 남지 않을 수 있습니다. PoC 설정에서 확인합니다.

## 6. PoC 제안 (승인 전)

아래는 실행 계획 초안이며 승인이 아닙니다. 실제 실행 전에 대상·목적·통화 수·예상 비용과 상한을 다시 제시하고 명시적 승인을 받습니다. 2026-11-10 Telkit 채택에 맞춰 업체·녹음·비용 행을 고쳤습니다.

| 항목 | 초안 |
| --- | --- |
| 업체 | Telkit 070 번호 + LiveKit SIP. 에이전트는 LiveKit Agents로 직접 구성 |
| 수신 대상 | 사전에 동의한 팀원 번호만 allowlist에 등록. 실제 식당·Kakao 장소 번호 제외 |
| 목적 | 한국어 고지·문의·예약 대화와 숫자·시간·부정 표현·단절의 결과 정규화 |
| 녹음 | Telkit은 녹음하지 않음. 우리 쪽 원본 음성 녹음은 하지 않고, 전사 저장 범위는 수신자 사전 동의 후 결정 |
| 비용 요소 | 번호 30일당 7,000원, 휴대폰 발신 10초당 12원, 수신 10초당 2원(VAT 별도), LiveKit·STT·LLM·TTS 사용료, 카드 등록. 실제 견적은 실행 제안에 기록 |
| 기록 | [INTEGRATIONS](../INTEGRATIONS.md#실제-통화-poc-절차)의 검증 기록 양식. 실제 번호·원 음성은 Git에 넣지 않음 |

## 7. 남은 확인 사항

- 실제 식당 대상 발신에 대한 업체 서면 확인과 법률 검토
- Vox 녹음 해제의 API 필드, 추출 스키마의 API 설정 가능 여부
- 한국AI통신 webhook 재시도 횟수·간격, 개정 개인정보처리방침
- 두 업체의 한국어 숫자·시간·금액 인식 품질(실행 검증 필요)
- KT AICC와 SIP 트렁크 조합의 재조사 필요 여부

## 8. 근거 (확인일 2026-11-08, Telkit 2026-11-10)

- Telkit: [홈](https://telkit.io/), [요금](https://telkit.io/pricing), [번호와 요금](https://telkit.io/docs/numbers/pricing.md), [한도](https://telkit.io/docs/numbers/limits.md), [빠른 시작](https://telkit.io/docs/quickstart.md), [첫 통화](https://telkit.io/docs/connect/first-call.md), [LiveKit 연동](https://telkit.io/docs/connect/livekit.md), [플랫폼별 지원](https://telkit.io/docs/connect/platforms.md), [API 목록](https://telkit.io/docs/api-reference.md), [통화 결과와 웹훅](https://telkit.io/docs/operate/call-events.md), [이용약관](https://telkit.io/terms), [개인정보처리방침](https://telkit.io/privacy)

- Vox: [요금](https://www.tryvox.co/pricing), [요금 문서](https://docs.tryvox.co/docs/start/pricing.md), [이용약관](https://tryvox.co/legal/terms-of-service), [이용 정책](https://tryvox.co/legal/use-policy), [개인정보처리방침](https://tryvox.co/legal/privacy-policy), [통화 생성 API](https://docs.tryvox.co/api-reference/v3/calls/%ED%86%B5%ED%99%94-%EC%83%9D%EC%84%B1.md), [webhook 서명](https://docs.tryvox.co/docs/operate/monitor/webhooks/organization.md), [webhook 스키마](https://docs.tryvox.co/docs/operate/monitor/webhooks/schema.md), [통화 정보 추출](https://docs.tryvox.co/docs/build/post-call/extraction.md), [녹음](https://docs.tryvox.co/docs/operate/monitor/recording.md), [데이터 보관](https://docs.tryvox.co/docs/build/security/data-retention.md), [AI 고지](https://docs.tryvox.co/docs/security/ai-disclosure.md), [발신 콘텐츠 정책](https://docs.tryvox.co/docs/security/outbound-content-policy.md), [번호](https://docs.tryvox.co/docs/operate/deploy/phone-numbers.md), [워크스페이스 인증](https://docs.tryvox.co/docs/workspace/verification.md)
- 한국AI통신: [요금](https://koreaaitel.com/pricing), [이용약관](https://koreaaitel.com/terms), [개인정보처리방침](https://koreaaitel.com/privacy), [발신 API](https://platform.claw-ops.com/docs/api/calls/createCall), [webhook 서명](https://platform.claw-ops.com/docs/webhooks/signature-verification), [통화 녹음](https://platform.claw-ops.com/docs/call-recording), [요청 제한](https://platform.claw-ops.com/docs/api-reference/rate-limiting), [실패 원인](https://platform.claw-ops.com/docs/call-failure-causes), [Voice Agent](https://platform.claw-ops.com/docs/voice-agent)
- 기타 업체: [Twilio 한국 음성 가이드](https://www.twilio.com/en-us/guidelines/kr/south-korea-voice-guidelines---twilio), [CLOVA AiCall 개요](https://guide.ncloud-docs.com/docs/clovaaicall-aicall-1-1), [CLOVA AiCall 매뉴얼](https://docs.ncloud.com/en/clovaaicall/aicall-1-5.html)
- 법령·판례: [전기통신사업법](https://www.law.go.kr/LSW//lsLinkCommonInfo.do?lsJoLnkSeq=1019731109&chrClsCd=010202&ancYnChk=), [대법원 2006도4981](https://casenote.kr/%EB%8C%80%EB%B2%95%EC%9B%90/2006%EB%8F%844981), [대법원 2020도1007](https://casenote.kr/%EB%8C%80%EB%B2%95%EC%9B%90/2020%EB%8F%841007), [AI 기본법 해설(법률신문)](https://www.lawtimes.co.kr/news/articleView.html?idxno=216500)
