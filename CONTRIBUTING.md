# 협업 규칙

## 시작과 작업 범위

`AGENTS.md`, `README.md`, `docs/STATUS.md`를 먼저 읽습니다. 이 저장소는 차량·추천·전화·서버·UX 5명이 계약을 공유하는 모노레포입니다. 이슈 하나에 담당자 한 명과 검증 가능한 완료 조건을 둡니다. 다른 사람의 변경을 되돌리거나 같은 파일을 동시에 크게 수정하지 않습니다.

## 브랜치와 이슈 번호

기본 브랜치는 사용자 결정에 따라 **main**입니다. 5명 MVP 팀의 기본 흐름은 `main → 이슈별 작업 브랜치 → PR → main`입니다. 초기에는 별도 상시 develop 브랜치를 두지 않습니다. main은 리뷰·검증을 통과한 통합 기준으로 유지합니다.

새 브랜치 전 `AGENTS.md`, 이 문서, `git status`, `git branch -a`, `git remote -v`를 확인합니다. 다른 저장소에서는 그 저장소의 기존 관례를 우선합니다.

작업 브랜치 형식은 **`type/<GitHub 이슈 번호>-<short-topic>`**입니다.

| 예시 이슈 | 브랜치 예시 | 의미 |
| --- | --- | --- |
| #12 목적지 조회 | `feat/12-navi-probe` | 기능 개발 |
| #18 예약 시각 오류 | `fix/18-booking-time` | 오류 수정 |
| #23 SDK 설치 문서 | `docs/23-android-setup` | 문서 |
| #25 CI 환경 | `chore/25-ci-setup` | 환경/유지보수 |

번호는 GitHub에서 이슈를 만든 뒤 부여된 값을 씁니다. `dev-1`처럼 별도 순번을 관리하지 않고 팀원별 번호도 부여하지 않습니다. 위 번호는 예시이며 원격 이슈가 생성된 상태가 아닙니다. 계획 문서의 VEH-01 등은 작업 분류용 코드로, 실제 브랜치에는 GitHub 번호를 씁니다. 소문자 영문·숫자·하이픈, 최대 100자입니다.

원격 생성 후 작업 시작 예시:

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/12-navi-probe
```

이슈 하나에 담당자와 완료 조건을 정하고 작업 브랜치를 만듭니다. 초기 첫 커밋은 main에 한 번 작성할 수 있으며, 이후 변경은 PR로 합칩니다. 팀원이 같은 작업을 나누면 별도 하위 이슈 번호를 받아 파일 충돌을 줄입니다. 브랜치 번호를 사람이 선점하거나 추측하지 않습니다.

## 커밋과 PR 제목

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)를 바탕으로 이 팀에서는 scope를 필수로 정합니다.

```text
type(scope): 구체적인 변경 내용

변경 이유와 필요한 검증 결과

Refs #12
```

제목은 공백 포함 72자 이내, 한국어 또는 영어를 사용합니다. 제목 뒤에 본문이 있다면 빈 줄로 구분합니다. 한 커밋은 한 목적을 다룹니다. `수정`, `작업`, `완료`만 적지 말고 무엇이 달라졌는지 씁니다.

| type | 용도 |
| --- | --- |
| feat | 새 기능 |
| fix | 버그 수정 |
| refactor | 동작을 유지하는 구조 개선 |
| docs | 문서 |
| test | 테스트 추가/수정 |
| style | 포맷·공백 등 동작 변화 없는 수정 |
| build | 빌드/의존성/패키징 |
| ci | CI 워크플로 |
| perf | 성능 개선 |
| chore | 그 외 저장소 유지보수 |
| revert | 기존 변경 되돌림 |

scope는 `server`, `android`, `api`, `recommendation`, `calls`, `ux`, `docs`, `infra`, `deps`, `repo` 중 변경의 중심 영역을 씁니다. scope 추가는 규칙 검사 스크립트와 이 문서를 함께 수정합니다.

```text
feat(android): 목적지 조회 probe 추가
fix(server): 미확인 변경 중 원 예약 시각 유지
build(deps): Spring Boot 의존성 갱신
docs(repo): Android SDK 설치 절차 정리
chore(repo): 초기 프로젝트와 협업 기반 구성
```

호환성을 깨는 변경은 `feat(api)!: ...`처럼 `!`와 본문의 `BREAKING CHANGE:` 설명을 사용하고 계약 담당 리뷰를 받습니다. API 주요 버전 정책도 지킵니다.

PR 제목도 같은 형식입니다. PR 본문에는 작업 브랜치의 이슈와 같은 `Closes #12`를 별도 줄로 적습니다. 대상은 main, 최소 리뷰 1명, CI 통과 후 **squash merge**를 사용합니다. squash 커밋 제목은 PR 제목과 일치하도록 설정합니다. 리뷰 중 main 갱신이 필요하면 merge commit을 추가하기보다 작업 브랜치를 rebase하고, 이미 공유한 브랜치는 팀원과 조율합니다. 공유 브랜치 force push와 main 직접 push를 하지 않습니다.

GitHub에서 `Server and contract`, `Android checks`, `PR naming` 체크를 보호 규칙에 연결합니다. 원격/플랜 설정 전까지 보호 규칙이 강제되는 상태는 아닙니다. main에 합친 후 작업 브랜치를 삭제합니다. 검증된 제출 시점은 `v0.1.0` 같은 태그로 남기되 아직 태그는 생성하지 않았습니다.

