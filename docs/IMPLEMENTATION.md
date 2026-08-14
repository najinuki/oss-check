# oss-check — Implementation Notes

> 이 문서는 [DESIGN.md](DESIGN.md)의 설계 결정을 코드로 옮기면서 확정한 구현 구조와
> 진행 중 내린 세부 결정의 기록이다. 설계 자체의 변경은 DESIGN.md에서만 다룬다.
>
> 마지막 갱신: 2026-08-12 (v0.1 릴리스 완료. **DESIGN.md 10절 v0.2 인프라 착수** —
> 9단계 중 1~2단계까지)

## 1. 패키지 구조

```
com.nj.oss.check
├── OssCheckApplication          # Spring Boot 진입점 (컨텍스트 기동 → picocli 실행)
├── cli/                         # 명령·옵션·출력·종료 코드 (Spring/picocli 와이어링)
├── snapshot/                    # ClusterSnapshot 모델 (record·enum, 순수 Java)
│   └── parse/                   # wire format을 아는 유일한 곳 (파서·예외)
├── rule/                        # 룰 엔진 + 룰 공통 타입 (순수 Java)
│   └── catalog/                 # 룰 구현체 3개 + RuleCatalog (결정 16)
└── collect/                     # 수집 계층 경계 + HTTP 수집기·tar.gz 리더/라이터
```

**core 패키지(snapshot/rule/collect)는 Spring 비의존.** 순수 Java로 유지해 테스트를
가볍게 하고, Spring 와이어링(DI, picocli 통합)은 CLI 계층에서만 한다.

패키지는 **"무엇에 대한 것인가"로만 나눈다.** record/enum과 클래스를 갈라놓는
`entity/`·`model/`류 분리는 하지 않는다 — 이 프로젝트의 record 대부분이 도메인 로직을
들고 있어서(`ClusterSettings.effective()`의 설정 우선순위, `CollectTarget.isRequired()`의
필수/선택 정책, `ClusterSnapshot.absenceReason()`) 그 분리는 이름과 내용이 어긋난다.
게다가 같이 변하는 것들(타깃 추가 = `CollectTarget` + 파서 + 스냅샷)을 갈라놓는다.

## 2. `snapshot` 패키지 — ClusterSnapshot 필드 설계

`ClusterSnapshot`은 OpenSearch 클러스터의 **어느 한 시점 상태를 API 응답들로 찍은 사진**을
자바 객체로 옮긴 것이다. collect가 모은 응답을 전부 파싱해 담은 불변 record이며,
룰이 보는 유일한 입력이고 live/offline 출처를 알지 못한다. 왜 이런 형태인지는 DESIGN.md 4.3.

`snapshot` 패키지에 있는 record·enum은 **전부 이 사진의 부품**이다. 새로 보는 사람이
가장 헷갈리는 지점이 여기라 풀어 쓴다 — 이들은 우리가 설계한 도메인 모델이 아니라
**OpenSearch가 돌려주는 JSON 응답의 자바 표현**이다. 필드 이름이 어색해 보이면
(`prirep`, `pri`, `rep`) 대개 OpenSearch API가 그렇게 부르기 때문이다.

### 각 API가 무엇을 알려주는가

진단에 필요한 정보가 하나의 API에 모여 있지 않다는 것이 이 패키지가 존재하는 이유다.

| API | 한 줄로 | 이걸로 알 수 있는 것 |
|---|---|---|
| `_cluster/health` | 클러스터 **종합 진단서** | GREEN/YELLOW/RED, 노드 몇 대, 샤드 중 몇 개가 배정 안 됐는지. "문제가 있다"까지만 알려주고 원인은 말해주지 않는다 |
| `_nodes/stats` | 노드별 **활력 징후** | 각 노드의 JVM heap 사용률, 서킷 브레이커가 몇 번 터졌는지, 스레드풀 큐 상태. 가장 크고 정보가 많은 응답 |
| `_cluster/settings` | 운영자가 **손댄 설정** | `include_defaults=true`로 부르면 기본값까지 온다. 오설정 진단의 핵심 — "이 값이 기본값인가 누가 바꾼 것인가"를 구분해야 하기 때문 |
| `_cluster/allocation/explain` | 샤드가 **배정 안 되는 이유** | 클러스터가 직접 설명해주는 유일한 API. 정상 클러스터에서는 설명할 게 없어 HTTP 400을 낸다(결정 2) |
| `_cat/shards` | 샤드 **배치도** | 어느 인덱스의 몇 번 샤드가, 주샤드인지 복제본인지, 어느 노드에 있는지 |
| `_cat/indices` | 인덱스 **목록** | 인덱스별 문서 수·용량·샤드 수 |
| `_cat/allocation` | 노드별 **디스크 상황** | 노드마다 샤드 몇 개를 들고 디스크를 얼마나 쓰는지 |

> **`_cat/*` API란**: 원래 사람이 터미널에서 읽으라고 만든 표 형식 API다. `format=json`을
> 붙이면 각 행이 JSON 객체 하나인 배열로 온다. 컬럼 이름이 `docs.count`, `store.size`처럼
> 점을 포함하고 값이 전부 문자열이라 파싱에 주의가 필요하다(결정 1의 `bytes=b`가 이 때문).

여기에 collect가 직접 만들어 넣는 `metadata.json`이 더해진다 — 이건 클러스터가 준 게 아니라
**우리가 언제·무엇을 수집했는지 기록한 것**이다(DESIGN.md 3.1).

### 필드 매핑

**REQUIRED 타깃 필드는 값 그대로, OPTIONAL 타깃 필드는 `Optional`**이다 (DESIGN.md 3.1).

| 필드 | 타입 | 원본 API |
|---|---|---|
| `metadata` | `SnapshotMetadata` — 덤프 스키마 버전·수집 시각·도구 버전·클러스터 이름/버전·**식별 실패 사유**·수집 리포트 | collect가 쓰는 `metadata.json` |
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

15개 타깃 중 **파싱되는 것은 위 7개뿐**이다. 나머지 8개(`cluster_stats`, `cat_nodes`,
`cat_recovery`, `cat_segments`, `cat_plugins`, `cat_fielddata`, `cluster_pending_tasks`,
`index_templates`)는 수집만 하고 아직 스냅샷 필드가 없다 — 룰이 실제로 요구할 때 추가한다
(결정 8). 따라서 아카이브 파일 수와 스냅샷 필드 수가 다른 것이 정상이다.

