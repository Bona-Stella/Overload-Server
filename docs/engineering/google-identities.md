# Google 로그인 수단 연결·해제 — Task 007-1C-3B-3B

현재 Overload 계정의 VERIFIED 대표 이메일과 유효한 EmailOwnershipProof를 사용한다.
일반 Google SSO 시각/표시 이메일만으로 연결하지 않으며, Google LINK는 프로비저닝이나 seed를 호출하지 않는다.
EMAIL 수단과 Google 수단은 로그인 credential이고 대표 이메일은 별도 연락처다. 계정 병합/이전은 없다.

## API·오류 계약

| 요청 | 입력과 성공 |
|---|---|
| POST /oauth2/link/google | 인증+CSRF, 빈 form 또는 `_csrf`만 허용. 공식 OIDC authorization으로 302 |
| GET /login/oauth2/code/google-link | 같은 원본 인증 세션·일회 state. 성공 시 고정 `/api/v1/auth/identities`로 302 |
| GET /auth/google/link/failure | 일반화된 401 `{code:"GOOGLE_LINK_FAILED"}` |
| POST /api/v1/auth/identities/google/unlink | 인증+CSRF, JSON `{identityId}`, 성공 204 |
| GET /api/v1/auth/identities | 기존 자기 UUID/provider 목록에 연결·해제 결과 반영 |

LINK는 userId/identityId/이메일/sub/issuer/next/returnUrl 입력을 받지 않는다. 비어 있지 않은 JSON body도 거부한다.
서버가 현재 principal을 선택하고, 외부 Google 계정 선택 화면만 `prompt=select_account`로 요청한다.
이 값은 Google 비밀번호 재인증 강제를 의미하지 않는다. auth_time이나 Google 이메일 일치에 의존하지 않는다.
성공/실패 Location에 OAuth code/state/sub/이메일/토큰을 넣지 않는다. 응답은 no-store/no-referrer다.

| 상황 | HTTP / code |
|---|---|
| 익명·원본 세션 폐기/회전/90일 만료 | 401 AUTHENTICATION_REQUIRED |
| CSRF 오류 | 403 CSRF_INVALID |
| 대표 이메일 없음 | 409 REAUTH_EMAIL_REQUIRED |
| 누락·다른 세션/사용자/연락처·출처 불명·10분 초과 이메일 증명 | 403 EMAIL_OWNERSHIP_PROOF_REQUIRED |
| 다른 UUID 소유의 검증된 Google issuer/sub | 409 IDENTITY_ALREADY_OWNED |
| 해제로 인해 기존 LINK가 무효화됨 | 409 IDENTITY_CHANGE_SUPERSEDED |
| 자기 Google 신원이 아닌 unlink/반복 unlink | 404 IDENTITY_NOT_FOUND |
| 마지막 사용 가능한 로그인 수단 제거 | 409 LAST_LOGIN_IDENTITY |
| 잘못된/금지 입력 | 400 INVALID_REQUEST |
| Google LOGIN 비활성의 인증된 LINK 시작/콜백 | 503 GOOGLE_LINK_UNAVAILABLE |
| 실제 신원 쓰기/확인 실패 | 503 IDENTITY_CHANGE_UNAVAILABLE |
| OAuth 취소·잘못된 state/nonce/PKCE/서명/클레임·provider 장애 | 고정 failure 경로로 302, 원본 세션 보존 |

일부 프로토콜 저장 실패는 기존 공통 redirect filter의 일반화된 failure 경로로 처리된다.
LINK 중 동일 issuer/sub가 자기 계정 소유이면 정상 중복 성공이며 새 행/사용자/종목을 만들지 않는다.
다른 사용자 소유이면 주소가 같아도 충돌한다. 대표 이메일이나 EMAIL identity도 자동 생성/변경하지 않는다.

## 원본 계정·이메일 증명·OIDC 경계

