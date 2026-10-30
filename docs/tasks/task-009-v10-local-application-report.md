# Task 009 — 개인 로컬 DB V8→V9→V10 실제 적용

- 적용일: 2026-10-31 (Asia/Seoul), 적용 후 최종 읽기 확인 00:17:08.
- 기준: [Task 009 v1.0](task-009-redesign-v1.0.md). 이 최종 보고서에 정식 V10·503개 격리 회귀·백업 복원·실제 적용의 필수 근거를 모았다.
- 사용자 명시 승인 범위: 기존 `overload-local-postgres-1`, `127.0.0.1:5433/overload_dev`, V8→V9→V10. 다른 DB·프로젝트·공유/운영 환경은 제외했다.
- 결과: **V9/V10 적용 성공, 현재 V10, 원본 14개 테이블 데이터와 기존 V1~V8 이력 보존, validate 성공, pending 0, 재migrate 적용 0**.

## 1. 적용 직전 확인

| 확인 항목 | 실제 결과 |
|---|---|
| PostgreSQL / DB / schema / 연결 role | 18.6 / overload_dev / public / overload_dev |
| 컨테이너 ID | a3901933942976a1400f9f2f40b07035126860434d9634e8a2179a29233a5e1b, 이전 감사와 동일 |
| 컨테이너·이미지·Compose | overload-local-postgres-1 / postgres:18.6-alpine / overload-local, postgres |
| 기존 볼륨 / 마운트 | overload-local_postgres_data / /var/lib/postgresql, 재생성·초기화 없음 |
| 바인딩 | 127.0.0.1:5433 → container 5432. 다른 프로젝트의 호스트5432 접속 없음 |
| 최초 상태 | exited. 기존 컨테이너만 start했고 작업 후 다시 stop |
| Flyway 이력 | V1~V8 성공8개, checksum·script 일치. V9/V10/후속 이력 없음 |
| 데이터 최신성 | 검증된 V8 백업 snapshot과 14개 테이블의 기존 모든 컬럼·행 지문·건수가 일치. 새 백업 불필요 |
| 암호화 백업 | 파일 존재, 암호화 SHA-256 일치, 현재 Windows 계정 DPAPI 복호화·평문 SHA-256 일치 |
| 백업 접근 | Git 밖의 기존 제한 디렉터리 유지. 현재 Windows 사용자·SYSTEM만 접근 허용. 평문 파일 생성 없음 |
| 다른 client / 장기 tx / 관련 잠금 | 모두0. 전체 activity 열람 권한 확인 |
| 외부 작업·예상 밖 의존성 | 프로젝트 관련 Windows 예약 작업0, 추가 extension·publication·replication slot·사용자 view/materialized view/trigger/function0 |
| SQL 보호 | V1~V9는 HEAD 원문과 동일. 정식 V9/V10은 격리 검증 당시 SHA-256 및 Flyway checksum과 일치 |

DB 읽기는 READ ONLY·REPEATABLE READ 트랜잭션으로 수행하고 ROLLBACK으로 종료했다. Flyway 사전 검증 후에도 실제 쓰기 직전에 데이터·접속·잠금 조건을 다시 확인했다. 원문 사용자/인증/세션 값·비밀번호·명령 payload·SQL 예외 원문을 보고서나 로그로 출력하지 않았다.

## 2. 실행 및 Flyway 이력

Spring Boot/HTTP 서버를 실행하지 않고 기존 프로젝트 의존성을 사용하는 독립 Java 도구에서 대상을 고정한 Flyway를 명시적으로 실행했다. target10, group(false), outOfOrder(false), cleanDisabled(true), baselineOnMigrate(false), validateOnMigrate(true)를 사용했다. SQL·테스트·설정을 변경하지 않았다.

| Version | Script | Checksum | Success |
|---|---|---:|---|
| 1 | V1__create_users.sql | 1980645960 | true |
| 2 | V2__create_account_provisioning.sql | -671956178 | true |
| 3 | V3__create_spring_session.sql | -766153280 | true |
| 4 | V4__create_email_otp.sql | 1645014211 | true |
| 5 | V5__add_email_reauthentication.sql | 1315488907 | true |
| 6 | V6__add_reauthentication_emails.sql | 788021689 | true |
| 7 | V7__preserve_retired_email_otp_identity_references.sql | 1264650683 | true |
| 8 | V8__preserve_google_unlink_references.sql | -1446403908 | true |
| 9 | V9__add_workout_write_foundation.sql | 187413715 | true |
| 10 | V10__remove_obsolete_workout_command_storage.sql | -2058343517 | true |

