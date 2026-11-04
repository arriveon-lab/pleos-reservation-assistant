# GitHub 조직 생성 후 설정

로컬 협업 기반은 준비되어 있습니다. 조직·원격 저장소 생성, 팀원 초대, push는 아직 수행하지 않았습니다. 조직명·팀원 ID·공개 범위·라이선스가 정해지면 연결합니다.

## 추천 구조

초기에는 이 모노레포 하나를 `pleos-reservation-assistant` 같은 이름으로 운영합니다. 차량/서버를 별도 저장소로 나누는 시점은 권한·릴리스 주기가 실제로 달라질 때 결정합니다. 공통 계약을 한 PR에서 검토할 수 있는 현재 구조를 유지합니다.

조직 owner는 대표와 백업 1명, 나머지는 member로 두는 안을 제안합니다. 5명 규모라면 팀 하나로 시작해도 충분합니다. 역할별 리뷰가 필요하면 vehicle/backend/voice/integration 팀을 만들고 `.github/CODEOWNERS.example`을 실제 ID로 바꿔 CODEOWNERS로 등록합니다. 현재 예제는 GitHub에 적용되지 않습니다. [GitHub 조직 설명](https://docs.github.com/en/organizations/collaborating-with-groups-in-organizations/about-organizations)

## 순서

1. 조직명, 무료/유료 플랜, 공개 범위를 결정합니다. 유료 플랜이나 유료 Actions 사용을 자동으로 활성화하지 않습니다. 비공개 조직 저장소에서 보호 규칙을 사용할 수 있는지는 선택 플랜을 확인합니다. 공개 저장소의 GitHub Free 보호와 비공개 저장소의 유료 플랜 지원 범위가 다릅니다. [공식 보호 브랜치 안내](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
2. 조직에 빈 저장소를 생성합니다. 이 로컬 저장소에 README와 gitignore가 이미 있으므로 웹에서 중복 초기화하지 않습니다. 2FA와 멤버 최소 권한을 설정합니다.
3. `git status`, `git branch -a`, `git remote -v`로 다시 확인합니다. 현재 main/커밋 없음/원격 없음입니다. 로컬 main은 사용자 요청으로 변경했습니다. GitHub 기본 브랜치도 main으로 지정합니다.
4. 포함 파일을 검토하고 첫 커밋을 만듭니다. `work`, `data`, 빌드 결과, `.env`, 녹음, SDK 원본, 서명은 제외됩니다. `outputs/CODEX_HANDOFF.md`는 공유되도록 예외 처리했습니다.
5. 실제 조직 URL로 origin을 연결하고 기본 브랜치를 push합니다. 아래는 자리표시자이며 그대로 실행하지 않습니다.

```bash
git add .
git diff --cached --stat
git commit -m "chore(repo): 로컬 MOCK MVP와 협업 기반 구성"
git remote add origin https://github.com/ORG/REPOSITORY.git
git push -u origin main
```

6. GitHub에서 `Verify / Server and contract`, `Verify / Android checks`, PR의 `Conventions / PR naming` 실행 결과를 확인합니다. 첫 push/PR 시 워크플로가 실행되므로 비용 정책·Actions 예산을 먼저 설정합니다. 로컬 검증 결과와 원격 CI 결과를 별도로 기록합니다. Android job은 [Ubuntu 24.04 hosted image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)의 SDK 35/build-tools 35.0.0을 점검한 뒤 Lint/단위 테스트 태스크/debug APK를 실행합니다. 필수 패키지가 이미지에서 제거되면 명시적으로 설치 단계를 갱신합니다.
7. 지원 플랜에서 기본 브랜치 PR 필수, 리뷰 1명, Server and contract·Android checks·PR naming 통과, force push/삭제 제한을 설정합니다. 규칙을 설정할 수 없는 플랜에서는 팀 규칙만 있는 상태임을 명시합니다.
8. Squash merge만 활성화하고 기본 squash 제목을 PR 제목으로 설정합니다. merge 후 작업 브랜치 삭제, Issue 템플릿, 리뷰 담당, 릴리스 담당을 정합니다. 5개 역할별 초기 이슈는 `docs/PLAN.md`에서 생성합니다.

## 공개·제출 전

- 팀 소유권과 공개 라이선스를 합의합니다. 오픈소스 라이선스를 임의로 붙이지 않았습니다.
- 키/번호/개인정보/서명/DB/SDK 재배포 파일 포함 여부를 확인합니다.
- README의 검증 수준과 APK 경로를 실제 산출물에 맞춥니다.
- 비공개 제출이면 공지된 심사용 계정의 접근을 검증합니다. 개인 저장소와 조직 권한 모델을 혼동하지 않습니다. [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)

조직 생성 자체를 이 저장소 초기 세팅 완료의 필수 조건으로 두지 않습니다. 지금 로컬에서 팀원이 실행할 수 있는 기준은 README와 verify/smoke입니다.
