# os-check — Design Decisions (v0.1)

> 이 문서는 프로젝트의 확정된 설계 결정사항이다. 코드 작성 시 이 문서를 기준으로 하며,
> 여기 명시된 스코프를 벗어나는 기능 추가는 사전 논의 없이 하지 않는다.

## 1. 프로젝트 개요

- **이름(가칭)**: `os-check` (또는 `oss-check`, 추후 확정. "OpenSearch" 상표를 프로젝트명 선두에 쓰지 않는다)
- **개념**: OpenSearch 클러스터 진단 CLI. 여러 API 응답(`_cluster/health`, `_nodes/stats`, `_cat/shards` 등)을
  교차 분석해 근본 원인을 추론하고, **근거(evidence) + 조치안(recommendation)**까지 출력한다.
  단순 임계값 체크 도구가 아니라, 실제 장애 추적 과정의 추론 체인을 룰로 코드화한 것이 본체다.
- **핵심 차별화**: **air-gapped by design.** 폐쇄망(에어갭)에서 완전 동작.
  텔레메트리·업데이트 체크·외부 호출 제로. 유일한 네트워크 통신은 대상 클러스터뿐.
- **라이선스**: Apache 2.0. **README는 영문 필수.**

## 2. 기술 스택 (확정)

- **Java 25** + **Spring Boot** + **picocli** (CLI 파싱)
- 배포: **Spring Boot fat jar** (`./gradlew bootJar` → `java -jar os-check.jar`).
  대상 호스트에 **Java 25만 있으면 되고** 별도 설치가 필요 없다는 뜻이며,
  라이브러리를 쓰지 않는다는 뜻이 아니다(의존성은 `BOOT-INF/lib/`에 jar 채로 중첩된다).
  섀도잉으로 클래스를 펼치는 진짜 uber-jar가 아니므로 클래스 충돌·relocation 문제가 없다.
- OpenSearch 지원 버전: **2.10 ~ 3.x**. 2.10 미만(1.x 포함)은 **미지원**으로 README에 명시.
  하한을 2.10으로 잡은 이유는 그 이전 버전에서만 존재하는 API 형태를 파서가 분기 처리하지
  않기 위해서다. 상한 3.x는 수집 엔드포인트의 응답 구조가 2.x와 호환되는 범위까지를 뜻한다.
- 출력 언어: **영문 단일**

Spring Boot 선택 이유: 개발자 생산성 + v0.2의 MCP 서버 모드(장기 실행 프로세스) 확장 기반.
CLI 도구치고 무겁다는 트레이드오프는 인지하고 수용한 결정이다.

## 3. 명령어 명세 (2개, 그 이상 없음)

### 3.1 `collect` — API 응답 일괄 수집 → tar.gz 생성

폐쇄망에서 덤프를 반출해 외부에서 분석하는 시나리오용.

사용자는 접속 정보만 준다. 엔드포인트를 하나씩 호출하는 일은 collect가 전부 대신한다.

- **접속 옵션**: `--endpoint`, `--user` / `--password` (환경변수 대체 가능), `--insecure` (자체 서명 인증서 허용)
- 외부 통신은 대상 클러스터 단 하나뿐

#### 수집 엔드포인트와 필수/선택 등급

엔드포인트 목록은 앞으로 늘어난다는 전제로 설계한다. 따라서 각 타깃은 **필수(REQUIRED) /
선택(OPTIONAL)** 등급을 갖는다.

