# Task 007-1C-2 구현 및 검증 보고서

검증일: 2026-10-27~28 (Asia/Seoul). 범위는 서버 이메일 OTP 로그인·Resend 어댑터·PostgreSQL 검증이다.
실제 이메일 발송, 프론트엔드 수정, 운영 접속·배포, Git staging·Commit·Push는 수행하지 않았다.

## 1. 작업 전후 Git·환경

| 저장소 | 시작 | 완료 시점 |
|---|---|---|
| overload-server | main, `353d87aa45ebb0b1b82821adf464f9aa211c6a8c`, clean | 같은 main/SHA, 이번 단계 28개 파일 변경, staged 0 |
| overload-pwa | main, `f90c8f1f26478af516b0c8586e0de5447477031d`, clean | 같은 SHA, clean, 수정 없음 |

시작 시 서버 원격 main도 `353d87aa45ebb0b1b82821adf464f9aa211c6a8c`임을 읽기 전용으로 확인했다.
Java 25, Spring Boot 4.1.2, Gradle Wrapper 9.8.2, PostgreSQL `18.6-alpine`, 기존 JDBC/Flyway/Session JDBC를 유지했다.
신규 의존성·Redis·JWT·JPA·메일 큐를 추가하지 않았다. Task 006 최종 설계는 변경하지 않았다.

## 2. 변경 파일 전체

수정 12개:

| 파일 | 변경 |
|---|---|
| `.env.example` | 비밀값 없는 OTP 활성화·Resend·HMAC 변수 안내 |
| `README.md` | 서버 이메일 인증 구현 상태와 안내 링크 |
| `docs/engineering/account-provisioning.md` | 검증된 이메일 신원 연결 상태 |
| `docs/engineering/backend.md` | 이메일 OTP 현재 구현 범위 |
| `docs/engineering/local-postgresql.md` | V4·테스트·설정 안내 |
| `docs/engineering/security-session.md` | 이메일 로그인과 즉시 저장·저장소 재조회 |
| `src/main/java/com/overload/overload_server/security/SecurityConfiguration.java` | 두 OTP POST만 익명 허용, CSRF 유지 |
| `src/main/resources/application.yaml` | JDBC flush-mode immediate, 미검증 forwarded 헤더 비신뢰 |
| `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java` | DB 없는 컨텍스트 검사용 SessionRepository mock |
| `src/test/java/com/overload/overload_server/account/AccountProvisioningIntegrationTests.java` | V4 테이블 목록 반영 |
| `src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java` | V4 업그레이드·재실행·checksum 보존 |
| `src/test/java/com/overload/overload_server/security/SecuritySessionIntegrationTests.java` | 최신 마이그레이션 개수 반영 |

신규 16개:

| 파일 | 역할 |
|---|---|
| `docs/engineering/email-otp.md` | API·정책·구성·장애 복구·프론트 계약·공식 근거 |
| `docs/tasks/task-007-1c-2-validation-report.md` | 본 보고서 |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpConfiguration.java` | 실제 HttpClient·sender·서비스 wiring |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpController.java` | CSRF 보호 발급·검증, 새 세션 성립·저장 확인 |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpErrors.java` | 민감정보 없는 오류 응답 |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpRepository.java` | SQL·잠금·rate·소비·브라우저 claim |
| `src/main/java/com/overload/overload_server/auth/email/EmailOtpService.java` | 발급·발송·소비·계정 프로비저닝 경계 |
| `src/main/java/com/overload/overload_server/auth/email/OtpCrypto.java` | CSPRNG·HMAC·식별 해시 |
| `src/main/java/com/overload/overload_server/auth/email/OtpEmailSender.java` | sender 경계·민감정보를 숨기는 메시지 |
| `src/main/java/com/overload/overload_server/auth/email/OtpFailure.java` | 안전한 상태·오류 코드·Retry-After |
| `src/main/java/com/overload/overload_server/auth/email/ResendOtpEmailSender.java` | 실제 HTTPS API 어댑터 |
| `src/main/resources/db/migration/V4__create_email_otp.sql` | OTP·제한·claim DDL |
| `src/test/java/com/overload/overload_server/auth/email/EmailOtpIntegrationTests.java` | PostgreSQL·실제 HTTP·동시성·실패 주입 |
| `src/test/java/com/overload/overload_server/auth/email/OtpCryptoTests.java` | 선행 0·바인딩·키 fail-closed |
| `src/test/java/com/overload/overload_server/auth/email/ResendOtpEmailSenderTests.java` | 루프백 API 스텁·오류·timeout |
| `src/test/java/com/overload/testfixture/EmailOtpFixtures.java` | 테스트 전용 Clock·fake sender·보호된 소유자 확인 경로 |

