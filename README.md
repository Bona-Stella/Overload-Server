 <div align="center">
  <h1>🏋️ overload-server</h1>

  <h3>꾸준한 운동을 데이터로, 성장의 과정을 기록으로.</h3>

  <p>
    웨이트 트레이닝 기록 및 관리를 위한<br>
    <strong>Overload</strong> 앱의 백엔드 REST API 서버
  </p>

  <p>
    무게와 반복 횟수부터 운동 루틴, 운동 이력, 성장 지표까지<br>
    체계적으로 관리하는 서비스를 목표로 합니다.
  </p>

  <img src="https://img.shields.io/badge/Java-25%20LTS-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F?style=flat-square&amp;logo=springboot&amp;logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Gradle-9.8.2-02303A?style=flat-square&amp;logo=gradle&amp;logoColor=white" alt="Gradle">
  <img src="https://img.shields.io/badge/PostgreSQL-18.6-4169E1?style=flat-square&amp;logo=postgresql&amp;logoColor=white" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/Status-Planning-6B7280?style=flat-square" alt="Status">
</div>

---

## 📌 프로젝트 소개

**Overload**는 웨이트 트레이닝을 하는 사용자가 자신의 운동을 기록하고, 루틴을 관리하며, 기록의 변화를 통해 **점진적 과부하(Progressive Overload)**를 확인할 수 있도록 돕는 앱입니다.

`overload-server`는 사용자 계정, 운동 종목, 운동 세션, 세트별 기록, 루틴 및 통계 데이터를 관리하고 클라이언트 앱에 REST API를 제공하는 백엔드 프로젝트입니다.

> 🚧 현재 초기 설계 단계입니다. 아래 기능, API 및 프로젝트 구조는 개발 목표이며, 구현 과정에서 변경될 수 있습니다.

---

## ✨ 주요 기능

### 👤 사용자 관리
- [ ] 회원가입 및 로그인
- [ ] 사용자 정보 조회 및 수정
- [ ] 사용자별 운동 데이터 관리
- [ ] 인증 및 인가

### 🏋️ 운동 기록 관리
- [ ] 운동 종목 등록 및 조회
- [ ] 날짜별 운동 세션 생성
- [ ] 운동 종목별 세트 추가 및 수정
- [ ] 세트별 중량(kg), 반복 횟수(Reps) 기록
- [ ] 운동 시간 및 메모 기록
- [ ] 운동 기록 수정 및 삭제

### 📋 운동 루틴 관리
- [ ] 사용자별 운동 루틴 생성
- [ ] 루틴에 운동 종목 추가
- [ ] 운동 순서 및 목표 세트 설정
- [ ] 저장된 루틴을 이용한 운동 시작
- [ ] 루틴 수정 및 삭제

### 📊 운동 통계 및 성장 분석
- [ ] 일별, 주별, 월별 운동 기록 조회
- [ ] 운동 종목별 중량 변화 추적
- [ ] 총 운동 볼륨(Volume) 분석
- [ ] 운동 빈도 및 수행 횟수 집계
- [ ] 개인 최고 기록(PR) 관리
- [ ] 점진적 과부하 추적

---

## 📈 핵심 운동 지표

| 지표 | 설명 |
|:---|:---|
| Weight | 세트별 운동 중량 (kg) |
| Reps | 세트별 반복 횟수 |
| Sets | 종목별 수행 세트 수 |
| Volume | 중량 × 반복 횟수의 합계 |
| Frequency | 일정 기간 동안의 운동 빈도 |
| Personal Record | 종목별 개인 최고 기록 |
| Progressive Overload | 이전 운동 대비 수행 능력 변화 |

**운동 볼륨 계산 예시**

```text
Exercise: Bench Press

Set 1: 60kg × 10 reps = 600kg
Set 2: 70kg × 8 reps  = 560kg
Set 3: 80kg × 5 reps  = 400kg

Total Volume = 1,560kg
```

> 운동 볼륨과 PR의 세부 계산 기준은 도메인 설계 과정에서 확정할 예정입니다.

