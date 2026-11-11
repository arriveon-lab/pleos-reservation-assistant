# 단일 Linux 서버 배포

예약 API와 선택형 음성 worker가 같은 영속 SQLite를 사용하는 전화 프로토타입 배포입니다. 제품 Spring 서버 배포와 별개입니다. 기본 시작은 MOCK 웹 화면만 실행하며, 실제 전화 worker는 `voice` profile과 LIVE 검증을 통과해야 시작합니다.

## 구성

- Python 3.12 Debian 이미지 digest와 음성 SDK 버전을 고정합니다. 호스트 Python 버전은 사용하지 않습니다.
- Linux host network에서 API는 `127.0.0.1:18765`, 음성 worker health는 `127.0.0.1:8081`에 바인딩합니다. 포트를 공개하거나 기존 Nginx 설정을 변경하지 않습니다.
- `/opt/arriveon-phone/data/phone-agent-prototype.sqlite3`를 API와 worker의 `/var/lib/arriveon-phone/phone-agent-prototype.sqlite3`에 함께 마운트합니다. 단일 서버·동시 통화 1개용이며 다중 서버 배포는 별도 설계가 필요합니다.
- worker는 idle process 1개를 준비하고 Silero를 작업 할당 전에 로드해 통화 중 재로딩을 줄입니다. 호출 중 다른 종류의 지연이나 외부 STT 단절 해결을 보장하지 않습니다.
- API 192MiB, worker 1200MiB 메모리 상한을 사용합니다. 실제 worker/모델의 메모리는 승인된 통화에서 별도로 관측합니다. OOM 여부는 `docker inspect`와 서버 메모리로 확인합니다.
- 비 root UID/GID 10001, 읽기 전용 root filesystem, 제한된 임시 디렉터리와 로그 회전을 사용합니다. `Dockerfile.dockerignore`는 전화 소스만 빌드에 허용합니다.

## 서버 준비와 데이터

Docker Engine·Compose가 있는 Linux에서 release 소스를 별도 경로에 둡니다. 서버 IP·SSH 키·runtime.env·DB·로그는 Git에 넣지 않습니다. SSH 개인 키는 로컬에만 둡니다.

서버 경로:

```text
/opt/arriveon-phone/
  releases/<revision>/prototypes/phone_agent/...
  current -> releases/<revision>
  config/runtime.env
  data/phone-agent-prototype.sqlite3
```

`config/`는 권한 700, `runtime.env`와 DB는 600·소유자 10001:10001로 준비합니다. data 디렉터리도 10001:10001이 SQLite journal을 만들 수 있어야 합니다. 새 서버로 옮길 때는 진행 중 통화가 없고 LIVE가 꺼져 있는지 확인한 후 SQLite backup API로 사본을 만듭니다. 기존 원격 DB를 자동으로 덮어쓰지 않습니다. 통화 이력을 유지해 이전 UNKNOWN과 일일 통화 수가 초기화되지 않게 합니다.

config 디렉터리를 읽기 전용으로 마운트하므로 설정 파일을 원자적으로 교체해도 API가 새 파일을 읽습니다. 단일 파일 bind mount는 교체 전 inode에 남을 수 있어 사용하지 않습니다. config에는 runtime.env만 두며, 설정 교체 시에도 소유자·권한 600을 유지합니다. 실행 중 worker는 설정을 재로딩하지 않으므로 LIVE를 끌 때 반드시 worker도 중단합니다.

runtime.env는 다음 `.env.example`의 값 중 실행에 필요한 설정만 담습니다. 실제 값은 별도로 비공개 전송합니다.

```dotenv
PHONE_LIVE_ENABLED=false
PHONE_INBOUND_ENABLED=false
PHONE_DATABASE=/var/lib/arriveon-phone/phone-agent-prototype.sqlite3
PHONE_SECRETS_DIR=/run/secrets/no-admin
PHONE_MAX_SECONDS=60
PHONE_DAILY_CALLS=1
# LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET
# LIVEKIT_INBOUND_TRUNK_ID, LIVEKIT_OUTBOUND_TRUNK_ID
# PHONE_AGENT_NAME, PHONE_ALLOWED_NUMBERS, PHONE_RESTAURANT_NUMBERS
# PHONE_STT_MODEL, PHONE_LLM_MODEL, PHONE_TTS_MODEL, PHONE_TTS_VOICE
# PHONE_NOISE_SUPPRESSION=true, PHONE_INTERRUPTION_MODE=adaptive
```

