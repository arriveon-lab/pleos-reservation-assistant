# 구현·검증 현황

갱신: **2026-11-04 KST**. 기준 환경: macOS arm64, Corretto OpenJDK 21.0.6, Gradle 8.11.1, Spring Boot 3.5.16, Python 3. 본 문서는 실제 실행 결과와 미검증을 구분합니다.

문서 공유 갱신: **2026-11-07 KST**. 아래 제품 실행 결과는 2026-11-04 기록입니다. ERD·기능 명세의 문서/정적 검증을 제품 재실행이나 실제 연동 성공으로 세지 않습니다.

## 공유한 설계·협업 문서

- [문서 목차](README.md), [이슈별 상세 작업](ISSUE_GUIDE.md), [기능 명세](FUNCTIONAL_SPEC.md)를 Git에서 리뷰할 수 있도록 정리했습니다. #1–5의 상태는 2026-11-07 확인 시 OPEN·담당자 미배정입니다.
- [ERD](ERD.md)는 현재 H2 6개 테이블과 구분한 11개 테이블 제안입니다. ERDCloud의 107컬럼·15FK 대조 기록과 UNIQUE 5개 누락, 가져오기용 SQL을 공유하며 DB에는 적용하지 않았습니다.
- [다중 예약·차별화·지연 대응](DESIGN_PROPOSALS.md)은 미확정 제안입니다. 기존 활성 예약 제한과 자동 지연 감지의 미구현 상태를 유지합니다.
- 공유본 정적 대조 12항목 통과: 기능 17개·인수 시나리오 20개·API 10개·오류 26개·ERD 키/관계·로컬 링크 등을 확인했습니다. `./gradlew -PincludeAndroid=false verify`도 성공했으며 15개 태스크는 모두 UP-TO-DATE였습니다. 제품 테스트를 새로 실행한 결과로 세지 않습니다.

## 확인된 결과

| 항목 | 상태 | 근거/한계 |
| --- | --- | --- |
| Gradle 통일 | 빌드 확인 | 루트 Wrapper + Kotlin DSL, 서버 기본/Android 선택 포함. Maven 실행 파일·POM은 현재 빌드에서 제거 |
| 서버 컴파일·포맷·패키징 | 통과 | `./gradlew --no-daemon :server:spotlessApply :server:clean verify --no-build-cache` |
| 자동 테스트 | **25개 통과, 실패/오류/skip 0** | `server/build/test-results/test/TEST-ai.pleos.assistant.WorkflowTest.xml` |
| 실제 HTTP 모의 시나리오 | 통과 | 패키징 JAR에서 검색→문의→갱신→예약 수락→변경 UNKNOWN→재시도 차단 |
| 영속화/재시작 | 통과 | 임시 파일 H2, Flyway V1/V2, 서버 종료·재시작 후 확정/미해결 변경 유지 |
| OpenAPI 3.1 | 통과 | 문서/예제 및 시연 HTTP 요청·응답을 JSON Schema로 대조 |
| LIVE 차단 | 통과 | `--app.mode=LIVE` 시작 실패와 LIVE_DISABLED 확인; 발신 클라이언트 없음 |
| 협업 문서·CI | **원격 Verify 통과** | 공개 조직/저장소·이슈 #1–6 생성. 첫 main Verify 및 초기 설정 PR #7의 세 검사 성공; 설정/실행 링크는 GITHUB_SETUP |
| Android SDK | **설치·연결 확인** | 사용자 약관 동의 후 ~/Library/Android/sdk에 CLI 22.0 / Platform 35 rev 2 / Build-Tools 35.0.0 / Platform-Tools 37.0.1, local.properties·IDE 모듈 opt-in 연결 |
| Android probe | **debug APK 빌드 확인** | verifyAll 및 최종 verifyAndroid 성공. Java/DEX/APK 생성, apksigner debug v2 서명 검증; 기기 실행 미검증 |
| Android Lint | **오류 0 / 경고 0** | 문자열 리소스·dp, 앱 아이콘·백업 제외 규칙 반영. android/probe/build/reports/lint-results-debug.html |
| Android 단위 테스트 | 미작성·미실행 | testDebugUnitTest NO-SOURCE. 실행 테스트 통과로 세지 않음 |
| 코드 규칙·에이전트 환경 | 로컬 검증 | CODE_CONVENTIONS / 영역별 AGENTS / format·verify·verifyAndroid·verifyAll. Android 꺼진 verifyAndroid의 명시적 실패도 확인 |
| Pleos API/ETA | 문서·소스·바이트코드 확인 | eta int/초 확인. 시간 기준·이벤트 주기·백그라운드·실행 미검증 |
| SDK 정책·가용성 | 문서 확인·설계 반영 | 사용자 공유 문서와 연결 문서를 INTEGRATIONS에 정리. 기능별 지원 확인·대체 흐름·주행 UX 제한은 구현/기기 검증 전 |
| 실제 장소/AI/전화/예약 | 미구현·미실행 | 가상 후보3개와 모의 outcome만 구현 |

계약 검증 명령: 개발 의존성 설치 후 `python scripts/smoke-test.py --check-contract`. 이번 실행은 Git 제외 `work/contract-venv`에서 수행했습니다. 로그는 `work/gradle-verify.log`, `work/smoke-contract.log`, `work/android-build.log`에 있습니다. 팀 공유 기준은 이 문서와 재현 명령이며 로그 원본을 자동 업로드하지 않습니다.

