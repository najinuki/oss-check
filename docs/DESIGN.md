# oss-check — Design Decisions (v0.1)

> 이 문서는 프로젝트의 확정된 설계 결정사항이다. 코드 작성 시 이 문서를 기준으로 하며,
> 여기 명시된 스코프를 벗어나는 기능 추가는 사전 논의 없이 하지 않는다.

## 1. 프로젝트 개요

- **이름**: `oss-check` (확정). "OpenSearch" 상표를 프로젝트명 선두에 쓰지 않는다.
  저장소명·패키지(`com.nj.oss.check`)와 일치시켰다. 실행 명령·도움말·환경변수 접두사가
  전부 이 이름을 따른다 (`OSS_CHECK_PASSWORD`, 3.1)
- **개념**: OpenSearch 클러스터 진단 CLI. 여러 API 응답(`_cluster/health`, `_nodes/stats`, `_cat/shards` 등)을
  교차 분석해 근본 원인을 추론하고, **근거(evidence) + 조치안(recommendation)**까지 출력한다.
  단순 임계값 체크 도구가 아니라, 실제 장애 추적 과정의 추론 체인을 룰로 코드화한 것이 본체다.
- **핵심 차별화**: **air-gapped by design.** 폐쇄망(에어갭)에서 완전 동작.
  텔레메트리·업데이트 체크·외부 호출 제로. 유일한 네트워크 통신은 대상 클러스터뿐.
- **라이선스**: Apache 2.0. **README는 영문 필수.**

## 2. 기술 스택 (확정)

- **Java 25** + **Spring Boot** + **picocli** (CLI 파싱)
- 배포: **Spring Boot fat jar** (`./gradlew bootJar` → `java -jar oss-check.jar`).
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

- **접속 옵션**: `--endpoint`, `--user`, `--insecure` (자체 서명 인증서 허용)
- **비밀번호를 받는 옵션은 없다.** 프롬프트 또는 환경변수로만 받는다 (아래)
- **출력**: `--output <path>` (선택). 기본값은 현재 디렉토리의 `oss-check-<수집시각>.tar.gz`
    - **이미 있는 파일은 덮어쓰지 않고 실패한다**(종료 코드 2). 덤프는 증거물이다 —
      같은 경로로 두 번 돌려 앞선 장애 시점의 덤프를 지우면 되돌릴 수 없다
    - 이 검사는 **수집을 시작하기 전에** 한다. 60초짜리 수집을 끝낸 뒤 쓸 곳이 없다고
      말하는 것은 실패를 알리는 방식으로 최악이다
    - 파일명에 클러스터 이름을 넣지 않는다. 루트 엔드포인트가 막히면 이름이 없을 수 있고,
      반출되는 파일 이름에 환경 정보를 덜 싣는 편이 낫다
- 외부 통신은 대상 클러스터 단 하나뿐

#### 비밀번호 입력 — `--password` 옵션은 두지 않는다

명령줄 옵션으로 비밀번호를 받으면 **셸 히스토리에 남고, 같은 호스트의 다른 사용자에게
`ps`로 보인다.** 진단 대상이 운영 클러스터라는 점을 생각하면 기본값으로 둘 수 없는 위험이다.
운영자에게도 이미 `curl -u admin`·`psql -U admin`처럼 **비밀번호는 물어보는 것**이라는
손버릇이 있다.

비밀번호를 얻는 경로는 둘이고, 순서가 있다:

1. **환경변수 `OSS_CHECK_PASSWORD`** — 비대화형 경로(cron·CI·스크립트). 3.2의 종료 코드가
   스크립트 연동용인 이상 이 경로는 반드시 있어야 한다
   (변수 이름의 접두사는 1절의 도구 이름 확정과 함께 정한다)
2. **프롬프트** — 표준 입력이 TTY일 때. 입력은 **에코하지 않는다**

규칙:

- `--user`가 없으면 익명 접속이다(보안 플러그인이 없는 클러스터). 이때는 묻지 않는다.
- **`--user`를 줬는데 비밀번호를 얻지 못하면 즉시 실패한다** (종료 코드 2).
  익명으로 조용히 넘어가지 않는다 — 그러면 클러스터가 돌려준 401/403이 수집 리포트에
  "이 계정에 권한이 없음"으로 기록되어, **설정 실수가 클러스터 권한 문제로 위장된다.**
  덤프는 몇 달 뒤 다른 사람이 여는 물건이므로 이 위장은 그때까지 남는다.
- **TTY가 아니면 묻지 않고 바로 실패한다.** 파이프·리다이렉트·cron에서 대화형 입력을
  기다리며 매달리는 일이 없어야 한다(3.2의 스크립트 연동 계약).
