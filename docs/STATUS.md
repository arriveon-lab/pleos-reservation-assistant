# 구현·검증 현황

**최신 상태 (2026-11-07):** 사용자 승인 후 [PR #10](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/10)을 squash merge했습니다(`a55de3a`). #2는 CLOSED, #11/#12/#13은 OPEN입니다. 최신 main에서 `feat/12-place-preview`를 생성해 [REC-03 내부 세션 첫 구현](REC03_PREVIEW.md)을 진행했고, 서버 테스트 169개와 JAR smoke/계약 검증을 통과했습니다. 신규 실행기는 제품 경로·공개 API에 연결하지 않았습니다. 아래 범위 분리/병합 대기 기록은 병합 전 이력입니다.

갱신: **2026-11-07 KST**. 기준 환경: macOS arm64, Corretto OpenJDK 21.0.6, Gradle 8.11.1, Spring Boot 3.5.16, Python 3. 본 문서는 실제 실행 결과와 미검증을 구분합니다.

REC-01 서버 재검증: **2026-11-07 KST**. Android·SDK 기록은 2026-11-04입니다. ERD·기능 명세의 문서/정적 검증을 제품 재실행이나 실제 연동 성공으로 세지 않습니다.

## 공유한 설계·협업 문서

- [문서 목차](README.md), [이슈별 상세 작업](ISSUE_GUIDE.md), [기능 명세](FUNCTIONAL_SPEC.md)를 Git에서 리뷰할 수 있도록 정리했습니다. 문서 공유 당시 #1–5는 OPEN·담당자 미배정이었고, 이후 확인한 #2의 배정 상태는 아래 착수 기록을 따릅니다.
- [ERD](ERD.md)는 현재 H2 6개 테이블과 구분한 11개 테이블 제안입니다. ERDCloud의 107컬럼·15FK 대조 기록과 UNIQUE 5개 누락, 가져오기용 SQL을 공유하며 DB에는 적용하지 않았습니다.
- [다중 예약·차별화·지연 대응](DESIGN_PROPOSALS.md)은 미확정 제안입니다. 기존 활성 예약 제한과 자동 지연 감지의 미구현 상태를 유지합니다.
- 공유본 정적 대조 12항목 통과: 기능 17개·인수 시나리오 20개·API 10개·오류 26개·ERD 키/관계·로컬 링크 등을 확인했습니다. `./gradlew -PincludeAndroid=false verify`도 성공했으며 15개 태스크는 모두 UP-TO-DATE였습니다. 제품 테스트를 새로 실행한 결과로 세지 않습니다.

## 2026-11-07 REC-01 P1과 P2 검증 기록

- GitHub #2는 OPEN, 담당자 `tiemo0708`임을 확인했습니다. 사용자 선택은 성수·서울숲 음식점, Kakao Local 우선 검토입니다.
- [상세 계획](REC01_PLAN.md)에 변경할 코드, adapter 내부 계약, P0~P4 순서, 테스트 16개 시나리오와 #3·#4·#5 협업 경계를 작성했습니다. 반경 1km·수집 최대 15곳 등은 실험 초기안입니다.
- `PlaceSearchAdapter`·query/summary/성공/실패 타입, `MockPlaceSearchAdapter`·`MockCandidateFactory`, 오프라인 `KakaoPlaceMapper`를 구현했습니다. 공개 API·migration·의존성 변경은 없습니다.
- 추천 엔진에서 가상 데이터 생성을 제거하고 scope·ETA·값 타입·관측/만료 시각·모드를 검증합니다. 입력 후보 전체를 평가한 뒤 최대 3곳을 선택하며 결과가 적으면 그대로 반환합니다.
- 실제 Spring/H2/MockMvc에서 빈 결과·1·2·5개 입력, 저장 후 조회, 원 응답 snapshot 재전송과 중복 adapter 호출 방지를 검증했습니다. 기존 가상 ID·facts·TTL·예약/변경 시연도 유지됩니다.
- `./gradlew -PincludeAndroid=false format verify` 성공. 테스트는 **104개 실행, 실패/오류/skip 0**입니다. 기존 WorkflowTest 25개와 신규 79개를 재실행했습니다. 시작 전 verify는 15개 태스크 UP-TO-DATE였으며 이 결과와 구분합니다.
- `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 성공: 패키징 JAR·MOCK HTTP 흐름·OpenAPI·H2 재시작·LIVE 시작 차단. 로컬 증거는 Git 제외 `work/rec01-tests.json`, `work/rec01-smoke.log`에 있습니다.
- 현재 서비스는 네트워크 없는 MOCK adapter만 주입받습니다. 실제 HTTP·transport timeout·DB 잠금 밖 IO·사용 조건 검토·실데이터/모의 예약 표시는 후속 작업입니다. 실제 장소 API 조회·전화 발신·비용 발생 작업은 없습니다. Android 검증과 원격 CI는 이번에 새로 실행하지 않았습니다.

## 2026-11-07 REC-01 HTTP 경계와 정책 검토

- 공식 FAQ와 2026-08-20 지도/로컬 담당 답변에서 응답·가공 데이터의 별도 저장 제한을 확인했습니다. 근거와 H2 snapshot/commands 저장의 충돌, 팀 앱 생성 절차, #4·#5 검토안은 [실제 연동 검토](REC01_INTEGRATION.md)에 기록했습니다. 사용자는 팀 앱이 아직 없다고 확인했습니다.
- `KakaoPlaceSearchAdapter`를 추가해 127.0.0.1 가상 서버에서 목적지 3개·정상/빈 결과·오류·redirect·headers/body timeout·취소·수신 크기·UTF-8을 검증했습니다. Spring 미등록이며 제품 검색 서비스에 연결하지 않았습니다.
- 최종 `format verify` 성공. **130개 실행, 실패/오류/skip 0**, 6개 suite: 기존 업무 25개 + 장소/추천 79개 + HTTP 경계 26개. 로컬 기록은 Git 제외 `work/rec01-http-verify.log`, `work/rec01-http-tests.json`입니다.
- 최종 패키징 JAR에 대해 `smoke-test.py --check-contract`로 MOCK HTTP·OpenAPI·H2 재시작·LIVE 차단을 다시 확인했습니다. 로컬 기록은 `work/rec01-http-smoke.log`입니다.
- 공개 API·Model·migration·의존성 변경 없음. 실제 TLS/키/무료 쿼터·IP 제한·실데이터 품질·연결 장애의 과금 요청 수는 미검증입니다. JDK transport 내부 재전송 제어를 실제 실행 전에 검토합니다. Kakao 실제 요청·전화·유료 작업은 0회입니다.

## 2026-11-07 Kakao 개발자 앱 설정

- 사용자 개인 Kakao 계정으로 로그인 후, 앱 이름 ArriveOn·운영 단체 ArriveOn Lab·자동차/교통수단 카테고리로 앱을 생성했습니다. 운영정책 확인과 앱 생성은 사용자 명시 동의 후 실행했습니다.
- 첫 활성화 앱의 무료 쿼터 대상이 이후 변경 불가하다는 안내를 사용자에게 제시하고 동의 후 카카오맵을 ON으로 설정했습니다. 이 앱의 일간 무료 쿼터 제공 문구와 배지를 확인했습니다.
- 유료 API 화면에서 카카오맵·카카오톡 공유 모두 사용 안 함을 확인했습니다. 비즈월렛 연결·결제 설정은 하지 않았습니다. 일간 쿼터 화면에는 조회 기간 내 호출 없음이 표시되었습니다.
- 설정 확인 화면은 로컬 Git 제외 `work/kakao-setup-complete.png`, `work/kakao-paid-disabled.png`, 비밀 키 없는 설정 요약은 `work/kakao-setup-result.json`에 있습니다. 개인 계정 이메일·다른 앱 정보·앱 키는 공개 문서에 기록하지 않습니다.
- 이번 단계는 실제 계정 설정과 문서 갱신입니다. 제품 코드·API·DB 변경은 없고 로컬 Gradle 테스트는 다시 실행하지 않았습니다. 앞선 코드 검증은 130개 통과이며 PR #10의 서버·Android·naming CI도 통과했습니다.
- 실제 장소 API 호출·키 값 복사/저장·전화·비용 발생은 없습니다. 데이터 보관 허용 범위와 실제 IO/중복 실행·화면 계약은 여전히 후속 작업입니다.

## 2026-11-07 정책 문의 등록과 지도 역할 확인

- 사용자 승인 문안 그대로 [Kakao DevTalk 정책 문의](https://devtalk.kakao.com/t/local-api/152647)를 공개 등록하고 제목·본문 일치를 확인했습니다. 원문 목록의 실시간 사용/세션 보유, 전화 공급자 전달, 예약 이력 보관, 별도 계약 가능성을 문의했습니다. 현재 답변 대기이며 보관 허가를 받은 상태가 아닙니다.
- [INTEGRATIONS](INTEGRATIONS.md#내비게이션과-장소-검색의-역할)에 Pleos 내비게이션/목적지/ETA와 외부 식당 후보 데이터의 역할을 정리했습니다. 카카오맵 설정은 장소 REST API 준비이며 차량 지도 교체가 아닙니다. NaviHelper 3.0.5 공식 안내·배포 SDK 공개 메서드를 대조했습니다.
- 앱 설정 문서 커밋 f4a1001의 서버·Android·PR naming CI가 통과했습니다. 이번 추가 변경도 문서만 포함하며 제품 테스트를 로컬 재실행한 결과가 아닙니다. 실제 장소 요청·전화·유료 작업은 없습니다.

## 확인된 결과

| 항목 | 상태 | 근거/한계 |
| --- | --- | --- |
| Gradle 통일 | 빌드 확인 | 루트 Wrapper + Kotlin DSL, 서버 기본/Android 선택 포함. Maven 실행 파일·POM은 현재 빌드에서 제거 |
| 서버 컴파일·포맷·패키징 | 2026-11-07 통과 | `./gradlew -PincludeAndroid=false format verify` |
| 자동 테스트 | **130개 통과, 실패/오류/skip 0** | `server/build/test-results/test/TEST-*.xml` 6개 suite, 기존 WorkflowTest 25개 포함 |
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

기존 업무 테스트 25개는 동시 같은 키/다른 키 예약, 동시 같은 이벤트, 중복·변조 이벤트, 실패 시 롤백, 수락 시간·금액 불일치, 예약/변경 UNKNOWN, 거절, 가족 필수 조건, 예약 불가/빈자리 구분, 만료·늦은 관측, 허용 변경·추가 비용·누적 비용·재통화 간격, 입력 검증, 동의/권한 분리 등을 다룹니다. 신규 장소/추천 테스트 79개를 더한 총 실행 수는 104개입니다.

Android Studio/SDK 설치·경로 연결·Pleos 이미지 준비는 [ANDROID_SETUP](ANDROID_SETUP.md)를 따릅니다. `python3 scripts/setup-android.py`는 설치 상태 점검, `--configure`는 설치 확인 후 Git 제외 로컬 경로와 IDE 모듈 설정만 작성합니다.

## 알려진 한계와 다음 순서

1. Android Studio·Pleos Emulator/Virtual Console 확보 후 기기 probe. 빌드 SDK와 debug APK는 준비됨. `./gradlew -PincludeAndroid=true verifyAndroid`로 재현하고 기기 실행은 별도로 확인.
2. 실제 ETA 시간 기준·콜백 주기·권한·백그라운드·목적지 변경을 확인한 뒤 변환 adapter와 테스트 추가. 지금 source=PLEOS는 서버에서 거부.
3. 제품 Android 화면, CarUxRestrictions, Gleo/STT/TTS 연동, release 서명·시연 영상은 미구현.
4. 성수·서울숲 시범 범위 구체화, Kakao 사용 조건 확인과 장소 adapter 구현, 전화 업체 선정. 계정·번호·허용 대상·예산 승인 전 실발신 없음.
5. 실제 통화 도입 전 사용자 인증/소유권·승인 증빙, webhook 서명, outbox/timeout 대조, UNKNOWN 수동 해결 추가. 현재 API는 loopback 단일 사용자용이며 공개 배포 대상이 아님.
6. 지속 ETA 지연 감지·자동 변경은 후속 범위. 현재는 명시적 모의 변경 명령만 지원. 학습 동의는 저장만 하며 학습 미구현.
7. 팀 경험 확인 후 운영 DB 결정. H2 전역 lock은 단일 서버 MVP 한계가 있음.

Git: **main**을 기본으로 [arriveon-lab/pleos-reservation-assistant](https://github.com/arriveon-lab/pleos-reservation-assistant)에 첫 커밋 `81f0b40`을 공개했습니다. 작업 브랜치는 실제 이슈 번호, 커밋/PR은 type(scope) 규칙입니다. 초기 설정은 `chore/6-github-bootstrap`의 PR #7에서 기록합니다. 조직 멤버 2FA를 필수로 설정했고, main은 사용자 요청에 따라 2026-11-07 필수 승인 0명으로 변경했습니다. 필수 CI 3개·최신 main 반영·대화 해결·관리자 포함 보호 규칙을 적용합니다. 설정과 원격 실행 근거는 [GITHUB_SETUP](GITHUB_SETUP.md)을 확인합니다.

이 Mac에는 저장소 로컬 commit-msg 훅과 메시지 템플릿을 활성화했습니다. 정상/잘못된 제목·브랜치·PR 이슈 번호 검사와 훅 실행을 확인했습니다. 전역 Git 설정은 바꾸지 않았습니다. Android 설정 점검은 설치된 SDK/JDK를 확인하고 Git 제외 local.properties와 gradle-local.properties를 작성했습니다. 개인 셸 설정이나 전역 Codex 설정은 변경하지 않았습니다.

실제 전화 발신 **0회**, 번호 구매 **0건**, 유료 API 실행 **0회**. SDK/빌드 도구/공식 문서 다운로드와 localhost 테스트만 수행했습니다. GitHub 무료 조직/공개 저장소 생성과 소스 업로드는 사용자 요청·약관 동의 후 수행했습니다. 공모전 제출·팀원 초대는 수행하지 않았습니다.

GitHub 업로드 직전 로컬 `verifyAll` 및 `smoke-test.py --check-contract`도 통과했습니다. 로그는 Git 제외 `work/github-bootstrap-verify.log`, `work/github-bootstrap-smoke.log`입니다.


## 2026-11-07 REC-01 재전송 제어 후속 검증

- `KakaoPlaceSearchAdapter`는 HTTP/1.1을 사용하고 `jdk.httpclient.disableRetryConnect=true`, `jdk.httpclient.redirects.retrylimit=1`이 JVM 시작 인수와 현재 속성에 모두 설정돼야 생성됩니다. 실행 중 뒤늦게 설정한 값이나 잘못된 옵션을 거부합니다. 전역 JVM/셸 설정은 변경하지 않았고 Gradle 테스트 JVM에만 옵션을 전달합니다.
- 새 JVM에서 시작 설정과 정상 생성을 검증했습니다. loopback의 응답 전 단절·불완전 본문·재사용 연결 단절에서 추가 요청이 없으며 정상 빈 결과는 성공했습니다. 신규 14개, 기존 130개를 재실행해 **144개 실행, 실패/오류/skip 0**입니다.
- `./gradlew -PincludeAndroid=false format verify` 및 `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 통과. 로컬 증거: Git 제외 `work/rec01-retry-verify.log`, `work/rec01-retry-tests.json`, `work/rec01-retry-smoke.log`.
- [P3 계약 초안](REC01_CONTRACT_DRAFT.md)에 저장 검색과 미리보기 분리, IO/중복 처리, 출처·예약 모드와 화면 상태, #3/#4/#5 검토·인수 조건을 작성했습니다. 팀 합의·공개 API·DB 적용은 아직 없습니다.
- 정책 문의 페이지를 새로고침해 운영자 답변이 없는 상태를 확인했습니다. 실제 Kakao 요청·키 값 복사·전화·유료 작업 0회, 제품 MOCK 주입과 LIVE 차단 유지. 실제 TLS·프록시·사업자 호출/과금 집계는 미검증입니다. Android는 이번 로컬 변경/검사 대상이 아닙니다.


## 2026-11-07 이슈·브랜치·PR 범위 정리

- 사용자 요청으로 #2 제목을 “REC-01: 장소 adapter 기반 구현과 MOCK 검증”으로 조정했습니다. 원래의 미완료 조건은 #11(정책/공급자), #12(계약·IO/중복/복구), #13(실계정 API 검증)으로 실제 생성해 이관했습니다. 세 이슈 모두 tiemo0708 담당이며 OPEN입니다. #4/#5의 기존 범위/배정은 바꾸지 않았습니다.
- 현재 브랜치 `feat/2-place-adapter`를 유지합니다. PR #10은 “feat(recommendation): 장소 adapter 기반과 MOCK 검증 추가”, `Closes #2`와 `Refs #11/#12/#13`으로 정리했습니다. 이번 작업은 머지가 아니며 PR은 Draft입니다.
- 후속 브랜치 계획: `docs/11-place-data-policy`, `feat/12-place-preview`, `feat/13-place-api-validation`. 아직 생성하지 않았습니다. #2 통합 뒤 최신 main에서 해당 번호의 브랜치를 만듭니다.
- 후속 이관 시 완료 범위·원래 범위·담당/의존성·Refs 연결을 함께 기록하도록 CONTRIBUTING과 PR 템플릿을 보강했습니다. 기존 Closes 규칙·CI·main 보호는 변경하지 않았습니다.
- 제품 코드 변경은 없습니다. 기존 144개 테스트·JAR smoke 증거를 유지하며 이번에는 명명·문서 링크·diff 정적 검사를 수행합니다. 실제 Kakao 조회·전화·비용 0회와 MOCK/LIVE 차단을 유지합니다.


## 2026-11-07 PR #10 통합과 REC-03 첫 구현

- 사용자가 머지와 후속 착수를 승인해 PR #10을 Ready 전환 후 필수 CI·충돌·미해결 대화·현재 head를 확인하고 squash merge했습니다. merge commit: `a55de3aaa6e0b715f77495984ae96ca9ddc30b06`. 원격 `feat/2-place-adapter`는 삭제됐으며 #2 CLOSED, #11/#12/#13 OPEN을 확인했습니다. main 보호나 Closes 규칙 우회는 없습니다.
- 최신 main에서 `feat/12-place-preview`를 생성했습니다. package-private `PlacePreviewSession`은 진행 중 동일 세대/query 합류, 세대 충돌/구세대 거부, 목적지 변경·취소·제한 시간·늦은 응답, 세션당 실제 실행 1개+최신 대기 1개를 구현합니다. Spring bean·공개 endpoint·기존 workflow에는 등록하지 않았습니다.
- 단위 22개와 실제 Spring/H2 통합 3개를 추가했습니다. 조회 지연 중 기존 검색 진행, preview DB 미저장, DB mutex 점유와 preview 독립, 트랜잭션 안 start 거부를 검증했습니다. 기존 144개를 재실행해 **169개 실행, 실패/오류/skip 0**입니다.
- `./gradlew -PincludeAndroid=false format verify`와 `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 통과. 로컬 Git 제외 증거: `work/rec03-preview-verify.log`, `work/rec03-preview-tests.json`, `work/rec03-preview-smoke.log`.
- [REC-03 구현 범위](REC03_PREVIEW.md)에 세션 메모리 수명, 완료 결과 재조회/캐시 없음, 취소/응답 세대, 재시작 후 중복 기억 없음과 후속 계약을 기록했습니다. #12의 팀 검토·인증/세션 수·전체 호출 상한·재시작 복구·navigation freshness·공개 API/화면은 미완료입니다.
- 공개 API/Model·migration·의존성 변경 없음. 실제 Kakao 조회·키 값 복사·전화·유료 실행 0회, 제품 MOCK과 LIVE 차단 유지. Android 기기 검증이나 실제 서비스 연결 성공으로 세지 않습니다.
