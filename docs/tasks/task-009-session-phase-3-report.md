# Task 009 — 운동 세션 3단계 구현 보고서

상태: 구현·최종 전체 검증·자체 리뷰 완료. 사용자 검토 전 커밋/Push하지 않는다.
기준 HEAD: `72d34f0b0f416ef225ac327fd2282726ca2df357` (`main`, 2단계 커밋).
대상: Backend만. 개인 `overload_dev`에 접속하거나 V12~V14를 적용하지 않았다.

## 구현과 API

정확한 JSON·타입·검증·오류 계약은 [운동 세션 계약](../api/workout-sessions.md)을 따른다.

- `POST /api/v1/workout-sessions/{id}/finish`: V13 snapshot PUT의 전체 요청에 실제 사용자 종료 시각 `endedAt`을 추가한다. 기존 저장 함수를 재사용하여 최종 계획·수행·명시 승인·최초 평가·FINISHED 전환을 한 트랜잭션으로 확정한다.
- AppliedPlan은 별도 복사본 없이 기존 JSONB를 종료와 동시에 동결한다. InitialPlan 불변 trigger는 유지한다. 최초 평가 JSONB에는 규칙 버전·최초 종료 version·평가 세트 UUID/반복·비교 문맥·과거 비교 근거·권고/제외 사유를 보존한다.
- 동일 기기/epoch/revision/canonical snapshot hash/endedAt의 FINISHED 재요청은 원래 평가와 종료 메타를 `duplicate=true`로 반환한다. 별도 종료 요청 UUID·영수증은 없다. 다른 종료 내용은409 FINISH_REQUEST_MISMATCH, 구 편집자는409 EDITOR_OWNERSHIP_LOST다.
- 만료는 잠금 후 서버 시각으로 `now >= lastWorkoutSyncedAt + 24h`일 때 처리한다. AUTO_CLOSED에 endedAt·최초 명시 종료 평가를 만들지 않는다. 부분 웨이트·확정 유산소·삭제 이력·PENDING을 그대로 보존한다.
- START·세션 조회·snapshot·finish·takeover·승인 해결에 만료 검사를 연결했다. 늦은 쓰기는 자동 종료를 먼저 커밋한 뒤409 SESSION_AUTO_CLOSED로 반환한다. 새 START는 만료 슬롯 해제와 새 세션 생성이 원자적이다.
- 기존 Spring `@Scheduled`를 사용하여 기동 시 및 기본60초 간격으로 DB 만료 후보를 조회한다. 후보별 독립 트랜잭션과 SKIP LOCKED를 사용하고 실패는 롤백/다음 실행 재시도한다. 별도 서버·잠금 시스템·미래 테이블·의존성은 없다.

조회·takeover·동일 재전송·높은 무변경 revision·승인 해결은 운동 동기화 기한을 연장하지 않는다. 서버 저장 메타 시각만 PostgreSQL microsecond 정밀도로 맞추어 원래 응답과 재확인 ACK를 일치시킨다. 사용자 발생 시각은 보정하지 않는다.

## 공식 평가 정책 확인

Task 006의 원계획/실행계획·실제 수행·명시 승인 분리와 Task 008 §11의 비교 자격/단일 과거 세션 규칙을 대조했다. 기존 데모 `overload-pwa/src/features/workout/model.ts`의 `recommendation`, `historicalWeightReview`, `increaseBenchmark`를 읽기 전용으로 확인했다.

사용자가 이번 구현 중 **A·B 모두 확정**했다:

- A: 목표 반복 미달·초과 자체로 정상 문맥의 비교 후보를 제외하지 않는다. 실제 비교 대상 선택과 이번 수행의 중량 권고를 분리한다.
- B: 승인 전 수행 후 명시 삭제한 세트는 원본·최초 승인 문맥·삭제 이력에 남긴다. 현재 유효 첫 N개가 정상 승인 문맥이면 삭제 이력만으로 영구 제외하지 않는다. 나중의 승인으로 과거 문맥을 변경하지 않는다.

