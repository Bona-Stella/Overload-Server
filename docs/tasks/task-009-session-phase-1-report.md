# Task 009 — 운동 세션 1단계 구현·검증 보고서

- 일자: 2026-10-31 (Asia/Seoul).
- 시작 Git: server main/c31370e, pwa main/642dcaa, 양쪽 clean.
- 승인 범위: 최소 V12·영속성·온라인 START·ACTIVE/상세·명시적 takeover 및 격리 PostgreSQL 회귀.
- 개인 로컬 overload_dev 접속·migration 적용 없음. Frontend 변경·stage·commit·Push 없음.
- 계약: [세션 1단계](../api/workout-sessions.md), [현행 설계 보완](task-009-redesign-v1.0.md), [기존 루틴](../api/routine-management.md).

## 구현

V12는 workout_sessions 한 테이블, 사용자 단독 ACTIVE 부분 UNIQUE, 소유자/세대·출처 루틴 복합 FK, 카운터 CHECK와 최초 계획/시작 사실 불변 trigger를 추가한다.
InitialPlan은 불변 JSONB 한 곳에만 저장한다. 루틴 배치와 구별되는 세션 종목 UUID를 생성하되 예정 세트 수만큼 객체/행을 생성하지 않는다.
사건·수행·승인 요청·AppliedPlan·종료용 미래 테이블/컬럼은 선행 추가하지 않는다.

START는 workoutTransactionManager의 REPEATABLE_READ 쓰기에서 users FOR SHARE→routine FOR SHARE→종목 UUID순 공유 잠금→세션 저장을 원자 처리한다.
동일 UUID 재확인은 최초 요청의 불변 사실을 검사하며 루틴 삭제/권한 이전 이후에도 계획을 재복사하거나 편집권을 되돌리지 않는다.
조회는 현재 세대의 일관된 readOnly REPEATABLE_READ 응답이며 seed·운동 DML·시각 연장 없이 DTO를 완성한다.
takeover는 users 공유 잠금→세션 FOR UPDATE에서 기대 epoch를 검사하고 기기/epoch/version/revision을 함께 변경한다.
동일 인수 재확인은 무변경이며 다른 인수가 끼어든 구 요청은 거절한다. 읽기/takeover 모두 lastWorkoutSyncedAt을 갱신하지 않는다.
기존 로그인·CSRF·기기 UUID·epoch·revision만 사용하고 editorProof·기기 비밀 토큰은 없다.

## 변경 파일

- 신규 migration: src/main/resources/db/migration/V12__create_workout_sessions.sql. V1~V11은 불변.
- 신규 제품 코드: src/main/java/com/overload/overload_server/workoutsession/ 아래 InitialPlan, WorkoutSession, WorkoutSessionController, WorkoutSessionService, WorkoutSessionRepository, WorkoutSessionRequests, WorkoutSessionResponses, WorkoutSessionFailure, WorkoutSessionErrors.
- 기존 제품 코드: routine/RoutineRepository.java에 START용 잠금 조회만 추가. 기존 루틴 HTTP DTO·메서드·버전/승인 의미는 유지.
- 신규 테스트: workoutsession/WorkoutSessionRequestsTests.java, WorkoutSessionIntegrationTests.java, database/V12MigrationIntegrationTests.java.
- 기존 테스트: OverloadServerApplicationTests·RequestIdHttpTests의 DB 없는 서비스 mock; MixedPersistenceAssertions의 정확한 Entity 목록; AccountProvisioningIntegrationTests·EmailOtpIntegrationTests·SecuritySessionIntegrationTests·PostgresMigrationTests·JpaJdbcCompatibilityIntegrationTests·V9MigrationCompatibilityTests·V10MigrationIntegrationTests의 신규 테이블/최신 migration 기대값.
- 문서: AGENTS.md, README.md, backend.md, routine-management.md, task-009-redesign-v1.0.md, 신규 workout-sessions.md 및 이 보고서.

## 승인 정책 기록

후속 snapshot 승인 요청은 원래 순서·UUID·각 의도를 보존한다. 같은 업로드의 공통 외부 version을 잠금 당시 값과 비교한 뒤 체인을 순서대로 처리하고 부모 루틴 version은 실제 변경 트랜잭션당1회 증가시킨다.
외부 승인 충돌이어도 수행을 저장하고 승인 요청만 PENDING으로 보존한다. 재승인은 현재 시점 새 승인이고 과거 수행 문맥/기존 승인 사건/최초 종료 평가를 소급 변경하지 않는다.
공식 과거 비교는 첫 N개 유효 반복 합계 DESC→실제 명시 종료 DESC→UUID ASC이며 AUTO_CLOSED는 후보에서 제외하되 이력·통계에 보존한다.
기기 시각은 구조적 일관성을 검사하고 명백한 모순은 CLOCK_CONTEXT_INVALID로 거절하며 서버 시각으로 보정하지 않는다.
이 단락은 확정 계약 기록이며 해당 후속 기능을 구현했다는 의미가 아니다.

