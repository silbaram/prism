# 이슈 #32: Windows 호환성과 운영 개선

[GitHub 이슈 #32](https://github.com/silbaram/prism/issues/32)의 Windows 빌드 오류와 후속 정리 8개를 반영한다.

## 변경 내용

| 항목 | 반영 내용 |
| --- | --- |
| Windows 파일 배정 저장소 | Windows 기본 파일시스템에서만 디렉터리 fsync를 생략한다. 파일 fsync, 원자적 이동, 파일 잠금, 나머지 I/O 오류 전파는 유지한다. |
| 분석 확정 실패 격리 | 행별 트랜잭션의 실패를 격리하고 재시도 시각·횟수를 DB에 저장한다. 성공적으로 커밋한 행만 완료 건수로 센다. |
| Admin 검증 메시지 | 분석 계획·홀드아웃·레이어의 사용자 입력 오류를 `AdminValidationException`으로 구분해 구체적인 원인을 표시한다. 일반 예외와 DB 오류는 기존 일반 메시지를 사용한다. |
| 이벤트 JSON 확장 | 알 수 없는 최상위 필드를 보존해 수신한다. 멱등성 해시, Kafka inbox, warehouse 출력에도 포함한다. |
| 중복 실험 조회 | 이벤트 수신에서 조회한 엔티티를 분석 기록에 전달한다. 노출당 `findByKey` 호출은 1회다. |
| 보고서 성능 | 변경 감지 후 보고서를 재사용하는 제한된 캐시를 추가하고 통계 계산을 DB 트랜잭션 밖으로 이동한다. |
| flush 시간 예산 | 일반·주기 전송의 `flushTimeout`과 종료 전송의 `shutdownTimeout`을 분리한다. 각각 기본 5초다. |
| 폼 경로 | Thymeleaf 링크 표현식을 사용해 `/prism` 같은 context path를 포함한 경로로 제출한다. |
| Kafka 종료 API | Kafka 4.1의 `CloseOptions.timeout(...)`을 사용한다. |

## 배포

기존 DB는 백업 후 [034_analysis_finalization_retry.sql](../prism-infrastructure/src/main/resources/migrations/034_analysis_finalization_retry.sql)을 **새 API/Admin 배포 전에 한 번 적용**한다. Phase 5의 033까지 적용된 DB가 대상이다. 신규 DB는 최신 `schema.sql`을 사용한다. 자동 마이그레이션은 없다.

추가 열은 `analysis_observations.finalization_retry_at`(UTC, nullable TIMESTAMP(6))과 `finalization_attempts`(기본 0)이다. 이전 데이터의 분석 결과를 변경하거나 임의로 확정하지 않는다.

확정 실패 시 해당 행을 미확정으로 두고 관측 ID와 예외 종류를 기록한다. 재시도 간격은 30초, 60초, 120초 등으로 증가해 최대 1시간이며 다음 성공 시 초기화한다. 재시도 기준은 실패 처리 시점과 배치 기준 시각 중 늦은 값이므로, 오래 걸린 배치가 과거의 재시도 시각을 저장하지 않는다. 대기 중인 실패 행은 다음 배치 조회에서 제외해 뒤의 정상 행이 처리될 수 있게 한다. 정상 행의 전환 여부나 기존 통계식은 변경하지 않는다. 실패 원인을 고치면 예약된 재시도에서 복구한다. 로그와 다음 조회로 지연을 확인할 수 있다.

```sql
SELECT id, experiment_id, matures_at, finalization_attempts, finalization_retry_at
FROM analysis_observations
WHERE finalized_at IS NULL AND finalization_attempts > 0
ORDER BY finalization_retry_at;
```

## 보고서 캐시의 일관성과 범위

각 GET은 계획 생성 시각, 변형별 등록 수, 전체 노출 사용자 수, 확정·미확정·무효 사용자 수를 한 DB 스냅샷에서 확인한다. 값이 같으면 기존 보고서를 재사용한다. 신규 등록, 확정 완료, 늦은 전환에 따른 무효 표시가 생기면 다음 조회에서 다시 집계한다. 확정된 관측값과 사전 계획의 불변성을 전제로 하며 DB 직접 수정은 지원하지 않는다.

캐시는 Admin 프로세스당 최대 64개 실험이며 10분간 미사용 시 만료한다. 같은 실험의 동시 요청은 DB 연결을 얻기 전에 대기해 중복 전량 집계를 막는다. 고정된 수의 잠금과 집계값만 메모리에 유지한다.

캐시를 새로 채울 때는 `REPEATABLE_READ`에서 1,000행씩 읽어 제한된 크기의 집계값을 만든다. Bayesian 샘플링·CUPED 계산은 이 트랜잭션이 끝난 뒤 실행한다. **첫 조회와 데이터 변경 후 조회는 여전히 전체 관측을 읽는다.** 캐시 조회의 카운트 쿼리도 데이터 크기에 영향을 받는다. 지속적인 대규모 변경에서는 별도 사전 집계가 추가로 필요할 수 있다.

## JSON 호환성

추가 최상위 필드는 최대 16개, 이름은 공백이 아닌 1–64자, 추가 값의 직렬화 크기는 합계 16 KiB 이하다. 알려진 이벤트 종류와 필수 필드 검증은 계속 적용한다. 제한 위반은 해당 이벤트를 거절하며 같은 배치의 정상 이벤트는 처리한다.

HTTP의 Jackson 3와 SDK/Kafka의 Jackson 2 모두에서 추가 값을 보존한다. `extensions`라는 JSON 필드 자체도 보존하며, 소수는 비정형 Map을 읽을 때 `BigDecimal`로 받아 Double 반올림을 피한다. 추가 최상위 키는 정렬해 직렬화하여 디코더의 수집 순서가 멱등성 해시를 바꾸지 않게 한다. 알려진 기존 필드의 직렬화 형식과 추가 필드가 없는 기존 이벤트의 해시는 유지한다.

같은 ID로 추가 값을 바꿔 재전송하면 다른 payload로 거절한다. 추가 필드를 저장한다고 구버전 서버가 새 필드의 업무 의미까지 처리하는 것은 아니다. 의미 있는 새 계측을 켜기 전에는 수신 노드를 먼저 갱신한다.

## 검증

2026-09-14, Temurin JDK 21.0.12.1+1 / Gradle 9.1.0 기준:

| 환경 | 결과 |
| --- | --- |
| Linux 전체 `build` | 일반 테스트 200개 통과 (Admin 53, API 30, Core 26, SDK 58, Starter 33) |
| Windows 11 Pro 실제 `build --offline` | 동일한 일반 테스트 200개 통과 |
| 독립 Java SDK 소비자 | 두 OS 모두 Gradle module metadata / Maven POM 소비자 실행 통과 |
| 실제 MySQL 8.4.11 | 신규/기존 스키마 × UTC/Asia-Seoul의 4개 테스트 통과. 034 적용과 재시도 시각의 UTC·마이크로초 저장 포함 |
| 실제 Kafka 4.1.0 | 통합 테스트 1개 통과. 정밀 소수 확장 필드의 수신·저장·warehouse 전달 포함 |
| GitHub Actions 설정 | `actionlint` 통과, 사용 action의 커밋 확인 |

추가 회귀 테스트는 행별 분석 실패 격리·30초 재시도·복구, 일반/종료 flush의 독립적인 시간 예산, 파일 I/O 오류 전파, 확장 필드의 이름 충돌·정밀 소수·멱등성·크기 제한, 노출당 실험 조회 1회, context path 아래 폼 action과 실제 검증 오류 화면을 확인한다.

1,002명의 확정 관측을 사용한 통합 테스트에서 같은 보고서를 두 번 조회했을 때 전량 조회는 1회였다. H2 단일 실행 측정은 Linux 최초 66.48ms / 캐시 11.33ms, Windows 최초 62.17ms / 캐시 6.78ms였다. 캐시 재사용과 결과 일치 여부가 테스트 조건이며 시간 수치는 참고값이다. 운영 DB 부하·대규모 데이터에서 같은 지연을 보장하는 벤치마크는 아니다.

Windows에서 신규 inbox 행의 DB 시각 반올림 직후 즉시 처리된다고 가정하던 기존 테스트도 보완했다. 미처리 테스트 데이터만 과거의 처리 가능 시각으로 지정하며, 이미 잡힌 재시도 lease의 대기·복구 검증은 유지한다.

[GitHub Actions](../.github/workflows/build.yml)는 Ubuntu/Windows에서 `build`를 실행하고, 별도 Ubuntu 작업에서 MySQL 8.4와 Kafka 4.1 통합 테스트를 실행한다. 일반 `build`에는 Gradle module metadata와 Maven POM을 각각 사용하는 독립 Java SDK 소비자 실행이 포함된다. GitHub 러너 실행은 이 워크플로를 푸시한 뒤 시작된다.

## 추가 코드 리뷰와 회귀 검증

- `ClientEvent`에 확장 필드를 추가하면서 기존 Java 10인자 생성자가 사라진 문제를 독립 소비자의 컴파일 실패로 재현했다. `@JvmOverloads`로 생성자를 복구하고 공개 DTO의 Jackson 어노테이션 의존성을 `api`로 내보낸다. Gradle module metadata와 Maven POM 소비자 모두 기존 생성자를 호출해 검증한다. Kotlin 소비자는 변경된 라이브러리에 맞춰 다시 빌드한다.
- 오래된 배치 기준 시각으로 재시도 시각을 계산하면 재시도가 이미 지난 시각으로 저장되는 문제를 재현했다. 실패를 처리하는 현재 시각도 반영해 최소 재시도 간격을 보장한다.
- 500개의 실패 행으로 배치 한도가 모두 소진되어도 다음 정상 행이 진행하고, 원인 수정 후 500개가 모두 복구되는 통합 테스트를 통과했다.
- JSON 필드 순서를 바꾸고 Jackson 3 → Jackson 2 디코딩을 반복해도 `extensions`·`extensionFields` 필드와 극소수·큰 정수 값이 보존되는지 확인했다. 홀드아웃 재설정 실패의 구체적 메시지도 실제 HTTP 응답으로 검증했다.

재리뷰는 이슈의 Windows 저장소 오류와 후속 8개, 신규 스키마·설정·CI·공개 DTO 호환성을 대상으로 했다. 캐시 무효화는 신규 관측 확정과 늦은 전환에 따른 추론 차단 테스트로, 파일 오류 전파와 종료 예산은 SDK 회귀 테스트로 확인했다. Kafka 4.1.1 클라이언트의 기존 `close(Duration)`가 내부적으로 같은 `CloseOptions.timeout(...)` 경로를 호출하는 것도 확인했다. 검토 범위에서 남은 수정 사항은 발견하지 못했다.
