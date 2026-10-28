# Task 007-1C-3B-COMMON-EMAIL-LIVE 검증 보고서

상태: **실제 Google-contact·Resend 발송/수신·OTP 최초 등록·JDBC 세션 회전 및 복원 검증 완료**.
작성일: 2026-10-28. 일반 REAUTH 실메일 발송은 이번에 수행하지 않았다.

## 시작 상태와 승인 범위

- HEAD `46fcb69139b2b01425f0dbefe23be6c4b5b3350f`, main, 시작 시 작업 트리 clean.
- 직전 커밋 `feat(auth): unify verified email reauthentication`은 선행 3B-2의 기본 비활성 Google 재인증과
  공통 대표 이메일, 설계·검증 보고서를 포함한 46개 파일이다. staged 46개와 committed 46개를 확인했다.
- 원격 대비 기존 ahead 2이며 Push는 하지 않았다. 이번 LIVE의 Commit·Push도 하지 않는다.
- 사용자에게 기존 LOGIN 및 신규 CONTACT의 localhost:8080 콜백 URI가 모두 등록됐음을 확인받았다.
- 실제 `.env` 존재를 확인했고 내용·비밀값을 출력하거나 수정하지 않았다.

## 개발 DB 사전 점검과 V5·V6

기존 소유 라벨 `overload-local/postgres`를 확인한 컨테이너만 시작했다. 다른 프로젝트 Docker 리소스에는 접근하지 않았다.
시작 전 V1~V4, 사용자 2명, identity 2개, 운동 종목 40개, 휴식 설정 2개, 세션 2개와 속성 6개를 확인했다.
행의 UUID·주소·sub·세션/쿠키는 출력하지 않았다.

정상 서버 시작 전에 custom-format pg_dump 28,926바이트를 확보하고 Windows CurrentUser DPAPI로 암호화했다.
백업·읽기 전용 비교 기준은 사용자 전용 ACL을 설정한 Git 제외 build 하위 폴더에 보관했다.
백업에 인증 데이터가 포함될 수 있으므로 원문을 로그·보고서·Git에 넣지 않았다. 복원이나 DB 초기화는 하지 않았다.

Java 25 / local / 127.0.0.1:8080 정상 서버 시작으로 V5·V6를 적용했다. Flyway 이력은 1~6이며 기존
체크섬·이력이 보존됐다. 기존 사용자·identity·운동·휴식·세션/속성·OTP 행/제한 상태의 기존 값과 행 수가 유지됐다.
V6의 보존된 OTP 근거로 VERIFIED 대표 연락처 1개가 추가됐다. 이 초기 backfill은 실제 Google 연락처 등록 성공과 구분한다.

비교 도구의 초기 전체-row JSON 비교가 V5/V6의 추가 NULL 바인딩 열을 값 변경으로 판단했다.
기존 열·행 수 기준의 정확한 비교로 보완하여 모두 불변임을 확인했다. 마이그레이션 우회·repair·강제 재적용은 없다.

## 임시 테스트 환경

- Health GET 200, status UP.
- 일반 Google LOGIN만 활성화하고 GOOGLE_REAUTH_ENABLED=false를 명시했다.
- OTP 발송 설정은 이 테스트 프로세스에서만 활성화하며 승인 이전에는 발송 API를 호출하지 않는다.
- 기존 세션의 자동 정리는 테스트 프로세스에서 중지했다. 제품 코드나 실제 `.env`는 변경하지 않았다.
- 인증정보·요청 상세·접근 로그를 비활성화했다. 임시 HTML은 외부 static 위치로 제공하며 인증·CSRF 정책을 유지한다.
- Google-contact는 기존 POST 시작과 같은 브라우저 세션의 identityId/CSRF로 진행한다. GET 변경이나 CSRF 우회는 없다.
- 임시 화면은 원문 이메일·UUID·토큰·코드를 결과에 표시하지 않고 OTP 입력은 password 필드로만 받는다.
- 서버 저장 후보 주소는 별도 로컬 확인 창에서만 표시하고 명시 승인 후 단 한 번 발송하도록 준비했다.
  자동 재발송 및 일반 REAUTH 추가 메일 발송은 하지 않는다.

제한 권한의 프로세스 조회가 서버를 보지 못해 일시적으로 종료로 오인했다. 확장 권한 읽기 조회에서 기존 PID와
8080/8091 리스너가 정상임을 확인했으며 재시작하지 않았다. 제품 장애나 데이터 변경은 아니었다.

## 실제 인증·발송·등록 결과