- 사용자명과 비밀번호는 **함께 있거나 함께 없다.** 한쪽만 있는 상태는 만들어지지 않는다.

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
- **새 엔드포인트의 기본 등급은 항상 OPTIONAL.** 이 규칙이 **같은 스키마 버전 안에서**
  덤프 하위호환을 보장한다 (엔드포인트가 늘어도 구버전으로 뜬 덤프를 신버전이 열 수 있음).
  REQUIRED 승격은 덤프 스키마 버전을 올리는 변경이다.
  **보장의 범위는 스키마 버전 하나 안이지 영구가 아니다.** 버전이 올라가면 끊길 수 있고,
  v0.2가 레이아웃을 바꾸면서 버전 1 덤프를 거부하는 것이 그 예다(10.2).

부분 수집 실패(권한 부족 403, 타임아웃, 버전에 따라 없는 API)는 엔드포인트가 늘어날수록
정상 상태에 가까워진다. 하나 실패했다고 진단 전체가 죽어서는 안 된다.

#### 메타데이터 파일 (`metadata.json`)

수집 시각·도구 버전·클러스터 이름/버전에 더해 **덤프 스키마 버전**과 **수집 리포트**를 담는다.

> **v0.2에서 이 구조와 아카이브 레이아웃이 바뀐다 (10.2 참고).** 구간 샘플링이 들어오면서
> 파일은 `collection/` 아래로 내려가고 `metadata.json`은 `shared` + `samples[]`로 갈린다.
> 스키마 버전은 2가 되며, **버전 1 덤프는 읽지 않고 거부한다.** 아래는 v0.1 형식이다.

```json
{
  "dump_schema_version": 1,
  "collected_at": "2026-07-27T04:11:00Z",
  "tool_version": "0.1.0",
  "cluster_name": "prod-search",
  "cluster_version": "2.19.1",
  "collection": [
    { "target": "CLUSTER_HEALTH",     "status": "OK",     "http_status": 200 },
    { "target": "ALLOCATION_EXPLAIN", "status": "FAILED", "http_status": 400, "message": "unable to find any unassigned shards" },
    { "target": "CAT_INDICES",        "status": "FAILED", "http_status": 403, "message": "no permissions for [indices:monitor/stats]" }
  ]
}
```

키는 **snake_case**다. 이 파일은 아카이브 안에서 OpenSearch 응답들과 나란히 놓이므로
같은 표기를 쓴다. (`diagnose --format json`의 리포트는 별개 산출물이라 camelCase다.)

- **수집 리포트가 있어야 "왜 이 룰이 안 돌았나"를 덤프 하나만 보고 답할 수 있다.**
  덤프는 폐쇄망에서 반출돼 몇 달 뒤 다른 곳에서 열리는 물건이므로, 수집 당시의 실패 사유가
  파일 안에 남아 있지 않으면 재현이 불가능하다.
- `dumpSchemaVersion`은 **구조가 깨지는 변경에만** 올린다. 엔드포인트 추가는(OPTIONAL이므로) 올리지 않는다.

##### 클러스터 식별 실패 (`identityFailure`)

`clusterName`·`clusterVersion`은 루트 엔드포인트(`/`)에서 얻는다. 루트는 클러스터 상태를
기술하는 것이 아니라 **덤프가 무엇의 덤프인지 밝히는** 것이라 `CollectTarget`이 아니고,
따라서 실패해도 수집 리포트에 남을 자리가 없다. 이름 없는 덤프도 진단은 되므로 치명적이지
않지만, **왜 이름이 없는지는 남아야 한다.**

```json
{
  "identity_failure": "root endpoint returned HTTP 200 but the body was not JSON: <!DOCTYPE html>…"
}
```

식별에 실패했으므로 `cluster_name`·`cluster_version`은 **아예 없다**. 이 파일은 null을 쓰지
않는다 — 없는 것은 없는 대로 둔다.

- **식별에 성공하면 이 필드는 없다.** 있다는 것 자체가 실패했다는 뜻이다.
- 두 경우를 모두 덮는다: 루트가 **거부된 경우**(403 등)와 **2xx인데 본문이 JSON이 아닌 경우**.
  후자는 앞에 프록시나 인증 게이트웨이가 서서 로그인 페이지를 200으로 돌려주는 전형적인
  상황이고, 지금 두 경우 모두 흔적 없이 사라진다.
- 메시지는 다른 수집 실패와 같은 규칙으로 잘라 담는다.
- **`dumpSchemaVersion`은 올리지 않는다.** 필드 추가는 구버전 리더가 무시하면 그만이다.
- **루트 응답 본문 자체는 덤프에 저장하지 않는다.** 프록시가 돌려준 HTML을 파일로 넣으면
  "payload가 있는데 파손이면 예외"(4.3 / 파서 규칙)에 걸려 **덤프 전체가 읽히지 않게 된다** —
  진단하려던 문제가 덤프를 못 쓰게 만드는 셈이다. 사유만 남긴다.
