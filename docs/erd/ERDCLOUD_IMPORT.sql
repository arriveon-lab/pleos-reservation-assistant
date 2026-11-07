-- ERDCloud import ONLY. Logical draft v0.1, not a deployment migration.
-- MySQL-style syntax is an import notation, not a selected production database.
-- CHAR(36)=logical UUID; DATETIME(6)=logical UTC instant; lengths are provisional.
-- Conditional business constraints are documented separately in ../ERD.md.

CREATE TABLE `users` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `auth_provider` VARCHAR(64) NOT NULL COMMENT '인증 제공자',
  `auth_subject` VARCHAR(255) NOT NULL COMMENT '인증 주체 식별자',
  `created_at` DATETIME(6) NOT NULL COMMENT '생성 시각 UTC',
  `updated_at` DATETIME(6) NOT NULL COMMENT '수정 시각 UTC',
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_users_1` UNIQUE (`auth_provider`, `auth_subject`)
) COMMENT='사용자 | #4';

CREATE TABLE `trips` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `user_id` CHAR(36) NOT NULL COMMENT '소유 사용자',
  `client_trip_id` VARCHAR(100) NOT NULL COMMENT '클라이언트 여정 ID',
  `mode` VARCHAR(8) NOT NULL COMMENT 'MOCK 또는 LIVE',
  `created_at` DATETIME(6) NOT NULL COMMENT '생성 시각 UTC',
  `updated_at` DATETIME(6) NOT NULL COMMENT '수정 시각 UTC',
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_trips_1` UNIQUE (`user_id`, `mode`, `client_trip_id`),
  CONSTRAINT `fk_trips_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) COMMENT='여정 | #1 입력 / #4 저장';

CREATE TABLE `searches` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `trip_id` CHAR(36) NOT NULL COMMENT '소속 여정',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `eta` DATETIME(6) NOT NULL COMMENT '검색 시점 도착 예정 시각',
  `navigation_source` VARCHAR(16) NOT NULL COMMENT 'MOCK MANUAL PLEOS',
  `navigation_observed_at` DATETIME(6) NOT NULL COMMENT '내비 관측 시각',
  `request_snapshot` JSON NOT NULL COMMENT '동행 조건 취향 동의 등 SearchRequest 원본',
  `created_at` DATETIME(6) NOT NULL COMMENT '검색 생성 시각',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_searches_trip_id` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`)
) COMMENT='검색 스냅샷 | #2 / #4';

CREATE TABLE `places` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `provider` VARCHAR(64) NOT NULL COMMENT '장소 데이터 사업자',
  `provider_place_id` VARCHAR(255) NOT NULL COMMENT '사업자 장소 ID',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `name` VARCHAR(200) NOT NULL COMMENT '매장명',
  `cuisine` VARCHAR(100) NULL COMMENT '음식 종류',
  `address` VARCHAR(500) NULL COMMENT '허용된 주소 정보',
  `latitude` DECIMAL(10,7) NULL COMMENT '위도',
  `longitude` DECIMAL(10,7) NULL COMMENT '경도',
  `contact_ref` VARCHAR(255) NULL COMMENT '관리되는 연락처 참조',
  `fetched_at` DATETIME(6) NULL COMMENT '기본 정보 조회 시각',
  `valid_until` DATETIME(6) NULL COMMENT '기본 정보 유효기한',
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_places_1` UNIQUE (`provider`, `provider_place_id`, `mode`)
) COMMENT='장소 기본 정보 | #2';

