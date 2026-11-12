# REC-05 · Docker Compose로 넘기는 로컬 MOCK 서버

2026-11-10. #16/PR #19의 화면 인수용 개발 이미지입니다. 각자 PC에서 같은 전달 commit을 빌드해 사용합니다. 원격 공유 서버나 운영 배포가 아니며 다른 사람의 Mac을 켜 둘 필요가 없습니다. APK의 UI/Keystore/CarUx 인수와 실제 장소·전화·GPT 실행은 별도입니다.

## 팀원의 실행 순서

필수는 Git과 실행 중인 Docker Engine28이상/Desktop 및 `up --wait`를 지원하는 Compose plugin입니다. host의 Java/Gradle/Android SDK는 이 서버를 빌드하는 데 필요하지 않습니다. 최초 빌드는 공개 JDK 이미지·Gradle·Maven 의존성 다운로드가 필요합니다. 이미지 registry push는 하지 않고 각자 로컬에서 빌드합니다.

앱 작업 폴더를 전환하지 않으려면 별도 checkout을 만듭니다. AGENTS/기여 지침과 본인 작업 상태를 먼저 확인하고, `SERVER_COMMIT`은 #16 코멘트로 전달한 **Docker 설정이 들어 있는 실제 commit SHA**로 바꿉니다. 이전07b0a43에는 Docker 구성이 없습니다. 경로가 이미 있으면 기존 작업을 지우지 않고 다른 경로를 사용합니다.

```bash
git fetch origin
git worktree add --detach work/rec05-server SERVER_COMMIT
cd work/rec05-server
git rev-parse HEAD
docker compose up --build --detach --wait
```

기본 fixture는 RESULTS, 접속 주소는 `http://127.0.0.1:18082`입니다. 준비 상태 확인은 `docker compose ps`와 `http://127.0.0.1:18082/api/v1/status`로 합니다. Compose의 healthcheck가 MOCK/계약1.1.0/외부 발신 꺼짐을 확인합니다. 현재 앱 연결은 실제 서버 발급 익명 토큰을 사용하며 MOCK_OPERATOR 권한을 공급하지 않습니다.

이미18082를 쓰고 있다면 기존 서버를 종료하지 않고 아래처럼 포트를 선택합니다. 아래 인라인 환경변수 명령은 macOS/Linux shell 기준입니다.

```bash
PREVIEW_PORT=18083 docker compose up --build --detach --wait
```

## 네 상태 전환과 DB 유지

```bash
PREVIEW_FIXTURE=RESULTS docker compose up --detach --wait
PREVIEW_FIXTURE=EMPTY docker compose up --detach --wait
PREVIEW_FIXTURE=FILTERED docker compose up --detach --wait
PREVIEW_FIXTURE=FAILED docker compose up --detach --wait
```

한 번에 한 상태를 시험하고 다음 명령으로 바꿉니다. 설정 변경 시 컨테이너가 재생성돼도 같은 Compose project의 named volume `preview-auth`를 사용하므로 인증과 H2의 모의 업무 데이터는 유지됩니다. **메모리 preview는 복구되지 않습니다.** 각 상태를 바꾼 뒤 사용자의 명시적 새 검색으로 시험합니다. 서버 재시작/새 주체를 실제429나 미해결 요청의 우회 방법으로 채택하지 않습니다.

| fixture | 고정 시험 조건과 기대 결과 |
| --- | --- |
| RESULTS | 중심37.544/127.055·반경1000m·상한3, 가상 후보3개 |
| EMPTY | 공급자 정상0건, 후보0 |
| FILTERED | 범위 밖 가상1건, 필터 후 후보0 |
| FAILED | 가상 공급자 UPSTREAM_UNAVAILABLE, 후보0/추천 계획null |

위 상태와 UNKNOWN/질문은 가상 데이터입니다. 실제 공급자 장애·매장 빈자리·예약 수락으로 세지 않습니다. 일반 종료는 아래 명령을 사용합니다.

```bash
docker compose down
```

`down --volumes`는 인증 DB도 삭제하므로 일반 종료 절차에 넣지 않습니다. 새 volume의 DB와 기존 앱 토큰은 같은 인증 조합이 아니므로 검증용 package/DB를 명확히 정하고 기존 앱 데이터/Keystore를 자동 삭제하지 않습니다. 이 구성을 앱·개발 DB 공유용으로 사용하지 않습니다.

## 에뮬레이터 연결과 결과 전달

화면 담당 PC에서 서버가 준비되면 같은 PC에 adb로 연결한 대상 시리얼을 고릅니다. SERIAL은 실제 값으로 바꾸고 기존 reverse를 먼저 기록합니다.

```bash
adb devices -l
adb -s SERIAL reverse --list
adb -s SERIAL reverse tcp:8080 tcp:18082
```

