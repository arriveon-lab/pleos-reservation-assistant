# 코드 컨벤션

팀 기준 확정일: 2026-11-04. 브랜치·커밋·PR 규칙은 [CONTRIBUTING](../CONTRIBUTING.md), 검증 상태는 [STATUS](STATUS.md)를 따릅니다. 아래에서 **현재 적용**과 **제품 앱 구현 시 적용할 설계 규칙**을 구분합니다.

## 언어와 포맷 — 현재 적용

| 대상 | 기준 | 자동 검사 |
| --- | --- | --- |
| 서버 Java | JDK 21, Google Java Style, 기본 들여쓰기 2칸 | Spotless 7.0.4 + Google Java Format 1.28.0 |
| Android Java | 소스 수준 17, 서버와 같은 스타일 | 루트 Spotless, SDK 없이 검사 가능 |
| Kotlin / Gradle Kotlin DSL | 기본 들여쓰기 4칸, ktfmt kotlinlangStyle | Spotless + ktfmt 0.54 |
| XML / YAML / JSON | UTF-8, LF, 2칸, 리소스 이름 snake_case | EditorConfig; Android XML 의미 검사는 Lint |
| Python | 4칸, snake_case, 표준 라이브러리 우선 | EditorConfig; 변경 스크립트 직접 실행 |

코드 줄 길이는 100자를 기준으로 하되 포맷터 결과가 우선합니다. Java/Kotlin의 이름·아키텍처·오류 처리까지 포맷터가 검사한다고 해석하지 않습니다. 현재 Android는 Java 진단용 probe입니다. Kotlin 포맷 설정은 준비했지만 Kotlin 컴파일 플러그인·Compose 제품 앱을 도입한 상태는 아닙니다. 도입 PR에서 AGP/JDK/Kotlin/Compose 호환 버전을 함께 고정합니다.

```bash
./gradlew -PincludeAndroid=false format
./gradlew -PincludeAndroid=false verify
./gradlew -PincludeAndroid=true verifyAndroid
```

`format`은 소스를 수정합니다. `verify`는 공통 포맷과 서버 테스트/JAR, `verifyAndroid`는 공통 포맷과 Android Lint/단위 테스트 태스크/APK를 검사합니다. 소스가 없는 테스트 태스크의 `NO-SOURCE`는 테스트 통과 건수가 아닙니다. Lint error는 실패시키며 warning은 PR에서 원인과 처리 여부를 설명합니다. 오류를 숨기기 위한 전체 disable, 무조건적인 baseline 생성, `abortOnError=false`는 사용하지 않습니다. 꼭 필요한 억제는 해당 코드에 이유와 추적 이슈를 남깁니다.

## 이름·타입·경계

- 클래스/record/interface는 `UpperCamelCase`, 메서드/변수는 `lowerCamelCase`, 상수/enum은 `UPPER_SNAKE_CASE`, 패키지는 소문자입니다. `data`, `manager`, `util` 같은 이름만으로 책임을 감추지 않습니다.
- `BookingRequest`, `BookingResponse`, `BookingUiState`, `VehicleRepository`, `PleosNaviDataSource`처럼 경계와 역할을 드러냅니다. 서버 DTO와 SDK 모델을 화면·도메인 전체에 직접 전파하지 않습니다.
- Kotlin은 불변 `val`과 null-safe 타입을 우선하고 `!!`, `GlobalScope`, 변경 가능한 컬렉션의 외부 노출을 피합니다. Java의 nullable SDK/IO 결과는 경계에서 검사하고 누락 상태를 명시합니다.
- 시간은 이름에 단위/의미를 넣습니다(`rawEtaSeconds`, `elapsedRealtimeMs`, `observedAt`). API 시각은 RFC3339/Instant, 표시는 KST, 금액은 정수 KRW입니다. `eta`가 초라는 사실만으로 epoch나 duration으로 변환하지 않습니다.
- 주석은 이유·제약·근거를 설명합니다. SDK/권한/단위 추정은 공식 문서 링크와 확인 버전을 남기고 미확인 부분을 명시합니다. TODO는 조직 생성 후 실제 이슈 번호와 완료 조건에 연결합니다.

