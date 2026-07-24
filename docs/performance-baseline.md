# Performance Baseline

*Dated Snapshot — Not a Live View*

**Captured 2026-07-23**, one run of all three [load test scripts](../loadtest/) (peak, spike, soak) against both deployment paths on the same laptop, back to back, nothing else competing for resources during either run. This is a point-in-time capture — see [Local Deployment Guide Part 6](local-deployment.md#observability) for the live equivalent (a Grafana dashboard that updates as you run a test yourself). **This date is load-bearing information, not decoration** — the numbers below are only as trustworthy as how recently they were captured relative to the code; treat a stale-looking date with proportionate skepticism, and see [Part 6](#reproduce) to regenerate them yourself.

`k6` `Docker Compose` `Kubernetes (Docker Desktop)` `10 CPU / 24 GB node` `macOS / Apple Silicon`

## Contents

- [01 Methodology](#methodology)
- [02 Results at a Glance](#glance)
- [03 Docker Compose](#compose)
- [04 Kubernetes](#k8s)
- [05 Why They Diverge](#divergence)
- [06 Reproducing This](#reproduce)

<a id="methodology"></a>
## 01 Methodology

- One MacBook (Apple Silicon), Docker Desktop allocated 10 CPUs / ~24 GB — the same machine running the full stack (7 services + 6 Postgres + Kafka + Redis + the full observability stack) *and* the k6 load generator itself. Nothing about these numbers isolates "the platform" from "the laptop it shares resources with" — that's deliberate; see [Part 5](#divergence) for why the shared-resource reality is itself the interesting finding.
- Docker Compose and Kubernetes were never running simultaneously during capture — each got the whole machine to itself, in turn, so one deployment's load test isn't skewed by the other's containers idling in the background. Every run used the scripts' default `BASE_URL` (`http://localhost:8080`) unchanged — Compose was torn down (`docker compose down`) before Kubernetes was port-forwarded onto that same native `:8080` (`./scripts/port-forward-persistent.sh`, no offset). This deliberately is *not* the `+10000`-offset dual-stack setup documented in [Local Deployment Guide Part 5](local-deployment.md#urls) (that scheme exists so both stacks can be reached *at once*, which is exactly the confound this capture avoids by running them one at a time on the identical port instead) — worth stating plainly since the two schemes could otherwise be confused for each other.
- Each script ran once, real profile (not `smoke`) — `peak-load.js` and `spike-load.js` at their full designed duration; `soak-load.js` shortened to a 4-minute hold (`SOAK_MINUTES=4`) instead of the 30-minute default, for practicality capturing this snapshot. A genuine soak run needs the full 30+ minutes to say anything about slow drift; treat the soak numbers here as "does it stay healthy for 4 minutes," not a real endurance result.
- **Not a production capacity claim.** Production runs on dedicated infrastructure with no laptop-sharing, real Postgres instances instead of single-node containers, and headroom this dev setup doesn't have. Read every number below as "what this exact stack does on this exact dev machine," full stop.

<a id="glance"></a>
## 02 Results at a Glance

- **Compose peak — unexpected errors:** 17.08% — crossed the 2% threshold above ~350 VUs
- **K8s peak — unexpected errors:** 22.13% — gateway-service OOMKilled once mid-run
- **Compose spike (400 VU burst):** 0.00% — clean — rate limiter shed load as designed
- **K8s spike (400 VU burst):** 51.66% — same burst, same code — very different outcome

The full number set, all six runs:

| Run | Peak VUs | Req/s | Unexpected errors | order_journey p95 | browse p95 |
|---|---:|---:|---:|---:|---:|
| Compose · peak | 500 | 636 | 17.08% | 1.65s | 26ms |
| Compose · spike | 400 | 384 | 0.00% | 870ms | 25ms |
| Compose · soak (4m) | 30 | 85 | 0.00% | 987ms | 20ms |
| K8s · peak | 500 | 433 | 22.13% | 8.00s | 493ms |
| K8s · spike | 400 | 319 | 51.66% | 6.01s | 218ms |
| K8s · soak (4m) | 30 | 58 | 0.21% | 7.93s | 9ms |

Read `order_journey p95` next to `browse p95` in each row, not just down the column — that gap (26ms vs 1.65s on Compose peak; 493ms vs 8s on K8s peak) is the platform's own design showing through: `browse` is one read, no auth; `order_journey` is signup + login (both BCrypt) + order + get + list + cancel, several inter-service hops deep. It degrades first and worst under load by design, not by accident — see [architecture.html Part 6](architecture.md#part6).

<a id="compose"></a>
## 03 Docker Compose

Handles all three shapes reasonably well. No container resource limits exist in `docker-compose.yaml` — every service can burst to use as much of the host's CPU/memory as it needs, which is exactly why these numbers look better than the Kubernetes ones below on the same hardware: there's no artificial ceiling to hit before the host itself runs out.

- **Peak**: clean through the 200-VU plateau (0% unexpected errors), real degradation starting during the ramp to 350 VUs (~4-6% errors at that plateau), climbing to 17.08% by the 500-VU plateau. This is the platform's actual breaking point on this machine — not a cliff, a gradual slope, which is what you want a peak test to show.
- **Spike**: 0.00% unexpected errors through a 15→400 VU instant burst. 30% of requests got an expected 4xx (mostly the auth rate limiter shedding load, exactly as designed) — the backend itself never actually broke.
- **Soak (4m)**: 0.00% unexpected errors, latency flat throughout the window. No signal of drift in this short a capture — see the methodology caveat above.

<a id="k8s"></a>
## 04 Kubernetes

The same code, same k6 script, meaningfully worse outcomes across all three shapes — and two real pod restarts during capture, not just slow responses:

> **Critical:** **`gateway-service` was OOMKilled once during the peak run** (`kubectl describe pod`: `Reason: OOMKilled, Exit Code: 137`), restarted, recovered, and finished the run. Its Helm chart limit is 768Mi (`helm/ecommerce-platform/values-local.yaml`) — genuinely exceeded under the 500-VU plateau specifically, not a fluke.

> **Warning:** **`user-service` restarted once during the soak run** (`Exit Code: 143` — SIGTERM, most likely a liveness-probe timeout under BCrypt-driven CPU contention, not an OOM) — at only 30 VUs, the same load Compose handled at 0.00% unexpected errors and flat latency throughout.

- **Peak**: 22.13% unexpected errors (worse than Compose's 17.08% on the identical ramp), `order_journey` p95 hit **8.00s** versus Compose's 1.65s.
- **Spike**: 51.66% unexpected errors on the exact same 400-VU burst Compose absorbed at 0.00%. This is the single largest divergence in the whole data set.
- **Soak (4m)**: unexpected errors stayed low (0.21%) but `order_journey` p95 was still 7.93s — at just 30 VUs. Low error rate with high latency is the signature of requests queuing behind CPU-throttled pods rather than being rejected outright.

<a id="divergence"></a>
## 05 Why They Diverge

Every number above traces back to one structural difference, not six separate bugs: **Kubernetes enforces the Helm chart's per-pod CPU/memory limits (`values-local.yaml`); Docker Compose enforces nothing.** Under light load that difference is invisible — it's exactly what showed up in `docs/local-deployment.html`'s account of raising those limits after an earlier, smaller load test found request failures at just 5 concurrent VUs. That fix (roughly doubling every service's CPU/memory ceiling) was correctly sized for a *smoke*-scale run. It was not sized for real concurrent auth traffic at 30+ VUs — the soak result above is the proof: identical traffic shape, Compose flat and clean, Kubernetes queuing behind a 768Mi ceiling badly enough to restart a pod.

This is the platform correctly reflecting a genuine operational tradeoff, not a bug to silently patch: unbounded resources (Compose) make problems disappear locally that bounded resources (Kubernetes, and any real production cluster) will always surface. That's arguably the more valuable of the two deployment paths to load-test against, precisely because it doesn't hide this.

**Known follow-up, not fixed here** — this snapshot is a report of what's true today, not a change: `helm/ecommerce-platform/values-local.yaml`'s resource limits (last tuned against a 5-VU smoke test) likely need another, larger pass specifically informed by these peak/spike numbers before Kubernetes should be trusted at real concurrency on this hardware.

<a id="reproduce"></a>
## 06 Reproducing This

Full instructions, including how to watch a run live in Grafana instead of waiting for a snapshot like this one: [loadtest/README.md](../loadtest/README.md). In short:

```bash
# Docker Compose
docker compose up -d
k6 run --env PROFILE=peak --summary-export=loadtest/results/compose-peak.json loadtest/peak-load.js
k6 run --env PROFILE=spike --summary-export=loadtest/results/compose-spike.json loadtest/spike-load.js
k6 run --env PROFILE=soak --env SOAK_MINUTES=4 --summary-export=loadtest/results/compose-soak.json loadtest/soak-load.js

# docker compose down, THEN bring up Kubernetes (tilt ci) and
# ./scripts/port-forward-persistent.sh with no offset — same native :8080 the
# Compose runs above used, just at a different time. Don't run both stacks at once
# on the +10000 offset ports for this: that's for reachability, not for keeping
# a fair, uncontended comparison. Same three commands again, --tag testid=... changed.
```

Raw structured output for every run behind this page's numbers: `loadtest/results/*.json` and matching `*.log` files, checked in alongside this doc.

---

Point-in-time performance snapshot for the six-service e-commerce platform. Pairs with [docs/architecture.md](architecture.md) (why it's built this way) and [docs/local-deployment.md](local-deployment.md) (how to run it) — regenerate this page whenever either the code or the conclusions here go stale.
