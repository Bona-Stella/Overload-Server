# Task 009-0 — 운동 쓰기 도메인·API 계약 및 구현 계획 v0.1

- 작성일: 2026-10-30 (Asia/Seoul).
- 상태: **사용자 리뷰용 초안 / 신규 계약 미승인 / 제품 구현 없음**.
- 이번 변경: 이 문서 1개 신규 작성. 제품 코드·설정·의존성·Entity·Flyway 원문/신규 migration 변경 없음. DB 접속·migration 실행·Google/Resend 호출·Commit/Push 없음.
- 목적: Task 006 확정 정책을 보존하면서 Task 009의 쓰기·멱등성·관계형 기록·오프라인 계약과 구현 게이트를 검토한다. 문서 제출은 Task 009-1 구현 승인이 아니다.

상태 표기는 **P**(Task 006/008에서 이미 확정된 정책), **I**(현재 소스에서 확인한 구현), **D**(008-0B의 미승인 기술 제안), **N**(이번 신규/구체화 제안, 승인 대기)이다. 아래 권장안·DTO·테이블·수치는 P/I로 표시하지 않는 한 D/N이며 구현된 기능이 아니다. 기존 문서의 작성 당시 상태와 현재 구현 상태를 구분한다.

## 1. 기존 설계와 구현 현황

### 1.1 시작 Git과 Task 008 반영

원격 fetch 없이 실제 로컬 Git을 확인했다. 아래 HEAD는 작업 시작과 문서 최종 검사에서 확인할 기준이며 원격 최신성 보장은 아니다.

| 저장소 | 브랜치 / 전체 HEAD | 시작 작업 트리 | 확인된 커밋 |
|---|---|---|---|
| Backend | `main` / `10e1a0242c36d525230cdef5859bacbbcc44da6a` | clean, staged/unstaged/untracked 없음 | `10e1a02` 008-1C 격리 실서버 브라우저 harness, `be3087b` 008-1A 조회, `d28f0fb` 008-0C JPA/JDBC, `7eb3ef7` 공통 HTTP/Request ID, `e29bfd9` 008-0B |
| Frontend | `main` / `28257838737dda0b24fd3d356cadd8c7ae757f36` | clean, staged/unstaged/untracked 없음 | `2825783` 008-1C 실서버 통합 검증, `9cee624` 008-1B 조회 연동 |

양쪽 AGENTS.md를 확인했다. 차이는 Task 006 링크의 저장소 상대 경로뿐이다. 하위 AGENTS.md는 파일 목록 검색에서 발견되지 않았다. [Backend 규칙](../engineering/backend.md), [Frontend 규칙](../../../overload-pwa/docs/engineering/frontend.md)을 함께 적용했다.

### 1.2 읽은 핵심 자료와 증거 수준

| 자료 | 이번 검토에 적용한 내용 |
|---|---|
| [Task 006 v0.3.1](task-006-integrated-design-v0.3.1.md) 1·2·4·5·6·8절 | 확정 운동 정책, 로컬 원자성, 최초 송신 봉인, generation/epoch, 정정·복구, 관계형 수행 |
| [008 JPA 설계](task-008-0-jpa-persistence-design.md) 3·5·7·8절 | 운동 JPA / 인증 JDBC 분리, FK·version·잠금·직접 SQL·보존 |
| [008-0B 도메인/API](task-008-0-domain-and-api-contract-v0.1.md) | R-01~03와 D-01~10, typed POST·DTO·평가·feed 후보 |
| [008-0D 공통 HTTP](task-008-0d-common-api-contract-v0.1.md) | 승인된 성공 직접 DTO / code 오류 / 204 / Security / Request ID 경계 |
| [008-0C 검증](task-008-0c-jpa-jdbc-validation-report.md) | TX-01~08: 동일 연결, flush/stale, commit 실패 롤백, Session 독립 tx |
| [008-1A 보고서](task-008-1a-workout-read-api-report.md) | R 실제 구현, 두 SELECT 동일 REPEATABLE_READ, 원래 실패를 rollback 실패가 덮는 사례 |
| [008-1B 보고서](../../../overload-pwa/docs/tasks/task-008-1b-workout-read-integration-report.md) | owner/세대/늦은 응답 방어, 추가 응답 필드 무시, demo 비접근 |
| [008-1C 보고서](../../../overload-pwa/docs/tasks/task-008-1c-real-backend-integration-report.md) | 실제 Java+PG+React 브라우저 검증, 테스트 발송기만 대체, 개발 DB와 분리 |

008-0C의 475개, 008-1A의 497개 Backend 테스트, 008-1B/1C의 312개 Frontend 테스트와 1C 브라우저 12개·HTTP 52건·감사 11개는 **각 보고서의 과거 실행 결과**다. 이번 설계 작업에서 재실행하거나 신규 쓰기 보장으로 계산하지 않았다.

### 1.3 실제 코드·스키마

Backend 기준 경로는 `src/main/java/com/overload/overload_server/`다.

| 확인한 구현 | 현재 사실 (I) / 후속 영향 |
|---|---|
| `build.gradle`, `application.yaml` | Java 25, Boot 4.1.2, JPA/JDBC/Flyway/Session/Validation/Testcontainers 설치. OSIV=false, ddl-auto=validate, generate-ddl=false. `test`는 postgres 태그 제외, `integrationTest`는 해당 태그, `check`는 통합 테스트 포함 |
| `persistence/WorkoutPersistenceConfiguration.java` | 단일 DataSource, primary JDBC `transactionManager`와 명시적 JPA `workoutTransactionManager`; Repository scan은 **exercise/workoutsettings 두 패키지**만 지정. 신규 Repository 도입 때 scan 확장·선택 검증 필요 |
| `exercise/ExerciseDefinition.java`, `workoutsettings/UserWorkoutSettings.java` 및 두 Repository | 기존 V2 매핑 2개. 읽기 메서드만 공개. version/deleted_at/관계·수정 서비스 없음 |
| `workout/WorkoutRead{Controller,Service,Errors}.java`, `WorkoutOwnerRepository.java`, 두 response DTO | GET exercises/settings. principal owner, 문자열 generation, 잠금 없는 JPA readOnly REPEATABLE_READ+JDBC. 오류 Advice는 해당 Controller에 한정 |
| `account/Account{Seed,Repository,ProvisioningService}.java` | JDBC REQUIRES_NEW 가입, 20종목 새 UUID+설정 180/120/90. 기존 계정 재로그인 재seed 없음. 신규 컬럼은 이 기존 INSERT와 호환되는 DB default가 필요 |
| `security/SessionPrincipal.java`, `SecurityConfiguration.java`, `http/RequestIdFilter.java` | principal은 userId. 기존 쿠키·CSRF·401/403 유지. Request ID는 서버 생성 UUID, 재요청은 새 값. 명령 결과 식별자가 아님 |
| `JpaJdbcCompatibilityIntegrationTests`, `WorkoutReadIntegrationTests`, `MixedPersistenceAssertions`, `JpaCompatibilityFixtures` | 후속 테스트에서 재사용할 실제 혼합 구성·연결/tx·flush·회귀 관측 근거 |

현재 Flyway 파일을 모두 읽었다. V1 users(data_generation≥0), V2 auth_identities/exercise_definitions/user_workout_settings와 owner-generation FK, V3 Spring Session, V4 OTP, V5 재인증, V6 대표 재인증 이메일/OTP 확장, V7 retired EMAIL 참조 보존, V8 Google unlink 참조 보존이다. 운동 루틴·세션·세트·멱등 영수증 테이블은 없다. V2 종목명 UNIQUE 없음, 설정 PK=user_id, 휴식 INTEGER>0, users `(id,data_generation)` UNIQUE와 종목 `(user_id,data_generation,id)` UNIQUE가 이미 있다.

Frontend는 `package.json`/lockfile, `main.tsx`, `App.tsx`, `features/auth`, `features/workout-read/{api,controller,WorkoutOverview}`, `features/workout/{types,model,storage,useWorkout,id,weight,cardio,CardioScreen}`, `features/routine/ExercisePicker`와 관련 보고서를 확인했다.

- I: `/`는 실제 인증+메모리 조회. `workout-read`는 credentials/include·no-store·timeout·응답 검증과 owner/요청 epoch 방어를 갖고 저장소에 접근하지 않는다. 두 GET의 세대가 다르면 최대 한 번 추가 쌍 조회 후 정지한다.
- I: 개발 `/demo`만 `App/useWorkout`과 `overload.core.v1`을 사용. `WorkoutState.version=1`은 로컬 문서 형식이고 서버 version이 아니다. UUID 생성은 있으나 목업 seed에는 비UUID가 남아 있다.
- I: 데모 일반 저장 실패 시 `persistWorkoutUpdate`가 next 메모리 상태를 유지할 수 있음, `structureModified`에 의존한 자격 제외, 이름으로 병합하는 createDefinition, 물리 배열 제거·복원 부재, restSeconds의 ≤8/9~12 구간이 남아 있다. 운영 계약의 준수 증거로 사용하지 않는다.
- I: 데모 유산소는 자동 timerMs/정정 activeMs·null 값·상태 전이 참고 가능. 반면 숫자 number, session 종목 ID/배치 ID 혼용, 미리 생성한 빈 세트 배열·loader 수 검사는 새 관계형 수행/복구와 직접 호환되지 않는다.
- I: React 19/TS 6/Vite 8, fetch·일반 CSS·Node test/SSR 사용. Dexie/Zustand/TanStack Query/RTL/Playwright/vite-plugin-pwa는 설치되어 있지 않다. IndexedDB·Outbox·운동 쓰기·설치형 오프라인 지원은 설계만 있다.

## 2. 이미 확정된 정책 (P)

다음은 재승인 대상이 아니며 기술 선택으로 바꾸지 않는다.

| 영역 | 유지할 정책 / 필수 사례 |
|---|---|
| 운동 종목 | 개인 UUID 동일성, 동명 허용. 이름 변경으로 계보 분리 금지. 활성 루틴 참조가 있으면 정의 삭제 차단. 과거 세션의 이름·계획·수행은 보존 |
| 루틴·배치 | 루틴·배치·정의 ID 구별, 동일 종목 중복 배치 허용. 저장된 제거는 soft delete. 복원은 지정 배치 ID·이전 승인값 유지·새 승인 사건 없음. NEW는 새 배치·새 계보. 편집 취소로 ID 변경 금지 |
| 계획·완료 | Initial은 시작 시 불변, Applied는 진행 중 편집/최초 명시 종료 봉인. 종료는 사용자 행동이며 ACK와 별개. 완료 웨이트 공식 원본은 performed_sets. 미완료 초안을 완료 실적으로 만들지 않음 |
| 공식 자격 | 3→4→추가 수행 삭제→정상 3은 후보 가능, 3→유효 2는 제외. 다른 원계획 종목 삭제로 전체 완료 우회 금지. 종목 이동·유산소 skip 자체로 웨이트 자격 제외 금지 |
| 무게 | initial_weight·approved_weight·오늘만 작업값 구별. 승인값 변경은 명시 사용자 행동+불변 사건. 시작 60, 첫 실제 세트 전62.5 승인 후 전체 정상62.5는 새 문맥 후보. 혼합/오늘만은 정규 기준 제외. 자동 증량/감량 없음 |
| 과거 기준 | 서로 다른 세션의 세트별 최대 조합 금지. 동일 문맥의 **한 과거 공식 세션 전체 반복 배열**을 사용. 최고 기준과 최신 수행의 감량 검토 구별 |
| 사후 정정 | 기존 종목 완료 세트 kg/reps 정정·세트/세션 삭제 허용. 변경 사건 보존, 현재 평가 재계산. Initial/봉인 Applied/최초 종료 판단/승인 사건 불변. 완료 후 새 종목·계획/목표/순서 편집 금지 |
| 선택 복구 | 올바른 현재 version/epoch의 새 operationId. 기존 세션 종목 누락 세트만 명시 선택 복구, deleted ID 복원은 별도 명시 선택. ordinal 충돌 자동 합산 금지. 새 공식 세션 자동 생성 금지 |
| 수치·날짜 | kg 원본, 신규 실제 reps≥1, 0kg 허용, 0과 미입력 구별. 시작 현지 DATE 고정, Instant+IANA zone 별도. 자정·지연 업로드로 날짜 변경 금지. 구 데모 0회 읽기 호환이 신규 허용 근거는 아님 |
| 휴식·유산소 | 목표 반복 **상한** ≤10 / 11~12 / ≥13. 실제 저장 설정 사용. READY→RUNNING↔PAUSED→REVIEW→COMPLETED/SKIPPED, 원본 timerMs와 정정 activeMs 구별. 실적 미입력은 null, 계획으로 추정 금지. 매초 서버 전송 없음 |
| 로컬·동기화 | 도메인 완료+명령/Outbox를 동일 IDB tx로 저장하고 complete 후 로컬 완료 표시. 서버 ACK 전 서버 저장 완료 표시 금지. 계정/세대 격리, 송신 전 봉인, 동일 명령 재전송, 오래된 응답 차단 |
| 제어권 | 세션당 서버 쓰기 기기 하나, 정상/강제 이전·epoch 증가. 구 epoch 미전송 명령 재태깅 금지. 구 로컬 원본은 해결 ACK까지 보존. 단순 deviceId는 권한 증명 아님 |
| 초기화·탈퇴 | 초기화는 가입 seed+generation 증가, 구세대 차단. 탈퇴/초기화/내보내기 실제 구현은 별도 범위. 운영 파기·백업 보존 일수 미확정 |
| HTTP·인증 | 성공 DTO 직접, 명시 오류 code JSON, 204 무본문, 기존 Security 401/403/CSRF/세션과 X-Request-ID 유지. 모든 프록시·필터 오류의 JSON 보장 없음 |

## 3. D-01~D-10 검토 및 승인 필요 여부

R-01~03은 현재 I이며 이번에 변경하지 않는다. D-01~10을 승인했다고 볼 근거는 현재 소스/보고서에서 확인되지 않았다. 문서 커밋과 JPA 도입·읽기 승인으로 쓰기 D를 일괄 승인한 것으로 해석하지 않는다.

| ID | 기존 제안 및 검토 | 이번 권장·대안 | 승인/선행 게이트 |
|---|---|---|---|
| D-01 | NUMERIC(20,6), BigDecimal, decimal 문자열 | 유지 권장. unconstrained NUMERIC+자릿수 검증 대안. 소수6자리 초과를 무반올림 거절하는 새 제한임 | 필요, 009-1 스키마 전 |
| D-02 | 신규 UUID v4, Counter 문자열, Count INTEGER | 유지 권장. 기존 UUID 재발급 없음. 양수 Count≤2147483647은 기술 상한이며 무제한 배열 허용 아님 | 필요, 공통 DTO/DB 전 |
| D-03 | 4종 버전 snapshot, 수행/초안 분리 | 유지+§4.3/6의 authoritative 필드 구체화. checkpoint가 공식 수행 원본이 되지 않음 | 필요, 세션 스키마 전 |
| D-04 | typed POST+JCS/SHA-256+ACK | 유지 권장+N-01 결과 GET·N-02 보존/실패 경계. REST 다중 경로와 비교 | 필요, 최초 쓰기 기반 전 |
| D-05 | 사용자·세대별 직렬 revision/feed | 사용자 행 잠금+단일 revision/batch 권장. 시각/sequence MAX 커서 대안은 누락 위험으로 비권장 | 필요, 첫 동기화 대상 쓰기 전 |
| D-06 | 루트 version·고정 잠금·다중 집계 원자성 | JPA @Version+루트 명시 touch 후 flush 권장; root 조건부 SQL RETURNING은 대안. 새 순서 제약 N-03 | 필요, 자식 변경/commit 경쟁 테스트 |
| D-07 | 합계 반복 우선·동률 최신 등 공식 기준 순위 | 한 세션 정책 P는 유지. 합계/동률·목표 밖 reps·추가 수행 영향 범위는 §12에서 사용자 결정 | 필요, 평가/종료 판단 구현 전 |
| D-08 | 서버 device binding·탭 조정 | 전용 HttpOnly 쿠키 후보 vs WebCrypto. 새 로그인 체계 도입 아님. §7.4 상세 보안 승인 전 보류 | 필요, 세션 쓰기/이전 전 |
| D-09 | 종목/설정/일정/메모 version·tombstone | 유지, R을 그대로 두는 별도 편집용 GET/동기화 DTO 권장. 가입 JDBC default 호환 필수 | 필요, 보조 리소스 편집 전 |
| D-10 | 시계·ordinal·오프라인 Initial 검증 | ordinal 명시 충돌, 시각 무보정. 오프라인 source 근거 N-04 대안 비교·선택 필요 | 필요, START/복구 전 |

