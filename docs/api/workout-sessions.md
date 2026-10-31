# 운동 세션 API — Task 009 1단계

상태: START·ACTIVE/상세 조회·명시적 takeover 구현. 신규 V12는 격리 PostgreSQL에서만 검증한다. 개인 로컬 `overload_dev`에는 적용하지 않는다.
기준: [현행 설계와 후속 승인 보완](../tasks/task-009-redesign-v1.0.md), [루틴 계약](routine-management.md), 기존 인증·CSRF·Task 008 영속성 경계.

## 현재 제공 범위

V12는 `workout_sessions` 한 테이블만 추가한다. InitialPlan은 `initial_plan_snapshot` JSONB 하나가 유일한 원본이다.
공식 수행·AppliedPlan·승인 요청·finish·자동 종료·사후 정정·IndexedDB는 후속 단계다. 예정 세트 수만큼 행이나 객체를 생성하지 않는다.
**이번 단계에는 만료 전이/예약 작업이 없으므로 24시간이 지나도 ACTIVE가 자동 해제되지 않는다.** 후속 자동 종료 구현 전의 서버 개발 단계이며 완료된 운동 기록 기능으로 제공하지 않는다.

## HTTP·인증

기존 SessionPrincipal의 userId, 세션 쿠키, 쓰기의 X-CSRF-TOKEN을 사용한다. userId를 입력받지 않는다.
별도 editorProof·기기 비밀 토큰·기기 인증 테이블은 없다. editorDeviceId는 인증 수단이 아닌 브라우저 인스턴스 UUID다.
query는 지원하지 않으며 GET body도 거절한다. POST Content-Type은 application/json이다.
성공·명시적 오류는 no-store, 기존 X-Request-ID를 유지한다. Entity를 반환하지 않는다.
UUID는 완전한 하이픈 표기(대소문자 허용), Counter는 0..9223372036854775807의 정규 십진 문자열이다.
JSON 중복 키·추가 필드·필수 필드 누락·null·잘못된 타입·후행 JSON을 거부한다.

| 메서드 | 경로 | 성공 |
|---|---|---|
| POST | /api/v1/workout-sessions | 신규201+Location, 동일 시작 재확인200 |
| GET | /api/v1/workout-sessions/active | 200, session은 상세 또는null |
| GET | /api/v1/workout-sessions/{id} | 200, 본인 현재 세대 상세 |
| POST | /api/v1/workout-sessions/{id}/takeover | 200, 새 epoch 또는 동일 인수 확인 |

START의 모든 필드:

```text
{id:UUID, dataGeneration:Counter, routineId:UUID, editorDeviceId:UUID,
 startedAt:Instant, workoutDate:LocalDate, startedTimeZone:IANA}
```

startedAt은 실제 사용자 시작 시각이다. UTC `YYYY-MM-DDTHH:mm:ss[.SSS]Z`, 소수초 최대3자리, 연도0001..9999다.
workoutDate는 실제 유효한 YYYY-MM-DD이며 시작 시각을 해당 IANA 시간대로 변환한 날짜와 일치해야 한다.
잘못된 JSON/날짜 형식은400, 시간대·현지 날짜 문맥 불일치는422 CLOCK_CONTEXT_INVALID다.
서버 업로드 시각으로 덮어쓰거나 서버와의 임의 허용 편차를 도입하지 않는다.

takeover의 모든 필드:

```text
{dataGeneration:Counter, editorDeviceId:UUID, expectedWriterEpoch:Counter}
```

현재 epoch가 기대값이면 이전 기기 승인 없이 writerEpoch/version을 각각1 올리고 새 기기를 지정하며 localRevision=0으로 한다.
현재 epoch가 기대값+1이고 대상 기기가 같다면 직전 인수 응답 유실의 재확인으로200, 추가 변경 없음이다.
다른 인수가 끼어든 오래된 요청은409 EDITOR_OWNERSHIP_LOST다. Counter overflow도409이며 전체 롤백한다.
takeover는 lastWorkoutSyncedAt을 갱신하지 않는다. 별도 heartbeat·주기 송신은 없다.

## 응답·불변 계획

상세:

```text
{userId,dataGeneration,id,status:"ACTIVE",version,startedAt,workoutDate,startedTimeZone,
 editorDeviceId,writerEpoch,localRevision,lastWorkoutSyncedAt,initialPlan}
initialPlan = {schemaVersion:1,routineId,routineName,routineVersion,
 weightExercises:[{sessionExerciseId,sourcePlacementId,exerciseDefinitionId,exerciseName,
   setCount,minReps,maxReps,initialWeightKg,approvedWeightKg}],
 cardioActivities:[{sessionActivityId,sourcePlacementId,plan:CardioPlan}]}
```

