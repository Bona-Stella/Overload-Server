# Task 009-1A — 최소 쓰기 기반·DB 계약 구현 보고서

- 작업일: 2026-10-30 (Asia/Seoul).
- 범위: 사용자 채택 v0.2의 최소 형식·additive DB·검증 기반. 명령 dispatcher/쓰기 HTTP API는 없다.
- 기준: [v0.2 및 1A 보완](task-009-0-write-domain-api-design-v0.2.md), Task 008의 기존 JDBC/JPA·HTTP·조회 계약.

## 1. 시작 상태와 보존

| 저장소 | 실제 브랜치 / HEAD | 시작 상태 |
|---|---|---|
| Backend | main / `10e1a0242c36d525230cdef5859bacbbcc44da6a` | 추적 파일 변경 없음, 009-0 v0.1/v0.2 문서만 untracked |
| Frontend | main / `28257838737dda0b24fd3d356cadd8c7ae757f36` | clean |

양쪽 AGENTS.md·기술별 규칙과 관련 구현을 확인했다. 하위 AGENTS.md는 발견되지 않았다. v0.1은 수정하지 않았으며 SHA-256은 `0A735BEE35BA476210C0A5576113CAFA0B0BB250E80BAAA05CECDCDDB31E9B0C`다. v0.2는 이번 정책·채택 상태·최종 상태 영수증 구조만 연관 개정했다. 시작 v0.2 SHA-256은 `52E67F8498EC706B04A93786BEB5611920B6ACB2336D94A4DB452855ACA8213B`다. 두 문서를 삭제하거나 staging하지 않았다.

## 2. 제품 정책 보완

- 루틴 기준 중량·반복 평가는 ordinal/ID 순서의 첫 N개 유효 수행만 사용한다. N개 이후 추가 세트의 다른 중량·오늘만 중량은 안내 자격을 박탈하지 않는다. 첫 N개 내부의 혼합/오늘만 제한은 유지하며 전체 수행 통계에는 추가분도 포함한다. 삭제·정정 후 같은 선택 규칙으로 재평가한다.
- 정상 온라인 입력·세트 완료·타이머·화면 이동에 불필요한 서버 ACK 대기를 넣지 않는다. 로컬 원자 저장 완료와 서버 동기화 상태를 분리하고 로컬 저장 실패는 성공으로 표시하지 않는다.
- 네트워크 불통과 iPhone PWA 백그라운드·잠금·화면 꺼짐·재실행을 각각 검증할 상황으로 명시했다. 이미 저장된 기록·시각 앵커 복원은 후속 필수 게이트다. 이번에 React/IDB/PWA 구현·실기기 성능 검증은 하지 않았다.
- N-09 확인 설정 기본 OFF는 확정 정책이다. 실제 설정 API와 자동 해결 동작은 미구현이다.

## 3. 실제 변경 파일

| 경로 (Backend 루트 기준) | 변경 |
|---|---|
| `src/main/resources/db/migration/V9__add_workout_write_foundation.sql` | 기존 두 테이블 확장, 최종 영수증·revision/feed 3개 테이블 |
| `src/main/java/com/overload/overload_server/exercise/ExerciseDefinition.java` | version/deletedAt/intentStamps 매핑 |
| `src/main/java/com/overload/overload_server/workoutsettings/UserWorkoutSettings.java` | version/intentStamps 매핑 |
| `src/main/java/com/overload/overload_server/workoutcommand/Counter.java`, `WeightKg.java` | 문자열 Counter와 정확한 BigDecimal 중량 |
| 같은 패키지 `CommandValues.java`, `IntentStamp.java` | UUID·UTC millisecond Instant·문자열 검증, stamp 비교 |
| 같은 패키지 `CommandEnvelope.java` | sealed typed payload·기대 version·fieldBases·해결 DTO |
| 같은 패키지 `CommandJson.java`, `WorkoutCommandCodec.java` | 엄격한 JSON 파서·제한된 JCS·fingerprint·stamp 디코딩 |
| 같은 패키지 `WriteContractViolation.java`, `WorkoutWriteGuard.java` | 내부 오류·인증 계정/현재 세대/user 잠금/새 ID 검증 |
| `src/test/java/com/overload/overload_server/workoutcommand/CommandValueTests.java`, `WorkoutCommandCodecTests.java` | 형식·canonicalization 단위 테스트 |
| 같은 테스트 패키지 `WorkoutWriteFoundationIntegrationTests.java`, `WorkoutWriteUpgradeIntegrationTests.java` | 격리 PostgreSQL 제약·혼합 tx·업그레이드 테스트 |
| 기존 테스트 `account/AccountProvisioningIntegrationTests.java`, `auth/email/EmailOtpIntegrationTests.java`, `database/PostgresMigrationTests.java`, `persistence/JpaJdbcCompatibilityIntegrationTests.java`, `security/SecuritySessionIntegrationTests.java` | V9 history·테이블 목록을 명시 확장. 기존 데이터 비교는 신규 metadata를 제외한 모든 기존 컬럼 유지 |
| `README.md`, `docs/tasks/task-009-0-write-domain-api-design-v0.2.md`, 이 보고서 | 구현 상태·정책·검증·후속 경계 |

