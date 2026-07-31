# oss-check

Diagnoses an OpenSearch cluster from its own API responses.

`oss-check` reads what the cluster already knows about itself — health, node stats, settings,
shard placement — and cross-references those answers to work out *why* something is wrong, not
just *that* a number is high. Each finding comes with the evidence it was drawn from and a
concrete action to take.

**Air-gapped by design.** No telemetry, no update checks, no outbound calls of any kind. The only
network traffic is to the cluster you point it at. A dump collected inside a closed network can be
carried out and diagnosed anywhere.

## Requirements

- **Java 25** on the machine running the tool. Nothing else to install.
- **OpenSearch 2.10 – 3.x.** Earlier versions, including 1.x, are not supported: the parser does
  not branch for response shapes that only existed before 2.10.

## Build

```bash
./gradlew bootJar      # -> build/libs/oss-check-<version>.jar
./gradlew test
```

The examples below write `oss-check` for `java -jar oss-check.jar`.

## Two commands

### `collect` — take a dump

```bash
oss-check collect --endpoint https://opensearch.internal:9200 --user admin
```

Calls every endpoint below and writes the responses to a single `tar.gz`. You supply connection
details; which endpoints to call is not your problem.

| Option | |
|---|---|
| `--endpoint <url>` | Cluster base URL. Required. |
| `--user <name>` | Basic-auth user. See [Passwords](#passwords). |
| `--insecure` | Accept a TLS certificate this host does not trust, typically self-signed. |
| `--output <path>` | Defaults to `oss-check-<timestamp>.tar.gz` in the current directory. |

An existing file is **never overwritten** — a dump is evidence, and a second run to the same path
would destroy the state the first one was taken to capture.

A partial collection is a normal outcome, not a failure. If an endpoint is denied, times out, or
does not exist on this cluster version, the reason is recorded in the dump and collection
continues. Only `_cluster/health` and `_nodes/stats` are required; without them there is nothing
to diagnose at all.

```
Wrote oss-check-20260731T041100Z.tar.gz (14 of 15 targets collected)
1 target(s) could not be collected; metadata.json in the dump says why:
  cat_indices.json
```

### `diagnose` — run the rules

```bash
oss-check diagnose --input oss-check-20260731T041100Z.tar.gz      # offline, no network
oss-check diagnose --endpoint https://opensearch.internal:9200    # live, nothing written to disk
```

Exactly one source, never both. Live mode runs the same collector in memory and then the same
rules — an offline dump is a faithful rehearsal of what a live run would have said.

| Option | |
|---|---|
| `--input <dump.tar.gz>` | Diagnose a dump. Touches no network. |
| `--endpoint <url>` | Diagnose a live cluster. Takes the same connection options as `collect`. |
| `--format <text\|json>` | Defaults to `text`. |

```
prod-search (OpenSearch 2.19.1) - collected 2026-07-31T04:11:00Z by oss-check 0.1.0

CRITICAL  OSC-003  Shard allocation is disabled cluster-wide and 12 shard(s) are unassigned;
                   they cannot be assigned while it stays off
  evidence
    cluster.settings.transient.cluster.routing.allocation.enable = none
    cluster.health.status = RED
    cluster.health.unassigned_shards = 12
  recommendation
    If a rolling restart is over, turn allocation back on: PUT _cluster/settings
    {"transient":{"cluster.routing.allocation.enable":null}}. Setting it to null restores
    the default (all) instead of pinning it.

1 finding

SKIPPED (1 rule could not be evaluated)
  OSC-002  requires cluster_settings.json (collection failed: HTTP 403)
```

The **SKIPPED** section is not decoration. A rule that could not run is not the same as a rule
that found nothing, and a report that blurs the two is how a tool quietly misses things.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Ran to completion, nothing to report. |
| `1` | Ran to completion, at least one finding. |
| `2` | Could not run: bad arguments, unreachable cluster, missing credentials, unreadable dump. |

Skipped rules never change the exit code — missing data is not a finding. `2` never means "the
cluster is unhealthy", so a script can tell a failed run from a bad cluster.

## Passwords

**There is no `--password` option.** A password on the command line lands in shell history and is
visible to every other user of the host through `ps`. The password comes from, in order:

1. `OSS_CHECK_PASSWORD` — the non-interactive path, for cron and CI.
2. A prompt, when standard input is a terminal. Input is not echoed.

If `--user` was given and neither is available, the run fails with exit code `2`. It never falls
back to an anonymous request: the 401/403 that came back would be recorded as *this account has no
permission*, hiding a configuration mistake in a dump somebody reads months later. In a pipe or a
cron job it fails immediately rather than waiting at a prompt nobody will answer.

## Rules

| ID | Severity | Fires when |
|---|---|---|
| OSC-001 | CRITICAL | The parent circuit breaker has tripped on a node **and** that node is still above 85% heap. Adds the `top_queries-*` indices to the evidence when Query Insights has left them lying around. |
| OSC-002 | CRITICAL / WARNING | Shard count has reached `cluster.max_shards_per_node × data nodes` (CRITICAL) or is within 10% of it (WARNING). |
| OSC-003 | CRITICAL | `cluster.routing.allocation.enable` is explicitly set to `none` — usually a rolling restart that was never finished. |

Rules are Java code, not a DSL or a config file. Adding one is a code change and a release.

## What a dump contains

15 endpoints, stored under the file name each is listed with. `metadata.json` records when the
dump was taken, by which version, and what happened to every target.

| Endpoint | File | |
|---|---|---|
| `_cluster/health` | `cluster_health.json` | **required** |
| `_nodes/stats` | `nodes_stats.json` | **required** |
| `_cluster/settings?include_defaults=true` | `cluster_settings.json` | |
| `_cluster/allocation/explain` | `allocation_explain.json` | |
| `_cluster/pending_tasks` | `cluster_pending_tasks.json` | |
| `_cluster/stats` | `cluster_stats.json` | |
| `_cat/shards` | `cat_shards.json` | |
| `_cat/indices` | `cat_indices.json` | |
| `_cat/allocation` | `cat_allocation.json` | |
| `_cat/nodes` | `cat_nodes.json` | |
| `_cat/recovery` | `cat_recovery.json` | |
| `_cat/segments` | `cat_segments.json` | |
| `_cat/plugins` | `cat_plugins.json` | |
| `_cat/fielddata` | `cat_fielddata.json` | |
| `_index_template` | `index_templates.json` | |

The archive is a plain `tar.gz`. Open it with `tar -xzf` on any host, with or without this tool.

> ### ⚠️ A dump contains index names
>
> Index names, index template definitions, and the field names inside them are collected as-is.
> On many clusters those describe the business. **There is no automatic masking.** Review a dump
> before it leaves a controlled environment.
>
> Deliberately *not* collected: `_mapping` in full, and anything under `_snapshot/{repo}`.

## Not in this version

By decision, not by omission: no `fix` command, no rule DSL or plugin system, no external
threshold config, no Elasticsearch compatibility, no daemon or continuous monitoring, and no
directory input for `diagnose` (the input format is a single `tar.gz`).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