| 엔드포인트 | 아카이브 파일명 | 등급 | 용도 |
|---|---|---|---|
| `_cluster/health` | `cluster_health.json` | **REQUIRED** | 클러스터 상태·노드 수·샤드 카운트 |
| `_nodes/stats` | `nodes_stats.json` | **REQUIRED** | JVM heap, 서킷 브레이커, 스레드풀 |
| `_cluster/settings?include_defaults=true` | `cluster_settings.json` | OPTIONAL | 설정 오설정 감지 (명시값 vs 기본값) |
| `_cluster/allocation/explain` | `allocation_explain.json` | OPTIONAL | unassigned 샤드 사유 |
| `_cat/shards?format=json&bytes=b` | `cat_shards.json` | OPTIONAL | 샤드 배치·상태 |
| `_cat/indices?format=json&bytes=b` | `cat_indices.json` | OPTIONAL | 인덱스 목록·크기 |
| `_cat/allocation?format=json&bytes=b` | `cat_allocation.json` | OPTIONAL | 노드별 디스크 사용량 |
| `_cluster/pending_tasks` | `cluster_pending_tasks.json` | OPTIONAL | 클러스터 상태 업데이트 큐 적체(마스터 과부하·스플릿브레인 전조) |
| `_cluster/stats` | `cluster_stats.json` | OPTIONAL | 클러스터 전체 집계(노드 역할별 개수, 플러그인·버전, 총 샤드/인덱스 수). `_nodes/stats` 합산값과 교차검증 + 플러그인 버전 불일치 진단 |
| `_cat/nodes?format=json&full_id=true&bytes=b` | `cat_nodes.json` | OPTIONAL | 현재 마스터로 선출된 노드 식별(`m` 컬럼). `_nodes/stats`에는 이 정보가 없다 |
| `_cat/recovery?format=json&active_only=true&bytes=b` | `cat_recovery.json` | OPTIONAL | 진행 중인 샤드 리커버리/릴로케이션. 리밸런스 정체·복구 지연 진단 |
| `_cat/segments?format=json&bytes=b` | `cat_segments.json` | OPTIONAL | 샤드당 세그먼트 수/크기. 과다 세그먼트로 인한 검색 지연 진단 (대형 클러스터에서 응답이 클 수 있음, 주의) |
| `_cat/plugins?format=json` | `cat_plugins.json` | OPTIONAL | 설치된 플러그인·버전. 노드 간 버전 불일치·호환성 진단 |
| `_cat/fielddata?format=json&bytes=b` | `cat_fielddata.json` | OPTIONAL | 노드·필드별 fielddata 메모리. OSC-001 계열에서 "어떤 필드가 breaker를 밀어올리는지"까지 근거를 좁히는 데 사용 |
| `_index_template` | `index_templates.json` | OPTIONAL | 인덱스 템플릿 정의(매핑·설정). 템플릿 오설정/매핑 위생 진단. **주의**: 필드명 등 스키마 정보 포함 → 6절의 "덤프에 인덱스명 포함" 경고에 매핑 필드명도 추가해야 함 |

**수집은 넓게, 파싱은 룰 수요 기반.** 아래 8개(`_cluster/pending_tasks` 이하)는 수집만 하고
`ClusterSnapshotParser`는 아직 파싱하지 않는다. 룰이 실제로 필요로 할 때 파싱을 추가한다.
초기 룰 3개가 요구하는 최소 필드만 모으던 원칙에서 방향을 바꾼 것으로, HTTP 라이브 수집기가
미구현인 지금이 확장 비용이 가장 낮은 시점이라는 판단이다.
선정 근거는 elastic/support-diagnostics(공식 진단 수집기)의 수집 목록과
AutoOps 이벤트 카탈로그(pending tasks, 플러그인 호환성, 세그먼트, fielddata 등).

조사했으나 **의도적으로 제외**한 엔드포인트:

- `_nodes/hot_threads` — 응답이 JSON이 아닌 텍스트라 현재의 JSON 전용 파싱 모델과 맞지 않음
  (v0.2 이후 텍스트 아티팩트 저장을 지원하면 재검토)
- `_mapping` (전체) — 대형 클러스터에서 응답이 지나치게 커질 수 있고, `_index_template`보다
  민감정보(실제 운영 필드 스키마 전체) 노출 범위가 큼
- `_snapshot/{repo}/_all` 류 스냅샷 상태 — 저장소 이름을 먼저 알아야 호출 가능한 동적
  엔드포인트라 "고정 목록" 원칙에 안 맞음. `_snapshot`(저장소 목록)만으로는 실익이 적어 제외
- X-Pack 계열(`_ml`, `_security`, `_watcher`, `_ccr`, `_slm`, `_transform` 등) —
  Elasticsearch 상용 플러그인 전용이라 OpenSearch 스코프 밖
- `_cat/thread_pool` — 이미 수집 중인 `_nodes/stats` 응답 안에 동일 데이터(스레드풀 큐·rejected)가
  있음. **새 엔드포인트가 아니라 `ClusterSnapshotParser`가 그 필드를 더 파싱하면 되는 문제**

