# GitHub 조직과 협업 설정

기준일: 2026-11-04 KST. [ArriveOn Lab](https://github.com/arriveon-lab)의 공개 모노레포 [pleos-reservation-assistant](https://github.com/arriveon-lab/pleos-reservation-assistant)를 사용합니다. 조직은 GitHub Free, 기본 브랜치는 `main`입니다.

## 저장소 설정

| 항목 | 운영 설정 |
| --- | --- |
| 공개 범위 | Public |
| 기본 브랜치 | main |
| 병합 | Squash만 허용, 제목은 PR 제목, 본문은 PR 본문 |
| 작업 브랜치 | merge 후 자동 삭제 |
| 문서·이슈 | 저장소 docs + Issues/템플릿, Wiki 비활성화 |
| Actions 권한 | 기본 read, Actions의 PR 승인 금지 |
| 비밀정보 보호 | Secret scanning + Push protection 활성화 |
| 의존성 보안 | Dependabot alerts 활성화, 자동 업데이트 PR은 아직 미설정 |
| 조직 멤버 기본 권한 | read, 저장소 생성은 owner만 가능 |
| 조직 인증 | 모든 멤버 2단계 인증 필수 |
| 공개 라이선스 | 미지정. 팀 소유권·재사용 조건 합의 후 별도 PR |

공개 저장소의 표준 GitHub-hosted runner는 무료 범위입니다. 현재 워크플로는 `ubuntu-latest`와 `ubuntu-24.04`만 사용하며 larger runner·유료 플랜·유료 API를 활성화하지 않았습니다. 공개 저장소용 Secret scanning/Push protection의 무료 범위도 확인했습니다. [Actions 과금](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [Secret scanning](https://docs.github.com/en/code-security/concepts/secret-security/about-alerts), [Push protection](https://github.blog/changelog/2023-05-09-secret-scannings-push-protection-is-available-on-public-repositories-for-free/)

## main 보호 규칙

main은 다음 규칙으로 운영합니다. 현재 적용값은 [브랜치 설정](https://github.com/arriveon-lab/pleos-reservation-assistant/settings/branches) 또는 아래 API로 확인할 수 있습니다.

- PR 필수, 다른 리뷰어 1명 승인, 새 변경 시 기존 승인 무효화
- `Server and contract`, `Android checks`, `PR naming` 통과 및 최신 main 반영
- 대화 해결 필수, 선형 이력, 관리자에게도 동일 규칙 적용
- force push·브랜치 삭제 금지

```bash
gh api repos/arriveon-lab/pleos-reservation-assistant/branches/main/protection
```

초기 설정 PR은 팀원 초대 및 보호 규칙 적용 전에 소유자가 CI를 확인하고 한 번 통합합니다. 이후 PR에는 다른 리뷰어가 필요합니다. 현재 소유자는 `tiemo0708` 한 명이며, 팀원 ID가 정해지면 초대·write 권한·백업 owner를 지정합니다. 다른 리뷰어가 아직 없다는 이유로 보호 규칙을 임의 해제하지 않습니다.

`.github/CODEOWNERS.example`은 예시이며 자동 리뷰 요청에 적용되지 않습니다. 실제 담당자/팀을 확인한 뒤 CODEOWNERS로 등록합니다. 사용자의 본인 재인증 후 조직 전체 2FA 필수 정책을 적용했습니다. 기존 계정의 인증 수단이나 비밀번호는 변경하지 않았습니다.

## 팀원 시작 절차

```bash
git clone https://github.com/arriveon-lab/pleos-reservation-assistant.git
cd pleos-reservation-assistant
python3 scripts/setup-git.py
./gradlew -PincludeAndroid=false verify
```

Android 담당자는 [ANDROID_SETUP](ANDROID_SETUP.md)에 따라 SDK를 설치한 뒤 `python3 scripts/setup-android.py --configure`, `./gradlew -PincludeAndroid=true verifyAndroid`를 실행합니다. SDK 설치 경로·키·서명·빌드 산출물은 Git에서 제외합니다.

실제 초기 작업 이슈는 [#1 차량](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1), [#2 장소](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/2), [#3 전화](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/3), [#4 서버](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/4), [#5 UX](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/5)입니다. 저장소 초기 설정 기록은 [#6](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/6)입니다. 담당자는 미배정입니다.

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/1-navi-probe
```

브랜치 번호는 실제 이슈 번호입니다. `dev-1`이나 사람별 번호를 만들지 않습니다. 커밋/PR은 `feat(android): 목적지 조회 probe 검증`처럼 작성하고 PR 본문에 `Closes #1`을 별도 줄로 적습니다. 세부 규칙은 [CONTRIBUTING](../CONTRIBUTING.md)을 따릅니다.

## 실행 증거와 공개 범위

첫 커밋 `81f0b40`의 [원격 Verify 실행](https://github.com/arriveon-lab/pleos-reservation-assistant/actions/runs/41801748006)에서 `Server and contract`와 `Android checks`가 성공했습니다. 초기 설정 [PR #7](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/7)의 세 검사도 통과했으며 [PR naming 실행](https://github.com/arriveon-lab/pleos-reservation-assistant/actions/runs/41802342293)으로 명명 규칙을 확인했습니다. 최신 커밋의 검사 결과는 [Actions](https://github.com/arriveon-lab/pleos-reservation-assistant/actions)에서 확인합니다. Android 단위 테스트는 NO-SOURCE이며 기기 실행 성공을 의미하지 않습니다.

업로드 전에 키·실제 전화번호·연락처 패턴과 포함 파일을 검토하고 개인 작업 경로를 제거했습니다. 커밋 작성자는 GitHub noreply 이메일을 사용합니다. 개인정보 동의서·서명·DB·녹음·SDK 원본·로컬 설정·빌드 결과는 업로드하지 않았습니다.

공개한 것은 소스 저장소입니다. 서버는 단일 사용자 localhost MOCK용이며 공용 서비스로 배포하지 않았습니다. 실발신·번호 구매·유료 API 실행은 별도 승인이 필요합니다.