## 로컬 Git 설정과 자동 검사

클론한 팀원은 저장소 루트에서 한 번 실행합니다. 전역 설정은 변경하지 않습니다.

```bash
python3 scripts/setup-git.py
```

`.githooks/commit-msg`와 `.gitmessage`를 활성화하며, 기존 hooksPath/template가 다르면 덮어쓰지 않고 알려줍니다. 자동 검사는 형식과 이슈 연결을 검사하고 변경 내용의 적절성은 리뷰에서 판단합니다. 원격 이슈의 실제 존재/내용은 GitHub에서 확인합니다. 로컬 훅을 쓰지 않는 환경에서도 PR 제목/브랜치/이슈 연결 CI는 적용됩니다.

```bash
python3 scripts/check-conventions.py --title 'feat(android): 목적지 조회 추가'
python3 scripts/check-conventions.py --branch feat/12-navi-probe
```

## 코드와 계약

- 상세 규칙은 [CODE_CONVENTIONS](docs/CODE_CONVENTIONS.md), 사람·에이전트 공통 검증 절차는 [AGENT_HARNESS](docs/AGENT_HARNESS.md)를 따릅니다.
- 서버·Android Java는 Google Java Format 1.28.0, Kotlin/Gradle DSL은 ktfmt 0.54 kotlinlangStyle, UTF-8, LF. `./gradlew -PincludeAndroid=false format` 후 `verify`합니다. Android 변경은 SDK 환경에서 `./gradlew -PincludeAndroid=true verifyAndroid`도 실행합니다.
- 클래스/record `UpperCamelCase`, 메서드/변수 `lowerCamelCase`, 상수/enum `UPPER_SNAKE_CASE`, 패키지 소문자. API JSON은 camelCase, 오류 code는 UPPER_SNAKE_CASE.
- 요청 경계에서 Bean Validation, 상태 전이는 `WorkflowService`, DB 접근은 `Store`, 후보 평가와 사실 갱신은 `RecommendationEngine`으로 분리합니다. Controller에 업무 규칙을 넣지 않습니다.
- 변경 명령·이벤트 처리는 트랜잭션 안에서 DB lock → 중복 검사 → 상태 검증 → 저장 순서로 수행합니다. 현재 전역 mutex는 로컬 MVP용이며 다중 사용자 배포 전에 aggregate 단위로 재설계합니다.
- 시각은 `Instant`/RFC3339, 표시만 KST. 금액은 정수 KRW. 서버 시간을 새로 붙여 오래된 관측을 최신으로 만들지 않습니다.
- API 변경은 `api/openapi.json`, 예제, 호출자, 테스트를 같은 PR에서 갱신합니다. 호환성 깨짐은 `/api/v2`로 분리하고 계약 담당 리뷰를 받습니다.
- Flyway 적용 이력이 생긴 SQL은 수정하지 않고 다음 버전 migration을 추가합니다. DB 바이너리를 공유하지 않습니다.
- `learningConsent`는 저장만 하며 현재 학습은 하지 않습니다. 예약 권한·추가 비용 승인으로 해석하지 않습니다.

서버·Android 빌드 도구는 Gradle Kotlin DSL로 통일합니다. 공통 플러그인 버전은 루트 `settings.gradle.kts`, 서버 의존성은 Spring Boot BOM과 `server/gradle.lockfile`로 고정합니다. 의존성 변경 시 `./gradlew verify --write-locks`로 갱신하고 lockfile diff를 리뷰합니다. Wrapper 버전 변경은 Android AGP와 Spring Boot 호환 범위를 함께 확인합니다.

## 검증과 리뷰

```bash
./gradlew verify
python3 scripts/smoke-test.py
```

계약 변경 시 `api/README.md`의 검증 명령도 실행합니다. 예약·동시성·권한·외부 부작용 변경은 정상/거절/UNKNOWN/중복/경계 시나리오를 포함합니다. 단순 문서 변경에 불필요한 코드 테스트를 추가하지 않습니다.

PR에는 문제와 변경 후 동작, 실행한 명령과 결과, MOCK/LIVE 구분, 미검증 내용을 적습니다. 최소 다른 팀원 1명 리뷰를 권장하고 예약 상태·비용·전화·권한 변경은 서버/전화 담당의 리뷰를 요청합니다. GitHub 보호 규칙은 조직 생성 후 적용해야 하며 현재 로컬에서 강제되지 않습니다.

완료는 코드 작성이 아니라 관련 테스트 통과·계약 일치·문서 상태 갱신까지입니다. `문서 확인`, `정적 확인`, `빌드/테스트 확인`, `기기 확인`, `미검증`을 구분합니다. CI 설정 파일의 존재를 GitHub 실행 성공으로 보고하지 않습니다.

## 데이터와 외부 실행

키·토큰·실제 연락처·가족/위치 정보·녹음·서명·개인정보 동의서를 Git에 올리지 않습니다. 테스트는 가상 데이터만 사용합니다. SDK 배포물은 필요한 라이선스/재배포 조건을 확인하고 의존성으로 받습니다. 수신 번호 allowlist, 사용자 승인 기록, 비용 상한, 발신 횟수 제한, kill switch가 마련되기 전 LIVE adapter를 활성화하지 않습니다.

외부 발신/유료 API/번호 구매는 실행 전에 대상·목적·예상 비용과 상한을 제시하고 명시적 승인을 받습니다. 이미 승인된 구체적 실행 범위 안에서는 불필요하게 다시 확인받지 않습니다.
