# Task 007-1B 구현·검증 결과

- 검증일: 2026-10-27 (Asia/Seoul)
- 결과: **구현 및 로컬 검증 완료**. 실제 인증·공개 계정 API는 미구현이며 007-1C 범위다.
- 사용자 요청에 따라 첨부 최종 지시서의 내부 계정 프로비저닝·승인된 운영 seed 범위를 구현했다.
  문서의 과거 승인/환경 설명을 현재 실행 결과로 간주하지 않고 소스·Git·Docker를 직접 확인했다.
- Git staging·Commit·Push는 이번 요청의 승인 범위에 포함하지 않았으며 수행하지 않았다.

## 시작 상태·선행 조건

| 항목 | 확인 결과 |
|---|---|
| 브랜치 / HEAD | main / c43ee9b7c1d34cbd48707df62a6f1ca8bd785c79 |
| 원격 확인 | `git ls-remote --heads origin refs/heads/main`이 같은 해시 반환: 007-1A Push 반영 확인 |
| 작업 트리 | 서버·프론트 모두 clean, 예상 밖의 변경 없음 |
| 007-1A 기반 | V1, Compose, JDBC/Flyway, 실제 PostgreSQL Testcontainers 테스트 존재 |
| 고정 도구 | Java 25 / Boot 4.1.2 / Gradle 9.8.2 및 기존 BOM·의존성 유지 |
| 실제 검증 DB | Testcontainers PostgreSQL 18.6, 테스트별 새 컨테이너·빈 DB·임의 포트·임의 비밀번호 |
| Docker | Engine 29.9.2 / Compose 5.6.1 사용 가능 |

Overload 개발 컨테이너는 시작 시 Exited (0)이었으며 이번 작업에서 시작하거나 DB를 변경하지 않았다.
기존 welog PostgreSQL 및 타 서비스에도 접속하지 않았다.

## 구현·DDL·동시성 근거

신규 `V2__create_account_provisioning.sql`에만 DDL을 추가했다.
V1 blob은 HEAD와 동일한 `c2bacee9695e41170edd43127a6408132f5ae99f`이며 수정하지 않았다.
V1의 실제 Flyway checksum `1980645960`도 V1→V2 업그레이드 테스트에서 유지됨을 확인했다.

- users에는 현재 소유권/세대 FK의 참조 키 `(id, data_generation)` UNIQUE만 추가한다.
- auth_identities는 UUID PK·users FK·provider/issuer 조합 CHECK·issuer/subject NOT NULL/공백 거부·
  `(provider, issuer, subject)` UNIQUE·생성 시각을 갖는다. user_id 참조 인덱스를 둔다.
- exercise_definitions는 UUID PK·사용자·현재 세대·표시명·생성/변경 시각을 갖고 현재 사용자/세대를 복합 FK로 참조한다.
  이름 UNIQUE는 없다. `(user_id, data_generation, id)` 복합 UNIQUE로 다른 사용자의 종목 UUID를 연결하는 FK를 거부한다.
- user_workout_settings는 사용자 PK·현재 세대 복합 FK, 휴식 세 INTEGER/NOT NULL/CHECK > 0,
  생성/변경 시각을 갖는다. 계정당 하나만 존재한다.
- FK cascade·기존 데이터 갱신·파괴적 SQL·전체 운동 스키마·자동 baseline/clean은 추가하지 않았다.

내부 서비스는 **새 READ COMMITTED 트랜잭션**에서 사용자·신원·개인 종목 20개·설정 1행을 함께 생성한다.
동일 신원 경쟁은 지정된 credential UNIQUE의 `ON CONFLICT DO NOTHING`으로 판정한다.
승자만 seed를 커밋하고, 패자는 users까지 롤백한 후 새 트랜잭션에서 승자를 읽는다.
다른 SQL 오류는 성공으로 바꾸거나 무시하지 않는다. 기존 계정은 종목·설정·닉네임·세대를 덮어쓰지 않는다.

