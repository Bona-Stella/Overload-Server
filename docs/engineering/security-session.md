# 보안·JDBC 세션 기반 (Task 007-1C-1)

현재 단계는 Spring Security 7.1.2 / Spring Session JDBC 4.1.1 기반이다. Boot 4.1.2 BOM으로
관리한다. 이메일 OTP는 [007-1C-2](email-otp.md), Google OIDC는 [007-1C-3A](google-oidc.md)로 추가했다.
[007-1C-3B-1](email-reauthentication.md)에 인증 수단 조회와 이메일 재인증을 추가했다. 실제 민감 업무 쓰기 API는 아직 없다. `VerifiedIdentity` 생성은
인증 성공이 아니다. 후속 검증 코드가 계정 프로비저닝을 완료한 뒤 검증된 계정 UUID만
`AuthenticationSessionService.establishAfterVerification`에 전달해야 한다.
Google/이메일 자동 병합 금지와 기존 seed 정책은 [계정 계약](account-provisioning.md)을 유지한다.

## HTTP 보안 계약

| 요청 | 결과 |
|---|---|
| GET /api/v1/health | 기존 200 / 정확히 `{"status":"UP"}` |
| POST /api/v1/health | 기존 405; 이 정확한 경로·메서드만 CSRF 검사 제외, 상태 변경 없음 |
| GET /api/v1/auth/csrf | 익명/인증 사용자 모두 200, no-store JSON `{headerName, token}` |
| GET /api/v1/auth/session | 인증 사용자만 200, no-store JSON `{userId}`; 익명 401 |
| POST /api/v1/auth/logout | 유효한 CSRF 필요, 세션 무효화 후 204 |
| POST /api/v1/auth/email-otp/request, /verify | CSRF를 유지한 익명 로그인 경로, 상세는 이메일 OTP 계약 |
| GET /oauth2/authorization/google, /login/oauth2/code/google | 명시적 활성화 시 OIDC 시작/콜백, 비활성 503; 기존 인증 세션은 409 |
| GET /auth/google/failure | 고정 실패 리디렉션 도착점, 401 GOOGLE_LOGIN_FAILED |
| 그 밖의 익명 API | 401 JSON `{"code":"AUTHENTICATION_REQUIRED"}`, HTML 로그인/리다이렉트 없음 |
| 인증 후 CSRF 누락/오류 | 403 JSON `{"code":"CSRF_INVALID"}` |
| 인증 후 권한 부족 | 403 JSON `{"code":"ACCESS_DENIED"}` |

폼 로그인·HTTP Basic을 끄고 기본 개발 사용자 생성도 차단했다. RequestCache가 익명 요청을 세션에
보관하거나 로그인 HTML로 이동시키지 않는다. ERROR dispatcher의 오류 렌더링은 허용하되
일반 `/error` 요청이나 다른 업무 경로를 익명 공개하지 않는다. CORS 전체 origin 허용 설정은 없다.
인증되지 않은 unsafe 요청이 CSRF에서 거부되면 401로 반환한다. CSRF가 짧은 익명 세션을 만들 수 있으나
이를 인증된 장기 세션으로 취급하지 않는다.

## 세션 상태와 시간 경계

Spring SecurityContext와 별도로 직렬화 가능한 `SessionMetadata`를 JDBC 세션 속성에 저장한다.
메타데이터에는 `userId`, `loginStartedAt`, `absoluteExpiresAt`, `reauthenticatedAt`이 있다.
007-1C-2는 JDBC immediate flush와 저장소 재조회로 OTP 로그인 성공 전에 영속 인증 상태를 확인한다.
007-1C-3A는 이 경로를 VerifiedSessionLogin으로 공용화하고 저장 메타데이터 전체 일치도 확인한다.
OAuth 필터의 임시 Google principal은 저장하지 않으며, 검증·프로비저닝·영속 자체 세션 확인 뒤만 성공 리디렉션한다.
principal에는 같은 사용자 UUID만 두며 요청의 userId나 신원 DTO로 SecurityContext를 만들지 않는다.

- 최초 성공 로그인: 서버 Clock으로 시작 시각과 **90 × 24시간** 뒤 절대 만료를 정한다.
- 활동: 세션이 살아 있는지 재사용 서비스/필터에서 검사하며 원래 시작·만료 시각을 변경하지 않는다.
- 로그인 시 세션 ID를 회전하고 SecurityContext를 명시적으로 저장한다. 같은 계정의 활성 세션에서
  재검증되어도 시작·만료를 유지한다. 다른 계정/만료 메타데이터는 새 세션과 분리한다.
- 재인증 접점은 동일 계정 증명 후에만 호출한다. 재인증 시각과 ID/CSRF를 갱신하되 원래 만료는 유지한다.
- 민감 작업 검사 `SessionPolicy.hasRecentReauthentication`: 최근 **10분 이하**이면 인정한다.
  정확히 10분은 인정, 그 이후는 거부한다. 최초 로그인도 검증 시각으로 인정한다.
- **정확히 90일 만료 시각부터는 무효**다. 미래 시각, 누락/잘못된 메타데이터, 임의 연장된 만료,
  principal/userId 불일치도 인증 상태를 폐기한다. 만료 상태로 보호 경로를 요청하면 401이다.
- 정책 검사는 실제 Clock을 주입받는다. 저장소 TTL이나 브라우저 쿠키의 존재만으로 인증하지 않는다.

