# Task 007-1C-3A-LIVE — Google 실제 로그인 검증 보고서

검증일: **2026-10-28 (Asia/Seoul)**.
최종 결과: **사용자가 직접 수행한 실제 Google 로그인과 자체 세션·PostgreSQL 검증 성공**.
자동화 124개 통과와 구분되는 실서비스 E2E 결과다. 이번에는 자동화 테스트를 재실행하지 않았다.

## 1. 시작 상태·설정

- `overload-server`: main, HEAD `b64cdac2db39b6c749292fca0cea38bfa6dfa612`, 시작 작업 트리 clean.
- AGENTS.md, Google OIDC 기술 문서, 1C-3A 구현·검증 보고서와 기존 설정/소스를 확인했다.
- `.env`의 GOOGLE_CLIENT_ID·GOOGLE_CLIENT_SECRET 존재/기본 형식과 GOOGLE_REDIRECT_URI 일치를 확인했다.
  실제 값·길이·개인 Google 계정 주소·sub·UUID는 보고하지 않는다.
- 승인된 콜백은 정확히 `http://localhost:8080/login/oauth2/code/google`이다.
- `.env`의 GOOGLE_OIDC_ENABLED=false를 유지하고 서버 프로세스 인자로만 true를 적용했다.
  실제 클라이언트 설정을 가짜 값이나 테스트 provider로 바꾸지 않았다.
- `.env`의 OTP_EMAIL_ENABLED=false도 유지했다. 서버 인자로 false를 명시해 Resend 발송을 차단했다.
- Google/Resend/HMAC/PostgreSQL 비밀 설정은 수정하지 않았다. 시작/종료 `.env` fingerprint가 동일하다.

## 2. 개발 환경 실행·사전 점검

기존 Docker 리소스와 8080 포트가 비어 있음을 확인한 뒤 `overload-local-postgres-1`만 시작했다.
데이터가 있는 `overload-local_postgres_data`를 그대로 사용했다. 초기화·재생성·삭제는 하지 않았다.
DB에는 이미 V1~V4가 적용되어 있었고 추가 마이그레이션은 없었다.

| 버전 | checksum | success |
|---|---:|---|
| V1 | 1980645960 | true |
| V2 | -671956178 | true |
| V3 | -766153280 | true |
| V4 | 1645014211 | true |

기존 JAR을 Java 25, local 프로필, 127.0.0.1:8080으로 실행했다. 브라우저 기준 주소는 localhost:8080이다.
계정 정보가 로그에 남지 않도록 이번 프로세스의 앱/HTTP 요청·액세스 로그를 비활성화했다.
종료 후 stdout/stderr 파일은 각각 **0바이트**였다. 원본 OAuth 콜백 URL을 수집하지 않았다.

| 사전 점검 | 결과 |
|---|---|
| GET /api/v1/health | 200, 정확한 UP JSON |
| 익명 GET /api/v1/auth/session | 401 |
| GET /api/v1/auth/csrf | 200, 토큰 존재 확인만 수행 |
| GET /oauth2/authorization/google | 302 |
| 302 목적지/요청 | Google HTTPS, 정확한 callback, openid·state·nonce·PKCE S256 확인 |

사전 점검 HTTP 클라이언트는 리디렉션을 따라가지 않았다. Google 인증을 실행하지 않고 로컬 응답 형식만 확인했다.
이 점검이 만든 익명 세션은 같은 쿠키·CSRF로 POST logout 204를 수행해 정리했다.
기존 사용자 브라우저나 인증 세션을 로그아웃하지 않았다.

## 3. 실제 사용자 브라우저 로그인

사용자에게 `http://localhost:8080/oauth2/authorization/google`을 안내했다.
사용자가 자신의 PC 브라우저에서 계정 선택·인증·동의를 직접 진행했다.
비밀번호·2FA·Google 개인 계정 정보·OAuth code/state·토큰을 요구하거나 읽지 않았다.

사용자는 **"로그인 완료, 세션 JSON 표시"**를 확인했다.
고정 성공 경로 `/api/v1/auth/session` 도착, 새 Google identity/자체 세션 생성 및 후속 상태 API 200을 함께 확인해
실제 OAuth 콜백 성공으로 판단했다. 콜백의 원본 네트워크 URL/쿼리나 토큰 응답을 캡처했다고 주장하지 않는다.
사용자 동의 없는 로그인 반복·자동 재시도는 하지 않았다.

## 4. 계정·seed·기존 데이터

시작 DB에는 OTP 사용자 1명·email identity 1개·인증 세션 1개가 있었고 Google identity는 없었다.
읽기 전용 감사 도구로 기존 users/auth_identities/exercise_definitions/user_workout_settings의
행별 비식별 fingerprint를 기록해 로그인 이후와 비교했다. 원본 UUID·sub·이메일·행 데이터는 파일/보고서에 기록하지 않았다.

