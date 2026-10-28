# Task 007-1C-3B-3B — Google 로그인 수단 연결·해제 검증 보고서

작성일: 2026-10-28. 구현·자동화 검증·자체 검토 완료.
실제 Google/Resend 요청, 개발 DB 마이그레이션·데이터 변경, Commit·Push는 수행하지 않았다.

## 시작 상태와 범위

main HEAD `18a6e147fc762bfd2812d7cf659df4f22189c138`에서 시작했고 작업 트리는 clean이었다.
3B-3A EMAIL 구현은 커밋되어 있었다. Task 006·V1~V7·기존 LIVE 보고서·실제 `.env`·프론트를 보존했다.
실제 개발 계정의 UUID·identity·개인 데이터·세션에 접속하거나 변경하지 않았다.

상세 기술 계약은 [Google 로그인 수단](../engineering/google-identities.md)에 기록했다.
기존 일반 LOGIN/EMAIL OTP/대표 연락처/세션과 Google 강제 재인증의 기본 비활성을 유지했다.

## 최종 API

| 요청 | 성공/계약 |
|---|---|
| POST /oauth2/link/google | 인증+CSRF, 빈 form 또는 `_csrf`만. 공식 OIDC로 302 |
| GET /login/oauth2/code/google-link | 원본 세션·proof·state·OIDC 검증 후 고정 GET identities로 302 |
| GET /auth/google/link/failure | 401 GOOGLE_LINK_FAILED, 프로토콜 실패의 고정 도착점 |
| POST /api/v1/auth/identities/google/unlink | `{identityId}`, 자기 Google 신원만 해제, 204 |
| GET /api/v1/auth/identities | 기존 UUID/provider 목록에 결과 반영 |

LINK는 요청 userId·identityId·이메일·sub·issuer·next/returnUrl을 받지 않는다. 비어 있지 않은 JSON도 400이다.
기존 인증 세션의 일반 Google LOGIN은 409 LOGOUT_REQUIRED를 유지한다.
성공/실패 리디렉션에 code/state/토큰/sub/주소를 넣지 않고 no-store/no-referrer를 사용한다.

401 AUTHENTICATION_REQUIRED, 403 CSRF_INVALID/EMAIL_OWNERSHIP_PROOF_REQUIRED,
409 REAUTH_EMAIL_REQUIRED/IDENTITY_ALREADY_OWNED/LAST_LOGIN_IDENTITY/IDENTITY_CHANGE_SUPERSEDED,
404 IDENTITY_NOT_FOUND, 400 INVALID_REQUEST, 503 IDENTITY_CHANGE_UNAVAILABLE을 구분한다.
Google 비활성 LINK 시작/콜백은 인증된 사용자에게 503 GOOGLE_LINK_UNAVAILABLE이다.
OAuth 취소·서명/클레임/nonce/PKCE/state 오류·provider 장애는 고정 failure 경로이며 원본 계정을 교체하지 않는다.
일부 공통 redirect 저장 오류도 상세를 반사하지 않고 해당 failure 경로로 처리한다.

## 원본 계정·proof·Google 신원 증명

EMAIL과 Google이 `IdentityChangeAuthorization`을 공유한다. 사용자→자기 identity→원본 세션 순으로 잠그고
현재 VERIFIED 대표 연락처 및 DB의 EmailOwnershipProof를 검사한다. 단순 SSO reauthenticatedAt는 근거가 아니다.
실제 OTP 출처와 현재 userId/sessionId/연락처 UUID·주소 해시 및 10분 이하 검증 시각을 요구한다.

LINK Pending은 원본 Snapshot, **정확한 이메일 proof 값**, 요청 시각, 해제 세대, 표준 authorization request를
서버의 전용 JDBC 속성에 저장한다. 수명은 5분 미만, state는 일회용이다. 다른 세션/사용자/브라우저/바뀐 proof,
로그아웃·회전·10분 초과·90일 만료를 거부한다. 구형 5필드 Pending도 읽되 LINK 증명을 소급 부여하지 않는다.

