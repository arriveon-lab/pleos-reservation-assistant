# REC-09 성동구 공공데이터 명세·좌표·품질 검증

2026-11-09 KST. 담당 `tiemo0708`, [#32](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/32), 관련 [#11](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/11). **독립 품질 검사 도구와 공개 샘플 검증이며 제품 공급자 채택/예약 연결이 아닙니다.** #11의 [MVP 데이터 흐름](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/e3e7de0/docs/REC02_MVP_DATA_FLOW.md)에 정한 검증 순서를 진행했습니다.

## 확인한 공식 명세

카탈로그의 Open API 탭이 불러오는 [공식 명세](https://data.seoul.go.kr/dataList/openApiView.do?infId=OA-18655&srvType=A)를 읽었습니다. infId만 있는 URL은404였으며 실제 탭에 명시된 srvType=A 경로에서 명세를 확인했습니다. 브라우저/검색 열람 도구의 실패를 데이터 서비스 미지원으로 해석하지 않았습니다.

- 데이터셋 OA-18655, 서비스 LOCALDATA_072404_SD, 출력 필드39개. [기계 판독용 명세 요약](quality/rec09-source-contract.json)에 필드명/설명을 보존했습니다.
- 관리번호 MGTNO, 상호 BPLCNM, 전화번호 SITETEL, 도로명주소 RDNWHLADDR, 행정 영업 상태 TRDSTATEGBN/TRDSTATENM, 최종수정 LASTMODTS, 행 데이터갱신 UPDATEDT, 좌표 X/Y가 있습니다. 필드 존재와 실제 값의 품질은 별도입니다.
- 공개 sample 키의 시작/종료 범위는1–5, 일반 요청은 한 번에 최대1000건입니다. [공식 이용 안내](https://data.seoul.go.kr/together/guide/useGuide.do)는 일반 이용용 인증키 발급 절차와 샘플 코드를 제공합니다. 개인 키 발급/로그인/약관 동의는 하지 않았습니다.
- [카탈로그](https://data.seoul.go.kr/dataList/OA-18655/A/1/datasetView.do)의 좌표는 EPSG:5174이며 WGS84가 아닙니다. 매일 갱신/3일 전 자료 안내는 실시간 매장 운영을 보장하지 않습니다.
- [공공누리 제1유형](https://www.kogl.or.kr/info/licenseType1.do)의 출처표시 조건을 유지합니다. 공공기관의 후원/제휴로 표시하지 않고 전화/개인정보/처리업체 계약은 별도로 검토합니다.

## 독립 검사 도구

[scripts/seoul-place-quality.py](../scripts/seoul-place-quality.py)는 HTTP 기능 없이 기본 가상 입력 또는 로컬 XML만 처리합니다. 서버 bean·PlaceSearchAdapter·공개 API·DB·예약 워크플로에 등록하지 않습니다. 실제 영업·전화 연결 가능·예약 가능 여부를 판단하거나 자동 발신하지 않습니다.

| 검사 | 동작과 한계 |
| --- | --- |
| 입력/스키마 | UTF-8 XML, 2MiB/1000행 한도, DTD/entity·중복 태그·중첩 필드 거부. 실패·서비스 불일치·정상0건 구분 |
| 식별/누락 | 검사 필드 기준 ID 중복/충돌, 상호·주소·좌표 누락 집계. 행 값/ID/해시를 출력하지 않음 |
| 전화 | 결측·형식 미인식·가상 placeholder·이동전화 검토·기본 형식 통과 구분. 정규화값·일부 번호도 출력하지 않으며 형식 통과는 유효 발신 대상 증명이 아님 |
| 좌표 | pyproj3.7.2/PROJ, always_xy=True, ballpark 금지·only_best=True·네트워크 OFF. 변환 오류/좌표계 적용 범위 밖 거부. 실제 좌표 출력 없음 |
| 시각 | 결측·파싱 실패·미래 시각 구분. naive 행 시각은 KST로 해석하는 **검증 가정**을 명시. upstream timezone은 아직 확인 전 |
| 최신성 | 검증된 source snapshot 시각을 명시한 경우만 진단. 행 수정일이나 HTTP 조회 시각으로 snapshot을 만들지 않음. 없으면 UNKNOWN |
| 출력 권한 | adoptionReady/bookable/inquiryDispatchAllowed는 모두 false. PROFILED는 집계 성공이며 공급자 채택/사용 허가/전화 승인 아님 |

snapshot 진단의7일은 실험 기준이며 서비스 SLA/권한/실시간 유효기간이 아닙니다. 원본 batch/version 의미를 확인하기 전 제품 통과 기준으로 확정하지 않습니다. 빈 입력의 비율은0이 아닌 null이며 스키마 관측도 UNKNOWN_EMPTY입니다. 전화/좌표/중복 집계의 분모는 입력 batch의 행수로 표시합니다. 국가/시범 반경 내 여부를 다른 사업자 POI와 조인하지 않습니다.

검사 기록은 데이터 품질 검토 스킬의 누락·고유성·유효성·시각 구분을 적용했습니다. 별도 노트북 대신 재현 스크립트·공식 명세·가상 테스트와 집계JSON을 보존합니다.

## 실제 공개 샘플 확인

2026-11-09 15:41 KST, 공식 문서의 공개 sample XML 1–5 경로를 **1회/최대5건/비용0원** 읽었습니다. 개인 키·결제 계정을 쓰지 않았고 redirect·자동 재시도·추가 페이지는 없었습니다. 원문은 메모리에서만 처리하고 저장하지 않았습니다. 공개 문서의 HTTP:8088 샘플 경로이며 개인 자격 증명을 보내지 않았습니다.

[집계 증거](quality/rec09-public-sample-2026-11-09.json)는 연락처·상호·주소·좌표·ID 없이 다음을 보존합니다. 이는 첫5건의 편의 표본이며 성수·서울숲/현재 운영 매장이나 전체 데이터 품질을 대표하지 않습니다.

| 항목 | 이 표본에서 관측 | 활용상 의미 |
| --- | --- | --- |
| 전화 필드 | 5/5 비어 있지 않고 정규화 후 기본 형식 통과 | 누락0/5. 번호 소유·최신성·응답·발신 동의는 미검증 |
| 좌표 | 3/5 변환 가능, 2/5 누락, 입력 있는3건 변환 오류0 | 결측40%. 값 있는 항목만 변환 가능하며 측량 정확도 검증은 아님 |
| 도로명주소 | 3/5 누락 | 결측60%. 원래 주소 유형/행정 이력 특성도 추가 확인 필요 |
| 행정 상태 | 영업/정상1/5, 폐업4/5 | 인허가 이력을 현행 매장 목록으로 그대로 사용하면 위험. 정상적인 이력 데이터일 수 있음 |
| ID | 결측0/5, 검사 필드 중복/충돌0/5 | 전체 모집단의 유일성 보장이 아님 |
| snapshot 최신성 | UNKNOWN | LASTMODTS/UPDATEDT 파싱 성공을 batch 최신성으로 바꾸지 않음 |

시범 중심1030m 내 변환점은2/5였지만 영업 상태·전화와 교차한 실제 추천 후보 수는 이 집계에서 판단하지 않습니다. 반경1000m에30m 진단 허용오차를 더한 값이며 도로거리/ETA가 아닙니다. 첫5건은 역사적 폐업 행을 포함하므로 일반 결측률·영업점 분포 추정이나 실제 전화 대상 선정에 쓰지 않습니다.

## 검증과 재현

품질 도구는 Python3.11+를 사용합니다(로컬3.13, CI3.12). 기존 Java/Android 런타임 의존성은 바꾸지 않았고 추가 Python 의존성은 품질 검사에 한정합니다.

```bash
python3 -m venv work/quality-venv
work/quality-venv/bin/python -m pip install -r scripts/requirements-quality.txt
work/quality-venv/bin/python -m unittest discover -s scripts/tests -p 'test_seoul_place_quality.py'
# 외부 호출0회인 기본 가상 프로파일
work/quality-venv/bin/python scripts/seoul-place-quality.py
# 이미 별도 확보한 허용된 로컬 XML만 점검. 원문을 공개 Git에 넣지 않음
work/quality-venv/bin/python scripts/seoul-place-quality.py --input work/allowed-source.xml
```

네트워크 없는 가상 테스트15개를 통과했습니다. EPSG 정의의 투영 원점(동200000/북500000→Korean1985 경위도), 파생 가상 시범점, 축 교환·NaN/무한/0·범위 밖, 결측·중복 충돌, 오래된 행과 새 snapshot의 구분, 미래/경계/시각 누락,0건/실패·XML 공격/크기·출력 비노출을 검사했습니다. 가상 시범점은 PROJ에서 파생한 값이며 독립 측량 기준점이나 매장 좌표 정확도 인증이 아닙니다. PROJ의 operationAccuracyMeters는 연산 정의의 기대 정확도이며 실제 관측한 위치 정확도가 아닙니다.

CI에는 품질 의존성 설치·가상 테스트15개·MOCK 실행만 추가했습니다. CI/테스트는 공개 샘플을 재조회하지 않습니다. 실제5건의 행을 fixture로 넣지 않았고 실제 표본 통계와 가상 테스트를 구분합니다.

## 남은 제품 채택 조건

- 일반 인증키/접근 환경과 원본 batch/version·시각 의미 확인. 실제 파일/추가 조회가 필요하면 목적·상한을 정한 뒤 최소 범위로 수행.
- 현행 영업 상태 필터·해당 지역·필요한 연락처/좌표를 대상으로 대표성 있는 추가 검증. 첫5건으로 결측 임계나 공급자 품질 확정 금지.
- #11의 출처표시·저장/삭제·제공 조건, #4의 데이터 모델/실행 상한, #3의 허용 대상/발신 승인·비용·AI 고지, #26의 출처/UNKNOWN 표시 인수.
- 검증된 후보 기반을 실제 adapter에 연결할 별도 구현 범위 확정. 현재 서버는 MOCK이고 source=PLEOS 거부·LIVE 차단을 유지.

#32는 독립 검증 도구·명세·제한된 공개 표본 증거까지의 범위입니다. #11/#16–18과 다른 담당 이슈를 닫거나 PR을 병합하지 않습니다. 실제 전화·Kakao·AI·유료 API·계정/키/IP 설정 변경은0회입니다.
