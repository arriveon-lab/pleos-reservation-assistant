# 구현·검증 현황

## 2026-11-10 #35/#38 통합과 #42 CI·문서 이력 복구

- 사용자 승인으로 #35는4d65c01, #38은9ea227d에 squash merge됐고 #34/#37은 독립 검증/코드 준비 범위로 종료됐습니다. 실제 공급자 채택·기기/ETA·전화/유료 실행 인수는 별도입니다.
- #42에 최신 main과 REC-08 Gradle cache 산출물 수정을 반영했습니다. 문서 충돌 정리 중 누락된 PLAN/STATUS/HANDOFF 이력은 원본4d65c01/a22745a/6e48416과3-way 재대조해 복구했습니다. 세 원본의 모든 비어 있지 않은 행 보존을 확인했습니다. 전화 계획과 문서 복구만 변경하며 런타임 구현을 추가하지 않습니다.
- 통합 기반 Java313개·JAR 인증/MOCK/UNKNOWN·OpenAPI/H2 재시작/LIVE 차단을 확인했습니다. REC-08 가상 파일 제거 후 FROM-CACHE 동일 hash 복원·추천 계약2개를 재현했습니다. 실제 외부 장소/GPT/전화/유료 호출0회입니다. 아래 실패/미병합 기록은 당시 이력이며 최신 CI는 해당 PR head를 따릅니다.


## 2026-11-10 #35 병합 이후 #38 통합 검증

- 사용자 승인으로 PR #35를4d65c01에 squash merge했고 #34는 종료됐습니다. #38은 이 main을 공유 이력 보존으로 반영하며 PLAN/STATUS/HANDOFF의 충돌에서 양쪽 기록을 보존했습니다. 품질 도구40개 검사와 GPT/웹 상세정보 코드 준비 범위를 함께 유지합니다.
- 이 기록은 최신 main 반영 단계이며 #16/#26 기기·#11 데이터 인수나 실제 GPT/웹 수집/전화/유료 호출을 완료로 세지 않습니다. #38은 코드 준비/가상 검증 범위로 통합하며 실제 실행은 별도 승인 후입니다. 아래 #35 미병합/#34 OPEN 표기는 당시 이력입니다.
- 최신 통합본 format/verify·Java313개/실패·오류·skip0, 가상 품질40개, 패키징 JAR 인증/MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. 원격 CI는 이 통합 head의 결과를 별도 확인합니다.

## 2026-11-09 전화예약 에이전트 개발 계획

- [전화예약 에이전트 계획](PHONE_RESERVATION_AGENT_PLAN.md)에 Telkit·LiveKit 기반 예약 발신, 회신 식별, 변경 승인·식당 재확인, UNKNOWN·중복 복구와 P01~P11의 일정·인수 기준을 정리했습니다. 15영업일은 역할별 병행 개발을 가정한 예상치입니다.
- 사용자 요청으로 프로필·여정별 동승자 조건·예약 당시 조건 사본을 분리하고, 재사용 저장 선택·예약 승인·학습 동의 구분, 최소 정보 전달, 수정·삭제·만료 기준을 추가했습니다. P01/P05/P06/P08과 인수 시나리오 9개를 보완했으며 새 DB/API 구현은 아닙니다.
- 계획의 함수·API·상태·저장 모델은 팀 검토용 제안이며 현행 계약·소스·DB·MOCK/LIVE 동작을 변경하지 않았습니다. #3/#4/#26의 구현·인수 상태와 담당을 유지합니다.
- 이번 변경은 문서 4개·로컬 링크 65개·작업 ID 11개·형식·게시 정보·명명 규칙·diff 정적 검사를 통과했습니다. 로컬 Gradle·기기 테스트를 새로 실행한 결과가 아니며 실제 전화·계정 생성·번호 구매·유료 API 실행은 없습니다.
## 2026-11-09 REC-11 #37 웹 상세정보·GPT 코드 준비

