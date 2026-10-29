# Task 008-0D — 공통 HTTP API 계약 v0.1 및 Request ID 기반

- 작성일: 2026-10-30 (Asia/Seoul).
- 공통 응답 방향: 사용자 승인됨. Request ID만 최소 구현하며 운동 API·운동 Advice·오류 DTO·프론트엔드 변경은 후속 범위다.
- 시작 상태: server `main/e29bfd9`, pwa `main/1c84d23`, 양쪽 clean.
- 기준: 양쪽 AGENTS.md 및 Engineering 문서, [Task 006 최종 설계](task-006-integrated-design-v0.3.1.md), [008 JPA 설계](task-008-0-jpa-persistence-design.md), [008 도메인·API 계약](task-008-0-domain-and-api-contract-v0.1.md).
- 이 문서는 기존 008-0A/0B의 정책·구현 게이트를 변경하지 않는다. R 읽기 계약과 D 쓰기 기술 후보를 현재 구현으로 간주하지 않는다.

## 1. 확정된 공통 방향

1. 성공은 API별 DTO를 직접 반환한다. 공통 `ApiResponse<T>` 및 자동 성공 래퍼를 도입하지 않는다.
2. 명시적으로 처리한 애플리케이션 오류는 기존 `code` 중심 JSON을 유지한다. 오류를 HTTP 200으로 감싸지 않는다.
3. 204는 본문 없음이다. `{}`, `null`, 성공 메시지 및 추적 필드를 넣지 않는다.
4. 기존 인증 JSON·HTTP 상태·관련 헤더, Security·OAuth 처리 경계를 보존한다.
5. 오류 코드는 안정적인 의미의 UPPER_SNAKE_CASE다. 기존 코드에 접두사를 강제로 붙이거나 예외 클래스명을 공개하지 않는다.
6. `message/fieldErrors/requestId/operationId/details`는 API별 필요와 계약에 따라 선택적으로 확장한다. 없는 선택 필드는 생략한다.
7. `path/timestamp/status`는 필수 오류 본문 필드가 아니다. HTTP 상태와 본문의 중복 상태값을 만들 필요가 없다.

**모든 오류가 공통 JSON이라는 보장은 없다.** 이 계약은 명시적으로 처리한 오류에 적용한다. Security 필터, OAuth redirect, 응답 커밋 직전 검증, MVC 기본 오류, 컨테이너·프록시 오류는 각각 경계가 다르다. 본문 없는 오류나 예상하지 못한 응답도 클라이언트가 처리해야 한다.

`backend.md`의 ProblemDetail 우선 검토를 수행했다. 현재는 기존 code 소비자 및 JSON 미디어 타입을 유지하기 위해 전면 도입하지 않는다. 향후 도입 여부도 기존 계약 변경 영향과 함께 검토한다.

## 2. 현재 인증 응답과 구현 근거

소스 경로의 공통 기준은 `src/main/java/com/overload/overload_server/`다.

| 파일 | 역할·현재 성공 계약 |
|---|---|
| security/AuthSessionController.java | GET session: 200 `{userId}` |
| security/CsrfController.java | GET csrf: 200 Token record `{headerName,token}` |
| auth/email/EmailOtpController.java | OTP 요청 202 `{status:"ACCEPTED",challengeId}`, 검증 204 |
| auth/email/EmailReauthenticationController.java | identity 목록 200 `{identities:[{id,provider}]}`; 대표 이메일 상태 200; OTP 요청 202, 검증·등록 취소 204 |
| auth/email/EmailIdentityController.java | LINK 201 `{status:"LINKED",identity:{id,provider:"email"}}`; 기존 연결은 200 ALREADY_LINKED; UNLINK 204 |
| auth/identity/GoogleIdentityController.java | Google UNLINK 204 |
| auth/google/GoogleLoginController.java | 비활성·실패 경로의 code-only JSON |
| security/SecurityConfiguration.java | EntryPoint·AccessDeniedHandler·CSRF 오류 및 로그아웃 204 |

성공은 Map 또는 DTO와 ResponseEntity를 사용하며 공통 성공 래퍼는 없다. 대표 이메일은 `{status:"VERIFIED",maskedEmail}` 또는 `{status:"REQUIRED",candidate?:{id,maskedEmail}}`이다. 원문 이메일·Google subject는 목록에 없다.

