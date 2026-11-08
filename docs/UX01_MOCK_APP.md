# UX-01 MOCK 차량 화면

#5의 첫 구현입니다. `android/app`은 probe와 분리한 Android 화면이며 `ux/core`는 SDK 없이 실행할 수 있는 API/복구 계층입니다. Activity → AndroidViewModel → MockRepository → loopback HTTP 구조입니다. 기본 main의 OpenAPI 1.0.0만 사용하고, 1.1.0 인증 서버는 명시적으로 거절합니다. 미병합 #20/PR #21 또는 #16/PR #19를 현행 계약으로 간주하지 않습니다.

## 구현한 범위

- 가상 동행 인원·주차 필수 조건, 고정 가상 성수 목적지·검색 후 1시간 ETA·한식 선호·학습 동의 false.
- 후보 비교와 `eligible`/`bookable`/`missing`/`exclusionReasons`, 사실 출처·관측/적용/만료·확실성·NOW/ARRIVAL 구분. 버튼을 서버의 예약 가능 판정에 맞춰 제한합니다.
- 후보별 모의 문의, 매장·인원·시각·변경 정책을 확인하는 별도 예약 승인 dialog, PENDING/CONFIRMED/REJECTED/UNKNOWN 표시.
- 0원·15분 변경만 제공하며 승인 dialog에서 기존/요청 시각을 표시합니다. 변경이 미해결이면 원 예약 정보와 미확인 요청을 함께 표시하고 재변경을 막습니다. 유료 변경은 #26의 별도 승인 화면 이후입니다.
- 개발용 결과 주입은 사용자가 별도로 누릅니다. 예약 요청 자체가 모의 수락을 생성하지 않습니다. 실제 발신은 없습니다.
- JSON 요청 본문·키·경로를 먼저 동기 저장하고, 응답 유실 후 새 명령을 차단합니다. 명시적 재전송은 같은 키/본문이며 `eventId`도 유지합니다. 복구는 ID 기반 GET이고 새 예약/여정을 만들지 않습니다.
- 전체 후보/사실 응답은 저장하지 않습니다. 가상 여정 ID·검색/예약/통화 ID와 미완료 명령만 앱 private preferences에 보존하고 백업/기기 이전에서 제외합니다. 실제 데이터/인증 토큰 저장용 구현이 아닙니다.
- 화면 정지 시 조작 상태를 UNKNOWN으로 돌리고 승인 dialog를 닫습니다. ViewModel은 회전 중 작업을 보존하며, 종료 시 executor를 해제하고 늦은 UI callback을 무시합니다.

## 현재 제한과 후속 이슈

