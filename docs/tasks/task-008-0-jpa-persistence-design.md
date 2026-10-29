# Task 008-0 — 운동 도메인 JPA 영속성 및 JDBC 호환성 설계

- 작성일: 2026-10-30 (Asia/Seoul)
- 상태: **설계 완료 / 런타임 호환성 미검증 / 구현 미착수**
- 기준 커밋: server `41ba849`, pwa `1c84d23`; 시작 시 양쪽 작업 트리 clean.
- 변경 범위: 이 문서만 신규 작성. 제품 코드·설정·의존성·migration·개발 DB·인증 코드 변경 및 Commit/Push 없음.
- 결정 근거: 사용자의 Task 008 영속성 기술 결정. 운동 도메인에 JdbcTemplate 유지를 기본 권장하던 이전 안을 대체한다.
- 제품·보안 정책: [Task 006 최종 설계 v0.3.1](task-006-integrated-design-v0.3.1.md), [AGENTS.md](../../AGENTS.md), [backend.md](../engineering/backend.md)를 유지한다.

## 1. 확정 기술 경계와 검증 수준

| 영역 | 영속성 | 적용 경계 |
|---|---|---|
| 계정·identity·OTP·대표 재인증 이메일·OAuth pending | 기존 JdbcTemplate | 기존 서비스·SQL·잠금 순서·소비·롤백 유지. User/AuthIdentity JPA 전환 없음 |
| 로그인 세션 | 기존 Spring Session JDBC | 기존 BYTEA 직렬화·쿠키·90일 절대 만료·CSRF·세션 회전 유지 |
| 개인 종목·운동 설정·루틴·배치·일정·메모 | Spring Data JPA + Hibernate | 운동 서비스·Repository·Entity; 소유자와 현재 세대 조건 필수 |
| 운동 세션·세트·유산소·기록·승인/정정 사건 | Spring Data JPA 기본 | 집계 명령의 한 트랜잭션에서 저장 |
| 복잡한 동시성·멱등성·동기화 | JPA 우선, 필요 시 Native SQL/JDBC | JPA 명령의 동일 물리 트랜잭션에 참여하는 제한된 SQL 어댑터 |
| 스키마 | Flyway 단독 | Hibernate 자동 DDL 및 Spring Session 자동 DDL 사용 금지 |

기존 신규 가입 프로비저닝이 종목 20개와 설정 1행을 JDBC로 삽입하는 것은 유지한다. 가입 경계의 예외이며, 가입 이후 운동 조회·편집은 JPA가 담당한다. 두 경로의 스키마·기본값 호환성을 테스트한다. 기존 계정 재로그인 시 seed를 재실행하지 않는다.

이번에 확인한 것은 **공식 관리 버전, 현재 코드의 트랜잭션 경계, 캐시된 프레임워크 구현과 설계 가능성**이다. JPA 의존성을 프로젝트에 추가하거나 혼합 애플리케이션을 실행하지 않았다. 동일 DataSource를 사용할 수 있다는 설명만으로 호환성 통과를 선언하지 않는다.

## 2. Spring Boot 4.1.2 의존성 검토

현재 `build.gradle`은 Boot 4.1.2와 dependency-management 1.1.7을 사용하며 JPA 의존성은 없다. 추가 예정인 최소 런타임 의존성은 다음 하나다. **이 문서의 예시는 추가 실행 지시가 아니다.**

```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
```

개별 Spring Data/Hibernate 버전은 지정하지 않고 현재 Boot BOM을 따른다. 기존 JDBC·Session JDBC starter와 PostgreSQL driver는 유지한다. JPA starter가 JDBC starter도 포함하더라도 기존 의존성을 이번 결정만으로 정리하지 않는다.

| 구성요소 | Boot 4.1.2 관리값 | 확인 근거 |
|---|---|---|
| Spring Data BOM | 2026.0.2 | 로컬 `spring-boot-dependencies-4.1.2.pom` |
| Spring Data JPA | 4.1.2 | 로컬 Data BOM 및 공식 관리 좌표 |
| Hibernate ORM | 7.4.13.Final | 로컬 Boot BOM 및 공식 관리 좌표 |
| Spring Framework | 7.0.10 | 로컬 Boot BOM |
| Jakarta Persistence | 3.2.0 | 로컬 Boot BOM |
| Spring Session | 4.1.1 | 로컬 Boot BOM 및 JDBC jar |

공식 [Boot 관리 좌표](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)와 로컬 캐시 POM을 대조했다. 이는 BOM의 조합 확인이며 이 저장소에 JPA를 추가한 뒤의 최종 Gradle 해석 결과는 아니다. 구현 시 `dependencyInsight`로 실제 runtimeClasspath를 다시 확인한다. Hibernate 가이드의 현재 패치 버전을 BOM보다 우선하지 않는다.

통합 테스트는 기존 JUnit·Spring Boot·Testcontainers로 구성할 수 있다. `@DataJpaTest` slice를 채택할 때만 Boot 4.1.2의 `spring-boot-starter-data-jpa-test`를 검토한다. 이 추가 starter는 최소 혼용 검증의 필수 조건이 아니다. H2·별도 ORM·새 테스트 프레임워크·Envers는 필요하지 않다.

## 3. Entity와 Repository 설계

