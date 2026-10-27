# Task 007-1A 구현·검증 결과

- 검증일: 2026-10-27 (Asia/Seoul)
- 결과: **완료**. 격리된 실제 PostgreSQL의 최초 마이그레이션·재실행과 Compose 개발 DB 연동을 검증했다.
- 범위: 사용자 요청의 Docker Compose 로컬 PostgreSQL + 제공된 007-1A 지시서의 JDBC/Flyway/최소 users/통합 테스트.
  제공 문서는 작업 범위 참고 지시서이며 과거 실행 결과나 시스템 변경·Commit/Push 승인으로 해석하지 않았다.
- 기준: Task 006 최종 설계 v0.3.1. 확정 운동·인증·저장·동기화 정책 변경 없음.

## 시작 상태와 환경

| 항목 | 확인 결과 |
|---|---|
| 브랜치 / HEAD | `main` / `5ea3098a8923a047947481f33c7999102ec8b512` |
| 시작 작업 트리 | 서버·프론트 모두 clean |
| Java | 기존 설치 Temurin 25.0.4.1, 실행 프로세스에만 JAVA_HOME 지정 |
| Gradle Wrapper / Boot | 9.8.2 / 4.1.2, 기존 고정 버전 유지 |
| Docker Desktop / Engine | 4.97.0 / 29.9.2, `desktop-linux` |
| Docker Compose | 5.6.1 |
| WSL | 2.6.1.0, 기본 WSL2 Ubuntu-22.04 중지 상태 / docker-desktop 실행 중 |
| Windows PostgreSQL | psql 명령과 PostgreSQL Windows 서비스 미발견; 직접 설치하지 않음 |
| 기존 PostgreSQL | `welog-postgres`, `postgres:17-alpine`, 호스트 5432 사용 중; DB 접속·변경 없음 |
| 새 개발 포트 | 5433 사용 가능 확인 후 `127.0.0.1:5433`에 바인딩 |

기존 컨테이너 6개(`welog-nginx`, `welog-redis-insight`, `welog-postgres`, `welog-redis`,
`welog-minio`, 종료 상태의 `k6-k6-1`)와 기존 볼륨 6개를 먼저 확인했다.
검증 종료 시 동일 컨테이너 ID·기존 실행/종료 상태 및 기존 볼륨이 보존됨을 확인했다.
기존 DB 내부 데이터에는 접근하지 않았다. 중지된 Ubuntu 배포판을 시작하거나 내부 PostgreSQL을 조회하지 않았다.

## 변경 파일 전체

수정:

- `build.gradle`: JDBC/Flyway/드라이버/Testcontainers, DB 없는 test와 Docker 통합 테스트 분리, check/build에 통합 테스트 포함.
- `src/main/resources/application.yaml`: Flyway clean 차단·자동 baseline 금지·검증 활성화, 기본 SQL 초기화 비활성화.
- `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java`: 이 테스트에만 DB/Flyway 자동설정 제외.
- `README.md`: 현재 DB 도입 상태, local 실행·중지·테스트 명령 갱신.
- `docs/engineering/backend.md`: Flyway/Testcontainers 도입 상태와 로컬 실행 문서 링크.

신규(아직 미추적):

