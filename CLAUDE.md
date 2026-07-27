# CLAUDE.md

OpenSearch 클러스터 진단 CLI. 여러 API 응답을 교차 분석해 근본 원인을 추론하고
**근거(evidence) + 조치안(recommendation)**까지 출력한다. 단순 임계값 체크 도구가 아니다.

## 문서가 먼저다

작업 전에 반드시 읽는다:

- **[docs/DESIGN.md](docs/DESIGN.md)** — 확정된 설계 결정. 코드는 항상 이 문서를 따른다.
    - **9절 = 현재 위치**. 무엇부터 할지는 여기서 확인한다.
    - **7절 = v0.1 명시적 제외 목록**. 여기 적힌 기능은 요청받지 않는 한 절대 만들지 않는다.
- **[docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)** — 구현 구조 + 결정 로그(왜 그렇게 했는지).
- [docs/AUTOOPS_BENCHMARK.md](docs/AUTOOPS_BENCHMARK.md) — 경쟁 제품 조사 자료. **설계 문서가 아니다.**
  여기 있는 기능을 근거로 스코프를 넓히지 않는다.

**읽는 것은 항상, 고치는 것은 확인 후.** 문서는 작업 전에 반드시 읽지만,
`docs/` 아래 문서를 고치는 것은 **별도 요청이 있을 때만** 한다 (코딩 행동 원칙 3 참고).

**설계 변경은 DESIGN.md에서만 한다.** 코드를 먼저 바꾸고 문서를 나중에 맞추지 않는다.
설계에 없는 것을 구현해야 하는 상황이면 코드를 쓰기 전에 멈추고 설계 변경을 먼저 제안한다.

## 빌드 / 테스트

```bash
./gradlew test          # 테스트 (Spring 컨텍스트 없이 도는 순수 단위 테스트가 대부분)
./gradlew build         # 빌드 + 테스트
./gradlew bootJar       # 배포물: 단일 실행 uber-jar
```

Java 25 toolchain, Spring Boot 4.1, picocli, Jackson 3(`tools.jackson`), Lombok.

## 아키텍처

```
com.nj.oss.check
├── OssCheckApplication   # Spring Boot 진입점 (CLI 와이어링)
├── snapshot/             # ClusterSnapshot 모델 (record·enum) — 순수 Java
│   └── parse/            # wire format을 아는 유일한 곳 (파서·SizeParser·예외)
├── rule/                 # 룰 엔진 + 룰 공통 타입 — 순수 Java
│   └── catalog/          # 룰 구현체 (OSC-001 작성 시 생성)
└── collect/              # 수집 계층 (CollectTarget / RawDump / DumpSource)
```

데이터 흐름은 한 방향이다:

```
DumpSource (HTTP live | tar.gz 리더) → RawDump → ClusterSnapshotParser → ClusterSnapshot → RuleEngine → 리포트
```

- **`snapshot` / `rule` / `collect`는 Spring 비의존.** 여기에 `@Component`, `@Autowired`,
  `org.springframework.*` import를 넣지 않는다. Spring/picocli 와이어링은 CLI 계층에서만 한다.
- **`ClusterSnapshot`은 불변**이고, 룰이 보는 유일한 입력이다. 룰은 live/offline 출처를 알지 못한다.
  룰이 파일이나 HTTP를 직접 만지면 설계 위반이다.
- **`CollectTarget`이 엔드포인트 목록의 단일 진실.** live 수집기와 tar.gz 리더가 이걸 공유해서
  두 모드의 덤프 구조가 항상 일치한다. 엔드포인트 추가는 여기 한 줄로 끝나야 한다.
- **`RawDump`가 collect 계층과 파서 사이의 경계**다. live/offline 두 경로가 여기서 수렴한다.
- **패키지는 "무엇에 대한 것인가"로만 나눈다.** record/enum과 클래스를 갈라놓는 `entity/`·`model/`류
  분리는 하지 않는다 — 여기 record 대부분이 도메인 로직을 들고 있어(`ClusterSettings.effective()`,
  `CollectTarget.isRequired()`) 이름과 내용이 어긋나고, 같이 변하는 것들을 갈라놓는다.

## 지켜야 할 규칙

**미탐(false negative)을 조용히 만들지 않는다.** 이 프로젝트에서 가장 중요한 원칙이다.

- 필수 파일 누락·JSON 파손 → `SnapshotParseException`으로 **시끄럽게 실패**한다(종료 코드 2).
  기본값으로 때우고 넘어가지 않는다.