V1~V8, build.gradle/의존성, 인증·계정·조회 제품 코드, transaction manager/Repository scan 설정은 수정하지 않았다. Frontend는 변경하지 않았다.

## 4. 동결한 데이터·명령 형식

### 4.1 수치·ID·stamp

| 형식 | 계약 |
|---|---|
| Counter | JSON 문자열, `0` 또는 선행 0 없는 양의 십진수. 범위 0..9223372036854775807. 부호·지수·공백·소수 금지. 증가 overflow는 거절 |
| WeightKg | canonical 비음수 decimal 문자열, 정수 최대14자리·소수 최대6자리. 0 허용. 선행 0·소수 후행 0·지수·부호 금지. BigDecimal 변환 전/후 반올림 없음 |
| 신규 ID/operationId | 소문자 canonical UUID v4, RFC variant 2. 기존 계정/리소스 참조 UUID는 재발급하지 않으며 기존 UUID version을 제한하지 않음 |
| occurredAt | `YYYY-MM-DDTHH:mm:ss.SSSZ`의 UTC millisecond. 기기 시각 보정/서버 수신시각 기반 우선순위 없음 |
| IntentStamp | `{occurredAt,originOperationId}`. 시각 비교 후 동일 millisecond이면 canonical UUID 문자열 사전순. Java UUID의 signed 비교를 사용하지 않음 |
| intent_stamps | 허용 의미 group→IntentStamp인 JSON object. 기존 `{}`는 미관측, fieldBases의 `baseStamp:null`과 대응 |

현재 두 도메인에는 중량 필드가 없으므로 쓰이지 않는 NUMERIC 컬럼을 추가하지 않았다. D-01의 NUMERIC(20,6)은 DTO와 격리 임시 테이블 왕복으로 검증했다. 실제 중량 테이블 도입 시 같은 형식과 유한 범위 CHECK를 적용해야 한다. PostgreSQL 자체 scale 변환은 반올림하므로, 모든 미래 쓰기는 **SQL 전달 전에** WeightKg 검증을 거쳐야 한다.

### 4.2 Envelope와 봉인

schema 1의 필수 키는 `commandSchemaVersion,operationId,expectedUserId,dataGeneration,type,expectedVersions,fieldBases,sessionControl,occurredAt,payload`다. 모르는 키·누락·임의 JSON merge를 거절한다. 현재 sessionControl은 null만 허용한다. 표현용 SessionControl DTO가 기기 proof 기능을 제공하는 것은 아니다.

| type (DTO만 존재) | payload / 전제 |
|---|---|
| CREATE_EXERCISE | `{id,name}`, expectedVersions/fieldBases 빈 배열 |
| RENAME_EXERCISE | `{exerciseId,name}`, 해당 EXERCISE version 1개, name fieldBase 1개 |
| UPDATE_WORKOUT_SETTINGS | `{changes:[{field,value}]}`, 해당 계정 settings version 1개, 실제 변경 필드별 fieldBase. 세 휴식 필드만, 양의 INTEGER |
| RESOLVE_WRITE_CONFLICT | `{originOperationId,originFingerprint,mode,choices}`, 새 operationId·현재 expectedVersions. 원래 fieldBases는 거절 영수증에 봉인되어 있으므로 바깥 fieldBases는 빈 배열 |

expectedVersions는 `resourceType/resourceId`, fieldBases와 choices는 `resourceType/resourceId/fieldKey`의 canonical 문자열 오름차순·중복 없음이다. settings의 resourceId는 expectedUserId다. AUTO choices는 빈 배열, USER_CHOICE는 비어 있지 않다. choice는 `{resourceType,resourceId,fieldKey,action,value}`이며 KEEP_CURRENT/APPLY_INCOMING의 value는 null, SET_VALUE는 해당 이름 문자열 또는 양의 휴식 INTEGER다. lifecycle 자동 해결은 받지 않는다.