V9 SHA-256: `cb39eb1b6df35ebf0723877ea5c776919be5790d673c75c9ab8691147f546e79`.

V10 SHA-256: `935383f75dadf83b1abbae54ae411d752e2d2ea20297fe8fb62c4750f6fbcc2b`.

실제 변경을 실행하는 migrate 호출은 한 번이며 V9→V10 두 개를 적용했다. 성공 후 전체 데이터 보존·validate·pending0을 확인한 다음 두 번째 migrate 호출로 적용0을 확인했다. 실패 migration 재시도는 없었다. clean·repair·이력 수정·수동 보정·자동 복원·CASCADE·DB 초기화는 실행하지 않았다.

### 사전 검증 도구 오류와 처리

최초 사전 검사에서는 독립 validate()가 미적용 migration을 기본적으로 오류 처리하는 동작 때문에 중단했다. 이때 migrationAttempted=false였고 실제 DB는 V8 그대로였으며 컨테이너를 다시 정지했다. DB 데이터나 checksum 오류로 확인된 것은 아니었다.

Flyway의 로컬 공식 소스에서 validate()와 migrate()의 pending 처리 차이를 확인했다. 검사 도구만 수정해 target8 validate로 적용된 V1~V8을 검증하고, target10 validateWithResult의 오류가 정확히 V9·V10 두 건의 RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED인지, 실제 pending 상태·순서·checksum도 정확한지 확인했다. 다른 오류를 무시하는 설정은 추가하지 않았다. 백업·컨테이너·DB 사전 조건 전체를 다시 통과한 뒤 승인된 실제 적용을 진행했다. 적용 후 기본 validate()는 정상 통과했다. 최초 중단 기록도 Git 밖에 보존했다.

## 3. 실제 데이터 보존

아래14개 public 테이블에서 V8 원본의 모든 컬럼을 projection한 전체 행 지문과 건수가 백업 snapshot·적용 직전·V10 적용 직후·재migrate 후 모두 일치했다. 정렬된 JSONB 행의 UTF-8 길이를 구분한 SHA-256을 사용하고 비교 시각대는 UTC로 고정했다. 세션 bytea·OTP verifier·UUID·시각·설정 값도 지문 대상이다. 원문은 출력하지 않았다.

| 원본 테이블 | 보존 행 수 | 원본 전체 컬럼·행 지문 |
|---|---:|---|
| users | 4 | 일치 |
| auth_identities | 5 | 일치 |
| exercise_definitions | 80 | 일치 |
| user_workout_settings | 4 | 일치 |
| spring_session | 5 | 일치 |
| spring_session_attributes | 18 | 일치 |
| email_otp_challenges | 13 | 일치 |
| email_otp_limits | 6 | 일치 |
| email_otp_login_claims | 5 | 일치 |
| email_otp_rate_limits | 18 | 일치 |
| google_identity_change_versions | 2 | 일치 |
| reauth_email_candidates | 2 | 일치 |
| user_reauthentication_emails | 4 | 일치 |
| flyway_schema_history | 기존 V1~V8의8행 | 일치 |

현재 Flyway 이력 전체는10행이다. 추가된9·10을 제외한 원본8행의 전체 컬럼 지문을 별도로 비교했다. 단순 count 비교가 아니다.

| V10 스키마·관계 확인 | 실제 결과 |
|---|---|
| workout_sync_state / sync_operations / workout_change_batches | 세 테이블 모두 없음 |
| exercise_definitions.intent_stamps / user_workout_settings.intent_stamps | 두 컬럼 모두 없음 |
| exercise_definitions.version / deleted_at, user_workout_settings.version | 세 컬럼 존재. 기존 행 version0 / deleted_at NULL |
| 종목·설정 소유 사용자/세대, 인증 신원 사용자, 세션 속성 부모 | 고아 참조0 |
| 미검증 public constraint | 0 |
| 최종 public base table 목록 | 기존14개와 정확히 일치 |

V9가 생성한 폐기 구조는 후속 V10에서 제거했다. 이 DB는 V8에서 출발했으므로 해당 구조에 기존 사용자 명령 데이터는 없었다.

## 4. 검증 범위와 한계

| 검증 | 결과·범위 |
|---|---|
| 실제 DB V8→V9→V10 | 성공, 적용2개, 최신V10 |
| 실제 DB Flyway validate | 통과, V1~V10 checksum/이력 정상 |
| 실제 DB pending / 재migrate | 0 / 0 |
| 실제 DB 데이터·계정/세션 관계 읽기 | 위14개 전체 지문·건수·관계 검사 통과 |
| 이전 격리 Backend 전체 회귀 | 503/503 PASS: unit112 + integration391, failures/errors/skipped0. 이번에 다시 실행한 결과는 아님 |
| 이전 실제 백업 격리 복원 | V8 전체14개 일치, 복원본 V9/V10 후에도 원본 전체 일치. 이번에는 기존 백업의 복호화·무결성·최신성을 재확인 |
| 실제 Google·이메일 호출/HTTP 로그인·조회 | 실행하지 않음. 사용자 세션·OTP 변경 방지를 위해 앱/외부 서비스를 시작하지 않음 |
| Frontend | 변경 없음, 이번 재검증 없음 |

