# SDK·실제 인프라 추가 진단 결과

진단일: 2026-10-19 (한국 시간)

대상은 `feature/sdk-diagnostics-config-freshness`의 로컬 변경이다. 기준 HEAD는 `2c54d57`이며 SDK 진단·설정 신선도 변경은 미커밋 상태다. 초기 진단에서는 애플리케이션 코드나 설정 파일을 변경하지 않았다. 이후 같은 날 발견된 Kafka 종료 처리 문제를 수정하고 회귀 테스트를 추가했다. 아래에 초기 진단과 수정 후 검증을 구분해 기록한다.

## 결과

실제 MySQL·Kafka와 Prism API를 구동해 진단할 수 있었고, 추가 통합 테스트 5건과 실제 SDK의 장애·복구 흐름이 통과했다. 테스트 종료 중 발견된 Kafka consumer 정리 문제는 이후 수정했다. 수정 후 API 회귀 테스트 44건과 실제 Kafka 통합 테스트 2건이 통과했고, 기존 종료 오류 로그는 재발하지 않았다.

| 항목 | 실행 내용 | 결과 |
|---|---|---|
| MySQL 통합 테스트 | 신규 스키마/기존 DB 마이그레이션 × UTC/Asia/Seoul, 식별자·노출 귀속 검증 | 4건 통과, 실패·오류·건너뜀 0건 |
| Kafka 통합 테스트 | 실제 브로커의 QUEUED, 뒤늦은 노출 도착, 재시도·중복, DB 반영·warehouse 출력 | 1건 통과, 실패·오류·건너뜀 0건 |
| 실제 API 인증 | API 키 없는 설정 조회 | 401 확인; 정상 키로 SDK 설정 수신 성공 |
| DIRECT 경로 | 실제 API·MySQL에 SDK 노출·전환 전송 | ACCEPTED 2건, SDK 대기 0건 |
| 실제 API 중단 | 진단용 API 프로세스 종료 후 동기화·전송 시도 | 연결 실패 진단, 설정 STALE, 신규 배정 9996, 기존 노출 전환 1건 큐 보존 |
| 실제 API 복구 | 같은 DB로 API를 Kafka 모드에서 재시작 | 설정 FRESH, 배정 재개, QUEUED 2건, SDK 대기 0건 |
| 복구 후 실제 DB 확인 | MySQL과 Kafka를 함께 사용하는 실제 API에서 materialization 확인 | 노출 2건, 전환 2건, inbox DONE 2건, outbox 대기 0건 |
| 정리 | 진단용 API·SDK 프로세스와 임시 컨테이너 종료 | 컨테이너 잔존 없음, 진단 포트 모두 닫힘 |

Kafka 전용 JUnit 테스트는 실제 Kafka와 H2 DB를 사용한다. 이를 보완해 별도 SDK 실행 검증에서는 실제 MySQL과 실제 Kafka를 함께 연결했다. QUEUED 응답을 DB 저장 성공으로 합산하지 않고, 이후 MySQL에 반영되는 것까지 따로 확인했다.

## 실행 환경과 절차

- JDK 21, 현재 브랜치에서 이미 빌드한 API JAR와 SDK 클래스패스를 사용했다.
- 임시 MySQL `8.0.44`, Kafka `4.1.0` 컨테이너를 생성했다. 기존 실행 컨테이너는 없었다.
- MySQL 13306, Kafka 19092, API 18080 포트는 모두 `127.0.0.1`에만 바인딩했다.
- 기존 운영 DB·브로커·API는 사용하지 않았다. 테스트용 비밀번호와 API 키는 프로세스 메모리에서 생성·전달했으며 문서·명령 인자·로그에 값을 기록하지 않았다.
- SDK의 최대 설정 유지 시간은 재현용으로 8초, 자동 폴링 간격은 1일로 설정했다. 운영 권장 임계값이나 중단 전파 SLA를 의미하지 않는다.

별도 테스트 접속 환경 변수를 해당 실행 프로세스에만 주입하고 아래 작업을 실행했다.

```bash
./gradlew :prism-api:mysqlTest :prism-api:kafkaTest --offline --max-workers=4
```

실제 SDK 흐름은 일반 Java 프로그램으로 실행했다. 정상 전송 이후 API만 중단했고, SDK 프로세스는 계속 실행해 메모리 큐 보존을 확인했다. API를 Kafka 모드로 복구한 다음 원래 노출 참조의 지연 전환과 새 노출을 전송하고, 최종 DB 상태를 확인했다. SDK 프로세스 강제 종료 뒤의 큐 복구를 검증한 것은 아니다.

초기 진단에서는 이전 일반 회귀 테스트 363개와 게시 소비자 4가지의 결과도 확인했다. 당시에는 그 검증 이후 소스 변경이 없어 같은 전체 테스트를 반복하지 않고, 이전에 실행하지 못했던 실제 인프라와 실제 프로세스 중단·복구를 추가 진단했다. 이후 Kafka 종료 코드 수정에 대해서는 아래 API·Kafka 검증을 별도로 실행했다.

