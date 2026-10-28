# Task 007-1C-3B-LIVE — EMAIL·Google 로그인 수단 실제 검증

작성일: 2026-10-28. **전용 계정의 실제 EMAIL/Google LINK·UNLINK 및 로그인 검증 완료.**
제품 코드 수정·Git Commit·Push는 수행하지 않았다. 자동화 364개 통과는 선행 결과이며 이번 LIVE와 구분한다.

## 1. Git 및 시작 환경

- main, HEAD `00c2e4dda7b57aabe659550f3414ea9a89ee160b`, 시작 작업 트리 clean.
- EMAIL 3B-3A `18a6e14`와 Google 3B-3B `00c2e4d` 구현이 커밋됐음을 확인했다.
- AGENTS.md, Task 006 최종 설계, 계정·EMAIL/Google 신원·공통 대표 이메일·OTP·세션 문서와 선행 검증 보고서를 확인했다.
- 사용자가 Google Console의 LOGIN/CONTACT/LINK 세 localhost 콜백 등록과 전용 이메일·미연결 Google 신원 준비를 확인했다.
- 기존 실제 EMAIL·Google 계정 두 UUID는 변경 금지 보호 대상으로 고정했다. 테스트 대상은 이들과 별개의 계정이다.
- 실제 `.env`의 필요한 설정 존재만 확인하고 값을 출력하거나 수정하지 않았다.
- 일반 Google LOGIN 활성, `GOOGLE_REAUTH_ENABLED=false` 유지. 인증+CSRF를 갖춘 REAUTH 시작은
  503 GOOGLE_REAUTH_UNAVAILABLE로 거부됨을 확인했고 실제 Google 재인증은 실행하지 않았다.

## 2. 개발 PostgreSQL 백업·V7·V8

소유 라벨 `overload-local/postgres`를 확인한 기존 `overload-local-postgres-1`만 시작했다.
시작 전 중지 상태였으며 컨테이너·볼륨을 재생성하거나 다른 프로젝트 Docker 리소스에 접근하지 않았다.

사전 읽기 전용 기준은 V1~V6, 사용자 2명·identity 2개·운동 40개·휴식 설정 2개·대표 연락처 2개,
세션 3개와 속성 7개, OTP challenge 3개였다. 전용 테스트 UUID는 아직 없었다.

정상 서버 시작 전 39,851바이트 custom-format pg_dump를 확보해 Windows CurrentUser DPAPI로 암호화했다.
PGDMP 헤더와 암호화/복호화 왕복 일치를 확인했다. 실제 DB 복원은 수행하지 않았다.
백업은 Git 제외·현재 Windows 사용자 전용 ACL 폴더의
`build/identity-management-live-20261028/database-before.dump.dpapi`에 보존했다.
선행 암호화 백업의 기존 두 사용자 ID도 메모리 안에서만 확인해 보호 대상을 식별했다.

Java 25 local / 127.0.0.1:8080의 정상 서버 시작으로 V7·V8을 적용했다.
V1~V8 Flyway 체크섬이 소스와 모두 일치하고 기존 V1~V6 이력도 동일했다.
기존 열 기준으로 사용자·신원·운동·설정·대표 이메일·후보·세션/속성·OTP와 제한 행의 값/행 수가 보존됐다.
추가 NULL 열은 기존 값 변경과 구분했다. 마이그레이션 오류·repair·강제 재적용·우회는 없었다.

## 3. 임시 브라우저 절차와 승인

제품 및 프론트엔드 코드를 변경하지 않고 ignored build 아래 외부 static 화면과 loopback 승인·감사 도구를 만들었다.
익명 EMAIL 로그인은 공개 CSRF 페이지에서 비밀값 없는 고정 로컬 스크립트를 실행해 같은 origin의 임시 화면을 사용했다.
Google LINK는 같은 인증 세션의 CSRF를 포함한 POST form으로만 시작했다. GET 변경·CSRF 우회는 없다.

모든 실제 발송과 연결·해제·테스트 브라우저 로그아웃은 로컬 창에서 대상·목적을 확인하고 별도 승인받았다.
승인 창의 기본 선택은 거절이다. 기존 실제 두 계정의 변경은 도구에서도 차단했다.
마지막 수단 검사는 실제 해제가 아닌 거절 확인임을 표시했다.
OTP는 password 입력란에서 기존 API로 직접 전달했고 저장/출력하지 않았다.
Google 계정 선택·인증·동의는 사용자가 직접 수행했다.

