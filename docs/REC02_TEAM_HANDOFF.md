# REC-02 · 추천 표시·차량 입력·전화 연동 회신 기준

2026-11-11 KST, 담당 tiemo0708. #11/#52의 추천 표시·폐기 기준을 정하고 #1/#43/#50의 담당 요청에 답변하기 위한 문서입니다. 문서상 결정과 현재 구현을 구분합니다. 다른 담당의 API를 새로 구현하거나 #54의 종료를 번복하지 않습니다.

검토 기준은 서버 미리보기 [a5ae15e](https://github.com/arriveon-lab/pleos-reservation-assistant/tree/a5ae15ed9fedd9bcd7755a75f384020f2d5a2731), 웹 조건 추천 [2773971](https://github.com/arriveon-lab/pleos-reservation-assistant/tree/2773971701616b63489b6a73b9a830696023d940), 목적지 추천 [cbef0c5](https://github.com/arriveon-lab/pleos-reservation-assistant/tree/cbef0c5e728bbb6260d2688a299d1e2b711f0717), 전화 프로토타입 [092cb1f](https://github.com/arriveon-lab/pleos-reservation-assistant/tree/092cb1fabc7d59020703636f3d023ee5dbcf9c36), 차량 인수안 [25b28a6](https://github.com/arriveon-lab/pleos-reservation-assistant/tree/25b28a6ccef0b5ac18ecd27b437654af669f47fa)입니다. 모두 별도 PR의 검토 기준이며 main 통합 완료를 뜻하지 않습니다.

<a id="retention"></a>
## 1. #11 · 클라이언트 표시·폐기 기준

같은 이용자의 현재 화면·목적지·조건·세대에 한해 외부 결과를 메모리에서 표시합니다. 표시 마감은 서버 작업/결과의 `validUntil`, listing의 `displayValidUntil`, 개별 근거의 기한 중 적용 가능한 **가장 이른 시각**입니다. 작업/결과의 현재 개발 상한은 5분이며 UI 수신·재조회 때 연장하지 않습니다. 이는 프로젝트의 표시 제한이며 공급자의 정확성/최신성 보장이 아닙니다. 필수 기한 누락·잘못된 시각·이미 만료된 응답은 표시하지 않습니다.

| 필드/정보 | 현재 화면 | 디스크·재실행 복구 | 서버/다음 담당 경계 |
| --- | --- | --- | --- |
| 상호·주소·좌표·Kakao 후보·목록 순서/거리 | 같은 비교 세션 메모리 | 저장·재생 금지 | 조회 응답은 장소 확인/예약/전화 승인 아님 |
| 웹 근거 URL/제목·조건 설명·YES/NO/UNKNOWN/CONFLICT·출처/관측/유효 시각 | 표시 중 출처·조건·확실성을 함께 유지 | DB/Room/SharedPreferences/journal/백업/화면 복원 상태/분석 이벤트에 넣지 않음 | 자체 매장 사실/모델 학습 데이터로 바꾸지 않음 |
| 사용자 직접 지역·요구 조건·세대·개인화 선택 | 사용자 입력으로 구분 | 새 추천용 로컬 영속 journal을 이번 범위에서 추가하지 않음 | #51의 기존 서버는 요청을 보관함. 운영 보관 기간은 #4 결정 필요 |
| 직접 선택 ordinal·MATCH/PARTIAL/NO_MATCH·고정 이유 | 사용자 직접 행위만 전송 | 새 로컬 영속 기록/백업 추가 없음 | #51의 본인 요청/평가 GET만 사용. ordinal은 매장/예약 ID 아님 |
| requestId/taskId/viewId/generation·요청 키·안전한 상태 코드 | 실행 중 대조에 필요한 최소 메모리 | 새 추천 경로에서는 프로세스 종료 후 복구용으로 보존하지 않음 | 서버의 멱등/최소 tombstone과 별개. 응답 유실을 새 키 자동 실행으로 우회하지 않음 |
| 기존 인증 토큰·기존 MOCK 예약 복구 journal | 기존 #26 정책 유지 | 이번 결정으로 삭제/확장하지 않음 | 새 외부 추천 payload를 기존 원 명령 복구 기록에 넣지 않음 |

목적지/조건 변경, 화면 이탈·onStop, 만료, 새 세대, 앱 재실행 때 외부 후보와 근거를 먼저 비웁니다. 늦은 응답은 owner/view/generation과 기한을 다시 확인해 버립니다. 결과·URL·외부 판정·원문을 오류 로그, HTTP body 로그, screenshot telemetry 또는 외부 내용 hash로 남기는 우회도 하지 않습니다. 사용자 요청 hash와 외부 매장 내용 hash는 구분합니다.

### 서로 다른 API의 수명

- **#51 `/api/experimental/web-recommendations`:** POST 결과는 일회 전달입니다. 같은 키/본문은 `ALREADY_REQUESTED`/후보0이며 웹 결과를 재생하지 않습니다. GET은 본인 사용자 요청/직접 평가만 반환합니다. 화면은 먼저 비우고 `POST /{id}/close`를 호출합니다. 첫 선택은 현재 유효한 후보만 가능하며, 이미 저장한 동일 ordinal의 나중 직접 평가 수정만 만료/종료 후 허용됩니다. 새 ordinal 선택은 금지합니다.
- **#53 `/api/experimental/destination-recommendations`:** 같은 주체·작업·세대의 유효한 메모리 결과는 GET 폴링으로 반복 전달될 수 있습니다. 폴링이 외부 재검색을 시작하거나 기한을 갱신하지 않습니다. 이 경로에는 #51의 `ALREADY_REQUESTED`/피드백 연산을 가정하지 않습니다. `DELETE /views/{viewId}`로 무효화합니다.
- #53의 `listing.candidates`는 미확인 장소 목록입니다. 웹·지점 대조를 통과한 추천은 `matches.candidates`로 구분하고, `matches=null`/NO_MATCH/빈 배열/APPROVAL_REQUIRED를 검증된 추천으로 표시하지 않습니다. UNKNOWN을 가능으로 읽지 않고 예약/전화 버튼을 활성화하지 않습니다.
- close/DELETE 실패·응답 유실에도 이전 결과는 숨긴 채 유지합니다. 다른 이용자·새 요청·재진입의 캐시로 재사용하거나 자동 재검색하지 않습니다. 프로세스 종료로 작업 ID가 사라졌으면 외부 결과 복구를 약속하지 않습니다. #53 서버의 현재 정리는 접근 시 reap이므로 5분 경과 직후 물리적 메모리 제거를 보장한다고 안내하지 않습니다. 화면 종료 무효화 실패/비활성 작업 정리의 서버 인수는 #52/#17에 남습니다.
- #51 DELETE는 사용자 JSON을 비우지만 최소 tombstone을 남깁니다. 완전 삭제/익명화로 안내하지 않습니다. 보관 기간·계정 삭제 연계는 #4가 확정할 항목이며 임의 30일/무기한 보관 약속을 만들지 않습니다. `personalizationConsent`는 현재 학습이나 예약·발신 승인으로 사용하지 않습니다.

기존 [출처별 경계](PLACE_DATA_USE_MATRIX.md)를 유지합니다. Kakao 후보는 같은 이용자 비교 세션의 일시 표시 경로로 다루고 GPT·전화 공급자 입력/영속 예약 이력으로 전환하지 않습니다. 예약 연락처용 공공 catalog는 선택 경로이며 추천 3곳의 선행 조건으로 두지 않습니다. 이번 기준은 추가 외부 호출 승인이 아닙니다.

<a id="phone"></a>
## 2. #43 · 제품 전화 API 제안에 대한 지원 범위

[서버 담당 제안](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/43#issuecomment-6767789664)의 비동기 작업/멱등/소유권/비용 상한 방향에 동의합니다. 아래는 담당 측 설계 회신이며 아직 구현된 서버 간 API가 아닙니다. 현재 프로토타입의 브라우저 쿠키를 제품 Bearer 인증이나 서비스 자격 증명으로 재사용하지 않습니다.

| 작업 | 현재 재사용 가능 | 새로 필요한 것 / 현재 상태 |
| --- | --- | --- |
| BOOK | 승인 사본·버전·소유 세션 검증, 발신 claim, 매장 수락 근거/시각/인원/비용 대조, UNKNOWN·중복 보호 | 제품 사용자/여정/후보와 프로토타입 식별자 연결·서버 간 인증·작업 API·제품 결과 mapping 미구현 |
| VERIFY_FEATURES | 대화/근거 수집과 매장 식별의 일부 기반 | 독립 질문 작업·질문별 YES/NO/UNKNOWN/CONFLICT 및 GENERAL/ARRIVAL 결과는 신규 구현 필요. 예약 도구를 조건 문의 성공으로 대용하지 않음 |
| DELAY_NOTICE | 원 예약 보존, 사용자 변경 승인·매장 재확인 기반 | 독립 지연 전달 작업/DELIVERED 결과는 신규 구현 필요. 전달 완료와 시간 변경 수락을 별도 필드로 둠 |

책임 분리안은 #52/#4가 제품 Spring의 `/api/v1/phone-tasks`와 소유권·승인/작업 원장을 맡고, #43은 `prototypes/phone_agent`의 실행 adapter/worker와 실제 통화 결과를 맡는 것입니다. 서비스 간 진입점과 인증/소유자 mapping은 공동 확정 후 별도 구현합니다. 공개 API가 이미 동작한다고 호출자를 만들지 않습니다. 초기 결과 전달은 **폴링**을 제안하며 웹훅·서명/재생 방지는 후속입니다.

- 접수 응답 `202 taskId/PENDING`, 같은 주체·작업별 같은 키/동일 본문은 같은 taskId, 다른 본문은409의 의미에 동의합니다. 현재 #45가 이 HTTP 계약을 제공하는 것은 아닙니다. 제품 원장과 실행 claim의 crash/재시작 대조 및 중복 dispatch 검사가 필요합니다.
- `tripId`, 안정된 `candidateId`, `contactRef`, `approvalRef`, 실행 모드·최대 횟수/금액·만료와 작업별 필수 조건을 검증합니다. contactRef/approvalRef라는 문자열만으로 허가하지 않고 소유자·출처·승인된 조건/버전과 실제 상한을 조회·대조합니다.
- 작업 상태와 업무 결과를 나눕니다. `COMPLETED`는 통화 작업 종료일 뿐 예약 확정이 아닙니다. BOOK의 ACCEPTED/REJECTED/UNKNOWN과 실제 수락 시각·인원·비용을 별도로 검증합니다. 비용 집계가 미확정이면 0원으로 확정하지 않습니다.
- timeout/단절/UNKNOWN 뒤 자동 재발신하지 않습니다. 공급자 전달 전 취소와 전달 여부 미확인의 취소를 구분하며, 이미 dispatch한 작업을 로컬에서 지웠다고 CANCELLED로 확정하지 않습니다. 현재 제품용 취소/대조 endpoint도 미구현입니다.
- 통화 결과의 callId/evidenceRef와 허용된 최소 관측/확실성/근거를 반환하고 전체 전사/연락처를 일반 상태 응답이나 로그로 복제하지 않는 방향입니다. 실제 공개 범위·보관/삭제는 #4/#11과 확정합니다.
- ARRIVAL 조건 확인·예약·지연 통보는 검증된 절대 시각과 그 작업의 명시 승인이 전제입니다. 원 예약은 매장 수락·시간·비용 일치 전까지 유지합니다. 약10분 판단기는 현재 순수 판단이며 스케줄러나 자동 연락 허가가 아닙니다.

지금은 추천 3곳의 병목을 먼저 해결하고 전화 연동을 별도 단계로 유지합니다. LIVE 기본 OFF·현재 allowlist/일일·동시/시간 상한을 유지하며 이번 회신으로 실발신/수신/배포/추론을 실행하지 않습니다. 기존 실제 시험 승인을 재사용하지 않습니다.

<a id="navigation"></a>
## 3. #1 · 현재 서버 Navigation 기준과 차량 인수 조건

[csjws 인계](https://github.com/arriveon-lab/pleos-reservation-assistant/issues/1#issuecomment-6760697971)와 최신 PR #47 문서를 검토했습니다. raw ETA가 초라는 사실만으로 남은 시간/Unix 시각을 결정하지 않습니다. 현재 경로 응답 연관성도 미확인이라 **`source=PLEOS` 업무 요청 차단은 유지**합니다.

| 기존 `/api/v1/searches` 기준 | 정확한 현재 동작 |
| --- | --- |
| source | MOCK/MANUAL만 기존 검증 경로 허용. PLEOS는 시간 검사 전에400 `PLEOS_ETA_UNVERIFIED` |
| eta | 절대 RFC3339 Instant, `now < eta <= now+86400초`; 벗어나면400 `INVALID_TIME` |
| observedAt | `now-300초 <= observedAt <= now+5초`; 벗어나면400 `STALE_NAVIGATION` |
| 필수 필드 누락/잘못된 형식 | 기존 요청 검증의400 `INVALID_REQUEST` |

위 300초/+5초는 기존 MOCK/MANUAL HTTP 입력의 서버 거절 기준이며 차량 SDK의 실제 freshness/갱신 주기를 검증한 값이 아닙니다. 앱 콜백 시각을 원천 생성 시각으로 바꾸거나 현재+1시간·MOCK/MANUAL 이름으로 미확인 차량 ETA를 우회하지 않습니다.

오류 예시는 다음과 같습니다. 모두 가상 설명이며 실제 호출 결과를 새로 생성한 것이 아닙니다.

```json
{"code":"PLEOS_ETA_UNVERIFIED","message":"Pleos ETA 시간 기준·기기 검증 전에는 MOCK 또는 MANUAL 입력만 가능합니다.","mode":"MOCK"}
```

가상 기준 now=`2026-11-11T03:00:00Z`에서 사용자가 직접 제공한 MANUAL eta=`2026-11-11T03:30:00Z`, observedAt=`2026-11-11T03:00:00Z`는 시간 검사 범위입니다. eta가 now와 같으면 INVALID_TIME, observedAt가 now-301초 또는 now+6초면 STALE_NAVIGATION입니다. 전체 요청은 [기존 예제](../api/examples/search.json)의 필수 필드를 함께 사용해야 합니다.

차단 해제 전 #1은 동시 UI·SDK/monotonic 관측으로 시간 기준·현재 경로 연관성·갱신/오래됨·변경/취소/늦은 응답·복귀를 인계하고, #16/#4/#26은 변환·freshness·오류/호출자를 같은 계약으로 검증해야 합니다. 목적지 좌표/이름이 유효하다는 사실과 현재 경로의 응답이라는 사실은 분리합니다.

#53 목적지 추천 요청은 **eta/observedAt 없는 별도 계약**입니다. 추천 3곳 때문에 미확인 ETA를 만들어 기존 Navigation 요청에 넣을 필요는 없습니다. 현재 경로에 속한 목적지인지 확인하고 view/generation을 바꿔 이전 결과를 폐기하는 앱 인수는 여전히 필요합니다. ETA를 쓰는 예약·지연 통보는 계속 별도 차단합니다.

<a id="scope"></a>
## 4. #50 · 현재 MVP 범위와 후속 인수

- 기존 #16/#26의 MOCK 예약 연습과 복구를 유지합니다. #54의 새 웹 추천·직접 피드백·삭제 앱 확장은 **미구현/not planned 종료** 상태를 유지하며 재개/재배정하지 않습니다. #50/#51 서버 구현이 앱 채택 완료를 뜻하지 않습니다.
- 지금 #52/#53의 목표는 목적지 근처 실제 후보 최대3곳의 추천 근거/지점 대조입니다. 공공 catalog나 전화번호 확보·전화 API·직접 피드백 화면을 이 단계의 필수 선행으로 두지 않습니다. #53 Android 호출자는 soyeong03 담당이고 계약/quality는 jjin70 담당입니다.
- 향후 #50 연결을 재개할 때는 기존 흐름을 대체하지 않는 별도 명시 진입점으로 시작합니다. 기준은 [2773971의 REC13](https://github.com/arriveon-lab/pleos-reservation-assistant/blob/2773971701616b63489b6a73b9a830696023d940/docs/REC13_WEB_RECOMMENDATION.md)·그 커밋의 OpenAPI/예제입니다. 기본OFF/0건/실패/만료/상충, 최대3개, 출처·시각·확실성·선택 가능 기한을 구분합니다. 선택은 예약/문의 승인이 아닙니다.
- #51 첫 선택/직접 평가는 현재 유효 ordinal로 명시 전송합니다. 현재 PUT은 선택+직접 평가를 함께 받는 계약이며 별도 선택 API를 가정하지 않습니다. 만료 후에는 기존 저장 ordinal의 직접 평가 수정만 가능합니다. GET/DELETE/재시도의 수명은 위 1절을 따릅니다.
- #53의 목적지 후보 ordinal/목록을 #51의 피드백 대상으로 보내는 교차 ID 변환은 없습니다. 새 공개 필드·migration 조합은 #4/#52와 별도 인수하고 기존 DB를 초기화하지 않습니다.

## 이번 확인의 검증 수준

위 고정 commit의 코드/OpenAPI/기존 검증 기록과 이슈 댓글을 정적으로 대조하고 문서 링크·형식·시각 경계를 확인합니다. 문서 회신 작업으로 런타임/API/DB/앱을 변경하지 않습니다. 기존 서버385개·UX80/신규13개·전화130개의 과거 통과 기록을 이번 새 실행 건수로 세지 않습니다. 실제 장소/GPT/전화/유료 호출·키/서버/앱/기기 변경은 없습니다. 운영 보관 기간·서비스 간 인증·새 전화 작업 API·차량 시간 변환·전체 공동 인수는 완료로 표시하지 않습니다.