추가 제안 N-01(영수증 조회), N-02(보존 및 실패 결과 한계), N-03(재정렬 제약), N-04(과거 계획 검증), N-05(로컬 탭 fencing), N-06(상세 자원 제한), N-07(예상 계정 전제조건)은 모두 승인 대기다. P의 원자성/소유권을 새 정책처럼 재결정하지 않는다.

## 4. 도메인·쓰기 API 계약 제안

### 4.1 전송·타입·공통 응답

**권장 D-04:** `POST /api/v1/workout-commands`에서 명시된 type별 DTO만 받는다. 조회는 별도 GET. 이 endpoint는 임의 Entity merge/JSON Patch/SQL/자유 명령 batch를 받지 않는다.

| 선택 | 장점 | 비용 |
|---|---|---|
| typed POST (권장) | 봉인·재전송·결과 조회 일관, 루틴+세션 승인 같은 한 명령 원자성 표현 | type별 validator/handler와 문서가 필요, 일반 CRUD URI보다 낯섦 |
| POST/PATCH/DELETE `/exercises`, `/routines/{id}`, `/workout-sessions/{id}/sets` 등 | 리소스별 HTTP 의미 친숙 | 모든 경로에 같은 멱등 메타데이터 필요, DELETE 본문 회피 방식 및 다중 집계 명령 별도 필요 |

신규 POST 정상은 신규/중복/no-op 모두 **200 CommandAck 직접 반환**이다. 비동기 접수202/공통 성공래퍼/실패200은 사용하지 않는다. CommandAck가 삭제 명령에도 필요하므로 이 POST에 204는 쓰지 않는다. 기존 인증 204는 본문을 추가하지 않는다.

```text
CommandRequest<T> = {
  commandSchemaVersion: 1, operationId: UUID,
  expectedUserId: UUID, dataGeneration: Counter,
  type: CommandType,
  expectedVersions: [{resourceType, resourceId: UUID, version: Counter}],
  sessionControl: {deviceId: UUID, ownershipEpoch: Counter,
                   deviceBindingRef: string} | null,
  occurredAt: Instant, payload: T
}
CommandAck<R> = {
  ackSchemaVersion: 1, userId: UUID, dataGeneration: Counter, operationId: UUID,
  outcome: APPLIED | REPLAYED, fingerprint: string, appliedAt: Instant,
  resultVersions: [{resourceType, resourceId: UUID, version: Counter}],
  changeCursor: string | null, result: R
}
```

필수 필드는 모두 제시하고 null 허용 위치만 null이다. expectedVersions는 `(resourceType,resourceId)` 중복 없이 type·UUID 순 정렬. 실제 owner는 principal로만 확정한다. **N-07의 expectedUserId는 권한 주장이 아닌 계정 일치 전제조건**이며 principal과 다르면 아무 쓰기 없이409 OWNER_CONTEXT_MISMATCH다. 클라이언트가 계정을 선택하거나 다른 계정에 접근할 수 있게 하지 않는다. 모든 아래 payload ID는 UUID 필수이며 `?`는 선택(생략 가능), `|null`은 필수 nullable을 뜻한다. resourceVersion과 version은 동일한 서버 동시성 Counter의 DTO별 이름이다.

resourceType 허용값은 EXERCISE/WORKOUT_SETTINGS/ROUTINE/WEEKLY_SCHEDULE/EXERCISE_MEMO/WORKOUT_SESSION이다. 설정·일정 resourceId는 principal 계정 UUID, Memo는 정의 UUID다. 이 ID도 권한 근거가 아니며 서버에서 다시 범위를 확인한다. ackSchemaVersion은 이번 응답 구체화 제안으로, 저장 영수증을 오래 읽기 위한 타입 표식이다.

이 전제조건은 신규 생성에서 특히 필요하다. A 계정 확인 GET 직후 다른 탭이 쿠키를 B로 바꾸면, A의 CREATE_EXERCISE가 B의 principal·동일 generation=0으로 도착할 수 있다. 응답을 버리는 것만으로 B에 잘못 저장된 데이터를 막을 수 없다. 생성 당시 expectedUserId=A를 봉인하여 서버가 비교하면 차단된다. 기존 008-0B의 owner 권한 입력 금지와 구별되는 **새 wire 제안**이며 승인 대상이다. 기존 R 두 GET에는 필드/query를 추가하지 않는다.