`IdentityChangeAuthorization`을 EMAIL과 Google이 공유한다. 사용자 → 자기 identity → 원본 세션 순으로 잠그고
DB에 저장된 principal/metadata와 EmailOwnershipProof를 검사한다. 실제 OTP 소비 후 10분 이하,
현재 userId/sessionId/대표 연락처 UUID·주소 해시·출처가 모두 일치해야 한다. 커밋 직전에도 검사한다.
LOGIN/REAUTH/CONTACT_ENROLL로 생성한 증명만 수용하며, 일반 SSO/회전은 verifiedAt를 갱신하지 않는다.

GOOGLE_LINK Pending은 원본 Snapshot, **그 정확한 이메일 proof 값**, 요청 시작 시각, 해제 세대,
표준 authorization request(state/nonce/PKCE)를 JDBC 원본 세션에 저장한다. 수명은 5분 미만이다.
다른 브라우저/사용자/세션·바뀐 proof·만료·재사용 state는 허용하지 않는다.
이전 5필드 CONTACT/REAUTH Pending은 추가 필드가 null/0으로 복원되며 LINK 증명으로 취급하지 않는다.

LOGIN, CONTACT, 조건부 REAUTH, LINK는 registrationId·callback·pending 속성·purpose를 구분한다.
동일 필터 클래스의 중복 실행 방지 키도 목적별로 분리되어 있다. 인증 상태의 일반 LOGIN은 기존 409 LOGOUT_REQUIRED다.
GoogleBoundOidcSecurity의 공식 OIDC provider/decoder를 사용한다. 표준 code, PKCE S256, state, nonce,
RSA/JWKS·issuer·audience/azp·exp/iat·sub 검증을 유지한다. LINK는 email/email_verified를 소유권 키로 사용하지 않는다.

state는 원자 소비·커밋한 뒤 provider token/JWKS 네트워크 교환을 실행한다. 이 네트워크 호출 중 사용자/세션 행 락을
유지하지 않는다. 이후 별도 연결 트랜잭션에서 원본/정확한 proof/세대/요청 수명을 다시 검사하고,
기존 전역 credential UNIQUE와 ON CONFLICT로 소유권을 판정한다. 커밋 직전 proof·세대·요청 수명을 재검사한다.
공식 OAuth 필터는 임시 OidcUser를 장기 Context에 저장하지 않고 authorized client도 폐기한다.
핸들러는 기존 자체 SecurityContext를 복원한다. OAuth token을 최종 세션에 추가하지 않는다.

LINK/UNLINK는 원래 userId/loginStartedAt/absoluteExpiresAt/reauthenticatedAt/EmailOwnershipProof/CSRF를 바꾸지 않는다.
새 세션을 발급하는 인증 절차가 아니다. 계정 수단 변경만 커밋하며 proof의 검증 시각과 90일 기한을 연장하지 않는다.
커밋 전 실패는 전체 신원 변경을 롤백한다. state/code 소비는 별도 선행 트랜잭션이므로 되살리지 않는다.
응답이 불확실하면 GET identities로 실제 결과를 확인한다. 외부 인증을 자동 반복하지 않는다.

## 해제·V8·경합

V1~V7은 수정하지 않는다. 추가형 V8은 다음만 수행하며 기존 행을 쓰거나 삭제하지 않는다.

- google_identity_change_versions: 사용자 FK/PK와 비음수 해제 세대. 필요할 때만 생성한다.
- 후보에 retired_google_identity_id/invalidated_at을 추가하고 명시적인 live 또는 retired CHECK를 둔다.
  동일 소유자 composite FK는 유지하되 ON DELETE CASCADE를 NO ACTION으로 바꾼다.
- 기존 retired_reauth_identity_id를 CONTACT_ENROLL 이력에도 사용할 수 있도록 목적 CHECK를 확장한다.
  LOGIN/현재 REAUTH·사용자 FK·전역 신원 UNIQUE는 유지한다.

