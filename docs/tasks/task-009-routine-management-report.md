# Task 009 — 루틴 관리 API 구현 보고서

- 일자: 2026-10-31 (Asia/Seoul).
- 시작 Backend: main / 0b2aa6f6310b59459bd7d12dc4b51759cad597cc, clean.
- Frontend: main / 642dcaa2a883ffe8e4b4666ee2603c54de086737, clean 유지. 변경·빌드하지 않았다.
- 승인 범위: 웨이트·유산소 루틴 관리, 명시적 배치 복원, 중량 승인 사건, V11 및 격리 PostgreSQL 전체 회귀.
- 결과: **구현 완료 / 전체 build 성공 / 총638개 PASS / 미커밋·Push 없음 / 개인 overload_dev V11 미적용**.

## 구현 및 API 계약

정확한 요청·응답·수치·오류·중복·버전 계약은 [루틴 관리 API](../api/routine-management.md)에 기록했다.

| 메서드·경로 | 기능 |
|---|---|
| POST /api/v1/routines | 고정 UUID로 생성, 201·Location·version0 |
| GET /api/v1/routines | 현재 세대 비삭제 루틴 목록 |
| GET /api/v1/routines/{id} | 활성 배치·계획·최초/승인 중량 상세 |
| PUT /api/v1/routines/{id} | 필수 전체 구성·명시 승인 원자 저장 |
| DELETE /api/v1/routines/{id} | 기대 버전 검사 및 부모 논리 삭제 |
| GET /api/v1/routines/{id}/removed-placements | 명시 복원 후보, 삭제된 웨이트·유산소 배치 |

같은 이름의 루틴·종목과 같은 종목의 복수 배치를 허용한다. 이름은 기존 종목과 같은 공백·Unicode 보존 정책이다.
필수 배열 누락/null은400이며 빈 배열만 명시 제거 의미를 갖는다. 최종 배치는 최소1개다. 배열 순서가 계획 순서다.
NEW/EXISTING/RESTORE는 이 루틴 DTO의 배치 생명주기만 나타낸다. 범용 명령·IntentStamp·fieldBases·영수증/feed·이벤트 엔진·오프라인 대기열은 없다.
최초 중량은 신규 배치에만 입력하고 이후 수정 요청에서 거부하며 JPA 컬럼도 updatable=false다.
현재 승인값은 weightApprovals로만 변경한다. 복원만으로 승인 변경·사건을 생성하지 않는다. 명시 승인에서도 동일 값이면 사건이 없다.
승인 사건은 서버가 읽은 이전 값·다음 값·당시 이름·사용자 발생 시각·서버 기록 시각·ROUTINE_EDITOR 출처를 보존한다.
조회·승인·복원 참조는 소유자·세대·루틴 범위를 확인한다. 다른 계정의 존재 정보를 반환하지 않는다.

## 구현 파일

신규 제품 파일은 src/main/java/com/overload/overload_server/routine/의 다음11개다.

- RoutineController.java: 6개 HTTP 경로, 기존 principal·no-store, 엄격한 경로/query/body 처리.
- RoutineService.java: 단일 운동 트랜잭션, 전체 구성 diff·순서 교환·복원·승인·부모 버전.
- RoutineRepository.java: 소유자/세대/루틴 범위 JPA 조회·잠금·persist, 같은 연결의 users 공유 잠금.
- RoutineRequests.java: 해당 API만 사용하는 strict JSON 파서와 요청 record. 기존 전역 JSON 설정 불변.
- RoutineResponses.java: Entity와 분리된 DTO, decimal·counter 문자열 응답.
- RoutineFailure.java / RoutineErrors.java: 좁은 적용 범위의 code-only 도메인/저장소 오류 분류.
- Routine.java / RoutineWeightPlacement.java / RoutineCardioPlacement.java / ProgressChangeEvent.java: 네 Entity, 출처 UUID·중량 계보·불변 사건.

