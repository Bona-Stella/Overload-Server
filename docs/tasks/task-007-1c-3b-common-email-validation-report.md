# Task 007-1C-3B 보완 — 공통 대표 이메일 구현·검증 보고서

작성일: 2026-10-28. 사용자 승인 범위는 구현·격리 자동화·보고서까지다.
실제 `.env`/개발 DB/Google Console/비밀 설정을 변경하지 않았으며 Git staging/Commit/Push를 수행하지 않았다.

## 결과와 기존 작업 보존

Google SSO LOGIN을 유지하고 대표 이메일 OTP 재인증을 공통화했다.
`GOOGLE_REAUTH_ENABLED`는 생략/false 시 비활성이고 Google LOGIN과 독립이다.
기존 엄격한 auth_time 검증 코드는 보존하고 기본 비활성으로 전환했다. 누락·오래된 auth_time 또는 iat를
성공으로 처리하는 대체 경로는 없다. Google 연락처 확인 OIDC는 그 실험 재인증과 분리했다.

작업 시작 시 main HEAD는 `5415d9fb5cbe0d18c1b47fe2bb3712c0ff13b7c2`였으며,
origin/main 대비 기존 1커밋 ahead, 미커밋 3B-2 파일 21개가 있었다. 기존 파일을 폐기하거나 되돌리지 않았다.
이전 3B-2 검증·설계 재검토 보고서 두 파일은 작업 전후 해시가 동일하다. 이번 결과는 별도 문서로 기록한다.
Task 006 최종 설계 원문과 V1~V5는 변경하지 않고 이번 사용자 확정 정책을 현재 기술 계약에 반영했다.

## 확정 API와 데이터 모델

상세 계약은 [공통 대표 재인증 이메일](../engineering/reauthentication-email.md)을 따른다.

| 기능 | API/모델 |
|---|---|
| 신원 목록 | 기존 GET `/api/v1/auth/identities`, 자기 identity UUID/provider만 반환 |
| 연락처 상태 | GET `/api/v1/auth/reauth/email/contact`, VERIFIED/REQUIRED 및 마스킹 주소·자기 세션 후보 UUID |
| Google 신원·이메일 후보 확인 | POST `/oauth2/contact/google` form identityId+CSRF → 별도 OIDC 콜백 `/login/oauth2/code/google-contact` |
| 등록 OTP | POST `/api/v1/auth/reauth/email/registration/request`, `{candidateId}` |
| 등록 확인 | POST `/api/v1/auth/reauth/email/registration/verify`, `{candidateId,challengeId,code}` |
| 후보 취소 | POST `/api/v1/auth/reauth/email/registration/cancel` |
| 공통 재인증 | 기존 POST `/api/v1/auth/reauth/email/request` `{}`, `/verify` `{challengeId,code}` |
| 대표 연락처 | V6 `user_reauthentication_emails`, user_id PK, VERIFIED 한 행, 주소·검증 시각·출처 |
| 미검증 후보 | V6 `reauth_email_candidates`, 현재 사용자/기존 Google identity/sub/원본 세션 해시와 만료·소비에 바인딩 |

대표 이메일 주소는 로그인 identity가 아니다. 주소가 같은 서로 다른 UUID를 허용하되 로그인 identity의
기존 전역 소유권 제약은 유지한다. 계정·신원 생성/병합/이전/연결·해제는 추가하지 않았다.
대표 연락처 변경 API는 없고 기존 VERIFIED 값은 이후 로그인/Google 이메일 변경으로 덮어쓰지 않는다.
응답은 원문 주소·issuer/sub를 노출하지 않는다. 클라이언트 주소 필드와 발급/검증 URL의 주소 파라미터는 거절한다.

기존 3B-1 identityId는 선택적으로 호환한다. 자기 EMAIL identity이고 대표 주소와 같을 때만 사용한다.
다른 자기 주소는 409 REAUTH_EMAIL_MISMATCH, 타인/Google identity는 404이며 다른 주소로 발송하지 않는다.
대표 연락처가 없으면 409 REAUTH_EMAIL_REQUIRED, 이미 등록된 최초 등록은 409 REAUTH_EMAIL_ALREADY_VERIFIED다.
기존 OTP 형식/실패/제한/장애와 CSRF/인증 오류 계약은 유지한다. 모든 성공·오류를 제공자와 무관하게 공통 처리한다.

## Google-only 최초 등록의 실제 증명 방식

현재 자체 사용자 UUID·원본 세션을 캡처하고 새 공식 OIDC 흐름을 현재 계정의 기존 Google identity에 바인딩한다.
state 원자 소비와 표준 서명/JWKS·issuer·audience·만료·nonce·PKCE 검증을 거친 뒤 ID 토큰 issuer/sub가
해당 기존 신원과 정확히 같은지 재검사한다. 원시 email 문자열과 Boolean email_verified=true가 필수다.
이메일이 같아도 다른 Google sub는 거부하며 계정을 생성하거나 전환하지 않는다.