첫 N개는 삭제되지 않은 수행의 ordinal ASC→UUID ASC다. 선택 후 승인 문맥을 검사하며, PENDING/임시 수행을 건너뛰지 않는다. N개 초과 수행도 보존한다. 최종 계획이 원계획으로 돌아온 3→4→3을 중간 변경 flag로 제외하지 않는다.
과거 후보는 현재 사용자·세대의 FINISHED 중 `endedAt <= current.startedAt`, 같은 루틴/배치/종목/계획 N/반복 범위/정상 승인 중량 문맥이다. AUTO_CLOSED는 제외한다. 첫 N개 합계 DESC→실제 endedAt DESC→UUID ASC로 단일 세션을 선택한다.

정상 종료 권고는 첫 N개 중 하한 미달이 있으면 DECREASE, 없고 전부 상한 이상이면 INCREASE, 나머지는 MAINTAIN이다. 자격 미충족은 UNAVAILABLE이다. 데모의 진행 중 경고와 공식 종료 자격을 혼동하지 않으며, Task 009에서 확정한 첫 N개·추가 수행 보존·중간 변경 flag 제거를 우선 적용했다. 최고 기준과 최근 감량 신호를 새 점수로 합성하지 않는다. 중량 변경 폭·1RM 같은 미정 공식은 추가하지 않았다. 승인 중량은 자동 변경하지 않는다. 남아 있는 평가 정책 차단 사항은 없다.

## 스키마·동시성·보존

V14는 기존 `workout_sessions`에 `ended_at`, `closed_at`, `completion_assessment_snapshot`만 추가한다. 상태별 제약·ACTIVE 만료 조회 인덱스·종료 사실 불변 trigger를 추가한다. 새로운 테이블은 없다. V1~V13 원문은 변경하지 않았다.
최초 종료 AppliedPlan과 평가·종료 시각·편집 epoch/revision/hash/동기화 메타를 DB에서도 동결한다. 현재 version/updatedAt은 미래 정정과 구분할 수 있지만 이번 단계에 종료 후 수정 API는 없다.

기존 users 공유 잠금 → 루틴 배타 잠금(명시 승인 포함 시) → 종목 UUID순 공유 잠금 → 세션 배타 잠금 순서를 유지한다. 자동 종료는 users→session 순서이고 후보 조회에서는 잠그지 않는다. 동일 workoutTransactionManager 안에서 JPA/JDBC를 재사용한다.
로그인·CSRF·소유권·현재 세대·기기/epoch 검증을 유지한다. 정상 만료와 DB 실패를 분리하고, 수행/승인/평가/종료 중 SQL 실패는 전부 롤백한다. 원래 Task 008 종목/설정 조회 및 인증 트랜잭션은 바꾸지 않았다.

## 변경 파일

제품 코드/스키마(12):

- `src/main/resources/db/migration/V14__add_workout_session_closure.sql`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutAssessment.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutExpiryJob.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSession.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionController.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionFailure.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionRepository.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionResponses.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionService.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotData.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRequests.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotService.java`

테스트/fixture(12):

- `src/test/java/com/overload/overload_server/database/V14MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutAssessmentTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutSessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRequestsTests.java`
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`
- `src/test/java/com/overload/overload_server/database/V9MigrationCompatibilityTests.java`
- `src/test/java/com/overload/overload_server/database/V10MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/database/V13MigrationIntegrationTests.java` (역사 검증 target=13 고정)
- `src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java`
- `src/test/java/com/overload/overload_server/persistence/JpaJdbcCompatibilityIntegrationTests.java`
- `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java`
- `src/test/resources/application-integration.yaml` (일반 통합 fixture의 예약 작업 비활성화, 별도 실제 예약 실행 테스트는 활성화)

문서(7): `AGENTS.md`, `README.md`, `docs/engineering/backend.md`, `docs/api/workout-sessions.md`, `docs/tasks/task-009-redesign-v1.0.md`, `docs/tasks/task-008-0-domain-and-api-contract-v0.1.md`, 이 보고서.

## 검증

JDK25·기존 Gradle Wrapper/오프라인 캐시·Testcontainers PostgreSQL18.6을 사용했다. 임시 DB명과 동적 포트(5432/5433 제외)를 확인했다. 개인 DB를 대상으로 Spring Boot를 실행하지 않았다.

```powershell
.\gradlew.bat --offline --no-daemon compileJava compileTestJava
.\gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutAssessmentTests' --tests '*WorkoutSnapshotRequestsTests' --tests '*WorkoutSessionIntegrationTests' --tests '*V14MigrationIntegrationTests'
.\gradlew.bat --offline --no-daemon integrationTest --tests '*WorkoutSessionIntegrationTests.finishRetryPreservesServerTimestampsAcrossPostgresPrecisionRoundTrip'
.\gradlew.bat --offline --no-daemon build
```