- 사용자 선택에 따라 [GPT 출처 검색·웹 상세정보 모듈](REC11_PLACE_DETAILS.md)을 최신 mainabd9aa4 기반 `feat/37-place-detail-enrichment`에 분리했습니다. GPT 인용 URL 후보 → 별도 이용 조건/host 검토 → 이름+주소 일치 → 구조화 필드/출처·관측/표시 기한·충돌/UNKNOWN → 후보 최대3개의 남은 질문을 제공합니다. #19/#26 코드나 공개 v1/미리보기 계약은 변경하지 않았습니다.
- Responses/web_search의 일회성 Permit·명시적 모델·도구1회/출력1024토큰·최대30초/1MiB·재시도/redirect 차단을 구현했습니다. Permit은 메모리상의 제한이며 운영 인증/전역 비용 원장이 아닙니다. 실제 실행 전 데이터·모델·횟수·비용 상한 승인이 별도로 필요합니다. HTTP/페이지 adapter는 Spring 미등록·제품 미연결입니다.
- Java313개(기반268+신규45), 실패/오류/skip0. 가상 JSON-LD·GPT 응답·loopback HTTP로 지점/주소·출처 충돌·시각·취소/만료·중복 실행·응답 한도와 후보 결합을 검증했습니다. `:server:placeDetails`의 전체 예제도 MOCK입니다. 패키징 JAR 인증·기존 MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. jsoup1.24.1과 lockfile을 추가했습니다.
- 웹 일반 보고는 GENERAL이며 도착 시각 빈자리·대기·예약 가능이나 권한으로 전환하지 않습니다. #11의 KAKAO/미검토 출처 GPT 전송을 차단하며 검토된 공공 출처의 제품 채택도 아직 아닙니다. 일반 문장/PDF/JS-only/다른 업종과 실제 TLS/모델/청구/매장 페이지·Android 기기는 미검증입니다. 이번 실제 GPT·웹 수집·Kakao·전화·유료 호출0회입니다. 원격 CI 결과는 PR에서 따로 확인합니다.
- #16 별도 브랜치는 mainabd9aa4와의 충돌을 공유 이력 보존으로 해결하고 #26 담당의 [정적 검토](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008)를 반영해 head5526f92로 push했습니다. Java329·Python7/15·가상HTTP137·토큰HTTP16·CI3개 통과. 계약 추가 차단 필드는 없었고 완료 preview 재사용 금지·app token의 MOCK_OPERATOR 미보유를 확인했습니다. 실제 #26 구현/공동 기기·#1 ETA·#11 인수는 남아 #16 OPEN/#19 Draft입니다. 이 #37 모듈은 이후 필드 합의 후 별도 연결합니다.
## 2026-11-09 REC-10 #34 공식 상태·HTTPS 실제 입력 확인

- [REC-10](REC10_SEOUL_PILOT_VALIDATION.md)의 최신 결과가 아래 초기 진단보다 우선합니다. 공공데이터 제공 표준20차의127)일반음식점 원문에서 행정01–04·상세01/02 코드/명칭·폐업일 의미를 확인했습니다. 기존 가정 표기를 공식 사전 기반 판정으로 바꾸고 코드/명칭·상태 충돌을 구분했습니다. 범용 표준EPSG2097과 데이터셋EPSG5174의 차이를 기록하고 데이터셋별5174를 유지합니다.
- 키 없는 공식 웹 Sheet HTTPS 경로를 확인해 영업상태01 첫 페이지를 읽었습니다. 최초1회는 레거시 형식 파싱 실패, 사용자 승인 추가1회는1000행 집계 성공입니다. 총2회·자동 재시도/추가 페이지0·0원이며 원문은 메모리에서만 재분석하고 폐기했습니다. 키 있는OpenAPI 인증 성공이나 안정적인 운영 API 채택은 아닙니다.
- [실제 집계](quality/rec10-public-sheet-2026-11-09.json): 정상1000행, 좌표누락82·유효918, 전화누락719(71.9%)·기본형식273·placeholder1·미인식7, 도로명주소누락3, ID중복/충돌0입니다. 교차 단계는1000→918→반경1000m433→전화형식116→상호/도로명주소115행입니다. 1000–1030m8행은 제외합니다. 수신1000/보고된필터전체3959의 제한 범위이며 서로 다른 실제 매장115곳이나 예약 가능을 뜻하지 않습니다. pageSize/batch/version/TZ는UNKNOWN입니다.
- 식별자 키/끝 쉼표만 정규화하는 비실행 파서와 명시적 로컬 sheet-json 입력을 추가했습니다. 가상40개(기존29+신규11)·MOCK CLI·Gradle format/verify 통과(Java UP-TO-DATE). 실제 행 fixture·새 의존성·제품 API/DB/Android 변경 없음, CI는 가상 검사만 수행합니다.
- #34 범위는 제한된 독립 검증이며 PR 병합 전OPEN입니다. 운영 경로/팀 계정·원본 최신성/CRS 차이·저장/전달·공급자 채택은 기존 #11에 남깁니다. 생산 후보 수null·추천/채택/예약/발신false와 제품MOCK/LIVE차단 유지. 키 전송·전화/Kakao/AI/유료 호출0회이며 아래 초기 실제 조회0회/상태 사전 미확보 표기는 당시 이력입니다.

