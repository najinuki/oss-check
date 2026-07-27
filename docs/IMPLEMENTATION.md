# os-check — Implementation Notes

> 이 문서는 [DESIGN.md](DESIGN.md)의 설계 결정을 코드로 옮기면서 확정한 구현 구조와
> 진행 중 내린 세부 결정의 기록이다. 설계 자체의 변경은 DESIGN.md에서만 다룬다.
>
> 마지막 갱신: 2026-07-27 (DESIGN.md 9절 기준 1·2·3단계 완료 시점)

## 1. 패키지 구조

```
com.nj.oss.check
├── OssCheckApplication          # Spring Boot 진입점 (CLI 와이어링은 4단계에서)
├── snapshot/                    # ClusterSnapshot 모델 (record·enum, 순수 Java)
│   └── parse/                   # wire format을 아는 유일한 곳 (파서·예외)
├── rule/                        # 룰 엔진 + 룰 공통 타입 (순수 Java)
│   └── catalog/                 # 룰 구현체 (OSC-001 작성 시 생성, 결정 16)
└── collect/                     # 수집 계층 경계 (HTTP 수집기·tar.gz 리더는 4단계에서)
```

**core 패키지(snapshot/rule/collect)는 Spring 비의존.** 순수 Java로 유지해 테스트를
가볍게 하고, Spring 와이어링(DI, picocli 통합)은 CLI 계층에서만 한다.

패키지는 **"무엇에 대한 것인가"로만 나눈다.** record/enum과 클래스를 갈라놓는
`entity/`·`model/`류 분리는 하지 않는다 — 이 프로젝트의 record 대부분이 도메인 로직을
들고 있어서(`ClusterSettings.effective()`의 설정 우선순위, `CollectTarget.isRequired()`의
필수/선택 정책, `ClusterSnapshot.absenceReason()`) 그 분리는 이름과 내용이 어긋난다.
게다가 같이 변하는 것들(타깃 추가 = `CollectTarget` + 파서 + 스냅샷)을 갈라놓는다.

## 2. `snapshot` 패키지 — ClusterSnapshot 필드 설계

`ClusterSnapshot`은 collect가 모은 모든 API 응답을 파싱해 담은 불변 record다.
룰이 보는 유일한 입력이며, live/offline 출처를 알지 못한다 (DESIGN.md 4.2).

**REQUIRED 타깃 필드는 값 그대로, OPTIONAL 타깃 필드는 `Optional`**이다 (DESIGN.md 3.1).

| 필드 | 타입 | 원본 API |
|---|---|---|
| `metadata` | `SnapshotMetadata` — 덤프 스키마 버전·수집 시각·도구 버전·클러스터 이름/버전·수집 리포트 | collect가 쓰는 `metadata.json` |
| `health` | `ClusterHealth` — status(enum GREEN/YELLOW/RED), 노드 수, 샤드 카운트류 | `_cluster/health` (REQUIRED) |
| `nodesStats` | `NodesStats` — 노드ID→NodeStats(name, roles, JVM heap, breakers 맵) | `_nodes/stats` (REQUIRED) |
| `settings` | `Optional<ClusterSettings>` — persistent/transient/defaults 3개 스코프, dotted key 평탄화 맵 | `_cluster/settings?include_defaults=true` |
| `allocationExplain` | `Optional<AllocationExplain>` — 설명 대상 샤드, unassigned 사유, can_allocate | `_cluster/allocation/explain` |
| `shards` | `Optional<List<ShardEntry>>` — index, shard, prirep, state, docs, storeBytes, node | `_cat/shards?format=json&bytes=b` |
| `indices` | `Optional<List<IndexEntry>>` — health, status, index, pri, rep, docsCount, storeSizeBytes | `_cat/indices?format=json&bytes=b` |
| `allocations` | `Optional<List<NodeAllocation>>` — shards, disk 사용량/가용량/percent, node | `_cat/allocation?format=json&bytes=b` |

필드 선정 기준은 **초기 룰 3개가 필요로 하는 데이터**다:

- **OSC-001**: `nodesStats`의 breaker tripped 카운트 + heap 사용률, `indices`의 `top_queries-*` 존재/크기
- **OSC-002**: `settings`의 `cluster.max_shards_per_node` × `nodesStats.dataNodeCount()` vs 현재 샤드 수
- **OSC-003**: `settings`의 `cluster.routing.allocation.enable` + `health`의 status/unassigned + `allocationExplain`