익명 임시 화면, GET/POST Google LINK 및 EMAIL LINK/Google UNLINK는 401이었다.
인증된 독립 요청은 CSRF GET 200, CSRF 없는 비파괴 POST 403, 정상 CSRF가 미지원 메서드의 405까지 통과함을 확인했다.
세션 자동 정리를 중지해 기존 세션을 임의 삭제하지 않았고, 접근/인증 상세 로그는 비활성화했다.

## 4. EMAIL LINK·UNLINK 및 새 로그인

| 실제 절차 | 결과 |
|---|---|
| 전용 EMAIL 최초 OTP LOGIN | 요청 202·검증 204, 새 전용 UUID·운동 20개·설정 1개·VERIFIED 대표 이메일 생성 |
| Google 연결 후 EMAIL UNLINK | 별도 승인, 204, Google 수단과 대표 이메일 보존 |
| 대표 이메일 EMAIL LINK | 별도 승인, 201 LINKED, 같은 사용자에 EMAIL 신원 1개 |
| 같은 EMAIL LINK 반복 | 별도 승인, 200 ALREADY_LINKED, 중복 행 없음 |
| 독립 Google SSO 세션에서 EMAIL LINK | 별도 승인, 403 EMAIL_OWNERSHIP_PROOF_REQUIRED |
| 재인증 후 EMAIL UNLINK·재연결 | 각각 204·201, 원래 사용자/대표 연락처 보존 |
| Google 해제 후 마지막 EMAIL UNLINK | 409 LAST_LOGIN_IDENTITY, EMAIL 보존 |
| 재연결한 EMAIL로 새 OTP LOGIN | 별도 발송·검증 승인, 202·204, 기존 전용 UUID 재사용 |

연결·해제 중 userId·개인 운동 20개·설정·대표 이메일은 그대로였다.
해당 작업은 세션 ID·metadata·이메일 proof·JDBC CSRF 속성의 직렬화 값도 바꾸지 않았다.
사용자 소유 EMAIL을 다른 실제 계정으로 이전하거나 병합하지 않았다.

## 5. Google LINK·LOGIN·UNLINK 및 충돌

전용 EMAIL 세션에서 로컬 승인 후 미연결 테스트 Google을 선택했다.
공식 OIDC 콜백 후 같은 UUID에 email+google이 저장됐고 새 사용자/seed는 생성되지 않았다.
원래 세션·로그인 시작·90일 만료·재인증 시각·이메일 proof·대표 연락처·개인 데이터도 유지됐다.

쿠키가 분리된 다른 브라우저에서 연결한 Google로 일반 LOGIN을 수행해 같은 UUID가 복원됐다.
이 일반 SSO 세션에는 이메일 소유 proof가 없었고, EMAIL LINK 시도는 403으로 거부됐다.
그 새 테스트 브라우저만 별도 승인으로 로그아웃했으며 원래 EMAIL 세션은 유지됐다.

소유권 충돌은 원본 전용 계정에서 별도 Google LINK를 승인하고 이미 다른 실제 계정이 소유한 Google을 선택했다.
사용자가 응답 본문 `IDENTITY_ALREADY_OWNED`를 확인했다. 모든 identity 소유권과 사용자 행이 불변이었다.
브라우저에 HTTP 숫자는 표시되지 않았으므로 해당 콜백의 409를 별도 실측했다고 주장하지 않는다.
구현의 409 IdentityChangeFailure 및 응답 status 전달 계약을 확인했고, 상태 확인을 위해 OAuth를 재실행하지 않았다.

| 후속 실제 절차 | 결과 |
|---|---|
| EMAIL을 해제해 Google만 남긴 뒤 Google UNLINK | 별도 거절 검사 승인, 409 LAST_LOGIN_IDENTITY |
| EMAIL 재연결 후 Google UNLINK | 대상/대안 재확인·별도 승인, 204, Google 해제 세대 1 저장 |
| 해제한 Google로 익명 창에서 새 LOGIN | 사용자가 새 테스트 가입 가능성에 동의하고 진행, 기존 EMAIL UUID와 다른 새 테스트 UUID 생성 |

