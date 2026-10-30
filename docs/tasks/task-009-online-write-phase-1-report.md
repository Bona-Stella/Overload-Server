# Task 009 — 일반 온라인 쓰기 API 1차 구현

- 작업일: 2026-10-31 (Asia/Seoul).
- 기준: Task009 v1.0, Task006 종목 UUID/동명이종목·계정/세대 규칙, Task008 GET 계약, 현재 V10 Entity/Repository.
- 범위: 종목 생성·이름 변경·휴식 설정 변경만 구현. 신규 구현은 **사용자 검토 전 미커밋**, Push 없음.
- 상태: 구현·전체555개 회귀·빌드·자체 리뷰 완료. 실제 개인 로컬 DB는 사용하지 않았다.
- V10 정리 커밋: `988c3bfb78b7be9d1a985ad0120f397e636a3990`, `chore(db): finalize Task 009 V10 schema cleanup`.
- 이번 단계에서는 실제 개인 로컬 DB/다른 프로젝트 DB에 접속하거나 migration을 실행하지 않았다.

## 1. V10 커밋과 이후 상태

V10 커밋에는 아래13개만 포함했다. V1~V9는 원문 보존했으며 SQL·테스트는 이전503개 검증 상태와 해시가 같음을 확인했다. 최종 문서 링크·스테이징 전체 diff·공백 검사도 통과했다.

| 분류 | 포함 파일 |
|---|---|
| 정식 migration | src/main/resources/db/migration/V10__remove_obsolete_workout_command_storage.sql |
| 기존 테스트 | account/AccountProvisioningIntegrationTests.java, auth/email/EmailOtpIntegrationTests.java, database/PostgresMigrationTests.java, database/V9MigrationCompatibilityTests.java, persistence/JpaJdbcCompatibilityIntegrationTests.java, security/SecuritySessionIntegrationTests.java |
| 신규 테스트 | database/V10MigrationIntegrationTests.java |
| 현재 안내 | AGENTS.md, README.md, docs/engineering/backend.md, docs/engineering/local-postgresql.md |
| 최종 결과 | docs/tasks/task-009-v10-local-application-report.md |

위 테스트7개의 공통 경로는 src/test/java/com/overload/overload_server다. 커밋 직후 Backend tracked/staged 변경0, 기존 미추적 자료4개만 남았고 Frontend는 HEAD `642dcaa2a883ffe8e4b4666ee2603c54de086737`의 clean 상태였다.

보존한4개는 docs/plans/task-009-v10-cleanup.sql.draft, task-009-v10-db-cleanup-preparation.md, task-009-v10-local-db-read-audit.md와 docs/tasks/task-009-v10-formal-preparation-report.md다. 현재 구현에 필요한 검증 근거는 V10 최종 보고서에 모았고 중간 자료는 커밋하지 않았다. 삭제·초기화도 하지 않았다.

## 2. 새 API 계약

구현 전에 기존 GET과 비교해 경로·DTO·오류를 정리했다. 상세 계약은 [온라인 쓰기 API](../api/workout-online-write.md)를 따른다.

| API | 요청 | 응답 |
|---|---|---|
| POST /api/v1/exercises | UUID id, 문자열 dataGeneration, name | 201 신규 / 200 동일 UUID·현재 이름 재요청, userId/dataGeneration/id/name |
| PATCH /api/v1/exercises/{id} | 문자열 dataGeneration, name | 200, userId/dataGeneration/id/name |
| PATCH /api/v1/workout-settings | 문자열 dataGeneration, 정수 restLowSeconds/restMediumSeconds/restHighSeconds | 200, 기존 설정 GET과 동일한5필드 |

세션 쿠키·CSRF header가 필요하며 owner는 SessionPrincipal에서 결정한다. 임의 userId/query·미지원 필드는400이다. dataGeneration은 기존 GET의 음이 아닌 long 십진 문자열을 그대로 보낸다. 이름은 공백만 있는 값·NUL·잘못된 Unicode를 거부하며 임의 trim/길이 제한/이름 UNIQUE는 없다. 휴식은 기존 모델·INTEGER와 같은 양의 정수 초(최대2147483647)이며 소수·숫자 문자열을 정수로 강제 변환하지 않는다.