CREATE TABLE `search_candidates` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `search_id` CHAR(36) NOT NULL COMMENT '소속 검색',
  `place_id` CHAR(36) NOT NULL COMMENT '참조 장소',
  `place_snapshot` JSON NOT NULL COMMENT '검색 당시 허용된 장소 정보',
  `created_at` DATETIME(6) NOT NULL COMMENT '후보 생성 시각',
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_search_candidates_1` UNIQUE (`search_id`, `place_id`),
  CONSTRAINT `fk_search_candidates_search_id` FOREIGN KEY (`search_id`) REFERENCES `searches` (`id`),
  CONSTRAINT `fk_search_candidates_place_id` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
) COMMENT='검색별 후보 | #2';

CREATE TABLE `candidate_facts` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `candidate_id` CHAR(36) NOT NULL COMMENT '검색 후보',
  `fact_key` VARCHAR(64) NOT NULL COMMENT '주차 아동 대기 예약가능 등 사실 키',
  `value` JSON NULL COMMENT '관측값 NULL과 false 및 0 구분',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `source` VARCHAR(64) NOT NULL COMMENT '관측 출처',
  `evidence_ref` VARCHAR(255) NOT NULL COMMENT '논리 증거 참조 DB FK 아님',
  `observed_at` DATETIME(6) NOT NULL COMMENT '원 관측 시각',
  `applies_at` DATETIME(6) NOT NULL COMMENT '사실 적용 시각',
  `valid_until` DATETIME(6) NOT NULL COMMENT '관측 유효기한',
  `certainty` VARCHAR(16) NOT NULL COMMENT 'CONFIRMED ESTIMATED UNKNOWN',
  `scope` VARCHAR(16) NOT NULL COMMENT 'GENERAL NOW ARRIVAL',
  `created_at` DATETIME(6) NOT NULL COMMENT '저장 시각 관측 시각과 구분',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_candidate_facts_candidate_id` FOREIGN KEY (`candidate_id`) REFERENCES `search_candidates` (`id`)
) COMMENT='후보 관측 이력 | #2 / #3';

CREATE TABLE `reservations` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `trip_id` CHAR(36) NOT NULL COMMENT '소속 여정',
  `candidate_id` CHAR(36) NOT NULL COMMENT '선택한 검색 후보',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `status` VARCHAR(16) NOT NULL COMMENT 'PENDING CONFIRMED REJECTED UNKNOWN',
  `original_requested_time` DATETIME(6) NOT NULL COMMENT '최초 예약 요청 시각 불변',
  `confirmed_time` DATETIME(6) NULL COMMENT '명시적 수락 확인 시각',
  `change_policy_snapshot` JSON NOT NULL COMMENT '승인 당시 변경 정책',
  `total_additional_cost_krw` INT NOT NULL DEFAULT 0 COMMENT '수락 추가 비용 누계',
  `booking_approved_by` CHAR(36) NOT NULL COMMENT '예약 승인 사용자',
  `booking_approved_at` DATETIME(6) NOT NULL COMMENT '예약 승인 시각',
  `booking_approval_snapshot` JSON NOT NULL COMMENT '대상 인원 시각 정책과 인증 근거',
  `created_at` DATETIME(6) NOT NULL COMMENT '생성 시각',
  `updated_at` DATETIME(6) NOT NULL COMMENT '수정 시각',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '동시 수정 검증 버전',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_reservations_trip_id` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`),
  CONSTRAINT `fk_reservations_candidate_id` FOREIGN KEY (`candidate_id`) REFERENCES `search_candidates` (`id`),
  CONSTRAINT `fk_reservations_booking_approved_by` FOREIGN KEY (`booking_approved_by`) REFERENCES `users` (`id`)
) COMMENT='예약과 승인 | #4';

CREATE TABLE `reservation_changes` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `reservation_id` CHAR(36) NOT NULL COMMENT '원 예약',
  `status` VARCHAR(16) NOT NULL COMMENT 'PENDING ACCEPTED REJECTED UNKNOWN',
  `previous_confirmed_time` DATETIME(6) NOT NULL COMMENT '요청 당시 원 예약 시각',
  `requested_time` DATETIME(6) NOT NULL COMMENT '변경 요청 시각 불변',
  `requested_additional_cost_krw` INT NOT NULL COMMENT '요청 추가 비용 KRW',
  `accepted_time` DATETIME(6) NULL COMMENT '검증된 수락 시각',
  `accepted_additional_cost_krw` INT NULL COMMENT '검증된 수락 비용',
  `authorization_source` VARCHAR(16) NOT NULL COMMENT 'POLICY 또는 EXPLICIT',
  `authorization_snapshot` JSON NOT NULL COMMENT '적용 정책 또는 명시 승인 증빙',
  `cost_approved_by` CHAR(36) NULL COMMENT '추가 비용 승인자',
  `cost_approved_at` DATETIME(6) NULL COMMENT '추가 비용 승인 시각',
  `created_at` DATETIME(6) NOT NULL COMMENT '변경 요청 생성 시각',
  `resolved_at` DATETIME(6) NULL COMMENT '해결 시각 UNKNOWN이면 NULL',
  `result_reason` VARCHAR(255) NULL COMMENT '변경 결과 사유',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_reservation_changes_reservation_id` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`),
  CONSTRAINT `fk_reservation_changes_cost_approved_by` FOREIGN KEY (`cost_approved_by`) REFERENCES `users` (`id`)
) COMMENT='예약 변경 이력 | #3 / #4';