공식 OIDC provider/decoder와 code/state/nonce/PKCE S256, RSA/JWKS·issuer·audience/azp·exp/iat·sub 검증을 사용한다.
Google 계정 선택에는 select_account만 요청한다. auth_time/max_age나 Google 비밀번호 강제 재인증에 의존하지 않는다.
Google identity는 검증된 issuer/sub만 사용한다. 표시 이메일이 같아도 다른 계정의 신원은 409이며 이전/병합하지 않는다.
표시 이메일이 다르거나 LINK 토큰에 email/email_verified가 없어도 신원 검증 결과를 이메일 소유 증명으로 대체하지 않는다.
대표 연락처는 변경하지 않고 EMAIL identity도 자동 생성하지 않는다.

LOGIN/CONTACT/조건부 REAUTH/LINK는 별도 registrationId·callback·purpose·Pending 이름을 사용한다.
공용 필터와 저장 구조를 재사용하지만 LOGIN 성공/프로비저닝 핸들러를 LINK에 재사용하지 않는다.
연결은 기존 사용자에 credential만 삽입하고 AccountProvisioningService/seed를 호출하지 않는다.
임시 OidcUser·ID/access token·authorized client를 최종 세션에 저장하지 않으며 자체 Context를 복원한다.
userId/loginStartedAt/absoluteExpiresAt/reauthenticatedAt/EmailOwnershipProof 검증 시각·CSRF는 변경하지 않는다.

## DB 원자성·경합·FK 보존

state는 잠금 안에서 원자 소비·커밋한 뒤 provider 네트워크를 호출한다. 사용자/세션 행 락을 잡고 Google을 기다리지 않는다.
실제 연결 트랜잭션과 커밋 직전에도 원본 계정·정확한 proof·세대·요청 수명을 재검사한다.
기존 auth_identities_credential_unique와 ON CONFLICT 및 owner 재조회로 같은 subject의 단일 소유자를 보장한다.
자기 중복은 정상 성공, 다른 소유자는 409이며 사용자·운동·설정·연락처는 불변이다.

해제는 같은 사용자 락과 proof를 사용하고 다른 **사용 가능한** 로그인 수단을 재검사한다.
EMAIL_LOGIN_ENABLED를 기본 true로 도입해 기존 LOGIN을 유지한다. 명시 false면 EMAIL LOGIN request/verify는
503 EMAIL_LOGIN_UNAVAILABLE이며 공통 REAUTH/CONTACT 엔진은 별개다. 실제 `.env`는 바꾸지 않았다.
마지막 수단 판정은 Google 활성 여부 및 EMAIL 활성·HMAC/sender 로컬 ready를 포함한다.
외부 provider 건강 검사를 하거나 장애를 예측하지는 않는다. 비활성 EMAIL만 남기고 Google을 해제할 수 없다.

추가형 V8은 다음을 수행한다. 마이그레이션 자체는 기존 행/직렬화 bytes를 쓰거나 삭제하지 않는다.

- 사용자별 Google 해제 세대 테이블 추가. Google unlink 시만 증가시키고 저장 값을 재조회한다.
- CONTACT 후보에 retired Google UUID/invalidated_at을 추가. 기존 소유자 FK를 보존하며 CASCADE를 NO ACTION으로 바꾼다.
- 과거 CONTACT_ENROLL OTP도 live identity FK를 역사 UUID로 보존할 수 있도록 기존 CHECK를 확장한다.

명시 unlink 트랜잭션에서 대상 Google 후보를 만료·퇴역시키고 OTP 참조를 보존한 후 신원만 삭제한다.
후보/OTP의 사용자·주소·subject·state·verifier·오답/소비 이력을 삭제하지 않는다. 대표 연락처도 유지한다.
다른 세션의 해당 CONTACT/REAUTH Pending과 모든 미완료 LINK Pending을 선택적으로 무효화한다.
state를 이미 소비해 네트워크 교환 중이어도 세대 변경을 확인하여 늦은 재연결을 차단한다.
신원/후보/OTP 참조/Pending/세대는 같은 트랜잭션이며 오류·저장 누락·만료 시 함께 롤백한다.

해제한 issuer/sub로 이후 로그인하면 기존 계정에 접근하지 못한다. 기존 신규 가입 정책이 새 UUID를 만들 수 있고
기존 데이터 이전은 없다. 이미 승인된 다른 기기 세션은 EMAIL 정책과 같이 전역 로그아웃하지 않는다.
따라서 신원 해제가 기존 인증 세션의 전역 철회를 의미하지 않는 한계를 유지한다.
커밋 전 실패는 credential 변경을 롤백하지만 선행 state/code 소비를 되살리지는 않는다.
응답이 불확실하면 GET identities로 실제 결과를 확인하며 외부 인증을 자동 반복하지 않는다.

