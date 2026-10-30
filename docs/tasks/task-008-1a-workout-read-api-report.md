# Task 008-1A — 운동 종목·휴식 설정 조회 API

- 작성일: 2026-10-30 (Asia/Seoul).
- 시작 Git: backend `main/d28f0fb`, frontend `main/1c84d23`, 양쪽 clean. 008-0C 커밋을 확인한 뒤 시작했다.
- 기준: [Task 006](task-006-integrated-design-v0.3.1.md), [JPA 설계](task-008-0-jpa-persistence-design.md), [R-01~R-03 읽기 계약](task-008-0-domain-and-api-contract-v0.1.md), [공통 HTTP 계약](task-008-0d-common-api-contract-v0.1.md), [008-0C 검증](task-008-0c-jpa-jdbc-validation-report.md), 양쪽 AGENTS/Engineering 문서.
- 상태: **전체 build 성공 / 신규 22개 포함 PostgreSQL 385개 + DB 비사용 112개 = 총 497개 통과 / 리뷰 전 미커밋**.

## 1. 구현 및 데이터 경계

`WorkoutReadController → WorkoutReadService → WorkoutOwnerRepository(JDBC) + 기존 JPA Repository`로 조회한다. 새 클래스는 `src/main/java/com/overload/overload_server/workout/`에 있다.

| 파일 | 책임 |
|---|---|
| WorkoutReadController.java | 두 GET, SessionPrincipal에서 owner 결정, query/body 거절, no-store |
| WorkoutReadService.java | 명시적 운동 JPA 관리자·REPEATABLE_READ·readOnly 경계, DTO 완성 |
| WorkoutOwnerRepository.java | `SELECT data_generation FROM users WHERE id = ?`; 잠금·생성 없이 같은 연결 참여 |
| ExerciseListResponse.java | UUID userId, 문자열 직렬화 long dataGeneration, exercises 배열; 항목은 UUID id/String name |
| WorkoutSettingsResponse.java | UUID userId, 문자열 직렬화 long dataGeneration, 세 개 int 휴식 초 |
| WorkoutReadFailure.java | 형식 오류·사용자 부재·설정 누락의 명시적 내부 구분 |
| WorkoutReadErrors.java | 이 Controller만 대상으로 하는 Advice와 타입이 명확한 code-only Body |

두 기존 Entity와 읽기 Repository는 수정하지 않았다. 이름·UUID는 원값을 반환한다. 종목은 PostgreSQL UUID 오름차순이며, 설정은 user_id PK와 현재 세대 조건으로 실제 값을 읽는다. 반복 구간 ≤10 / 11~12 / ≥13은 바뀌지 않는다. version/deleted_at·새 테이블·migration·의존성·인프라는 추가하지 않았다.

`userId/dataGeneration/generation/sort/filter/limit` 등을 포함한 비어 있지 않은 query는 전부 400이다. GET body도 지원하지 않으며 본문 길이 또는 Transfer-Encoding이 있으면 400이다. 클라이언트 owner/generation 값은 서비스로 전달하지 않는다. 사용자 부재는 401, 종목 0개는 200 빈 배열, 현재 세대 설정 부재는 500이며 어떤 경우에도 seed·계정 복구를 하지 않는다.

`dataGeneration`은 Java long을 유지하고 DTO의 JsonFormat STRING으로 직렬화한다. 9007199254740993과 Long.MAX_VALUE도 정확한 10진 문자열로 확인했다. 시각 컬럼·사용자 설정·종목을 읽는 과정에서 변경하지 않는다.

## 2. 실제 트랜잭션과 실패 보존

운동 서비스 생성자에서 `@Qualifier("workoutTransactionManager")`를 지정한 TransactionTemplate을 만들고 readOnly=true, isolation=REPEATABLE_READ로 설정한다. 인증용 primary JDBC 관리자와 Session의 REQUIRES_NEW는 그대로다. users 조회용 JdbcTemplate은 별도 JDBC 트랜잭션을 열지 않는다. 한 응답의 generation 조회·JPA 조회·DTO 생성이 같은 운동 트랜잭션 안에서 끝난다.

신규 테스트의 DataSource 관찰기는 실행 SQL을 대체하지 않고 실제 Statement를 관측한다. 두 GET 각각 다음을 확인했다.

