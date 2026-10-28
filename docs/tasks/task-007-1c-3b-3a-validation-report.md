# Task 007-1C-3B-3A — EMAIL 로그인 수단·이메일 소유 증명 검증 보고서

작성일: 2026-10-28. 구현·격리 자동화·자체 검토를 완료했다. 실제 개발 DB/Google 인증/Resend 발송,
Git staging/Commit/Push는 수행하지 않았다.

## 시작 상태와 보존

시작 HEAD는 `e5ca64eb7a9f3c59ada36de40e12909cf9dd6f03`, main이며 작업 트리는 clean이었다.
선행 Common Email LIVE 보고서는 이미 커밋되어 있었다. 그 보고서와 실제 `.env`는 보존했고,
Task 006 및 V1~V6, 기존 SessionMetadata 직렬화 소스는 변경하지 않았다.
기존 실제 EMAIL/Google 계정이나 개발 DB를 열어 수정·합치거나 마이그레이션하지 않았다.

## 확정 API

상세 계약은 [EMAIL 로그인 수단·이메일 소유 증명](../engineering/email-identities.md)을 따른다.
기존 GET `/api/v1/auth/identities`와 principal 기반 사용자 선택을 유지한다.

| 요청 | 입력 | 성공 |
|---|---|---|
| POST /api/v1/auth/identities/email/link | `{}` | 신규 201 LINKED, 자기 기존 신원은 200 ALREADY_LINKED |
| POST /api/v1/auth/identities/email/unlink | `{identityId}` | 204, 반복/없는/타인/Google 대상은 404 |

link 성공 본문은 `{status,identity:{id,provider:"email"}}`이며 자기 신원 UUID만 반환한다.
연결 주소는 서버의 VERIFIED 대표 이메일 하나로 고정한다. 임의 주소·userId 및 unknown JSON/query는 400이다.
모든 쓰기는 인증 및 CSRF가 필수이며 no-store다. 이메일 원문·Google sub·proof·token은 반환하지 않는다.

오류는 익명/만료/회전된 세션 401 AUTHENTICATION_REQUIRED, CSRF 오류 403 CSRF_INVALID,
대표 이메일 없음 409 REAUTH_EMAIL_REQUIRED, 이메일 소유 증명 누락/만료/불일치 403 EMAIL_OWNERSHIP_PROOF_REQUIRED,
다른 소유자 충돌 409 IDENTITY_ALREADY_OWNED, 마지막 사용 가능한 수단 409 LAST_LOGIN_IDENTITY,
대상 없음 404 IDENTITY_NOT_FOUND, DB 장애 503 IDENTITY_CHANGE_UNAVAILABLE로 구분한다.

대표 연락처 등록이 EMAIL 로그인을 자동 연결하지 않는다. 실제 이메일 증명이 있어도 link POST가 필요하다.
다른 UUID가 동일 EMAIL credential을 소유하면 이동/병합하지 않고 충돌한다. 해제는 자기 EMAIL 행만 대상으로 하며
대표 연락처·사용자·개인 데이터·세션 기한을 삭제/갱신하지 않는다. Google LOGIN 비활성 상태의 Google identity는
마지막 대체 로그인 수단으로 세지 않는다. Google 강제 재인증의 기본 비활성 설정은 유지했다.

## 일반 SSO 시각과 실제 이메일 증명의 구분

기존 SessionMetadata timestamp만으로는 EMAIL 연결·해제를 허용하지 않는다.
별도 서버 소유 JDBC 세션 속성 `EmailOwnershipProof`에 사용자 UUID·현재 sessionId·대표 연락처 UUID·정규화 주소 해시·
실제 OTP 소비 시각·출처 EMAIL_LOGIN/EMAIL_REAUTH/CONTACT_ENROLL을 저장한다. 원문 주소는 추가 저장하지 않는다.
클라이언트가 proof나 출처를 입력하는 API는 없다. 주소 해시는 OTP verifier가 아니며 기존 OTP HMAC은 유지한다.

| 실제 검증 경로 | 증명 생성과 보존 |
|---|---|
| EMAIL LOGIN OTP | 실제 소비 시각과 현재 대표 주소 일치 시 새 인증 세션에 바인딩. 다른 legacy EMAIL 주소 로그인은 proof를 만들지 않음 |
| 공통 EMAIL REAUTH OTP | OTP 소비·새 JDBC 세션 PK/ID·proof를 같은 트랜잭션으로 저장 |
| CONTACT_ENROLL OTP | 실제 연락처 등록·후보/OTP 소비·세션 회전·proof를 같은 트랜잭션으로 저장 |
| 일반 Google SSO | 기존 reauthenticatedAt는 유지하지만 이메일 proof를 생성하지 않음 |
| 일반/Google 세션 회전 | 현재 사용자·원본 세션에 바인딩된 유효 proof만 새 sessionId로 계승, verifiedAt/source는 갱신하지 않음 |
| 기존 proof 없는 세션 | 기존 timestamp가 최근이어도 새 이메일 재인증 요구, 과거 자료로 소급 성공 추정하지 않음 |