격리503개 회귀에는 Google·이메일 인증, 계정 격리, Spring Session/CSRF, Request ID, Task008 조회, JPA/JDBC 트랜잭션·rollback, Hibernate schema validation이 포함됐다. 이번 단계에서는 검증된 migration/Java/테스트 코드를 변경하지 않아 전체 build를 다시 실행하지 않았다. 실제 DB의 읽기·이력 검증을 실제 HTTP 회귀 성공으로 표현하지 않는다.

### 정식 V10 구현·테스트와 전체 빌드 근거

[정식 V10](../../src/main/resources/db/migration/V10__remove_obsolete_workout_command_storage.sql)은 순환 FK의 `sync_operations_change_batch_fk`를 먼저 해제하고 batch→operation→state 순으로 세 테이블을 제거한 뒤 두 stamp 컬럼을 제거한다. `SET LOCAL lock_timeout='5s'`, `statement_timeout='30s'`를 사용하며 CASCADE·IF EXISTS 없이 PostgreSQL/Flyway의 migration별 트랜잭션으로 실행한다. 기존 V1~V9는 수정하지 않았다.

| 테스트 파일 | V10 변경·검증 목적 |
|---|---|
| AccountProvisioningIntegrationTests.java | 최종 테이블 목록 갱신, 가입·계정별 seed 보존 |
| EmailOtpIntegrationTests.java | 최신 Flyway 버전10 기대값, 기존 이메일 인증 검사 유지 |
| PostgresMigrationTests.java | 최신 버전·history10·V10 script·폐기 테이블 부재·재시작/사용자 보존 |
| V9MigrationCompatibilityTests.java | Flyway target9로 역사적 V8→V9의 checksum·데이터·JPA/JDBC 호환성 유지 |
| JpaJdbcCompatibilityIntegrationTests.java | history10, 동일 연결/물리 트랜잭션·rollback 유지 |
| SecuritySessionIntegrationTests.java | history10, 기존 Spring Session·CSRF·쿠키 검사 유지 |
| [V10MigrationIntegrationTests.java](../../src/test/java/com/overload/overload_server/database/V10MigrationIntegrationTests.java) | 아래 신규5개 격리 시나리오 |

기존 테스트6개 수정·신규 V10 테스트 클래스1개이며 테스트 삭제·비활성화·신규 라이브러리 도입은 없다. 신규5개는 빈 DB V1→V10, 데이터 있는 V8→V9→V10, 데이터 있는 V9→V10, V8에서 V9 성공 후 V10 실패, 기존 V9에서 V10 실패다. 성공 시 기존 계정·Google/이메일 신원·세션 직렬화 bytea·종목/설정·metadata·이력을 비교하고 Hibernate validation·JPA/JDBC·계정 seed/조회·재migrate0을 확인한다.

실패 시 마지막 stamp 컬럼을 참조하는 view를 격리 DB에 만들어 SQLSTATE 2BP01을 유도했다. V10 앞부분에서 제거한3개 테이블·FK·비어 있지 않은 stamp·기존 행·이력이 전부 rollback되며 이미 성공한 V9는 그대로 남는 것을 검증했다.

최종 전체 검증 명령은 `gradlew.bat --offline --no-daemon build`이며 **BUILD SUCCESSFUL, 4분55초, unit112 + integration391 = 503개, failures/errors/skipped0**이다. 기존498개에 V10 신규5개를 추가했다. 격리 PostgreSQL18.6/Testcontainers와 외부 서비스 대체기를 사용했고 실제 `.env` DB를 사용하지 않았다. 커밋 직전에도 빌드 XML·성공 로그와 소스/테스트/빌드 파일의 검증 당시 해시를 대조해 변경 없음을 확인했다. 문서 정리만 추가되어 전체 테스트를 다시 실행하지 않았다.

### 실제 V8 백업의 격리 복원 근거

원본 DB의 READ ONLY·REPEATABLE READ exported snapshot을 pg_dump와 공유해 custom archive를 생성했다. Git 밖의 제한 디렉터리에 DPAPI CurrentUser로 암호화했으며 평문 dump는 디스크에 쓰지 않았다. 동일 snapshot에서 위14개 테이블의 원본 컬럼·전체 행 지문과8개 이력을 수집했다.

