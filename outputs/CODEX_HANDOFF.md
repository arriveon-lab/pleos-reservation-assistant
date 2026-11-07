# Pleos 차량용 AI 예약 비서 — 작업 인계

문서 갱신: 2026-11-07 KST. 기존 제품 실행 기록은 2026-11-04이며, 현재 검증 상태의 기준 문서는 `docs/STATUS.md`입니다.

## 작업 위치와 사용자의 최신 결정

- 프로젝트: 이 저장소 루트. 개인 작업 디렉터리는 공유 문서에 기록하지 않습니다.
- 최초의 빌드 전 초안에서 로컬 MOCK 서버와 협업 기반을 구현·검증했습니다.
- **사용자의 후속 요청에 따라 Maven에서 Gradle로 통일했습니다.** 루트 Gradle 8.11.1 Wrapper, Kotlin DSL, Java 21, Spring Boot 3.5.16. Android는 AGP 8.9.2/Java 17 소스 수준.
- 기본 브랜치 `main`. 사용자 요청과 약관 동의 후 GitHub Free 조직 `arriveon-lab`, 공개 저장소 `pleos-reservation-assistant`를 생성하고 첫 커밋 `81f0b40`을 업로드했습니다. 원격 URL·협업 설정은 `docs/GITHUB_SETUP.md`. 초기 이슈 #1–5, 설정 기록 #6을 생성했고 담당자는 미배정입니다.
- 후속 요청으로 공식 웹 레퍼런스 기반 코드 규칙과 영역별 AGENTS/검증 환경을 추가했습니다. 사용자가 Google SDK 약관에 동의해 이 Mac의 빌드용 SDK 설치·프로젝트 연결·debug APK/Lint까지 확인했습니다.
- 실제 외부 전화·번호 구매·유료 API 실행은 없었고, 실행 승인을 받은 적도 없습니다.
- 이전 인계 원문과 Maven 설정은 로컬 Git 제외 `work/CODEX_HANDOFF.initial.md`, `work/legacy-maven/`에 보존했습니다. 현재 빌드에는 사용하지 않습니다.

## 먼저 읽을 것

`AGENTS.md` → `README.md` → `CONTRIBUTING.md` → `docs/STATUS.md` → `api/README.md`.
팀 일정은 `docs/PLAN.md`, 계정/업체/SDK 증거는 `docs/INTEGRATIONS.md`, 조직 준비는 `docs/GITHUB_SETUP.md`. 코드 규칙은 `docs/CODE_CONVENTIONS.md`, 검증 환경은 `docs/AGENT_HARNESS.md`, 수정 영역의 `android/AGENTS.md` 또는 `server/AGENTS.md`도 읽습니다.

## 재현 명령

저장소 루트에서:

```bash
./gradlew verify
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar
# 별도 터미널
python3 scripts/mock-demo.py
```

임시 DB·임의 포트에서 패키지 실행/재시작/LIVE 차단 검증:

```bash
python3 scripts/smoke-test.py
```