---

## 🛠 기술 스택

Java 및 Spring Boot 기반의 REST API 서버를 개발합니다.

### Backend

| Category | Technology | Version |
|:---|:---|:---|
| Language | Java | 25 LTS |
| Framework | Spring Boot | 4.1.2 |
| Framework Core | Spring Framework | 7.x |
| Build Tool | Gradle | 9.8.2 |
| ORM | Spring Data JPA / Hibernate | Spring Boot 관리 |
| Security | Spring Security | 도입 예정 |
| Authentication | JWT | 검토 예정 |
| Validation | Jakarta Validation | 도입 예정 |

### Database & Infrastructure

| Category | Technology | Version / Status |
|:---|:---|:---|
| RDBMS | PostgreSQL | 18.6 (도입 예정) |
| DB Migration | Flyway | 도입 검토 |
| Container | Docker | 도입 예정 |
| Local Environment | Docker Compose | 도입 예정 |

### Testing & Documentation

| Category | Technology |
|:---|:---|
| Unit Test | JUnit |
| Integration Test | Spring Boot Test |
| Container Test | Testcontainers |
| API Documentation | OpenAPI / Swagger UI |

> 라이브러리 버전은 가능한 한 Spring Boot의 의존성 관리 설정(BOM)을 따릅니다. 실제 적용 버전은 호환성 검증 후 확정합니다.

---

## 🧩 도메인 설계

주요 도메인은 사용자, 운동 종목, 운동 세션, 운동 세트, 루틴 및 통계로 구성합니다.

```text
User
 │
 ├── WorkoutSession
 │      │
 │      └── WorkoutExercise
 │                │
 │                ├── Exercise
 │                │
 │                └── WorkoutSet
 │
 └── Routine
        │
        └── RoutineExercise
                  │
                  └── Exercise
```

### 주요 엔티티

| Entity | Description |
|:---|:---|
| User | 사용자 계정 및 프로필 |
| Exercise | 스쿼트, 벤치프레스 등 운동 종목 |
| WorkoutSession | 특정 날짜에 진행한 운동 세션 |
| WorkoutExercise | 운동 세션에 포함된 운동 종목 |
| WorkoutSet | 개별 세트의 중량, 횟수, 순서 |
| Routine | 사용자가 저장한 운동 루틴 |
| RoutineExercise | 루틴에 포함된 운동 종목과 수행 순서 |

> 위 구조는 초기 도메인 설계안으로, 실제 DB 테이블 및 엔티티 관계는 개발 과정에서 변경될 수 있습니다.

---

## 📁 프로젝트 구조

기능별 도메인을 중심으로 패키지를 구성할 예정입니다.

```text
overload-server/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/overload/server/
│   │   │       ├── OverloadServerApplication.java
│   │   │       │
│   │   │       ├── global/
│   │   │       │   ├── config/
│   │   │       │   ├── exception/
│   │   │       │   └── response/
│   │   │       │
│   │   │       ├── auth/
│   │   │       ├── user/
│   │   │       ├── exercise/
│   │   │       ├── workout/
│   │   │       ├── routine/
│   │   │       └── statistics/
│   │   │
│   │   └── resources/
│   │       └── application.yml
│   │
│   └── test/
│
├── gradle/
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
└── README.md
```

### 패키지 역할

- `global` : 공통 설정, 예외 처리, 응답 규약
- `auth` : 사용자 인증 및 인가
- `user` : 사용자 계정 및 프로필 관리
- `exercise` : 운동 종목 데이터 관리
- `workout` : 운동 세션 및 세트 기록 관리
- `routine` : 운동 루틴 생성 및 관리
- `statistics` : 운동 기록 집계 및 성장 지표 분석

---

## 🔗 API 설계

RESTful API를 기반으로 클라이언트와 통신합니다.

Base URL:

```text
/api/v1
```

### Exercise API

| Method | Endpoint | Description |
|:---|:---|:---|
| GET | `/api/v1/exercises` | 운동 종목 목록 조회 |
| GET | `/api/v1/exercises/{id}` | 운동 종목 상세 조회 |

