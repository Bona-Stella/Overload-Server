# Task 009-0 — 운동 쓰기 도메인·API 계약 및 구현 계획 v0.2

- 작성일: 2026-10-30 (Asia/Seoul).
- 상태: **009-1A 사용자 정책 보완·최소 기술 계약 채택 / 최초 쓰기 기반 구현 / 쓰기 HTTP API·React 미구현**.
- 최초 작성: v0.1의 관련 절을 수정해 v0.2 문서 1개 추가했다. 그때는 구현 변경·DB 접속이 없었다. 009-1A 개정에서는 아래 채택·정책 보완과 실제 기반 구현을 반영한다. 아직 untracked인 [v0.1 원본](task-009-0-write-domain-api-design-v0.1.md)은 그대로 보존한다. 외부 발송·개발/운영 DB 변경·Commit/Push 없음.
- 목적: 사용자 결정으로 변경된 정책과 최소 쓰기·충돌 해결·기록 보존 계약을 정리한다. 최초 문서 작성 이후 사용자가 009-1A를 명시 지시했다. 아래의 최초 검토 시점 현황과 후속 계획은 구현 완료 증거가 아니며, 현재 구현·검증 범위는 [009-1A 보고서](task-009-1a-write-foundation-report.md)를 따른다.

**009-1A 채택 범위:** D-01/02, D-04/05/06, N-01/02의 성공·제한된 terminal409 영수증, N-07 계정 일치, N-08의 허용 의미 group·stamp·봉인 fieldBases·새 operation 해결 형식을 채택한다. 실제 dispatcher·POST/결과 GET·자동 해결·N-09 설정 API는 후속 단계다. V9는 정의/설정의 version·intent_stamps와 정의 deleted_at, 최종 영수증·revision/feed만 추가한다. memo counter·세션·루틴·수행·기기 proof·시작 계획 원본은 포함하지 않는다. N-09 기본 OFF는 확정 정책이다.

상태 표기는 **P**(기존 확정 정책), **P-NEW**(이번 첨부 요청에서 새로 승인한 제품 정책), **I**(현 구현), **D**(008-0B 기술 제안), **N**(신규/구체화 기술 제안)이다. 최신 P-NEW가 상충하는 Task 006/008/v0.1 문구보다 우선한다. D/N은 명시적으로 승인된 제품 의미를 제외하면 여전히 구현 계약 제안이다. 제품 승인·기술 제안·실제 구현을 구별한다. Task 006/008 원문은 수정하지 않는다.

개정 범위: 최신 의도·설정·명령 최소 변경 단위(§2·4.2·7.6), 결정적 세트 선택·통계 분리(§4.6), 기기 시계 가정(§7.1), 삭제 루틴의 오프라인 시작(§7.5), 최소 DB 추가분(§6.5), 구현 선행 게이트(§12). 그 밖의 기존 인증·트랜잭션·영수증·조회 계약은 필요한 연동 문구만 조정한다.

## 1. 기존 설계와 구현 현황

### 1.1 시작 Git과 Task 008 반영

원격 fetch 없이 실제 로컬 Git을 확인했다. 아래 HEAD는 작업 시작과 문서 최종 검사에서 확인할 기준이며 원격 최신성 보장은 아니다.

| 저장소 | 브랜치 / 전체 HEAD | 시작 작업 트리 | 확인된 커밋 |
|---|---|---|---|
| Backend | `main` / `10e1a0242c36d525230cdef5859bacbbcc44da6a` | v0.1 최초 작성 전 clean. 이번 개정 시작은 v0.1 문서 1개 untracked, 추적 파일/staging 변경 없음 | `10e1a02` 008-1C 격리 실서버 브라우저 harness, `be3087b` 008-1A 조회, `d28f0fb` 008-0C JPA/JDBC, `7eb3ef7` 공통 HTTP/Request ID, `e29bfd9` 008-0B |
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

## 2. 유지할 확정 정책과 이번에 승인한 변경 (P / P-NEW)

다음의 유지 정책과 이번 변경은 재승인 대상으로 돌리지 않는다. v0.1을 이전부터 승인된 정책처럼 취급하지 않는다.

| 영역 | 유지할 정책 / 필수 사례 |
|---|---|
| 운동 종목 | 개인 UUID 동일성, 동명 허용. 이름 변경으로 계보 분리 금지. 활성 루틴 참조가 있으면 정의 삭제 차단. 과거 세션의 이름·계획·수행은 보존 |
| 루틴·배치 | 루틴·배치·정의 ID 구별, 동일 종목 중복 배치 허용. 저장된 제거는 soft delete. 복원은 지정 배치 ID·이전 승인값 유지·새 승인 사건 없음. NEW는 새 배치·새 계보. 편집 취소로 ID 변경 금지 |
| 계획·완료 | Initial은 시작 시 불변, Applied는 진행 중 편집/최초 명시 종료 봉인. 종료는 사용자 행동이며 ACK와 별개. 완료 웨이트 공식 원본은 performed_sets. 미완료 초안을 완료 실적으로 만들지 않음 |
| 공식 자격 (P-NEW 반영) | 3→4의 추가 유효 세트 자체로 제외하지 않음. 3→4→3도 후보 가능, 3→유효 2는 정상 완료 미충족. Initial의 목표 N개를 ordinal/ID 순서의 현재 유효 수행에서 선택. 다른 원계획 종목 삭제로 전체 완료 우회 금지. 이동·유산소 skip 자체로 제외하지 않음 |
| 무게 | initial_weight·approved_weight·오늘만 작업값 구별. 승인값 변경은 명시 사용자 행동+불변 사건. 계획 N개 중 선택 첫 N개 유효 수행의 승인 근거·중량·목표로 평가. 그 안의 혼합/오늘만 제한 유지. N개 이후 추가 수행의 다른 중량은 자격을 박탈하지 않음. 자동 증량/감량 없음 |
| 과거 기준 (P-NEW 반영) | 서로 다른 세션의 최고 세트 합성 금지. 루틴 기준 비교는 한 세션에서 결정적으로 선택한 N개 유효 수행만 사용. 총 반복 수 우선, 동률이면 운동 수행 시각이 더 최근인 세션. 전체 수행 통계는 N개로 자르지 않음 |
| 사후 정정 | 기존 종목 완료 세트 kg/reps 정정·세트/세션 삭제 허용. 변경 사건 보존, 현재 평가 재계산. Initial/봉인 Applied/최초 종료 판단/승인 사건 불변. 완료 후 새 종목·계획/목표/순서 편집 금지 |
| 선택 복구 | 올바른 현재 version/epoch의 새 operationId. 기존 세션 종목 누락 세트만 명시 선택 복구, deleted ID 복원은 별도 명시 선택. ordinal 충돌 자동 합산 금지. 새 공식 세션 자동 생성 금지 |
| 수치·날짜 | kg 원본, 신규 실제 reps≥1, 0kg 허용, 0과 미입력 구별. 시작 현지 DATE 고정, Instant+IANA zone 별도. 자정·지연 업로드로 날짜 변경 금지. 구 데모 0회 읽기 호환이 신규 허용 근거는 아님 |
| 휴식·유산소 | 목표 반복 **상한** ≤10 / 11~12 / ≥13. 실제 저장 설정 사용. READY→RUNNING↔PAUSED→REVIEW→COMPLETED/SKIPPED, 원본 timerMs와 정정 activeMs 구별. 실적 미입력은 null, 계획으로 추정 금지. 매초 서버 전송 없음 |
| 로컬·동기화 | 도메인 완료+명령/Outbox를 동일 IDB tx로 저장하고 complete 후 로컬 완료 표시. 서버 ACK 전 서버 저장 완료 표시 금지. 계정/세대 격리, 송신 전 봉인, 동일 명령 재전송, 오래된 응답 차단 |
| 제어권 | 세션당 서버 쓰기 기기 하나, 정상/강제 이전·epoch 증가. 구 epoch 미전송 명령 재태깅 금지. 구 로컬 원본은 해결 ACK까지 보존. 단순 deviceId는 권한 증명 아님 |
| 초기화·탈퇴 | 초기화는 가입 seed+generation 증가, 구세대 차단. 탈퇴/초기화/내보내기 실제 구현은 별도 범위. 운영 파기·백업 보존 일수 미확정 |
| HTTP·인증 | 성공 DTO 직접, 명시 오류 code JSON, 204 무본문, 기존 Security 401/403/CSRF/세션과 X-Request-ID 유지. 모든 프록시·필터 오류의 JSON 보장 없음 |

### 2.1 이번 사용자 결정과 변경 전·후

| 결정 / 출처 | 이전 문구·미결 항목 | 이번 확정 의미 |
|---|---|---|
| P-NEW-01 최신 의도 | Task 006 §4.3의 자동 마지막 쓰기 승자 금지, v0.1의 version 충돌 후 일괄 수동 선택 | 안전한 같은 의미의 값 충돌은 **기기 occurredAt 최신 의도 자동 적용**. 도착/업로드 순서 제외. 서로 다른 속성은 모두 보존. 계정의 확인 설정 ON에서만 실제 값 충돌을 사용자 확인, 기본 OFF |
| P-NEW-01 안전 경계 | '구 명령 자동 재태깅 금지'와 '자동 해결 금지'가 혼재 | 원래 operation/봉인 payload/version/epoch 불변은 유지. 기존 결과 확인 후 **새 operation·새 expectedVersion의 해결 명령**은 허용. owner/generation/epoch/proof/멱등성 우회 없음. 손실 위험·안전하지 않은 병합은 OFF라도 보류 |
| P-NEW-01 기기 시각 | D-10의 시계 편차·서버 시각 기반 보정 검토 | 기기 시각 정확성 가정. 별도 clock-skew 추정/허용 편차/서버 시각 보정 안 함. 파싱·양의 duration·동일 운동 내 시각 순서·DATE/zone 같은 형식/논리 검증은 유지 |
| P-NEW-02 최종 유효 수행 | 중간 structureModified/추가 유효 수행을 자격 제외 근거로 삼는 제안 | 현재 삭제되지 않은 완료 수행 우선. 추가/취소 이력 자체는 제외 사유 아님. Initial/최초 봉인 Applied/최초 판단은 불변, 현재 평가만 재계산 |
| P-NEW-03 반복·추가 세트 | D-07에서 목표 밖 reps/추가 수행 제외 범위 미결 | 목표는 권장 범위. 양수 실제 reps 모두 저장, 실제 4세트 모두 보존. 안내는 시작 계획 N개와 목표 min/max, 순서상 첫 N개 유효 수행으로 계산. 추가 수행으로 루틴 N 자동 변경 없음 |
| P-NEW-04 통계·공식 비교 | 한 세션의 '전체' 배열이라는 표현과 N개 루틴 비교 혼재 | 실제 수행 전체 통계와 루틴 기준 선택을 분리. 공식 루틴 비교의 합계는 **선택 N개 reps**로 비교, 동률은 실제 운동 종료 시각. 서버 업로드 시각 제외 |
| P-NEW-05 오프라인 기록 | v0.1 START의 현재 활성 루틴/같은 version 전제와 source 삭제 거절 | 검증 가능한 당시 계획이면 루틴 수정·삭제 후 도착해도 실제 운동을 저장. 삭제된 루틴/일정/배치를 복원하지 않음. 타 owner/세대·변조 계획은 거절 |
| P-NEW-06 선택 N개의 중량 범위 (009-1A) | 추가 세트의 혼합/오늘만 중량을 전체 종목 자격에 적용하는 보수적 제안 | 승인·오늘만·혼합 중량과 반복 목표 판단은 첫 N개 유효 수행만 사용. 이후 추가 세트는 중량이 달라도 정규 기준 안내 자격 유지, 전체 수행 통계에는 모두 포함 |
| P-NEW-07 사용자 경험 성능 (009-1A) | 로컬 원자성과 서버 저장 상태 분리 원칙 | 정상 온라인 입력·세트 완료·타이머·화면 이동은 불필요한 서버 ACK를 기다리지 않음. 로컬 저장 성공을 확인하고 서버 동기화는 분리. 로컬 저장 실패를 성공으로 표시하지 않음 |

Task 006의 최초 계획 불변성·다른 원계획 종목 제거로 미완료 우회 금지·오늘만/혼합 중량 구별·명시 승인 사건은 유지한다. 충돌한 문구만 이번 결정으로 대체한다. 통계 화면/일반 기록 복원 UI/자동 중량 변경을 추가 승인한 것은 아니다.

### 2.2 성능·저장·복원 필수 검증 (009-1A 정책 보완)

정상 온라인 사용의 입력·세트 완료·타이머·화면 이동에 불필요한 서버 ACK 대기를 넣지 않는다. 도메인 변경과 명령/Outbox의 로컬 원자 저장이 완료되면 로컬 완료로 진행하고 서버 저장 여부는 별도로 표시한다. 로컬 저장 실패 시 입력 초안을 보존하고 실패를 알리며 저장 성공/완료로 표시하지 않는다. 늦은 ACK는 현재 입력·타이머 앵커를 되돌리거나 타이머를 재시작하지 않는다. 권한·제어권 획득처럼 실제 서버 판단이 필요한 전제조건까지 생략한다는 의미는 아니다.

네트워크 불통과 iPhone PWA의 백그라운드·잠금·화면 꺼짐·재실행은 별도 상황이다. 네트워크 불통에서는 이미 저장한 기록·pending 명령과 재연결을, iPhone 각 수명주기 상황에서는 이미 저장한 기록과 startedAt/runSince/endAt 등 시각 앵커의 복원을 각각 검증한다. 매초 실행 지속을 가정하지 않고 저장 앵커로 경과 시간을 복원한다. 이들은 009-3/4의 필수 실제 브라우저·기기 게이트이며 009-1A는 문서만 반영하고 React/IDB/PWA 동작은 구현·검증하지 않는다.

## 3. D-01~D-10 검토 및 승인 필요 여부

R-01~03은 현재 I이며 이번에도 변경하지 않는다. P-NEW로 D-07의 제품 경계와 D-10의 시계/오프라인 기록 수용 방향 일부가 확정되었다. 009-1A는 문서 첫머리의 최소 기술 계약만 채택하며 나머지 D/N을 일괄 승인하지 않는다. 아래 표의 최초 승인 대기 표기는 이 채택 범위에 한해 해소되었다.

| ID | 기존 제안 및 검토 | 이번 권장·대안 | 승인/선행 게이트 |
|---|---|---|---|
| D-01 | NUMERIC(20,6), BigDecimal, decimal 문자열 | 1A 채택. 소수6자리 초과 무반올림 거절 | DTO·격리 NUMERIC 검증 완료, 실제 중량 컬럼은 후속 |
| D-02 | 신규 UUID v4, Counter 문자열, Count INTEGER | 1A 채택. 기존 UUID 재발급 없음. 양수 Count≤2147483647은 기술 상한이며 무제한 배열 허용 아님 | 공통 형식·검증 구현 |
| D-03 | 4종 버전 snapshot, 수행/초안 분리 | 유지+§4.3/6의 authoritative 필드 구체화. checkpoint가 공식 수행 원본이 되지 않음 | 필요, 세션 스키마 전 |
| D-04 | typed POST+JCS/SHA-256+ACK | 1A 형식·fingerprint·최종 영수증 기반 채택. N-01 결과 GET은 후속 | HTTP 실행·ACK 유실은 1B 게이트 |
| D-05 | 사용자·세대별 직렬 revision/feed | 사용자 행 잠금+단일 revision/batch 채택. 시각/sequence MAX 커서 제외 | 최소 DB·잠금 검증 완료, 실제 할당은 1B |
| D-06 | 루트 version·고정 잠금·다중 집계 원자성 | 1A 채택. version만으로 최신 의도 판단 불가. §7.6 의미별 stamp·새 해결 명령 형식 | 최소 매핑·형식 구현, 도메인 실행 원자성은 1B 이후 |
| D-07 | 합계 반복 우선·동률 최신 등 공식 기준 순위 | 목표 밖 양수 reps 저장, 추가 세트 보존, 첫 N개 기준 평가·합계 우선/실제 종료 시각 동률은 이번 제품 결정. 세부 DTO/동일 시각 tie-break는 기술 제안 | 제품 의미 재승인 불필요, 평가 fixture/남은 기술 상세 검증 |
| D-08 | 서버 device binding·탭 조정 | 전용 HttpOnly 쿠키 후보 vs WebCrypto. 새 로그인 체계 도입 아님. §7.4 상세 보안 승인 전 보류 | 필요, 세션 쓰기/이전 전 |
| D-09 | 종목/설정/일정/메모 version·tombstone | 유지, R을 그대로 두는 별도 편집용 GET/동기화 DTO 권장. 가입 JDBC default 호환 필수 | 필요, 보조 리소스 편집 전 |
| D-10 | 시계·ordinal·오프라인 Initial 검증 | 시계 정확성 가정/보정 없음, 변경·삭제 루틴의 오프라인 수행 수용은 이번 결정. ordinal 안전 복구·서버 계획 원본 대조는 기술 상세 | 시계 오차 정책 재결정 없음, START 전 원본 검증 계약 확정 |

N-01/02의 영수증 기반과 N-07의 예상 계정 전제조건은 009-1A에서 채택했다. 실제 결과 조회는 1B다. N-03(재정렬 제약), N-04(과거 계획 검증), N-05(로컬 탭 fencing), N-06의 제품별 세부 상한은 후속 검토 대상이다. P의 원자성/소유권을 새 정책처럼 재결정하지 않는다.