nullable 필드는 boxed 타입(`Long`, `Integer`)으로 표현한다
(예: unassigned 샤드 행의 `docs`/`storeBytes`/`node`, closed 인덱스의 `docsCount`).
**OpenSearch 응답에서 값이 빠질 수 있는 자리**를 타입으로 드러내는 것이다 — 배정되지 않은
샤드는 아직 노드가 없어 `node`가 비고, 닫힌(closed) 인덱스는 문서 수를 보고하지 않는다.

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
  DESIGN.md 4.4의 3-상태. `RuleResult.fired/notFired/skipped` 정적 팩토리 제공.
- `Finding` — ruleId / severity / finding(한 줄) / evidence 목록 / recommendation. 불변 record.
- `Evidence(source, value)` — `render()`하면 `"nodes.stats.breakers.parent.tripped = 847"` 형태.
- `Severity` — CRITICAL, WARNING, INFO. **enum 선언 순서가 곧 보고서 정렬 순서.**
- `SkippedRule(ruleId, reason)` — 평가되지 못한 룰과 그 사유.
- `DiagnosticReport(findings, skipped)` — 한 번의 diagnose 실행 결과 전부.
  **종료 코드는 `findings`만으로 정한다** (SKIPPED는 실행 오류가 아님, DESIGN.md 3.2).
- `RuleEngine` — 모든 룰 실행 후 finding은 심각도순(동률이면 ruleId순), skipped는 ruleId순 정렬.

### 3.1 `rule.catalog` — 룰 구현체

`RuleCatalog.all()`이 이 빌드가 싣는 룰 전부다. 룰 추가 = 여기 한 줄 추가이며,
스캐닝·레지스트리·설정 파일은 없다(DESIGN.md 4.1). **카탈로그를 CLI가 아니라 룰 옆에 둔 이유**는
`rule` 패키지가 Spring 비의존이어야 하기 때문이다 — `@Component` 스캐닝을 쓰면 룰이
Spring을 알게 된다. CLI는 목록을 받아쓰기만 한다.

| 룰 | 클래스 | 발화 조건 | 없으면 SKIPPED |
|---|---|---|---|
| OSC-001 | `CircuitBreakerTrippingRule` | parent breaker `tripped > 0` **그리고** 같은 노드 heap ≥ 85% | 없음 (REQUIRED만 씀) |
| OSC-002 | `ShardLimitRule` | `max_shards_per_node × 데이터노드 수` 대비 샤드 수가 한도 도달(CRITICAL) 또는 90% 이상(WARNING) | `cluster_settings`, `cat_shards` |
| OSC-003 | `AllocationDisabledRule` | `cluster.routing.allocation.enable`의 **명시 설정값**이 `none` | `cluster_settings` |

각 룰에서 판단이 필요했던 지점:

- **OSC-001은 두 조건을 모두 요구한다.** `tripped`는 노드 기동 이후 누적이라 단독으로는 몇 달 전
  장애를 보고할 수 있고, heap 사용률 단독은 JVM이 정상 동작하는 모습이다. 둘이 겹쳐야
  "트립을 유발한 압박이 아직 유지된다"가 성립한다. finding 문구도 딱 거기까지만 말한다 —
  누적 카운터로 "지금 요청을 거절 중"이라고 단정할 수는 없다.
- **OSC-001은 SKIPPED되지 않는다**(결정 27). `top_queries-*` 보강이 빠질 뿐이고,
  `cat_indices`가 덤프에 없으면 evidence에 "확인하지 못했다"고 남긴다.
- **OSC-003은 `explicit()`로 읽는다.** 기본값이 `all`이므로 `none`은 항상 사람이 한 일이다(결정 4).
  발화 여부는 **실효값**으로 정하고(transient `all`이 persistent `none`을 덮으면 발화하지 않는다),
  조치안은 **값이 실제로 있는 scope**를 지운다(결정 28).
- **OSC-002는 설정값이 숫자가 아니면 SKIPPED**로 노출한다. 조용히 넘어가면 "정상"과 구별되지 않는다.

## 4. `collect` 패키지 — 수집 계층 경계

- `CollectTarget` enum — 수집할 15개 엔드포인트 경로·**아카이브 내 파일명**·**필수/선택 등급**을
  한 곳에 못 박음. live 수집기와 tar.gz 리더가 공유하므로 두 모드의 덤프 구조가 항상 일치한다.
  `metadata.json` 파일명 상수도 여기에 있다.
  REQUIRED는 `CLUSTER_HEALTH`·`NODES_STATS` 둘뿐이고 나머지 13개는 OPTIONAL이다.
  뒤쪽 8개는 **수집만 하고 아직 파싱하지 않는다** (수집은 넓게, 파싱은 룰 수요 기반).
  등급과 나란히 **`Cadence`(`PER_SAMPLE`/`SHARED`)**도 여기에 있다 — 구간 샘플링에서
  매 샘플 뜰 것과 한 번만 뜰 것의 구분이다(결정 33).
- `CollectionOutcome(target, status, httpStatus, message, collectedAt)` — 타깃별 수집 결과.
  `collectedAt`은 **그 응답을 받은 시각**이고, rate 계산의 분모가 여기서 나온다(결정 34).
  `Status`는 `OK` / `FAILED` / `UNKNOWN`(신버전 덤프가 쓴 모르는 상태값).
  `describeFailure()`가 `"HTTP 403: no permissions for [...]"` 형태 문자열을 만든다.
- `RawDump` — 파싱 전 원본 JSON 묶음 (metadata + 타깃별 payload 맵).
  **collect 계층과 snapshot 파서 사이의 경계 타입**으로, live/offline 두 모드가 여기서 수렴한다.