최종 `build`: **BUILD SUCCESSFUL (5m 14s)**. 최종 코드의 XML 결과를 집계했다.

| 구분 | 실행 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| 단위/DB 없는 HTTP | 198 | 0 | 0 | 0 |
| 실제 PostgreSQL 통합 | 541 | 0 | 0 | 0 |
| 합계 | **739** | **0** | **0** | **0** |

실제 PostgreSQL 검증에는 다음을 포함한다:

- V13→V14에서 모든 기존 테이블/컬럼값·Flyway 체크섬 보존, 추가 ordinal 수행·승인 사건·정정 이력 보존, 재실행 무변경.
- 최종 snapshot/순차 승인/종료/평가 원자성, 동일 종료 ACK 유실 재전송, 서로 다른 종료 거절, 계획/최초 평가 불변 trigger.
- 현재 유효 첫 N개·추가 수행·범위 미달/초과·계획 원복·PENDING·정상 승인 문맥, 승인 전 수행의 명시 삭제와 새 정상 수행의 평가 분리.
- 과거 종료 cutoff, 합계 우선 선택, 실제 종료 시각/UUID 동률, AUTO_CLOSED 제외. 현재 권고와 최고 비교 대상 선택 분리.
- 정확히24시간의 snapshot/finish/takeover/해결 거절과 자동 종료 커밋, 무변경/GET/takeover 기한 유지, 실제 상태 변경의 기한 갱신.
- 부분 세트·유산소·PENDING 보존, 유산소 전용 명시 종료에서 웨이트 평가 생성 금지.
- 실제 PostgreSQL lock wait를 사용한 finish↔snapshot/takeover/중복 finish/자동 종료 경합, 작업의 SKIP LOCKED와 재시도, 만료 슬롯을 해제하는 새 START.
- 서버 재기동 시 실제 @Scheduled 실행으로 만료 행 처리(동일 격리 DB, 별도 애플리케이션 context 종료).
- 수행 INSERT·승인 사건 INSERT·snapshot UPDATE·최종 closure UPDATE의 실제 SQL 실패 주입과 전체 롤백/동일 요청 재시도, 자동 종료 실패 후 재시도.
- 서버 나노초 시각의 PostgreSQL round trip 이후 START/finish 재조회·동일 ACK 시각 일치.
- 기존 인증·CSRF·소유권·세대·epoch, 루틴 API·START·조회·takeover·snapshot·승인 해결 전체 회귀.

검증 중 발견한 문제는 snapshot 함수 재사용 과정의 guard 누락, 만료 경합 테스트의 다른 임시 사용자 후보 선택, 서버 메타 시각의 PostgreSQL 정밀도 차이다. guard를 복구하고 격리 경합 대상을 고정했으며, 실제 발생 시각을 건드리지 않고 서버 메타 정밀도를 맞췄다. 관련 회귀 검증을 추가했다.
최종 `git diff --check` 및 새 파일 공백 검증을 통과했다. 테스트용 애플리케이션/Gradle 프로세스와 Testcontainers가 종료된 것을 확인했다. 시작 전의 사용자 컨테이너5개만 남아 있으며 임의로 종료하지 않았다. 이번 범위의 미실행 필수 검증이나 미해결 실패는 없다.

## 남은 작업·Git

종료 후 수행 정정/누락 복구·현재 평가 재계산·종료 후 승인 해결·Frontend/IndexedDB는 후속 범위다. 자동 종료 후 미전송 원본을 자동 병합하거나 운동을 재개하지 않는다.
기존 V13의 운동 중 정정/삭제 이력만 재사용하고 범용 이벤트·Outbox·명령·영수증/feed를 만들지 않았다. 새 주요 의존성이나 미확정 권고 공식은 없다. 추가 승인 대기는 없다.
Backend HEAD는 기준 커밋 그대로이며 변경31개(추적 수정24, 새 파일7)는 모두 미스테이징이다. 커밋/Push하지 않았다. Frontend HEAD `642dcaa2a883ffe8e4b4666ee2603c54de086737`, 변경 없음.
