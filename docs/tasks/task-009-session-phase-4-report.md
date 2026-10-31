# Task 009 — 종료 후 기록 정정·복구 4단계 구현 보고서

상태: 구현·전체 회귀 검증·자체 리뷰 완료. 사용자 검토 전 커밋/Push하지 않는다.
기준 HEAD: `538a0b429340c4e3e39e4476850074c619048a44` (`main`, 3단계 커밋).
대상: Backend만. 개인 `overload_dev`에 접속하거나 V12~V15를 적용하지 않았다.

## 구현 및 계약

정확한 JSON·숫자 타입·필수/null 필드·오류·재조회 계약은 [세션 API](../api/workout-sessions.md)의 V15 절을 따른다.

| API | 실제 동작 |
|---|---|
| PATCH `/{id}/records` | 기존 웨이트 중량/반복, 완료 유산소 activeMs/정정 여부/거리/BPM 정정 및 명시 논리 삭제 |
| POST `/{id}/recoveries` | confirm=true로 선택한 미저장 완료 수행만 기존 세션 종목에 추가 |
| DELETE `/{id}` | 전체 세션 deletedAt 및 SESSION/DELETED 사건 기록, 하위 사실 보존 |
| POST `/{id}/closed-approval-resolutions` | 종료 후 KEEP_CURRENT 또는 새 명시 REAPPROVE |
| GET `/{id}/assessment` | 최초 평가와 현재 평가 분리, 현재 유효 과거 후보로 조회 시 재계산 |
| GET `/{id}/record-change-events` | 본인 세션의 운동 중/종료 후 변경 이력 조회 |

모든 경로는 `/api/v1/workout-sessions` 아래다. 종료 후 쓰기는 dataGeneration/expectedVersion을 검증하며 ACTIVE writerEpoch/localRevision DTO와 분리했다. 선택되지 않은 수행은 수정하지 않는다. 무변경은 version/이력을 추가하지 않고 실제 변경 트랜잭션당 version을 정확히 한 번 증가시킨다. stale expectedVersion은 동일 내용이라도 거절하고 조회로 확인한다.

## 확정 정책과 보존 경계

- InitialPlan·봉인 AppliedPlan·최초 평가/근거·실제 종료 시각·완료 UUID/종목/ordinal/최초 완료 시각·최초 수행 승인 문맥을 유지한다. 사후 쓰기는 마지막 snapshot hash/시각·운동 동기화 시각·epoch/revision을 갱신하지 않는다.
- 확인 가능한 InitialPlan·ACTIVE 당시 APPLIED/PENDING 요청과 TODAY_ONLY 문맥을 유지한다. 실제 중량과 보존 승인값의 일치 여부는 현재 평가에서 계속 검사한다. 확인할 수 없는 주장은 UNVERIFIED로 저장하고 첫 N개 평가에서 건너뛰거나 정상 승인으로 취급하지 않는다.
- 원래 주장과 관련 UUID·source 기기/epoch/revision은 RECOVERED 사건에 보존한다. 원본 문맥은 인증 증명이 아니며 구 epoch를 최신 epoch로 바꾸지 않는다. 중간 takeover 전체 이력이 존재한다고 주장하지 않는다.
- AUTO_CLOSED는 closedAt 뒤 실제 완료도 구조적 시각/종목/타이머 검증 후 명시 복구한다. startedAt 이전·승인 전 수행 등 명백한 모순은 CLOCK_CONTEXT_INVALID다. 상태 재개·FINISHED 전환·사용자 endedAt/최초 평가 생성은 없다. 공식 후보에서도 계속 제외한다.
- 원래 FINISH는 최초 version/평가/시각/식별을 유지한다. currentVersion·현재 approvalResults·deletedAt을 함께 반환하여 이후 편집·해결·전체 삭제 뒤에도 원래 요청의 중복 확인을 보장한다. 조회 확인이 삭제를 해제하거나 수행을 재적용하지 않는다.
- 삭제된 수행은 tombstone과 before 이력을 유지한다. 복구 입력의 동일 UUID/동일 사실은 alreadyPresent로 확인하며, 다른 값·ordinal/활동 점유·tombstone·다른 종목 문맥은 자동 합병/교체/재번호 없이 거절한다.
- 전체 삭제는 parent tombstone이다. 하위 수행/승인 사건을 연쇄 삭제하지 않는다. 상세는 소유자에게 삭제 표식과 보존 사실을 제공하고 현재 평가/비교 후보와 쓰기에서는 제외한다.