오류는 기존 code-only·no-store·Request ID를 유지한다. 잘못된 요청400, 인증401, 기존 CSRF/인가403, 본인 현재 세대의 비삭제 종목 부재404, 세대 불일치/UUID 점유/낙관적 잠금 충돌409, 설정 부재/예상 밖 쓰기 오류500, 확인된 연결/timeout 오류503이다. SQL·다른 계정 값·예외 원문은 응답에 넣지 않는다.

## 3. 구현 파일과 필요한 이유

| 파일 | 구현 |
|---|---|
| workout/WorkoutWriteController.java (신규) | 명시적 POST/PATCH3개, 세션 principal·DTO·HTTP status/no-store |
| workout/WorkoutWriteService.java (신규) | concrete create/rename/changeRest3개와 기존 JPA manager의 작업별 트랜잭션 |
| workout/WorkoutWriteRequests.java (신규) | 세 요청 record와 실제 사용되는 엄격한 JSON 타입·필드·범위 검증. 전역 Jackson 설정은 불변 |
| workout/ExerciseWriteResponse.java (신규) | Entity 노출 없이 종목 쓰기 결과와 소유자/문자열 세대 반환 |
| workout/WorkoutWriteFailure.java (신규) | 이3개 API에 필요한 의미별 오류만 정의 |
| workout/WorkoutWriteErrors.java (신규) | 쓰기 Controller에만 적용, 낙관적 잠금/PK/연결/내부 오류 구분 |
| exercise/ExerciseDefinition.java | 생성 factory와 rename만 추가. UUID/owner/generation/created_at은 변경하지 않음 |
| workoutsettings/UserWorkoutSettings.java | 기존 WorkoutSettings 양수 검증을 활용한 changeRest 추가 |
| exercise/ExerciseDefinitionRepository.java | 실제 생성에 쓰는 saveAndFlush와 수정 flush 추가 |
| workoutsettings/UserWorkoutSettingsRepository.java | 실제 수정 flush만 추가 |
| workout/WorkoutOwnerRepository.java | 쓰기에만 users FOR SHARE 추가. 기존 GET generation SELECT는 불변 |
| workout/WorkoutReadErrors.java | 기존 unavailable classifier만 package 범위로 재사용. GET 오류 처리 동작 불변 |

위 Java 공통 경로는 src/main/java/com/overload/overload_server다. 별도 Repository 구현/인터페이스 계층·범용 실행기·공통 command/envelope는 만들지 않았다. 요청3개의 JSON 검증 helper, 트랜잭션 경계, 기존 연결 오류 classifier는 이번 실제 사용처만 공유한다.

기존 두 DB 비사용 테스트의 MockitoBean 목록에 새 WorkoutWriteService를 추가했다(OverloadServerApplicationTests, testfixture/RequestIdHttpTests). 기존 WorkoutReadService와 같은 컨텍스트 배선용 stub이며 DB 통합 검사나 새 쓰기를 mock으로 통과시키지 않았다. 인증·Session·CSRF·Request ID 제품 코드는 변경하지 않았다.

## 4. 계정 격리·동시성·중복·rollback

- 같은 workoutTransactionManager/JPA 트랜잭션에서 JdbcTemplate로 users를 FOR SHARE 조회해 계정 존재·현재 세대를 확인한다. 공유 잠금은 계정 삭제/세대 변경을 commit까지 막으면서 같은 사용자 쓰기끼리 병행을 허용한다. 종목/설정 Repository는 owner+generation으로만 찾는다. 타 계정/이전 세대/삭제 종목에 rename하지 않는다.
- 새 Entity의 null @Version으로 persist하므로 클라이언트 UUID로 다른 행에 merge하지 않는다. 같은 owner/generation/UUID와 현재 이름이 같으면 기존 결과200, 다른 내용/삭제 행/PK 충돌이면409다. 다른 UUID의 동명 종목은 별개다. 동시 생성 PK 충돌도409이며 재요청 때 기존 동일 행을 반환한다.
- rename/changeRest는 관리 Entity의 필요한 필드만 변경하고 flush/commit에서 기존 @Version을 확인한다. 같은 값의 순차 재요청은 updated_at/version을 바꾸지 않는다. 기존 version/deleted_at과 생성 시각/UUID/다른 종목·설정은 보존한다.
- 기존 GET에는 version이 없으므로 expectedVersion을 새로 강제하거나 GET DTO를 확장하지 않았다. 겹친 DB 트랜잭션은409지만, 이전 수정이 이미 끝난 후 도착한 새 명시적 PATCH는 통상적인 새 수정으로 허용한다. 이미 완료된 요청까지 과거 화면 수정으로 판정하는 동기화/병합 시스템은 없다.
- 실패한 트랜잭션에서 재조회·자동 재시도하지 않는다. 연결 단절의 rollback 실패가 원래 오류를 가리지 않도록 기존 read와 같은 작은 TransactionTemplate 경계에서 원래 원인을 suppressed로 보존한다. Session의 JDBC/REQUIRES_NEW 관리자와 기존 운동 JPA/JDBC 연결 경계는 변경하지 않았다.