설명용 합성 예시:

```json
{"userId":"11111111-1111-4111-8111-111111111111"}
```

```json
{"status":"ACCEPTED","challengeId":"22222222-2222-4222-8222-222222222222"}
```

```json
{"code":"AUTHENTICATION_REQUIRED"}
```

현재 오류 분류:

| HTTP | 기존 코드 |
|---|---|
| 400 | INVALID_REQUEST |
| 401 | AUTHENTICATION_REQUIRED, OTP_INVALID, GOOGLE_LOGIN_FAILED, GOOGLE_REAUTH_FAILED, GOOGLE_CONTACT_FAILED, GOOGLE_LINK_FAILED |
| 403 | CSRF_INVALID, ACCESS_DENIED, EMAIL_OWNERSHIP_PROOF_REQUIRED |
| 404 | IDENTITY_NOT_FOUND |
| 409 | LOGOUT_REQUIRED, REAUTH_EMAIL_REQUIRED, REAUTH_EMAIL_MISMATCH, REAUTH_EMAIL_ALREADY_VERIFIED, IDENTITY_ALREADY_OWNED, LAST_LOGIN_IDENTITY, IDENTITY_CHANGE_SUPERSEDED |
| 429 | OTP_RATE_LIMITED, Retry-After 초 단위 헤더 |
| 503 | OTP_UNAVAILABLE, EMAIL_LOGIN_UNAVAILABLE, IDENTITY_CHANGE_UNAVAILABLE, GOOGLE_*_UNAVAILABLE |

명시적 Controller·Advice 오류 및 성공은 no-store를 사용한다. 로그아웃 등의 응답은 Security 기본 헤더에도 의존한다. OAuth 경계는 no-store 및 no-referrer를 명시한다. 모든 인증 응답에 no-referrer가 설정됐다고 일반화하지 않는다. 기본 Security 헤더·쿠키 Secure/HttpOnly/SameSite·CSRF·90일 절대 만료·세션 회전을 변경하지 않는다.

## 3. 기존 예외 처리의 책임과 예외 경계

`auth/email/EmailOtpErrors`는 EmailOtpController, EmailReauthenticationController, EmailIdentityController, GoogleIdentityController에만 적용되는 Advice다. OtpFailure, IdentityChangeFailure, EmailProofRequired, SessionChanged, HttpMessageNotReadableException, DataAccessException을 변환한다. `ResponseEntity<Map<String,String>>`와 code-only JSON을 사용하고 OtpFailure의 Retry-After를 보존한다. 이름 정리나 전역 Advice 확장은 이번 범위가 아니다.

SecurityConfiguration의 EntryPoint는 401 AUTHENTICATION_REQUIRED다. AccessDeniedHandler는 익명이면 401, 인증된 CSRF 실패면 403 CSRF_INVALID, 그 밖의 거부는 403 ACCESS_DENIED다. 익명 CSRF 실패를 새로 403으로 바꾸지 않는다. OTP_INVALID의 401은 OTP 거절이며 로그인 만료와 동일하지 않다.

GoogleOidcSecurity·GoogleBoundOidcSecurity·GoogleLinkSecurity는 별도의 필터 경계에서 JSON 또는 고정 302 복귀를 만든다. 프론트 복귀 설정에 따른 차이도 보존한다. OAuth navigation을 공통 fetch 오류 처리로 바꾸지 않는다.

ReauthenticationResponseFilter와 SessionReauthentication.finishResponse는 성공 응답을 커밋하기 직전 다시 증명을 검증한다. 실패 시 401/503, Location 제거, 쿠키 만료가 가능하며 이 경로는 새 code JSON을 생성하지 않는다. 일반 Advice로 이 처리를 대체하지 않는다.

현재 미처리 예외·MVC 기본 응답·컨테이너 오류를 code-only라고 보장하지 않는다. 기존 인증 DTO에는 수동 검증과 일부 JsonAnySetter가 있으며 공통 Bean Validation 상세 형식은 아직 없다.

## 4. 공통 오류 JSON과 HTTP 상태

