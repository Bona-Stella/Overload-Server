<div align="center">
🏋️ overload-server
꾸준한 운동을 데이터로, 성장의 과정을 기록으로.
웨이트 트레이닝 기록 및 관리를 위한 Overload 앱의 백엔드 REST API 서버
무게와 반복 횟수부터 운동 루틴, 운동 이력, 성장 지표까지 체계적으로 관리하는 서비스를 목표로 합니다.
![Java](https://img.shields.io/badge/Java-25%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.8.2-02303A?style=flat-square&logo=gradle&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18.6-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![Status](https://img.shields.io/badge/Status-Planning-6B7280?style=flat-square)
</div>
---
📌 프로젝트 소개
Overload는 웨이트 트레이닝을 하는 사용자가 자신의 운동을 기록하고, 루틴을 관리하며, 기록의 변화를 통해 점진적 과부하(Progressive Overload)를 확인할 수 있도록 돕는 앱입니다.
`overload-server`는 사용자 계정, 운동 종목, 운동 세션, 세트별 기록, 루틴 및 통계 데이터를 관리하고 클라이언트 앱에 REST API를 제공하는 백엔드 프로젝트입니다.
> 🚧 현재 초기 설계 단계입니다. 아래 기능·API·폴더 구조는 **개발 목표 및 설계안**이며, 구현 완료를 의미하지 않습니다.
✨ 주요 기능 (계획)
[ ] 사용자 관리 — 회원가입, 로그인, 사용자별 운동 데이터 관리
[ ] 운동 종목 관리 — 운동 이름, 운동 부위, 운동 유형 조회 및 관리
[ ] 운동 기록 — 날짜별 운동 세션 생성, 종목 및 세트 기록
[ ] 세트 상세 기록 — 중량(kg), 반복 횟수(reps), 세트 순서, 메모 기록
[ ] 루틴 관리 — 자주 수행하는 종목과 세트 구성을 루틴으로 저장·재사용
[ ] 운동 이력 조회 — 날짜별·종목별 운동 기록 검색
[ ] 운동 통계 — 운동 빈도, 총 볼륨, 종목별 기록 변화 시각화를 위한 데이터 제공
[ ] 성장 추적 — 개인 최고 기록(PR) 및 점진적 과부하 추적
📈 핵심 지표
지표	설명
중량 (Weight)	각 세트에서 사용한 무게 (kg)
반복 횟수 (Reps)	각 세트에서 수행한 횟수
세트 수 (Sets)	운동 종목별 수행 세트 수
운동 볼륨 (Volume)	기록된 세트의 `중량 × 반복 횟수` 합계
운동 빈도	일정 기간 동안 수행한 운동 세션 수
개인 최고 기록 (PR)	종목별 최고 중량 등 기준에 따른 개인 최고 기록
> 볼륨 및 PR의 세부 산정 기준(워밍업 세트 포함 여부, 체중 운동 처리 방식 등)은 도메인 설계 과정에서 확정할 예정입니다.
🛠 기술 스택
2026-10-25 기준 안정 릴리스를 참고한 초기 권장 구성입니다. 프레임워크·라이브러리 호환성에 따라 일부 구성은 변경될 수 있습니다.
구분	기술	버전 / 비고
Language	Java	25 LTS
Framework	Spring Boot	4.1.2
Framework Core	Spring Framework	7.x (Spring Boot 관리)
Build	Gradle	9.8.2, Wrapper 사용 권장
Database	PostgreSQL	18.6 (도입 예정)
ORM	Spring Data JPA / Hibernate	Spring Boot 의존성 관리 (도입 예정)
Security	Spring Security	인증·인가 적용 예정
Authentication	JWT 기반 인증	적용 방식 검토 예정
Validation	Jakarta Validation	적용 예정
DB Migration	Flyway	도입 검토 예정
Testing	JUnit / Spring Boot Test / Testcontainers	도입 예정
API Docs	OpenAPI / Swagger UI	호환 버전 확인 후 도입 예정
Infrastructure	Docker / Docker Compose	로컬 DB 환경 구성 예정
> 의존성의 세부 버전은 가능한 한 Spring Boot의 BOM(의존성 관리 설정)에 맡깁니다.
🧩 도메인 설계 (초안)
```text
User
 ├── WorkoutSession
 │    └── WorkoutSet ─── Exercise
 └── Routine
      └── RoutineExercise ─── Exercise
```
User: 사용자 계정
Exercise: 스쿼트·벤치프레스 등 운동 종목
WorkoutSession: 특정 날짜에 진행한 운동 단위
WorkoutSet: 운동 종목, 무게, 횟수 등을 포함한 개별 세트 기록
Routine: 저장된 운동 루틴
RoutineExercise: 루틴에 포함된 운동 종목과 수행 순서
위 관계는 이해를 돕기 위한 개념도이며, 세부 테이블과 컬럼은 구현 과정에서 변경될 수 있습니다.
📁 프로젝트 구조 (예정)
기능별로 코드를 묶는 도메인 중심 패키지 구조를 고려하고 있습니다.
```text
overload-server/
├── src/
│   ├── main/
│   │   ├── java/...
│   │   │   ├── OverloadServerApplication.java
│   │   │   ├── global/           # 공통 설정, 예외 처리, 응답 규약
│   │   │   ├── auth/             # 인증 및 인가
│   │   │   ├── user/             # 사용자
│   │   │   ├── exercise/         # 운동 종목
│   │   │   ├── workout/          # 운동 세션 및 세트 기록
│   │   │   ├── routine/          # 운동 루틴
│   │   │   └── statistics/       # 기록 집계 및 통계
│   │   └── resources/
│   │       └── application.yml
│   └── test/                     # 단위 및 통합 테스트
├── gradle/
├── build.gradle                  # 또는 build.gradle.kts
├── settings.gradle               # 또는 settings.gradle.kts
├── gradlew
├── gradlew.bat
└── README.md
```
🔗 API 설계 (예정)
아래는 REST API의 경로 예시입니다. 실제 구현·명세와 다를 수 있습니다.
Method	Endpoint	설명
`GET`	`/api/v1/exercises`	운동 종목 목록 조회
`POST`	`/api/v1/workouts`	운동 세션 생성
`GET`	`/api/v1/workouts`	내 운동 이력 조회
`GET`	`/api/v1/workouts/{workoutId}`	운동 세션 상세 조회
`POST`	`/api/v1/workouts/{workoutId}/sets`	운동 세트 기록 추가
`POST`	`/api/v1/routines`	운동 루틴 생성
`GET`	`/api/v1/routines`	내 운동 루틴 목록 조회
`GET`	`/api/v1/statistics/volume`	기간별 운동 볼륨 조회
인증 방식, 요청·응답 DTO 및 오류 응답 규약은 API 구현과 함께 문서화할 예정입니다.
🚀 로컬 실행 (환경 구성 후 사용)
> 아직 프로젝트 초기 단계이므로 Gradle Wrapper, DB 설정, 애플리케이션 설정 파일이 추가된 이후 아래 명령을 사용할 수 있습니다.
요구 환경
JDK 25
Docker / Docker Compose (PostgreSQL 컨테이너를 사용하는 경우)
프로젝트에 포함된 Gradle Wrapper
실행 예시
```bash
# 프로젝트 디렉터리에서 실행 (macOS / Linux)
./gradlew bootRun
```
```powershell
# Windows
.\gradlew.bat bootRun
```
데이터베이스 설정과 환경변수 예시는 설정 파일이 추가되는 시점에 문서화할 예정입니다. 비밀번호·토큰 서명키 등 민감 정보는 저장소에 커밋하지 않습니다.
🗺 개발 로드맵
[ ] Phase 1 · 기반 구축 — 프로젝트 생성, DB 연결, 공통 응답/예외 처리, 테스트 환경
[ ] Phase 2 · 기록 MVP — 운동 종목, 운동 세션, 세트 CRUD
[ ] Phase 3 · 사용자 및 루틴 — 인증·인가, 사용자별 기록 분리, 루틴 관리
[ ] Phase 4 · 분석 및 개선 — 운동 통계, PR 분석, API 문서화, 성능·보안 개선
📚 참고 문서
Spring Boot
Java
Gradle
PostgreSQL
---
<div align="center">
Overload — Track your lifts. Own your progress. 💪
</div>