CREATE TABLE `calls` (
  `id` CHAR(36) NOT NULL COMMENT '내부 UUID',
  `candidate_id` CHAR(36) NOT NULL COMMENT '대상 검색 후보',
  `reservation_id` CHAR(36) NULL COMMENT '관련 예약 문의면 NULL',
  `change_id` CHAR(36) NULL COMMENT '관련 변경 CHANGE만 사용',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `purpose` VARCHAR(16) NOT NULL COMMENT 'INQUIRY BOOKING CHANGE',
  `status` VARCHAR(16) NOT NULL COMMENT 'PENDING ANSWERED ACCEPTED REJECTED UNKNOWN',
  `requested_time` DATETIME(6) NOT NULL COMMENT '대상 예약 또는 ETA 시각',
  `additional_cost_krw` INT NOT NULL COMMENT '요청 매장 추가 비용 KRW',
  `provider` VARCHAR(64) NULL COMMENT '전화 사업자',
  `provider_call_id` VARCHAR(255) NULL COMMENT '사업자 통화 ID',
  `disclosure_snapshot` TEXT NOT NULL COMMENT 'AI 대리 통화 고지 내용',
  `result_reason` VARCHAR(255) NULL COMMENT '업무 결과 사유',
  `created_at` DATETIME(6) NOT NULL COMMENT '생성 시각',
  `updated_at` DATETIME(6) NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (`id`),
  CONSTRAINT `uq_calls_1` UNIQUE (`change_id`),
  CONSTRAINT `fk_calls_candidate_id` FOREIGN KEY (`candidate_id`) REFERENCES `search_candidates` (`id`),
  CONSTRAINT `fk_calls_reservation_id` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`),
  CONSTRAINT `fk_calls_change_id` FOREIGN KEY (`change_id`) REFERENCES `reservation_changes` (`id`)
) COMMENT='문의 예약 변경 통화 작업 | #3 / #4';

CREATE TABLE `events` (
  `event_id` VARCHAR(100) NOT NULL COMMENT '전역 고유 이벤트 ID',
  `call_id` CHAR(36) NOT NULL COMMENT '대상 통화',
  `request_hash` CHAR(64) NOT NULL COMMENT '통화와 정규화 결과 SHA256',
  `normalized_payload` JSON NOT NULL COMMENT '명시적 수락 시간 비용 사실 원본',
  `received_at` DATETIME(6) NOT NULL COMMENT '이벤트 수신 시각',
  `processed_at` DATETIME(6) NOT NULL COMMENT '업무 반영 시각',
  PRIMARY KEY (`event_id`),
  CONSTRAINT `fk_events_call_id` FOREIGN KEY (`call_id`) REFERENCES `calls` (`id`)
) COMMENT='정규화 통화 결과 이벤트 | #3 / #4';

CREATE TABLE `commands` (
  `actor_id` CHAR(36) NOT NULL COMMENT '명령 실행 사용자',
  `mode` VARCHAR(8) NOT NULL COMMENT '실행 모드',
  `scope` VARCHAR(160) NOT NULL COMMENT '작업 종류와 대상 ID',
  `idempotency_key` VARCHAR(100) NOT NULL COMMENT '동일 명령 재전송 키',
  `request_hash` CHAR(64) NOT NULL COMMENT '요청 본문 SHA256',
  `response_snapshot` JSON NOT NULL COMMENT '최초 응답 불변 스냅샷',
  `created_at` DATETIME(6) NOT NULL COMMENT '명령 생성 시각',
  PRIMARY KEY (`actor_id`, `mode`, `scope`, `idempotency_key`),
  CONSTRAINT `fk_commands_actor_id` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) COMMENT='사용자 명령 멱등성 | #4';