- 리포트 헤더는 이 사유를 노출한다. `unknown cluster`만 찍고 마는 것은 "이름이 없는 클러스터"와
  "이름을 못 읽은 클러스터"를 뭉개는 것이고, 그 구분은 이 프로젝트가 계속 지켜온 것이다.
- **아카이브 안의 모르는 파일은 무시한다.** 신버전으로 뜬 덤프를 구버전이 여는 경우를 위한 전방 호환.
- 덤프 스키마 버전이 리더가 아는 것보다 높으면 경고만 남기고 아는 파일로 진행한다.

### 3.2 `diagnose` — 룰 엔진 실행

- **live 모드**: `--endpoint` 지정 시, collect와 동일한 수집기를 메모리 상에서 실행 후 진단.
  접속 옵션과 **비밀번호 입력 규칙은 3.1과 동일하다** — 같은 수집기를 쓰는 이상 인증 경로가
  갈리면 안 된다
- **offline 모드**: `--input dump.tar.gz` 지정 시, 덤프 파일만으로 진단 (네트워크 제로).
  접속 정보가 필요 없으므로 비밀번호를 묻지 않는다
- **입력 포맷은 tar.gz 단일로 못 박는다** (개별 JSON 파일/디렉토리 입력은 백로그)
- **출력**: 사람이 읽는 텍스트(기본) + `--format json`
- **종료 코드**: `0` = 발견 없음 / `1` = 발견 있음 / `2` = 실행 오류 (스크립트·cron 연동용)
    - **SKIPPED 룰이 있어도 종료 코드는 바뀌지 않는다.** 종료 코드는 오직 finding 유무로 정한다.
      데이터 부족은 리포트 본문과 `--format json`의 `skipped` 배열로만 알린다 (스크립트 연동 호환 유지).
    - 비밀번호를 얻지 못한 채 인증이 필요한 실행을 시작하는 일은 없다 → 이 경우는 `2`다(3.1).
      **비대화형 실행에서 프롬프트를 띄우고 멈추는 것은 이 계약 위반이다.**

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
| OSC-001 | **없음.** `cat_indices`는 있으면 근거 보강, 없어도 발화 (아래) |
| OSC-002 | `cluster_settings`, `cat_shards` |
| OSC-003 | `cluster_settings` (+ `allocation_explain`은 있으면 근거 보강, 없어도 발화 가능) |

> **OSC-001은 SKIPPED되지 않는다.** 발화 조건(브레이커 트립 + heap 압박)이 REQUIRED 타깃인
> `_nodes/stats`만으로 판정되기 때문이다. `cat_indices`는 `top_queries-*` 인덱스 방치 패턴을
> 덧붙이는 **근거 보강용**이고, 이건 Query Insights 플러그인의 로컬 인덱스 익스포터가 켜진
> 클러스터에서만 성립한다. 따라서 두 경우 모두 발화하되 보강 근거만 빠진다:
> 인덱스가 존재하지 않는 경우, 그리고 `cat_indices.json` 자체가 덤프에 없는 경우.
> 후자는 evidence에 "확인하지 못했다"고 명시한다 — 없는 것과 못 본 것은 다른 사실이다.
> 무관한 엔드포인트의 403 때문에 서킷 브레이커 트립을 판정하지 않는 것은 손해가 더 크다.
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
- **`diagnose` 다중 덤프 입력**: 여러 tar.gz를 한 시퀀스로 읽어 진단.
  10.3의 `SnapshotSequence`가 서면 그 위에 얹는 작은 작업이므로 v0.2에서 분리했다
- **`collect --watch <기간> --interval <간격>`**: 정해진 기간 동안 주기적으로 수집하고
  **스스로 종료하는** 관찰 모드. 샘플마다 덤프를 하나씩 즉시 쓰므로 중간에 죽어도 앞선
  덤프는 온전하고(결정 18과 충돌하지 않는다 — 각 덤프가 완전하거나 없거나다),
  끝나면 사라지므로 **제거할 설정이 없다**. cron을 요구하지 않기 위한 것이지
  금지하기 위한 것이 아니다 — 며칠 이상 관찰은 재부팅을 버티는 cron이 여전히 옳다.
  상주 에이전트가 아닌 근거: 종료되고, 시계열 저장소가 없고(평범한 덤프만 남긴다),
  설치·권한 흔적이 없다

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
    - ~~tar.gz 쓰기/읽기~~ ✅ 완료 — `TarGzDumpWriter` / `TarGzDumpSource`
    - ~~HTTP 라이브 수집기~~ ✅ 완료 — `HttpDumpSource` / `ClusterConnection`
    - ~~CLI 와이어링~~ ✅ 완료 — picocli `collect`·`diagnose`, 종료 코드,
      스키마 버전 경고, 비밀번호 프롬프트/환경변수(3.1)
    - ~~diagnose offline 모드~~ ✅ 완료 — `--input`, 텍스트·JSON 리포트
    - ~~룰 3개~~ ✅ 완료 — OSC-001/002/003, `rule.catalog`
    - ~~`diagnose --endpoint` live 모드~~ ✅ 완료 — 접속 옵션을 collect와 공유