기존 인증·계정·운동 종목·설정 Controller/Service/Entity/Repository·트랜잭션 관리자 제품 코드는 수정하지 않았다.
새로운 주요 의존성, 빌드 설정, 런타임 환경 설정도 추가하지 않았다.

## DB 및 동시성

[V11__create_routine_management.sql](../../src/main/resources/db/migration/V11__create_routine_management.sql)은 routines, routine_weight_placements, routine_cardio_placements, progress_change_events 네 테이블만 추가한다.
V1~V10 원문은 변경하지 않았다. 기존 행 변환·삭제·재시드는 없다.

- 사용자·세대·루틴·종목의 복합 FK, 배치 UUID PK, 계획 정수·중량·유산소 범위 CHECK.
- 활성 배치의 루틴+순서 부분 UNIQUE. 같은 루틴+종목 UNIQUE는 없음.
- 승인 사건은 소유자·세대·루틴·웨이트 배치를 함께 참조하며 기존 기록으로 향하는 삭제 cascade는 없음.
- NUMERIC(20,6)·BigDecimal·JSON 문자열. API가 소수6자리 초과를 저장 전에 거부하고 반올림하지 않음.
- 계획 수 INTEGER 전체 양수 범위. 수치 크기에 비례한 예정 세트 객체 생성/순회 없음.

쓰기 잠금 순서는 users FOR SHARE → routine FOR UPDATE → 종목 UUID 순 공유 잠금이다.
루틴 version은 서비스가 부모 행 잠금 아래 정확히 한 번 증가시킨다. 루틴 Entity에 @Version을 중복 적용하거나 강제 증가와 dirty checking 증가를 혼합하지 않는다. 기존 종목·설정 @Version은 불변이다.
동시 PUT의 동일 기대 버전은 한 요청만 성공한다. 실제 내용이 같아도 오래된 버전은409이고 현재 버전의 무변경은 version/updated_at/사건 불변이다.
순서 교환은 충돌 없는 양수 임시 위치 flush → 최종 위치 저장을 같은 트랜잭션에서 수행한다. 배치 복원·삽입도 부분 UNIQUE와 양립한다.
승인 저장 실패 시 앞서 flush된 이름·순서·구성까지 전체 롤백한다.

루틴 삭제는 부모 tombstone만 설정하며 자식·승인 사건과 출처 ID를 보존한다. 삭제 부모에는 신규 편집·승인이 불가능하다.
세션·InitialPlan API나 테이블은 구현하지 않았다. 향후 START는 같은 잠금 순서와 routine FOR SHARE 및 REPEATABLE_READ 쓰기 트랜잭션에서 전체 계획 복사·세션 생성을 원자 처리해야 한다. 해당 경합의 실제 세션 통합 검증은 후속이다.

## 검증

Temurin25.0.4.1·기존 Gradle Wrapper·기존 Testcontainers PostgreSQL18.6을 사용했다. 테스트 실행 프로세스에서 외부 SPRING/OVERLOAD/OTP/GOOGLE/RESEND/SERVER 설정을 제거했다.
테스트 local overlay는 개발 .env를 import하지 않으며 각 fixture가 격리 DB URL·합성 비밀값을 전달한다. 신규 fixture는 DB 이름과 매핑 포트가5432/5433이 아닌 것도 확인한다. 실제 Google·Resend·개인 DB를 사용하지 않았다.

실행 명령:

```powershell
.\gradlew.bat --offline --no-daemon compileJava compileTestJava
.\gradlew.bat --offline --no-daemon test integrationTest --tests '*Routine*' --tests '*V11Migration*' --tests '*V10Migration*'
.\gradlew.bat --offline --no-daemon build --rerun-tasks
```

최종 build는 **4분45초, BUILD SUCCESSFUL, 9개 task 모두 실제 실행**이다. 전체 XML 집계:

| 범위 | tests | failures / errors / skipped |
|---|---:|---|
| 단위·컨텍스트·기존 Request ID | 170 | 0 / 0 / 0 |
| PostgreSQL 전체 회귀 | 468 | 0 / 0 / 0 |
| 전체 | **638** | **0 / 0 / 0** |

