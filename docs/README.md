# 팀 문서 안내

공유본 갱신: 2026-11-07 KST. 현재 실행 범위는 **로컬 단일 사용자 MOCK 서버와 SDK 진단용 Android probe**입니다. 아래 문서의 목표 기능과 제안 ERD는 구현·실연동 완료를 뜻하지 않습니다.

## 처음 합류한 팀원의 읽는 순서

1. [프로젝트 소개와 실행](../README.md), [현재 검증 상태](STATUS.md)
2. [협업 규칙](../CONTRIBUTING.md), [코드 컨벤션](CODE_CONVENTIONS.md)
3. [이슈별 상세 작업 안내](ISSUE_GUIDE.md): #1–5 담당 범위·완료 조건·동시 착수 방법
4. [기능 명세](FUNCTIONAL_SPEC.md): FR-01–17, API·오류·상태·AT-01–20
5. [API 계약 안내](../api/README.md), [API 계약 1.0.0 / OpenAPI 3.1](../api/openapi.json)
6. [ERD와 컬럼 정의](ERD.md): 현재 6개 테이블과 제안 11개 테이블의 차이

## 목적별 문서

| 문서 | 확인할 내용 | 상태 |
| --- | --- | --- |
| [STATUS](STATUS.md) | 실행한 검사와 미검증 범위 | 실행 증거 기준, 날짜 확인 |
| [PLAN](PLAN.md) | 전체 범위·일정·5명 역할·통합 순서 | 기존 개발 계획 |
| [ISSUE_GUIDE](ISSUE_GUIDE.md) | 각 이슈의 입력/출력·파일 경계·완료 조건 | 작업 해설, 이슈 상태는 GitHub가 기준 |
| [REC-01 상세 계획](REC01_PLAN.md) | #2 기반 PR과 후속 #11–13의 범위·브랜치·계약·검증 | PR #10 통합·#2 CLOSED, 실연동 미완료 |
| [REC-01 실제 연동 검토](REC01_INTEGRATION.md) | 공식 저장 제한·앱 설정 결과·HTTP 경계·재전송 제어 | 무료 쿼터 설정·HTTP loopback 검증, 실제 API 미실행 |
| [REC-01 서버·화면 계약 초안](REC01_CONTRACT_DRAFT.md) | 미리보기/예약 분리·IO/중복·출처/모드·후속 인수 조건 | 팀 합의 전 제안, API/DB 미적용 |
| [REC-03 세션 관리와 실행 경계](REC03_PREVIEW.md) | #12 소유 scope·수명/상한·동시 합류·취소·H2 경계 | 전체 188개 검증, 인증/제품 API 미연결 |
| [FUNCTIONAL_SPEC](FUNCTIONAL_SPEC.md) | 현재 동작과 목표 기능·인수 기준 | 팀 검토용 명세 |
| [ERD](ERD.md) | 관계도·컬럼·무결성·DB 전환 | DB/API 미적용 설계 초안 |
| [설계 제안](DESIGN_PROPOSALS.md) | 다중 방문 예약·피클 비교·차별화 방향 | 팀 합의 전 제안 |
| [연동 근거](INTEGRATIONS.md) | Pleos API 정책·ETA 증거·전화 PoC 조건 | 문서/정적 확인과 실행 확인 구분 |
| [Android 환경](ANDROID_SETUP.md), [probe 사용법](../android/README.md) | SDK·APK·Pleos emulator·관측 절차 | 빌드와 기기 실행 구분 |
| [GitHub 설정](GITHUB_SETUP.md) | 조직·main 보호·CI·팀원 시작 절차 | 기록된 설정 기준 |
| [에이전트·검증 환경](AGENT_HARNESS.md) | 영역별 AGENTS와 공통 검사 명령 | 사람·에이전트 공통 |
| [인계](../outputs/CODEX_HANDOFF.md) | 다음 담당자가 이어갈 작업과 한계 | 최신 공유 변경 기록 |

## 계약과 설계를 함께 바꾸는 방법

- 현재 API의 필드·enum·오류·응답은 OpenAPI와 소스를 기준으로 합니다. 명세와 다르면 PR에서 함께 고칩니다.
- ERD는 정규화 제안입니다. SQL 파일은 ERD 가져오기용이며 `server/src/main/resources/db/migration/`에 적용한 migration이 아닙니다.
- #4가 schema/migration을 조율하고 #2·#3이 데이터 의미를 함께 검토합니다. DB는 각자 실행하고 migration·가상 seed·버전을 공유합니다.
- 다중 예약과 자동 지연 대응은 아직 구현 범위로 확정하지 않았습니다. 제안 채택 시 PLAN·명세·API·ERD·관련 이슈를 함께 조정합니다.
- 문서 게시, 코드 구현, 테스트, 기기 실행, 실제 장소/전화 연동을 각각 구분해 기록합니다.

## ERD 원본과 편집본

- GitHub에서 읽는 관계도와 컬럼 정의: [ERD.md](ERD.md)
- 편집 가능한 관계도 소스: [erd/ERD.mmd](erd/ERD.mmd)
- 107개 컬럼과 15개 FK를 포함한 가져오기 표기: [erd/ERDCLOUD_IMPORT.sql](erd/ERDCLOUD_IMPORT.sql)
- [ERDCloud 편집본](https://www.erdcloud.com/d/GLtbN3YfiazAMYi6X)은 비공개입니다. GitHub 게시가 ERDCloud 접근 권한을 변경하지는 않습니다. UNIQUE 5개의 가져오기 누락과 필요한 업무 제약은 [ERD](ERD.md)에 기록했습니다.
