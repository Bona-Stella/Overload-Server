# Task 008-0B — 운동 도메인·외부 API 계약 v0.1

> **현행 기준 변경 (2026-10-30):** 운동 쓰기·진행 세션·복구는 [Task 009 v1.0](task-009-redesign-v1.0.md)이 우선합니다. Task 008의 기존 조회(R-01~03)·공통 HTTP·JPA/JDBC 검증 경계는 유지합니다. 본문의 미구현 쓰기·명령/영수증/feed·Outbox·선택 복구 제안은 현재 구현 요구사항이 아닙니다. 새 쓰기·세션·IndexedDB 기능은 아직 미구현입니다.

- 작성일: 2026-10-30 (Asia/Seoul)
- 상태: **검토안 / API 미구현 / 새 기술 선택 승인 대기 / 후속 사용자 승인으로 문서만 로컬 Git 보관, Push 없음**
- 기준: server `a8eccf9`, pwa `1c84d23`. JPA 설계만 `a8eccf9` (`docs: design JPA persistence for workout domain`)으로 보존했다.
- 우선순위: 현재 사용자 결정 → [Task 006 최종 설계 v0.3.1](task-006-integrated-design-v0.3.1.md) → 이 문서의 제안. 확정 제품 정책을 기술 제안으로 변경하지 않는다.
- 영속성·트랜잭션·연관관계·검증 게이트는 승인된 [JPA 설계](task-008-0-jpa-persistence-design.md)를 참조한다. JPA/JDBC 런타임 호환성은 아직 미검증이다.
- 사전 분석 근거: 같은 채팅의 Task 008 인수인계 분석, [Task 006 모델 대응 분석](task-006-model-mapping-report.md), 현재 [React 타입](../../../overload-pwa/src/features/workout/types.ts)·[모델](../../../overload-pwa/src/features/workout/model.ts)·[유산소](../../../overload-pwa/src/features/workout/cardio.ts). 과거 분석의 구현 상태보다 현재 소스와 V1~V8을 우선한다.
- 이번 실행 범위: 문서 검토·기존 JPA 문서 로컬 커밋·이 문서 작성만 수행. 제품 코드·의존성·SQL migration·DB·인증·이메일·OAuth 변경/호출 없음.

## 0. 계약 상태와 구현 게이트

**P**는 Task 006 또는 현재 사용자에게 이미 승인된 정책, **R**은 008-1의 구체적인 읽기 계약 기준안, **D**는 새 기술 권장안으로 다음 단계 승인에 포함해야 하는 선택이다. R도 현재 설치된 API를 뜻하지 않으며, 구현은 별도 승인 후 JPA/JDBC 통합 검증부터 시작한다.

| 결정 ID | 권장안 | 필요한 시점·승인 |
|---|---|---|
| R-01 | 두 GET의 owner/generation envelope, 종목 id/name, 휴식 3필드, 아래 오류 계약 | 008-1 구현 전 계약 확인. 쓰기 계약을 기다릴 필요 없음 |
| R-02 | BIGINT generation을 JSON 10진 문자열로 전달; 기존 UUID 그대로 반환 | 008-1 구현 전 확인 |
| R-03 | 같은 읽기 응답 안의 owner·generation·데이터를 일관된 DB snapshot에서 조회 | JPA/JDBC 검증에서 실제 구현 방법 확인 |
| D-01 | 중량 NUMERIC(20,6), BigDecimal, JSON/TS decimal 문자열; 초과 scale 거절 | 최초 중량 쓰기 전 승인 필요. 데모의 더 긴 소수를 제한하므로 무승인 확정 금지 |
| D-02 | 새 오프라인 도메인/operation UUID v4, 카운터 문자열, 계획·반복 INTEGER 범위 | 최초 쓰기 전 승인 필요. 기존 UUID 교체 없음 |
| D-03 | 명시 필드의 snapshotSchemaVersion=1, checkpoint와 공식 수행 분리 | 세션 쓰기 전 승인·fixture 검증 |
| D-04 | 단일 typed POST 명령 전송 API + 리소스 GET, JCS/SHA-256 fingerprint·ACK | 최초 쓰기 전 승인. 아래 대안과 비교 |
| D-05 | 사용자·세대별 직렬 revision/변경 batch와 무효화 기반 pull | 최초 동기화 대상 쓰기 스키마 전 승인. feed를 나중에 누락 없이 만들 수 있게 준비 |
| D-06 | 루트별 version, 고정 잠금 순서, 여러 집계의 한 명령 원자성 | 최초 쓰기 전 승인·동시성 검증 |
| D-07 | 공식 단일 세션 선택의 합계 반복 우선·동률 규칙 등 | 공식 기준 자동 입력/평가 구현 전 사용자 승인. UX·공식 자격 관련 경계 포함 |
| D-08 | 기기 등록·제어권 부여의 서버 바인딩 증명, 같은 기기 다중 탭 조정 | 세션 쓰기 전 보안 구현 계약 확정 필요. deviceId만으로 권한 부여 금지 |
| D-09 | 보조 리소스 version/종목 tombstone·memo 삭제 전파 | 종목·설정·일정·메모 편집 전 승인 및 신규 migration 검토 |
| D-10 | 시계 이상·ordinal 충돌·오프라인 원계획 확인의 실패/복구 상세 | 세션/복구 쓰기 전 확정. 날짜 임의 수정·자동 합산은 승인 범위 밖 |

008-1에는 D-01~10의 미래 컬럼·테이블·응답 필드를 선행 도입하지 않는다. 특히 현재 V2에 없는 version/deleted_at/cursor를 읽기 계약에 필수로 요구하지 않는다.

## 1. 공통 외부 타입과 보안 경계

| 타입 표기 | JSON / Java / TypeScript | 의미 |
|---|---|---|
| UUID | canonical 하이픈 문자열 / UUID / string | 읽기는 기존 유효 UUID를 버전 변경 없이 반환. 신규 생성만 D-02 적용 |
| Counter | `"0"` 또는 선행 0 없는 양의 10진 문자열 / long / string(계산 시 bigint) | generation·version·epoch·revision; 0..9223372036854775807, 증가 overflow는 실패 |
| Decimal | canonical 비음수 소수 문자열 / BigDecimal / string | 중량 등 정확한 수치. D-01 승인 전 정밀도 미확정 |
| Count | JSON 정수 / Integer / number | 실제 반복·계획 수·ordinal; 신규 ≥1, 상한은 D-02 |
| Instant | UTC ISO-8601 `...Z` 문자열 / Instant / string | 클라이언트 시각과 서버 시각의 필드를 구분 |
| LocalDate | `YYYY-MM-DD` / LocalDate / string | 운동 시작 현지 날짜; timestamp로 변환하지 않음 |
| DurationMs | JSON 안전 정수 / long / number | 누적 시간; 0..9007199254740991 내에서 도메인별 기존 제한도 적용 |

API base는 `/api/v1`, JSON은 camelCase다. UUID/Counter는 문자열, nullable 필드는 명시 null, 빈 목록은 `[]`다. 이름은 식별자가 아니며 동명 종목·중복 배치가 가능하다. 현재 API의 이름을 읽을 때 trim/대소문자 변경/정규화하지 않는다.

서버의 `SessionPrincipal.userId()`만 owner를 결정한다. 요청의 userId/계정 header를 권한 근거로 받지 않는다. response의 userId는 서버가 결정한 계정 범위를 표시하며 credential 정보는 포함하지 않는다. 인증·쿠키·CSRF·90일·재인증 정책은 기존 구현 유지다. GET은 no-store이며 세션 복원 외에 OTP/OAuth 호출을 유발하지 않는다. 쓰기는 기존 CSRF 검증을 그대로 통과해야 한다.

생성·수정 시각은 서버 값, 실제 운동 시작·완료 시각은 별도의 사용자 기기 이벤트 값이다. Entity·세션 쿠키·identity·원문 이메일·SQL·stack trace는 응답하지 않는다. R 읽기 계약에는 요청별 추적 필드나 개인정보를 새로 추가하지 않는다.

## 2. 008-1: GET /api/v1/exercises (R-01~03)

### 2.1 요청·정상 응답

- Method/Path: **GET `/api/v1/exercises`**
- body 없음. v0.1의 query parameter는 없음. `userId`, `dataGeneration`, 임의 owner/sort/filter/limit query는 400 `INVALID_REQUEST`; 사용자 범위로 해석하지 않는다.
- 서버 세션 인증 필수. Accept: application/json, credentials 포함. GET에 새 CSRF 토큰 발급을 요구하지 않는다.
- 200, Content-Type: application/json, Cache-Control: no-store. ETag/304·Service Worker 개인 데이터 캐시는 이번 계약에 없음.

```json
{
  "userId": "11111111-1111-4111-8111-111111111111",
  "dataGeneration": "0",
  "exercises": [
    {"id": "22222222-2222-4222-8222-222222222222", "name": "벤치프레스"}
  ]
}
```

위 UUID는 문서용 합성 값이다. 기존 개발 계정을 식별하지 않는다.

| 응답 필드 | JSON 타입 | 필수·null | 읽는 컬럼·출처 |
|---|---|---|---|
| userId | UUID string | 필수, null 불가 | principal UUID에 일치하는 `users.id` |
| dataGeneration | Counter string | 필수, null 불가 | 같은 사용자의 `users.data_generation` |
| exercises | array | 필수, null 불가 | 현재 세대 종목 전체; 0개는 빈 배열 |
| exercises[].id | UUID string | 필수, null 불가 | `exercise_definitions.id` 원값 |
| exercises[].name | string | 필수, null 불가 | `exercise_definitions.name` 원값 |

필터에 `exercise_definitions.user_id = principal.userId`와 `exercise_definitions.data_generation = users.data_generation`를 모두 적용한다. `users.id/data_generation`과 `exercise_definitions.user_id/data_generation`은 검증에 쓰고 응답에 중복 복제하지 않는다. V2의 created_at/updated_at은 008-1 DTO에 노출할 필요가 없으며 DB 원값을 그대로 보존한다.

정렬은 **종목 UUID 오름차순**으로 고정한다. 이름·DB locale·seed 삽입 순서에 의존하지 않는다. V2에는 표시 순서 컬럼이 없다. 008-1은 전량 조회이며 pagination/검색/사용자 정렬은 없다. 쓰기 단계에서 목록 규모 제한·pagination을 도입하려면 별도 계약을 정하고 조용히 일부만 잘라 반환하지 않는다.