## 3. 구현 결과와 실제 DDL

POST `/api/v1/auth/email-otp/request`는 `{email}`을 받아 발송 수락·DB 활성화 확인 후 202와 challengeId를 반환한다.
이 단계에서는 계정을 만들지 않는다. POST `/api/v1/auth/email-otp/verify`는 `{email, challengeId, code}`의
유효한 일회용 증명을 소비하고 계정·저장된 세션까지 확인한 경우 204다. 두 POST 모두 기존 CSRF가 필요하다.
이미 인증된 세션은 같은 이메일이라도 409로 거절하고 명시적 로그아웃을 요구한다.

도메인만 소문자화하며 로컬 부분의 대소문자·점·+태그를 보존한다. 같은 주소의 Google identity와 병합하지 않는다.
신규 계정은 기존 1B 서비스를 통해 운동 정의 20개·휴식 180/120/90초 설정만 생성한다.
기존 계정의 UUID·generation·닉네임·편집 데이터·seed를 유지한다. 공개 임의 userId 프로비저닝 API는 없다.

V4를 실제 PostgreSQL 빈 Testcontainers DB에 적용해 다음을 확인했다. 보존용 개발 DB에는 적용하지 않았다.

| 테이블 | 핵심 DDL |
|---|---|
| email_otp_limits | `(email_key CHAR(64), purpose)` PK, 현재 발급 UUID·최근 발급·오답 0~5·실패 창 |
| email_otp_challenges | UUID PK, 목적·수신 주소·브라우저 해시·BYTEA verifier(32바이트)·발급별 오답 0~5·상태·시각 |
| email_otp_login_claims | 브라우저 해시 PK, challenge UUID UNIQUE/FK·claim 시각 |
| email_otp_rate_limits | `(bucket_key CHAR(64), purpose)` PK, 고정 창 시작·음수 불가 횟수 |

목적은 LOGIN CHECK로 고정한다. 발급에는 이메일/목적 guard FK, 현재 포인터에는 같은 이메일/목적/발급 복합 FK가 있다.
상태·소비 시각·활성 수락 시각·만료 순서 CHECK와 만료 인덱스를 검증했다. 실제 SQL은 V4 파일을 기준으로 한다.

V1→V4, V2→V4, V3→V4 및 재시작 후 재적용 0건을 확인했다. 기존 Flyway checksum·사용자 데이터 보존 테스트가 통과했다.
HEAD 대비 원본 Git blob도 동일하다:

| 보호 파일 | Git blob SHA |
|---|---|
| V1__create_users.sql | `c2bacee9695e41170edd43127a6408132f5ae99f` |
| V2__create_account_provisioning.sql | `dc5649595defe7e059b18fdcb97a905f8e87ab63` |
| V3__create_spring_session.sql | `a5252855ad4819f4035bb3c9f4c577315a0a7a81` |
| Task 006 v0.3.1 | `54412e45b55787899d76e2d829caab6cac3219eb` |

## 4. OTP·동시성·트랜잭션