- `.env.example`: 비밀번호 없는 로컬 환경변수 예시.
- `compose.yaml`: PostgreSQL 18.6, 루프백 포트, 전용 named volume, healthcheck.
- `src/main/resources/application-local.yaml`: 로컬 .env 외부 설정 연동.
- `src/main/resources/db/migration/V1__create_users.sql`: 최소 users 테이블.
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`: 실제 DB 테스트 3개.
- `docs/engineering/local-postgresql.md`: 재현·접속·시작·중지·문제 진단·스키마 정책.
- `docs/tasks/task-007-1a-validation-report.md`: 이 보고서.

로컬 전용 `.env`에는 새 임의 비밀번호를 생성했으며 기존 .gitignore 규칙으로 제외됨을 확인했다.
실제 비밀번호는 보고서/Git 대상에 포함하지 않는다. 빌드·테스트 XML·검증 로그는 제외된 `build/`에 있다.

## DB 구성과 의존성

개발용 Compose는 프로젝트 `overload-local`, 컨테이너 `overload-local-postgres-1`,
볼륨 `overload-local_postgres_data`만 새로 생성했다. `/var/lib/postgresql` 마운트는 PostgreSQL 18의
공식 이미지 데이터 경로에 맞춘 것이다. 기존 `postgres_pg_data` 및 익명 볼륨은 사용하지 않았다.

실제 서버는 **PostgreSQL 18.6**이다. 이미지 `postgres:18.6-alpine`의 검증 시 digest:
`sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5`.

Gradle `testRuntimeClasspath` 해석과 Boot 4.1.2 BOM을 대조했다:

| 의존성 | 실제 해석 버전 |
|---|---|
| spring-boot-starter-jdbc / spring-boot-starter-flyway | 4.1.2 |
| flyway-core / flyway-database-postgresql | 12.4.0 |
| PostgreSQL JDBC | 42.7.14 |
| Testcontainers PostgreSQL / JUnit Jupiter | 2.0.5 |

각 버전을 별도로 고정하거나 기존 Boot/Gradle을 업그레이드하지 않았다.
JPA·Security·OTP·Spring Session 의존성은 추가하지 않았다.
테스트는 Docker가 실제 사용 가능하므로 Testcontainers를 선택했다. 각 테스트에 빈 DB와 임의 포트를
배정하고 개발 Compose DB 및 기존 사용자 DB와 분리했다. Docker 부재 시 건너뛰거나 H2로 대체하지 않는다.

## 첫 마이그레이션

`users`만 생성하며 seed는 없다. 기본 키 외 추가 FK/UNIQUE는 현 단계에 필요하지 않다.

| 컬럼 | 타입 / NULL / 기본값 |
|---|---|
| id | UUID PK, NOT NULL, 기본값 없음; 후속 서버 코드가 생성 |
| data_generation | BIGINT, NOT NULL, DEFAULT 0, CHECK >= 0 |
| nickname | TEXT, NULL 허용, 기본값 없음 |
| created_at | TIMESTAMPTZ, NOT NULL, DEFAULT CURRENT_TIMESTAMP |
| updated_at | TIMESTAMPTZ, NOT NULL, DEFAULT CURRENT_TIMESTAMP |

data_generation은 후속 서버 초기화 트랜잭션의 관리 대상이며 초기화/API 자체는 미구현이다.
updated_at은 삽입 시만 기본값을 제공하며 후속 갱신 코드가 유지해야 한다. 자동 갱신 트리거는 없다.
이후 스키마 변경은 새 버전 SQL로 수행한다.

개발 DB는 적용 전에 public 테이블 **0개**임을 확인했다. 적용 후, 컨테이너 재시작 후,
앱 재실행 후 모두 다음 1건을 유지했다:

| installed_rank | version | script | checksum | success |
|---|---|---|---|---|
| 1 | 1 | V1__create_users.sql | 1980645960 | true |

개발 users 행 수는 계속 0이다. 앱 재실행은 `Schema "public" is up to date. No migration necessary.`로 종료됐다.
테스트에서는 별도 일회성 DB에 삽입한 사용자가 앱 컨텍스트 재시작 후 보존되는 것도 검증했다.

## 검증 결과와 오류 구분

| 실행 / 검증 | 결과 |
|---|---|
| 변경 전 `gradlew.bat --no-daemon test` | 성공, 기존 캐시로 UP-TO-DATE; 새 실행 증거는 아래 변경 후 실행 |
| `gradlew.bat --no-daemon test integrationTest build` | **BUILD SUCCESSFUL, 8개 task 실제 실행** |
| test XML | 컨텍스트 1 + MVC Health 2 = 3개, 실패/오류/skip 0 |
| integrationTest XML | 실제 PostgreSQL 3개, 실패/오류/skip 0 |
| 최초 적용 / 재시작 / 제약 | 빈 DB V1 적용, 동일 이력·사용자 보존, 추가 적용 0, UUID PK·NULL·음수 generation 거부 |
| `docker compose config --quiet` | 성공, 비밀값 펼침 없이 설정 검증 |
| `docker compose up -d --wait --wait-timeout 120 postgres` | 성공, healthy |
| 패키징 jar + `local` 프로필 실행 | 성공, Compose DB 연결·최초 Flyway 적용 |
| 실제 HTTP | GET 200 + 정확한 JSON, POST 405 (테스트 DB와 개발 DB 각각 확인) |
| `docker compose stop postgres` 후 up 및 local jar 재실행 | 성공, 볼륨·마이그레이션 이력 보존, 추가 적용 없음 |
| `gradlew.bat --no-daemon --offline dependencies --configuration testRuntimeClasspath` | 성공, 실제 의존성 버전 확인 |
| `git diff --check` 및 신규 파일 whitespace 점검 | 성공 |
| Git 비밀값 제외 / 프론트 상태 | `.env` ignored, 프론트 변경 없음 |

최초 제한 샌드박스에서는 Docker named pipe, WSL 및 Gradle 전역 캐시 잠금파일 접근이 거부됐다.
동일 목적의 실행 권한 확장으로 해결했으며 Docker/WSL·Windows 서비스·영구 환경변수 변경은 없었다.
애플리케이션 컴파일/테스트/빌드 실패나 미해결 검증 오류는 없다.
미구성 lint·운영 환경 검증·후속 인증/업무 기능 검증은 수행했다고 주장하지 않는다.

## 종료 상태와 후속 단계

- 검증용 Java PID 99476 및 107472 종료 확인. 일회성 Gradle daemon과 테스트 worker는 작업 완료 후 종료했다.
- Testcontainers PostgreSQL/Ryuk 컨테이너와 테스트 볼륨이 남아 있지 않음을 최종 목록으로 확인했다.
- 개발 PostgreSQL은 `stop` 후 **Exited (0)**. 컨테이너·영속 볼륨·로컬 .env는 재사용을 위해 유지했다.
- 기존 컨테이너/볼륨 삭제·기존 DB 변경·WSL 직접 설치·시스템 설정 변경·운영 연결/배포 없음.
- 프론트 코드/CSS/설정과 브라우저 localStorage·목업 데이터 접근/변경 없음. Health 소스 및 기존 계약 유지.
- Commit·Push·staging 미실행. 새 파일 7개는 검토 가능한 미추적 상태다.
- 승인 상태: 현재 요청 범위로 수행했으며 추가 사전 승인 필요 작업/대기 작업 없음.

007-1B의 DB 기반 장애물은 없다. 다음 작업에서는 별도 승인된 범위 안에서 계정/신원 테이블의 구체 DDL과
고유키, 서버 UUID 생성·계정 생성 트랜잭션 및 사용자별 기본 종목 seed를 구현·검증해야 한다.
**기본 종목 이름 목록과 기본 휴식 설정값은 아직 준비가 필요**하며 실제 seed 정의/테스트로 명시해야 한다.
루틴·일정·기록을 비워 두는 Task 006 정책과 계정별 격리·원자적 생성 테스트를 유지한다.
007-1B 및 인증/운동/동기화 후속 구현은 이번 변경에 포함하지 않았다.