## 2026-11-09 REC-10 #34 인증키와 교차 검사

- 사용자 진행 지시로 필수 CI3개·최신 main·충돌 상태를 확인하고 #33을 `abd9aa4`로 squash merge했습니다. #32 CLOSED, 다른 Draft/담당 이슈는 유지합니다. 이 main에서 `feat/34-seoul-pilot-validation`을 만들었습니다.
- [REC-10](REC10_SEOUL_PILOT_VALIDATION.md)의 개인 개발용 인증키 발급/정상 UI 상태를 확인했고 로컬 Git 제외0700/0600으로 보관합니다. 팀 공유·실제 API 인증 성공·운영 키 승인은 아닙니다. 이전 REC-09의 키 미발급 기록은 당시 이력입니다.
- 품질 도구에 선택적 `--pilot-screening`을 추가했습니다. 동일 행 교집합·모든 중복 행 제외·상세 상태/폐업일·1000m와1030m 경계·연락처·기본 정보를 집계합니다. **상태 튜플은 코드 사전 확보 전 실험 가정**이고 생산 후보 수는 null, 추천/채택/예약/발신 권한은 false입니다.
- Python29개(기존15+신규14)·MOCK CLI 통과. 원격 CI의 기존 REC-08 계약 파일 캐시 누락을 발견해 Gradle test 산출물로 선언했습니다. 수정 후 format/verify·Java268개 재실행(실패/오류/skip0), 생성 파일 제거 후 FROM-CACHE 복원·계약2개 통과를 확인했습니다. CI에 가상 옵션 실행만 추가했습니다. 실제 행/키 fixture·새 의존성·서버 런타임/Android/API/DB 변경은 없습니다. Gradle 테스트 산출물 선언만 보강했습니다. 기기/실데이터 검증이 아닙니다.
- 키 없는 TLS443 연결1회는 timeout, 애플리케이션 요청0회·키 전송0회입니다. 다른 서비스의 공식 HTTPS 미제공 답변은 확인했으나 OA-18655 전체 지원 결론으로 확대하지 않습니다. 상태 사전·batch/TZ·안전한 공급 경로/실제 지역 품질은 미검증이며 #34 OPEN, #11 공급자 채택과 #3/#4/#26 인수는 별도입니다. 전화·Kakao·AI·유료 호출0회, 제품 MOCK/LIVE 차단 유지.

## 2026-11-09 REC-09 #32 공공데이터 독립 검증