Telkit 관리 API 키·SIP 비밀번호는 이미 준비된 trunk로 통화하는 데 필요하지 않아 이 배포에 복사하지 않습니다. runtime 진단의 `missingRuntime`이 비어 있는지 확인하며 `missingSetup`은 관리 자격 증명을 두지 않아 남을 수 있습니다.

## 빌드와 기본 시작

release 루트에서 실행합니다. Docker 이미지 빌드는 공개 모델 파일을 미리 받으며 계정 키·전화 발신·STT/LLM/TTS 추론을 사용하지 않습니다.

```bash
export PHONE_RELEASE=<revision>
docker compose -f prototypes/phone_agent/deploy/compose.yml config --quiet
docker compose -f prototypes/phone_agent/deploy/compose.yml build
docker compose -f prototypes/phone_agent/deploy/compose.yml up -d api
docker compose -f prototypes/phone_agent/deploy/compose.yml ps
```

필요한 경우 위 명령에 서버 권한에 맞게 `sudo`를 사용합니다. revision은 환경에 남기거나 매번 지정해 같은 이미지를 선택합니다. 현재 배포된 소스에서 `PHONE_RELEASE`와 revision을 바꿔 다른 소스와 같은 태그로 빌드하지 않습니다.

로컬에서 SSH 터널을 열고 `http://localhost:18765`에 접속합니다. 터널의 양쪽 포트는 같아야 기존 Host 검증과 맞습니다.

```bash
ssh -i "$EC2_KEY" -N -L 18765:127.0.0.1:18765 "$EC2_USER@$EC2_HOST"
```

healthcheck는 정적 `/`만 읽어 세션을 추가하지 않습니다. `/api/state`는 세션을 만들 수 있어 주기적인 healthcheck로 사용하지 않습니다.

## 실제 전화 worker

### EC2에서 웹검색 후보로 시험 예약

통합 시험 환경은 Compose 프로젝트 `arriveon-integration`과 `compose.integration.yml`을 사용합니다.
전화 화면은 EC2의 루프백 포트 18767에 있으며, 추천 서버와 음성 worker를 함께 실행합니다.
Mac에서 접속할 때 SSH 터널을 엽니다. 키 경로와 서버 주소는 현재 사용하는 값으로 지정합니다.

```bash
ssh -i "$EC2_KEY" -N -L 18767:127.0.0.1:18767 "$EC2_USER@$EC2_HOST"
```