## Kafka 종료 처리: 수정 및 재검증 완료

초기 통합 테스트 종료 시 Kafka consumer의 coordinator·fetcher 정리에서 `InterruptException` 오류 로그가 각각 관찰됐다. 당시 JUnit 테스트와 Gradle 빌드는 성공했으며, 실제 복구 검증에서 이벤트 유실이나 처리 미완료가 재현된 것은 아니다.

기존 종료 순서는 `wakeup()` 직후 `shutdownNow()`를 호출해, 진행 중인 DB 저장이나 consumer 정리를 즉시 인터럽트할 수 있었다. 새 회귀 테스트를 수정 전 코드에 실행했을 때 저장 중인 작업을 기다리지 않고 종료하는 동작으로 실패해 문제를 재현했다.

[KafkaPipeline](../prism-api/src/main/kotlin/io/github/silbaram/prism/api/pipeline/KafkaPipeline.kt)의 종료 절차를 다음과 같이 수정했다.

- 신규 레코드 처리와 다음 inbox/outbox 작업을 중단하고, 이미 진행 중인 저장·전송·consumer 정리는 먼저 완료를 기다린다.
- 정상 종료 대기 6초를 넘길 때만 강제 인터럽트를 보내며, 전체 종료 대기는 약 12초로 제한한다. 끝나지 않은 작업은 경고 로그로 남긴다.
- consumer 정리 전 기존 인터럽트 플래그를 일시 해제하고 정리 후 복원한다. 종료 대기 호출자의 인터럽트 상태도 보존한다.
- 종료 중 저장된 레코드는 Kafka offset을 커밋하지 않는다. 재시작 후 재수신되더라도 영속 inbox와 이벤트 ID로 중복 처리를 방지한다.
- `close()`를 반복 호출해도 종료 절차를 다시 실행하지 않는다.

[추가 통합 테스트](../prism-api/src/test/kotlin/io/github/silbaram/prism/api/KafkaPipelineShutdownIntegrationTest.kt)는 실제 Kafka와 H2를 사용해 저장 도중 종료, 진행 중인 저장 완료, 같은 consumer group으로 재시작, 미커밋 레코드 재수신과 명시적 중복 전송을 검증한다. 최종 상태는 노출 1건, inbox DONE 1건, outbox 대기 0건이었다.

로컬 임시 Kafka 주소를 `PRISM_TEST_KAFKA_BOOTSTRAP`으로 지정한 뒤 실행했다.

```bash
./gradlew :prism-api:test :prism-api:kafkaTest :prism-api:bootJar --offline --max-workers=4
```

| 수정 후 검증 | 결과 |
|---|---|
| API 일반 회귀 테스트 | 44건 통과, 실패·오류·건너뜀 0건 |
| 실제 Kafka 통합 테스트 | 기존 처리 테스트와 신규 종료·재시작 테스트, 총 2건 통과, 실패·오류·건너뜀 0건 |
| API 실행 JAR | 빌드 성공 |
| 종료 오류 로그 | `Failed to close coordinator`, `Failed to close fetcher`, `InterruptException` 각각 0건 |
| 임시 Kafka 정리 | 검증용 컨테이너 제거, 19092 포트 닫힘 |

수정 전 재현 로그는 `/tmp/prism-kafka-shutdown-repro.log`, 수정 후 검증 로그는 `/tmp/prism-kafka-shutdown-verified.log`에 있다. 이번 재검증은 정상 종료와 재시작의 단일 브로커 경로를 확인한 것으로, 모든 강제 종료 상황에서 오류 로그나 중단이 없음을 보장하지 않는다.

## 검증 범위의 한계

이번 검증은 단일 MySQL·단일 Kafka 브로커와 적은 합성 이벤트를 사용한 기능·장애 재현이다. 운영 부하, 장시간 누적 사용자, 여러 브로커의 장애·리더 변경, DB 자체 장애, 운영 데이터 복원, 관리자 중앙 진단 수집은 검증하지 않았다. 이 항목들은 대표 데이터량·구성·허용 지연·유실 기준을 정한 별도 검증이 필요하다.

초기 진단의 임시 근거 파일은 `/tmp/prism-runtime-diagnostics-20261019/`에 있다. `integration-tests.log`, `runtime-probe-result.json`, `result.json`, API·브로커 로그로 결과를 확인할 수 있다. 테스트 XML은 `prism-api/build/test-results/mysqlTest/`, `prism-api/build/test-results/kafkaTest/`에 생성됐으며, `kafkaTest` 결과는 수정 후 실행 결과로 갱신됐다. 임시 파일은 이 클라우드 실행 환경의 수명에 종속된다.