- [REC-09](REC09_SEOUL_DATA_VALIDATION.md)에 OA-18655의 공식 명세39필드·공개 sample1–5 제한·EPSG:5174·라이선스를 확인했습니다. 개인 인증키 발급/로그인/약관·결제·IP 설정 변경은 없습니다.
- scripts/seoul-place-quality.py는 기본 네트워크 없는 MOCK 또는 허용된 로컬 XML의 품질만 집계합니다. pyproj3.7.2·PROJ 네트워크OFF, axis order/ballpark·입력 한도·개인정보 비노출을 검사합니다. Java/Android 의존성·공개 API·DB·예약·LIVE 경계는 변경하지 않았습니다.
- 가상 데이터 테스트15개 통과. 투영 원점·파생 시범점·축 교환/불량 좌표, 누락/중복 충돌, 정상0건/실패·XML 경계, 행 시각과 snapshot 최신성 분리, 출력 비노출을 검증했습니다. 원본 행·연락처를 테스트 fixture에 넣지 않았습니다.
- 실제 공개 sample을1회/5건/0원으로 읽고 자동 재시도·추가 페이지 없이 [집계만](quality/rec09-public-sample-2026-11-09.json) 보존했습니다. 전화 기본형식5/5, 좌표 누락2/5·변환가능3/5, 도로명주소 누락3/5, 행정상 영업/정상1/5·폐업4/5, ID결측/중복/충돌0/5입니다. 첫5건은 지역/현행 영업점 대표 표본이 아니며 실제 번호 유효/발신 동의는 미검증입니다. snapshot 기준일은 UNKNOWN이고 조회 시각/오래된 행 수정일로 대체하지 않습니다.
- CI에는 품질 의존성 설치·가상 테스트15개·MOCK 실행만 연결했습니다. CI에서 실제 표본을 재조회하지 않습니다. #32 범위는 독립 도구/명세/표본 증거이며 #11/#16–18·다른 담당의 제품/기기 인수를 완료로 세지 않습니다. 이번 실제 장소 API는 공개 sample1회이며 Kakao·전화·AI·유료 호출은0회입니다.
- 문서5개·로컬 링크78개와 명세/집계JSON·명명/diff 정적 검사를 통과했습니다. Java/Android 코드를 변경하지 않아 로컬 서버/기기 테스트를 새로 실행하지 않았고, 원격 필수 CI는 이 PR의 실제 결과로 구분합니다.

## 2026-11-09 SDK 대체·단계별 연동 계획 (#30)

- 사용자 결정에 따라 [위치·음성 SDK 대체 계획](SDK_FALLBACK_PLAN.md)을 작성했습니다. 표준 API 우선·기능별 adapter·명시적 개발 MOCK·Pleos 구현체 추가 조건과 SDK-01–10 인수 시나리오를 정의했습니다. 새 런타임 구현/테스트 결과가 아닙니다.
- ArriveOn 프로젝트 생성 후 콘솔에서 NaviHelper·Vehicle State 사용 가능, FusedLocation·STT·TTS 준비 중, 신청 내역 없음을 확인했습니다. 공식 신청/표준 API 문서를 대조했으며 권한 신청·문의 발송은 수행하지 않았습니다. 프로젝트 카테고리는 미설정이며 팀원 초대는 계정 이메일 확보 후 별도 진행해야 합니다. 키·시크릿은 공개 문서에 저장하지 않았습니다.
- 사용자 승인에 따라 추천 PR #29를 `c482baa`로 main에 squash merge했고 #28은 종료됐습니다. #31에 이 main을 반영하면서 상태/인계 충돌을 양쪽 내용 보존으로 해소했습니다. #31의 main 대비 차이는 문서뿐이며 다른 Draft PR은 병합하지 않습니다. 아래 BE-02 “PR 제출·미병합” 제목과 이전 main 값은 당시 이력입니다.
- 차량 #1·전화 #3·UX #25/#26의 담당과 미완료 상태를 유지합니다. 사용자 음성 런타임은 별도 담당/이슈 배정이 필요합니다. 표준 위치·STT·TTS의 Pleos 기기 실행, 외부 전화/예약 연동은 미검증입니다. 이번 작업의 실제 장소 검색·전화·유료 API 실행은 0회입니다.
- 최신 main 반영 후 문서 7개의 로컬 링크 79개·SDK-01–10의 고유성·코드 블록/개행·신규 계획의 개인정보 패턴·브랜치/제목 규칙·diff 공백 검사를 통과했습니다. 문서만 변경했으므로 Gradle/기기 테스트를 새로 실행하지 않았으며 인수 시나리오 통과로 세지 않습니다.

**REC-08 #28 (2026-11-09):** [후보 추천·질문 계획](REC08_RECOMMENDATION_PLAN.md)의 순수 평가기·MOCK 실행 예제·별도 JSON Schema를 추가했습니다. 최대3개 후보에 추천 근거·누락 정보·UNKNOWN 질문을 반환하고 bookable/발신 승인은 false입니다. Java268개(기반228+신규40)·계약3문서·부적합변형8개·JAR 인증/MOCK/H2 재시작/LIVE 차단 검증을 통과했습니다. 당시 검증 기준은 인증 #21이 병합된 main 1b37bc2입니다. #19의 main 충돌은 2026-11-09 별도 작업에서 해소했고 #16/#26 제품 화면 연결은 별도 인수입니다. 추가 실제 검색/전화/AI/비용 작업0회이며 #11/#16–18의 완료를 의미하지 않습니다. #29는 사용자 승인 후 c482baa로 main에 병합됐고 #28은 종료됐습니다.

