# 이메일 OTP 로그인·Resend (Task 007-1C-2)

이메일 OTP 발급·검증·계정 프로비저닝·JDBC 인증 세션 경로를 구현했다.
실메일은 기본 비활성화다. 1C-2 자동화는 fake sender와 로컬 Resend HTTP 스텁을 사용했고,
후속 1C-2-LIVE에서 별도 승인한 실발송·수신·PostgreSQL 로그인을 검증했다.
Google OIDC 서버는 [1C-3A](google-oidc.md)로 추가했다. 인증된 사용자용 이메일 재인증은
[1C-3B-1 별도 계약](email-reauthentication.md)을 따른다. 아래의 소비·프로비저닝 경계는 LOGIN 경로의 계약이다.
계정 연결/변경·Google 재인증·실제 민감 업무 쓰기는 후속 범위다.

## HTTP 계약

모든 POST는 기존 GET `/api/v1/auth/csrf`에서 얻은 `X-CSRF-TOKEN`과 같은 세션 쿠키가 필요하다.
두 이메일 경로만 익명 POST 접근을 허용하며 CSRF 제외는 추가하지 않았다.
이미 인증된 세션에서는 이메일과 관계없이 **409 LOGOUT_REQUIRED**다. 사용자를 암묵적으로 교체하거나
이 경로를 계정 연결·재인증으로 사용하지 않는다.

| 경로 | 요청 JSON | 성공 |
|---|---|---|
| POST /api/v1/auth/email-otp/request | `{email}` | 202 `{status:"ACCEPTED", challengeId}` |
| POST /api/v1/auth/email-otp/verify | `{email, challengeId, code}` | 204, 새 인증 세션 쿠키 |

challengeId는 발급 UUID이며 userId가 아니다. code는 선행 0을 유지하는 **6자리 문자열**이다.
응답에는 OTP/이메일/계정 존재 여부를 넣지 않는다. 이메일은 도메인만 소문자화하고 로컬 부분의
대소문자·점·플러스 태그를 보존한다. 공백 제거·Gmail 별칭 정리·Google 자동 병합은 하지 않는다.

오류는 no-store JSON `{code}`다:

| 상태 | code / 의미 |
|---|---|
| 400 | INVALID_REQUEST: JSON/입력 형식 오류 |
| 401 | OTP_INVALID: 오답·없는/만료/이전/소비된 발급·다른 브라우저, 같은 일반 응답 |
| 401 | AUTHENTICATION_REQUIRED: 기존 필터의 익명 CSRF 실패·보호 경로 접근 |
| 403 | CSRF_INVALID: 인증된 요청의 기존 CSRF 실패 |
| 409 | LOGOUT_REQUIRED: 기존 인증 세션을 먼저 명시적으로 로그아웃해야 함 |
| 429 | OTP_RATE_LIMITED, Retry-After 초 제공 |
| 503 | OTP_UNAVAILABLE: 설정/발송/DB/계정/세션 저장 문제, 성공으로 간주하지 않음 |

202는 provider 요청 수락과 DB 활성화가 확인됐다는 뜻이며 최종 수신/전달 완료가 아니다.
없는 challenge와 오답에도 HMAC 계산·상수시간 비교를 수행한다. SQL/네트워크까지 완전히 같은
응답 시간을 보장하지는 않는다. OTP 확인 전에는 계정 존재를 조회하지 않는다.

## 정책·기술 제한값

SecureRandom의 `nextInt(1_000_000)`으로 균등 생성한다. 유효 **5분**, 재발송 **최소 60초**,
오답 **최대 5회**다. 서버 시각은 밀리초로 통일해 PostgreSQL 반올림의 경계 차이를 제거했다.
정확한 만료부터 거부하고 정확히 60초부터 재전송이 가능하다(다른 제한도 통과해야 함).
새 발급은 이전 PENDING/ACTIVE를 폐기하지만 실패 예산을 초기화하지 않는다.
발급별 오답도 별도로 5회 제한해 관찰 기간 경계를 넘겨 같은 코드를 더 추측할 수 없다.
목적은 서버·DB에서 LOGIN으로 고정한다.

다음은 소규모 MVP용으로 선택한 **기술 기본값**이며 실제 사용량 검증 후 별도로 조정할 수 있다:

| 구분 | 제한 / 관찰 기간 |
|---|---|
| 이메일+LOGIN 발급 요청 | 5회 / 1시간 |
| IP+LOGIN 발급 요청 | 20회 / 1시간 |
| 이메일+LOGIN 검증 요청 | 20회 / 15분 |
| IP+LOGIN 검증 요청 | 100회 / 15분 |
| 이메일+LOGIN 오답 누적 | 5회 / 30분, 재전송 후에도 유지 |
| 개별 발급 오답 | 최대 5회, 관찰 기간이 바뀌어도 복구되지 않음 |

고정 창이며 제한/쿨다운으로 거절된 요청도 트래픽 시도로 센다. PostgreSQL에 저장하므로 재시작으로
초기화되지 않는다. 출발 IP는 Servlet getRemoteAddr이며 `server.forward-headers-strategy=none`으로
X-Forwarded-For를 신뢰하지 않는다. 개발 Vite proxy 뒤 기기들은 IP 제한을 공유할 수 있다.
실제 프록시 신뢰·원본 IP 처리는 별도 배포 검토가 필요하며 전체 origin CORS 허용은 없다.

## V4·상태·잠금

V1~V3는 불변이다. V4는 네 테이블만 추가한다:

- email_otp_limits: 이메일 식별 해시+목적 PK, 현재 발급 포인터·발급 시각·오답·실패 창.
- email_otp_challenges: 발급 UUID·목적·수신 주소·브라우저 nonce 해시·32바이트 HMAC verifier·오답·상태·시각.
- email_otp_rate_limits: 용도별 식별 해시+LOGIN PK, 창 시작·요청 횟수.
- email_otp_login_claims: 브라우저 nonce 해시 PK·발급 UNIQUE/FK, 같은 익명 브라우저의 병렬 소비 제한.

현재 포인터는 같은 이메일/목적 발급만 참조하는 복합 FK다. 상태·오답·시각·목적 CHECK와 만료 인덱스가 있다.
IP/이메일 버킷 SHA-256은 식별용이며 OTP verifier의 대체가 아니다.

발급은 짧은 DB 트랜잭션에서 IP→이메일 버킷→이메일 목적 행을 같은 순서로 잠그고, 이전 코드 폐기·
PENDING/HMAC/쿨다운을 커밋한다. **열린 쓰기 트랜잭션 없이** 메일을 보낸 뒤 다시 이메일 행을 잠근다.
명확한 수락 이후에도 현재 발급·만료 전인 경우만 ACTIVE다. 늦은 수락이 이전 코드를 되살리지 않는다.
실패/timeout/불확실한 수락은 FAILED, 프로세스 중단/DB 활성화 실패는 PENDING으로 남을 수 있으며 모두 검증 불가다.
60초 이후 새 발급으로 복구하고 기존 intent는 자동 재전송하지 않는다.

검증은 같은 이메일 잠금에서 ACTIVE·만료·브라우저·예산·HMAC을 확인하고 브라우저 claim과 CONSUMED를
함께 커밋한다. 소비 verifier는 32바이트 0으로 덮어쓴다. 병렬 같은 코드 검증은 한 건만 통과한다.
실패 횟수와 차단도 커밋하여 예외 롤백으로 지워지지 않는다.

검증 소비가 먼저 커밋됐다면 그 요청은 유효한 증명을 확보한 것이다. 재전송이 먼저 커밋되면 이전 코드가 거부된다.
별도 새 코드/브라우저의 정상 로그인까지 다중 기기 금지 정책으로 바꾸지는 않는다.

## 계정·세션 경계와 실패 복구

소비 커밋 후 **외부 트랜잭션 없이** 기존 AccountProvisioningService(REQUIRES_NEW)를 호출한다.
전체 소비·계정·세션이 하나의 트랜잭션이라는 주장은 하지 않는다. 서비스가 외부 트랜잭션 안에서 호출되면 거부한다.
계정 성공 뒤 익명 JDBC 세션을 무효화하고 새 primary key의 세션에 기존 세션 서비스를 적용한다.
늦은 익명 요청이 회전된 인증 ID를 덮어쓰는 경합을 피한다.

`spring.session.jdbc.flush-mode=immediate`와 **저장소 재조회**로 실제 SecurityContext·계정·메타데이터를
확인한 뒤에만 204를 반환한다. 채택 버전은 일부 속성 무결성 오류를 동시 삭제로 간주해 생략할 수 있으므로
단순 save/메모리 속성만으로 성공을 판단하지 않는다. 즉시 저장은 세션 DB 쓰기가 늘어나는 선택이다.
1C-3A에서 이 저장·재조회 경로를 VerifiedSessionLogin으로 공용화하고 저장 메타데이터 전체 일치를 확인한다.