| 필드 | 계약 |
|---|---|
| code | 명시적으로 처리한 애플리케이션 오류의 필수 문자열 |
| message | 선택적 안전 안내. UI 분기나 성공 판단의 근거가 아님 |
| fieldErrors | 선택적 `[{field,code}]`; 요청 DTO의 안전한 필드 경로만 포함 |
| requestId | 선택적 추적 값. 제공 시 X-Request-ID 헤더와 동일 |
| operationId | 쓰기 API에서 검증된 명령 ID. Request ID와 역할이 다름 |
| details | 오류별로 정의된 허용 값만 포함. 무제한 Map/예외 덤프 아님 |

기존 인증과 008 초기 읽기 오류는 code-only를 유지한다. 초기 읽기의 userId/dataGeneration 성공 DTO도 그대로 유지한다. requestId를 성공·오류 본문에 강제로 추가하지 않는다.

향후 쓰기 검증 오류의 예시이며 현재 설치된 DTO는 아니다:

```json
{
  "code":"VALIDATION_FAILED",
  "requestId":"33333333-3333-4333-8333-333333333333",
  "fieldErrors":[{"field":"payload.reps","code":"OUT_OF_RANGE"}]
}
```

fieldErrors에 rejectedValue·요청 원문·검증 라이브러리의 내부 객체 경로·예외 메시지를 넣지 않는다. details는 권한 확인된 본인 리소스의 현재 version 등 필요한 값만 API별로 정의한다. 타 계정 리소스 존재 여부·SQL·제약조건명·stack trace는 제외한다.

| HTTP | 신규 운동 API 정책 |
|---|---|
| 200/201/202 | 각 API가 명시한 정상 처리·생성·접수. 접수와 영속 커밋을 혼동하지 않음 |
| 204 | 정상 처리, 본문 없음 |
| 400 | JSON·타입·UUID·지원하지 않는 query 등 형식 오류 |
| 401/403 | 기존 Security 인증·인가·CSRF 처리 유지 |
| 404 | 미존재·타 계정 대상에 동일한 안전 응답 |
| 409 | version/generation/제어권/ID/활성 참조 등 경쟁·상태 충돌 |
| 410 | 008-0B의 향후 만료된 동기화 cursor 계약을 유지 |
| 422 | 해석 가능한 요청의 값·도메인 상태 전이 규칙 위반 |
| 429 | 제한 도달 및 정의된 Retry-After |
| 500 | 예상하지 못한 오류·매핑 오류·데이터 불일치 |
| 503 | 확인된 일시적 저장소·서비스 이용 불가 |

WORKOUT_SETTINGS_MISSING은 기존 기준안대로 500이다. DataAccessException 전체를 신규 운동 API에서 무조건 503으로 처리하지 않는다. 기존 인증 매핑은 이번 작업으로 바꾸지 않는다. 405/415 등 프로토콜 상태를 임의로 400/500으로 뭉개지 않는다.

## 5. Request ID 실제 구현

구현 파일은 `http/RequestIdFilter.java`, `http/RequestIdConfiguration.java`다. 별도 의존성·DB·세션 속성·트랜잭션은 추가하지 않는다.

- 서버 생성 UUID v4를 request attribute `RequestIdFilter.ATTRIBUTE`에 **java.util.UUID**로 저장한다.
- 같은 값을 X-Request-ID 응답 헤더와 MDC `requestId` 문자열로 제공한다.
- 외부 X-Request-ID 헤더를 읽거나 반사하지 않는다. 내부 attribute는 HTTP 헤더로 설정할 수 없다.
- FilterRegistrationBean만 필터를 소유한다. 필터를 Component/별도 Filter bean/Security chain에 중복 등록하지 않는다.
- 등록 순서는 `SessionRepositoryFilter.DEFAULT_ORDER - 1` = **-2147483599**다. 기본 Session 순서 **-2147483598**, 기본 Security proxy 순서 **-100**보다 앞이다.
- URL mapping은 `/*`, dispatcher는 REQUEST와 ERROR다. Security·Session 정책과 내부 필터 순서는 변경하지 않는다.
- 순차 ERROR 재디스패치와 원래 필터 실행 중의 nested ERROR 모두 request attribute의 UUID를 재사용한다.
- 응답 wrapper는 본문·status·Location·쿠키를 해석하거나 버퍼링하지 않는다. X-Request-ID 중복·교체를 방지하고 `reset()` 성공 직후 같은 헤더를 다시 설정한다. resetBuffer는 본래 Servlet 동작을 유지한다.
- 헤더는 chain 진입 전에 설정하므로 조기 204·302·거절·flush에도 존재한다. 이미 커밋된 응답의 reset은 기존처럼 실패한다.
- MDC는 finally에서 이전 값으로 복원하거나 제거한다. ERROR 중첩에서도 외부 요청 문맥을 복원한다. 예외를 변환하거나 삼키지 않는다.

