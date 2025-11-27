# Prism (A/B Testing System)

Prism은 확장 가능한 A/B 테스트 플랫폼입니다.
무상태 분배 엔진, 실험 관리 Admin, 고성능 Traffic Serving API로 구성되어 있습니다.

## 1. 프로젝트 구조
- **prism-core**: 핵심 도메인 로직 (MurmurHash, TrafficSplitter, Targeting). 순수 Kotlin.
- **prism-admin**: 실험 관리 및 통계 분석 서버 (Spring Boot, JPA).
- **prism-api**: 트래픽 분배 및 로그 수집 서버 (Spring Boot, Caffeine, Async).
- **prism-infrastructure**: 데이터베이스 엔티티 및 공통 인프라 설정 (JPA Entities, Schema).

## 2. 시작하기 (Getting Started)

### 필수 요구사항
- JDK 17 이상
- Docker & Docker Compose (로컬 DB 실행용)
- Gradle (또는 IntelliJ IDEA 사용 권장)

### 로컬 개발 환경 설정 (Local Setup)
**1. 데이터베이스 실행**
프로젝트 루트의 `docker` 디렉토리에서 Docker Compose를 실행하여 MySQL 데이터베이스를 준비합니다.
```bash
cd docker
docker-compose up -d
```
이 명령어는 MySQL 컨테이너를 실행하고, `prism-infrastructure` 모듈의 `schema.sql`을 사용하여 테이블을 자동으로 생성합니다.

**2. 애플리케이션 실행**
데이터베이스가 준비되면 애플리케이션을 실행할 수 있습니다.

### 빌드 및 테스트
이 프로젝트는 Gradle을 사용합니다. 터미널에 `gradle`이 설치되어 있지 않다면, **IntelliJ IDEA**로 프로젝트를 열어 실행하는 것을 권장합니다.

```bash
# 전체 테스트 실행
./gradlew test

# 모듈별 테스트 실행
./gradlew :prism-core:test
./gradlew :prism-api:test
./gradlew :prism-admin:test
```

### 애플리케이션 실행
**Admin 서버 실행** (포트 8080)
```bash
./gradlew :prism-admin:bootRun
```

**API 서버 실행** (포트 8081 - 설정 필요)
`prism-api`는 기본적으로 8080을 사용하므로, 동시에 띄우려면 `application.yml`에서 포트를 변경해야 합니다.

## 3. 주요 기능
- **실험 생성**: Admin API를 통해 실험(Variants, 타겟팅 규칙)을 생성합니다.
- **트래픽 분배**: API 서버(`/v1/assign`)를 통해 사용자를 그룹에 할당합니다.
- **로그 수집**: 할당 및 전환 로그가 비동기로 DB에 저장됩니다.
- **통계 분석**: Admin API를 통해 실험별 CVR(전환율)과 승자를 확인합니다.

## 4. 타겟팅 규칙 (SpEL)
`prism-core`는 Spring Expression Language (SpEL)를 지원합니다.
예: `age >= 20`, `os == 'iOS'`, `appVersion > '1.5'`