- 선택 데이터가 없어서 판단 못 하는 룰은 `RuleResult.Skipped(reason)`로 **리포트에 노출**한다.
  "발화 안 함"과 "판단할 데이터가 없음"을 같은 값으로 뭉개지 않는다.
- 없는 데이터를 빈 리스트·빈 맵으로 대체하지 않는다. `Optional`을 쓴다.
  ("설정이 비어 있다"와 "설정을 못 읽었다"는 다른 사실이다.)

**오탐(false positive)이 미탐보다 도구 신뢰를 더 죽인다.** 룰마다 정상 클러스터 픽스처에서
절대 발화하지 않는 음성 테스트를 반드시 둔다.

**임계값은 전부 명명된 상수 + 근거 주석.** 매직 넘버 금지. 테스트도 상수를 참조한다.

**air-gapped by design.** 텔레메트리·업데이트 체크·외부 호출 제로. 유일한 네트워크 통신은
대상 클러스터뿐이다. 어떤 이유로도 외부 호출을 추가하지 않는다.

**룰은 Java 코드다.** YAML/JSON 룰 정의, DSL, 플러그인 구조를 만들지 않는다(DESIGN.md 4.1).

## 코드 스타일

- **사용자에게 보이는 출력·코드 주석·Javadoc·README는 전부 영문.** 문서(`docs/`)만 한국어.
- record + `sealed interface` 우선. 모델은 불변으로.
- nullable 필드는 boxed 타입(`Long`, `Integer`)으로 표현해 의도를 드러낸다.
- 테스트 픽스처는 **실제 API 응답 형태의 JSON** (`src/test/resources/fixtures/`).
  파일명은 `CollectTarget`의 `fileName()`과 동일해야 한다 — offline 덤프 구조를 그대로 재현.

## 지원 범위

- OpenSearch **2.10 ~ 3.x**. 2.10 미만·1.x는 미지원.
- 명령은 `collect`와 `diagnose` **2개뿐**. 세 번째 명령을 추가하지 않는다.
- 진단 입력 포맷은 **tar.gz 단일**. 개별 JSON/디렉토리 입력은 v0.1 제외 항목이다.

## 코딩 행동 원칙 (Coding Behavior Principles)

Andrej Karpathy가 관찰한 LLM의 나쁜 습관을 방지하기 위한 원칙입니다.

### 1. 코딩 전에 먼저 생각하라

- 가정이 있으면 명시적으로 표현하고 확인을 받은 후 진행한다
- 해석이 여러 갈래면 조용히 하나를 선택하지 말고 제시하고 선택하게 한다
- 더 간단한 방법이 있으면 먼저 말하고, 현재 방향이 잘못됐으면 이의를 제기한다
- 요구사항이 모호하면 멈추고 무엇이 모호한지 명확히 말한다 — 추측으로 진행하지 않는다

### 2. 단순성을 우선하라

- 요청된 것만 구현한다 — 요청하지 않은 기능·유연성·확장성을 추가하지 않는다
- 일회성 코드에 추상화·인터페이스·클래스 계층을 만들지 않는다
- 발생할 수 없는 시나리오의 에러 처리를 추가하지 않는다
- 200줄로 작성된 코드가 50줄로 가능하다면 다시 쓴다

### 3. 외과적으로만 변경하라

- 요청된 부분만 수정한다 — 기존 코드·주석·포맷을 "개선"하려 하지 않는다
- 내가 만든 변경으로 생긴 미사용 항목만 정리한다 — 기존 dead code는 삭제하지 않고 언급만 한다
- 기존 코드 스타일·네이밍 컨벤션에 맞춘다
- **코드 작업에서 `docs/` 문서를 함께 고치지 않는다.** 갱신이 필요해 보이면 무엇을 어떻게
  고칠지 말로 제안하고, 승인받은 뒤에 별도로 고친다. 문서 갱신은 항상 별개의 요청이다
  (DESIGN.md 9절 진행 표시, IMPLEMENTATION.md 결정 로그도 예외가 아니다)

### 4. 목표 중심으로 실행하라

- "이렇게 구현하라"가 아닌 **성공 기준**을 먼저 파악하고 그에 맞춰 검증한다
- 다단계 작업은 먼저 간단한 계획을 보여주고 승인 후 단계별로 진행한다
- 각 단계 완료 시 기준 충족 여부를 확인하고 다음 단계로 넘어간다
