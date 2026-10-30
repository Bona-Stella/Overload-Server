# Task 009 — 1차 구조 정리 보고서

- 작업일: 2026-10-30 (Asia/Seoul).
- 기준: [Task 009 현행 설계 v1.0](task-009-redesign-v1.0.md), 사용자의 1차 구조 정리 승인.
- 범위: 폐기 구조 제거와 기존 기능 회귀 검증. 신규 쓰기·세션 API·IndexedDB·DB 제거 migration 구현은 포함하지 않는다.
- 상태: **1차 코드 구조 정리·전체 로컬 회귀·자체 리뷰 완료 / 미커밋**. 실제 DB 제거는 미실행이며 후속 승인/적용 단계로 남는다.

## 1. 시작 상태와 보호 범위

| 저장소 | 시작 브랜치 / HEAD | 작업 트리 |
|---|---|---|
| Backend | main / 227640ebe24b75a100125afeedad395a1fbb7479 | 수정6·미추적10·staged0, 감사 당시 009-1B와 동일 |
| Frontend | main / 28257838737dda0b24fd3d356cadd8c7ae757f36 | clean |

Git 상태·diff·실제 파일·참조를 다시 확인했다. 추가 사용자 변경은 발견하지 않았다. 미커밋 1B도 이번 명시적 승인 범위에서 파일별로 정리했다. reset/clean·commit/push를 하지 않았다. 개발·운영 DB 및 브라우저 저장 데이터는 조회/수정/삭제하지 않았다.

## 2. 실제 삭제 목록 — 26개 파일

Backend 루트 기준이다. 미추적 파일도 아래 삭제 목록에 포함되므로 최종 git diff의 삭제 개수와 다를 수 있다.

### 제품 코드 16개

범용 명령·codec·stamp·영수증/feed와 그 전용 오류/DTO를 제거했다. Counter·WeightKg·CommandValues·WorkoutWriteGuard·WriteContractViolation도 남은 제품 호출부가 없으므로 삭제했다. 다른 패키지에 복사하거나 호환 wrapper를 만들지 않았다.

- `src/main/java/com/overload/overload_server/workoutcommand/CommandEnvelope.java`
- `src/main/java/com/overload/overload_server/workoutcommand/CommandJson.java`
- `src/main/java/com/overload/overload_server/workoutcommand/CommandValidationFailure.java`
- `src/main/java/com/overload/overload_server/workoutcommand/CommandValues.java`
- `src/main/java/com/overload/overload_server/workoutcommand/Counter.java`
- `src/main/java/com/overload/overload_server/workoutcommand/IntentStamp.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WeightKg.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandCodec.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandController.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandErrors.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandFailure.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandResponses.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandService.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutCommandStore.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WorkoutWriteGuard.java`
- `src/main/java/com/overload/overload_server/workoutcommand/WriteContractViolation.java`

### 기존 테스트 파일 6개

명령 DTO·canonical fingerprint·필드 의도·영수증/feed 실행만을 위한 테스트는 삭제했다. 마지막 업그레이드 테스트의 V8→V9 checksum·기존 데이터·seed 보존 검증은 실제로 남는 Flyway 이력을 보호하므로 database/V9MigrationCompatibilityTests.java로 분리했다. 명령 런타임을 다른 패키지로 옮긴 것이 아니다.

- `src/test/java/com/overload/overload_server/workoutcommand/CommandValueTests.java`
- `src/test/java/com/overload/overload_server/workoutcommand/WorkoutCommandCodecTests.java`
- `src/test/java/com/overload/overload_server/workoutcommand/WorkoutCommandHttpIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutcommand/WorkoutCommandIntentTests.java`
- `src/test/java/com/overload/overload_server/workoutcommand/WorkoutWriteFoundationIntegrationTests.java`
- `src/test/java/com/overload/overload_server/workoutcommand/WorkoutWriteUpgradeIntegrationTests.java`

### 구 설계·보고서 4개

현재 개발 경로에서 제거했다. 별도 유산 문서 폴더는 만들지 않았다. 과거 1A/1B의 구현 완료·1C 로드맵을 현행 요구사항으로 남기지 않는다.

- `docs/tasks/task-009-0-write-domain-api-design-v0.1.md`
- `docs/tasks/task-009-0-write-domain-api-design-v0.2.md`
- `docs/tasks/task-009-1a-write-foundation-report.md`
- `docs/tasks/task-009-1b-write-api-report.md`

## 3. 수정 및 추가 파일