- users와 JPA 조회의 PostgreSQL backend PID 및 txid가 동일하다.
- 실제 연결 autoCommit=false·readOnly=true·REPEATABLE_READ, PostgreSQL의 transaction_read_only=on·transaction_isolation=repeatable read다.
- 두 SELECT 동안 EntityManager가 트랜잭션에 바인딩돼 있다. 종목/설정 조회와 users 조회는 총 두 SQL이며 행 잠금을 사용하지 않는다.
- users SELECT 직후 독립 PostgreSQL 연결에서 종목·설정을 바꾸고 새 세대를 커밋해도 첫 응답은 이전 세대의 전체 데이터, 다음 요청은 새 세대 데이터다. 이 변경은 일회성 테스트 DB의 fixture이며 제품 reset API 구현이 아니다.
- 운동/사용자 데이터 INSERT/UPDATE/DELETE가 없다. UUID·이름·created_at/updated_at을 포함한 전후 행도 동일하다. 기존 Session 필터의 세션 저장·접근 시각 갱신은 별개이며 제거하지 않았다. Session SQL 실행 중 운동 EntityManager binding은 없다.
- 두 조회 SQL의 MDC requestId가 실제 HTTP X-Request-ID와 일치한다.

실제 pg_terminate_backend로 운동 연결만 끊는 테스트에서 조회 실패 뒤 Hibernate rollback도 실패했다. Spring 7.0.10의 TransactionTemplate/트랜잭션 인터셉터는 TransactionSystemException과 달리 다른 RuntimeException으로 rollback이 실패하면 원래 예외를 보존하지 않는 경로가 있다. 설치된 source JAR와 실행 로그를 대조했다.

따라서 서비스의 작은 read 경계에서 원래 조회 RuntimeException을 보관하고, 바깥 rollback 실패가 이를 덮어쓰면 suppressed 원인으로 보존한다. Advice는 cause/suppressed 및 TransactionSystemException의 applicationException을 순환 방지하며 조사한다. 실패한 트랜잭션에서 재조회하지 않는다. 실제 연결 단절은 두 GET 모두 503이고 다음 새 요청은 정상 복구됐다. 인증 서비스나 TransactionManager 설정은 바꾸지 않았다.

## 3. HTTP 오류·보안 호환성

| 상태 | code | 실제 기준 |
|---|---|---|
| 400 | INVALID_REQUEST | 미지원 query/body·MVC 요청 바인딩 오류 |
| 401 | AUTHENTICATION_REQUIRED | 기존 Security의 익명/만료·인증 실패 또는 users 부재 |
| 403 | ACCESS_DENIED / CSRF_INVALID | 기존 Security handler 유지 |
| 500 | WORKOUT_SETTINGS_MISSING | 현재 세대 설정 없음 |
| 500 | WORKOUT_DATA_READ_FAILED | SQL grammar/매핑·무결성·예상하지 못한 오류 |
| 503 | WORKOUT_DATA_UNAVAILABLE | 확인된 resource/timeout 예외, 연결 SQLSTATE 08*, PostgreSQL 일시 종료 57P01/02/03 |

DataAccessException 전체 또는 모든 트랜잭션 예외를 503으로 취급하지 않는다. 실제 컬럼 rename으로 JPA SELECT가 실패하면 500이고 원복 후 다시 조회된다. 무결성 예외·내부 IllegalArgumentException도 500이다. 연결/트랜잭션 예외 객체 주입 검증은 실제 연결 단절 검증과 구분한다.

응답은 `{ "code": "WORKOUT_DATA_READ_FAILED" }`와 같은 단일 필드다. SQL·예외 원문·stack trace·사용자 입력·다른 계정 데이터는 넣지 않는다. 새 5xx 로그는 안정적인 code만 기록하며 예외 객체/원문을 로그 인자로 넘기지 않는다. 기존 Hibernate/Spring의 진단 로그 정책 전체를 이 Advice가 대체하지는 않는다.

AuthenticationException/AccessDeniedException은 Advice가 재전파해 기존 Security handler가 처리한다. ErrorResponse 프로토콜 예외도 기존 MVC 처리로 넘긴다. 405를 500으로 바꾸지 않았고, Controller 선택 전 404/405/415·필터/컨테이너 오류 전체에 code-only를 보장하지 않는다.

성공 및 명시적 400/401/403/500/503에서 JSON 본문, no-store, X-Request-ID UUID v4, nosniff, DENY를 확인했다. Security 기본 Cache-Control은 여러 지시어에 no-store가 포함될 수도 있다. 외부 Request ID를 반사하지 않고 본문에 requestId를 추가하지 않는다. 실제 인증 OTP의 기존 400 INVALID_REQUEST 및 DB 오류 503 OTP_UNAVAILABLE도 유지돼 두 Advice의 적용 범위가 섞이지 않는다.

## 4. 실제 JSON 응답

