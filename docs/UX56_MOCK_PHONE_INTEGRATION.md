# UX-56 MOCK 추천 후보와 전화 예약 연결

2026-11-12. 이 문서는 로컬 기기 검증이며 실기 공동 인수 완료가 아니다. #56은 OPEN, PR은 Draft로 유지한다.

## 기준과 구현

- main `dc0ab0d`의 #53 계약을 사용한다. #46의 공유 브랜치에 main을 합친 `8fa56d9`에서 시작했다. #46이 미병합이므로 이 PR에도 해당 앱 구현이 포함된다.
- PUBLIC_CATALOG/SEOUL_OPEN_DATA 가상 후보만 선택 가능하다. WEB_SEARCH 후보에는 예약 버튼을 열지 않는다. 후보의 bookable/inquiryDispatchAllowed=false를 실제 예약 가능으로 바꾸지 않는다.
- 명시적 승인 후 `/api/experimental/destination-recommendations/{id}/phone-reservations` POST. 승인 전 정확한 요청과 Idempotency-Key를 별도 `mock-phone-recovery`에 저장한다. 인증은 기존 AuthSession을 사용한다.
- 응답 유실·재실행은 동일 경로 GET으로 대조한다. 404/UNKNOWN/FAILED에서는 새 POST나 다음 후보 자동 예약을 하지 않는다. DECLINED에 한해 서버가 지정한 다음 후보를 같은 이름·시각으로 다시 승인하며 previousAttemptId를 보낸다.
- CONFIRMED는 조건 MET·빈자리 AVAILABLE·명시적 bookingRequested·승인 시각 일치·MOCK/externalCalls=0을 확인한다. 예약 요청과 확정은 구분한다.
- 승인 화면은 가상 이름·인원·필수 조건·0원·내일 19시(KST) 연습 시각을 표시한다. 연습 시각은 내비 ETA가 아니다. 테스트 시나리오 선택은 MOCK 데모 전용이다.
- `mockPhoneDemo` 기본값은 false. true와 로컬 서버의 phoneReservationsMode=MOCK 표시가 모두 있어야 전송한다.

## 실행 환경

