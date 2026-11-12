# UX-56 도착 시각·예약·식당 회신 UI

2026-11-12. 사용자 요청에 따른 **Native Android UI 선구현**이다. 새 화면은 API 미연결이며 실제 전화·유료 검색·예약을 실행하지 않는다. 기존 실제/MOCK 연동과 기록은 별도 진입점으로 보존한다.

## 구성

- 새 런처 `DemoFlowActivity`: 좌측 서울숲 목적지·25분 후 도착·19:00 도착 예정(고정 시연값), 우측 현재 단계. 주야간 기존 색상·72dp 이상 주요 버튼·가로/좁은 창 배치.
- 조건: 성인/아이, 도착 후 여유0/10/20분으로 19:00/19:10/19:20 예약 희망 시각 제안. 주차/유아의자/반려동물/계단 없는 입장 조건. 실제 SDK ETA를 변환한 값이 아니다.
- 가상 후보 → 선택 → 승인 → 시연 전화 진행 → 시연 수락/예약금 없음 → 확정 카드.
- 식당의 변경 연락 → 기존 시각/19:30 제안/사유 → 변경 동의 또는 거절 → 식당 재확인 후 변경 완료 또는 원 시각 유지.
- 변경 응답 유실은 UNKNOWN이고 원 확정 시각을 유지한다. 변경 거절을 예약 취소로 해석하지 않는다. 취소 API가 없으므로 취소 완료 화면을 만들지 않았다.
- 대화 내역은 별도 펼침. 합성 문구임을 표시한다. `시연 조작`에서 명시적으로 응답을 주입하며 서버 응답처럼 자동 진행하지 않는다.
- 모든 화면 상단에 `UI 시연 · API 미연결 · 실제 전화·예약 없음`. 실제 조회·인증·예약 기록은 `기존 연동·예약 기록`으로 접근한다. 기존 Gleo 명령 수신부는 그대로이며 새 화면과의 음성 연결은 아직 없다.

## 실행 검증

Pleos Connect2.1.0/API34, emulator-5554/user10, EC2 검증 설치에 install-r. `DemoFlowDeviceChecks`에서 실제 차량 입력 허용 확인 후 조건→후보→승인→시연 확정→식당 회신→동의→재확인 UI 전환 PASS. 거절·UNKNOWN 원 시각 유지와 제한 시 버튼 비활성 검사 PASS. 이 검사는 실제 수발신·서버 공동 인수가 아니다.

- `./gradlew -PincludeAndroid=true -PnavigationDemo=true -PmockPhoneDemo=true -Pec2MockDemo=true -PdemoFlowChecks=true verifyAll :android:app:assembleDebugAndroidTest`
- `adb shell am instrument --user 10 -w ai.pleos.assistant.mock.ec2validation.test/ai.pleos.assistant.app.DemoFlowDeviceChecks`
- 로그: `work/ux56-demo-flow/`. 화면: `captures/ux56/demo-flow/`.

추가 시각 확인: 주간 전체 창의 조건·후보·승인·전화 진행·확정·회신·변경 대기·변경 완료 화면을 캡처했다. 야간840px 창에서는 세로 배치와 변경 완료 카드 표시를 확인했고 주간 전체 창으로 복원했다. 전체 시나리오의 좁은 창 재검증이나 실차 인수는 아니다. 기존 세 설치의 인증·예약 journal과 별도 시험 세션 암호문은 전후 해시가 같다. Lint 오류0·기존 경고22, JAR smoke PASS.

## API 연결 순서

1. 새 세션 연결 계약과 선택 후보/예약 ID를 새 UI 상태에 매핑한다.
2. 가상 후보와 고정 ETA를 각각 검증된 출처로 교체한다. 검증 전 ETA는 시연값 표시를 유지한다.
3. 승인→작업 ID→GET 결과/대화를 연결한다. 서버의 명시적 수락만 CONFIRMED로 표시한다.
4. 회신 예약 식별·사용자 결정·식당 재확인 상태를 연결한다. 재실행·중복·단절·UNKNOWN을 실제 서버로 검증한다.
5. 사용자 승인 범위 내 시험전화와 식당 재전화로 공동 인수한다.

#56 완료나 release 완료로 표시하지 않는다. 새 UI의 모든 예약/변경 결과는 아직 합성 상태다.