## 5. 테스트 결과

신규 WorkoutWriteIntegrationTests는 Testcontainers PostgreSQL18.6의 전용 임의 loopback 포트/DB에서 실제 Spring HTTP·OTP 대체기·JPA/JDBC를 사용했다. 실제 .env import는 기존 테스트 overlay로 제외하고 실행 프로세스의 외부 설정 변수도 제거했다. 실제 메일·Google 호출은 없다.

| 신규 시나리오 | 건수 | 대상 검증 결과 |
|---|---:|---|
| 생성·이름 변경·휴식·동명 rename/중복 재요청·GET 반영 | 4 | PASS |
| 익명·만료·CSRF 및 users가 사라진 세션 | 6 | PASS |
| 타 계정·부재·삭제 UUID 수정/덮어쓰기 거부 | 1 | PASS |
| 세대 불일치와 JS 안전 정수 초과 세대 문자열 보존 | 3 | PASS |
| 잘못된 JSON/타입/공백/Unicode/범위/owner 필드 | 26 | PASS |
| query를 통한 owner/generation 입력 거부 | 3 | PASS |
| 실제 동시 생성·이름/설정 경쟁 수정 | 3 | PASS |
| DML 실행 뒤 실제 SQL 오류·전체 rollback | 3 | PASS |
| 실제 backend 연결 단절·rollback·다음 요청 복구 | 1 | PASS |
| 공유 owner 잠금·같은 JDBC/JPA pid/tx 확인 | 1 | PASS |
| 설정 부재 시 seed/자동 복원하지 않음 | 1 | PASS |
| 합계 | **52** | **PASS, failures/errors/skipped0** |

대상 실행 `gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutWriteIntegrationTests'`는47초에 성공했다. 단위112개도 함께 실행해 통과했다.

최종 `gradlew.bat --offline --no-daemon build --rerun-tasks`는 **BUILD SUCCESSFUL, 5분33초, 9개 task 모두 실제 실행**이다. 단위112개·통합443개, 총 **555개 PASS**, failures/errors/skipped 모두0이다. 기존503개를 유지하고 신규52개를 추가했다. 컴파일·bootJar·jar·test·integrationTest·check·build를 새로 실행했다. 기존 검사·테스트를 삭제/비활성화하지 않았다.

| 전체 통합 테스트 클래스 | 통과 건수 |
|---|---:|
| AccountProvisioningIntegrationTests | 11 |
| EmailOtpIntegrationTests | 27 |
| EmailReauthenticationIntegrationTests | 40 |
| CommonReauthenticationEmailIntegrationTests | 35 |
| EmailIdentityIntegrationTests | 34 |
| GoogleContactFrontendIntegrationTests | 8 |
| GoogleIdentityIntegrationTests | 56 |
| GoogleLinkFrontendIntegrationTests | 11 |
| GoogleOidcIntegrationTests | 40 |
| GoogleReauthenticationIntegrationTests | 64 |
| PostgresMigrationTests | 10 |
| V10MigrationIntegrationTests | 5 |
| V9MigrationCompatibilityTests | 1 |
| JpaJdbcCompatibilityIntegrationTests | 17 |
| SecuritySessionIntegrationTests | 10 |
| WorkoutReadIntegrationTests | 22 |
| WorkoutWriteIntegrationTests | 52 |
| 합계 | **443** |

