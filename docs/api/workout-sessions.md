# 운동 세션 API — Task 009 1·2단계

상태: START·ACTIVE/상세 조회·takeover 및 확정 snapshot·명시 승인 요청·온라인 승인 해결 구현. V12·V13은 격리 PostgreSQL에서만 검증하며 개인 로컬 `overload_dev`에는 적용하지 않는다.
기준: [현행 설계와 후속 승인 보완](../tasks/task-009-redesign-v1.0.md), [루틴 계약](routine-management.md), 기존 인증·CSRF·Task 008 영속성 경계.

## 현재 제공 범위

V12는 `workout_sessions`를 추가한다. InitialPlan은 `initial_plan_snapshot` JSONB 하나가 유일한 원본이다.
V13은 현재 AppliedPlan과 수행·승인·정정 사실을 추가한다. finish·공식 평가·자동 종료·종료 후 정정·IndexedDB는 후속 단계다. 예정 세트 수만큼 행이나 객체를 생성하지 않는다.
**이번 단계에는 만료 전이/예약 작업이 없으므로 24시간이 지나도 ACTIVE가 자동 해제되지 않는다.** 후속 자동 종료 구현 전의 서버 개발 단계이며 완료된 운동 기록 기능으로 제공하지 않는다.

## HTTP·인증

기존 SessionPrincipal의 userId, 세션 쿠키, 쓰기의 X-CSRF-TOKEN을 사용한다. userId를 입력받지 않는다.
별도 editorProof·기기 비밀 토큰·기기 인증 테이블은 없다. editorDeviceId는 인증 수단이 아닌 브라우저 인스턴스 UUID다.
query는 지원하지 않으며 GET body도 거절한다. 쓰기의 Content-Type은 application/json이다.
성공·명시적 오류는 no-store, 기존 X-Request-ID를 유지한다. Entity를 반환하지 않는다.
UUID는 완전한 하이픈 표기(대소문자 허용), Counter는 0..9223372036854775807의 정규 십진 문자열이다.
JSON 중복 키·추가 필드·필수 필드 누락·명시적으로 허용하지 않은 null·잘못된 타입·후행 JSON을 거부한다.

| 메서드 | 경로 | 성공 |
|---|---|---|
| POST | /api/v1/workout-sessions | 신규201+Location, 동일 시작 재확인200 |
| GET | /api/v1/workout-sessions/active | 200, session은 상세 또는null |
| GET | /api/v1/workout-sessions/{id} | 200, 본인 현재 세대 상세 |
| POST | /api/v1/workout-sessions/{id}/takeover | 200, 새 epoch 또는 동일 인수 확인 |
| PUT | /api/v1/workout-sessions/{id}/snapshot | 200, 전체 확정 상태 저장 또는 중복 ACK |
| POST | /api/v1/workout-sessions/{id}/approval-resolutions | 200, 미해결 승인 명시 해결 또는 결과 재확인 |

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
 editorDeviceId,writerEpoch,localRevision,lastWorkoutSyncedAt,initialPlan,lastSnapshotSavedAt,snapshot}
initialPlan = {schemaVersion:1,routineId,routineName,routineVersion,
 weightExercises:[{sessionExerciseId,sourcePlacementId,exerciseDefinitionId,exerciseName,
   setCount,minReps,maxReps,initialWeightKg,approvedWeightKg}],
 cardioActivities:[{sessionActivityId,sourcePlacementId,plan:CardioPlan}]}