필터 진입 전 컨테이너·프록시의 요청 거절에는 ID가 없을 수 있다. 현재 비동기 작업·ASYNC dispatcher의 MDC 전달은 구현 범위 밖이다. 이후 비동기 API를 도입할 때 명시적 전달·정리 테스트가 필요하다.

## 6. 로그·민감정보·향후 Trace ID

필터는 dispatch 종료 시 INFO로 requestId, dispatcher enum, 관측 status, RETURNED/THREW, durationMs만 기록한다. route template·method·원문 경로를 기록하지 않는다. 안전한 route template이 없는 경우에도 원문 URI로 대체하지 않는다.

REQUEST에서 예외가 전파되고 후속 ERROR가 실행되면 같은 ID로 두 dispatch 로그가 생길 수 있다. REQUEST의 관측 status를 최종 HTTP status나 DB 커밋 결과라고 해석하지 않는다. 로그에 예외 메시지나 Throwable을 전달하지 않는다. 기존 프레임워크 로그 전체의 안전성을 이 필터 하나로 보장하는 것은 아니다.

이메일, OTP, 쿠키, CSRF, OAuth code/token, SQL 바인드 값, 원문 URL/query/body, 인증 헤더를 새 로그에 넣지 않는다. 오류 본문에도 요청/예외 원문을 복제하지 않는다. 예상 가능한 4xx마다 stack trace를 추가하지 않는다. 향후 5xx 진단도 원문 메시지·cause의 개인정보를 정제해야 한다.

Request ID는 **한 HTTP 요청**의 식별자다. 재송신은 새 Request ID를 가진다. operationId는 재송신에도 동일한 **봉인된 도메인 명령**의 식별자다. 둘 다 인증·인가 근거가 아니며 Request ID는 커밋 영수증이 아니다.

Trace ID는 향후 여러 서비스·span을 연결하는 별도 문맥이다. 지금 UUID를 분산 trace라고 부르거나 외부 trace header를 신뢰하지 않는다. 추후 tracing 도입 시 requestId와 traceId를 함께 기록할 수 있으며 OpenTelemetry 등은 이번에 추가하지 않는다.

## 7. 신규 운동 Advice — 설계만

008-1에서 실제 첫 운동 Controller와 함께 좁은 적용 범위의 Advice 및 code 중심 오류 DTO를 추가한다. 현재는 만들지 않는다. 인증 Advice의 전역 확대·모든 예외의 공통 상속·범용 성공 래퍼는 필요하지 않다.

신규 Advice는 형식 400, 소유권 확인 후 안전한 404, 경쟁 409, 도메인 검증 422, 예상하지 못한 500, 확인된 일시 장애 503을 처리한다. 401/403의 Security 경계를 유지하고 인증·인가 예외를 포괄적 Exception handler가 500으로 삼키지 않도록 한다.

검증 어노테이션·서비스 검증의 구체 적용에 맞춰 HttpMessageNotReadableException, MethodArgumentNotValidException, HandlerMethodValidationException 등의 변환을 검증한다. 내부 IllegalArgumentException을 일괄 400으로 숨기지 않는다. DB flush/commit 실패는 트랜잭션 바깥에서 HTTP로 변환하며 실패한 트랜잭션으로 처리를 계속하지 않는다.

Controller 선택 전 404/405/415와 필터·컨테이너 오류는 별도 경계다. scoped Advice가 이들을 모두 잡는다고 문서화하지 않는다. 이후 필요 시 신규 운동 경로에 한정된 fallback을 검토하고 기존 인증 `/error` 처리를 전역 교체하지 않는다.

## 8. 프론트엔드 결과·복구 계약 — 설계만

기존 AuthApi의 credentials/include, no-store, timeout, 204 처리, 런타임 검증 및 AuthController의 계정·요청 세대 방어를 참고한다. 이번에는 프론트 제품 코드를 바꾸지 않는다.