Pleos Connect 2.1.0 / API 34 / emulator-5554 / 사용자 10. 별도 설치 패키지는 `ai.pleos.assistant.mock.integrationvalidation`이다. 기존 destinationvalidation/jointvalidation의 앱 데이터·인증·예약 기록은 지우거나 이관하지 않았다.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew -PincludeAndroid=true verifyAll
python3 scripts/run-mock-phone-device.py
adb reverse tcp:8080 tcp:18088
adb reverse tcp:8081 tcp:18088
```

런처는 127.0.0.1의 gateway 18088 / Spring 18089 / Python phone 18090을 사용한다. `work/ux56-runtime`에 독립 H2·SQLite·내부 토큰을 보존한다. 이 디렉터리는 커밋하지 않는다. 실제 공급자 관측을 갱신하지 않고 fictional catalog만 새 시각으로 준비한다. 실제 전화·외부 API·유료 호출은 없다.

기기 APK 빌드는 `-PnavigationDemo=true -PmockPhoneDemo=true`와 별도 applicationId init script를 사용했다. 로컬 `work/ux56/isolated.init.gradle` 내용:

```groovy
allprojects {
    afterEvaluate { project ->
        if (project.path == ':android:app') {
            project.android.defaultConfig.applicationId = 'ai.pleos.assistant.mock.integrationvalidation'
        }
    }
}
```

기존 앱의 이전 연결 포트는 18085였다. 현재 reverse는 격리 서버 18088을 향하므로 기존 앱을 이 서버에 연결하지 않는다. 기존 서버로 돌아갈 때 reverse도 복원한다.

## 실제 수행 및 결과

| 절차 | 기대 결과 | 실제 결과 |
| --- | --- | --- |
| 가상 목적지 주입 → 화면의 데모 확인 → 추천 검색 | 현재 catalog 후보 표시 | PASS |
| 후보 선택 → 승인 창 취소 | POST 없음 | PASS |
| 후보 선택 → 명시적 승인 → 수락 | 조건/빈자리 확인 후 CONFIRMED | PASS |
| 조건 불충족 | bookingRequested=false, DECLINED | PASS |
| 제안된 다음 후보를 재승인 | previousAttemptId 연결, 새 승인 후 CONFIRMED | PASS |
| 결과 불명확 | UNKNOWN, 다른 후보 자동 예약 차단 | PASS |
| 강제 종료 → 재실행 → GET 조회 | 기존 attemptId와 확정 결과 유지 | PASS |

`PhoneDeviceChecks`는 실제 Activity 버튼과 승인 대화상자를 누르고 실제 로컬 Spring→Python HTTP를 사용했다. 음성 세션·음성 Intent는 사용하지 않았다. 목적지는 가상 좌표를 주입했으며 실제 내비 응답 연관성을 검증한 것이 아니다.

PHONE_FULL은 실제 SharedPreferences에 기록하고 PHONE_RESTORE가 강제 종료 이후 이를 복구했다. PHONE_ACCEPT/UNMET/UNKNOWN 반복 시나리오는 기존 영속 기록을 덮어쓰지 않도록 테스트 메모리 Store를 사용했다. 이 세 시나리오만으로 영속 복구를 통과 처리하지 않았다.

## 검사와 증거

- `verifyAll`, `verify`, Android APK/test APK 빌드 통과. Java 서버 464개·UX 131개, 실패/오류/skip 0. Gradle UP-TO-DATE 재사용 포함.
- `scripts/mock-agent-integration.py --verify` 통과.
- `scripts/smoke-test.py --check-contract` 통과(프로젝트 계약 검증 venv).
- 단위 검사: 중복 클릭·저장 실패·주행 제한·응답 유실/GET·404·시각 불일치·DECLINED 재승인·web 후보 제외.
- Lint errors 0, 기존 warnings 15, 신규 억제 없음.
- 초기 컴파일 오류는 중복 삽입된 검증 호출을 제거하여 해결했고 이후 검사 통과.
- 제품 APK SHA256: `9103650af4dc39bae2c845abd24b3c30cd641baf9f78243086680d8a23fbf7dc` (`work/ux56/tested.apk`).
- 로그: `work/ux56/verify-final.log`, `unit-final.log`, `final-{accept,unmet,unknown,restore}.log`, `capture-restore.log`, `http-integration.json`, `smoke.log`.
- 화면: `captures/ux56/approval.png`, `captures/ux56/restored.png`. 가상 데이터만 포함. 캡처는 로컬 증거이며 Git에는 넣지 않는다.
- 기존 예약 XML 전후 SHA256 동일: destinationvalidation `cd53323c4c0e83e38df13804d60091ab6ff4da8c6658a567023d64524590f60d`, jointvalidation `beb02ab84b57d492514117bb1e0eb7d069c3f4036e0fd1a22e5f29967b1f13b3`.

## 남은 범위

- #56: Pleos 실기 설치·네트워크·화면 확인 및 공동 인수. EC2 주소·앱 인증은 인계받아 아래와 같이 에뮬레이터에서 연결 검증했다.
- #25: 실제 차량/분할창 공동 검증. 이 문서의 에뮬레이터 결과로 실기 완료를 대신하지 않는다.
- #1: 현재 길안내 목적지 응답 연관성 및 ETA 기준은 미확정. demo 확인과 정식 자동 활성화를 구분한다.
- #52/#40: 실제 장소/전화 경로 및 팀 공동 인수는 미완료. 실통화는 별도 대상·비용 승인 필요. 이번 작업은 외부 호출 0의 MOCK이다.
- 인증 저장소 자체 유실, 실제 차량 연결 단절, 배포 서버 장애는 이번 기기 시나리오에서 미검증이다.

## 최신 EC2 인계 반영

작업 중 [#56 EC2 인계](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/56#issuecomment-6812281297)가 추가된 것을 재확인했다. `https://43.201.115.170/pleos-mock`의 TLS 검증 및 상태 조회에서 contractVersion=1.1.0, mode=MOCK, dataSource=FICTIONAL_FIXTURES, externalCallsEnabled=false를 확인했다. 서버 기준은 담당자가 명시한 `dc0ab0d`이며 원격 OS/프로세스 커밋을 직접 감사한 것은 아니다.

`-Pec2MockDemo=true -PnavigationDemo=true -PmockPhoneDemo=true`는 별도 applicationId `ai.pleos.assistant.mock.ec2validation`을 생성한다. 모든 인증·추천·예약 요청을 같은 고정 HTTPS base로 보낸다. 플랫폼 TLS/호스트 검증을 유지하며 redirect는 거부한다. 매 호출 전 MOCK 상태를 확인한다. 이 배포의 PUBLIC_CATALOG 및 외부 호출 비활성은 인계 계약에 따른다. 기본 앱은 계속 loopback이며 release에서는 이 전송을 허용하지 않는다.

EC2 빌드에서 verifyAll 및 test APK 빌드 통과. APK SHA256 `a8bd75cd7e989be1ff6ebe0f08cab9abf4e92c957afd69155fac52f03946bca8`. 에뮬레이터에서 신규 시험 세션 발급→가상 목적지 확인→후보 검색→선택→승인 취소→재승인→예약 확정→GET을 실제 버튼으로 검증했다. 로컬 중계나 adb reverse를 거치지 않는 HTTPS 요청이다. 기존 설치의 자격 증명을 옮기거나 재사용하지 않았다.

EC2 기기 로그는 `work/ux56/ec2-{full,unmet,unknown,restore}.log`, 빌드 로그는 `ec2-build.log`. PHONE_FULL/RESTORE는 보호 저장의 같은 인증·예약 기록을 사용하며, UNMET/UNKNOWN은 반복용 메모리 phone Store를 사용한다. 모든 시나리오는 가상 목적지/가상 후보/가상 이름이며 실제 전화·유료 호출 0이다. 토큰 강제 만료·실제 서버 장애·실차 검증은 미수행이다.