## 자동화·빌드 결과

최종 실행:

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/temurin-25.0.4.1'
.\gradlew.bat --no-daemon test integrationTest build
```

**BUILD SUCCESSFUL (4분 3초), 364개 통과, 실패 0·오류 0·skipped 0.**
실제 PostgreSQL 18.6-alpine Testcontainers, 루프백 OIDC/JWKS/RSA 서버, fake sender만 사용했다.

| 테스트 | 수 |
|---|---:|
| 단위/컨텍스트/HTTP (구형 Pending 호환 1개 포함) | 39 |
| 계정 provisioning PostgreSQL | 11 |
| EMAIL LOGIN OTP PostgreSQL | 27 |
| EMAIL REAUTH PostgreSQL | 40 |
| 공통 대표 이메일 PostgreSQL/OIDC | 35 |
| EMAIL 수단/소유 증명 PostgreSQL/OIDC | 34 |
| 새 Google LINK/UNLINK PostgreSQL/OIDC | 56 |
| 기존 Google LOGIN PostgreSQL/OIDC | 38 |
| 기존 조건부 Google REAUTH PostgreSQL/OIDC | 64 |
| Flyway/PostgreSQL (V7→V8 보존 추가) | 10 |
| 보안·JDBC 세션 PostgreSQL | 10 |
| 합계 | 364 |

기존 306개를 유지하고 Google 통합 56개, V8 보존 1개, 구형 Pending 호환 1개를 추가했다.
기존 테스트를 제거/비활성화하거나 보안 조건을 낮추지 않았다.
최신 스키마/version 기대값을 8로 갱신했다. 초기 전체 회귀에서 기존 Google REAUTH 정리 코드가
후보 CASCADE에 의존해 후속 7건이 실패했다. 일회성 테스트 DB에서 후보/연락처를 먼저 정리하도록 수정했고
기존 보안 assertion은 유지했다. 최종 미해결 실패는 없다.

주요 검증:

- EMAIL-only의 신규 Google 연결, 이후 양쪽 LOGIN의 같은 UUID 재사용, 사용자/20개 종목/설정 불변.
- 자기 중복, 다른 계정 소유 subject 충돌, 같은 이메일의 계정 분리, 다른 Google 이메일이어도 연락처 불변/EMAIL 자동 생성 없음.
- 모든 mock provider fault(서명/issuer/audience/azp/만료/nonce/토큰 장애 등), PKCE 변조, state·목적 혼용·취소와 원본 세션/CSRF 보존.
- 단순 SSO/미검증 후보/바뀐 proof/다른 사용자·브라우저·세션, 10분 정확 경계, 5분 Pending 경계, 회전·로그아웃·90일 만료 거부.
- Google/EMAIL 비활성 및 sender 비준비 시 마지막 수단 보호, 다른 사용자 해제 거부, 중복·동시 callback/unlink와 두 사용자의 단일 소유자 경쟁.
- CONTACT Pending/후보/OTP 발급 중 및 token 교환 중 unlink, LINK state 소비 후 unlink가 먼저 끝난 경우의 재연결 차단.
- 실제 INSERT를 advisory gate로 멈춘 상태의 proof/Pending/90일 만료와 커밋 롤백. 고정 sleep으로 경합 시점을 추측하지 않는다.
- SQL INSERT/DELETE 실패와 세대 저장의 조용한 누락 시 신원·후보·Pending·세션 롤백, 재시작 Pending/proof 복원, 토큰 장기 저장 없음.
- V1~V7 체크섬과 기존 Google 후보/OTP·직렬화 proof bytes 보존, CASCADE 방지 FK 독립 검사, 재마이그레이션 0건.
- 이전 5필드 Pending 클래스를 별도 classloader에서 실제 직렬화한 뒤 새 형식으로 복원해 추가 proof가 null/세대 0임을 확인.

컴파일의 테스트 generic 경고와 JVM 클래스 공유 안내는 빌드 실패가 아니다. 신규 주요 의존성은 없다.

## 변경 파일·Git·정리

총 37개 파일(추적 수정 24개, 신규 13개), staged 0. 시작 HEAD/main을 유지하고 Commit·Push하지 않았다.

```text
.env.example
docs/engineering/account-provisioning.md
docs/engineering/email-identities.md
docs/engineering/email-otp.md
docs/engineering/google-identities.md
docs/engineering/google-oidc.md
docs/engineering/security-session.md
docs/tasks/task-007-1c-3b-3b-validation-report.md
src/main/java/com/overload/overload_server/auth/email/EmailIdentityService.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpController.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpErrors.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpService.java
src/main/java/com/overload/overload_server/auth/email/OtpChallenges.java
src/main/java/com/overload/overload_server/auth/email/ReauthenticationEmails.java
src/main/java/com/overload/overload_server/auth/google/GoogleBoundOidcSecurity.java
src/main/java/com/overload/overload_server/auth/google/GoogleBoundRequests.java
src/main/java/com/overload/overload_server/auth/google/GoogleLinkRequests.java
src/main/java/com/overload/overload_server/auth/google/GoogleLinkSecurity.java
src/main/java/com/overload/overload_server/auth/google/GoogleLoginController.java
src/main/java/com/overload/overload_server/auth/google/GoogleOidcSecurity.java
src/main/java/com/overload/overload_server/auth/google/GooglePendingInvalidation.java
src/main/java/com/overload/overload_server/auth/identity/GoogleIdentityController.java
src/main/java/com/overload/overload_server/auth/identity/GoogleIdentityService.java
src/main/java/com/overload/overload_server/auth/identity/IdentityChangeAuthorization.java
src/main/java/com/overload/overload_server/auth/identity/IdentityChangeFailure.java
src/main/java/com/overload/overload_server/auth/identity/LoginAvailability.java
src/main/java/com/overload/overload_server/security/ReauthenticationSessionRepository.java
src/main/java/com/overload/overload_server/security/SecurityConfiguration.java
src/main/java/com/overload/overload_server/security/SessionReauthentication.java
src/main/resources/db/migration/V8__preserve_google_unlink_references.sql
src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java
src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java
src/test/java/com/overload/overload_server/auth/google/GoogleIdentityIntegrationTests.java
src/test/java/com/overload/overload_server/auth/google/GooglePendingCompatibilityTests.java
src/test/java/com/overload/overload_server/auth/google/GoogleReauthenticationIntegrationTests.java
src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java
src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java
```

Gradle/Overload Java 프로세스 잔존 0개, 이번 라벨 Testcontainer 잔존 0개를 읽기 확인했다.
기존 개발 postgres는 중지 상태이며 이번 작업에서 Compose 시작/중지/DB 접속은 하지 않았다.
배포 JAR에는 V8이 포함되고 테스트 픽스처/통합 테스트/.env/TLS 비밀 파일은 없다.
실제 키·비밀번호·OAuth 토큰·쿠키·OTP·개인정보를 보고서에 싣지 않는다.
최종 `git diff --check`와 `git diff --cached --check`는 통과했다. 변경 37개 전체가 위 목록과 일치하고,
실제 `.env`의 비밀 설정값과 비노출 대조에서 일치한 값/토큰 패턴은 0건이다.
실제 `.env`·선행 LIVE 보고서의 전체 해시와 HEAD가 시작 상태와 같고, V1~V7·Task 006·기존 LIVE 문서의 diff는 없다.
프론트 HEAD 및 clean 작업 트리도 보존했다. main은 기존 origin/main 대비 ahead 1이며 staged 0이다.

## 실제 LIVE 준비와 남은 범위

개발 DB V8 적용과 실제 Google 연결·해제는 미검증이며 별도 승인 LIVE에서 진행해야 한다.
Google Console에 기존 LOGIN/CONTACT URI를 유지하고 새 callback
`http://localhost:8080/login/oauth2/code/google-link`를 추가 등록해야 한다. 이번에는 Console을 변경하지 않았다.
실제 계정 선택/동의·다중 계정·현재 브라우저 세션과 이메일 proof의 시간/해제 결과를 확인해야 한다.
Google 강제 재인증 활성화·대표 이메일 변경/복구·계정 병합·프론트·운영/iPhone HTTPS는 수행하지 않았다.
제품 구현을 막는 미해결 문제와 추가 승인 대기는 없다. 자동화 통과를 실제 Google LIVE 성공으로 보고하지 않는다.