## BE-02 #20 작업 브랜치 — 2026-11-08 (PR 제출·미병합)

- `/api/v1`에 앱 설치별 익명 주체·토큰 발급/검증/갱신/폐기를 구현했다. 사용자는 로그인하지 않는다. 갱신 후 주체는 유지하고, 같은 갱신 토큰+요청 키는 120초 내 동일 응답을 재생한다. 토큰 원문을 DB에 저장하지 않는다.
- V3 소유권 격리를 유지하고 V4 인증 저장 구조를 추가했다. 기존 데이터는 미확인 소유권으로 보존하며 자동 배정하지 않는다. 보호 API는 실제 발급한 접근 토큰으로 호출할 수 있다. 일반 토큰은 MOCK_OPERATOR 권한이 없다.
- #16은 동일 Principal·필터 순서로 연결한다. 소스는 작업 브랜치에 합치지 않고 임시 통합 검증한다. 계약 1.1.0, URL v1을 유지한다. 제품 Android 토큰 저장·자동 갱신은 별도 후속 작업이다.
- 사용자 승인에 따라 기능별 6개 커밋으로 분리했다. PR 검증 후에도 사용자 지시 없이 병합하지 않는다. #20 범위는 토큰 인증까지 확대했고 #4의 UNKNOWN 해결·승인 증빙·outbox·운영 DB는 후속 범위다.
- 발급 제한은 단일 서버 메모리 기준이다. 운영 프록시/분산 제한·만료 토큰 정리·앱의 안전 저장·복구 UX는 후속 작업이다. 실전화·실제 장소·유료 호출은 없다.

- 최종 검증: 서버 227개, #16 `6f286f8` 전체 통합본 271개 테스트 실행·실패/오류/skip 0. 실제 발급 토큰의 HTTP·갱신 응답 재시도·폐기·H2 재시작과 기존 예약/UNKNOWN 스모크 통과. 통합본의 미리보기 계약 96건 및 실제 토큰 HTTP 16건(생성/검색/취소/종료·타인 접근·갱신 후 소유권·폐기) 통과. Android·원격 CI·실전화는 미검증이다.
- #16 통합 시 문서 2개와 스모크 스크립트 충돌을 양쪽 내용 보존으로 해결하고, PlacePreviewDisabledTest의 계약 기대값을 1.1.0으로 맞췄다. 인증 필터가 조기 거절할 때 #16 보안 헤더가 누락되는 문제를 발견해 원본 토큰 필터에서 수정하고 회귀 검사했다. 실제 #16 브랜치는 변경하지 않았다.

아래는 기존 main 및 이전 작업의 검증 이력이다.


