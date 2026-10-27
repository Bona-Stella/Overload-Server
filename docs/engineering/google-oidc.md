# Google OIDC 로그인 (Task 007-1C-3A)

서버 측 Authorization Code + OIDC 로그인이다. 공식 Spring Security OAuth2 Client 7.1.2/Jose와
Boot 4.1.2 BOM을 사용하며, JWT를 앱 인증 수단으로 발급하지 않는다. 기존 이메일 OTP·JDBC 세션을 유지한다.
실제 Google 계정 브라우저 E2E는 아직 수행하지 않았다. 자동화는 루프백 OIDC 서버의 서명된 토큰과 실제 PostgreSQL로 수행한다.

## 로컬 설정·실행

Google Cloud Console에서 OAuth **웹 애플리케이션**을 준비한다. 개발 클라이언트 이름은 Overload Web Dev로
관리할 수 있지만, 이 서버가 Console 생성 여부를 추측하거나 클라이언트를 자동 생성하지 않는다.
승인된 redirect URI는 정확히 다음 하나다:

```text
http://localhost:8080/login/oauth2/code/google
```

동의 화면이 Testing이면 로그인에 사용할 Google 계정을 테스트 사용자로 등록한다.
JS origin localhost:3000은 별도 프론트의 설정이고 이 서버 흐름의 필수 설정이 아니다.
iPhone의 localhost는 PC 서버가 아니다. 휴대폰 LAN IP·임의 포트 콜백을 추가하지 않는다.

기존 local `.env[.properties]` import를 유지한다. 실제 값은 Git·채팅·로그에 넣지 않는다.

| 변수 | 의미 |
|---|---|
| GOOGLE_OIDC_ENABLED | 기본 false, Google 필터/등록의 명시적 활성화 |
| GOOGLE_CLIENT_ID | 발급된 웹 클라이언트 ID, apps.googleusercontent.com 형식 |
| GOOGLE_CLIENT_SECRET | 해당 클라이언트 비밀값 |
| GOOGLE_REDIRECT_URI | local에서 빈 값이면 위 URI; 지정해도 정확히 같은 URI만 허용 |

비-local 또는 local+prod/production 혼합은 명시적 HTTPS URI와 동일 콜백 경로가 필요하다.
true로 활성화했는데 클라이언트/콜백이 누락·잘못된 경우 부팅 실패로 알린다. 가짜 값으로 대체하지 않는다.
Google이 비활성이면 자격정보 없이 Health·OTP·세션·DB 없는 컨텍스트 테스트가 기동하며 Google 진입/콜백 GET은 503이다.
기존 Resend/HMAC/PostgreSQL 환경변수는 변경할 필요가 없다. 특히 Google 테스트 때문에 실메일을 활성화하지 않는다.

기존 Docker Compose PostgreSQL 실행 안내를 따른 뒤 저장소 루트에서 실행한다:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/temurin-25.0.4.1"
.\gradlew.bat --no-daemon bootRun --args="--spring.profiles.active=local --server.address=127.0.0.1 --server.port=8080 --GOOGLE_OIDC_ENABLED=true"
```

사용자가 승인한 PC 브라우저에서 **최상위 탐색**으로 `http://localhost:8080/oauth2/authorization/google`을 연다.
fetch·Vite /api 프록시를 로그인 진입으로 사용하지 않는다. 프론트 코드는 이번에 변경하지 않았다.
Google 계정 선택·비밀번호·2FA·동의는 사용자가 직접 수행하며 에이전트에게 제공하지 않는다.
테스트 종료 시 자신의 서버 프로세스를 종료하고 일시 활성화한 Google 설정을 되돌린다. DB·볼륨은 보존한다.

## HTTP 계약