마지막 새 Google 계정은 Google identity만 가지고 운동 20개·설정 1개를 새로 생성했다.
대표 이메일은 자동 생성하지 않았고 기존 전용 EMAIL 계정의 연락처·개인 데이터와 병합하지 않았다.
해제한 신원으로 새로 로그인한 principal이 이전 전용 UUID가 아님을 확인했다.
이미 인증된 모든 기기의 세션을 UNLINK가 전역 로그아웃시키는 계약은 아니며,
이번 차단 확인은 해제 후의 새 LOGIN을 기준으로 한다.

## 6. 실제 Resend 메일·증명·세션

| 승인된 목적 | 실제 발송 | 수신/검증 |
|---|---:|---|
| 최초 전용 EMAIL LOGIN | 1통 | 사용자 수신 확인, 202·204 |
| 10분 경과 후 EMAIL REAUTH | 1통 | 사용자 수신 확인, 202·204 |
| EMAIL 재연결 후 새 LOGIN | 1통 | 사용자 수신 확인, 202·204 |
| 합계 | **3통** | challenge 3개 모두 CONSUMED, accepted/consumed 시각 존재, verifier 영점화 |

각 발송은 로컬에서 정확한 수신처·목적·추가 1통을 별도 승인한 요청이다.
자동 재발송·다른 임의 주소 발송은 없었다. Resend 수락은 기존 sender 검증을 통과한 202와 accepted DB 상태,
실제 전달은 사용자 수신 및 OTP 성공으로 확인했다. 대시보드 provider message ID는 수집하지 않았다.

EMAIL_LOGIN proof는 현재 사용자·실제 sessionId·VERIFIED 대표 연락처·주소 해시·OTP 검증 시각에 바인딩돼 있었다.
일반 Google SSO의 최근 시각만으로 이 증명을 만들지 않았다.
첫 proof가 10분을 지나 만료됐음을 확인하고 EMAIL_REAUTH를 요구했으며 시각을 임의 연장하지 않았다.
CONTACT_ENROLL 증명의 실제 발송/등록은 선행 Common Email LIVE 결과이며 이번에 재실행하지 않았다.

EMAIL_REAUTH 성공 시 새 세션 PK/ID·EMAIL_REAUTH proof를 확인했다.
옛 세션은 DB에서 삭제되고 독립 옛 쿠키 요청은 401, 새 세션은 200이었다.
기존 userId·loginStartedAt·absoluteExpiresAt는 그대로이며 reauthenticatedAt만 갱신됐다.
CSRF 직렬화 값이 교체됐고 브라우저 옛 CSRF는 403, 새 CSRF는 비파괴 미지원 메서드의 405까지 통과했다.
마지막 새 EMAIL LOGIN에서도 옛 CSRF 403·새 CSRF 405를 확인했다.
새 LOGIN은 별도 로그인 시작/90일 기한을 만들며, 기존 세션의 재인증으로 기한을 연장한 것과 구분한다.

## 7. 최종 DB·재시작 보존 검증

| 항목 | 결과 |
|---|---|
| 기존 실제 EMAIL·Google 계정 | 사용자·identity·운동·설정·대표 연락처·후보 및 기존 OTP challenge 값 불변 |
| 시작 시 존재한 세션 3개/속성 7개 | 모든 기존 행·속성 bytes 보존 |
| 최종 전체 사용자·identity | 각각 4개: 기존 실제 2계정 + 명시적으로 만든 전용 테스트 2계정 |
| 최종 운동·설정·대표 연락처 | 80개·4개·3개 |
| 원래 전용 EMAIL 계정 | EMAIL만 유지, 원래 UUID·운동 20개·설정·대표 이메일 보존 |
| 해제 후 새 Google 테스트 계정 | 별도 UUID, GOOGLE만 존재, 자체 운동 20개·설정, 대표 이메일 없음 |
| 최종 두 테스트 세션 | 각각 독립 HTTP 두 번 200, 해당 principal 일치, 최초 로그인+90일 |
| 증명 구분 | EMAIL 세션은 EMAIL_LOGIN proof, Google SSO 세션은 proof 없음 |
| OAuth 장기 저장 | 최종 테스트 세션에 access/refresh token·OidcUser 클래스 및 Google Pending 속성 없음 |