**통합 범위와 검증 기준 (2026-11-07):** [PR #14](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/14)를 `8cbe890`으로 squash merge했고 #12는 CLOSED입니다. #15는 이 main을 반영한 상태에서 통합 검증했습니다. #13의 종료 범위는 독립 미저장 시연·최초 실제 검색 검증입니다. 미완료 제품 인증/API/화면·navigation 계약은 #16, 자동 정리·재시작/다중 서버 실행 관리는 #17, 지역 품질·실제 IP 제한 검증은 #18로 이관했고 #11 정책 검토는 OPEN입니다. [통합 범위와 후속 조건](REC_FOLLOWUPS.md)을 우선합니다.

**통합 검증:** `./gradlew -PincludeAndroid=true verifyAll` 성공. 서버 200개 실행·실패/오류/skip 0, Android Lint/APK 검사 통과(일부 UP-TO-DATE, 단위 테스트 NO-SOURCE). `work/contract-venv/bin/python scripts/smoke-test.py --check-contract`로 JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단을 확인했습니다. 기기 실행과 실제 예약은 검증하지 않았습니다.

**실제 실행 증거:** [독립 시연](REC04_LIVE_SEARCH.md)의 Kakao 1회 성공·3곳 일시 표시·쿼터 1 / 100,000, 비용 0원·전화/예약/AI 0회입니다. 이번 병합 중 추가 실제 호출은 없으며 제품 MOCK/LIVE 차단·응답 미저장을 유지합니다. 앞선 브랜치별 188개/156개 기록과 통합 결과를 구분합니다. 아래 OPEN/Draft·병합 대기 표현은 당시 이력이며 최신 병합 상태는 GitHub가 기준입니다.

**VOICE-01 업체 문서 검토 (2026-11-08, #3 `feat/3-call-normalizer`):** Vox·한국AI통신·Twilio·CLOVA AiCall의 공개 문서·약관·요금을 [업체 검토](calls/VENDOR_REVIEW.md)에 정리했습니다. Vox를 PoC 1순위 후보로 두되 업체를 선정하지 않았습니다. 문서 확인만이며 계정 생성·번호 구매·실제 발신·유료 API는 0회, 제품 코드·API·DB 변경과 Gradle 재실행은 없습니다. 법률 검토와 실제 한국어 통화 품질은 미검증입니다.

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


## 2026-11-07 REC-04 독립 실제 장소 검색 1회

- 사용자가 저장 없는 실제 검색 시연 진행을 지시했고, 일반 실시간 검색을 #11 전체 문의 답변이나 #12 전체 완료와 분리했습니다. `feat/13-place-api-validation`은 main `a55de3a` 기준입니다. 추천/장소 담당의 검색·정규화·품질 검증 범위이며 제품 UI #5·전화 #3·인증/DB #4는 별도로 유지합니다.
- `PlacePreviewDemo`와 `:server:placePreview`를 추가했습니다. 기본 MOCK, 명시적 live 모드에서만 기존 Kakao adapter를 사용합니다. 127.0.0.1의 임의 경로 첫 GET에만 query(FD6·위도37.544/경도127.055·1km·거리순·page1·size3)를 실행하며 새로고침/동시 열기/잘못된 경로/POST/cross-site/prefetch가 추가 호출을 만들지 않습니다.
- 무료 대상/카카오맵 ON·유료 꺼짐·조회 전 사용량·기존 REST 키/IP 설정을 확인했습니다. 기존 REST 키 값을 출력하지 않고 Git 제외 로컬 파일(0600/부모0700)에 설정했습니다. 새 키 생성/재발급·IP/유료/월렛 설정 변경은 없습니다.
- 실제 HTTPS/키 인증 조회 1회 성공, 실제 식당 3곳을 브라우저에 일시 표시했습니다. 공급자 콘솔 카테고리 검색이 호출 없음 → 1 / 100,000으로 바뀐 것을 확인했습니다. 비용 0원, 전화/예약/AI 호출 0회. 응답 원문·장소 ID/주소/연락처·결과 캡처를 파일/DB/로그/fixture에 저장하지 않았습니다. 검증 상태/개수/실행 수 메타데이터만 기록했습니다.
- no-store/no-referrer/CSP·문자열 escape, 출처/오류/정상0건과 미확인 조건 구분, 5분 표시 종료를 적용했습니다. 첫 응답 후 프로세스가 종료되며 API 결과 캐시나 제품 검색/예약 연결은 없습니다. 제품 app.mode=LIVE 시작 차단과 기존 H2 명령 snapshot은 유지합니다.
- main의 기존 144개 + 도구 HTTP/키 검증 12개 = 156개 실패/오류/skip 0, format/verify와 패키징 JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단 통과. 별도 PR #14의 미병합 188개 테스트를 줄이거나 덮어쓴 것이 아닙니다. 새 의존성·migration·공개 API/Model 변경 없음.
- 다음: 다른 시범 입력/실데이터 품질·IP 제한 사례의 실행 범위 정리, #5 화면/#12 API 계약 연결, #11 예약 보관/전화 제공 조건. 최초 1회 성공을 전체 지역 품질·실예약 완료로 세지 않고 #13은 OPEN/Draft PR로 관리합니다. 실행 메타데이터는 Git 제외 work/rec04-first-live-result.json, 검증은 work/rec04-preview-*에 있습니다.

## 2026-11-07 PR #10 통합과 REC-03 첫 구현

- 사용자가 머지와 후속 착수를 승인해 PR #10을 Ready 전환 후 필수 CI·충돌·미해결 대화·현재 head를 확인하고 squash merge했습니다. merge commit: `a55de3aaa6e0b715f77495984ae96ca9ddc30b06`. 원격 `feat/2-place-adapter`는 삭제됐으며 #2 CLOSED, #11/#12/#13 OPEN을 확인했습니다. main 보호나 Closes 규칙 우회는 없습니다.
- 최신 main에서 `feat/12-place-preview`를 생성했습니다. package-private `PlacePreviewSession`은 진행 중 동일 세대/query 합류, 세대 충돌/구세대 거부, 목적지 변경·취소·제한 시간·늦은 응답, 세션당 실제 실행 1개+최신 대기 1개를 구현합니다. Spring bean·공개 endpoint·기존 workflow에는 등록하지 않았습니다.
- 단위 22개와 실제 Spring/H2 통합 3개를 추가했습니다. 조회 지연 중 기존 검색 진행, preview DB 미저장, DB mutex 점유와 preview 독립, 트랜잭션 안 start 거부를 검증했습니다. 기존 144개를 재실행해 **169개 실행, 실패/오류/skip 0**입니다.
- `./gradlew -PincludeAndroid=false format verify`와 `work/contract-venv/bin/python scripts/smoke-test.py --check-contract` 통과. 로컬 Git 제외 증거: `work/rec03-preview-verify.log`, `work/rec03-preview-tests.json`, `work/rec03-preview-smoke.log`.
- [REC-03 구현 범위](REC03_PREVIEW.md)에 세션 메모리 수명, 완료 결과 재조회/캐시 없음, 취소/응답 세대, 재시작 후 중복 기억 없음과 후속 계약을 기록했습니다. #12의 팀 검토·인증/세션 수·전체 호출 상한·재시작 복구·navigation freshness·공개 API/화면은 미완료입니다.
- 공개 API/Model·migration·의존성 변경 없음. 실제 Kakao 조회·키 값 복사·전화·유료 실행 0회, 제품 MOCK과 LIVE 차단 유지. Android 기기 검증이나 실제 서비스 연결 성공으로 세지 않습니다.


## 2026-11-07 REC-03 소유 scope·세션 수명과 실행 상한

- 같은 `feat/12-place-preview` / Draft PR #14에서 `PlacePreviewRegistry`를 추가했습니다. 서버 생성 UUID, 신뢰된 호출자 owner scope 대조, owner별/전체 세션 상한, 단조 경과 시간의 유휴 만료를 구현했습니다. foreign/미존재/만료 세션은 같은 NOT_FOUND로 처리하고 비정상 요청은 수명을 갱신하지 않습니다.
- 여러 세션의 동시 실행 슬롯·Registry 수명 누적 승인 예산을 공유합니다. 취소/만료를 무시하는 adapter가 실제로 반환하기 전까지 슬롯을 유지하고 실패·취소나 세션 재생성으로 누적 예산을 돌려주지 않습니다. 삭제/재시작된 UUID를 자동 복원하거나 재조회하지 않습니다.
- Registry 18개 + H2 추가 1개를 더해 총 **188개 실행, 실패/오류/skip 0**입니다. 기존 H2 비차단/DB 미저장 테스트도 Registry 경로에서 통과했습니다. format/verify와 JAR MOCK HTTP/OpenAPI/H2 재시작/LIVE 차단 통과. 로컬 증거는 Git 제외 `work/rec03-registry-results.json`, `work/rec03-registry-verify.log`, `work/rec03-registry-smoke.log`에 있습니다. 초기 테스트 helper 컴파일 오류는 수정했고 초기 로그도 따로 보관했습니다.
- 로그인 인증·외부 요청의 owner 검증은 아직 미구현이며, Owner는 trusted caller가 공급할 내부 scope입니다. 만료는 요청/명시적 reap 시 정리하고 자동 정리 스케줄은 아직 없습니다. Registry 재생성/다중 인스턴스를 넘는 누적 예산·영속 claim·불확실한 실행 대조도 미완료입니다.
- 공개 REST API·Spring bean·기존 WorkflowService 연결·Model/migration/의존성 변경 없음. navigation freshness/ETA 재검증, #4/#5 계약·공개 API/화면, 실제 인증/복구는 다음 작업입니다. #12는 OPEN·PR #14는 Draft로 유지하며 실제 Kakao 조회·전화·비용은 0회입니다.