검증 시간은 미래를 거부하고 실제 OTP 소비 후 10분 이하(정확 경계 포함)다. 일반 SSO/회전이 새 10분을 만들지 않는다.
다른 사용자·브라우저·세션·대표 연락처의 proof 및 출처 불명은 거부한다.
현재 DB 세션의 principal/metadata/proof를 잠금 후 읽으며 요청 객체의 시각만 신뢰하지 않는다.
세션 회전의 저장·커밋 후 재조회·쿠키·최종 응답 검사에 proof 전체 일치 검사를 포함했다.
저장 트리거가 proof INSERT를 조용히 무시하는 경우도 성공으로 처리하지 않는다.

기존 SessionMetadata/Snapshot 직렬화 형식을 바꾸지 않고 별도 serialVersionUID=1 클래스를 추가했다.
기존 metadata-only 세션의 로그인 상태는 유지하되 민감 쓰기는 거부하고, 새 proof는 앱 재시작 후 복원된다.
userId/loginStartedAt/최초 로그인+90일은 보존한다. 배포 구버전으로의 다운그레이드는 수행하지 않았다.

## DB 트랜잭션·경합·V7

READ COMMITTED에서 사용자 행 → 자기 identity 행들 → 원본 session 행 순으로 잠근다.
동일 계정의 연결/해제/마지막 수단 검사는 직렬화하며 다른 계정의 identity를 잠그거나 이동하지 않는다.
연결은 기존 auth_identities_credential_unique와 ON CONFLICT DO NOTHING 및 실제 owner 재조회를 사용한다.
따라서 자기 반복은 중복 생성하지 않고, 동일 주소의 다른 사용자는 한 명만 연결된다.

저장/삭제 직후 실제 결과를 확인하고 beforeCommit에서 이메일 증명 및 90일 기한을 재검사한다.
DB 쓰기 중 증명이 10분/90일을 넘으면 전체 identity 변경을 롤백한다. 로그아웃/회전이 먼저 확정되면 거부한다.
기존 프로비저닝의 사용자/seed 생성 정책은 유지한다. 신원 조회만 사용자 락 획득 후 현재 credential 소유권을
다시 읽어, unlink 대기 이전 JOIN 결과를 그대로 돌려주는 경쟁을 막았다.

V7은 과거 V5 REAUTH가 삭제 대상 EMAIL identity를 FK로 참조하는 경계를 처리한다.
retired_reauth_identity_id와 직접 user FK 및 과거 REAUTH CHECK 분기를 추가하며 마이그레이션 자체는 기존 데이터를 쓰지 않는다.
명시 unlink 때만 해당 사용자/identity의 legacy UUID를 과거 참조 열로 보존하고 live FK를 비운다.
state/verifier/실패 예산/소비 시각/소유 사용자는 유지한다. identity 삭제 실패 시 참조 변환도 롤백한다.
과거 바인딩은 V6부터 이미 사용 불가이며 새 인증 권한으로 수용하지 않는다.
V1~V6 SQL·전역 credential unique·현재 OTP 소유권 FK는 불변이다. 개발 DB에는 V7을 적용하지 않았다.

해제한 이메일로 나중에 새 OTP 로그인을 하면 이전 Google 사용자에 접근할 수 없다.
신원이 없을 때 기존 가입 로직이 새 UUID를 만드는 동작은 유지되며, 이전 계정/데이터의 자동 이전이 아니다.
이미 승인된 다른 기존 인증 세션을 unlink가 전역 로그아웃시키지는 않는다.

## 자동화·빌드

