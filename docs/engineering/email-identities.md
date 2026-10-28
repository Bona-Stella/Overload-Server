# EMAIL 로그인 수단과 이메일 소유 증명 — Task 007-1C-3B-3A

대표 재인증 이메일은 연락처, EMAIL identity는 로그인 credential이다. 대표 이메일 최초 등록은
EMAIL identity를 만들지 않는다. 현재 principal 소유 계정에 대한 명시적 POST만 연결·해제를 수행한다.
계정 생성/seed·Google 연결·해제·대표 이메일 변경/복구·계정 병합은 이 API의 역할이 아니다.

## 최근 이메일 소유 증명

기존 `SessionMetadata`의 userId/loginStartedAt/absoluteExpiresAt/reauthenticatedAt 형식과
최초 로그인+90일/최근 10분 정책은 유지한다. 이 timestamp만으로 EMAIL 수단 변경을 허용하지 않는다.
일반 Google SSO와 실험 Google auth_time 재인증은 이메일 소유 증명을 새로 만들거나 갱신하지 않는다.

별도 JDBC 세션 속성 `EmailOwnershipProof`(Serializable, serialVersionUID=1)에 다음을 저장한다.

| 필드 | 서버 검증 |
|---|---|
| userId / sessionId | 현재 principal 및 현재 저장된 원본 세션과 일치 |
| contactId / emailHash | 현재 VERIFIED 대표 연락처 UUID와 정규화 주소 SHA-256에 일치 |
| verifiedAt | 실제 OTP 소비 시각, 미래 거부, 현재부터 10분 이하(정확 경계 포함) |
| source | EMAIL_LOGIN / EMAIL_REAUTH / CONTACT_ENROLL 중 검증된 호출 경로 |

주소 해시는 OTP verifier가 아니다. OTP의 기존 HMAC·목적·오답 예산은 그대로이며, 이 속성은 클라이언트가
작성하는 token/DTO가 아니라 서버가 보호하는 JDBC 세션의 증명이다. toString에는 실제 값을 넣지 않는다.
신원 목록/상태 API에 proof·주소 해시·원문 주소·sub·토큰을 추가하지 않는다.

- EMAIL LOGIN: 실제 OTP 소비·프로비저닝·대표 연락처 소유권 확인 후 새 인증 세션 ID에 증명을 바인딩한다.
  로그인 주소와 기존 대표 주소가 다르면 로그인은 유지하지만 대표 이메일 소유 증명을 발급하지 않는다.
  DB 저장소 재조회에서 증명이 일치하지 않으면 성공을 반환하지 않는다. 임의 VerifiedIdentity는 근거가 아니다.
- EMAIL REAUTH / CONTACT_ENROLL: OTP 소비와 기존 JDBC 새 PK/ID 교체 및 증명 저장을 같은 트랜잭션으로 처리한다.
  연락처 등록도 해당 트랜잭션에 포함된다. 커밋 후/쿠키/응답 시 실제 저장 증명의 전체 일치도 확인한다.
- 일반 회전: 유효한 기존 이메일 증명만 새 sessionId로 재바인딩하고 verifiedAt/source/contact는 유지한다.
  내부 기본 인증 접점도 같은 사용자/원본 세션의 유효 증명만 계승한다. 새로운 Google 시각으로 10분을 연장하지 않는다.
- 누락·출처 불명·미래·만료·다른 연락처·사용자·브라우저·세션의 증명은 거절한다. 과거 세션에는
  timestamp 또는 보존된 과거 OTP만으로 증명을 소급 추가하지 않는다. 새 공통 이메일 재인증이 필요하다.

기존 SessionMetadata/Snapshot 직렬화 형식을 수정하지 않아 기존 JDBC 세션은 읽을 수 있다.
새 클래스가 없는 과거 버전으로의 배포 롤백은 이번에 수행하지 않았다. DB 트랜잭션 롤백과 앱 재시작 복원은 검증했다.

## HTTP 계약

GET `/api/v1/auth/identities`의 `{identities:[{id,provider}]}`를 유지한다.
모든 쓰기는 인증 세션과 같은 세션에서 GET `/api/v1/auth/csrf`로 받은 CSRF가 필수다.
주소/userId/returnUrl 등 알 수 없는 JSON 필드와 query 입력을 거절한다. 응답은 no-store다.

| API | JSON | 성공 |
|---|---|---|
| POST /api/v1/auth/identities/email/link | `{}` | 신규 201 `{status:"LINKED",identity:{id,provider:"email"}}` |
| 동일 소유자의 반복 link | `{}` | 200 `{status:"ALREADY_LINKED",identity:{id,provider:"email"}}` |
| POST /api/v1/auth/identities/email/unlink | `{identityId}` | 204 |

link의 주소는 서버의 VERIFIED 대표 이메일 하나로 고정한다. unlink의 identityId는 자기 EMAIL 신원 선택값이며
주소 지정/다른 계정 접근 수단이 아니다. 최근 증명은 대표 이메일 기준이므로 과거에 연결된 다른 자기 EMAIL
identity의 해제도 가능하다. unlink 반복은 404이고 재인증 증명 자체는 10분 동안 재사용 가능하다.
유효 증명이 있으면 새 메일을 발송하지 않으며, 이 쓰기 서비스는 sender/프로비저너를 호출하지 않는다.

