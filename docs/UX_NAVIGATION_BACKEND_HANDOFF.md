# 내비 목적지 기반 추천 연결 준비

## 기준과 구현

2026-11-11. #47 `bbfb1844af7b11118d229ef2b8fe40822705dd19` 인수안·NaviHelper3.0.5와 #53 `cc3933999c11d14336f841ec04354b6712144fa9` 추천 초안을 기준으로 한다. 공동 인수 완료가 아니다.

- 추천 화면의 가상 목적지 선택을 제거했다. 내비 수신 이름·위도·경도만 입력 대상으로 사용하고 목적지 누락/잘못된 필드/현재 경로 미확인에서는 POST하지 않는다.
- `NavigationDestinationAdapter`가 NAVI_ROUTE 권한으로 목적지를 읽는다. 변경·경로 탐색/재탐색 완료 시 기존 결과부터 무효화하고 재조회한다. 시작·취소·도착·오류·화면 종료 시 기존 목적지/후보를 폐기한다. 종료된 adapter의 지연 콜백은 적용하지 않는다.
- SDK 응답에 요청/경로 식별자가 없어 현재 경로 연관성을 입증할 수 없다. 실제 SDK 값은 UNVERIFIED로 유지한다. 유효 좌표만으로 검색 허용하지 않는다. 현재 제품 경로에서 이를 true로 승격하는 코드는 없다.
- Gleo SET_PARTY_SIZE는 목적지 확인 전에도 인원을 반영한다. SEARCH 명령은 목적지 검증이 필요하다. 입력 성공을 검색/예약 성공으로 알리지 않는다.
- 기존 가상 예약 연습과 그 기록은 유지하며 내비 추천과 별개라고 표시한다. #53 후보 ordinal을 전화/예약 식별자로 사용하지 않는다.

## 백엔드 연결

기존 Bearer 인증과 POST `/api/experimental/destination-recommendations`, GET/close를 사용한다. 서버 계약 변경 없음.

| 앱 값 | 요청 필드 |
| --- | --- |
| 내비 목적지 이름·좌표 | destination.name / latitude / longitude |
| 음성 또는 화면 인원 | party.adults / children |
| 필수 조건 | requirements |

ETA는 이 추천 요청에 필요하지 않다. 원시 ETA를 도착 시각으로 변환하지 않는다. 목적지 이름 1–100자, 유한 위도/경도 범위, 성인1–20/아이0–10을 검사한다. MOCK 서버 가드와 외부 호출 차단을 유지한다.

## 검증

- format, verifyAll, assembleDebugAndroidTest PASS. UX123개 실패/오류/skip0. 서버313개는 UP-TO-DATE 결과를 포함하며 새 실행 건수로 세지 않는다. Android JVM NO-SOURCE 제외.
- smoke-test.py PASS: MOCK HTTP, H2 재시작 영속화, LIVE 시작 차단.
- Lint 오류0/경고15, 새 억제 없음. SDK XML 버전 경고가 있으나 빌드 성공.
- 별도 destinationvalidation 설치의 NAVIGATION 계측: 목적지 없음/UNVERIFIED 검색 차단 → 합성 검증 목적지 주입 → 실제 loopback HTTP 후보 표시 → 목적지 무효화 시 후보 폐기, journal 보존 PASS.
- 같은 설치 GLEO 계측: 인원 반영·검색·중복/잘못된 값/제한·UNKNOWN·journal 보존 PASS. 직접 Intent 주입이며 이번 변경의 실제 사용자 발화 검사로 세지 않는다.
- 제품 Activity에서 실제 SDK DESTINATION_UNVERIFIED 수신을 기기05:20:46.772 로그로 확인했다. 이름/좌표는 로그·fixture에 저장하지 않았다. 실제 내비 목적지 POST 성공으로 세지 않는다.
- 로컬 증거: `work/navigation-binding/`, `captures/navigation-binding/unverified-fixture.png`. 테스트 목적지는 모두 합성 값이다.

## 남은 작업

#1/#47에서 현재 경로와 응답의 연관성 보장 근거를 확보해야 실제 내비 목적지 검색을 활성화할 수 있다. 입력의 boolean을 강제로 true로 바꾸는 것은 완료 방법이 아니다. 실제 변경/취소/늦은 응답 기기 인수, #52/#53 공동 인수, 실제 서버 배포 연결은 미완료다. 추천에 ETA 계약 변경은 필요 없으나 실제 예약 ETA 인수는 별개다. 외부 장소·전화·AI·유료 호출0. PR #46 Draft 유지.

최종 APK SHA256: `3d62c2408f3498bcaa1a2002117bd03d93ad41f52e81a59f4478651675823c2d`. 최종 APK 재설치 후 NAVIGATION/GLEO 모두 PASS. mock-recovery.xml 전체 SHA256 전후 `cd53323c4c0e83e38df13804d60091ab6ff4da8c6658a567023d64524590f60d` 동일.