[EC2 시험 화면](http://127.0.0.1:18767/)에서 목적지와 조건을 입력하고 웹검색을 실행합니다.
후보 카드의 **이 식당으로 시험 예약 만들기**를 누른 뒤 예약 카드에서 발신을 승인합니다.
로그인으로 보호된 EC2 HTTPS 화면에서 받을 010 번호를 입력하고 **입력한 휴대폰으로 시험전화**를
누릅니다. 확인 창에 표시된 번호를 승인하면 발신합니다. 서버 전체 기준 1일 1회, 최대 60초입니다.
결과는 통합 시험 환경의 SQLite에 저장하며 세션 삭제 후에도 당일 통화 횟수는 유지합니다.

### 기본 전화 worker 배포

기본 `up`은 worker를 실행하지 않습니다. Compose는 서비스를 명시하면 profile 없이도 시작할 수 있으므로, `voice.launch`가 LIVE를 먼저 검증하고 비활성 상태에서는 SDK worker 등록 자체를 막습니다. 실제 발신/수신 시험은 대상·횟수·시간·비용 승인을 받은 다음 진행합니다. 이미 소비한 통화 이력을 지우거나 DB를 바꿔 상한을 우회하지 않습니다.

```bash
# 승인된 시험 범위로 runtime.env를 준비한 뒤
docker compose -f prototypes/phone_agent/deploy/compose.yml --profile voice up -d worker
```

수신 전용 시험은 `PHONE_LIVE_ENABLED=false`, `PHONE_INBOUND_ENABLED=true`로 실행합니다.
발신 trunk가 없어도 수신 설정이 완전하면 worker를 시작할 수 있습니다. 수신에도 동일한
일일 상한·동시 1통·시간 제한이 적용되므로 기존 통화 이력은 그대로 유지합니다.

API는 공개 서비스 인증이 없는 테스트 도구이므로 SSH 터널로 사용합니다. worker를 로컬과 서버에서 동시에 켜지 않습니다. 시험이 끝나면 runtime.env의 LIVE와 INBOUND를 모두 false로 되돌리고 worker를 중단합니다. API는 계속 MOCK으로 사용할 수 있습니다.

```bash
docker compose -f prototypes/phone_agent/deploy/compose.yml stop worker
docker compose -f prototypes/phone_agent/deploy/compose.yml ps --all
```

`down -v`나 data 폴더 삭제를 초기화 수단으로 사용하지 않습니다. rollback은 worker 중단 후 이전 release/image로 API를 다시 시작하되 같은 DB를 보존합니다. 스키마가 달라지는 변경은 별도 호환 검토가 필요합니다.

## 검증

```bash
PHONE_ENV_FILE=work/no-test-secrets python -m unittest prototypes.phone_agent.test_service prototypes.phone_agent.voice.test_voice prototypes.phone_agent.voice.test_resilience prototypes.phone_agent.voice.test_echo -q
docker compose -f prototypes/phone_agent/deploy/compose.yml config --quiet
```

116개 로컬 검사는 전화/모델 요청 없이 LIVE 시작 차단, 기존 예약 상태·소유권·중복·장애 처리, VAD 재사용과 AEC 참조·겹침·중단 수명을 확인합니다. 컨테이너에서도 같은 검사를 실행합니다. 배포·readiness·MOCK 검증과 실제 한국어 음질 검증은 별개이며, 서버에서의 실통화 품질은 승인된 시험 전까지 미검증입니다.

근거: [Docker host network](https://docs.docker.com/engine/network/drivers/host/), [Compose profiles](https://docs.docker.com/compose/how-tos/profiles/), [LiveKit prewarm](https://docs.livekit.io/agents/server/options/), [LiveKit 이미지 빌드](https://docs.livekit.io/deploy/agents/builds/).

2026-11-10 첫 EC2 실전화는 36초·48원, SDK 모델/연결 오류 0개였으나 사용자 청취 끊김과 AI 중단 메시지 3개가 남았습니다. UNKNOWN 저장 후 worker를 중단했습니다. 스피커폰 에코 여부와 실제 음질 개선은 확정하지 않았으며 [STATUS](../../../docs/STATUS.md)를 따릅니다.


2026-11-10 후속 스피커폰 시험은 배포 소스 `e7e397f`에서 42초·60원, UNKNOWN 저장 후 종료됐습니다. 사용자 청취 개선과 AI 중단 메시지 0개를 관측했습니다. 로컬/Linux 102개 검사와 해당 코드의 원격 CI 5개를 통과했습니다. 에코 처리·16kHz·출력 속도 조정을 함께 적용한 한 번의 결과이며 전체 음질 통과로 확대하지 않습니다. 시험 후 LIVE=false·AEC 기본 false·일일 상한 1·worker 중단·활성 room 0개·API healthy입니다.


짧은 수락 판정 보완 소스 `8b54619`를 후속 배포했습니다. 로컬·네트워크 차단 Linux 컨테이너 각각 110개 검사와 해당 코드의 원격 CI 5개를 통과했습니다. 배포 후 짧은 수락 함수·HTTP 상태·기존 통화 이력 9건·LIVE/AEC false·일일 상한 1·worker 중단을 확인했습니다. 실제 전화/모델 호출은 추가하지 않았습니다.


입력 무음 진단 소스 `cc96ad4`를 배포하고 네트워크 차단 Linux에서 116개 검사를 통과했습니다. `PHONE_INPUT_LEVEL_PROBE`는 기본 false이며, 다음 승인된 진단 통화에만 선택적으로 켭니다. 최근 2회 발신의 재생 대기/입력 무음과 비용은 [STATUS](../../../docs/STATUS.md)를 따릅니다. 새 진단은 아직 실전화로 실행하지 않았습니다.


2026-11-11 열세 번째 시험은 의미 기반 수락을 인정해 CONFIRMED 저장과 결과에 맞는 종료 안내까지 확인했습니다. 사용자는 확정 안내까지 자연스럽게 들렸다고 답했습니다. 43초·60원(VAT 별도), 누적 발신 13회·768원·잔액 2,232원입니다. AI 중단 메시지·모델/AEC/계측 오류 0개였으며 시험 후 LIVE/AEC/계측 비활성·일일 상한 1·worker 중단·활성 room 0개를 확인했습니다. 배포 코드 변경 없이 진행한 1회 가상 식당 역할 시험이며 모든 환경의 품질 보장은 아닙니다.