| 관찰 | 처리 |
|---|---|
| 유효한 쓰기 ACK 후 GET 실패 | 쓰기 응답과 최신 조회 실패를 독립 표시; 저장 실패로 되돌리지 않음 |
| timeout/네트워크/응답 유실/2xx 본문 불일치 | 커밋 미확인; 입력·봉인 명령 보존. abort를 롤백으로 해석하지 않음 |
| GET에서 값 확인 | 현재 상태 확인과 특정 명령의 적용 확인을 구분 |
| 명시적 검증·권한 거절 | 사유 안내; 임의 자동 재실행 없음 |
| 인증 만료 | 동기화 중단, 같은 계정 재인증; OTP_INVALID와 구분 |
| 계정/generation/epoch 변경 | 이전 작업 격리·늦은 성공과 오류 폐기; 자동 재태깅 금지 |

HTTP 파서는 body가 비었거나 HTML/잘못된 JSON이어도 status를 보존하고, 알 수 없는 필드·코드 및 없는 Request ID를 허용한다. 서버 message를 HTML로 렌더링하거나 분기 기준으로 삼지 않는다. 안전한 UI 안내를 code/status/작업 단계에 따라 선택한다. CSRF 준비 단계 실패와 실제 쓰기 송신 후 불확실성을 구분한다.

공통 fetch 계층에 자동 POST 재시도를 넣지 않는다. 향후 승인된 멱등 API에서는 같은 operationId·payload·expectedVersions·generation·epoch의 봉인본으로 확인한다. 새 ID로 충돌을 우회하지 않는다. 해당 기술 계약은 008-0B의 승인·구현 게이트를 따른다.

요청 시작의 사용자/인증 epoch/dataGeneration/조회 순번과 응답 적용 시점의 문맥을 대조한다. 이전 응답은 새 화면·캐시·CSRF를 바꾸지 못하게 한다. 기존 Controller의 UI generation과 서버 dataGeneration은 별개다.

EMAIL LINK 참고: [격리 수정 보고서](../../../overload-pwa/docs/tasks/task-007-1d-2b-fix-validation-report.md), [LIVE 후속 보고서](../../../overload-pwa/docs/tasks/task-007-1d-2b-fix-live-validation-report.md). 재현된 201 후 GET 실패 처리 결함과 과거 LIVE 원인은 구분한다. 과거 LIVE의 정확한 원인은 여전히 미확정이다.

## 9. 호환성 및 테스트 계약

기존 SecuritySessionIntegrationTests의 인증 오류 본문 정확 비교, OTP_INVALID의 일반화, no-store, Retry-After, identity 소유권·마지막 로그인 수단 정책, OAuth JSON/302 설정별 경계, 응답 커밋 직전 증명 검증은 유지해야 한다.

이번 DB 없는 테스트는 실제 embedded Servlet·Security·Spring Session 체인과 테스트 전용 메모리 SessionRepository/모의 인증 서비스로 검증한다. JDBC·트랜잭션 관리자·발송기는 mock이며 DB 호출이 없음을 검사한다. 실제 Google endpoint로 연결하지 않도록 OAuth 등록 주소를 loopback 테스트 값으로 대체하고 redirect를 따라가지 않는다. Flyway/DataSource 자동설정은 이 테스트 컨텍스트에서만 제외한다. 개발 `.env`를 읽는 local profile을 사용하지 않는다.

검증 대상: 200/204/401/403/302, 기존 인증 JSON/no-store/보안 헤더, 요청별 새 UUID, 외부 헤더 무시, 실제 ERROR 재디스패치, nested ERROR, reset/resetBuffer/commit, 고유 Servlet 등록 및 Security chain 중복 부재, Session 저장소에서 MDC 존재, MDC 복원·누수 방지, 새 로그의 민감 입력·예외 원문 미노출이다.

JDBC 영속성·실제 OAuth token 교환·OTP 발송 및 기존 postgres 통합 테스트는 이번 검증으로 완료라 하지 않는다. migration 실행 금지 범위를 지켜 별도 DB 테스트를 실행하지 않는다. 향후 DB 검증은 Testcontainers 임시 PostgreSQL만 사용하고 기존 JPA 혼용 게이트와 인증 회귀를 따른다.

## 10. 이번 범위·후속 범위·검증 기록

이번 구현은 Request ID 필터·등록 설정·격리 테스트 및 이 문서다. 인증 Controller/서비스/Security 정책·인증 JSON·프론트 제품 코드·의존성·migration·008-0A/0B 원문은 변경하지 않는다. Git commit/push는 수행하지 않는다.