| 요청 | 동작 |
|---|---|
| GET /oauth2/authorization/google | 활성 상태: Google authorization 302; 비활성: 503 GOOGLE_LOGIN_UNAVAILABLE |
| GET /login/oauth2/code/google | 활성 상태: 표준 code/state 검증; 비활성: 같은 503 |
| 검증·가입·실제 세션 저장 성공 | 고정 `/api/v1/auth/session`으로 302 |
| 프로토콜 취소·오류·저장 실패 | 고정 `/auth/google/failure`로 302 |
| GET /auth/google/failure | 401 `{code:"GOOGLE_LOGIN_FAILED"}` |
| GET /api/v1/auth/session | 인증 시 200 `{userId}` UUID 하나만; 익명 401 |
| 기존 인증 세션의 Google 시작/콜백 | 409 `{code:"LOGOUT_REQUIRED"}`, 현재 로그인 유지 |

상태/실패 API와 OAuth 응답은 no-store다. OAuth에는 no-referrer도 설정한다.
성공 URL에는 코드·토큰·이메일·UUID를 넣지 않는다. next/returnUrl은 사용하지 않는다.
보호 API의 익명 응답은 기존 JSON 401이며 로그인 HTML·Basic·폼 로그인으로 대체하지 않는다.
세션 상태 API는 읽기 전용이며 요청 userId를 사용하지 않는다. 앱 계정 식별자는 서버 principal에서만 얻는다.

## 검증 경계

등록은 공식 Google 엔드포인트와 `https://accounts.google.com` issuer를 사용한다. 외부 issuer/JWKS를
설정하는 생산용 환경변수를 추가하지 않았다. 공식 등록과 같은 RS256 키 검증·OidcIdTokenValidator·
JwtTimestampValidator·OIDC provider의 nonce 검증을 사용한다.

- 표준 code grant의 redirect_uri·state·PKCE S256을 확인하고 클라이언트 인증으로 토큰을 교환한다.
- state/nonce/code verifier는 임시 익명 Spring Session에만 바인딩한다. 기본 resolver의 nonce 해시 바인딩을 그대로 유지한다.
- 서명/JWKS·정확한 issuer·client audience·필수 sub/exp/iat·시각·다중 aud의 azp 등을 공식 검증기가 검사한다.
  기본 시계 오차 허용은 라이브러리 기준(60초)이며 테스트 만료값은 이를 충분히 넘긴다.
- ID 토큰은 등록된 HTTPS issuer를 정확히 따라야 한다. 기존 VerifiedIdentity의 Google issuer 정규화 정책은 바꾸지 않는다.
  실제 프로비저닝 키는 `(google, https://accounts.google.com, sub)`다.
- 이메일/이메일 검증 플래그를 신원 키로 쓰지 않는다. 이메일 변경·주소 일치로 새 신원 연결/계정 병합을 하지 않는다.
- 검증된 ID 토큰으로 충분하므로 UserInfo 호출을 끈다. Google API·offline access·refresh token을 요청하지 않는다.
- 브라우저가 임의 ID token/OidcUser/userId를 넘기는 공개 로그인/가입 경로는 없다.

토큰 교환은 connect 3초/read 5초이며 자동 재시도하지 않는다. Google 원문 오류를 안전한 일반 오류로 바꾼다.
callback의 code/state 전체 URL, Authorization 헤더, 토큰 본문을 기록하지 않는다.
기본 access log는 비활성이고 민감 HTTP/OAuth DEBUG·TRACE·wire logging을 켜지 않아야 한다.
CSRF는 기존 상태 변경 요청 전체에 유지하며 OAuth GET의 state 보호를 이유로 제외 범위를 넓히지 않았다.

## 일회성 상태·계정·세션 순서

1. authorization request를 기존 JDBC Spring Session에 저장하고 저장소 재조회로 state 저장을 확인한 뒤 Google로 이동한다.
2. 콜백에서 익명 세션 행을 잠그고 최신 요청/state를 같은 DB 연결로 다시 읽는다. Spring Session JDBC 기본값과 같은
   Spring Core DeserializingConverter를 사용한다. REQUIRES_NEW인 SessionRepository를 잠금 트랜잭션 안에서 호출하지 않아
   작은 연결 풀 고갈을 피한다. 기본 세션 serializer를 바꾸면 이 임시 상태 reader도 함께 검토해야 한다.
   일치한 요청만 익명 세션 행을 삭제·커밋하고 FK로 임시 속성도 제거한다. 병렬 콜백은 한 건만 획득하며 새 DDL은 없다.