| 저장소 / 파일 | 변경 |
|---|---|
| Backend exercise/ExerciseDefinition.java | intentStamps 필드·JSONB annotation/import·getter 및 구 명령 문맥 주석 제거 |
| Backend workoutsettings/UserWorkoutSettings.java | intentStamps 필드·JSONB annotation/import·getter 제거 |
| Backend OverloadServerApplicationTests.java | 시작 시 있던 명령 서비스 mock 제거. 기존 smoke 검사 유지; HEAD 대비 순변경 없음 |
| Backend testfixture/RequestIdHttpTests.java | 시작 시 있던 명령 서비스 mock 제거. 기존 HTTP/Request ID 검사 유지; HEAD 대비 순변경 없음 |
| Backend database/V9MigrationCompatibilityTests.java (추가) | 현재 남는 V9 업그레이드·기존 user/identity/UUID/name/설정/시각·seed 보존 검사; stamp getter 의존 제거 |
| 양쪽 AGENTS.md | Task 009 v1.0 필수 문서와 우선순위·현재 미구현 경계 명시 |
| 양쪽 README.md | 구 쓰기 제공/Outbox 로드맵 제거, 현행 설계·현재 조회 기능·신규 쓰기 미구현 안내 |
| Backend docs/engineering/backend.md | 일반 온라인 관리·세션 snapshot/epoch/revision·24시간 보존 및 DB 후속 정리 기준 |
| Frontend docs/engineering/frontend.md | 구 큐·필드 충돌/선택 복구 지침을 진행 운동 로컬 보호·최신 스냅샷 전송 기준으로 교체 |
| Backend task-006-integrated-design-v0.3.1.md, task-006-model-mapping-report.md | 상단에 구 동기화 내용 대체됨 명시. 유지되는 도메인·인증/과거 분석 근거 보존 |
| Backend task-008-0-domain-and-api-contract-v0.1.md, task-008-0-jpa-persistence-design.md | 상단에 미구현 구 쓰기 제안 폐기 및 기존 조회/혼합 영속성 경계 유지 명시 |
| Backend task-008-0d-common-api-contract-v0.1.md | 폐기된 미래 sync cursor의 410 약속 제거; 기존 공통 HTTP 계약 유지 |
| Backend task-009-redesign-v1.0.md (추가) | 사용자 제공 Downloads 문서를 원문 그대로 현행 설계로 등록 |
| Backend 이 보고서 (추가) | 파일별 정리·DB 후속 절차·검증·다음 단계 기록 |

위 Java 경로의 공통 기준은 src/main/java/com/overload/overload_server 또는 src/test/java/com/overload/overload_server이다. 문서 경로는 docs/tasks 기준이다. 신규 의존성·설정·API·UI 변경은 없다. 프론트 제품 코드/테스트에는 구 명령 호출·stamp·receipt/feed 구현이 없어 삭제할 관련 런타임이 없었다. 개발 전용 /demo 및 /auth-preview와 기존 데이터는 유지했다.

## 4. 유지한 코드와 필요성

- 인증·Google/이메일·Spring Session·CSRF·Request ID: 현재 정상 로그인·세션·보안 경로다. 제품 코드와 기존 검사를 유지했다.
- 계정 provisioning·20종목/휴식180·120·90 seed·기존 UUID·dataGeneration 및 복합 FK: 현재 계정 격리와 가입/재로그인 데이터 무결성에 필요하다.
- Task 008 GET exercises/workout-settings, WorkoutOwnerRepository, JPA Repository, 읽기 DTO: 현재 실제 조회·인증 사용자 범위에 사용된다. 문자열 generation의 기존 직렬화와 검사도 유지했다.
- JDBC primary/Spring Session 관리자와 workoutTransactionManager: 현재 조회의 JPA/JDBC 동일 연결·rollback 경계다. 범용 쓰기 guard를 남길 필요는 없어 그 클래스는 삭제했다.
- 두 Entity의 @Version, 기존 version 매핑과 종목 deletedAt: 폐기 stamp와 분리된 현행 V9 컬럼 매핑이다. @Version은 Hibernate의 변경 감지/낙관적 잠금 기능이며 명령 엔진을 요구하지 않는다. 물리 컬럼과 값은 손대지 않았다. 쓰기 API·논리 삭제 기능이 생긴 것으로 표현하지 않는다.
- V1~V9와 기존 migration/table 목록 단언: 여전히 실제 생성되는 DB 이력·스키마이므로 정확히 검사한다. 이를 삭제하면 현재 DB와 검증이 어긋난다. 이는 폐기 DB 구조의 영구 유지 결정이 아니다.

## 5. DB 후속 제거 절차 — 이번에는 작성·적용하지 않음

### 확인된 사실과 미확인 상태

