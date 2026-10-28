# Task 007-1C-3B-2 — Google OIDC 최근 재인증 검증 보고서

- 일자: 2026-10-28 (Asia/Seoul)
- 판정: 서버 구현 및 mock OIDC/실제 PostgreSQL 자동화 검증. 실제 Google 재인증은 미검증이다.
- 시작: `main`, HEAD `5415d9fb5cbe0d18c1b47fe2bb3712c0ff13b7c2`, clean.
- 3B-1 커밋의 33개 파일 포함을 확인했다. 로컬 origin/main 추적값보다 1커밋 앞섰으며 Push하지 않았다.
- 적용 근거: 사용자 요청/첨부 3B-2 지시서, AGENTS.md, backend 규칙, Task 006 최종 v0.3.1,
  google-oidc/security-session/email-reauthentication/account-provisioning/email-otp 및 3B-1 검증 보고서.

## 구현·보존 결과

| 구분 | 결과 |
|---|---|
| 시작 | POST `/oauth2/reauth/google`, 기존 인증+CSRF, form-urlencoded identityId |
| 전용 콜백 | GET `/login/oauth2/code/google-reauth`, 동일 원본 인증 세션 필수 |
| 성공 | 새 SESSION 쿠키, 고정 `/api/v1/auth/session`으로 302, 다음 독립 HTTP 요청 200 |
| 일반 실패 | 고정 `/auth/google/reauth/failure` 302 → 401 GOOGLE_REAUTH_FAILED |
| 최종 응답 직전 실패 | 성공 302 취소, 401/503, 쿠키 제거·새 세션 폐기 |
| 익명/CSRF/신원 선택 | 각각 401/403/일반화된 404, 사용자 입력의 userId/sub/issuer 불신 |
| 계정/정책 | 원래 userId/loginStartedAt/absoluteExpiresAt·운동/휴식/세대/identity 보존 |
| 마이그레이션/의존성 | 신규 없음. V1~V5 및 Task 006 변경 없음 |

일반 LOGIN과 별도 공식 OAuth2LoginAuthenticationFilter/OidcAuthorizationCodeAuthenticationProvider를 구성했다.
일반 로그인 프로비저닝·익명 세션 삭제 저장소·일반 로그인 실패 핸들러를 재인증에 사용하지 않는다.
서명/JWKS·issuer/aud/azp·exp/iat·state/nonce·PKCE는 표준 검증을 유지한다.
검증된 Google issuer/sub가 현재 principal 소유의 **선택한 기존 Google identity**와 일치해야 한다.
주소 일치/다른 Google 계정 선택으로 계정이 생성·교체·연결·병합되지 않는다.

CSRF 보호 POST는 원하지 않는 재인증 시작을 막고 브라우저 최상위 폼 제출/302/GET 콜백과 호환된다.
기존 일반 로그인 GET·콜백은 유지하며 인증된 브라우저에서는 계속 LOGOUT_REQUIRED다.
정확한 API·오류·프로토콜·실사용 준비는 [Google 재인증 문서](../engineering/google-reauthentication.md)를 따른다.

## auth_time 판정

- REAUTH 전용 decoder에서 형 변환 전에 비음수 정수형 JSON 숫자인지 검사한다. 누락/문자열/소수는 거부한다.
- 검증된 auth_time이 요청 시작(초 단위 내림) 이전이면 거부한다. 허용하는 과거 정밀도 차이는 1초 미만이며 과거 60초 허용은 없다.
- 미래 오차는 현재 서버 시각 +60초까지, 요청 TTL은 5분 미만이다. 미래 초과·시간 역행·5분 정확 경계는 거부한다.
- `reauthenticatedAt = max(이전 재인증 시각, min(auth_time, 서버 현재 시각))`이며 콜백의 현재 시각으로 10분을 새로 시작하지 않는다.
- 클레임 요청은 essential auth_time과 표준 max_age=0이다. Google이 max_age를 지원/강제한다고 가정하지 않는다.
- 최근 iat·새 토큰·계정 선택/동의·email_verified는 대체 증거가 아니다. 특정 비밀번호/2FA 재입력을 강제했다고 주장하지 않는다.