### 2.2 인증·세대·일관된 읽기

1. 기존 보안 필터를 거쳐 principal을 얻는다. principal이 없거나 만료되면 기존 401 `AUTHENTICATION_REQUIRED`다.
2. 해당 users의 현재 세대를 **서버가 조회**한다. 요청 세대를 최신으로 채택하지 않는다.
3. 같은 DB snapshot에서 현재 세대 종목을 읽고 DTO를 만든다. 단순 READ_COMMITTED의 분리된 두 SELECT 사이에 향후 reset이 끼어들어 옛 세대와 빈 목록이 섞이지 않도록 한다.
4. 구현 우선안은 JPA readOnly 트랜잭션의 검증된 REPEATABLE_READ 범위에서 users 세대 조회(JDBC 허용)와 JPA 종목 조회를 함께 수행하는 것이다. 동일 연결/isolation 지원은 승인된 JPA 설계의 통합 게이트에서 확인한다. 동등한 단일 SELECT projection도 가능하다. readOnly 트랜잭션에서 FOR UPDATE/SHARE로 우회하지 않는다.
5. 읽는 동안 reset이 커밋되더라도 응답은 요청 snapshot 기준 하나의 세대다. 이 GET이 미래 reset을 실시간 봉쇄한다는 보장은 없다. 이후 쓰기·pull은 최신 세대와 다시 비교한다.

