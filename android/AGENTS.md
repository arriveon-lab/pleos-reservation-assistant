# Android 작업 지침

- 루트 AGENTS.md와 docs/CODE_CONVENTIONS.md, android/README.md를 함께 읽는다. 명령은 저장소 루트에서 실행한다.
- probe는 정차 개발용 진단 앱이다. 제품 앱의 ViewModel/Repository 설계 규칙과 probe의 명시적 예외를 구분한다.
- SDK 확인: `python3 scripts/setup-android.py`. 연결: `python3 scripts/setup-android.py --configure`.
- 포맷: `./gradlew -PincludeAndroid=false format`. Android 검증: `./gradlew -PincludeAndroid=true verifyAndroid`.
- SDK가 없으면 포맷 확인까지 수행하고 APK/Lint/기기 확인을 통과로 기록하지 않는다. 테스트 NO-SOURCE를 통과 테스트 건수로 세지 않는다.
- SDK 메서드/권한/단위는 고정 버전 공식 문서·소스로 확인한다. TripInfo eta는 초이나 시간 기준은 미검증이다. Instant 변환과 source=PLEOS 서버 허용을 임의로 추가하지 않는다.
- UI 문자열은 resources, 크기는 dp/sp. SDK 콜백의 thread와 Activity 종료 후 도착 가능성을 처리하고 같은 listener를 해제한다.
- 로그에 좌표/주소/POI ID/이름/연락처를 남기지 않는다. raw ETA와 시각·비식별 상태만 기록한다.
- AAOS 우선, 기능별 가용성·권한·단절 처리, CarUxRestrictions 기준을 따른다. 기기 증거 없이 주행 사용 허용을 선언하지 않는다.
- APK 생성, adb 설치, 앱 실행, NaviHelper 수신을 각각 검증한다. 기록은 docs/STATUS.md와 outputs/CODEX_HANDOFF.md에 갱신한다.

## Code Review Rules

- UNKNOWN이 성공이나 자동 재발신으로 바뀌는 변경, ETA 시간 기준 추정, 주행 제한 우회를 지적한다.
- listener/비동기 작업이 화면 종료 뒤 남거나 SDK/HTTP 예외가 정상 빈 데이터로 감춰지는 경우를 확인한다.
- 포맷은 Spotless 결과를 사용하고 리뷰는 동작·권한·수명·계약에 집중한다.
