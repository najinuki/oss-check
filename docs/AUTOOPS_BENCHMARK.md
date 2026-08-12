# Opster AutoOps 기능 전수 조사 (벤치마킹용)

> 이 문서는 oss-check의 향후 방향을 논의하기 위해 Opster AutoOps(현재 Elastic이 인수해
> Elastic Cloud 제품군에 통합됨)의 공개 문서를 조사해 기능을 전수 정리한 것이다.
> **설계 문서가 아니다.** oss-check에 무엇을 채택할지는 별도 논의 후 DESIGN.md에 반영한다.
>
> 출처: opster.com/docs/autoops, elastic.co/docs/deploy-manage/monitor/autoops,
> opster.com 제품/커뮤니티 페이지 (2026-07 기준 공개 문서. Elastic 인수 이후 문서가
> opster.com ↔ elastic.co 양쪽에 걸쳐 있어 일부는 마케팅 문구 수준으로만 확인됨)

## 1. 제품 포지셔닝

- Elasticsearch/OpenSearch 클러스터를 위한 **SaaS 기반** 관제·진단·최적화 플랫폼
- 경량 에이전트가 메트릭·통계만 수집(민감 정보 미수집 표방), 클러스터에 붙여 상시 모니터링
- "수백 개 메트릭을 실시간 분석 → 근본 원인 분석(root cause) → 구체적 해결 경로 제시"가 핵심 슬로건
- Elastic Cloud Hosted / Serverless / ECE / ECK / self-managed(Cloud Connected) 전부 지원
- oss-check와의 근본적 차이: **AutoOps는 상시 상주 SaaS 에이전트**, oss-check는 **일회성 air-gapped CLI**

## 2. 진단 엔진 — 이벤트(체크) 카탈로그

AutoOps Public API 문서에 노출된 이벤트 타입 59종(전체 목록, 카테고리는 조사자가 분류):

### 2.1 클러스터 헬스 / 가용성
- `STATUS_YELLOW`, `STATUS_RED`
- `MASTER_NOT_DISCOVERED`
- `MASTER_NODE_DISCONNECTED`, `DATA_NODE_DISCONNECTED`, `COORDINATING_NODE_DISCONNECTED`
- `CLUSTER_BLOCKS_READ_ONLY`, `CLUSTER_BLOCKS_READ_ONLY_ALLOW_DELETE`

### 2.2 디스크 워터마크
- `DISK_WATERMARK_LOW_THRESHOLD`, `DISK_WATERMARK_LOW`, `DISK_WATERMARK_HIGH`, `DISK_WATERMARK_FLOOD_STAGE`
- `DISK_WATERMARKS_WRONG_CONFIGURATION` (워터마크 설정값 자체의 오설정)

### 2.3 샤드 사이징 / 개수
- `SHARD_TOO_LARGE`, `SHARD_TOO_SMALL`
- `MAX_SHARD_PER_NODE`, `TOTAL_SHARD_PER_NODE`, `TOTAL_SHARD_PER_NODE_UNLIMITED`
- `NODE_CONTAINS_TOO_MANY_SHARDS`
- `UNBALANCED_SHARDS` (노드 간 샤드 분포 불균형)
- `NO_SHARDS_IN_DATA_NODE` (데이터 노드인데 샤드 0개 — 자원 낭비)

### 2.4 마스터/노드 토폴로지 설정
- `MIXED_MASTER_NODES` (마스터 자격 노드의 버전/설정 혼재)
- `MIN_MASTER_NODE_HIGHER_THAN_ELIGIBLE`, `MIN_MASTER_NODE_LESS_THAN_QUORUM`, `MIN_MASTER_NODE_HIGHER_THAN_QUORUM`
- `NUMBER_OF_MASTER_NODES` (권장 마스터 수 이탈, 통상 짝수 등 스플릿브레인 위험)
- `DEDICATED_MASTER_NODES`, `DEDICATED_CLIENT_NODES` (전용 노드 역할 분리 권고)
- `COORDINATING_NODE_NOT_UTILIZED` (코디네이팅 전용 노드 있는데 활용 안 됨)

### 2.5 리밸런스 / 리커버리 설정
- `CLUSTER_CONCURRENT_REBALANCE_HIGH`, `CLUSTER_CONCURRENT_REBALANCE_LOW`
- `NODE_CONCURRENT_RECOVERIES_HIGH`, `NODE_CONCURRENT_RECOVERIES_LOW`
- `SHARD_ALLOCATION_ENABLE_ALL`, `SHARD_REBALANCE_ENABLE_ALL` (설정이 기본값인지/의도한 제한인지 점검 — oss-check의 OSC-003과 결이 비슷)