SecureRandom으로 균등한 6자리 문자열을 만들고 선행 0을 유지한다. 5분 유효·60초 재전송·오답 최대 5회를 적용한다.
서버 시각은 밀리초 정밀도로 통일한다. HMAC-SHA256에 버전·LOGIN·정규화 이메일·발급 UUID·코드를 길이와 함께 바인딩한다.
환경변수 Base64 비밀키는 최소 32바이트이고 누락/잘못된 키는 fail-closed다. 키 변경은 기존 코드를 무효화하되 제한 예산은 보존한다.
OTP 평문은 DB·응답·로그에 쓰지 않는다. 없는 발급에도 HMAC·상수시간 비교를 수행하고 계정 존재를 미리 조회하지 않는다.
잘못된/없는/만료/소비된/다른 브라우저 증명은 같은 OTP_INVALID 응답이다. 전체 SQL 실행 시간을 동일하게 보장한다는 주장은 하지 않는다.

확정되지 않은 제한은 다음 보수적인 고정 창 기본값으로 선택했다. 이메일 발송 남용과 분산 추측을 제한하면서
공유 IP의 소수 사용자에게 여유를 주기 위한 값이며, 실제 트래픽에 따른 후속 조정 대상이다.

| 제한 | 기본값 |
|---|---|
| 이메일 발급 / IP 발급 | 5회/1시간 / 20회/1시간 |
| 이메일 검증 / IP 검증 | 20회/15분 / 100회/15분 |
| 이메일 누적 오답 | 5회/30분, 재전송으로 초기화 안 됨 |
| 개별 발급 오답 | 발급 수명 전체 5회, 누적 실패 창 경계로 초기화 안 됨 |

제한·쿨다운 거절도 요청 횟수에 반영한다. DB에 지속되며 재시작으로 풀리지 않는다.
Servlet remoteAddr만 사용하고 forwarded 헤더 처리를 꺼서 X-Forwarded-For 위조로 IP 제한을 우회하지 못한다.
프록시 신뢰 정책은 배포 전 별도 검토 대상이다.

1. 발급: 짧은 READ_COMMITTED 트랜잭션에서 IP→이메일 버킷→이메일 guard 순서로 잠근다.
   이전 발급 폐기와 새 PENDING·HMAC·쿨다운을 커밋한다.
2. 발송: DB 트랜잭션 밖에서 호출한다. 명확한 provider 수락 뒤 현재 발급·만료 전인 경우만 ACTIVE로 전환한다.
   오류·timeout·애매한 응답은 FAILED, 프로세스 중단·활성화 DB 실패는 PENDING으로 남을 수 있고 모두 인증 불가다.
   늦게 끝난 이전 발송은 새 발급을 덮거나 옛 코드를 활성화하지 못한다.
3. 검증: 같은 이메일 guard 잠금에서 예산·브라우저·ACTIVE·유효 시각·HMAC 확인 후 브라우저 claim과 CONSUMED를 원자 커밋한다.
   소비 verifier는 32바이트 0으로 덮어쓴다. 같은 코드 또는 같은 익명 브라우저의 병렬 인증은 한 건만 통과한다.
4. 계정: 소비 커밋 후 외부 트랜잭션 없이 기존 REQUIRES_NEW 프로비저닝을 호출한다.
   오케스트레이션의 ambient DB 트랜잭션은 거절한다. 계정/seed 실패는 롤백하고 claim을 풀지만 코드를 되살리지 않는다.
5. 세션: 계정 성공 후 기존 익명 세션을 무효화하고 새 JDBC primary key 세션을 만든다.
   기존 AuthenticationSessionService로 인증하며 immediate flush와 저장소 재조회로 Context·principal·metadata의 사용자 일치를 확인한다.
   저장 실패는 성공으로 응답하지 않고 Context 제거·세션 폐기를 시도한다.

