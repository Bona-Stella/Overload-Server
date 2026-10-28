# Task 007-1C-3B-1 — 신원 조회·이메일 OTP 재인증 검증 보고서

- 일자: 2026-10-28 (Asia/Seoul)
- 결과: 구현·자동화 검증·자체 검토 완료. Git staging/Commit/Push 없음.
- 시작/최종 브랜치: `main`; HEAD `5fcfca5313799ccc775073d5211d5197519ef422` 유지.
- 시작 작업 트리는 clean이었고 실제 origin/main도 같은 HEAD였다. 선행 Google LIVE 보고서는 이미 커밋된 상태였다.
- 근거: 사용자 요청/첨부 3B-1 지시서, AGENTS.md, Task 006 최종 v0.3.1, backend/account-provisioning/email-otp/security-session/google-oidc/local-postgresql 및 기존 LIVE 보고서.

## 구현 결과

| API | 성공 계약 |
|---|---|
| GET /api/v1/auth/identities | 인증 principal 소유 identity UUID/provider만 200 반환 |
| POST /api/v1/auth/reauth/email/request | 기존 소유 EMAIL identity UUID, CSRF 필요; 202 + challengeId |
| POST /api/v1/auth/reauth/email/verify | identityId/challengeId/문자열 code, CSRF 필요; 204 + 회전된 세션 쿠키 |

조회에 원문/가린 이메일·Google sub·issuer·토큰을 넣지 않았다. 없는/타인/Google identity는 같은 404다.
재인증에서 AccountProvisioningService/seed를 호출하지 않으며 사용자·신원을 생성·연결·이전·병합·삭제하지 않는다.
기존 LOGIN OTP, Google OIDC 시작/콜백, session `{userId}`, logout, Health 계약을 유지했다.

LOGIN과 REAUTH는 HMAC·DB purpose·제한 버킷·소비 경로를 분리했다. 기존 LOGIN HMAC 바이트 형식과
LOGIN 전용 browser claim은 유지했다. REAUTH는 사용자/identity/현재 session ID/전체 metadata의 해시 바인딩을 추가했다.
OTP는 기존 6자리 CSPRNG·5분·60초·5회 누적 예산·이메일/IP 제한·상수시간 비교·원자적 일회용 소비를 따른다.
새 메일의 목적 표기와 멱등 키는 로컬 HTTP 스텁으로만 검사했다.

V5는 기존 purpose CHECK를 확장하고 REAUTH owner/identity 컬럼·복합 FK를 추가하는 최소 스키마다.
새 영구 테이블 없이 LOGIN 상태와 기존 V1~V4를 보존했다. 개발 Compose DB에는 V5를 적용하지 않았다.

## 세션·트랜잭션 검증

OTP 소비와 원본 세션 행 락/교체를 같은 PostgreSQL 트랜잭션으로 처리한다.
새 session ID와 primary key로 교체하고 기존 자체 UUID principal·최초 생성 시각을 보존하며,
userId/loginStartedAt/absoluteExpiresAt를 유지하고 reauthenticatedAt만 갱신한다. 기존 CSRF는 복사하지 않는다.
90일 절대 만료와 최근 10분 정확 경계 정책은 변경하지 않았다.

쓰기 전/후·커밋 직전 검사에 더해 커밋 후 공식 SessionRepository.findById로 principal과 metadata 전체를 재조회한다.
쿠키 작성 및 HTTP 응답 커밋 직전도 검사한다. 메모리 객체나 저장 호출의 반환만으로 성공을 판단하지 않는다.
실제 PostgreSQL 새 행/속성 조회와 다음 독립 HTTP 요청의 인증 복원, 예전 cookie 거부, 새 CSRF 성공/옛 CSRF 거부를 확인했다.

| 실패 시점 | 검증된 복구 계약 |
|---|---|
| 소비/세션 SQL·속성 쓰기·커밋 전 시각/metadata 오류 | OTP/세션 변경 함께 롤백; 유효한 원본 세션과 ACTIVE OTP 유지 |
| 커밋 후 저장소 재조회 오류/metadata 불일치 | OTP CONSUMED 유지, 새 세션 폐기, 401/503; 일반 LOGIN으로 복구 |
| 쿠키/최종 응답 직전 만료 | 204 거부, 새 인증 세션 폐기·쿠키 제거; OTP 재활성화 없음 |
| 로그아웃/다른 ID/다른 principal/metadata/원래 만료 경합 | 저장된 원본과 불일치하면 성공 거부 |
| 중복 verify 또는 resend/verify 경쟁 | 성공 한 건만 허용, 예전 primary key 복원 없음 |

