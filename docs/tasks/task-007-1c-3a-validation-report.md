# Task 007-1C-3A Google OIDC 구현·검증 보고서

작성일: 2026-10-28 (Asia/Seoul).
구현 범위는 서버 측 Google Authorization Code/OIDC·계정 프로비저닝·자체 세션·자동화 검증이다.
실제 Google 계정 브라우저 로그인은 미검증이며, 프론트·운영·계정 연결/해제·실제 민감 작업 재인증은 구현하지 않았다.

## 1. Git·기존 상태 보존

| 항목 | 시작·종료 상태 |
|---|---|
| 서버 브랜치/HEAD | main / `766f3a260e2d091d930fc2d519414dbe2b851708`, 변경 없음 |
| 정상 원격 main | 같은 SHA를 git ls-remote로 확인, Push 없음 |
| 선행 변경 | `docs/tasks/task-007-1c-2-live-validation-report.md` 미추적 1개, 원문 그대로 보존 |
| 신규 작업 변경 | 아래 22개 파일, staged 0; 선행 LIVE 보고서는 이 범위에 포함하지 않음 |
| 프론트 | main, `f90c8f1f26478af516b0c8586e0de5447477031d`, clean·수정 없음 |

기존 LIVE 보고서와 `.env`의 시작/종료 SHA-256 fingerprint를 비교해 내용 불변을 확인했다. fingerprint나 비밀값 자체는 싣지 않는다.
Git staging·Commit·Push·Force Push는 수행하지 않았다.

## 2. 변경 파일 전체

수정 10개:

