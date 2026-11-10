# REC-12 공공데이터 로컬 catalog와 예약 연락처 경계

2026-11-10 KST, 담당 tiemo0708, [#48](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/48). **오프라인 코드 준비·가상 검증이며 운영 공급자 채택/실제 검색·전화·예약 완료가 아닙니다.** #11의 사용자 결정에 따라 예약용 매장 정보는 서울시 성동구 OA-18655를 우선합니다. 정책 인수는 [#23](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/23), 화면 계약은 #16/#26, 전화 구현은 #3, DB 계약은 #4의 담당 범위를 유지합니다.

## 확인한 출처와 사용 경계

- [OA-18655 카탈로그](https://data.seoul.go.kr/dataList/OA-18655/A/1/datasetView.do)는 공공누리1유형/상업적 이용·변경 가능/제3저작권자 없음 표시입니다. [제1유형 조건](https://www.kogl.or.kr/info/licenseType1.do)에 따라 기관·데이터명·원본 링크·이용허락을 표시하고 제공 연도/버전/가공 내역을 수신 증거와 함께 기록합니다. 모든 매장과 별도 저작권 계약을 체결해야만 개발할 수 있다는 뜻은 아닙니다.
- [서울시 약관10·11조](https://data.seoul.go.kr/etc/accessTerms.do)는 출처표시 조건 자유이용 원칙과 제3자 권리 예외를 안내합니다. 저작권 이용허락은 개인정보·외부 처리업체 보관/삭제·국외 처리·실제 발신 승인의 대체물이 아닙니다. 개인 API 키는 공유하지 않습니다.
- [Kakao 답변2](https://devtalk.kakao.com/t/local-api/152647/2)는 동일 이용자 비교 세션 메모리 허용/종료 폐기, 직접 전화 연결 가능, 외부 전화/음성 공급자 상호·번호 전달 및 장기 예약 보관 불허를 안내합니다. 카카오 ID/URL 예외는 카카오맵 연결용입니다. 이 catalog는 Kakao 결과/재입력/GPT 요약을 수입하거나 공공데이터로 재분류하지 않습니다.
- [Naver Developers 검색약관2.3](https://developers.naver.com/products/terms/)은 AI 입력/제3자 제공 제한을 두고, [지역 검색 전화 필드](https://developers.naver.com/docs/serviceapi/search/local/local.md)는 값을 반환하지 않습니다. API HUB의 별도 신규 계약은 이번에 검토하지 않았습니다. 매장 공식 홈페이지도 특정 사이트의 조건 검토 전 자동 수집 대상으로 채택하지 않습니다.

## 입력 → 로컬 저장 → 검색/추천

1. 이미 별도로 적법하게 확보한 공식 OA-18655 XML만 입력합니다. 도구에 HTTP/키 읽기/자동 다운로드/페이지 반복은 없습니다. 공식 웹 표의 점검 경로를 운영 API 계약으로 채택하지 않습니다. 현재 실제 원문을 다시 확보하거나 저장하지 않았습니다.
2. 기존 REC-09/10 XML 파서·공식 상태 판정·네트워크 OFF EPSG:5174→4326 변환을 재사용합니다. 최대2MiB/1000행이며 모든 중복 관리번호, 비정상/충돌 상태·폐업일 존재/미제공·상호/도로명주소 누락·불량 좌표·시범 중심1km 밖·번호 누락/모바일/placeholder를 제외합니다. 모바일 검토는 개인정보 판정을 확정하는 분류가 아닙니다.
3. 자체 ID는 데이터셋 URL+관리번호에서 결정적으로 생성하며 원천 ID도 보존합니다. 실제 식별/상호/주소/번호/좌표는 비공개 파일에만 저장합니다. CLI는 건수/사유 코드만 출력하고 오류에 원문/경로를 싣지 않습니다.
4. JSON은 새 `*.private.json` 파일에만 저장합니다. 부모0700·파일0600, 기존 파일/심볼릭링크 덮어쓰기 거부, Git ignore입니다. 동일 파일 overwrite 대신 새 버전을 생성하고, 삭제는 운영자 수동 작업입니다. 개인 디렉터리의 권한을 자동으로 변경하지 않습니다.
5. Java `SeoulPlaceCatalog.load`가 버전/원천/라이선스·ID·중복·상태/번호·크기/모양을 재검사합니다. `SeoulPlaceSearchAdapter`는 이 명시적 snapshot만 조회하고 반경/거리순/limit을 적용합니다. Spring bean·제품 주입·공개 HTTP endpoint는 추가하지 않습니다.
6. 기존 `PlaceRecommendationPlanner`로 최대3개·근거·UNKNOWN 질문을 만듭니다. 음식 종류는 원천 업태만으로 추론하지 않고 null, 현재 영업/주차/아동/대기/빈자리/예약 가능을 생성하지 않습니다. `bookable=false`, `inquiryDispatchAllowed=false`입니다.

가상 fixture의 상호·주소·번호·관리번호는 합성 데이터이며 어떤 실매장의 연락처라고 검증한 값이 아닙니다. 어떤 fixture도 실제 발신에 쓰지 않습니다.

## 시각과 계약

[로컬 catalog 초안 스키마](../api/seoul-place-catalog-draft.schema.json), [공유 가상 fixture](../server/src/test/resources/places/seoul-catalog-fictional.json). 제품 v1/미리보기0.2.0-draft 공개 필드는 바꾸지 않았습니다. REC-08 내부 추천 초안의 provider에 SEOUL_OPEN_DATA만 추가했습니다. #16의 공개 provider/출처시각/라이선스 표시는 앱과 채택 전입니다.

| 항목 | 의미 |
| --- | --- |
| sourceKind/datasetId/catalogUrl/licenseUrl/attribution | 전체 필드가 공공데이터 원천이며 다른 사업자 필드 자동 혼합 없음 |
| catalogPlaceId/sourceRecordId | 자체 안정 ID/원천 관리번호. 후보 ordinal·Kakao ID와 호환하지 않음 |
| acquiredAt | 입력 파일을 실제 취득한 시각. 로컬 catalog 생성/검색 시각으로 갱신하지 않음 |
| sourceSnapshotAt | 확인된 upstream snapshot 시각, 모르면 null. 운영자가 수신 증거로 확인한 값을 입력해야 하며 필수 인수 증거는 별도 |
| rowModifiedRaw/rowUpdatedRaw/rowTimeZone | 원천 수정·갱신 문자열 그대로, 시간대 UNKNOWN. 오래된 개별 행이 곧 오래된 전체 snapshot이라는 가정 없음 |
| query fetchedAt | 로컬 검색 실행 시각. 원천 관측/수정 시각 아님. 공개 API 연결 시 catalog provenance를 함께 표시해야 함 |
| contactStatus/administrativeStatus | 번호 UNVERIFIED/행정 NORMAL_REPORTED. 발신 동의·실시간 운영/예약 사실 아님 |

snapshot 시각이 UNKNOWN/미래/취득 이후이거나 조회 시점7일 이상이면 검색을 INVALID_RESPONSE로 거부합니다. 7일은 **개발용 선별 기준**이며 사업자의 정확성 보장/번호 검증/보존 기간이 아닙니다. 시범 중심 밖/반경1000m 초과는 INVALID_QUERY, 실제0건은 Success(empty), 오류를 빈 결과나 MOCK 후보로 채우지 않습니다. 파일 재로딩·검색은 sourceSnapshotAt을 최신화하지 않습니다.

## 실행/검증

품질 도구와 같은 requirements-quality를 설치한 격리 Python에서 실행합니다. 실제 입력이 없으면 가상 테스트만 실행합니다.

```bash
python -m unittest discover -s scripts/tests -p 'test_seoul_place*.py'
./gradlew -PincludeAndroid=false format verify
python scripts/check-public-catalog.py
python scripts/smoke-test.py --check-contract
```

별도로 확보한 XML을 변환하는 예시(도구 자체 네트워크0회):

```bash
python scripts/seoul-place-catalog.py \
  --input work/allowed-source.xml \
  --output work/catalog-private/seoul-v1.private.json \
  --acquired-at 2026-11-10T00:00:00Z
```

시각은 실제 수신 증거로 바꿉니다. 확인된 snapshot 시각이 있다면 `--source-snapshot-at`을 추가합니다. 생략하면 null을 보존하므로 검색 adapter가 실제 후보로 채택하지 않습니다. 이 명령 예시를 이번에 실제 매장 데이터에 실행한 것은 아닙니다.

## DB·전화·화면 담당 인계 계약

| 담당 | 다음 적용안 / 인수 조건 |
| --- | --- |
| #4 DB | catalog_snapshot(원천/라이선스/취득/snapshot/버전), catalog_place(자체ID/원천ID/소속snapshot/상호/주소/번호/좌표/원시시각/번호검증)를 분리. snapshot 교체는 검증 후 트랜잭션으로 원자적 전환하고 실패 시 이전 snapshot 보존. 예약 당시 참조는 독립 출처 snapshot에 결합. 실제 migration·보관/삭제 기간은 합의 후 별도 구현 |
| #3 전화 | catalogPlaceId/선택 snapshot으로 매장 일치·번호 검증/시각·processor/목적/필드/보관·삭제 조건을 확인. 이 단계의 dispatchBlockers는 항상 비어 있지 않으며 실제 전화 대상/payload를 생성하지 않음. 사용자 실행 승인·번호 allowlist·비용/횟수 상한·UNKNOWN 대조는 별도 |
| #16/#26 화면 | 공개 필드 합의 후 최대3·공공데이터 출처/갱신/UNKNOWN·전화 미검증 표시. 목적지/조건 변경·STOPPED·기한 경과에 결과 제거. 기존 MOCK 인수를 실데이터 인수로 승격하지 않음 |
| #11 추천 | 공식 운영 공급/팀 계정·snapshot 증거·번호 누락 보완·필드별 저장/전달·팀 채택. 직접 제공 매장은 선택적 보완 경로이며 모든 매장 제휴를 일률적 필수로 만들지 않음 |

## #16 최신 답변 대조

[화면 담당 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6757222237)과 [기기 기록](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/5dc9cb1/docs/UX16_DOCKER_ACCEPTANCE.md)을 읽었습니다. 서버d415ef7·앱cf35ead/APK bdd4f931…·Docker arm64 조합은 앞서 전달한 commit과 일치하며 앱 코드의 main 통합 후 diff 없음 기록도 확인했습니다. 원본 PNG/기기를 새로 검증한 것은 아닙니다.

- 직접4fixture·최대3/UNKNOWN·갱신·조건/목적지 변경·실제 STOPPED 결과 제거·force-stop 인증 복구는 **앱 측 실행 보고**로 수용해 서버/core 자동 검증과 대조합니다. 프록시 late/503/응답 유실/3초 만료는 주입임을 유지합니다.
- 기본 한식1개·인원2/1은 기존 두 취향/20/10·21/11 경계의 기기 증거가 아닙니다. 서버/core 검증과 합쳐 전체 기기 통과로 표시하지 않습니다.
- 컨테이너 재시작 후 메모리 세션 없음·cancel503 후 미정리 세션/동시 생성429는 이전 후보 재표시 없이 한도 보호를 유지한 경계입니다. 새 인증/자동 재검색으로 우회하지 않은 점은 계약과 일치합니다. 자동 정리/수명 보강은 #17/#22와 연결하되 해당 PR의 기기 검증으로 대체하지 않습니다.
- 과거 정리 실패 안내가 이후 정상/빈 상태에도 남는 것은 앱에 지속된 경고입니다. 서버 상태가 새로 실패한 것으로 오해되지 않는 문구/해제 기준은 #26 담당 검토 항목으로 남깁니다. 앱을 임의 수정하지 않습니다.
- 실제5분 만료·moving/장애·#1 ETA·#40 운영자 예약 시연·웹 공개 필드·실데이터/전화 인수는 미완료. #16/#26 OPEN·#19/#46 Draft를 유지하고 이번에 PR 병합/공동 완료 체크/새 공개 댓글 전송은 하지 않았습니다.

## 이번 검증 결과

- 가상 Python49개(기존 품질40+catalog9)·서버 Java321개(기존313+catalog8), 실패/오류/skip0. format/verify 및 schema/공유 fixture·REC-08 가상2계획 검사를 통과했습니다.
- 패키징 JAR의 실제 서버 토큰/소유권·MOCK/UNKNOWN·OpenAPI·Flyway/H2 재시작·LIVE 시작 차단을 확인했습니다. 로컬 로그는 작업 루트의 Git 제외 work/public-catalog-verify.log 및 work/public-catalog-smoke.log입니다.
- 수정/신규18파일·로컬 문서 링크76개·JSON/diff 검사. Python 테스트는 네트워크0회이며 실매장 원문/연락처 fixture를 추가하지 않았습니다. Android/기기 검증은 이번 로컬 실행에 포함하지 않았고 원격 CI는 PR에서 별도로 확인합니다.