```

Counter는 문자열, 계획 수는 JSON 정수, decimal은 기존 루틴 정규 문자열이다. 두 배열은 원래 배치 순서이며 루틴 version은 출처 정보일 뿐 비교 계보 key가 아니다.
세션 종목/활동에는 새 UUID를 부여하고 배치 UUID와 구별한다. 수행 슬롯이나 세트 UUID는 생성하지 않는다.
ACTIVE 응답은 `{userId,dataGeneration,session:상세|null}`이다. 호출 기기는 응답 editorDeviceId와 자신의 ID를 비교해 읽기 전용 여부를 표시한다.
snapshot 구조는 아래를 따른다. START·상세·takeover 및 ACTIVE 내부 상세는 같은 확장 DTO를 사용한다.

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

## V13 저장 경계

| 저장 위치 | 원본과 관계 |
|---|---|
| workout_sessions.applied_plan_snapshot | 현재 적용 목표·순서의 유일 원본 JSONB. InitialPlan과 별개의 변경 가능한 계획 |
| workout_sessions.last_snapshot_hash/saved_at | 현재 epoch의 마지막 수락 revision의 정규 요청 hash와 ACK 시각. 요청별 영수증 아님 |
| session_exercises | 세션 종목/활동 UUID와 종류·정의 ID. 목표·승인값·원래 순서를 복제하지 않음 |
| performed_sets | 실제 완료 세트와 최초 저장 당시 승인 문맥, 명시 삭제 tombstone |
| cardio_results | completed/skipped 확정 결과와 원본 타이머·허용 정정값·tombstone |
| session_weight_approval_requests | 불변 승인 의도·종목별 sequence·선행 의존성과 적용/해결 사실 |
| record_change_events | 수행 정정·삭제 전후 값과 서버 기록 시각. 클릭/전송 로그 아님 |
| progress_change_events의 추가 출처 FK | 실제 승인 중량 변화만 WORKOUT 사건으로 추가. V11 사건·기존 컬럼값 보존 |

V12→V13은 기존 모든 컬럼값을 유지하고 InitialPlan에서 현재 계획과 종목 ID registry만 초기화한다. 초기 수행/승인 행을 만들지 않는다.
InitialPlan은 계속 유일한 최초 계획 원본이며 관계형 registry는 그 계획의 두 번째 사본이 아니다.
현재 AppliedPlan에는 목표와 순서만, 실제 중량·반복·시간은 수행 테이블에만 저장한다.
새 WEIGHT 종목은 현재 세대의 본인 활성 정의를 참조해야 한다. 기존 종목의 정의 삭제는 과거 registry/수행을 지우지 않는다.
새 세션 종목에는 출처 배치 입력 필드가 없다. InitialPlan에 출처가 있는 최초 종목만 원래 루틴 배치로 승인할 수 있다.

## snapshot 정확한 요청 계약

아래 모든 키는 필수다. nullable 값도 키를 생략하지 않는다. 배열은 비어 있을 수 있지만 AppliedPlan에는 최소 한 종목/활동이 있어야 한다.

```json
{
  "dataGeneration": "0",
  "editorDeviceId": "11111111-1111-4111-8111-111111111111",
  "writerEpoch": "0",
  "localRevision": "1",
  "snapshot": {
    "sessionExercises": [
      {"id": "22222222-2222-4222-8222-222222222222", "kind": "WEIGHT", "exerciseDefinitionId": "33333333-3333-4333-8333-333333333333"}
    ],
    "appliedPlan": {
      "schemaVersion": 1,
      "weightExercises": [{"sessionExerciseId": "22222222-2222-4222-8222-222222222222", "setCount": 3, "minReps": 6, "maxReps": 10}],
      "cardioActivities": []
    },
    "performedSets": [
      {"id": "44444444-4444-4444-8444-444444444444", "sessionExerciseId": "22222222-2222-4222-8222-222222222222", "ordinal": 1, "weightKg": "62.5", "reps": 8, "completedAt": "2026-10-31T16:02:00Z", "weightContext": {"kind": "APPROVAL_REQUEST", "requestId": "55555555-5555-4555-8555-555555555555"}}
    ],
    "cardioResults": [],
    "deletedPerformedSetIds": [],
    "deletedCardioResultIds": [],
    "approvalRequests": [
      {"id": "55555555-5555-4555-8555-555555555555", "sessionExerciseId": "22222222-2222-4222-8222-222222222222", "sequence": "1", "previousRequestId": null, "expectedRoutineVersion": "0", "previousApprovedWeightKg": "60", "nextApprovedWeightKg": "62.5", "occurredAt": "2026-10-31T16:01:00Z"}
    ]
  }
}
```

`sessionExercises.kind`: WEIGHT는 definition UUID 필수, CARDIO는 null이다. ID·종류·정의는 불변이며 누락된 기존 registry를 삭제하지 않는다.
AppliedPlan의 배열 순서가 현재 계획 순서다. CARDIO 항목은 `{sessionActivityId,plan}`이며 plan은 기존 [루틴 CardioPlan](routine-management.md)의 키·제약을 그대로 사용한다.
setCount/반복 목표/ordinal/reps는 1..2147483647 JSON 정수이고 minReps≤maxReps다. ordinal은 계획 세트 수를 초과해도 허용한다.
중량은 0 이상, 정수부 최대14자리·소수 최대6자리의 기존 정규 문자열(불필요한 소수 끝0 없음)이다. 시각은 START와 같은 UTC 밀리초 이하 형식이다.

`weightContext`는 다음 중 하나다. 어떤 경우에도 실제 weightKg와 승인 중량을 동일하다고 추정하지 않는다.

- `{kind:"INITIAL_APPROVED",requestId:null}`: 최초 종목만 허용. InitialPlan 승인값을 최초 저장 문맥으로 고정.
- `{kind:"TODAY_ONLY",requestId:null}`: 오늘만 수행. 승인값 없음. 추가 종목에도 허용.
- `{kind:"APPROVAL_REQUEST",requestId:UUID}`: 본 세션·종목의 보존 요청 참조. 서버 최초 수행 저장 시의 요청 상태와 APPLIED일 때의 승인값을 고정.

CARDIO 확정 항목의 모든 키:

```text
{id,sessionActivityId,status:"COMPLETED"|"SKIPPED",startedAt:Instant|null,occurredAt:Instant,result:CardioResult|null}
CardioResult = {timerMs:Integer,activeMs:Integer,corrected:Boolean,distanceKm:Decimal|null,averageBpm:Integer|null}
```

COMPLETED는 startedAt/result 필수, SKIPPED는 result=null이며 미완료 값을 생성하지 않는다.
timerMs는 0..9007199254740991, activeMs는 1..86400000이다. corrected=false이면 둘이 같아야 한다.
distanceKm는 0.001..1000, TREADMILL/BIKE/OUTDOOR만 허용한다. BPM은20..300이다.
초안·현재 running/paused 상태·타이머 앵커는 이 API에 저장하지 않는다.
모든 수행/승인 시각은 시작 이후, 유산소 종료는 시작 이후, timerMs는 해당 경과시간 이하여야 한다. 승인 문맥을 참조한 세트는 승인 요청 시각 이후여야 한다.
sequence가 뒤인 승인 시각이 선행 보존 요청보다 앞서면 CLOCK_CONTEXT_INVALID다. 서버 시각으로 보정하지 않는다.

### 교체·삭제·정정

- AppliedPlan은 전체 교체한다. 기존 완료 수행이 있는 종목을 빼려면 같은 snapshot에서 해당 수행 전부를 삭제 ID로 명시해야 한다. 완료 수행을 그대로 둔 제외는422 PERFORMED_EXERCISE_IN_USE다.
- 기존 유효 수행을 active 배열과 삭제 배열 양쪽에서 누락하면422 SNAPSHOT_INCOMPLETE다. 누락을 삭제 의도로 해석하지 않는다.
- 명시 삭제는 deleted_at과 before audit를 남긴다. 이미 삭제한 ID 재확인은 무변경이다. 일반 snapshot은 삭제한 UUID를 부활시키지 않는다.
- 같은 ordinal/활동의 교체는 이전 UUID를 명시 삭제하고 새 UUID로 완료 사실을 추가한다. 기존 삭제 UUID의 재사용은 금지한다.
- 기존 세트 UUID·종목·ordinal·완료 시각·승인 참조/최초 승인 문맥은 불변이다. 중량·반복만 정정하며 before/after audit를 남긴다.
- 유산소 UUID·활동·status·시작/완료 시각·timerMs는 불변이다. activeMs/정정 플래그/거리/BPM만 허용 범위 안에서 정정한다.
- 승인 요청·처리 결과와 registry는 서버 보존 사실이다. 후속 입력에서 빠져도 삭제/재적용하지 않는다.
- 원래 미완료 종목을 빼도 InitialPlan은 유지된다. 향후 평가가 현재 계획 축소를 원계획 완료로 해석해서는 안 된다. 현재 단계는 공식 평가를 실행하지 않는다.

### revision·ACK

현재 epoch에서 localRevision>0의 높은 값만 수락한다. 연속 번호일 필요는 없다.
동일 revision·동일 정규 요청은200 duplicate=true이며 운동 DML/시간/version 변경이 없다. 다른 본문은409 REVISION_PAYLOAD_MISMATCH, 낮은 revision은409 REVISION_STALE다.
객체 키 순서·UUID 대소문자·identity/수행/삭제 배열의 순서는 의미를 바꾸지 않는다. 계획 순서는 보존하고 승인 배열은 종목별 sequence로 정규화한다.
ACK 유실 시 같은 입력을 재전송한다. 응답/상세의 acceptedLocalRevision 또는 localRevision과 승인 결과를 확인한다.
높은 revision의 실제 무변경 저장은 수락 revision/hash·snapshotSavedAt·version만 갱신한다. lastWorkoutSyncedAt은 AppliedPlan/유효 수행/명시 삭제/허용 정정이 실제 달라진 경우만 갱신한다.
takeover는 localRevision=0/hash=null/lastSnapshotSavedAt=null로 새 epoch를 시작하며 계획·수행·승인 결과·lastWorkoutSyncedAt을 유지한다.

정확한 ACK 키(모든 Counter는 문자열):

```text
{userId,dataGeneration,sessionId,writerEpoch,acceptedLocalRevision,version,snapshotSavedAt,lastWorkoutSyncedAt,duplicate,approvalResults:[ApprovalResult]}
ApprovalResult = {id,sessionExerciseId,sequence,status:"APPLIED"|"PENDING"|"NOT_APPLIED",reason:String|null,
 approvalEventId:UUID|null,routineVersionBefore:Counter|null,routineVersionAfter:Counter|null,resolvedAt:Instant|null,resolvedByRequestId:UUID|null}