1A의 field vocabulary는 EXERCISE `name,lifecycle`, WORKOUT_SETTINGS `restLowSeconds,restMediumSeconds,restHighSeconds`뿐이다. 이름/휴식 원본 명령은 payload가 실제 건드리는 group과 fieldBases가 정확히 일치해야 한다. 미래 resource enum은 키 형식용이며 미래 field/명령 수용을 의미하지 않는다. 원본 receipt 일치·해결 대상 정확성·실제 최신 stamp 선택은 1B 서비스 책임이다.

### 4.3 Fingerprint

`SHA-256(UTF8("overload-command-v1\n" + principalUserId + "\n" + canonicalCommandJSON))`의 소문자64 hex다. expectedUserId를 principal과 비교하고 전체 envelope를 hash한다. Request ID·쿠키·CSRF·서버 수신시각은 넣지 않는다.

기존 Jackson 3와 JDK로 **이 명령 schema에 허용한 JSON 부분집합**의 JCS 직렬화를 구현했다. 객체 key는 UTF-16 순서, 배열 순서는 유지, Unicode trim/NFC 변환 없음, RFC escaping 규칙을 사용한다. JSON number는 safe integer만 받는다. decimal/Counter는 문자열이므로 범용 IEEE754 부동소수점 직렬화 라이브러리가 필요하지 않다. `1.0`/지수 number·unsafe integer·중복 key·고립 surrogate·NUL은 거절한다. 범용 부동소수점 JCS 준수를 주장하지 않는다. [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html)

codec의 입력 제한은 UTF-8 256KiB, 깊이64, number token32자다. 제품 이름200자 제한은 추가하지 않았다. HTTP 크기 제한·413/422 매핑은 1B에서 연결해야 한다. 독립 mapper이므로 기존 HTTP 응답 직렬화에는 영향이 없다. 새 의존성은 추가하지 않았다.

## 5. V9 DB·트랜잭션 계약

| 대상 | 최소 구조·방어 |
|---|---|
| exercise_definitions | 기존 전 컬럼 유지. version BIGINT≥0 default0, deleted_at nullable, intent_stamps JSONB object default{} |
| user_workout_settings | 기존 PK/FK/휴식값 유지. version BIGINT≥0 default0, intent_stamps JSONB object default{} |
| workout_sync_state | PK(user_id,data_generation), users의 현재 owner/generation 복합 FK, committed_revision BIGINT≥0 default0 |
| sync_operations | PK(owner,generation,operationId), v4 CHECK, fingerprint64hex/schema1/type CHECK, owner/generation FK. 성공200 SUCCEEDED+scope에 바인딩한 ack_body와 제한된 거절409 REJECTED+code+typed intent_command만 수용 |
| 원본 해결 연결 | origin_operation_id/fingerprint는 둘 다 있거나 둘 다 null, 자기 참조 금지, 원본 operation에 owner/generation 복합 FK |
| workout_change_batches | PK(owner,generation,revision>0), UNIQUE(owner,generation,operationId), 비어 있지 않은 changes 배열, sync-state FK |
| receipt/feed 연결 | receipt의 change_revision과 batch의 revision/operationId를 양방향 deferred 복합 FK로 묶어 불완전·엇갈린 연결의 commit을 차단 |

거절 영수증은 VERSION_CONFLICT/CONFLICT_CONFIRMATION_REQUIRED만 허용한다. 성공/거절 JSON의 공통 scope·형식은 CHECK로 검증하되 전체 typed payload·resultVersions/changes 내부 참조·원본 fingerprint 일치까지 SQL CHECK가 검증하는 것은 아니다. 이들은 dispatcher의 typed 검증이 필요하다. 성공 no-op은 change_revision=null일 수 있다. DB가 revision의 단조 할당/최신 state 일치를 자동 실행하지 않으며, 1B가 user 락 아래 증가·batch/도메인/receipt를 동일 tx에 기록해야 한다.

**설계 단순화:** v0.2 최초안의 PROCESSING 예약 행을 제거했다. 최종 SUCCEEDED/REJECTED만 DB에 존재한다. 1B는 user FOR UPDATE → 같은 key 최종 receipt 조회 → 검증/도메인 적용 → 최종 receipt INSERT를 한 workout tx에서 실행한다. user 락과 PK를 함께 유지하고, PK 충돌/SQL 실패 시 전체 rollback한다. SELECT만으로 멱등성을 주장하지 않는다. 별도 pending registry/constraint trigger/독립 receipt tx는 없다. 영수증의 불변성·보존/no-TTL·replay는 1B 서비스가 구현해야 하며 이번에 TTL 정리 기능을 만들지 않았다.

