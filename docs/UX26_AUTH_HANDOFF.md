# UX-03 인증 1.1.0 연결과 미리보기 인수 검토

2026-11-09, #26. 최신 main `abd9aa4`에서 `feat/26-app-auth-session`을 만들고 #5/PR #27의 앱·UX core 기반을 가져왔습니다. 기존 공유 브랜치/PR은 rebase·force push·병합하지 않았습니다. main의 서버·인증·추천·CI 품질 검사를 보존했습니다. 이 브랜치는 #5 기반을 포함하므로 향후 통합 시 #27과 중복 반영하지 않도록 PR 관계를 재검토해야 합니다.

> 아래는 인증 연결 단계의 이력이다. 이후 미리보기 구현·초안 기반 기기 검증은 [UX26_PREVIEW_VALIDATION](UX26_PREVIEW_VALIDATION.md)을 우선한다. 공동 인수는 여전히 미완료다.

## #19 인수안 대조

검토 대상은 미병합 PR #19 `285087b`의 `REC05_UI_HANDOFF.md`, 요청/응답 예제, `place-preview-draft.json`입니다. 인증은 1.1.0, 미리보기는 별도 0.2.0-draft입니다. [#16에 검토 결과 게시](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008). 서버 담당의 답변/공동 합의·기기 인수는 아직 완료가 아닙니다.

| 현재 앱 | 미리보기 계약 | 적용할 경계 |
| --- | --- | --- |
| 예약 candidateId·eligible/bookable | ordinal·NEEDS_CONFIRMATION·bookable/inquiryDispatchAllowed=false | 별도 비교 모델, ID 변환·문의/예약 활성화 금지 |
| 고정 최대15분/한식, 인원1–8/0–6 | 명시 requirements, 서버 인원1–20/0–10 | UI 입력 범위와 서버 허용 범위를 구분, 조건 누락 시 plan=null |
| 검색/예약 ID·pending 명령 영속 복구 | 일시 결과·generation·화면 수명 | 결과 미저장, 취소 성공 여부와 무관하게 이전 화면 먼저 무효화 |
| 같은 키 재전송·GET 복구 | 완료 세대 ALREADY_FINISHED, 조건 충돌 REQUEST_CONFLICT | 자동 새 세대/새 검색으로 우회하지 않음 |
| 후보 사실·누락 정보 | 추천 근거·누락 정보·UNKNOWN 질문 | 취향을 필수 조건 충족으로 해석하지 않음, ARRIVAL/ETA를 추정하지 않음 |

EMPTY/FILTERED/FAILED와 만료/취소/늦은 응답 처리가 필요합니다. 완료 미리보기의 앱 복귀 시 결과 비우기와 후보→예약 변환 API 부재를 공동 확인 요청했습니다. 이번에는 이 초안의 서버 코드를 main에 가져오거나 미리보기 UI를 구현하지 않았습니다.

## 구현한 인증 경계