| 상황 | HTTP / code |
|---|---|
| 익명·만료·폐기/변경된 원본 세션 | 401 AUTHENTICATION_REQUIRED |
| CSRF 누락/오류 | 403 CSRF_INVALID |
| 형식/필수 UUID/임의 주소 입력 오류 | 400 INVALID_REQUEST |
| VERIFIED 대표 이메일 없음 | 409 REAUTH_EMAIL_REQUIRED |
| 현재 대표 이메일 소유 증명 없음/오래됨/불일치 | 403 EMAIL_OWNERSHIP_PROOF_REQUIRED |
| 다른 UUID 소유 EMAIL identity | 409 IDENTITY_ALREADY_OWNED |
| 타인/없는/Google 대상 unlink 및 반복 unlink | 404 IDENTITY_NOT_FOUND |
| 마지막 사용 가능한 로그인 수단 | 409 LAST_LOGIN_IDENTITY |
| 새 쓰기 API의 DB 장애/저장 결과 확인 실패 | 503 IDENTITY_CHANGE_UNAVAILABLE |

인증·CSRF → 형식 → 대표 연락처/증명 → 대상 소유권/개수 순으로 검사한다.
증명이 없는 요청은 다른 계정의 소유권 상태를 조회하는 도구가 되지 않는다.
Google 수단은 GOOGLE_OIDC_ENABLED=true일 때만 해제 후 사용 가능한 대안으로 센다.
EMAIL도 EMAIL_LOGIN_ENABLED=true 및 OTP HMAC/sender의 로컬 ready가 필요하다. 기본값 true는 기존 LOGIN을 유지한다.
GOOGLE_REAUTH_ENABLED의 false 기본값은 바꾸지 않는다.

## 트랜잭션과 경합

1. 서버 principal·현재 세션 metadata를 캡처한다. READ COMMITTED에서 사용자 행 → 자기 identity 행들 → 원본 세션
   순으로 잠근다. 현재 VERIFIED 연락처와 DB의 typed proof를 다시 읽는다. 요청 메모리의 시각만 신뢰하지 않는다.
2. link는 기존 `auth_identities_credential_unique`와 ON CONFLICT DO NOTHING으로 전역 유일 소유권을 보장하고
   실제 owner를 재조회한다. 자기 소유면 반복 성공, 다른 소유면 충돌로 롤백한다. 타 계정 identity를 이동/갱신하지 않는다.
3. unlink는 소유 EMAIL 대상과 다른 정상 로그인 수단을 잠금 안에서 재검사하고 해당 행만 제거한다.
   사용자/운동/설정/대표 이메일/기존 세션 metadata는 변경하지 않는다.
4. 실제 저장/삭제 결과를 재조회하고 beforeCommit에서 원본 인증·이메일 증명의 10분 및 90일 기한을 다시 검사한다.
   만료·로그아웃/회전 우승·SQL 오류는 identity 변경 전체를 롤백한다. 보안 조건을 통과시키려고 재시도하지 않는다.

동일 사용자 link/link, link/unlink, unlink/unlink는 사용자 락으로 직렬화된다. 다른 사용자가 같은 주소를
연결해도 unique 제약으로 한 소유자만 남는다. 연락처 주소 중복은 여전히 허용한다.
일반 로그인 신원 조회도 사용자 락 획득 후 credential 소유권을 새로 읽어, unlink 대기 전의 오래된 JOIN 결과를
그대로 반환하지 않는다. 이미 검증·승인된 기존 인증 세션을 unlink가 전역 로그아웃시키는 계약은 아니다.

해제 이후 새 EMAIL OTP 로그인에는 이전 UUID를 반환하지 않는다. 주소에 신원이 없다면 기존 가입 로직이
새 UUID를 만들 수 있으며, 이는 과거 신원/데이터 이전이 아니다. 새 소유자가 명시적으로 차지한 주소를
이전 계정이 다시 link하면 충돌한다. 기존 Google 계정 데이터나 대표 연락처를 자동 병합하지 않는다.

## V7: 과거 V5 참조와 기존 데이터

V1~V6는 수정하지 않는다. V7은 `email_otp_challenges.retired_reauth_identity_id`와 직접 user FK 및
명시적인 과거 REAUTH 보존 분기를 추가한다. 마이그레이션 자체는 기존 행·직렬화 bytes·identity를 갱신/삭제하지 않는다.
새 proof 테이블이나 의존성은 없다.

과거 V5 REAUTH는 삭제할 EMAIL identity를 FK로 참조할 수 있다. 명시 unlink 트랜잭션에서 해당 사용자/identity의
legacy 참조 UUID만 retired_reauth_identity_id로 옮기고 live identity FK 열을 NULL로 바꾼 뒤 삭제한다.
원래 사용자 FK·주소·verifier·state·실패 횟수·소비 시각은 유지한다. 삭제 실패 시 이 변환도 롤백한다.
archived UUID는 과거 식별 정보이며 현재 로그인 권한이 아니다. V6부터 이 과거 바인딩은 이미 API에서 수용하지 않는다.
LOGIN/CONTACT_ENROLL/현재 contact REAUTH CHECK·FK와 전역 credential unique는 유지한다.

이 V7은 격리 PostgreSQL Testcontainers에만 적용했다. 실제 개발 DB·계정·세션은 열거나 변경하지 않았다.
실제 메일·Google 인증·Commit·Push·프론트 수정도 하지 않았다.

## 3B-3B 재사용 경계

현재 principal/대표 연락처/typed 이메일 증명과 사용자→identity→세션 잠금은 공통 IdentityChangeAuthorization으로
추출해 [Google LINK/UNLINK](google-identities.md)에서도 사용한다. Google 수단의 OIDC·후보 FK/해제 세대는 별도 처리한다.
EMAIL API로 Google identity를 생성/해제할 수 없으며, Google 표시 이메일 기반 자동 연결/병합도 없다.