| 항목 | 실제 결과 |
|---|---|
| Google 계정 | **신규 사용자 1명** |
| Google 신원 | provider google, 공식 HTTPS issuer, 비어 있지 않은 sub, 신원 1개 |
| 기본 운동 종목 | **20개** |
| 휴식 설정 | **1개, 180/120/90초** |
| 기존 OTP 계정·운동·설정·신원 | 모든 기존 행 fingerprint 불변 |
| Google/OTP 소유자 | 서로 다른 사용자, 한 사용자에 두 제공자가 연결된 행 0건 |
| 최종 전체 데이터 | users 2 / identity email 1+google 1 / 종목 40 / 설정 2 |

이번 실제 Google 로그인은 신규 계정 경로였다. 기존 Google 계정의 재로그인·UUID/편집 데이터 보존은
추가 로그인으로 반복하지 않았다. 해당 경로는 기존 1C-3A 자동화 검증 범위로 구분한다.

사용자는 이번 Google 계정 주소가 기존 OTP 계정과 **다른 주소**라고 확인했다.
따라서 실제 동일 이메일 조합의 자동 병합 금지를 이번 LIVE에서 검증했다고 표현하지 않는다.
실제 DB에서 두 계정이 분리되고 OTP 데이터가 보존된 것을 확인했고, 동일 이메일 분리 사례는
기존 1C-3A PostgreSQL 통합 테스트에서 이미 검증한 정책이다. 주소 일치에 따른 연결/병합은 구현하지 않았다.

## 5. JDBC 세션·새 HTTP 요청

새 Google 인증 세션을 DB에서 유일하게 식별했다. 다음을 Java 역직렬화 및 읽기 전용 SQL로 확인했다.

- 최종 principal은 Google/OidcUser가 아닌 **Overload SessionPrincipal**이며 Google 사용자 UUID와 일치한다.
- SecurityContext의 credentials/details는 null이다. 세션에 Google token/AuthorizedClient 타입이 남지 않았다.
- SessionMetadata의 사용자와 principal이 일치하고, 절대 만료는 **최초 로그인 시각 + 정확히 90일**이다.
- 최초 재인증 시각은 로그인 시각과 같다. JDBC TTL은 남은 90일 범위 안이다.
- 기존 OTP 세션과 새 Google 세션을 합쳐 인증 세션 2개가 저장됐다.

브라우저 쿠키/개발자 도구를 읽지 않았다. 이미 검증되어 저장된 새 Google 세션 ID를 감사 도구의 메모리에서만
사용해 별도 HTTP 클라이언트로 상태 API를 조회했다. 쿠키·CSRF·응답 UUID는 명령 인자나 파일/로그로 출력하지 않았다.

| 후속 검증 | 결과 |
|---|---|
| 새 HTTP GET /api/v1/auth/session | 200, 응답 UUID와 DB 사용자가 일치, 단일 userId 필드 |
| 추가 새 HTTP GET /api/v1/auth/session | 200, 같은 사용자 |
| 로그인 후 CSRF GET | 200, 토큰 존재만 확인 |
| 후속 요청 전후 SessionMetadata | 전체 값 동일, 90일 만료 연장 없음 |

실제 90일을 기다리는 검증이나 기존 자동화의 시계 이동 테스트를 새로 실행한 것은 아니다.
실제 저장된 기한의 정확한 차이와 새 요청에서 기한이 불변인 것을 확인했다.

## 6. 발견 문제·코드 변경

발견된 제품 결함과 미해결 LIVE 검증 오류는 **없음**이다.
제품 소스·테스트·마이그레이션·빌드 설정·프론트는 수정하지 않았다.
임시 Java 감사 도구는 DB 읽기·비식별 비교·기존 인증 세션의 HTTP 조회만 수행했다.
임의 신원/계정 생성·Google 검증 우회·토큰 수집·재발급·Resend 이메일 발송은 하지 않았다.

## 7. 종료·데이터 보존·Git

- 이번 서버 PID와 감사 프로세스를 정리했고 잔여 프로세스 및 8080 리스너는 0건이다.
- 기존 Overload PostgreSQL을 시작 전과 같은 중지 상태로 돌렸다. Google/OTP 계정·개인 seed·인증 세션은 보존했다.
- 기존 7개 컨테이너의 ID/실행 상태와 7개 볼륨 목록이 시작 상태와 동일하다. 타 프로젝트는 변경하지 않았다.
- Google 임시 활성화는 서버 종료로 해제됐다. `.env`의 Google/OTP 활성화 값은 모두 원래 false이며 내용 전체가 불변이다.
- 임시 감사 도구·추출 라이브러리·비식별 baseline·실행 로그/결과 파일은 정리했다. 민감값 없는 결과만 본 보고서에 남겼다.
- 변경 파일은 **신규 본 보고서 1개**다. V1~V4와 Task 006, 프론트 및 기존 문서/보고서는 그대로다.
- 최종 HEAD는 `b64cdac2db39b6c749292fca0cea38bfa6dfa612`, main이다. 본 보고서만 untracked, staged 0이다.
  Git Commit·Push는 수행하지 않았다. 007-1C-3B·프론트·iPhone/LAN·운영 배포는 진행하지 않았다.

기존 [1C-3A 자동화 보고서](task-007-1c-3a-validation-report.md)의 124개 통과와 실제 Google 미검증 표시는
그 단계의 기록이다. 본 LIVE 결과에서 PC Google 실로그인 성공을 별도로 추가 확인했다.
