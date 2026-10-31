# Task 009 — 운동 세션 2단계 구현 보고서

상태: 구현·최종 전체 검증 완료. 사용자 검토 전 커밋/Push하지 않는다.
기준 HEAD: `2e3e6ccd6cfd5dc744ad3f70ed2b5f264ded588f` (`main`).
대상: Backend만. 개인 `overload_dev`에 접속하거나 V12/V13을 적용하지 않았다.

## 구현과 API 계약

정확한 JSON·필수/null 필드·숫자 범위·오류 코드는 [운동 세션 계약](../api/workout-sessions.md)을 따른다.

- `PUT /api/v1/workout-sessions/{id}/snapshot`: 전체 현재 계획과 완료/확정 수행, 명시 삭제, 승인 요청을 원자 저장한다.
- `POST /api/v1/workout-sessions/{id}/approval-resolutions`: KEEP_CURRENT 또는 현재 시점의 새 REAPPROVE만 지원한다.
- 기존 START/상세/ACTIVE/takeover는 현재 snapshot, 전체 보존 승인 요청/결과, 최초 수행 승인 문맥을 반환한다.
- 높은 revision, 동일 revision 동일 정규 요청 ACK, 역전/다른 본문 거절을 구현했다. 무변경 snapshot과 승인 해결은 운동 동기화 기한을 연장하지 않는다.
- 요청 누락은 수행 삭제가 아니다. 완료 종목 제외는 수행 전부를 명시 삭제해야 가능하다. 삭제 UUID 재등장은 차단하며 허용 정정과 삭제의 전후 이력을 저장한다.
- 같은 업로드의 승인 순서를 유지한다. ACK 유실 후 이어지는 승인도 자체 version 연결만으로 설명되는 증가일 때 정상 처리한다. 외부 변경이 끼면 승인만 PENDING으로 남기고 수행은 저장한다.
- KEEP_CURRENT 결과는 NOT_APPLIED/KEPT_CURRENT/해결 시각으로 조회한다. 영구 전송 영수증은 없다.
- 재승인은 새 UUID·새 시각의 승인이다. 과거 사건과 기존 수행의 최초 승인 문맥을 소급 변경하지 않는다.

## 최소 영속성 구조와 경계

V13은 기존 세션에 현재 AppliedPlan JSONB와 마지막 snapshot ACK 메타를 추가하고, `session_exercises`, `performed_sets`, `cardio_results`, `session_weight_approval_requests`, `record_change_events`를 추가한다.
V11 승인 사건에는 WORKOUT 출처와 세션/요청 연결만 추가한다. 원래 사건 값은 유지한다.
InitialPlan 원본은 계속 불변 JSONB 하나다. registry는 ID/FK와 신규 종목명만 저장하며 최초 목표·승인값·순서의 사본이 아니다. 실제 수행은 관계형 저장한다.

사용자 공유 잠금 → 원본 루틴 배타 잠금(승인 시) → 종목 UUID순 공유 잠금 → 세션 배타 잠금 → 하위 저장 순서다.
단일 workoutTransactionManager 안에서 JPA와 JDBC를 사용하며 인증/Spring Session의 기존 트랜잭션 경계는 바꾸지 않는다.
편집 기기/epoch/세대/소유권 검증은 ACK와 해결 재확인에서도 적용한다. SQL 오류는 전체 롤백하고 PENDING으로 분류하지 않는다.

## 변경 파일

제품 코드/스키마:

- `src/main/resources/db/migration/V13__add_workout_snapshots.sql`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotData.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotEntities.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRepository.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRequests.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotService.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSession.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionController.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionFailure.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionResponses.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionService.java`
- `src/main/java/com/overload/overload_server/routine/ProgressChangeEvent.java`
- `src/main/java/com/overload/overload_server/routine/RoutineRepository.java`
- `src/main/java/com/overload/overload_server/routine/RoutineRequests.java` (기존 CardioPlan 검증 재사용, 루틴 HTTP 계약 유지)

테스트/fixture:

- `src/test/java/com/overload/overload_server/database/V13MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRequestsTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutSessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/database/V12MigrationIntegrationTests.java` (기존 역사 검증을 V12에 고정)
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`
- `src/test/java/com/overload/overload_server/database/V9MigrationCompatibilityTests.java`
- `src/test/java/com/overload/overload_server/database/V10MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java`
- `src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java`
- `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/persistence/JpaJdbcCompatibilityIntegrationTests.java`
- `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java`
- `src/test/java/com/overload/testfixture/MixedPersistenceAssertions.java`
- `src/test/java/com/overload/testfixture/RequestIdHttpTests.java`

문서: `AGENTS.md`, `README.md`, `docs/engineering/backend.md`, `docs/api/workout-sessions.md`, `docs/tasks/task-009-redesign-v1.0.md`, 이 보고서.

## 검증