## JDBC 상태·세션·실패 경계

1. 현재 자체 principal/metadata/session ID/identity/start time과 공식 state/nonce/PKCE 요청을
   별도 Spring Session Pending 속성에 저장한다. 새 테이블이나 장기 provider-token 저장은 없다.
2. identity 공유 락 → 원본 session 행 락으로 snapshot/state를 재검사하고 Pending 속성만 원자 소비한다.
   삭제 1건 및 실제 제거 재조회를 확인하고 커밋한 뒤 네트워크 토큰 교환을 시작한다. 원본 인증 세션 행은 보존한다.
3. provider 검증 후 별도 트랜잭션에서 identity issuer/sub와 원본 세션을 다시 검사한다.
   3B-1 공용 JDBC 교체 경로로 새 primary key/session ID를 만들고 원본 행·CSRF를 폐기한다.
4. 공용 경로에 검증된 인증 시각 overload와 최종 증명 검사를 추가했다.
   이메일 REAUTH의 기존 기본 호출은 그대로이며 40개 이메일 재인증 회귀가 통과했다.
5. 실제 공식 SessionRepository 재조회, 쿠키, HTTP 응답 커밋 전 검사를 한다. 재조회 전후에도 시간 증거를 검사한다.
   원래 90일이 유효하다는 이유로 만료된 Google 요청/증명을 성공 처리하지 않는다.

임시 OIDC Context 저장은 Null repository, 자동 세션 전략은 no-op, authorized client 저장은 폐기형이다.
성공/실패 핸들러는 원래 자체 Context를 복원하며 중간 OidcUser/token이 DB에 쓰이지 않음을 SQL 감사 트리거로 확인했다.
JDBC 원본 Context/metadata/기존 CSRF 바이트는 정상적인 인증 거절 전후 동일하다.

- 취소/부정확한 클레임/다른 신원/잘못된 state는 재인증만 거부하며 유효한 원본 인증을 보존한다.
- state 소비 커밋 후 provider/회전 실패로 state/code를 되살리지 않는다. 새 시작 POST부터 다시 수행한다.
- state 삭제가 실제 커밋되지 않았고 provider 교환도 시작하지 않았다면 장애 복구 후 아직 Pending인 흐름의 정상 소비가 가능하다.
- 최종 교체 SQL 실패는 교체를 롤백하되 이미 소비한 state는 복원하지 않는다.
- 교체 커밋 후 저장소/쿠키/최종 응답 검증 실패는 새 세션을 폐기한다. 이전 ID도 이미 폐기되어 일반 LOGIN이 필요하다.
- 원본 로그아웃/회전/사용자 변경·이메일/Google 재인증 경합은 snapshot 일치와 행 락으로 순서를 정한다.
  예전 ID는 새 primary key를 되살리거나 덮어쓰지 못한다.