전체를 하나의 트랜잭션으로 묶지 않았다. 계정 실패는 새 OTP, 세션 실패는 새 CSRF·새 OTP로 복구한다.
소비 직후 프로세스가 중단돼 브라우저 claim이 남으면 CSRF를 얻고 기존 익명 logout 후 새 OTP를 받는다.
완성된 계정은 다음 검증에서 재사용한다. 소비한 코드를 재활성화하거나 검증 없는 계정을 만들지 않는다.
기존 90일 절대 만료·최근 10분 재인증·쿠키·로그아웃 정책은 회귀 테스트로 유지했다.

## 5. Resend 구현·실발송 상태

새 SDK 대신 기존 Java 25 HttpClient와 Jackson 3으로 공식 HTTPS `POST https://api.resend.com/emails`를 구현했다.
공식 API·멱등 문서를 확인했고 개별 intent의 `Idempotency-Key: email-otp/<issuanceId>`와 동일 payload를 사용한다.
connect 3초/request 5초, redirect·자동 retry 없음이다. 2xx와 유효한 message id만 수락으로 인정하며 최종 전달과 구분한다.
권한·발신자 오류·429·5xx·timeout·애매한 수락 본문은 일반 503이며 provider 본문/키를 노출하지 않는다.

실제 Resend 외부 API 호출·메일 발송·받은편지함 수신 검증은 **0회**다. sender 단위 테스트는 127.0.0.1 HTTP 스텁만 사용했다.
통합 테스트는 테스트 소스의 fake sender를 명시적으로 주입한다. 테스트용 로그인 우회 경로는 추가하지 않았다.

확인 당시 RESEND_API_KEY·RESEND_FROM_EMAIL·OTP_HMAC_KEY·OTP_EMAIL_ENABLED는 프로세스와 로컬 .env에 없었다.
값을 출력하지 않았고 .env를 수정하지 않았다. 실메일 기본 활성화는 false다.
실발송에는 Resend 계정·인증된 발신 도메인·발송 전용 최소 권한 키·bare 발신 주소·별도 CSPRNG HMAC 키·승인된 수신자가 필요하다.
설정을 Git/채팅에 공개하지 않고 로컬 환경으로 준비한 뒤, **실제 발송·수신 테스트를 별도 승인**받아야 한다.
현재 승인 대기는 없으며 실발송은 이번 범위 밖으로 남겼다.