| 경로 | 내용 |
|---|---|
| `.env.example` | 기본 비활성 Google 변수와 빈 자격정보 예시 |
| `README.md` | OTP 실수신·OIDC 서버/실브라우저 상태 구분·안내 링크 |
| `build.gradle` | Boot BOM의 공식 OAuth2 Client 스타터 1개 추가 |
| `docs/engineering/account-provisioning.md` | 검증된 Google 신원 접점 안내 |
| `docs/engineering/backend.md` | 현재 인증 구현·검증 상태 |
| `docs/engineering/email-otp.md` | LIVE/Google 상태 구분, 공용 세션 저장 검증 |
| `docs/engineering/local-postgresql.md` | 현재 로그인 경로·OIDC 설정 안내 |
| `docs/engineering/security-session.md` | 최소 상태 API·OIDC 경계·공용 저장 검증 |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpController.java` | 기존 세션 저장·재조회를 공용 서비스에 위임, OTP 계약 유지 |
| `src/main/java/com/overload/overload_server/security/SecurityConfiguration.java` | 명시적 OIDC 구성·정확한 GET 공개 경로·기존 CSRF/401 유지 |

신규 12개:

| 경로 | 내용 |
|---|---|
| `docs/engineering/google-oidc.md` | 클라이언트 준비·HTTP·프로토콜/트랜잭션·보안·복구 계약 |
| `docs/tasks/task-007-1c-3a-validation-report.md` | 본 보고서 |
| `src/main/java/com/overload/overload_server/auth/google/GoogleClientConfiguration.java` | opt-in Google 등록·콜백/환경 검증·소유 HttpClient 정리 |
| `src/main/java/com/overload/overload_server/auth/google/GoogleOidcSecurity.java` | 공식 OAuth 필터·PKCE·nonce·검증 성공/실패·기존 로그인 보호 |
| `src/main/java/com/overload/overload_server/auth/google/JdbcAuthorizationRequestRepository.java` | 기존 JDBC 익명 세션의 원자적 state 소비 |
| `src/main/java/com/overload/overload_server/auth/google/DiscardingAuthorizedClients.java` | Google 토큰 장기 저장 차단 |
| `src/main/java/com/overload/overload_server/auth/google/GoogleLoginController.java` | 비활성 503·고정 일반 실패 401 |
| `src/main/java/com/overload/overload_server/security/AuthSessionController.java` | 보호된 읽기 전용 GET `/api/v1/auth/session` |
| `src/main/java/com/overload/overload_server/security/VerifiedSessionLogin.java` | 실제 자체 principal/metadata 저장·재조회·실패 폐기 공용화 |
| `src/test/java/com/overload/overload_server/auth/google/GoogleClientConfigurationTests.java` | 기본 비활성·설정 누락·프로필/콜백 검증 |
| `src/test/java/com/overload/overload_server/auth/google/GoogleOidcIntegrationTests.java` | 실제 HTTP·서명/JWKS·PostgreSQL·실패·경합·세션 회귀 |
| `src/test/java/com/overload/testfixture/GoogleOidcFixtures.java` | 테스트 전용 루프백 OIDC/RSA 키·저장 메타데이터 실패 주입 |

기존 테스트 파일은 수정/비활성화하지 않았다. JDBC/Flyway/세션 모델을 유지했고 JPA·Redis·JWT 앱 인증·별도 큐를 도입하지 않았다.
도입 버전은 Java 25 / Gradle 9.8.2 / Boot 4.1.2 / Security Client·Core·Jose 7.1.2다.
BOM 해결 결과 Nimbus OIDC SDK 11.38.2 / JOSE JWT 10.9.1을 사용한다. 임의 버전 override는 없다.
공식 OAuth2 Client 의존성 추가는 이번 지시서에 명시된 구현 범위로 진행했다.

## 3. 엔드포인트·활성화·실 Google 준비

| 요청 | 계약 |
|---|---|
| GET /oauth2/authorization/google | 활성: 표준 authorization 302, 비활성: 503 GOOGLE_LOGIN_UNAVAILABLE |
| GET /login/oauth2/code/google | code/state 콜백, 비활성은 같은 503 |
| 성공 | 영속 자체 세션 재조회 후 고정 `/api/v1/auth/session` 302 |
| 실패/사용자 취소 | 고정 `/auth/google/failure` 302 → 401 GOOGLE_LOGIN_FAILED |
| GET /api/v1/auth/session | 인증 시 200 `{userId}` UUID 하나만, 익명 401, no-store |
| 이미 인증된 브라우저의 Google 시작/콜백 | 409 LOGOUT_REQUIRED, 이전 사용자/세션 유지 |

고정 리디렉션에는 사용자 ID·이메일·code/state·토큰을 싣지 않는다. next/returnUrl 등 외부 입력은 무시한다.
폼/Basic/기본 로그인 HTML을 열지 않으며 Health GET 200/POST 405, CSRF, OTP, 기타 익명 401은 유지한다.
상태 API는 새 업무 쓰기 API가 아니며 요청 userId를 무시하고 서버 principal UUID만 반환한다.

환경변수는 GOOGLE_OIDC_ENABLED(기본 false), GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI다.
local의 기본/유일 허용 콜백은 **http://localhost:8080/login/oauth2/code/google**이다.
비-local·혼합 production 프로필은 같은 경로의 명시적 HTTPS URI가 필요하다. 활성화했는데 필수 설정이 없거나 잘못되면 부팅 실패다.
기존 `.env` import/우선순위는 변경하지 않았으며 `.env.example`에는 실제 자격정보가 없다.

확인 당시 위 Google 설정 3종(enabled/id/secret)은 `.env`에 없었다. 임의 값을 로컬 개발 설정에 넣지 않았다.
Google Console의 Overload Web Dev 생성 완료 여부는 확인되지 않았고, 실제 Google 네트워크 로그인·개인 브라우저 E2E는 수행하지 않았다.
사용자는 웹 클라이언트 생성·위 redirect URI·필요시 Testing Google 사용자 등록·로컬 비밀 설정을 준비해야 한다.
JS origin localhost:3000은 별도 프론트 설정이며 서버 측 시작 URL의 대체가 아니다.
실제 계정 선택/동의·비밀번호/2FA는 사용자 본인이 수행해야 하며 이번에 요청하거나 읽지 않았다.

## 4. OIDC 검증·신원·토큰 보존 경계

공식 `DefaultOAuth2AuthorizationRequestResolver`/PKCE S256, code grant provider,
`OidcIdTokenDecoderFactory`의 RS256/JWKS·OidcIdTokenValidator·JwtTimestampValidator 및 nonce 검증을 사용한다.
생산용 issuer/JWKS 우회 설정, 임의 JWT/OidcUser 입력 API, 수동 토큰 검증기를 추가하지 않았다.

- 등록된 공식 HTTPS Google issuer와 일치, aud의 client ID 포함, 필요한 azp, sub/exp/iat·시각을 검사한다.
- 기본 nonce 원문은 서버 임시 요청에, 해시는 authorization/ID token에 바인딩한다. state는 같은 익명 세션에 묶는다.
- 잘못된 서명·무서명·HS256·issuer/audience/nonce/azp·만료/누락 exp·미래 iat·잘못된 sub 등을 거절했다.
- Google provider 검증 완료 후만 기존 VerifiedIdentity.google로 정규화한다. 키는 `(google, https://accounts.google.com, sub)`다.
  기존 issuer 정규화 코드는 불변이다. 표시 이메일·email_verified는 계정 식별/자동 병합 근거가 아니다.
- 같은 이메일의 OTP/Google과 다른 Google sub는 별도 users/개인 seed다. 같은 sub에서 이메일만 바뀌면 기존 계정/편집/세대를 유지한다.
- ID token으로 충분하여 UserInfo를 호출하지 않고 Google API·offline access·refresh token을 요구하지 않는다.
- Google access/ID token·OidcUser·OAuth2AuthenticationToken은 최종 세션/DB/리디렉션에 보존하지 않는다.
  AuthorizedClient repository는 저장을 폐기한다. 테스트는 기본 authorized-client service도 비어 있음을 확인한다.

토큰 교환 connect 3초/read 5초, 애플리케이션 자동 재시도 없음이다. provider 원문 오류/예외 원인은 일반 오류로 바꾼다.
기본 access log 비활성·no-store·no-referrer와 기존 CSRF를 유지한다. callback 전체 URL/헤더/body·OAuth DEBUG/wire 로그를 켜지 않는다.

## 5. 트랜잭션·상태·세션 연결 순서

1. 기존 JDBC 익명 세션에 임시 authorization request를 저장·재조회한 후만 Google authorization URL을 반환한다.
2. 콜백에서 해당 익명 세션 행을 FOR UPDATE로 잠그고, 최신 state를 같은 연결로 읽는다.
   현재 Spring Session JDBC 기본값과 같은 Spring Core DeserializingConverter를 재사용한다.
   일치한 요청만 익명 세션 행을 원자 삭제·커밋한다. FK로 임시 속성도 지워진다. 새 테이블/V5는 필요하지 않았다.
3. state 소비 트랜잭션이 끝난 뒤 provider 네트워크 코드 교환과 OIDC 검증을 수행한다.
   같은 state 병렬 콜백은 provider 교환·인증 성공 각 1건만 통과했다. 타인 브라우저/잘못된 state는 계정 생성 전 거절한다.
4. OAuth 필터의 기본 성공 동작이 임시 Google principal을 먼저 저장하는 순서를 실제 7.1.2 소스/JAR로 확인했다.
   필터별 NullSecurityContextRepository와 no-op 세션 전략을 적용하고 기본 임시 저장/회전은 맡기지 않았다.
5. 검증된 issuer/sub만 외부 트랜잭션 없이 기존 AccountProvisioningService(REQUIRES_NEW)에 전달한다.
   신규 계정/identity/종목20/설정은 기존 원자성·유일키 경쟁 처리를 그대로 사용한다.
6. VerifiedSessionLogin이 새 JDBC primary key·기존 AuthenticationSessionService를 사용한다.
   최종 principal은 UUID만 가진 SessionPrincipal, metadata는 기존 90일·재인증 기준이다.
   immediate flush 후 실제 저장소 Context·UUID·metadata 전체 일치를 확인한 뒤만 성공 302를 보낸다.

단일 거대 트랜잭션이라고 주장하지 않는다. 프로비저닝 오류는 전부 롤백하고 상태/code는 재활성화하지 않는다.
세션 저장 실패 시 완성된 계정이 남을 수 있으나 성공으로 응답하지 않고 Context·세션을 폐기한다.
저장소 회복 후 새 OAuth 시작부터 진행하면 동일 신원 계정을 재사용한다.
기존 인증 세션은 시작/콜백 필터 앞에서 409로 거절해 교체/병합/90일 갱신을 하지 않는다.
현재 기본 세션 serializer를 다른 방식으로 바꾸면 이 임시 요청 reader도 함께 검토해야 한다.

## 6. 자동화 검증

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/temurin-25.0.4.1"
.\gradlew.bat --no-daemon test integrationTest build
```

최종 전체 실행: **BUILD SUCCESSFUL, 2분 8초**, 8 actionable tasks: 5 executed, 3 up-to-date.
XML 합계 **124개: 단위 33 / PostgreSQL 통합 91**, 실패·오류·skip 모두 **0**이다.

| 테스트 | 개수 |
|---|---:|
| AccountSeedTests / VerifiedIdentityTests | 8 / 3 |
| OtpCryptoTests / ResendOtpEmailSenderTests | 3 / 8 |
| GoogleClientConfigurationTests | 5 |
| HealthControllerTests / 컨텍스트 / SessionPolicyTests | 2 / 1 / 3 |
| AccountProvisioningIntegrationTests | 11 |
| EmailOtpIntegrationTests | 27 |
| GoogleOidcIntegrationTests | 37 |
| PostgresMigrationTests | 6 |
| SecuritySessionIntegrationTests | 10 |
| 합계 | **124** |

기존 82개와 신규 42개(구성 5 / OIDC 통합 37)를 모두 실행했다. 중간의 타깃 실행 결과가 아닌 최종 전체 XML을 집계했다.

테스트는 별도 PostgreSQL 18.6 Testcontainers·JDK 루프백 OIDC 서버·매 실행 생성한 RSA 키/클라이언트만 사용한다.
테스트 등록은 authorization/token/JWKS 및 임시 포트만 대체하고 **공식 Google issuer·서명/nonce/state 검증을 유지**한다.
Mock 자격정보는 test 컨텍스트의 임시 입력이며 `.env`나 생산 기본값에 넣지 않았다.

검증 범위:

- Google 기본 비활성·활성 설정 누락 실패·local 정확한 콜백·HTTPS/혼합 프로필 조건.
- start 302·정확한 scope/state/nonce/PKCE·임의 리디렉션 무시·비활성 Health/OTP/세션 회귀.
- 실제 code→token→서명/JWKS/클레임 검증·신규 seed·다음 HTTP 요청 복원·CSRF 갱신·로그아웃 이전 쿠키 거절.
- 서명/alg/iss/aud/exp/iat/sub/nonce/azp 오류·code 재사용·취소·token 500/timeout/JWKS 장애의 계정/세션 미생성.
- 같은 state 경쟁 1회 교환, 같은 sub 동시 첫 가입 1계정, 재로그인 UUID/세대/개인 편집 보존.
- 같은 이메일 OTP/Google/다른 sub 분리, 인증된 기존 OTP/Google 브라우저의 시작/콜백 사용자 교체 거절.
- 프로비저닝 SQL 실패 전체 롤백·JDBC 속성 저장 실패 및 metadata mismatch의 성공 리디렉션 금지·새 흐름 복구.
- DB 감사 트리거로 성공 핸들러 전을 포함한 **모든 Context 쓰기에서 임시 OAuth principal/ID token 미저장** 확인.
- 임시 state와 자체 인증 세션의 서버 재시작 후 복원, 원래 90일 절대 만료 불변.
- 정상 스키마 준비 후 런타임 연결 풀을 실제 1개로 줄인 로그인 전체 경로 통과.
- 기존 82개의 OTP·계정·V1→V4·보안·90일/10분·로컬/운영 쿠키 회귀 유지.

중간 실패/보완:

1. 테스트의 generic AuthorizedClient 반환값과 AssertJ overload가 충돌해 컴파일이 실패했다. 비밀값을 노출하지 않는 boolean 판정으로 수정했다.
2. 테스트 Primary mock 등록보다 생산 등록 조건이 먼저 평가되어 활성 설정 누락으로 기동이 거절됐다.
   실설정 실패 동작은 유지하고, 루프백 provider만 수락하는 임시 테스트 자격정보를 test 컨텍스트에 명시했다.
3. Spring 7의 제거 예정 messageConverters API를 현재 configureMessageConverters로 바꾸고 token HttpClient도 context 종료 시 닫도록 했다.
4. 123개 전체 통과 후 자체 리뷰에서 잠금 중 SessionRepository 조회가 REQUIRES_NEW로 추가 연결을 요구함을 확인했다.
   같은 DB 연결·공식 기본 converter로 바꾸고 runtime 풀 1개 회귀를 추가했다.
   처음 풀 1개로 앱 자체를 부팅하는 테스트는 Flyway 초기화의 연결 획득에서 실패했다.
   정상 마이그레이션 후 런타임 풀만 제한해 대상 로그인 동작을 직접 검증했고 해당 회귀가 통과했다.

## 7. 보존·비밀·종료 확인

V1~V4 및 Task 006의 HEAD 대비 Git blob은 불변이다:

| 파일 | Git blob SHA |
|---|---|
| V1__create_users.sql | c2bacee9695e41170edd43127a6408132f5ae99f |
| V2__create_account_provisioning.sql | dc5649595defe7e059b18fdcb97a905f8e87ab63 |
| V3__create_spring_session.sql | a5252855ad4819f4035bb3c9f4c577315a0a7a81 |
| V4__create_email_otp.sql | 68704e9b7e397395f6e9597ecf520512b1eae099 |
| Task 006 v0.3.1 | 54412e45b55787899d76e2d829caab6cac3219eb |

생산 JAR의 클래스 목록에서 Google/OTP 테스트·MockProvider·testfixture가 없음을 확인했다.
`.env`는 ignored이며 실제 Resend/HMAC/DB/Google 비밀값·토큰·쿠키를 변경 파일/보고서에 포함하지 않는다.
추적 diff와 신규 미추적 파일의 공백, 실제 비밀값/키 패턴 포함 여부를 검사했다. `.env`와 선행 LIVE 보고서는 fingerprint가 불변이다.
기존 overload-local DB를 시작/접속/마이그레이션하지 않았고 LIVE 생성 계정·볼륨도 변경하지 않았다.
기존 7개 Docker 컨테이너의 ID/실행 상태와 7개 볼륨 목록이 작업 전과 동일하다. 테스트 소유 PostgreSQL/Ryuk는 정리됐다.
앱/테스트/keytool 프로세스 종료를 확인하고, 남은 본 작업 일회성 Gradle daemon은 PID·시작시각·명령/저장소 소유권을 확인해 해당 PID만 정리했다.
미해결 자동화 실패는 없다. 실제 Google 브라우저 E2E만 설정/사용자 참여가 필요한 미검증 항목으로 남긴다.

## 8. 남은 범위

- 실제 Google Console 웹 클라이언트/Testing 사용자 준비 및 사용자 참여 PC 브라우저 E2E는 미검증이다.
- 007-1C-3B의 인증 수단 연결/해제·실제 재인증·계정 변경은 시작하지 않았다. Google/이메일 자동 병합은 계속 금지다.
- 프론트 로그인 UI·자격증명 포함/CSRF 갱신 연동·iPhone HTTPS/LAN·운영 배포는 이번에 하지 않았다.
- 프론트 목표 반복 상한 9~10회 구간을 서버 휴식 정책과 일치시키는 기존 전달 사항도 남아 있다.
- 설정·HTTP·장애 복구·공식 출처는 [Google OIDC 안내](../engineering/google-oidc.md)를 따른다.
