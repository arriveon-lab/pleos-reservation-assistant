# VOICE-01 한국어 전화 업체 검토

작성: 2026-11-08 KST. 담당: `zito-git`. 이슈: [#3](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/3). 브랜치: `feat/3-call-normalizer`.

**문서 확인 단계입니다.** 공개 웹페이지·약관·API 문서를 읽고 정리했습니다. 업체 계정 생성·번호 구매·실제 발신·유료 API 호출은 **0회**이며 업체를 최종 선정하지 않았습니다. 가격·약관은 확인일 기준이며 변경될 수 있습니다. 법률 검토를 완료한 결과가 아니고 적법성 결론을 내리지 않습니다.

## 1. 요약

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

아래는 실행 계획 초안이며 승인이 아닙니다. 실제 실행 전에 대상·목적·통화 수·예상 비용과 상한을 다시 제시하고 명시적 승인을 받습니다.

| 항목 | 초안 |
| --- | --- |
| 업체 | Vox 우선. 한국AI통신은 유료 전환 후에만 발신 가능 |
| 수신 대상 | 사전에 동의한 팀원 번호만 allowlist에 등록. 실제 식당·Kakao 장소 번호 제외 |
| 목적 | 한국어 고지·문의·예약 대화와 숫자·시간·부정 표현·단절의 결과 정규화 |
| 녹음 | 저장하지 않거나 즉시 삭제로 시작. 필요 시 수신자 사전 동의 후 범위 결정 |
| 비용 요소 | 070 월 이용료, 통화 분당 요금, 모델비, 결제 수단 등록. 실제 견적은 실행 제안에 기록 |
| 기록 | [INTEGRATIONS](../INTEGRATIONS.md#실제-통화-poc-절차)의 검증 기록 양식. 실제 번호·원 음성은 Git에 넣지 않음 |

## 7. 남은 확인 사항

- 실제 식당 대상 발신에 대한 업체 서면 확인과 법률 검토
- Vox 녹음 해제의 API 필드, 추출 스키마의 API 설정 가능 여부
- 한국AI통신 webhook 재시도 횟수·간격, 개정 개인정보처리방침
- 두 업체의 한국어 숫자·시간·금액 인식 품질(실행 검증 필요)
- KT AICC와 SIP 트렁크 조합의 재조사 필요 여부

## 8. 근거 (확인일 2026-11-08)

- Vox: [요금](https://www.tryvox.co/pricing), [요금 문서](https://docs.tryvox.co/docs/start/pricing.md), [이용약관](https://tryvox.co/legal/terms-of-service), [이용 정책](https://tryvox.co/legal/use-policy), [개인정보처리방침](https://tryvox.co/legal/privacy-policy), [통화 생성 API](https://docs.tryvox.co/api-reference/v3/calls/%ED%86%B5%ED%99%94-%EC%83%9D%EC%84%B1.md), [webhook 서명](https://docs.tryvox.co/docs/operate/monitor/webhooks/organization.md), [webhook 스키마](https://docs.tryvox.co/docs/operate/monitor/webhooks/schema.md), [통화 정보 추출](https://docs.tryvox.co/docs/build/post-call/extraction.md), [녹음](https://docs.tryvox.co/docs/operate/monitor/recording.md), [데이터 보관](https://docs.tryvox.co/docs/build/security/data-retention.md), [AI 고지](https://docs.tryvox.co/docs/security/ai-disclosure.md), [발신 콘텐츠 정책](https://docs.tryvox.co/docs/security/outbound-content-policy.md), [번호](https://docs.tryvox.co/docs/operate/deploy/phone-numbers.md), [워크스페이스 인증](https://docs.tryvox.co/docs/workspace/verification.md)
- 한국AI통신: [요금](https://koreaaitel.com/pricing), [이용약관](https://koreaaitel.com/terms), [개인정보처리방침](https://koreaaitel.com/privacy), [발신 API](https://platform.claw-ops.com/docs/api/calls/createCall), [webhook 서명](https://platform.claw-ops.com/docs/webhooks/signature-verification), [통화 녹음](https://platform.claw-ops.com/docs/call-recording), [요청 제한](https://platform.claw-ops.com/docs/api-reference/rate-limiting), [실패 원인](https://platform.claw-ops.com/docs/call-failure-causes), [Voice Agent](https://platform.claw-ops.com/docs/voice-agent)
- 기타 업체: [Twilio 한국 음성 가이드](https://www.twilio.com/en-us/guidelines/kr/south-korea-voice-guidelines---twilio), [CLOVA AiCall 개요](https://guide.ncloud-docs.com/docs/clovaaicall-aicall-1-1), [CLOVA AiCall 매뉴얼](https://docs.ncloud.com/en/clovaaicall/aicall-1-5.html)
- 법령·판례: [전기통신사업법](https://www.law.go.kr/LSW//lsLinkCommonInfo.do?lsJoLnkSeq=1019731109&chrClsCd=010202&ancYnChk=), [대법원 2006도4981](https://casenote.kr/%EB%8C%80%EB%B2%95%EC%9B%90/2006%EB%8F%844981), [대법원 2020도1007](https://casenote.kr/%EB%8C%80%EB%B2%95%EC%9B%90/2020%EB%8F%841007), [AI 기본법 해설(법률신문)](https://www.lawtimes.co.kr/news/articleView.html?idxno=216500)