5. ~~README(영문 필수, 1절) + LICENSE + 버전 정리~~ ✅ 완료 — 버전은 `0.1.0`

**여기까지가 v0.1로 계획한 전부다.** 남은 것은 구현 중 발견한 숙제(IMPLEMENTATION.md
"남은 숙제")와 8절 백로그이며, 둘 다 착수 전에 이 문서에서 결정한다.

3번을 4번보다 먼저 한 이유: 룰 3개를 만든 뒤에 `RuleResult`로 바꾸면 룰과 룰 테스트를
전부 다시 손봐야 한다. 룰 시그니처가 굳지 않은 시점이 비용이 가장 낮았다.

구현 구조와 진행 중 내린 세부 결정(결정 로그)은 [IMPLEMENTATION.md](IMPLEMENTATION.md) 참고.

**v0.2 스코프는 10절에서 확정했다.**

## 10. v0.2 — 구간 샘플링 + 룰 확장

v0.1은 클러스터의 **사진 한 장**을 진단한다. v0.2는 그것을 **짧은 동영상**으로 넓히고,
그 위에서 룰을 늘린다. 상시 관제(에이전트)로는 가지 않는다.

### 10.1 왜 구간 샘플링인가 (그리고 왜 에이전트가 아닌가)

한 장으로 못 하는 것은 좁지만 뼈아프다: rate·latency, 추세, "언제부터".
특히 **누적 카운터를 현재 상태로 읽을 수 없다는 제약**이 크다 — `rejected`·`tripped`·
`index_total`·GC 카운트는 전부 노드 기동 이후 누적이라, 단독으로는 몇 달 전 장애를
지금 일로 보고하게 된다(OSC-001이 `tripped`를 heap과 짝지어야 했던 이유).

**두 샘플의 차이를 실제 경과 시간으로 나누면 그 구간의 rate다.** 카운터가 쓸모없던
자리에서 바로 쓸 수 있는 값으로 바뀐다. 이것이 구간 샘플링으로 얻는 핵심이다.

상시 수집 에이전트를 택하지 않은 이유:

1. **경쟁 구도가 바뀐다.** 지금 위치는 "이런 OSS가 사실상 없다"인데(AUTOOPS_BENCHMARK 11.7),
   상시 수집으로 가면 상대가 `elasticsearch_exporter` + Grafana + Alertmanager가 된다.
   빈 자리에서 나와 붐비는 곳으로 걸어 들어가는 선택이다.
2. **상주 프로세스는 시계열 저장소를 요구한다.** 직접 만들면 TSDB를 어설프게 만드는 것이고,
   Prometheus로 밀면 이미 있는 exporter를 다시 만드는 것이다.
3. **폐쇄망에서 상주 프로세스는 진입 장벽이다.** "jar 하나 넣고 한 번 돌린다"가 보안 심사를
   통과시키는 실체다. 배포·권한·업그레이드가 붙으면 그 장점이 사라진다.
4. **"덤프는 증거물"이라는 설계 전체가 시점 아티팩트 전제다.** 덮어쓰기 금지(3.1),
   수집 리포트, `identity_failure`는 전부 "몇 달 뒤 다른 사람이 여는 물건"이라서 있다.
   에이전트가 만드는 것은 아티팩트가 아니라 데이터베이스다.

### 10.2 `collect` 구간 샘플링

```
collect --samples 3 --interval 30s
```

- **기본값은 `--samples 3`, `--interval 30s`.** 관찰 구간은 `(N-1) × interval`이므로
  **약 60초**이고, CLI 실행 시간은 여기에 수집 3회 소요만큼 더 걸린다. 둘은 다른 값이다.
  기본을 1로 두지 않는 이유는
  **폐쇄망에서는 다시 뜰 수 없기 때문**이다. 반출한 덤프를 일주일 뒤 밖에서 열었더니
  rate 룰이 전부 SKIPPED라면 그 클러스터에는 이미 접근할 수 없다. 1분을 아끼려고
  조사 전체를 날리는 거래다. 급하면 `--samples 1`로 v0.1과 똑같이 즉시 끝난다.
- **3개인 이유**: 2개면 구간이 하나뿐이라 "계속 그런 것"과 "한 번 튄 것"을 구별할 수 없다.
- **`--interval`은 시작 시각 기준(start-to-start)이다.** "수집 완료 후 대기"로 정의하면
  관찰 구간이 수집 소요에 따라 흔들려 미리 알 수 없다. 수집이 간격보다 오래 걸리면 그만큼
  밀리지만, rate는 어차피 실제 `collected_at`으로 나누므로(10.3) 계산은 어긋나지 않는다.
- **간격 하한 10초.** 그 아래는 수집 자체가 간격을 넘어 의미가 없어지고, 이미 힘든
  클러스터를 두드리기만 한다(`_nodes/stats`는 가장 큰 응답이다).
