# os-check — Implementation Notes

> 이 문서는 [DESIGN.md](DESIGN.md)의 설계 결정을 코드로 옮기면서 확정한 구현 구조와
> 진행 중 내린 세부 결정의 기록이다. 설계 자체의 변경은 DESIGN.md에서만 다룬다.
>
> 마지막 갱신: 2026-07-16 (DESIGN.md 9절 기준 1·2단계 완료 시점)

## 1. 패키지 구조

```
com.nj.oss.check
├── OssCheckApplication          # Spring Boot 진입점 (CLI 와이어링은 3단계에서)
├── snapshot/                    # ClusterSnapshot 모델 + 파서 (순수 Java)
├── rule/                        # 룰 엔진 인터페이스 (순수 Java)
└── collect/                     # 수집 계층 경계 (HTTP 수집기·tar.gz 리더는 3단계에서)
```

**core 패키지(snapshot/rule/collect)는 Spring 비의존.** 순수 Java로 유지해 테스트를
가볍게 하고, Spring 와이어링(DI, picocli 통합)은 CLI 계층에서만 한다.

## 2. `snapshot` 패키지 — ClusterSnapshot 필드 설계

`ClusterSnapshot`은 collect가 모은 모든 API 응답을 파싱해 담은 불변 record다.
룰이 보는 유일한 입력이며, live/offline 출처를 알지 못한다 (DESIGN.md 4.2).

| 필드 | 타입 | 원본 API |
|---|---|---|
| `metadata` | `SnapshotMetadata` — 수집 시각(Instant)·도구 버전·클러스터 이름/버전 | collect가 쓰는 `metadata.json` |
| `health` | `ClusterHealth` — status(enum GREEN/YELLOW/RED), 노드 수, 샤드 카운트류 | `_cluster/health` |
| `settings` | `ClusterSettings` — persistent/transient/defaults 3개 스코프, dotted key 평탄화 맵 | `_cluster/settings?include_defaults=true` |
| `allocationExplain` | `Optional<AllocationExplain>` — 설명 대상 샤드, unassigned 사유, can_allocate | `_cluster/allocation/explain` |
| `nodesStats` | `NodesStats` — 노드ID→NodeStats(name, roles, JVM heap, breakers 맵) | `_nodes/stats` |
| `shards` | `List<ShardEntry>` — index, shard, prirep, state, docs, storeBytes, node | `_cat/shards?format=json&bytes=b` |
| `indices` | `List<IndexEntry>` — health, status, index, pri, rep, docsCount, storeSizeBytes | `_cat/indices?format=json&bytes=b` |
| `allocations` | `List<NodeAllocation>` — shards, disk 사용량/가용량/percent, node | `_cat/allocation?format=json&bytes=b` |

필드 선정 기준은 **초기 룰 3개가 필요로 하는 데이터**다:

- **OSC-001**: `nodesStats`의 breaker tripped 카운트 + heap 사용률, `indices`의 `top_queries-*` 존재/크기
- **OSC-002**: `settings`의 `cluster.max_shards_per_node` × `nodesStats.dataNodeCount()` vs 현재 샤드 수
- **OSC-003**: `settings`의 `cluster.routing.allocation.enable` + `health`의 status/unassigned + `allocationExplain`

nullable 필드는 boxed 타입(`Long`, `Integer`)으로 표현한다
(예: unassigned 샤드 행의 `docs`/`storeBytes`/`node`, closed 인덱스의 `docsCount`).

### 파서 (`ClusterSnapshotParser`)

각 API의 wire format을 아는 유일한 장소. `RawDump` → `ClusterSnapshot` 변환.
필수 파일 누락·JSON 파손 시 `SnapshotParseException`을 던진다 (조용한 미탐 방지).

## 3. `rule` 패키지 — 룰 엔진

DESIGN.md 4.2의 인터페이스를 그대로 구현:

- `DiagnosticRule` — `id()` / `severity()` / `evaluate(ClusterSnapshot): Optional<Finding>`.
  `severity()`는 룰의 명목(최악) 심각도이고, 실제 발생 건의 심각도는 `Finding`이 갖는다
  (OSC-002처럼 CRITICAL/WARNING 가변인 룰 대응).
- `Finding` — ruleId / severity / finding(한 줄) / evidence 목록 / recommendation. 불변 record.
- `Evidence(source, value)` — `render()`하면 `"nodes.stats.breakers.parent.tripped = 847"` 형태.
- `Severity` — CRITICAL, WARNING, INFO. **enum 선언 순서가 곧 보고서 정렬 순서.**
- `RuleEngine` — 등록된 모든 룰 실행 후 심각도순(동률이면 ruleId순) 정렬해 반환.