## 검증

최종 명령: `.\gradlew.bat --offline --no-daemon build` — **BUILD SUCCESSFUL, 4분50초**.
기존 Temurin25.0.4.1과 캐시를 사용했으며 새 의존성 설치는 없다. 실행 프로세스에서 상속 Spring/OTP/Google/DB 설정을 제외했다.
테스트 전용 local 설정에는 개발 .env import가 없고, Testcontainers 임시 DB·임의 포트·합성 계정만 사용했다.
기존 Gradle 캐시 잠금 접근은 sandbox 실행 권한으로 해결했다. 개인 DB 대상 bootRun은 실행하지 않았다.

| 범위 | tests | failures / errors / skipped |
|---|---:|---|
| 전체 DB 비사용 단위·컨텍스트·HTTP | 173 | 0 / 0 / 0 |
| 전체 PostgreSQL 통합 | 486 | 0 / 0 / 0 |
| 합계 | **659** | **0 / 0 / 0** |
| 신규 요청 검증(위 단위에 포함) | 3 | 0 / 0 / 0 |
| 신규 세션 HTTP/경합(위 통합에 포함) | 17 | 0 / 0 / 0 |
| 신규 V11→V12 보존(위 통합에 포함) | 1 | 0 / 0 / 0 |

기존 루틴23·조회22·온라인 쓰기52 및 인증·Spring Session·CSRF·JPA/JDBC·과거 migration 회귀가 전체 통합에 포함된다.
최종 test/integrationTest는 실제 실행됐고, bootJar/jar는 같은 최종 기능 코드로 앞서 생성한 산출물의 UP-TO-DATE였다. 원래 unchecked/CDS 안내 외 최종 오류는 없다.
결과 XML/HTML은 build/test-results/{test,integrationTest}, build/reports/tests/에 있다.
추가로 일반 git diff --check와 신규 파일별 공백 검사를 통과했다. V1~V11 변경은 없다.

새 테스트는 실제 HTTP·쿠키·CSRF·PostgreSQL을 사용한다. source PUT/DELETE와 양방향 경쟁에서 실제 pg_stat_activity Lock 대기를 확인한다.
START의 JDBC/JPA 연결·txid·REPEATABLE_READ 일치, 동시 START UNIQUE·동시 takeover, 최초 JSONB/시각 불변, 인수 ACK 유실 재확인, 루틴 삭제 이후 재조회, 큰 세대/카운터 overflow, 데이터 초기화 경합을 검증한다.
V11→V12 테스트는 합성 계정·종목·설정·루틴·배치·승인 사건을 넣고 기존 모든 테이블 행·checksum 불변과 신규 테이블 하나를 검사한다.
초기화 경합은 일회성 컨테이너의 테스트 전용 SQL이며 reset 제품 API를 추가한 것이 아니다.

검증 과정 수정 사항:

1. DB 없는 컨텍스트가 새 Repository를 실제 생성하려던 실패는 기존 방식대로 Service/Repository mock을 추가해 해결했다.
2. 두 기기 실제 OTP 로그인에서 기존 재요청 제한429를 만나 테스트 시계를61초 이동했다. 제품 OTP 제한을 변경하지 않았다.
3. 비웹 컨텍스트에 Clock 빈이 없어 발생한 실패는 기존 RoutineService처럼 ObjectProvider와 UTC 기본값으로 해결했다.
4. 최신 migration이 V12로 바뀌면서 실패한 기존 정확한 테이블 목록/개수/구동 목표를 갱신했다. 보안·원자성·보존 검사를 비활성화하지 않았다.

최종 실행 후 Gradle wrapper/단발 daemon/worker 프로세스0, Testcontainers/Ryuk 없음, 기존 사용자 컨테이너5개의 동일 ID 유지 상태를 확인했다.
검증용 서버·테스트 프로세스는 남기지 않았다. 기존 사용자 프로세스/컨테이너를 종료하지 않았다.

## 남은 범위·제약

snapshot·순차 승인/PENDING 보존·finish·24시간 자동 종료·사후 정정/복구·IndexedDB·Frontend 연결은 미구현이다.
따라서 현재는 API로 ACTIVE를 종료/만료시키지 않으며 새로운 운동으로 교체하는 완성 사용자 흐름도 제공하지 않는다. 후속 단계에서 상태 제약과 필요한 테이블을 새 migration으로 확장한다.
실제 개인 DB·실브라우저/iPhone/PWA·외부 메일/Google LIVE는 이번 검증 범위가 아니다.
승인 상태: 현재1단계 구현/격리 테스트는 사용자 승인됨. 개인 로컬 신규 migration 적용·commit·Push는 수행하지 않음.

최종 Git: server main/c31370e에 추적 수정16개·신규15개(총31개), 전부 미스테이징. pwa main/642dcaa clean.
미해결 검증 실패·추가 승인 대기 없음. 후속 단계와 실제 개인 DB 적용은 이번 완료 범위에 포함하지 않는다.