- 상한을 두지 않는다. `--interval 5m`으로 늘리면 성격이 자연스럽게 "짧은 관찰"로 바뀐다.

#### 덤프 레이아웃 — 규칙 하나, 예외 없음

```
metadata.json                          ← 루트에 있는 유일한 파일
collection/cluster_settings.json       ← 모든 샘플이 공유
collection/cat_indices.json
collection/01/cluster_health.json      ← 그 샘플만의 것
collection/01/nodes_stats.json
collection/02/cluster_health.json
collection/02/nodes_stats.json
```

`collection/` 바로 밑의 파일은 **모든 샘플이 공유**하고, `collection/NN/`은 **그 샘플만의
것**이다. 폴백 구조라 규칙이 하나로 떨어진다 — 샘플 02를 볼 때는 `collection/02/`를 보고,
없으면 `collection/`에서 찾는다.

- **샘플이 1개여도 `collection/01/`이다.** 예외가 없으므로 writer에 분기가 없고,
  덤프를 여는 사람이 외울 규칙도 하나뿐이다.
- **리더는 경로를 본다.** 지금의 "경로를 버리고 파일명만 매칭"(디렉토리로 묶인 덤프를
  읽기 위한 장치였다)은 폐기한다. `collection/cluster_settings.json`과
  `collection/01/cluster_settings.json`이 구분돼야 하기 때문이다.
- **같은 타깃이 양쪽에 있으면 파손 덤프로 보고 실패한다.** 어느 쪽이 진실인지 고를 근거가
  없고, 조용히 하나를 택하면 결정 11이 막으려던 상황이 된다.
- 샘플 디렉토리는 `01`, `02`…로 zero-pad 하고 **숫자로 정렬**한다. 문자열로 정렬하면
  `10`이 `2`보다 앞에 온다.

#### v0.1 덤프는 읽지 않는다

`dump_schema_version`을 **2**로 올리고, 1인 덤프는 **명확한 메시지로 거부**한다.
개발 초기라 v0.1 덤프를 지킬 이유가 없고, 호환을 버리면 리더에서 "평평한 덤프인가
샘플 덤프인가" 분기가 통째로 사라진다 — **존재하는 레이아웃이 하나뿐**이 되는 것이 이
결정의 소득이다. 조용히 오독하느니 거부하는 것이 낫다(결정 6).

번호는 재사용하지 않는다. v0.1 태그를 지우더라도 이미 `1`이 박힌 덤프가 로컬에 있으므로,
재사용하면 서로 다른 두 레이아웃이 같은 번호를 주장하게 된다.

#### 무엇을 공유하고 무엇을 반복하나

15개를 전부 N번 뜨면 덤프가 비대해진다. 그렇다고 덜 뜨면 **진단이 틀린다.**

기준을 "구간 안에서 변할 수 있는가"로 잡으면 안 된다. 그렇게 잡으면 `cluster_settings`가
공유로 분류되는데, 그것은 장애 대응 중 운영자가 **가장 자주 바꾸는** 데이터이고
OSC-003의 존재 이유 자체가 "사람이 설정을 바꿔놓고 잊었다"이다. 기준은 둘을 함께 본다:
**변하면 진단이 달라지는가**, 그리고 **반복 비용을 감당할 수 있는가.**

**그래서 반복이 기본이고 공유가 예외다.**

| 구분 | 타깃 | 근거 |
|---|---|---|
| **공유** | `index_templates`, `cat_plugins` | 구조적으로 정적이다. 클러스터가 **무엇으로 이루어져 있는지**를 말할 뿐 무엇을 하고 있는지는 말하지 않는다 |
| **공유** | `cat_segments` | **정적이지 않다** — 색인·merge로 계속 변한다. 다만 "과다 세그먼트"는 시간 단위로 쌓이는 상태라 1분짜리 구간에서 판정이 뒤집히지 않고, 수집 대상 중 응답이 가장 크다(3.1의 크기 주의). **세그먼트 변화율을 보는 룰이 생기면 재검토한다** |
| **반복** | 나머지 전부 (`cluster_settings` 포함) | 변하면 진단이 달라진다 |

공유의 근거가 둘로 갈린다는 점이 중요하다. 하나로 뭉뚱그려 "정적인 것"이라고 적으면
`cat_segments`가 정적이라는 틀린 주장이 되고, 다음 사람이 그 기준으로 새 타깃을
분류하게 된다.

`CollectTarget`에 이 구분을 속성으로 둔다. 판정을 타깃 옆에 두는 것은 필수/선택 등급과
같은 방식이다(엔드포인트 추가는 여기 한 줄). **새 타깃의 기본값은 반복이다** — 공유로
두는 것은 위 두 조건을 모두 확인한 뒤의 예외다.