## 최소 V15와 기존 코드 재사용

새 테이블이나 의존성은 없다. V15는 세션 deleted_at, 승인 요청 created_after_closure, 수행의 UNVERIFIED 제약, 기존 record_change_events의 SESSION/RECOVERED·nullable before·선택적 change_context와 세션 이력 인덱스를 추가한다. V1~V14는 수정하지 않았다.
created_after_closure는 실제로 필요한 승인 출처 구분이다. 종료 후 해결에서 생성한 새 승인 요청을 복구 시 과거 정상 승인 근거로 오인하지 않도록 한다. 기존 요청은 모두 ACTIVE 때 생성됐으므로 false로 초기화하며 기존 컬럼값은 보존한다.
현재 평가는 기존 순수 함수/규칙 버전을 재사용한다. 현재 평가 저장 컬럼·별도 캐시·연쇄 재계산 엔진은 없다. 다른 과거 기록의 정정/삭제는 다음 읽기에 반영되지만 최초 평가를 변경하지 않는다. AUTO_CLOSED/삭제 기록의 현재 평가는 EXCLUDED/UNAVAILABLE이다.
V13 수치/완료 수행 파서·유산소 타이머 문맥 검사·승인 해결 코어·기존 엔티티 적용 함수를 재사용했다. 종료 후 전체 snapshot 저장 함수를 호출하거나 봉인 계획을 교체하지 않는다.

## 트랜잭션·보안

기존 로그인·CSRF·principal 소유권·현재 세대 검증과 no-store/X-Request-ID를 유지한다. 다른 소유자의 부모/자식 기록은 변경하지 못한다. 새 기기 비밀 토큰·인증 체계는 없다.
일반 정정/복구/삭제는 users FOR SHARE→session FOR UPDATE, 재승인은 users→routine FOR UPDATE→session 순이다. 기존 ACTIVE/루틴 경로의 종목 UUID순 잠금도 유지한다. 세션 잠금 뒤 루틴을 잠그지 않는다.
같은 workoutTransactionManager에서 JPA/JDBC의 수행·tombstone·이력·승인 요청/사건·부모 version을 원자 처리한다. 입력 충돌은 자동 병합하지 않으며 실제 DB 실패는 전체 롤백한다. 인증/Spring Session의 기존 독립 트랜잭션 경계는 바꾸지 않았다.

## 변경 파일

제품 코드/스키마(15):

- `src/main/resources/db/migration/V15__add_closed_workout_record_edits.sql`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutRecordData.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutRecordRequests.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutRecordService.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSession.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionController.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionFailure.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionRepository.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionResponses.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionService.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotData.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotEntities.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRepository.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotRequests.java`
- `src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotService.java`

테스트/fixture(12):

- `src/test/java/com/overload/overload_server/database/V15MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutRecordRequestsTests.java`
- `src/test/java/com/overload/overload_server/workoutsession/WorkoutSessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/database/V14MigrationIntegrationTests.java` (역사 검증 target=14 고정)
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`
- `src/test/java/com/overload/overload_server/database/V9MigrationCompatibilityTests.java`
- `src/test/java/com/overload/overload_server/database/V10MigrationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java`
- `src/test/java/com/overload/overload_server/persistence/JpaJdbcCompatibilityIntegrationTests.java`
- `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java`
- `src/test/java/com/overload/testfixture/RequestIdHttpTests.java`