신규83개: RoutineRequestsTests58, RoutineIntegrationTests23, V11MigrationIntegrationTests2. 기존555개 회귀도 유지했다.

주요 실제 검증:

- 동명 루틴·동일 종목 복수 배치, 별도 UUID·최초/승인 중량.
- 웨이트·유산소 순서 교환·제거·선택 ID 복원, 승인 계보·최초값 보존, 복원만으로 사건 없음.
- 최초/승인값 직접 주입 거부, 누락 필드·타입·수치·중복 JSON 키 검증.
- 승인 변경+여러 자식 수정에도 부모 버전1 증가, 동일 내용은 domain DML0 및 전체 행·시각 불변.
- 같은 고정 ID의 동시 POST는201/409, 루틴 한 건만 생성.
- 동시 PUT 및 PUT/DELETE를 요청 장벽으로 시작하고 pg_stat_activity의 실제 Lock 대기를 확인. 한 버전만 적용되며 승인 사건 중복 없음.
- 승인 사건 INSERT 직전 격리 장애 주입 후 이미 flush된 루틴·배치까지 전체 원상 복원.
- 조회 도중 독립 트랜잭션이 부모·자식을 함께 변경해도 같은 응답은 기존 전체 snapshot 유지, 다음 조회에서 새 상태 반환.
- 루틴·종목 이름 변경 이후에도 승인 사건의 이름·전후 값 불변.
- 비로그인·CSRF·소유권·다른 루틴 참조·세대 불일치·큰 generation·버전 overflow.
- INTEGER 최대 계획 수와 중량 정밀도 한계의 실제 HTTP 왕복, 예정 세트 생성 없음.
- V10→V11 업그레이드에서 모든 기존 테이블 행·Flyway 이력 보존, 추가 테이블 정확히4개, 실제 PostgreSQL FK/CHECK/부분 UNIQUE.
- 기존 Google/OTP/쿠키 세션/CSRF/Request ID/JPA-JDBC/종목·설정 GET 및 온라인 쓰기 전체 회귀.

첫 대상 실행에서 테스트의 AssertJ 제네릭 추론 때문에 compileTestJava가 실패했다. 기대 목록 비교로 수정한 뒤 대상 실행과 전체 build가 성공했다. 제품 정책·검사를 완화하거나 테스트를 비활성화하지 않았다.
기존 V9/V10 테스트는 역사적 migration target 검증을 유지했다. 과거 V9 가입 seed는 미래 Entity 검증 없이 실제 JDBC 프로비저닝으로 검증하고, 현재 전체 Entity 부팅은 V11에서 검사하도록 경계를 정리했다.
최신 스키마의 테이블 목록·이력 수11·Entity 수6에 대한 기존 검증과 DB 없는 컨텍스트 mock 배선도 갱신했다.

로그: build/task-009-routine-targeted.log, build/task-009-routine-full-build.log. XML: build/test-results/test 및 integrationTest. 빌드 산출물은 Git에 포함하지 않는다.
최종 git diff --check 통과. 기존 unchecked/CDS 안내 외 미해결 컴파일·테스트 오류 없음.

## 종료 상태 및 제한

검증 JVM·Gradle daemon·테스트 컨테이너가 남지 않은 것을 확인했다. 기존 실행 컨테이너5개는 시작 시와 같은 ID로 유지됐다. 사용자가 시작한 프로세스를 종료하지 않았다.
**실제 개인 로컬 overload_dev에는 V11을 적용하지 않았고 그 DB로 앱을 실행하지 않았다.**
Frontend·운동 세션·IndexedDB·공식 평가 알고리즘·일정은 이번 범위에서 변경하지 않았다.
구현 승인은 충족했고 별도 승인 대기나 보류 작업은 없다. 실제 DB 적용·Frontend 연동·세션 구현은 별도 범위다.
Backend HEAD는 시작 커밋 그대로이며 변경은 작업 트리에만 있다. stage·commit·Push하지 않았다.