아래는 설계명이다. 새 테이블의 물리 DDL·전체 컬럼·DTO 계약은 후속 구현 전에 확정한다. 현재 스키마로 매핑 가능한 Entity는 ExerciseDefinition과 UserWorkoutSettings뿐이다.

| Entity / 테이블 | 식별자·역할 | Repository 책임 |
|---|---|---|
| ExerciseDefinition / exercise_definitions | UUID; 소유자·세대·개인 표시명 | 현재 세대 종목 목록·상세·편집, 활성 참조 검사 |
| UserWorkoutSettings / user_workout_settings | user_id UUID PK; 세대·휴식 초 | 소유자+세대 설정 조회·편집 |
| Routine / routines | UUID; 이름·version·삭제 표식 | 소유자+세대 집계 조회·경합 제어 |
| RoutineWeightPlacement / routine_weight_placements | UUID; 순서·목표·initial/approved weight·삭제 표식 | 루틴 범위 배치 조회·제거·명시 복원 |
| RoutineCardioPlacement / routine_cardio_placements | UUID; 유산소 계획·순서·삭제 표식 | 루틴 유산소 배치 관리 |
| WeeklySchedule / weekly_schedule | 사용자·세대·요일 기준 논리 키 | 요일당 루틴 1개, 명시 교체 |
| ExerciseMemo / exercise_memos | 사용자·세대·종목 기준 논리 키 | 종목 정의별 기구 세팅 메모 |
| WorkoutSession / workout_sessions | UUID; version·epoch·현재 기기·날짜·스냅샷 | 소유 세션·현재 평가·목록·변경 경쟁 |
| SessionWeightExercise / session_weight_exercises | 매 세션 새 UUID; 출처 배치 ID·당시 표시명 | 세션 범위 종목 수행 문맥 |
| PerformedSet / performed_sets | UUID; ordinal·kg·실제 반복·완료 시각·삭제 표식 | 실제 완료 수행, 정정·제외 |
| SessionCardioActivity / session_cardio_activities | UUID; 상태·실제 시간·자동 원본·정정 | 세션 유산소 수행 |
| ProgressChangeEvent / progress_change_events | UUID; 불변 승인 사건 | 승인 중량 전후·근거 조회 |
| RecordChangeEvent / record_change_events | UUID; 불변 정정/삭제 사건 | 수행 전후·서버 변경 시각 조회 |
| SyncOperation / sync_operations | UUID 또는 명령 복합 키의 매핑 검토 | 명령 영수증 조회; 경쟁 예약은 필요 시 SQL |

도메인 Repository는 소유권 있는 명시 메서드를 제공한다. 예: `findByIdAndUserIdAndDataGeneration(...)`, `findAllByUserIdAndDataGeneration(...)`. Controller에 `findById`, `findAll`, `deleteAll`, 임의 detached Entity의 `save`를 노출하지 않는다. Spring Data Repository를 선택적 메서드 인터페이스 또는 서비스 내부 전용으로 제한한다.

principal이 사용자 UUID를 결정한다. users의 현재 `data_generation`은 서버 조회 결과를 사용하며, 클라이언트 generation은 기대값 검증용이다. JPA의 LAZY 연관관계·Hibernate 필터는 인가를 대신하지 않는다. 부모 확인뿐 아니라 변경 대상 자식이 해당 부모·소유자·세대에 속하는지도 확인한다.

### 3.1 연관관계 방향

| 관계 | 기본 매핑 방향 | 수명·보존 조건 |
|---|---|---|
| 운동 Entity → users | scalar userId, dataGeneration | users Entity 및 계정 객체 그래프를 만들지 않음; DB 복합 FK |
| WeightPlacement → Routine / ExerciseDefinition | 자식 → 부모 단방향 `ManyToOne(fetch=LAZY)` | 같은 소유자·세대 조건을 가진 FK; 부모 삭제 cascade 없음 |
| CardioPlacement → Routine | 자식 → 부모 단방향 LAZY | 루틴 집계 안에서 변경 |
| WeeklySchedule → Routine, Memo → ExerciseDefinition | 단방향 LAZY 또는 scalar FK | 사용자·세대 조건 검증; 키의 물리 표현은 후속 DDL에서 확정 |
| SessionWeightExercise → WorkoutSession | 자식 → 부모 단방향 LAZY | 과거 수행 보존 |
| PerformedSet → SessionWeightExercise | 자식 → 부모 단방향 LAZY | 논리 삭제와 정정 사건 사용 |
| SessionCardioActivity → WorkoutSession | 자식 → 부모 단방향 LAZY | 부모 세션 version 함께 변경 |
| 세션 → 출처 Routine/Placement/Definition | nullable scalar 출처 ID와 불변 스냅샷 우선 | 현재 이름·설정의 탐색 결과로 과거 사실을 재구성하지 않음 |
| 사건 → 대상 집계 | scalar 참조 및 당시 값 | 현재 집계 논리 삭제와 독립 보존 |

부모의 `OneToMany` 컬렉션은 기본 생략하고 범위 조회로 자식을 읽는다. 필요한 경우에만 `mappedBy` 컬렉션을 추가하며 양방향 관계를 일괄 도입하지 않는다. 영속 컬럼의 쓰기 주체는 한 군데로 정한다. 소유자·세대 scalar와 복합 `JoinColumns`가 같은 컬럼을 공유하면 연관관계 쪽을 읽기 전용으로 두는 등 중복 writable 매핑을 방지하고, 실제 FK 일치 테스트를 필수로 둔다.