재진입은 users 행을 `FOR UPDATE`로 잠근다. 신규 사용자는 같은 트랜잭션에서 삽입한 행의 잠금이 유지된다.
향후 초기화도 users 행을 먼저 잠그고 이전 세대 자식 데이터 정리→세대 증가→새 seed를 한 트랜잭션에 처리해야 한다.
즉시 검사 복합 FK는 자식 참조를 남긴 채 세대만 변경하는 경로를 거부한다. 초기화 기능 자체는 구현하지 않았다.

FK는 소유자/세대 관계를 검증하며 인증 principal이나 호출자의 권한을 대신하지 않는다.
현재 repository는 패키지 내부이며 신원 조회·새 계정 seed 경로만 제공한다.
실제 인가와 principal 기반 읽기/쓰기는 후속 구현이 필요하다.

`VerifiedIdentity`는 내부 검증 결과 입력형이다. 생성자는 모양만 검사하고 인증하지 않는다.
Google issuer 두 공식 표현을 하나로 통일하고 sub를 보존한다. 이메일은 별도 issuer이며 도메인만 소문자화한다.
로컬 부분 대소문자·점·플러스 태그를 보존하며 Google/이메일 자동 연결·병합을 하지 않는다.
007-1C의 신뢰된 검증 코드가 호출해야 하고 공개 HTTP/테스트 우회 API는 없다.
REQUIRES_NEW 서비스의 호출은 외부 쓰기 트랜잭션 밖에서 해야 한다.
정규화·잠금·트랜잭션·운영 seed 상세는 [기술 계약 문서](../engineering/account-provisioning.md)에 기록했다.

## 운영 seed 결과

단일 운영 정의 `AccountSeed`에 승인된 웨이트 종목 **20개**와 휴식 설정 **180/120/90초**를 선언했다.
정확한 종목 이름 전체를 독립 테스트 기대값으로 확인했고, DB에 저장된 목록도 대조했다.
각 계정의 종목 UUID 집합은 겹치지 않고 같은 표시명의 개인 추가 종목도 허용된다.
목표 반복 상한 10/11/12/13의 휴식은 각각 **180/120/120/90초**이며 DB 저장값으로도 확인했다.
마이그레이션과 앱 시작에서는 계정/seed를 생성하지 않는다. 초기 계정 생성 때만 적용한다.
루틴·요일 일정·운동 세션·실제 수행·승인 값/사건·메모는 생성하지 않고 관련 테이블도 선구현하지 않았다.

## 실제 변경 파일 전체

수정 5개:

- `README.md`
- `docs/engineering/backend.md`
- `docs/engineering/local-postgresql.md`
- `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java`
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`

신규·미추적 12개:

- `docs/engineering/account-provisioning.md`
- `docs/tasks/task-007-1b-validation-report.md`
- `src/main/java/com/overload/overload_server/account/Account.java`
- `src/main/java/com/overload/overload_server/account/AccountProvisioningService.java`
- `src/main/java/com/overload/overload_server/account/AccountRepository.java`
- `src/main/java/com/overload/overload_server/account/AccountSeed.java`
- `src/main/java/com/overload/overload_server/account/VerifiedIdentity.java`
- `src/main/java/com/overload/overload_server/account/WorkoutSettings.java`
- `src/main/resources/db/migration/V2__create_account_provisioning.sql`
- `src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java`
- `src/test/java/com/overload/overload_server/account/AccountSeedTests.java`
- `src/test/java/com/overload/overload_server/account/VerifiedIdentityTests.java`

새 의존성은 없다. build.gradle·Compose·접속 설정·V1·Health 구현·Task 006 확정 정책은 변경하지 않았다.
DB 없는 컨텍스트 배선 테스트에는 JdbcTemplate/트랜잭션 관리자 stub만 제공한다.
실제 트랜잭션·JDBC·DDL·제약 검증은 별도 실제 PostgreSQL 테스트에서 수행한다.

## 검증 결과

명령: `.\gradlew.bat --no-daemon test integrationTest build`

- 최초 전체 검증: **성공**, 8 task 실제 실행, 총 28개 테스트 통과.
- 이후 현재 세대 7인 기존 계정 재진입 회귀 테스트를 추가하고 위 명령 재실행: **성공**.
  최종 8 task 중 3개 실행(compileTestJava·test·integrationTest), 5개 UP-TO-DATE.
- 최종 test **14개**, integrationTest **15개**, 합계 **29개**, 실패/오류/skip 모두 **0**.
- XML/HTML 및 산출물은 Git 제외 build/에 있고 실제 비밀번호는 Git 대상 파일에 없다.
- `git diff --check`, 신규 파일 whitespace 검사, .env 제외·실제 비밀번호 비포함 검사 통과.

| 실제 검증 | 확인 결과 |
|---|---|
| 신규 빈 DB / 재시작 | V1·V2 각 1회 성공, 이력 2건 유지, 추가 migrate 0, 사용자 보존 |
| V1→V2 | 기존 UUID·닉네임·세대 7 유지, 자동 seed 없음, V1 checksum 동일 |
| 서로 다른 신원 | 서로 다른 user ID·종목 UUID, 계정별 종목 20개·설정 1행 |
| 같은 신원 재진입 | 같은 계정, 기존 편집·UUID·닉네임·설정 및 현재 generation 유지 |
| 동시 첫 가입 4건 | SQL 테스트 게이트로 모두 신원 INSERT 직전 대기 확인 후 경합; 계정 1·신원 1·종목 20·설정 1 |
| 4단계 SQL 실패 주입 | users/auth/종목/설정 각각 실패 후 모든 업무 테이블 0행, 원인 제거 후 재요청 정상 생성 |
| 소유권·세대 | 실제 복합 FK 소비용 테스트 전용 테이블에서 다른 계정 종목 연결 거부, 잘못된 세대 연결/세대만 갱신 거부 |
| 기본 제약 | credential 중복·없는 사용자 FK·잘못된 provider/issuer·공백/NULL 신원·잘못된 휴식값 거부 |
| 신규 계정 데이터 | 승인된 종목·설정만 존재, 운영 스키마에 샘플 루틴/일정/운동/승인/메모 테이블 없음 |
| Health 회귀 | MVC 및 실제 PostgreSQL + HTTP: GET 200/정확한 JSON, POST 405 |

컴파일/테스트/빌드 실패는 없었다. 정리 확인용 PowerShell에서 daemon 로그 이름의 `.out` 부분 때문에
PID 변환이 한 번 실패했으며 추출 정규식을 수정해 두 daemon의 종료를 확인했다. 제품 코드 오류는 아니다.
실제 Google/OTP·세션·프론트 연동·운영 배포 검증은 범위 밖으로 수행하지 않았다.

## Docker·프로세스·Git 종료 상태 및 후속 사항

- 테스트 컨테이너·Ryuk·일회성 볼륨은 모두 정리됐다. 이번 Gradle daemon 112796·113448 및 테스트 worker 종료 확인.
- 시작과 종료의 기존 컨테이너 7개 ID/상태와 볼륨 7개가 일치한다. 개발 DB는 계속 Exited (0)이며 별도 유지했다.
- 기존 컨테이너/볼륨 삭제·기존 DB 변경·시스템 설정 변경·운영 연결·배포 없음.
- .env 내용은 변경/공개하지 않았으며 기존 ignore 규칙을 유지했다.
- 프론트 Git 상태는 clean이고 코드·CSS·설정·localStorage·목업 데이터는 변경하지 않았다.
- **프론트 연동 차이:** 현재 휴식 함수와 UI는 ≤8/9~12/≥13이다. 승인된 서버 기준 ≤10/11~12/≥13에 맞춘 후속 연동 필요.
  프론트 수정은 이번 지시서에서 금지되어 수행하지 않았다.
- Commit·Push·staging 미실행, 시작 HEAD 유지. 작업 트리는 수정 5개와 신규·미추적 12개이며 모두 007-1B 범위다.
- 구현/검증의 미해결 장애는 없다. 추가 구현 승인은 불필요했고, Git 보관은 별도 승인 전까지 수행하지 않는다.
- 007-1C에서는 Google/OTP 신원 검증 후 내부 호출, 같은 정규화 규칙·인가·쿠키/CSRF·세션 등 후속 보안 검증이 필요하다.