nullable 필드는 boxed 타입(`Long`, `Integer`)으로 표현한다
(예: unassigned 샤드 행의 `docs`/`storeBytes`/`node`, closed 인덱스의 `docsCount`).

`Optional<List<...>>`는 다소 거추장스럽지만 의도적이다. 빈 리스트로 대체하면 "샤드가 없다"와
"샤드 목록을 못 읽었다"가 같은 값이 되어 미탐으로 이어진다 (DESIGN.md 3.1).

### `ClusterSnapshot.absenceReason(target)`

룰이 `RuleResult.Skipped`에 넣을 사유 문자열을 만든다. `metadata`의 수집 리포트를 참조해
실패 사유까지 붙인다:

```
requires cluster_settings.json (collection failed: HTTP 403: no permissions for [cluster:monitor/settings])
requires cat_indices.json (not in dump)          # 리포트에 기록이 없는 경우(구버전 덤프)
```

### 파서 (`snapshot.parse` 패키지)

각 API의 wire format을 아는 유일한 장소. 이 규칙을 디렉토리로 드러내려고 모델과 분리했다.
OpenSearch 버전에 따라 응답 형태가 갈리는 분기가 생기면 전부 이 패키지 안에 가둔다.

- `ClusterSnapshotParser` — `RawDump` → `ClusterSnapshot` 변환
- `SizeParser` — `"1.2gb"` 같은 사람용 사이즈 문자열 파싱. **package-private**이라
  모델 패키지에서 보이지 않는다 (이동 후 가시 범위가 오히려 좁아졌다)
- `SnapshotParseException`

`RawDump` → `ClusterSnapshot` 변환 규칙:

- REQUIRED 타깃 누락 → `SnapshotParseException` (종료 코드 2)
- OPTIONAL 타깃 누락/빈 payload → 해당 필드 `Optional.empty()`
- **payload가 있는데 파손된 경우는 OPTIONAL이어도 예외.** 부분 덤프(partial)와 파손 덤프(broken)는
  다른 상황이고, 후자를 조용히 넘기면 미탐이 된다.

## 3. `rule` 패키지 — 룰 엔진

DESIGN.md 4.2의 인터페이스를 그대로 구현:

- `DiagnosticRule` — `id()` / `severity()` / `evaluate(ClusterSnapshot): RuleResult`.
  `severity()`는 룰의 명목(최악) 심각도이고, 실제 발생 건의 심각도는 `Finding`이 갖는다
  (OSC-002처럼 CRITICAL/WARNING 가변인 룰 대응).
- `RuleResult` — sealed interface. `Fired(Finding)` / `NotFired` / `Skipped(reason)`.
  DESIGN.md 4.3의 3-상태. `RuleResult.fired/notFired/skipped` 정적 팩토리 제공.
- `Finding` — ruleId / severity / finding(한 줄) / evidence 목록 / recommendation. 불변 record.
- `Evidence(source, value)` — `render()`하면 `"nodes.stats.breakers.parent.tripped = 847"` 형태.
- `Severity` — CRITICAL, WARNING, INFO. **enum 선언 순서가 곧 보고서 정렬 순서.**
- `SkippedRule(ruleId, reason)` — 평가되지 못한 룰과 그 사유.
- `DiagnosticReport(findings, skipped)` — 한 번의 diagnose 실행 결과 전부.
  **종료 코드는 `findings`만으로 정한다** (SKIPPED는 실행 오류가 아님, DESIGN.md 3.2).
- `RuleEngine` — 모든 룰 실행 후 finding은 심각도순(동률이면 ruleId순), skipped는 ruleId순 정렬.

## 4. `collect` 패키지 — 수집 계층 경계

- `CollectTarget` enum — 수집할 15개 엔드포인트 경로·**아카이브 내 파일명**·**필수/선택 등급**을
  한 곳에 못 박음. live 수집기와 tar.gz 리더가 공유하므로 두 모드의 덤프 구조가 항상 일치한다.
  `metadata.json` 파일명 상수도 여기에 있다.
  REQUIRED는 `CLUSTER_HEALTH`·`NODES_STATS` 둘뿐이고 나머지 13개는 OPTIONAL이다.
  뒤쪽 8개는 **수집만 하고 아직 파싱하지 않는다** (수집은 넓게, 파싱은 룰 수요 기반).
- `CollectionOutcome(target, status, httpStatus, message)` — 타깃별 수집 결과.
  `Status`는 `OK` / `FAILED` / `UNKNOWN`(신버전 덤프가 쓴 모르는 상태값).
  `describeFailure()`가 `"HTTP 403: no permissions for [...]"` 형태 문자열을 만든다.