- 서버의 1.1.0/MOCK/externalCallsEnabled=false를 확인하고 명시적 새 설치 버튼에서만 익명 토큰을 발급합니다. 최초/모의 제한 상태에서는 발급과 사용자 변경 명령을 차단합니다. 실제 CarUxRestrictions 연결은 #25입니다.
- 접근/갱신 토큰·갱신 요청 키는 AndroidKeyStore AES-256-GCM으로 암호화해 noBackupFilesDir의 AtomicFile에 함께 저장합니다. 새 IV와 AAD를 사용하고 쓰기 결과를 재확인합니다. 토큰은 ViewModel snapshot·복구 SharedPreferences·화면·로그·백업으로 보내지 않습니다. [공식 KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) 기준이며 새 암호 라이브러리는 추가하지 않았습니다.
- 최초 발급에는 서버 재전송 계약이 없으므로 발급 시도부터 저장합니다. 응답 유실/저장 실패 후 다시 발급하지 않고 대조 상태로 멈춥니다. 성공한 발급 뒤 복구 기록까지 저장되기 전에 종료된 경우도 보수적으로 차단합니다.
- 보호 요청의 401에만 직렬화된 갱신을 한 번 수행하고 원 요청 키/본문으로 한 번 재전송합니다. 일반 네트워크 실패·403·404에 자동 재발급하지 않습니다. 갱신 키는 HTTP 전에 저장하고 응답 유실/저장 실패 때 기존 refreshToken+키를 유지합니다. 서버의 120초 재전송 창 이후 409 또는 401이면 BLOCKED로 보존합니다.
- 로컬 인증 binding과 복구 기록을 연결합니다. 1.0.0의 소유자 없는 기록, 다른 binding, 손상·유실된 토큰/키/복구 파일을 새 사용자에게 자동 배정하지 않습니다. 기존 기록 삭제·새 사용자 우회 버튼은 없습니다. 재설치/앱 데이터 전체 삭제에 의한 복구는 지원하지 않습니다.
- 일반 발급 토큰에는 MOCK_OPERATOR가 없습니다. 앱의 결과 주입 버튼과 transport 경로를 제거/차단했습니다. 예약/문의 결과는 GET으로 읽고 권한 있는 서버 측 시험 도구가 필요합니다. 제품 서버 권한을 변경하지 않았습니다.

## 검사와 실제 기기 실행

- `./gradlew -PincludeAndroid=true format verifyAll`: 서버 268개 결과(최초 기반 반영 때 실행, 후속 UP-TO-DATE), UX 26개 실행·실패/오류/skip 0. 인증 12개는 **각각 독립된 실제 배포 JAR/임시 H2**에서 검사합니다. 기존 업무 14개는 `src/test`의 기존 운영자 Principal 하네스를 사용하며 일반 앱 권한이나 배포 JAR의 모의 수락 성공으로 세지 않습니다.
- 인증 검사: 발급 승인/UNKNOWN, 검색 소유권·재생성 GET, 갱신 응답 유실·동일 키, 401 원 명령 동일 키 재전송, 403 비재시도, 동시 갱신, 발급/갱신 저장 실패, 갱신 ACK 저장 실패, 폐기, 레거시/binding 충돌, 저장 손상. 409 재전송 창 종료는 가상 오류 주입으로 검사하고 실제 120초 경과 시험과 구분합니다.
- 처음 12개 인증 검사를 한 서버에서 연속 실행하자 정상 분당 발급 제한(10회)으로 HTTP429가 발생했습니다. 서버 제한을 끄지 않고 각 테스트를 별도 서버/DB로 격리해 해결했습니다.
- `python3 scripts/smoke-test.py`: 실제 발급 토큰·소유권/역할 거절·갱신 재전송·폐기·H2 재시작, 별도 test-only 운영자 업무 흐름, LIVE 시작 차단 성공. 초기 smoke 뒤 별도 저장소 내부 가상환경에서 `smoke-test.py --check-contract`까지 성공했습니다.
- 앱 Lint 오류 0, 고정 AndroidX 갱신 경고 2; probe 오류/경고 0. 억제/baseline/SDK 의존성 변경 없음. 원격 Lint와 검사 결과는 해당 PR 실행을 기준으로 별도 기록합니다.

Pleos Connect v2.1.0/API34/4GB에서 직접 adb UI 조작·PNG/XML로 검증했습니다. 기존 `ai.pleos.assistant.mock`의 데이터는 삭제하지 않았습니다. 새 인증 흐름은 **applicationId만 `ai.pleos.assistant.mock.authvalidation`으로 바꾼 별도 debug 설치**를 사용했습니다(로컬 init script, 소스 기능 동일). 기본 APK와 분리 APK 실행 증거를 구분합니다. 테스트 서버는 별도 작업 폴더의 MOCK 1.1.0 JAR, 호스트 18080/adb reverse8080→18080입니다.