별도 일회성 PostgreSQL18.6 컨테이너의 `overload_restore_validation` DB에 `pg_restore --exit-on-error --no-owner --no-privileges`로 복원했다. loopback 임의 포트·tmpfs를 사용하며 원본 볼륨은 연결하지 않았다. V8 복원 직후14개 전체 테이블·원본 컬럼·행 지문·건수가 원본과 일치했고, 이 복원본에 V9/V10 적용2개 후에도 원본 전체가 일치했다. 폐기3표/2열 부재·metadata 기본값·validate·재migrate0도 통과했다. 원문 데이터나 복원 오류 원문을 출력하지 않았고 복원용 컨테이너는 검증 후 제거했다. 백업 파일 생성만으로 복원 가능성을 주장한 것이 아니다.

## 5. 최종 상태·복구 위험·다음 단계

- 기존 컨테이너 ID/볼륨을 유지했다. 작업 종료 시 다른 client·장기 transaction0과 호스트5433의 established 연결0을 확인한 후 stop했다. 최종 상태는 exited이며 실제5433 listener가 없다. 임시 DB/앱 서버를 새로 만들지 않았고 검증용 Java 프로세스는 종료했다. 다른 프로젝트 컨테이너는 그대로 유지했다.
- 암호화 V8 백업과 매니페스트는 기존 Git 외 제한 디렉터리에 그대로 보존한다. 현재 계정 복호화와 이전 격리 복원이 검증됐지만 DPAPI 키 유실·다른 Windows 계정/PC에서의 복구는 보장하지 않는다. 기존 복원 시험은 --no-owner --no-privileges를 사용했으므로 역할 전역 설정·ACL 재현은 별도 준비가 필요하다.
- 적용 실패나 데이터 손실은 없다. 이후 문제가 생겨도 SQL/이력을 되돌리거나 실제 DB를 자동 복원하지 않는다. V8 백업 복원은 데이터 교체를 수반하므로 대상·후속 변경 데이터·역할/ACL·앱/Flyway 자동 재적용 방지를 정리한 뒤 별도 승인해야 한다.
- 현재 개발 안내4개(AGENTS.md, README.md, docs/engineering/backend.md, local-postgresql.md)만 실제V10 상태로 갱신했다. 이 적용 보고서를 추가했다. 과거 준비·감사 보고서와 SQL 초안은 역사 기록으로 원문 보존했다. migration·테스트·제품 코드는 이번 단계에서 수정하지 않았다.
- 실제 DB 적용 작업 종료 당시(후속 V10 커밋 전) Backend HEAD는 `9ddbde73f6c5f3313e18285f0cc789f59b26ba1e`, Frontend HEAD는 `642dcaa2a883ffe8e4b4666ee2603c54de086737`이었다. Backend는 tracked10개 수정·untracked7개·staged0, Frontend는 clean이었고 해당 적용 작업에서는 Commit/Push하지 않았다.
- **일반 온라인 쓰기 API 개발을 시작할 DB 준비는 완료**했다. 구현은 아직 없으며 다음 별도 작업에서 Task009 v1.0에 따라 종목·루틴·설정의 일반 온라인 쓰기 계약·소유권·검증·트랜잭션·회귀부터 진행한다. 운동 세션·IndexedDB·폐기 명령 런타임 복구는 이번 범위에 포함하지 않았다.

로컬 실행 증거(비밀값·원문 사용자 데이터 제외)는 Git 밖의 actual-local-v10-application-result.json 및 task-009-actual-application-lifecycle.json에 보존했다. 백업 위치와 개인 접근 제어 상세는 기존 로컬 백업 보고서를 따른다.

## 6. V10 커밋 범위 선정

사용자 승인에 따라 정식 SQL1개·기존 테스트 수정6개·신규 테스트1개·현행 안내4개·이 최종 보고서1개, 총13개 파일을 V10 독립 커밋 대상으로 선정했다. 이 최종 보고서만으로 정식 구현·검증·실제 적용 근거를 확인할 수 있도록 중간 보고서에 대한 필수 링크는 제거했다.

과거 SQL 초안과 DB 정리 준비·읽기 감사·정식 준비 보고서4개는 현재 구현의 필수 문서가 아니므로 커밋에서 제외하고 기존 미추적 원문으로 보존한다. 삭제·초기화하지 않는다. 일반 온라인 쓰기 API는 사용자가 별도로 전달할2단계 범위이며 이 커밋에 포함하지 않는다. 원격 Push도 수행하지 않는다.