N-08의 의미별 최신 의도 stamp·새 operation 해결 DTO는 1A에서 채택했다. N-09는 계정 단위 충돌 확인 설정이며 기본 OFF는 확정, 실제 설정 저장/전송은 후속이다. 자동 해결 동작도 미구현이다. N-04 권장은 v0.1의 서명 template에서 **서버 보관 불변 시작 계획 원본**으로 변경한다.

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
  fieldBases: [{resourceType, resourceId: UUID, fieldKey,
                baseStamp: null | {occurredAt: Instant, originOperationId: UUID}}],
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

resourceType 허용값은 EXERCISE/WORKOUT_SETTINGS/ROUTINE/WEEKLY_SCHEDULE/EXERCISE_MEMO/WORKOUT_SESSION/WORKOUT_SYNC_PREFERENCES다. 설정·일정·Preferences resourceId는 principal 계정 UUID, Memo는 정의 UUID다. 이 ID도 권한 근거가 아니며 서버에서 다시 범위를 확인한다. fieldBases는 §7.6의 typed 변경 group에 대한 관측 기준이며 해당하지 않는 명령은 빈 배열이다. ackSchemaVersion은 저장 영수증의 타입 표식이다.

이 전제조건은 신규 생성에서 특히 필요하다. A 계정 확인 GET 직후 다른 탭이 쿠키를 B로 바꾸면, A의 CREATE_EXERCISE가 B의 principal·동일 generation=0으로 도착할 수 있다. 응답을 버리는 것만으로 B에 잘못 저장된 데이터를 막을 수 없다. 생성 당시 expectedUserId=A를 봉인하여 서버가 비교하면 차단된다. 기존 008-0B의 owner 권한 입력 금지와 구별되는 **새 wire 제안**이며 승인 대상이다. 기존 R 두 GET에는 필드/query를 추가하지 않는다.