`CascadeType.ALL/REMOVE`, `orphanRemoval=true`는 위 관계에 기본 적용하지 않는다. 컬렉션에서 빠졌다는 이유로 이력 행을 물리 삭제하지 않는다. tombstone 조회·복원이 필요하므로 전역 soft-delete 필터에만 의존하지 않는다.

### 3.2 기존 V2 테이블의 안전한 매핑

- `ExerciseDefinition`: `@Id UUID id`, `UUID userId`, `long dataGeneration`, PostgreSQL TEXT에 맞춘 name, `Instant createdAt/updatedAt`. 명시적 snake_case 테이블·컬럼명 사용. 이름 UNIQUE, 자동 ID 재발급, seed marker, deleted_at, version 컬럼을 현재 존재하는 것처럼 매핑하지 않는다.
- `UserWorkoutSettings`: PK는 별도 id가 아니라 `user_id`다. 세대와 `rest_low_seconds/rest_medium_seconds/rest_high_seconds`를 현재 INTEGER에 매핑한다. `@OneToOne User`나 `@MapsId User`가 필요하지 않다. 기존 180/120/90과 ≤10/11–12/≥13 구간을 유지한다.
- 두 테이블 모두 현재 version 컬럼이 없다. `@Version` 추가에는 별도 migration이 필요하다. 읽기 전용 008-1에 이를 필수로 추가하지 않는다. 향후 편집 경합 전략은 version 추가와 조건부 갱신을 검토한다.
- 기존 UUID·세대·이름·시각은 조회만으로 변경하지 않는다. 새 JPA 생성은 명시 생성 경로에서 UUID와 시각을 설정한다. createdAt은 변경 불가로 매핑하고, updatedAt은 서비스 Clock 등 명시 정책으로 갱신한다. JDBC 삽입의 DB 기본값은 그대로 유효해야 한다.
- assigned UUID/설정 PK는 Spring Data `save`의 신규 판단을 주의한다. 신규 삽입은 명시 `persist` 경로 또는 검증된 `Persistable` 전략을 사용한다. 기존 행 편집은 범위 조회 후 managed Entity 변경으로 처리해, 존재하지 않는 임의 ID의 merge 삽입을 방지한다. [공식 신규 판단 규칙](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)
- 초기 매핑은 V2 그대로 검증한다. FK·CHECK·부분 인덱스는 Flyway 책임이다. `ddl-auto=validate`는 소유권 인가나 모든 DB 제약 검증을 대신하지 않는다.

## 4. LAZY·조회·DTO 전략

모든 엔티티 연관관계는 명시적으로 LAZY를 기본으로 한다. `spring.jpa.open-in-view=false`를 설계 기본값으로 두며, 서비스의 읽기 트랜잭션 안에서 DTO 변환을 마친다. Entity·프록시를 HTTP 응답이나 Spring Session 속성으로 직렬화하지 않는다.

- 종목·설정: scalar DTO projection 또는 제한된 Entity 조회.
- 루틴 목록: 루틴 페이지와 해당 ID들의 배치 일괄 조회. 종목 표시명은 DTO join 또는 필요한 to-one fetch로 가져온다.
- 세션 목록: 날짜·집계 수치 DTO projection과 안정적 페이지 정렬. 목록마다 전체 performed_sets를 로드하지 않는다.
- 세션 상세: 세션 1회 + 세션 종목/유산소/세트 범위별 일괄 조회. 필요한 단일 관계만 fetch join/EntityGraph 적용.
- 여러 to-many 컬렉션을 한 번에 fetch join하지 않는다. 컬렉션 fetch join에 페이지네이션을 결합하지 않는다. batch fetch는 보조 수단이다.
- 로컬 초안, API request/response DTO, Entity, JSONB snapshot 타입을 분리한다. JSONB는 스키마 버전 있는 값 객체와 Hibernate JSON 타입 매핑을 검토하되 완료 수행을 중복 저장하지 않는다.
- 실제 SQL 수를 데이터 수 증가 전후로 측정한다. 1개·다수 루틴/세션에서 쿼리 수가 행 수만큼 증가하지 않는지 확인하고 OSIV 없이 렌더링한다. Hibernate 통계/SQL 관측은 격리 테스트에서만 켜며 바인드 로그로 인증·개인정보를 출력하지 않는다.

## 5. 트랜잭션 관리자 구성안과 혼용 경계

### 5.1 선택한 구현 후보: 단일 DataSource, 명시적인 두 관리자

**인증 트랜잭션 관리자를 자동으로 JPA로 교체하지 않는 안**을 우선 검증한다. 현재 인증 생성자들은 qualifier 없는 `PlatformTransactionManager`를 받으므로, 다음 구성으로 기존 선택을 보존하는 후보를 잡는다. 이는 검증 전 구성안이며 현재 bean 등록 사실이 아니다.

