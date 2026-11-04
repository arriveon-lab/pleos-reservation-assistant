# 에이전트와 로컬 검증 환경

별도 모델 호출 서비스 없이 저장소 지침·Gradle 태스크·기존 MOCK 시연 스크립트를 공통 작업 환경으로 사용합니다. 같은 명령을 사람과 에이전트, CI가 실행합니다. 이 문서를 추가했다고 외부 에이전트 세션이나 유료 API를 실행한 것은 아닙니다.

## 작업 지침

루트 `AGENTS.md`는 공통 안전·계약·작업 흐름을, `android/AGENTS.md`와 `server/AGENTS.md`는 해당 영역의 검증·리뷰 기준을 담습니다. 루트에서 시작한 에이전트도 수정 영역의 AGENTS.md를 명시적으로 읽도록 루트 지침에 연결했습니다. 도구마다 중첩 지침을 발견하는 방식이 다르므로 다른 도구에서는 루트 지침을 entry point로 지정합니다.

[OpenAI 공식 AGENTS.md 가이드](https://learn.chatgpt.com/docs/agent-configuration/agents-md)에 따라 프로젝트/하위 디렉터리 지침을 사용합니다. 이 저장소 전용 규칙을 사용자 전역 설정에 넣지 않으며, 모델·자동 승인·샌드박스·다중 에이전트 실행 정책을 덮어쓰지 않습니다. 현재 규모에는 별도 MCP/모델 설정 파일이 필요하지 않습니다.

## 재현 가능한 확인 순서

```bash
# 1. 클론 직후: Git 로컬 훅과 템플릿
python3 scripts/setup-git.py

# 2. 서버 담당자 / Android SDK 없는 환경
./gradlew -PincludeAndroid=false verify
python3 scripts/smoke-test.py

# 3. Android 담당자: SDK 존재 확인과 로컬 연결
python3 scripts/setup-android.py --configure
./gradlew -PincludeAndroid=true verifyAll

# 4. 포맷 수정이 필요할 때만
./gradlew -PincludeAndroid=false format
```

계약 변경은 [api/README](../api/README.md)에 따라 Python 개발 의존성을 설치하고 `python3 scripts/smoke-test.py --check-contract`를 실행합니다. Smoke는 loopback·임시 DB·MOCK에 한정합니다. Android APK 설치와 기기 수신은 [android/README](../android/README.md)의 별도 절차이며 verifyAll이 수행하지 않습니다.

SDK 설정 스크립트는 다운로드·라이선스 수락을 하지 않습니다. 개발자는 [ANDROID_SETUP](ANDROID_SETUP.md)으로 SDK를 설치한 뒤 연결합니다. 포맷 검사는 Android opt-in과 무관하게 Java/Kotlin/Gradle 소스를 포함합니다. verifyAndroid는 모듈이 꺼져 있으면 명확한 오류로 실패하며 성공처럼 skip하지 않습니다.

## 결과물과 완료 보고

| 검사 | 결과 위치 | 해석 |
| --- | --- | --- |
| 서버 테스트 | server/build/reports/tests/test/index.html | 실행/실패/skip 건수 확인 |
| Android Lint | android/probe/build/reports/lint-results-debug.html | error 0, warning 처리 검토 |
| debug APK | android/probe/build/outputs/apk/debug/probe-debug.apk | 진단용 APK, release 제출용 아님 |
| Android 단위 테스트 | android/probe/build/reports/tests/testDebugUnitTest/ | 테스트 추가 후 생성; NO-SOURCE는 실행 없음 |
| 기기 로그 | Git 제외 captures/ | 허용된 필드만, 이미지/SDK/앱 버전과 시나리오 기록 |

문서 확인 / 빌드·테스트 / APK 생성 / 기기 실행 / 실제 서비스 연동을 분리해 기록합니다. 실패 명령과 차단 원인을 남기고 다음 담당자가 재현할 수 있게 합니다. 진행 상태는 STATUS, 다음 작업은 PLAN, 인계는 CODEX_HANDOFF에 반영합니다. GitHub CI는 원격에서 실제 실행된 경우에만 성공으로 보고합니다.