| 항목 | 현재 결과 |
|---|---|
| 현재 Google 인증 | 사용자 진행 완료 확인, 기존 Google 사용자 일치 및 독립 HTTP 200. 아래 LOGIN 범위 구분 참고 |
| 실제 Google-contact / 후보 | 사용자 완료 확인, 기존 Google issuer/sub와 후보·원본 세션 바인딩 DB 확인 |
| 수신 주소 로컬 확인·명시 승인 | 로컬 확인 창에서 해당 주소의 최초 등록 메일 1통 발송 승인 |
| Resend 최초 등록 OTP 발송 | 승인한 후보에 요청 1회, HTTP 202 및 DB 수락·CONSUMED 상태 확인 |
| 실제 메일 수신 | 사용자가 메일 수신 및 OTP 등록 검증 완료 확인 |
| OTP 소비 / VERIFIED 등록 | HTTP 204, Google 사용자당 VERIFIED 대표 연락처 1개, 등록 OTP 1개 단일 소비 |
| 실제 세션 회전·독립 HTTP 복원 | 새 PK/ID, 옛 행 폐기 및 401, 새 세션 독립 HTTP 두 번 모두 200 |
| Google 사용자의 20개 종목/설정·기존 EMAIL 계정 보존 | 등록 후에도 기존 사용자/identity/운동/휴식 전체 값 불변, 기존 EMAIL 세션/속성 보존 |
| 일반 REAUTH 실메일 | 범위 밖, 미실행 |

후보는 기존 Google identity 소유자/issuer/sub와 일치했고, JDBC SessionMetadata 전체를 사용한
세션 바인딩 해시가 정확히 하나의 현재 인증 세션과 일치했다. 원문 값은 출력하지 않았다.
대표 연락처가 아직 없는 상태에서 실제 Google-contact가 검증된 email/email_verified 후보를 만들었음을 확인했다.
Google 테스트 세션의 독립 GET session은 200, 임시 화면은 200, 비활성 Google REAUTH callback은 503이었다.
이 확인에서 Google 인증을 재실행하거나 Resend 메일을 요청하지 않았다.

## 발송 승인·OTP 입력과 실제 수락 증거

서버 저장 후보의 기존 Google identity와 현재 세션 바인딩을 먼저 확인했다.
원문 수신 주소는 사용자 PC의 로컬 확인 창에만 표시했고 기본 선택은 거절로 설정했다.
사용자가 해당 주소의 최초 등록 메일 **1통** 발송을 명시 승인한 뒤 같은 인증 브라우저의 2번 버튼으로 요청했다.
요청 본문은 candidateId만 사용하고 임의 주소를 받지 않았다. 자동 재시도/새 후보/다른 수신처 발송은 없었다.

브라우저는 발송 전에 단일 요청 표시를 저장해 중복 클릭·새로고침 재발송을 차단했다.
기존 Resend sender의 수락 검증을 통과한 HTTP 202와 PostgreSQL 등록 challenge 1개 및 수락/소비 상태를 확인했다.
사용자가 실제 수신을 확인하고 password 입력란으로 OTP를 입력해 204를 받았다.
OTP는 채팅·콘솔·명령 인자·로그·보고서로 전달하지 않았고, 검사 후 입력값을 비웠다.
Resend 대시보드 로그나 원문 provider message ID는 수집하지 않았다.

브라우저의 임시 결과 수집기는 bool/HTTP 상태만 허용했다. 관측 결과:

| 단계 | 결과 |
|---|---|
| 최초 등록 OTP request | 202 |
| 최초 OTP verify | 204 |
| 옛 CSRF로 후속 verify | 403 |
| 새 CSRF로 같은 증명 중복 verify | 401, 재등록 거부 |
| 새 브라우저 session 조회 | 200 |
| 대표 연락처 상태 | VERIFIED |

추가 독립 요청에서 새 CSRF로 소비된 후보의 registration/request를 호출해 401 OTP_INVALID를 확인했다.
이는 CSRF 검사를 통과한 뒤 후보 소비/바인딩 검사에서 거부된 것이며 추가 challenge나 메일은 생성되지 않았다.
CONTACT_ENROLL 행은 최종에도 1개이고 CONSUMED 및 verifier 영점화, 후보 consumed_at을 확인했다.
일반 REAUTH request나 LOGIN OTP 발송 API는 호출하지 않았다.

## PostgreSQL 계정·데이터·세션 검증

| 검증 | 결과 |
|---|---|
| 기존 users/identity 전체 행 | 사용자 2명·identity 2개, UUID 및 모든 값 보존 |
| 자동 병합/연결·해제 | 없음, Google/EMAIL 계정 분리 유지 |
| 기존 개인 운동/휴식 전체 행 | 40개 종목·설정 2개, 기존 값 보존 |
| 검증 대상 Google 사용자 seed | 종목 20개·휴식 설정 1개 보존 |
| VERIFIED 연락처 | Google 사용자에 1개 최초 등록, 기존 EMAIL backfill 1개를 포함해 총 2개 |
| 기존 EMAIL 계정 세션 | 기존 세션 행과 모든 속성 값 보존, 로그아웃하지 않음 |
| 검증 원본 Google 세션 | 새 primary key/session ID로 교체, 옛 ID의 DB 행 제거 및 HTTP 401 |
| 다른 기존 세션 | 행과 속성 전체 값 보존 |
| 독립 새 HTTP 클라이언트 | 두 차례 session 200, DB의 동일 사용자와 응답 UUID 일치 |
| 독립 연락처 조회 | 200 VERIFIED, 마스킹 주소 계약 유지 |
| userId/loginStartedAt/absoluteExpiresAt | 모두 원본 값 보존 |
| 실제 만료 기한 | 최초 로그인 시각과 정확히 90×24시간 차이 |
| reauthenticatedAt | 실제 OTP 소비로 증가, 검증 시 최근 10분 안 |
| 독립 HTTP 후 metadata | 전체 값 불변, 90일 기한 연장 없음 |
| CSRF | 옛 토큰 403, 새 backing token 상이, 새 토큰 검사 통과 확인 |