## 6. 실행한 검증

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/temurin-25.0.4.1"
.\gradlew.bat --no-daemon test integrationTest build
git diff --check
```

최종 Gradle 결과: **BUILD SUCCESSFUL**, 1분 46초, 8 actionable tasks: 3 executed, 5 up-to-date.
실행 결과 XML에서 실패·오류·skip 모두 0을 확인했다. up-to-date 태스크의 앞선 실행 결과도 보존되어 있으며 테스트를 비활성화하지 않았다.

| 테스트 | 개수 |
|---|---:|
| AccountSeedTests / VerifiedIdentityTests | 8 / 3 |
| OtpCryptoTests / ResendOtpEmailSenderTests | 3 / 8 |
| HealthControllerTests / 컨텍스트 / SessionPolicyTests | 2 / 1 / 3 |
| AccountProvisioningIntegrationTests | 11 |
| EmailOtpIntegrationTests | 27 |
| PostgresMigrationTests | 6 |
| SecuritySessionIntegrationTests | 10 |
| 합계 | **82 (단위 28, PostgreSQL 통합 54)** |

OTP 통합 검증은 실제 PostgreSQL 18.6·HTTP 쿠키·CSRF·DB 세션 재복원을 사용했다.
5분/60초 정확한 경계·선행 0·5회 실패/6회 차단·재전송/관찰 창 실패 예산·재시작 후 제한·IP 헤더 위조·
새 사용자 seed/기존 편집 보존/Google 독립성/소유권·병렬 verify/request/resend·같은 브라우저의 다른 이메일 경합을 검증했다.
발송 실패/중단·수락 후 DB 실패·소비 DB 실패·계정 seed 롤백·세션 속성 저장 실패·소비 후 프로세스 중단 복구도 검증했다.
Resend 스텁은 401/403/422/429/500/불명확한 200·timeout·비활성 무발송을 확인했다.

검증 중 발견하고 해결한 문제:

- PostgreSQL 시각 반올림과 Java 나노초 차이로 정확한 만료 테스트가 실패했다. 서버 시각을 밀리초로 통일한 뒤 경계가 통과했다.
- 누적 실패 창이 바뀌는 순간 같은 발급의 추측 예산이 늘 수 있음을 검토하고 발급별 오답 5회도 지속 저장·테스트했다.
- 세션 속성 SQL 실패 주입 시 204가 나왔다. Session JDBC 4.1.1이 일부 무결성 오류를 동시 삭제로 간주해 삼키는 동작을 확인했다.
  response flush 또는 immediate만으로는 부족했고, 최종적으로 immediate와 실제 저장소 재조회를 함께 적용해 성공 오판을 막았다.
  실패 주입·동시 쿠키 복원과 전체 회귀 테스트가 최종 통과했다.

## 7. 비밀 보호·리소스·최종 상태

추적 변경은 `git diff --check`, 신규 미추적 파일도 별도 공백 검사했다.
Git에 보이는 파일 전체에 로컬 실제 DB 비밀번호의 포함 여부를 값 출력 없이 검사하고 private-key/API-token 패턴도 검사했다.
.env는 ignored이며 실제 키·비밀번호·TLS 키·build/.gradle·Docker 데이터는 변경 파일 목록에 없다.
생산 bootJar에 testfixture/FakeSender/OTP 테스트 클래스가 포함되지 않음을 확인했다. OTP/API DTO·sender 메시지는 toString을 숨긴다.

Testcontainers/Ryuk와 테스트용 임시 PostgreSQL만 생성·정리했다. 기존 7개 컨테이너의 ID·이름·실행/중지 상태와 7개 볼륨 목록이
시작 시점과 동일하다. overload-local-postgres-1은 계속 중지 상태이며 overload-local_postgres_data도 보존했다.
타 프로젝트 컨테이너/DB, Windows/WSL/Docker 시스템 설정을 변경하지 않았다.
이번 앱/테스트/keytool 잔여 프로세스 0건과 최근 Gradle 일회성 daemon 종료를 확인했다.

미해결 테스트 실패는 없다. 실제 이메일·실제 iPhone/PWA·운영 환경 검증은 수행하지 않았다.
실발송 준비 외에도 OTP 수신 주소/상태/claim/버킷의 보존 기간·정리 정책과 운영 프록시 신뢰는 배포 전 후속 검토가 필요하다.
정책 미확정을 이유로 자동 삭제 작업이나 새로운 운영 설정은 넣지 않았다.

## 8. 후속 전달 사항

- 007-1C-3 구현은 시작하지 않았다. 실제 Google OIDC·인증 수단 연결/해제·이메일 변경·민감 작업 재인증은 후속 범위다.
- VerifiedIdentity 자체는 증명 수단이 아니다. 이메일은 이번 원자 소비 이후만 프로비저닝하고, Google도 검증된 신원만 연결해야 한다.
- Google·이메일 주소 자동 병합은 금지한다.
- 프론트는 credentials include·CSRF 취득/로그인 후 갱신·문자열 OTP·challengeId·60초 cooldown·Retry-After 및 401/403/409/429/503을 연동해야 한다.
- 프론트의 휴식시간 9~10회 구간은 서버 확정 정책에 맞추는 별도 작업으로 남긴다.
- 실제 사용/보안/장애 복구 계약은 [email-otp 안내](../engineering/email-otp.md), 최종 DDL은 V4를 참조한다.

Git staging·Commit·Push는 수행하지 않았다. 현재 변경 내용은 사용자 검토용 작업 트리에 남겨 두었다.
