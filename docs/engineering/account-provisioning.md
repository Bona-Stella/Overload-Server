# 계정 프로비저닝·운영 초기값 (Task 007-1B)

현재는 **인증 검증 후 호출할 서버 내부 경로**만 구현되어 있다.
`AccountProvisioningService.provision(VerifiedIdentity, nickname)`은 계정 생성·재진입을 처리한다.
이메일 OTP 검증·로그인 접점은 [007-1C-2](email-otp.md)에 구현했다. Google 검증·프론트 연동은 후속 범위다.
공개 계정 생성 HTTP API, 테스트용 HTTP 우회 경로, 앱 시작 시 임의 계정/seed 생성은 없다.
007-1C-1의 보안·세션 기반과 검증 후 내부 호출 접점은 [보안·세션 문서](security-session.md)를 따른다.

## 신원 입력 계약

`VerifiedIdentity`는 요청 DTO나 인증 증명이 아니다. 007-1C의 신뢰할 수 있는 검증 코드가
검증을 마친 결과만 생성·전달해야 한다. 생성자의 형식 검사만으로 로그인 성공을 판단하지 않는다.

| 제공자 | issuer / subject |
|---|---|
| google | 검증된 `iss`의 `accounts.google.com` / `https://accounts.google.com`을 `https://accounts.google.com`으로 통일. 검증된 `sub`는 대소문자 포함 그대로 유지 |
| email | 고정 issuer `overload:email`. 검증 주소의 도메인만 Locale.ROOT 소문자화, 로컬 부분의 대소문자·점·플러스 태그는 보존 |

신원은 `(provider, issuer, subject)` 전체로 식별한다. Google 표시 이메일을 저장·비교하거나
주소 일치를 이유로 계정을 연결/병합하지 않는다. 이메일의 앞뒤 공백을 조용히 제거하거나
Gmail 점·플러스 별칭을 합치지 않는다. 입력은 비어 있거나 공백·제어문자를 포함할 수 없다.
이메일 형식 검사는 내부 계약의 최소 모양 검사이며, 완전한 주소 검증/OTP 구현이 아니다.
007-1C는 같은 주소 처리 규칙을 OTP 발급·검증·신원 조회에 일관되게 적용해야 한다.
IDN·국제화 주소 등 미구현 전달/검증 규칙을 이 단계에서 지원한다고 간주하지 않는다.
신원 객체의 `toString()`은 실제 subject를 노출하지 않는다.

## 원자성·동시성

1. 새로운 READ COMMITTED 트랜잭션에서 신원으로 계정을 찾는다. 재진입 시 사용자 행을
   `FOR UPDATE`로 잠그고 현재 UUID·세대·닉네임을 반환한다. 기존 종목/설정/닉네임을 덮어쓰지 않는다.
2. 신원이 없으면 서버 UUID로 users와 auth_identity를 삽입한다.
3. `auth_identities_credential_unique`를 지정한 `ON CONFLICT DO NOTHING`으로 동일 신원의
   동시 생성 경쟁을 처리한다. 다른 PK/FK/CHECK 오류를 성공으로 바꾸지 않는다.
4. 신원 삽입 성공 시 개인 종목 20개·설정 1행을 같은 트랜잭션에서 삽입하고 커밋한다.
   어느 단계에서든 SQL 오류가 나면 전체를 롤백한다.
5. 경쟁에서 신원 삽입이 0행이면 그 요청의 users도 롤백한 후, 새 트랜잭션에서 승자의 계정을 읽는다.
   승자 계정이 그 사이 제거됐다면 오류를 전달하며 무한 재시도/자동 계정 복구는 하지 않는다.

서비스는 `REQUIRES_NEW`로 자기 트랜잭션을 소유한다. 호출자의 다른 쓰기와 함께 커밋되는 계약이 아니다.
007-1C에서 인증 검증 후 **외부 쓰기 트랜잭션을 잡지 않은 경로**로 호출한다.
외부 트랜잭션이 사용자 잠금을 보유한 상태로 호출하거나 연결 풀을 고갈시키는 중첩 호출을 만들지 않는다.
기존 신원에 대한 별도 계정 연결/해제 기능은 구현하지 않았다.

## V2 스키마와 소유권·세대

V1은 수정하지 않는다. `V2__create_account_provisioning.sql`은 다음만 추가한다:

- `users`: `(id, data_generation)` 복합 UNIQUE. 기존 행이나 세대·닉네임·시각을 변경하지 않는다.
- `auth_identities`: UUID PK, users FK, google/email 제공자와 고정 issuer 조합 CHECK,
  issuer/subject NOT NULL·공백만 있는 값 거부, credential 복합 UNIQUE, 생성 시각.
  계정 유지와 함께 존속하므로 운동 데이터 세대를 복제하지 않는다. 사용자별 참조 탐색을 위한 user_id 인덱스가 있다.
- `exercise_definitions`: UUID PK, user_id·data_generation·표시명·생성/변경 시각.
  현재 `(user_id, data_generation)`를 users에 복합 FK로 참조한다.
  이름은 비어 있을 수 없지만 **UNIQUE는 없다**. 기본 목록은 웨이트 정의만 생성하며 유산소 타입을 혼합하지 않는다.
- `exercise_definitions`의 `(user_id, data_generation, id)` 복합 UNIQUE는
  종목을 참조하는 관계가 소유자·세대를 함께 검증할 수 있는 키다. 사용자·세대별 탐색도 이 인덱스를 사용한다.
  현재 운영 스키마에 루틴 등 자식 테이블을 미리 만들지는 않는다.
