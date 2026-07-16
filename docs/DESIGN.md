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
- 배포: **단일 실행 uber-jar** (`java -jar os-check.jar`). Java 25 외 런타임 의존성 없음.
- OpenSearch 지원 버전: **2.x 우선**, 1.3.x는 best-effort (README에 명시)
- 출력 언어: **영문 단일**

Spring Boot 선택 이유: 개발자 생산성 + v0.2의 MCP 서버 모드(장기 실행 프로세스) 확장 기반.
CLI 도구치고 무겁다는 트레이드오프는 인지하고 수용한 결정이다.

## 3. 명령어 명세 (2개, 그 이상 없음)

### 3.1 `collect` — API 응답 일괄 수집 → tar.gz 생성

폐쇄망에서 덤프를 반출해 외부에서 분석하는 시나리오용.

- **수집 엔드포인트 (고정 목록)**:
    - `_cluster/health`
    - `_cluster/settings?include_defaults=true`
    - `_cluster/allocation/explain`
    - `_nodes/stats`
    - `_cat/shards?format=json`
    - `_cat/indices?format=json`
    - `_cat/allocation?format=json`
- **접속 옵션**: `--endpoint`, `--user` / `--password` (환경변수 대체 가능), `--insecure` (자체 서명 인증서 허용)
- **출력물에 메타데이터 파일 포함**: 수집 시각, 도구 버전, 클러스터 이름/버전
- 외부 통신은 대상 클러스터 단 하나뿐

### 3.2 `diagnose` — 룰 엔진 실행

- **live 모드**: `--endpoint` 지정 시, collect와 동일한 수집기를 메모리 상에서 실행 후 진단
- **offline 모드**: `--input dump.tar.gz` 지정 시, 덤프 파일만으로 진단 (네트워크 제로)
- **입력 포맷은 tar.gz 단일로 못 박는다** (개별 JSON 파일/디렉토리 입력은 백로그)
- **출력**: 사람이 읽는 텍스트(기본) + `--format json`
- **종료 코드**: `0` = 발견 없음 / `1` = 발견 있음 / `2` = 실행 오류 (스크립트·cron 연동용)

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
    Optional<Finding> evaluate(ClusterSnapshot snapshot);
}
```

- **`ClusterSnapshot`**: collect가 모은 모든 API 응답을 파싱해 담은 **불변 객체**.
  룰은 이것만 보고 판단하며, live/offline 모드 여부를 전혀 알지 못한다.
- **`Finding` 출력 구조 (모든 룰 통일)**:
    - `ruleId` — 예: `OSC-001`
    - `severity` — CRITICAL / WARNING / INFO
    - `finding` — 무엇이 문제인지 한 줄
    - `evidence` — 어떤 API의 어떤 필드 값이 근거인지 (예: `nodes.stats.breakers.parent.tripped = 847`)
    - `recommendation` — 실행 가능한 조치 (구체적 API 호출 예시 포함)

### 4.3 임계값 처리

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

## 6. 테스트 전략

**픽스처 = 실제 API 응답 형태의 JSON 덤프** (`src/test/resources`). offline 모드가 곧 테스트 하네스.

### 룰당 픽스처 3종 세트

1. **양성(positive)**: 장애 시점 덤프 → 룰이 반드시 탐지. finding 내용과 evidence 필드 값까지 assert
2. **음성(negative)**: 정상 클러스터 덤프 → 절대 발화 금지. 오탐 방어선 (오탐이 미탐보다 도구 신뢰를 더 죽인다)
3. **경계(edge)**: 임계 근처 값 (예: 샤드 사용률 89% vs 91%) → 발화 기준 정확성 검증

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

## 9. 다음 작업 (현재 위치)

1. ~~`ClusterSnapshot` 필드 설계~~ ✅ 완료 — `snapshot` 패키지 (모델 record + `ClusterSnapshotParser`)
2. ~~프로젝트 골격: 디렉토리 구조, 룰 엔진 인터페이스, 수집기 계층~~ ✅ 완료 — `rule` 패키지(인터페이스·엔진), `collect` 패키지(`CollectTarget`/`RawDump`/`DumpSource` 경계)
3. collect 구현 → diagnose offline 모드 → 룰 3개 → live 모드 순서 권장 ← **여기부터 시작**

구현 노트 (파싱 관련 결정):
- _cat 엔드포인트는 `bytes=b` 파라미터를 추가해 수집 (사이즈를 사람용 문자열 대신 숫자로 받음).
  단, 파서(`SizeParser`)는 `"1.2gb"` 형태도 허용 — 외부에서 만든 덤프 호환.
- `_cluster/allocation/explain`은 unassigned 샤드가 없으면 HTTP 400 에러 바디를 반환 →
  스냅샷에서 `Optional.empty()`로 처리 (파싱 실패 아님).
- 클러스터 설정은 파싱 시점에 dotted key로 평탄화. `ClusterSettings.effective()`가
  transient > persistent > defaults 우선순위 적용, `explicit()`은 운영자가 명시한 값만 조회.