모든 브라우저 절차 완료 후 자체 서버만 재시작했다.
같은 JDBC 세션 ID와 저장된 세션 속성 bytes로 인증이 다시 복원됐다.
사용자·identity·운동·설정·대표 연락처·OTP·Flyway 이력도 재시작 전후 동일했다.
실제 계정·세션을 바꾸거나 신규 OTP를 발송해 재시작 복원을 우회하지 않았다.

## 8. 발견한 문제·해결 및 검증 한계

제품 결함은 발견하지 않았다. 임시 검증 도구 문제는 다음과 같이 해결했다.

- 승인 창이 대기 중인데 화면이 ‘준비 중’으로 남았다. 승인 전 발송이 없음을 DB로 확인한 뒤 로컬 승인 창을 안내했고,
  임시 화면에 승인 대기/Alt+Tab 안내를 추가했다.
- 최초 OTP 로그인 204 뒤 감사 도구의 `http` 함수가 Python 모듈 이름을 가려 AttributeError가 났다.
  임시 함수 이름을 수정하고 승인/관측 기록을 보존해 도구만 재시작했다.
  제품 서버 재시작·메일 재발송·OTP 재검증 없이 독립 HTTP에서 이미 성공한 로그인을 확인했다.
  그 최초 로그인 직후 브라우저 CSRF 검사는 중단됐으므로 당시 결과를 성공으로 주장하지 않는다.
  이후 REAUTH와 최종 새 LOGIN에서는 옛/새 CSRF를 실제 검증했다.
- 로그인된 EMAIL 창에서 익명 Google LOGIN 버튼을 누른 시도는 외부 요청 전에 중단됐다.
  안내를 보완하고 익명 상태를 확인한 다른 창에서 사용자가 직접 진행했다.

이번 LIVE는 자동화 전체를 재실행하지 않았다. 선행 364개 통과와 실제 검증을 혼동하지 않는다.
동시성·롤백·다른 세션의 proof 탈취/재사용·OTP 오답/재전송 경계 전체를 실제 외부 서비스로 반복하지 않았다.
V7 legacy FK 퇴역과 V8 CONTACT 후보 퇴역은 이번 전용 계정에 해당 과거 참조가 없어 LIVE에서 유발하지 않았고,
선행 PostgreSQL 자동화 범위로 구분한다. 운동 도메인 API별 IDOR 시도는 이번에 수행하지 않았다.
Google REAUTH·새 CONTACT 등록·대표 이메일 변경/복구·운영 및 iPhone HTTPS 검증은 미실행이다.

## 9. 테스트 계정 및 환경 정리

사용자가 승인한 테스트 데이터는 삭제하지 않고 보존했다.
원래 전용 EMAIL 계정은 EMAIL-only, 해제한 Google의 새 테스트 계정은 별도 GOOGLE-only 상태다.
이 상태를 임의로 다시 병합·연결하거나 기존 실제 계정으로 옮기지 않았다.

최초 서버 PID 142896, 수정 전 도구 PID 143132, 수정 후 도구 PID 147172,
재시작 서버 PID 147768은 모두 종료했다. 8080/8091 리스너가 없음을 확인했다.
Overload PostgreSQL도 시작 전의 중지 상태로 복구했다. 컨테이너·볼륨·개발 DB 데이터는 보존했다.
실메일 활성은 종료한 테스트 프로세스에만 적용했으므로 지속 설정을 남기지 않았다.
실제 `.env`·서버 HEAD·프론트엔드 HEAD는 시작 시점과 같다.

임시 화면·도구·비공개 스냅샷·프로세스 파일·로그는 정리하고, 위 암호화 사전 백업만 남긴다.
서버/도구 로그는 검증 시 0바이트였다. 비밀번호·키·OAuth code/state·토큰·쿠키·OTP·주소·sub·개인 UUID는 보고서에 넣지 않았다.

## 10. 변경 파일 및 Git 상태

추적 대상 변경은 새 보고서 `docs/tasks/task-007-1c-3b-live-validation-report.md` 한 개뿐이다.
제품 구현·V1~V8·Task 006·선행 LIVE 보고서·프론트엔드·실제 환경 파일은 수정하지 않았다.
최종 Git 상태는 main / 기존 HEAD 유지, 이 보고서만 untracked다.
Git staging·Commit·Push·운영 배포는 수행하지 않았다.