### 2.6 부하 / 리소스
- `LOADED_DATA_NODES`, `LOADED_MASTER_NODES`, `LOADED_CLIENT_NODES` (역할별 과부하 노드)
- `MAX_HEAP_SIZE_REACHED`
- `CIRCUIT_BREAKER`, `CIRCUIT_BREAKER_USED_IS_HIGH` (oss-check OSC-001과 동일 계열)

### 2.7 인덱싱 / 검색 성능·실패
- `REJECTED_INDEXING`, `REJECTED_SEARCH`
- `NODE_INDEXING_FAILED`, `INDEX_INDEXING_FAILED`
- `SLOW_SEARCH`, `SLOW_INDEXING`
- `SEARCH_REJECTED_QUEUE`, `SEARCH_QUEUE_SIZE`, `INDEX_QUEUE_SIZE`, `MANAGEMENT_QUEUE_SIZE` (스레드풀 큐 포화)
- `HIGH_CLUSTER_PENDING_TASKS`

### 2.8 데이터 구조 위생
- `DETECTED_EMPTY_INDICES`, `DETECTED_EMPTY_REPLICAS` (자원 낭비성 빈 인덱스/불필요 레플리카)

### 2.9 장기 실행 작업
- `LONG_RUNNING_SEARCH_TASK`, `LONG_RUNNING_INDEX_TASK`, `LONG_RUNNING_SHARD_TASK`, `LONG_RUNNING_SNAPSHOT_TASK`

### 2.10 스냅샷
- `REPOSITORY_SNAPSHOT` (스냅샷 저장소 관련 이상)

> 각 이벤트는 심각도(severity)를 갖고, 발생 시 영향받는 노드/인덱스/샤드와 **권장 조치(해결 경로)**를
> 함께 제시한다. oss-check의 `Finding{evidence, recommendation}` 구조와 개념적으로 동일하다.

## 3. 대시보드 뷰 (UI 구성)

SaaS 대시보드는 6개 뷰로 구성:

| 뷰 | 내용 |
|---|---|
| **Overview** | 조직 내 전체 배포/클러스터 목록, 활성 크리티컬 이벤트 수, 상위 10개 이벤트, 기간별 이벤트 발생 추이 히트맵 |
| **Deployment/Cluster** | 클러스터 단위 이벤트 제어판. Events Over Time(히트맵), Open Events, Events History, Resources(티어별 JVM/CPU/스토리지 추이), Performance(검색/색인 rate·latency) |
| **Nodes** | 노드별 9개 카테고리 메트릭: Activity(색인·검색 rate/latency), Host/Process(load, CPU, heap, GC), Thread pool(큐 깊이, rejected), Data(디스크, 샤드 수, 세그먼트 수, 문서 수), HTTP(연결 수), Circuit breaker(파훼 이벤트), Network, Disk I/O, 병합(merge) rate/latency |
| **Indices** | 인덱스별 표: 샤드 수, 색인/검색 rate·latency, 문서 수, 사이즈, 에러 수, 병합 rate. 행 확장 시 실시간 메트릭 |
| **Shards** | 샤드 단위 상세: 사이즈, 문서 수, 노드별 배치. 7개 기준(색인/검색/병합 rate·latency, 사이즈) 정렬 + 시간 슬라이더로 과거 시점 조회 |
| **Template Optimizer** | 템플릿(매핑) 최적화 권고 — 필드 타입 최적화(예: 정수 범위 쿼리에 integer/long 사용), 과거 적용 이력 추적 |

## 4. 자동 조치 — AutoOps Operator (클러스터에 실제로 작업을 실행하는 컴포넌트)

SaaS 대시보드와 별도로, 클러스터 내부/근처에 배치되어 **실제 조치를 수행**하는 오퍼레이터:

- **Delete Index**: prefix 매칭 + retention 경과 인덱스 삭제 (최신 문서의 timeField 기준 나이 판단)
- **Delete Empty Index**: 생성 24시간 경과 빈 인덱스, 또는 alias의 비-write 인덱스 정리
- **Rollover Index**: 사이즈/문서수/나이 임계값 도달 시 롤오버, 롤오버 후 force-merge 등 후속 액션 지원
- **Reindex**: 소스→타겟 재색인, 병렬 서브프로세스 + 재시도 로직
- **Shrink Index**: 샤드가 작은 인덱스를 프라이머리 샤드 수를 줄여 통합
- **Split Index**: 50GB 초과 등 과대 인덱스를 더 많은 프라이머리 샤드로 분할
- **Optimize Indices**: 다수의 작은 인덱스를 alias 유지하며 더 적은 큰 인덱스로 통합 (클러스터 스테이트 절감)
- 안전장치: shrink/split에 **dry-run**, 재시도 로직, 인덱스 제외(exclude) 필터, 실행 주기 설정

> DESIGN.md 7절에서 oss-check는 `fix` 명령(자동 조치)을 v0.1 명시적 제외로 못박았는데,
> AutoOps Operator가 바로 그 영역이다. v0.2 백로그의 "dry-run 우선, 되돌리기 가능한 조치만"
> 방향성과 궤가 같다.

## 5. AI 어시스턴트 — OpsGPT

- 클러스터에 연결된 상태로 실시간 데이터 기반 Q&A를 제공하는 대화형 어시스턴트
- "왜 이 인덱스가 느린가", "지금 힙 상황이 어떤가" 같은 질의에 라이브 데이터로 응답
- oss-check DESIGN.md v0.2 백로그의 "MCP 서버 모드: 같은 진단 엔진을 LLM이 호출"과 목적이 유사
  (다만 AutoOps는 자체 챗봇 UI, oss-check 구상은 LLM 클라이언트가 도구로 호출하는 MCP 서버)

## 6. 알림/통합

- Slack, PagerDuty, Opsgenie, Microsoft Teams, VictorOps, 커스텀 Webhook
- 이벤트별 알림 트리거 커스터마이징 (Event Settings)
- Ticket System 연동 가이드 존재

## 7. Search Gateway (별도 서브 제품)

- 클러스터 앞단에 프록시 형태로 배치해 **무거운 검색 쿼리를 사전 차단/제한**
- 슬로우 로그가 잘리는 문제 없이 heavy search를 탐지하는 용도로도 소개됨
- "블로킹 heavy search"라는 AutoOps 마케팅 문구의 실체가 이 컴포넌트로 보임

## 8. 커뮤니티 무료 도구 (SaaS 가입 없이 사용 가능)

- **Check-Up**: `_cluster/settings`, `_nodes/stats` 등 JSON 덤프 2개를 업로드하면 정적 분석 후
  개인화된 권고 리포트 생성. **oss-check의 `collect`+`diagnose` 조합과 컨셉이 가장 가깝다**
  (다만 웹 업로드 기반이라 air-gapped 전제가 없고, 룰 내용이 비공개 블랙박스)
- **Search Log Analyzer**: 슬로우 로그 파일 분석 → 느린 쿼리 원인(집계 과다, size 파라미터 과다 등) 리포트
- 두 도구 모두 20,000+ 사용자 보유 표방

## 9. 비용 최적화

- 리소스 사용률 분석 기반 하드웨어 다운사이징 권고
- 매핑/템플릿 최적화 → 스토리지·힙 절감 → 인스턴스 사이즈 축소로 연결되는 내러티브

## 10. 보안/컴플라이언스

- 경량 에이전트, 메트릭/통계만 수집(문서 내용·민감정보 미수집 표방)
- SOC-2 인증 표방

---

## oss-check 대비 정리 (참고용, 결론 아님)

| 축 | AutoOps | oss-check (현재 DESIGN.md) |
|---|---|---|
| 실행 방식 | 상주 SaaS 에이전트, 상시 모니터링 | 일회성 CLI, air-gapped |
| 룰 규모 | 이벤트 59종+ (공개된 것만), 계속 확장 | 3개로 시작, Java 코드 하드코딩 |
| 자동 조치 | Operator가 실제 실행 (dry-run 지원) | v0.1 명시적 제외, v0.2 백로그 |
| AI 인터페이스 | OpsGPT 챗봇 | v0.2 백로그 MCP 서버 모드 |
| 뷰/대시보드 | 6개 뷰, 시계열 UI | 텍스트/JSON 출력만 |
| 비용 모델 | SaaS 구독 | 무료 배포 전제(단일 jar) |
| 데이터 반출 | 클러스터에 상시 연결, 벤더 서버로 메트릭 전송 | 텔레메트리 제로가 핵심 차별화 |