- `RawDump` — 파싱 전 원본 JSON 묶음 (metadata + 타깃별 payload 맵).
  **collect 계층과 snapshot 파서 사이의 경계 타입**으로, live/offline 두 모드가 여기서 수렴한다.
- `DumpSource` — `RawDump`를 만들어내는 인터페이스. 구현체 2개(HTTP live 수집,
  tar.gz 아카이브 리더)는 4단계에서 작성한다.

### 덤프 호환 정책이 코드에서 강제되는 지점

- `CollectTarget`: 새 타깃은 항상 OPTIONAL. `ClusterSnapshotParserTest`의
  `areExactlyTheTargetsNoRuleCouldRunWithout()`가 REQUIRED 목록을 고정해 실수로 승격되는 것을 막는다.
- `SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION` = 1. 리더가 아는 것보다 높은 덤프는
  `isNewerThanSupported()`로 표시만 하고 아는 파일로 계속 진행한다.
  **경고 출력은 CLI 계층 몫이다** — core는 로깅 프레임워크에 의존하지 않는다.
- 모르는 파일/모르는 `CollectTarget` 이름/모르는 `Status`는 전부 무시하거나 `UNKNOWN`으로 흡수한다.

## 5. 테스트 현황

DESIGN.md 6절 전략대로 **픽스처 = 실제 API 응답 형태의 JSON**:

- `src/test/resources/fixtures/normal/` — 3노드 green 클러스터, 7개 타깃 전부 수집됨
  (정상/음성 픽스처의 원본). 파일 구성은 `CollectTarget`의 파일명과 동일 → offline 덤프 구조를 그대로 재현.
- `src/test/resources/fixtures/required-only/` — **덤프 결손 픽스처** (DESIGN.md 6절).
  REQUIRED 2개만 있고 OPTIONAL 5개는 전부 HTTP 403으로 수집 실패한 덤프.
  엔드포인트 확장 시의 하위호환 회귀 테스트 역할도 겸한다.
- `Fixtures` 테스트 헬퍼 — 픽스처 디렉토리를 `RawDump`로 로드 (없는 파일은 그냥 빠진 채로).
- `ClusterSnapshotParserTest` (16개)
    - 전 필드 파싱, 설정 우선순위, explain 에러 바디 처리, 수집 리포트 파싱
    - `RequiredTargets` — REQUIRED 2개 각각 누락 시 예외, REQUIRED 목록 고정 검증
    - `OptionalTargets` — 부재 시 `Optional.empty()`, `absenceReason` 문자열,
      payload 파손 시엔 여전히 예외
    - `ForwardCompatibility` — 신버전 덤프(모르는 필드/타깃/상태값, 높은 스키마 버전) 읽기,
      스키마 버전 필드가 없는 구버전 덤프는 1로 간주
- `RuleEngineTest` (4개) — 심각도·ruleId 정렬, SKIPPED와 NotFired 구분, 둘 혼재 시 수집.
- `SizeParserTest` (4개) — 숫자/사람용 단위/null/불량 입력.

