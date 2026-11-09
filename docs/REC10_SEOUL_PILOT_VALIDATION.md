# REC-10 시범 지역 교차 검사와 인증키 준비

2026-11-09 KST, 담당 tiemo0708, [#34](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/34) / [PR #35](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/35). 공식 상태 코드와 키 없는 HTTPS 표 경로를 확인하고 **행정상 영업 상태01의 첫1000행**을 교차 검사했습니다. 1km·전화형식·상호/도로명주소까지 만족한 것은115행입니다. 입력 범위의 인허가 기록이며 서로 다른 현재 영업 매장115곳·예약 가능115곳을 뜻하지 않습니다.

선행 REC-09 PR #33은 사용자 진행 지시로 `abd9aa4`에 squash merge돼 #32가 종료됐습니다. 이 main에서 `feat/34-seoul-pilot-validation`을 만들었으며 다른 Draft PR/팀원 코드는 병합하지 않았습니다.

## 공식 상태 코드 근거

[공공데이터포털의 제공 표준20차 원문](https://www.data.go.kr/bbs/rcr/selectRecsroom.do?originId=PDS_0000000001252)을 확보했습니다. 게시2025-11-17·수정2026-03-03, 첨부 전문2,664,887bytes의 **127) 일반음식점**에서 확인했습니다. [참조 메타데이터](quality/rec10-status-reference.json)에 URL·문서 SHA256·절·코드/필드 의미만 기록하며 원문 전체/예시 매장 정보는 Git에 넣지 않습니다. 문서 해시는 매장/연락처 해시가 아닙니다.

| 필드 | 확인한 값/의미 |
| --- | --- |
| 영업상태구분코드 | 01 영업/정상,02 휴업,03 폐업,04 취소/말소/만료/정지/중지 |
| 상세영업상태코드 | 01 영업,02 폐업 |
| 폐업일자 | 폐업한 일자, 선택 항목 |
| 최종수정/갱신 시점 | 행 수정/갱신 시각, 날짜·시간 형식. 시간대와 데이터셋 snapshot은 확인되지 않음 |

[OA-18655 명세](https://data.seoul.go.kr/dataList/openApiView.do?infId=OA-18655&srvType=A)의 대응 필드명을 사용합니다. 기존 실험 튜플은 표준의 정상 코드와 일치합니다. 이제 코드/명칭 충돌·행정 상태와 상세 상태의 상충·미인식 코드·폐업일 값 존재를 구분합니다. 실제1000행은 정상 코드/명칭 조합을 모두 만족했습니다. 행정상 정상은 지금 영업 중·빈자리·예약 가능 확인이 아닙니다.

**좌표 근거 차이:** 일반 제공 표준은 EPSG:2097을 표기하지만 [해당 데이터셋 카탈로그](https://data.seoul.go.kr/dataList/OA-18655/A/1/datasetView.do)와 원천 카탈로그는 EPSG:5174를 안내합니다. 데이터셋별 명시를 우선해5174를 유지하며 근거 차이를 출력합니다. 범용 표준으로 일괄 변환하지 않았고 실제 매장 좌표의 측량 정확도를 검증했다고 주장하지 않습니다.

## 키와 취득 경로

사용자 명시 동의 후 개인 개발용 일반 키1개를 발급해 정상 상태를 확인했습니다. Git 제외 로컬 디렉터리0700/파일0600 보관, 기존 키 변경 없음입니다. [약관](https://data.seoul.go.kr/etc/accessTerms.do) 제7조의 본인 사용·타인 제공/공유 금지를 유지하며 팀/CI에 공유하지 않습니다. 이메일·키/일부 문자/해시·키 화면은 Git에 넣지 않습니다.

키 있는 OpenAPI의 HTTPS는 미확인입니다. 앞선 키 없는443 연결은 timeout이었고, [다른 서비스의 서울시 답변](https://data.seoul.go.kr/together/notice/inquireView.do?bbsCd=10003&ditcCd=QNA01&seq=1d2186fcde0d53cbbb2814bec743624a)은 HTTPS 미제공을 안내합니다. 이를 모든 서비스의 결론으로 확대하거나 개인 키를 HTTP로 보내지 않았습니다. 공공데이터포털이 연결한 원천 파일 안내 경로도 접근 거절을 확인했습니다.

대신 [공식 Sheet 메타데이터](https://data.seoul.go.kr/dataList/sheetView.do?infId=OA-18655&srvType=S)의 표 조회 기능이 사용하는 `https://data.seoul.go.kr/dataList/dataView.do`를 확인했습니다. 원래 성동구 조건을 유지하고, 화면에 있는 영업상태코드 필터01·page1·최대1000행으로 읽었습니다. **공식 웹 표의 제한된 점검 경로**이며 안정적인 제품 API 계약/운영 수집 허가로 채택하지 않습니다. 키 발급/키 인증 API와 별도이며 이 경로에는 개인 키를 보내지 않았습니다.

## 실제 실행과 범위

첫 요청은 HTTP200·882,435bytes였으나 레거시 객체 응답을 엄격한 JSON으로 읽어 실패했습니다. 원문을 저장/재조회하거나0건으로 바꾸지 않았습니다. 그 전에 있었던 로컬 Python import 오류는 네트워크 전 중단으로 실제 호출0회입니다.

사용자가 최대1회 추가 조회를 명시 승인했습니다. 추가 요청은 HTTP200·882,166bytes·1000행이며 원문을 프로세스 메모리에만 유지했습니다. 파서 수정 후 같은 메모리를 재분석하고 프로세스 종료로 폐기했습니다. 추가 네트워크 재시도/다음 페이지는 없습니다. 두 요청 모두 HTTPS·키 없음·2MiB 상한·0원입니다. **이번 실제 데이터 요청은 총2회(첫1+승인 추가1)**, 전화/Kakao/AI/유료 호출0회입니다. 기존 공개 sample 상한을 재사용하지 않았습니다.

응답은 text/html;charset=UTF-8의 객체 형태로 따옴표 없는 식별자 키·끝 쉼표를 포함합니다. 파서는 문자열 내부를 보존하며 이 두 문법만 정규화한 뒤 JSON으로 검사합니다. eval/JavaScript 실행·주석·함수 값·추가 실행문·단일 인용 문자열은 허용하지 않습니다. 중복 키·인코딩·행/크기·필드 한도·메타데이터/실패 경계를 검사합니다. null 폐업일은 빈 문자열로 바꾸지 않고 미확인으로 유지합니다.

[실제 집계](quality/rec10-public-sheet-2026-11-09.json)는 행·상호·연락처·주소·좌표·ID/해시 없이 개수만 보존합니다. 응답은 필터된 총3959행·pageNo1·pageCount40을 보고했지만 pageSize는 없었습니다. 요청 상한/pageCount로 pageSize를 추정하지 않고 null로 남깁니다. 수신1000행은 전체/대표 표본이 아니며 나머지 페이지를 스캔하지 않았습니다. request 시작·로컬 분석 시각과 원본 snapshot 시각도 구분합니다.

| 검사 | 실제 수신1000행에서 관측 |
| --- | --- |
| 관리번호·상호/정상 상태 | 결측/ID 중복·충돌0, 정상 코드/명칭1000 |
| 좌표 | 누락82, 유효 변환918, 입력 있는 행 변환 오류0 |
| 전화 | 누락719(71.9%), 기본 형식273, placeholder1, 미인식7 |
| 도로명주소 | 누락3 |
| 1km 교차 단계 | 유일ID1000 → 정상1000 → 유효좌표918 → 1000m 이내433 → 전화형식116 → 상호/도로명주소115 |
| 반경 경계 | 1000m 초과~1030m8행 제외, 1030m 밖477행 제외 |
| 원본 batch/version·시간대 | 미확인, snapshot 최신성UNKNOWN 유지 |

전화 형식은 번호 소유/최신성/응답/동의의 증명이 아닙니다. 누락률을 전체3959행의 품질로 일반화하지 않습니다. 반경은 EPSG/시범 중심 가정 아래 구면 직선거리이며 도로거리/ETA가 아닙니다.

## 도구와 가상 검증

[독립 도구](../scripts/seoul-place-quality.py)는 네트워크/키 로딩 없이 기본 MOCK 또는 허용된 로컬 입력만 처리합니다. `--pilot-screening`은 같은 행의 유일 MGTNO·정상 상태/빈 폐업일·유효 좌표·1000m·전화형식·상호/도로명주소를 교차 집계합니다. 동일 중복도 그룹 전체를 제외하고 최초 탈락/누적 합계·분모를 함께 출력합니다. 정상0건/실패·빈 batch UNKNOWN_EMPTY를 구분합니다.

`screenedRecords`는 입력 인허가 기록의 진단 통과 수입니다. `productionCandidateCount=null`, `readyForRecommendation=false`, `adoptionReady/bookable/inquiryDispatchAllowed=false`를 유지합니다. 서버 bean·adapter·공개 API·DB·예약에 연결하지 않았습니다.

```bash
work/contract-venv/bin/python -m unittest discover -s scripts/tests -p 'test_seoul_place_quality.py'
work/contract-venv/bin/python scripts/seoul-place-quality.py --pilot-screening
# 별도 허용받아 이미 확보한 로컬 입력만 처리. 도구 자체가 내려받지 않음
work/contract-venv/bin/python scripts/seoul-place-quality.py --input work/allowed-sheet.json --input-format sheet-json --pilot-screening
```

의존성 설치는 [REC-09](REC09_SEOUL_DATA_VALIDATION.md#검증과-재현)를 따릅니다. 가상40개(기존29+신규11)를 통과했습니다. 상태 사전/충돌·좌표 근거 차이·레거시 파싱·결측/중복/메타데이터·비노출을 검사하며 실제 행은 fixture가 아닙니다. MOCK 가상3행의 통과0행을 실제115행과 혼동하지 않습니다. 새 의존성 없음, Gradle format/verify 성공(Java 테스트 UP-TO-DATE)입니다.

앞선 CI의 REC-08 계약 파일 캐시 누락은 test 산출물 선언으로 수정했습니다. 당시 Java268개 재실행·파일 제거 후 FROM-CACHE 복원·계약2개를 통과했습니다. 이번 Python/문서 변경을 Java 재실행/기기 검증으로 세지 않습니다. 원격 CI는 최신 PR head가 기준입니다.

## 종료 범위와 제품 후속

#34 인수는 공식 상태 근거·개인 키 준비·안전한 실제 접근/제한 표본·오프라인 교차 도구/검증/한계 기록입니다. batch/version/TZ는 확인된 값이 없어 UNKNOWN을 보존하는 완료 조건을 따릅니다. 키 있는 OpenAPI 인증은 이번 경로에서 불필요해 실행하지 않았으며, 운영 경로/팀 계정·원본 최신성/CRS 차이·추가 품질/저장·전달 판단은 **기존 #11의 공급자 채택 인수**에 남깁니다. 원래 키 인증 검토를 실제 인증 성공으로 바꾸지 않습니다.

#3 발신/번호/비용/AI 고지, #4 운영 모델, #26 출처/UNKNOWN 표시와 기기 인수도 별도입니다. #35 병합 전 #34는 OPEN이며 다른 미완료 이슈를 닫지 않습니다. 실제 데이터가 포함된 제품의 LIVE 경계는 계속 차단합니다.