- `user_workout_settings`: user_id PK (계정당 1행), 현재 user_id·data_generation 복합 FK,
  휴식 세 필드 INTEGER NOT NULL CHECK > 0, 생성/변경 시각.
- UUID는 서버에서 새로 생성한다. 생성/변경 시각은 TIMESTAMPTZ와 CURRENT_TIMESTAMP 기본값이며,
  향후 편집 구현에서 updated_at을 명시적으로 갱신한다. 삭제/갱신 cascade는 없다.

FK는 잘못된 소유자·세대의 **관계 연결**을 거부한다. SQL에 임의의 유효한 user_id를 넣는 호출자의
권한까지 증명하지는 않는다. 현재 repository는 패키지 내부에 제한되어 있고 신원 조회·새 계정 seed만 처리한다.
향후 읽기/편집은 인증 principal의 user_id와 서버 세대를 조건으로 사용해야 하며 클라이언트 user_id를 신뢰하지 않는다.

전체 초기화는 미구현이다. 향후에는 사용자 행을 `SELECT ... FOR UPDATE`로 먼저 잠그고,
현재 FK가 가리키는 이전 운동 데이터·설정을 같은 트랜잭션에서 정리한 뒤 세대를 증가시키고 새 seed를 넣는다.
현재의 즉시 검사 FK는 자식 행을 둔 채 세대만 바꾸는 작업을 거부한다.
신원·닉네임은 유지한다. 기존 신원 재진입은 seed를 다시 실행하지 않으므로 초기화와 새 가입 처리를 혼동하지 않는다.

## 승인된 운영 초기값

유일한 운영 정의는 `AccountSeed`다. 사용자별로 목록을 복사하며 종목마다 새 UUID를 부여한다.
같은 종목명도 계정별 UUID가 다르고, 개인 추가 종목에 같은 이름이 있어도 허용한다.

| 분류 (문서 표시용) | 종목 |
|---|---|
| 가슴 4 | 벤치프레스, 인클라인 덤벨 프레스, 체스트 프레스, 케이블 플라이 |
| 등 4 | 랫풀다운, 시티드 케이블 로우, 바벨 로우, 풀업 |
| 하체 5 | 스쿼트, 레그 프레스, 레그 익스텐션, 레그 컬, 루마니안 데드리프트 |
| 어깨 3 | 숄더 프레스, 사이드 레터럴 레이즈, 리어 델트 플라이 |
| 팔 4 | 바벨 컬, 덤벨 컬, 케이블 푸시다운, 오버헤드 트라이셉스 익스텐션 |

`WorkoutSettings`의 목표 반복 **상한** 구간은 다음과 같다:

| 상한 | 필드 | 초기 초 |
|---|---|---|
| 1~10 | rest_low_seconds | 180 |
| 11~12 | rest_medium_seconds | 120 |
| 13 이상 | rest_high_seconds | 90 |

사용자가 편집할 앱 초기값이며 운동 처방이 아니다. 향후 초기화도 이 정의를 재사용하고 UUID를 새로 발급해야 한다.
루틴·요일 일정·세션·실제 수행·승인 값/사건·메모는 생성하지 않으며 테이블도 아직 없다.
기존 localStorage·`createInitialState()`·개발 목업은 읽거나 이관하지 않는다.

## 검증과 후속 연동

[로컬 PostgreSQL 문서](local-postgresql.md)의 `test`, `integrationTest`, `build` 명령으로 검증한다.
테스트는 일회성 실제 PostgreSQL을 사용한다. 프로비저닝 테스트의 SQL 실패 트리거·경쟁 제어 잠금·
소유권 FK 검사용 참조 테이블은 **테스트 DB에만** 만들며 애플리케이션과 버전 SQL에는 포함하지 않는다.

프론트 읽기 확인 결과 현재 `features/workout/model.ts:restSeconds`는 **≤8 / 9~12 / ≥13**이고
`features/settings/SettingsScreen.tsx`의 표시 구간도 같다. 이번 승인 정책인 **≤10 / 11~12 / ≥13**과 다르다.
설정값 180/120/90이 같다는 사실을 정책 근거로 삼지 않았고 승인된 운영값을 별도로 정의했다.
이번 작업은 프론트 수정 금지이므로 후속 연동에서 구간·라벨을 함께 정리해야 한다.

## 다음 단계 전달 사항

- `VerifiedIdentity` 생성은 실제 인증 성공을 뜻하지 않는다. 007-1C는 Google 토큰 또는 이메일 소유 증명을
  검증한 신원만 프로비저닝 서비스에 전달해야 한다.
- Google과 이메일 신원은 이메일 주소가 같아도 자동 연결·병합하지 않는다.
- 프론트의 목표 반복 상한 **9~10회**는 현재 medium(120초) 구간이다. 후속 연동에서 서버의
  low(초기 180초) 구간에 맞추고 휴식 함수와 설정 화면 라벨을 함께 변경해야 한다.
  이 전달 메모는 007-1C 및 프론트 구현을 시작한 것이 아니다.

## 기술 근거

- [Google issuer 및 sub 규약](https://developers.google.com/identity/openid-connect/openid-connect#validatinganidtoken)
- [PostgreSQL INSERT / ON CONFLICT](https://www.postgresql.org/docs/18/sql-insert.html)
- [Spring 트랜잭션 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