3. 이 트랜잭션이 끝난 뒤 네트워크 토큰 교환·서명/클레임/nonce 검증을 수행한다. provider 실패나 상태 소비 실패는 계정을 만들지 않는다.
4. 임시 OAuth context는 저장하지 않는다. 필터의 Context repository를 Null로, 필터 자체 세션 전략을 no-op으로 두어
   기본 성공 저장/회전이 최종 자체 세션보다 먼저 일어나지 않게 한다. AuthorizedClient 저장도 폐기형 repository로 대체한다.
5. 검증된 issuer/sub만 외부 트랜잭션 없이 기존 AccountProvisioningService(REQUIRES_NEW)에 전달한다.
   기존 유일키/롤백·동시 가입·운동 20개/휴식 기본값·재로그인 보존 경로를 재사용한다.
6. 공용 VerifiedSessionLogin이 새 JDBC primary key 세션을 만들고 기존 AuthenticationSessionService로
   SessionPrincipal(UUID)/SessionMetadata만 저장한다. immediate flush 후 Context·UUID·metadata 전체 일치를 재조회한다.
   이 확인을 통과한 뒤만 성공 302를 보낸다. 같은 공용 경로를 이메일 OTP도 사용한다.

Google access/ID/refresh token·OidcUser·OAuth2AuthenticationToken은 장기 세션/DB에 저장하지 않는다.
콜백 재생·취소·오류는 임시 상태를 제거하고 일반 실패 위치로 보낸다. 이미 로그인한 브라우저의 시작/콜백은
토큰 교환 전에 409로 닫으며 기존 계정·세션을 교체하거나 만료를 연장하지 않는다.
다른 브라우저의 잘못된 콜백이 원래 브라우저의 미완료 state를 소유·삭제할 수는 없다.

프로비저닝 실패는 계정/seed 원자 롤백 후 새 Google 진입부터 재시도한다. 세션 저장 실패는 이미 완성된 계정이
남을 수 있으나 성공으로 응답하지 않고 Context/세션을 폐기한다. DB 회복 후 새 흐름은 같은 issuer/sub 계정을 재사용한다.
소비된 state/code를 재활성화하거나 계정/세션/외부 Google 교환을 하나의 트랜잭션이라고 취급하지 않는다.

기존 최초 로그인 +90일 절대 만료·10분 재인증·쿠키 HttpOnly/SameSite/Secure·로그아웃/CSRF 갱신을 유지한다.
이번 Google 로그인은 인증 수단 연결·민감 작업 재인증 API가 아니다. 그 기능은 007-1C-3B 후속 범위다.

## 검증·실로그인 구분

```powershell
.\gradlew.bat --no-daemon test integrationTest build
```

자동화의 Google 자격정보·RSA 키·OIDC 서버는 src/test에만 있으며 생산 JAR에는 포함되지 않는다.
Mock 등록은 테스트의 루프백 authorization/token/JWKS와 임시 포트만 대체하고 issuer·서명·state·nonce 검증은 유지한다.
PostgreSQL은 독립 Testcontainers 18.6이며 기존 Compose DB·실메일·개인 Google 계정은 사용하지 않는다.
최종 개수·실패 수정·Git/정리 증거는 [검증 보고서](../tasks/task-007-1c-3a-validation-report.md)를 따른다.

실제 Google Cloud 생성/활성 여부·Testing 사용자 등록·개인 브라우저 동의·실제 Google 네트워크 E2E는 별도로 확인해야 한다.
프론트 전환·iPhone/LAN HTTPS·운영 배포·계정 연결/해제·실제 민감 작업 재인증은 아직 구현하지 않았다.

공식 근거:

- [Spring OAuth2 Login 구성](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html)
- [Security 7.1.2 OAuth2 Login 필터](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/web/OAuth2LoginAuthenticationFilter.java)
- [기본 성공 시 Context 저장 순서](https://github.com/spring-projects/spring-security/blob/7.1.2/web/src/main/java/org/springframework/security/web/authentication/AbstractAuthenticationProcessingFilter.java)
- [기본 ID token 검증기](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcIdTokenDecoderFactory.java)
- [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect)