## Android 제품 앱 구조 — 구현 시 적용

Android 공식 아키텍처 권고를 바탕으로 **UI → ViewModel → Repository → DataSource/SDK adapter** 방향을 사용합니다. 작은 앱은 패키지로 분리하고, 독립 배포/테스트 등의 필요가 생길 때 모듈을 나눕니다. 단순 전달만 하는 UseCase를 일괄 생성하지 않습니다. 여러 화면에서 재사용하는 복잡한 규칙에 도메인 계층을 둡니다.

| 계층 | 책임 | 금지/주의 |
| --- | --- | --- |
| UI | 상태 표시, 사용자 의도 전달, 주행 제한에 맞는 상호작용 | 직접 HTTP/SDK 호출·예약 확정 판정 |
| ViewModel | 화면 상태와 사용자 액션 처리, 불변 UiState 노출 | Activity/View/Context 보관, SDK listener 직접 소유 |
| Repository | 데이터 출처 통합, freshness/오류/가용 상태 전달 | UI 문구·View 의존 |
| DataSource/adapter | SDK/HTTP 경계, 원시값·권한·연결 상태 변환 | 확인되지 않은 ETA 변환, UNKNOWN을 성공으로 바꾸기 |

단방향 상태 흐름을 유지합니다. 화면은 상태를 구독하고 액션을 전달합니다. Kotlin 제품 앱을 도입하면 StateFlow와 수명주기에 맞는 collection을 사용하고, callbackFlow 등록은 awaitClose에서 같은 listener로 해제합니다. ViewModel 작업은 viewModelScope로 관리하고 취소를 실패/재시도 신호로 삼지 않습니다. `CancellationException`을 삼키지 않습니다. Compose 채택은 Pleos 화면·주행 제약 검증과 함께 결정하며 일반 모바일 UI 권고만으로 차량 사용 적합성을 확정하지 않습니다.

SDK 콜백 스레드를 가정하지 않습니다. UI 변경은 main thread에서, IO/계산은 적절한 executor/dispatcher에서 수행합니다. 등록·해제 소유자는 하나로 정하고 실패/화면 종료/재연결 시 중복 listener와 늦은 callback을 처리합니다. 백그라운드 수신은 문서와 기기 증거로 확인합니다.

현재 `android/probe`는 SDK 수신값 관측을 위한 **명시적 예외**입니다. 단일 Activity에서 진단 UI와 SDK 수명을 관리하며 onStop 이후 callback 수신을 측정합니다. 이 구조를 제품 예약 앱의 기본 구조로 복제하지 않습니다. 서버 연결·예약·전화 기능은 없습니다.

## Android UI·리소스·차량 API

- 표시 문구는 `res/values/strings.xml`, 리소스 파일·ID는 `snake_case`, 크기는 dp/sp를 사용합니다. 사용자 데이터나 에러의 원문 문자열을 그대로 표시하지 않습니다.
- 제품에는 loading/empty/available/unsupported/permissionDenied/disconnected/error 상태와 수동 입력 대체 흐름을 정의합니다. 미지원·권한 거부를 빈 정상 데이터로 감추지 않습니다.
- AAOS 표준 API를 우선하고 Pleos 전용 API는 특화 기능에 사용합니다. 모델명 분기 대신 실행 시 지원·접근 가능 상태를 확인합니다. 실제 메서드 이름은 고정 SDK 문서/소스에서 확인합니다.
- 주행 UX는 CarUxRestrictionsManager의 현재 restriction을 기준으로 처리합니다. 속도가 0이라는 이유만으로 입력/예약 화면을 허용하지 않습니다. 구현·기기 검증 없이 distractionOptimized를 선언하지 않습니다.
- 앱/서버/테스트에서 MOCK·SDK_PROBE·LIVE의 의미를 유지합니다. 이름·주소·좌표·POI·전화·키·녹음은 로그에서 제외합니다. 오류는 안전한 코드/종류와 비식별 요청 ID로 추적합니다.