[UX-02 #25](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/25): 실제 CarUxRestrictions adapter·기기 검증·시연 영상·release 서명/설치. 현재 UNKNOWN/RESTRICTED/SIMULATED_PARKED는 **개발용 모의 정책**이며 실제 차량 상태를 읽지 않습니다. 최초/복귀 UNKNOWN에서 입력·승인을 막습니다. debug 화면의 모의 정차 버튼은 기기 가용성 검증이 아닙니다. release에는 이 버튼과 인터넷 권한이 없고 주행 허용 선언도 없습니다.

[UX-03 #26](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/26): #20 인증 계약 통합 후 안전한 토큰 보관·갱신, #16 미리보기 세대/취소 연결, 추가 비용 별도 승인. 서버/추천 담당자의 이슈·코드는 변경하지 않았습니다.

#5는 원래 범위를 보존한 OPEN/Draft 상태입니다. 후속 링크만으로 실제 차량 제한·release/영상 조건을 완료로 바꾸지 않습니다. 현재 한 가상 여정만 취급하며 예약 후 임의 새 여정/새 예약으로 우회하지 않습니다. 확정적인 HTTP 오류도 미완료 명령을 보수적으로 유지하며, 실패 요청 폐기·새 세션 전환 UX는 아직 없습니다. 복구 저장 손상/유실 시 조용히 새 예약을 생성하지 않도록 사용자 재확인 절차를 후속 검증해야 합니다.

## 실행

JDK 21/Python 환경에서 서버 및 UI 요청 계층:

```bash
./gradlew -PincludeAndroid=false verify
python3 scripts/smoke-test.py
```

Android SDK 35/build-tools 35.0.0이 설치된 개발 환경:

```bash
python3 scripts/setup-android.py --configure
./gradlew -PincludeAndroid=true verifyAndroid
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar
# 연결한 개발 기기/에뮬레이터에서 로컬 서버만 사용
adb reverse tcp:8080 tcp:8080
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.pleos.assistant.mock/ai.pleos.assistant.app.ReservationActivity
```

앱에서 모의 정차 → 조건 검색 → 첫 후보 문의 → 개발용 모의 조건 확인 → 최신 상태 → 예약 내용 확인/승인 → 별도 모의 수락 → 15분 변경 승인 → 모의 미확인을 선택합니다. 원 시각/미해결 표시와 변경 차단을 확인합니다. 거절/예약 UNKNOWN은 별도 깨끗한 가상 시험 환경에서 수행합니다. 앱 데이터 삭제는 기존 예약을 복구하는 방법이 아닙니다.

## 실제 검증과 미검증

2026-11-08 로컬 `format verify` 성공. 기존 서버 200개는 UP-TO-DATE(앞선 환경 준비 시 실제 실행 성공), 신규 UX 13개는 실제 실행·실패/오류/skip 0입니다. UX 테스트는 패키징 JAR과 임시 H2·loopback HTTP를 사용합니다. 흐름·명시적 승인·UNKNOWN/거절·미확인 주행 상태·디스크 저장 실패·예약 중복 클릭·응답 유실/동일 키 재전송·Repository 재생성 후 GET 복구·계약 불일치를 검증합니다.

이 테스트는 Android Activity의 강제 종료·회전·화면 배치·실제 기기 상태 검증을 대신하지 않습니다. 이 Mac의 SDK 점검에서 Platform 35/build-tools/platform-tools/CLI/emulator가 없음을 확인했습니다. 로컬 APK/Lint/기기·영상/release는 미검증이며 원격 CI 결과는 PR과 STATUS에 별도로 기록합니다.

설계 참고: [ViewModel](https://developer.android.com/reference/androidx/lifecycle/ViewModel), [ComponentActivity](https://developer.android.com/reference/androidx/activity/ComponentActivity), [AAOS 주행 제한 소비](https://source.android.com/docs/automotive/driver_distraction/consume). 실제 주행 제한 SDK 연결은 #25에서 이 API의 초기값·변경·실패를 검증해야 합니다.

### UX-01 원격 검증 결과

코드 `5406a7a`의 [Verify 실행](https://github.com/arriveon-lab/pleos-reservation-assistant/actions/runs/42484812195)에서 **Server and contract·Android checks 통과**, 별도 PR naming도 통과했습니다. Android 앱 Java/DEX/debug APK 생성과 Lint를 확인했습니다. 신규 UX 테스트 13개, JAR MOCK/OpenAPI/H2 재시작/LIVE 차단 성공입니다. 첫 실행의 network-security-config `includeSubdomains` 누락은 수정했습니다.

앱 Lint **오류 0 / 경고 3**: 팀 targetSdk 35의 OldTargetApi 1개, 고정 AndroidX activity 1.9.3/lifecycle-viewmodel 2.8.7의 GradleDependency 2개. 기존 probe는 오류 0 / OldTargetApi 경고 1개입니다. SDK/AGP/Pleos 호환 기준을 이번 UI 작업에서 임의 변경하지 않았고 억제/baseline은 추가하지 않았습니다. SDK·AndroidX 갱신과 기기 호환성은 #25에서 검증합니다.

CI artifact `android-mock-evidence`에 debug APK·두 앱의 Lint 보고서를 보존했습니다. 앱 APK SHA-256: `888c79de273d67353db3cdee3dcf6b1bd62d55509a7f9480ef5fffda96d99fc2`. debug APK 생성 성공이며 설치/기기 실행·영상·release 서명 성공이 아닙니다. PR #27은 Draft, #5/#25/#26은 OPEN이고 병합하지 않았습니다.