경로는 **기기8080 → 자기 PC18082 → 컨테이너18082**입니다. PREVIEW_PORT를 바꿨다면 마지막 목적 포트도 바꿉니다. 기존8080 매핑은 시험 후 원래 목적 포트로 복구하고, 원래 없었다면 `adb -s SERIAL reverse --remove tcp:8080`으로 이번 매핑만 제거합니다. 다른 매핑/앱/DB를 일괄 삭제하지 않습니다.

최종 APK 한 개의 SHA256, 서버 commit(`git rev-parse HEAD`), image ID(`docker compose images -q preview`), PC/컨테이너 platform, 기기/이미지, fixture, 기대/실제, 증거, 미검증을 [공동 인수표](REC05_JOINT_ACCEPTANCE.md)에 기록합니다. 서로 다른 debug 키나 CPU platform의 빌드를 같은 APK/image hash라고 가정하지 않습니다. 두 사람의 동시 접속은 필수가 아니며, 각자 결과를 대조해 인수에 합의합니다. 아직 공동 기기 인수가 완료된 것은 아닙니다.

## 이미지와 실행 범위

- multi-stage 빌드로 Gradle Wrapper/Java21을 컨테이너 안에서 사용합니다. Temurin21-jdk-jammy의 multi-platform index digest를 고정했습니다. 의존성은 기존 lockfile을 사용합니다. base digest를 갱신할 때는 다시 검증합니다.
- 이미지에는 main 클래스/리소스·런타임 JAR와 PreviewAcceptanceApplication/HealthCheck만 포함합니다. 다른 테스트 클래스·가상 Principal/운영자 주입 실행체는 복사하지 않습니다. 정식 bootJar와는 별도의 개발용 배포물입니다.
- `.dockerignore`의 허용 목록은 wrapper/빌드 설정/main 소스와 지정한 두 시험 실행체만 보냅니다. work/secrets·Git·.env·개인키·실행 DB·captures·다른 앱 checkout은 빌드 context에 포함하지 않습니다. host API 키를 런타임 환경으로 넘기지 않습니다.
- 로컬 직접 실행은 여전히127.0.0.1만 수신합니다. 명시적인 `--container` 인자로만 컨테이너 내부0.0.0.0을 사용하며 Compose는 host127.0.0.1에만 publish합니다. Docker bridge의 외부 통신 방화벽을 구현한 것은 아니며 실제 호출 차단은 고정 MOCK 실행체/미제공 키/서비스 LIVE 차단 경계입니다.
- UID/GID10001, 읽기 전용 root filesystem, 임시/tmp, named DB volume, capability 제거를 적용했습니다. 준비 확인 전 접속하지 않도록 healthcheck/`up --wait`를 사용합니다.

근거: [multi-stage 빌드](https://docs.docker.com/build/building/multi-stage/), [포트 공개/host loopback](https://docs.docker.com/engine/network/port-publishing/), [Compose 변수](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/), [준비 상태](https://docs.docker.com/compose/how-tos/startup-order/). 기본 bridge에 내부 전용 네트워크를 설정하면 이 검증 환경에서 host publish가 비어 접속되지 않아, bridge와 명시적 loopback publish 조합으로 검증했습니다.

## 자동 Docker 검증

```bash
docker compose build
work/contract-venv/bin/python scripts/check-preview-compose.py --no-build
```

Python은 scripts/requirements-dev.txt 환경을 사용합니다. 이 검사는 임의 포트·고유 Compose project/volume을 새로 만들고 네 상태를 순차 재생성한 뒤 **자기가 만든 시험 volume만** 정리합니다. 사용자의 기본 개발 컨테이너/volume은 변경하지 않습니다. 토큰·응답 원문은 출력/파일 저장하지 않습니다.

로컬 macOS arm64/Docker29.9.2·Compose5.6.1에서 실제 이미지 빌드·healthy 기동과 MOCK4상태, preview HTTP22건·인증 HTTP23건을 통과했습니다. 기존 토큰/소유권·갱신 응답 재시도·폐기가 컨테이너/H2 재시작 후 유지되고 일반 토큰의 결과 주입403도 확인했습니다. host 포트/비root/읽기 전용 root 검사를 포함합니다. GitHub의 별도 MOCK Compose checks는 Linux amd64 실행 결과로 구분합니다. Android 기기 실행과 실제 장소/GPT/전화/유료 호출은 이번 Docker 시험에서 하지 않았습니다.

기존 서버 테스트329개와 정식 JAR의 MOCK/UNKNOWN·인증·H2 재시작·preview 토큰HTTP16건·LIVE 시작 차단도 통과했습니다. Docker 실행체는 정식 JAR에 포함되지 않습니다.