단위14개 클래스112개에는 Request ID·컨텍스트 배선·입력/기존 도메인 검증이 포함된다. 최종 XML과 빌드 로그로 결과를 집계했다. 로그는 Git 제외 build/task-009-online-write-full-build.log, XML은 build/test-results/{test,integrationTest}다. 최초 전체 실패 로그도 build/task-009-online-write-full-build-initial.log에 보존했다. 최종 빌드의 unchecked/CDS 안내는 실패가 아니며 미해결 컴파일/테스트 오류는 없다.

초기 compile에서 record name accessor와 JSON helper 이름이 충돌한 오류는 명시적 클래스 한정 호출로 수정했다. 최초 대상48개 중 생성 rollback1건은 fixture가 기존 UUID를 재사용해 의도한 DML 대신409에 도달한 문제였고 새 UUID로 수정했다. 추가 동명 rename·users 부재4건까지 포함한 최종52개를 통과했다. 제품 요구사항을 완화해 실패를 숨기지 않았다.

첫 전체 회귀에서는 기존 WorkoutReadIntegrationTests의 미지원 HTTP 메서드 검사가 POST /exercises에405를 기대해 실패했다. POST가 이번에 구현되었으므로 그405 검사를 여전히 미지원인 PUT으로 옮기고 잘못된 POST 본문은400임을 추가 검사했다. 기존 테스트를 삭제·비활성화하지 않았으며 GET/보안/405 검증 의도도 유지했다.

## 6. 기존 기능 영향·남은 범위

기존 GET Controller/Service/DTO/조회 메서드·정렬은 불변이다. 이름·UUID·세대·휴식 결과가 기존 GET에 반영되는 것을 실제 HTTP로 확인했다. 새 GET이나 개별 종목 version 조회를 추가하지 않았다. Flyway V1~V10·schema·의존성·설정 변경도 없다. Frontend는 변경하지 않았으며 별도 검증을 실행하지 않았다.

루틴 CRUD·운동 세션 START/ACTIVE/takeover·snapshot·epoch/revision·24시간 자동 종료·IndexedDB/오프라인 진행·Frontend 운동 화면 교체는 미구현이다. 구 명령·stamp·fieldBases·영수증/feed·Outbox·범용 동기화/자동 병합을 재도입하지 않았다.

다음은 이3개 API 계약/동시 수정 의미를 사용자 검토한 뒤 Frontend의 온라인 종목·휴식 설정 관리에 연결하는 작은 단계가 적절하다. 루틴 및 진행 세션은 별도 범위·계약으로 진행한다. 신규 구현의 Commit/Push는 사용자 검토 전 수행하지 않는다.

## 7. 최종 Git·프로세스 상태

- Backend HEAD는 V10 커밋 `988c3bfb78b7be9d1a985ad0120f397e636a3990` 그대로다. 신규 API의 commit은 만들지 않았다. tracked12개 수정, 신규 구현/계약/보고서9개 미추적, 기존 보존 자료4개 미추적, staged0이다. 파일 삭제·reset·clean·Push는 없다.
- tracked12개는 개발 안내3개, 위 기존 Java6개, DB 비사용 테스트2개와 기존 조회 통합 테스트1개다. 신규9개는 쓰기 Java6개·쓰기 통합 테스트1개·API 계약1개·이 보고서1개다. 기존4개 미추적 자료의 보호 해시도 유지했다.
- Frontend HEAD `642dcaa2a883ffe8e4b4666ee2603c54de086737`, clean. Frontend 파일·package/lockfile·검증 스크립트는 변경하지 않았다.
- 테스트 종료 후 관련 Java/Gradle 프로세스0, 남은 Testcontainers0을 확인했다. 기존 개인 PostgreSQL은 동일 ID·overload-local_postgres_data 볼륨의 exited 상태이며5433 listener0이다. 이 작업에서 기존 DB 컨테이너를 시작하지 않았다. 기존 다른 프로젝트 컨테이너5개는 그대로 유지했다.
- git diff --check·신규 파일 공백 검사·문서 링크 검사를 통과했다. 인증/보안 제품 코드·기존 GET 본문/서비스·Flyway V1~V10·빌드/의존성 설정은 HEAD와 동일하다. 이제 사용자 코드 검토를 기다린다.