unlink는 잠금 안에서 자기 Google 대상과 다른 사용 가능한 로그인 수단을 재검사한다.
해당 Google의 후보를 만료/퇴역시키고 CONTACT_ENROLL의 live FK를 과거 UUID로 보존한 후 해당 신원만 삭제한다.
후보·OTP의 원래 사용자·주소·subject·state·verifier·실패 횟수·소비 시각은 삭제하지 않는다.
타임스탬프/활성 참조만 명시적으로 무효화한다. 대표 연락처는 보존한다.

다른 기기의 해당 CONTACT/REAUTH Pending과 모든 미완료 LINK Pending은 선택적으로 무효화한다.
이미 state를 소비하고 token 교환 중인 LINK도 해제 세대가 달라지면 연결하지 못한다.
세대 저장 결과를 재조회하므로 쓰기가 조용히 누락돼도 성공하지 않는다. 삭제/참조 변환/Pending/세대는 같은 트랜잭션이다.
실패 시 모두 롤백한다. 늦은 CONTACT 콜백도 현재 identity 부재로 후보를 생성하지 못한다.

동일 Google sub의 다른 계정 동시 연결은 기존 UNIQUE로 한 소유자만 성공한다.
같은 계정의 연결·해제는 사용자 락으로 직렬화하고, 해제 이전 LINK의 늦은 재연결은 세대 검사로 차단한다.
해제 이후 동일 Google로 새 로그인하면 기존 계정에 접근할 수 없다. 기존 가입 정책이 새 UUID를 만들 수 있으며
이전 데이터/신원을 옮기는 동작이 아니다. 이미 승인된 다른 기기 세션을 전역 로그아웃하지 않는 정책은 EMAIL과 같다.
따라서 해제는 기존 인증 세션의 전역 철회 수단이 아니다. 해당 세션은 기존 로그아웃/90일 정책을 따른다.

## 로그인 기능 설정

GOOGLE_OIDC_ENABLED는 일반 Google LOGIN 및 LINK/CONTACT를 제어한다. GOOGLE_REAUTH_ENABLED=false 기본값은 유지한다.
EMAIL_LOGIN_ENABLED는 새 독립 설정이며 기본 true로 기존 LOGIN을 유지한다. false면 익명 LOGIN OTP request/verify가
503 EMAIL_LOGIN_UNAVAILABLE이며, 공통 REAUTH/CONTACT_ENROLL 엔진은 별개다.
현재 `.env`는 변경하지 않았고 비밀값 없는 `.env.example`에만 예시를 추가했다.

마지막 수단 판정은 설정상 Google이 활성인지, EMAIL LOGIN이 활성이고 HMAC/sender가 로컬 설정상 ready인지 확인한다.
테스트 fake sender도 같은 ready 계약을 사용한다. 외부 provider 장애까지 예측하거나 네트워크 건강 검사를 하지는 않는다.
비활성/설정 불가 EMAIL을 유일한 대안으로 남기고 Google을 제거하지 않는다. Google이 비활성이어도 EMAIL이 사용 가능하면
기존 Google 신원을 해제할 수 있다. 비활성 credential을 자동 삭제/이전하지 않는다.

## LIVE 준비·범위

실제 LIVE 전에 기존 LOGIN/CONTACT URI를 유지하고 다음 URI를 Google Console에 별도 등록해야 한다.

```text
http://localhost:8080/login/oauth2/code/google-link
```

이번에는 Console/실제 Google/Resend/개발 DB에 접근·마이그레이션하지 않았다. V8은 Testcontainers에서만 적용했다.
실제 계정 선택/동의·다중 신원·브라우저 동일 세션·메일 proof의 시간과 해제 동작은 후속 승인 LIVE 검증이다.
실제 결과와 자동화를 혼동하지 않는다. [검증 보고서](../tasks/task-007-1c-3b-3b-validation-report.md)를 참고한다.
