# REC-05 화면 연결 인수안

2026-11-09. 추천/장소 담당 `tiemo0708`, 화면 협업 `soyeong03`. [#16](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16) / [Draft PR #19](https://github.com/arriveon-lab/pleos-reservation-assistant/pull/19)와 [#26](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/26)을 연결합니다. **서버 MOCK 계약 제안이며 화면 담당의 합의·Android 연결 완료가 아닙니다.** #19는 main에 미병합입니다.

## 2026-11-10 최신 화면 제안에 대한 서버 확인

실행 방식은 [각자 PC의 로컬 서버와 비동기 검토](REC05_JOINT_ACCEPTANCE.md)를 따릅니다. 원격 공유 서버는 없으며 화면 담당 PC에서 고정 서버 commit을 실행하고 그 PC의 기기/APK로 확인합니다. 양측의 동시 접속은 필수가 아니고, 최종 버전/시험 조건과 결과 검토가 필요합니다.

[#26/#39의 최신 공동 시험 제안](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6741208185)을 확인하고 앱 소스cf35ead와 대조했습니다. **미리보기 인원1–20/0–10**, 기존 예약 연습1–8/0–6의 구분과 조건 배열의 집합 비교를 서버 측에서 확인합니다. [공통 MOCK fixture·실행 명령·인수표](REC05_JOINT_ACCEPTANCE.md)를 제공합니다. 기기 공동 실행과 웹 상세정보 필드 채택은 아직 미완료입니다.

## 2026-11-09 화면 담당 정적 검토 반영 (당시 이력)

[#26 담당의 검토 답변](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/16#issuecomment-6737968008)을 확인했습니다. 당시 초안0.2.0-draft의 현재 필드에 추가 차단 사항은 없다는 정적 검토입니다. 당시 앱1–8/0–6 제한은 후속 미리보기 구현에서1–20/0–10으로 확장됐으며 기존 예약 연습의 범위와 구분합니다. 기기 공동 인수는 위 최신 기준을 따릅니다.

서버 측에서도 다음 두 경계를 확인합니다. 앱 복귀 시 완료 미리보기 결과를 다시 표시/복원하지 않고 비운 상태에서 사용자의 명시적 새 검색을 기다립니다. 현재 추천 후보의 ordinal을 예약 candidateId로 변환하거나 발신 승인으로 바꾸는 API는 없습니다. bookable/inquiryDispatchAllowed=false이면 문의·예약 조작을 제공하지 않습니다. 웹 상세정보 보강의 후속 구현도 이 계약에 임의 필드를 추가하거나 UNKNOWN을 발신 허가로 바꾸지 않습니다.

## 이번 서버 제공 범위

- main의 인증 계약 1.1.0과 추천 평가기를 사용합니다. `/api/v1` 경로·인증 구현·DB는 변경하지 않습니다.
- 별도 [미리보기 OpenAPI](../api/place-preview-draft.json)는 `0.2.0-draft`입니다. 기본 비활성·opt-in MOCK 전용입니다. `requirements`를 명시한 검색은 `recommendationPlan`을 함께 반환합니다.
- 기존 `generation + query` 요청도 유지하며, 조건 누락/null 시 `recommendationPlan=null`입니다. 인원·취향을 임의 기본값으로 채우지 않습니다.
- [조건 포함 요청](../api/examples/place-preview-recommendation-query.json)과 [가상 응답](../api/examples/place-preview-recommendation-result.json)을 제공합니다. 고정 시각의 설명용 예제로 실제 기기/식당 결과가 아닙니다.

## 요청과 응답

기존 네 연산(생성/조회/취소/종료)과 소유권·no-store 정책을 유지합니다. 자세한 오류/토큰 흐름은 [미리보기 안내](REC05_PREVIEW_API.md), [인증 연결 안내](REC05_TOKEN_INTEGRATION.md)를 따릅니다.

| 입력 | 의미 |
| --- | --- |
| `generation` | 1 이상 JavaScript 안전 정수. 검색 중심·반경·상한·동행 조건·취향이 바뀌면 사용자 의도에 맞춰 새 세대 사용 |
| `query` | 위경도·반경·표시 상한 1–3. 목적지 좌표와 현재 위치를 혼동하지 않음 |
| `requirements.party` | adults 1–20, children 0–10. 두 값 모두 명시 |
| `requirements.maxWaitMinutes` | 0–120분, 도착 시 대기 질문의 조건 |
| `requiredFeatures` | 중복 없는 childFriendly/parking 목록. 아동 동행이면 childFriendly 질문 자동 포함 |
| `preferredCuisines` | 중복 없는 최대 8개, 값당 1–30자·공백만 있는 값 거부. 취향 일치는 필수 조건 충족과 별개 |

같은 세대·같은 query·동등한 requirements의 진행 중 요청은 한 번의 가상 검색에 합류합니다. 같은 세대의 조건 변경은 `REQUEST_CONFLICT`이며 자동 재검색하지 않습니다. 완료 세대의 동일 요청도 결과를 보관/재생하지 않고 `ALREADY_FINISHED`를 반환합니다. 네트워크 실패를 새 세대/새 세션/새 인증 주체 자동 발급으로 우회하지 않습니다.

| 화면 데이터 | 적용 규칙 |
| --- | --- |
| `places`와 `recommendationPlan.candidates` | 같은 후보·순서·최대 3개. 추천 계획이 있으면 표시 거리도 계산한 직선거리이며 도로 거리/ETA가 아님 |
| `reasons` | WITHIN_SEARCH_WINDOW / PREFERRED_CUISINE_MATCH를 문구로 표시. 주차·아동 동반 확인 근거로 사용하지 않음 |
| `missingInformation` | ADDRESS/CUISINE/PHONE 누락 안내. 임의 값·전화번호 생성 또는 자동 외부 검색 금지 |
| `questions` | field/scope/certainty/prompt 표시. 모두 UNKNOWN. ARRIVAL 질문은 arrivalTimeRequired=true |
| `qualification` | NEEDS_CONFIRMATION. 상호/음식 분류로 필수 조건 충족을 추측하지 않음 |
| `bookable`, `inquiryDispatchAllowed` | 모두 false. 질문이 있다는 이유로 문의·예약 버튼을 활성화하지 않음 |
| `fetchedAt`/`expiresAt` | plan의 listingFetchedAt/displayValidUntil과 동일. 원 시각 보존, 만료 결과 숨김 |
| `ordinal` | 현재 응답 안의 순번. candidateId나 예약/전화 참조가 아님 |

## 화면 수명과 상태 처리

1. 요청 전 현재 `(previewId, generation, 화면 수명 식별자)`를 보관합니다. 목적지/조건 변경·뒤로 가기·화면 종료 시 이 식별자를 먼저 무효화하고 기존 후보·추천 계획을 비웁니다.
2. 응답 시 세 값이 여전히 현재 화면과 맞는지 확인하고, 유효기간과 상태를 확인한 뒤 표시합니다. 이전 화면 응답은 취소 HTTP의 성공 여부와 무관하게 폐기합니다. 시계 기준을 신뢰할 수 없으면 최신으로 단정하지 않습니다.
3. 목적지/조건 변경은 이전 조회 취소와 새 사용자 의도의 세대로 연결합니다. 화면 이탈은 취소·종료를 최선 노력으로 요청하며, 실패를 성공으로 표시하거나 자동 새 검색으로 보상하지 않습니다. 서버 내부 deadline/유휴 수명과 화면의 즉시 무효화를 함께 사용합니다.
4. `RESULTS`는 비교 후보, `EMPTY`는 정상 공급자 0건, `FILTERED`는 응답은 있었으나 검색 범위 내 후보가 없음을 뜻합니다. 후보를 가상 값으로 보충하지 않습니다.
5. `FAILED`는 실패 종류를 안내합니다. `CANCELLED/SUPERSEDED/TIMED_OUT/STALE_GENERATION/REQUEST_CONFLICT/ALREADY_FINISHED/CLOSED`에서는 결과를 비우고 자동 재요청하지 않습니다. 이 상태와 FAILED에는 recommendationPlan이 null입니다.
6. 토큰 보관/갱신은 #26 범위입니다. 갱신 뒤 동일 주체/미리보기 소유권을 유지하고, 무인증/폐기/토큰 유실 시 기존 예약의 불확실성을 새 주체로 우회하지 않습니다. 주행 입력·표시 제한은 #25 기준을 유지합니다.

현재 location/navigation 검증과 실제 데이터 정책은 #1/#11에 남아 있습니다. 이 요청에는 ETA를 추가하지 않았고 ARRIVAL 질문을 실제 발신할 수 없습니다. 현재 계약의 placeSource/reservationMode/mode는 모두 MOCK이며, 추후 실제 검색을 붙일 때 기능별 모드를 구분해야 합니다.

## 화면 담당과 함께 확인할 항목

아래 체크는 **공동 인수 대기**입니다. 서버 단위/HTTP 검사 통과로 Android 체크를 대신하지 않습니다.

- [x] #26에서 `0.2.0-draft` 요청·응답·오류를 정적 검토하고 현재 필드로 진행 가능하다는 답변을 #16에 남겼다. 실제 화면/기기 인수와 구분한다.
- [ ] 성인2/아동1·주차 필수·한식 선호·최대15분에서 후보 3개·근거·미확인 질문을 표시한다.
- [ ] 0건/범위 밖/실패를 다르게 표시하고 UNKNOWN을 예약 가능/확정으로 바꾸지 않는다.
- [ ] 조건 변경은 새 세대를 사용하고 같은 세대 조건 충돌을 자동 재시도하지 않는다.
- [ ] 목적지 변경·뒤로 가기·화면 종료 후 늦은 응답을 숨긴다. 취소/종료 네트워크 실패도 포함한다.
- [ ] 만료 시각 경계·앱 복귀·주행 제한·인증 갱신/유실을 기기에서 확인하고 결과/제한을 기록한다.

먼저 로컬 opt-in 서버에서 새 호출자를 재현할 수 있습니다. 출력은 가상 후보/질문 개수와 정리 상태만 포함하며 토큰·세션 ID·장소 원문은 출력하지 않습니다.

```bash
./gradlew -PincludeAndroid=false verify
# 별도 터미널, 테스트용 실행 위치에서만
java -jar server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar --app.place-preview.enabled=true
python3 scripts/preview-session-demo.py --base-url http://127.0.0.1:8080
```

서버 저장소는 loopback에 한정하며 외부 전화·Kakao·AI·유료 API를 실행하지 않습니다. 호출자는 인증 발급 → 임시 미리보기 → 가상 검색 1회 → 명시적 갱신 → 취소/종료 → 인증 폐기를 수행합니다. 공동 화면 검증·#1 ETA·#11 데이터 조건 인수 전에는 #16을 닫거나 #19를 병합 준비로 바꾸지 않습니다.
