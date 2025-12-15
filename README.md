# Prism (A/B Testing System)

Prism은 무상태 트래픽 분배 엔진, 실험 관리 Admin, 고성능 Serving API로 구성된 확장 가능한 A/B 테스트 플랫폼입니다. SpEL 타기팅, MurmurHash 기반 분배, 실패 안전(Fail-safe) SDK를 제공합니다.

## 주요 특징
- **무상태 트래픽 분배**: 고성능 API로 사용자별 변형(variant) 할당
- **실험 관리/Admin**: 실험 생성·승자 판정·통계 뷰 제공
- **Fail-safe SDK**: 네트워크/서버 장애 시에도 기본값으로 안전하게 동작
- **Spring 통합**: `@PrismExperiment` 어노테이션과 안전한 전환 추적 래퍼 제공

## 프로젝트 구조
- **prism-core**: MurmurHash 기반 트래픽 분배 및 SpEL 타기팅 유틸리티
- **prism-common**: 공용 DTO 및 상수 모음 (`ResponseCode` 등)
- **prism-api**: 트래픽 분배/로그 수집 API 서비스
- **prism-admin**: 실험 생성·승자 판정·통계 조회용 Admin 서비스
- **prism-sdk**: Java/Kotlin 클라이언트 SDK (자세한 내용은 `prism-sdk/README.md`)
- **prism-spring-boot-starter**: Spring Boot 통합 스타터 (자세한 내용은 `prism-spring-boot-starter/README.md`)
- **prism-infrastructure**: JPA 엔티티와 스키마 정의

## 빠른 시작
```bash
# 의존성 설치 없이 Gradle 래퍼 사용을 권장합니다.
# 1) 로컬 DB (MySQL 예시)
cd docker
docker-compose up -d

# 2) 전체 빌드/테스트
cd ..
./gradlew clean build

# 3) API/ADMIN 실행 (포트 조정은 각 모듈 application.yml 또는 환경변수)
./gradlew :prism-api:bootRun
./gradlew :prism-admin:bootRun
```

## 빌드 및 실행
- 필수 요구사항: JDK 17+, Docker Compose(로컬 DB), Gradle 래퍼
- 빌드/테스트: `./gradlew test` 또는 `./gradlew clean build`
- 실행:
  - Admin: `./gradlew :prism-admin:bootRun` (기본 8070)
  - API: `./gradlew :prism-api:bootRun` (기본 8080 → 동시 실행 시 `application.yml` 혹은 `SERVER_PORT` 환경변수로 변경)
  - 로컬 DB: `cd docker && docker-compose up -d` (MySQL, 스키마는 `prism-infrastructure` 기준)

## 포트·환경 변수 요약
- Admin 기본 포트: 8070 (`prism-admin/src/main/resources/application.yml`)
- API 기본 포트: 8080 (`prism-api/src/main/resources/application.yml`)
- 포트 변경: `SERVER_PORT=<포트>` 환경 변수로 오버라이드하거나 각 모듈 `application.yml` 수정
- DB 연결: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` 등 환경 변수로 설정 가능
- 프로필: `SPRING_PROFILES_ACTIVE=local` 등으로 환경 분리

## 문서
- SDK 상세 사용법: `prism-sdk/README.md`
- Spring Boot 통합 가이드: `prism-spring-boot-starter/README.md`
- 빌드 시 `PrismClient.trackConversion` 직접 호출을 감지해 실패합니다. 통계 오염 방지를 위해 `PrismExperimentClient.trackConversionIfAssigned` 등 안전 래퍼를 사용하세요.