SDK/컨벤션 보강 후 로그는 `work/conventions-verify.log`, `work/android-verify.log`, `work/android-verify-final.log`입니다. 이전 android-build.log의 SDK 부재는 해결했습니다. 서버 동작 코드는 이번 보강에서 변경하지 않았으며 verify의 테스트 태스크는 기존 25개 성공 결과를 UP-TO-DATE로 확인했습니다. GitHub 첫 main에서도 Server and contract·Android checks 성공을 확인했습니다.

최종 debug APK: `android/probe/build/outputs/apk/debug/probe-debug.apk`, SHA-256 `66a7b62196afbe4fa5a77525f92001b092425cf428d44e639a9e88e56477da75`. 개발용 서명·SDK_PROBE이며 release 산출물이 아닙니다. 이후 빌드 결과가 바뀌면 해시도 다시 기록합니다.

## 구현 범위

- 검색/조회/문의/예약/변경/모의 결과 API, 전역 DB mutex를 이용한 서비스 트랜잭션
- 요청 키·본문 해시, 이벤트 ID 중복 방지, 동일 tripId 활성 예약 중복 차단
- 명시적 매장 수락·시간·비용 일치 확인 후 확정, 그 외 UNKNOWN 보존
- 변경 진행/미확인 중 기존 confirmedTime 유지, pendingChangeCallId 보존, 10분 재요청 간격
- 추가 비용 별도 승인과 누적 상한, 필수 가족 조건 우선, 정보 만료, NOW/ARRIVAL 구분
- UNKNOWN 대조 전 재발신 차단, 원래 요청 시각과 변경 이력 보존

25개 테스트는 동시 같은 키/다른 키 예약, 동시 같은 이벤트, 중복·변조 이벤트, 실패 시 롤백, 수락 시간·금액 불일치, 예약/변경 UNKNOWN, 거절, 가족 필수 조건, 예약 불가/빈자리 구분, 만료·늦은 관측, 허용 변경·추가 비용·누적 비용·재통화 간격, 입력 검증, 동의/권한 분리 등을 다룹니다.

Android Studio/SDK 설치·경로 연결·Pleos 이미지 준비는 [ANDROID_SETUP](ANDROID_SETUP.md)를 따릅니다. `python3 scripts/setup-android.py`는 설치 상태 점검, `--configure`는 설치 확인 후 Git 제외 로컬 경로와 IDE 모듈 설정만 작성합니다.

## 알려진 한계와 다음 순서

1. Android Studio·Pleos Emulator/Virtual Console 확보 후 기기 probe. 빌드 SDK와 debug APK는 준비됨. `./gradlew -PincludeAndroid=true verifyAndroid`로 재현하고 기기 실행은 별도로 확인.
2. 실제 ETA 시간 기준·콜백 주기·권한·백그라운드·목적지 변경을 확인한 뒤 변환 adapter와 테스트 추가. 지금 source=PLEOS는 서버에서 거부.
3. 제품 Android 화면, CarUxRestrictions, Gleo/STT/TTS 연동, release 서명·시연 영상은 미구현.
4. 시범 지역과 장소 데이터/전화 업체 선정. 계정·번호·허용 대상·예산 승인 전 실발신 없음.
5. 실제 통화 도입 전 사용자 인증/소유권·승인 증빙, webhook 서명, outbox/timeout 대조, UNKNOWN 수동 해결 추가. 현재 API는 loopback 단일 사용자용이며 공개 배포 대상이 아님.
6. 지속 ETA 지연 감지·자동 변경은 후속 범위. 현재는 명시적 모의 변경 명령만 지원. 학습 동의는 저장만 하며 학습 미구현.
7. 팀 경험 확인 후 운영 DB 결정. H2 전역 lock은 단일 서버 MVP 한계가 있음.

Git: **main**을 기본으로 [arriveon-lab/pleos-reservation-assistant](https://github.com/arriveon-lab/pleos-reservation-assistant)에 첫 커밋 `81f0b40`을 공개했습니다. 작업 브랜치는 실제 이슈 번호, 커밋/PR은 type(scope) 규칙입니다. 초기 설정은 `chore/6-github-bootstrap`의 PR #7에서 기록합니다. 조직 멤버 2FA를 필수로 설정했고, main은 리뷰 1명·필수 CI 3개·관리자 포함 보호 규칙으로 운영합니다. 설정과 원격 실행 근거는 [GITHUB_SETUP](GITHUB_SETUP.md)을 확인합니다.

이 Mac에는 저장소 로컬 commit-msg 훅과 메시지 템플릿을 활성화했습니다. 정상/잘못된 제목·브랜치·PR 이슈 번호 검사와 훅 실행을 확인했습니다. 전역 Git 설정은 바꾸지 않았습니다. Android 설정 점검은 설치된 SDK/JDK를 확인하고 Git 제외 local.properties와 gradle-local.properties를 작성했습니다. 개인 셸 설정이나 전역 Codex 설정은 변경하지 않았습니다.

실제 전화 발신 **0회**, 번호 구매 **0건**, 유료 API 실행 **0회**. SDK/빌드 도구/공식 문서 다운로드와 localhost 테스트만 수행했습니다. GitHub 무료 조직/공개 저장소 생성과 소스 업로드는 사용자 요청·약관 동의 후 수행했습니다. 공모전 제출·팀원 초대는 수행하지 않았습니다.

GitHub 업로드 직전 로컬 `verifyAll` 및 `smoke-test.py --check-contract`도 통과했습니다. 로그는 Git 제외 `work/github-bootstrap-verify.log`, `work/github-bootstrap-smoke.log`입니다.