아래는 Testcontainers에서 만든 합성 계정의 HTTP 응답이다. 실제 개발 계정과 무관하다. 기본 20종목 중 한 이름과 휴식 값만 해당 테스트에서 편집했으며 서버가 공백·Unicode·편집값을 그대로 반환했음을 보여준다. 전체 응답은 JUnit XML의 actualExerciseResponse/actualSettingsResponse에도 기록한다.

GET `/api/v1/exercises`, 200 (실제 20개 전체):

```json
{
  "userId": "bfc6192c-4507-4b53-b81f-0f658cb66a9b",
  "dataGeneration": "0",
  "exercises": [
    {"id":"1436e7f5-9287-4b2d-b0b6-dc0d0b01dddf","name":"  동명 종목 Ω  "},
    {"id":"24984ac7-bf26-4bf7-8eb2-55debe414e01","name":"바벨 로우"},
    {"id":"3531214f-c73f-4ec0-bdb4-a7c8151d188e","name":"케이블 플라이"},
    {"id":"370cff8b-8c2b-4791-9447-82288a8b8da6","name":"풀업"},
    {"id":"538195b1-d87f-4ec1-830d-132cacbcb4a4","name":"덤벨 컬"},
    {"id":"59000163-4b64-4cb6-a7d2-f2f856428a2f","name":"레그 익스텐션"},
    {"id":"5bc77757-cc73-4a3f-8284-3b766af70204","name":"오버헤드 트라이셉스 익스텐션"},
    {"id":"5feb3c88-57b9-40eb-b140-cc24c96bd390","name":"벤치프레스"},
    {"id":"66c20c65-ad8e-4665-a52f-ae300d02bac0","name":"레그 컬"},
    {"id":"6ab0346e-0202-40c0-821e-3dd9dd2a1a9e","name":"스쿼트"},
    {"id":"7868756c-6c24-4186-be8f-671fc4cd0dc1","name":"숄더 프레스"},
    {"id":"84560baa-f64e-4a76-bbe7-f6c1c61e1490","name":"인클라인 덤벨 프레스"},
    {"id":"8c2ef5de-f2cf-407e-80b9-586834535a2c","name":"바벨 컬"},
    {"id":"a0674667-2390-4b27-943b-400636691c7e","name":"케이블 푸시다운"},
    {"id":"b0aeccdf-6a57-494c-89b8-ca2c30cbf23e","name":"리어 델트 플라이"},
    {"id":"c56e66b5-bf04-4bad-bc3a-50123428f28a","name":"사이드 레터럴 레이즈"},
    {"id":"dcc24e1f-1f80-4a99-9d68-8e331743ed85","name":"루마니안 데드리프트"},
    {"id":"e758808d-e43d-4b75-a73c-1d219396370d","name":"시티드 케이블 로우"},
    {"id":"ecb8a850-031b-4913-8512-ec14dc57d6b8","name":"체스트 프레스"},
    {"id":"ff646889-788e-4db9-9d5b-0d36bd7cb9fc","name":"랫풀다운"}
  ]
}
```

GET `/api/v1/workout-settings`, 200:

```json
{
  "userId": "bfc6192c-4507-4b53-b81f-0f658cb66a9b",
  "dataGeneration": "0",
  "restLowSeconds": 17,
  "restMediumSeconds": 29,
  "restHighSeconds": 43
}
```

## 5. 검증 기록

- 최초 대상 실행: DB 비사용 112 통과, 신규 PostgreSQL 22 중 6 실패. 잘못된 fixture 컬럼, Security Cache-Control의 다중 지시어, 로그인 상태의 OTP 요청 가정을 고쳤다. 실제 연결 단절 두 건은 rollback이 원래 실패를 덮어써 500이 된 문제였다.
- 1차 재실행: 22 중 20 통과. TransactionSystemException의 applicationException만 조사해서는 다른 rollback RuntimeException 경로를 복구하지 못함을 확인했다.
- 최종 대상 실행: `gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutReadIntegrationTests'`, BUILD SUCCESSFUL, 39초. DB 비사용 112 및 신규 PostgreSQL 22 모두 실패/오류/skipped 0이다.
- 전체 `gradlew.bat --offline --no-daemon build`: **BUILD SUCCESSFUL, 4분 12초**. bootJar/jar/check/build 성공. integrationTest는 전체 재실행, test는 같은 최종 코드로 직전 실행된 112개 결과를 사용해 UP-TO-DATE였다. 실패/오류/skipped 모두 0이다.