- `DumpSource` — `RawDump`를 만들어내는 인터페이스. 구현체는 2개다.
    - `HttpDumpSource` — live 수집. `CollectTarget`을 전부 순회하며 HTTP GET 하고
      타깃별 결과를 `CollectionOutcome`으로 기록한다. **REQUIRED 실패만 `IOException`으로
      중단**하고 OPTIONAL 실패는 리포트에 남긴 채 계속한다(DESIGN.md 3.1).
      클러스터 이름/버전은 루트 엔드포인트에서 얻는데, 이건 `CollectTarget`이 아니라
      덤프를 식별하는 정보라 실패해도 치명적이지 않다(이름 없는 덤프도 진단은 된다).
    - `TarGzDumpSource` — offline 리더. 아카이브 엔트리를 **경로가 아닌 파일명**으로
      `CollectTarget`에 매칭하므로 디렉토리로 묶인 덤프도 평평한 덤프와 똑같이 읽힌다.
      모르는 엔트리는 무시한다(신버전 덤프 전방 호환).
- `TarGzDumpWriter` — `RawDump` → tar.gz. 엔트리를 맵이 아닌 **enum 선언 순서**로 쓰고,
  `tar -xzf`만으로 열리는 아카이브를 만든다(도구 없는 호스트에서 응답을 확인하는 경우).
- `ClusterConnection` — 접속 정보(endpoint / user / password / insecure) record.
  생성 시점에 endpoint의 scheme·userinfo(결정 19)와 자격증명 짝 불변식(결정 22)을 검증한다.

### 덤프 호환 정책이 코드에서 강제되는 지점

- `CollectTarget`: 새 타깃은 항상 OPTIONAL. `ClusterSnapshotParserTest`의
  `areExactlyTheTargetsNoRuleCouldRunWithout()`가 REQUIRED 목록을 고정해 실수로 승격되는 것을 막는다.
- `SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION` = 1. 리더가 아는 것보다 높은 덤프는
  `isNewerThanSupported()`로 표시만 하고 아는 파일로 계속 진행한다.
  **경고 출력은 CLI 계층 몫이다** — core는 로깅 프레임워크에 의존하지 않는다.
- 모르는 파일/모르는 `CollectTarget` 이름/모르는 `Status`는 전부 무시하거나 `UNKNOWN`으로 흡수한다.

## 5. `cli` 패키지 — 명령 계층

**Spring과 picocli 와이어링이 사는 유일한 곳.** core(`snapshot`/`rule`/`collect`)는 이 패키지를
알지 못하고, 환경변수·TTY·파일시스템 경로·터미널 출력 같은 "바깥 세상"은 전부 여기서 다룬다.

```
collect:            옵션 → ClusterConnection → HttpDumpSource ─┐
diagnose --endpoint 옵션 → ClusterConnection → HttpDumpSource ─┤
diagnose --input                              TarGzDumpSource ─┴→ RawDump ─┐
                                                                           │
collect:   RawDump → TarGzDumpWriter → 파일                                │
diagnose:  RawDump → ClusterSnapshotParser → RuleEngine → 리포트 ←─────────┘
```

**`DumpSource` 하나만 갈아끼우면 live와 offline이 갈린다.** 그 뒤의 파싱·룰·리포트·종료 코드는
같은 코드이고, 오프라인 덤프가 라이브 실행의 충실한 리허설이 되는 근거가 이것이다(DESIGN.md 4.3).

- `OssCheckCommand` — 최상위 `oss-check`. 자체 옵션 없이 `collect`/`diagnose`만 단다(DESIGN.md 3).
  서브커맨드 없이 실행하면 usage를 내고 **종료 코드 2**로 끝난다 — 조용히 0으로 끝나면
  스크립트가 성공으로 읽는다. 정적 메서드 `commandLine(factory)`가 명령 트리 조립을 한 곳에
  모으므로 **main과 테스트가 같은 배선을 쓴다**(예외 핸들러가 실제로 붙어 있는지를 테스트가 본다).
- `CollectCommand` — 접속 옵션 + `--output`.
  부분 수집이면 빠진 타깃을 이름으로 나열하고 `metadata.json`을 가리킨다.
- `DiagnoseCommand` — `--input` 또는 접속 옵션 중 **정확히 하나**, 그리고 `--format text|json`.
  둘을 배타 그룹으로 묶어 picocli가 usage 오류로 잡는다(결정 30).
- `ClusterConnectionOptions` — `--endpoint` / `--user` / `--insecure`와 비밀번호 해석.
  `collect`는 `@Mixin`으로, `diagnose`는 배타 그룹의 한쪽으로 **같은 클래스**를 쓴다(결정 29).
- `PasswordSource` — 환경변수 → TTY 프롬프트 → 실패(DESIGN.md 3.1). 환경변수 조회와 `Console`을
  생성자로 받아 테스트 가능하게 했다. **프롬프트 경로 자체는 TTY가 필요해 테스트되지 않는다.**
  `ClusterConnectionOptions`가 소유하므로 두 명령이 같은 경로를 탄다.
- `ReportRenderer` / `ReportFormat` — 텍스트(사람)와 JSON(스크립트). JSON의 `collectedAt`은
  Jackson의 날짜 처리에 맡기지 않고 문자열로 직접 넣는다. 스크립트가 읽는 계약이 매퍼 설정
  변화에 흔들리면 안 된다.
- `ExitCode` — 0/1/2를 상수와 근거로 고정(DESIGN.md 3.2).
- `ExecutionErrorHandler` — 명령에서 새어나온 예외를 종료 코드 2로 바꾼다(결정 24).
- `ToolVersion` — jar 매니페스트에서 읽고 없으면 `"dev"`. 버전을 코드에 박아두면
  덤프가 빌드하지 않은 버전을 주장할 수 있다.
- `SpringFactory` — picocli가 명령을 Spring 빈으로 만들게 하는 다리(결정 23).

**출력 스트림 규약**: stdout은 결과(덤프 경로, 진단 리포트), stderr는 경고·에러.
스크립트가 stdout만 읽어도 되게 한다.

## 6. 테스트 현황

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
- `TarGzDumpRoundTripTest` — 쓰기→읽기 왕복, 파일명 매칭, 모르는 엔트리 무시.
- `ClusterConnectionTest` (7개) — 자격증명 짝 불변식(결정 22), 빈 비밀번호 허용,
  endpoint의 userinfo 거부 시 **예외 메시지에 비밀번호가 없는지**, scheme 제한(결정 19).
- `OssCheckCommandTest` (5개) — 도움말, 무인자 실행이 2로 끝나는지, 모르는 옵션, `--version`,
  그리고 **출하되는 명령 트리에 예외 핸들러가 붙어 있는지**.
