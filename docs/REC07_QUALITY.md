# REC-07: 장소 품질 검증 준비와 실행 경계

기준: 2026-11-08 KST. 담당 이슈 [#18](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/18). 이 문서는 도구·모의 검증 결과이며 추가 실제 검색 성공 보고서가 아닙니다. 현재 main의 최초 실제 Kakao 검색 증거는 앞서 승인한 1회뿐입니다.

## 준비한 범위

독립 개발용 `PlaceQualityProbe`와 `:server:placeQuality`를 추가했습니다. Spring·제품 API·DB·전화·AI에 연결하지 않습니다. 기본은 **dry-run**이며 키나 네트워크 없이 계획만 검사합니다. `mock`은 가상 adapter를 사용합니다. `live`는 별도 명시 실행·새 사용자 승인·무료 설정 확인이 모두 필요합니다.

[계획 원본](quality/rec07-plan.json)은 성수·서울숲 주변의 공개 테스트 좌표입니다. 실제 사람의 이동 경로나 특정 장소의 정확한 좌표를 뜻하지 않습니다. FD6, page 1, 거리순, 입력당 최대 3곳이며 호출 상한 6회·비용 상한 0원입니다.

| 입력 | 위도 | 경도 | 반경(m) | 목적 |
| --- | --- | --- | --- | --- |
| center | 37.544 | 127.055 | 1000 | 중심 표본 |
| west | 37.544 | 127.047 | 1000 | 서쪽 입력 변화 |
| east | 37.544 | 127.063 | 1000 | 동쪽 입력 변화 |
| north | 37.551 | 127.055 | 1000 | 북쪽 입력 변화 |
| south | 37.537 | 127.055 | 1000 | 남쪽 입력 변화 |
| narrow | 37.544 | 127.055 | 100 | 좁은 반경의 정상 0건/소수 결과 여부 관찰 |

입력당 한 번만 호출하며 다음 페이지·반경 확대·재시도·실패 후 다른 공급자로 전환하지 않습니다. 첫 실패에서 전체 실행을 중단합니다. 좁은 반경도 실제 0건을 보장하지 않으며, 실제 0건이 나오지 않으면 미관측으로 남깁니다.

## 안전하게 실행할 수 있는 명령

저장소 루트에서:

```bash
# 외부 호출 0회, 키 불필요
./gradlew -PincludeAndroid=false :server:placeQuality

# 가상 adapter 6회, 외부 호출 0회
./gradlew -PincludeAndroid=false :server:placeQuality -PplaceQualityMode=mock

./gradlew -PincludeAndroid=false format verify
```

실행 결과는 `QUALITY_REPORT` 한 줄의 모드·실행 시각·호출 시도 수·입력별 집계입니다. 장소명·ID·좌표·전화번호·원 응답은 출력하지 않습니다. 중복 ID 검사는 각 응답 안에서만 수행하고 원문은 다음 입력 결과에 누적하지 않습니다. `adapterCalls`는 애플리케이션의 호출 시도 수이며 공급자 집계나 실제 전송 수의 증명이 아닙니다. LIVE 실행 후 콘솔 집계와 별도로 대조해야 합니다.

집계는 반환/거절 수, 응답 내 중복, 반경 밖 결과, 음식 분류·주소·전화 누락 수입니다. 반경 검사는 Haversine 직선거리(지구 반경 6,371,000m)에 30m 허용 오차를 둔 진단입니다. 도로 이동거리나 차량 ETA가 아닙니다. `recordedAt`은 검사 실행 시각이며 식당 정보의 최신성·영업·빈자리·예약 가능성을 뜻하지 않습니다. `SUCCESS`도 adapter 성공만 뜻하며 데이터 품질 합격이 아닙니다. 입력당 최대 3곳이므로 지역 전체 포함률·검색 재현율을 주장할 수 없습니다.

## 실제 실행 전 필요한 확인

기존 1회 승인은 이번 6회 승인으로 확장하지 않습니다. 사용자에게 공개한 동일 계획의 최대 6회·비용 0원 범위에 명시적으로 동의받고, 실행 직전 ArriveOn 앱의 지도 활성화·해당 앱 무료 쿼터·남은 무료 수량 6회 이상·유료 API 꺼짐을 콘솔에서 확인합니다. 로그인/확인 불가·잔량 부족·승인 미도착이면 실행하지 않습니다. 키·IP·비즈월렛 설정은 변경하지 않습니다.

LIVE task는 다음 경로 인자를 모두 요구합니다. 이 명령은 **승인과 확인 후에만** 사용합니다.

```bash
./gradlew -PincludeAndroid=false :server:placeQuality \
  -PplaceQualityMode=live \
  -PplaceQualityApproval=work/quality-approval.json \
  -PplaceQualityKeyFile=work/secrets/kakao-rest-api-key
```

승인 파일은 Git 제외 디렉터리에 owner-only 600으로 직접 생성하고, 키 파일도 기존 보안 읽기 규칙을 따릅니다. 아래는 실행 불가능한 기본 거부 예시입니다. 사용자 응답과 콘솔 확인 없이 true나 잔량을 채우지 않습니다.

```json
{
  "approvalId": "00000000-0000-0000-0000-000000000000",
  "planSha256": "REPLACE_AFTER_APPROVAL_WITH_EXACT_PLAN_SHA256",
  "userApproved": false,
  "approvedMaxRequests": 6,
  "maxCostKRW": 0,
  "mapEnabled": false,
  "paidApiEnabled": false,
  "freeQuotaRemaining": 0,
  "attestedAt": "1970-01-01T00:00:00Z"
}
```

계획의 정확한 바이트 SHA-256, UUID, 엄격한 boolean/정수 타입, 알 수 없는 필드와 중복 JSON 키, 15분 이내의 확인 시각을 검사합니다. 계획 파일은 일반 파일만 허용하고 최대 16KiB입니다. 첫 호출 전 승인 UUID별 `.used` 파일을 원자적으로 생성하므로 동일 승인을 병렬/반복 실행할 수 없습니다. 중도 실패도 승인을 소비하며 자동 재실행하지 않습니다. JVM transport의 연결 재전송·redirect도 제한합니다. 이 파일은 신뢰된 로컬 개발 도구의 보호 장치이며 사용자 동의를 인증하는 시스템이나 다중 서버 실행 원장이 아닙니다. 파일 삭제/복제로 운영 비용 상한을 보장하지 않습니다. 제품의 재시작/다중 서버 실행 보호는 #17 및 #4 합의 후 별도 구현합니다.

장소 데이터는 처리 중 메모리에서만 사용하며 DB·Git·fixture·결과 캡처에 보관하지 않습니다. 집계도 장소 목록을 재구성하거나 예약/전화 공급자에게 데이터를 제공하는 허가로 해석하지 않습니다. 데이터 사용 범위는 [#11 정책 PR](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/23)의 목적별 표를 함께 검토합니다.

## 실제 IP 허용/거절 검증 계획

#4와 테스트 시간·배포 egress IP·기존 키 사용자·별도 테스트 키 사용 가능 여부·변경/복구 담당·추가 호출 상한을 먼저 합의합니다. 허용 IP에서의 성공과 통제된 다른 egress에서의 거절을 구분하고 공급자 오류와 쿼터 집계를 확인해야 합니다. 현재 팀 키를 예고 없이 잠그거나 재발급하지 않습니다. 실제 IP/키 설정 변경과 이 테스트의 추가 호출은 이번 6회 제안에도 포함하지 않습니다. 배포 네트워크·일정·권한이 정해지기 전에는 미검증입니다.

## 실행한 검증과 남은 인수

- 이 브랜치의 `format verify`: Java 244개, 실패/오류/skip 0(main 200 + 품질 도구 44). #19의 244개와는 별도 구성입니다.
- 기본 task: `DRY_RUN`, 계획 6개, adapter 0회, `NOT_EXECUTED`.
- 모의 task: `MOCK`, adapter 6회, 가상 결과 입력당 3개. 가상 fixture의 주소/전화 누락 3개도 집계했으며 실제 식당 품질 결론이 아닙니다.
- JAR MOCK HTTP·OpenAPI·H2 재시작·LIVE 시작 차단 통과. 공개 API·Model·migration·의존성 변경 없음.
- #19+#22+#24 담당 코드의 별도 임시 조합에서 Java 299개·Python 7개·가상 HTTP 96건·JAR 계약·dry-run/MOCK도 통과했습니다. #21은 제외했고 main/원격 통합은 하지 않았습니다. [담당 작업 정리](REC_WORK_SUMMARY.md)를 참고합니다.
- 추가 실제 검색·공급자 쿼터 대조·실제 IP 제한 검증은 실행하지 않았습니다. 무료 상태의 이전 확인을 현재 확인으로 바꾸어 쓰지 않습니다.
- #5에는 모드별 표시, 0건/누락/실패, 좌표 직선거리와 ETA 구분, 원문 저장 금지, 표본의 한계를 인수합니다. 실제 지역 품질 결론은 승인 후 실험 결과로 보완해야 합니다. #18은 OPEN이며 PR도 Draft로 유지합니다.

공식 API의 FD6·좌표·반경·거리순 입력은 [Kakao REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api), 무료 대상·쿼터 확인은 [공통 안내](https://developers.kakao.com/docs/ko/kakaomap/common)를 참고했습니다. 문서 확인과 계정의 현재 설정 확인은 구분합니다.