- **REQUIRED 판정 기준**: 이 파일이 없으면 "클러스터 스냅샷"이라 부를 것이 성립하지 않고
  어떤 룰도 돌 수 없는 것만 필수다. 누락 시 `SnapshotParseException` → 종료 코드 2 (시끄럽게 실패).
- **OPTIONAL 누락 시**: 스냅샷의 해당 필드는 `Optional.empty()`가 되고, 그 데이터를 필요로 하는
  룰만 SKIPPED 처리된다(4.4). 진단 전체는 계속 진행한다.
- **빈 값으로 대체하지 않는다.** 예를 들어 `cluster_settings.json`이 없을 때 빈 설정 맵을 넣으면
  "설정이 비어 있다"와 "설정을 못 읽었다"가 구분되지 않아 미탐으로 이어진다. 반드시 `Optional`이다.
- **새 엔드포인트의 기본 등급은 항상 OPTIONAL.** 이 규칙이 덤프 하위호환을 구조적으로 보장한다
  (구버전으로 뜬 덤프를 신버전이 열 수 있음). REQUIRED 승격은 덤프 스키마 버전을 올리는 변경이다.

부분 수집 실패(권한 부족 403, 타임아웃, 버전에 따라 없는 API)는 엔드포인트가 늘어날수록
정상 상태에 가까워진다. 하나 실패했다고 진단 전체가 죽어서는 안 된다.

#### 메타데이터 파일 (`metadata.json`)

수집 시각·도구 버전·클러스터 이름/버전에 더해 **덤프 스키마 버전**과 **수집 리포트**를 담는다.

```json
{
  "dumpSchemaVersion": 1,
  "collectedAt": "2026-07-27T04:11:00Z",
  "toolVersion": "0.1.0",
  "clusterName": "prod-search",
  "clusterVersion": "2.19.1",
  "collection": [
    { "target": "CLUSTER_HEALTH",     "status": "OK",     "httpStatus": 200 },
    { "target": "ALLOCATION_EXPLAIN", "status": "FAILED", "httpStatus": 400, "message": "unable to find any unassigned shards" },
    { "target": "CAT_INDICES",        "status": "FAILED", "httpStatus": 403, "message": "no permissions for [indices:monitor/stats]" }
  ]
}
```

- **수집 리포트가 있어야 "왜 이 룰이 안 돌았나"를 덤프 하나만 보고 답할 수 있다.**
  덤프는 폐쇄망에서 반출돼 몇 달 뒤 다른 곳에서 열리는 물건이므로, 수집 당시의 실패 사유가
  파일 안에 남아 있지 않으면 재현이 불가능하다.
- `dumpSchemaVersion`은 **구조가 깨지는 변경에만** 올린다. 엔드포인트 추가는(OPTIONAL이므로) 올리지 않는다.
- **아카이브 안의 모르는 파일은 무시한다.** 신버전으로 뜬 덤프를 구버전이 여는 경우를 위한 전방 호환.
- 덤프 스키마 버전이 리더가 아는 것보다 높으면 경고만 남기고 아는 파일로 진행한다.

### 3.2 `diagnose` — 룰 엔진 실행

- **live 모드**: `--endpoint` 지정 시, collect와 동일한 수집기를 메모리 상에서 실행 후 진단
- **offline 모드**: `--input dump.tar.gz` 지정 시, 덤프 파일만으로 진단 (네트워크 제로)
- **입력 포맷은 tar.gz 단일로 못 박는다** (개별 JSON 파일/디렉토리 입력은 백로그)
- **출력**: 사람이 읽는 텍스트(기본) + `--format json`
- **종료 코드**: `0` = 발견 없음 / `1` = 발견 있음 / `2` = 실행 오류 (스크립트·cron 연동용)
    - **SKIPPED 룰이 있어도 종료 코드는 바뀌지 않는다.** 종료 코드는 오직 finding 유무로 정한다.
      데이터 부족은 리포트 본문과 `--format json`의 `skipped` 배열로만 알린다 (스크립트 연동 호환 유지).

## 4. 룰 아키텍처 (확정)

### 4.1 룰 = Java 코드 (설정 파일/DSL 아님)

룰의 본질은 임계값 비교가 아니라 **여러 API 응답의 교차 참조 + 조건 분기(추론 체인)**이므로,
v0.1에서 룰은 Java 클래스로 하드코딩한다. YAML/JSON 룰 정의, 플러그인 구조, DSL은 만들지 않는다.
룰 추가·수정·삭제 = 코드 수정 + 새 버전 릴리스.

