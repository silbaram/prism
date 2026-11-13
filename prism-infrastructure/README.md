# prism-infrastructure

Prism 서버 모듈들이 의존하는 JPA 영속화 계층입니다. 엔티티·리포지토리 정의와 MySQL 스키마 자산을 담고, Spring Boot 데이터 JPA 자동구성을 노출합니다. 이 모듈 자체는 애플리케이션이 아닙니다(`bootJar` 비활성, `java-library` + `sourcesJar` + Maven 퍼블리싱).

## 구성
- `persistence/jpa/entities` — JPA 엔티티와 UTC 타임스탬프 변환기. 문자열 식별자는 utf8mb4_0900_bin 기반 대소문자 구분 비교를 전제로 합니다.
- `persistence/jpa/repository` — Spring Data JPA 리포지토리 인터페이스.
- `persistence/jpa/config` — `JpaConfiguration`(리포지토리/엔티티 스캔 패키지 고정), `PopulationInitializer`(`PopulationPolicy` 행이 없으면 생성하고, 생성 경합은 무시하는 `ApplicationRunner`).
- `src/main/resources` — JPA 로컬 설정, 전체 스키마, 마이그레이션 스크립트.

## 스키마 자산
- `schema.sql` — `CREATE TABLE` 17개. `IF NOT EXISTS` 사용, MySQL 8.0.17+, utf8mb4_0900_bin, 대소문자 no-pad 비교를 명시합니다. `docker/docker-compose.yml`이 `/docker-entrypoint-initdb.d/`에 마운트해 신규 DB 초기화에 사용합니다.
- `migrations/027_metric_integrity.sql` … `034_analysis_finalization_retry.sql` — 번호가 붙은 마이그레이션 스크립트 8개. 027 이전 기준 스키마는 소비 모듈 테스트 리소스 `mysql/pre-027-schema.sql`로 남아 있습니다.
- 마이그레이션 도구(Flyway 등) 연동은 저장소에 없습니다(의존성·설정 0 확인). 대신 각 단계 문서가 신규 설치("최신 `schema.sql`")와 기존 설치(누락 번호를 순서대로 1회 수동 적용)를 안내하며 "자동 마이그레이션은 수행하지 않는다"고 명시합니다: `docs/issue-27.md`, `docs/issue-28-phase-1..5.md`. 스크립트의 기준선 대비 관계·적용 주체·롤백은 문서 범위 밖이라 미확인입니다. `prism-api`의 opt-in `mysqlTest`가 027–034와 `schema.sql`을 실제 서버에 스크립트로 적용해 검증합니다.

## 로컬 설정
- `application-infrastructure-jpa.yml` — `jdbc:mysql://localhost:3306/prism`, `root`/`root`, `ddl-auto: validate`, SQL 로그 출력. 로컬 개발 전제이며 비로컬 환경의 오버라이드 방식은 미확인입니다.

## 소비·검증
- `prism-api`·`prism-admin`이 소비하며, 서버 런타임은 스키마를 자동 생성하지 않습니다(`ddl-auto: validate`). 신규 DB 초기화는 `schema.sql`, 기존 설치는 각 단계 문서가 안내하는 마이그레이션 스크립트가 경로입니다.

## 변경 시
- 엔티티/컬럼 변경 시 마이그레이션 추가 여부와 적용 경로(수동/미확인)를 함께 결정하고, 소비 모듈의 MySQL 테스트(`PRISM_TEST_MYSQL_URL` 필요)와 함께 검증하세요.
- 스키마 변경은 설정·이벤트 계약과 어긋나기 쉬우니 `docs/issue-28-phase-1.md`의 배포 순서(스키마 → API/Admin → 소비자)를 참고하세요.