## 검증 명령·결과

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/temurin-25.0.4.1'
.\gradlew.bat --no-daemon compileJava compileTestJava
.\gradlew.bat --no-daemon integrationTest --tests '*GoogleReauthenticationIntegrationTests'
.\gradlew.bat --no-daemon test integrationTest build
git diff --check
```

Java 25 / Boot 4.1.2 / Security 7.1.2 / Session JDBC 4.1.1 / Gradle 9.8.2를 유지했다.
실제 `postgres:18.6-alpine` Testcontainers와 루프백 OIDC/JWKS/RSA 서버이며 H2/skip 대체가 아니다.
Flyway 기동 시 두 연결을 허용한 뒤 재인증 프로토콜 전체를 실제 풀 최대 크기 1로 실행했다. 재시작 복원도 포함한다.

최종 전체 실행은 **BUILD SUCCESSFUL (2m 19s)**였으며 최종 XML 집계는 아래와 같다.

| Gradle task | 테스트 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| test | 38 | 0 | 0 | 0 |
| integrationTest | 196 | 0 | 0 | 0 |
| **합계** | **234** | **0** | **0** | **0** |

| PostgreSQL suite | 테스트 |
|---|---:|
| AccountProvisioningIntegrationTests | 11 |
| EmailOtpIntegrationTests | 27 |
| EmailReauthenticationIntegrationTests | 40 |
| GoogleOidcIntegrationTests | 38 |
| GoogleReauthenticationIntegrationTests | 63 |
| PostgresMigrationTests | 7 |
| SecuritySessionIntegrationTests | 10 |

기존 168개를 삭제/skip/완화하지 않고 신규 66개(시간 단위 3, PostgreSQL 재인증 63)를 추가했다.
정상·거절·장애 시나리오 모두 최종 통과했으며 빌드와 diff 검사도 성공했다.

검증 범위:

- Google-only 기존 계정·기존 UUID/seed/데이터·원래 90일/metadata 보존, 실제 JDBC primary key/ID 교체와 독립 HTTP 인증 복원.
- 새 CSRF 성공/옛 CSRF 거부, 최근 10분 정확 경계/초과 및 원래 90일 정확 만료.
- 신원 소유권/EMAIL-only·타인 UUID·다른 sub(동일 이메일 포함), 익명·CSRF·잘못된 HTTP 메서드.
- auth_time 누락/오래됨/문자열/소수/음수/미래/시작 전 인증, 최근 iat 대체 금지, 미래 60초 및 시작/TTL 경계·시계 역행.
- 표준 서명/알고리즘/issuer/aud/azp/exp/iat/nonce 검증, provider 500/timeout/JWKS 실패·취소.
- state/목적 혼용·타 브라우저·동일 사용자 다른 세션·리플레이·병렬 callback·멀티탭·앱 재시작·작은 JDBC 풀.
- token 교환 중 원본 로그아웃/ID 회전/다른 principal/metadata/절대 만료.
- state/세션 속성 쓰기 실패, 삭제가 무시된 소비 실패, 커밋 후 재조회 실패 및 각 쓰기/읽기/쿠키/응답 시점의 세션·인증 증거 만료.
- SQL 감사로 중간 OAuth principal 미기록, 장기 JWT/토큰 미저장, 일반 실패 시 기존 Context/metadata/CSRF 바이트 보존.
- 이메일 REAUTH/Google 재인증의 교체 충돌·CSRF 및 기존 LOGIN/identities/Health/Flyway 전체 회귀.

검증 중 발견·해결:

1. 첫 시도는 Flyway 초기 스키마 적용에서 풀 크기 1의 연결 대기로 실패했다. 기동용 두 연결과 런타임 한 연결을 구분했다.
2. 첫 재인증 suite의 속성 보존 20개 기대값은 CSRF 생성 전을 기준으로 잡아 실패했다.
   CSRF를 먼저 생성한 원본 바이트를 기준으로 고쳤고 보존 검사 자체는 유지했다.
3. 공용 최종 응답 검사를 204뿐 아니라 Google의 302에도 적용하고, 저장소 재조회 전후의 인증 증거 만료도 검사했다.
4. 일회 소비는 DELETE 호출만으로 인정하지 않고 영향 행 수와 제거 재조회까지 확인하도록 보강했다.

현재 미해결 자동화 실패는 없다. JVM CDS 경고는 있었으며 테스트/빌드 실패 사유는 아니다.

## 미확인 LIVE 제한 — 완료로 주장하지 않음

Google 공식 문서/2026-06-16 발표는 auth_time과 verified-app 제공을 설명한다. **현재 개발용 Testing
클라이언트의 auth_time 제공 여부와 max_age 동작은 미확인**이다. 누락/오래된 값은 예외 없이 거부한다.

별도 승인 LIVE 전에는 다음이 남는다:

- Google Console에 `http://localhost:8080/login/oauth2/code/google-reauth` 추가 등록 및 기존 LOGIN 콜백 유지.
- 검증 앱/Testing 제한 확인, 사용자의 직접 브라우저 인증, 서명 검증된 실제 auth_time과 시작/현재 시각 관계 확인.
- 실제 성공·거부·쿠키/세션 복원 확인. 자동화 통과를 실제 Google 재인증 성공으로 표시하지 않는다.