이 결과는 후보 생성만 허용한다. 후보 UUID로 사용자가 명시 요청해야 해당 서버 저장 주소 하나에 OTP를 발송하고,
CONTACT_ENROLL OTP 검증에 성공해야 VERIFIED로 등록한다. 연락처·후보/OTP 소비·JDBC 세션 회전은 같은 트랜잭션이다.
등록 OTP의 최근 소유권 증명을 재사용해 재인증 시각도 갱신하므로 중복 재인증 메일을 요구하지 않는다.
OIDC 확인 자체로는 최근 시각을 갱신하지 않으며 Google 비밀번호 재인증 강제라고 주장하지 않는다.

과거 이메일 클레임을 저장하지 않은 Google-only 사용자도 동일한 새 bound OIDC 확인으로 진입한다.
임의 주소 입력, 과거 세션의 미검증 이메일 사용, auth_time 우회가 필요하지 않다.
실제 Google callback 등록과 email/email_verified 클레임 검증은 이번 자동화와 구분된 후속 LIVE 항목이다.

## EMAIL 최초 등록과 V6 전환

실제 LOGIN OTP를 원자 소비한 시각과 프로비저닝된 EMAIL identity 소유권을 재확인하여 최초 연락처를 등록한다.
추가 메일은 없고 범용 VerifiedIdentity/프로비저닝만으로는 등록하지 않는다.

V6는 보존된 accepted+CONSUMED LOGIN OTP 또는 소유자가 일치하는 REAUTH 증거가 있는 기존 EMAIL 주소만
HISTORICAL_OTP로 보충한다. 과거 LOGIN의 주소는 전역 유일 EMAIL 신원과 신원 이전 미구현 계약에 근거해 연결한다.
bare identity, Google-only 또는 입증 주소가 둘 이상인 계정은 자동 선택하지 않는다.
근거가 없거나 모호한 EMAIL 계정은 다음 정상 EMAIL OTP 로그인으로 최초 등록한다.
실제 과거 데이터에 증거가 얼마나 남았는지는 개발 DB에 접속하지 않았으므로 산정하지 않았다.

사용자·신원·기존 OTP 행과 V1~V5 체크섬은 보존한다. 기존 V5 REAUTH 실패 횟수/실패 창/발급 시각 및
request-email/verify-email rate 창/hits는 새 사용자+주소 범위 키로 복사하고 기존 행은 그대로 둔다.
LOGIN/IP 제한은 불변이다. 과거 미완료 REAUTH 코드는 기존 바인딩을 보존하되 새 API에서는 수용하지 않으므로
전환 후 기존 제한이 끝나면 다시 발급해야 한다. V6는 일회성 Testcontainers에서만 적용했으며 개발 DB에는 미적용이다.

## 발송·동시성·세션 경계

- LOGIN/REAUTH/CONTACT_ENROLL 목적별 HMAC·상태·실패 예산을 분리한다. LOGIN HMAC 바이트 형식은 불변이다.
- REAUTH/등록은 사용자+주소별 guard/rate를 사용한다. 같은 주소의 다른 계정은 분리하고 같은 사용자의
  새 세션/새 OIDC 후보는 기존 60초 제한/오답 예산을 초기화하지 못한다. IP 제한도 유지한다.
- PostgreSQL guard/rate 락 안에서 한 건만 예약한 뒤 트랜잭션 밖에서 발송한다. 동시 발급은 202 한 건/429 한 건,
  두 로그인 제공자가 연결돼 있어도 대표 주소 한 곳에만 발송한다. Resend 자동 재시도는 없다.
- 기존 5분 유효/60초 재전송/5회 오답, 30분 누적 실패 창, 요청·검증/IP rate 상한을 유지한다.
  후보는 발급 예약 시에만 실제 OTP 발급+5분까지 연장하여 지연 발급도 동일한 유효기간을 보장한다.
- 성공은 새 session primary key/ID를 만들고 기존 세션·CSRF를 폐기한다. userId/loginStartedAt/최초 로그인+90일은
  유지하고 reauthenticatedAt만 OTP 검증 시각으로 갱신한다. 최근 10분 경계와 새 CSRF 발급 계약을 유지한다.
- 커밋 전 DB/절대 만료 오류는 연락처·OTP·회전을 롤백한다. 커밋 후 저장소/쿠키/응답 검증 실패는 성공하지 않고
  새 세션을 폐기한다. 확정된 연락처/소비 OTP는 보존하고 정상 LOGIN으로 복구한다.

## 자동화·빌드 검증