계약까지 대조:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements-dev.txt
.venv/bin/python scripts/smoke-test.py --check-contract
```

전체 포맷 `./gradlew -PincludeAndroid=false format`. 서버 의존성 변경 시 `./gradlew -PincludeAndroid=false verify --write-locks` 후 `server/gradle.lockfile` 리뷰.

Android 환경은 이 Mac의 `~/Library/Android/sdk`에 설치했습니다. 루트 Git 제외 local.properties/gradle-local.properties가 연결되어 있습니다. 새 팀원은 SDK 설치 후 `python3 scripts/setup-android.py --configure`. Android 확인은 `./gradlew -PincludeAndroid=true verifyAndroid`, 서버까지 함께는 `./gradlew -PincludeAndroid=true verifyAll`입니다.

## 이번에 구현한 것

- v1 REST API: 검색/후보, 모의 문의 작업, 결과 주입, 예약 요청/조회, 변경 요청/조회
- `WorkflowService` 트랜잭션, `Store` DB mutex, 요청 키+본문 해시, eventId 중복 방지
- 같은 tripId의 PENDING/CONFIRMED/UNKNOWN 예약 중복 차단. 같은 여정 재검색은 같은 tripId 유지
- 명시적 수락·시간·비용 일치 때만 CONFIRMED. ANSWERED나 통화 완료만으로는 확정하지 않음
- 변경 UNKNOWN이면 기존 CONFIRMED/confirmedTime과 pendingChangeCallId를 함께 보존; 자동 재시도 차단
- 추가 비용 사용자 확인·누적 정책 한도, 재문의/변경 10분 간격
- 가족 필수 조건·미확인 정보·만료 처리, NOW 대기와 ARRIVAL 예상 대기 분리
- 출처/mode/evidenceRef/observedAt/appliesAt/validUntil/certainty/scope. 늦게 온 관측은 원 관측 시각 보존
- `MockCallAdapter`에는 네트워크 클라이언트/번호/키가 없음. MOCK 외 설정은 시작 실패
- OpenAPI 3.1 `api/openapi.json` 계약 1.0.0 및 실제 HTTP 스키마 검사
- 로컬 시연·JAR 기동·Flyway/H2 재시작 검증 스크립트
- Gradle Wrapper/의존성 lock/Google Java Format/EditorConfig/Git 속성/CI/Issue·PR 템플릿/협업 문서
- Android NaviHelper 원시값 probe 소스·manifest·선택 모듈 구성과 로그 검증 절차

## 실행 결과

- **Gradle clean 빌드·포맷 검사·테스트 25개 통과**, 실패/오류/skip 0
- 패키징 JAR HTTP 흐름 및 H2 재시작 후 확정/미해결 변경 유지 통과
- OpenAPI 문서·예제·실제 시연 요청/응답 대조 통과
- LIVE 시작 차단 확인
- GitHub 첫 main의 `Server and contract`, `Android checks` 성공. 초기 설정 PR #7은 실제 #6 이슈 브랜치를 사용하고 PR naming을 포함한 세 검사를 통과했습니다. main 보호 규칙과 최신 결과는 GITHUB_SETUP 및 원격 설정을 확인합니다.
- 이전 SDK 부재 오류는 해결. **verifyAll 및 최종 verifyAndroid 성공**, Java/DEX/debug APK 생성·debug 서명 검증, Lint **오류 0/경고 0**. Android 테스트 소스는 없어 NO-SOURCE이고 기기 실행은 미검증
- 코드 규칙: Java GJF 1.28.0, Kotlin/Gradle ktfmt 0.54 kotlinlangStyle, Spotless 7.0.4. 서버만 포함해도 Android 소스 포맷을 검사. Android 비활성화 상태의 verifyAndroid는 명확히 실패함을 확인
- SDK CLI 22.0 / Platform 35 rev 2 / Build-Tools 35.0.0 / Platform-Tools 37.0.1. Studio·Emulator·Pleos 이미지 미설치. 전역 셸/Codex 설정 변경 없음
- APK: `android/probe/build/outputs/apk/debug/probe-debug.apk`. 현재 SHA-256과 Lint report는 STATUS 참조
- 기존 로그: Git 제외 `work/gradle-verify.log`, `work/smoke-contract.log`. 최신 환경 검증: `work/conventions-verify.log`, `work/android-verify.log`, `work/android-verify-final.log`. 서버 동작 변경은 없고 기존 25개 테스트 성공 결과를 UP-TO-DATE로 확인

## SDK에 관해 새로 확인한 사실

- NaviHelper 3.0.5와 navi-data 3.0.4 공식 AAR/classes.jar에 대해 javap 확인
- `getDestinationInfo()`, `requestTrip(TripRequest)`, `onDestinationInfo`, `onTripInfo` 존재. `getTripInfo()`를 만들지 말 것
- 공식 sources JAR의 `TripInfo.kt`: eta는 int이며 **단위 seconds**라는 주석 확인
- `DestinationInfo.kt`: remainTime은 남은 이동 시간, 초라는 설명 확인
- TripInfo eta의 남은 시간/epoch/자정 기준 등 **시간 기준은 아직 불명확**. 임의 Instant 변환 금지. 서버 source=PLEOS는 검증 전 거부
- sources JAR 공식 URL과 버전 증거는 `docs/INTEGRATIONS.md`, 실제 probe 절차는 `android/README.md`
- probe 로그는 개인정보 위치 대신 관측 시각·monotonic 시각·rawEtaSeconds·상태를 기록. backend 연결 없음

## 다음 우선 작업

사용자가 추가 공유한 SDK API 정책과 연결된 가용성/주행 규제 문서는 `docs/INTEGRATIONS.md`의 API 선택 정책 절에 반영했습니다. 다음 차량 구현은 이 기준을 먼저 읽고 기능 미지원·권한 거부·서비스 단절 대체 흐름을 포함합니다. 이번 반영은 문서/계획 변경이며 기기 검증 완료를 뜻하지 않습니다.

1. 팀원에게 README 기준 재현 확인을 받고 실제 GitHub ID·역할·백업 owner·라이선스 결정. 조직/공개 원격은 생성했으며 이슈 #1–5의 담당자를 배정합니다. 조직 2FA 필수 정책은 적용했으며 CODEOWNERS는 팀원 초대 후 실제 담당자로 등록. 유료 플랜/Actions 비용은 승인 없이 실행하지 않음.
2. 빌드 SDK와 debug APK는 준비됨. Pleos 계정/SDK 신청, Android Studio와 Pleos Emulator/Virtual Console 확보 후 probe 설치·실행. 실제 콜백/권한·초기화 실패 후 재실행·종료 중 늦은 콜백 등 수명 경계도 기기에서 확인.
3. ETA 단위는 확인됐으므로 시간 기준·갱신 주기·권한·백그라운드·도착 이벤트 증거 확보, 변환 adapter+테스트.
4. Android 제품 UI/후보 선택/명시적 예약 승인/변경 미확인 안내, CarUxRestrictions, Gleo 또는 STT/TTS. 현재 probe는 제출용 앱 아님.
5. 장소 사업자와 시범 지역, 전화 업체 선택. Twilio/CLOVA/KT 비교와 검증 계획은 INTEGRATIONS. 계정·협의 번호·예상 비용/상한·명시적 승인 없이는 실발신 금지.
6. LIVE 전 인증/사용자 소유권·승인 증빙·allowlist·비용/횟수 제한·웹훅 서명·outbox/timeout·UNKNOWN 대조 해결 구현.
7. 운영 DB는 팀 경험 확인 후 결정. 지속 ETA 지연 감지/자동 변경/학습은 후속 범위. 현재 변경 API는 모의 수동 명령.
8. release APK·시연 영상·접수양식/개별 서명·심사용 접근 확인. 현재 진단 debug APK만 있고 release 앱/영상/서명/제출 없음.

## 유지할 규칙과 한계

- MOCK과 LIVE를 API·화면·저장 데이터에서 구분. 가상 성수동 3곳은 실제 장소 추천이 아님.
- 예약 UNKNOWN은 대조 전 재발신 금지. 변경 미확인은 기존 예약 정보를 유지하지만 매장의 현재 상태를 재확인했다는 뜻은 아님.
- API는 단일 사용자 localhost 개발용. 승인 boolean은 운영 인증 증빙이 아님. 공개 배포하지 말 것.
- 모든 boolean/정수 요청 필드는 OpenAPI required에 맞게 0/false도 명시. null/누락/알 수 없는 필드/타입 강제변환 거부.
- 명령 재전송은 같은 Idempotency-Key. replay는 최초 response snapshot, 최신 상태는 GET. outcome은 eventId로 전역 중복 방지.
- 실제 가족/개인 위치/연락처/녹음·서명·키는 테스트나 Git에 넣지 않음.
- 현재 법률/사업자 약관 검토는 미완료이며 출시 적법성 확정 금지.
- 브랜치 전 지침과 기존 이름을 확인. main에서 type/<GitHub 이슈번호>-<topic>으로 작업하고 PR→squash merge. dev-1 별도 순번이나 사람별 번호가 아님. 커밋/PR은 type(scope): 설명, 72자 이내. CONTRIBUTING의 허용 type/scope를 따름.
- scripts/setup-git.py로 로컬 훅/템플릿 설정. PR naming CI는 브랜치·제목·Closes #번호를 검사. 첫 main의 원격 Verify는 성공했으며 최신 PR 검사는 GitHub에서 확인.
- Android 설치 절차는 docs/ANDROID_SETUP.md. SDK 설치 후 scripts/setup-android.py --configure로 local.properties와 gradle-local.properties 연결. -PincludeAndroid가 로컬 설정보다 우선.

공식 접수 마감 2026-11-15, PT 12-03~04를 기준으로 한 내부 계획은 `docs/PLAN.md`. 팀원 포함 최대5인 요건은 공식 안내 확인, 개인 자격/참가부문/정확한 마감 시각은 추가 확인이 필요합니다.

## 2026-11-06 ERDCloud 설계 초안

- 사용자 요청으로 비공개 ERDCloud 도표를 생성: https://www.erdcloud.com/d/GLtbN3YfiazAMYi6X
- 11 테이블, 107 컬럼, 11 PK, 15 FK를 가져오고 SQL 내보내기로 이름·타입·NULL·키 관계를 대조했다.
- 사용자→trip→검색→후보, 예약→변경→통화→이벤트 흐름으로 테이블을 수동 재배치해 겹침과 교차선을 줄였다. 새로고침 후 위치와 메모 유지 확인.
- ERDCloud 가져오기에서 UNIQUE 5개가 누락되어 도표 메모에 명시했다. 실제 마이그레이션에는 원본 ERDCLOUD_IMPORT.sql의 UNIQUE 및 업무별 조건부 제약을 별도로 구현해야 한다.
- 로컬 원본·대조 자료는 outputs/erd/에 보관. 팀 공유 관계도·컬럼·검증 요약은 docs/ERD.md, 가져오기 SQL은 docs/erd/ERDCLOUD_IMPORT.sql, Mermaid 원본은 docs/erd/ERD.mmd를 사용한다.
- 설계 초안이며 현재 DB/API에 적용하지 않았다. MySQL 문법은 ERD 가져오기용이며 운영 DB 선정 결정이 아니다. 실제 전화·유료 API 실행 없음.


## 2026-11-07 팀 공유 문서 정리

- 읽는 순서와 문서 상태는 docs/README.md, 상세 기능은 docs/FUNCTIONAL_SPEC.md, 이슈별 역할·병행 작업은 docs/ISSUE_GUIDE.md로 정리했다. 기존 outputs의 검토본보다 docs의 공유본을 우선한다.
- docs/ERD.md와 docs/erd/의 SQL·Mermaid는 11개 테이블의 설계 제안이다. 107컬럼·15FK·5UNIQUE 표기를 공유하지만 DB migration으로 실행하지 않았다. ERDCloud는 비공개이며 링크 게시가 팀원 권한을 바꾸지 않는다.
- 다중 방문 예약, 아이 동반 운전자 타깃, 주차·도보·대기 비교, 지연 감지/변경 제안의 MVP 승격은 docs/DESIGN_PROPOSALS.md에 미확정 제안으로 기록했다. 현행 활성 예약 제한·OpenAPI·구현 동작을 변경하지 않았다.
- #1–5는 2026-11-07 GitHub API 확인 시 OPEN·미배정, #6은 CLOSED다. 문서 게시 이슈는 기능 완료와 별도로 관리한다.
- 제품 실행/기기/실통화 상태는 기존 STATUS 기록과 구분한다. 실제 전화·번호 구매·유료 API 실행 없음.
- 문서 게시 추적은 GitHub #8, 브랜치 docs/8-team-design-docs. 기능 이슈 #1–5는 이 PR에서 닫지 않는다. 정적 대조 12항목과 naming/diff 검사 통과, 서버 verify 성공(15개 태스크 UP-TO-DATE)으로 검증했다. 로컬 대조 상세는 Git 제외 work/team-docs-validation.json에 있다.


## 2026-11-07 리뷰 승인 선택 정책

- 사용자 명시 요청으로 main의 required_approving_review_count를 1에서 0으로 변경했다. 리뷰 없이 필수 CI 통과 후 squash merge할 수 있다.
- 필수 CI 3개와 strict, enforce_admins, 선형 이력, 대화 해결, force push/삭제 금지는 유지됨을 API로 확인했다. PR #9의 REVIEW_REQUIRED 차단 해제 확인.
- CONTRIBUTING·GITHUB_SETUP·PLAN·STATUS를 새 정책에 맞춰 갱신했다. 이 작업은 PR을 자동으로 병합하거나 CI를 우회하는 설정이 아니다.