### 4.2 공통 인터페이스

```java
public interface DiagnosticRule {
    String id();                 // "OSC-001" 형식
    Severity severity();         // CRITICAL / WARNING / INFO
    RuleResult evaluate(ClusterSnapshot snapshot);
}
```

- **`Finding` 출력 구조 (모든 룰 통일)**:
    - `ruleId` — 예: `OSC-001`
    - `severity` — CRITICAL / WARNING / INFO
    - `finding` — 무엇이 문제인지 한 줄
    - `evidence` — 어떤 API의 어떤 필드 값이 근거인지 (예: `nodes.stats.breakers.parent.tripped = 847`)
    - `recommendation` — 실행 가능한 조치 (구체적 API 호출 예시 포함)

### 4.3 룰이 보는 유일한 입력: `ClusterSnapshot`

**OpenSearch 클러스터는 자기 상태를 하나의 API로 알려주지 않는다.** 클러스터가 건강한지는
`_cluster/health`가, 각 노드의 메모리·서킷브레이커 상태는 `_nodes/stats`가, 샤드가 어느 노드에
어떻게 놓였는지는 `_cat/shards`가, 운영자가 무슨 설정을 걸어놨는지는 `_cluster/settings`가
따로 답한다. 장애 원인은 대개 이 응답들 **사이의 관계**에 있다 — 예를 들어 "샤드가 배정되지
않는다"(health)의 원인이 "운영자가 allocation을 꺼놨다"(settings)인 식이다.

`ClusterSnapshot`은 **그 API 응답들을 한 시점 기준으로 모아 파싱해 담은 불변 객체**다.
클러스터의 어느 한 순간을 찍은 사진에 가깝다. 이 프로젝트의 진단이란 결국
"이 사진 한 장 안에서 서로 모순되거나 위험한 조합을 찾아내는 일"이고,
`ClusterSnapshot`은 그 사진의 자료형이다.

이렇게 **하나의 객체로 묶는 이유**는 세 가지다:

1. **교차 참조가 본체이기 때문.** 룰은 응답 하나만 보고 임계값을 넘겼는지 확인하는 게 아니라
   여러 응답을 동시에 본다(1절). 한 덩어리로 들고 있지 않으면 룰마다 데이터를 다시 끌어모아야 한다.
2. **시점을 고정하기 위해.** 룰이 실행 중에 API를 직접 호출하면 룰마다 다른 순간의 클러스터를
   보게 되어, 근거(evidence)로 제시한 값들이 서로 다른 시점의 것이 된다. 스냅샷은 한 번 만들어지면
   변하지 않으므로 리포트 안의 모든 근거가 같은 순간을 가리킨다.
3. **live와 offline을 같은 코드로 처리하기 위해.** 아래 항목 참고.

- **룰은 데이터의 출처를 알지 못한다.** `ClusterSnapshot`이 라이브 클러스터를 직접 찔러 만든
  것인지 몇 달 전 tar.gz 덤프를 푼 것인지 룰은 구분할 수 없고, 구분할 필요도 없다.
  덕분에 **룰을 한 번 쓰면 live 모드와 offline 모드 양쪽에서 그대로 동작하고**,
  offline 모드가 곧 테스트 하네스가 된다(6절).
- **룰은 스냅샷 밖으로 나가지 않는다.** 룰이 파일을 열거나 HTTP를 호출하면 위 세 가지가 전부
  깨진다. 룰에 필요한 데이터가 없다면 스냅샷에 필드를 추가할 일이지 룰이 직접 가져올 일이 아니다.
- **불변이므로 룰 실행 순서가 결과에 영향을 주지 않는다.** 어떤 룰도 다른 룰이 볼 데이터를
  바꿔놓을 수 없다.

### 4.4 룰 결과는 3-상태다 (`RuleResult`)

`Optional<Finding>`은 **"발화 안 함"과 "판단할 데이터가 없음"을 구분하지 못한다.** 둘 다 빈 값이
되므로, 데이터가 없어서 못 본 것이 조용한 미탐(false negative)으로 흘러간다. 엔드포인트가
OPTIONAL을 가지는 이상(3.1) 이 구분은 필수다.