원본 Google 세션은 이번 LIVE 전부터 존재했다. 따라서 모든 기존 세션을 무조건 보존하는 비교는
의도적으로 회전·폐기한 대상 때문에 false가 된다. 회전 대상은 삭제를 검증하고, 나머지 기존 세션은 전체 값을
보존하는 것으로 정확히 구분했다. 데이터 손실이나 EMAIL 세션 변경이 아니었다.

세션 ID/쿠키/UUID/시각 원문은 감사 도구의 메모리와 Windows 사용자 암호화 임시 비교 자료에서만 취급했다.
독립 HTTP는 DB에서 검증된 이번 Google 세션만 사용했으며 기존 EMAIL 세션에는 요청하지 않았다.

## 일반 LOGIN과 미검증 범위

Google LOGIN 활성과 GOOGLE_REAUTH_ENABLED=false 조합은 그대로다. 인증된 세션에서 일반 LOGIN 시작은
기존 계약대로 409 LOGOUT_REQUIRED다. 별도 익명 로컬 시작 요청은 공식 Google authorization과 기존 LOGIN callback으로
302를 반환함을 확인했고 리디렉션은 따라가지 않았다. 추가 Google 인증은 수행하지 않았다.

이번 원본이 기존 인증 세션이므로 새로운 일반 LOGIN 콜백 성공을 독립 관측했다고 주장하지 않는다.
일반 Google LOGIN의 실제 콜백 검증은 선행 [3A-LIVE 보고서](task-007-1c-3a-live-validation-report.md)와 구분한다.
이번에는 **새 Google-contact 공식 OIDC 확인, 이메일 후보 및 실제 OTP 등록**을 검증했다.
Google auth_time 강제 재인증이나 Google REAUTH 활성화는 하지 않았다.

90일을 실제 기다리거나 LIVE 시계를 이동하지 않았다. 최근 10분은 저장 시각과 현재 시각으로 확인했으며,
정확한 만료/10분 경계·경합·장애 전체 검증은 기존 [271개 자동화 보고서](task-007-1c-3b-common-email-validation-report.md)를 따른다.
이번에는 그 테스트를 재실행하지 않았다. 일반 REAUTH 이메일 실발송·수신은 **미검증**이며 추가 승인이 필요하다.
연락처 변경/복구·인증 수단 연결·해제·3B-3·프론트·운영/iPhone HTTPS는 범위 밖이다.

## 종료·보존·Git

- 검증용 Java 서버와 임시 결과 수집 Python 프로세스를 각각 PID/명령줄 소유 확인 후 종료했다.
  잔존 프로세스와 8080/8091 리스너는 0개다.
- 이번에 시작한 Overload postgres만 정상 stop으로 원래의 중지 상태로 돌렸다.
  컨테이너·볼륨·V5/V6·기존 데이터·새 대표 이메일·세션은 보존했다. 다른 프로젝트 Docker 리소스에 접근하지 않았다.
- 실행 프로세스의 Google/OTP 임시 활성화는 종료로 해제됐다. 실제 `.env` 전체 해시와 비밀 설정은 불변이다.
- DPAPI 암호화 백업이 정상 PostgreSQL custom dump로 복호화되는지 확인했다. 복원은 하지 않았으며 안전한 로컬 백업은 유지한다.
  위치는 Git 제외 `build/common-email-live-20261028/database-before.dump.dpapi`이며 현재 Windows 사용자로만 복호화한다.
- 임시 감사 소스/클래스·HTML·승인 파일·로그·결과 수집기·비교 snapshot은 제거했다. 비공개 폴더에는 암호화 백업 1개만 남겼다.
- 제품/테스트 소스·마이그레이션·Task 006·프론트엔드는 변경하지 않았다. 발견된 제품 결함과 미해결 검증 오류는 없다.
- 최종 변경 파일은 이 LIVE 보고서 1개(untracked), staged 0개다. HEAD는
  `46fcb69139b2b01425f0dbefe23be6c4b5b3350f`, main, 기존 origin/main 대비 ahead 2를 유지한다.
  이번 LIVE에서 Commit·Push는 하지 않았다.