익명 세션의 저장소 비활성 TTL은 `spring.session.timeout=30m`이다. 인증된 세션은 최초 90일,
이후 요청 시 **원래 만료까지 남은 시간**으로 MAX_INACTIVE_INTERVAL을 줄인다.
활동이 새 90일 비활성 유효기간을 만들지 않으며 별도의 짧은 인증 비활성 제한도 도입하지 않았다.
남은 시간이 1초 미만인 저장소 TTL은 최소 1초지만, 권한 검사는 Instant 절대 만료를 그대로 적용한다.
Spring Session의 만료 행 정리는 저장 공간 정리이며 인증 만료 판정의 대체가 아니다.
정리 전 남아 있는 행도 만료 필터를 통과하지 못한다.

## 쿠키와 프로필

`SESSION`은 host-only, Path `/`, **HttpOnly**, SameSite=Lax다. URL rewriting을 사용하지 않는다.
익명 쿠키는 브라우저 세션 쿠키이며, 인증 성공 후에는 남은 절대 수명 이내의 Max-Age/Expires를
갖는 영속 쿠키로 바뀐다. PWA 재시작 후에도 쿠키와 DB 상태가 유효하면 같은 세션을 재사용할 수 있다.
이는 브라우저/사용자의 쿠키 삭제까지 막는 영구 보존 보장이 아니다.

기본/운영/HTTPS 설정은 **Secure=true**다. `local`의 `overload.session.cookie.secure=false`만
iPhone LAN HTTP 개발에 사용할 수 있다. local이 아닌 프로필에서는 false 설정을 무시하고 Secure를
강제한다. `local`과 `production`/`prod`를 함께 켠 경우도 Secure를 강제한다.
ID 회전/재인증 시 쿠키를 다시 발급해도 남은 기간으로 제한한다. 로그아웃은 Max-Age=0으로 제거한다.
Vite host/3000/strictPort/API proxy는 변경하지 않았다. 실제 배포·신뢰할 프록시 설정은 이번 범위 밖이다.

## 프론트가 향후 지킬 CSRF 계약

HttpSessionCsrfTokenRepository와 기본 XOR/BREACH 보호를 유지한다. 읽기 가능한 XSRF 쿠키를 도입하지 않았다.
인증 쿠키는 JavaScript가 읽지 않는다. 토큰은 다음 순서로 다룬다:

1. 동일 origin `/api/v1/auth/csrf`를 GET한다. 세션 쿠키를 함께 보내고 JSON의 headerName/token을 얻는다.
2. POST/PUT/PATCH/DELETE 등 변경 요청에 반환된 헤더명(현재 `X-CSRF-TOKEN`)과 토큰을 보낸다.
3. 로그인·재인증·로그아웃 성공 뒤에는 이전 토큰을 버리고 GET으로 새 토큰을 얻는다.
4. 401이면 인증이 필요하다. 403/CSRF_INVALID면 새 토큰을 조회하되 실제 변경 성공으로 표시하지 않는다.

응답은 no-store다. 토큰을 URL·로그에 넣지 않는다. 쿠키와 같은 origin 세션에 바인딩된 토큰이며,
CSRF 필터를 `/api/**` 전체에서 제외하거나 프론트에서 세션 쿠키를 인증 증명으로 재구성하지 않는다.
후속 실제 로그인 endpoint도 로그인 CSRF 방어와 신원 검증을 모두 유지해야 한다.

## V3와 검증

V1/V2는 그대로 두고 V3에 채택 버전 JAR의 공식 PostgreSQL DDL을 반영했다:

- SPRING_SESSION: primary/session ID, 생성/접근/만료 시각(BIGINT), 비활성 초(INT), principal 이름.
- session_id UNIQUE, expiry_time 및 principal_name 인덱스.
- SPRING_SESSION_ATTRIBUTES: 세션 PK/속성명 복합 PK와 BYTEA, 세션 삭제에 종속된 FK cascade.
- `spring.session.jdbc.initialize-schema=never`: 스키마 관리자는 Flyway 하나다.

통합 테스트는 실제 PostgreSQL·HTTP·HTTPS와 CookieManager를 사용하고 mock session으로 통과시키지 않는다.
실제 TLS는 테스트 임시 키 저장소와 전용 신뢰 저장소를 사용하며 OS 인증서/신뢰 설정을 바꾸지 않는다.
픽스처 로그인·보호/재인증 경로는 **src/test**에만 있고 명시적 테스트 구성에서만 활성화한다.
테스트 구성이 좁게 익명 허용하는 로그인 경로에도 운영 CSRF·만료·세션 필터를 그대로 사용한다.
운영 프로필의 mapping/인증 관리자 검사와 boot jar의 픽스처 비포함 검사를 수행했다.

서버 재시작 후 DB 세션 재사용, 90일/10분 경계, 쿠키 속성·수명, CSRF 로그인/로그아웃 후 재발급,
세션 고정·로그아웃 재사용 거부, 공식 스키마 일치, V1/V2 체크섬 보존 및 기존 계정 회귀를 검증한다.
1C-1 자체 검증에는 iPhone/PWA·실서비스·운영 배포가 포함되지 않았다. 이후 이메일은
1C-2-LIVE에서 실수신·로그인을 검증했고, Google의 현재 자동화/실브라우저 범위는 [OIDC 문서](google-oidc.md)를 따른다.

## 공식 근거

- [Boot 4.1.2 Spring Session 스타터와 설정](https://docs.spring.io/spring-boot/reference/web/spring-session.html)
- [고정 BOM 좌표](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Spring Security 7.1 CSRF / JavaScript 토큰 조회·갱신](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Session JDBC 구성](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
- [Spring Session 4.1.1 PostgreSQL 공식 스키마](https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql)