- `ExecutionErrorHandlerTest` (3개) — 예외가 1이 아니라 2로 나가는지, 메시지 없는 예외도
  뭔가는 말하는지, 스택 트레이스를 쏟지 않는지.
- `CollectCommandTest` (5개) — `FakeCluster`로 실제 수집을 돌린다. 핵심은 **쓴 파일을
  `TarGzDumpSource`로 되읽어 15개 타깃이 그대로 나오는지** — 오프라인 리더가 못 읽는 덤프는
  쓸 이유가 없다. 그 외 부분 수집 보고, 덮어쓰기 거부(기존 내용 보존까지), 비밀번호 없을 때
  수집 전 중단, 기본 파일명 형식.
- `DiagnoseCommandTest` (11개) — 리포트 계약은 스텁 룰로 검증한다(실제 룰의 발화 조건과
  리포트 형식을 얽히게 하지 않기 위해). 깨끗한 클러스터(exit 0), **룰 0개 경고**,
  발화 시 evidence·recommendation 출력과 exit 1, **SKIPPED가 종료 코드를 바꾸지 않는지**,
  JSON 필드, 없는 덤프(exit 2),
  **REQUIRED 빠진 덤프가 조용히 "No findings"를 내지 않고 exit 2로 실패하는지**.
  live 모드는 4개 — 파일을 남기지 않는지, **라이브에서도 같은 룰이 도는지**(설정을 바꾼
  클러스터에서 OSC-003 발화 + exit 1), 두 입력 동시 지정 거부, 입력 없음 거부.
- `FakeCluster` (testsupport) — 로컬 `HttpServer`가 픽스처로 응답한다. 수집기를 스텁으로
  바꾸면 정작 검증하고 싶은 것(실제로 무언가와 통신하는가)이 사라지므로 진짜 소켓을 쓴다.
  `HttpDumpSourceTest`는 지연·리다이렉트 같은 HTTP 동작 자체를 보므로 자체 서버를 유지한다.
- `PasswordSourceTest` (3개) — 환경변수 경로, TTY도 변수도 없을 때 묻지 않고 실패, 빈 변수 허용.
- `ReportRendererTest` (3개) — 헤더가 클러스터를 밝히는지, **이름이 없을 때 그 이유를 말하는지**,
  JSON 리포트에도 같은 사유가 실리는지(결정 31).
- **룰 테스트 (22개)** — 룰마다 양성·음성·경계 3종(DESIGN.md 6절). 경계 테스트는 매직 넘버가
  아니라 룰의 상수(`HEAP_PRESSURE_PERCENT`, `WARNING_RATIO`)를 참조한다.
  음성 테스트는 전부 정상 픽스처 그대로를 넣는다 — 오탐 방어선이다.
- `ClusterSnapshotBuilder` (testsupport) — 정상 픽스처를 파싱한 뒤 **한 가지만 바꾼** 스냅샷을
  만든다. 룰마다 덤프 디렉토리를 새로 쓰면 바뀐 한 필드가 수백 줄 JSON에 묻히고, 무엇보다
  모든 룰 테스트가 "그 외에는 건강한 클러스터"에서 돌아야 엉뚱한 이유로 발화하는 룰이
  숨을 곳이 없다.
- `HttpDumpSourceTest` (12개) — **실제 `HttpServer`를 띄워** 검증한다(HTTP 클라이언트를
  목으로 대체하면 정작 검증하고 싶은 것이 사라진다). 픽스처는 파서 테스트와 같은
  `fixtures/normal/`을 응답 본문으로 재사용한다.
    - 전 타깃 수집 + 선언된 경로로 요청, Basic 인증 헤더 유무
    - OPTIONAL 실패(403) 시 수집 계속 + `absenceReason`까지 이어지는지,
      `allocation/explain`의 400이 치명적이지 않은지, REQUIRED 실패(500) 시 중단
    - 루트 엔드포인트가 막혀도 진단 가능한 덤프가 나오는지, 에러 바디 절단
    - **인터럽트 시 부분 덤프 대신 중단**(결정 18) — OPTIONAL 타깃 응답을 서버에서
      멈춰 세우고 수집 스레드를 인터럽트한다
    - **리다이렉트를 따라가지 않음**(결정 20) — 302를 실패로 기록하는지 확인

룰별 3종 픽스처(양성/음성/경계)와 `ClusterSnapshotBuilder` 헬퍼는 룰 구현과 함께 작성한다.

## 7. 구현 중 내린 세부 결정 (결정 로그)

