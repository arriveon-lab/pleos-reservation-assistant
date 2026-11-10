# REC-13 웹검색 조건 추천과 사용자 직접 피드백

2026-11-10 KST. 담당 tiemo0708, [#50](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/50). 사용자가 승인한 공모전 MVP는 **요청별 검색→조건별 근거/판정→최대3 후보→사용자 선택·직접 평가 저장**입니다. 모든 매장의 사전 제휴나 자체 리뷰 DB 구축을 개발의 필수 선행 조건으로 두지 않습니다. #49 공공 catalog는 선택적 기반으로 유지하며 미병합 코드를 복사하지 않았습니다.

## 실행 범위

- 기본 JAR은 MOCK·loopback·외부 발신OFF이고 새 API도 기본 비활성입니다. `--app.web-recommendations.enabled=true`로 켜도 **고정 가상 gateway만** 사용합니다. 실제 검색/키 읽기·전화·유료 호출은 이번에0회입니다.
- `GptWebRecommendationAdapter`는 별도의 비용/전송 데이터 승인 이후 주입할 연동 코드입니다. Spring bean·환경변수 키 로더·공개 실행/승인 endpoint는 없습니다. 앱 설정을 켜는 것만으로 GPT가 호출되지 않습니다.
- [OpenAI 공식 문서](https://developers.openai.com/api/docs/guides/tools-web-search), [Responses 생성 명세](https://developers.openai.com/api/reference/python/resources/responses/methods/create)를 확인했습니다. `web_search`, 필수 도구 실행, 호출1회/출력3072토큰, store=false, 최대30초/1MiB, 기존 고정 HTTPS transport/재시도·redirect 차단을 재사용합니다. 모델은 실제 도구 지원/계정/가격 확인 후 명시해야 하고 자동 기본 모델은 없습니다.
- store=false나 웹검색 사용은 서비스 전체의 무보존/원 출처의 자유로운 저장·재제공 권한을 뜻하지 않습니다. 기존 #11의 출처별 경계를 유지합니다. 공모전이라는 이유로 특정 플랫폼 제한을 우회하지 않습니다.

## 검색과 판정

입력은 공개 지역명과 PET_FRIENDLY/INDOOR_PET_FRIENDLY/PARKING/CHILD_FRIENDLY 조건입니다. viewId·generation·개인화 동의·인증 토큰·사용자 식별/가족 정보는 GPT 요청에 넣지 않습니다. 현재 가격·음식 종류·반경/좌표·ETA·반려견 크기의 구조화 필터는 후속이며 일반 웹 지역 검색을 정확한1km 검색으로 안내하지 않습니다. 조건 설명에는 테라스/크기/추가요금/시간 제한을 보존하도록 요청합니다.

검색 응답은 JSON 파싱/크기/깊이/중복키·완료된 검색1회·실제 인용 annotation URL과 후보/근거 URL의 대응을 확인합니다. 검색 인용이 이름·주소 일치나 사실의 진실성을 독립 검증하는 것은 아니므로 `identityVerified=false`입니다. 웹 내용/입력의 명령은 따르지 않도록 프롬프트에 분리하고 전화/예약 권한은 프로그램에서 항상 false로 고정합니다.

| 판정 | 프로그램 처리 |
| --- | --- |
| YES (화면 O) | 모델이 매장 페이지로 분류한 인용 근거의 명시적 가능 보고. 매장 소유/정책을 독립 검증한 뜻은 아님 |
| NO (화면 X) | 같은 범위의 명시적 불가 보고. 필수 조건이면 추천에서 제외 |
| UNKNOWN | 근거 없음/인용 불일치·후기만 있음·날짜 이상/180일 초과. 추가 확인 질문 유지 |
| CONFLICT | 가능/불가 보고가 상충. 최신처럼 보이는 것만 임의 선택하지 않음 |

일반 애견동반 YES가 실내 동반 YES를 채우지 않습니다. 실내 조건이 누락되면 UNKNOWN입니다. 같은 이름과 주소만 묶고 다른 지점은 분리합니다. 알려진 불충족 제외 후 질문이 적은 순으로 최대3곳이며 평점/리뷰 인기순 추천을 구현했다고 표시하지 않습니다. `POTENTIAL_MATCH`도 웹 보고 기준일 뿐, 현재 빈자리·도착 시각 예약 수락이 아닙니다. `bookable/inquiryDispatchAllowed=false`입니다.

인용 URL·제목·모델이 보고한 출처 종류·세부 조건·관측 시각·원본 수정시각(미제공 null)·기한을 반환합니다. 확실성은 REPORTED_WEB_NOT_MERCHANT_CONFIRMATION, scope=GENERAL, appliesAt=null로 도착 시각의 운영 사실과 구분합니다. 원본 수정일을 검색일로 덮지 않습니다. 5분 표시 기한과180일 근거 선별은 개발 기준이며 사업자의 최신성/정확성·이용허락 보장이 아닙니다. UI는 링크를 클릭 가능한 출처로 표시하고 최상위 validUntil과 화면 세대를 우선해 결과를 숨겨야 합니다.

## API·소유권·수명

[OpenAPI0.1.0-draft](../api/web-recommendation-draft.json), [요청 예제](../api/examples/web-recommendation-request.json), [직접 피드백 예제](../api/examples/web-recommendation-feedback.json). 기존 v1 인증1.1.0과 #19 미리보기0.2.0-draft는 변경하지 않았으며 Android 앱에 자동 연결하지 않았습니다. 새 경로에도 실제 Bearer 토큰 필터·소유권·no-store를 적용합니다.

| 메서드 | 경로 (공통 /api/experimental/web-recommendations) | 동작 |
| --- | --- | --- |
| POST | 공통 경로 | Idempotency-Key·사용자 요청 저장 후 트랜잭션 밖 가상 검색. 웹 결과는 응답으로만 반환 |
| GET | /{id} | 사용자 자신의 요청·직접 평가만 조회. 웹 결과/상호/URL/AI 판정 재표시 없음 |
| PUT | /{id}/feedback | 현재 표시한1–3 ordinal 선택·직접 MATCH/PARTIAL/NO_MATCH와 고정 이유 코드 저장 |
| POST | /{id}/close | 화면 종료/목적지 변경 시 결과 선무효화 후 종료. 반복해도 검색 없음 |
| DELETE | /{id} | 요청/평가 JSON 삭제. 재실행 방지용 최소 tombstone만 유지 |

- 같은 키+같은 요청은 ALREADY_REQUESTED/후보0으로 반환해 자동 재검색/응답 snapshot 재생을 하지 않습니다. 같은 키+다른 요청은409, 같은 view의 구세대/동일 세대 새 요청도409입니다. 최신 generation이 이전 결과와 선택 자격을 무효화합니다.
- 첫 선택은 현재 유효한 응답의 ordinal 범위만 허용합니다. 이미 직접 선택해 저장한 ordinal의 **사용자 평가 수정**은 만료/종료/재시작 후에도 허용하지만 다른 ordinal로 바꾸지는 못합니다. 이를 매장 사실 재확인으로 해석하지 않습니다.
- 재시작 후 웹 결과/선택되지 않은 후보는 복원하지 않습니다. 사용자 요청·기존 직접 평가와 중복 실행 방지는 H2에 남습니다. 새 인증 발급으로 결과/한도를 복구하는 흐름은 없습니다.
- 단일 서버 active window100/사용자2/동시 gateway4를 제한하며 취소를 무시하는 gateway가 실제 반환하기 전까지 실행 슬롯을 돌려주지 않습니다. live adapter는 이 API에 연결하지 않았고 전역 비용 원장·다중 서버·실제 취소 전파는 #17 후속입니다.
- 모델 답변의 JSON 출력 형식/필드가 실제 계정·명시 모델에서 유지되는지는 실호출 미검증입니다. malformed/incomplete/출처 없는 응답은 FAILED로 처리하며 빈 결과로 채우거나 재시도하지 않습니다.

## DB — 사용자 입력과 직접 평가만

Flyway V5 `web_recommendation_requests`를 추가했습니다. 기존 migration을 수정하지 않았습니다. 기존 실제 사용자 소유 `app_users.id`에 FK를 둡니다.

| 저장 | 저장하지 않음 |
| --- | --- |
| 사용자가 입력한 지역/필수 조건·화면 세대·개인화 선택 | 검색 응답/리뷰 원문·매장명/주소/전화·인용 URL·AI O/X/조건 설명 |
| 요청 ID·생성 시각·중복 키/정규화한 사용자 요청 hash | 일반 commands의 웹 응답 snapshot·검색 원문/장소 hash |
| 직접 선택 ordinal·MATCH/PARTIAL/NO_MATCH·고정 이유 코드 | AI 평가를 사용자 만족도로 자동 저장 |

사용자 요구 JSON의 hash는 조건 집합을 정렬해 프로세스가 바뀌어도 동일하게 계산합니다. 삭제는 request_json/feedback_json을 null로 만들며 id/소유자/키/hash/view/generation/생성시각/deleted의 tombstone을 남깁니다. 이것은 완전한 개인정보 삭제/익명화가 아닙니다. tombstone 보존 기간과 계정 삭제/개인정보 운영 정책은 #4 팀 인수 항목입니다. 사용자 정보의 사용 목적·삭제·보관 조건은 운영 전 확정해야 합니다.

personalizationConsent=false여도 이번 요청과 직접 피드백은 사용자 자신의 기록으로 남길 수 있지만 개인화 학습의 동의로 승격하지 않습니다. true도 후속 활용 대상을 구분할 뿐 **현재 모델 학습·취향 자동 갱신을 수행하지 않습니다.** 예약/발신/추가 비용 승인과는 별개입니다.

## 실행과 검증

```bash
./gradlew -PincludeAndroid=false format verify
python scripts/smoke-test.py --check-contract
python scripts/check-web-recommendation.py
```

마지막 검사는 패키징 JAR·임시 H2·임의 loopback 포트만 사용합니다. 생성/인증/최대3·실내 UNKNOWN/불충족 제외·직접 평가·소유권·같은 키/구세대·재시작·삭제·기본 비활성404를 스키마와 대조합니다. 실제 장소/GPT/웹/전화/유료 호출은 없습니다. 기존 개발 DB·앱·기기·키를 변경하지 않습니다.

사용자가 직접 볼 수 있는 MOCK CLI:

```bash
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar \
  --server.port=18084 --app.web-recommendations.enabled=true \
  --spring.datasource.url=jdbc:h2:file:./work/rec13-demo-auth
# 다른 터미널. 출력은 가상 추천이며 실제 웹검색이 아님
python scripts/web-recommendation-demo.py --interactive
```

CLI는 직접 입력한 선택/평가만 저장합니다. --personalization-consent는 명시적 선택이며 기본 false입니다. 토큰은 출력/파일 보관하지 않는 일회성 시험 호출자입니다. 제품 앱의 인증 저장·복구를 대신하지 않습니다.

## 팀 인계·남은 작업

- #16/#26: 기존 미리보기 계약과 별도인 새 초안입니다. 지역/필수 조건 입력, O/X/UNKNOWN/상충·조건·출처/기한, 사용자 평가 UI를 합의하고 화면 identity부터 무효화해 close를 호출합니다. 가격·취향·거리/ETA·release 차량 UX는 이번 구현으로 완료되지 않습니다.
- #4: V5 번호/스키마·tombstone 보존/삭제·개인화 정책·운영 제한 인수. 사용자 입력/직접 평가와 웹 파생 사실의 저장을 구분합니다.
- #11: 추천용 검색 경로와 예약용 연락처 경로를 분리합니다. 특정 플랫폼 직접 수집/AI 입력 제한은 웹검색을 이유로 우회하지 않습니다. 실제 공급자·데이터 처리 조건을 확인하되 모든 매장별 계약을 일률적 개발 전제로 두지 않습니다.
- 실제 GPT 검증: 명시 모델의 도구/출력 호환·단가·계정/키·전송 데이터·횟수/금액 상한을 확인해 사용자 승인 후 소규모 실행. 현재 비용/호환·실제 추천 품질은 미검증입니다.
- #3: 이 응답에는 전화번호/발신 대상이 없고 문의/예약 권한도 false입니다. 실제 전화는 별도로 확보/확인한 연락처·사용자 승인·비용 상한과 결합합니다.


## 이번 로컬 검증 결과

- Java334개(기존313+REC13 신규21), 실패/오류/skip0. format/verify 통과. parser8·일회 승인/transport 경계4·API/DB/수명9개를 추가했습니다. 초기 실패1건은 검색 item을 제거한 테스트 helper가 message index를 고정한 문제였으며 helper 입력을 바로잡고 전체334개를 다시 통과했습니다.
- 패키징 JAR+임시 H2의 신규 계약 HTTP20개와 실제 CLI의 HTTP5개를 통과했습니다. 사용자 입력/직접 평가 재시작 보존·웹 결과 미복원·기존 선택에 대한 나중 직접 평가 수정·삭제/tombstone·기본 비활성404를 대조했습니다. CLI 평가는 가상 시험 입력이며 실제 사용자의 만족도 조사 결과가 아닙니다.
- 기존 패키징 JAR 인증/소유권·MOCK/UNKNOWN·OpenAPI·Flyway/H2 재시작·LIVE 시작 차단도 통과했습니다. 새 실험 API 외 기존 경로의 반환 계약은 유지합니다.
- 수정/신규26파일·로컬 문서 링크108개·JSON/diff 정적 검사 통과. 로컬 로그는 Git 제외 work/web-needs-verify.log, work/web-needs-http.log, work/web-needs-smoke.log에 있습니다. 원격 CI는 PR의 최신 head 기준으로 별도 기록합니다.
- 이번 로컬 Android/기기·실제 모델/웹 수집/전화/유료 호출·실제 API 키 읽기는0회입니다. 실제 추천 정확도/모델 호환을 이 MOCK 통과로 주장하지 않습니다.