```

START/상세/takeover의 추가 `snapshot`은 `{state:위 snapshot 전체 구조,approvalResults:[ApprovalResult],recordedContexts:[{setId,approvalStateAtRecording,approvedWeightKgAtRecording:Decimal|null}]}`다.
state는 유효 수행·전체 삭제 ID·전체 보존 요청을 반환한다. recordedContexts는 삭제된 세트까지 최초 문맥을 보존한다. 승인 상태는 전송 필드가 아니라 서버 응답이다.
lastSnapshotSavedAt은 아직 해당 epoch에서 snapshot을 수락하지 않았다면 null이다.

## 명시 승인 적용·충돌·온라인 해결

요청 UUID별 원문 의도를 보존한다. 같은 UUID의 다른 의도는409 APPROVAL_REQUEST_ID_CONFLICT다.
종목별 sequence는1부터 빈틈없이 증가한다. 점유 순번은409 APPROVAL_SEQUENCE_CONFLICT, 건너뜀은422 APPROVAL_SEQUENCE_GAP다.
previousRequestId는 같은 종목의 앞선 요청을 참조하며 그 next 중량과 새 previous 중량이 같아야 한다. 오프라인 연속 승인은 이 연결을 유지한다. 재조회로 확인한 새 독립 승인은 null로 표현한다.
같은 업로드는 잠금 당시 루틴 version을 공통 외부 기준으로 처리한다. 내부 각 승인을 순서대로 적용하고 실제 변화가 있으면 루틴 version은 트랜잭션당1 증가한다. 각 실제 변화 사건은 따로 보존한다.
ACK 유실 후 이어지는 요청은 이 세션의 APPLIED 요청에 저장한 versionBefore→versionAfter 연결이 기대값과 현재값 사이를 빠짐없이 설명하는 경우에만 자체 증가로 인정한다. 외부 변경으로 생긴 빈틈은 승인 충돌이다.

정상은 APPLIED다. 같은 중량의 명시 승인도 의도를 보존하지만 실제 변화 사건/루틴 version 증가는 없다.
루틴 변경(ROUTINE_CHANGED), 출처 삭제(SOURCE_REMOVED), 출처 없음(NO_SOURCE_PLACEMENT), 선행 미해결/미적용(PREDECESSOR_PENDING/PREDECESSOR_NOT_APPLIED)은 PENDING이다.
PENDING이 있어도 유효 수행과 현재 계획은 같은 트랜잭션에서 저장하고200 ACK한다. 승인된 것으로 표시하지 않는다. 저장소 실패/제약 오류를 PENDING으로 바꾸지 않는다.

온라인 해결 요청의 모든 키:

```json
{
  "dataGeneration": "0", "editorDeviceId": "11111111-1111-4111-8111-111111111111", "writerEpoch": "0",
  "expectedSessionVersion": "2", "expectedLocalRevision": "1",
  "sessionExerciseId": "22222222-2222-4222-8222-222222222222",
  "pendingRequestIds": ["55555555-5555-4555-8555-555555555555"],
  "decision": "KEEP_CURRENT", "occurredAt": "2026-10-31T16:05:00Z", "newApprovalRequest": null
}
```

pendingRequestIds는 그 종목의 현재 미해결 요청 전부와 일치해야 한다. session version/localRevision도 같아야 하며 불일치는409 RESOLUTION_CONFLICT다.
KEEP_CURRENT는 선택한 요청을 NOT_APPLIED/KEPT_CURRENT로 확정하고 현재 루틴 승인값을 유지한다.
REAPPROVE는 `decision:"REAPPROVE"`, `newApprovalRequest:{id:새UUID,sequence:다음Counter,expectedRoutineVersion:현재Counter,previousApprovedWeightKg:현재Decimal,nextApprovedWeightKg:Decimal}`를 보낸다.
새 요청의 종목/occurredAt은 해결 요청에서 가져오고 previousRequestId=null이다. 선택한 과거 요청은 NOT_APPLIED/REAPPROVED/resolvedByRequestId=새UUID로 확정한다.
루틴 현재 version/중량을 다시 검증하고 새 요청·새 사건을 지금 발생한 승인으로 원자 저장한다. 삭제/출처 없는 배치는409 SOURCE_NOT_APPROVABLE이며 KEEP_CURRENT만 가능하다.
과거 PENDING을 APPLIED로 소급 전환하거나 기존 수행 문맥·과거 사건을 고치지 않는다.

해결 응답은 `{userId,dataGeneration,sessionId,writerEpoch,localRevision,version,lastWorkoutSyncedAt,approvalResults:[ApprovalResult]}`다.
해결은 session version만 올리고 localRevision/hash/lastWorkoutSyncedAt을 바꾸지 않는다.
응답 유실 시 GET 상세의 status/reason/resolvedAt/resolvedByRequestId로 확인한다. 동일 해결 사실 재확인은200 무변경이다. 별도 영구 요청 영수증/요청 ID는 없다.

## snapshot 트랜잭션과 추가 오류

하나의 workoutTransactionManager READ_COMMITTED 트랜잭션에서 users FOR SHARE → 승인 포함 시 source routine FOR UPDATE → 정의 UUID순 공유 잠금 → 본인 세션 FOR UPDATE → 하위 저장 순서다.
세션 출처 사전 조회는 스칼라 JDBC만 사용하고, 잠금 전에 JPA 세션을 캐시하지 않는다. routine PUT/DELETE와 같은 잠금 순서이며 takeover는 세션 잠금에서 직렬화한다.
수행·승인 요청·실제 변화 사건·정정 이력·계획·버전·ACK 메타는 하나의 커밋이다. 중간 SQL 오류/overflow/불변 문맥 위반은 전체 롤백한다.
CSRF·소유권·세대·편집 기기·epoch 검증은 중복 ACK와 해결 재확인에도 우회하지 않는다.

추가400 INVALID_REQUEST,404 RECORD_NOT_FOUND/EXERCISE_NOT_FOUND,409 RECORD_ID_CONFLICT(다른 문맥이 점유한 수행/종목 UUID),422 INVALID_SNAPSHOT/IMMUTABLE_RECORD_CONTEXT/RECORD_DELETED와 위 도메인 오류를 사용한다. 기존503/500 저장소 오류 경계는 유지한다.
finish·24시간 만료 수신 검사/작업·최초 종료 계획/평가 봉인·사후 종료 정정/복구는 아직 제공하지 않는다.
