# Task 007-1C-1 구현·검증 결과

- 검증일: 2026-10-27 (Asia/Seoul)
- 결과: **보안·JDBC 세션 기반 구현 및 로컬 검증 완료**. 실제 Google/OTP 로그인은 미구현이다.
- 사용자 요청에 따라 첨부 지시서의 범위를 구현했다. 첨부 문서 자체를 과거 실행 결과나 Git 승인으로 해석하지 않았다.
- staging·Commit·Push 없음. 다음 인증 단계·프론트 구현·운영 연결/배포 없음.

## 시작 상태

| 항목 | 실제 확인 |
|---|---|
| 서버 | main / 35b4b9abc3a95f19a862f118eefb9757af45c41b / clean |
| 원격 | origin/main이 같은 007-1B 커밋, ls-remote 확인 |
| 프론트 | main / f90c8f1f26478af516b0c8586e0de5447477031d / clean |
| 고정 도구 | Boot 4.1.2 / Java 25 / Wrapper 9.8.2 / 실제 테스트 PostgreSQL 18.6 |
| 기존 Docker | 컨테이너 7개·볼륨 7개. Overload 개발 DB는 Exited (0), 다른 기존 서비스 상태 유지 |

007-1A/B 소스·DDL·실제 테스트와 Task 006 인증 정책을 대조했다. 예상 밖의 미커밋 변경은 없었다.
기존 개발 DB는 시작하거나 연결하지 않았으며 통합 테스트는 전용 Testcontainers DB만 사용했다.

## 의존성·마이그레이션

추가한 최소 의존성은 Boot의 security / session-jdbc 스타터와 테스트용 security-test 스타터다.
공식 문서, 실제 BOM/Gradle 해석 결과 및 실제 JAR API를 확인했다:

- Spring Boot 스타터: 4.1.2
- Spring Security core/config/web/test: 7.1.2
- Spring Session core/jdbc: 4.1.1
- 기존 JDBC/Flyway/PostgreSQL/Testcontainers 버전 유지. JPA·JWT·Redis·OAuth 클라이언트·OTP 의존성 추가 없음.

신규 `V3__create_spring_session.sql`은 채택된 Session 4.1.1 JAR의 공식 PostgreSQL 스키마와
주석/공백을 제외하고 완전히 일치함을 실제 통합 테스트로 검증했다. 두 세션 테이블, session_id UNIQUE,
expiry_time/principal_name 인덱스, 속성 복합 PK와 세션 삭제 종속 FK를 포함한다.
`spring.session.jdbc.initialize-schema=never`로 Spring Session 자체 DDL은 실행하지 않는다.

| 보호 파일 | HEAD와 동일한 blob |
|---|---|
| V1__create_users.sql | c2bacee9695e41170edd43127a6408132f5ae99f |
| V2__create_account_provisioning.sql | dc5649595defe7e059b18fdcb97a905f8e87ab63 |
| Task 006 최종 설계 v0.3.1 | 54412e45b55787899d76e2d829caab6cac3219eb |

빈 DB 최초 V1..V3 적용·재시작 이후 이력 3건 유지/추가 적용 0을 검증했다.
V1 기존 사용자 보존 테스트를 유지했고 V2→V3에서는 기존 V1/V2 checksum 두 개가 보존됨을 추가 검증했다.

## 보안·세션·프론트 계약

정확한 접점과 설계는 [보안·세션 문서](../engineering/security-session.md)에 기록했다.

- 기본 보호: 익명 API 401 JSON, 로그인 HTML/리다이렉트·기본 사용자·폼/Basic 로그인 없음.
  인증 후 CSRF 실패는 403/CSRF_INVALID, 권한 부족은 403/ACCESS_DENIED.
- Health GET 정확한 200/JSON, POST 405 유지. POST health만 정확한 경로/메서드로 CSRF 제외하며
  다른 API를 광범위하게 제외하지 않았다.