| 절차 | 기대/실제 결과 | 캡처 |
| --- | --- | --- |
| 기존 1.0.0 데이터에 기본 APK 업데이트 | 새 인증 버튼 없음, 기존 기록 대조 안내, 데이터 보존 | 09-legacy-corrected |
| 분리 새 설치 UNKNOWN | 발급/검색 버튼 비활성 | 02-new-unknown |
| 모의 정차·새 인증 버튼 | 발급 후 연결됨, 조건 입력 허용 | 03-authenticated |
| 검색 | 보호 API 성공, 후보 3개, 모의 결과 주입 없음 | 04-auth-search |
| 같은 사용자 인증 갱신 | 새 암호문으로 교체, 기존 검색 유지 | 05-auth-refreshed |
| force-stop·재실행 | UNKNOWN으로 복귀, 동일 검색 GET 복구 | 06-auth-restored |
| 검증용 암호문 파일만 임시 이동·재실행 | 새 인증/검색 차단, 대조 안내 | 07-storage-loss-blocked |
| 파일 복원·재실행 | 기존 검색 복구, 데이터 초기화 없음 | 08-storage-restored |

암호문 envelope는 version/iv/data만 포함하고 평문 토큰은 없음을 검사했습니다. 갱신 전후 암호문 hash가 바뀌고 강제 종료 후 복구 기록 hash가 같음을 확인했습니다. 토큰 원문을 증거 파일로 추출하지 않았습니다. 로컬 Git 제외 `captures/ux26/`에 PNG/XML·hash manifest, `work/ux26/`에 빌드/smoke/기기 로그가 있으며 GitHub 첨부로 간주하지 않습니다. 초기 01 캡처의 레거시 안내 문구가 신규 발급을 권하는 문제가 있어 바로잡고 09로 재검증했습니다.

실행 코드: `a3a2aad`. 기본 debug APK SHA-256: `75bafa5902fb4aa9d32fad19eebd707b7f806d3800bfb9eba53e222404567ae9`. 분리 설치 APK SHA-256: `343bef051d19a66c4e1e43c3540a2cec9ac2ab190cd0f985807abc95b035e5e6`.

## 아직 완료하지 않은 #26 범위

미리보기 화면·generation/취소/만료/늦은 결과·공동 기기 인수, 유료 변경 별도 승인, 확정 거절 명령 종료 UX와 사용자가 안전하게 복구할 수 있는 대조 절차는 남아 있습니다. 이번 저장 유실 처리는 차단/안내까지이며 완전한 복구 구현이 아닙니다. 실제 Keystore 키 무효화·OS 백업/기기 이전·회전·실제 시간 만료 후 자동 갱신·기기 네트워크 응답 유실은 미검증입니다. 에뮬레이터 검색 복구를 인증된 예약 전체 흐름 검증으로 확대하지 않습니다. #25의 실제 차량 제한/release·영상과 #1 ETA/#11 데이터 조건도 그대로 남습니다.

#26 및 #5 OPEN, 관련 PR Draft를 유지합니다. 다른 담당자의 서버/계약·이슈 완료 조건을 변경하지 않았고 병합/자동 병합하지 않습니다. 실제 장소·전화·AI·유료 API 호출 0회입니다.

## 원격 CI에서 발견한 캐시 산출물 누락 수정

`2b0b08d`의 Android checks/PR naming은 성공했으나 Server and contract는 추천 계약 검사가 소비하는 `server/build/rec08/mock-recommendation-plan.json` 누락으로 실패했습니다. 서버 테스트가 FROM-CACHE였고 해당 파일이 Test output에 선언되지 않은 것이 원인입니다. 서버 기능/계약은 바꾸지 않고 `server:test` 산출물로 등록했습니다. 로컬 verify 후 JSON을 삭제해 테스트를 FROM-CACHE로 복원했고 추천 스키마 검사와 JAR `--check-contract` smoke를 통과했습니다. 새 커밋의 CI는 별도 실행으로 확인합니다. 사용하지 않는 결과 주입 문자열4개도 제거해 로컬 앱 Lint는 기존 갱신 경고2개만 남습니다.