**공유 타깃은 모든 샘플의 스냅샷에 적용된다.** 샘플 01에만 있는 것으로 취급하면 02·03의
스냅샷에서 `index_templates`가 `Optional.empty()`가 되어, 그것을 쓰는 룰이 엉뚱하게
SKIPPED된다 — 데이터가 있는데 없다고 보고하는 미탐이다.

#### `metadata.json`

**시각은 타깃마다 기록한다.** 샘플에 하나만 두면 rate가 틀린다 — 아래에 이유가 있다.

```json
{
  "dump_schema_version": 2,
  "tool_version": "0.2.0",
  "cluster_name": "prod-search",
  "cluster_version": "2.19.1",
  "shared": {
    "started_at": "2026-08-11T04:11:00Z",
    "collection": [
      { "target": "INDEX_TEMPLATES", "status": "OK", "http_status": 200,
        "collected_at": "2026-08-11T04:11:01Z" }
    ]
  },
  "samples": [
    {
      "started_at": "2026-08-11T04:11:02Z",
      "collection": [
        { "target": "CLUSTER_HEALTH", "status": "OK", "http_status": 200,
          "collected_at": "2026-08-11T04:11:02Z" },
        { "target": "NODES_STATS",    "status": "OK", "http_status": 200,
          "collected_at": "2026-08-11T04:11:03Z" },
        { "target": "CAT_INDICES",    "status": "OK", "http_status": 200,
          "collected_at": "2026-08-11T04:11:09Z" }
      ]
    },
    {
      "started_at": "2026-08-11T04:11:32Z",
      "collection": [
        { "target": "CLUSTER_HEALTH", "status": "OK", "http_status": 200,
          "collected_at": "2026-08-11T04:11:32Z" },
        { "target": "NODES_STATS",    "status": "OK", "http_status": 200,
          "collected_at": "2026-08-11T04:11:34Z" },
        { "target": "CAT_INDICES",    "status": "FAILED", "http_status": 503,
          "collected_at": "2026-08-11T04:11:48Z" }
      ]
    }
  ]
}
```

##### 왜 타깃별인가

v0.1은 **모든 엔드포인트를 다 돈 뒤** 시각을 한 번 찍는다(`HttpDumpSource`). 그런데
`_nodes/stats`는 순회 앞쪽에서 읽히므로, **카운터를 T+1초에 읽고 시각은 T+15초로 기록**된다.
단일 스냅샷에서는 무해했다 — 그 시각은 "언제 뜬 덤프인가" 표시용이었다.

**v0.2는 그 시각으로 나눗셈을 한다.** 샘플마다 수집 소요가 다르면(느린 `cat_segments`,
그리고 장애 중에는 응답이 전반적으로 느려진다) 분모가 틀리고, 30초 간격에서 몇 초만
어긋나도 rate가 십수 퍼센트 흔들린다. **근거로 내놓는 숫자가 조용히 틀리는 자리**다.

- **`CollectionOutcome`이 `collected_at`을 갖는다** — 그 타깃의 응답을 받은 시각.
- **샘플·공유 블록의 시각은 `started_at`** — 그 묶음의 수집을 시작한 시각. 이름이 다르므로
  같은 사실이 두 곳에 적히는 것은 아니다(결정 32가 경계한 것은 *같은* 사실의 중복이다).
- **rate는 타깃별 시각으로 나눈다.** 카운터가 `_nodes/stats`에서 왔으면 두 샘플의
  `_nodes/stats` **수신 시각 차이**로 나눈다. 그것이 실제로 측정된 구간이다.
- 리포트 헤더는 첫 샘플의 `started_at`을 쓴다.

단일 스냅샷 덤프도 이 변경의 덕을 본다 — "이 응답이 언제 읽힌 것인가"는 그 자체로
evidence의 일부다.

수집 결과(`collection`)를 파일이 놓인 자리와 같은 곳에 적는 것은 어느 파일의 결과인지
헷갈리지 않게 하기 위해서다.

#### 샘플 하나가 실패하면 그 샘플만 버린다

REQUIRED 타깃 수집에 실패한 샘플은 **폐기하고 수집을 계속한다.** 살아남은 샘플이 하나도
없을 때 비로소 collect가 실패한다(종료 코드 2).

v0.1의 "REQUIRED 실패 = 즉시 중단"을 샘플 단위로 좁힌 것이다. REQUIRED가 실패하는 순간은
대개 **클러스터가 실제로 아픈 순간**이고, 그것이 덤프를 뜨러 온 이유다. 2분짜리 수집을
마지막 샘플의 일시적 503 때문에 통째로 버리면 정작 필요한 증거를 잃는다.

- **폐기한 샘플도 `metadata.json`의 `samples[]`에 사유와 함께 남긴다.** 즉 이 배열은
  아카이브에 쓰인 샘플이 아니라 **시도한 샘플 전부**를 기록하고, 폐기된 항목은 대응하는
  `collection/NN/` 디렉토리가 없다. 조용히 빠지면 읽는 사람이 "3샘플 수집"이라고 믿는다 —
  결정 18이 막으려던 것과 같은 형태의 거짓이다.