- 메타데이터: 사용자 UUID, 최초 로그인·절대 만료·재인증 시각을 별도 SessionMetadata에 저장하고 principal과 대조한다.
- **90일**: 서버 Clock 기준 최초 로그인부터 절대 수명, 정확한 만료 시각부터 거부.
  활동·ID 회전·동일 계정 재검증/재인증은 원래 시작·만료를 연장하지 않는다.
- **10분**: 최근 로그인/재인증 증명은 정확히 10분까지 인정, 초과 시 민감 작업 검사 실패.
- 익명 저장소 TTL 30분과 인증 세션의 남은 절대 수명은 분리한다. 인증 저장소 TTL은 남은 기간으로 줄이며,
  정리 작업이나 쿠키 존재를 인증 판정으로 대신하지 않는다.
- SESSION 쿠키: host-only / Path=/ / HttpOnly / SameSite=Lax, 기본 Secure. 인증 이후 영속 Max-Age/Expires를
  남은 절대 수명 이내로 제한한다. 익명 쿠키는 장기 인증 쿠키가 아니다. 로그아웃 시 무효화·제거한다.
- HTTP 예외는 local만 허용한다. 비-local 및 production/prod 혼합 프로필은 false 설정에도 Secure를 강제한다.
- CSRF는 세션 저장과 기본 XOR/BREACH 보호를 유지한다. 동일 origin GET `/api/v1/auth/csrf`의 no-store
  JSON에서 토큰을 받아 `X-CSRF-TOKEN`에 보낸다. 인증·재인증·로그아웃 후 토큰을 다시 조회한다.
  읽기 가능한 XSRF 쿠키를 쓰지 않으며 HttpOnly 인증 쿠키를 JS에서 읽을 필요도 없다.
- 후속 인증 코드는 **실제 검증 후** 내부 세션 서비스에 계정 UUID를 전달해야 한다.
  VerifiedIdentity·목업·요청 DTO만으로 인증하는 공개 경로는 없고 Google/이메일 자동 병합도 하지 않는다.

## 변경 파일 전체

수정 10개:

- `build.gradle`
- `README.md`
- `docs/engineering/backend.md`
- `docs/engineering/local-postgresql.md`
- `docs/engineering/account-provisioning.md`
- `src/main/resources/application.yaml`
- `src/main/resources/application-local.yaml`
- `src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java`
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`
- `src/test/java/com/overload/overload_server/health/HealthControllerTests.java`

신규·미추적 14개:

- `docs/engineering/security-session.md`
- `docs/tasks/task-007-1c-1-validation-report.md`
- `src/main/java/com/overload/overload_server/security/AbsoluteExpiryCookieSerializer.java`
- `src/main/java/com/overload/overload_server/security/AuthenticationSessionService.java`
- `src/main/java/com/overload/overload_server/security/CsrfController.java`
- `src/main/java/com/overload/overload_server/security/SecurityConfiguration.java`
- `src/main/java/com/overload/overload_server/security/SessionExpiryFilter.java`
- `src/main/java/com/overload/overload_server/security/SessionMetadata.java`
- `src/main/java/com/overload/overload_server/security/SessionPolicy.java`
- `src/main/java/com/overload/overload_server/security/SessionPrincipal.java`
- `src/main/resources/db/migration/V3__create_spring_session.sql`
- `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java`
- `src/test/java/com/overload/overload_server/security/SessionPolicyTests.java`
- `src/test/java/com/overload/testfixture/SecurityFixtures.java`

## 실행한 검증 및 결과

| 명령·검증 | 결과 |
|---|---|
| Gradle dependencies --configuration testRuntimeClasspath | 성공, 고정 BOM 해석 확인 |
| Gradle compileJava | 성공, 버전별 API 확인 |
| Gradle test (보안 도입 후) | 기존 DB 없는 테스트 14개 성공 |
| Gradle integrationTest --tests '*SecuritySessionIntegrationTests' 첫 실행 | 테스트 픽스처 중복 등록으로 초기화 실패 |
| 같은 보안 통합 테스트 재실행 | 10개 중 9개 성공, 익명 unsafe CSRF 요청의 세션 생성에 대한 테스트 기대값 1개 실패 |
| `.\gradlew.bat --no-daemon test integrationTest build` 최종 | **BUILD SUCCESSFUL**, 8 task 중 6개 실행·2개 UP-TO-DATE |
| 최종 test | **17개**, 실패/오류/skip 0 |
| 최종 integrationTest | **26개**, 실패/오류/skip 0 |
| 전체 최종 테스트 | **43개 통과**, 기존 29개 회귀 포함 |
| 생산 boot jar 픽스처 검사 | 테스트 로그인/픽스처 클래스 없음 |
| git diff --check·신규 파일 whitespace·비밀값 제외 | 성공 |

첫 실패는 테스트 구성이 중첩 컨트롤러를 자동 등록하면서 별도 @Bean으로 중복 등록한 원인이었다.
중복 factory를 제거했다. 두 번째는 익명 unsafe 요청에서도 CSRF가 단기 세션을 생성할 수 있다는 실제 동작이었다.
CSRF를 끄지 않고 익명 GET의 무세션 상태와 unsafe 요청의 **미인증 principal/30분 TTL**을 검증하도록 수정했다.
최종 실행에서는 미해결 컴파일·테스트·빌드 실패가 없다.

실제 네트워크/DB 검증 근거:

- JdbcIndexedSessionRepository가 만든 행과 속성, principal_name, 세션 ID 회전·이전 ID 재사용 거부.
- 서버 재시작 후 같은 쿠키로 DB 세션 복원·보호 경로 응답 성공.
- 유효 인증 쿠키만으로 변경 요청 실패, 잘못된/로그인 전 CSRF 403, 새 토큰 성공.
- 로그아웃 후 DB 세션 제거·쿠키 Max-Age=0·기존 ID 재사용 401, 새 CSRF/테스트 로그인 성공.
- 제어 Clock으로 89일 활동·회전·재인증 후 원래 만료 유지, 90일 직전 성공·정확한 만료부터 401.
- 재인증 10분 경계와 재인증 이후에도 절대 만료 유지.
- local 실제 HTTP 및 운영 설정의 실제 HTTPS CookieManager 재요청과 Secure/HttpOnly/SameSite/Path/영속 수명 확인.
- 운영 프로필에 테스트 mapping/기본 사용자 없음, 인증 관리자는 미구성 로그인 시도 거부.
- Session 공식 스키마 일치·DDL 중복 실행 비활성·기존 계정 seed/동시 가입/롤백/소유권/Health 통과.

테스트 인증 성공/재인증 수단은 src/test와 명시적 테스트 구성 안에만 있다. 실제 신원 검증 성공을
주장하지 않는다. 실제 iPhone/PWA 수동 조작·Google/Resend·운영 환경은 검증하지 않았다.

## 종료 상태와 후속 작업

- 테스트 컨테이너/Ryuk·일회성 볼륨·HTTP/HTTPS 서버·keytool·Gradle test worker 정리 확인.
- 최근 검증 daemon 110512/112340/113660/113252/112076 종료 확인.
- 기존 컨테이너 7개 ID·상태 및 볼륨 7개 보존. 개발 DB는 계속 Exited (0), 데이터 접근·변경 없음.
- TLS 테스트는 임시 자체 서명 키와 전용 테스트 trust store만 사용했으며 OS 신뢰/서비스/시간 설정을 변경하지 않았다.
- .env·실제 비밀번호·테스트 산출물·임시 TLS 키는 Git 대상에 없다. 비밀값 로그 공개 없음.
- 프론트 branch/HEAD/작업 트리 및 Vite host/port/strictPort/proxy·CSS·localStorage·목업은 변경하지 않았다.
- 서버 HEAD 유지, staging·Commit·Push 미실행. 수정 10개/신규 미추적 14개는 모두 이번 범위다.
- 구현 범위의 추가 승인 대기/미해결 장애는 없다. Git 작업은 별도 요청 전까지 수행하지 않는다.
- 다음 단계는 실제 Google/OTP 검증 후 접점 호출·로그인 CSRF·보안 실패 시나리오·세션 폐기 연동이다.
  인증 제공자·메일·계정 연결/초기화·동기화·프론트 후속 구현은 착수하지 않았다.