```java
public sealed interface RuleResult {
    record Fired(Finding finding) implements RuleResult {}   // 발화
    record NotFired() implements RuleResult {}               // 정상 — 조건 미충족
    record Skipped(String reason) implements RuleResult {}   // 판단 불가 — 필요한 데이터 없음
}
```

- `Skipped.reason`은 사람이 읽을 사유다. 예: `"requires cluster_settings (not in dump)"`.
- **`RuleEngine`은 `List<Finding>`이 아니라 `DiagnosticReport`를 반환한다**:
  발화한 finding 목록 + SKIPPED 룰 목록(룰 ID·사유).
- 리포트 출력에 SKIPPED 섹션을 반드시 노출한다:

```
SKIPPED (2 rules could not be evaluated)
  OSC-002  requires cluster_settings (collection failed: HTTP 403)
  OSC-003  requires cluster_settings (collection failed: HTTP 403)
```

이는 결정 로그의 "조용히 넘어가면 미탐으로 이어진다 — 시끄럽게 실패한다" 원칙과 같은 계열이다.
다만 SKIPPED는 실행 오류가 아니므로 종료 코드를 바꾸지 않는다(3.2).

### 4.5 임계값 처리

- 룰 안의 임계값은 전부 **명명된 상수** (예: `SHARD_USAGE_WARNING_THRESHOLD = 0.9`)
- 각 상수에 **근거 주석** 필수
- 테스트는 매직 넘버가 아니라 상수를 참조
- 사용자용 임계값 외부화(`--config thresholds.yaml`)는 **v0.1에서 의도적으로 제외** (v0.2 백로그)

## 5. 초기 룰 3개

| ID | Severity | 내용 |
|---|---|---|
| OSC-001 | CRITICAL | **서킷 브레이커 트립**: breaker tripped 카운트 > 0 + heap 사용률 + `top_queries-*` 인덱스 존재/비대 여부를 교차 확인 → 쿼리 인사이트 인덱스 방치 패턴 진단 |
| OSC-002 | CRITICAL / WARNING | **샤드 한도 고갈**: `max_shards_per_node × 데이터노드 수`(설정에서 읽음) vs 현재 샤드 수. 한도 도달 = CRITICAL, 90% 이상 근접 = WARNING |
| OSC-003 | CRITICAL | **`cluster.routing.allocation.enable: none` 오설정**: settings에서 감지 + health RED / unassigned shards와 연결해 인과 관계 서술 |

각 룰이 필요로 하는 OPTIONAL 타깃(없으면 SKIPPED):

| 룰 | 필요한 OPTIONAL 타깃 |
|---|---|
| OSC-001 | `cat_indices` (`top_queries-*` 존재/크기 확인용) |
| OSC-002 | `cluster_settings`, `cat_shards` |
| OSC-003 | `cluster_settings` (+ `allocation_explain`은 있으면 근거 보강, 없어도 발화 가능) |

> OSC-001의 `top_queries-*` 부분은 Query Insights 플러그인의 로컬 인덱스 익스포터가 켜진
> 클러스터에서만 성립한다. 해당 인덱스가 없으면 브레이커 트립 + heap 근거만으로 발화하고,
> 쿼리 인사이트 방치 패턴은 언급하지 않는다 (룰 자체를 SKIPPED하지는 않는다).
> 플러그인 도입 최소 버전은 룰 구현 시점에 실제 클러스터로 확인해 확정한다.

## 6. 테스트 전략

**픽스처 = 실제 API 응답 형태의 JSON 덤프** (`src/test/resources`). offline 모드가 곧 테스트 하네스.

### 룰당 픽스처 3종 세트

1. **양성(positive)**: 장애 시점 덤프 → 룰이 반드시 탐지. finding 내용과 evidence 필드 값까지 assert
2. **음성(negative)**: 정상 클러스터 덤프 → 절대 발화 금지. 오탐 방어선 (오탐이 미탐보다 도구 신뢰를 더 죽인다)
3. **경계(edge)**: 임계 근처 값 (예: 샤드 사용률 89% vs 91%) → 발화 기준 정확성 검증

### 덤프 결손 픽스처 (룰 3종 세트와 별개)

OPTIONAL 타깃이 빠진 덤프를 별도로 둔다. 검증 대상은 두 가지다:

- REQUIRED만 있는 덤프 → 파싱 성공 + 해당 룰들이 **SKIPPED로 보고**됨 (조용히 NotFired 되지 않음)
- REQUIRED 누락 덤프 → `SnapshotParseException` (종료 코드 2)

이 픽스처가 엔드포인트 확장 시의 하위호환 회귀 테스트 역할도 한다.

### 보조 수단

- `ClusterSnapshotBuilder` 테스트 헬퍼: 기존 덤프 기반으로 특정 필드만 변형한 스냅샷을 한 줄로 생성 (픽스처 파일 폭발 방지)
- 통합 테스트: 픽스처 전체에 대해 "기대한 finding 집합 == 실제 finding 집합" 검증.
  precision/recall 프레임워크는 v0.1에서 만들지 않는다.

### 픽스처 데이터 원칙

- 실제 장애 덤프에서 출발하되 **일반화·마스킹 처리** (실제 환경의 설정값·식별 가능한 정보 미포함)
- README에 "collect 덤프에는 인덱스명이 포함된다" 경고 명시 (자동 마스킹은 v0.1 제외)

## 7. v0.1 명시적 제외 목록 (스코프 방어선)

아래 기능은 논의 끝에 **의도적으로 제외**한 것이다. 구현하지 않는다:

- `fix` 명령 (자동 조치)
- MCP 서버 모드
- 룰 DSL / 플러그인 구조 / 룰 설정 파일
- 임계값 외부 설정 파일
- 개별 JSON 파일/디렉토리 입력 (`diagnose --input dir/`)
- Elasticsearch 호환
- 수집 데이터 자동 마스킹
- 지속 모니터링 / 데몬 모드

## 8. v0.2+ 백로그 (참고용, 지금 구현 금지)

- MCP 서버 모드: 같은 진단 엔진을 LLM이 호출. 로컬 LLM 지원으로 폐쇄망 차별화
- 룰 확장
- `fix` 명령: dry-run 우선, 되돌리기 가능한 조치만
- 임계값 외부화 (`--config thresholds.yaml`)
- 개별 JSON 파일 입력 지원
- `collect --print-script`: 동일한 tar.gz 레이아웃을 만드는 curl 스크립트 출력.
  폐쇄망에서 **클러스터에 닿는 호스트에 Java 25 런타임을 올릴 수 없는** 경우의 탈출구.
  실제 그 요구가 확인되기 전까지는 만들지 않는다 (명령 2개 원칙에 붙는 군더더기)

## 9. 다음 작업 (현재 위치)

1. ~~`ClusterSnapshot` 필드 설계~~ ✅ 완료 — `snapshot` 패키지 (모델 record + `ClusterSnapshotParser`)
2. ~~프로젝트 골격: 디렉토리 구조, 룰 엔진 인터페이스, 수집기 계층~~ ✅ 완료 — `rule` 패키지(인터페이스·엔진), `collect` 패키지(`CollectTarget`/`RawDump`/`DumpSource` 경계)
3. ~~**엔드포인트 확장 대비 리팩터링** (3.1 / 4.4 반영)~~ ✅ 완료
    - ~~`CollectTarget`에 REQUIRED/OPTIONAL 등급 추가~~ — REQUIRED는 health·nodes_stats 둘뿐
    - ~~`ClusterSnapshotParser`: OPTIONAL 타깃 → `Optional` 필드 (빈 값 대체 금지)~~
    - ~~`SnapshotMetadata`에 `dumpSchemaVersion` + 수집 리포트 필드 추가~~ — `CollectionOutcome`
    - ~~`DiagnosticRule.evaluate` 반환 타입 → `RuleResult`, `RuleEngine` → `DiagnosticReport`~~
    - 덤프 결손 픽스처(`fixtures/required-only/`) + 전방 호환 테스트 추가
4. collect 구현 (HTTP 수집기 + tar.gz 생성) → diagnose offline 모드 → 룰 3개 → live 모드
   ← **여기부터 시작**

3번을 4번보다 먼저 한 이유: 룰 3개를 만든 뒤에 `RuleResult`로 바꾸면 룰과 룰 테스트를
전부 다시 손봐야 한다. 룰 시그니처가 굳지 않은 시점이 비용이 가장 낮았다.

구현 구조와 진행 중 내린 세부 결정(결정 로그)은 [IMPLEMENTATION.md](IMPLEMENTATION.md) 참고.