후속 범위: 첫 운동 Controller/Advice/오류 DTO, 필요한 validation 상세, 오류 본문의 선택적 requestId, 안전한 5xx 진단, 승인된 명령 ACK/멱등성/Outbox, 비동기 및 분산 추적, 필요한 신규 경로 fallback이다. 이 문서만으로 운동 쓰기·JPA 구현을 승인하거나 완료 처리하지 않는다.

### 10.1 실제 검증 결과

JDK는 기존 Temurin 25.0.4.1을 명령 프로세스에만 지정했다. Gradle 캐시 잠금 파일의 sandbox 접근 제한은 승인된 오프라인 실행으로 해결했다. 신규 설치·네트워크 의존성 다운로드는 하지 않았다.

| 실행 | 결과 |
|---|---|
| `gradlew.bat --offline --no-daemon compileJava` | PASS |
| Request ID 계약 검증 (최종 test 실행에 포함) | **17개 PASS**: 단위 10개, 실제 HTTP 7개 |
| `gradlew.bat --offline --no-daemon test bootJar` 최종 | **112 tests / failures 0 / errors 0 / skipped 0**, bootJar PASS |
| 새 RequestIdFilterTests | 10개 PASS |
| 새 com.overload.testfixture.RequestIdHttpTests | 7개 PASS |

처음 테스트 컴파일에서 WebServerApplicationContext에 ServletContext 접근 메서드가 없어 실패했다. ServletContext를 별도로 주입해 수정했고 최종 전체 테스트를 다시 통과했다. 실패한 검사를 비활성화하지 않았다. 새 HTTP 테스트의 Controller와 설정은 앱 컴포넌트 스캔 밖 testfixture 패키지에서 명시적으로 import한다.

실제 Boot 등록 bean의 순서는 Request ID -2147483599, Session -2147483598, Security -100이었다. Servlet 필터 등록은 1개이며 별도 RequestIdFilter bean·Security chain 중복은 없었다. 실제 SessionRepository 호출에서 MDC가 존재했고, 실제 Tomcat의 REQUEST/ERROR 두 dispatch에서 헤더·attribute·로그 MDC의 동일 ID를 확인했다. 다른 컨테이너의 nested ERROR는 mock Servlet 경계 테스트다.

실제 HTTP 검증에는 Health 200, session/CSRF, 시험용 로그인·실제 logout 204, 세션 쿠키 회전·기존 CSRF 거절, Security 401/403, Advice 400, 모의 서비스 기반 OTP 202 및 EMAIL LINK 201/200·403/404/409/503, 실제 OAuth 시작/거절 콜백 302가 포함됐다. 모의 서비스 검증은 실제 OTP 소비나 identity 쓰기의 DB 원자성 검증이 아니다. JDBC·트랜잭션 관리자·발송기 호출이 없음을 테스트에서 검사했다.

테스트 worker 종료와 Tomcat graceful shutdown을 확인했다. 시작한 테스트 서버의 리스너 및 식별한 worker/단발 Gradle daemon 프로세스가 남지 않았음을 확인했다. 사용자 프로세스는 종료하지 않았다.

`integrationTest` 및 이를 포함하는 `build`는 실행하지 않았다. 이유는 이번 migration 실행 금지 범위이며 개발 DB와 `.env`는 접근하지 않았다. 기존 postgres 인증 회귀·JDBC 세션 저장·증명 커밋 경계·실제 OAuth token 검증·실제 이메일·HTTPS 전체 헤더 회귀·프론트 오류 파서는 미검증이다. 이들을 PASS로 간주하지 않는다.

자체 검토: 변경은 문서 1개·제품 코드 2개·테스트 2개뿐이다. 기존 인증/보안/설정/migration/의존성/008-0A·0B 및 프론트 파일은 불변이다. 일반 diff와 신규 파일별 공백 검사를 수행했다. stage/commit/push하지 않았다.

공통 계약·Request ID 기반 측면에서는 후속 008-0C 착수를 막는 문제가 없다. 저장소에 별도의 008-0C 상세 명세는 확인되지 않아 그 단계 전체의 완료 조건까지 통과했다고 선언하지 않는다. 특히 JPA/JDBC·Session 혼용 게이트는 기존 문서대로 별도 실행·승인이 필요하다.