| # | 결정 | 이유 |
|---|---|---|
| 1 | _cat 엔드포인트에 `bytes=b` 파라미터 추가 | 사이즈를 `"1.2gb"` 같은 사람용 문자열이 아닌 숫자 바이트로 수집 → 파싱 견고성. DESIGN.md 3.1의 엔드포인트 목록에서 벗어난 유일한 변경. 단 `SizeParser`는 사람용 단위도 허용해 외부 제작 덤프와 호환 |
| 2 | `allocation/explain`의 HTTP 400 에러 바디 → `Optional.empty()` | unassigned 샤드가 없는 정상 클러스터에서 이 API는 에러를 반환한다. 이는 "설명할 것 없음"이지 파싱 실패가 아님 |
| 3 | 클러스터 설정을 파싱 시점에 dotted key로 평탄화 | 룰이 설정 API에 넘기는 것과 같은 키(`cluster.routing.allocation.enable`)로 조회 가능. 배열 값은 콤마 결합 문자열로 평탄화 |
| 4 | `ClusterSettings.effective()` vs `explicit()` 구분 | `effective`는 transient > persistent > defaults 우선순위 적용(실제 동작값), `explicit`은 운영자가 명시한 값만(transient/persistent). OSC-003의 "오설정 감지"는 기본값과 명시 설정을 구분해야 함 |
| 5 | core 패키지는 Spring 비의존 | 룰·파서 테스트가 Spring 컨텍스트 없이 도는 순수 단위 테스트. Spring/picocli 와이어링은 CLI 계층에서만 |
| 6 | 필수 파일 누락·JSON 파손 시 즉시 예외 (`SnapshotParseException`) | 조용히 넘어가면 미탐으로 이어짐. 실행 오류는 종료 코드 2로 구분되므로(DESIGN.md 3.2) 시끄럽게 실패하는 것이 맞다 |
| 7 | Jackson 3 (`tools.jackson`, Spring Boot 4 관리 버전) 사용 | Boot 4의 기본 Jackson 세대와 통일. Jackson 2를 별도 추가하면 배포 jar에 두 세대가 공존하게 됨 |
| 8 | `CollectTarget`을 7개→15개로 확장 (`CLUSTER_PENDING_TASKS`, `CLUSTER_STATS`, `CAT_NODES`, `CAT_RECOVERY`, `CAT_SEGMENTS`, `CAT_PLUGINS`, `CAT_FIELDDATA`, `INDEX_TEMPLATES` 추가) | 초기 룰 3개가 요구하는 최소 필드만 모으던 원칙을 넘어, 향후 룰이 필요로 할 만한 데이터를 미리 폭넓게 수집하기로 방향 전환. 근거: elastic/support-diagnostics(공식 진단 수집기)의 수집 목록과 AutoOps 이벤트 카탈로그(pending tasks, 플러그인 호환성, 세그먼트, fielddata 등)를 참고해 선정. HTTP 라이브 수집기가 아직 미구현 상태라 지금이 확장 비용이 가장 낮은 시점. **새 타깃은 아직 `ClusterSnapshotParser`가 파싱하지 않는다** — 룰이 실제로 필요로 할 때 파싱을 추가한다(수집과 파싱을 분리: collect는 넓게, parse는 룰 수요 기반) |
| 9 | REQUIRED는 `CLUSTER_HEALTH`·`NODES_STATS` 둘만 | 판정 기준을 "이게 없으면 어떤 룰도 못 도는가"로 잡음. 이 둘만 남기면 권한 제한·타임아웃으로 일부만 수집된 덤프도 진단 가능한 덤프가 된다. REQUIRED를 넓게 잡을수록 실패하는 덤프가 늘어난다. 결정 8로 타깃이 15개가 된 뒤 이 판정이 더 중요해졌다 — 8개는 파싱조차 하지 않으므로 REQUIRED일 수 없다 |
| 10 | OPTIONAL 부재는 `Optional`, 빈 컬렉션 대체 금지 | "샤드가 없다"와 "샤드 목록을 못 읽었다"가 같은 값이 되면 룰이 조용히 NotFired 하고 미탐이 된다. `Optional<List<T>>`의 거추장스러움을 감수한 이유 |
| 11 | payload가 있는데 파손이면 OPTIONAL이어도 예외 | 부분 덤프(partial)와 파손 덤프(broken)는 다른 상황이다. 전자는 계속 진행할 일이고 후자는 종료 코드 2로 알릴 일 |
| 12 | `RuleResult` 3-상태 도입 (`Optional<Finding>` 폐기) | OPTIONAL 타깃이 생긴 이상 "안 걸림"과 "못 봄"을 구분해야 한다. 룰 3개를 만들기 **전에** 시그니처를 바꾼 이유는, 나중에 바꾸면 룰과 룰 테스트를 전부 다시 손봐야 하기 때문 |
| 13 | 덤프 스키마 버전 경고를 core에서 출력하지 않음 | core는 로깅 프레임워크 비의존(결정 5). `SnapshotMetadata.isNewerThanSupported()`로 사실만 노출하고 출력은 CLI 계층이 정한다 |
| 14 | 모르는 `CollectTarget`/`Status`는 드롭·`UNKNOWN` 흡수 (`EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL`) | 신버전 덤프를 구버전 도구로 여는 전방 호환. 결정 6(시끄럽게 실패)의 예외인데, 이건 **덤프 파손이 아니라 어휘 차이**라 진단 자체는 문제없이 진행된다 |
| 15 | `dumpSchemaVersion`만 `Integer`(boxed) | Jackson 3는 `FAIL_ON_NULL_FOR_PRIMITIVES`가 기본 활성이라 필드 없는 구버전 덤프에서 `int` 매핑이 깨진다. 전역으로 끄면 다른 곳(예: health 카운트류)의 loud-fail까지 약해지므로 해당 필드만 boxed로 두고 compact 생성자에서 1로 정규화 |
| 16 | 룰 구현체는 `rule.catalog` 패키지로 분리 (**아직 미생성**) | 룰은 3개에서 20개 이상으로 늘어난다(AUTOOPS는 59종). 프레임워크 타입(`DiagnosticRule`/`RuleEngine`/`Finding`…)과 구현체가 한 디렉토리에 섞이면 그때는 탐색이 불가능해진다. 이름을 `rules`로 하면 `rule.rules`로 겹치고 `impl`은 의미가 없어 `catalog`를 골랐다(AUTOOPS 벤치마크 문서도 룰 모음을 "체크 카탈로그"라 부른다). **지금 만들면 빈 패키지이므로 OSC-001 작성 시점에 만든다** |
| 17 | tar.gz 처리에 Apache Commons Compress 채택 | JDK에는 gzip(`java.util.zip`)만 있고 tar가 없다. 덤프는 도구 없이 `tar -xzf`로 열어볼 수 있어야 하는 물건이라(폐쇄망에서 반출된 덤프를 도구 없는 호스트에서 확인하는 경우), USTAR 헤더 체크섬·패딩·긴 파일명을 직접 구현해 미묘하게 어긋나는 위험을 감수할 이유가 없다. **비용은 전이 의존성 포함 4개·약 2.7MB** (commons-compress 1091KB / commons-lang3 697KB / commons-io 551KB / commons-codec 393KB). 배포 jar 14.3MB 중 19%이며, 가장 큰 덩어리는 Spring Boot 계열 8.4MB(59%)다. 직접 구현하면 약 150줄로 이 2.7MB를 줄일 수 있으나 포맷 정합성 리스크와 맞바꾸는 선택이 된다 |
| 18 | HTTP 수집 중 `InterruptedException`은 타깃 실패로 기록하지 않고 `InterruptedIOException`으로 즉시 중단 | 인터럽트를 다른 수집 실패와 똑같이 기록하고 계속 돌면, 남은 타깃이 전부 OPTIONAL일 때 **취소된 실행이 "부분 수집 성공"으로 둔갑**한다 — 사용자가 Ctrl-C로 끊은 덤프가 진단 가능한 덤프처럼 보이는 미탐이고, 결정 6·10과 같은 계열이다. interrupt flag를 복원하고 원인을 cause로 보존해 던지므로 취소했다는 사실이 호출자에게 남는다. `InterruptedIOException`은 `IOException`이라 `DumpSource.load()` 계약과 종료 코드 2 경로는 그대로다 |
| 19 | `ClusterConnection`이 endpoint의 scheme(http/https)과 userinfo 부재를 생성 시점에 검증 | REQUIRED 수집 실패 메시지가 endpoint 문자열을 그대로 담기 때문에, `https://user:pw@host` 형태를 허용하면 **자격증명이 에러 메시지로 새어나간다**. userinfo를 scheme보다 먼저 검사하는 이유도 같다 — scheme 오류 메시지가 URL을 출력한다. query·fragment 등 나머지 URI 구성요소까지 막지는 않았다(발생하지 않을 시나리오의 에러 처리) |
| 20 | 자동 리다이렉트 비활성화 (`Redirect.NEVER`) | 모든 요청에 Basic 인증 헤더를 직접 붙이므로, 리다이렉트를 따라가면 `Location`이 가리키는 아무 호스트에나 자격증명이 갈 수 있다. OpenSearch API는 리다이렉트하지 않으니 얻을 것이 없는 위험이다. 끄고 나면 JDK가 cross-origin 리다이렉트에서 `Authorization`을 떼는지 여부도 따질 필요가 없어진다 |
| 21 | `--insecure`의 범위를 **인증서 신뢰**로 한정 (호스트명 검증은 유지) | JDK `HttpClient`는 넘겨받은 `SSLParameters`와 무관하게 HTTPS endpoint identification을 다시 켠다. 즉 `setEndpointIdentificationAlgorithm(null)`은 효과가 없었고, 호스트명 검증을 실제로 끄는 방법은 JVM 전역 시스템 속성(`jdk.internal.httpclient.disableHostnameVerification`)뿐인데 이는 프로세스 전체에 영향을 준다. 동작하지 않는 코드와 "이게 동작한다"고 주장하는 주석을 남기는 대신 **계약을 실제 동작에 맞췄다** — DESIGN.md 3.1의 "자체 서명 인증서 허용"과도 이미 일치한다. IP로 접속하려면 그 IP를 포함한 인증서가 필요하다는 제약은 `ClusterConnection` Javadoc에 명시 |
| 22 | `username`/`password` 짝 불변식을 `ClusterConnection` 생성자에서 강제 (빈 사용자명도 거부) | DESIGN.md 3.1이 `--password` 옵션을 없애고 프롬프트/환경변수로 바꾸면서 "함께 있거나 함께 없다"를 확정했다. 한쪽만 있으면 `hasCredentials()`가 `false`가 되어 **사용자는 인증했다고 믿는데 익명 요청이 나가고**, 돌아온 401/403이 수집 리포트에 "이 계정에 권한 없음"으로 박힌다 — 설정 실수가 클러스터 권한 문제로 위장되고, 덤프를 몇 달 뒤 여는 사람은 그 위장을 풀 수 없다. 불변식이 생기면서 `hasCredentials()`는 `username != null`로 단순해졌다. **빈 문자열 비밀번호는 허용**한다: 일부러 준 빈 값과 아예 안 온 것(`null`)은 다른 사실이라는 결정 10과 같은 결이다. 환경변수·프롬프트·TTY 판정은 전부 CLI 몫이고 core는 이 불변식만 진다 |
| 23 | Spring을 **인자 없이** 기동하고 argv는 picocli에만 넘긴다 (`CommandLineRunner` 미사용) | 둘 다에게 argv를 주면 `--user admin` 같은 옵션이 Spring 애플리케이션 프로퍼티로 해석된다. 덤으로 `CommandLineRunner`를 안 쓰므로 `@SpringBootTest`가 컨텍스트를 띄우면서 CLI를 실행해버리는 문제도 없다. Spring은 빈만 제공하고 명령줄의 주인은 picocli다 |
| 24 | 명령에서 새어나온 예외를 종료 코드 **2**로 매핑 (`ExecutionErrorHandler`) | picocli의 기본 실행 예외 종료 코드는 1인데 이 도구에서 1은 **"finding 발견"**이다(DESIGN.md 3.2). 그대로 두면 접속 불가·덤프 파손 같은 실행 실패를 스크립트가 **진단 결과로 오인**한다. Spring 기동 실패도 같은 이유로 `main`에서 잡는다. 운영자에게는 스택 트레이스가 아니라 한 줄 메시지를 준다 |
| 25 | 덤프 덮어쓰기 금지를 **두 겹**으로 (CLI 사전 검사 + `CREATE_NEW`) | 사전 검사만으로는 수집이 도는 수십 초 동안 같은 경로에 파일이 생기면 그대로 덮어쓴다(TOCTOU). 실제 보장은 `TarGzDumpWriter`가 `StandardOpenOption.CREATE_NEW`로 파일을 만들며 원자적으로 거부하는 것이고, CLI의 사전 검사는 **60초 수집 후에 실패를 알리지 않기 위한 예의**다. 보장을 라이터에 두면 앞으로 어떤 호출자도 덤프를 실수로 날릴 수 없다. 대신 `CREATE_NEW`는 부작용을 하나 만든다 — 쓰다 실패하면(디스크 부족 등) 반쪽 파일이 남아 **재시도까지 막는다**. 그래서 쓰기 실패 시 이 호출이 만든 파일을 지운다. 임시 파일+rename은 이름 선점 시점이 뒤로 밀려 동시 실행 방어가 약해지므로 택하지 않았다. JVM이 쓰는 도중 죽으면 반쪽 파일이 남는 것은 이 선택의 잔여 비용이다 |
| 26 | 룰이 0개면 diagnose가 stderr로 경고한다 | 카탈로그가 비어 있는 빌드는 **무조건 "No findings"**를 낸다. 이건 구조적 미탐이라, 빈 리포트가 건강한 클러스터로 읽히지 않게 소리를 낸다. 룰이 생기면 조건이 저절로 거짓이 되어 사라진다. 덤프 스키마 버전 경고(결정 13)도 같은 자리에서 낸다 — 둘 다 "이 리포트는 보이는 것보다 좁다"는 뜻이기 때문 |
| 27 | OSC-001은 `cat_indices`가 없어도 SKIPPED되지 않는다 (DESIGN.md 5 표 정정) | 발화 조건(브레이커 트립 + heap)이 REQUIRED 타깃인 `_nodes/stats`만으로 판정된다. `cat_indices`는 `top_queries-*` 방치 패턴을 덧붙이는 보강용일 뿐인데, 그것 때문에 서킷 브레이커 트립을 판정하지 않는 것은 손해가 더 크다 — 무관한 엔드포인트의 403이 진짜 장애를 가린다. 대신 evidence에 "확인하지 못했다"를 남긴다: 인덱스가 **없는 것**과 **못 본 것**은 다른 사실이다(결정 10과 같은 결) |
| 28 | OSC-003의 조치안은 값이 실제로 있는 **scope**를 지운다 | 롤링 재시작 중에는 `transient`로 거는 것이 흔하고 transient가 persistent를 이긴다. 조치안이 persistent만 지우면 운영자가 그대로 실행해도 **allocation은 계속 꺼져 있고 본인은 켰다고 믿는다** — 동작하지 않는 조치안은 근거 없는 finding만큼 해롭다. 두 scope에 모두 `none`이면 둘 다 지운다(transient만 지우면 persistent가 살아난다). 발화 여부는 실효값으로 정하므로 transient `all`이 persistent `none`을 덮는 클러스터에서는 발화하지 않는다 |
| 29 | 접속 옵션을 `ClusterConnectionOptions` 한 클래스로 공유 (`collect`는 `@Mixin`, `diagnose`는 배타 그룹 멤버) | DESIGN.md 3.2가 "같은 수집기를 쓰는 이상 인증 경로가 갈리면 안 된다"고 못 박았다. 옵션을 복사하면 도움말 문구부터 비밀번호 해석 순서까지 **언젠가 한쪽만 바뀐다** — 그때 갈라지는 것이 하필 인증이면 한쪽 명령에서만 익명 요청이 나가는 식의 사고가 된다. 비밀번호 해석(`PasswordSource` 소유)까지 이 클래스에 함께 둔 이유도 같다 |
| 30 | `diagnose`의 입력 소스는 배타 그룹 `multiplicity = "1"` (기본값 없음) | `--input`과 `--endpoint` 중 하나를 조용히 우선하면 **리포트가 어느 클러스터 얘기인지 알 수 없다**. 덤프는 몇 달 전 다른 곳의 상태일 수 있어서, 잘못 고른 쪽으로 진단하면 존재하지 않는 장애를 보고하거나 실제 장애를 놓친다. 둘 다 없을 때 무언가를 기본값으로 고르지 않고 usage 오류(종료 코드 2)로 끝내는 것도 같은 이유다 |
| 31 | 클러스터 식별 실패를 `metadata.json`의 `identity_failure`로 기록 (DESIGN.md 3.1) | 루트 엔드포인트는 `CollectTarget`이 아니라 수집 리포트에 남을 자리가 없어, 403이든 "2xx인데 본문이 JSON이 아님"이든 이름·버전만 null이 되고 이유가 사라졌다. 후자는 앞단 프록시가 로그인 페이지를 200으로 돌려주는 전형적인 상황이라 실제로 마주친다. **본문 자체는 저장하지 않는다** — 그 HTML을 파일로 넣으면 결정 11("payload가 있는데 파손이면 예외")에 걸려 덤프 전체가 안 읽힌다. 사유 문자열만 남기고 리포트 헤더가 그것을 노출한다. 필드 추가는 구조를 깨지 않으므로 `dumpSchemaVersion`은 올리지 않았다. 부수적으로 **DESIGN.md 3.1의 `metadata.json` 예시가 camelCase로 적혀 있던 것을 실제 wire format인 snake_case로 정정**했다 — 문서대로 손으로 덤프를 만들면 파서가 못 읽는 상태였다 |
| 32 | `metadata.json`에 **파생 상태를 쓰지 않고 null도 생략** (writer 매퍼의 mixin + `NON_NULL`) | 실제 클러스터로 처음 돌려본 덤프에 `ok`·`identified`·`newer_than_supported`가 들어 있었다. Jackson이 `isOk()` 같은 편의 메서드를 bean getter로 인식해 record 컴포넌트가 아닌데도 기록한 것이다. 두 개는 해롭기까지 하다 — `ok`는 `status`와 같은 사실을 두 번 적어 **몇 달 뒤 읽는 파일에 진실 원천이 둘**이 되고, `newer_than_supported`는 *읽는 쪽이 자기 버전과 비교해 내리는 판단*이라 쓰는 시점엔 언제나 `false`다. null 생략은 DESIGN.md 3.1의 "식별에 성공하면 이 필드는 없다"를 코드가 실제로 지키게 만든다. **mixin을 모델이 아니라 writer 쪽에 둔 이유**는 `SnapshotMetadata`/`CollectionOutcome`이 wire format을 알지 않게 하기 위해서다. 읽기는 영향받지 않는다 — 파서는 모르는 필드를 무시하고 없는 필드는 null로 둔다(결정 14) |
| 33 | `CollectTarget`에 `Cadence`(`PER_SAMPLE`/`SHARED`) 추가, **기본은 `PER_SAMPLE`** | 구간 샘플링에서 무엇을 매번 뜰지 정해야 하는데, 기준을 "구간 안에서 변할 수 있는가"로 잡으면 `cluster_settings`가 공유로 분류된다 — 장애 대응 중 **가장 자주 바뀌는** 값이고 OSC-003의 존재 이유가 "설정을 바꿔놓고 잊었다"인데도 그렇다. 그래서 기준을 **"변하면 진단이 달라지는가 + 반복 비용을 감당할 수 있는가"**로 잡았다. SHARED는 셋뿐이고 **그 근거도 하나가 아니다**: `index_templates`·`cat_plugins`는 구조적으로 정적이고, `cat_segments`는 **정적이 아니지만**(색인·merge로 계속 변한다) 과다 세그먼트가 시간 단위로 쌓이는 상태라 1분 구간에서 판정이 뒤집히지 않고 응답이 가장 크다. 하나로 뭉뚱그려 "정적인 것"이라 적으면 틀린 주장이 문서에 남고 다음 사람이 그 기준으로 새 타깃을 분류한다. 가드 테스트가 SHARED 집합을 고정하고 `cluster_settings`가 `PER_SAMPLE`인지를 따로 한 번 더 본다 |
| 34 | `CollectionOutcome`에 `collectedAt`(응답 수신 시각) 추가 | v0.1은 15개 타깃을 **다 돈 뒤** `clock.instant()`를 한 번 찍었다. `_nodes/stats`는 순회 앞쪽이라 **카운터를 T+1초에 읽고 시각은 T+15초로 기록**됐고, 단일 스냅샷에서는 "언제 뜬 덤프인가" 표시용이라 무해했다. **v0.2는 그 시각으로 나눗셈을 한다** — 샘플마다 수집 소요가 다르면(장애 중에는 응답이 전반적으로 느려진다) 분모가 틀리고, 30초 간격에서 몇 초만 어긋나도 rate가 십수 퍼센트 흔들린다. 근거로 내놓는 숫자가 조용히 틀리는 자리라 타깃마다 찍는다. 샘플 단위 시각은 `startedAt`으로 따로 둔다(DESIGN.md 10.2) |