- Counter: 기존 R과 같은 `0..9223372036854775807`의 canonical 10진 문자열; 증가 overflow 실패, Number 변환 금지. epoch/revision/schemaVersion을 구별한다.
- 신규 ID: 클라이언트 CSPRNG UUID v4로 definition/routine/placement/session/sessionExercise/performedSet/cardioActivity/operation을 생성 의도와 함께 고정. 사용자·seed·사건 ID는 서버 생성. UUID 동일성만으로 인가하지 않음. 기존 읽기 UUID는 원값 유지.
- Decimal: canonical 비음수 평문 문자열, kg=0 허용. D-01 권장은 정수14+소수6자리, 지수/NaN/Infinity/-0/+부호 금지. 입력 draft `62.`는 UI에 보존하되 제출 때 검증·정규화. DB scale 변환 전 서버 검증, 무승인 반올림 금지. PostgreSQL NUMERIC은 선언 scale로 반올림할 수 있다. [공식 NUMERIC](https://www.postgresql.org/docs/18/datatype-numeric.html)
- Count: 양수 JSON 정수, ordinal≥1, position은 0부터. D-02 범위 적용. DurationMs는 비음수 안전 정수≤9007199254740991이며 유산소 실제값 등은 별도 좁은 도메인 범위 적용.
- 신규 명령 Instant는 UTC millisecond `...ss.SSSZ`, DATE는 YYYY-MM-DD, zone은 IANA. 기존 DB microsecond 읽기는 자르지 않음. 기기 운동 시각과 서버 적용 시각 구분.
- 새 이름은 비공백, 기존 표시명 Unicode/공백을 읽기에서 변환하지 않음. N-06 요청크기·문자열/배열 한계는 §12 승인 게이트이며 과거 데이터에 소급 강제하지 않음.

### 4.2 종목·설정·루틴 명령

표의 **E**는 §9 공통 형식/인증/404/세대/ID/fingerprint/5xx 오류, **V**는 명시 expectedVersions 일치 검증이다. 생성은 해당 ID 미존재 검증을 사용하고 version=0의 새 루트를 반환한다. 기존 루트는 변경 시 +1; 같은 성공 명령 재전송은 증가 없음. 모든 행은 도메인+사건+루트 version+feed+성공 영수증을 §5의 **한 workout tx**로 처리한다.

| type / 요청 payload DTO | 전제조건·동시성 | 정상 result / 원자 범위 | 추가 오류 |
|---|---|---|---|
| CREATE_EXERCISE `{id,name}` | 현재 owner/세대, ID 신규, 동명 허용 | `{exerciseId,changed:true}` / 정의+기본 메모 부재 상태 | E, ID_UNAVAILABLE |
| RENAME_EXERCISE `{exerciseId,name}` | 활성 정의, V(Exercise) | `{exerciseId,changed}` / 정의 이름만. 과거 snapshot 변경 없음 | E, RESOURCE_DELETED |
| DELETE_EXERCISE `{exerciseId}` | V(Exercise), 활성 루틴의 활성 weight 배치 참조 없음 | `{exerciseId,changed:true}` / 정의 tombstone+memo tombstone/버전+무효화. 이력 보존 | E, EXERCISE_IN_USE, RESOURCE_DELETED |
| UPDATE_WORKOUT_SETTINGS `{restLowSeconds,restMediumSeconds,restHighSeconds}` | V(Settings), 기존 현재세대 설정 필수, 각 INTEGER>0 | `{changed}` / 세 필드 일괄 변경, 부분 PATCH 없음 | E, WORKOUT_SETTINGS_MISSING(500), VALIDATION_FAILED |
| UPSERT_ROUTINE `{routineId,name,weightPlacements,cardioPlacements,scheduleUpdate?,approvedWeightChanges?}` | 신규 또는 V(Routine). 이름 비공백, 활성 종목 합≥1. 명시 NEW/KEEP/RESTORE 식별, 전체 순서/출처 검증 | `{routineId,changed,progressChangeEventIds}` / 루틴+배치+명시 승인 사건, scheduleUpdate 있으면 일정까지 | E, RESTORE_TARGET_INVALID, ACTIVE_ORDER_CONFLICT, SCHEDULE_CONFLICT |
| DELETE_ROUTINE `{routineId}` | V(Routine)+V(WeeklySchedule), 빈 일정도 상태 version 검증 | `{routineId,changed:true}` / 루틴 tombstone+요일 정리. 배치/승인/이미 시작한 세션 보존 | E, RESOURCE_DELETED |
| REMOVE_WEIGHT_PLACEMENT / REMOVE_CARDIO_PLACEMENT `{routineId,placementId}` | V(Routine), 자식 범위·활성·최소 구성 검증 | `{placementId,changed:true}` / 자식 tombstone+순서 정리+루트 | E, VALIDATION_FAILED |
| RESTORE_WEIGHT_PLACEMENT / RESTORE_CARDIO_PLACEMENT `{routineId,placementId,position}` | V(Routine), 같은 루틴의 삭제 배치, weight는 해당 정의도 활성 | `{placementId,changed:true}` / 지정 ID 복원+순서. approved/initial 그대로, 승인 사건 없음 | E, RESTORE_TARGET_INVALID, ACTIVE_ORDER_CONFLICT |
| REORDER_ROUTINE_PLACEMENTS `{routineId,weightPlacementIds,cardioPlacementIds}` (N) | V(Routine), 각 활성 ID 전부 정확히 1회, 타입별 순서 | `{routineId,changed}` / 두 배열의 position+루트. 새 배치/계보 생성 없음 | E, ACTIVE_ORDER_CONFLICT |
| REPLACE_WEEKLY_SCHEDULE `{assignments:[{weekday,routineId}],replaceConflicts:boolean}` | V(WeeklySchedule)+참조 활성 루틴 V, weekday0..6 중복 없음, 덮어쓰기 명시 확인 | `{changed}` / 일정 전체 교체 | E, SCHEDULE_CONFLICT |
| SET_EXERCISE_MEMO `{exerciseId,text}` / DELETE_EXERCISE_MEMO `{exerciseId}` | V(Memo) 또는 생성 시 명시 부재 조건, 활성 정의 검사 | `{exerciseId,changed}` / 메모+memo version, 삭제 전파 | E, RESOURCE_DELETED |

UPSERT에서 `weightPlacements[]`는 판별 union이다. NEW=`{mode:"NEW",id,exerciseDefinitionId,position,setCount,minReps,maxReps,initialWeightKg}`; KEEP=`{mode:"KEEP",id,position,setCount,minReps,maxReps}`; RESTORE=`{mode:"RESTORE",id,position}`. KEEP는 정의 교체·initial/approved 덮어쓰기 불가, RESTORE는 승인값 입력 불가. cardio NEW는 `{mode:"NEW",id,position,plan}`, KEEP는 `{mode:"KEEP",id,position,plan}`, RESTORE는 ID/position만. 기존 활성 ID 누락은 명시 전체 저장에 의한 제거로 해석하되 UI에서 제거 목록 확인을 만들고 tombstone 처리한다. 임의 미등록 ID를 KEEP로 merge 삽입하지 않는다. 이미 삭제된 ID는 KEEP/일반 생성으로 살릴 수 없다.

`scheduleUpdate={assignments:[{weekday,routineId}],replaceConflicts:boolean}`는 완전한 주간 교체안이다. 별도 SAVE와 섞지 않고 같은 명령의 expectedVersions로 보호한다. memo 최초 생성/부재 version은 §6의 정의에 내장된 memoVersion=0을 사용하고 API는 `{text:null,resourceVersion:"0"}`을 반환한다. 빈 문자열은 저장값이며 DELETE와 구별한다. 정의 삭제가 memo를 함께 지우는 것은 N이며 별도 memo 기대값은 수신하지 않고 정의 삭제 효과로 명시한다. 명시적 메모 편집·정의 삭제 경합은 사용자 잠금과 정의 활성 재검증으로 차단한다.

`approvedWeightChanges=[{placementId,newWeightKg}]`는 사용자가 루틴 저장에서 명시 승인한 변경만 담는 제한적 복합 명령 제안이다. 기존 활성 KEEP 배치만 대상, 같은 배치 중복/제거·복원 대상 포함 금지, source=ROUTINE_EDITOR 사건을 같은 tx에 생성한다. initial을 덮지 않고 진행 Session을 자동 수정하지 않는다. 어떤 승인/일정 검증이든 실패하면 루틴 저장 전체 롤백, Routine version은 한 번만 증가한다. 자유 batch가 아니며 신규 배치의 초기 무게는 NEW 필드로만 받는다.

운영 가입은 기존20종목+설정만 유지한다. 신규 사용자에게 루틴/일정/메모/운동 실적을 만들지 않는다. sync state 등 기술 제어 행은 최초 쓰기에서 지연 초기화할 수 있지만 조회에서 누락 설정을 자동 생성하지 않는다.

### 4.3 세션 DTO·스냅샷의 원본

| 값 | 권장 필드·원본/수명 |
|---|---|
| Session | `id,sourceRoutineId,routineNameAtStart,phase:WARMUP\|WORKING\|FINISHED,startedAt,workoutDate,startedTimeZone,endedAt\|null,version,ownershipEpoch,writerDeviceId,deletedAt\|null`. FINISHED 유지 상태에서 정정하며 EDITING/동기화완료를 새 phase로 혼합하지 않음 |
| InitialPlanSnapshot v1 | `snapshotSchemaVersion,sessionId,sourceRoutineId,routineVersionAtStart,routineNameAtStart,startedAt,workoutDate,startedTimeZone,weightExercises[{sessionExerciseId,sourcePlacementId,exerciseDefinitionId,nameAtStart,position,plannedSetCount,minReps,maxReps,approvedWeightKgAtStart,approvalEventIdAtStart\|null}],cardioActivities[{sessionCardioActivityId,sourcePlacementId,position,plan}]`. 시작 후 불변 |
| AppliedPlanSnapshot v1 | `snapshotSchemaVersion,sessionId,sealedAt\|null,weightExercises[{sessionExerciseId,sourcePlacementId\|null,exerciseDefinitionId,nameInSession,position,plannedSetCount,minReps,maxReps,included,approvedContext:{weightKg,approvalEventId\|null}}],cardioActivities[{sessionCardioActivityId,position,plan,included}]`. 계획/승인 문맥의 유일 수정 원본, 최초 FINISH에 봉인 |
| SessionWeightExercise | 관계형 `id,sessionId,exerciseDefinitionId,sourcePlacementId\|null,nameAtStart,addedDuringSession`. 세션 내 식별/출처, 실제 계획 변경값은 Applied에서 읽음. Initial 소속은 snapshot의 ID로 판단, 현재 배열에서 지워도 원계획 증거 존속 |
| PerformedSet | `id,sessionExerciseId,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId\|null,deletedAt\|null`. 실제 완료 때 생성. 삭제 후 자동 재번호 없음. 빈 예정 세트는 행 생성하지 않음 |
| CompletionAssessmentSnapshot v1 | `snapshotSchemaVersion,ruleVersion,assessedAt,sessionVersion,overallWeightPlanComplete,exercises[{sessionExerciseId,eligible,reasonCodes,comparisonContext,benchmarkEvidence:{sessionId,sessionVersion}\|null,evaluatedPerformedSetIds,advice}]`. 최초 종료 판단·근거만 불변 저장 |
| ActiveCheckpoint v1 | `snapshotSchemaVersion,checkpointSequence,capturedAt,currentActivityId,workingWeights[{sessionExerciseId,weightText,mode:TODAY\|APPROVED}],setDrafts[{sessionExerciseId,ordinal,repsText}],timer:REST(endAt,source IDs)\|TRANSITION(since,source IDs)\|null,cardioDrafts`. 복원 보조, 수행/승인 생성 불가 |

고정 식별 행에 계획/수행을 다시 복제하지 않는다. Applied에 실제 weight/reps 전체 배열을 넣어 performed_sets와 이중 원본으로 만들지 않는다. checkpoint TODAY 작업값은 다음 수행에 쓰일 UI 상태이며 승인값이 아니다. COMPLETE_SET는 typed 값과 승인 근거를 별도로 검증한다. 승인 작업 시 Applied 승인 문맥과 지정 session checkpoint 작업값이 함께 바뀔 수 있으나 Initial과 기존 수행값은 그대로다.

`CardioPlan={kind:TREADMILL|BIKE|OUTDOOR|STAIRS|OTHER,name,targetSeconds,targetDistanceKm:Decimal|null,intensity:{mode:SPEED|PACE,value:Decimal}|null,inclinePercent:Decimal|null,resistance:Decimal|null}`. 계획은 Initial/Applied, 실제 활동 state/result는 관계형 session_cardio_activities가 원본이다. 해당 행에 계획 JSON을 또 수정 가능하게 중복 보관하지 않는다.

기존 cardio.ts와 대조한 유지 범위: 이름≤100(현재 JS UTF-16 length 기준), 목표1..86400초, 거리0.001..1000km(거리지원 kind만), speed0.1..100, pace0.1..120, 경사0..50(treadmill만), 저항1..100(bike만), 실제 activeMs1..86400000, 수동 BPM20..300 정수/미입력null. 자동 timerMs는 ≥0 안전 정수이며 보정된 activeMs 상한을 timerMs에 소급 강제하지 않는다. `corrected=false`면 activeMs=timerMs, true면 원본 timerMs 보존. 타이머가 오래 방치돼도 원본을 자르지 않는다. decimal scale은 D-01 승인 필요다.

### 4.4 세션·수행·종료 명령

일반 세션 명령은 V(Session)+현재 sessionControl 필요. 표의 행마다 owner·세대·자식범위·deleted 상태를 재검증한다. 같은 이름·같은 ordinal·같은 kg/reps라는 이유로 중복 적용을 추측하지 않는다.

| type / payload | 상태 전제·필수 값 | result / 도메인 원자 범위 | 추가 오류 |
|---|---|---|---|
| START_WORKOUT `{sessionId,routineId,routineVersionAtStart,initialPlanCandidate,startedAt,workoutDate,startedTimeZone,deviceId,sourceEvidence?}` | 새 세션/종목/activity UUID; 활성 소스, 계획/승인 근거 검증(§7.5), 등록 기기. Initial 후보를 신뢰하지 않음 | `{sessionId,version:"0",ownershipEpoch:"0",writerDeviceId}` / 세션+식별 자식+Initial/Applied+제어권. cardio-only는 WORKING, weight 있으면 WARMUP 권장 | PLAN_SOURCE_CONFLICT, CLOCK_CONTEXT_INVALID, ID_UNAVAILABLE |
| BEGIN_WORKOUT `{sessionId}` | WARMUP→WORKING | `{sessionId,phase:"WORKING",changed:true}` / 세션+checkpoint | INVALID_STATE_TRANSITION |
| CHANGE_SESSION_PLAN `{sessionId,actions}` | 진행 중. actions 명시 union, 아래 참조 | `{sessionId,changed:true}` / 식별 자식+Applied+정리 checkpoint. Initial 불변 | SESSION_FINISHED, PERFORMED_EXERCISE_IN_USE, VALIDATION_FAILED |
| SET_TODAY_WEIGHT `{sessionId,sessionExerciseId,weightKg}` | 진행 중, 포함된 종목 | `{sessionId,sessionExerciseId,changed}` / 해당 작업값 mode=TODAY. Routine·승인 사건 변경 없음 | SESSION_FINISHED |
| COMPLETE_SET `{sessionId,sessionExerciseId,id,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId:null\|UUID}` | WORKING, 종목 포함, 신규 set UUID, ordinal 비충돌, reps≥1, 승인 근거 일치 | `{sessionId,performedSetId,changed:true}` / 수행행+세션+관련 checkpoint. 계획 초과 실제 수행도 저장 가능 | ACTIVE_ORDINAL_CONFLICT, APPROVAL_CONTEXT_INVALID, INVALID_STATE_TRANSITION |
| UNDO_SET `{sessionId,performedSetId}` | 진행 중, 활성 수행 | `{performedSetId,changed:true}` / 수행 tombstone+취소 사건+세션. draft는 로컬 보존 | SESSION_FINISHED, RESOURCE_DELETED |
| CORRECT_PERFORMED_SET `{sessionId,performedSetId,weightKg,reps}` | 기존 활성 수행, 진행/완료 가능. 시각/계획/정의/ordinal 변경 없음 | `{performedSetId,recordChangeEventId,changed}` / 수행 정정+전후 사건+세션+현재 평가 무효화 | RESOURCE_DELETED, VALIDATION_FAILED |
| DELETE_PERFORMED_SET `{sessionId,performedSetId}` | 기존 활성 수행, 진행/완료 가능 | `{performedSetId,recordChangeEventId,changed:true}` / tombstone+전후 사건+세션+평가 무효화 | RESOURCE_DELETED |
| CARDIO_TRANSITION `{sessionId,activityId,action,eventAt,elapsedEvidence}` | START:READY/PAUSED→RUNNING, PAUSE:RUNNING→PAUSED, REVIEW:RUNNING/PAUSED→REVIEW, CANCEL_REVIEW:REVIEW→PAUSED, SKIP:未完→SKIPPED | `{activityId,status,changed:true}` / 활동 state/앵커+세션. 동시에 RUNNING/REVIEW 1개 | CARDIO_ACTIVITY_BUSY, INVALID_STATE_TRANSITION, CLOCK_CONTEXT_INVALID |
| COMPLETE_CARDIO `{sessionId,activityId,timerMs,activeMs,corrected,distanceKm,averageBpm,completedAt}` | REVIEW→COMPLETED, 서버 누적 자동시간과 timerMs 일치, 보정/null/종류별 검증 | `{activityId,status:"COMPLETED",changed:true}` / 실제 결과+세션 | INVALID_STATE_TRANSITION, VALIDATION_FAILED |
| SAVE_ACTIVE_CHECKPOINT `{sessionId,checkpoint}` | 진행 중, 현재 epoch, checkpointSequence가 현재보다 큼, 초안만 허용 | `{sessionId,changed}` / checkpoint+세션. 매초 송신 없음 | CHECKPOINT_STALE, SESSION_FINISHED |
| FINISH_WORKOUT `{sessionId,endedAt,confirmPartial,confirmSkipPendingCardio}` | 진행→FINISHED 명시 행동, 미완료 웨이트/유산소 확인, 시각 검증, D-07 평가 게이트 | `{sessionId,phase:"FINISHED",completionAssessment}` / Applied 봉인+최초 평가+활동 skip+checkpoint 제거+세션 | INCOMPLETE_WORKOUT_CONFIRMATION_REQUIRED, CLOCK_CONTEXT_INVALID, INVALID_STATE_TRANSITION |
| DELETE_WORKOUT_SESSION `{sessionId}` | 활성 소유 세션, 올바른 제어권 | `{sessionId,changed:true}` / 세션 tombstone+정정/삭제 사건+평가 무효화, 기존 승인 사건 보존 | RESOURCE_DELETED |
| RECOVER_EXISTING_EXERCISE_SETS `{sessionId,sessionExerciseId,selections,confirm:true}` | 현재 제어권+V, 현재 세션에 이미 존재하는 종목. 봉인 계획 불변 | `{sessionId,performedSetIds,recordChangeEventIds}` / 선택 세트 전체+사건+세션, 하나 실패 시 전부 롤백 | NEW_EXERCISE_AFTER_FINISH, ACTIVE_ORDINAL_CONFLICT, RESTORE_TARGET_INVALID |

`actions`는 ADD_WEIGHT=`{kind,sessionExerciseId,exerciseDefinitionId,sourcePlacementId:null,position,plannedSetCount,minReps,maxReps,workingWeightKg}`, EXCLUDE_WEIGHT=`{kind,sessionExerciseId}`, SET_WEIGHT_PLAN=`{kind,sessionExerciseId,plannedSetCount,minReps,maxReps}`, ADD_CARDIO=`{kind,activityId,position,plan}`, EXCLUDE_CARDIO=`{kind,activityId}`로 제한하는 안이다. 웨이트 포함 종목을 다시 여는 별도 INCLUDE는 최초 제외 ID를 명시하는 경우만 후보이며 일반 ADD로 같은 ID 부활 금지. 미완료 종목 제거는 원계획 검사에서 계속 미완료다. 완료 수행이 있는 종목 제외는 먼저 명시 수행 취소/삭제가 필요. 단순 navigate는 로컬 UI이며 이 actions를 보내지 않는다. 별도 세션 드래그 재정렬 기능을 추가하지 않는다.

`elapsedEvidence={priorAccumulatedMs,runSince:null|Instant,eventAt}`는 기존 state와 맞는지 검사하는 자료다. 서버는 원래 앵커와 이벤트 시각으로 PAUSE/REVIEW의 delta를 산출하고, 수신 지연을 운동 시간으로 합산하지 않는다. CANCEL_REVIEW는 누적값 유지, SKIP은 result=null. 오프라인 전이는 의존순서로 전송한다. 역행/불일치는 CLOCK_CONTEXT_INVALID로 보류하며 구체 시계 편차는 §12 미결이다.

`selections[]`는 ADD_MISSING=`{action,performedSetId,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId:null|UUID}`, CORRECT=`{action,performedSetId,weightKg,reps}`, RESTORE_DELETED=`{action,performedSetId,weightKg,reps}`이다. ADD는 신규 ID, RESTORE는 기존 tombstone ID/기존 ordinal·시각 보존. 완료 후에는 최초/봉인 계획에 속한 기존 종목으로 제한하고 새 종목을 만들지 않는다. 서명/근거 없는 승인 문맥을 복구로 위조하지 않는다. 같은 ordinal의 활성 수행이 있으면 사용자가 그 ID의 CORRECT를 선택해야 한다.

UNDO 후 동일 세트를 다시 완료할 때 일반 COMPLETE로 tombstone 해제하지 않는다. 새 완료 의도는 새 performedSetId/operationId를 생성하고 비어 있는 ordinal을 쓴다. 삭제된 이전 ID 복원이 필요하면 RESTORE_DELETED를 사용한다. 예정 슬롯 UUID는 만들지 않는다. CORRECT는 기존 temporary/승인 근거를 자동 바꿔 오늘만 기록을 공식 기록으로 승격시키지 않는다. 정정 kg가 승인 문맥과 다르면 현재 평가에서 제외한다.

COMPLETE의 temporaryWeight와 approvalEventId는 클라이언트 주장만으로 공식 자격을 부여하지 않는다. 앞서 커밋된 SET_TODAY_WEIGHT/APPROVE와 서버 Applied 승인 문맥·작업 mode를 대조한다. TODAY이면 같은 수치여도 temporary=true, APPROVED이면 해당 승인 근거/중량이 일치해야 한다. checkpoint 업로드로 승인 근거를 만들거나 바꾸지 못한다.

SAVE_ACTIVE_CHECKPOINT가 받는 값은 초안/표시 선택/타이머 복원값으로 한정한다. phase·작업 mode·승인 근거 등 typed 명령이 소유한 필드가 들어오면 현재 서버 문맥과 같아야 하며 이것으로 TODAY→APPROVED를 전환하지 못한다. 타이머 source performedSetId와 세션/종목 범위를 검증한다. COMPLETE 수신 시각에 휴식을 새로 시작하지 않는다. 실제 캐시된 휴식 설정으로 로컬 완료 순간 만든 endAt/since를 보존하고 상태 변경 시 checkpoint로 묶어 전송한다. 늦은 ACK가 로컬 타이머를 재시작하거나 서버의 최신 설정을 과거 휴식에 소급 적용하지 않는다.

FINISH는 수행을 JSON에서 추정 생성하지 않고 현재 관계형 유효 행으로 평가한다. 미완료 초안은 공식 실적에서 제외하되 로컬 원본은 동기화/복구 정책에 따라 보존한다. 실제 완료 세트 수와 예정 세트 수가 달라도 저장 자체를 막지 않는다. 동시 COMPLETE와 FINISH는 §7.3대로 하나만 먼저 성공하고 다른 쪽은 version 충돌이다. FINISHED 재전송은 동일 operation 영수증일 때만 같은 성공이고 다른 신규 FINISH는 INVALID_STATE_TRANSITION이다.

### 4.5 승인 중량·기록·조회

`APPROVE_WEIGHT {routineId,placementId,newWeightKg,source:ROUTINE_EDITOR|WORKOUT,sessionId:null|UUID}`: 활성 루틴/배치·V(Routine), WORKOUT이면 지정 진행 Session의 출처 일치·V(Session)/control 필수. 결과=`{placementId,approvedWeightKg,progressChangeEventId:null|UUID,changed}`. 승인값+불변 progress event+관련 Applied 승인 문맥/작업값+각 루트 version+receipt/feed가 한 tx다. 편집기 승인은 진행 Session 자동 덮어쓰기 없음. 초기 배치는 initial=approved로 시작하고 생성 자체를 증량 사건으로 만들지 않는 기존 제안 유지. 같은 값 승인은 no-op, WORKOUT의 작업 문맥이 실제 바뀐다면 해당 Session만 changed이고 사건은 없음. 삭제 루틴/배치 신규 승인은409 APPROVAL_TARGET_INACTIVE, 오늘만 변경은 허용. Initial·기존 수행·기존 승인 사건 변경 없음.

이력/평가 DTO는 `{session,initialPlan,appliedPlan,completionAssessment,weightExercises,performedSets,cardioActivities,activeCheckpoint,currentAssessment,summary}`를 기본으로 한다. `summary={completedSetCount,volumeKg:Decimal,elapsedMs,cardioCompleted,cardioTotal}`. volume은 현재 유효 완료 웨이트 weight×reps 합, 실제 수행 원본에서 계산한다. 종료 후 현재 평가/차트만 달라지고 completionAssessment는 바꾸지 않는다.

추가 GET 제안은 모두 no-store·principal 범위이며 **기존 R 두 GET의 DTO·query·오류를 바꾸지 않는다**.

| Method/path | 요청 → 직접 성공 DTO | 동시성·오류 |
|---|---|---|
| GET `/api/v1/exercises/{id}` | UUID → `{userId,dataGeneration,exercise:{id,name,resourceVersion,createdAt,updatedAt}}` | 편집 version 전용, 미존재/foreign/삭제404 |
| GET `/api/v1/sync/bootstrap` | 없음 → `{userId,dataGeneration,exercises(versions),settings(version),routines,schedule,memos,activeSessions,cursor}` | 한 REPEATABLE_READ snapshot. R은 version 없는 기존 형태 유지 |
| GET `/api/v1/routines`, `/{id}` | 목록 cursor?/limit? 또는 UUID → `{userId,dataGeneration,data}` | 상세 routine/배치/출처 versions, placement-options는 삭제 후보 ID/이전 승인값 제공 |
| GET `/api/v1/routines/{id}/placement-options?exerciseDefinitionId=...` | 정의 UUID → scope+`{active,restorable}` | 동일 정의/루틴 후보만, 동명 매칭 금지 |
| GET `/api/v1/workout-sessions`, `/{id}` | fromDate?/toDate?/cursor?/limit? → scope+page/detail | 목록 FINISHED 비삭제, 날짜는 workoutDate 포함 범위. 상세 자체 snapshot 일관 |
| GET `/api/v1/workout-sessions/{id}/assessment` | UUID → scope+`{completionAssessment,currentAssessment}` | version/평가시점 포함, 과거 snapshot과 현재 평가 구별 |
| GET `/api/v1/routines/{id}/weight-placements/{placementId}/progress` | UUID → scope+`{approvedWeightKg,benchmark,latestComparable,advice}` | 공식 선택 D-07 전 미구현 |
| GET `/api/v1/progress-change-events`, `/api/v1/workout-sessions/{id}/record-change-events` | 필터/커서 → scope+`{items,nextCursor}` | 본인 사건만; before/after 허용 도메인값만 |
| GET `/api/v1/sync/changes?cursor=...&limit=...` | 커서 → §8.3 ChangePage | 만료410, 형식400, 구세대409 |
| GET `/api/v1/workout-operations/{operationId}?dataGeneration=...` (N-01) | UUID/Counter → §5의 저장 ACK | 부작용 없음, 미확인404, 구세대409 |

일정/메모의 개별 GET 후보는 008-0B와 같고 편집 version은 bootstrap 또는 이 신규 GET으로 획득한다. 목록 기본50/최대100, 세션 `workoutDate DESC,startedAt DESC,id DESC`, 사건 서버시각 DESC/id DESC의 keyset 권장. 커서는 필터/owner/generation에 바인딩하고 다음 페이지 전 변경으로 재조회 필요할 수 있다. 전체 누적 이력을 bootstrap에 싣지 않는다.

### 4.6 공식 평가 경계

비교 문맥=`{routineId,sourcePlacementId,exerciseDefinitionId,plannedSetCount,minReps,maxReps,approvedWeightKg,unit:"KG"}`. 이름/sessionExerciseId는 배치 계보 key가 아니다. 평가 순서는 Initial 원계획→현재 유효 수행→원계획 웨이트 전체 완료→종목별 정상 승인/세트 수/반복목표 문맥→한 과거 세션 선택이다.

`Assessment={status:PROVISIONAL|FINAL,ruleVersion,sessionVersion,eligible,reasonCodes,comparisonContext,actualReps,benchmark:{sessionId,sessionVersion,reps}|null,latestComparable:{sessionId,sessionVersion,reps}|null,advice}` 권장. 제외 사유는 INCOMPLETE_INITIAL_WEIGHT_PLAN/SET_COUNT_MISMATCH/EXTRA_VALID_PERFORMANCE/TEMPORARY_WEIGHT/MIXED_WEIGHT/NO_APPROVED_CONTEXT/SESSION_DELETED 등 안정 enum이다. 완료 이전 PROVISIONAL을 공식 기준으로 등록하지 않는다.

P 사례는 §2 그대로 보장한다. **D-07**: 비교 가능한 완료 과거 세션 중 합계 reps DESC→endedAt DESC→sessionId ASC 권장, 대안은 동률 때 이전 기준 유지(데모에 가까움). 배열은 선택된 한 세션 ordinal 순이며 최댓값 합성 금지. 과거 판정은 event endedAt≤대상 startedAt 권장, 늦은 serverReceivedAt 정렬로 대체하지 않는다. 단 시계 이상은 D-10이 필요하다.

추가 승인 대상: 목표 밖 실제 reps도 동일 문맥 후보로 둘지, 다른 종목 추가 수행의 자격 제외 범위가 종목별인지 세션 전체인지, 수행 후 삭제→승인 시점 판단이다. 목표 상한 전세트 충족 INCREASE/어느 세트 하한 미달 DECREASE/그 밖 MAINTAIN 권고는 자동 무게 변경이 아니며 정확한 후보 범위 결정 전 구현하지 않는다. 서버/React는 같은 정책 fixture로 각각 검증할 순수 평가 함수를 사용한다. benchmarks 테이블·새 PR/1RM 공식을 추가하지 않는다.

## 5. operationId·멱등 영수증·충돌 계약

### 5.1 식별·생성·봉인

operationId는 **로컬 의도 생성 시** 발급, Outbox PK 일부이자 서버 멱등 key다. Request ID는 개별 HTTP 요청/조회/재송신의 추적값이다. 한 operation에 여러 Request ID가 존재한다. 어느 ID도 인증 증명이 아니다.

명령 생성 tx에는 owner/generation/당시 epoch(또는 명시 권한 선행 명령), 도메인 UUID, 사용자의 값, occurredAt, 선행 operation IDs를 저장한다. 서버 version을 오프라인에서 추측하지 않는다. 의존 ACK를 얻으면 처음 송신 직전에 그 ACK로 설명되는 expectedVersions·참조 사건 ID를 확정하여 **봉인본을 IDB에 커밋한 뒤** 송신한다. 명시 선행 APPROVE ACK의 사건 ID를 바인딩하는 것과 새 서버 값을 조회해 충돌을 덮는 것은 다르다.

구 epoch 의도를 최신 epoch로 교체하지 않는다. START/정상 이전의 명시 선행 권한만 해석할 수 있다. 첫 송신 뒤 payload/type/occurredAt/expectedVersions/generation/epoch/IDs는 불변. 수정 의도는 새 operation이며 원래 불확실 명령의 적용을 확인한 뒤에만 만들 수 있다.

### 5.2 fingerprint의 정확한 입력 (D-04)

`SHA-256(UTF8("overload-command-v1\n" + principalUserIdLowercase + "\n" + JCS(CommandRequest)))`의 소문자64 hex 권장. type/schemaVersion/operationId/expectedUserId/dataGeneration/expectedVersions/sessionControl/occurredAt/payload 전체를 포함한다. deviceBindingRef/소스 evidence 공개 참조는 포함, 쿠키/CSRF/개인키/재시도횟수/Request ID/서버 수신시각/로컬 의존 메타데이터는 제외한다.

서버에서 재계산하며 클라이언트 해시를 신뢰하지 않는다. 허용 enum/필드·null을 schemaVersion별 고정, UUID 소문자, canonical Counter/Decimal/Instant만 수용한다. 배열은 도메인 순서 유지(expectedVersions만 계약 순서), 문자열 Unicode를 trim/NFC/대소문자 변환하지 않는다. 객체 중복 key/알 수 없는 필드/고립 surrogate·비정상 수치는 거절한다. JCS에 decimal/BIGINT를 JSON number로 맡기지 않는다. 단순 JSON.stringify/Map 정렬만을 RFC 구현이라 부르지 않는다. [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)

객체 key 순서/JSON 공백만 다른 정상 요청은 같은 의미와 fingerprint, 도메인 배열 순서·null/값 차이는 다른 fingerprint다. 서버는 계약대로 정규 형식을 요구하며 다른 소수/UUID 표기를 재전송 때 몰래 보정하지 않는다. 한글·결합문자·이모지·escape·null·0kg·큰 Counter·배열·다중버전 golden vectors가 승인/구현 게이트다. 신규 JCS 라이브러리는 아직 선택/추가하지 않는다.

### 5.3 원자적 서버 알고리즘

권장 READ_COMMITTED 쓰기 tx는 `workoutTransactionManager` 하나만 시작한다. 예상치 못한 JPA auto-flush가 범위검증 전 실행되지 않도록 쓰기 객체 로딩/변경 순서를 통제한다.

1. tx 밖에서 기존 Security 인증/CSRF, 크기·JSON·type 형식과 expectedUserId=principal.userId 전제조건을 확인한다. principal userId로 tx 안 `users` 행을 **가장 먼저 FOR UPDATE**하고 현재 generation 확인. 부재401, 구세대409. 이전 세대 영수증부터 공개하지 않는다.
2. 같은 tx에 `(user_id,data_generation,operation_id)` **NOT DEFERRABLE UNIQUE/PK**를 이용해 `sync_operations` 예약을 `INSERT ... ON CONFLICT ... DO NOTHING RETURNING`으로 시도한다. 예약은 PROCESSING이지만 **독립 커밋하지 않는다**. user 락이 정상 경로를 직렬화하고 DB UNIQUE가 누락 경로/경쟁을 최종 방어한다.
3. insert가 안 됐으면 **다음 SELECT 문**으로 성공 영수증을 확인한다. 같은 fingerprint면 기존 결과 반환, 다르면409 OPERATION_ID_REUSED. 이미 커밋된 성공의 경우 현재 resource version/epoch/삭제 여부를 다시 적용 조건으로 검사하지 않는다. READ_COMMITTED의 ON CONFLICT 경쟁은 기존 tx 종료를 기다릴 수 있고 같은 statement snapshot에서 행이 안 보일 수 있으므로 한 CTE의 INSERT+SELECT만으로 결과 존재를 단정하지 않는다. [PostgreSQL isolation](https://www.postgresql.org/docs/18/transaction-iso.html), [INSERT](https://www.postgresql.org/docs/18/sql-insert.html)
4. 신규 예약이면 본인 자원/참조·generation·expectedVersions·tombstone·device/epoch·도메인 조건을 검증하고 실제 변경·사건을 적용한다. 신규 ID를 다른 계정/세대 행으로 upsert하지 않는다.
5. 변경한 각 루트의 version을 정확히 한 번 올리고 JPA flush/필요 native SQL 정합성을 확인한다. no-op면 변경 version/feed 없음. sync-state revision과 한 변경 batch를 같은 tx에서 기록한다. no-op 성공도 영수증은 기록한다.
6. 최종 result/resultVersions/fingerprint/appliedAt/changeCursor를 예약에 채워 SUCCEEDED로 만든다. **미완성 예약이 커밋되지 않도록** handler는 완료 실패 시 rollback-only/예외를 보장한다. 추가 방어로 이 테이블에만 deferred constraint trigger로 commit 시 SUCCEEDED+결과 필수 검사를 두는 N안 검증을 권장한다. trigger는 최초 INSERT 이벤트의 PROCESSING 값을 그대로 검사하지 않고 그 key의 최종 행을 확인해야 한다. 이는 범용 이벤트 시스템이 아니다.
7. tx 실행이 **정상 커밋 반환한 후** Controller가 ACK 직렬화. 검증/flush/제약/optimistic 오류 또는 DB가 확정 거절한 commit은 도메인·사건·version·revision·영수증·예약 전부 롤백한다. 단 **commit 중 연결이 끊긴 경우는 전체 성공 또는 전체 rollback 중 어느 쪽인지 미확인**일 수 있다. 예외가 왔다는 이유로 미적용을 확정하지 않고 receipt 확인으로 해결한다. 부분 도메인만 커밋하는 것은 허용되지 않는다. DB exception이 난 tx를 catch하고 재조회/성공 반환하지 않는다. 실패 매핑은 tx 바깥이다.

appliedAt과 batch의 committedAt 이름은 서버가 커밋 직전 기록한 논리 적용시각이다. 정확한 PostgreSQL 물리 commit instant라고 주장하지 않는다. 순서는 revision으로 판단한다. 응답 직렬화/네트워크/Spring Session 후처리가 실패해도 도메인 커밋이 이미 성공했을 수 있다.

### 5.4 경쟁·삭제·세대·실패의 결과

| 상황 | 권장 결과와 불변식 |
|---|---|
| 동일 key·동일 fingerprint의 동시 요청 | 첫 성공 tx 1개, 뒤 요청은 기다린 뒤 REPLAYED. 사건/version/feed 추가 없음 |
| 동일 key·다른 fingerprint | 성공 예약 winner 기준 loser409 OPERATION_ID_REUSED. 처음 요청이 rollback했다면 성공 binding은 없음(아래 한계) |
| 첫 요청 처리 중 롤백/서버 crash | 도메인+예약 모두 없음. 경쟁 요청/동일 봉인 재시도가 신규 처리 가능. 별도 PENDING 영수증이 영구 잔류하지 않음 |
| DB 반영 후 응답 유실·timeout·abort | UNKNOWN. 같은 봉인본 재전송/결과 GET으로 확인. 새 ID로 재생성 금지 |
| 성공 뒤 자원이 삭제됨 | 같은 현재세대 영수증은 기존 성공 확인, 자원을 다시 생성/복원하지 않음. 최신 resource GET은404일 수 있음 |
| 성공 뒤 version/ownershipEpoch 증가 | 영수증은 원래 결과. 과거 ACK의 version/epoch로 최신 cache/제어권을 되돌리지 않음 |
| dataGeneration 변경 | 영수증 조회/재전송 모두409 DATA_GENERATION_MISMATCH. 구세대 적용 결과를 새세대에 공개·재실행하지 않음 |
| 다른 계정 로그인 | principal별 별도 key, GET은 요청 operation의 다른 owner 존재를 노출하지 않음. 클라이언트는 애초 old queue 송신 차단 |
| no-op | 성공 영수증과 changed=false, 원래 version 그대로, changeCursor=null. 같은 값 무게 승인 사건 없음 |
| 현재세대 성공 영수증/필수 결과 훼손 |500, 리소스 상태를 보고 성공 추측/재적용 금지 |

**N-02 권장: 성공/no-op 영수증만 저장**한다. 따라서 거절/롤백된 명령의 동일 ID가 영구 바인딩되거나 같은 실패를 영원히 재현한다고 약속할 수 없다. 예: EXERCISE_IN_USE 거절 후 참조가 사라지면 동일 요청이 나중에는 성공할 수 있다. 클라이언트는 확정4xx를 자동 재시도하지 않고 수정/재시도 의도는 새 ID로 표현한다. 성공한 동일 ID에 다른 payload를 주는 충돌은 항상 거절한다. **실패한 ID까지 영구 다른 payload 거절**이 필요하면 별도 내구 접수/실패 기록 설계가 필요하다. 그 경우 도메인 tx와 분리된 레지스트리·불완전 접수 복구·보존 비용이 생기므로 이번 권장안에 몰래 포함하지 않는다. 사용자 결정 항목이다.

### 5.5 응답 미수신 결과 조회 (N-01)

`GET /api/v1/workout-operations/{operationId}?dataGeneration={Counter}`. principal·현재 generation을 같은 readOnly snapshot에서 확인한다. 성공200은 저장된 CommandAck(result는 당시값, outcome=REPLAYED) 직접 DTO, no-store. device write 권한이 없어도 동일 owner/현재세대의 과거 성공 확인은 허용하되 제어권 부여는 아니다.

- 404 OPERATION_NOT_FOUND: 그 snapshot에서 **커밋된 성공을 찾지 못함**. 요청 미도착/진행 중/rollback/기존 거절을 구별하지 못한다. definitive NOT_APPLIED/안전한 새ID 재실행 승인으로 해석하지 않는다. negative cache 금지.
- 409 DATA_GENERATION_MISMATCH: old queue 격리. 현재세대를 old 명령에 붙이지 않는다.
- 400 INVALID_REQUEST: UUID/Counter/query 형식, 401/403 기존 경계, 500/503은 §9.
- 필요하면 미확인 뒤 제한 backoff 후 **동일 봉인 POST**를 한다. 그 POST의 UNIQUE/receipt가 원래 요청과 경합해도 한 번만 적용한다. 무기한 polling 대신 사용자에게 커밋 미확인을 표시한다.

응답의 owner/generation/operationId/fingerprint/schema/result 타입을 Outbox 봉인본과 검증해야 ACK로 수용한다. 리소스 GET의 값 일치나 Request ID 존재는 특정 명령 성공 증거가 아니다. outcome만 APPLIED→REPLAYED로 달라질 수 있으며 result/resultVersions/appliedAt은 원본 불변이다. 커서 서명키 교체에 따른 opaque cursor 재발급이 필요해도 논리 revision은 바뀌지 않는다.

### 5.6 보존·조회·만료 (N-02)

MVP 권장은 **활성 generation의 성공 영수증·삭제 ID tombstone을 단기 TTL로 정리하지 않는 것**이다. 상세 result를 포함해 조회 가능하게 유지한다. 보존 작업은 이번에 구현하지 않는다. 계정 존속 기간의 무조건 영구 백업 약속도 아니다. 초기화/탈퇴 시 구세대 차단 후 운동 데이터/영수증 파기는 별도 운영 계약으로 처리한다.

향후 receipt 본문을 정리하려면 key+fingerprint+결과만료 표식은 dedupe 장벽으로 보존하고 POST/GET을410 OPERATION_RESULT_EXPIRED로 거절하는 새 계약을 먼저 승인받아야 한다. 만료 key를 미존재로 취급해 재적용해서는 안 된다. 최소 N일 보존 후 key까지 삭제하는 안은 무기한 오프라인 재전송과 양립하지 않는다. feed cursor 만료는 영수증 만료와 독립이다. schemaVersion=1 영수증을 조회할 deserializer/응답 지원은 해당 보존 기간 동안 유지해야 한다.

## 6. PostgreSQL·Flyway·JPA 스키마 초안

이 절은 논리/컬럼 명세이며 실행 가능한 migration/Entity 코드를 작성하지 않는다. 실제 migration 번호·제약명은 구현 시 그때 HEAD의 마지막 버전을 확인해 정한다. 지금은 V8 다음 확장 순서만 제안한다.

### 6.1 공통 키·버전·삭제

- `Scope=(user_id UUID,data_generation BIGINT≥0)`. 독립 루트는 V2와 같이 Scope→users(id,data_generation) FK. 각 참조 경계에서 `(Scope,id)` UNIQUE와 복합 FK로 같은 owner/세대를 강제한다. 현재 FK는 즉시 검사이며 users에 여러 과거 generation 행이 있는 구조가 아니다.
- id는 UUID PK, 다른 테이블의 UUID와 같다는 것만으로 같은 객체로 보지 않는다. 각 리소스 생성은 대상 테이블의 전체 owner/세대/tombstone PK 충돌 시409 ID_UNAVAILABLE만 노출한다.
- 루트 version은 BIGINT NOT NULL DEFAULT0 CHECK≥0, JPA `@Version Long` 후보. 자식은 부모 version을 공유하고 별도 @Version을 기계적으로 붙이지 않는다. 자식 변경도 받는 루트에 `last_mutation_operation_id UUID nullable`을 두어 실제 변경당 서로 다른 operationId로 touch하는 안을 권장한다. memo는 별도 memo_version, settings/exercise는 resourceVersion으로 노출.
- created_at/updated_at TIMESTAMPTZ, 삭제 가능 리소스 deleted_at nullable. created immutable, 서버가 변경 시각 명시 갱신. 일반 삭제는 tombstone, FK ON DELETE CASCADE/ORM REMOVE/orphanRemoval 기본 금지.
- 사건은 서버 UUID, operation_id 연결, 변경 전후값/서버 적용시각. 서버 승인/정정 사건은 추가만 가능. 세션/배치 soft delete는 사건이나 출처 FK를 끊지 않는다.

### 6.2 테이블·관계·제약

| 테이블(기존 변경/신규) | 컬럼·키·FK 초안 | CHECK/UNIQUE·삭제·인덱스 |
|---|---|---|
| exercise_definitions (기존 확장) | V2 유지+version default0, deleted_at, memo_version default0 | 기존 owner/id UNIQUE 유지. name UNIQUE 금지. `(Scope,id)` 활성 목록 인덱스/조건 검토. memo_version≥0 |
| user_workout_settings (기존 확장) | 기존 user_id PK/Scope FK/세 휴식 컬럼 유지+version default0 | 휴식>0/version≥0. 가입 JDBC INSERT는 default 사용, 기존 값/시각 변경 없음 |
| routines | id PK, Scope FK/UNIQUE(Scope,id), name, version, deleted_at, times | 비공백 name, version≥0. `(Scope,id)` 비삭제 목록 |
| routine_weight_placements | id PK, Scope,routine_id,exercise_definition_id,position,initial_weight,approved_weight,set_count,min_reps,max_reps,deleted_at,times | FK(Scope,routine_id)→routines, FK(Scope,exercise_definition_id)→definitions. UNIQUE(Scope,routine_id,id)도 출처검사용. set_count>0,1≤min≤max, kg≥0/유한. 동일정의 복수배치 UNIQUE 금지. 활성순서 제약 §6.3, `(Scope,exercise_definition_id,routine_id)` 참조검사 인덱스 |
| routine_cardio_placements | id PK, Scope,routine_id,position,plan JSONB v1,deleted_at,times | 루틴 복합 FK, UNIQUE(Scope,routine_id,id), kind/계획 DTO 검증. 웨이트/유산소 각각 순서, 혼합정렬 신규 기능 없음 |
| workout_schedule_state / weekly_schedule | state PK(Scope),version; 자식 PK(Scope,weekday),routine_id | state→현재 users, schedule→state 및 routine 복합 FK, weekday0..6. 일정 비어도 version state는 필요, 최초 expected0. 삭제 루틴 참조 정리 같은 tx |
| exercise_memos | PK(Scope,exercise_definition_id),text,deleted_at,times | 정의 복합 FK. version은 정의 memo_version이 유일 원본. 미생성/null과 text="" 구별. tombstone에도 memo_version 유지 |
| workout_sessions | id PK, Scope,source_routine_id,version,ownership_epoch,writer_device_id,phase,started_at,workout_date,started_time_zone,ended_at,4 JSONB,deleted_at,times | UNIQUE(Scope,id). 소스 루틴 복합 FK(새 세션은 필수), device binding 참조. phase enum, epoch/version≥0, FINISHED↔ended_at/Applied seal/최초 assessment 존재, 진행은 null. `(Scope,workout_date DESC,started_at DESC,id DESC)` 비삭제 완료 이력 인덱스; `(Scope,phase)` 진행 조회. 계정당 진행세션1개 UNIQUE를 새로 만들지 않음 |
| session_weight_exercises | id PK, Scope,session_id,exercise_definition_id,source_placement_id nullable,name_at_start,added_during_session | FK(Scope,session_id)→sessions, 정의 복합 FK. UNIQUE(Scope,session_id,id). source는 같은 정의·루틴 문맥 검사; source placement 복합 FK 유지, §6.3 보완. `(session_id,id)` 조회 |
| performed_sets | id PK, Scope,session_id,session_exercise_id,ordinal,weight_kg,reps,performed_at,temporary_weight,approval_event_id nullable,deleted_at,times | FK(Scope,session_id,session_exercise_id)→session exercise. FK 승인사건 범위검사. active UNIQUE(session_exercise_id,ordinal) WHERE deleted_at IS NULL, ordinal/reps≥1,kg≥0/유한. `(session_id,session_exercise_id,ordinal)`·삭제 제외 조회. 부모 세션 soft delete시 물리 cascade 없음 |
| session_cardio_activities | id PK, Scope,session_id,source_placement_id nullable,status,started_at,run_since,paused_at,accumulated_ms,timer_ms nullable,active_ms nullable,corrected nullable,distance_km nullable,average_bpm nullable,completed_at nullable | 세션 복합 FK/source cardio FK, UNIQUE(Scope,session_id,id). 상태별 null/시간·거리/BPM CHECK. UNIQUE(session_id) WHERE status IN('RUNNING','REVIEW')로 바쁨 제한. COMPLETED에서만 result 필드, SKIPPED는 결과null. 세션 조회 인덱스 |
| progress_change_events | id PK, Scope,routine_id,placement_id,related_session_id nullable,previous_weight,next_weight,source,occurred_at,server_recorded_at,routine_name_at_change,exercise_name_at_change,operation_id | 루틴+배치+선택 session 복합 FK, placement/정의 근거 검증. 전후kg≥0/서로 다름, source enum. Scope/operation→receipt(동일tx 예약행), `(Scope,placement_id,server_recorded_at,id)` |
| record_change_events | id PK, Scope,session_id,session_exercise_id nullable,performed_set_id nullable,change_type,before_values/after_values JSONB,server_recorded_at,operation_id | 해당 세션/자식 관계 복합 FK. DTO 허용 실제값만 JSON, 원문 요청 없음. 세트 정정/취소/삭제/복구·세션삭제 사건만, 클릭 로그 없음. `(Scope,session_id,server_recorded_at,id)` |
| sync_operations | **PK(Scope,operation_id) NOT DEFERRABLE**, fingerprint(64hex),command_schema_version,type,state,ack_schema_version,result_versions/result JSONB,applied_at,change_revision nullable | fingerprint NOT NULL/형식, state PROCESSING/SUCCEEDED와 commit 완료 검사 §5.3. 성공/no-op만 커밋. result에 오류/인증 비밀 저장 금지. resource FK를 result JSON에 걸지 않아 삭제 뒤 영수증 조회 가능 |
| workout_sync_state | PK(Scope),committed_revision BIGINT default0 | 현재 users 복합 FK, revision≥0/overflow 거절. 최초 쓰기 지연 생성, 데이터변경당 한 번 증가 |
| workout_change_batches | PK(Scope,revision),operation_id,changes JSONB,server_recorded_at | Scope/operation→receipt, UNIQUE(Scope,operation_id), revision>0, changes는 resource 참조/version/UPSERT·DELETE·RESTORE만. feed가 수행 원본이 되지 않음 |
| workout_device_bindings (D-08 선택 시) | 공개 device_id UUID, user_id, 공개 binding_ref, proof_hash/검증정보,created/revoked_at | PK/UNIQUE(user_id,device_id), ref 유일. credential 원문 저장/로그 금지, Spring SESSION ID FK 금지. 계정 별 바인딩 수명·회전은 §7.4 승인 대상 |

모든 snapshot JSONB는 객체/지원 snapshotSchemaVersion CHECK와 서버 typed schema 검증을 병행한다. JSON 내부 ID 일치·다중 행 원계획 완료·시간대 날짜 일치·owner 인가는 단일 CHECK로 보장한다고 주장하지 않는다. 특별한 숫자 NaN/±Infinity는 DTO에서 거절하고 DB numeric CHECK도 유한 범위를 명시하여 `>=0`만으로 NaN 허용이 남지 않게 한다. volume 합계는 kg 컬럼 자릿수로 자르지 않고 충분한 BigDecimal/NUMERIC로 계산한다.

memo_version을 정의 행에 둔 것은 메모 미생성 상태에도 실제 DB counter를 제공하기 위한 N안이다. 메모 행/JPA를 저장하고 counter만 동일 tx의 조건부 SQL로 증가시킨다. 이 컬럼은 ExerciseDefinition의 JPA 쓰기에서 제외(insertable/updatable=false)하여 정의 이름 변경이 오래된 memo counter를 덮지 못하게 한다. 메모만 변경할 때 Exercise resourceVersion은 증가하지 않고 Memo version만 증가한다. 이 예외를 지원하기 어렵다면 별도 memo state 테이블을 두는 대안으로 전환하되 승인/스키마 리뷰에서 선택한다. 정의 삭제의 memo 정리는 memo counter와 DELETE 통지를 함께 증가·기록한다.

### 6.3 참조·순서·generation에서 놓치기 쉬운 경계

1. 세션 종목의 source_placement_id가 non-null이면 `(Scope,sourceRoutineId,sourcePlacementId,exerciseDefinitionId)`가 맞아야 한다. 권장 DB 강화는 placement에 이 튜플 UNIQUE를 추가하고 session_weight_exercises에 source_routine_id를 제한적으로 보관하여 복합 FK를 건다. `(Scope,session_id,source_routine_id)` 세션 UNIQUE/FK로 세션 출처와도 일치시킨다. 새 도중추가 종목은 source placement/routine 둘 다 null CHECK. 이름/현재 루틴 탐색으로 과거 출처를 재구성하지 않는다. cardio 출처도 같은 원리다.
2. performed approval_event FK는 `(Scope,eventId)`로 foreign owner를 차단한다. 같은 배치/정의/승인kg·관련 session의 실제 유효 승인 문맥은 service가 검증한다. 배치 생성 당시 초기 승인에는 event가 없을 수 있으므로 무조건 NOT NULL로 만들지 않는다. 진행 중 승인 ACK 사건을 뒤따르는 세트 명령이 참조한다.
3. 배치 순서 swap은 단순 UPDATE라 해도 즉시 부분 UNIQUE에서 중간 충돌할 수 있다. **N-03 권장**: `live_position`을 deleted_at=null일 때 position, 아니면 null인 **STORED generated column**으로 두고 일반 UNIQUE(routine_id,live_position)를 DEFERRABLE INITIALLY IMMEDIATE로 선언하는 안을 검증한다. 재정렬 명령만 해당 제약을 defer, 전체 순서 갱신·flush 후 IMMEDIATE로 재검사한다. 삭제 후보는 null이므로 같은 옛 position 보존 가능. 같은 방식으로 두 배치 타입을 독립 적용한다. receipt PK는 defer하지 않는다. [generated columns](https://www.postgresql.org/docs/18/ddl-generated-columns.html), [CREATE TABLE](https://www.postgresql.org/docs/18/sql-createtable.html), [SET CONSTRAINTS](https://www.postgresql.org/docs/18/sql-set-constraints.html)
4. 기존 D의 **부분 UNIQUE+충돌 없는 임시 position을 통한 2단계 재정렬**도 대안이다. 더 단순한 스키마 대신 임시영역/정수 overflow·중간 CHECK·JPA stale 정합성 검증이 필요하다. 부분 인덱스 자체를 SET CONSTRAINTS로 지연할 수 있다고 가정하지 않는다. 위 generated 제안은 신규 선택이지 기존 승인 사항이 아니다.
5. V2의 users(id,generation) FK는 현재세대만 허용한다. 향후 reset에서 users generation부터 올리면 옛 자식 FK가 즉시 막는다. **사용자 락 아래 FK 의존 역순으로 구 운동행/사건/receipt/feed 정리→기존 settings 제거→users generation 증가→새 seed** 등의 순서를 별도 설계해야 한다. 현재 schema를 과거세대 무기한 보관 가능한 구조로 오인하지 않는다. 이번에 deferrable로 기존 FK를 바꾸거나 reset SQL을 작성하지 않는다. snapshot JSON만 보고 FK 검증을 생략하지 않는다.
6. 일반 delete는 source들을 물리 삭제하지 않아 FK/과거 계보를 유지한다. 실제 generation 파기는 예외적 별도 작업이며 사용자의 명시 승인과 파기 계약이 선행한다. auth/Session/OTP 테이블을 운동 삭제 cascade 그래프에 연결하지 않는다.

### 6.4 JPA와 직접 SQL·migration 순서

Routine/WorkoutSession/정의/설정은 제한적 Entity+Repository, 하위는 단방향 LAZY 또는 scalar FK. users는 Entity로 전환하지 않는다. scope scalar와 JoinColumns 공유시 중복 writable 금지. 부모 to-many 없이 범위 조회 우선, 서비스 tx에서 DTO 완성, OSIV=false 유지. assigned UUID 신규는 명시 persist/Persistable 검증, detached save/merge로 tombstone/foreign ID를 삽입하지 않는다.

직접 SQL 적합 구간: user 락·현재세대, receipt ON CONFLICT/RETURNING, revision 할당, 순서 제약 defer/재정렬, 명시 조건부 root version의 대안. 모두 **동일 JPA tx의 DataSource 연결**에 참여한다. JPA 수정 뒤 SQL 읽기 전 flush; SQL이 이미 로드한 Entity를 바꾸면 미flush 변경을 먼저 처리한 후 refresh/clear·재조회. bulk SQL 뒤 @Version이 자동 증가한다고 가정하지 않는다.

| 구현 시 migration 묶음 | 내용 / 통과 조건 |
|---|---|
| 첫 확장 (V8 다음 실제 번호) | 기존 정의/설정 version·tombstone·memo_version의 additive default, receipts/sync-state/batches. 기존 JDBC 가입·R 조회·V1~V8 checksum 불변 |
| 보조 편집 묶음 | exercise_memos와 실제 memo counter 경로. 009-2A의 정의/설정/메모 편집이 의존할 테이블부터 도입. 활성 루틴 참조가 필요한 정의 삭제는 계획 묶음 이후 활성화 |
| 계획 묶음 | routines/배치/일정·복합 FK/순서 제약, 편집기 승인용 progress_change_events. 세션 참조 컬럼/FK·WORKOUT source 허용은 다음 세션 묶음에서 additive 확장. 중량 D-01·숫자 D-02·D-09 승인 선행 |
| 세션·사건 묶음 | sessions/session exercises/sets/cardio/record events, progress 사건의 related_session_id/FK와 source 확장, snapshot·epoch·제어권. 참조 cycle는 테이블 생성 후 FK 추가 순서로 해결. 사건/세트 FK와 승인 atomic gate |
| 제어권·오프라인 근거 선택분 | 선택된 device binding 구조, N-04의 계획 근거 저장이 필요하면 해당 구조. 자동 새 보안체계/이벤트 저장소 도입 없음 |

정확한 분할/파일명은 구현 리뷰에서 확정한다. 빈 Testcontainers DB에 V1→새 버전, 기존 V8 합성 데이터 보유 DB에 upgrade 두 경로를 검사한다. 기존 종목 UUID/name/timestamps/seed·auth 직렬화 유지, 새 default 읽기/쓰기와 JPA validate 확인. 전체 테스트의 'Entity 정확히2개' assertion은 추가된 허용 Entity 목록으로 명시 확장하며 검사를 없애지 않는다. 파괴적 축소/기존값 반올림/일괄 재ID는 이 로드맵에 없다.

## 7. 트랜잭션·소유권·generation·epoch 경계

### 7.1 각 값의 의미

| 값 | 권위/수명 | 검증 |
|---|---|---|
| userId | 인증 principal의 서버 계정, 계정 전환 시 다른 scope | body owner 불신, 루트/자식/참조부터 principal Scope로 조회, foreign ID404 |
| dataGeneration | users의 운동 데이터 세대, 초기0/향후 reset 증가 | 모든 쓰기는 생성 당시 값과 현재 서버값 비교. old ID/queue/cursor 자동 이관 금지 |
| version/expectedVersion | 루틴/세션 등 집계별 서버 변경 번호 | 자식 변경도 부모 +1, no-op/중복은 증가 없음. 로컬 optimistic 순번과 다름 |
| ownershipEpoch | 세션의 서버 제어권 세대 | 정상/강제 이전 때 +1, 구epoch 신규 쓰기 거절. 완료 phase/계정 세대와 다름 |
| 로컬 auth/request epoch·탭 fencing | UI 응답/로컬 송신 시도 구분 | 서버 권한이 아님. 오래된 success/error/ACK 적용 차단 |

### 7.2 잠금·version·원자성

쓰기 잠금 순서는 user→현재 generation sync-state→Routine UUID 순→Session UUID 순→자식이다. 사용자별 user 락을 커밋까지 유지하여 참조 삭제검사/생성/승인/최종화/feed를 직렬화한다. 다른 사용자까지 직렬화하지 않는다. 단점은 동일 사용자의 여러 기기 쓰기 처리량과 기존 인증의 user 락 대기다. 사용자 락 보유 중 외부 호출/SessionRepository/다른 manager 인증 서비스 호출 금지. 기존 인증의 내부 잠금 순서를 수정하지 않는다.

명시 V 비교는 user 락과 별도로 반드시 수행한다. user 락은 stale intent를 최신값으로 바꿀 권한이 아니다. 일반 루트는 JPA @Version+명시 `updatedAt/lastMutationOperationId` 같은 루트 변경을 통해 child-only 변경에도 dirty update를 한 번 만들고 flush한 최종 버전을 ACK에 쓴다. 임의 시각 동일값만 touch하여 dirty 검출을 놓치지 않도록 검증한다. 루트/자식 둘 다 바뀌어도 루트 +1만 보장한다. OPTIMISTIC_FORCE_INCREMENT는 commit 지연 증가가 있어 ACK에 추측값을 넣지 않는다. SQL 조건부 UPDATE RETURNING을 선택하면 동일 루트를 JPA로 다시 증가시키지 않는다.

검증용 조회를 먼저 마친 뒤 변경을 적용하고 루트 touch/최종 flush를 배치한다. 중간 조회/native 쿼리의 auto-flush로 같은 루트가 여러 번 갱신되지 않게 하며, receipt/feed 저장 후에는 루트를 다시 dirty하게 만들지 않는다. 여러 flush가 필요한 경로는 실제 SQL과 최종 ACK로 +1을 입증하거나 조건부 SQL 전략을 선택한다.

루틴+일정·운동중승인 Routine+Session 등 다중 집계 명령은 영향을 주는 모든 기존 루트 expectedVersions 필수. 정의 삭제의 memo 제거처럼 명령 자체에 명시한 종속 삭제효과는 예외로 문서화하며 UI의 무관 편집을 한 명령에 자동 추가하지 않는다. 최종 version/사건/receipt/feed 검증·저장의 확정 실패는 전체 롤백이며 commit 응답 유실은 §5.3의 전체 결과 미확인이다. Spring Session의 session touch/폐기와 운동 데이터는 별개 tx다.

동일 DataSource의 직접 JDBC 참여는 [JpaTransactionManager 공식 계약](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)과 008-0C의 실제 PID/txid 관측을 근거로 한다. Session은 [공식 JDBC 트랜잭션 설명](https://docs.spring.io/spring-session/reference/configuration/jdbc.html) 및 기존 구현처럼 REQUIRES_NEW다. 두 manager를 임의 중첩하거나 운동 tx 안에서 SessionRepository를 호출하도록 확장하지 않는다.

### 7.3 주요 경합의 결정 결과

| 경쟁 | 기대 결과 / 후속 행동 |
|---|---|
| 동일 신규 resource ID 생성 두 건 | 한 명령만 생성, 다른409 ID_UNAVAILABLE. 같은 operation이면 receipt replay |
| 정의 삭제 vs 루틴 배치 추가/복원 | user 락 후 최신 활성상태/참조 확인. 추가 선행이면 삭제 EXERCISE_IN_USE, 삭제 선행이면 추가 RESOURCE_DELETED |
| 배치 reorder vs restore/remove | V(Routine)로 한 쪽 성공/한 쪽409. 부모/활성 순서 제약 둘 다 유지 |
| FINISH(v) vs COMPLETE_SET(v) | 먼저 user 락 얻어 성공한 쪽 v+1, 뒤쪽409 VERSION_CONFLICT. FINISH 선행이면 새 COMPLETE는422 SESSION_FINISHED/상태 오류. 유실 수행은 복구 선택 가능, 봉인 계획 변경 불가 |
| APPROVE vs FINISH / 루틴 삭제 | 모든 관련 version·active·phase 확인. 부분 승인/사건 단독 커밋 불가. 삭제 루틴 신규 승인 금지 |
| 재전송 A vs 신규 B | 성공 A는 resource V보다 receipt 먼저 확인해 replay. 새 B는 명시 의존 ACK의 version 필요. 서버가 HTTP 도착순을 사용자 의도 순서로 추측하지 않음 |
| transfer vs old epoch 명령 | 먼저 커밋한 경계 기준. old 명령 선행 커밋이면 transfer stale version409, transfer 선행이면 old 신규 명령409 OWNERSHIP_LOST. 이미 성공한 old명령은 receipt만 확인 |
| 향후 reset vs 쓰기 | 같은 user 락에서 직렬화. 쓰기 선행 성공도 reset 후 replay/GET은 generation mismatch. reset 선행이면 구세대 쓰기 부작용 없음 |
| commit 실패/DB 단절 | 원자 rollback 또는 commit 미확인. 실패 tx 재사용 금지. 500/503를 단순 미적용 확정으로 표시하지 않음 |

### 7.4 device binding과 이전 (D-08, 승인 전 구현 보류)

권장 후보는 기존 로그인 쿠키/CSRF를 유지하고, 별도 무작위 proof를 서버 등록 device에 바인딩하는 전용 HttpOnly·Secure·SameSite 쿠키다. DB에는 hash/검증자료만 저장. 공개 deviceId/bindingRef만 복제하여 writer로 가장할 수 없어야 한다. 대안 WebCrypto 기기 키는 쿠키 추가를 줄이지만 키 보존/서명/복구·Safari 검증이 커진다. 로그인 session ID에 영구 바인딩하면 재로그인/회전으로 기기 ID가 바뀌므로 비권장이다.

wire 초안: CSRF 보호 `POST /api/v1/workout-devices`는 client device UUID+등록 operationId로 등록/현재 바인딩을 확인하고 공개 ref만 반환한다. proof 발급/응답유실 때 중복등록·알 수 없는 기기를 자동 기존 writer로 승격하지 않는다. 같은 proof를 제시한 재확인은 idempotent, proof 유실 재등록은 새 기기로 취급하고 명시 transfer 필요인 안이다. **정확한 쿠키명/경로/수명/재발급·로그아웃/다중계정 보존·탈퇴 파기·등록 응답유실**은 보안 계약으로 승인받기 전 확정/구현하지 않는다. deviceBindingRef는 credential이 아니다.

`TRANSFER_CONTROL {sessionId,fromDeviceId,toDeviceId,lastAcknowledgedOperationId}`: 현재 writer proof+V/epoch, 대상 등록/같은 owner 확인, 기존 탭 생성·송신 fence 및 선행 queue ACK 확인 후 실행. `FORCE_TRANSFER_CONTROL {sessionId,toDeviceId,acknowledgePossibleUnsyncedData:true}`는 목표 기기 proof+현재 V/epoch+명시 takeover. 결과=`{sessionId,writerDeviceId,ownershipEpoch,changed:true}`. 둘 다 Session version/epoch+1+receipt/feed 한 tx. TRANSFER_NOT_READY/OWNERSHIP_LOST/VERSION_CONFLICT. 새 target proof 검증과 source 권한 검증의 차이를 handler에서 명시한다.

lastAcknowledgedOperationId 하나로 서버가 미전송 명령이 전혀 없음을 증명하지는 못한다. source는 IDB tx로 세션 freeze→의존 queue drain→barrier ACK→이전 명령 봉인 순서다. force 때 구 기기 미전송 원본을 지우지 않는다. 새 proof 발급이 일반 운동/권한 명령과 자동 원자적이라고 주장하지 않는다.

### 7.5 오프라인 START의 원계획 근거 (D-10 / N-04)

현재 R에는 루틴도 version도 없고 현재 테이블만으로 과거 루틴 계획을 검증할 수 없다. client initialPlanCandidate를 그대로 공식 원본으로 채택하거나 현재 루틴으로 몰래 대체하면 P를 훼손한다.

| 대안 | 결과·비용 |
|---|---|
| A. 현재 routineVersion이 같을 때만 START 허용 | 최소 구현, 변경되면409 PLAN_SOURCE_CONFLICT. 로컬 수행을 보존할 수 있지만 **과거 루틴으로 오프라인 시작한 정상 운동의 자동 업로드가 막힘**. 이를 완전한 오프라인 지원이라 부를 수 없음 |
| B. 불변 plan revision 이력 저장 | 예전 계획/승인/당시 이름 증거 검증 가능. 정의 이름/승인 등 참조 version의 이력 포괄·보존·삭제와 생성 비용 필요. 루틴 version만 저장하고 나머지 현재 이름을 섞는 방식은 불충분 |
| C. 서버 발행 서명 start-template evidence (권장 검토) | 루틴 상세/bootstrap이 관측한 owner/generation/루틴·정의·승인 version/내용 hash/schema를 서명한 근거를 제공. 저장소 대형 이력 없이 오프라인 후보 검증. 서명키 운영·회전/유효성·과거활성 참조 계약이 추가됨 |

C의 제안 DTO=`{formatVersion:1,userId,dataGeneration,sourceRoutineId,sourceRoutineVersion,templateHash,evidenceId,keyId,signature}`. 서버가 정의/배치 이름·목표·승인문맥을 포함한 canonical template을 생성해 hash/sign, client는 이를 고정 보존하고 session 종목/activity의 새 UUID 매핑과 시작시각만 추가한다. 같은 snapshot을 다시 만드는 GET은 DB 도메인 쓰기를 하지 않는다. evidence가 생성한 시작시각을 증명하지는 않는다. 타계정/세대/내용변조/다른 배치 재사용은 거절한다.

예전 evidence의 루틴 수정만으로 로컬 시작 계획을 치환하지 않는 안을 권장하지만 **소스 루틴/정의/배치가 삭제된 뒤 늦게 START가 도착한 경우의 수용 범위, evidence 유효기간/키회전/원본 파기**는 미결이다. 단기 만료가 장기 오프라인을 차단할 수 있다. 현재 source V 불일치 허용 시 START의 `routineVersionAtStart`는 검증된 역사적 출처이지 현재 Routine expectedVersion이 아님을 별도 schema로 명시해야 한다. source 현재활성 검사는 별도로 수행한다. N-04 승인 전 세션 쓰기 착수 게이트를 통과한 것으로 보지 않는다. 이 기능을 서명 인프라 없이 임의 완성하지 않는다.

## 8. Frontend IndexedDB·Outbox 계약

### 8.1 저장소와 의도·봉인 최소 구조

N 권장은 demo 키와 별도 DB `overload.workout.v1`(이름은 제안)·object store별 composite key다. 모든 사용자 데이터 키에 `[serverUserId,dataGeneration,...]`를 포함하고 접근 API 자체가 scope를 필수 인자로 받는다. UI는 현재 인증 scope만 연결한다. 사용자별 DB 여러 개 대안도 가능하지만 업그레이드/다중탭 조정 비용이 늘어난다. 인증 비밀·쿠키·CSRF·device proof는 이 DB에 저장하지 않는다.

| object store | key / 최소 데이터 |
|---|---|
| scopeMeta | `[userId,generation]` / schemaVersion,verifiedOwner,lastPulledCursor,dirtyRefs,scopeStatus |
| serverResources | `[userId,generation,resourceType,id]` / lastAcknowledgedVersion,serverValue,tombstone. 수정 UI 초안과 분리 |
| localWorkouts / drafts | `[userId,generation,sessionId]` / Initial/Applied local projection,완료수행·cardio/checkpoint,localRevision,기기/epoch문맥. 공식 서버 원본의 대체가 아님 |
| commands | `[userId,generation,operationId]` / createdAt,type,immutableIntent,domainIds,creationEpoch 또는 controlDependency,dependsOn[],resourceKeys[],sealedRequest 또는 null,fingerprint 또는 null,sealedAt 또는 null |
| outbox | 같은 operation key / state,attemptCount,nextAttemptAt,lastRequestId,lastSafeErrorCode,leaseOwner/leaseToken,leaseUntil |
| acknowledgedOperations | 같은 operation key / 검증 ACK/resultVersions/fingerprint,의존명령이 사용할 결과. queue 정리 후에도 선행 근거 유지 |
| recoverySources | `[userId,generation,sessionId,sourceId]` / 구epoch/충돌/거절 원본+선택결과+해결operation. 사용자 선택 ACK까지 유지 |
| writerLeases | `[userId,generation,sessionId 또는 sender]` / tabId,fencingToken,expiry. 로컬 동시주체 조정 |

1. 입력 완료 시 UUID·의도·당시 control 문맥을 준비하고 IDB readwrite tx에서 scope/fencing 재검증→도메인 projection/명령/Outbox 함께 기록한다. 각 request success가 아니라 **tx complete** 후 로컬 완료 표시. abort/quota/schema upgrade 실패는 모두 rollback, 메모리 input draft 유지, 전송 없음. IDB tx 안에 fetch/해시 Promise 등 외부 await를 끼우지 않는다. [W3C IndexedDB 2.0](https://www.w3.org/TR/IndexedDB-2/)
2. 봉인할 때 선행 ACK와 scope/fencing을 읽어 request canonical bytes를 준비한다. 준비와 저장이 분리되면 localRevision/의존 ACK가 그대로인지 CAS 검사하는 짧은 두 번째 IDB tx로 봉인+READY를 원자 저장한다. `complete` 전에 fetch 금지. crash가 나도 원래 봉인본을 재사용한다.
3. 같은 집계 명령은 선행 ACK로 순차 송신. Routine+Session을 함께 만지는 명령은 양쪽 resourceKeys에 barrier 의존을 둔다. 서로 의존하는 cycle를 생성 시 거절한다. 단순 같은 user의 독립 집계는 병렬 가능하지만 MVP sender 직렬이 단순하다.
4. 불확실 선행명령 뒤 의존명령은 blocked. 충돌 해결은 새 명령·새ID와 명시 연결. 원본 command를 수정하거나 생성 epoch를 갱신하지 않는다. 아직 봉인 전 명령도 원래 의미를 바꾸는 coalescing을 하지 않는 안을 권장한다.

### 8.2 상태·ACK·늦은 응답

권장 상태: WAITING_DEPENDENCY→READY_SEALED→SENDING→ACKNOWLEDGED, 일시불확실 UNKNOWN_RETRY, 확정거절 REJECTED, version CONFLICT, account/generation/epoch QUARANTINED. 로컬 도메인 상태(진행/완료)와 송신 상태는 별개다.

- 네트워크 단절/timeout/abort/HTML/2xx 잘못된 DTO는 UNKNOWN_RETRY. 탭 종료 시 SENDING은 재기동에서 UNKNOWN으로 회수해 같은 봉인본을 확인한다. 송신 lease 만료는 서버 미적용 증거가 아니다.
- ACK 검증 후 **한 IDB tx**로 acknowledgedOperations 저장·serverVersion 갱신·해당 Outbox 제거/ACK 표시·의존 해제를 처리한다. 이 tx 실패면 queue를 지우지 않아 재전송 가능하다. 서버 receipt가 중복 적용을 방지한다.
- localRevision이 더 새로우면 ACK는 해당 operation 확인만 한다. 최신 input/오늘만 중량/현재 타이머/현 제어권을 옛 result로 덮어쓰지 않는다. ACK 후 GET 실패는 '서버 저장됨, 최신 조회 실패'로 별도 표시한다.
- 요청 capture=`{userId,generation,authEpoch,localRequestEpoch,sessionEpoch,leaseToken,operationId,fingerprint}`와 최신 문맥을 응답 적용 전에 비교한다. 계정전환/로그아웃/epoch 변경 뒤 늦은 성공/실패는 현재 UI/새 scope를 바꾸지 못한다. 원래 scope에 대한 성공확인이 필요하면 같은 계정 재확인 후 receipt GET으로 복구한다.
- 인증 만료 시 확인된 같은 계정의 로컬 기록은 계속 가능하나 송신 중지, 같은 계정 재인증. 명시 logout은 화면/메모리·송신을 차단하고 숨겨진 old queue를 다른 로그인으로 전송하지 않는다. 다른 계정 데이터로 fallback하지 않는다.
- generation mismatch는 old scope 전체 QUARANTINED, 자동 신규 generation 복사/UUID 연결 없음. ownership loss는 해당 세션 원본과 의존명령 격리, 원본 보존 후 현 제어권에서 선택 복구. 과거 이미 성공한 receipt 확인은 제어권 획득이 아니다.

### 8.3 pull·삭제·최신 상태 복구

권장 D-05: 사용자 락 보유 tx에서 sync-state revision+1과 변경 batch를 같이 커밋. 일반 sequence/서버시각 MAX를 사용하지 않아 늦은 낮은 revision 누락을 막는다. feed는 무효화용이며 수행 전체 복제가 아니다.

`ChangeBatch={revision,operationId,committedAt,changes:[{resourceType,resourceId,resourceVersion,changeType:UPSERT|DELETE|RESTORE}]}`; `ChangePage={userId,dataGeneration,batches,nextCursor,hasMore}`. batch 기준 기본100/최대500 권장, 다중 집계 batch를 페이지 사이에서 쪼개지 않는다. cursor는 user/generation/revision/format에 바인딩된 opaque 값. 서명/HMAC을 쓰면 OTP key 재사용 없이 별도 운영키 계약 필요이며 token 자체가 인가 증명은 아니다.

클라이언트는 한 IDB tx로 삭제표식/dirty refetch 작업과 nextCursor를 저장한다. DELETE는 캐시에서 제거하되 미송신 원본은 격리 보존. refetch 실패 중 최신 동기화 완료 표시 금지. 여러 GET이 서버의 과거 단일 tx snapshot을 재현한다고 가정하지 않고, 일관성 필요 화면은 bootstrap/세션 상세 같은 묶음 응답 사용. 서버 read version이 낮으면 cache 회귀 차단. bootstrap은 snapshot+cursor이며 로딩 중 변경은 cursor 뒤 pull로 받는다.

ACK의 changeCursor는 **해당 명령의 위치**이며 pull watermark를 바로 그 값으로 올리면 아직 못 읽은 타 기기 변경을 건너뛸 수 있다. pull에서 실제 처리한 batch만 watermark를 전진시킨다. cursor만료410이면 본인 원본 보존→새 bootstrap→dirty 해소/충돌 선택. 원본 queue를 bootstrap으로 삭제하지 않는다.

### 8.4 여러 탭·여러 기기·demo

N-05는 Web Locks 사용 가능 시 보조로 이용하되 IDB writerLease의 단조 fencingToken을 최종 로컬 방어로 두는 안이다. 모든 도메인 변경/봉인/ACK 적용 tx에서 현재 token을 검사한다. 타이머/탭 suspension 후 lease가 바뀐 옛 탭은 read-only로 전환하며 stale tx를 커밋하지 않는다. BroadcastChannel은 알림만 하고 권한 근거가 아니다. 송신 중 lease 상실로 중복 HTTP가 나가더라도 서버 operation 멱등성이 방어한다. Web Locks를 Safari 전체에서 지원한다고 가정하지 않고 실제기기 검증한다.

다른 기기는 서버 proof+epoch/V로 검증한다. 계정별 여러 탭이 서로 다른 계정으로 전환하는 경우 전역 auth 변경 알림+scope 검증을 수행하고 송신 직전 서버 session owner를 재확인한다. 그 확인 직후 쿠키가 바뀌는 경합은 **N-07의 봉인 expectedUserId를 서버 principal과 대조하여 부작용 자체를 차단**한다. 응답 scope 검사만으로 잘못된 계정 쓰기를 막을 수 있다고 가정하지 않는다. 송신 중 바뀐 경우에도 응답 scope를 적용하지 않고 옛 원본을 유지한다.

`/demo`, `overload.core.v1`, 목업 routines/progress/benchmarks는 운영 importer/fallback/seed가 아니다. 운영 코드에서 demo useWorkout/storage import 금지, 기존 demo 키 읽기·삭제·rename·자동 업로드 없음. 기존 UI는 표현 컴포넌트/정책에 맞는 순수 계산만 검증 후 재사용한다. 실제 운영 코드와 demo를 한 번에 대규모 교체하지 않는다. 서비스워커/manifest 설치도 별도 승인 범위이고 IDB만으로 종료된 iOS 앱의 background sync를 보장하지 않는다.

## 9. 오류·복구·재전송 정책

오류 DTO는 `{code,operationId?,requestId?,fieldErrors?:[{field,code}],details?}`. 선택 필드는 필요할 때만, requestId를 넣으면 X-Request-ID와 동일. rejectedValue·원문 요청·SQL·제약명·예외 원문·credential·foreign 자원 정보 금지. details는 인가 확인된 본인 currentVersion/현재 generation/참조 루틴 ID·이름/복구 가능 여부의 명시 allowlist만. 기존 R/auth code-only 오류는 바꾸지 않는다.

| HTTP | 명령별 사용/코드 예 | 로컬 처리·재시도 확실성 |
|---|---|---|
| 400 | 모든 명령 JSON/type/UUID/Counter/필수필드·지원 안 하는 필드 INVALID_REQUEST, cursor 형식 INVALID | 검증된 앱 거절이면 확정 미적용. 보존/수정 필요, 같은 요청 무한재시도 없음 |
| 401 | 기존 AUTHENTICATION_REQUIRED (user 부재 포함) | 송신 중지·같은 계정 재확인. body 없는 필터오류도 상태 보존, OTP_INVALID를 운동만료와 혼동 금지 |
| 403 | 기존 CSRF_INVALID/ACCESS_DENIED | CSRF 준비 단계와 실제 POST 뒤 실패 구별. 토큰만 새로 받더라도 봉인 body는 그대로. 무조건 자동 로그아웃 금지 |
| 404 | 본인 범위 대상 미존재/타계정 동일 RESOURCE_NOT_FOUND, 결과 GET은 OPERATION_NOT_FOUND | 리소스 없음으로 특정 명령 미적용을 추측하지 않음. 결과 GET404는 미확인, 동일 봉인 POST로 확인 |
| 409 | 모든 write: OWNER_CONTEXT_MISMATCH/DATA_GENERATION_MISMATCH/VERSION_CONFLICT/OPERATION_ID_REUSED/ID_UNAVAILABLE; 세션 OWNERSHIP_LOST/TRANSFER_NOT_READY; 종목 EXERCISE_IN_USE; 승인 APPROVAL_TARGET_INACTIVE; routine ACTIVE_ORDER_CONFLICT/SCHEDULE_CONFLICT; set ACTIVE_ORDINAL_CONFLICT; START PLAN_SOURCE_CONFLICT; checkpoint CHECKPOINT_STALE; 명시 tombstone RESOURCE_DELETED | 확정 거절이면 적용하지 않음. 원본 보존·최신 조회·명시 선택, old payload 자동 변경/retag/마지막쓰기우선 없음 |
| 410 | sync pull의 SYNC_CURSOR_EXPIRED; 향후 N-02 결과본문 만료를 도입할 때만 OPERATION_RESULT_EXPIRED | bootstrap 또는 수동 확인. key 만료를 신규 실행 근거로 쓰지 않음. 현재 영수증 자동만료 제안 없음 |
| 422 | reps/kg/목표/날짜 VALIDATION_FAILED/CLOCK_CONTEXT_INVALID; 완료뒤 계획 SESSION_FINISHED/PLAN_IMMUTABLE/NEW_EXERCISE_AFTER_FINISH; 세트/유산소 INVALID_STATE_TRANSITION/CARDIO_ACTIVITY_BUSY; 승인 APPROVAL_CONTEXT_INVALID; 복원 RESTORE_TARGET_INVALID; FINISH INCOMPLETE_WORKOUT_CONFIRMATION_REQUIRED | 입력/명시확인 필요. 수정 의도 새 operation. 원래명령 불확실하면 먼저 결과 확인 |
| 429 | 향후 정의된 workout 요청제한 시 WORKOUT_COMMAND_RATE_LIMITED+Retry-After, 현시점 미구현 | 봉인본 보존 후 제한 백오프. 기존 OTP_RATE_LIMITED 정책 그대로. 제한량 신규 확정 없음 |
| 500 | settings 누락 WORKOUT_SETTINGS_MISSING, 나머지 예상밖 WORKOUT_COMMAND_FAILED/기존 읽기 WORKOUT_DATA_READ_FAILED | commit 미확인 가능. 자동 새ID 금지. receipt/같은 봉인 재시도 전 보존, 반복500은 멈추고 안전 안내 |
| 503 | 확인된 DB연결/일시종료/lock-timeout 등 WORKOUT_DATA_UNAVAILABLE, 별도 운영 장애 | 같은 봉인 backoff. SQL grammar/무결성 예외 전부503 처리 금지. commit 여부 불확실 |

자식/종목/상태 충돌 검사는 receipt 성공 확인보다 뒤다. 그래서 삭제 후 duplicate COMPLETE는 원래 성공을 반환하고 새 COMPLETE는 삭제 오류가 된다. 알려진 UNIQUE/버전 조건은 정확한 코드409로 매핑하되 모르는 제약실패는500이며 제약명 공개 금지. DB deadlock/serialization/락 시간초과는 전체 rollback 후 일시오류로 분류할 테스트가 필요하고 실패 tx 내부 retry는 하지 않는다. 405/415/프록시 오류는 실제 HTTP 의미 유지, 모두400/JSON으로 만들지 않는다.

backoff N안은 jitter·Retry-After 존중·foreground 복귀 시 재개·상한 대기와 수동재개를 둔다. 정확한 횟수/초 단위는 구현 UX 검토에서 정하며 공통 fetch에 임의 POST retry를 추가하지 않는다. 서버 영수증 없는 '확정실패 저장'을 약속하지 않는다. network/timeout/5xx/잘못된2xx는 UNKNOWN, 서명된 원문이 아닌 정상 검증된 ACK만 성공 증거다. ACK를 받은 뒤 Outbox 정리 실패도 입력삭제가 아닌 동일명령 재확인 대상이다.

## 10. 세부 구현 로드맵 및 검증 계획

이번에는 테스트/서버/DB를 실행하지 않았다. 아래 명령은 **후속 구현 단계에서** 실제 설정과 그때 변경분에 맞춰 실행할 게이트다. 주요 의존성/보안/운영/파괴적 작업은 별도 명시 승인 선행, Commit/Push는 그때 사용자 지시에 따르며 이번에는 하지 않는다.

### 10.1 작은 단계별 범위·커밋 시점

| 단계 | 선행조건 / 수정 파일 범위 제안 | 구현 결과물 | 테스트 게이트 / 커밋 시점 |
|---|---|---|---|
| 009-0 | 현재 문서 검토 | 이 초안+결정표 | 문서만 제출, **리뷰 대기·미커밋** |
| 009-1A 계약 타입·DB 기반 | D-01/02/04/05/06/09, N-01/02/06/07 승인. server 신규 additive migrations·command/sync DTO·SQL adapter, 기존 Entity 확장 | receipt/sync/versions·범위검사·fingerprint | 새 schema/seed/validate/R 회귀, 한 기능 종목생성으로 실원자성 vertical slice 게이트(1B·2A의 CREATE와 함께). schema만으로 쓰기완료 선언 금지 |
| 009-1B 멱등 실행·결과 GET | 1A, receipt 실패보존 수준 결정 | 사용자락·명령 dispatcher·ACK·결과조회·오류 | 동일키 경쟁/rollback/ACK유실/미확인404, 독립 SQL commit 관찰. 1C slice와 검증 후 작은 승인 커밋 |
| 009-2A 종목·휴식·memo | 1A/B, memo 스키마·새 편집 GET/본인 범위 | definition 생성/이름·settings/memo handler+Controller/scoped Advice·테스트. 정의 삭제는 2B의 루틴 참조 스키마 이후 활성화 | 동명/소유권/설정누락/메모부재/기존 가입 default. 이 단계의 CREATE_EXERCISE를 1B 검증 slice로 먼저 연결 가능. 승인된 파일만 기능 단위 커밋 |
| 009-2B 루틴·배치·일정 | 2A, N-03 승인, 편집기 승인 사건 스키마 | routine/placement/schedule Entity·handler·상세/후보 GET, 편집기 승인·정의 삭제 참조 검사 활성화 | 복수동일정의·RESTORE/NEW·swap·경합·정의 사용 중 삭제 차단·삭제일정/승인 사건 원자성. 독립 리뷰/커밋 |
| 009-2C 기기권한·시작 | 2B, D-03/08/10·N-04 승인 | device 등록·기초 이전·session skeleton/Initial/Applied/checkpoint | proof 위조·응답유실·계정전환, 오프라인 source 변경/삭제·시계 경계·Initial 불변. 별도 보안 리뷰/커밋 |
| 009-2D 수행·유산소 | 2C | performed/cardio·상태전이·정정/삭제 사건, Session version | 신규 reps0거절/0kg/ordinal/tombstone·시간원본/정정·동시성·전체rollback. 기능별 커밋 |
| 009-4A 공통 공식 평가 | 2D, **D-07 남은 제품경계 승인** | 서버/TS 정책 fixture·순수 평가·정정 재평가 | §10.3 정책 표 전부, 단일 과거세션 선택/동률/늦은과거업로드. FINISH 구현 전 게이트 |
| 009-2E 종료·사후복구·제어권 완성 | 2D+4A | FINISH/봉인·과거기록 GET·정상/강제이전·선택복구 | FINISH vs COMPLETE·승인 atomic·완료새종목차단·구epoch 차단·봉인불변. 리뷰/커밋 |
| 009-3A IDB 저장·명령 생성 | 서버 DTO 고정, 새 라이브러리 필요시 사전 승인 | native IDB adapter 또는 승인된 선택, scope/원자저장/Outbox·단일탭작성 | 실제 브라우저 IDB complete/abort/quota/reload·봉인 crash. Node/SSR만으로 통과 선언 금지. 별도 커밋 |
| 009-3B sender·pull·충돌 UI | 3A+서버 result/sync·이전/복구 계약 | 순차봉인/재송신/ACK·bootstrap/dirty/pull·recovery 보존 | timeout/ACK유실/순서/여러탭 fencing·다른기기/계정/세대/epoch·삭제전파. 기능별 리뷰/커밋 |
| 009-3C 실제 React 운동 UI | 3B | 운영 UI에 검증한 demo 표현만 재사용, 진행/완료/서버저장 구분 | 사용자입력·저장실패·재연결·명시종료·표시/승인·기존 인증/조회 유지. 별도 커밋 |
| 009-4B 종단간·실기기 | 앞 단계 통과 | 격리 PostgreSQL+실React E2E·리포트 | 전체 회귀·오프라인/재연결/다중기기, iPhone Safari/PWA는 실제 구성된 범위만 별도 검사. 결과 리뷰 뒤 커밋 |

009-4의 평가를 009-2E보다 앞에 두는 이유는 **최초 종료 판단을 봉인하려면 최종 평가 규칙이 먼저 필요**하기 때문이다. 임시 평가 null로 FINISH를 먼저 출시하고 나중에 최초 판단을 소급 작성하지 않는다. UI/Outbox 독립 개발은 계약 승인 후 병행 가능하지만 이번 작업에서 착수하지 않는다. reset/탈퇴/export/새 Service Worker 배포는 위 하위 단계의 묵시적 범위가 아니다.

### 10.2 PostgreSQL 원자성·경합 테스트 매트릭스

기존 Testcontainers PostgreSQL18.x·혼합 컨텍스트·테스트 local overlay를 재사용한다. 개발 .env/DB/실제 외부발송 제외, 합성 사용자·새 임시 DB만. 실제 별도 커넥션/트랜잭션과 barrier/latch로 순서를 제어하고 sleep만으로 성공을 추측하지 않는다.

| 게이트 | 구체 테스트 / 검증할 DB·HTTP 결과 |
|---|---|
| W-01 업그레이드 | 빈 DB·V8 데이터 보유→새 migration, Flyway 과거 checksum/UUID/name/timestamps 유지, JDBC 새가입20종목/기본설정·재로그인 무seed, JPA validate/OSIV/manager/확장 scan |
| W-02 fingerprint | Java/TS 같은 golden hash, key순서/공백 같음, 배열/null/필드차이 다름, duplicate JSON key/알수없는필드/큰Counter/decimal scale/NaN/Unicode/overflow 거절 |
| W-03 동일키 경쟁 | 여러 HTTP/독립tx 동일 operation 동시 전송→도메인1/사건1/version1증가/feed1/receipt1. 다른payload→winner1+409, 첫 tx rollback 후 경쟁자 정상 처리 |
| W-04 모든 rollback 지점 | 예약직후/자식저장후/사건후/version후/feed후/flush·deferred constraint·commit에 실패 주입, 독립연결에서 도메인/receipt/revision 전부 미반영. 같은 봉인 재시도 성공 |
| W-05 응답유실 | 실제 서버 커밋 후 transport가 응답 폐기→GET receipt200·원result, replay200/version추가없음. 진행중 GET404와 rollback뒤404를 미확인으로 처리. 삭제/epoch변경 뒤 receipt 조회 재생성없음 |
| W-06 owner/세대 | A/B UUID/배치/사건/cursor/operation 강제교차,404/안전409·정보비노출. A에서 봉인한 CREATE를 B쿠키로 송신하면 OWNER_CONTEXT_MISMATCH·양계정 삽입0. large generation 왕복. 합성 reset tx vs write 양순서로 구generation 영수증/삽입차단, old queue 자동이관없음 |
| W-07 version/제약 | child-only 변경 부모+1, 동시에 같은V 한 성공/한409, 다중집계 한쪽V stale 전체rollback, no-op 영수증만. 재정렬/복원활성 UNIQUE·삭제 후보중복·복합FK foreign 조합 차단 |
| W-08 종료경합 | COMPLETE/FINISH·APPROVE/FINISH·루틴삭제/승인 양순서 barrier. 추가 수행/봉인/승인 사건의 부분커밋 없음, 뒤명령 정확한 충돌 |
| W-09 feed | T1 낮은revision 후보 지연/T2 시도→T2가 user락 뒤 커밋, 누락없음. rollback revision/feed취소, batch비분할·다중집계 한batch, ACKcursor로 미수신변경 skip없음 |
| W-10 제어권 | deviceId만 위조한 요청 차단, source/target proof·정상drain/force 최신V, 옛epoch 새명령 거절/과거성공 receipt 허용. tombstone 복구 명시선택·ordinal충돌·새종목차단 |
| W-11 ORM/직접SQL | backend PID/txid 일치, flush전후 JDBC 관측, native후 refresh/clear, root 이중version증가없음, rollback원인 덮임 오류분류. deferred 실패를200으로 보내지 않음 |
| W-12 보안/HTTP 회귀 | 기존 인증·OTP·Google 모의/JWKS·SpringSession JDBC/90일/재인증/CSRF/쿠키회전/no-store/RequestID/204/401/403/302 전체. SessionSQL 동안 workout EM binding 없음, 외부 TX rollback과 Session독립성 유지 |
| W-13 조회·성능 | 기존 WorkoutReadIntegrationTests와 1C 실브라우저 흐름, 원래 R 필드/오류/query거절/반복구간. 목록/상세 N+1·pagination/동시bootstrap snapshot·늦은과거기록 무효화 |

DB 측 미완성 receipt commit 차단 trigger를 채택하면 의도적 PROCESSING 커밋 시도도 실패해야 한다. trigger 미채택 대안은 handler 완료 불변식을 모든 경로에서 증명해야 하며 단순 happy-path 테스트로 대체하지 않는다. deadlock/lock timeout/연결종료/commit-time 오류에서 안전한500/503와 receipt 재조회까지 검증한다. 신규 제한/테스트 도구 설치는 승인 없이 하지 않는다.

### 10.3 도메인/React·브라우저 게이트

필수 정책 fixture: 동명이종목 UUID별, 배치 같은정의복수/명시복원 승인사건0, 3→4→3 후보/3→2 제외/타원계획미완료삭제우회차단, 이동무영향, 첫세트전62.5 승인정상후보/혼합·오늘만제외, 단일과거배열·동률/목표밖reps(승인 후), 정정/삭제 현재평가만변경, 종료후새종목차단, 기존종목추가세트복구와 봉인불변, 0kg/신규0reps거절, 휴식 maxReps10/11/12/13 실제편집값, 유산소 timerMs 원본/activeMs 정정/null/동시busy, 시작날짜 자정/DST/여행/지연업로드 보존이다.

| Frontend 게이트 | 실제 검증 |
|---|---|
| F-01 로컬 원자성 | 저장 request success 후 tx abort, quota/권한/versionchange, 작업중 탭닫기. 도메인 완료/Outbox 둘 다 rollback, 입력 보존·네트워크 송신0, 재진입 |
| F-02 봉인·의존 | 오프라인 START→APPROVE→COMPLETE→FINISH 수동생성, 선행ACK로만 V/사건바인딩. 봉인 commit전 crash시미송신, 송신후crash 동일payload. 두집계 barrier/cycle·의미변경금지 |
| F-03 네트워크 | 미도착/DBcommit후응답유실/timeout/잘못된2xx/ACK뒤IDB실패→동일명령 재확인. 실제 fetch proxy로 응답폐기, DB1회적용 관찰 |
| F-04 UI/재연결 | offline 로컬완료는 서버대기, reload로 pending복원, 재연결 ACK 후 서버저장표시. ACK뒤GET실패는 저장성공유지. 진행초안/타이머/최신입력을 옛ACK가 덮지않음 |
| F-05 계정/권한 | A→logout→B, 같은A 재로그인, 오래된성공/401/503, 구generation/epoch, 탭fence이전·suspension. old UI/DB영역/CSRF침범없음, 원본격리·선택복구 |
| F-06 pull/삭제 | 느린GET과새version·batchdirty, ACKcursor건너뛰기금지, cursor410/bootstrap, 삭제된ID 자동부활없음, 복수탭/기기 갱신 |
| F-07 실제UI/분리 | demo 키 getter 차단·import 경계, 기존312개 회귀 재실행(당시 개수는 달라질 수 있음), React 실제 DOM 입력/네트워크/IDB. SSR 단독 통과로 DOM/브라우저 보장하지 않음 |
| F-08 기기/PWA | 실제 iPhone Safari 잠금·앱복귀·탭동시·오프라인/재연결·저장공간. 설치형/ServiceWorker가 실제 도입된 때만 설치·업데이트 보호 별도 게이트 |

Backend 명령 후보는 기존 `gradlew.bat --offline --no-daemon test`, 해당 `integrationTest --tests ...`, 최종 `build`; Frontend는 `npm test`, `npm run lint`, `npm run build`다. 별도 typecheck 스크립트는 없고 build가 tsc를 포함한다. Node 내장 테스트·기존 SSR를 유지하며 IDB 실제 경합은 실브라우저로 보완한다. 테스트 framework 추가가 필요하면 대안/호환성 검토 후 승인한다. 격리 real-backend harness는 재사용 가능하지만 현재 수동 시나리오를 자동 E2E runner라 부르지 않는다.

각 단계에서 시작한 JVM/Node/컨테이너/PID/포트를 기록하고 성공·실패와 무관하게 종료 및 잔류 확인, 기존 사용자 프로세스는 유지한다. 이번 문서 작업에서는 새 검증 프로세스를 시작하지 않았다.

## 11. 기존 문서와의 충돌·불확실성 및 위험

| 확인 사항 | 판단·영향 / 이번 처리 |
|---|---|
| 006/008 초안의 'JPA/인증/API 미구현', 보고서의 리뷰전미커밋 상태 | 작성 당시 기록. 현재 HEAD는 인증/JPA/읽기와 1C 커밋 포함. 원문 수정 없이 §1에서 현재 사실 분리 |
| backend.md 기술표의 '실제 로그인 미구현' | 현재 실제 인증 코드/1C와 다름. 기술표보다 현 소스·후속 보고서를 근거로 사용, 원문 수정 안 함 |
| demo 휴식 ≤8 / 정책 ≤10 | 명백한 기존 차이. 실제 조회 UI는 올바른구간. 새 운영계산은 P 적용, 이번 demo 수정 없음 |
| demo structureModified·이름병합·save실패·session/placement ID혼용 | 운영 전환시 단순API연결만으로 해결 안 됨. 정책 fixture/새adapter로 제한 재사용, demo 전체 리팩터링 이번 범위 아님 |
| D-04 success-only와 '같은ID 언제나 같은결과' | 성공에만 불변결과 보장. 실패영구바인딩은 별도설계 필요. §5.4에 한계/대안 명시 |
| 현재 R 응답에 version 없음 | expectedVersion=0을 상상해 제출 금지. 새 bootstrap/detail 계약 필요. 기존 GET 변경 없이 병행 가능 |
| V2 현재세대 FK와 구세대 보존/초기화 | users 세대만UPDATE 불가. 구운동행 정리순서·멱등/백업 파기 별도 설계. reset 구현 선행 아님 |
| 부분 UNIQUE와 배치 재정렬 | 한 tx라도 중간swap 충돌 가능. N-03에 실검증 가능한 대안, 승인 전 migration 없음 |
| 오프라인 START의 과거계획·이름·승인 근거 | 현재 DB만으로 불충분. 엄격충돌/불변이력/서명template 중 결정 필요, 세션게이트 보류 |
| D-08의 공개 deviceBindingRef | 실제 proof 계약 없음. UUID만으로 writer 인가 금지, 새 쿠키/키 보안 상세 별도 승인 |
| 계정 확인 직후 쿠키 전환 | 읽기 응답 폐기만으로 신규 CREATE의 다른 principal 적용을 막지 못함. N-07 expectedUserId는 인증된 owner와의 일치 전제조건으로만 제안. 기존 R/인증 DTO는 변경 없음 |
| JPA 하위수정과 @Version·ACK | child-only 자동증가 안 됨. 확정 version 생성/flush/commit 테스트 필수. FORCE_INCREMENT 추측 금지 |
| Spring Session 독립 REQUIRES_NEW | workout과 원자성 아님. 세션실패 응답 뒤 도메인성공 가능. receipt 확인으로 해소, manager 자유중첩 금지 |
| D-07의 남은 평가경계 | Task006 P 사례는 고정. 동률/목표밖/추가수행 영향 등을 데모로 임의확정 안 함. FINISH 선행 gate |
| 크기/문자열/기기시계편차/보존일수 | 현재 미확정, 새값을 '확정'으로 쓰지 않음. §12 결정 필요 |

가장 큰 재작업 위험은 (1) version 없는 R을 운영편집 state로 확장해 가짜버전을 퍼뜨리는 것, (2) 계획 JSON에 공식 수행을 복제하고 slot UUID/benchmarks 테이블을 선행 도입하는 것, (3) receipt를 도메인과 별도커밋하거나 TTL로 멱등장벽을 지우는 것, (4) offline 원계획 증거와 device proof를 뒤늦게 붙이는 것, (5) demo 전체state를 계정데이터로 가져오는 것이다. 본 초안은 이 경계의 계약을 먼저 리뷰하도록 분할한다. 별도 Redis·메시지브로커·범용 이벤트 소싱·User JPA 전환·인증 TX 재작성은 제안하지 않는다.

## 12. 사용자 결정이 필요한 쟁점과 권장안

이 표는 승인 요청 목록이며 **현 문서 작성으로 승인된 상태가 아니다**. 제품확정 P를 다시 묻는 항목은 없다. 서로 독립적인 승인 묶음으로 검토할 수 있다.

| 결정 | 권장안 / 대안·영향 | 결정 필요 시점 |
|---|---|---|
| A. 숫자·ID (D-01/02) | NUMERIC(20,6)·decimal 문자열·초과 scale 무반올림 거절, Count INTEGER·새 UUID v4 권장. 대안 unconstrained NUMERIC/더 큰 Count는 검증·매핑 비용 증가 | 009-1A 전 |
| B. 명령·영수증 (D-04/N-01/02) | typed POST·JCS/SHA-256·결과 GET, 성공/no-op만 원자 커밋, 현재 generation receipt/tombstone 자동 TTL 없음. 실패까지 영구 binding을 요구하면 별도 접수 레지스트리 설계 필요 | 009-1A/B 전 |
| C. 동시성·feed·편집 version (D-05/06/09) | user 락 직렬화·루트 V·커밋 순서 batch, R 유지·새 detail/bootstrap 권장. 대안으로 더 세밀한 락/전체 변경 로그를 사용하면 복잡도 증가 | 최초 쓰기 스키마 전 |
| D. 배치 순서 (N-03) | generated live_position·deferrable UNIQUE 검증 권장. 대안은 부분 UNIQUE·2단계 임시 position | 009-2B migration 전 |
| E. 스냅샷·제어권 (D-03/08) | 네 snapshot의 목적 분리·관계형 수행 유지. 전용 HttpOnly device proof 쿠키 후보의 수명/회전/계정 격리까지 별도 보안 검토. 대안 WebCrypto | 009-2C 전 |
| F. 오프라인 시작 (D-10/N-04) | 서명 start-template 검토 권장. 현재 V만 허용하는 안은 최소지만 정상 오프라인 계획 업로드를 막음. revision 이력은 모든 계획 근거의 보존 비용 발생. 삭제 소스/만료/키 회전을 함께 결정 | START 구현 전 |
| G. 공식 선택 (D-07) | 한 세션 후보의 합계 DESC→최근 종료→UUID 결정 순 권장, 동률 때 기존 기준 유지 대안. 목표 밖 양수 reps도 후보 유지 권장이나 사용자 결정 필요 | 평가/FINISH 전 |
| H. 추가 공식 경계 (D-07) | 타 종목 추가 수행은 세션 전체 제외안과 종목별 제외안 비교. 승인 전 수행 후 삭제한 경우의 시점 판단 필요. P의 3→4→3/3→2/첫 수행 전 승인 인정은 유지 | 평가 구현 전 |
| I. 시계·ordinal (D-10) | DATE/zone 일치·역행 거절·ordinal 충돌 수동 선택. 서버 현재 시각을 정답으로 보정하지 않음. 허용 시계 편차/복구 UX 추가 결정 | 세션/복구 전 |
| J. 로컬 조정 (N-05) | native IDB·fencing lease, Web Locks 보조·실기기 게이트 권장. Dexie 등 저장소/테스트 주요 의존성 도입은 별도 승인 | 009-3A 전 |
| K. 자원 제한 (N-06) | 신규 명령 body 256KiB, 이름 200 Unicode code points, memo 10000 code points, 변경 배열 1000개를 **검토 시작값**으로 제안. cardio 이름 100/도메인 숫자 규칙 유지. 실제 UX 측정 후 조정 가능. 큰 Count 허용과 무제한 배열은 별개이며 기존 값 소급 절단 금지. 초과 요청은 정의된 413/필드 422로 구분 | 요청 DTO 동결 전, 실제 UX fixture로 조정 |
| L. 보존·파기 운영 | 현재 세대 no-TTL로 중복 방어. feed 만료기간/백업/초기화·탈퇴 파기 일수는 운영 정책으로 별도 결정. 만료 key 재실행 금지 | 정리 job·reset/탈퇴·배포 전 |
| M. 예상 계정 (N-07) | 생성 당시 expectedUserId를 명령에 봉인하고 principal과 대조. 클라이언트 owner 권한 신뢰 없이 쿠키 전환 경합 차단. 대안은 세션별 서버 바인딩 쓰기 문맥 token이지만 수명·재로그인 복잡도가 더 큼 | 최초 쓰기 DTO 동결 전 |

### 12.1 이번 문서 검증과 제출 상태

이번 검증은 읽기 전용 Git/문서/소스 대조, 신규 Markdown 공백·링크·코드블록·섹션/결정표·민감정보 패턴·변경범위 확인이다. 제품 컴파일/테스트/빌드, SQL 구문/JPA 매핑 실행 검증, 브라우저/DB/외부서비스는 실행하지 않았다. 따라서 이 초안의 동시성·DDL·IDB 제안은 후속 테스트 게이트 전 런타임 검증 완료가 아니다. 새 로컬서버·테스트프로세스 시작 없음.

초기 문서 검사에서 PATH에 Python이 없어 검사 명령이 실행되지 않았다. 설치 없이 PowerShell 검사로 전환했다. Markdown 표의 union 기호를 escape하여 열 분리 오류를 수정하고 재검사했다. 12개 필수 섹션·D-01~10 표·20개 Markdown 링크(저장소 상대 링크의 실제 대상 포함)·표 열·코드블록 균형·끝 공백·credential 패턴 검사는 통과했다. 기존 추적 파일의 `git diff --check`/`git diff --cached --check`도 통과했다. 이는 제품 테스트 통과를 의미하지 않는다.

최종 확인 변경은 Backend의 이 파일 1개 untracked, Frontend clean이다. HEAD는 §1의 두 전체 해시와 같으며 stage/Commit/Push 없음이다. Task006/008 원문·기존 읽기제품코드는 불변이다. **이 문서를 제출하고 사용자 리뷰를 기다리며 009-1 구현을 시작하지 않는다.**