문서(6): `AGENTS.md`, `README.md`, `docs/engineering/backend.md`, `docs/api/workout-sessions.md`, `docs/tasks/task-009-redesign-v1.0.md`, 이 보고서.

## 검증

JDK25/Gradle Wrapper의 기존 오프라인 캐시와 Docker Testcontainers PostgreSQL18.6만 사용한다. 테스트의 임시 DB명과 동적 포트(5432/5433 제외)를 확인하며, 개인 DB로 Spring Boot를 실행하지 않는다.

```powershell
.\gradlew.bat --offline --no-daemon compileJava compileTestJava
.\gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutRecordRequestsTests' --tests '*WorkoutSnapshotRequestsTests' --tests '*WorkoutSessionIntegrationTests' --tests '*V15MigrationIntegrationTests'
.\gradlew.bat --offline --no-daemon build
```

최종 `build`: **BUILD SUCCESSFUL (6m 18s)**. 최종 코드의 XML 결과를 집계했다.

| 구분 | 실행 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| 단위/DB 없는 HTTP | 207 | 0 | 0 | 0 |
| 실제 PostgreSQL 통합 | 576 | 0 | 0 | 0 |
| 합계 | **783** | **0** | **0** | **0** |

실제 PostgreSQL에서 FINISHED/AUTO_CLOSED 정정·삭제·복구, 서버 자동 종료 뒤의 실제 완료 복구, 확인된 승인/UNVERIFIED, 현재 중량 정정 자격/권고, 최초 평가·계획·메타 보존, tombstone/UUID/ordinal/활동 충돌의 전체 롤백을 검증했다.
서로 다른 두 인증 세션의 같은 expectedVersion 편집과 재승인↔루틴 편집을 실제 PostgreSQL lock wait로 검증했다. 수행 UPDATE/INSERT·이력 INSERT·세션 UPDATE·승인 사건 INSERT의 실제 SQL 오류 주입으로 전체 롤백을 확인했다.
V14의 ACTIVE/FINISHED/AUTO_CLOSED와 모든 테이블의 기존 컬럼/값·Flyway 이력/체크섬을 V15 이후와 비교했다. 최초 평가와 하위 수행·승인 사실을 보존하고 재마이그레이션은 무변경이다. 기존 Backend 인증·CSRF·루틴·snapshot·FINISH·자동 종료 회귀도 통과했다.

초기 컴파일에서 Java 제네릭 추론 두 곳을 명시했고, DB 없는 HTTP/context 테스트는 새 RecordService도 기존 패턴으로 mock하도록 보완했다. 새 DELETE API 때문에 기존의 '지원하지 않는 DELETE=405' 테스트는 여전히 지원하지 않는 PUT으로 바꿨으며 새 DELETE의 상태/입력/보안 검증은 별도로 추가했다. 테스트 비활성화나 요구사항 완화는 없다.
최종 공백 검사와 기존 migration 원문 무변경 검사를 통과했다. 직접 실행한 Java/Gradle·테스트 애플리케이션과 Testcontainers/정리 컨테이너의 종료를 확인했다. 시작 전 사용자 컨테이너5개만 남아 있으며 임의 종료하지 않았다. 이번 범위의 미실행 필수 검증·미해결 실패·추가 승인 대기는 없다.

## 남은 작업·Git

Frontend/IndexedDB 저장·원본 비교/명시 선택 UI·설치형 PWA 연결은 후속 범위다. 서버의 명시 복구 API와 로컬 원본 보존 계약만 제공하며 자동 병합·재태깅·tombstone 해제·종료 세션 재개는 구현하지 않는다. 표준 운동 DB·근육 통계·디로딩 작업도 하지 않았다.
Backend HEAD는 기준 커밋 그대로이며 변경33개(추적 수정26, 새 파일7)는 모두 미스테이징이다. 사용자 검토 전 커밋/Push하지 않았다. Frontend HEAD `642dcaa2a883ffe8e4b4666ee2603c54de086737`, 변경 없음.