- 저장소 최신 migration은 V9. V9 원문과 checksum은 보존한다.
- 애플리케이션의 stamp·영수증/feed 읽기/쓰기 경로는 제거했다. JPA는 추가 미매핑 컬럼이 있는 기존 V9 스키마를 사용할 수 있다.
- 실제 개발/공유/운영 DB의 Flyway 적용 이력·폐기 테이블 행 수·보존 필요성·추가 view/trigger/외부 FK는 이번에 확인하지 않았다. 격리 테스트 DB의 결과를 실제 DB 상태로 취급하지 않는다.
- 신규 번호의 파괴적 SQL을 db/migration에 미리 넣지 않았다. 앱/테스트 시작 시 무심코 실제 DB에 적용되는 것을 막기 위한 적용 보류이며 최종 제거 대상은 유지된다.

### 실행 전 확인 및 승인

1. DB 대상·환경·스키마를 특정하고 flyway_schema_history의 version/script/checksum/success와 실제 컬럼·행 수를 읽기 전용으로 확인한다. V9 적용 여부를 추측하지 않는다.
2. pg_constraint/pg_depend/pg_views/pg_trigger와 외부 소비자를 조사한다. 제거 대상은 두 intent_stamps 컬럼과 workout_sync_state·sync_operations·workout_change_batches뿐이다. 기존 user/identity/세션/종목/설정 데이터는 제거 대상이 아니다.
3. 영수증/거절 원문 등에 보존할 데이터가 있는지 판단하고 필요한 백업·복구 검증·접근 통제를 준비한다. 실제 운동 사실/승인/정정 이력이 별도로 있으면 도메인 데이터와 구분해 보존한다.
4. 대상별 데이터 손실 범위·백업·배포 순서·복구 계획을 제시해 파괴적 migration 적용 승인을 받는다. 구 명령 런타임을 사용하는 인스턴스/작업을 먼저 중지한다.
5. 그 시점 최신 번호 다음의 Flyway migration을 작성한다(현재 기준 다음 번호는 V10). 이미 적용된 V9 파일이나 flyway_schema_history를 변경/삭제/repair하지 않는다.

### 현재 V9에서 확인된 FK와 제거 순서

- sync_operations의 자기 참조 origin_operation_id FK.
- workout_change_batches → workout_sync_state FK.
- workout_change_batches → sync_operations의 deferred 복합 FK.
- sync_operations_change_batch_fk: sync_operations → workout_change_batches의 역방향 deferred FK. 먼저 제거해야 테이블 삭제의 순환 의존성이 풀린다.
- 각 테이블의 users(user_id,data_generation) FK는 부모 users를 삭제하지 않고 자식 테이블 제거로 사라진다.

아래는 **미실행 절차 예시**다. 실제 추가 의존성/보존 확인 후 최종 migration으로 확정하며 CASCADE로 미확인 의존성을 지우지 않는다. Flyway의 PostgreSQL 단일 트랜잭션으로 실행하고 별도 BEGIN/COMMIT을 섞지 않는다.

```sql
ALTER TABLE sync_operations DROP CONSTRAINT sync_operations_change_batch_fk;
DROP TABLE workout_change_batches;
DROP TABLE sync_operations;
DROP TABLE workout_sync_state;
ALTER TABLE exercise_definitions DROP COLUMN intent_stamps;
ALTER TABLE user_workout_settings DROP COLUMN intent_stamps;
```

version·exercise_definitions.deleted_at 및 기존 이름/UUID/휴식값/시각은 이 제거 SQL에 포함하지 않는다. IF EXISTS로 예상과 다른 DB 상태를 숨기지 않는다. 트랜잭션 실패 시 전체 rollback하며, 성공 후 구 앱으로 코드만 되돌려 복구했다고 간주하지 않는다. 복구는 사전 검증한 백업/스키마 복원 또는 검토된 전진 수정으로 한다.

### 후속 migration 완료 기준

- 빈 DB V1→신규 버전, V8→V9→신규 버전, 데이터가 있는 기존 V9→신규 버전 모두 검증한다.
- 기존 V1~V9 checksum, 사용자/identity/직렬화 Session/종목 UUID·이름·설정·시각 보존과 재실행0을 검사한다.
- 폐기 컬럼·테이블 부재, Hibernate validate, 가입/재로그인·Google/이메일·CSRF·Request ID·Task 008 조회 전체 회귀를 확인한다.
- 현재 테스트의 V9/table 목록 단언을 새 실제 스키마로 갱신한다. 역사적 V9 대상 검증은 target(9) 등 명시 경계에서만 유지한다.

## 6. 검증 결과