### Workout API

| Method | Endpoint | Description |
|:---|:---|:---|
| POST | `/api/v1/workouts` | 운동 세션 생성 |
| GET | `/api/v1/workouts` | 운동 기록 목록 조회 |
| GET | `/api/v1/workouts/{id}` | 운동 기록 상세 조회 |
| PATCH | `/api/v1/workouts/{id}` | 운동 세션 수정 |
| DELETE | `/api/v1/workouts/{id}` | 운동 세션 삭제 |
| POST | `/api/v1/workouts/{id}/exercises` | 세션에 운동 종목 추가 |
| POST | `/api/v1/workouts/{id}/sets` | 세트 기록 추가 |

### Routine API

| Method | Endpoint | Description |
|:---|:---|:---|
| POST | `/api/v1/routines` | 운동 루틴 생성 |
| GET | `/api/v1/routines` | 루틴 목록 조회 |
| GET | `/api/v1/routines/{id}` | 루틴 상세 조회 |
| PATCH | `/api/v1/routines/{id}` | 루틴 수정 |
| DELETE | `/api/v1/routines/{id}` | 루틴 삭제 |

### Statistics API

| Method | Endpoint | Description |
|:---|:---|:---|
| GET | `/api/v1/statistics/volume` | 기간별 운동 볼륨 조회 |
| GET | `/api/v1/statistics/frequency` | 운동 빈도 조회 |
| GET | `/api/v1/statistics/progress` | 운동 기록 변화 조회 |
| GET | `/api/v1/statistics/records` | 개인 최고 기록 조회 |

> 위 API는 초기 설계안입니다. 요청 및 응답 DTO, 인증 방식, 상세 API 명세는 개발 과정에서 확정합니다.

---

## 🚀 로컬 실행

### Requirements

- JDK 25
- Gradle Wrapper
- Docker / Docker Compose (DB 컨테이너 사용 시)

### Clone Repository

```bash
git clone https://github.com/<username>/overload-server.git
cd overload-server
```

> `<username>`은 실제 GitHub 사용자명 또는 조직명으로 변경해야 합니다.

### Run Application

**macOS / Linux**

```bash
./gradlew bootRun
```

**Windows**

```powershell
.\gradlew.bat bootRun
```

> 현재 초기 개발 단계입니다. DB 및 애플리케이션 환경 설정을 마친 후 실행할 수 있습니다.

---

## 🗺 개발 로드맵

### Phase 1. 프로젝트 기반 구축
- [ ] Spring Boot 프로젝트 생성
- [ ] Gradle 및 의존성 구성
- [ ] PostgreSQL 연동
- [ ] 공통 예외 처리 및 응답 규약 설계
- [ ] 테스트 환경 구축

### Phase 2. 운동 기록 MVP
- [ ] 운동 종목 도메인 구현
- [ ] 운동 세션 CRUD
- [ ] 운동 종목 및 세트 기록 CRUD
- [ ] 운동 이력 조회

### Phase 3. 사용자 및 루틴 관리
- [ ] 사용자 인증 및 인가
- [ ] 사용자별 운동 기록 분리
- [ ] 운동 루틴 CRUD
- [ ] 저장된 루틴 기반 운동 기록 생성

### Phase 4. 운동 통계 및 서비스 개선
- [ ] 운동 볼륨 통계
- [ ] 운동 빈도 분석
- [ ] 개인 최고 기록 및 성장 추적
- [ ] API 문서화
- [ ] 성능 최적화 및 보안 개선

---

## 📚 참고 문서

- [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/index.html)
- [Spring Framework 공식 문서](https://docs.spring.io/spring-framework/reference/)
- [Java 공식 문서](https://docs.oracle.com/en/java/)
- [Gradle 공식 문서](https://docs.gradle.org/)
- [PostgreSQL 공식 문서](https://www.postgresql.org/docs/)

---

<div align="center">
  <strong>Overload — Track your lifts. Own your progress. 💪</strong>
</div>