| 최종 결과 XML 범위 | tests | 실패 / 오류 / skipped |
|---|---:|---|
| DB 비사용 단위·컨텍스트·Request ID HTTP | 112 | 0 / 0 / 0 |
| 기존 PostgreSQL 인증·계정·마이그레이션·Session 회귀 | 346 | 0 / 0 / 0 |
| 기존 008-0C JPA/JDBC 호환성 | 17 | 0 / 0 / 0 |
| 신규 WorkoutReadIntegrationTests | 22 | 0 / 0 / 0 |
| PostgreSQL 합계 | 385 | 0 / 0 / 0 |
| **전체 합계** | **497** | **0 / 0 / 0** |

JUnit XML은 `build/test-results/test/`, `build/test-results/integrationTest/`, HTML은 `build/reports/tests/`에 있으며 Git에 포함하지 않는다. 초기 실패는 위 수정 후 재실행으로 해소했으며 테스트 비활성화·pool 확대·오류 코드 계약 변경으로 우회하지 않았다. 기존 JVM CDS 안내 외 최종 빌드 실패는 없다.

기존 Temurin 25.0.4.1과 Boot 관리 의존성만 사용했다. 상속된 Spring/Google/OTP/Resend/PostgreSQL 등 환경 설정은 테스트 실행 프로세스에서 제거하고, test local overlay가 개발 .env를 읽지 않음을 혼합 구성 검사로 재확인한다. 로컬 Testcontainers PostgreSQL 18.6만 사용했다. 개발 PostgreSQL·Google/Resend 실제 호출·실사용 비밀값은 사용하지 않았다.

## 6. 변경 파일과 남은 범위

새 제품 파일 7개, WorkoutReadIntegrationTests 및 WorkoutReadFixtures를 추가했다. 기존 DB 비사용 Application/RequestIdHttpTests에는 WorkoutReadService mock을 추가했다. 이는 DataSource 없는 테스트의 배선 유지용이며, 실제 PostgreSQL 회귀에서는 실제 서비스·두 manager·두 Entity를 모두 사용한다. README의 구현 상태를 갱신했다.

인증·보안·Request ID 제품 코드, 008-0C 설정/Entity/Repository, Flyway V1~V8, 프론트엔드 제품 코드는 불변이다. demo localStorage를 읽거나 업로드하지 않는다. 008-1B React 연동·브라우저/PWA·개발 DB/LIVE·운동 쓰기·새 동기화/과부하 계산은 미검증/미구현이다. D-01~D-10 승인 상태를 변경하지 않는다. 두 GET 간 하나의 공통 snapshot까지 보장하지 않으므로 후속 클라이언트는 userId/generation이 다르면 응답을 혼합하지 않아야 한다.

변경 파일은 다음 13개다. 기존 추적 파일 수정 3개·신규 10개이며 모두 미스테이징 상태다.

```text
README.md
docs/tasks/task-008-1a-workout-read-api-report.md
src/main/java/com/overload/overload_server/workout/ExerciseListResponse.java
src/main/java/com/overload/overload_server/workout/WorkoutSettingsResponse.java
src/main/java/com/overload/overload_server/workout/WorkoutOwnerRepository.java
src/main/java/com/overload/overload_server/workout/WorkoutReadController.java
src/main/java/com/overload/overload_server/workout/WorkoutReadErrors.java
src/main/java/com/overload/overload_server/workout/WorkoutReadFailure.java
src/main/java/com/overload/overload_server/workout/WorkoutReadService.java
src/test/java/com/overload/overload_server/workout/WorkoutReadIntegrationTests.java
src/test/java/com/overload/testfixture/WorkoutReadFixtures.java
src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java
src/test/java/com/overload/testfixture/RequestIdHttpTests.java
```

최종 변경에서 기존 파일의 `git diff --check`와 신규 파일의 끝 공백·비밀값 패턴·예상 외 산출물 검사를 수행했다.

Testcontainers/Ryuk가 종료됐고 시작 전 사용자 Docker 컨테이너 5개는 같은 ID로 유지됐다. 검증 후 Java 프로세스가 남지 않은 것도 확인했다. 사용자 프로세스를 임의로 종료하지 않았다.

최종 Git: backend는 `main/d28f0fb` 그대로(origin/main보다 1커밋 앞섬)이며 위 13개 파일이 미커밋 상태다. frontend는 `main/1c84d23`에서 clean이다. Commit/Push하지 않았다.

DB 전체가 Session/Security 필터 단계부터 이용 불가능한 상황이나 실제 query timeout은 신규 API 전용 테스트에서 재현하지 않았다. 이번 실제 DB 장애 시험은 인증 복원 후 운동 연결만 끊는 것이다. 컨테이너/필터 경계 전체 오류의 동일 JSON 변환이나 새 GET의 실제 브라우저·HTTPS/PWA 검증을 완료한 것으로 간주하지 않는다.