## 서버와 외부 부작용 — 현재 적용 기준

Controller는 요청 검증/응답 변환, WorkflowService는 업무 상태 전이, Store는 DB 접근, RecommendationEngine은 후보 평가를 맡습니다. 요청 경계의 Bean Validation과 서비스의 상태 검증을 함께 사용합니다. 예외를 빈 catch로 감추거나 SQL/스택을 API 응답으로 노출하지 않습니다.

변경 명령은 트랜잭션 안에서 lock → 중복 검사 → 상태 검증 → 저장 순서를 지킵니다. 지금의 전역 DB mutex는 단일 서버 MVP용이며 운영 확장 시 aggregate 단위로 재설계합니다. 전화/유료 호출을 DB 트랜잭션 안에서 바로 실행하는 패턴은 도입하지 않습니다. 실연동 전에 outbox·timeout·서명 검증·대조 흐름을 마련합니다.

API 변경은 OpenAPI·예제·호출자·테스트를 같은 PR에 반영합니다. Idempotency-Key와 eventId의 의미를 유지하고, UNKNOWN에는 자동 재발신하지 않습니다. 빈자리·예약 가능·예약 확정은 별도입니다. 변경 미확인 중 원 예약 정보를 보존하며, 명시적 시간·비용 수락이 있어야 확정합니다.

## 테스트·의존성·리뷰

실패할 수 있는 행동을 테스트합니다. SDK adapter는 미지원·권한 거부·연결 단절·늦은 콜백·해제·단위 경계, 예약은 중복·동시성·거절·UNKNOWN·비용/시간 경계를 다룹니다. 기기 전용 동작은 장치/이미지/SDK 버전과 기대·실제 로그를 기록합니다. 구현을 그대로 반복하는 테스트나 문서만 바꾼 PR의 무의미한 테스트는 추가하지 않습니다.

플러그인 버전은 settings.gradle.kts, 의존성은 각 모듈에서 명시합니다. `+`, SNAPSHOT, 자동 버전 업그레이드를 사용하지 않습니다. 서버는 BOM과 lockfile을 갱신해 리뷰하고 Android는 고정 좌표와 해석된 transitive 의존성을 확인합니다. 의존성 추가는 목적·라이선스·API/기기 호환을 PR에 설명합니다. SDK 원본·빌드 산출물·개인 경로·서명키는 커밋하지 않습니다.

## 근거와 팀 결정

아래 공식 문서를 2026-11-04 확인했습니다. 문서 권고와 팀 선택을 구분합니다.

- [Google Java Style](https://google.github.io/styleguide/javaguide.html): 서버와 Android Java의 공통 스타일로 **팀이 선택**했습니다.
- [Android Kotlin Style](https://developer.android.com/kotlin/style-guide): 이름·불변성·4칸 들여쓰기 기준. 정확한 자동 포맷은 [ktfmt 0.54](https://github.com/Kotlin/ktfmt/tree/v0.54)의 kotlinlangStyle로 **팀이 고정**합니다.
- [Spotless Gradle](https://github.com/diffplug/spotless/tree/gradle/7.0.4/plugin-gradle): 포맷 검사와 자동 적용 분리.
- [Android 아키텍처 권고](https://developer.android.com/topic/architecture/recommendations): 계층·Repository·ViewModel·단방향 상태·수명주기 관리의 근거.
- [Android Lint](https://developer.android.com/studio/write/lint): APK 빌드와 별도로 Lint를 CI에서 실행하는 근거.
- [Pleos SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api), [가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability): AAOS 우선과 runtime 지원 확인의 근거. 상세 증거는 [INTEGRATIONS](INTEGRATIONS.md).