원본 행 락으로 경합 순서를 정한다. 재인증 커밋 이후 도착한 예전 쿠키 요청은 새 primary key를 수정할 수 없다.
응답 유실은 인증 성공의 증거가 아니며 클라이언트는 현재 session API로 확인해야 한다.
상세 HTTP/상태/실패 복구 계약은 [이메일 재인증 문서](../engineering/email-reauthentication.md)를 따른다.

## 실행한 검증과 실제 결과

환경은 Java 25 / Gradle Wrapper 9.8.2 / Boot 4.1.2 / Security 7.1.2 / Session JDBC 4.1.1을 유지했다.
새 의존성은 없다. PostgreSQL 테스트는 실제 `postgres:18.6-alpine` Testcontainers이며 H2/건너뛰기 대체를 사용하지 않았다.

```powershell
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/temurin-25.0.4.1'
.\gradlew.bat --no-daemon compileJava compileTestJava
.\gradlew.bat --no-daemon integrationTest --tests '*EmailReauthenticationIntegrationTests'
.\gradlew.bat --no-daemon test integrationTest build
git diff --check
```

전체 실행: **BUILD SUCCESSFUL**. 최종 XML 결과를 합산했다.

| 구분 | 실행 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|
| 단위/설정·계약 test | 35 | 0 | 0 | 0 |
| PostgreSQL integrationTest | 133 | 0 | 0 | 0 |
| **합계** | **168** | **0** | **0** | **0** |

| PostgreSQL suite | 테스트 수 |
|---|---:|
| AccountProvisioningIntegrationTests | 11 |
| EmailOtpIntegrationTests | 27 |
| EmailReauthenticationIntegrationTests | 40 |
| GoogleOidcIntegrationTests | 38 |
| PostgresMigrationTests | 7 |
| SecuritySessionIntegrationTests | 10 |

기존 124개를 유지하고 44개를 추가했다(재인증 40, Google-only/주소 동일 분리 1, V4→V5 보존 1,
HMAC 호환/목적 바인딩 1, REAUTH Resend 스텁 1). 기존 버전 개수 기대값만 V5까지로 확장했다.
정상 처리와 거부·장애·경합 시나리오 모두 최종 통과했다.

- 소유 신원 최소 조회·타인 거부·익명/CSRF, 로그인 후 기존 계정/운동/휴식 설정 전체 스냅샷 보존.
- UUID·최초 로그인·90일 불변, reauthenticatedAt 진전, 10분 정확 경계 및 초과, 90일 정확 만료.
- LOGIN↔REAUTH 혼용 거부, 같은 사용자의 다른 기기 거부, Google-only/OTP 주소 동일 계정 분리.
- 잘못된/만료/중복/선행 0/오답 5회/재전송 60초 경계/누적 예산·회복, 영속 제한 및 앱 재시작.
- untrusted X-Forwarded-For 변경에도 socket IP 제한 유지, 발송 실패/PENDING 검증 거부.
- 실제 SQL 소비/속성 쓰기 실패 롤백, 새 principal/deadline 손상, 원본 세션 변경·만료, 응답 전 만료 주입.
- 원본 세션 변경은 독립 JDBC 연결의 실제 커밋으로도 재현하여 OTP 롤백이 타 요청의 로그아웃/회전을 되돌리지 않음을 확인했다.
- 기존 Google 서명·issuer/audience·state/nonce/PKCE, OTP LOGIN/프로비저닝·세션/JDBC 복원·Health 전체 회귀.
- V4→V5에서 기존 4개 migration 체크섬, 사용자/신원/운동/휴식/LOGIN 제한·ACTIVE challenge·세션 속성 바이트 보존 및 재적용 0건.

검증 중 발견·해결한 사항:

1. 초기 테스트 장애 주입기가 Spring Repository AOP 프록시를 직접 spy하여 초기화에 실패했다.
   테스트에서 실제 target을 unwrap한 뒤 spy하도록 수정했다. 제품 인증 우회 경로는 추가하지 않았다.
2. 새 세션 쓰기 직후 시각이 만료로 바뀌는 시험에서 OTP가 먼저 소비될 수 있어 커밋 직전 재검사를 추가했다.
3. 응답 커밋 단계의 별도 재검사가 필요함을 테스트로 확인해 응답 필터를 추가했다.
   최종 쿠키/응답 전 만료 테스트도 204 없이 통과했다.

초기 실패를 최종 성공과 구분했다. 현재 미해결 테스트 실패는 없다. JVM CDS 경고는 있었으며 빌드 실패 사유가 아니었다.
별도 lint 도구는 현 빌드에 없어 임의 설치하지 않았고 `git diff --check`는 통과했다.

## 보존·보안·정리