이 표는 논의를 위한 참고 자료다. oss-check가 AutoOps의 어느 축을 어디까지 따라갈지는
DESIGN.md의 "air-gapped by design" 정체성과 충돌하는 지점(상시 모니터링, 자동 조치,
벤더 SaaS)이 있으므로 별도 논의가 필요하다.

---

## 11. 오픈소스 대안 조사 (2026-07 기준)

**결론: AutoOps처럼 "여러 API 응답을 교차 분석해 근본 원인 + 조치안을 내는" 오픈소스 도구는
사실상 존재하지 않는다.** OpenSearch 자체에 유일하게 그 역할을 하던 컴포넌트(RCA 프레임워크)마저
공식적으로 폐기(deprecate) 수순이다. 대신 아래 세 계층으로 기능이 쪼개져서 부분적으로만
존재한다: **① 수집(collect)만 하는 도구, ② 정적 임계값 알림만 하는 도구, ③ 인덱스
생명주기 자동화만 하는 도구.** "교차 참조 추론 체인 + evidence + recommendation"을 한 번에
묶어 내는 도구는 없다 — 이게 oss-check가 메우려는 공백과 정확히 일치한다.

### 11.1 유일하게 근접했던 시도: OpenSearch Performance Analyzer + RCA Framework — **폐기 진행 중**