- Counter: 기존 R과 같은 `0..9223372036854775807`의 canonical 10진 문자열; 증가 overflow 실패, Number 변환 금지. epoch/revision/schemaVersion을 구별한다.
- 신규 ID: 클라이언트 CSPRNG UUID v4로 definition/routine/placement/session/sessionExercise/performedSet/cardioActivity/operation을 생성 의도와 함께 고정. 사용자·seed·사건 ID는 서버 생성. UUID 동일성만으로 인가하지 않음. 기존 읽기 UUID는 원값 유지.
- Decimal: canonical 비음수 평문 문자열, kg=0 허용. D-01 권장은 정수14+소수6자리, 지수/NaN/Infinity/-0/+부호 금지. 입력 draft `62.`는 UI에 보존하되 제출 때 검증·정규화. DB scale 변환 전 서버 검증, 무승인 반올림 금지. PostgreSQL NUMERIC은 선언 scale로 반올림할 수 있다. [공식 NUMERIC](https://www.postgresql.org/docs/18/datatype-numeric.html)
- Count: 양수 JSON 정수, ordinal≥1, position은 0부터. D-02 범위 적용. DurationMs는 비음수 안전 정수≤9007199254740991이며 유산소 실제값 등은 별도 좁은 도메인 범위 적용.
- 신규 명령 Instant는 UTC millisecond `...ss.SSSZ`, DATE는 YYYY-MM-DD, zone은 IANA. 기존 DB microsecond 읽기는 자르지 않음. 기기 운동 시각과 서버 적용 시각 구분.
- 새 이름은 비공백, 기존 표시명 Unicode/공백을 읽기에서 변환하지 않음. N-06 요청크기·문자열/배열 한계는 §12 승인 게이트이며 과거 데이터에 소급 강제하지 않음.

### 4.2 종목·설정·루틴 명령 — 전체 덮어쓰기 제거

**v0.1의 UPSERT_ROUTINE 전체 배열·누락 ID 자동 제거·주간 전체 교체는 구현 전 제안에서 철회한다.** 루트 version과 사건 occurredAt만으로는 어느 속성을 사용자가 실제 바꿨는지 알 수 없다. 오래된 snapshot 배열을 다시 보내면 이름 변경과 무관한 다른 배치의 최신 세트 수·승인 무게·새 배치를 지울 수 있다. 생성은 전체 초기값을 허용하고, 기존 루틴 저장은 사용자가 바꾼 의미만 typed delta로 보낸다. 자유 JSON Patch나 범용 이벤트 시스템은 만들지 않는다.

표의 E는 §9 공통 오류, V는 현재 expectedVersion 검증이다. stale V의 원본은409이고, 안전한 재기준화는 §7.6의 **새 operation**이다. 모든 행은 값·의도 stamp·사건·version·feed·영수증을 한 workout tx로 저장한다. 결과의 changed는 실제 값 변경, intentMetadataChanged는 최신 의도 stamp만 갱신한 경우를 구별한다.

| type / payload | 전제조건·변경 최소 단위 | 정상 result / 추가 오류 |
|---|---|---|
| CREATE_EXERCISE `{id,name}` | 새 UUID, 현재 owner/generation. 이름 중복 허용. 생성 시 name/lifecycle stamp 설정 | `{exerciseId,changed:true}` / ID_UNAVAILABLE |
| RENAME_EXERCISE `{exerciseId,name}` | 활성 정의·V(Exercise), field=name만 변경 | `{exerciseId,changed,intentMetadataChanged}` / RESOURCE_DELETED |
| DELETE_EXERCISE `{exerciseId}` | V(Exercise), 활성 루틴 참조 없음. lifecycle 명령이며 이름 편집의 자동 해결로 삭제/복원하지 않음 | 정의+memo tombstone/통지 / EXERCISE_IN_USE |
| UPDATE_WORKOUT_SETTINGS `{changes:[{field,value}]}` | field는 restLowSeconds/restMediumSeconds/restHighSeconds, 서로 중복 없음, 값>0, V(Settings). 바꾼 필드만 전송 | `{changed,intentMetadataChanged}` / WORKOUT_SETTINGS_MISSING(500), VALIDATION_FAILED |
| CREATE_ROUTINE `{routineId,name,weightPlacements,cardioPlacements,scheduleChanges?}` | 새 루틴/배치 UUID, 전체 초기 구성 검증. weight는 initialWeightKg와 목표. initial=approved, 생성 자체는 증량 사건 아님 | `{routineId,progressChangeEventIds:[],changed:true}` / ID_UNAVAILABLE |
| UPDATE_ROUTINE `{routineId,changes,scheduleChanges?}` | V(Routine), 허용 typed delta 목록 아래 참조. 같은 field/group 중복 변경 금지 | `{routineId,changed,intentMetadataChanged,progressChangeEventIds}` / MERGE_REQUIRES_REVIEW, ACTIVE_ORDER_CONFLICT |
| DELETE_ROUTINE `{routineId}` | V(Routine)+V(WeeklySchedule). 루틴 tombstone과 해당 요일만 정리. 다른 루틴 요일/세션/과거 승인 사건 유지 | `{routineId,changed:true}` / RESOURCE_DELETED |
| REMOVE_WEIGHT_PLACEMENT / REMOVE_CARDIO_PLACEMENT `{routineId,placementId}` | 해당 lifecycle 하나만 변경, V(Routine). 최소 구성·참조 조건 검증 | `{placementId,changed:true}` / VALIDATION_FAILED |
| RESTORE_WEIGHT_PLACEMENT / RESTORE_CARDIO_PLACEMENT `{routineId,placementId,position}` | 지정 tombstone의 명시 복원, 현재 V/활성 루틴. 기존 승인값·ID 유지, 새 승인 사건 없음 | `{placementId,changed:true}` / RESTORE_TARGET_INVALID |
| REORDER_ROUTINE_PLACEMENTS `{routineId,weightPlacementIds,cardioPlacementIds}` | 현재 활성 집합과 정확히 일치해야 함. order만 변경, 누락=삭제 해석 금지. membership가 바뀌었으면 자동 순서 재구성 안 함 | `{routineId,changed}` / ACTIVE_ORDER_CONFLICT, MERGE_REQUIRES_REVIEW |
| UPDATE_WEEKLY_SCHEDULE `{changes:[{weekday,routineId:null 또는 UUID}]}` | weekday0..6 중복 금지, null=해당 요일 해제. V(Schedule)·활성 참조 검증. 요일별 독립 stamp | `{changed,intentMetadataChanged}` / SCHEDULE_CONFLICT |
| SET_EXERCISE_MEMO `{exerciseId,text}` / DELETE_EXERCISE_MEMO `{exerciseId}` | V(Memo), text값과 lifecycle 의미를 구별. 빈 문자열은 저장값이며 삭제 아님 | `{exerciseId,changed,intentMetadataChanged}` / RESOURCE_DELETED |
| SET_WORKOUT_SYNC_PREFERENCES `{askOnConflict:boolean}` (N-09) | 계정 단위 설정, V(Preferences). 기본 OFF. 이 설정 변경 자체에 확인 설정을 재귀 적용하지 않음 | `{askOnConflict,resourceVersion}` / VERSION_CONFLICT |
| RESOLVE_WRITE_CONFLICT (N-08) | 원본 terminal 거절 receipt 확인·새 V·새 operation·동일 권한. 서버 보관 typed intent를 참조 | `{resolutionOutcome,fieldDecisions,changed,intentMetadataChanged,historyChanged}` / CONFLICT_CONFIRMATION_REQUIRED, MERGE_REQUIRES_REVIEW |

UPDATE_ROUTINE.changes의 허용값은 SET_NAME(name), ADD_WEIGHT(새 placementId/definitionId/initialWeightKg/setCount/minReps/maxReps/position), SET_WEIGHT_TARGET(placementId/setCount/minReps/maxReps), APPROVE_WEIGHT(placementId/newWeightKg), REMOVE_WEIGHT(placementId), RESTORE_WEIGHT(placementId/position), ADD_CARDIO(새 placementId/plan/position), SET_CARDIO_PLAN(placementId/plan), REMOVE_CARDIO, RESTORE_CARDIO다. kind별 필드를 DTO에서 제한한다. setCount/minReps/maxReps는 서로 조건이 연결된 **목표 group 하나**로 비교하고, 승인 무게·표시 이름은 각각 별도 group이다. min/max를 서로 다른 기기 값으로 섞어 잘못된 목표를 만들지 않는다. cardio plan도 한 의미 group으로 우선 처리한다.

다중 delta 저장은 기존 편집 UI의 한 번 저장을 원자적으로 유지하는 제한적 복합 명령이다. APPROVE_WEIGHT는 기존 활성 배치만 대상으로 불변 승인 사건을 생성하고 진행 세션을 자동 수정하지 않는다. NEW의 초기 무게와 기존 승인 무게를 혼합하지 않는다. 어느 참조/권한/안전성 조건이든 실패하면 전체 명령을 보류/롤백하며 승인만 먼저 적용하지 않는다. 안전한 scalar 충돌의 일부 field가 과거 의도로 판정되면 그 field는 KEEP_CURRENT로 남기고 다른 안전한 field를 같은 tx로 적용하는 것은 명시 resolution 결과에 기록한다.

scheduleChanges도 변경한 요일만 받는다. '기존 요일을 덮을지 묻기'라는 과거 v0.1 요구는 일괄 충돌 확인 정책으로 재사용하지 않는다. 안전한 동일 요일의 경쟁은 P-NEW-01과 계정 설정을 적용하고, 삭제 루틴 참조/정보 유실 위험 등은 별도 안전 조건이다. 일반 UI의 명시 삭제/종료/강제 이전 확인은 충돌 설정 OFF로 사라지지 않는다.

memo 최초 부재의 실제 memo counter=0 계약, 정의 삭제 시 memo 정리·memoVersion 증가·통지 원자성은 유지한다. 사용자 가입은 기존20종목+설정만 유지하며 추가 루틴/일정/실적을 seed로 넣지 않는다.

### 4.2.1 계정 단위 충돌 확인 설정 (N-09)

권장 신규 GET `/api/v1/workout-sync-preferences` → `{userId,askOnConflict:false,resourceVersion:"0"}`. 현재 R GET `/api/v1/workout-settings`의 세 휴식 필드·generation·query 금지·오류는 그대로 유지한다. 설정 쓰기는 위 typed 명령으로 operationId/원자 영수증을 사용하고 현재 dataGeneration 검증도 유지한다. 값의 수명은 **계정 단위**여서 운동 데이터 reset으로 기본 OFF로 되돌리지 않는다. 해당 기능을 reset에 포함하자는 새 제품 제안은 하지 않는다.

최소 저장은 `user_workout_sync_preferences(user_id PK/FK users.id,ask_on_conflict BOOLEAN NOT NULL DEFAULT false,version BIGINT,last_intent_at,last_intent_operation_id)`이다. row가 없으면 명시 계약상 기본 OFF/version0, GET은 INSERT하지 않는다. 첫 변경은 user 락 아래 부재+expected0 조건으로 삽입/version1; 이후 V 검증으로 갱신한다. 무관한 현재 workout 설정 행이나 generation의 FK를 수정하지 않는다. 새 bootstrap의 syncPreferences에 같은 값을 담을 수 있으나 기존 auth/session DTO도 변경하지 않는다.

서버는 해결 tx의 최신 설정을 최종 판단에 사용한다. OFF이면 안전한 서로 다른 값 충돌을 최신 intent로 자동 해결한다. ON이면 **같은 의미 group을 양쪽이 실제 다르게 바꾼 경우에만** 최신값을 추천하여409 CONFLICT_CONFIRMATION_REQUIRED. 다른 속성 변경·동일값·원본 성공 replay는 확인 대상이 아니다. 기록 삭제/ordinal 충돌/제어권 상실 같은 안전 보류는 이 옵션과 독립이다. 오프라인의 캐시된 설정은 UI 준비용이며 최신 서버 설정을 우회하지 못한다.

### 4.3 세션 DTO·스냅샷의 원본

routineVersionAtStart는 서버에서 확인해 캐시한 **start source의 계획 출처 version**을 뜻하며 오프라인 실제 시작 시 서버 HEAD를 알고 있었다는 주장이 아니다. metadata-only로 루트 V가 증가했어도 계획 내용이 같으면 source를 재사용한다. templateHash는 계획 내용·승인 근거로 계산하고 발행 메타데이터/V/서버시각만 달라져서 불필요한 원본을 만들지 않는다.

| 값 | 권장 필드·원본/수명 |
|---|---|
| Session | `id,sourceRoutineId,routineNameAtStart,phase:WARMUP\|WORKING\|FINISHED,startedAt,workoutDate,startedTimeZone,endedAt\|null,version,ownershipEpoch,writerDeviceId,deletedAt\|null`. FINISHED 유지 상태에서 정정하며 EDITING/동기화완료를 새 phase로 혼합하지 않음 |
| InitialPlanSnapshot v1 | snapshotSchemaVersion,startPlanSourceId,sessionId,sourceRoutineId,routineVersionAtStart,routineNameAtStart,startedAt/workoutDate/startedTimeZone,weightExercises(세션 종목·출처 배치/정의·이름·순서·N/min/max·승인 문맥),cardioActivities(출처/계획). 검증한 source에서 생성, 시작 뒤 불변 |
| AppliedPlanSnapshot v1 | `snapshotSchemaVersion,sessionId,sealedAt\|null,weightExercises[{sessionExerciseId,sourcePlacementId\|null,exerciseDefinitionId,nameInSession,position,plannedSetCount,minReps,maxReps,included,approvedContext:{weightKg,approvalEventId\|null}}],cardioActivities[{sessionCardioActivityId,position,plan,included}]`. 계획/승인 문맥의 유일 수정 원본, 최초 FINISH에 봉인 |
| SessionWeightExercise | 관계형 `id,sessionId,exerciseDefinitionId,sourcePlacementId\|null,nameAtStart,addedDuringSession`. 세션 내 식별/출처, 실제 계획 변경값은 Applied에서 읽음. Initial 소속은 snapshot의 ID로 판단, 현재 배열에서 지워도 원계획 증거 존속 |
| PerformedSet | `id,sessionExerciseId,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId\|null,deletedAt\|null`. 실제 완료 때 생성. 삭제 후 자동 재번호 없음. 빈 예정 세트는 행 생성하지 않음 |
| CompletionAssessmentSnapshot v1 | snapshotSchemaVersion,ruleVersion,assessedAt,sessionVersion,overallWeightPlanComplete,종목별 eligible/reasonCodes/comparisonContext/benchmarkEvidence/selectedPerformedSetIds/extraPerformedSetIds/advice. 최초 종료 당시 선택·판단만 불변 저장 |
| ActiveCheckpoint v1 | `snapshotSchemaVersion,checkpointSequence,capturedAt,currentActivityId,workingWeights[{sessionExerciseId,weightText,mode:TODAY\|APPROVED}],setDrafts[{sessionExerciseId,ordinal,repsText}],timer:REST(endAt,source IDs)\|TRANSITION(since,source IDs)\|null,cardioDrafts`. 복원 보조, 수행/승인 생성 불가 |

고정 식별 행에 계획/수행을 다시 복제하지 않는다. Applied에 실제 weight/reps 전체 배열을 넣어 performed_sets와 이중 원본으로 만들지 않는다. checkpoint TODAY 작업값은 다음 수행에 쓰일 UI 상태이며 승인값이 아니다. COMPLETE_SET는 typed 값과 승인 근거를 별도로 검증한다. 승인 작업 시 Applied 승인 문맥과 지정 session checkpoint 작업값이 함께 바뀔 수 있으나 Initial과 기존 수행값은 그대로다.

`CardioPlan={kind:TREADMILL|BIKE|OUTDOOR|STAIRS|OTHER,name,targetSeconds,targetDistanceKm:Decimal|null,intensity:{mode:SPEED|PACE,value:Decimal}|null,inclinePercent:Decimal|null,resistance:Decimal|null}`. 계획은 Initial/Applied, 실제 활동 state/result는 관계형 session_cardio_activities가 원본이다. 해당 행에 계획 JSON을 또 수정 가능하게 중복 보관하지 않는다.

기존 cardio.ts와 대조한 유지 범위: 이름≤100(현재 JS UTF-16 length 기준), 목표1..86400초, 거리0.001..1000km(거리지원 kind만), speed0.1..100, pace0.1..120, 경사0..50(treadmill만), 저항1..100(bike만), 실제 activeMs1..86400000, 수동 BPM20..300 정수/미입력null. 자동 timerMs는 ≥0 안전 정수이며 보정된 activeMs 상한을 timerMs에 소급 강제하지 않는다. `corrected=false`면 activeMs=timerMs, true면 원본 timerMs 보존. 타이머가 오래 방치돼도 원본을 자르지 않는다. decimal scale은 D-01 승인 필요다.

### 4.4 세션·수행·종료 명령

수행의 nullable approvalOriginOperationId는 미확인 승인을 추적하는 상관관계이며 FK/승인 증명이 아니다. approvalEventId와 별개로 요청/저장 DTO에 추가하는 N-08 상세다. 승인과 첫 수행이 같은 millisecond면 명시 선행 의존/사건 참조로 순서를 확인할 수 있다. 이를 UUID 시간 추정이나 서버 업로드 순서로 대체하지 않는다.

일반 세션 명령은 V(Session)+현재 sessionControl 필요. 표의 행마다 owner·세대·자식범위·deleted 상태를 재검증한다. 같은 이름·같은 ordinal·같은 kg/reps라는 이유로 중복 적용을 추측하지 않는다.

| type / payload | 상태 전제·필수 값 | result / 도메인 원자 범위 | 추가 오류 |
|---|---|---|---|
| START_WORKOUT `{sessionId,startPlanSourceId,initialPlanCandidate,startedAt,workoutDate,startedTimeZone,deviceId}` | 새 세션/종목/activity UUID, 본인 현 세대의 불변 서버 source와 내용 대조(§7.5). 현재 루틴은 수정/삭제 상태여도 수용, 복원 없음 | `{sessionId,version:"0",ownershipEpoch:"0",writerDeviceId}` / 세션+식별 자식+Initial/Applied+제어권 | START_PLAN_SOURCE_NOT_FOUND, START_PLAN_MISMATCH, CLOCK_CONTEXT_INVALID, ID_UNAVAILABLE |
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

`elapsedEvidence={priorAccumulatedMs,runSince:null|Instant,eventAt}`는 기존 state와 맞는지 검사하는 자료다. 서버는 원래 앵커와 이벤트 시각으로 PAUSE/REVIEW의 delta를 산출하고, 수신 지연을 운동 시간으로 합산하지 않는다. CANCEL_REVIEW는 누적값 유지, SKIP은 result=null. 오프라인 전이는 의존순서로 전송한다. 동일 활동 안의 음수 duration/불일치는 CLOCK_CONTEXT_INVALID지만, 서버 현재 시각과의 편차를 추정하거나 보정하지 않는다. 기기 시각 정확성은 P-NEW 가정이다.

`selections[]`는 ADD_MISSING=`{action,performedSetId,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId:null|UUID}`, CORRECT=`{action,performedSetId,weightKg,reps}`, RESTORE_DELETED=`{action,performedSetId,weightKg,reps}`이다. ADD는 신규 ID, RESTORE는 기존 tombstone ID/기존 ordinal·시각 보존. 완료 후에는 최초/봉인 계획에 속한 기존 종목으로 제한하고 새 종목을 만들지 않는다. 검증 근거 없는 승인 문맥을 복구로 위조하지 않는다. 같은 ordinal의 활성 수행이 있으면 사용자가 그 ID의 CORRECT를 선택해야 한다.

UNDO 후 동일 세트를 다시 완료할 때 일반 COMPLETE로 tombstone 해제하지 않는다. 새 완료 의도는 새 performedSetId/operationId를 생성하고 비어 있는 ordinal을 쓴다. 삭제된 이전 ID 복원이 필요하면 RESTORE_DELETED를 사용한다. 예정 슬롯 UUID는 만들지 않는다. CORRECT는 기존 temporary/승인 근거를 자동 바꿔 오늘만 기록을 공식 기록으로 승격시키지 않는다. 정정 kg가 승인 문맥과 다르면 현재 평가에서 제외한다.

COMPLETE의 temporaryWeight/approvalEventId는 클라이언트 주장만으로 공식 자격을 부여하지 않는다. 검증된 시작 source 또는 **그 실제 performedAt 이전의 명시 승인 문맥**과 weight를 대조한다. 정상/역사적 승인 사건은 owner/세대/배치·종목/원래 occurredAt을 확인한다. 늦은 업로드 시 서버 현재 승인값/작업값이65여도 당시 승인60으로 수행한 행은60으로 저장한다. 승인 사건이 서버에 나중에 커밋됐다는 이유로 당시 수행을 거절하지 않는다. TODAY이면 같은 수치라도 temporary=true, 근거 없는 false는 공식 자격을 얻지 못한다. 현재 작업 mode와 과거 실제 수행 문맥을 혼합하지 않으며 checkpoint로 승인 근거를 만들지 못한다.

실제 수행 저장과 공식 승인 근거 확인은 별개다. 예를 들어 삭제를 몰랐던 오프라인 기기의 새 승인 명령이 활성 조건 때문에 거절되더라도, 검증된 시작 source·본인 세션·현재 권한의 실제 kg/reps 행 자체를 잃게 하지 않는다. 기존 실제값/temporaryWeight 의도와 nullable approvalEventId를 보존하고 `approvalOriginOperationId`를 상관관계로 둘 수 있다. 이 원본 ID는 승인 증명이 아니며 타 owner 사건을 연결하지 않는다. 서버가 근거를 확인하지 못하면 현재 평가는 NO_APPROVED_CONTEXT로 표시한다. 현재 승인값으로 kg를 바꾸거나 오늘만으로 몰래 재분류하지 않는다. 이미 봉인된 잘못된 명령은 수정하지 않고 새 저장/선택 복구 명령으로 처리한다. 신규 이 기술 상세는 수행 API 전에 확정하며 invalid foreign 참조·숫자/형식 오류·구 epoch 차단을 완화하지 않는다.

SAVE_ACTIVE_CHECKPOINT가 받는 값은 초안/표시 선택/타이머 복원값으로 한정한다. phase·작업 mode·승인 근거 등 typed 명령이 소유한 필드가 들어오면 현재 서버 문맥과 같아야 하며 이것으로 TODAY→APPROVED를 전환하지 못한다. 타이머 source performedSetId와 세션/종목 범위를 검증한다. COMPLETE 수신 시각에 휴식을 새로 시작하지 않는다. 실제 캐시된 휴식 설정으로 로컬 완료 순간 만든 endAt/since를 보존하고 상태 변경 시 checkpoint로 묶어 전송한다. 늦은 ACK가 로컬 타이머를 재시작하거나 서버의 최신 설정을 과거 휴식에 소급 적용하지 않는다.

FINISH는 수행을 JSON에서 추정 생성하지 않고 현재 관계형 유효 행으로 평가한다. 미완료 초안은 공식 실적에서 제외하되 로컬 원본은 동기화/복구 정책에 따라 보존한다. 실제 완료 세트 수와 예정 세트 수가 달라도 저장 자체를 막지 않는다. 동시 COMPLETE와 FINISH는 §7.3대로 하나만 먼저 성공하고 다른 쪽은 version 충돌이다. FINISHED 재전송은 동일 operation 영수증일 때만 같은 성공이고 다른 신규 FINISH는 INVALID_STATE_TRANSITION이다.

### 4.5 승인 중량·기록·조회

`APPROVE_WEIGHT {routineId,placementId,newWeightKg,baseApprovalContext,source:ROUTINE_EDITOR|WORKOUT,sessionId:null|UUID}`는 활성 루틴/배치·V(Routine), WORKOUT이면 지정 진행 Session의 출처·V/control을 검증한다. `baseApprovalContext={kind:INITIAL_PLACEMENT|APPROVAL_EVENT|START_PLAN_SOURCE,refId,weightKg}`는 본인 해당 배치의 초기값/이전 사건/시작 source와 대조하고 fieldBases의 승인 stamp 계보도 맞춰야 한다. UPDATE_ROUTINE의 승인 delta도 이 값을 포함한다. 결과=`{placementId,currentApprovedWeightKg,approvalContextWeightKg,progressChangeEventId:null|UUID,changed,intentMetadataChanged,historyChanged}`. 현재 승인값과 당시 수행 문맥을 구별한다. 값·불변 사건·관련 Applied/작업값·V·영수증은 한 tx다. 편집기 승인은 다른 진행 Session을 자동 수정하지 않는다. 초기 배치는 initial=approved이며 생성 자체를 증량 사건으로 만들지 않는다. 같은 값의 최신 명시 승인도 stamp는 전진할 수 있지만 무게 변화 사건은 중복 생성하지 않는다. 삭제 루틴/배치의 신규 승인은409 APPROVAL_TARGET_INACTIVE, 오늘만 변경과 검증된 옛 수행 업로드는 허용한다. Initial·기존 수행 kg·과거 사건은 바꾸지 않는다.

**N-08 승인 이력 보존 보완:** 월요일 오프라인60 승인·60 수행, 화요일65 승인이 먼저 서버에 왔다면 최신 현재값은65로 유지하면서 월60의 실제 승인 문맥은 잃지 않아야 한다. 동일 owner/세대/올바른 control의 원래 승인 의도를 검증한 해결 명령은 불변 progress 사건을 `effect:HISTORICAL_CONTEXT_ONLY`로 남기고 수행의 승인 근거로 연결할 수 있게 하는 안을 권장한다. 일반 현재값 변경은 CURRENT_VALUE_UPDATED다. 사건의 `previousApprovedWeightAtIntent`는 원래 base 승인 문맥으로 검증하고, server의 현재65를 월요일의 이전값으로 꾸미지 않는다. base 근거가 없으면 원본 보존 후 안전 보류한다. 당시60 수행을65로 수정하거나 오늘만으로 강제 변환하지 않는다.

사건은 `(owner,generation,placementId,originOperationId)` UNIQUE, `operation_id`는 실제 커밋한 원본/해결 operation FK, `origin_operation_id`는 최초 의도 상관관계다. 미성공 원본의 receipt FK가 있다고 가정하지 않는다. 같은 역사적 승인 재해결은 기존 사건 ID를 반환하고 중복 생성하지 않는다. 역사적 사건 추가도 historyChanged=true로 부모V/feed/receipt를 함께 갱신한다. 이 기술 상세는 제품의 명시 승인/실제 수행 보존을 실현하기 위한 제안이며 새 사용자 승인 무게 자동 변경 정책이 아니다.

'같은 값 승인 사건 없음'은 **의도 당시 base 승인값도 같은 재선택**을 뜻한다. 현재 서버가65라는 이유만으로 과거 base55→65의 명시 승인 사실을 없애지 않는다. 기존65 사건이 실제 수행 뒤의 시각이면 과거 수행의 근거로 쓸 수 없으므로, 검증한 과거55→65는 HISTORICAL_CONTEXT_ONLY가 필요하다. 반대로 당시65→65 재선택이면 이미 그때 유효했던 source/승인 사건을 재사용하고 최신 intent stamp만 전진시킨다.

이력/평가 DTO는 `{session,initialPlan,appliedPlan,completionAssessment,weightExercises,performedSets,cardioActivities,activeCheckpoint,currentAssessment,summary}`를 기본으로 한다. `summary={completedSetCount,volumeKg:Decimal,elapsedMs,cardioCompleted,cardioTotal}`. volume은 현재 유효 완료 웨이트 weight×reps 합, 실제 수행 원본에서 계산한다. 종료 후 현재 평가/차트만 달라지고 completionAssessment는 바꾸지 않는다.

추가 GET 제안은 모두 no-store·principal 범위이며 **기존 R 두 GET의 DTO·query·오류를 바꾸지 않는다**.

| Method/path | 요청 → 직접 성공 DTO | 동시성·오류 |
|---|---|---|
| GET `/api/v1/exercises/{id}` | UUID → `{userId,dataGeneration,exercise:{id,name,resourceVersion,createdAt,updatedAt}}` | 편집 version 전용, 미존재/foreign/삭제404 |
| GET `/api/v1/sync/bootstrap` | 없음 → owner/generation,편집용 exercises/settings/routines/schedule/memos,값별 intentStamps,계정 syncPreferences,저장된 startPlanSources,activeSessions,cursor | 동일 REPEATABLE_READ snapshot, 누적 전체 이력 포함 안 함. 기존 R DTO는 version/stamp 없는 형태 그대로 |
| GET `/api/v1/routines`, `/{id}` | 목록 cursor?/limit? 또는 UUID → `{userId,dataGeneration,data}` | 상세 routine/배치/출처 versions, placement-options는 삭제 후보 ID/이전 승인값 제공 |
| GET `/api/v1/routines/{id}/placement-options?exerciseDefinitionId=...` | 정의 UUID → scope+`{active,restorable}` | 동일 정의/루틴 후보만, 동명 매칭 금지 |
| GET `/api/v1/workout-sessions`, `/{id}` | fromDate?/toDate?/cursor?/limit? → scope+page/detail | 목록 FINISHED 비삭제, 날짜는 workoutDate 포함 범위. 상세 자체 snapshot 일관 |
| GET `/api/v1/workout-sessions/{id}/assessment` | UUID → scope+`{completionAssessment,currentAssessment}` | version/평가시점 포함, 과거 snapshot과 현재 평가 구별 |
| GET `/api/v1/routines/{id}/weight-placements/{placementId}/progress` | UUID → scope+`{approvedWeightKg,benchmark,latestComparable,advice}` | 공식 선택 D-07 전 미구현 |
| GET `/api/v1/progress-change-events`, `/api/v1/workout-sessions/{id}/record-change-events` | 필터/커서 → scope+`{items,nextCursor}` | 본인 사건만; before/after 허용 도메인값만 |
| GET `/api/v1/sync/changes?cursor=...&limit=...` | 커서 → §8.3 ChangePage | 만료410, 형식400, 구세대409 |
| GET `/api/v1/workout-operations/{operationId}?dataGeneration=...` (N-01) | UUID/Counter → 성공200 저장 ACK 또는 보관된 terminal409의 code 오류 | 미확인404·구세대409, 성공/실패를200 공통 래퍼로 감싸지 않음 |

일정/메모의 개별 GET 후보는 008-0B와 같고 편집 version은 bootstrap 또는 이 신규 GET으로 획득한다. 목록 기본50/최대100, 세션 `workoutDate DESC,startedAt DESC,id DESC`, 사건 서버시각 DESC/id DESC의 keyset 권장. 커서는 필터/owner/generation에 바인딩하고 다음 페이지 전 변경으로 재조회 필요할 수 있다. 전체 누적 이력을 bootstrap에 싣지 않는다.

### 4.6 최종 유효 수행·안내·통계·공식 과거 비교

이 절은 **이번 P-NEW-02~04 반영**이며 v0.1 D-07의 추가 수행 일괄 제외/미결 순위 제안을 대체한다. 목표 min/max는 입력 제한이 아니다. 신규 실제 reps는 양수면 저장하고 kg·정수/기술 범위 검증만 별도로 수행한다. N세트 계획에 N+1세트를 수행해도 행을 모두 보존하고 루틴 N을 자동 수정하지 않는다.

**결정적 루틴 기준 선택 권장 계약:**

1. Initial의 원래 sessionExerciseId별로 시작 당시 `N=plannedSetCount`, minReps/maxReps를 읽는다. 현재 루틴이나 진행 중 수정된 Applied 목표로 원래 안내 기준을 바꾸지 않는다.
2. 해당 종목의 삭제되지 않은 실제 완료 performed_sets를 수집한다. 중간 취소·삭제·structureModified 사건은 평가 입력이 아니며 현재 유효값만 사용한다.
3. `ordinal ASC → performedSetId의 canonical UUID ASC`로 정렬한다. 활성 ordinal UNIQUE 때문에 정상 DB에는 동률이 없으며 ID는 결정성을 명시하는 마지막 기준이다. 세트 삭제 후 자동 재번호/성적순 정렬하지 않는다.
4. 유효 개수 M<N이면 정상 완료 미충족/증감 안내 UNAVAILABLE. M≥N이면 **정렬상 처음 N개**를 selectedPerformedSetIds로 고정한다. 나머지는 extraPerformedSetIds이며 저장·통계에서 제외하지 않는다.
5. 선택 N개가 전부 max 이상이면 INCREASE, 한 개라도 min 미만이면 DECREASE_REVIEW, 그 밖 MAINTAIN. 이 값은 안내이고 승인 무게 자동 변경이 아니다. 실제 증감 안내를 정상 승인 문맥으로 제시할지와 임시/혼합 수행 표시는 아래 무게 규칙을 함께 적용한다.

| Initial N=3 / 목표8~12 / 현재 유효 reps(ordinal 순) | 선택 N개 | 안내 / 보존 |
|---|---|---|
| 12/12/12 | 12/12/12 | 증량 권장 |
| 12/12/12/10 | 12/12/12 | 증량 권장, 4세트 전부 저장 |
| 12/10/12/15 | 12/10/12 | 유지, 마지막15를 유리하게 끼워 넣지 않음 |
| 6/5/4/12 | 6/5/4 | 감량 검토, 추가12로 첫3개를 대체하지 않음 |
| 14/13/12 | 14/13/12 | 증량 권장, 목표 초과 reps도 저장/후보 허용 |
| 12/12 | 2개뿐 | 계획 수 미달, 정상 완료 기준 미충족 |
| ordinal1 삭제, ordinal2/3/4 유효 | 2/3/4의 현재값 | 처음 남은3개 선택, 삭제된1은 통계/평가 제외 |
| ordinal2를 새 ID로 다시 완료 | 현재 유효 ordinal1/2/3 | 새로운 ID를 실제 순서대로 선택, tombstone ID 자동 복원 없음 |

**무게와 원계획 범위:** Initial의 source placement/definition·원래 목표 문맥, 정상 승인 중량·오늘만·혼합 중량 구분을 유지한다. 첫 실제 수행 전에 새 무게를 명시 승인하고 정상 수행한 사례는 기존대로 해당 문맥 후보다. 과거 수행 kg를 최신 승인값으로 고치지 않는다. 승인 전에 수행했다가 현재 모두 취소/삭제된 기록은 그 조작 이력만으로 현재 자격을 영구 박탈하지 않는다. 최종 유효 수행의 승인 근거/무게로 재평가하되 과거 승인 사건은 보존한다.

추가 세트만 있다는 이유로 **EXTRA_VALID_PERFORMANCE를 생성하지 않는다**. 원래 다른 웨이트 종목이 미완료인데 제거한 경우는 여전히 원계획 미완료다. 운동 중 새 종목 추가도 그것만으로 타 종목의 후보를 일괄 제외하는 구조 flag로 사용하지 않는 해석을 권장한다. 새 종목의 전체 통계는 포함하되 그 종목에 Initial 비교 문맥이 없으면 해당 종목의 루틴 기준 후보는 별도로 UNAVAILABLE이다.

**009-1A 확정 보완:** 승인 중량·오늘만 중량·혼합 중량 및 반복 목표의 판단 범위는 계획 N개의 **첫 N개 유효 수행**이다. N개 이후의 추가 세트는 중량이 달라도 루틴 기준 안내 자격을 박탈하지 않는다. 예를 들어 첫3개가 정상 승인60kg이고 추가1개만 오늘만50kg이면 첫3개로 정규 기준을 평가하며 전체4개는 통계에 포함한다. 반대로 첫3개 안에 오늘만/혼합 중량이 있으면 기존 정규 비교 제한을 유지한다. 삭제·정정 후에는 ordinal/ID 순서의 현재 유효 수행에서 N개를 다시 선택하고 같은 범위를 적용한다. 이 범위는 미결 리뷰 항목이 아니다.

**전체 통계와 루틴 비교는 서로 다른 projection이다.** 전체 세트 수·총 reps·volume=sum(유효 weight×reps)·사용 무게 목록은 4세트라면 4세트를 모두 사용한다. 루틴 기준 비교만 selected N개를 쓴다. summary/향후 분석을 위해 나머지 실제 행을 자르거나 JSON에 통계용 복사본을 만들지 않는다. 새 통계 화면/범용 분석 알고리즘/PR·1RM·benchmarks 저장 테이블은 이번 범위가 아니다.

공식 비교 문맥은 `{routineId,sourcePlacementId,exerciseDefinitionId,initialPlannedSetCount,minRepsAtStart,maxRepsAtStart,effectiveApprovedWeightKg,unit:"KG"}`다. 이름·sessionExerciseId는 세션 간 계보 key가 아니다. 비교 가능한 완료 과거 세션마다 위 규칙으로 N개를 선택하고 **그 N개 reps 합계 DESC → 해당 운동 session.endedAt DESC**로 한 세션을 고른다. endedAt은 실제 운동 시각이며 serverReceivedAt/updatedAt은 순위에서 제외한다. 완전 동률의 sessionId ASC는 의미상 우열이 아닌 결정성을 위한 기술 권장안이다. 서로 다른 세션의 최고 세트를 합성하거나 추가 세트 수만으로 루틴 기준 합계를 늘리지 않는다. 전체 수행 비교가 필요한 미래 화면은 별도 기준을 명시한다.

기존 과거 후보의 event endedAt≤대상 startedAt 조건은 유지하는 권장안이다. 기기 시각을 정확하다고 가정하며 서버 업로드 지연으로 과거 순서를 바꾸지 않는다. 현재 대상 자신·미완료·삭제 세션/세트는 제외한다. 과거 세션 정정/삭제 시 현재 benchmark/currentAssessment와 차트만 재계산한다.

응답 제안은 `Assessment={status:PROVISIONAL|FINAL,ruleVersion:"task009-user-policy-v0.2",sessionVersion,eligible,reasonCodes,comparisonContext,selectedPerformedSetIds,selectedReps,extraPerformedSetIds,benchmark:{sessionId,sessionVersion,selectedPerformedSetIds,reps}|null,latestComparable,advice}`다. 당시 판단 snapshot에는 선택 ID/당시 ruleVersion을 고정 보존하고 현재 평가는 별도 응답이다. 신규 계획/판정 schema는 미구현이므로 snapshotSchemaVersion=1과 정책 ruleVersion을 구별하면 된다. 이미 봉인된 과거 snapshot을 새 규칙으로 덮지 않는다.

대표 사유는 INCOMPLETE_INITIAL_WEIGHT_PLAN/INSUFFICIENT_VALID_SETS/TEMPORARY_WEIGHT/MIXED_WEIGHT/NO_APPROVED_CONTEXT/NO_INITIAL_COMPARISON_CONTEXT/SESSION_DELETED다. SET_COUNT_MISMATCH를 M>N에 적용하거나 EXTRA_VALID_PERFORMANCE를 추가 세트 자체에 적용하지 않는다. 3→4→3, 다른 종목 삭제 우회 차단, 오늘만/혼합, 첫 수행 전 명시 승인, 유산소 skip의 독립성은 함께 회귀 검증한다.

## 5. operationId·멱등 영수증·충돌 계약

### 5.1 식별·생성·봉인

operationId는 **로컬 의도 생성 시** 발급, Outbox PK 일부이자 서버 멱등 key다. Request ID는 개별 HTTP 요청/조회/재송신의 추적값이다. 한 operation에 여러 Request ID가 존재한다. 어느 ID도 인증 증명이 아니다.

명령 생성 tx에는 owner/generation/당시 epoch(또는 명시 권한 선행 명령), 도메인 UUID, 사용자의 값, occurredAt, 선행 operation IDs를 저장한다. 서버 version을 오프라인에서 추측하지 않는다. 의존 ACK를 얻으면 처음 송신 직전에 그 ACK로 설명되는 expectedVersions·참조 사건 ID를 확정하여 **봉인본을 IDB에 커밋한 뒤** 송신한다. 명시 선행 APPROVE ACK의 사건 ID를 바인딩하는 것과 새 서버 값을 조회해 충돌을 덮는 것은 다르다.

구 epoch 의도를 최신 epoch로 교체하지 않는다. START/정상 이전의 명시 선행 권한만 해석할 수 있다. 첫 송신 뒤 payload/type/occurredAt/expectedVersions/generation/epoch/IDs는 불변. 수정 의도는 새 operation이며 원래 불확실 명령의 적용을 확인한 뒤에만 만들 수 있다.

### 5.2 fingerprint의 정확한 입력 (D-04)

`SHA-256(UTF8("overload-command-v1\n" + principalUserIdLowercase + "\n" + JCS(CommandRequest)))`의 소문자64 hex 권장. type/schemaVersion/operationId/expectedUserId/generation/expectedVersions/fieldBases/sessionControl/occurredAt/payload 전체 포함. 해결 payload의 originOperationId/originFingerprint/모드/선택·source ID도 포함한다. 쿠키/CSRF/개인키/재시도횟수/Request ID/서버 수신시각/로컬 의존 메타데이터는 제외한다.

서버에서 재계산하며 클라이언트 해시를 신뢰하지 않는다. 허용 enum/필드·null을 schemaVersion별 고정, UUID 소문자, canonical Counter/Decimal/Instant만 수용한다. 배열은 도메인 순서 유지(expectedVersions만 계약 순서), 문자열 Unicode를 trim/NFC/대소문자 변환하지 않는다. 객체 중복 key/알 수 없는 필드/고립 surrogate·비정상 수치는 거절한다. JCS에 decimal/BIGINT를 JSON number로 맡기지 않는다. 단순 JSON.stringify/Map 정렬만을 RFC 구현이라 부르지 않는다. [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)

객체 key 순서/JSON 공백만 다른 정상 요청은 같은 의미와 fingerprint, 도메인 배열 순서·null/값 차이는 다른 fingerprint다. 서버는 계약대로 정규 형식을 요구하며 다른 소수/UUID 표기를 재전송 때 몰래 보정하지 않는다. 한글·결합문자·이모지·escape·null·0kg·큰 Counter·배열·다중버전 golden vectors가 승인/구현 게이트다. 신규 JCS 라이브러리는 아직 선택/추가하지 않는다.

**1A 구현 동결:** 기존 Jackson/JDK만 사용한다. 허용 JSON number는 정확한 safe integer뿐이며 kg/Counter는 문자열이다. 허용 입력의 객체 UTF-16 정렬·최소 escaping·Unicode 무정규화 출력은 JCS 규칙을 따르되 범용 부동소수점 JCS 구현을 제공하지 않는다. float/exponent number, 중복 key, 고립 surrogate, PostgreSQL JSONB에 저장할 수 없는 NUL은 거절한다. codec 한도는 UTF-8 256KiB/깊이64/number token32자이며 HTTP 413 매핑은 1B다. 이름200자 같은 추가 제품 상한은 도입하지 않았다. 현재 수용하는 네 payload와 엄격한 키·정렬·null 형식은 1A 보고서/DTO를 따른다.

### 5.3 원자적 서버 알고리즘 — 성공과 해결 가능한 확정 거절

권장 쓰기는 READ_COMMITTED의 workoutTransactionManager 하나다. user 락·멱등 UNIQUE·명시 V·권한 검증은 유지한다. **v0.2는 N-08 해결에 필요한 두 거절도 기존 receipt에 남기는 안으로 N-02를 수정**한다. 별도 접수 registry를 만들지 않는다.

1. 기존 Security/CSRF·JSON/크기 검증·expectedUserId=principal 전제조건 후, tx 안 user 행을 가장 먼저 FOR UPDATE하여 현재 generation을 확인한다. 구세대/다른 owner/proof는 최신 의도로 우회하지 않는다.
2. **1A에서 예약 행을 없애고 최종 상태만 저장하도록 단순화했다.** user 락을 보유한 채 PK(owner,generation,operationId)로 최종 receipt를 먼저 조회한다. SUCCEEDED+같은 fingerprint는 원래 ACK, REJECTED+같은 fingerprint는 **원래409**다. 다른 fingerprint는 OPERATION_ID_REUSED. SELECT만을 동시성 방어로 사용하지 않으며, user 락과 NOT DEFERRABLE PK를 함께 유지한다. 최종 receipt INSERT의 UNIQUE/SQL 오류는 전체 rollback하고 실패 tx를 재사용하지 않는다. [PostgreSQL isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
3. 새 명령이면 본인/참조/control·삭제상태·입력과 기대 V·typed fieldBases·stamp를 **도메인 변경 전에** 평가한다. VERSION_CONFLICT 또는 CONFLICT_CONFIRMATION_REQUIRED이면 최종 REJECTED receipt에 fingerprint·안전한 오류·원래 검증된 typed intent를 같은 tx로 저장한다. 도메인/사건/V/revision은 변경하지 않는다. 이 경로는 예외를 던져 rollback시키지 않고 내부 실행 결과로 반환하여 정상 commit 후 바깥 Controller가409를 보낸다.
4. 적용 가능하면 선택한 typed 값/intent stamp/필요 실제·역사적 사건을 기록한다. 값·stamp·사건 어느 것도 바뀌지 않을 때만 순수 no-op다. metadata/history-only도 루트 V+1 및 feed 대상이다. JPA 최종 flush/직접 SQL 정합성 확인 후 결과 V를 확정한다.
5. 도메인·사건·stamp·루트 V·revision/batch·성공 receipt를 한 tx로 커밋한다. no-op 성공도 SUCCEEDED receipt는 남긴다. V9에는 PROCESSING 상태 자체가 없다. 처리 종료 시 SUCCEEDED 또는 위 REJECTED가 필수이며 handler의 완료 검사를 유지한다. commit 검사 trigger는 최초 기반에 도입하지 않는다. 이 알고리즘의 실제 명령 실행·HTTP 검증은 1B다.
6. 검증된 논리 거절을 저장하는 경로와 SQL/flush/commit 오류를 구별한다. 무결성/SQL/optimistic/DB 확정 실패는 **전체 rollback**이며 그 실패 tx 안에서 거절 receipt를 후처리로 남기지 않는다. 500/503와 commit 연결 유실은 전체 성공/전체 rollback 중 결과 미확인일 수 있으므로 같은 봉인본/결과 조회로 확인한다.
7. 정상 commit 반환 뒤에만 HTTP ACK 또는 저장된409를 직렬화한다. 직렬화/Session 독립 tx/네트워크 실패는 도메인 커밋 여부를 역전시키지 않는다. appliedAt/serverRecordedAt은 논리 기록 시각이며 정확한 물리 commit instant가 아니다. 순서는 revision, intent 우선은 occurredAt이다.

성공의 값/사건/receipt 원자성과, 거절의 '도메인 미변경+거절 receipt 커밋'은 각각 명확한 한 tx다. 두 TransactionManager를 중첩하거나 오류 난 tx를 구제하려는 별도 독립 receipt tx를 도입하지 않는다.

### 5.4 경쟁·삭제·세대·실패의 결과

| 상황 | 권장 결과와 불변식 |
|---|---|
| 동일 key·동일 fingerprint의 동시 요청 | 첫 tx의 최종 결과를 기다려 성공이면 ACK replay, terminal409면 같은 거절. 도메인/사건/stamp/V/feed 중복0 |
| 동일 key·다른 fingerprint | 먼저 커밋된 SUCCEEDED/REJECTED 양쪽 모두409 OPERATION_ID_REUSED. key가 rollback되어 최종 receipt가 없을 때만 성공/거절 binding 없음 |
| 첫 요청 처리 중 롤백/서버 crash | 도메인+최종 영수증 모두 없음. 경쟁 요청/동일 봉인 재시도가 신규 처리 가능. 별도 PENDING 영수증이 영구 잔류하지 않음 |
| DB 반영 후 응답 유실·timeout·abort | UNKNOWN. 같은 봉인본 재전송/결과 GET으로 확인. 새 ID로 재생성 금지 |
| 성공 뒤 자원이 삭제됨 | 같은 현재세대 영수증은 기존 성공 확인, 자원을 다시 생성/복원하지 않음. 최신 resource GET은404일 수 있음 |
| 성공 뒤 version/ownershipEpoch 증가 | 영수증은 원래 결과. 과거 ACK의 version/epoch로 최신 cache/제어권을 되돌리지 않음 |
| dataGeneration 변경 | 영수증 조회/재전송 모두409 DATA_GENERATION_MISMATCH. 구세대 적용 결과를 새세대에 공개·재실행하지 않음 |
| 다른 계정 로그인 | principal별 별도 key, GET은 요청 operation의 다른 owner 존재를 노출하지 않음. 클라이언트는 애초 old queue 송신 차단 |
| 순수 no-op | 값·stamp·사건 모두 변경 없으면 영수증만, 원래 V/changeCursor=null. 같은값 최신 intent stamp 갱신은 순수 no-op가 아니며 V/feed 필요 |
| 현재세대 성공 영수증/필수 결과 훼손 |500, 리소스 상태를 보고 성공 추측/재적용 금지 |

**N-02 개정 권장:** 성공/순수 no-op와, 자동 해결에 필요한 VERSION_CONFLICT·CONFLICT_CONFIRMATION_REQUIRED 두 논리409만 terminal receipt로 보존한다. 두 거절도 key/fingerprint를 고정하여 같은 원본이 나중에 적용될 수 없게 하고, 새 해결 operation이 서버 보관 원본을 참조한다. 그 밖의 인증/형식/입력/SQL 실패까지 영구 거절 binding을 보장하지 않는다. 예를 들어 저장하지 않은 EXERCISE_IN_USE는 참조가 사라지면 나중에 같은 요청이 성공할 수 있으므로 자동 재송신하지 않는다. 모든 실패를 영구 재현하려는 범용 접수 시스템은 만들지 않는다. 이 변경은 v0.1 성공-only 제안과의 차이이며 사용자 최신 의도 정책을 안전하게 구현할 기술 제안이다.

### 5.5 응답 미수신 결과 조회 (N-01)

`GET /api/v1/workout-operations/{operationId}?dataGeneration={Counter}`는 principal/current generation의 같은 readOnly snapshot에서 조회한다. SUCCEEDED면200 저장 CommandAck(outcome=REPLAYED), REJECTED면 저장된409/code/operationId와 안전한 details를 반환한다. details는 operationState=REJECTED/fingerprint/당시 관측 V만 허용한다. no-store, 제어권 없는 동일 owner의 결과 확인은 허용하되 새 쓰기 권한을 주지 않는다. 거절 조회를 성공 ACK로 처리하지 않는다.

- 404 OPERATION_NOT_FOUND: 그 snapshot에서 **커밋된 성공을 찾지 못함**. 요청 미도착/진행 중/rollback/기존 거절을 구별하지 못한다. definitive NOT_APPLIED/안전한 새ID 재실행 승인으로 해석하지 않는다. negative cache 금지.
- 409 DATA_GENERATION_MISMATCH는 old queue 격리다. 저장된 VERSION_CONFLICT/CONFLICT_CONFIRMATION_REQUIRED는 원본 미적용의 확정 결과이며 N-08 새 해결을 진행할 수 있다. 실제 최신값은 별도 상세 조회로 확인한다.
- 400 INVALID_REQUEST: UUID/Counter/query 형식, 401/403 기존 경계, 500/503은 §9.
- 필요하면 미확인 뒤 제한 backoff 후 **동일 봉인 POST**를 한다. 그 POST의 UNIQUE/receipt가 원래 요청과 경합해도 한 번만 적용한다. 무기한 polling 대신 사용자에게 커밋 미확인을 표시한다.

응답의 owner/generation/operationId/fingerprint/schema/result 타입을 Outbox 봉인본과 검증해야 ACK로 수용한다. 리소스 GET의 값 일치나 Request ID 존재는 특정 명령 성공 증거가 아니다. outcome만 APPLIED→REPLAYED로 달라질 수 있으며 result/resultVersions/appliedAt은 원본 불변이다. 커서 서명키 교체에 따른 opaque cursor 재발급이 필요해도 논리 revision은 바뀌지 않는다.

### 5.6 보존·조회·만료 (N-02)

MVP 권장은 **활성 generation의 성공·선택적 terminal409 영수증과 삭제 ID tombstone을 단기 TTL로 정리하지 않는 것**이다. 해결에 필요한 거절 typed intent도 원본 key와 함께 보존한다. 보존 작업/영구 백업을 이번에 구현·약속하지 않는다. 초기화/탈퇴의 구세대 차단·파기는 별도 운영 계약이다.

향후 receipt 본문을 정리하려면 key+fingerprint+결과만료 표식은 dedupe 장벽으로 보존하고 POST/GET을410 OPERATION_RESULT_EXPIRED로 거절하는 새 계약을 먼저 승인받아야 한다. 만료 key를 미존재로 취급해 재적용해서는 안 된다. 최소 N일 보존 후 key까지 삭제하는 안은 무기한 오프라인 재전송과 양립하지 않는다. feed cursor 만료는 영수증 만료와 독립이다. schemaVersion=1 영수증을 조회할 deserializer/응답 지원은 해당 보존 기간 동안 유지해야 한다.

## 6. PostgreSQL·Flyway·JPA 스키마 초안

이 절의 전체 도메인 구조는 논리/컬럼 제안이다. 1A는 최소 부분만 V9로 구현했으며 나머지 테이블·필드가 존재한다는 뜻은 아니다. 실제 V9 구조·제약명은 migration과 1A 보고서를 따른다.

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
| progress_change_events | id PK, Scope,routine_id/placement_id/related_session_id,source,origin_operation_id,operation_id FK,effect,intent_previous_weight/intent_next_weight,previous_weight/next_weight nullable,occurred_at/server_recorded_at,당시 이름 | 복합 FK·UNIQUE(Scope,placement_id,origin_operation_id), intent kg≥0. CURRENT_VALUE_UPDATED는 현재값 전후 필수/서로 다름, HISTORICAL_CONTEXT_ONLY는 현재 전후 null·의도 전후값 보존. 시간/placement 인덱스 |
| record_change_events | id PK, Scope,session_id,session_exercise_id nullable,performed_set_id nullable,change_type,before_values/after_values JSONB,server_recorded_at,operation_id | 해당 세션/자식 관계 복합 FK. DTO 허용 실제값만 JSON, 원문 요청 없음. 세트 정정/취소/삭제/복구·세션삭제 사건만, 클릭 로그 없음. `(Scope,session_id,server_recorded_at,id)` |
| sync_operations | PK(Scope,operation_id) NOT DEFERRABLE, fingerprint/type/schema,state,성공 ack_body,거절 code/intent_command JSONB,origin_operation_id/origin_fingerprint,recorded_at/change_revision | 1A V9는 SUCCEEDED/REJECTED만 수용. REJECTED는 VERSION_CONFLICT/CONFLICT_CONFIRMATION_REQUIRED만, 성공과 거절 필드 CHECK 분리. 임의 비밀 저장 금지, 거절 typed intent만 보존 |
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
| 첫 확장 (V9, 1A) | 기존 정의/설정 version·intent_stamps, 정의 tombstone의 additive default, receipts/sync-state/batches. memo_version은 실제 메모 도입 때로 연기. 기존 JDBC 가입·R 조회·V1~V8 checksum 불변 |
| 보조 편집 묶음 | exercise_memos와 실제 memo counter 경로. 009-2A의 정의/설정/메모 편집이 의존할 테이블부터 도입. 활성 루틴 참조가 필요한 정의 삭제는 계획 묶음 이후 활성화 |
| 계획 묶음 | routines/배치/일정·복합 FK/순서 제약, 편집기 승인용 progress_change_events. 세션 참조 컬럼/FK·WORKOUT source 허용은 다음 세션 묶음에서 additive 확장. 중량 D-01·숫자 D-02·D-09 승인 선행 |
| 세션·사건 묶음 | sessions/session exercises/sets/cardio/record events, progress 사건의 related_session_id/FK와 source 확장, snapshot·epoch·제어권. 참조 cycle는 테이블 생성 후 FK 추가 순서로 해결. 사건/세트 FK와 승인 atomic gate |
| 제어권·오프라인 근거 선택분 | 선택된 device binding 구조, N-04의 계획 근거 저장이 필요하면 해당 구조. 자동 새 보안체계/이벤트 저장소 도입 없음 |

정확한 분할/파일명은 구현 리뷰에서 확정한다. 빈 Testcontainers DB에 V1→새 버전, 기존 V8 합성 데이터 보유 DB에 upgrade 두 경로를 검사한다. 기존 종목 UUID/name/timestamps/seed·auth 직렬화 유지, 새 default 읽기/쓰기와 JPA validate 확인. 전체 테스트의 'Entity 정확히2개' assertion은 추가된 허용 Entity 목록으로 명시 확장하며 검사를 없애지 않는다. 파괴적 축소/기존값 반올림/일괄 재ID는 이 로드맵에 없다.

### 6.5 이번 정책에 필요한 최소 DB 추가분과 단순화

§6.2의 v0.1 기본 관계에 대한 추가/수정 제안이다. 의미별 stamp와 해결 상관관계의 최소 부분은 1A V9에 구현했으며 다른 행의 기능은 아직 미구현이다.

| 목적 | 최소 추가 필드/행 | 제외할 복잡성·도입 시점 |
|---|---|---|
| 의미별 최신 의도 | 편집 가능한 루트의 `intent_stamps JSONB NOT NULL DEFAULT '{}'`에 허용 group→occurredAt/originOperationId. routine은 UUID별 key, memo는 별도 memo 행 stamp, schedule은 요일별 | root 마지막 timestamp 하나로 대체 금지. 전체 변경 이력/벡터시계/CRDT/시계 보정 없음. 최초 편집 스키마부터 |
| 충돌 확인 설정 | §4.2.1의 user_workout_sync_preferences 계정 PK, ask_on_conflict=false,version,마지막 intent 두 필드 | 기존 R settings DTO/인증 세션 변경 없음. workout reset FK/cascade 제외. resolver 공개 전 |
| 해결 상관관계 | sync_operations의 origin_operation_id/origin_fingerprint, REJECTED code/intent_command, result의 resolutionOutcome/fieldDecisions/intentMetadataChanged/historyChanged | 같은 기존 테이블에서 원본 거절 확정. 별도 일반 접수 registry/sync_conflicts 서비스 없음 |
| 서버 시작 계획 근거 | §7.5의 workout_start_plan_sources 1종, sessions.start_plan_source_id의 owner/generation 복합 FK | 서명키/토큰 만료·전체 revision 서비스 대신 내용 변경 시 필요한 원본만. 세션 쓰기 전 |
| 실제 수행 전체 보존 | performed_sets의 UUID·session/종목 FK·ordinal·정확한 kg·양수 reps·실제 performedAt·temporaryWeight·nullable approvalEventId·approval_origin_operation_id·deleted_at, 정정 전후 사건·stamp | 실제값과 승인 근거 판정 분리. N개로 잘라 저장/JSON 수행 복제/slot UUID 없음. 수행 스키마 전 |
| 승인 당시 사실과 현재값 분리 | progress_change_events에 origin_operation_id,effect,intent_previous_weight/intent_next_weight,실제 현재값 변화 previous_weight/next_weight(nullable),occurred_at,operation_id FK. UNIQUE(Scope,placement_id,origin_operation_id) | CURRENT_VALUE_UPDATED는 실제 전후값 보존. HISTORICAL_CONTEXT_ONLY는 현재값 변화 두 필드 null. 의도 전후값은 별도 검증. 승인 쓰기 전 |
| 평가 선택 근거 | completion_assessment_snapshot.ruleVersion/selectedPerformedSetIds, 현재 평가 응답의 selected/extra IDs | 과거 snapshot 소급 변경·사전 benchmark 테이블 없음. 평가 구현 전 |

intent_stamps는 typed 서버 DTO로 검증하고 arbitrary JSON merge를 API에 노출하지 않는다. 기존 V8 root의 기본 {}는 '기기 intent 미관측' 상태다. 불변 원본/사건의 서버 생성 UUID·시각을 바꾸지 않는다. root stamp·자식 수정·승인 사건·부모 V·영수증/feed가 같은 workout tx에 들어가며 JDBC/native 혼용 후 stale Entity 처리는 §6.4/7.2를 따른다. memo의 별도 counter/쓰기 제외 매핑 예외는 유지한다.

§6.2의 progress 사건에 있던 일률적인 previous_weight/next_weight NOT NULL·서로 다름 CHECK는 v0.2에서 effect별로 재정의한다. 현재값 변경은 두 값 필수·다름, 역사적 문맥은 두 값 null·intent 전후값 필수다. 같은값 재선택은 기존 승인 사건을 재사용하고 stamp만 갱신한다. 사건 발생시각과 서버 적용시각은 둘 다 보존하되 공식 순위/의도 판단에 서버 시각을 쓰지 않는다.

이 정도의 추가 상태 없이 과거60과 최신65의 도착순 독립성, 다른 속성 보존, 삭제 루틴의 원계획 진위 검증을 동시에 보장할 수는 없다. 반대로 모든 변경을 이력화하거나 별도 분산 인프라를 도입할 필요도 없다. JSONB path별 인덱스/범용 이벤트 검색/시계 동기화 기능은 지금 만들지 않는다.

## 7. 트랜잭션·소유권·generation·epoch 경계

### 7.1 각 값의 의미

기기 시각은 정확하다는 이번 가정을 적용한다. 서버 수신/DB 시각과의 허용 편차·오차 추정·날짜 자동 보정 계약은 만들지 않는다. 파싱/UTC 정규화, 동일 활동의 음수 경과 시간 방지, 시작 DATE/zone 일치는 형식·논리 검증이다. intent occurredAt을 serverRecordedAt으로 치환하지 않는다.

| 값 | 권위/수명 | 검증 |
|---|---|---|
| userId | 인증 principal의 서버 계정, 계정 전환 시 다른 scope | body owner 불신, 루트/자식/참조부터 principal Scope로 조회, foreign ID404 |
| dataGeneration | users의 운동 데이터 세대, 초기0/향후 reset 증가 | 모든 쓰기는 생성 당시 값과 현재 서버값 비교. old ID/queue/cursor 자동 이관 금지 |
| version/expectedVersion | 루틴/세션 등 집계별 서버 변경 번호 | 자식/intent stamp/역사 사건 변경도 부모 +1. 순수 no-op/중복만 증가 없음. 최신 의도 시각을 대신하지 않음 |
| occurredAt / originOperationId | 기기의 실제 사용자 의도 시각·최초 의도 ID, 서버 수신 시각과 별개 | 정확성 가정, 보정/clock-skew 추정 없음. 자동 해결은 원래 stamp를 보존하고 사용자의 새 선택만 새 stamp |
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
| 재전송 A vs 신규 B | 성공 A는 receipt 먼저 replay. 새 B는 의존 ACK의 V 필요. stale 원본은 결과 확인 후 새 해결 operation, 의미별 stamp로 최신성 판단. HTTP 도착 순서가 승자 기준 아님 |
| transfer vs old epoch 명령 | 먼저 커밋한 경계 기준. old 명령 선행 커밋이면 transfer stale version409, transfer 선행이면 old 신규 명령409 OWNERSHIP_LOST. 이미 성공한 old명령은 receipt만 확인 |
| 향후 reset vs 쓰기 | 같은 user 락에서 직렬화. 쓰기 선행 성공도 reset 후 replay/GET은 generation mismatch. reset 선행이면 구세대 쓰기 부작용 없음 |
| commit 실패/DB 단절 | 원자 rollback 또는 commit 미확인. 실패 tx 재사용 금지. 500/503를 단순 미적용 확정으로 표시하지 않음 |

### 7.4 device binding과 이전 (D-08, 승인 전 구현 보류)

권장 후보는 기존 로그인 쿠키/CSRF를 유지하고, 별도 무작위 proof를 서버 등록 device에 바인딩하는 전용 HttpOnly·Secure·SameSite 쿠키다. DB에는 hash/검증자료만 저장. 공개 deviceId/bindingRef만 복제하여 writer로 가장할 수 없어야 한다. 대안 WebCrypto 기기 키는 쿠키 추가를 줄이지만 키 보존/서명/복구·Safari 검증이 커진다. 로그인 session ID에 영구 바인딩하면 재로그인/회전으로 기기 ID가 바뀌므로 비권장이다.

wire 초안: CSRF 보호 `POST /api/v1/workout-devices`는 client device UUID+등록 operationId로 등록/현재 바인딩을 확인하고 공개 ref만 반환한다. proof 발급/응답유실 때 중복등록·알 수 없는 기기를 자동 기존 writer로 승격하지 않는다. 같은 proof를 제시한 재확인은 idempotent, proof 유실 재등록은 새 기기로 취급하고 명시 transfer 필요인 안이다. **정확한 쿠키명/경로/수명/재발급·로그아웃/다중계정 보존·탈퇴 파기·등록 응답유실**은 보안 계약으로 승인받기 전 확정/구현하지 않는다. deviceBindingRef는 credential이 아니다.

`TRANSFER_CONTROL {sessionId,fromDeviceId,toDeviceId,lastAcknowledgedOperationId}`: 현재 writer proof+V/epoch, 대상 등록/같은 owner 확인, 기존 탭 생성·송신 fence 및 선행 queue ACK 확인 후 실행. `FORCE_TRANSFER_CONTROL {sessionId,toDeviceId,acknowledgePossibleUnsyncedData:true}`는 목표 기기 proof+현재 V/epoch+명시 takeover. 결과=`{sessionId,writerDeviceId,ownershipEpoch,changed:true}`. 둘 다 Session version/epoch+1+receipt/feed 한 tx. TRANSFER_NOT_READY/OWNERSHIP_LOST/VERSION_CONFLICT. 새 target proof 검증과 source 권한 검증의 차이를 handler에서 명시한다.

lastAcknowledgedOperationId 하나로 서버가 미전송 명령이 전혀 없음을 증명하지는 못한다. source는 IDB tx로 세션 freeze→의존 queue drain→barrier ACK→이전 명령 봉인 순서다. force 때 구 기기 미전송 원본을 지우지 않는다. 새 proof 발급이 일반 운동/권한 명령과 자동 원자적이라고 주장하지 않는다.

### 7.5 오프라인 START와 변경·삭제 루틴 — 보관 원본 대조 (N-04 개정)

**P-NEW-05:** 이미 시작한 운동은 이후 루틴 변경/삭제에 영향받지 않는다. 오프라인 기기가 삭제 사실을 몰랐더라도 검증 가능한 당시 계획의 수행을 저장한다. 삭제 루틴은 현재 목록/향후 일정에서 계속 제외하고, START/기록 업로드가 루틴/배치/정의를 복원하지 않는다. 삭제 루틴에 대한 신규 승인 무게 변경 금지는 유지한다.

| 대안 | 이번 판단 |
|---|---|
| 현재 routineVersion/활성 여부만 비교 | 수정/삭제된 루틴의 정상 오프라인 기록을 거절하므로 이번 제품 결정과 불일치. 채택 안 함 |
| v0.1 서명 start-template | 변조 검증 가능하나 별도 key·회전·만료 운영이 필요. MVP 기본안에서 제외 |
| 모든 루틴/정의/승인 version의 일반 이력 저장 | 필요한 근거보다 범위가 크고 조회/보존·참조 복잡도 증가. 기본안에서 제외 |
| **운동 시작용 불변 계획 원본만 저장 (권장)** | 서버가 제공한 정확한 template 1행을 ID로 대조. 서명·일반 변경 이력 서비스 없이 owner/generation/내용 검증. 오프라인에 쓰일 옛 template 보존 비용은 필요 |

최소 후보 테이블 `workout_start_plan_sources`는 `id UUID PK, user_id,data_generation,source_routine_id,source_routine_version,template_schema_version,template JSONB,template_hash,created_at`이다. 현재 세대·루틴 복합 FK, UNIQUE(owner,generation,sourceRoutineId,templateHash), owner/generation/id 조회 인덱스를 둔다. source 행과 관련 루틴/배치/정의는 일반 삭제 시 물리 cascade하지 않는다. template_hash는 canonical 내용 중복 방지/일치 확인용이지 인증 비밀이 아니다. 인가는 principal Scope와 FK가 맡는다.

내용은 당시 이름/정의·배치 ID/순서/N/min/max/승인 무게·승인 사건/유산소 계획이다. 실제 수행·타이머·입력 초안은 포함하지 않는다. 모든 server version마다 만들지 않고 **시작 template 내용이 바뀔 때만** 생성한다. 루틴 저장/승인·참조 정의 이름 변경 등 template 내용에 영향을 주는 typed 명령에서 같은 user 락/tx 안 현재 내용을 확정해 저장한다. memo/checkpoint/확인 설정처럼 시작 계획과 무관한 변경은 새 원본을 만들지 않는다. 정의 이름 변경의 참조 루틴 fan-out은 현재 사용자의 활성 참조 범위만 처리하고 template 변경을 해당 routine 무효화와 함께 알린다. 일반 DB 이력/이벤트 재생 시스템은 만들지 않는다.

신규 루틴 상세/bootstrap은 서버에 이미 존재하는 `{startPlanSourceId,templateHash,template}`을 반환한다. GET이 DB 원본을 새로 INSERT하지 않는다. 루틴 저장 등 준비 쓰기가 해당 source를 만들었음을 검증한다. 기존 R 두 GET은 그대로다. 캐시된 옛 source가 뒤늦게 사용될 수 있어 현 generation에서 임의 단기 TTL/마지막1개 유지로 지우지 않는다. reset/탈퇴 파기·향후 보존 최적화는 별도 운영 계약이며 새 원본에 old UUID를 재연결하지 않는다.

START 요청 권장은 `{sessionId,startPlanSourceId,initialPlanCandidate,startedAt,workoutDate,startedTimeZone,deviceId}`이다. 서버는 principal/current generation으로 source를 조회하고 hash뿐 아니라 **typed template 전체 일치**와 새 sessionExercise/activity UUID의 1:1 매핑을 확인한다. 변경 가능한 루틴의 현재 version과 같을 필요는 없다. client는 서버 source의 N/목표/승인/배치/이름을 몰래 바꾸지 못한다. sourceRoutineId/sourceVersion은 서버 source에서 가져온다. source가 soft-deleted라도 이 검증을 통과하면 세션/관계형 수행을 저장하고 새 루틴이나 일정은 만들지 않는다. 원본에 있던 현재 삭제 정의도 과거 출처로만 유지한다. 승인 문맥은 당시 source를 보존하고 현재 승인값으로 수행 kg를 치환하지 않는다.

source UUID만 안다고 다른 사용자 계획을 사용할 수 없다. 다른 owner/generation source404, 현재 요청 generation 불일치409, 내용 변조422 START_PLAN_MISMATCH, 근거 없음404 START_PLAN_SOURCE_NOT_FOUND다. 기존 기록을 보존하되 이 실패를 정상 저장 ACK로 표시하지 않는다. 새로운 임의 루틴 생성/무근거 계획을 공식 원계획으로 인정하는 우회 경로가 아니다.

미송신 CREATE/UPDATE_ROUTINE으로 만든 계획을 오프라인에서 사용한 경우는 명시 선행 명령 의존성을 보존한다. 선행 ACK가 돌려준 source가 **생성 당시 고정한 계획과 정확히 같을 때만** source ID를 봉인에 바인딩할 수 있다. 자동 충돌 해결로 계획 내용이 달라졌다면 실제 로컬 계획/수행을 최신 source로 치환하지 않고 격리·복구 검토한다. 이 비정상 경계의 추가 복구 UI를 먼저 일반화하지 않는다. 이미 서버에서 확인해 캐시한 계획의 수정/삭제 후 업로드는 정상 경로로 지원한다. 근거 없는 로컬 값도 지우지 않지만 검증되지 않은 상태를 공식 저장 완료라 부르지 않는다.

source 새 ID가 생겼지만 routine V가 그대로인 참조 이름 변경도 무효화를 누락하지 않는다. routine dirty 알림은 같은 V라도 refetch하며, 각 응답은 요청/무효화 epoch를 대조해 이전 요청을 폐기한다. 불변 source ID 자체에 시간 순서를 추측해 넣지 않는다.

### 7.6 최신 사용자 의도의 최소 자동 해결 계약 (N-08)

#### 7.6.1 version만으로 부족한 이유와 최소 stamp

루트 version은 '읽은 이후 무엇인가 바뀜'만 알리고 바뀐 속성·의도의 최신성은 알려주지 않는다. 사건의 마지막 서버 삽입 순서나 root.updatedAt도 답이 아니다. 권장 추가 상태는 **현재 의미 group별 마지막 적용 intent stamp 하나**다. 각 stamp=`{occurredAt,originOperationId}`이고 operationId는 최초 의도 ID다. 자동 해결 operation의 생성 시각/ID로 이 stamp를 새롭게 만들지 않는다.

단순 루트에는 허용 key만 있는 `intent_stamps JSONB DEFAULT {}`를 둔다. routine은 `name`, `placement:{id}:target`, `placement:{id}:approvedWeight`, `placement:{id}:lifecycle`, `weightOrder`, `cardioOrder`; 설정은 휴식 필드별; schedule은 요일별; set 정정은 `performedSet:{id}:actual`, lifecycle은 별도 group이다. target의 N/min/max, set.actual의 kg/reps처럼 의미가 연결된 값은 한 group으로 비교한다. UUID 기반 key를 사용하고 배열 index/종목 이름을 key로 쓰지 않는다. 삭제 key의 stamp는 tombstone과 함께 보존한다. API가 임의 JSON 경로를 쓰게 하지 않는다.

이는 현재 값의 작은 메타데이터이며 모든 변경을 저장하는 이벤트 저장소/벡터시계/서버 시각 보정이 아니다. JSONB 저장은 가능한 표현이며 매칭·권한·순서는 애플리케이션에서 검증한다. 사용자 행 락이 같은 사용자의 검증/비교/적용을 직렬화하며 DB UNIQUE·루트 V를 유지한다. [PostgreSQL JSONB](https://www.postgresql.org/docs/18/datatype-json.html), [행 잠금](https://www.postgresql.org/docs/18/explicit-locking.html)

생성 명령은 초기 값의 stamp를 생성 의도 시각으로 설정한다. 기존 V8 값의 stamp는 없으므로 API에서 `baseStamp:null`=UNSTAMPED 기준으로 표시하며 row.updatedAt/문서 작성시각을 사용자 intent로 조작하지 않는다. 첫 실제 편집이 stamp를 만든다. null 상태와 실제 stamp를 구별해 비교한다. user가 실제 선택/변경하지 않은 필드에는 stamp를 찍지 않는다. 기본값을 화면에 표시한 것만으로 intent를 만들지 않는다.

각 재해결 가능한 원본 요청은 `fieldBases:[{resourceType,resourceId,fieldKey,baseStamp:null 또는 {occurredAt,originOperationId}}]`를 봉인한다. 허용 typed payload가 실제 건드리는 group과 정확히 일치해야 한다. 새 상세/bootstrap은 value와 이 stamp를 같이 반환하고 기존 R에는 가짜 version/stamp를 붙이지 않는다. fieldBases는 충돌 설명용이며 인가를 부여하지 않는다. 현재 V가 맞는 일반 typed 명령도 적용 직전 stamp 비교를 생략하지 않는다. 과거 intent가 의존 ACK로 최신 V를 얻어도 최신 의미값을 덮을 수 없다.

#### 7.6.2 결과 확인 → 새 operation 해결 → 원자 ACK

1. 원본은 기존대로 현재 V 검증에 실패하면409 VERSION_CONFLICT, 도메인/성공 receipt 없음이다. 클라이언트는 그 HTTP 거절 또는 원본 receipt를 확인한다. timeout/GET404만으로 거절을 확정하지 않고 **같은 봉인 POST**로 결과를 먼저 확인한다.
2. 재해결 필요 시 새 operationId/최신 expectedVersions의 RESOLVE_WRITE_CONFLICT를 만든다. payload=`{originOperationId,originFingerprint,mode:AUTO|USER_CHOICE,choices?}`. 서버가 **동일 Scope의 REJECTED receipt에 보관한 원본 typed intent**를 로드하고 fingerprint/type/creation epoch/fieldBases를 검증한다. client가 원본 전체를 반복해서 보내거나 arbitrary patch를 구성할 필요가 없다. 중첩 RESOLVE 원본은 금지하며 후속 재해결도 최초 원본 ID를 참조한다.
3. AUTO의 envelope occurredAt은 원본 occurredAt과 동일, effective intent key도 `(원본 occurredAt,원본 operationId)`다. 새 expectedVersion만 현재값이며 구 epoch를 현재 epoch로 바꾸지 않는다. 동일 owner/generation/올바른 제어권을 갖는 원본만 일반 자동 해결 대상이다. 구 epoch 기록은 별도 선택 복구 경로로만 처리한다.
4. user 락 아래 원본 receipt를 다시 확인한다. AUTO에서 이미 SUCCEEDED면 원본 적용 확인만 반환한다. REJECTED이며 저장된 code가 위 두409이고 fingerprint가 같아야 해결 가능하다. 미존재/진행 결과 미확인은 RESOLUTION_NOT_READY로 원본 확인부터 한다. **기대 V가 아직 같더라도 terminal 거절이므로 새 해결이 가능**하다. 같은 원본 POST는 영구 같은409이며 확인 설정이 바뀌어도 재적용되지 않는다. USER_CHOICE의 실제 새 선택은 새 intent로 검증한다. 저장하지 않은 모든 실패에 이 보장을 확대하지 않는다.
5. 서버가 원본 typed delta와 현재 값/stamp·fieldBases를 비교한다. 다른 group 변경만 있었으면 SAFE_REBASE다. 같은 group의 값이 같으면 충돌 확인 없이 최신 stamp만 필요시 갱신한다. 같은 group을 둘 다 다르게 바꿨다면 확인 설정 OFF는 최신 occurredAt 승자를 고르고, ON은 최신값 추천과 함께409 CONFLICT_CONFIRMATION_REQUIRED로 보류한다.
6. latest 비교는 기기 occurredAt만 우선하고 upload/수신 시각은 사용하지 않는다. 정확히 같은 millisecond의 서로 다른 값은 originOperationId canonical UUID 사전순을 마지막 결정 기준으로 삼는 계약을 **1A에서 채택**했다. 같은 timestamp의 실제 선후를 추측하지 않는다. 현재 허용 group은 1A 보고서에 고정했고 미래 도메인 group은 해당 단계에서 확장한다.
7. AUTO가 더 오래된 intent면 그 field는 KEEP_CURRENT이고 현재 값을 덮지 않는다. 다른 안전한 field는 APPLY_INCOMING, 같은값·새stamp는 ADVANCE_INTENT_ONLY다. 안전하지 않은 group/참조/상태가 하나라도 있으면 해당 복합 명령 전체를 MERGE_REQUIRES_REVIEW로 보류한다. 필드별 결정을 숨겨 전체 배열을 쓰지 않는다.
8. 선택한 값·stamp·승인/정정 사건·각 루트 V·feed·새 operation receipt를 같은 workout tx로 커밋한다. KEEP_CURRENT인 옛 명시 승인도 수행에 필요한 근거라면 §4.5의 HISTORICAL_CONTEXT_ONLY 사건을 한 번 보존한다. 새 해결 ACK도 유실되면 **동일 해결 operation**을 재전송한다. 재해결의 재해결을 중첩하지 않고 다음 경합도 원래 의도를 참조한 또 다른 새 operation으로 처리한다. 최신값이 계속 바뀌면 제한 재시도 후 pending 상태 유지, 무한 루프 없음.

USER_CHOICE는 그 시점 사용자의 새 의도다. choices는 원래 touched group에 대한 KEEP_CURRENT/APPLY_INCOMING/명시 새 값으로 제한하고, envelope occurredAt은 **선택한 현재 기기 시각**, effective originOperationId는 이 새 선택 operationId다. 예전60을 다시 선택해도 사용자 '현재의60 선택'으로 표현하며 월요일 stamp를 최신으로 위조하지 않는다. 확인 중 서버가 바뀌면 V로 다시 충돌 검증한다. 같은 epoch의 자동 rebase와 제어권을 잃은 기록의 복구를 합치지 않는다.

원본이 이미 성공한 뒤 다른 값이 더 최근에 바뀌었다면 AUTO가 다시 덮지 않는다. 자동 rebase는 확정 거절된 미적용 원본을 안전하게 해결하는 계약이다. 원본 성공 확인과 현재 상태 조회는 별개다. USER_CHOICE는 현재 사용자의 새 선택이며 과거 원본 수정이 아니다. server에 저장한 원본 intent에는 검증된 허용 DTO만 보관하고 쿠키/CSRF/proof 비밀/임의 요청 원문을 복사하지 않는다.

#### 7.6.3 도착 순서 독립성·같은 값 재선택

| 시나리오 (같은 승인값 group) | 처리 / 최종값 |
|---|---|
| 월60(v0)→화65(v0), 월이 먼저 도착 | 월 적용 v1. 화 원본409→새 해결 v1/원래 화 stamp로65 적용→65 |
| 같은 두 의도, 화가 먼저 도착 | 화 적용 v1. 월 원본409→새 해결에서 월 stamp가 오래됨→KEEP_CURRENT·65 |
| 수65를 명시 재선택한 뒤 월60이 늦게 도착 | 수65가 값 변화 없더라도 stamp를 수로 전진. 월60은65를 덮지 못함 |
| 월 이름 수정·화 목표 세트 수정 | 서로 다른 group이라 새 V 해결에서 둘 다 보존. 옛 전체 루틴 배열을 저장하지 않음 |
| 화65와 같은 timestamp의 다른 값 | 제안한 UUID tie-break로 도착 순서 독립. 사용자 확인 ON이면 실제 값 충돌을 먼저 확인 |

v0.1의 '같은 값 승인=no-op·version/feed 없음'을 세분화한다. **같은 값+새로운 더 최신 명시 intent는 metadata 변경**이므로 stamp·부모 V·feed를 갱신하되 무게 변화 사건을 중복 생성하지 않는다. 오래된 intent/완전 동일 stamp·값은 새 역사적 사실도 없을 때만 순수 no-op다. 같은 operation replay는 어느 경우든 stamp/V/사건을 추가하지 않는다. 화65 뒤 월60이 도착하면 현재65→60 변경을 가짜로 만들지 않지만 실제 월60 승인 문맥은 HISTORICAL_CONTEXT_ONLY로 보존할 수 있다. 현재 승인값65와 당시 수행60을 독립적으로 유지한다.

#### 7.6.4 자동 해결 허용 범위와 안전 보류

안전 후보는 이름·메모 text·휴식 단일값·같은 활성 배치 목표 group·명시 승인값·요일값·기존 활성 set의 실제값 정정이다. 정정은 무게/반복 group 하나를 갱신하고 실제 적용 전후 사건을 남긴다. 새 세트 추가는 기존 세트를 덮는 '값 충돌'이 아니라 UUID·ordinal·phase가 맞을 때만 합쳐 보존할 수 있는 별도 typed 처리다.

삭제/복원·membership 변화와 order 교환·이미 종료된 세션의 진행 명령·동일 ordinal의 서로 다른 수행·루틴 삭제 뒤 승인·수행의 다른 소유자/세대/epoch·최초/봉인 계획 변경은 단순 occurredAt 비교로 자동 적용하지 않는다. tombstone은 더 최신 정정 intent만으로 해제하지 않고 명시 복원/선택 복구가 필요하다. 삭제로 실제 수행을 잃을 수 있거나 typed 명령의 의미를 보존할 수 없으면 설정 OFF라도 **안전 보류/복구 진입**이다. 이것은 OFF에서 모든 정상 값 충돌을 수동 선택으로 돌리는 정책이 아니다.

최소 구현은 우선 scalar/group 몇 개를 명시 allowlist로 지원하고 미지원 type은 보존·보류한다. 모든 도메인에 범용 merge 엔진을 붙이거나 탭 시각 보정/벡터시계/전체 이벤트 재생을 만들지 않는다. 운영 쓰기 UI를 열기 전 그 UI가 지원하는 안전 type의 resolver를 완성하고, 불확실 원본·안전 보류 원본을 삭제하지 않는다.

## 8. Frontend IndexedDB·Outbox 계약

### 8.1 저장소와 의도·봉인 최소 구조

N 권장은 demo 키와 별도 DB `overload.workout.v1`(이름은 제안)·object store별 composite key다. 모든 사용자 데이터 키에 `[serverUserId,dataGeneration,...]`를 포함하고 접근 API 자체가 scope를 필수 인자로 받는다. UI는 현재 인증 scope만 연결한다. 사용자별 DB 여러 개 대안도 가능하지만 업그레이드/다중탭 조정 비용이 늘어난다. 인증 비밀·쿠키·CSRF·device proof는 이 DB에 저장하지 않는다.

| object store | key / 최소 데이터 |
|---|---|
| scopeMeta | `[userId,generation]` / schemaVersion,verifiedOwner,lastPulledCursor,dirtyRefs,scopeStatus |
| serverResources | `[userId,generation,resourceType,id]` / lastAcknowledgedVersion,serverValue,tombstone. 수정 UI 초안과 분리 |
| localWorkouts / drafts | `[userId,generation,sessionId]` / Initial/Applied local projection,완료수행·cardio/checkpoint,localRevision,기기/epoch문맥. 공식 서버 원본의 대체가 아님 |
| commands | `[userId,generation,operationId]` / immutableIntent,occurredAt,fieldBases,domainIds,creationEpoch/controlDependency,dependsOn/resourceKeys,sealedRequest/fingerprint,originOperationId 및 resolutionMode(해결만),해결 상태. 원본과 해결은 서로 다른 행 |
| outbox | 같은 operation key / state,attemptCount,nextAttemptAt,lastRequestId,lastSafeErrorCode,leaseOwner/leaseToken,leaseUntil |
| acknowledgedOperations | 같은 operation key / 검증 ACK/resultVersions/fingerprint,의존명령이 사용할 결과. queue 정리 후에도 선행 근거 유지 |
| recoverySources | `[userId,generation,sessionId,sourceId]` / 구epoch/충돌/거절 원본+선택결과+해결operation. 사용자 선택 ACK까지 유지 |
| writerLeases | `[userId,generation,sessionId 또는 sender]` / tabId,fencingToken,expiry. 로컬 동시주체 조정 |

1. 입력 완료 시 UUID·의도·당시 control 문맥을 준비하고 IDB readwrite tx에서 scope/fencing 재검증→도메인 projection/명령/Outbox 함께 기록한다. 각 request success가 아니라 **tx complete** 후 로컬 완료 표시. abort/quota/schema upgrade 실패는 모두 rollback, 메모리 input draft 유지, 전송 없음. IDB tx 안에 fetch/해시 Promise 등 외부 await를 끼우지 않는다. [W3C IndexedDB 2.0](https://www.w3.org/TR/IndexedDB-2/)
2. 봉인할 때 선행 ACK와 scope/fencing을 읽어 request canonical bytes를 준비한다. 준비와 저장이 분리되면 localRevision/의존 ACK가 그대로인지 CAS 검사하는 짧은 두 번째 IDB tx로 봉인+READY를 원자 저장한다. `complete` 전에 fetch 금지. crash가 나도 원래 봉인본을 재사용한다.
3. 같은 집계 명령은 선행 ACK로 순차 송신. Routine+Session을 함께 만지는 명령은 양쪽 resourceKeys에 barrier 의존을 둔다. 서로 의존하는 cycle를 생성 시 거절한다. 단순 같은 user의 독립 집계는 병렬 가능하지만 MVP sender 직렬이 단순하다.
4. 불확실 선행명령 뒤 의존명령은 blocked. 충돌 해결은 새 명령·새ID와 명시 연결. 원본 command를 수정하거나 생성 epoch를 갱신하지 않는다. 아직 봉인 전 명령도 원래 의미를 바꾸는 coalescing을 하지 않는 안을 권장한다.

### 8.2 상태·ACK·늦은 응답

권장 상태는 WAITING_DEPENDENCY→READY_SEALED→SENDING→ACKNOWLEDGED다. UNKNOWN_RETRY는 결과 미확인, REJECTED는 원본 확정 거절, RESOLVING은 원본을 참조한 새 해결 명령 대기, NEEDS_CONFIRMATION은 설정 ON의 실제 값 충돌, SAFETY_HOLD는 비안전 병합, QUARANTINED는 owner/generation/epoch 격리다. REJECTED 원본을 수정/삭제해 새 요청으로 위장하지 않고 해결 ACK에 연결해 보존한다. 로컬 운동 진행/완료와 서버 송신 상태는 별개다.

- 네트워크 단절/timeout/abort/HTML/2xx 잘못된 DTO는 UNKNOWN_RETRY. 탭 종료 시 SENDING은 재기동에서 UNKNOWN으로 회수해 같은 봉인본을 확인한다. 송신 lease 만료는 서버 미적용 증거가 아니다.
- ACK 검증 후 **한 IDB tx**로 acknowledgedOperations 저장·serverVersion 갱신·해당 Outbox 제거/ACK 표시·의존 해제를 처리한다. 이 tx 실패면 queue를 지우지 않아 재전송 가능하다. 서버 receipt가 중복 적용을 방지한다.
- localRevision이 더 새로우면 ACK는 해당 operation 확인만 한다. 최신 input/오늘만 중량/현재 타이머/현 제어권을 옛 result로 덮어쓰지 않는다. ACK 후 GET 실패는 '서버 저장됨, 최신 조회 실패'로 별도 표시한다.
- 요청 capture=`{userId,generation,authEpoch,localRequestEpoch,sessionEpoch,leaseToken,operationId,fingerprint}`와 최신 문맥을 응답 적용 전에 비교한다. 계정전환/로그아웃/epoch 변경 뒤 늦은 성공/실패는 현재 UI/새 scope를 바꾸지 못한다. 원래 scope에 대한 성공확인이 필요하면 같은 계정 재확인 후 receipt GET으로 복구한다.
- 인증 만료 시 확인된 같은 계정의 로컬 기록은 계속 가능하나 송신 중지, 같은 계정 재인증. 명시 logout은 화면/메모리·송신을 차단하고 숨겨진 old queue를 다른 로그인으로 전송하지 않는다. 다른 계정 데이터로 fallback하지 않는다.
- generation mismatch는 old scope 전체 QUARANTINED, 자동 신규 generation 복사/UUID 연결 없음. ownership loss는 해당 세션 원본과 의존명령 격리, 원본 보존 후 현 제어권에서 선택 복구. 과거 이미 성공한 receipt 확인은 제어권 획득이 아니다.

해결 ACK는 원본이 그대로 적용됐다는 뜻이 아니다. Outbox는 원본→해결 연결과 fieldDecisions를 저장하고, 의존 명령은 실제 확정 V/승인 문맥/source가 생성 당시 의도와 맞을 때만 해제한다. KEEP_CURRENT인 과거 무게를 현재값으로 바꾸어 실제 세트에 덮어쓰지 않는다. 역사적 승인 context가 보존되면 그 ID를 바인딩하고, 계획 내용이 달라졌다면 원래 수행을 보존한 채 안전 복구로 넘긴다. 원본 거절 영수증은 성공 ACK로 바꾸지 않는다.

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
| 404 | RESOURCE_NOT_FOUND(미존재/foreign 동일), START_PLAN_SOURCE_NOT_FOUND, 결과 GET의 OPERATION_NOT_FOUND | soft-deleted routine의 검증된 source가 남아 있으면 START404가 아님. receipt404는 적용 미확인, 동일 봉인 POST로 확인 |
| 409 | OWNER_CONTEXT_MISMATCH/DATA_GENERATION_MISMATCH/OWNERSHIP_LOST/OPERATION_ID_REUSED는 격리. VERSION_CONFLICT는 원본 미적용으로 N-08 새 operation 해결. CONFLICT_CONFIRMATION_REQUIRED는 설정 ON의 동일 의미 값 충돌, MERGE_REQUIRES_REVIEW는 안전 보류, RESOLUTION_NOT_READY는 원본 미확정. ordinal/tombstone/승인 활성 조건 유지 | 안전한 최신 의도 해결과 owner/epoch 재태깅 금지 구별. 불확실 원본을 먼저 확인 |
| 410 | sync pull의 SYNC_CURSOR_EXPIRED; 향후 N-02 결과본문 만료를 도입할 때만 OPERATION_RESULT_EXPIRED | bootstrap 또는 수동 확인. key 만료를 신규 실행 근거로 쓰지 않음. 현재 영수증 자동만료 제안 없음 |
| 422 | reps/kg/목표/DATE·시각 논리 VALIDATION_FAILED/CLOCK_CONTEXT_INVALID, START_PLAN_MISMATCH; 완료 뒤 PLAN_IMMUTABLE/NEW_EXERCISE_AFTER_FINISH, INVALID_STATE_TRANSITION 등 | 목표 밖 양수 reps/추가 세트 자체는422 아님. 근거 없는 계획/부정한 참조는 거절하되 실제 로컬 원본 보존 |
| 429 | 향후 정의된 workout 요청제한 시 WORKOUT_COMMAND_RATE_LIMITED+Retry-After, 현시점 미구현 | 봉인본 보존 후 제한 백오프. 기존 OTP_RATE_LIMITED 정책 그대로. 제한량 신규 확정 없음 |
| 500 | settings 누락 WORKOUT_SETTINGS_MISSING, 나머지 예상밖 WORKOUT_COMMAND_FAILED/기존 읽기 WORKOUT_DATA_READ_FAILED | commit 미확인 가능. 자동 새ID 금지. receipt/같은 봉인 재시도 전 보존, 반복500은 멈추고 안전 안내 |
| 503 | 확인된 DB연결/일시종료/lock-timeout 등 WORKOUT_DATA_UNAVAILABLE, 별도 운영 장애 | 같은 봉인 backoff. SQL grammar/무결성 예외 전부503 처리 금지. commit 여부 불확실 |

자식/종목/상태 충돌 검사는 receipt 성공 확인보다 뒤다. 그래서 삭제 후 duplicate COMPLETE는 원래 성공을 반환하고 새 COMPLETE는 삭제 오류가 된다. 알려진 UNIQUE/버전 조건은 정확한 코드409로 매핑하되 모르는 제약실패는500이며 제약명 공개 금지. DB deadlock/serialization/락 시간초과는 전체 rollback 후 일시오류로 분류할 테스트가 필요하고 실패 tx 내부 retry는 하지 않는다. 405/415/프록시 오류는 실제 HTTP 의미 유지, 모두400/JSON으로 만들지 않는다.

backoff N안은 jitter·Retry-After 존중·foreground 복귀 시 재개·상한 대기와 수동재개를 둔다. 정확한 횟수/초 단위는 구현 UX 검토에서 정하며 공통 fetch에 임의 POST retry를 추가하지 않는다. 서버 영수증 없는 '확정실패 저장'을 약속하지 않는다. network/timeout/5xx/잘못된2xx는 UNKNOWN, 정상 검증된 ACK만 성공 증거다. ACK를 받은 뒤 Outbox 정리 실패도 입력삭제가 아닌 동일명령 재확인 대상이다.

## 10. 세부 구현 로드맵 및 검증 계획

이번에는 테스트/서버/DB를 실행하지 않았다. 아래 명령은 **후속 구현 단계에서** 실제 설정과 그때 변경분에 맞춰 실행할 게이트다. 주요 의존성/보안/운영/파괴적 작업은 별도 명시 승인 선행, Commit/Push는 그때 사용자 지시에 따르며 이번에는 하지 않는다.

### 10.1 작은 단계별 범위·커밋 시점

| 단계 | 선행조건 / 수정 파일 범위 제안 | 구현 결과물 | 테스트 게이트 / 커밋 시점 |
|---|---|---|---|
| 009-0 | 제품 정책·최소 기술 계약 채택 | 이 설계+1A 보완 | v0.1 보존, v0.2 개정·미커밋 |
| 009-1A 계약 타입·DB 기반 | §12.1의 요청된 최소 계약 채택. 전체 후속 기술 일괄 승인은 아님 | V9·version/intent_stamps·receipt/sync·typed DTO 기반 구현 | 기존 가입/R/JPA·metadata-only·롤백 포함 574개 PASS. 미커밋 |
| 009-1B 멱등 실행·결과 GET | 1A, 성공 receipt 보존/실패 binding 한계 확정 | dispatcher·ACK·결과조회·최초 name scalar resolver | 원본/해결 ACK 유실·동일 key 경쟁·stamp 원자성. 2A CREATE/RENAME slice와 함께 검증 후 리뷰 |
| 009-2A 종목·휴식·memo·확인 설정 | 1A/B, 필요한 memo/preferences 스키마. 첫 운영 편집 노출 전에 해당 scalar resolver 완성 | typed delta·value/stamp 상세 GET, 계정 확인 설정 OFF/ON | 월60/화65 양 도착순, 서로 다른 휴식 필드 병합, 같은값 최신 stamp, ON/OFF. 정의 삭제는 루틴 도입 후 |
| 009-2B 루틴·배치·일정 | 2A, 순서 제약·승인 사건/schema·시작 source 생성 계약 | CREATE/UPDATE typed delta, 의미 group stamp, 실제/역사 승인 사건, immutable start sources | 오래된 전체 배열 미사용, 타배치 최신 편집 보존, 삭제/복원·order 안전 보류, 승인 history/current 값 분리 |
| 009-2C 기기권한·시작 | 2B, D-03/08·N-04 기술 상세 확정. 시계 정확성/삭제 source 수용은 이미 제품 확정 | 기기 proof·세션·Initial/Applied·source 참조 | 삭제/수정 루틴으로 오프라인 START 저장·복원0, foreign/변조 source 차단, clock-skew 보정 없음 |
| 009-2D 수행·유산소 | 2C | performed/cardio·상태전이·정정/삭제 사건, Session version | 신규 reps0거절/0kg/ordinal/tombstone·시간원본/정정·동시성·전체rollback. 기능별 커밋 |
| 009-4A 공통 공식 평가 | 2D, §4.6 첫 N개 중량·반복 범위 확정. 합계/동률·목표 밖·추가세트 제품 정책은 재승인 안 함 | 첫 N개 결정 선택·전체 통계 분리·정정 재평가·ruleVersion | 사용자 6개 예시, 삭제 후 선택 ID, 추가 세트만 다른 중량의 자격 유지, 전체4세트 volume, 한 세션 비교·업로드 순위 무관. FINISH 전 |
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
| W-04 모든 rollback 지점 | SQL/flush/DB 확정 실패는 값/stamp/사건/receipt/revision 전체 미반영. 별도로 논리 VERSION_CONFLICT/확인 거절은 도메인 변경0+REJECTED receipt1, 원본 재송신은 같은409. commit 응답 유실은 결과 조회로 확인 |
| W-05 응답유실 | 실제 서버 커밋 후 transport가 응답 폐기→GET receipt200·원result, replay200/version추가없음. 진행중 GET404와 rollback뒤404를 미확인으로 처리. 삭제/epoch변경 뒤 receipt 조회 재생성없음 |
| W-06 owner/세대 | A/B UUID/배치/사건/cursor/operation 강제교차,404/안전409·정보비노출. A에서 봉인한 CREATE를 B쿠키로 송신하면 OWNER_CONTEXT_MISMATCH·양계정 삽입0. large generation 왕복. 합성 reset tx vs write 양순서로 구generation 영수증/삽입차단, old queue 자동이관없음 |
| W-07 version/제약 | 자식/metadata-only/history-only 변경도 루트+1, 같은 stamp replay 증가0, 값·stamp·사건·receipt/feed 전체 롤백. 순서/복원 UNIQUE·복합FK 유지 |
| W-08 종료경합 | COMPLETE/FINISH·APPROVE/FINISH·루틴삭제/승인 양순서 barrier. 추가 수행/봉인/승인 사건의 부분커밋 없음, 뒤명령 정확한 충돌 |
| W-09 feed | T1 낮은revision 후보 지연/T2 시도→T2가 user락 뒤 커밋, 누락없음. rollback revision/feed취소, batch비분할·다중집계 한batch, ACKcursor로 미수신변경 skip없음 |
| W-10 제어권 | deviceId만 위조한 요청 차단, source/target proof·정상drain/force 최신V, 옛epoch 새명령 거절/과거성공 receipt 허용. tombstone 복구 명시선택·ordinal충돌·새종목차단 |
| W-11 ORM/직접SQL | backend PID/txid 일치, flush전후 JDBC 관측, native후 refresh/clear, root 이중version증가없음, rollback원인 덮임 오류분류. deferred 실패를200으로 보내지 않음 |
| W-12 보안/HTTP 회귀 | 기존 인증·OTP·Google 모의/JWKS·SpringSession JDBC/90일/재인증/CSRF/쿠키회전/no-store/RequestID/204/401/403/302 전체. SessionSQL 동안 workout EM binding 없음, 외부 TX rollback과 Session독립성 유지 |
| W-13 조회·성능 | 기존 WorkoutReadIntegrationTests와 1C 실브라우저 흐름, 원래 R 필드/오류/query거절/반복구간. 목록/상세 N+1·pagination/동시bootstrap snapshot·늦은과거기록 무효화 |

V9는 PROCESSING을 허용하지 않는다. 1B에서 handler 완료 불변식과 최종 SUCCEEDED/REJECTED 필드 CHECK를 검증한다. 별도 constraint trigger는 최초 필수 인프라에서 제외한다. SQL 오류를 catch해 같은 실패 tx에 거절 receipt를 쓰지 않는다. deadlock/lock timeout/연결 유실·HTTP409를 각각 분리 검증한다.

### 10.3 도메인/React·브라우저 게이트

필수 정책 fixture는 §4.6의 여섯 입력 예시와 ordinal/ID 삭제·정정 재선택, 추가4세트 전체 보존/통계, 3→4→3/3→2·다른 원계획 종목 제거 우회 차단, 첫 수행 전 승인/오늘만·혼합 구별, 한 과거 세션의 선택 N개 합계·실제 운동시각 동률, 사후 현재 평가만 변경, 종료 뒤 새 종목 차단·봉인 불변, 0kg/신규0reps 거절, 휴식10/11/12/13 실제 설정, 유산소 원본/정정/null, 지연 업로드의 시작 DATE 보존이다. 이번에 확정된 정책 예시를 다시 승인 대기로 두지 않는다.

| Frontend 게이트 | 실제 검증 |
|---|---|
| F-01 로컬 원자성 | 저장 request success 후 tx abort, quota/권한/versionchange, 작업중 탭닫기. 도메인 완료/Outbox 둘 다 rollback, 입력 보존·네트워크 송신0, 재진입 |
| F-02 봉인·의존 | 원본 intent/fieldBases/epoch 불변, 의존 ACK로만 V/사건/source ID 바인딩. 409 결과 확인→새 해결 op, AUTO 원래 occurredAt 유지·USER_CHOICE 새 시각. 원본/해결 봉인 crash·ACK 유실·계정전환 |
| F-03 네트워크 | 미도착/DBcommit후응답유실/timeout/잘못된2xx/ACK뒤IDB실패→동일명령 재확인. 실제 fetch proxy로 응답폐기, DB1회적용 관찰 |
| F-04 UI/재연결 | offline 로컬완료는 서버대기, reload로 pending복원, 재연결 ACK 후 서버저장표시. ACK뒤GET실패는 저장성공유지. 진행초안/타이머/최신입력을 옛ACK가 덮지않음 |
| F-04A 정상 온라인 성능 | 입력·세트 완료·타이머·화면 이동이 불필요한 서버 ACK를 기다리지 않음. 로컬 원자 저장 실패 시 성공 표시0·초안 보존. 지연 ACK가 현재 상태/타이머 앵커를 덮지 않음 |
| F-04B iPhone PWA 수명주기 | 네트워크 불통과 별개로 백그라운드·잠금·화면 꺼짐·재실행을 각각 검증. 이미 저장한 기록·pending 명령과 시각 앵커 복원, 매초 실행 중단 후 경과 시간 재계산. 1A에서는 미실행 |
| F-05 계정/권한 | A→logout→B, 같은A 재로그인, 오래된성공/401/503, 구generation/epoch, 탭fence이전·suspension. old UI/DB영역/CSRF침범없음, 원본격리·선택복구 |
| F-06 pull/삭제 | 느린GET과새version·batchdirty, ACKcursor건너뛰기금지, cursor410/bootstrap, 삭제된ID 자동부활없음, 복수탭/기기 갱신 |
| F-07 실제UI/분리 | demo 키 getter 차단·import 경계, 기존312개 회귀 재실행(당시 개수는 달라질 수 있음), React 실제 DOM 입력/네트워크/IDB. SSR 단독 통과로 DOM/브라우저 보장하지 않음 |
| F-08 기기/PWA | 실제 iPhone Safari 잠금·앱복귀·탭동시·오프라인/재연결·저장공간. 설치형/ServiceWorker가 실제 도입된 때만 설치·업데이트 보호 별도 게이트 |

Backend 명령 후보는 기존 `gradlew.bat --offline --no-daemon test`, 해당 `integrationTest --tests ...`, 최종 `build`; Frontend는 `npm test`, `npm run lint`, `npm run build`다. 별도 typecheck 스크립트는 없고 build가 tsc를 포함한다. Node 내장 테스트·기존 SSR를 유지하며 IDB 실제 경합은 실브라우저로 보완한다. 테스트 framework 추가가 필요하면 대안/호환성 검토 후 승인한다. 격리 real-backend harness는 재사용 가능하지만 현재 수동 시나리오를 자동 E2E runner라 부르지 않는다.

각 단계에서 시작한 JVM/Node/컨테이너/PID/포트를 기록하고 성공·실패와 무관하게 종료 및 잔류 확인, 기존 사용자 프로세스는 유지한다. 이번 문서 작업에서는 새 검증 프로세스를 시작하지 않았다.

### 10.4 이번 정책 변경에 추가할 구체 검증 시나리오

아래는 **구현 후 실행할 계획**이며 이번에 테스트 코드/런타임을 실행한 결과가 아니다. 기존 W/F 게이트와 함께 Testcontainers의 독립 tx·실브라우저 IDB로 확인한다.

| 시나리오 | 반드시 확인할 결과 |
|---|---|
| 월60/화65 양쪽 도착 순서·여러 재전송 | 최신 현재값은 모두65, 승자의 stamp는 화 의도, 원본/해결 receipt는 각 ID당 한 번. 업로드 순서로 결과가 달라지지 않음 |
| 수65 같은값 재선택 뒤 월60 도착 | 수 stamp까지 전진, 순수 no-op로 유실하지 않음. 월60은65를 덮지 못함 |
| 이름 수정·다른 배치 목표 수정 | 두 변경 모두 보존. stale 전체 배열을 재전송하지 않고 touched group만 새 V에서 해결 |
| target min/max 동시 수정 | group 단위 비교, 두 기기 값 일부를 섞어 min>max를 만들지 않음 |
| 설정 OFF/ON·요청 중 설정 변경 | OFF는 안전 최신값 자동 적용. ON은 같은 group의 다른 값에만 추천 확인. 다른 field·동일값·replay는 확인0. 서버 최신 설정이 최종 기준 |
| 확인 설정 API·reset·계정 전환 | 신규 GET/명령으로 저장, 기존 R settings 응답 정확히 유지. 기본 OFF·GET INSERT0·계정별 분리. reset fixture는 계정 설정 유지 |
| 원본 응답 유실·해결 ACK 유실 | 원본 GET404만으로 새 의도 생성 안 함. terminal409 receipt+원본 typed intent 보존/재조회, 같은 원본은 항상409. 원본 V가 아직 같은 확인 대기도 새 해결 가능. 해결 재시도는 같은 봉인본 |
| 같은 millisecond·메타 변경 실패 | 제안한 UUID tie-break로 도착 순서 독립, 중간실패는 stamp/값/사건/V/feed/receipt 전체 롤백 |
| owner/generation/epoch/proof 변경 | AUTO라도 그대로 차단. 구 원본 재태깅0·새 선택 복구만. old 응답이 다른 scope에 쓰기0 |
| 삭제·membership/order·ordinal 경합 | 자동 덮어쓰기/세트 소실/중복 합산0. OFF에서도 안전 보류 후 원본/차이 유지 |
| 월60 승인/수행·화65 먼저 동기화 | 현재65, 당시 실제60 유지, 검증된 역사적 승인60 한 건 연결. 서버65→60 실제 변경 사건을 가짜로 만들지 않음 |
| 삭제 루틴 뒤 미확인 승인·실제 수행 | 검증된 source의 실제 kg/reps 보존, 루틴/승인값 복원0. 근거 없는 수행은 NO_APPROVED_CONTEXT이며 kg/입력 모드를 자동 수정하지 않음 |
| 사용자 반복 예시 6개 | §4.6의 증량/유지/감량/미달 결과와 selected IDs 일치. 3세트 계획의4세트는 모든 실제 행/총 reps/volume에 포함 |
| 세트 삭제·정정·취소 후 다시 완료 | ordinal/ID 첫 N개 재선택, 최고 성적 선택0. 최초/봉인 계획·최초 평가 불변, 현재 평가만 변경 |
| 과거 비교 동률·늦은 업로드 | 한 세션 selected N개만 사용, 합계 우선·운동 endedAt 최신. 서로 다른 세션 최대값 합성0·serverRecordedAt 영향0 |
| 수정/삭제 루틴의 오프라인 시작 | 캐시된 본인 source와 같은 계획은 START/실제 수행 저장, 루틴·일정·배치 복원0. 다른 owner/generation/변조 source는 거절하고 로컬 원본 보존 |
| source 생성/보존·미ACK 선행 변경 | 내용 변화만 새 source, 단순 memo/설정 변경은 불필요 생성0. source ID 바인딩 시 계획 일치 필수. 어긋난 로컬 계획을 최신 source로 치환0 |

scalar 허용 group에 대해서는 여러 명령 순열이 같은 최종값/stamp로 수렴하는지도 검증한다. 이것을 삭제·제어권·종료 등 안전상 다른 의미에 무조건 확대하지 않는다. 인증/Session/CSRF/Request ID/R 조회 회귀와 프로세스 정리 기준은 §10.2~3을 유지한다.

## 11. 기존 문서와의 충돌·해결·단순화

| 변경 전 근거 | v0.2 처리 |
|---|---|
| Task 006 §4.3·v0.1: 자동 마지막 쓰기 승자 금지/일괄 수동 선택 | **이번 P-NEW-01로 변경**. 기기 occurredAt 최신 의도 자동 적용, 확인 ON일 때 같은 의미 값 충돌만 추천·확인. 원본 재태깅 금지와 안전 보류는 유지 |
| v0.1 전체 UPSERT 배열·누락 ID 삭제·일정 전체 교체 | CREATE 전체 초기값과 UPDATE typed delta 분리, UUID별 의미 group stamp. 다른 배치/속성의 최신값 보존 |
| Task 006 §1.3·v0.1 동일 세트 수/EXTRA_VALID_PERFORMANCE | **P-NEW-02~04로 변경**. M≥N이면 ordinal/ID 첫 N개로 평가, 추가 행 전체 저장. M<N 미충족·다른 원계획 종목 제거 우회 차단 유지 |
| D-07 목표 밖 reps·합계/동률 미결 | **이번에 확정**. 양수 reps 저장/후보 허용, 한 과거 세션의 선택 N개 reps 합계 우선·실제 endedAt 최신. 서버 업로드 시각 제외 |
| 중간 구조 변경·승인 전 수행 후 삭제의 제외 제안 | 현재 유효 수행/승인 근거로 재계산. 중간 조작 자체로 영구 제외 안 함. 불변 계획·최초 판단·사건 보존 |
| D-10 clock-skew 편차·교정 검토 | **이번 결정으로 제거**. 기기 시각 정확성 가정, 보정 없음. 형식/동일 활동의 논리 검증과 구별 |
| v0.1 START 현재 활성/같은 routine V·서명 우선 | **P-NEW-05 반영**. 보관 source 대조로 변경/삭제 루틴의 수행 수용, 복원0. 서명키·일반 revision 서비스 제외 |
| 같은 승인값 no-op는 V/feed 없음 | 최신 같은값 선택도 stamp 변경이므로 V/feed 필요. 승인값 변화 사건은 중복 생성 안 함. 순수 no-op/replay만 증가0 |
| 승인 사건은 현재값 변경만 | 역사적 승인 문맥 보존안으로 현재65와 당시60 수행 분리. 실제 현재값 전후와 intent 전후를 구별 |
| R에 version/stamp/확인 설정 없음 | 기존 DTO 그대로. 신규 detail/bootstrap·sync-preferences GET으로 보완. auth/session DTO 불변 |
| V2 현재세대 FK·Session 독립 tx | 유지. reset 순서/파기·별도 Session tx를 workout 원자성과 혼동하지 않음 |
| demo의 ≤8/structureModified/이름병합/저장실패·ID 혼용 | 기존 차이 그대로 보고. 이번 코드 변경/운영 데이터 이전 없음 |

v0.1·Task 006/008 원문은 보존한다. 이번에 승인한 정책을 과거부터 확정된 것처럼 표기하지 않는다. 줄인 구조는 서명 start token/key 운영·전체 루틴 revision 이력·서버 시계 오차 추정·전체 배열 자동 merge·범용 이벤트 replay·일반 sync_conflicts 저장소다. 남긴 최소 상태는 의미별 마지막 stamp, 원자 영수증, 실제 수행/필요 사건, 시작 계획 원본 한 종류다.

주요 재작업 위험은 root V만으로 최신성 추측, 해결 operation의 새 시각으로 원래 의도를 덮기, 같은값 최신 선택을 무조건 no-op 처리, 첫 N개만 DB에 저장, 현재 승인값으로 과거 kg 수정, source 없이 client plan 신뢰/삭제 루틴 부활이다. 관련 첫 schema/API에서 방지하되 미사용 대형 인프라를 먼저 만들지 않는다.

## 12. 구현 준비 상태·선행 확정 항목·후속 범위

### 12.1 009-1A 전에 반드시 동결할 기술 계약

추가 제품 정책과 009-1A의 두 보완을 반영했다. 사용자가 009-1A 실행을 명시 승인했으며, 아래 최초 동결 목록 중 요청에 포함된 숫자·ID·봉인·영수증·계정·stamp·최소 원자성 형식은 채택했다. 실제 구현/검증 범위와 후속 게이트는 1A 보고서를 따른다. 1B 실행 승인은 포함하지 않는다.

| 항목 | 권장 계약 / 남은 확인 | 1A 영향 |
|---|---|---|
| 숫자·ID D-01/02 | NUMERIC(20,6)·decimal 문자열·무반올림, Counter 문자열·양수 INTEGER·신규 UUID v4 채택. 기존 ID 재발급 없음 | DTO/직렬화·격리 NUMERIC 왕복. 실제 중량 컬럼은 후속 도메인 migration |
| 봉인·영수증 D-04/N-01/02/07 | typed POST·JCS/SHA-256, expectedUserId/fieldBases 포함 hash. 성공 및 N-08용 terminal409 저장, 도메인 변경과 성공 receipt 동일 tx, GET404 미확인, 현세대 dedupe 장벽 no-TTL | 확정 거절을 원본 참조로 사용. 모든 실패를 기록하는 별도 registry는 기본안 밖 |
| 최신 의도 D-06/N-08 | occurredAt+originOperationId, typed group/baseStamp null, 같은값 stamp 전진·순수 no-op 구별, 새 해결 operation lineage, 정확한 동시각 canonical UUID 사전순 tie-break 채택 | 최초 편집부터 stamp 필요. 형식·저장 기반만 1A, 실제 stamp 선택·해결 동작은 후속 |
| 원자성 D-05/06/09 | user 락 순서·root V+1, metadata/history-only도 revision/feed, JPA/SQL 정합성·기존 JDBC/Session 유지 | additive version/stamp·receipt/sync와 회귀 |
| 첫 slice | CREATE_EXERCISE·이름 scalar/해결부터, schema만으로 쓰기완료 선언 금지 | 아직 없는 routine/session Entity 선행 scan 없음 |
| 자원 제한 N-06 | body256KiB/이름200code points/memo10000/배열1000은 시작 제안. 해결 명령은 서버 보관 원본 참조로 반복 본문을 줄임. 긴 이름 fixture와 함께 승인/조정 | 무절단·명확한413/422, 임의 제한을 제품 확정으로 승격 안 함 |

N-09 설정의 기본 OFF는 확정됐다. 테이블/신규 GET은 1A에 모두 만들 필요는 없지만 **resolver를 외부에 제공하기 전** 저장/조회·서버의 최신 설정 검증을 완성해야 한다. 기존 R settings를 먼저 바꾸지 않는다.

### 12.2 후속 단계로 미룰 수 있는 상세

| 항목 | 필요 시점 / 남은 기술 상세 |
|---|---|
| N-09 설정·N-08 handler 확장 | 009-2A 운영 편집 전. 이름/휴식/memo allowlist부터, 이후 목표·승인·요일·set 정정 |
| N-03 순서 제약 | 009-2B migration 전. generated/deferrable UNIQUE 또는 부분 UNIQUE·검증된 2단계 변경, membership 경합 안전 보류 |
| N-04 source | 009-2B/2C 전. template 내용·중복 키·생성 hook·삭제 보존, 미ACK 선행 편집이 계획과 달라진 경우 복구. 서명/일반 revision 서비스는 제외 |
| D-03/08 기기·snapshot | 009-2C 전. proof 수명/회전/계정 격리·이전, JSON schema. device UUID만으로 인가 금지 |
| 현재 승인/역사적 문맥 | 승인 쓰기 전. effect별 사건/UNIQUE·base 근거·당시 수행 연결. 잃은 epoch는 명시 복구만 |
| D-07 평가 기술 | 평가/FINISH 전. selected/extra ID·ruleVersion·세션 동시각 tie-break. 중량·반복은 첫 N개만 평가하고 추가 세트의 다른 중량은 자격에 영향 없음. 목표 밖/추가세트·합계/운동시각 정책도 확정 |
| N-05 IDB·탭 | 009-3A 전. lease/fencing·원본/해결 queue·late response·실브라우저 저장 실패. 새 주요 의존성 별도 승인 |
| 운영 파기·reset/탈퇴·PWA 설치 | 해당 별도 기능/배포 전. receipt/source/tombstone/feed 보존·백업 파기. 이번 및 기본 1A 범위 아님 |

권장 순서는 **1A 최소 컬럼·계약 → 1B 영수증/결과 GET·scalar 해결 slice → 2A 종목·설정·memo → 2B typed 루틴·사건·source → 2C 제어권·세션 → 2D 수행 → 4A 결정적 평가 → 2E 종료/복구 → 3 IDB·Outbox·React → 4B 실제 통합**이다. 평가를 종료보다 먼저 완성해 최초 판단을 나중에 소급 작성하지 않는다. §10의 인증·Session·Request ID·R 조회 회귀를 유지한다.

### 12.3 이번 개정 검증·Git·제출

최초 v0.2 문서 제출 시에는 문서 검사만 수행했다. 이후 사용자 지시에 따라 1A의 최소 구현·격리 DB 검증을 완료했다. 이번 전체 Backend build는 unit176+integration398=574개 PASS이고 실제 변경 파일·검증·프로세스 정리·Git 상태는 [1A 보고서](task-009-1a-write-foundation-report.md)에 기록했다. 이 결과는 HTTP 멱등 실행·ACK 유실·자동 해결·React/PWA 성능 검증 완료를 뜻하지 않는다.

v0.1의 SHA-256 `0A735BEE35BA476210C0A5576113CAFA0B0BB250E80BAAA05CECDCDDB31E9B0C`는 시작과 같다. 두 저장소 HEAD는 §1과 같으며 Frontend는 clean이다. v0.1/v0.2를 포함한 Backend 변경은 미커밋·미staging 상태다. Commit/Push·개발/운영 DB 변경·실제 외부 발송은 없고 **009-1B는 시작하지 않았다.**