Console/`.env` 수정·실제 Google OAuth/JWKS 요청·Resend 발송은 수행하지 않았다.
신원 연결·해제(3B-3), 병합/이전, 프론트/운영/iPhone/LAN HTTPS 검증도 이번 범위 밖이다.

## 보안·정리·Git

- `.env` 및 프론트 작업 트리/HEAD 불변 비교. 실제 값·길이·fingerprint를 출력하거나 보고서에 싣지 않았다.
- Google/메일 기본 비활성 설정과 기존 비밀키/Resend 설정 유지. 변경 파일·테스트 XML의 실제 환경 비밀값 검사에서 검출 없음.
- V1~V5/Task 006/기존 3B-1·LIVE 보고서 diff 없음. 개발 Compose DB는 시작·접속·마이그레이션하지 않았다.
  기존 두 LIVE 계정 및 개발 볼륨을 수정하지 않았으며 직접 DB 재확인을 했다는 뜻은 아니다.
- 다른 프로젝트 Docker 리소스를 조작하지 않았다. 이번 3B-2 전용 라벨의 남은 테스트 컨테이너 0개,
  남은 Gradle/테스트·Overload Java 프로세스 0개를 읽기 전용으로 확인했다. 테스트 애플리케이션도 정상 종료했다.
- 생산 boot jar의 테스트 fixture/.env/TLS 키 저장소 항목 0건을 확인했다.
- 최종 `main` HEAD는 시작과 동일하며 변경 20개 모두 unstaged다. Git staging/Commit/Push 없음.

## 변경 파일 전체 (20개)

제품 Java 12개:

- `src/main/java/com/overload/overload_server/account/AccountIdentityRepository.java`
- `src/main/java/com/overload/overload_server/account/AccountIdentityService.java`
- `src/main/java/com/overload/overload_server/auth/google/GoogleAuthenticationTime.java` (신규)
- `src/main/java/com/overload/overload_server/auth/google/GoogleReauthenticationRequests.java` (신규)
- `src/main/java/com/overload/overload_server/auth/google/GoogleReauthenticationSecurity.java` (신규)
- `src/main/java/com/overload/overload_server/auth/google/GoogleLoginController.java`
- `src/main/java/com/overload/overload_server/auth/google/GoogleOidcSecurity.java`
- `src/main/java/com/overload/overload_server/security/AbsoluteExpiryCookieSerializer.java`
- `src/main/java/com/overload/overload_server/security/ReauthenticationResponseFilter.java`
- `src/main/java/com/overload/overload_server/security/ReauthenticationSessionRepository.java`
- `src/main/java/com/overload/overload_server/security/SecurityConfiguration.java`
- `src/main/java/com/overload/overload_server/security/SessionReauthentication.java`

테스트 4개:

- `src/test/java/com/overload/overload_server/auth/google/GoogleAuthenticationTimeTests.java` (신규)
- `src/test/java/com/overload/overload_server/auth/google/GoogleReauthenticationIntegrationTests.java` (신규)
- `src/test/java/com/overload/testfixture/GoogleOidcFixtures.java`
- `src/test/java/com/overload/testfixture/GoogleReauthenticationFixtures.java` (신규)

문서 4개:

- `docs/engineering/google-oidc.md`
- `docs/engineering/security-session.md`
- `docs/engineering/google-reauthentication.md` (신규)
- `docs/tasks/task-007-1c-3b-2-validation-report.md` (신규)

기존 프론트 9~10회 휴식 구간 정렬 등의 전달 사항은 그대로 남는다. 이번 결과는 사용자 검토 대상이다.