`WorkoutWriteGuard`는 caller가 연 **명시적 READ_COMMITTED의 workout JPA 쓰기 tx**만 받는다. primary JDBC tx/readOnly/tx 없음은 거절한다. expectedUserId=principal을 먼저 확인하고 users 행 FOR UPDATE 후 현재 세대를 비교한다. 잠금 ticket은 동일 EntityManager tx에서만 사용 가능하다. 새 정의 ID는 전체 소유자·tombstone 범위에서 존재를 검사하고 외부 계정 정보를 포함하지 않는 ID_UNAVAILABLE만 돌려준다. 최종 ID 경쟁은 기존 PK도 방어한다. 현재 Guard는 기존 root 조회/인가·expectedVersion 비교·루트 다중 잠금 전체를 구현하지 않는다. 1B는 user 이후 고정 resourceType/id 순서로 잠그고 typed 명령에 필요한 검증을 완성해야 한다.

## 6. Task 008 호환성

- 두 Entity에만 @Version/JSONB 매핑을 추가했다. Repository scan은 기존 두 패키지·정확히2개 Entity를 유지한다. 영수증/feed는 JDBC SQL로 다룰 최소 스키마이며 불필요한 JPA aggregate를 만들지 않았다.
- 기존 primary JDBC transactionManager·workoutTransactionManager·Spring Session 독립 tx 설정을 그대로 유지한다. 실제 JPA/JDBC의 pg_backend_pid 일치, user 락 경쟁, domain/receipt/revision/feed rollback을 관측했다.
- 기존 가입 INSERT가 신규 default를 사용한다. 기존 UUID/name/설정/시각을 재작성하지 않는다. 신규 가입은 그대로 20종목·휴식180/120/90, 기존 identity 재로그인은 reseed하지 않는다.
- 두 GET의 DTO·상태·에러·보안·Request ID 제품 코드는 변경하지 않았다. DB를 쓰지 않는 앱 컨텍스트도 Guard를 새 bean으로 등록하지 않아 그대로 유지한다.
- V1~V8 파일은 수정하지 않았고 V8 upgrade fixture에서 checksum을 전부 비교했다. 개발·운영 DB를 접속하거나 migration하지 않았다.

## 7. 검증 결과

| 명령 | 결과 |
|---|---|
| `gradlew.bat --offline --no-daemon compileJava` | PASS |
| `gradlew.bat --offline --no-daemon test --tests '*workoutcommand*'` | PASS (초기 targeted 실행, 최종 추가 케이스는 아래 전체 실행에 포함) |
| `gradlew.bat --offline --no-daemon integrationTest --tests '*WorkoutWrite*IntegrationTests'` | PASS (초기 11개, 최종 추가 케이스 포함 13개는 아래 전체 실행) |
| `gradlew.bat --offline --no-daemon build` | **PASS**, 3m53s. unit 176 + integration 398 = **574개**, failures/errors/skipped 모두0. check/integrationTest·bootJar 포함 |
| `git diff --check` 및 신규 파일 공백 검사 | PASS |

최종 새 테스트는 CommandValue35·WorkoutCommandCodec29·WorkoutWriteFoundation12·WorkoutWriteUpgrade1 = 77개다. 기존497개 회귀도 전체 실행에 포함했다. 결과 XML은 `build/test-results/{test,integrationTest}`에 있고 최종 빌드 로그는 `build/task-009-1a-build.log`에 있다. build 출력물은 Git 대상이 아니다. Frontend는 무변경이므로 테스트/빌드를 재실행하지 않았다.

검증은 기존 Gradle Wrapper·Temurin25·캐시된 의존성·Testcontainers PostgreSQL18.6-alpine만 사용했다. Gradle 실행 환경에서 SPRING_/GOOGLE_/OTP_/RESEND_/POSTGRES_/DATABASE_/OVERLOAD_ 변수를 제거했고 테스트 local overlay를 유지했다. 각 DB URL은 일회성 컨테이너로 주입했다. 기존 통합 테스트의 인증·메일 전송 대체기를 유지했으며 실제 Google/Resend를 호출하지 않았다.

주요 게이트:

- 빈 DB 전체 V1~V9, V8 데이터 보유 DB upgrade, V1~V8 checksum·기존 user/종목 UUID·공백/동명 Unicode name·설정·microsecond 시각 보존, 재migrate 0건.
- 신규20 seed/기본 설정, 기존 계정 재가입 경로 무변경, Hibernate validate·정확히2개 Entity·JSONB·metadata-only @Version 증가.
- 0/최소 소수/NUMERIC 상한의 정확한 왕복, 7자리 소수·범위 초과 제출 거절, PostgreSQL 자체 rounding 관측, JS safe integer 초과 Counter·Long.MAX_VALUE 왕복.
- operation key UNIQUE, UUIDv4·필수 CHECK·receipt scope·제한된 terminal409·owner/generation FK·삭제 ID 재사용 방어, deferred receipt/feed commit 제약.
- user 락 경쟁 SQLSTATE55P03, 동일 물리 JPA/JDBC 연결, domain/receipt/revision/feed commit·rollback, 다른 tx의 잠금 ticket 재사용·readOnly/primary JDBC tx 거절.
- fingerprint golden vector·공백/객체 순서 불변·배열/fieldBases/version/시각 변화·Unicode/잘못된 JSON·자원 한도 검증.
- 기존 WorkoutRead·프로비저닝·인증·CSRF·Session·Request ID unit/integration 회귀.

초기 전체 실행에서 기존 테스트 두 개가 V8 기준 테이블 목록/history 개수를 기대해 실패했다. 추가된 세 테이블·history9를 명시하는 검사로 갱신했다. 기존 동작 검증을 제거하거나 비활성화하지 않았다. JVM CDS 공유 경고는 실패가 아니며 검사 설정을 변경하지 않았다.

## 8. 009-1B로 넘기는 항목

1. 실제 typed dispatcher/HTTP POST·결과 GET·오류 매핑·receipt replay와 fingerprint 변경 재사용 거절. 동일 key 경쟁·ACK 유실·commit 후 연결/Session/직렬화 실패를 실제 HTTP로 관측해야 한다.
2. 기존 root owner/세대/삭제 상태·expectedVersion·fieldBases/stamp 의미 검증, 고정 다중 잠금, root version·stamp·revision/feed·영수증의 실제 명령 원자성, overflow 처리.
3. 제한된 terminal409의 검증된 원본 저장·불변 재조회·새 해결 operation 참조/선택 검증, 같은값 최신 stamp·KEEP_CURRENT/history/no-op 구별. 실제 자동 해결은 아직 없다.
4. resolver 공개 전에 N-09 계정 설정 저장/조회와 서버의 최신 설정 검사. OFF 기본값을 임의의 강제 자동 동작으로 대체하지 않는다.
5. 편집용 version/stamp 조회 계약·최소 scalar slice와 기존 R 조회 회귀. 나머지 루틴·세션·수행·시작 원본·기기 proof·Outbox·React·공식 평가는 해당 후속 단계다.

**HTTP 멱등 실행·ACK 유실 통합 테스트는 완료하지 않았다.** 이번 SQL fixture의 원자성·제약 테스트를 HTTP 보장으로 표현하지 않는다. 1B는 자동 시작하지 않았다.

## 9. 최종 상태·승인

Backend HEAD/main은 시작과 같다. 추적 파일8개 modified, untracked 파일17개(기존 v0.1/v0.2 포함), staged0이다. v0.1 해시는 시작과 같고 v0.2는 정책 보완을 유지한 untracked 상태다. Frontend HEAD/main은 시작과 같고 작업 트리는 clean이다. Commit/Push는 하지 않았다.

모든 검증 세션은 종료했다. 종료 후 Docker 실행 목록은 시작과 같은 기존 사용자 컨테이너5개뿐이며 추가 Testcontainers/Ryuk는 없다. Java/Gradle worker/daemon 프로세스도 남아 있지 않다. 검증용 애플리케이션 컨텍스트·서버는 각 테스트에서 종료했다. 기존 사용자 프로세스/컨테이너는 종료하지 않았다.

사용자 요청 내 additive migration·기반 구현·테스트·문서 변경만 수행했다. 신규 주요 의존성/파괴적 migration/보안 정책 변경은 없다. 승인 상태는 요청 범위 승인됨이며 별도 승인 대기·보류 작업은 없다. 검증 실패는 수정 후 해소했다. 위의 1B 이후 미구현 범위와 JSON 부분집합/DB 단독 의미 검증 한계는 남아 있지만 1A 완료를 막는 알려진 실패는 없다.
