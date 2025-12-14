# Prism (A/B Testing System)

Prism은 무상태 트래픽 분배 엔진, 실험 관리 Admin, 고성능 Serving API로 구성된 확장 가능한 A/B 테스트 플랫폼입니다.

## 프로젝트 구조
- **prism-core**: MurmurHash 기반 트래픽 분배 및 SpEL 타기팅 유틸리티
- **prism-common**: 공용 DTO 및 상수 모음
- **prism-api**: 트래픽 분배/로그 수집 API 서비스
- **prism-admin**: 실험 생성·승자 판정·통계 조회용 Admin 서비스
- **prism-sdk**: Java/Kotlin 클라이언트 SDK (자세한 내용은 `prism-sdk/README.md`)
- **prism-spring-boot-starter**: Spring Boot 통합 스타터 (자세한 내용은 `prism-spring-boot-starter/README.md`)
- **prism-infrastructure**: JPA 엔티티와 스키마 정의

## 빌드 및 실행
- 필수 요구사항: JDK 17+, Docker Compose(로컬 DB), Gradle 래퍼 사용 권장
- 빌드/테스트: `./gradlew test` 또는 `./gradlew clean build`
- 실행: `./gradlew :prism-admin:bootRun`, `./gradlew :prism-api:bootRun` (포트는 `application.yml`에서 조정)

## 문서
- SDK 상세 사용법: `prism-sdk/README.md`
- Spring Boot 통합 가이드: `prism-spring-boot-starter/README.md`