최종 실행은 BUILD SUCCESSFUL(2분 50초)이다. 실행 명령:

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/temurin-25.0.4.1'
.\gradlew.bat --no-daemon test integrationTest build
```

검증 환경: Java 25, Gradle 9.8.2, Spring Boot 4.1.2, 실제 PostgreSQL 18.6-alpine Testcontainers,
루프백 RSA/JWKS OIDC 서버와 fake sender. 테스트 전용 fixture/injector는 src/test에만 존재한다.
실제 Google·Resend 인증/메일 요청 및 기존 개발 Compose DB 작업은 수행하지 않았다.

JUnit XML 최종 집계: **271개 통과, 실패 0, 오류 0, skipped 0**. 기존 234개에 공통 이메일 35개,
Google 두 목적 공존 회귀 1개, V5→V6 보존 전환 1개를 추가했다.

| 구분 | 테스트 수 |
|---|---:|
| 단위/컨텍스트/기본 HTTP | 38 |
| Account provisioning PostgreSQL | 11 |
| LOGIN OTP PostgreSQL | 27 |
| 기존 이메일 재인증 PostgreSQL | 40 |
| 공통 대표 이메일 PostgreSQL/OIDC | 35 |
| 기존 Google LOGIN PostgreSQL/OIDC | 38 |
| 엄격한 Google REAUTH PostgreSQL/OIDC 및 두 목적 공존 | 64 |
| Flyway/PostgreSQL | 8 |
| JDBC 세션 PostgreSQL | 10 |
| 합계 | 271 |

Java 컴파일·test·integrationTest·bootJar·build가 성공했다. 새 의존성·테스트 비활성화는 없다.
배포 JAR에 V6가 포함됐고 테스트 픽스처/통합 테스트/.env/TLS 비밀 파일은 포함되지 않았다.

검증 범위는 EMAIL/Google-only/과거 Google-only/연결 신원의 단일 연락처와 단일 발송,
동일 주소의 다른 사용자 분리, 잘못된 issuer/sub/서명/nonce/email_verified/누락 이메일,
임의 주소 입력 거절, 취소·만료·다른 세션·LOGIN/등록 목적 혼용, 동시 발급/검증,
오답 예산·후보 재확인·재전송 제한·지연 발급 후 5분 경계, Google 이메일 변경 시 불변,
SQL 장애/회전/재조회/쿠키/최종 응답/90일 만료/CSRF/10분 경계 및 전 V1~V5 체크섬·데이터 보존이다.
실험 Google REAUTH true와 연락처 확인이 함께 켜진 경우에도 각 필터/state를 구분하는 회귀를 포함한다.
기존 234개 테스트는 비활성화하지 않았으며 V6의 정상 스키마/version 기대값만 갱신했다.

초기 회귀에서는 V6 테이블을 추가한 뒤 account 테스트의 정확한 테이블 목록 기대값과, linked fixture의
EMAIL 로그인 후 identity 조회 범위 때문에 2건이 실패했다. 새 테이블 기대값과 provider='email' 선택을 수정하고
픽스처도 실제 OTP 증명으로 연락처를 생성하도록 정리했다. 검증을 완화하거나 테스트를 삭제하지 않았다.
최종 미해결 검증 실패는 없다. JVM 클래스 공유와 Gradle configuration cache 안내는 빌드 실패가 아니다.

## 변경 파일과 Git 상태

최종 목록은 보고서 자체를 포함한 46개 파일이다. 아래 목록에는 시작 전부터 있던 3B-2 변경 21개도 포함한다.
그중 과거 보고서 2개는 이번에 수정하지 않았다. 기존 엄격한 GoogleAuthenticationTime 판정은 보존했다.

```text
.env.example
docs/engineering/email-otp.md
docs/engineering/email-reauthentication.md
docs/engineering/google-oidc.md
docs/engineering/google-reauthentication.md
docs/engineering/reauthentication-email.md
docs/engineering/security-session.md
docs/tasks/task-007-1c-3b-2-design-review.md                 (기존 내용 보존)
docs/tasks/task-007-1c-3b-2-validation-report.md             (기존 내용 보존)
docs/tasks/task-007-1c-3b-common-email-validation-report.md
src/main/java/com/overload/overload_server/account/AccountIdentityRepository.java
src/main/java/com/overload/overload_server/account/AccountIdentityService.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpConfiguration.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpRepository.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpService.java
src/main/java/com/overload/overload_server/auth/email/EmailReauthenticationController.java
src/main/java/com/overload/overload_server/auth/email/EmailReauthenticationService.java
src/main/java/com/overload/overload_server/auth/email/OtpChallenges.java
src/main/java/com/overload/overload_server/auth/email/OtpCrypto.java
src/main/java/com/overload/overload_server/auth/email/OtpPurpose.java
src/main/java/com/overload/overload_server/auth/email/ReauthenticationEmails.java
src/main/java/com/overload/overload_server/auth/email/ResendOtpEmailSender.java
src/main/java/com/overload/overload_server/auth/google/GoogleAuthenticationTime.java
src/main/java/com/overload/overload_server/auth/google/GoogleBoundOidcSecurity.java
src/main/java/com/overload/overload_server/auth/google/GoogleBoundRequests.java
src/main/java/com/overload/overload_server/auth/google/GoogleContactRequests.java
src/main/java/com/overload/overload_server/auth/google/GoogleContactSecurity.java
src/main/java/com/overload/overload_server/auth/google/GoogleLoginController.java
src/main/java/com/overload/overload_server/auth/google/GoogleOidcSecurity.java
src/main/java/com/overload/overload_server/auth/google/GoogleReauthenticationRequests.java
src/main/java/com/overload/overload_server/auth/google/GoogleReauthenticationSecurity.java
src/main/java/com/overload/overload_server/security/AbsoluteExpiryCookieSerializer.java
src/main/java/com/overload/overload_server/security/ReauthenticationResponseFilter.java
src/main/java/com/overload/overload_server/security/ReauthenticationSessionRepository.java
src/main/java/com/overload/overload_server/security/SecurityConfiguration.java
src/main/java/com/overload/overload_server/security/SessionReauthentication.java
src/main/resources/db/migration/V6__add_reauthentication_emails.sql
src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java
src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java
src/test/java/com/overload/overload_server/auth/google/CommonReauthenticationEmailIntegrationTests.java
src/test/java/com/overload/overload_server/auth/google/GoogleAuthenticationTimeTests.java
src/test/java/com/overload/overload_server/auth/google/GoogleReauthenticationIntegrationTests.java
src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java
src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java
src/test/java/com/overload/testfixture/GoogleOidcFixtures.java
src/test/java/com/overload/testfixture/GoogleReauthenticationFixtures.java
```

최종 Git 상태: **main, HEAD 변경 없음, origin/main 대비 기존 ahead 1, 변경 46개(추적 파일 수정 28개,
미추적 파일 18개), staged 0개**. 이 중 21개는 작업 시작 전부터 있던 3B-2 변경이다.
새 Commit/Push 또는 임의 기존 작업 폐기는 하지 않았다.

`git diff --check`, `git diff --cached --check` 통과. 첫 명령의 LF→CRLF 안내는 저장소의 기존 변환 설정이며
파일 내용을 자동 재작성하지 않았다. 명령 한정 `-c core.autocrlf=false` 검사도 통과했다.
V1~V5/Task 006의 Git diff는 없고 이전 설계·검증 보고서 두 파일과 실제 `.env`의 해시도 유지됐다.
프론트엔드 HEAD/작업 트리도 변경하지 않았다.
실제 `.env`의 키·비밀번호·Google 식별자/발신 주소와 변경 파일을 비노출 비교하여 일치 항목이 없음을 확인했다.
Git 변경 목록에 .env·빌드 산출물·TLS 비밀 키·Docker 데이터 경로가 없다.

이번 Gradle/Overload Java 프로세스 잔존 0개, 공통 이메일 및 기존 3B-2 테스트 라벨의 컨테이너 잔존 0개를
읽기 전용으로 확인했다. 테스트 앱/HTTP 클라이언트/OIDC 서버는 테스트 종료 시 닫혔다.
기존 사용자 프로세스·Compose 컨테이너·볼륨에는 정리 명령을 실행하지 않았다.

## 제한과 후속 작업

제품 구현을 막는 설계 충돌은 없다. 주소 중복과 로그인 identity 소유권은 별개이며 자동 병합은 금지한다.
대표 이메일 변경/분실 복구/인증 수단 연결·해제/패스키/프론트 UI/운영 배포/iPhone HTTPS는 구현하지 않았다.
변경은 민감 작업으로 기존 수단 재인증+새 주소 OTP가 필요한 후속 계약이다.

후속 승인 LIVE에서는 사용자가 Google Console의 google-contact 콜백 등록을 확인하고 실제 이메일 claim·
OIDC 취소/동의·메일 전달과 브라우저 CSRF/회전을 검증해야 한다. 이번 자동화 성공은 그 LIVE 성공 증거가 아니다.
실험 auth_time 재인증의 개발 클라이언트 제공 여부도 여전히 미확인이다.
[Google 공식 Security Bundle](https://developers.google.com/identity/siwg/security-bundle)의 강제 재인증 미지원 및
게시·검증 앱 설정 조건 때문에 기본 비활성을 유지한다. 공통 이메일 경로는 그 추가 클레임에 의존하지 않는다.
추가 승인 요청은 없으며 금지된 외부/개발 DB/Git 작업은 보류한 상태다.