| 예정 bean / 사용처 | 설계 |
|---|---|
| `transactionManager` | 기존 Boot 설정과 동등한 JDBC 관리자(기본 JdbcTransactionManager), `@Primary`; 기존 사용자 지정 설정 여부 확인·보존 |
| `workoutTransactionManager` | 같은 DataSource/EntityManagerFactory를 사용하는 JpaTransactionManager, non-primary |
| 운동 JPA Repository | `transactionManagerRef="workoutTransactionManager"` 명시 |
| 운동 서비스 | `@Transactional(transactionManager="workoutTransactionManager")` 명시; 읽기는 readOnly |
| Spring Session JDBC | JDBC 관리자에 `@SpringSessionTransactionManager`로 명시 연결; 저장소의 기본 REQUIRES_NEW 유지 |

하나의 Hikari DataSource를 EMF·JDBC 관리자·JdbcTemplate·Spring Session이 공유한다. 별도 DB나 두 번째 pool을 만들지 않는다. 다중 관리자를 등록하면 Boot의 `ConditionalOnMissingBean(TransactionManager)` 자동 구성이 back off할 수 있으므로 두 관리자를 명시적으로 구성하고, 기본 JDBC 관리자도 남아 있을 것이라고 가정하지 않는다. 실제 cache의 Boot 4.1.2 `JpaBaseConfiguration` 및 JDBC 자동 구성 annotation을 읽어 이 조건을 확인했다.

이 구성은 인증 코드 전환을 피하지만 설정 영향은 있다. 모든 `PlatformTransactionManager` 주입 대상, Session 선택, Repository 선택을 컨텍스트 테스트로 증명해야 한다. 혼용 검증 실패 시 이 안을 검증 완료로 처리하지 않으며 설정 대안을 검토한다.

### 5.2 JPA와 JDBC가 한 작업에서 함께 쓰는 경우

운동 명령은 **workoutTransactionManager 하나**가 트랜잭션을 소유한다. 같은 DataSource의 JdbcTemplate은 JPA 관리자가 노출한 연결에 참여하도록 검증한다. EntityManager의 Hibernate `doWork`와 JdbcTemplate에서 `pg_backend_pid()`·트랜잭션 식별자를 비교하고, 커밋/예외/제약 위반 롤백을 DB의 독립 연결에서 관찰한다. 공식적으로 JpaTransactionManager는 이 혼용 경로를 지원하지만 [동일 DataSource·vendor dialect 등의 조건](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)이 있다.