- **폐기된 샘플의 디렉토리는 아카이브에 넣지 않는다.** REQUIRED가 빠진 샘플 디렉토리를
  남기면 파서가 그것을 파손으로 보고 시퀀스 전체를 거부한다.
- 샘플이 빠져도 rate 계산은 어긋나지 않는다. 경과 시간을 요청한 간격이 아니라
  **타깃별 `collected_at` 차이**로 구하기 때문이다(10.3).

#### 구현 파급 — 진짜 비용은 `RawDump`다

아카이브 레이아웃보다 **경계 타입**이 더 많이 바뀐다. 지금 `RawDump`는 타깃당 payload가
하나(`Map<CollectTarget, String>`)라 샘플 N개를 담을 수 없다.

| 대상 | 변경 |
|---|---|
| `RawDump` | 공유 payload + 샘플별 payload 목록 |
| `ClusterSnapshotParser` | `ClusterSnapshot` 하나 → `SnapshotSequence` |
| `HttpDumpSource` | N회 수집 루프 |
| `TarGzDumpWriter` / `TarGzDumpSource` | 새 레이아웃, 경로 인식 |
| `Fixtures` (테스트) | 평평한 픽스처 디렉토리를 "공유분 + 샘플 1개"로 해석 |

**픽스처 파일 자체는 바꾸지 않는다.** `Fixtures`는 tar 리더를 거치지 않고 리소스
디렉토리에서 직접 `RawDump`를 만들므로, 해석만 바꾸면 기존 `normal/`·`required-only/`가
샘플 1개짜리 덤프로 그대로 읽힌다. 룰 테스트 22개도 영향받지 않는다.

### 10.3 `SnapshotSequence`와 시퀀스 룰

**진단 엔진이 받는 입력은 `SnapshotSequence` 하나다.** 샘플이 한 덤프에서 왔는지 여러
덤프에서 왔는지 룰은 알지 못한다 — live/offline을 구분하지 못하는 것(4.3)과 같은 구조이고,
덕분에 rate 룰을 한 번 쓰면 두 경로 모두에서 그대로 돈다.

**룰이 보는 것은 룰의 종류에 따라 다르다.** 시퀀스가 필요한 룰만 시퀀스를 받고, 나머지는
지금처럼 `ClusterSnapshot` 하나를 받는다. 시퀀스를 푸는 일은 엔진의 몫이다.

```
collect --samples 3   → 덤프 1개 안의 샘플 3개 ─┐
여러 덤프(8절 백로그)                          ─┴→ SnapshotSequence → 룰
```

- **`DiagnosticRule`은 그대로 둔다.** 기존 룰 3개와 앞으로의 설정·구조 룰은 한 장이면
  충분하고, 전부 시퀀스 타입으로 바꾸면 쓰지 않는 차원을 들고 다니게 된다.
  시퀀스가 필요한 룰만 **별도 인터페이스**로 선언한다 — "이 룰은 구간이 필요하다"가
  타입에 드러난다.
- **단일 스냅샷 룰은 마지막 샘플에 대해 평가한다.** 설정·구조 룰이 답하려는 질문은
  "지금 이 클러스터가 어떤 상태인가"이고, 그건 가장 최근 샘플이다. 첫 샘플로 잡으면
  관찰 중에 운영자가 고친 설정을 계속 문제라고 보고하게 된다. **리포트는 어느 샘플을
  기준으로 판정했는지 밝힌다** — 근거가 어느 시점의 것인지 모르면 evidence가 아니다.
  **이 판정은 10.2의 반복/공유 분류에 의존한다.** 룰이 쓰는 데이터가 마지막 샘플에
  없으면(= 공유 타깃으로 분류돼 첫 수집 값이 주입되면) 마지막 샘플을 고른 의미가 사라진다.
  `cluster_settings`가 반복 대상인 이유가 이것이다 — 둘 중 하나만 바꾸면 모순이 된다.
- **`RuleEngine`이 두 종류를 모두 실행해 하나의 `DiagnosticReport`로 모은다.** 정렬
  규칙(심각도 → ruleId)과 종료 코드 규칙(3.2)은 바뀌지 않는다. 룰 종류가 늘어난 것이지
  결과의 형태가 달라진 것이 아니다.
- **샘플이 1개인 덤프에서 시퀀스 룰은 `Skipped("requires at least 2 samples")`**가 된다.
  4.4의 "발화 안 함 ≠ 판단 불가"가 그대로 적용되는 자리다.
- **요청한 간격이 아니라 실제 경과 시간으로 나눈다.** 수집에 시간이 걸려 30초 요청이
  실제로는 34초일 수 있다. 그 시간은 **카운터를 읽어온 타깃의 `collected_at` 차이**로
  구한다 — 샘플 단위 시각으로 나누면 수집 소요 편차가 그대로 오차가 된다(10.2).