Counter는 문자열, 계획 수는 JSON 정수, decimal은 기존 루틴 정규 문자열이다. 두 배열은 원래 배치 순서이며 루틴 version은 출처 정보일 뿐 비교 계보 key가 아니다.
세션 종목/활동에는 새 UUID를 부여하고 배치 UUID와 구별한다. 수행 슬롯이나 세트 UUID는 생성하지 않는다.
ACTIVE 응답은 `{userId,dataGeneration,session:상세|null}`이다. 호출 기기는 응답 editorDeviceId와 자신의 ID를 비교해 읽기 전용 여부를 표시한다.
이 단계에는 일반 snapshot 쓰기 경로가 없으며 이후 쓰기는 deviceId·epoch·revision을 함께 검증한다.

START는 고정 UUID와 원래 요청을 사용한다. 이미 본인 현재 세대의 동일 ID가 있으면 루틴·최초 기기·시각·날짜·시간대 일치 확인 후 현재 세션을 반환한다.
이 재확인은 루틴 삭제 이후에도 가능하며 InitialPlan 재복사·편집권 재부여·시각 갱신을 하지 않는다. 다른 내용·타 소유자 ID 점유는409다.
동시 생성 PK/ACTIVE UNIQUE 경쟁은409 WRITE_CONFLICT이며 새 트랜잭션의 동일 START로 확인할 수 있다.

초기 version/writerEpoch/localRevision은0이다. 마지막 동기화 시각은 START의 서버 저장 시각이며 실제 시작 시각과 구별한다.
InitialPlan은 JPA updatable=false 및 PostgreSQL immutable-start trigger로 수정할 수 없다. 관계형 계획 사본은 없다.
루틴·종목 이름/목표/승인값 변경·배치 제거·루틴 논리 삭제가 이 JSONB를 바꾸지 않는다. 삭제 cascade는 없다.

## 제약·트랜잭션

ACTIVE 부분 UNIQUE는 **user_id 단독**이다. 세대가 바뀌어도 한 계정의 ACTIVE를 두 개 허용하지 않는다.
세션 owner/generation 및 source routine은 복합 FK다. 구 세대 연결·자동 세대 이관은 허용하지 않는다.
향후 reset은 users 배타 잠금 아래 기존 운동 데이터 정리·세대 증가·재구성을 원자 처리해야 한다. 현재 reset API는 없다.

START는 workoutTransactionManager의 REPEATABLE_READ 쓰기 트랜잭션 하나에서 users FOR SHARE → routine FOR SHARE → 종목 UUID순 공유 잠금 → InitialPlan/세션 INSERT를 처리한다.
루틴 PUT/DELETE의 기존 users→routine FOR UPDATE→종목 잠금과 경합하며, 복사 도중 계획이 섞이지 않는다. 직렬화 실패는409, 자동 재시도/병합 없음이다.
같은 트랜잭션에서 JDBC owner 조회와 JPA 잠금·삽입을 처리한다. takeover는 users 공유 잠금→본인 세션 FOR UPDATE로 직렬화한다.
상세/ACTIVE는 기존 Task 008과 같은 readOnly REPEATABLE_READ로 owner/generation 및 DTO를 일관되게 완성한다. 조회는 운동 DML·만료 연장·seed를 하지 않는다.
기존 primary JDBC 관리자·Spring Session REQUIRES_NEW·CSRF·인증 수명 정책은 유지한다.

## 오류

본문은 `{code}`다. 기존401 AUTHENTICATION_REQUIRED와403 CSRF_INVALID/ACCESS_DENIED를 유지한다.
400 INVALID_REQUEST,404 SESSION_NOT_FOUND/ROUTINE_NOT_FOUND,422 CLOCK_CONTEXT_INVALID,
409 DATA_GENERATION_MISMATCH/SESSION_ID_CONFLICT/ACTIVE_SESSION_EXISTS/EDITOR_OWNERSHIP_LOST/SESSION_NOT_ACTIVE/WRITE_CONFLICT를 정의한다.
타 소유자 조회/인수는 미존재와 같은404이며 UUID 점유 응답에 소유자 정보를 넣지 않는다.
확인된 연결·timeout 오류는503 WORKOUT_DATA_UNAVAILABLE, 예상 밖 실패는500 WORKOUT_DATA_WRITE_FAILED다.
405/415 등 프로토콜 오류는 기존 MVC 경계를 유지한다. SQL·예외 원문·민감정보는 본문에 노출하지 않는다.