## 8. 다음 단계

DESIGN.md 9절 4단계 — **collect 구현(HTTP 수집기 + tar.gz 생성) → diagnose offline 모드
→ 룰 3개 → live 모드** 순서. 이 문서는 각 단계 완료 시 갱신한다.

수집 계층(`HttpDumpSource` / `TarGzDumpSource` / `TarGzDumpWriter`)은 구현됐다.
위 항목들은 코드와 테스트로 지켜지고 있다:

- 타깃별 성공/실패를 `CollectionOutcome`으로 기록해 `metadata.json`에 쓴다.
  **실패한 타깃의 파일은 아카이브에 넣지 않는다** — 에러 바디를 payload로 저장하면
  파서가 그걸 정상 응답으로 읽으려다 파손 덤프로 취급한다.
- OPTIONAL 타깃 수집 실패는 collect를 중단시키지 않는다. REQUIRED 실패만 중단 사유다.
- `allocation/explain`의 HTTP 400은 실패로 기록하되(결정 2의 "설명할 것 없음"),
  이는 정상 클러스터의 기대 동작이므로 collect 종료 코드에 영향을 주지 않는다.

CLI 계층(5절)도 구현됐다. core가 의도적으로 미뤄뒀던 것들이 여기서 처리됐다:
덤프 스키마 버전 경고 출력(결정 13), 종료 코드(DESIGN.md 3.2),
비밀번호 프롬프트/환경변수(DESIGN.md 3.1).