- 저장소: [`opensearch-project/performance-analyzer-rca`](https://github.com/opensearch-project/performance-analyzer-rca) (Apache 2.0)
- OpenSearch 프로젝트가 직접 만든, **개념적으로 AutoOps와 가장 가까운 시도**였다.
  메트릭(leaf node) → symptom(중간 판단) → RCA(최종 근본원인) 형태의 **데이터플로우 그래프**로
  구성되어 있어, oss-check의 "여러 API 교차 참조 + 추론 체인" 철학과 정확히 같은 아이디어다.
- 확인된 RCA 종류(일부): `HighHeapUsageClusterRca`, `OldGenRca`, `HotNodeRca` / `HotNodeClusterRca`,
  `HotShardRca` / `HotShardClusterRca`, 그 외 `admissioncontrol`, `cache`, `jvmsizing`,
  `searchbackpressure`, `temperature`(hot/warm 데이터 티어 불균형), `threadpool` 관련 RCA 존재
- **하지만**: README에 스스로 "alpha code, 테스트 커버리지 부족"이라 명시. GitHub 스타 34개 수준으로
  커뮤니티가 거의 없었고, **[Issue #591 "Deprecate performance-analyzer-rca"](https://github.com/opensearch-project/performance-analyzer-rca/issues/591)**
  가 OpenSearch 3.0 릴리스 라벨로 열려 있다 — 공식적으로 폐기 대상. OpenSearch는 이 자리를
  OpenTelemetry 기반 **Telemetry 플러그인**으로 대체할 방침인데, 이는 **메트릭 export까지만** 하는
  관측성(observability) 계층이지 RCA/추천 계층이 아니다. 즉 OpenSearch 진영에서 "근본원인 추론"
  기능 자체가 없어지는 방향.

### 11.2 수집(collect)만 하는 도구 — 분석/추천 없음

| 도구 | 라이선스 | 비고 |
|---|---|---|
| [`elastic/support-diagnostics`](https://github.com/elastic/support-diagnostics) | Elastic License v2 (소스 공개, OSI Apache는 아님) | 공식 진단 번들 수집기. 966 커밋, 90+ 릴리스로 활발히 유지보수됨(최신 v9.4.1). REST 응답·시스템 메트릭(top/netstat/iostat)·스레드덤프·로그를 모아 아카이브로 묶을 뿐, **분석/해석은 하지 않고 지원 엔지니어가 수동으로 본다** — oss-check의 `collect`만 있고 `diagnose`가 없는 상태와 동일 |
| `jfcarp/elasticsearch-diagnostics`, `ESamir/elasticsearch-support-diagnostics` 등 개인 프로젝트 | 다양 | 커뮤니티 소규모 스크립트 수준, 유지보수 사실상 중단 |

### 11.3 정적 임계값 알림 — 교차 추론 없음

| 도구 | 비고 |
|---|---|
| `prometheus-community/elasticsearch_exporter` + 커뮤니티 알림 룰(`lukas-vlcek/prometheus-elasticsearch-rules`, `bdossantos/prometheus-alert-rules`, `openshift/elasticsearch-operator`의 `prometheus_alerts.yml` 등) | 디스크 워터마크(85/90/95%), 서킷브레이커 트립, unassigned shard 등 **개별 메트릭이 임계값을 넘으면 알림**만 한다. AutoOps처럼 "이 이벤트가 왜 발생했는지, 다른 API 응답과 어떤 인과관계인지"는 다루지 않음 — 순수 threshold watcher |
| ElastAlert2 | Elasticsearch/OpenSearch **데이터(로그)** 내용 기반 알림 도구. 클러스터 자체의 헬스/구성을 진단하는 도구가 아니라 애초에 카테고리가 다름 |

### 11.4 인덱스 생명주기 자동화 — AutoOps Operator의 일부와 겹침

| 도구 | 비고 |
|---|---|
| **OpenSearch Index State Management(ISM)** 플러그인 — OpenSearch 코어 내장 | rollover/shrink/delete/snapshot을 정책 기반으로 자동화. AutoOps Operator의 index 관리 기능과 가장 근접하지만, **"언제 실행할지"를 사용자가 정책으로 미리 정의**해야 함 — AutoOps처럼 "지금 이 인덱스가 문제니 자동으로 shrink하자"는 진단 기반 트리거는 없음 |
| **Elasticsearch Curator** (`elastic/curator`) | rollover/shrink/delete/forcemerge 등 액션을 설정 파일로 정의해 실행. Elasticsearch 진영 도구이며 최근 ILM/ISM에 밀려 레거시화되는 추세 |

### 11.5 모니터링/관리 UI — 진단 로직 없음, 사실상 방치 상태

| 도구 | 라이선스 | 상태 |
|---|---|---|
| Cerebro (`lmenezes/cerebro`) | MIT | 마지막 릴리스 2021년. 샤드 재동기화, 스냅샷 등 수동 조작 UI. 자동 진단·추천 없음 |
| ElasticHQ | 미상 (Python, Docker 100만+ 다운로드, GitHub 4.3k star) | 클러스터/노드/인덱스/샤드 모니터링 대시보드. 자격증명을 평문 저장하는 등 보안 이슈 지적됨. 진단 추론 없음 |
| elasticsearch-head | 미상 | 마지막 릴리스 2018년, 사실상 방치 |

### 11.6 "오픈소스"를 표방하지만 실제로는 폐쇄 SaaS인 경우 — 주의

- **OpenSearch Doctor** (opensearchdoctor.com): "50+ 자동 체크"를 표방하지만, 실제로는 **경량
  에이전트만 배포**하고 진단 로직(룰 엔진)은 벤더의 호스티드 SaaS 백엔드에서 실행된다.
  홈페이지에 "에이전트는 저희 플랫폼에 도달할 아웃바운드 인터넷 연결만 있으면 된다"고 명시 —
  **air-gapped 불가능**. Opster의 Check-Up과 동일 계열(무료 미끼 상품 + 클라우드 분석)이며,
  "open-source"라는 표현은 에이전트(수집기)에만 해당하고 핵심 가치인 진단 로직은 비공개다.
  oss-check와 정반대 지점에 있는 도구로 이해하는 게 정확하다.

### 11.7 결론 — 왜 "비슷한 툴이 없다"는 느낌이 드는가

1. **커뮤니티 OSS는 대부분 "수집" 또는 "정적 임계값 알림" 둘 중 하나에 머문다.** 여러 API를
   의미적으로 교차 참조해서(예: breaker tripped + heap 사용률 + top_queries 인덱스 존재를 동시에
   봐야 나오는 결론) 추론 체인을 만드는 시도는 공수가 커서 커뮤니티 프로젝트로 잘 나오지 않는다.
2. **OpenSearch 진영이 유일하게 만들었던 RCA 프레임워크조차 alpha 상태로 방치되다가 공식
   폐기 이슈가 열렸다** — 즉 "만들기 어렵다"를 프로젝트 소유자인 OpenSearch 재단 스스로
   증명한 셈. 벤더(Opster/Elastic, 그리고 OpenSearch Doctor류)들은 이 공백을 **유료/SaaS**로
   메우고 있고, 그게 정확히 AutoOps가 파는 것이다.
3. 결과적으로 oss-check가 노리는 지점 — **"근본원인 추론 체인 + evidence + recommendation"을
   air-gapped·무료·오픈소스로 제공** — 은 현재 OSS 생태계에 빈 자리가 맞다. 벤치마킹 대상이
   AutoOps 하나뿐인 이유가 바로 이것이다.

---

## 12. 성숙도 단계 모델 (2026-08-10 추가)

AutoOps를 단계로 쪼개 보면 진단 제품이 어디까지 가는지가 드러난다. 아래 모델은 별도로
정리된 요약을 검토해 이 프로젝트 제약에 맞게 **정정한 뒤** 옮긴 것이다.

| 단계 | 내용 | oss-check 현재 |
|---|---|---|
| 1 | 데이터 수집 | ✅ `collect` — 15개 타깃 |
| 2 | 룰 기반 점검 | ✅ `RuleEngine` + 룰 3개 |
| 3 | 진단(현상 서술) | ✅ `Finding.finding` |
| 4 | 교차 참조(correlation) | ✅ **룰 그 자체** (아래 정정 1) |
| 5 | 조치 권고 | ✅ `Finding.recommendation` |
| 6 | 실행 가능한 명령 생성 | ✅ recommendation이 구체적 API 호출을 담는다 |
| 7 | 승인 후 자동 실행 | ❌ `fix` — DESIGN.md 7절 제외 |

**정정 1 — correlation은 별도 계층이 아니다.** 원 모델은 "진단" 위에 "correlation"을
얹힌 층으로 그린다. 이 프로젝트에서 교차 참조는 룰의 본체이지(DESIGN.md 4.3) 룰 위에
올릴 것이 아니다. 별도 계층으로 오해하면 룰 위에 상관분석 엔진을 또 만드는,
목적 없는 추상화가 된다.

**정정 2 — 6단계는 이미 되어 있다.** `Finding.recommendation`은 처음부터 실행 가능한
API 호출을 담는다(OSC-003의 `PUT _cluster/settings {...}`). 따라서 AutoOps 대비 실제
공백은 7단계 하나이며, 그것이 DESIGN.md 7절이 의도적으로 제외한 `fix`다.

### 12.1 수집 형태가 만드는 제약 (조사 관찰)

**AutoOps는 상주 에이전트이고 oss-check는 시점 아티팩트다.** 이 차이 때문에 벤치마크
목록의 일부는 "채택할까"가 아니라 "이 수집 형태로 닿는가"의 문제가 된다. 조사하면서
확인된 것을 아래에 적는다.

| 항목 | 무엇이 걸리는가 |
|---|---|
| Hot Thread 탐지 | `_nodes/hot_threads` 응답이 JSON이 아닌 텍스트다 (DESIGN.md 3.1이 제외한 이유) |
| Hot Node 탐지 | 걸리는 것이 없다 — `_nodes/stats`의 노드별 CPU·load average로 판정 가능하고, 지금 파싱하지 않을 뿐이다 |
| Slow Search / Slow Indexing | 슬로우 **로그 파일** 기반이다. oss-check는 노드 파일시스템에 접근하지 않는다 |
| rate·latency 계열 | 두 시점 이상이 필요하다 |
| `*_DISCONNECTED` 계열 | 상시 관측이 전제다 |
| Long Running Task | `_tasks`가 수집 목록에 없다. **단, 원리적 제약은 아니다** — `_tasks`는 `running_time_in_nanos`를 주므로 한 번만 불러도 "10분째 돌고 있다"를 알 수 있다 |

**누적 카운터를 현재 상태로 읽지 않는다.** `rejected`·`tripped`는 노드 기동 이후 누적이라
단독으로는 몇 달 전 장애를 지금 일로 보고하게 된다(OSC-001이 tripped와 heap을 짝지은 이유).
이 계열은 **현재를 말해주는 값과 짝지어야** 쓸 수 있다 — rejection이라면 누적 rejected +
현재 큐 깊이.

> **이 표는 관찰이지 결정이 아니다.** 이 중 무엇을 실제로 제외하고 무엇을 어떤 조건에서
> 다시 볼지는 **DESIGN.md 10.5**에서 정한다. 특히 rate·latency 계열은 v0.2의 구간
> 샘플링(DESIGN.md 10.2)으로 상당 부분 판정 범위에 들어왔다 — 이 문서의 관찰이 곧
> 스코프가 아니라는 예다.

### 12.2 air-gap 차별화 (기존 결론 재확인)

별도 요약도 같은 결론에 도달했다: AutoOps의 구조는 `Agent → Internet → AutoOps Cloud`라
완전 폐쇄망에서는 쓸 수 없고, 문서 본문을 보내지 않더라도 **인덱스명·노드 정보·클러스터
구조·템플릿**은 외부로 나간다. 금융·공공·국방·산업제어망이 oss-check의 자리다.
이는 DESIGN.md 1절의 "air-gapped by design"과 이미 일치하므로 새로운 결정 사항은 아니다.