- 운동 SQL 어댑터는 별도 JDBC TransactionTemplate을 시작하지 않고 호출자의 JPA 트랜잭션에 참여한다.
- 인증 JDBC TransactionTemplate 내부에서 JPA Repository를 호출하거나, 운동 JPA 트랜잭션에서 기존 인증 서비스를 호출하는 구조는 도입하지 않는다.
- 두 관리자의 REQUIRED/REQUIRES_NEW가 서로의 EntityManager·ConnectionHolder를 안전하게 중단/재개한다고 가정하지 않는다. 같은 DataSource라고 서로 다른 관리자가 하나의 원자적 작업을 자동 구성하지 않는다.
- JPA 변경 후 JDBC가 읽어야 하면 먼저 명시 flush한다. JDBC/native update 후 이미 로드한 Entity는 stale하므로 refresh 또는 안전한 clear·재조회 전략을 사용한다. 미flush 변경을 clear로 버리지 않는다. bulk/native 갱신은 `@Version` 자동 검증을 우회할 수 있다. [Spring Data 수정 쿼리 주의](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- JDBC SQL 오류/optimistic lock 예외가 난 트랜잭션을 잡아서 계속 사용하지 않는다. 실패 작업은 전체 롤백하고 충돌 응답을 바깥 경계에서 만든다. readOnly는 인가 장치가 아니다.
- 인증 메일/OIDC 네트워크 호출을 운동 트랜잭션이나 사용자·세션 잠금 안으로 끌어들이지 않는다. 웹 필터 전체를 트랜잭션으로 감싸지 않는다.

대안인 **단일 JpaTransactionManager로 기존 JDBC까지 관리**하는 구성도 프레임워크상 가능하다. 그러나 모든 인증 TransactionTemplate의 실제 관리자가 바뀌고 명시 READ_COMMITTED·연결 준비·예외 변환·Session 전파에 영향을 준다. 기존 JDBC 관리자 유지안보다 먼저 채택하지 않는다. HibernateJpaDialect의 isolation 지원은 connection release mode 등의 조건이 있으므로 이를 인증 호환성 확인 없이 전제하지 않는다. [Framework 7.0.10 실제 구현](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.10/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java)

### 5.3 Spring Session의 독립 트랜잭션

Session JDBC 4.1.1의 `JdbcHttpSessionConfiguration` 캐시 jar에서도 저장소 TransactionTemplate이 REQUIRES_NEW로 생성됨을 확인했다. [공식 설정 문서](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)의 설명과 일치한다. custom `springSessionTransactionOperations`로 REQUIRED나 무트랜잭션으로 바꾸지 않는다.

- 기존 JDBC 인증 트랜잭션 안에서 SessionRepository를 부르면 별도 트랜잭션/연결이 필요할 수 있다. 외부가 같은 세션 행을 잠근 채 내부 저장소를 호출하면 자기 대기나 pool 고갈이 생길 수 있다.
- 운동 JPA 트랜잭션 안에서도 SessionRepository의 교차 관리자 동작을 안전하다고 가정하지 않는다. 인증 저장소 호출을 운동 명령 내부에 두지 않고, 저장소 필터의 실제 호출 시점도 회귀 테스트한다.
- REQUIRES_NEW 결과는 외부 롤백과 원자적으로 묶이지 않는다. 세션 생성·삭제가 별도로 커밋될 수 있는 점을 보안 성공 응답과 구분한다.
- 풀 크기를 키워 잠금 회귀를 숨기지 않는다. 기존 OAuth 1-connection 테스트와 독립 트랜잭션의 추가 연결 요구를 각각 검증한다. 상위/하위가 같은 행을 대기하는 구조는 연결 수만 늘려 해결되지 않는다.

## 6. 기존 인증의 반드시 보존할 코드 경계

| 코드 | 현재 동작 | JPA 도입 후 필수 보장 |
|---|---|---|
| AccountProvisioningService | JDBC REQUIRES_NEW + READ_COMMITTED; 신원 경쟁 loser 전체 롤백 | 기존 사용자/seed 보존, 중복 seed 없음, 인증 호출 밖 JPA 쓰기 없음 |
| JdbcAuthorizationRequestRepository | 익명 session 행 FOR UPDATE → 같은 연결로 pending 읽기 → session 삭제 커밋 → HTTP session 무효화 | 단 한 번 소비; provider 교환 전 커밋; 잠금 안 SessionRepository.findById 호출 금지 |
| GoogleBoundRequests | identity 검증 → session 잠금 → pending 속성 원자적 DELETE 후 커밋 | REAUTH/CONTACT/LINK 목적·소유자·증명 바인딩 및 replay 차단 유지 |
| OtpChallenges / ReauthenticationEmails | JDBC TransactionTemplate, 실패 예산·한 번 소비·대표 이메일 | 같은 트랜잭션의 잠금/롤백과 메일 발송 경계 유지 |
| ReauthenticationSessionRepository | OTP 트랜잭션 연결로 session 잠금·신규 session/증명 삽입·이전 session 삭제 | OTP 소비와 DB session 회전이 함께 롤백/커밋 |
| SessionReauthentication.complete / VerifiedSessionLogin | DB 커밋 후 저장소 재확인, 쿠키 발행/최종 검증, 실패 시 fail closed | callback/쿠키만으로 성공 선언 금지, 이전 session 재등장 금지 |
| EmailIdentityService / GoogleIdentityService | 사용자·identity·session의 기존 잠금 순서와 증명 검증 | 연결/해제·마지막 수단 보호·과거 인증 참조 보존 |

현재 Google LOGIN success handler는 활성 트랜잭션이 없는지도 검사한다. 이 조건을 바꾸지 않는다. OAuth 요청 소비를 JPA 서비스의 지연 flush로 옮기거나 소비와 외부 provider 교환을 한 트랜잭션으로 묶지 않는다. existing raw BYTEA 속성·SessionPrincipal 직렬화 형식에도 JPA 객체를 추가하지 않는다.

## 7. 루틴·세션 @Version과 원자적 명령

### 7.1 적용 가능 범위

신규 Routine과 WorkoutSession 집계 루트에 `@Version Long version`을 설계한다. DB 컬럼·초기값은 후속 Flyway에서 정의한다. 버전은 로컬 문서 스키마 버전·dataGeneration·epoch와 별개다.

1. 소유자·현재 세대·활성 상태를 확인한 루트를 로드하고 request의 expectedVersion을 비교한다. 오래된 요청을 최신 로드 version으로 조용히 재기준화하지 않는다.
2. 자식 변경만 있어도 부모가 한 번 갱신되도록 명령마다 보장한다. 부모의 명시 변경과 `OPTIMISTIC_FORCE_INCREMENT` 중 한 전략을 선택하고 실제 SQL·ACK version을 테스트한다. 자식 저장만으로 부모 @Version이 증가한다고 가정하지 않는다.
3. flush/commit의 optimistic 충돌은 모든 수행·사건·영수증과 함께 롤백한다. 재시도로 사용자 의도를 자동 덮어쓰지 않는다.
4. 승인 중량 변경이 관련 진행 세션도 수정하면 Routine과 Session의 기대 version을 모두 확인하고 같은 트랜잭션에서 변경한다. 복수 잠금 시 순서를 고정한다.
5. force increment의 실행·version 반영 시점은 provider/lock 방식에 따라 다를 수 있다. flush 호출만으로 최종 ACK version이 준비됐다고 단정하지 않는다. DB 조건부 갱신/RETURNING으로 새 version을 확정하는 SQL 방식도 후보이며, SQL과 managed root의 이중 증가를 금지한다.

@Version은 operationId 중복, epoch 제어권, 세대, 계정 소유권, 논리 삭제 재등장을 해결하지 않는다. bulk update, native SQL, 배치 재정렬의 각 경로에서도 부모 version 증가·기대값 검증을 직접 보장한다.

### 7.2 필요한 DB 제약과 처리 순서

- `sync_operations`: `(user_id, data_generation, operation_id)` UNIQUE; fingerprint NOT NULL, 명령 종류/형식 버전·적용 결과·결과 version 보존. fingerprint는 봉인된 명령 종류·payload·기대 versions·epoch 등 의미 전체를 포함한다. canonical 직렬화·해시 알고리즘·응답 DTO는 남은 기술 계약이다.
- 같은 키의 같은 fingerprint는 중복 적용하지 않고 저장 영수증으로 확인한다. 같은 키의 다른 fingerprint는 충돌로 거절한다. 매번 바뀌는 서버 현재 version만으로 이미 커밋된 동일 명령을 미적용으로 오인하지 않는다. 재전송에도 인증·소유자·세대 접근 검증은 생략하지 않는다.
- 작업 예약은 JPA를 우선 검토하되 unique 충돌 뒤 같은 실패 트랜잭션에서 조회하는 방식을 쓰지 않는다. 필요 시 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`으로 경쟁 예약하고, 충돌 명령의 커밋/롤백 후 결과를 판정한다. IN_PROGRESS 영수증만 따로 커밋하지 않는다.
- 같은 트랜잭션에서 루트의 expectedVersion·ownershipEpoch·수정 기기·deleted_at·서버 현재 세대를 검증하고, 수행/승인값·사건·부모 version·성공 영수증을 저장한다. ACK 성공은 커밋 이후다. 실패는 전부 롤백한다.
- 세션 epoch는 비음수 BIGINT 계열, 현재 수정 기기는 nullable 여부를 생성 계약에서 정한다. 수정 시 소유자·세대·version·epoch·기기를 함께 검사한다. 제어권 변경은 같은 행의 epoch 증가와 원자적으로 처리한다. CHECK만으로 사용자 인가·이전 상태 전이를 증명할 수 없다.
- `(user_id,data_generation,id)` 등 필요한 FK 경계, 활성 배치 순서/세트 ordinal 부분 UNIQUE, 수치 CHECK, tombstone을 Flyway로 정의한다. 이름 UNIQUE와 `(routine_id,exercise_definition_id)` UNIQUE는 금지한다. @Table 제약 annotation은 Flyway를 대체하지 않는다.
- 구 epoch 미전송 명령에 최신 epoch를 자동 부착하지 않는다. 명시 선택 복구와 새 operationId만 허용한다. 이미 적용된 명령의 영수증 조회와 구 epoch의 새 쓰기는 구분한다.

## 8. 직접 SQL이 더 적합할 수 있는 구간

| 구간 | 우선 검토 / SQL 사용 이유 | 혼용 조건 |
|---|---|---|
| 일반 CRUD·부모 @Version·범위 조회 | JPA 기본 | 서비스 트랜잭션·DTO 경계 |
| 단일 루트 잠금 | JPA LockMode/조건 조회 우선 | owner/generation/epoch 조건 누락 금지 |
| 멱등 명령 경쟁 예약 | native/JDBC ON CONFLICT | unique 예외로 tx가 실패한 뒤 계속 처리하지 않기 |
| version+epoch 조건부 갱신과 새 값 반환 | native/JDBC UPDATE ... RETURNING | 영향 행 수 검증, JPA managed 상태 동기화 |
| 여러 활성 순서의 교환·복원 | JPA 우선, 부분 UNIQUE 충돌 시 SQL 검토 | 같은 집계 잠금·version, 중간 상태 제약 테스트 |
| 사용자 현재 세대 행 잠금 | 제한된 SQL 조회 가능 | users를 JPA로 전환하지 않고 같은 운동 tx 연결 사용 |
| 공식 기준·대량 이력 집계/pull | JPA projection 우선, 필요 시 native 조회 | 유효 수행·삭제·계정·세대 필터 동일 |
| 기존 OTP/OAuth/session 원자적 SQL | 기존 JDBC 유지 | 이번 운동 설계로 변경하지 않음 |

필요한 native/JDBC만 custom Repository fragment에 모은다. 제2의 범용 CRUD 계층이나 별도 TransactionTemplate을 운동 SQL 어댑터에 추가하지 않는다.

## 9. Flyway 단독 스키마 관리 설정안

아래 값은 **향후 구현 설정안**이며 현재 application.yaml을 수정하지 않았다.

```yaml
spring:
  jpa:
    open-in-view: false
    generate-ddl: false
    hibernate:
      ddl-auto: validate
  sql:
    init:
      mode: never
  session:
    jdbc:
      initialize-schema: never
  flyway:
    clean-disabled: true
    baseline-on-migrate: false
    validate-on-migrate: true
```

기존 Session flush-mode immediate 등 다른 설정을 삭제·교체하지 않는다. Hibernate/JPA의 직접 schema-generation action, test profile의 create-drop, import.sql, schema.sql 등 우회 설정도 점검한다. Flyway 적용 후 Entity 검증 순서를 컨텍스트에서 확인한다. [Boot 초기화 문서](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

008-1의 기존 두 테이블 매핑에는 migration이 필요하지 않다. 새로운 루틴·세션 Entity를 미리 전체 scan에 넣어 존재하지 않는 테이블의 validate 실패를 만들지 않는다. V1~V8 checksum과 기존 행을 보존한다. Hibernate validate는 읽기 검증이며 스키마 오류를 update/create로 우회하지 않는다.

## 10. 008-1 전 최소 통합 검증 계획

### 10.1 실행 경계

이번에는 **테스트 코드를 작성하거나 아래 테스트를 실행하지 않는다**. 후속 구현에서 JPA dependency·설정·최소 매핑을 넣은 뒤, 운동 기능 확장 전에 통과해야 하는 게이트다.

- Testcontainers PostgreSQL 18.6의 전용 빈 DB·임의 포트·합성 계정만 사용한다. local `.env` 개발 DB를 사용하지 않는다.
- 후속 검증에서 기존 V1~V8만 임시 DB에 적용한다. 신규 migration 없이 exercise_definitions/settings와 시험용 Entity로 혼용을 확인한다. 미래 version/epoch 모델은 별도 테스트 전용 fixture 스키마로 먼저 검증할 수 있다. 시험 fixture는 제품 migration으로 취급하거나 배포하지 않는다.
- mock Google provider·fake OTP sender 사용. 실제 Google·Resend·실제 사용자 계정 접근 없음.
- 격리 검증은 테스트가 만든 프로세스/컨테이너만 정리하고 종료를 확인한다. 이번 요청의 migration 금지 상태에서 지금 실행하지 않는다.

### 10.2 최소 게이트

| ID | 검증 | 통과 기준 |
|---|---|---|
| TX-01 | 혼합 컨텍스트·의존성·bean 선택 | Boot 4.1.2 BOM 실제 해석값 일치; DataSource 1개; EMF·JDBC·Session 같은 DataSource; unqualified manager는 기존 JDBC, 운동 Repository/서비스는 JPA, Session은 JDBC |
| TX-02 | V2 기존 행 매핑·DDL 금지 | JDBC seed를 JPA로 동일 UUID/세대/이름/설정/시각 조회; 재시작 시 행·schema·V1~V8 checksum 불변; 없는 컬럼 검증 실패에도 자동 생성/변경 없음 |
| TX-03 | JPA+JDBC 한 물리 tx의 commit/rollback | Hibernate doWork와 JdbcTemplate의 backend PID/tx ID 일치; JPA→JDBC·JDBC→JPA 양방향 쓰기의 정상 커밋 및 강제 예외/제약 위반 시 전부 롤백 |
| TX-04 | flush·native update·rollback-only | flush 전후 가시성, native 후 refresh/재조회, unique 실패 후 새 tx 복구; stale Entity가 SQL 결과를 덮어쓰지 않음 |
| TX-05 | 기존 AccountProvisioning REQUIRES_NEW | 신규 합성 계정 20종목/1설정; 동일 identity 재진입 불변; 경쟁 loser 롤백. JDBC 외부 tx와 분리된 내부 커밋/롤백·재개 확인; 운동 tx에서 호출하지 않음 |
| TX-06 | Spring Session 독립 tx | 추가 연결을 허용한 제한 pool에서 기존 JDBC 외부 tx 중 REQUIRES_NEW가 다른 연결로 실행되고 외부 롤백과 독립임을 관찰; readOnly 호출·필터 저장 경계 정상. 실제 요청에서 활성 JPA tx 중 직접 저장소 호출이 없는지도 관측 |
| TX-07 | OAuth pending 소비·행 잠금 | 실제 기존 repository 사용; 동시 callback 중 소비 1회; provider 교환 전 소비 커밋; 1-connection pool에서도 기존 경로 성공; 실패·취소·재전송에 소비 복구/재등장 없음 |
| TX-08 | OTP와 session rotation 원자성 | 기존 service 사용; 회전 중 강제 실패 시 OTP/대표 이메일/신규 session 함께 롤백; 성공 시 구 session 무효·proof/CSRF/쿠키·절대 만료 유지 |
| TX-09 | 소유권·세대·최소 조회 성능 | A의 ID로 B 조회/수정 불가, 다른 generation 연결 차단; OSIV 없이 DTO 완성; 다수 루틴에 N+1 없는 조회 전략은 해당 Entity 구현 단계에서 추가 |
| TX-10 | 명령 경합 fixture | 루트 자식만 수정해도 version 증가; 동일 expectedVersion 경쟁 1회 성공; 같은 operation/fingerprint 1회 적용, 다른 fingerprint 거부; epoch 변경과 쓰기 경합·사건/영수증 동시 롤백 |

TX-01~08은 기존 두 테이블 매핑과 실제 인증 경로로 008-1 이전에 검증한다. TX-09는 008-1 조회 게이트이며, 미래 관계/성능 부분은 해당 단계에 추가한다. TX-10은 최소 시험 모델로 알고리즘을 확인하고 실제 Routine/Session/SyncOperation 구현 후 다시 검증한다. 시험 모델 성공만으로 제품 명령 호환성 완료를 선언하지 않는다.

테스트 이름 후보: `JpaJdbcTransactionCompatibilityIntegrationTests`, `JpaExistingWorkoutMappingIntegrationTests`, `PersistenceTransactionWiringTests`. 기존 `postgres` 태그/Gradle integrationTest 구조를 활용한다. 모든 테스트를 `@Transactional`로 감싸 자동 롤백시키면 독립 커밋·REQUIRES_NEW·경합 문제를 가릴 수 있으므로, 해당 시나리오는 명시적 트랜잭션과 독립 관측 연결을 쓴다. 시간 대기 대신 latch/barrier와 제한된 timeout으로 잠금 순서를 검증한다.

### 10.3 인증 회귀 범위와 기존 테스트 보완점

JPA를 켠 실제 혼합 설정으로 다음 기존 회귀를 전부 재실행한다. JPA 자동설정을 제외한 기존 인증 컨텍스트만 통과한 결과를 혼용 검증으로 세지 않는다.

- `AccountProvisioningIntegrationTests`, `PostgresMigrationTests`, `SecuritySessionIntegrationTests`
- `EmailOtpIntegrationTests`, `EmailReauthenticationIntegrationTests`, `CommonReauthenticationEmailIntegrationTests`
- `EmailIdentityIntegrationTests`, `GoogleIdentityIntegrationTests`
- `GoogleOidcIntegrationTests`, `GoogleReauthenticationIntegrationTests`
- `GoogleContactFrontendIntegrationTests`, `GoogleLinkFrontendIntegrationTests`
- 기존 단위·설정·직렬화 호환성 테스트 및 frontend `npm test/lint/build` (연동 변경 시)

특히 현재 `OverloadServerApplicationTests`는 DataSource/Flyway 자동설정을 제외하고 JdbcTemplate·PlatformTransactionManager·SessionRepository를 mock한다. JPA 추가 후 DB 없는 이 테스트의 목적을 유지할 설정 보완이 필요할 수 있다. 이를 위해 모든 테스트에서 JPA를 꺼 회귀를 숨기지 않는다. 별도의 실제 혼합 컨텍스트 테스트는 필수다. MVC slice·mock bean의 다중 관리자 모호성도 점검한다.

실행 순서는 **후속 사전 검증 작업에서 dependency·최소 설정·기존 테이블 매핑 준비 → TX-01~08 및 인증 회귀 실행 → 통과 후 008-1 조회/API 구현**이다. 사전 검증 자체에 필요한 의존성과 최소 매핑을 게이트 통과 뒤로 미루지 않는다. 현재 요청은 설계 단계이므로 지금 이 순서를 실행하지 않으며, 구현·격리 테스트 실행 범위는 후속 지시에서 확인한다.

## 11. 데이터 모델·API 계획에 미치는 영향

Task 006의 테이블 역할·UUID·소유권·세대·스냅샷·승인 정책·멱등성·단일 수정 기기 정책은 유지한다. 기존 API 후보 경로를 JPA Entity 구조에 맞춰 바꾸지 않으며, 엔티티 연관관계를 그대로 JSON으로 노출하지 않는다. request에는 userId 쓰기 권한을 주지 않는다.

| 기존 API 후보 | JPA 설계 적용 |
|---|---|
| GET `/api/v1/exercises`, GET `/api/v1/workout-settings` | 기존 V2 매핑을 owner+generation으로 읽어 DTO 반환; 008-1 최소 범위 |
| 종목·루틴·배치·일정·메모의 조회/편집 | 운동 JPA 서비스, 부모 version 및 DB 소유권 제약; 제거/복원과 단순 이동 구분 |
| 세션 생성·세트/유산소·종료·사후 정정 명령 | 명령 DTO → JPA 집계 트랜잭션 → 커밋 후 ACK; Entity merge를 API 수정 계약으로 사용하지 않음 |
| 이력·공식 평가 조회 | 현재 유효 수행의 DTO projection과 근거 세션; 당시 스냅샷과 현재 평가 분리 |
| bootstrap/pull/제어권 이전/선택 복구 | 도메인 ID·version·generation·epoch·operationId 계약 유지; JPA/SQL 구현은 외부 계약과 분리 |

위 경로·요청·응답은 후보이며 아직 확정된 API가 아니다. 008-1에서도 기존 auth/session 응답을 임의 확장하지 않고, 운동 읽기 DTO에서 필요한 세대 정보를 제공하는 계약을 검토한다.

008-1은 종목·설정 읽기와 DTO 연결로 제한한다. 그 전에 본 문서의 혼용 게이트를 둔다. 이후 루틴/배치 → 승인 → 세션/공식 평가 → 이력 정정 → IndexedDB/Outbox 연결 순서를 유지한다. 실제 기록 UI는 원자적 로컬 저장과 서버 멱등 명령 준비 후 연결한다. 데모 localStorage 자동 이전·업로드와 기존 계정 seed 재생성은 금지한다.

남은 기술 계약은 숫자 정밀도/직렬화, offline UUID 생성, snapshot JSON 구조, 명령 fingerprint/cursor/오류 DTO, 다중 집계 기대 version 및 최종 ACK version 생성 방식이다. 두 관리자 구성은 런타임 게이트 통과 전까지 **검증 대기 구현 후보**다. 기존 인증 정책이나 Task 006의 제품 결정을 다시 결정하는 항목은 아니다.

## 12. 이번 작업 검증 및 미실행 사항

- 현재 빌드·V2·보안/계정/OAuth/OTP 소스 및 관련 테스트 경계를 읽었다.
- 공식 Boot 관리 좌표, 로컬 Boot/Data BOM, Boot/Session/Framework 캐시 jar의 API·bytecode를 읽기 전용으로 대조했다. 캐시 존재를 이 프로젝트의 도입 완료로 해석하지 않았다.
- 공식 자료: [JPA 트랜잭션 경계](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html), [전파·독립 트랜잭션](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
- 새 의존성 해석·테스트·빌드·애플리케이션 시작·Flyway 실행·개발 DB 접속은 하지 않았다. 따라서 새로운 테스트 통과 수치나 런타임 호환성 성공을 보고하지 않는다.
- 문서 변경만 `git diff --check`와 링크/범위 검사로 확인한다. 제품 코드·인증 코드·의존성·설정·migration·기존 사용자 데이터는 이번 작업 대상이 아니다.
