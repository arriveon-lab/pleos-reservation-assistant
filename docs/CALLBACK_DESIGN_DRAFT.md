# AI 전화와 매장 회신 경로 검토안

기록: 2026-11-08 KST. 사용자 요청으로 대화에서 검토한 내용을 보존합니다. 팀 합의·공급자 선정·구현·실통화 성공이 아닌 설계 제안입니다. 전화/음성 #3, 서버 #4, 화면 #5와 검토하며 추천 담당의 #18 완료 조건에 새 전화 기능을 추가하지 않습니다.

## 음성 인식과 통화 오디오 접근

- Pleos SpeechToText는 차량 마이크의 `request()`와 `onUpdated(stt, completed)` 중간/최종 텍스트를 제공합니다.
- 앱이 이미 확보한 PCM은 `sendAudio()`로 전달할 수 있으나 공식 가이드상 ON_DEVICE 모드에 한합니다. 이 API가 상대방 통화 오디오를 확보해 주는 것은 아닙니다.
- Android의 VOICE_CALL 캡처에는 일반 앱에 제공되지 않는 시스템 권한 CAPTURE_AUDIO_OUTPUT이 필요합니다. Pleos도 Telephony가 제한될 수 있다고 안내합니다.
- 확인한 공개 문서에서는 Pleos 내장 실시간 통화 메모 기능이나 그 전사문을 외부 앱에 제공하는 API를 확인하지 못했습니다. 미지원으로 단정하지 않고, 차량 통화 오디오 수신·전사 콜백·합성 음성의 통화 송신 지원 여부를 기술 지원 확인 항목으로 둡니다.

근거: [Pleos 마이크 인식](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [결과 콜백](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [PCM 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data), [Android 통화 오디오](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_CALL), [Pleos 하드웨어 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture).

## 검토할 전화 경로

제안: 매장 ↔ 전화 공급자의 양방향 오디오 ↔ 서버의 STT/AI/TTS. 차량 앱은 요청·승인·진행·결과를 처리합니다. Twilio Media Streams는 이런 구성을 지원하는 공식 사례이며 최종 공급자 결정은 아닙니다. 한국 발신·수신 번호 확보·한국어 지연/정확도·과금은 계정과 회선에서 별도 검증해야 합니다.

근거: [양방향 Media Streams](https://www.twilio.com/docs/voice/media-streams).

## 매장에서 다시 전화하는 경우

| 선택안 | 회신 경로 | 필요한 확인 |
| --- | --- | --- |
| 수신 가능한 서비스 번호를 발신번호로 사용 | 매장 → 서비스 번호 → 예약 매칭 → 사용자 연결 또는 AI 응대 | 번호의 발신/수신 지원, 연결 비용, 예약 매칭, 부재중 처리 |
| 인증한 사용자 번호를 발신번호로 사용 | 매장 → 사용자 기존 휴대폰 회선 | 공급자·국가·통신망의 표시 허용과 실제 번호 보존. 직접 회신 통화는 서비스 AI가 자동 처리하지 않음 |
| 회신용 번호를 대화에서 명시적으로 전달 | 매장이 안내받은 번호로 직접 전화 | 사용자 번호 제공 범위, 매장의 확인, 발신기록에서 재다이얼하는 경우와 차이 |

수신 서비스를 설정하지 않은 발신 전용 번호만 쓰면 회신을 놓칠 수 있습니다. Twilio의 인증된 외부 발신번호는 그 번호의 수신 통화를 Twilio로 옮기지 않으며 기존 통신사로 연결됩니다. 서비스 번호 수신은 webhook과 Dial로 개인 전화 연결을 구성할 수 있습니다. 한국의 실제 표시/연결을 검증한 상태는 아닙니다.

근거: [인증 번호의 발신·수신 경로](https://help.twilio.com/articles/223179848-Using-a-non-Twilio-number-as-the-caller-ID-for-outgoing-calls), [공식 전화 연결 예제](https://www.twilio.com/code-exchange/simple-call-forwarding), [한국 음성 가이드](https://www.twilio.com/en-us/guidelines/kr/south-korea-voice-guidelines---twilio).

## 팀에서 정할 인수 조건

1. 같은 매장이 여러 사용자의 예약을 처리할 수 있으므로 매장 발신번호만으로 사용자를 고르지 않습니다. 예약별 번호나 확인 코드 등 매칭 방식을 정합니다. 발신번호를 예약 변경의 인증으로 사용하지 않습니다.
2. 사용자 직접 연결·AI 응대·부재중 알림과 수신 가능 기간, 연결/번호 유지 비용을 정합니다.
3. 통화/전사 데이터의 처리·보관과 장소 데이터의 전화/AI 공급자 전달 권리는 #11과 조율합니다. 전화 공급자의 기능 지원이 장소 정보 제공 권리를 보장하지 않습니다.
4. 실제 발신·번호 구매·유료 모델/수신 테스트는 대상·목적·횟수/시간·비용 상한을 정해 승인받은 뒤 진행합니다. 이번 문서화에서는 수행하지 않았습니다.

사용자가 승인한 Kakao 추가 6회 조회 예산은 이미 소비했습니다. 전화 검토나 추가 검색 예산으로 전용하지 않습니다. 다른 담당자의 PR을 수정하거나 병합하지 않았습니다.
