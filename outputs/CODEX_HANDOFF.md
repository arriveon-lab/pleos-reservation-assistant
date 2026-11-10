# Pleos 차량용 AI 예약 비서 — 작업 인계

## 2026-11-10 #35 병합 이후 #38 통합 검증

- 사용자 승인으로 PR #35를4d65c01에 squash merge했고 #34는 종료됐습니다. #38은 이 main을 공유 이력 보존으로 반영하며 PLAN/STATUS/HANDOFF의 충돌에서 양쪽 기록을 보존했습니다. 품질 도구40개 검사와 GPT/웹 상세정보 코드 준비 범위를 함께 유지합니다.
- 이 기록은 최신 main 반영 단계이며 #16/#26 기기·#11 데이터 인수나 실제 GPT/웹 수집/전화/유료 호출을 완료로 세지 않습니다. #38은 코드 준비/가상 검증 범위로 통합하며 실제 실행은 별도 승인 후입니다. 아래 #35 미병합/#34 OPEN 표기는 당시 이력입니다.
- 최신 통합본 format/verify·Java313개/실패·오류·skip0, 가상 품질40개, 패키징 JAR 인증/MOCK/UNKNOWN·OpenAPI·H2 재시작·LIVE 차단을 통과했습니다. 원격 CI는 이 통합 head의 결과를 별도 확인합니다.


## 2026-11-09 REC-10 후속: 공식 사전·키 없는 HTTPS1000행

- 최신 [REC-10](../docs/REC10_SEOUL_PILOT_VALIDATION.md)과 docs/quality/rec10-status-reference.json·rec10-public-sheet-2026-11-09.json을 우선합니다. 제공 표준20차127)일반음식점 원문을 확보해 행정01–04·상세01/02·폐업일 의미를 확인했습니다. 가정 튜플이 아닌 공식 코드/명칭 판정이며 실제 입력1000행의 정상 조합 일치를 확인했습니다. 범용EPSG2097과 데이터셋EPSG5174의 차이는 출력하고5174를 유지합니다.
- 공식 Sheet 메타데이터에 있는 키 없는 HTTPS 표 경로를1회/1000행/2MiB로 읽었으나 레거시 객체 형식으로 JSON 해석에 실패했습니다. 사용자 추가1회 승인을 받아 다시 읽고 그 응답을 메모리에서만 재분석했습니다. 총데이터요청2회·추가 페이지/자동 네트워크 재시도0·0원입니다. 원문/행/연락처/ID·좌표·캡처는 보관하지 않고 집계만 저장했습니다. 첫 로컬 Python import 실패는 네트워크 전이므로 데이터 요청0회입니다.
- 실제1000행: 정상1000, 좌표누락82/유효918, 전화누락719/형식273/placeholder1/미인식7, 도로명주소누락3, ID중복/충돌0. 교차1000→918→1000m433→전화형식116→기본정보115행, 경계1000–1030m8행 제외. 첫 페이지·필터총3959의 제한 범위이며 유일한 매장115곳/현재 영업·예약 가능으로 세지 않습니다. pageSize없음·batch/version/TZ UNKNOWN 유지, 분석 시각과request시작/snapshot을 구분합니다.
- 가상40개(기존29+신규11)와MOCK·Gradle format/verify(Java UP-TO-DATE)를 통과했습니다. JSON/레거시 객체 키·끝 쉼표만 정규화하며 eval/스크립트·주석·함수·중복 키·크기/행수/메타데이터 오류를 거부합니다. 실제 행 fixture나 새 의존성은 없습니다. 독립 도구는 네트워크/키 로딩 없이 로컬 입력만 처리합니다. 공개 실제 집계와MOCK3행/통과0을 구분합니다.
- #34 인수는 독립 명세/접근·제한된 품질·도구/검증/한계 기록입니다. batch/TZ확인값이 없어UNKNOWN을 유지했고, 키 있는OpenAPI인증은 키 없는 대체 경로에서 불필요해 미실행입니다. 운영 경로/팀 계정·원본 최신성/CRS 차이·추가 품질·보관/제공·공급자 채택은 기존 #11에 남깁니다. #35 병합 전#34 OPEN, 다른 담당 이슈/PR 유지. 제품 추천/채택/예약/발신false·LIVE차단 유지합니다.
- 이번 키 전송·전화/Kakao/AI/유료 호출0회. 추가승인 사용 표식은 Git제외 work/rec10-sheet-additional.used이며 소비됐습니다. 추가 실제 조회를 자동 재개하지 않습니다. 로컬 증거 work/rec10-standard20-tests.log·standard20-mock.json·standard20-gradle.log·sheet-aggregate.json·sheet-additional-aggregate.json은 가상/실제를 구분합니다. 아래 이전 사전미확보·실제조회0회는 당시 단계 기록입니다.

## 2026-11-09 REC-10 #34 인증키 준비·교차 진단

- #33은 사용자 진행 지시로 `abd9aa4`에 squash merge했고 #32 CLOSED입니다. 현재 `feat/34-seoul-pilot-validation`은 이 main에서 분기했습니다. 다른 미완료 PR/담당 범위는 유지합니다.
- [REC-10](../docs/REC10_SEOUL_PILOT_VALIDATION.md)을 우선합니다. 개인 개발용 키는 명시 동의 후1개 발급했고 정상 상태/로컬 Git 제외0700/0600을 확인했습니다. 키는 팀/CI에 공유하지 않습니다. 인증 조회나 운영 주체 승인은 미완료입니다.
- 독립 도구의 `--pilot-screening`은 가정 튜플·명시적으로 빈 폐업일·유일 MGTNO·유효 좌표·반경1000m·전화형식·상호/도로명주소를 같은 행에서 집계합니다. 모든 중복 그룹 제외,1000–1030m 경계 제외와 UNKNOWN을 보존합니다. **상태 사전 미확보**를 출력에 명시하며 생산 후보 수 null·추천/채택/예약/발신 false입니다. 공급자 adapter/API/DB에 연결하지 않았습니다.
- Python29개(기존15+신규14)·MOCK 통과. 초기 결측 테스트의 batch 스키마 전제 오류를 수정하고 재검증했습니다. 초기 Gradle은 Java FROM-CACHE였으나 원격 CI에서 기존 REC-08 계약 파일 캐시 누락을 확인했습니다. test 산출물 선언 후 format/verify·Java268개를 재실행해 실패/오류/skip0, 파일 제거 후 FROM-CACHE 복원·계약2개 통과를 확인했습니다. CI에는 가상 옵션만 추가하며 서버 런타임 변경은 없습니다. 실제 행 fixture·새 런타임 의존성·Android 변경 없음.
- 키 없이TLS443 연결1회가 timeout이어서 애플리케이션 요청/키 전송0회입니다. 다른 서비스의 공식 HTTPS 미제공 답변을 해당 API의 확정 결론으로 확대하지 않습니다. 다음은 공식 상태 사전·batch/TZ·안전한 공급 경로/접근과 제한된 실제 지역 품질 검증입니다. 원문 저장 없이 집계만 보존하며 기존 sample 상한은 재사용하지 않습니다. #34 OPEN/#11 채택 대기와 #3/#4/#26의 인수를 유지합니다.
- 이번 실제 음식점 조회·Kakao·전화·AI·유료 호출0회. 로컬 증거는 Git 제외 work/rec10-tests.log·mock-screening.json·transport-check.json·cache-output-verify.log·cache-restore.log·java-test-counts.json, 공개 상태는 docs/quality/rec10-validation-state.json입니다. 계정 정보·키 값·상태 화면을 Git에 올리지 않습니다.