## 4. `collect` 패키지 — 수집 계층 경계

- `CollectTarget` enum — 수집할 7개 엔드포인트 경로와 **아카이브 내 파일명**을 한 곳에 못 박음.
  live 수집기와 tar.gz 리더가 공유하므로 두 모드의 덤프 구조가 항상 일치한다.
  `metadata.json` 파일명 상수도 여기에 있다.
- `RawDump` — 파싱 전 원본 JSON 묶음 (metadata + 타깃별 payload 맵).
  **collect 계층과 snapshot 파서 사이의 경계 타입**으로, live/offline 두 모드가 여기서 수렴한다.
- `DumpSource` — `RawDump`를 만들어내는 인터페이스. 구현체 2개(HTTP live 수집,
  tar.gz 아카이브 리더)는 3단계에서 작성한다.

## 5. 테스트 현황

DESIGN.md 6절 전략대로 **픽스처 = 실제 API 응답 형태의 JSON**:

- `src/test/resources/fixtures/normal/` — 3노드 green 클러스터 (정상/음성 픽스처의 원본).
  파일 구성은 `CollectTarget`의 파일명과 동일 → offline 덤프 구조를 그대로 재현.
- `Fixtures` 테스트 헬퍼 — 픽스처 디렉토리를 `RawDump`로 로드.
- `ClusterSnapshotParserTest` (7개) — 전 필드 파싱, 설정 우선순위, explain 에러 바디 처리,
  필수 파일 누락 시 실패 검증.
- `SizeParserTest` (4개) — 숫자/사람용 단위/null/불량 입력.

룰별 3종 픽스처(양성/음성/경계)와 `ClusterSnapshotBuilder` 헬퍼는 룰 구현(4단계)과 함께 작성한다.

## 6. 구현 중 내린 세부 결정 (결정 로그)

| # | 결정 | 이유 |
|---|---|---|
| 1 | _cat 엔드포인트에 `bytes=b` 파라미터 추가 | 사이즈를 `"1.2gb"` 같은 사람용 문자열이 아닌 숫자 바이트로 수집 → 파싱 견고성. DESIGN.md 3.1의 엔드포인트 목록에서 벗어난 유일한 변경. 단 `SizeParser`는 사람용 단위도 허용해 외부 제작 덤프와 호환 |
| 2 | `allocation/explain`의 HTTP 400 에러 바디 → `Optional.empty()` | unassigned 샤드가 없는 정상 클러스터에서 이 API는 에러를 반환한다. 이는 "설명할 것 없음"이지 파싱 실패가 아님 |
| 3 | 클러스터 설정을 파싱 시점에 dotted key로 평탄화 | 룰이 설정 API에 넘기는 것과 같은 키(`cluster.routing.allocation.enable`)로 조회 가능. 배열 값은 콤마 결합 문자열로 평탄화 |
| 4 | `ClusterSettings.effective()` vs `explicit()` 구분 | `effective`는 transient > persistent > defaults 우선순위 적용(실제 동작값), `explicit`은 운영자가 명시한 값만(transient/persistent). OSC-003의 "오설정 감지"는 기본값과 명시 설정을 구분해야 함 |
| 5 | core 패키지는 Spring 비의존 | 룰·파서 테스트가 Spring 컨텍스트 없이 도는 순수 단위 테스트. Spring/picocli 와이어링은 CLI 계층에서만 |
| 6 | 필수 파일 누락·JSON 파손 시 즉시 예외 (`SnapshotParseException`) | 조용히 넘어가면 미탐으로 이어짐. 실행 오류는 종료 코드 2로 구분되므로(DESIGN.md 3.2) 시끄럽게 실패하는 것이 맞다 |
| 7 | Jackson 3 (`tools.jackson`, Spring Boot 4 관리 버전) 사용 | Boot 4의 기본 Jackson 세대와 통일. Jackson 2를 별도 추가하면 uber-jar에 두 세대가 공존하게 됨 |

## 7. 다음 단계

DESIGN.md 9절과 동일 — **collect 구현(HTTP 수집기 + tar.gz 생성) → diagnose offline 모드
→ 룰 3개 → live 모드** 순서. 이 문서는 각 단계 완료 시 갱신한다.