| 검증 명령/검사 | 실제 결과 |
|---|---|
| Backend `gradlew.bat --offline --no-daemon build --rerun-tasks` | **PASS**, 4분36초. compileJava·compileTestJava·test·integrationTest·check·bootJar 포함, 9개 task 실제 실행 |
| Backend JUnit XML 합계 | unit **112** + PostgreSQL integration **386** = **498개**, failures/errors/skipped 모두0 |
| Frontend `npm run lint` | PASS |
| Frontend `npm test` | **312/312 PASS**, failures/cancelled/skipped 모두0 |
| Frontend `npm run build` | PASS, TypeScript 빌드 및 Vite production bundle 생성 |
| 양쪽 diff 공백 검사 | PASS. 커밋 전 신규 파일까지 포함해 재검사했으며 보고서 EOF의 빈 줄을 정리했다. v1.0 원문의 3개 Markdown 하드브레이크는 의도된 줄바꿈으로 별도 확인·보존했다. |
| 소스·테스트 참조, 빌드 class·bootJar 검색 | 구 명령 패키지/클래스·API·stamp/fieldBases 런타임 참조 없음 |
| 수정/신규 문서 Markdown 링크 검사 | 새로 발생한 끊어진 로컬 링크 없음 |
| V9 SHA-256 및 v1.0 원문 비교 | V9 `CB39EB1B6DF35EBF0723877EA5C776919BE5790D673C75C9AB8691147F546E79`로 시작과 같음. 현행 v1.0은 Downloads 원문과 byte 동일 |
| Git HEAD·staged 확인 | 양쪽 HEAD 시작과 같고 staged0. Commit/Push 없음 |

과거 1B의 617개를 현재 결과로 재사용하지 않았다. 구 계약 전용 테스트 119개를 제거하고 실제 V9 업그레이드 검사 1개는 유지한 결과가 498개다. Google/이메일·Session·CSRF·계정 격리·Request ID·Task 008 조회·JPA/JDBC rollback 및 가입/기존 데이터 보존 검사가 포함된다. 인증 외부 서비스는 기존 테스트 대체기를 사용하며 실제 Google/Resend를 호출하지 않았다.

테스트는 기존 Temurin25·Gradle Wrapper·캐시된 의존성·Testcontainers PostgreSQL18.6-alpine의 일회성 DB를 사용했다. Backend 실행 환경의 SPRING_/GOOGLE_/OTP_/RESEND_/POSTGRES_/DATABASE_/OVERLOAD_/AUTH_ 변수를 제거했고 테스트 local overlay를 유지했다. 개발/운영 DB URL을 주입하지 않았다. 전체 로그는 Git 제외 경로 build/task-009-cleanup-build.log, XML은 build/test-results/{test,integrationTest}에 있다.

검증 중 컴파일·테스트·빌드 실패는 없었다. 초기 Docker 조회는 기본 샌드박스의 pipe 권한 제한으로 실패했으나 필요한 권한으로 전체 통합 검증을 정상 실행했다. 기존 unchecked 컴파일·JVM CDS 경고는 빌드 실패가 아니다. 테스트 비활성화·검사 회피·새 의존성 설치는 없다.

전체 실행 후 Java/Gradle worker·daemon과 Frontend test/preview 프로세스가 없고 Docker 실행 목록도 시작 시의 사용자 컨테이너5개뿐임을 확인했다. 새 PostgreSQL/Ryuk가 남지 않았다. 기존 사용자 프로세스·컨테이너를 종료하지 않았다. 실제 iPhone/설치형 PWA와 신규 쓰기/동기화 기능은 이번 범위에서 실행·검증하지 않았다.

## 7. 잔여 참조와 다음 단계

- Backend/Frontend src·tests에서 구 명령 패키지·API·codec/stamp·fieldBases 런타임 참조를 제거했고 최종 class·bootJar에도 없음 확인.
- 잔여 SQL/table 명칭은 보존한 V9 및 실제 스키마 검증, 이 DB 후속 제거 절차와 v1.0의 폐기 설명에 남는다.
- Task 006/008 과거 문서 본문의 구 쓰기 제안은 상단에서 명시적으로 대체됨을 표시했다. 기존 도메인·인증·조회·혼용 검증 근거를 잃지 않도록 과거 사실을 재작성하지 않았다. 현재 개발 지침에서 구 제안을 구현하도록 안내하지 않는다.
- 기존 1B 종목 생성·이름 변경·휴식 설정 변경은 **새 일반 API 구현 전까지 미구현**이다. 자동 충돌 해결 1C를 이어서 구현하지 않는다.
- 다음 권장 범위: 실제 DB 상태/보존·후속 제거 migration의 승인 및 업그레이드 검증, 이후 별도 단계의 일반 온라인 종목/설정 쓰기 API. 루틴·진행 세션·IndexedDB는 후속 단계로 분리한다.
- 코드·문서 정리는 사용자 승인됨. 실제 DB 제거는 미승인·미실행. Commit/Push 하지 않음.