JDK25/Gradle Wrapper, 기존 오프라인 캐시와 Docker Testcontainers PostgreSQL18.6만 사용한다. 개인 개발 DB로 앱을 실행하지 않는다.
테스트 DB 이름과 동적 포트(5432/5433 제외)를 확인하고 실제 Spring MVC 로그인·CSRF 및 JPA/JDBC 트랜잭션으로 검증한다.

실행 명령:

```powershell
.\gradlew.bat --offline --no-daemon compileJava compileTestJava
.\gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutSnapshotRequestsTests' --tests '*WorkoutSessionIntegrationTests' --tests '*V13MigrationIntegrationTests'
.\gradlew.bat --offline --no-daemon build
```

최종 `build`: **BUILD SUCCESSFUL (5m 7s)**. XML 결과를 집계했다.

| 구분 | 실행 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| 단위/DB 없는 HTTP | 187 | 0 | 0 | 0 |
| 실제 PostgreSQL 통합 | 510 | 0 | 0 | 0 |
| 합계 | **697** | **0** | **0** | **0** |

실제 트랜잭션에서 검증한 주요 경계:

- V12→V13에서 기존 테이블의 모든 기존 컬럼값·Flyway 이력/체크섬 보존, 현재 계획/registry backfill, 재마이그레이션 무변경.
- 서로 다른/동일 본문의 동시 snapshot, 동일 revision 다른 본문·낮은 revision·순번 충돌, 배열/속성 정규화와 중복 ACK.
- 무변경 높은 revision의 동기화 시각 유지, takeover와 snapshot의 실제 PostgreSQL lock wait·구 epoch 거절.
- 기존 수행 누락 차단, 완료 종목 제외 차단, 명시 삭제·삭제 후 새 UUID 교체·삭제 UUID 부활 차단, 세트/유산소 정정 이력과 불변 문맥.
- 추가 완료 세트, 유산소 완료/skip와 타이머 시각 검증, 서버 시각 대체 없는 CLOCK_CONTEXT_INVALID.
- 동일 업로드 연속 승인, 변화 후 원래 중량으로 돌아오는 승인, 무변경 승인, 앞선 ACK 유실 후 자체 version 연결.
- 루틴 외부 수정/외부 승인/삭제/출처 없는 신규 종목에서 수행 보존·승인 PENDING, 기존 사건 유지.
- 루틴 편집과 승인 저장의 실제 lock wait 및 기존 버전 충돌 응답.
- KEEP_CURRENT 응답 유실 후 상세 확인/재확인, 새 REAPPROVE 사건·기존 수행 문맥 불변, 해결의 CSRF/소유권/세대/기기/기대 버전/전체 PENDING 선택 검증.
- 승인 사건·수행·정정 이력 INSERT 및 세션 UPDATE 각 단계에서 실제 PostgreSQL division-by-zero를 주입하고 기존 수행·새 승인·사건·이력·계획/메타 전체 롤백과 동일 요청 재시도 확인.
- 기존 인증·CSRF·루틴·온라인 쓰기·Task 008 영속성·START·조회·takeover 전체 회귀.

`git diff --check`와 신규 파일 공백 검사 통과. V1~V12 tracked diff 없음. 실행한 테스트 앱·Gradle worker/daemon/래퍼 Java 프로세스가 남지 않았고 Testcontainers도 정리됐다. 기존 사용자 컨테이너5개는 유지했다.
Frontend 테스트/빌드는 실행하지 않았다(Frontend 수정 없음). 개인 DB의 상태를 확인하기 위한 접속도 하지 않았다.

집중 검증 최초 실패3건은 새 registry FK에 맞춘 기존 초기화 fixture 정리 누락, 루틴 오류 코드 기대값, PostgreSQL 세션 시간대에 의존한 문자열 비교였다. FK 하위 행부터 정리하고 기존 오류 코드 및 Instant 비교로 수정했다. 제품 정책을 완화하거나 테스트를 비활성화하지 않았다.

## 남은 작업과 Git

finish·24시간 자동 종료(수신 시 만료 검사 포함)·최초 명시 종료 계획/평가 봉인·공식 비교·종료 후 정정/복구·Frontend/IndexedDB 연결은 구현하지 않았다.
현재 API는 ACTIVE 저장만 제공하며, 아직 24시간 경과로 ACTIVE가 해제되지 않는다.
새 기기 복원 시 서버의 수행/삭제 ID/승인 사실을 기준으로 로컬 초안과 미전송 데이터를 별도로 보존해야 한다. 자동 병합이나 tombstone 복원은 제공하지 않는다.

V1~V12 원문 변경 없음. Frontend clean, 수정 없음. 개인 DB 변경 없음. 새 의존성·WorkoutCommand·IntentStamp·receipt/feed·Outbox·필드 병합 엔진 없음.
Backend는 기존 HEAD/main 그대로이며 **수정25개 + 신규9개 = 34개**, staged0개다. Frontend HEAD는 `642dcaa2a883ffe8e4b4666ee2603c54de086737`, clean이다.
구현은 승인된 범위에서 완료했다. 커밋/Push/스테이징하지 않았으며 사용자 검토 대기다. 미해결 테스트 실패나 구현 차단 사항은 없다.