PostgreSQL READ_COMMITTED는 statement별, REPEATABLE_READ는 transaction snapshot을 제공한다. 실제 관리자 구성은 런타임 테스트가 필요하다. [공식 isolation 문서](https://www.postgresql.org/docs/18/transaction-iso.html)

### 2.3 실패·빈 데이터·보존

| 상황 | HTTP/code | 클라이언트 의미·서버 부작용 |
|---|---|---|
| 비로그인·만료·principal의 users 부재 | 401 AUTHENTICATION_REQUIRED | 인증 재확인. 계정 생성/복구·재seed·인증 정책 변경 없음 |
| 인증된 요청의 기존 보안 접근 거부 | 403 ACCESS_DENIED | 기존 보안 응답 유지 |
| 지원하지 않는 query·형식 | 400 INVALID_REQUEST | userId/generation을 조회 범위로 쓰지 않음 |
| users 존재, 현재 종목 0개 | 200, exercises: [] | 정상 빈 목록. 기본 20개를 다시 만들지 않음 |
| DB 연결/일시 접근 실패 | 503 WORKOUT_DATA_UNAVAILABLE | 연결 문제. 빈 목록·로그아웃으로 바꾸지 않음 |
| 그 밖의 조회/매핑 실패 | 500 WORKOUT_DATA_READ_FAILED | 일반 오류. SQL/내부 exception 미노출 |

읽기 경로는 저장·기본값 대입·UUID 교체·updated_at 변경·demo import를 하지 않는다. 존재하지 않는 deleted_at/version 조건을 현재 V2에 적용하지 않는다. 미래 종목 논리 삭제 도입 때만 D-09에 따라 확장한다.

## 3. 008-1: GET /api/v1/workout-settings (R-01~03)

요청·인증·query 금지·JSON/no-store·일관된 snapshot 규칙은 2절과 같다. **GET `/api/v1/workout-settings`**, 200 응답은 아래다.

```json
{
  "userId": "11111111-1111-4111-8111-111111111111",
  "dataGeneration": "0",
  "restLowSeconds": 180,
  "restMediumSeconds": 120,
  "restHighSeconds": 90
}
```

| 응답 필드 | JSON / Java / TS | 읽는 V2 컬럼 | 의미 |
|---|---|---|---|
| userId | UUID string / UUID / string | `users.id` (principal과 일치) | 계정 범위 |
| dataGeneration | Counter string / long / string | `users.data_generation` | 서버 현재 세대 |
| restLowSeconds | 정수 / Integer / number | `user_workout_settings.rest_low_seconds` | 목표 반복 상한 ≤10회 |
| restMediumSeconds | 정수 / Integer / number | `user_workout_settings.rest_medium_seconds` | 목표 상한 11–12회 |
| restHighSeconds | 정수 / Integer / number | `user_workout_settings.rest_high_seconds` | 목표 상한 ≥13회 |

`user_workout_settings.user_id/data_generation`을 users의 owner·현재 세대에 함께 맞춘다. PK는 user_id이며 별도 settings ID가 없다. 휴식 초는 기존 INTEGER CHECK >0이므로 1..2147483647의 JSON 정수다. DTO의 180/120/90은 예시일 뿐이며 **편집된 실제 저장값을 반환**한다. created_at/updated_at은 008-1 응답에 없고 DB를 변경하지 않는다.

- users와 현재 세대 설정 1행 존재: 실제 값으로 200.
- users가 없으면 2절과 같은 401. users가 있지만 현재 세대 설정이 없으면 **500 `WORKOUT_SETTINGS_MISSING`**. 정상 빈 설정이 아니며 서버 데이터 불일치다. 옛 세대 설정이나 AccountSeed 기본값으로 대체하지 않는다. 조회 과정에서 INSERT/UPSERT하지 않는다.
- DB 장애는 503 `WORKOUT_DATA_UNAVAILABLE`, 그 밖의 매핑/조회 실패는 500 `WORKOUT_DATA_READ_FAILED`다. 다른 계정 설정을 fallback하지 않는다.
- R 오류 body는 `{ "code": "WORKOUT_SETTINGS_MISSING" }`와 같은 최소 객체다. 기존 인증 오류 body는 그대로 유지한다. 나중의 필드별 validation DTO는 12절을 따른다.

## 4. 008-1 React 연동·수용 기준

현재 `/`의 AuthProvider/AuthApp과 개발 전용 `/demo`를 유지하고, 인증된 영역에 **읽기 전용 종목 목록·휴식 설정 조회**를 연결하는 범위다. 운동 시작·저장·편집 버튼을 서버 지원 완료처럼 활성화하지 않는다. 기존 파란색 #3B82F6·CSS·모바일 UI를 유지한다.

- 기존 session GET으로 확인한 UUID를 요청 문맥에 묶는다. 두 응답의 userId가 그 UUID와 같고 두 dataGeneration이 같은지 검증한다. query에 UUID를 보내지 않는다.
- 두 GET은 독립 호출할 수 있지만 다른 세대가 섞이면 같은 화면 상태로 합치지 않는다. 결과를 보류하고 두 GET을 다시 조회한다. 반복 불일치는 동기화 확인 상태로 표시하며 자동 무한 재시도하지 않는다.
- 현재 인증 controller의 account 작업을 다시 구현하지 않고 기존 복원·로그아웃 상태와 연결한다. 사용자/조회 epoch별 요청 취소·늦은 응답 폐기·메모리 캐시 분리. 로그아웃·다른 계정 전환 뒤 이전 목록을 표시하지 않는다.
- fetch credentials/include, no-store, timeout, abort, JSON 런타임 검증 패턴을 사용한다. 읽기 실패를 빈 배열/기본 설정/로그아웃으로 취급하지 않는다. 401만 세션 재확인으로 연결한다.
- API의 `exercises`를 화면용 `{id,name}`로 투영하고 설정을 `{low:restLowSeconds,medium:restMediumSeconds,high:restHighSeconds}`로 변환한다. 데모의 ≤8 / 9–12 라벨·함수와 서버 확정 구간을 혼용하지 않는다.
- `overload.core.v1`, `loadWorkout`, `createInitialState`, 데모 `useWorkout`은 운영 조회의 입력·fallback·초기 seed로 사용하지 않는다. 기존 demo localStorage를 읽거나 삭제·rename·UUID 연결·서버 전송하지 않는다. 현재 R 단계에 IndexedDB/Outbox를 선행 설치할 필요는 없다.
- 로딩/빈 종목/설정 오류/일시 장애를 구분하고, 브라우저 캐시가 계정 데이터를 영구 저장한다는 표현을 하지 않는다.

수용 테스트: A/B 계정별 분리, 사용자 ID query 거절, nonzero·JS 안전 정수 초과 generation 문자열 round-trip, 사용자 종목 0개, 편집된 설정, 설정 누락, 401/403/500/503, 두 GET 사이 generation 변경, 로그아웃 뒤 늦은 응답 폐기, 기존 UUID·시각·seed 불변, demo 키 미접근. 실제 DB 테스트는 이후 전용 Testcontainers에서만 실행한다.

## 5. 쓰기 도메인 데이터 사전 (D: 구현 전 승인 대상)

아래는 핵심 DTO의 전체 의미 필드다. ID와 영속화는 JPA 문서의 Entity/Repository 표를 참조한다. `Scoped<T>` 응답은 `{userId:UUID,dataGeneration:Counter,data:T}`다. 표의 version/deletedAt은 **미래 스키마**이며 V2에 이미 있다는 의미가 아니다.

### 5.1 계획·설정·일정

| DTO | 필드·타입 | 불변식 |
|---|---|---|
| Exercise | id:UUID, name:string, resourceVersion:Counter, createdAt/updatedAt:Instant, deletedAt:Instant\|null | 이름 UNIQUE 금지. 활성 루틴 참조 시 삭제 거부; 과거 표시명 보존. resourceVersion/tombstone는 D-09 |
| WorkoutSettings | restLowSeconds/restMediumSeconds/restHighSeconds:positive integer, resourceVersion:Counter | 기존 설정 PK·구간 유지; 편집 경쟁 보호는 D-09 |
| Routine | id:UUID, name:string, version:Counter, weightPlacements:WeightPlacement[], cardioPlacements:CardioPlacement[], deletedAt:Instant\|null | 승인된 편집만 적용. 미저장 취소/되돌리기로 배치 ID 재발급 금지 |
| WeightPlacement | id:UUID, exerciseDefinitionId:UUID, position:integer≥0, setCount/minReps/maxReps:Count, initialWeightKg/approvedWeightKg:Decimal, deletedAt:Instant\|null | min≤max, kg≥0, 동일 종목 복수 배치 가능. 루틴 version으로 자식 경쟁 보호 |
| CardioPlacement | id:UUID, position:integer≥0, plan:CardioPlan, deletedAt:Instant\|null | 루틴 집계 소속. weight와 cardio의 각각 순서 배열을 보존하며 혼합 interleave 새 기능을 만들지 않음 |
| WeeklySchedule | resourceVersion:Counter, assignments:{weekday:0..6,routineId:UUID}[] | 요일 중복 없음. 일=0, 휴식은 행 부재; 오늘만 선택은 UI 상태로 원본 일정 수정 안 함 |
| ExerciseMemo | exerciseDefinitionId:UUID, text:string, resourceVersion:Counter, deletedAt:Instant\|null | 종목 정의별 1개, 세션 전용 메모 아님; 삭제도 pull로 전달 |

새 이름은 비공백 문자열, 과거 이름 원값은 snapshot에 보존한다. 이름/메모의 구체 길이·요청 크기는 기술 자원 제한으로 별도 검토하며 임의 길이를 기존 데이터에 소급 적용하지 않는다. WeeklySchedule·memo의 물리 PK 방식도 후속 DDL 검토다.

메모는 행 부재(null)와 저장된 빈 문자열을 구분하고, 단순 빈 입력을 자동 삭제 사건으로 바꾸지 않는다. 최초 memo 생성은 해당 key 미존재를 명시 검증하고, 기존 편집에는 해당 resourceVersion을 요구한다. 일정·메모의 version 보관 위치/초기 생성은 D-09의 스키마 계약이며 가짜 기대 version으로 upsert하지 않는다.

배치 재추가 요청은 `mode:"RESTORE",placementId` 또는 `mode:"NEW",newPlacementId,initialWeightKg,...`를 명시한다. 여러 삭제 후보가 있으면 UUID별 후보 목록을 제시한다. restore는 이전 승인 중량을 그대로 잇고 ProgressChangeEvent를 만들지 않는다. 루틴 삭제는 일정 제거와 원자적이며 진행 세션/과거 승인 사건은 유지한다.

### 5.2 세션·실제 수행·완료 기록

| DTO | 핵심 필드·타입 | 의미 |
|---|---|---|
| WorkoutSession | id:UUID, sourceRoutineId:UUID\|null, routineNameAtStart:string, phase:WARMUP/WORKING/FINISHED, startedAt:Instant, workoutDate:LocalDate, startedTimeZone:string, endedAt:Instant\|null, version/ownershipEpoch:Counter, writerDeviceId:UUID, deletedAt:Instant\|null | 사용자별 세션. 종료·삭제·기기 제어권은 별도 상태; 서버 ACK를 FINISHED로 표현하지 않음 |
| SessionWeightExercise | id:UUID, sourcePlacementId:UUID\|null, exerciseDefinitionId:UUID, nameAtStart:string, addedDuringSession:boolean | 매 세션 별도 UUID; 삭제된 원루틴/정의의 현재 표시명에 의존하지 않음 |
| PerformedSet | id:UUID, sessionExerciseId:UUID, ordinal:Count, weightKg:Decimal, reps:Count, performedAt:Instant, temporaryWeight:boolean, approvalEventId:UUID\|null, deletedAt:Instant\|null | 실제 완료 시 생성. 미완료 repsText는 여기에 저장하지 않음. kg=0 허용; 신규 reps≥1; ordinal 삭제 후 자동 재번호 금지 |
| SessionCardioActivity | id:UUID, sourcePlacementId:UUID\|null, planAtStart:CardioPlan, status:READY/RUNNING/PAUSED/REVIEW/COMPLETED/SKIPPED, startedAt/runSince/pausedAt:Instant\|null, accumulatedMs:DurationMs, result:CardioResult\|null | 시간 전이는 상태 변경 때만 전송. Session version·epoch 적용 |
| CardioResult | timerMs/activeMs:DurationMs, corrected:boolean, distanceKm:Decimal\|null, averageBpm:integer\|null, completedAt:Instant | 자동 원본과 채택 실제 시간 구분. 없는 거리/심박은 null, skip은 result=null |
| CompletedRecord | session:WorkoutSession, weightExercises:SessionWeightExercise[], performedSets:PerformedSet[], cardioActivities:SessionCardioActivity[], summary:Summary, completionAssessment:AssessmentSnapshot, currentAssessment:Assessment | 과거 판정과 정정 후 현재 판정을 분리 |
| Summary | completedSetCount:integer≥0, volumeKg:Decimal, elapsedMs:DurationMs, cardioCompleted/cardioTotal:integer≥0 | 현재 유효 완료 세트의 weight×reps 합; 삭제 수행·미완료 초안·유산소는 웨이트 볼륨 제외 |

기존 완료 세트 중량/반복 정정과 삭제는 실제 수행 행 및 RecordChangeEvent에 반영한다. 최초/종료 계획과 최초 평가를 수정하지 않는다. 종료 후 새 종목 추가는 금지하고, 누락된 기존 종목의 세트는 명시 복구 명령만 허용한다. 시작 후 루틴 편집은 이미 만든 세션 계획을 자동 덮어쓰지 않는다.

sourceRoutineId의 nullable 표기는 과거 출처 참조 보존 방식의 후보이지 루틴 없는 새 운동 시작 기능이 아니다. 생성은 아래 START_WORKOUT의 루틴 기반 경로로 제한한다. 계정 전체의 동시 진행 세션 개수는 이 문서에서 새 UNIQUE/정책으로 제한하지 않는다.

### 5.3 유산소 계획 및 기존 입력 범위

`CardioPlan = {kind:TREADMILL|BIKE|OUTDOOR|STAIRS|OTHER,name:string,targetSeconds:integer,targetDistanceKm:Decimal|null,intensity:{mode:SPEED|PACE,value:Decimal}|null,inclinePercent:Decimal|null,resistance:Decimal|null}`. 기존 데모의 소문자 kind/status와 API enum은 경계 mapper에서 변환한다. 계획 ID는 배치 ID가 가지며 세션 activity ID와 구분한다.

현재 데모 입력 규칙을 유지할 기준안: name 비공백·≤100자, 목표 1..86400초, 거리 0.001..1000km, SPEED 0.1..100km/h, PACE 0.1..120분/km, 경사 0..50%(TREADMILL만), 저항 1..100(BIKE만), 심박 20..300 정수, 정정 시간 1..86400 정수 초. 거리/강도는 TREADMILL/BIKE/OUTDOOR만 가능하며 다른 기구에서는 null이다. 실제 activeMs는 0초 초과·24시간 이하, timerMs는 자동 원본을 그대로 보존한다. decimal scale은 D-01과 함께 정한다.

READY → RUNNING ↔ PAUSED → REVIEW → COMPLETED; REVIEW 취소는 PAUSED, 미완료는 SKIPPED로 정리한다. 한 세션에 RUNNING/REVIEW는 동시에 하나다. 계획 도달을 자동 완료로 바꾸지 않는다. 시간/거리/심박을 계획에서 실적으로 추정하지 않는다. 종료 시 미완료 유산소는 사용자 확인 후 skip한다.

## 6. 네 종류 snapshot의 구조 (D-03)

모두 `snapshotSchemaVersion:1`을 포함한다. JSONB shape version은 DB generation·집계 version과 별개다. 서버의 PerformedSet/유산소 결과 행이 공식 수행 원본이며 snapshot을 두 번째 수정 가능한 수행 DB로 만들지 않는다.

```text
InitialPlanSnapshot {
  snapshotSchemaVersion: 1, sessionId, sourceRoutineId, routineVersionAtStart,
  routineNameAtStart, startedAt, workoutDate, startedTimeZone,
  weightExercises: [{sessionExerciseId, sourcePlacementId, exerciseDefinitionId,
    nameAtStart, position, plannedSetCount, minReps, maxReps, approvedWeightKgAtStart}],
  cardioActivities: [{sessionCardioActivityId, sourcePlacementId, position, plan}]
}
AppliedPlanSnapshot {
  snapshotSchemaVersion: 1, sessionId, sealedAt: Instant|null,
  weightExercises: [{sessionExerciseId, exerciseDefinitionId, sourcePlacementId,
    nameInSession, position, plannedSetCount, minReps, maxReps,
    approvedContext: {weightKg, approvalEventId: UUID|null}, included: boolean}],
  cardioActivities: [{sessionCardioActivityId, position, plan, included: boolean}]
}
AssessmentSnapshot {
  snapshotSchemaVersion: 1, ruleVersion: "task006-v0.3.1/contract-v0.1",
  assessedAt, sessionVersion, overallWeightPlanComplete: boolean,
  exercises: [{sessionExerciseId, eligible: boolean, reasonCodes: string[],
    comparisonContext, benchmarkEvidence: {sessionId, sessionVersion}|null,
    evaluatedPerformedSetIds: UUID[], advice: INCREASE|DECREASE|MAINTAIN|UNAVAILABLE}]
}
ActiveCheckpoint {
  snapshotSchemaVersion: 1, checkpointSequence: Counter, capturedAt,
  currentActivityId: UUID, phase: WARMUP|WORKING,
  workingWeights: [{sessionExerciseId, weightText, mode: TODAY|APPROVED}],
  setDrafts: [{sessionExerciseId, ordinal, repsText}],
  timer: {kind: REST, sessionExerciseId, performedSetId, endAt: Instant}
       | {kind: TRANSITION, sessionExerciseId, performedSetId, since: Instant}|null,
  cardioDrafts: [{activityId, distanceText, bpmText, correctedSecondsText: string|null}]
}
```

위 text schema의 타입을 생략한 필드는 5절의 동명 타입을 따른다. `comparisonContext`는 11절이다. Assessment는 당시 판정·근거 ID를 보존하며 실제 수행의 편집 원본이 아니다.

- Initial은 시작 시 불변. Applied는 운동 중에만 바뀌고 최초 명시 종료에서 seal한다. 추가→삭제로 원래 유효 구성에 돌아갈 수 있으며 최초 구성은 Initial로 검사한다.
- plannedSetCount/ordinal은 값이다. 계획 슬롯별 UUID 테이블은 만들지 않는다. 새 PerformedSet ID는 실제 완료 의도를 만들 때 할당한다.
- AssessmentSnapshot은 최초 종료 시 저장하고 불변이다. 현재 평가는 같은 순수 함수로 별도 계산한다. JSON에 실제 weight/reps 행 전체를 복제해 두 번째 수정 원본을 만들지 않는다.
- ActiveCheckpoint는 복원 보조다. 입력 중 문자열을 보존하지만 이것만으로 세션 생성·종료·실제 완료를 성립시키지 않는다. checkpoint로 performedSets를 생성하지 않는다.
- 단순 종목 이동은 로컬 checkpoint/UI 선택만 바꾼다. 클릭마다 서버 명령·감사 로그를 만들지 않는다. checkpoint 업로드는 실제 변경 명령 동봉 또는 복원용 묶음 저장 후보이며 매초 전송하지 않는다.
- 로컬 checkpoint를 서버 ACK로 덮어쓰지 않는다. checkpointSequence는 같은 제어권 안의 오래된 복원값 판정용이며 version/epoch를 대체하지 않는다.

조회 묶음의 권장 필드명은 `SessionDetail={session,initialPlan:InitialPlanSnapshot,appliedPlan:AppliedPlanSnapshot,completionAssessment:AssessmentSnapshot 또는 null,activeCheckpoint:ActiveCheckpoint 또는 null,weightExercises,performedSets,cardioActivities,currentAssessment}`다. CompletedRecord는 이 구조에 summary를 포함하고 phase=FINISHED·completionAssessment 존재·activeCheckpoint=null인 경우다. DB의 snake_case 컬럼명을 외부 JSON에 그대로 노출하지 않는다.

## 7. 수치·날짜·UUID·세대의 구체안

### 7.1 kg精度 (D-01: 승인 필요)

권장 **NUMERIC(20,6)**: 정수부14자리·소수부6자리, 0..99999999999999.999999kg. 체력 상한이 아니라 정밀도/전송/계산의 기술 범위다. `NUMERIC(10,3)`를 확정된 것으로 승격하지 않는다. 대안은 unconstrained NUMERIC+검증 자릿수로, 미래 확장이 쉽지만 자릿수 정책과 ORM 매핑·자원 제한을 별도로 관리해야 한다.

Java는 문자열에서 BigDecimal 생성, 범위·scale 검증 후 `RoundingMode.UNNECESSARY`로 손실을 거절한다. PostgreSQL은 지정 scale 초과값을 반올림할 수 있으므로 DB에 맡기지 않는다. [공식 NUMERIC 설명](https://www.postgresql.org/docs/18/datatype-numeric.html)

JSON/TS는 문자열이다. 전송 canonical 형식은 `0`, `60`, `62.5`, `0.000001`처럼 지수·부호+·선행0·불필요한 후행0 없는 평문이다. -0/NaN/Infinity/음수 거절. UI draft는 `62.` 같은 입력 중 문자열을 유지하고 유효한 제출 때 canonical화한다. 동등값 `60.000000`은 제출 전 `60`으로 바꾸며, 첫 송신 후에는 바꾸지 않는다. kg 합계·차이·volume도 정확한 decimal 문자열; JS Number를 공식 계산/재전송 원본으로 사용하지 않는다. 표시용 근삿값과 공식값을 분리한다. 서버 volume은 누적 overflow를 피하도록 충분한 NUMERIC/BigDecimal로 계산하고 kg 컬럼 scale만으로 합계를 자르지 않는다.

기존 데모는 임의 소수를 number로 받을 수 있다. 6자리를 넘는 유효 입력을 새 서버가 거절하는 것은 새 기술 제약이므로 승인 전 구현 금지다. 기존 demo 데이터를 반올림/이전하지 않는다.

### 7.2 날짜·발생 시각·타이머 (P + D-10)

- `workoutDate`: 시작 기기가 당시 IANA zone으로 만든 현지 날짜. `startedAt`은 UTC 시점, `startedTimeZone`은 예: Asia/Seoul. 서버가 같은 zone에서 계산한 날짜와 맞는지 검증한다. 늦게 동기화되어도 serverReceivedAt의 날짜로 대체하지 않는다.
- `performedAt`, endedAt, cardio completedAt: 기기 이벤트 시각. `createdAt`, updatedAt, serverReceivedAt, serverCommittedAt: 서버 관리 시각. DB Instant는 TIMESTAMPTZ, LocalDate는 DATE. JSON은 UTC Z 표현을 사용하고 날짜 정밀도와 시간 정밀도를 혼동하지 않는다.
- 클라이언트가 사용하는 millisecond 이벤트를 위해 신규 이벤트 Instant는 millisecond 정밀도를 권장하되, 기존 DB 시각을 읽을 때 microsecond를 잘라 덮어쓰지 않는다. 기존 값의 ISO 출력은 분수초 최대6자리까지 허용한다.
- 봉인되는 신규 명령의 시각은 UTC와 정확히3자리 millisecond(`YYYY-MM-DDTHH:mm:ss.SSSZ`)로 canonical화한다. 기존 행의 읽기 시각 표현과 구분하며, 동일 시각의 서로 다른 문자열 때문에 fingerprint가 달라지지 않게 golden vector로 검증한다.
- 휴식 endAt·전환 since는 절대 앵커, 누적 activeMs/timerMs는 경과 duration이다. 매초 로컬 표시만 갱신하고 잠금/앱 복귀 시 앵커로 복원한다. 종료·완료는 타이머 만료의 자동 부작용이 아니다.
- 잘못된 기기 시계·역행·날짜 불일치는 422 CLOCK_CONTEXT_INVALID로 보류하는 권장안이다. 허용 편차와 정정 UX는 미확정이므로 수신 시각으로 자동 보정하거나 임의 날짜 수정 API를 제공하지 않는다. 자정/여행/DST·늦은 업로드 테스트가 필요하다.

### 7.3 generation·신규 UUID (P + D-02)

dataGeneration은 users의 서버 전용 BIGINT. 가입 현재값은0이며 운동 초기화 때 증가한다. 모든 조회/쓰기·로컬 DB·Outbox는 `(serverUserId,dataGeneration)` 영역이다. read는 서버 현재값, write는 명령 생성 당시 값이 현재값과 같아야 한다. 옛 generation의 명령·cursor·UUID를 새 세대로 옮기지 않는다. 초기화/탈퇴 구현은 이번 API 범위 밖이다.

권장 신규 UUID는 CSPRNG UUID v4. 오프라인에서 Routine/배치/Session/세션 종목/PerformedSet/유산소/operation ID를 생성해 첫 송신 전 고정한다. 기기 UUID도 별도이며 사용자 UUID는 서버가 기존 인증으로 결정한다. 서버가 만드는 seed·승인/정정 사건 ID는 서버 생성이다. [UUID 표준](https://www.rfc-editor.org/rfc/rfc9562)

새 UUID라도 인가는 아니다. 다른 소유자·세대의 ID와 충돌하면 409 ID_UNAVAILABLE 같은 비식별 오류로 거절하고 기존 행을 upsert/소유권 이동하지 않는다. 같은 operation 재시도에서만 기존 성공을 확인한다. 삭제된 ID는 일반 생성으로 재사용하지 않는다. 순서·공식 비교를 UUID 발급 시각에 의존하지 않는다. 기존 V2 UUID는 재발급하거나 v4 조건으로 소급 거절하지 않는다.

신규 Count는 Java/PostgreSQL INTEGER의 양수 1..2147483647 권장안이다. 이는 기존 프론트 Number.MAX_SAFE_INTEGER 범위보다 좁으므로 기술 상한 승인 필요다. 거대한 세트 수를 이유로 수십억 빈 수행 행을 미리 생성하지 않으며, 본 계약의 수치 허용이 무제한 요청 크기를 의미하지 않는다.

## 8. version·ownershipEpoch·기기 제어권 (P + D-06/08)

Routine과 WorkoutSession의 version은 독립이다. 신규 루트 version은 `"0"`, 실제 변경한 루트마다 성공 명령당 +1 권장안이다. 하위 배치·세트·유산소만 바뀌어도 부모 version이 오른다. 같은 값 승인 같은 no-op는 `{changed:false}` 영수증만 남기고 사건·version·변경 feed를 새로 만들지 않는 안이다. 단순 이동·타이머 매초 갱신도 version 증가 사유가 아니다.

세션 생성 때 최초 제어권의 epoch=`"0"`, writerDeviceId를 정한다. 이후 정상/강제 이전 성공 시 version과 epoch를 증가시킨다. 동일 device UUID라는 주장만으로 제어권을 취득하거나 기존 명령을 새 epoch로 재태깅하지 않는다.

- 일반 세션 쓰기: 인증 principal·세대·세션 소유권·expectedVersion·현재 epoch·writerDeviceId와 서버가 부여한 기기 바인딩을 확인한다.
- 정상 이전: 현재 기기의 선행 Outbox ACK와 서버 version 확인 뒤 목표 기기로 명시 이전. `fromDeviceId,toDeviceId,lastAcknowledgedOperationId`를 제출하고 서버가 검증한다. 서버가 브라우저에 미전송 명령이 전혀 없음을 추측하지 않는다.
- 강제 이전: 새 기기가 expectedVersion/epoch와 `acknowledgePossibleUnsyncedData:true`를 제출한다. 서버 최신 상태를 기준으로 명시 승인하되 옛 기기 원본은 삭제하지 않는다. 더 최신 server version이면 다시 차이를 확인하도록 충돌 응답한다.
- 현재 제어권이 없는 기기는 조회만 가능. 옛 epoch의 미적용 쓰기는 409 OWNERSHIP_LOST다. 과거에 이미 적용된 동일 명령의 영수증 확인은 제어권 재부여가 아니다.
- 다중 탭은 IndexedDB/단일 송신 주체 조정을 검토한다. 서버 version/epoch 검증도 유지한다. 브라우저 지원 여부와 서버 바인딩 증명 형식은 D-08의 검증 대상이다.

**미확정 보안 상세:** device 등록/바인딩 증명의 실제 wire 형식·생명주기는 아직 승인되지 않았다. 명령 DTO의 `deviceBindingRef`는 그 계약의 자리이며 단순 UUID를 비밀 인증 증명으로 취급하지 않는다. 기존 로그인 세션·쿠키·CSRF를 바꾸거나 새 인증 체계를 설치하는 권한을 이 문서가 주지 않는다. 이 항목을 해결하기 전 세션 쓰기 구현은 막고, 008-1 읽기는 진행 가능하다.

권장 구현 방향은 서버 등록 device UUID에 별도 기기 소유 증명을 바인딩하고, 세션 제어권 claim을 명시 검증하는 것이다. 증명 운반은 전용 HttpOnly 기기 쿠키 방식과 WebCrypto 키 증명 방식을 비교한다. 전자는 기존 fetch에 실리지만 cookie 수명/계정 전환/CSRF 영향 검토가 필요하고, 후자는 플랫폼 키 보존·복구 구현이 늘어난다. MVP는 전용 쿠키를 우선 검토하되 **추가 보안 설정 승인이 필요한 후보**로만 둔다. deviceBindingRef는 공개 참조이며 쿠키/개인키 자체를 명령·fingerprint·로그에 넣지 않는다. 등록·재발급·응답 유실 계약까지 확정하기 전 관련 endpoint를 임의 구현하지 않는다.

## 9. 명령·fingerprint·멱등 영수증·ACK (D-04)

### 9.1 전송 API 선택 권장안

권장 **POST `/api/v1/workout-commands`** 하나에서 허용된 명령 type만 처리하고, 조회는 리소스 GET으로 제공한다. DTO를 그대로 Entity merge하거나 임의 필드 patch·임의 SQL·여러 명령의 자유 batch를 받지 않는다. 각 type의 도메인 검증·트랜잭션 경계는 별개로 명시한다.

대안은 각 리소스의 POST/PATCH/DELETE에 동일 명령 메타데이터를 나누는 방식이다. 익숙한 URI라는 장점이 있으나 DELETE body의 전달 및 다중 집계 원자적 명령 표현을 별도로 정해야 한다. 통일된 봉인 요청/ACK 처리를 위해 typed POST를 권장하며 **사용자 승인 전 확정 경로로 취급하지 않는다**. 008-1 두 GET에는 영향이 없다.

```text
CommandRequest<T> {
  commandSchemaVersion: 1,
  operationId: UUID,
  dataGeneration: Counter,
  type: 허용된 enum,
  expectedVersions: [{resourceType, resourceId: UUID, version: Counter}],
  sessionControl: {deviceId: UUID, ownershipEpoch: Counter, deviceBindingRef: string}|null,
  occurredAt: Instant,
  payload: T
}
```

`resourceType`은 ROUTINE/WORKOUT_SESSION 및 D-09의 EXERCISE/WORKOUT_SETTINGS/WEEKLY_SCHEDULE/EXERCISE_MEMO다. 설정·일정의 resourceId는 서버 계정 UUID, memo는 종목 정의 UUID를 권장하되 소유권은 principal로 다시 판단한다. 배열의 같은 (type,id) 중복 금지, 정렬은 type·UUID 오름차순이다. 신규 루트 생성에는 해당 루트의 expectedVersion 대신 ID 미존재를 검사하고, 참조하는 기존 루트의 기대값은 필요하다. 세션 일반 쓰기는 sessionControl 필수, 제어권 이전은 별도 명령 규칙을 적용한다.

로컬 명령은 생성 시 cached serverUserId·generation·epoch 또는 권한 선행 명령의 의존성을 함께 보존한다. serverUserId는 로컬 scope이며 요청 owner 권한 필드가 아니다. 선행 ACK로 확정한 expectedVersions를 최초 송신 전에 영속 봉인한다. 그 뒤 payload·occurredAt·예상 version·epoch·ID를 바꾸지 않는다. stale 실패를 같은 operationId의 payload 수정으로 해결하지 않는다.

### 9.2 fingerprint

권장 canonical 규칙: DTO 검증 후 UUID 소문자·Counter/Decimal/UTC 시각의 canonical 표기·명시 null·정렬된 expectedVersions를 요구한다. 객체 중복 key·알 수 없는 필드·형식 위반은 400. 의미 있는 문자열(이름/메모)의 Unicode를 몰래 정규화하지 않는다. 배열은 도메인 순서를 유지한다.

서버 fingerprint는 `SHA-256(UTF8("overload-command-v1\n" + principalUserId + "\n" + JCS(CommandRequest)))`의 소문자64자리 hex 권장안이다. 클라이언트가 fingerprint를 권한 주장으로 제출하지 않으며 서버가 재계산한다. JCS의 수치 처리에 decimal/BIGINT를 맡기지 않도록 이 값들은 문자열이다. CSRF 토큰·쿠키·네트워크 재시도 횟수·서버 수신 시각은 fingerprint에 포함하지 않는다. [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)

여기서 동일 요청은 동일한 canonical 의미다. 공백·객체 key 순서 차이는 같은 fingerprint지만 봉인 이후 클라이언트는 원래 봉인본을 재사용한다. 서버/TS 간 golden vector(한글·null·순서·0kg·큰 Counter·다중 version)를 구현 전에 검증한다. 신규 라이브러리는 이 문서에서 추가하지 않는다.

### 9.3 서버 처리·영수증

1. 인증·CSRF·형식·현재 계정 및 세대를 확인한다. 다른 세대는 과거 영수증을 조회/노출하기 전에 409 DATA_GENERATION_MISMATCH.
2. `(user_id,data_generation,operation_id)`로 영수증/경쟁 예약을 확인한다. 기존 성공과 fingerprint가 같으면 **재적용 없이 저장된 결과**를 반환한다. 다르면 409 OPERATION_ID_REUSED. 당시 요청이 지금의 version/epoch보다 오래됐다는 이유로 이미 적용된 명령을 다시 적용하지 않는다.
3. 새 명령이면 루트 소유권·기대 version·삭제 상태·epoch·참조·숫자·도메인 전이를 검증한다. 적용·사건·변경 version·feed batch·멱등 성공 결과를 하나의 DB 트랜잭션으로 커밋한다.
4. ACK는 커밋 성공 뒤에만 보낸다. SQL/flush/commit 실패 시 관련 행·사건·version·예약도 함께 롤백한다. IN_PROGRESS 예약만 독립 커밋하지 않는다. 기존 unique 실패 트랜잭션에서 무리하게 계속 조회하지 않는다.

MVP 권장 영수증은 **성공/no-op 결과**를 저장한다. 인증/형식/검증 실패는 성공 영수증으로 만들지 않는다. 확정 실패는 로컬 의도와 거절 사유를 보존하고, 사용자가 수정한 의도는 새 operationId다. 실패 영수증까지 영구 기록할 필요와 보존 기간은 운영 검토 사항이다. 최소한 현재 generation의 성공 영수증·tombstone을 자동 단기 만료시켜 중복/부활을 허용하지 않는다.

### 9.4 ACK와 재시도

성공 HTTP는 200. 신규와 재송신 모두 같은 구조를 쓴다.

```text
CommandAck {
  userId: UUID, dataGeneration: Counter, operationId: UUID,
  outcome: APPLIED|REPLAYED, fingerprint: string,
  appliedAt: Instant,
  resultVersions: [{resourceType, resourceId: UUID, version: Counter}],
  changeCursor: string|null,
  result: type별 결과(생성 ID, changed 여부, 승인 사건 ID, 세션 epoch 등)
}
```

REPLAYED는 새 수행·새 승인 사건·새 변경 revision을 만들지 않는다. appliedAt/resultVersions/result는 원래 커밋 결과다. 과거 ACK의 epoch가 현재 제어권을 되돌리지 않는다. no-op는 changeCursor=null 가능하다.

ACK 수신으로 해당 Outbox 명령만 확정하고 선행 의존 version을 해제한다. ACK의 오래된 결과를 더 최신 local draft/cache에 덮어쓰지 않는다. ACK의 changeCursor는 그 명령 위치이며 **아직 읽지 않은 다른 명령을 건너뛰도록 pull cursor를 전진시키면 안 된다**.

응답 유실·일시 네트워크/503는 동일 봉인본 재시도. 401은 동기화 중단 후 같은 계정 인증 확인, generation/epoch/version 충돌은 자동 무한 재시도나 재태깅 금지다. 로컬 원본은 해결 ACK까지 보존한다. HTTP 200만으로 계정/operation/fingerprint 검증을 생략하지 않는다.

## 10. 변경 pull·삭제 전파·다중 집계 원자성 (D-05/06/09)

### 10.1 cursor와 변경 batch

cursor는 opaque 문자열이다. 내부 권장 내용은 `{formatVersion,userScope,dataGeneration,revision}`를 서버가 위변조 방지한 값이다. 클라이언트가 decode/증가/다른 계정에 재사용하지 않는다. cursor는 인증 증명이 아니며 매번 principal과 scope를 검증한다.

서명/HMAC 키의 운영·회전은 후속 설정 계약이며 기존 OTP 비밀키를 재사용하지 않는다. 다른 계정/endpoint/filter의 cursor는 400 CURSOR_INVALID 또는 SYNC_CURSOR_INVALID로 거절하고 타 계정 범위를 details에 노출하지 않는다.

권장 논리 저장소는 `workout_sync_state`(사용자·세대별 committedRevision)와 `workout_change_batches`(revision별 변경 리소스 참조)다. **신규 테이블 후보이며 SQL을 작성하지 않았다.** 시각·UUID·일반 sequence의 최대값만 커서로 삼지 않는다. 먼저 낮은 sequence를 받은 T1이 늦게 커밋하고 T2의 높은 값을 먼저 pull하면 T1을 영구 누락할 수 있기 때문이다.

단순한 MVP 권장안은 운동 쓰기마다 사용자 행을 가장 먼저 FOR UPDATE로 잠그고 세대를 확인한 뒤, 현재 세대 sync-state의 revision을 같은 트랜잭션에서 +1하는 것이다. 잠금은 커밋까지 유지해 같은 사용자의 낮은 revision이 나중에 나타나지 않게 한다. 롤백은 revision/feed도 취소한다. 모든 사용자 전역 락은 없으며 같은 사용자의 쓰기는 직렬화되는 trade-off가 있다. version 기대값 검증은 별도로 유지한다. 사용자별 쓰기량이 늘면 최적화하기 전에 동등한 커밋 순서 보장을 다시 설계한다.

```text
ChangeBatch {
  revision: Counter, operationId: UUID, committedAt: Instant,
  changes: [{resourceType, resourceId: UUID, resourceVersion: Counter,
             changeType: UPSERT|DELETE|RESTORE}]
}
ChangePage {userId, dataGeneration, batches: ChangeBatch[], nextCursor, hasMore:boolean}
```

feed는 **무효화·삭제 통지**이며 과거 resource 내용 전체를 복제하는 두 번째 수행 DB가 아니다. 세트 변경은 소속 세션을 UPSERT로 표시하고 최신 세션 상세를 다시 읽는다. 종목/설정/메모/일정의 개별 version은 D-09가 필요하다. 이벤트는 최소한의 참조만 보존한다.

GET `/api/v1/sync/changes?cursor=...&limit=100` 권장안: batch 기준 기본100·최대500(전송 기술 한계), revision 오름차순. 한 다중 집계 batch를 페이지 중간에 자르지 않는다. 빈 결과도 nextCursor를 반환하며 아직 커밋되지 않은 revision으로 건너뛰지 않는다. 클라이언트는 한 IDB 트랜잭션에서 무효화/삭제 및 필요한 refetch 작업을 영속 기록한 뒤 nextCursor를 저장한다. refetch 실패 중에는 최신 동기화 완료라고 표시하지 않는다.

GET `/api/v1/sync/bootstrap`은 같은 읽기 snapshot의 현재 종목·설정·루틴·일정·메모·진행 세션 및 cursor를 제공하는 후보이며, 누적 완료 이력은 별도 keyset 목록으로 읽는다. bootstrap 로딩 중 발생한 변경은 받은 cursor 뒤 pull로 처리한다. 전체 이력을 한 JSON으로 강제 다운로드하지 않는다. 같은 소유자의 로컬 미동기화 의도는 bootstrap으로 삭제하지 않는다.

무효화 뒤 여러 리소스를 다시 읽는 동안 새 변경이 생길 수 있으므로 클라이언트는 dirty batch를 남긴 채 일괄 재조회하고 version/generation을 확인한다. GET 여러 개가 서버의 과거 단일 트랜잭션 snapshot을 재현한다고 주장하지 않는다. 현재 완료된 버전보다 낮은 결과로 캐시를 되돌리지 않는다. 일관된 묶음 조회가 필요한 화면은 bootstrap/세션 상세 같은 묶음 응답을 쓴다.

### 10.2 논리 삭제와 다른 기기 반영

- 일반 GET은 삭제 항목을 제외하고 상세는 404 RESOURCE_NOT_FOUND. feed의 DELETE는 UUID·종류·삭제 후 version으로 다른 기기 목록/상세 캐시를 제거한다. 그 기기의 미전송 원본은 별도 보존·충돌 상태로 격리한다.
- 루틴 삭제는 루틴 tombstone과 일정 정리를 한 batch로 전달한다. 과거 세션은 삭제하지 않는다. 세션 삭제는 현재 집계·차트·공식 기준에서 제외하지만 과거 승인 중량을 역전하지 않는다.
- 배치의 명시 RESTORE는 같은 ID·승인값 유지, 부모 Routine version 증가·변경 통지. 일반 stale UPSERT/이전 기기 명령은 tombstone을 해제하지 않는다. 종목 정의·완료 세션의 일반 휴지통 복원 UI를 추가하지 않는다.
- cursor 이력 보존 범위를 벗어나면 410 SYNC_CURSOR_EXPIRED와 `bootstrapRequired:true`. 원본을 보존한 bootstrap 후 안전하게 재검토한다. 다른 세대는 409 DATA_GENERATION_MISMATCH, cursor 변조/형식은 400 SYNC_CURSOR_INVALID.
- current generation의 영수증/tombstone/feed를 언제 정리할지는 운영 보존 계약이다. 임의 N일·즉시 파기·무기한 백업을 약속하지 않는다. 전체 reset은 새 generation으로 기존 운동 데이터를 파기하는 별도 승인 기능이다.

### 10.3 여러 집계 변경

| 명령 | 함께 검증·변경할 자원 |
|---|---|
| 루틴 저장+요일 지정 | Routine + WeeklySchedule. 다른 루틴이 차지한 요일은 명시 replace 확인; 충돌 시 전부 실패 |
| 루틴 삭제 | Routine tombstone + WeeklySchedule 정리. 진행/과거 Session 보존 |
| 운동 중 승인 중량 변경 | source Routine + 배치 + 지정된 진행 Session + ProgressChangeEvent + 영수증/feed |
| 루틴 편집기 승인 | Routine + 배치 + ProgressChangeEvent. 기존 데모 의미대로 진행 세션 작업값은 자동 덮어쓰지 않음 |
| 세트 정정·삭제/기존 세트 선택 복구 | WorkoutSession + 실제 수행 + RecordChangeEvent + 현재 평가 관련 무효화 + 영수증/feed |
| 제어권 이전 | WorkoutSession version + epoch + writerDevice + 영수증/feed |

명령에 영향을 주는 기존 자원의 expectedVersions를 모두 보낸다. 서버가 빠진 version을 최신값으로 채워 변경하지 않는다. lock 순서 권장안은 사용자 → 세대 sync-state → Routine(UUID 순) → WorkoutSession(UUID 순) → 하위 행이다. 기존 인증의 잠금 순서를 재작성하지 않고, 운동 tx에서 인증 서비스를 호출하지 않는다.

어느 한 검증·flush·제약·version·commit이 실패하면 전체를 롤백한다. 성공 시 변경 루트별 version은 한 번씩 증가하고 하나의 revision/batch/ACK에 모든 새 version을 담는다. 승인값만 바뀌고 사건이 없거나, 세트만 바뀌고 세션 version이 안 바뀌는 부분 성공은 없다. 최종 ACK version 생성은 JPA 문서의 force increment/flush 검증 게이트를 통과해야 한다.

## 11. 공식 과부하 평가와 단일 과거 세션 선택

### 11.1 이미 확정된 자격·계보 (P)

비교 문맥은 `{routineId,sourcePlacementId,exerciseDefinitionId,plannedSetCount,minReps,maxReps,approvedWeightKg,unit:"KG"}`다. 루틴·종목 이름은 key가 아니다. sessionExerciseId는 세션마다 달라지므로 배치 계보를 그 ID로 묶지 않는다. 승인/현재 비교의 중량과 최초 snapshot 중량을 구분한다.

평가 순서는 최초 원계획 확인 → 현재 유효 실제 수행 수집 → 전체 원계획 웨이트 완료 여부 확인 → 종목별 정상 승인 문맥·동일 세트 수/목표 확인 → 후보 선택이다. 중간 클릭 이력·structureModified flag로 자격을 박탈하지 않는다.

| 사례 | 필수 결과 |
|---|---|
| 계획3 → 추가4 → 추가 유효 수행 삭제 후 정상3 | 다른 조건도 맞으면 공식 후보 가능 |
| 계획3 → 최종 유효2 | 제외; 계획을 사후2로 축소하지 않음 |
| 최초 다른 미완료 웨이트 종목을 제거 | 전체 원계획 완료를 우회할 수 없음 |
| 종목 이동·표시 정렬 | 자격·원계획·승인값 변경 없음 |
| 60 시작, 첫 실제 세트 전62.5 명시 승인, 해당 정상 수행 전부62.5 | 새62.5 문맥 후보 가능; 최초60 snapshot 유지 |
| 60/62.5 혼합 또는 오늘만 중량 | 정규 후보 제외 |
| 유산소 skip | 웨이트 공식 자격을 그 이유로 막지 않음 |
| 정정·삭제된 완료 수행 | 현재 기준 재평가. 최초 종료 판단과 승인 사건은 불변 |

대표 제외 사유 후보: INCOMPLETE_INITIAL_WEIGHT_PLAN, EXTRA_VALID_PERFORMANCE, SET_COUNT_MISMATCH, TEMPORARY_WEIGHT, MIXED_WEIGHT, NO_APPROVED_CONTEXT, COMPARISON_CONTEXT_MISMATCH, SESSION_DELETED. 클라이언트는 이를 사용자 문구로 변환한다. 계획보다 많거나 적은 실제 수행의 **저장 허용**과 공식 자격을 분리한다.

### 11.2 선택 알고리즘 권장안 (D-07: 사용자 승인 필요)

**2026-10-31 후속 확정:** 아래 과거 권장안의 미확정 표현은 [Task 009 현행 보완](task-009-redesign-v1.0.md)의 사용자 확정 사항으로 대체한다. 첫 N개 선택·합계/종료 시각/UUID 순위·AUTO_CLOSED 제외 및 실제 종료 시각의 과거 조건을 적용한다. 정상 문맥의 목표 반복 미달/초과도 후보로 허용하고, 승인 전 수행했다가 삭제한 사실은 보존하되 현재 유효 첫 N개 자격과 구분한다(A·B 사용자 확정). 권고 산식은 데모의 정상 종료 평가를 유지하며 후보 선택과 분리한다. `EXTRA_VALID_PERFORMANCE`나 중간 구조 변경 flag를 근거로 N개 이후 추가 수행 자체를 제외 사유로 되살리지 않는다.

자격을 통과하고 비교 문맥이 동일한 **완료된 단일 과거 세션**만 후보로 삼는다. 현재 세션 자체·미완료·삭제 수행은 제외한다. 과거 여부는 기기 event `endedAt <= 대상 startedAt`을 우선안으로 두고, 현재 중량 검토는 조회 시점의 유효 완료 기록을 사용한다. 늦은 동기화의 serverReceivedAt만으로 과거 순서를 바꾸지 않는다. 시계 이상/동시 진행 세션 경계는 D-10에서 확인한다.

권장 순위는 **유효 reps 합계 내림차순 → endedAt 내림차순 → sessionId UUID 오름차순**이다. 선택한 한 세션에서 ordinal 오름차순의 전체 reps 배열을 그대로 가져온다. `[10,6,6]`과 `[8,8,8]`을 합쳐 `[10,8,8]`로 만들지 않는다. 합계 동률이면 최근 세션을 선택하는 이유는 현재 수행에 가까운 배열을 재사용하면서 결정성을 갖기 위해서다. 대안은 동률에 이전 기준을 계속 유지하는 것으로, 데모의 `>` 갱신 동작에 더 가깝다. 어느 쪽도 Task 006의 확정된 동률 정책은 아니다.

`Assessment = {eligible,reasonCodes,comparisonContext,actualReps,benchmark:{sessionId,sessionVersion,reps}|null,latestComparable:{sessionId,sessionVersion,reps}|null,advice}`를 응답용으로 계산한다. actualReps/benchmark 배열을 JSONB의 공식 수행 원본으로 저장하지 않는다. 현재 record DTO는 이 응답을 실시간 평가 결과로 포함할 수 있다.

기존 권고 의미를 유지할 권장안: 정상 문맥의 전체 반복이 목표 상한 이상이면 INCREASE, 한 세트라도 하한 미달이면 DECREASE, 그 밖에는 MAINTAIN. 안내만 하며 승인 중량을 자동 수정하지 않는다. **하한 미달/상한 초과 기록도 정규 문맥의 비교 후보에 포함하는지**, 총합 비교가 공식 최고 기준의 충분한 순위인지, 추가 유효 수행이 타 종목 자격에 미치는 정확한 범위는 Task 006 문구와 데모만으로 완전히 확정하지 않고 아래 사용자 검토 대상으로 남긴다.

미확정 경계의 권장안은 실제 반복이 목표 밖이어도 양수이고 다른 정상 문맥을 충족하면 비교 가능한 수행으로 남기는 것이다. 데모가 실제 목표 미달로 감량을 검토하고 상한 이상으로 증량을 안내하기 때문이다. 다른 웨이트 종목의 추가 유효 수행은 전체 원계획 검사의 엄격도를 명시하기 위해 세션 전체 공식 후보에서 제외하는 안을 우선 제시하되, 종목별 제외안과 UX 영향이 달라 사용자 승인 없이 선택하지 않는다. 승인 전에 확정된 필수 사례의 자격을 바꾸지 않는다.

완료 전의 권고는 PROVISIONAL이며 공식 기준 등록은 아니다. 현재 평가 응답에 `status:PROVISIONAL|FINAL`을 포함하는 안이다. 최초 명시 종료 후 FINAL 판정만 완료 당시 snapshot으로 보존하고 새 과거 후보로 사용한다.

시작 시 사전 입력은 그때의 로컬/서버 확인 기준을 사용하고, 나중에 업로드된 과거 기록으로 이미 입력한 초안을 자동 덮어쓰지 않는다. 종료 당시 AssessmentSnapshot은 최초 판단으로 고정한다. 사후 정정 뒤 currentAssessment·현재 성장 차트만 새로 계산한다. 공식 PR/추정1RM·디로딩·새 세트 유형은 추가하지 않는다.

**공식 자격 관련 남은 제품 경계:** 명시 승인 전에 수행했다가 삭제한 세트가 있는 경우의 승인 시점 판단, 다른 종목의 추가 유효 수행이 있는 경우 자격 적용 범위, 목표 미달·초과의 기준 후보 자격은 구현 전에 사용자에게 필요한 부분만 확인한다. 자동으로 전 세션 제외하거나 허용하는 정책을 새로 만들지 않는다.

## 12. REST API 목록·주요 DTO·오류

### 12.1 읽기 API

R은 008-1 구현 기준안, 나머지는 D-04~10 승인 후의 후보다. 모든 요청은 principal owner 범위이며 다른 사용자 ID를 query로 받지 않는다. 아래 공통 E 오류는 12.3에 정의한다.

| Method / Path | 주요 요청 → 응답 | 주요 오류 |
|---|---|---|
| GET `/api/v1/exercises` (R) | 없음 → 2절 ExercisesResponse | E + 2절 |
| GET `/api/v1/workout-settings` (R) | 없음 → 3절 SettingsResponse | E + WORKOUT_SETTINGS_MISSING |
| GET `/api/v1/routines` | cursor?,limit? → Scoped<{items:RoutineSummary[],nextCursor:null\|string}> | E, CURSOR_INVALID |
| GET `/api/v1/routines/{id}` | UUID → Scoped<Routine> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/routines/{id}/placement-options?exerciseDefinitionId=...` | 같은 정의의 후보 식별자 → Scoped<{active:WeightPlacement[],restorable:WeightPlacement[]}> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/weekly-schedule` | 없음 → Scoped<WeeklySchedule> | E |
| GET `/api/v1/exercises/{id}/memo` | UUID → Scoped<ExerciseMemo\|null> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/workout-sessions` | fromDate?,toDate?,cursor?,limit? → Scoped<{items:SessionSummary[],nextCursor:null\|string}> | E, CURSOR_INVALID |
| GET `/api/v1/workout-sessions/{id}` | UUID → Scoped<SessionDetail 또는 CompletedRecord> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/workout-sessions/{id}/assessment` | UUID → Scoped<{completionAssessment:null\|AssessmentSnapshot,currentAssessment:Assessment}> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/routines/{id}/weight-placements/{placementId}/progress` | UUIDs → Scoped<{approvedWeightKg,benchmark,latestComparable,advice}> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/progress-change-events` | routineId?,placementId?,cursor?,limit? → Scoped<{items:ProgressChangeEvent[],nextCursor}> | E, CURSOR_INVALID |
| GET `/api/v1/workout-sessions/{id}/record-change-events` | cursor?,limit? → Scoped<{items:RecordChangeEvent[],nextCursor}> | E, RESOURCE_NOT_FOUND |
| GET `/api/v1/sync/bootstrap` | 없음 → 10.1절 BootstrapResponse | E |
| GET `/api/v1/sync/changes` | cursor,limit? → 10.1절 ChangePage | E, SYNC_CURSOR_INVALID/EXPIRED, DATA_GENERATION_MISMATCH |

일반 목록의 권장 limit는 기본50·최대100, keyset 정렬이다. 세션은 workoutDate DESC, startedAt DESC, id DESC, 사건은 서버 변경 시각 DESC·id DESC를 우선안으로 한다. 커서는 endpoint/filter/generation에 바인딩한다. 늦게 업로드된 과거 기록으로 페이지가 바뀔 수 있으므로 sync 무효화와 재조회를 제공하며 HTTP 페이지 사이의 불변 snapshot을 보장하지 않는다.

세션 목록은 삭제되지 않은 FINISHED 이력을 대상으로 한다. 진행 세션은 bootstrap 또는 이미 아는 ID의 상세 GET에서 조회한다. fromDate/toDate는 운동 현지 날짜의 포함 범위이며 역전 범위는 400이다.

`RoutineSummary={id,name,version,weightPlacementCount,cardioPlacementCount}`. `SessionSummary={id,routineNameAtStart,workoutDate,startedAt,endedAt,phase,version,summary}`. `ProgressChangeEvent={id,routineId,placementId,relatedSessionId:null|UUID,previousApprovedWeightKg,nextApprovedWeightKg,source:ROUTINE_EDITOR|WORKOUT,occurredAt,serverCommittedAt,routineNameAtChange,exerciseNameAtChange}`. `RecordChangeEvent={id,sessionId,sessionExerciseId:null|UUID,performedSetId:null|UUID,changeType,before,after,serverCommittedAt,operationId}`; before/after는 해당 실제 수행 변경 필드로 제한하고 인증/세션 비밀은 포함하지 않는다.

### 12.2 쓰기 API와 허용 명령 DTO

공통 Method/Path는 **POST `/api/v1/workout-commands`**, 요청은 9.1절 CommandRequest<T>, 성공은 9.4절 CommandAck다. 아래 각 행이 별도의 허용 명령이며 명시된 주요 payload 외에 owner·서버 시각·결과 version을 임의 지정할 수 없다. 표의 모든 명령은 E 및 DATA_GENERATION_MISMATCH/VERSION_CONFLICT/OPERATION_ID_REUSED가 가능하다.

| type | payload 주요 필드 | 기대 집계·추가 오류 |
|---|---|---|
| CREATE_EXERCISE | id,name | ID 미존재; ID_UNAVAILABLE |
| RENAME_EXERCISE | exerciseId,name | Exercise; RESOURCE_NOT_FOUND |
| DELETE_EXERCISE | exerciseId | Exercise 및 활성 참조 검사; EXERCISE_IN_USE(details:사용 중인 내 routine IDs/names) |
| UPDATE_WORKOUT_SETTINGS | restLowSeconds,restMediumSeconds,restHighSeconds | Settings; WORKOUT_SETTINGS_MISSING |
| UPSERT_ROUTINE | routineId,name,weightPlacements,cardioPlacements,optional scheduleAssignments,replaceScheduleConflicts:boolean | 기존 Routine·일정 포함 시 WeeklySchedule; SCHEDULE_CONFLICT |
| DELETE_ROUTINE | routineId | Routine + WeeklySchedule; RESOURCE_NOT_FOUND |
| REMOVE_WEIGHT_PLACEMENT / REMOVE_CARDIO_PLACEMENT | routineId,placementId | Routine; RESOURCE_NOT_FOUND |
| RESTORE_WEIGHT_PLACEMENT / RESTORE_CARDIO_PLACEMENT | routineId,placementId,position | Routine; RESTORE_TARGET_INVALID, ACTIVE_ORDER_CONFLICT |
| APPROVE_WEIGHT | routineId,placementId,newWeightKg,source,sessionId:null\|UUID | Routine + source=WORKOUT이면 Session/control; APPROVAL_TARGET_INACTIVE |
| REPLACE_WEEKLY_SCHEDULE | assignments,replaceConflicts:boolean | WeeklySchedule 및 참조 Routine versions; SCHEDULE_CONFLICT |
| SET_EXERCISE_MEMO / DELETE_EXERCISE_MEMO | exerciseId,text(SET만) | ExerciseMemo/정의 검증; RESOURCE_NOT_FOUND |
| START_WORKOUT | sessionId,routineId,routineVersionAtStart,initialPlanCandidate,startedAt,startedTimeZone,workoutDate,deviceId | 원본 Routine 기대값·새 ID; PLAN_SOURCE_CONFLICT,CLOCK_CONTEXT_INVALID |
| BEGIN_WORKOUT | sessionId | Session/control; INVALID_STATE_TRANSITION |
| CHANGE_SESSION_PLAN | sessionId,actions(세트 수/종목 추가·제외·교체의 명시 동작 배열) | Session/control·새 정의 검증; SESSION_FINISHED,PERFORMED_EXERCISE_IN_USE |
| SET_TODAY_WEIGHT | sessionId,sessionExerciseId,weightKg | Session/control; SESSION_FINISHED |
| COMPLETE_SET | sessionId,sessionExerciseId,id,ordinal,weightKg,reps,performedAt,temporaryWeight,approvalEventId:null\|UUID | Session/control; ACTIVE_ORDINAL_CONFLICT,APPROVAL_CONTEXT_INVALID |
| UNDO_SET | sessionId,performedSetId | 진행 Session/control; SESSION_FINISHED |
| CORRECT_PERFORMED_SET | sessionId,performedSetId,weightKg,reps | Session/control; PLAN_IMMUTABLE,RESOURCE_DELETED |
| DELETE_PERFORMED_SET | sessionId,performedSetId | Session/control; RESOURCE_NOT_FOUND |
| CARDIO_TRANSITION | sessionId,activityId,action:START\|PAUSE\|REVIEW\|CANCEL_REVIEW\|SKIP,eventAt,elapsedEvidence | Session/control; CARDIO_ACTIVITY_BUSY,INVALID_STATE_TRANSITION |
| COMPLETE_CARDIO | sessionId,activityId,timerMs,activeMs,corrected,distanceKm,averageBpm,completedAt | Session/control; INVALID_STATE_TRANSITION |
| FINISH_WORKOUT | sessionId,endedAt,confirmPartial:boolean,confirmSkipPendingCardio:boolean | Session/control; INCOMPLETE_WORKOUT_CONFIRMATION_REQUIRED |
| DELETE_WORKOUT_SESSION | sessionId | Session/control; RESOURCE_NOT_FOUND |
| SAVE_ACTIVE_CHECKPOINT | sessionId,checkpoint | Session/control; SESSION_FINISHED,CHECKPOINT_STALE |
| TRANSFER_CONTROL | sessionId,fromDeviceId,toDeviceId,lastAcknowledgedOperationId | Session/기존 control; TRANSFER_NOT_READY |
| FORCE_TRANSFER_CONTROL | sessionId,toDeviceId,acknowledgePossibleUnsyncedData:true | Session/명시 takeover; OWNERSHIP_LOST,VERSION_CONFLICT |
| RECOVER_EXISTING_EXERCISE_SETS | sessionId,sessionExerciseId,selections[{action:ADD_MISSING 또는 CORRECT 또는 RESTORE_DELETED,performedSetId,ordinal,weightKg,reps,performedAt}],confirm:true | 현재 Session/control; NEW_EXERCISE_AFTER_FINISH,ACTIVE_ORDINAL_CONFLICT |

WRITE 응답 result는 생성된 ID·changed·승인/정정 사건 ID 등 해당 명령 결과만 포함한다. 생성 session은 `{sessionId,version,ownershipEpoch,writerDeviceId}`, 승인은 `{placementId,approvedWeightKg,progressChangeEventId:null|UUID}`, 종료는 `{sessionId,phase:"FINISHED",completionAssessment}`를 반환하는 안이다. 나머지 최신 화면은 ACK의 version을 기준으로 필요한 GET을 수행한다.

세션 생성의 initialPlanCandidate는 오프라인 시작 의도를 위한 것이며 신뢰된 서버 원본이 아니다. 서버는 source routine/version·출처 UUID·승인 문맥을 검증하고 서버 값으로 snapshot을 확정한다. source version이 오프라인 동안 바뀐 경우 현재 루틴으로 조용히 치환하지 않고 PLAN_SOURCE_CONFLICT로 원본을 보존한다. 과거 루틴 version을 확인/복구하는 구체 절차는 D-10에서 확정해야 한다. 이 문서가 루틴 없는 임의 운동 시작 기능을 추가하지 않는다.

CHANGE_SESSION_PLAN은 순서 이동 클릭 로그가 아니다. 완료 수행이 있는 종목 제외는 명시 취소 전 차단하고, 종료 뒤에는 계획/목표/순서/새 종목 추가를 거절한다. Applied만 바꾸며 Initial은 유지한다. elapsedEvidence는 기기 활동 시간·상태 앵커의 검증 자료이며 수신 지연을 운동 시간으로 추정하지 않는다. 상세 차분·시계 이상은 D-10 테스트가 필요하다.

RECOVER는 현재 권한·version의 새 operation으로 기존 세션 종목의 선택된 수행만 반영한다. tombstone 해제는 RESTORE_DELETED+명시 선택에서만 가능하다. ordinal 충돌은 CORRECT 대상으로 사용자가 고르며 자동 중복 합산하지 않는다. 독립 공식 세션을 자동 생성하지 않는다.

008-1의 R 응답에는 version이 없다. D-09 승인 후 편집 단계에서 resourceVersion 등을 추가하는 별도 응답 확장이 필요하며 R 클라이언트는 알 수 없는 추가 필드를 안전하게 무시한다. 초기 읽기 응답에 가짜 version=0을 채우지 않는다. export/reset/탈퇴·인증 API는 이 명령 endpoint에 섞지 않고 기존/후속 별도 범위를 유지한다.

### 12.3 오류 계약

공통 E: 400 INVALID_REQUEST, 401 AUTHENTICATION_REQUIRED, 403 CSRF_INVALID/ACCESS_DENIED(기존 보안 응답), 404 RESOURCE_NOT_FOUND(타 계정 ID도 동일), 503 WORKOUT_DATA_UNAVAILABLE, 500 WORKOUT_DATA_READ_FAILED(읽기)/WORKOUT_COMMAND_FAILED(쓰기). 내부 SQL·키·다른 사용자 정보는 details에 없다.

| HTTP | code | 주요 의미·클라이언트 처리 |
|---|---|---|
| 400 | INVALID_REQUEST, SYNC_CURSOR_INVALID, CURSOR_INVALID | 형식/미지원 필드. 자동 재전송 금지 |
| 409 | DATA_GENERATION_MISMATCH | 같은 계정의 세대 변경. old queue 격리, 자동 이관 금지 |
| 409 | VERSION_CONFLICT | 현재 version 재조회·차이 확인, 새 의도/operation으로 해결 |
| 409 | OWNERSHIP_LOST, TRANSFER_NOT_READY | 조회만 가능, 명시 이전/복구 경로 |
| 409 | OPERATION_ID_REUSED, ID_UNAVAILABLE | 명령 ID 재사용 또는 도메인 ID 충돌. 덮어쓰기 금지 |
| 409 | RESOURCE_DELETED, EXERCISE_IN_USE, APPROVAL_TARGET_INACTIVE | 삭제/활성 참조·승인 상태. 자동 복원 금지 |
| 409 | ACTIVE_ORDER_CONFLICT, ACTIVE_ORDINAL_CONFLICT, SCHEDULE_CONFLICT | 순서·세트·요일 충돌; 명시 선택 필요 |
| 409 | PLAN_SOURCE_CONFLICT, CHECKPOINT_STALE | 과거 계획 확인/최신 복원값 필요. 원본 보존 |
| 410 | SYNC_CURSOR_EXPIRED | bootstrap 필요; 미동기화 원본은 유지 |
| 422 | VALIDATION_FAILED, CLOCK_CONTEXT_INVALID | 숫자·목표·시각 의미 오류, fieldErrors 제공 가능 |
| 422 | SESSION_FINISHED, PLAN_IMMUTABLE, NEW_EXERCISE_AFTER_FINISH | 허용되지 않은 사후 변경 |
| 422 | INVALID_STATE_TRANSITION, CARDIO_ACTIVITY_BUSY, PERFORMED_EXERCISE_IN_USE | 수행·활동 상태 규칙 위반 |
| 422 | APPROVAL_CONTEXT_INVALID, RESTORE_TARGET_INVALID, INCOMPLETE_WORKOUT_CONFIRMATION_REQUIRED | 승인·복원·명시 확인 조건 실패 |

쓰기 확장 ErrorResponse 후보는 `{code:string,operationId?:UUID,fieldErrors?:[{field,code}],details?:{...허용된 own-resource 값}}`다. 현재 own-resource version/새 generation·충돌하는 내 루틴 목록만 필요 시 details에 제공한다. 오류 요청 원문·이메일·SQL·foreign ID 존재 여부를 복사하지 않는다. R 단계는 code만으로 충분하며 기존 auth 오류 구조를 바꾸지 않는다. 500/503 또는 응답 유실은 커밋 여부가 불확실할 수 있어 같은 봉인 명령으로 확인한다.

## 13. 구현 전 승인 항목·검증 순서

**008-1에 필요한 계약:** 2~4절 R-01~03의 정확한 필드·Counter 문자열·빈 목록/설정 누락 오류·일관된 generation·정렬·데모 분리. 신규 domain 테이블, 중량 정밀도, 공식 순위, pull cursor, 기기 등록을 먼저 구현할 필요는 없다. 다음 별도 승인 후 JPA 문서 TX-01~08의 실제 혼용 검증부터 실행하며, 통과 뒤 R API를 구현한다.

**쓰기 전에 확정할 계약:** D-01/02의 정밀도·ID·기술 상한, D-04/05/06/09의 명령·fingerprint·영수증·보조 version·commit-ordered feed·원자성. 이후 세션 쓰기 전 D-03/08/10, 공식 평가 전 D-07의 새 제품 경계를 확인한다. 운동 데이터의 reset/탈퇴/운영 보존 기간은 별도 후속 승인 범위다.

필수 후속 테스트는 읽기 수용 기준 외에 decimal 경계·무반올림, 큰 Counter 왕복, snapshot 불변·3→4→3, ID 재시도/타 소유자 충돌, ACK 유실 재송신, 동일 key 다른 fingerprint, 자식 변경 부모 version, 세대 변경과 쓰기 경합, 다중 집계 전체 롤백, 낮은 revision 지연 커밋 누락 방지, tombstone/pull·cursor 만료·다른 계정 cursor, 강제 이전 구 epoch 및 선택 복구, 한 과거 세션 선택 동률·사후 정정 재평가다. 이 문서 작성에서는 실행하지 않았다.

## 14. 이번 변경·검증 기록

- 승인된 JPA 문서 원문을 검토했고 예상 외 파일 변경·비밀값 패턴을 발견하지 않았다. 해당 문서 한 개만 지정 메시지로 로컬 커밋했다. JPA 문서를 불필요하게 수정하지 않았다.
- 새 문서는 현재 V1~V8, principal, 운영 seed·휴식 구간, React 모델과 대조했다. SQL migration 파일은 만들지 않았다. 개발 PostgreSQL에 접속하지 않았다.
- 공식 참고는 NUMERIC·isolation·JCS·UUID의 기술적 근거다. 공식 문서가 Overload의 새 정밀도/평가/보존 정책을 승인하는 것은 아니다.
- 새 문서 공백·로컬 링크·JSON 예시·비밀값 패턴·변경 범위를 검사한다. 제품 테스트/빌드/서버·이메일·OAuth는 실행하지 않는다. 따라서 새 테스트 통과 수치·런타임 호환성 성공을 주장하지 않는다.
- 작성 당시에는 새 계약 문서를 stage/commit/push하지 않았다. 후속 사용자의 문서 보관 요청에 따라 이 파일만 로컬 커밋하며 Push하지 않는다. R-01~R-03 및 D-01~D-10의 상태는 유지하고, 구현 및 JPA/JDBC 통합 검증은 다음 별도 승인까지 수행하지 않는다.