룰별 3종 픽스처(양성/음성/경계)와 `ClusterSnapshotBuilder` 헬퍼는 룰 구현과 함께 작성한다.

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
| 8 | `CollectTarget`을 7개→15개로 확장 (`CLUSTER_PENDING_TASKS`, `CLUSTER_STATS`, `CAT_NODES`, `CAT_RECOVERY`, `CAT_SEGMENTS`, `CAT_PLUGINS`, `CAT_FIELDDATA`, `INDEX_TEMPLATES` 추가) | 초기 룰 3개가 요구하는 최소 필드만 모으던 원칙(DESIGN.md 4.3 인용 근거)을 넘어, 향후 룰이 필요로 할 만한 데이터를 미리 폭넓게 수집하기로 방향 전환. 근거: elastic/support-diagnostics(공식 진단 수집기)의 수집 목록과 AutoOps 이벤트 카탈로그(pending tasks, 플러그인 호환성, 세그먼트, fielddata 등)를 참고해 선정. HTTP 라이브 수집기가 아직 미구현 상태라 지금이 확장 비용이 가장 낮은 시점. **새 타깃은 아직 `ClusterSnapshotParser`가 파싱하지 않는다** — 룰이 실제로 필요로 할 때 파싱을 추가한다(수집과 파싱을 분리: collect는 넓게, parse는 룰 수요 기반) |
| 9 | REQUIRED는 `CLUSTER_HEALTH`·`NODES_STATS` 둘만 | 판정 기준을 "이게 없으면 어떤 룰도 못 도는가"로 잡음. 이 둘만 남기면 권한 제한·타임아웃으로 일부만 수집된 덤프도 진단 가능한 덤프가 된다. REQUIRED를 넓게 잡을수록 실패하는 덤프가 늘어난다. 결정 8로 타깃이 15개가 된 뒤 이 판정이 더 중요해졌다 — 8개는 파싱조차 하지 않으므로 REQUIRED일 수 없다 |
| 10 | OPTIONAL 부재는 `Optional`, 빈 컬렉션 대체 금지 | "샤드가 없다"와 "샤드 목록을 못 읽었다"가 같은 값이 되면 룰이 조용히 NotFired 하고 미탐이 된다. `Optional<List<T>>`의 거추장스러움을 감수한 이유 |
| 11 | payload가 있는데 파손이면 OPTIONAL이어도 예외 | 부분 덤프(partial)와 파손 덤프(broken)는 다른 상황이다. 전자는 계속 진행할 일이고 후자는 종료 코드 2로 알릴 일 |
| 12 | `RuleResult` 3-상태 도입 (`Optional<Finding>` 폐기) | OPTIONAL 타깃이 생긴 이상 "안 걸림"과 "못 봄"을 구분해야 한다. 룰 3개를 만들기 **전에** 시그니처를 바꾼 이유는, 나중에 바꾸면 룰과 룰 테스트를 전부 다시 손봐야 하기 때문 |
| 13 | 덤프 스키마 버전 경고를 core에서 출력하지 않음 | core는 로깅 프레임워크 비의존(결정 5). `SnapshotMetadata.isNewerThanSupported()`로 사실만 노출하고 출력은 CLI 계층이 정한다 |
| 14 | 모르는 `CollectTarget`/`Status`는 드롭·`UNKNOWN` 흡수 (`EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL`) | 신버전 덤프를 구버전 도구로 여는 전방 호환. 결정 6(시끄럽게 실패)의 예외인데, 이건 **덤프 파손이 아니라 어휘 차이**라 진단 자체는 문제없이 진행된다 |
| 15 | `dumpSchemaVersion`만 `Integer`(boxed) | Jackson 3는 `FAIL_ON_NULL_FOR_PRIMITIVES`가 기본 활성이라 필드 없는 구버전 덤프에서 `int` 매핑이 깨진다. 전역으로 끄면 다른 곳(예: health 카운트류)의 loud-fail까지 약해지므로 해당 필드만 boxed로 두고 compact 생성자에서 1로 정규화 |
| 16 | 룰 구현체는 `rule.catalog` 패키지로 분리 (**아직 미생성**) | 룰은 3개에서 20개 이상으로 늘어난다(AUTOOPS는 59종). 프레임워크 타입(`DiagnosticRule`/`RuleEngine`/`Finding`…)과 구현체가 한 디렉토리에 섞이면 그때는 탐색이 불가능해진다. 이름을 `rules`로 하면 `rule.rules`로 겹치고 `impl`은 의미가 없어 `catalog`를 골랐다(AUTOOPS 벤치마크 문서도 룰 모음을 "체크 카탈로그"라 부른다). **지금 만들면 빈 패키지이므로 OSC-001 작성 시점에 만든다** |

## 7. 다음 단계

DESIGN.md 9절 4단계 — **collect 구현(HTTP 수집기 + tar.gz 생성) → diagnose offline 모드
→ 룰 3개 → live 모드** 순서. 이 문서는 각 단계 완료 시 갱신한다.

collect 구현 시 반드시 함께 해야 하는 것:

- 타깃별 성공/실패를 `CollectionOutcome`으로 기록해 `metadata.json`에 쓴다.
  **실패한 타깃의 파일은 아카이브에 넣지 않는다** — 에러 바디를 payload로 저장하면
  파서가 그걸 정상 응답으로 읽으려다 파손 덤프로 취급한다.
- OPTIONAL 타깃 수집 실패는 collect를 중단시키지 않는다. REQUIRED 실패만 중단 사유다.
- `allocation/explain`의 HTTP 400은 실패로 기록하되(결정 2의 "설명할 것 없음"),
  이는 정상 클러스터의 기대 동작이므로 collect 종료 코드에 영향을 주지 않는다.