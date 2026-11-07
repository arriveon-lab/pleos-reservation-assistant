# Pleos 차량용 AI 예약 비서

목적지·ETA·동행자의 필수 조건으로 식당을 비교하고, 누락 정보 문의에서 예약 결과 확인까지 연결하는 차량용 서비스입니다.

현재 **로컬 MOCK 서버**를 실행하고 **SDK 진단용 debug APK**를 빌드할 수 있습니다. 성수동 이름을 사용한 가상 식당 3곳이며 실제 장소 검색·AI 모델·전화·예약이 아닙니다. `app.mode=LIVE`는 서버 시작 단계에서 차단됩니다. Pleos 기기 실행과 실통화 검증은 아직 없습니다. 상세 검증 상태는 [STATUS](docs/STATUS.md)를 기준으로 합니다.

[GitHub 저장소](https://github.com/arriveon-lab/pleos-reservation-assistant) · [작업 이슈](https://github.com/arriveon-lab/pleos-reservation-assistant/issues) · [CI 실행](https://github.com/arriveon-lab/pleos-reservation-assistant/actions)

목적지 주변 장소의 독립 조회 도구는 [REC-04 시연 안내](docs/REC04_LIVE_SEARCH.md)를 따릅니다. 기본은 가상 화면이며, 실제 조회는 명시적 실행 모드·무료 설정·키 확인 후 1회 시연으로 진행합니다. 제품 서버의 실제 예약 연동과 구분합니다.

## 빠른 시작

필수: JDK 21, Python 3.9+, 인터넷(첫 의존성 다운로드). 서버와 Android는 루트의 Gradle Wrapper 8.11.1 / Kotlin DSL을 공유합니다. 시스템 Gradle 설치는 필요 없습니다. 서버만 개발할 때 Android SDK는 필요 없습니다.

저장소 루트에서 실행합니다.

```bash
./gradlew verify
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar
```

다른 터미널에서:

```bash
python3 scripts/mock-demo.py
```

검색 → 3곳 비교 → 모의 문의 → 후보 갱신 → 사용자 예약 명령 → 모의 수락 → 모의 변경 미확인 → 원 예약 보존을 검증합니다. 서버는 `http://127.0.0.1:8080`만 수신하고, 데이터는 **실행 디렉터리의** `data/pleos.mv.db`에 저장됩니다. 종료는 서버 터미널에서 Ctrl+C. Windows는 `gradlew.bat verify`, `python scripts/mock-demo.py`를 사용합니다(Windows 실행 미검증).

일회성 통합 점검은 별도 서버 없이 실행할 수 있습니다. 임시 DB와 임의 포트를 사용하고 종료 후 정리합니다.

```bash
python3 scripts/smoke-test.py
```

## 팀원이 처음 읽을 문서

전체 목록과 읽는 순서는 [팀 문서 안내](docs/README.md)에서 확인합니다. [이슈별 상세 작업](docs/ISSUE_GUIDE.md), [기능 명세](docs/FUNCTIONAL_SPEC.md), [ERD·컬럼 정의](docs/ERD.md)를 함께 공유하며, 설계 초안과 미확정 제안은 구현 완료와 구분합니다.

1. [CONTRIBUTING](CONTRIBUTING.md): 브랜치·커밋·PR·코딩 규칙과 완료 기준
2. [API 계약](api/README.md), [OpenAPI 3.1](api/openapi.json): 차량/전화/서버 공통 계약
3. [팀 계획](docs/PLAN.md): 5명 작업 분담, 일정, 선행 조건
4. [연동 검증](docs/INTEGRATIONS.md): SDK 증거, 전화 후보 비교, 필요한 계정
5. [GitHub 조직·협업 설정](docs/GITHUB_SETUP.md): 권한·보호 규칙·팀원 시작 절차
6. [Android 설치·설정](docs/ANDROID_SETUP.md): Studio·SDK·Pleos emulator 및 프로젝트 연결
7. [인계 문서](outputs/CODEX_HANDOFF.md): 다음 작업과 알려진 한계
8. [코드 컨벤션](docs/CODE_CONVENTIONS.md): 언어별 스타일·Android 계층/수명/오류·테스트 기준
9. [에이전트·검증 환경](docs/AGENT_HARNESS.md): 영역별 AGENTS와 공통 Gradle 검사 명령

## 구성과 실행 범위

```text
server/       Java 21 / Spring Boot 3.5.16 / Flyway / H2
api/          버전 명시 OpenAPI, 예제, 상태 의미
android/      NaviHelper 원시값 수집용 probe (debug APK·Lint 확인, 기기 미검증)
scripts/      로컬 MOCK 시연·패키지 실행 검증·계약 검증
docs/         상태·팀 계획·연동 조사·조직 준비
.github/      CI와 Issue/PR 템플릿
```

단일 사용자·단일 서버 개발용입니다. 인증·소유자 격리·실전화 웹훅 검증·운영 배포가 구현되지 않았으므로 공용 서버로 노출하지 않습니다. `MOCK` 예약 결과를 실제 예약으로 안내하면 안 됩니다. 환경 파일 자동 로딩은 없으며 `.env.example`은 설정 참고용입니다.

## 제출 산출물

현재 진단용 debug APK는 `android/probe/build/outputs/apk/debug/probe-debug.apk`에 생성됩니다. 공개 GitHub 원격은 연결했습니다. release 예약 앱·release 서명·시연 영상은 없습니다. 실행 가능한 release APK를 검증한 뒤 `apk/` 또는 GitHub Releases에 제공할 예정이며, 현재 JAR·모의 API 시연·probe APK 생성은 차량 앱의 실제 동작 증거가 아닙니다.

실제 전화 발신·번호 구매·유료 API 실행은 대상, 목적, 예상 비용과 상한을 제시해 명시적 승인 후 진행합니다. 공개 라이선스는 팀 합의 전까지 지정하지 않습니다.
