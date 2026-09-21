# gptr-java

[中文](README.md) | **English**

A **production-oriented deep research service** in Java 21: a recursive research graph
(LangGraph4j) running on a Postgres task substrate (atomic dequeue + leases + budgets), with
a web observability dashboard and an auditable evaluator.

> **Provenance**: this project is a **Java rewrite** of
> [gpt-researcher](https://github.com/assafelovic/gpt-researcher) (Apache-2.0) — its
> architecture and prompt assets are **derived from** that project, and the `crawler/`
> subdirectory is **extracted** and simplified from it (carrying its own `LICENSE` / `NOTICE`).
> Literature borrowings and **deliberate deviations** are listed item by item in
> [§Sources, Borrowings, and Deviations](#sources-borrowings-and-deviations); full attribution
> is in [NOTICE](NOTICE).

**Stack**: Java 21 · Spring Boot 3.5 · LangGraph4j · Postgres · Flyway · MinIO ·
Resilience4j · vanilla-JS dashboard

## Features

- **Recursive research engine**: clarification prelude → multi-round `breadth × decay`
  recursion (learnings-driven + plan reflection) → per-query evidence extraction →
  outline-first section-wise writing (per-section citation gate + mechanically generated
  References)
- **Structured evidence bank**: every `insight` carries a **verbatim** `quote` and a source
  URL, persisted as JSON ⇒ auditable and replayable
- **Two-stage citation integrity**: the model may only cite **authorized** sources (retrieved
  *and* extracted); cited URLs are canonicalized and matched against that set. **Measured in a
  controlled comparison**: `d1` citation consistency **1.0**, `d2` contradictory claims **0**
- **Task substrate**: Postgres queue (atomic dequeue) + lease with in-stage renewal guard
  (anti-split-brain) + three-part budget (steps / wall-clock / cost) + `ownerToken` fence +
  graceful retry semantics
- **Web dashboard** (`/dashboard/`, zero-dependency single page): live event timeline over
  WebSocket, task list and stats, report reader, evidence-bank browser, fork-as-blueprint, and
  a configuration panel
- **Auditable evaluator** (`gptr-benchmark`): micro-gold **judge self-calibration** (calibrate
  the judge before trusting its scores) + dual-metric accuracy + three-state hallucination
  falsification (sentence ↔ cited-source binding) + citation consistency / coverage + two-run
  blind A/B judging
- **Tests and gate**: engine **225** unit tests · benchmark **37** · integration
  **14 classes / 22 cases**; a 12-criterion readability gate runs automatically with `mvn test`

## Quick Start

Requirements: JDK 21, Maven 3.9+, Docker (Compose).

```bash
# 1) Local infrastructure (Postgres + MinIO + crawler service)
docker compose up -d --build

# 2) Configure a model (pick one)
#    a) Any OpenAI-compatible endpoint (DeepSeek / OpenAI / Qwen / GLM / local Ollama — see BUILD.md).
#       Both startup flags matter: provider=openai (otherwise the output is still mock);
#       search-chain=python (uses the in-repo crawler service; otherwise it falls back to the
#       Java-native retriever)
export DEEPSEEK_API_KEY=sk-...        # Windows: $env:DEEPSEEK_API_KEY="sk-..."
mvn -pl gptr-worker spring-boot:run "-Dspring-boot.run.arguments=--gptr.clients.llm-provider=openai --gptr.clients.search-chain=python"
#    b) Zero-key experience: llm-provider=mock (the default) — the whole pipeline runs with no key at all

# 3) API + dashboard
mvn -pl gptr-api spring-boot:run
#    open http://localhost:8080/dashboard/ to submit a task and watch it run live

# 4) Submit a task (or just use the page)
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"query":"…your research question…","config":"{\"mode\":\"deep_research\",\"breadth\":3,\"depth\":2,\"retriever\":\"bocha\"}"}'
```

Build details, integration tests and troubleshooting: [BUILD.md](BUILD.md).

## Sources, Borrowings, and Deviations

This project read and adjudicated **7 papers** (2025–2026) on deep research. The table below
records the **actual outcome** of each borrowing — marked *implemented*, *deliberately
deviated*, or *rejected*:

| Mechanism | Source | Outcome |
|---|---|---|
| Three-state verdict (Supported / Contradictory / Inconclusive) + counter-evidence criterion | **DeepFact** (ACL-26 Long) | ✅ Implemented: the D2 verdict protocol (per-sentence evidence output + verdict allow-list) |
| **micro-gold judge self-calibration** (inject known-answer sentences to test the judge before trusting it) | **DeepFact** | ✅ Implemented: 30 machine-anchored probes (1:4 adversarial injection) |
| KAE-lite citation coverage (KSR / KCR / KOR + a fourth `abstain` state) | **DeepResearch Arena** (AAAI-26) | ✅ Implemented: starts from the citations and checks whether the report fully states each key fact |
| "Forbidden sources" per item + leakage-rate reporting (URL blocking at the tool layer) | **DeepResearch Bench II** | ✅ Implemented: `blockedUrls` + filtering at the retrieval-result layer (more thorough than the paper, which recommends it but does not ship it) |
| Atomic, content-bearing rubric writing + a self-evaluation gate | DeepResearch Bench II | Consulted (used in the item-bank ingestion flow) |
| Evidence item structure (`insight` separated from `quote`) | **WebWeaver** (arXiv `2509.13312v3`) | ✅ Implemented, plus canonical normalization and authorized-set validation |
| Section-wise writing + per-section citation gate | **WebWeaver** + **FS-Researcher** (ACL-26 Long `2026.acl-long.288`) | ✅ Implemented. ⚠️ The two papers **disagree**: WebWeaver warns that section-wise writing causes *content and style incoherence* and argues for inter-section narrative flow; FS-Researcher's ablation finds section-wise strictly better. This project first adopted only the "context isolation" half, then restored global coherence via **already-written-section injection** (2026-09-19; repetition score 8.0 → 5.75, consistent across two runs) |
| Inter-layer plan reflection / central research state | **Reflect-Evolve** | ✅ Implemented: the `plan_reflect` node (writes "covered vs. gaps" at each layer boundary; the next round's query generation reads it) |
| Explicit plan + revision allowed | **DEEPPLANNER** (ACL-26 Findings) | Philosophy adopted (research plan as managed state; its RL training pipeline is not) |
| Distillation / sentence dehydration | WebWeaver | ⚠️ **Deliberately deviated**: the paper uses an LLM to generate a query-relevant summary (lossy, and with **no fidelity audit**); this project instead does "**delete only, never rewrite** + a **programmatic fidelity pre-check in Java**" — because the value of a `quote` is precisely that it is verbatim |
| Write-then-prune | WebWeaver | ❌ **Rejected**: this project does not accumulate evidence (each section takes what it needs), so there is nothing to prune |
| Source-quality gate (curate) | **gpt-researcher** `SourceCurator` | ⚠️ When porting we **added a prompt input budget the original never had**. Measured 2026-09-20: once candidates grow, that budget silently drops **already-scraped page content** (notes 62 → 34) ⇒ reverted to default-off |

**Explicitly not copied** (with reasons):

- **ACE-style unanchored checklists** (Arena) — the LLM invents criteria and then grades
  against them: a self-verifying loop;
- **WebWeaver's "all evidence extraction by LLM, no audit"** — we keep an original-text review
  channel and a programmatic fidelity pre-check;
- **Reflect-Evolve's Crossover and "LLM self-assessed 90% stop"** — the former merges answers
  with no judge and no revision (a hallucination amplifier), the latter uses an uncalibrated judge;
- **DeepFact's open-domain whole-literature verification as a default** ($1.16/claim) — used only
  as an adversarial upgrade of D2, never per task;
- **DEEPPLANNER's training pipeline** (48 steps × 24h × 8×A100) — a product-oriented inference
  service does not train models;
- **Bench II's single-expert-article gold, wholesale** (locked Analysis perspective, imbalanced
  dimension weights) — only its atomic rubric style and self-evaluation gate are borrowed.

## Relationship and Differences vs. gpt-researcher

Provenance is in the header and [NOTICE](NOTICE): `crawler/` is **extracted** (not rewritten);
the main pipeline is **rewritten**. What follows are verifiable structural differences, not
adjectives:

| Dimension | gpt-researcher | This project |
|---|---|---|
| Runtime | Python + asyncio, single process | Java 21 + Spring Boot; three processes: api / worker / crawler |
| Task substrate | In-process state, no queue | Postgres queue + leases + budgets + retry + `ownerToken` fence |
| Evidence layer | Context is a concatenated string | **Structured evidence bank** (`insight` / `quote` / `sourceUrl` kept separate, stored as JSON) |
| Citations | Soft constraint inside the prompt | **Authorized-source set** (only URLs that were retrieved *and* extracted may be cited) + mechanical References + canonical normalization |
| Quality evaluation | `evals/` (model quality) | **Auditable evaluator**: judge self-calibration + dual-metric accuracy + three-state hallucination falsification + two-run blind A/B judging |
| Source-quality gate | `SourceCurator`, default off | Same mechanism, once flipped to default-on; **flipped back to default-off on 2026-09-20** after measurement showed it discards material (see table above) |

**Same-question comparison** (each implementation run once; six-dimension blind judging with
order-swapped re-runs, only consistent results accepted):

| Round | Items | `overall` | Note |
|---|---|---|---|
| 2026-09-13 | 4 | **2 : 2** | This project led 2:1 on depth / citation / honesty |
| 2026-09-20 | 2 | **2 : 0 (this project)** | One "opinion-type" item flipped from the original's 5-dimension lead |

⚠️ **Boundaries**: only 2–4 items; in the second round the original's reports date from
2026-09-13 (not re-run); judge and subjects share the same base model (DeepSeek) ⇒ treat these
numbers as directional only, not as a general superiority claim.

## Retrieval and Scraping (pluggable)

- Retrieval fallback chain via `gptr.clients.search-chain` (comma-separated, tried in order):
  - `python` — the in-repo `crawler/` service (**20** search-engine adapters; the full-fat option)
  - `duckduckgo` — zero-key direct (Java-native)
  - `name:ok|transient|quota|permanent` — mock sources (tests / demos)
- The per-task retriever is set by the task config's `retriever` field (e.g. `bocha`);
  **specify it explicitly** — the default retriever is unavailable in some network environments,
  and omitting it shows up as "all sub-queries failed"
- Key-type settings (per-retriever API keys, model, search chain) can also be persisted in the
  dashboard's config panel (worker restart required; no hot reload)

## Evaluation

The evaluator ships with micro-gold judge self-calibration. Each run reports dual-metric accuracy
(conditional and strict full-sample), hallucination rate (conservative lower bound / upper bound
including inconclusive), citation consistency and citation coverage, and supports two-run blind
A/B judging.

```bash
mvn -pl gptr-benchmark test       # unit tests for the evaluator itself; the main classes and
                                  # arguments for a real evaluation run are documented in BUILD.md
```

## Repository Layout

```
gptr-java/
├── docker-compose.yml        # Postgres + MinIO + crawler (relative build from ./crawler)
├── crawler/                  # stateless Python crawler service (extracted from gpt-researcher, Apache-2.0)
├── gptr-common/              # domain model / state machine / JPA / Flyway / task service / event log
├── gptr-integration/         # external client interfaces / resilience (retry, breaker, fallback) / report storage / UrlSecurity
├── gptr-engine/              # research engine: LangGraph4j graph / section-wise writing / evidence bank / citation verification
├── gptr-api/                 # REST + WebSocket + single-page dashboard (/dashboard/)
├── gptr-worker/              # queue consumer: dequeue / lease / renewal guard / budget / storage / webhook
├── gptr-benchmark/           # evaluator (accuracy / hallucination / citation / coverage + A/B + micro-gold)
└── BUILD.md / README.md      # build-test guide and usage guide
```

## License and Acknowledgements

Apache-2.0, see [LICENSE](LICENSE). Attribution:

**Projects**

- **[gpt-researcher](https://github.com/assafelovic/gpt-researcher)** (Apache-2.0,
  Copyright (c) Assaf Elovic and contributors) — architecture and prompt assets are derived from
  it; `crawler/` is an extracted, simplified copy carrying its own `LICENSE` / `NOTICE`

**Papers** (per-paper borrowings and deviations: see
[§Sources, Borrowings, and Deviations](#sources-borrowings-and-deviations))

- **DeepFact** (ACL-26 Long) — three-state verdict protocol and micro-gold judge self-calibration
- **DeepResearch Arena** (AAAI-26) — KAE citation-coverage dimension
- **DeepResearch Bench II** (arXiv) — atomic rubrics, self-evaluation gate, tool-layer leakage blocking
- **WebWeaver** (arXiv [`2509.13312v3`](https://arxiv.org/abs/2509.13312)) — evidence memory bank, section-wise writing, citation anchoring
- **Reflect-Evolve** (arXiv) — central research context and inter-layer plan reflection
- **DEEPPLANNER** (ACL-26 Findings) — the "explicit plan + revision allowed" planning philosophy
- **FS-Researcher** (ACL-26 Long, `2026.acl-long.288`) — section-wise ablation methodology and per-section citation gate

See [NOTICE](NOTICE) for details.