최종 실행:

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/temurin-25.0.4.1'
.\gradlew.bat --no-daemon test integrationTest build
```

**BUILD SUCCESSFUL (2분 47초), 306개 통과, 실패 0·오류 0·skipped 0.**
실제 PostgreSQL 18.6-alpine Testcontainers·루프백 서명 OIDC 서버·fake sender를 사용했다.

| 테스트 | 수 |
|---|---:|
| 기존 단위/컨텍스트/기본 HTTP | 38 |
| 기존 account provisioning PostgreSQL | 11 |
| 기존 LOGIN OTP PostgreSQL | 27 |
| 기존 EMAIL REAUTH PostgreSQL | 40 |
| 기존 공통 대표 이메일 PostgreSQL/OIDC | 35 |
| 새 EMAIL identity·증명 PostgreSQL/OIDC | 34 |
| 기존 Google LOGIN PostgreSQL/OIDC | 38 |
| 기존 조건부 Google REAUTH PostgreSQL/OIDC | 64 |
| Flyway/PostgreSQL (V6→V7 보존 1개 추가) | 9 |
| 기존 보안·JDBC 세션 PostgreSQL | 10 |
| 합계 | 306 |

기존 271개 테스트를 제거/비활성화하지 않았다. 새 34개와 V7 보존 1개를 추가했다.
이전 migration 테스트의 최신 version/history 기대값만 7로 갱신했다.

새 테스트는 정상 연결 후 같은 UUID 이메일 로그인·중복 연결·EMAIL-only 마지막 수단 거부·연결 계정의 해제·
대표 연락처/세션/개인 데이터 보존·같은 주소의 다른 사용자 충돌·독립 EMAIL/Google 계정 병합 금지·미검증 후보 거부·
일반 SSO만의 연결/해제 거부·세 가지 OTP proof 수용·legacy 다른 EMAIL 주소 로그인·10분 정확 경계/미래/만료·
다른 기기/사용자/세션/연락처/주소 해시·로그아웃·재시작·중복/동시/서로 다른 소유자 연결·연결/해제 경합·
동시 해제·Google 비활성 대체 수단 거부·SQL 실패/재시도·legacy FK 참조 보존/롤백·proof 저장 누락을 검증했다.
커밋 중 만료는 PostgreSQL advisory gate로 실제 INSERT 대기를 확인한 후 시계를 이동해 검증했다.
고정 sleep 시간만으로 경쟁 시점을 추측하지 않는다.
V7 전환은 V1~V6 체크섬·기존 사용자/identity/OTP와 실제 직렬화 세션 bytes 보존 및 재실행 0건을 검증했다.

초기 검증 오류와 해결:

- OIDC와 EMAIL 재인증 fixture가 같은 저장소에 중복 spy를 적용해 테스트 컨텍스트가 실패했다.
  새 테스트에 불필요한 fixture import를 제거했고 제품 보안 동작은 바꾸지 않았다.
- 보존 테스트 3건이 byte[]를 포함한 Map 비교 때문에 실패했다. verifier bytes를 동일한 hex 표현으로
  비교하여 값 자체를 엄격히 검증하도록 수정했다. assertion/요구사항을 삭제하거나 낮추지 않았다.
- 최종 미해결 실패는 없다. 테스트 지원 코드의 generic 경고/JVM 클래스 공유 안내는 빌드 실패가 아니다.

## 변경 파일·안전 확인·Git

26개 파일, staged 0. 시작 HEAD/main은 유지하며 Commit·Push하지 않았다.

```text
docs/engineering/account-provisioning.md
docs/engineering/email-identities.md
docs/engineering/email-otp.md
docs/engineering/google-oidc.md
docs/engineering/reauthentication-email.md
docs/engineering/security-session.md
docs/tasks/task-007-1c-3b-3a-validation-report.md
src/main/java/com/overload/overload_server/account/AccountRepository.java
src/main/java/com/overload/overload_server/auth/email/EmailIdentityController.java
src/main/java/com/overload/overload_server/auth/email/EmailIdentityService.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpController.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpErrors.java
src/main/java/com/overload/overload_server/auth/email/EmailOtpService.java
src/main/java/com/overload/overload_server/auth/email/EmailReauthenticationService.java
src/main/java/com/overload/overload_server/auth/email/ReauthenticationEmails.java
src/main/java/com/overload/overload_server/security/AuthenticationSessionService.java
src/main/java/com/overload/overload_server/security/EmailOwnershipProof.java
src/main/java/com/overload/overload_server/security/ReauthenticationSessionRepository.java
src/main/java/com/overload/overload_server/security/SessionReauthentication.java
src/main/java/com/overload/overload_server/security/VerifiedSessionLogin.java
src/main/resources/db/migration/V7__preserve_retired_email_otp_identity_references.sql
src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java
src/test/java/com/overload/overload_server/auth/google/EmailIdentityIntegrationTests.java
src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java
src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java
src/test/java/com/overload/testfixture/EmailIdentityFixtures.java
```

테스트용 rotation probe는 src/test 명시 import에서만 존재한다. 배포 JAR에 V7은 포함되지만
testfixture/통합 테스트/.env/TLS 비밀 파일은 없다. 새 주요 의존성이나 프론트 변경은 없다.
Gradle/Overload Java 프로세스 잔존 0개, 이번 테스트 라벨 컨테이너 잔존 0개를 확인했다.
개발 postgres는 중지 상태이며 이번 작업에서 Compose 시작/중지나 DB 접속을 하지 않았다.
실제 키·쿠키·OTP·개인정보를 보고서에 싣지 않는다.
최종 `git diff --check`와 `git diff --cached --check`는 통과했다. 실제 `.env` 설정값과 변경 파일의
비노출 대조에서 일치한 비밀값은 0건이며, `.env`와 선행 LIVE 보고서의 전체 해시는 시작 때와 같다.
보고서의 26개 목록은 Git 변경 목록과 일치한다. 프론트 작업 트리도 clean이며 HEAD/main을 유지했다.

## 후속 범위

제품 구현을 막는 미해결 문제나 추가 승인 대기는 없다. EMAIL 연결·해제의 실제 서비스 LIVE와
개발 DB V7 적용은 미실행이며 별도 후속 검증이다. 대표 이메일 변경/복구·프론트·운영 배포도 범위 밖이다.
일반 Google auth_time 재인증은 계속 기본 비활성이다.

3B-3B에서는 typed 이메일 proof·현재 대표 연락처 검사·사용자/identity/세션 락·커밋 직전 재검사·
JDBC proof 회전/계승을 재사용할 수 있다. 새 Google 수단의 공식 OIDC 소유 증명과 CONTACT 후보 FK 해제 경계는
별도로 설계해야 한다. 이번에는 Google 신규 연결·해제 API를 구현하지 않았다.