- 계정 실패: 기존 계정/seed 원자적 트랜잭션은 롤백하고 브라우저 claim을 해제한다.
  **소비 코드를 되살리지 않는다.** 쿨다운 후 새 OTP로 재시도한다.
- 세션 저장 실패: 503과 Context 제거·세션 무효화. 계정은 완전하게 생성됐을 수 있다.
  새 CSRF와 새 OTP로 재시도하면 기존 계정을 재사용한다.
- 소비 직후 프로세스 중단: 계정 생성은 완료/미완료일 수 있다. 익명 세션에 claim이 남아 있다면
  CSRF를 얻어 기존 POST logout으로 익명 세션을 폐기한 뒤 새 OTP로 복구한다.
- 응답 유실: 같은 코드로 중복 로그인하지 않는다. 인증 여부가 불확실하면 새 CSRF/명시 로그아웃으로
  브라우저를 정리하고 새 OTP를 사용한다. 기존 UUID·generation·개인 편집·seed는 유지한다.

새 OTP 상태·수신 주소·버킷·소비 이력의 보존 기간/운영 정리 작업은 배포 전 별도 계약이 필요하다.
이번에는 기존 DB 삭제나 자동 파기 정책을 도입하지 않았다.

## Resend·HMAC 설정과 실발송 승인

`RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `OTP_HMAC_KEY`가 필요하고 live 활성화는 `OTP_EMAIL_ENABLED=true`다.
기본 false이며 누락 시 성공을 가장하지 않는다. HMAC 키는 다른 자격정보와 공유하지 않는
**32바이트 이상 CSPRNG의 Base64**다. Git·채팅·로그에 출력하지 않는다.
키 변경 후 재시작하면 기존 발급 코드가 무효가 되지만 rate 식별자는 키와 독립적이므로 예산은 유지된다.
local은 기존 .env import를 사용한다. from은 따옴표·표시명 없는 주소, Base64의 +/=/는 그대로 입력한다.

실발송에는 Resend 계정·인증된 발신 도메인·발송 전용 최소 권한 키·승인된 수신자가 필요하다.
**실제 발송과 수신 검증은 별도 사용자 승인 후 진행한다.** 현재 이 외부 준비가 완료됐다고 주장하지 않는다.

새 SDK 대신 기존 Java 25 HttpClient/Jackson 3으로 HTTPS POST `https://api.resend.com/emails`를 사용한다.
connect 3초/request 5초, redirect·자동 재시도 없음. `Idempotency-Key: email-otp/<issuanceId>`를 보내며
같은 intent의 payload는 유지한다. Resend 키 보존은 24시간이다. 재시작 후 평문을 복구하는 큐는 없다.
401/403/422/429/5xx/timeout/불명확한 2xx는 OTP_UNAVAILABLE이며 provider 본문/인증 헤더를 노출하지 않는다.
2xx와 message id가 있어야 수락으로 인정하지만 최종 전달과는 다르다.

## 프론트 최소 연동 (이번에는 미수정)

동일 origin /api에 `credentials: 'include'`와 CSRF 헤더를 보낸다. request의 challengeId와 코드 문자열을
연결하고 재전송 버튼에 최소 60초 쿨다운을 적용한다. 429는 Retry-After 우선, 401은 code에 따라 구분,
403은 토큰 갱신, 409는 명시 로그아웃, 503은 새 코드 복구로 처리한다.
로그인/로그아웃 뒤 새 CSRF를 얻고 OTP를 영속 저장소·URL·콘솔에 기록하지 않는다.
`/__otp_test__/*`와 fake sender는 테스트 전용이며 프론트가 호출할 제품 API가 아니다.

1C-2 자동화에는 실제 iPhone/PWA·실메일·Google·운영 검증이 포함되지 않는다.
이메일 실수신/로그인은 별도 LIVE에서 확인했으며, Google 실브라우저·iPhone/PWA·운영 검증은 아직 하지 않았다.
[1C-2 검증 보고서](../tasks/task-007-1c-2-validation-report.md)와 [Google 안내](google-oidc.md)를 구분해 따른다.

- [Resend API](https://resend.com/docs/api-reference/emails/send-email)
- [Resend 멱등 키](https://resend.com/docs/dashboard/emails/idempotency-keys)
- [OWASP 인증·열거 방지·throttling](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [Spring Security 명시적 저장](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Session JDBC 4.1.1 속성 저장 오류 처리](https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-jdbc/src/main/java/org/springframework/session/jdbc/JdbcIndexedSessionRepository.java)
