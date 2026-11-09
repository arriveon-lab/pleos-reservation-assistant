# REC-10 시범 지역 교차 검사와 인증키 준비

2026-11-09 KST, 담당 tiemo0708, [#34](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/34). [REC-09](REC09_SEOUL_DATA_VALIDATION.md)의 PR #33을 사용자 진행 지시에 따라 `abd9aa4`로 squash merge하고 #32를 종료했습니다. 후속 작업은 이 main에서 만든 `feat/34-seoul-pilot-validation`입니다. 다른 Draft PR이나 팀원 코드는 병합하지 않았습니다.

## 준비된 것과 미검증

- 사용자 명시 동의 후 일반 인증키1개를 발급했고 성공 안내와 새 키 정상 상태를 확인했습니다. 기존 키는 변경하지 않았습니다. 새 키는 Git 제외 로컬 디렉터리0700/파일0600에 보관하며 팀원·CI와 공유하지 않습니다. 발급은 데이터 API 인증 성공과 다릅니다.
- [신청 약관](https://data.seoul.go.kr/etc/accessTerms.do) 제7조의 발급 회원 본인 사용·타인 제공/공유 금지를 확인했습니다. 개인 개발용 키를 팀 공용 서비스의 운영 키로 승인받았다고 해석하지 않습니다. 출처 표시는 데이터셋 공공누리 조건과 서비스 약관을 함께 확인합니다. 관리 이메일·키/일부 문자/해시·키 관리 화면은 공개 문서에 넣지 않습니다.
- [공식 명세](https://data.seoul.go.kr/dataList/openApiView.do?infId=OA-18655&srvType=A)의 추가 선택 요청 인자는 OPNSFTEAMCODE입니다. 영업 상태·반경 검색 인자는 확인되지 않았으므로 수신 범위 안에서만 로컬 교차 검사합니다. 첫1000행도 지역 전체나 대표 표본이 아닙니다.
- 코드/명칭·상세 상태 사전, batch/version·시간대는 확보 전입니다. 필드 존재와 코드 값 의미 검증은 다릅니다. 행 수정일·조회 시각으로 snapshot을 만들지 않습니다.

## 전송 경로 확인

[공식 이용 안내](https://data.seoul.go.kr/together/guide/useGuide.do)는 HTTP:8088 경로에 키를 포함하는 예시를 제공합니다. 개인 키 없이 openapi.seoul.go.kr:443에 TLS 연결을 한 번 시도했지만 TimeoutError였으며 애플리케이션 요청은 보내지 않았습니다. 한 환경의 timeout만으로 모든 HTTPS 경로가 없다고 단정하지 않습니다. 인증서 검증을 끄거나 HTTP로 개인 키를 보내지 않았습니다.

[서울시 공개 답변](https://data.seoul.go.kr/together/notice/inquireView.do?bbsCd=10003&ditcCd=QNA01&seq=1d2186fcde0d53cbbb2814bec743624a)은 citydata_ppltn 문의에 HTTPS를 제공할 수 없다고 답합니다. **다른 서비스에 대한 답변**이며 OA-18655의 최종 확인으로 확대하지 않습니다. 해당 API의 지원 경로 또는 키 없는 공식 파일 공급 경로를 확인한 뒤 추가 실제 조회를 진행합니다. 새 문의 발송·약관 동의·다른 공급자 가입은 이번에 하지 않았습니다.

[상태 근거 JSON](quality/rec10-validation-state.json)에 문서 확인·TLS 관측·미검증을 구분했습니다. 이번 데이터 API 호출0회·키 전송0회입니다. 기존 REC-09 공개 sample1회 상한을 재사용하지 않았습니다.

## 오프라인 교차 검사

기존 [품질 도구](../scripts/seoul-place-quality.py)에 `--pilot-screening`을 추가했습니다. HTTP/키 로딩 기능은 없으며 기본 명령은 기존 프로파일만 반환합니다. 옵션을 켜면 입력 batch의 **같은 행**에서 다음 순서의 누적 수와 최초 탈락 사유를 집계합니다. 실제 행·연락처·좌표·관리번호·해시는 출력하지 않습니다.

1. 비어 있지 않고 batch 안에서 한 번만 나타나는 MGTNO. 동일 중복도 그룹 전체를 제외하고 첫 행을 임의 채택하지 않습니다. 검사 필드·상세 상태·폐업일이 다른 중복 그룹은 별도로 집계합니다.
2. **미검증 실험 가정** `(TRDSTATEGBN, TRDSTATENM, DTLSTATEGBN, DTLSTATENM) = (01, 영업/정상, 01, 영업)` 일치와 명시적으로 빈 DCBYMD. 확정 코드 사전이 아니며 `statusRule.verification=UNVERIFIED_FOR_OA18655`를 항상 내보냅니다. 태그/값 누락·다른 튜플·폐업일 값은 제외하고 미확인/불일치 집계로 보존합니다. `1`, `영업중` 등을 같은 값으로 바꾸지 않습니다.
3. EPSG:5174 유효 좌표를 기존 네트워크OFF PROJ 설정으로 변환. 공개 시범 중심 경도127.055/위도37.544로부터 구면 직선거리1000m 이내. `1000 < 거리 <= 1030m`는 진단 경계 구간으로 따로 집계하며 통과시키지 않습니다. 도로거리·ETA·측량 정확도 보장이 아닙니다.
4. 전화 기본 형식 `SYNTAX_ONLY`. 누락·미인식·placeholder·이동전화 검토는 제외합니다. 형식 통과는 소유자·응답·발신 동의·AI 처리 권한을 보장하지 않습니다.
5. 상호와 도로명주소가 비어 있지 않은지 확인. 주소 종류 대체나 다른 공급자 데이터 조인은 하지 않습니다.

`screenedRecords`는 위 **실험 가정을 만족한 입력 행 수**이며 실제 추천 후보나 물리적으로 서로 다른 매장 수가 아닙니다. `productionCandidateCount=null`, `readyForRecommendation=false`, `adoptionReady/bookable/inquiryDispatchAllowed=false`를 유지합니다. 정상0건도 입력 범위의 집계이며 지역에 매장이 없다는 뜻이 아닙니다. 빈 batch는 UNKNOWN_EMPTY입니다. 분모·중복 그룹·누적 단계·최초 탈락 합계를 함께 표시합니다.

## 재현과 검증

```bash
work/contract-venv/bin/python -m unittest discover -s scripts/tests -p 'test_seoul_place_quality.py'
work/contract-venv/bin/python scripts/seoul-place-quality.py --pilot-screening
```

품질 의존성 설치는 [REC-09 재현 절차](REC09_SEOUL_DATA_VALIDATION.md#검증과-재현)를 따릅니다. 별도 notebook/실제 행 fixture/새 런타임 의존성은 없습니다.

기존15개와 신규14개, 총29개 가상 테스트를 통과했습니다. 동일 행 교집합·주변 집계만 각각 통과하는 반례, 상세 상태/폐업일 결측과 코드 변형, 모든 중복 행 제외·순서 독립성, 유효/불량/반경 경계, 연락처 검토, 기본 정보 누락, 합계/분모와 출력 비노출을 확인합니다. 양성 교집합 테스트는 전화 분류 경계만 가상 값으로 대체하여 발신 가능한 번호를 fixture로 만들지 않습니다. CLI MOCK에는 실제 연락처가 없으므로 최종 통과0건이며 테스트 양성과 구분합니다.

CI에도 가상 옵션 실행을 추가했고 실제 네트워크 조회·키를 요구하지 않습니다. `./gradlew -PincludeAndroid=false format verify`는 처음 Java 테스트를 FROM-CACHE로 복원했습니다. 원격 CI에서 기존 REC-08 계약 파일이 캐시 복원 대상에 없어 검사 실패를 확인했고, server/build.gradle.kts의 test 산출물에 해당 가상 JSON을 선언했습니다. 수정 후 Java268개를 재실행해 실패/오류/skip0을 확인했으며, 생성 파일을 Git 제외 work로 옮긴 뒤 test FROM-CACHE가 파일을 복원하고 계약 검사2개가 통과하는 것까지 검증했습니다. 서버 런타임/Android/API/DB/예약 코드는 변경하지 않았고 Gradle 테스트 산출물 선언만 보강했습니다. 원격 CI 결과는 PR 최신 head에서 확인하며 기기/실데이터 검증으로 세지 않습니다.

## 남은 완료 조건

#34는 OPEN으로 유지합니다. 공식 상태 사전·원본 batch/TZ·안전한 전송 경로와 접근 성공·실제 지역/현행 행 품질은 미검증입니다. 접근이 준비되면 최대1회/1–1000행/2MiB·자동 재시도/redirect/추가 페이지 없음의 최소 범위에서 확인하고, 원문은 메모리 처리·집계만 보존합니다. 상한이 정해졌다는 이유로 호출했다고 기록하지 않습니다.

#11 공급자 채택·저장/삭제/전달, #3 발신/번호/비용/AI 고지, #4 운영 모델, #26 출처/UNKNOWN 표시와 기기 인수는 별도입니다. 이번 실제 음식점 조회·Kakao·전화·AI·유료 호출은0회이며 제품 MOCK/LIVE 차단을 유지합니다.