- V1~V4 SQL과 Task 006 최종 설계의 diff 없음. 기존 역사적 LIVE 보고서도 수정하지 않았다.
- `.env` SHA-256 시작/완료 비교 일치. 키/비밀번호 원문을 출력하지 않았다.
  OTP_EMAIL_ENABLED/GOOGLE_OIDC_ENABLED는 모두 false 유지. 실제 Resend/Google 요청은 하지 않았다.
- 변경 파일에서 기존 `.env` 비밀값과 credential 패턴 검출 0건. `.env`와 build/는 ignore 상태다.
- 생산 boot jar에 테스트 fixture/통합 테스트/.env/테스트 TLS 저장소 항목 0건.
- 프론트 HEAD와 clean 상태 유지. 서버 main HEAD도 그대로이고 스테이징 파일은 0개다.
- 시작 전/완료 후 기존 Docker 컨테이너 7개 ID·상태와 기존 볼륨 7개 목록이 동일하다.
  개발 PostgreSQL은 계속 중지 상태였고 DB에 접속하지 않았다. 기존 두 LIVE 계정은 읽거나 수정하지 않았으며
  직접 SQL 재확인을 새로 수행했다는 의미는 아니다. 해당 개발 볼륨과 데이터는 보존했다.
- Testcontainers 임시 컨테이너·볼륨은 정상 정리됐고, 이번 Gradle/테스트 애플리케이션 및 Java 프로세스 종료를 확인했다.
  기존 사용자 프로세스를 임의 종료하지 않았다. 별도 개발 서버는 시작하지 않았다.

## 변경 파일 전체 (33개, 모두 unstaged)

기술 문서 5개 및 이 보고서:

- `docs/engineering/account-provisioning.md`
- `docs/engineering/email-otp.md`
- `docs/engineering/email-reauthentication.md` (신규)
- `docs/engineering/google-oidc.md`
- `docs/engineering/security-session.md`
- `docs/tasks/task-007-1c-3b-1-validation-report.md` (신규)

제품 Java/SQL 19개:

- `src/main/java/com/overload/overload_server/account/AccountIdentityRepository.java` (신규)
- `src/main/java/com/overload/overload_server/account/AccountIdentityService.java` (신규)
- `src/main/java/com/overload/overload_server/auth/email/EmailOtpErrors.java`
- `src/main/java/com/overload/overload_server/auth/email/EmailOtpRepository.java`
- `src/main/java/com/overload/overload_server/auth/email/EmailOtpService.java`
- `src/main/java/com/overload/overload_server/auth/email/EmailReauthenticationController.java` (신규)
- `src/main/java/com/overload/overload_server/auth/email/EmailReauthenticationService.java` (신규)
- `src/main/java/com/overload/overload_server/auth/email/OtpChallenges.java` (신규)
- `src/main/java/com/overload/overload_server/auth/email/OtpCrypto.java`
- `src/main/java/com/overload/overload_server/auth/email/OtpEmailSender.java`
- `src/main/java/com/overload/overload_server/auth/email/OtpPurpose.java` (신규)
- `src/main/java/com/overload/overload_server/auth/email/ResendOtpEmailSender.java`
- `src/main/java/com/overload/overload_server/security/AbsoluteExpiryCookieSerializer.java`
- `src/main/java/com/overload/overload_server/security/ReauthenticationResponseFilter.java` (신규)
- `src/main/java/com/overload/overload_server/security/ReauthenticationSessionRepository.java` (신규)
- `src/main/java/com/overload/overload_server/security/SecurityConfiguration.java`
- `src/main/java/com/overload/overload_server/security/SessionPolicy.java`
- `src/main/java/com/overload/overload_server/security/SessionReauthentication.java` (신규)
- `src/main/resources/db/migration/V5__add_email_reauthentication.sql` (신규)

테스트 8개:

- `src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java`
- `src/test/java/com/overload/overload_server/auth/email/EmailReauthenticationIntegrationTests.java` (신규)
- `src/test/java/com/overload/overload_server/auth/email/OtpCryptoTests.java`
- `src/test/java/com/overload/overload_server/auth/email/ResendOtpEmailSenderTests.java`
- `src/test/java/com/overload/overload_server/auth/google/GoogleOidcIntegrationTests.java`
- `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java`
- `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java`
- `src/test/java/com/overload/testfixture/ReauthenticationFixtures.java` (신규)

## 남은 범위

추가 사용자 승인이 필요한 작업 없이 이번 구현/격리 검증 범위를 완료했다. 실제 이메일 재인증 발송/브라우저 E2E,
개발 DB V5 적용, Google 최근 재인증(3B-2), 인증 수단 연결·해제(3B-3), 프론트 연동은 수행하지 않았다.
기존 프론트의 9~10회 휴식 구간과 서버 정책 정렬 전달 사항도 그대로 남는다.
Git Commit·Push와 운영 배포는 수행하지 않았으며 이번 결과는 사용자 검토 대상이다.