비밀번호 처리는 계층이 갈린다. **환경변수를 읽고 프롬프트를 띄우고 TTY를 판정하는 것은
전부 CLI의 일**이다 — `ClusterConnection`이 환경을 알게 되면 core의 환경 비의존이 깨진다.
core가 지는 책임은 "사용자명과 비밀번호는 함께 있거나 함께 없다"는 불변식뿐이다(결정 22).

룰 3개(3.1절)도 구현됐다. `rule.catalog` 패키지, OSC-001/002/003, 룰별 3종 테스트와
`ClusterSnapshotBuilder` 헬퍼까지 DESIGN.md 5·6절대로다.
`diagnose --endpoint` live 모드까지 붙으면서 **DESIGN.md 9절 4단계는 전부 끝났다.**

**다음은 README**다(영문 필수, DESIGN.md 1). 들어가야 하는 것:
지원 버전(2.10 ~ 3.x, 그 미만은 미지원), 두 명령의 사용법과 종료 코드,
비밀번호를 옵션으로 받지 않는다는 점, 그리고 **덤프에 인덱스명이 포함된다는 경고**
(자동 마스킹은 v0.1 제외, DESIGN.md 6절).

README와 LICENSE(Apache 2.0, DESIGN.md 1절)는 작성됐고 버전도 `0.1.0`으로
맞췄다. **DESIGN.md 9절의 계획된 작업은 이것으로 전부 끝났다.** 이후는 아래 남은 숙제와
v0.2 백로그(DESIGN.md 8절)다.