- **카운터 리셋을 감지한다.** 샘플 사이에 노드가 재시작하면 누적 카운터가 0으로 돌아가
  delta가 음수가 된다. 노드 기동 시각으로 감지해 해당 노드를 계산에서 빼고,
  **그 사실을 리포트에 남긴다.** 조용히 0으로 만들지 않는다(3.1의 빈 값 대체 금지와 같은 결).

### 10.4 v0.2 작업 순서

1. **구간 샘플링 인프라** — `collect --samples/--interval`, 덤프 레이아웃,
   `SnapshotSequence`, 시퀀스 룰 인터페이스
2. **첫 시퀀스 룰** — 스레드풀 rejection (누적 rejected + 현재 큐 깊이).
   `_nodes/stats`의 thread_pool 파싱 추가가 선행된다. 인프라가 실제로 도는지 증명하는 룰이다
3. **설정·구조 룰 4개** — 인프라와 무관하므로 병렬 가능

**1번을 먼저 하는 이유**는 v0.1에서 `RuleResult`를 룰 3개 만들기 전에 바꾼 것과 같다
(9절). 룰이 7개로 늘어난 뒤 시퀀스 인터페이스를 도입하면 룰과 룰 테스트를 전부 다시
손봐야 한다. 룰 시그니처가 굳지 않은 지금이 비용이 가장 낮다.

#### 룰 목록

| ID | Severity | 내용 | 필요한 것 |
|---|---|---|---|
| OSC-004 | CRITICAL / WARNING | **디스크 워터마크 도달** — `cat_allocation` 사용률 vs `cluster_settings`의 **실제** 워터마크 값 | 없음 |
| OSC-005 | WARNING | **워터마크 설정 자체가 오설정** — low/high/flood 역전, 단위 혼용. OSC-004의 짝이다(오설정된 기준으로 정상 판정하면 미탐) | 없음 |
| OSC-006 | CRITICAL | **flood stage로 인덱스 read-only** — `cluster.blocks.read_only_allow_delete` × 디스크 × health. "디스크를 비웠는데 쓰기가 계속 실패"의 정체 | 없음 |
| OSC-007 | CRITICAL | **레플리카 수 ≥ 데이터 노드 수** — `cat_indices`의 `rep` × 노드 수 × health YELLOW. 설정이 원인인데 증상은 health에 나타난다 | 없음 |
| OSC-008 | WARNING | **스레드풀 rejection** — 누적 rejected + 현재 큐 깊이 | 시퀀스, thread_pool 파싱 |

2차 후보(v0.2에서 다루지 않을 수 있음): 샤드 분포 불균형·노드당 샤드 과다
(핫/웜 티어의 **의도된** 불균형을 오탐하지 않을 임계값 근거가 선행 과제),
마스터 토폴로지(`cat_nodes` 파싱), pending tasks 적체, 과대/과소 샤드.

### 10.5 구간 샘플링으로도 판정할 수 없는 것

AutoOps 이벤트 카탈로그(AUTOOPS_BENCHMARK 2절)에는 이 도구의 구조상 다룰 수 없는 항목이
있다. 룰 후보를 고를 때마다 다시 논의하지 않도록 여기서 경계를 정한다.

| 항목 | 사유 | 재검토 조건 |
|---|---|---|
| Hot Thread | `_nodes/hot_threads`가 필요한데 응답이 JSON이 아닌 텍스트라 3.1에서 제외했다 | 텍스트 아티팩트 저장을 지원하면 |
| Hot Node | **원리적 제약이 아니다.** "한 노드만 유독 뜨겁다"는 이미 수집 중인 `_nodes/stats`의 노드별 CPU·load average로 판정된다 — 지금 파싱하지 않을 뿐이다 | `_nodes/stats`의 CPU·load 파싱을 추가하면 |
| Slow Search / Slow Indexing | 슬로우 **로그 파일** 기반이다. oss-check는 노드 파일시스템에 접근하지 않는다 | 없음 (스코프 밖) |
| `*_DISCONNECTED` 계열 | 상시 관측이 전제다. 샘플 구간에서 "그 사이에 없었다"까지만 말할 수 있다 | 없음 |
| 수 시간~수일 단위 추세 | 구간 샘플링의 범위를 넘는다 | 8절의 다중 덤프 입력 / `--watch` |
| Long Running Task | **원리적 제약이 아니다.** `_tasks`는 `running_time_in_nanos`를 주므로 한 장으로도 판정된다 — 단지 수집 목록에 없을 뿐이다 | `_tasks`를 `CollectTarget`에 추가하면 |

**누적 카운터를 현재 상태로 읽지 않는다**는 규칙은 구간 샘플링이 생긴 뒤에도 유효하다.
샘플이 1개뿐인 덤프에서는 여전히 누적값밖에 없기 때문이다(10.3의 SKIPPED).