### 남은 숙제

- **비밀번호 프롬프트 경로가 테스트되지 않는다.** TTY가 필요해서다. 환경변수 경로와
  "TTY도 변수도 없으면 실패"는 테스트된다.

## 9. v0.2 — 구간 샘플링 인프라 (진행 중)

DESIGN.md 10절이 확정한 v0.2의 첫 항목(10.4의 1번)을 구현 중이다. 빌드가 항상 초록인
상태로 가도록 아홉 조각으로 나눴다. **진행 상황의 기록은 이 표 하나뿐이다** — DESIGN.md는
무엇을 만들지를, 이 문서는 어디까지 왔는지를 담는다. 두 곳에 적으면 언젠가 갈라진다.

| # | 단계 | 상태 |
|---|---|---|
| 1 | `CollectTarget.Cadence` — `PER_SAMPLE` / `SHARED` | ✅ 결정 33 |
| 2 | `CollectionOutcome.collectedAt` — 타깃별 수신 시각 | ✅ 결정 34 |
| 3 | `SnapshotMetadata` 재구성 — `shared` + `samples[]`, 스키마 2, 버전 1 거부 | |
| 4 | `RawDump` 구조 변경 — 공유 payload + 샘플별 payload 목록 | |
| 5 | `TarGzDumpWriter` / `TarGzDumpSource` — `collection/` 레이아웃, 경로 인식 | |
| 6 | `HttpDumpSource` N회 수집 — 샘플 폐기 정책 | |
| 7 | `ClusterSnapshotParser` → `SnapshotSequence` | |
| 8 | 시퀀스 룰 인터페이스 + `RuleEngine` 통합 | |
| 9 | CLI `--samples` / `--interval` | |

**3단계부터 wire format이 바뀐다.** 픽스처의 `metadata.json`과 파서 테스트가 함께 움직이고,
`Fixtures`는 평평한 픽스처 디렉토리를 "공유분 + 샘플 1개"로 해석하도록 바뀐다.
픽스처 파일 자체는 건드리지 않는다 — `Fixtures`가 tar 리더를 거치지 않고 리소스에서 직접
`RawDump`를 만들기 때문이다(DESIGN.md 10.2의 구현 파급 표).