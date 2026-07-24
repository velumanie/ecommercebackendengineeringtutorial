# Load testing

Three [k6](https://k6.io) scripts, one shared traffic mix (`lib/flows.js`), three different
questions asked of the same running stack — Docker Compose or Kubernetes, either works, since
both are reached the same way: through the gateway on `:8080`.

| Script | Question it answers | Real-run duration |
|---|---|---|
| `peak-load.js` | Where does the system degrade under sustained, increasing load? | ~8 min |
| `spike-load.js` | Does it shed load and recover cleanly from a sudden, unannounced burst? | ~2.5 min |
| `soak-load.js` | Does it stay healthy after a long time at ordinary load — leaks, slow drift? | 30 min default, override via `SOAK_MINUTES` |

Every script also has a `smoke` profile (~1 min, low VUs) — run that first, always, to confirm
the script and the stack both actually work before committing to the real profile:

```bash
k6 run --env PROFILE=smoke loadtest/peak-load.js
k6 run --env PROFILE=smoke loadtest/spike-load.js
k6 run --env PROFILE=smoke loadtest/soak-load.js
```

Then the real thing (drop `PROFILE`, or pass the script's named profile explicitly —
`peak`/`spike`/`soak`):

```bash
k6 run loadtest/peak-load.js
k6 run loadtest/spike-load.js
k6 run --env SOAK_MINUTES=10 loadtest/soak-load.js   # shorter than the 30-minute default
```

`BASE_URL` defaults to `http://localhost:8080`; override it if the gateway is reachable
somewhere else (e.g. `http://localhost:18080` for the offset dual-stack setup in
`docs/local-deployment.html`).

## Watching a run live in Grafana

Prometheus (both `docker-compose.yaml` and `kubernetes/observability/prometheus.yaml`) has its
remote-write receiver enabled specifically so k6 can push metrics straight into the same
Prometheus the app's own dashboards read from — a run shows up next to golden-signal panels for
the services it's hitting, instead of only existing as terminal output that's gone once the run
ends.

```bash
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg" k6 run \
  --tag testid=my-run \
  --out experimental-prometheus-rw=http://localhost:9090/api/v1/write \
  loadtest/peak-load.js
```

- `K6_PROMETHEUS_RW_TREND_STATS` is what makes p95 (not just k6's default p99) show up as its
  own queryable metric — matches the p95 thresholds the scripts already assert on.
- `--tag testid=my-run` attaches a label so Grafana can tell one run's data apart from another's
  in the same time range; without it every run's series are indistinguishable except by
  timestamp.

Open **Grafana → E-Commerce Platform → Load Testing (k6)** (`http://localhost:3000`) to watch
virtual users, request rate, unexpected-error rate, p95 latency per flow, and orders/sec update
live as the run progresses. The dashboard JSON lives at
`monitoring/grafana/loadtest-dashboard.json` for Compose and is mirrored into
`kubernetes/observability/grafana-dashboards-configmap.yaml` for Kubernetes (that file embeds
every dashboard's JSON inline — there's no generator, so a change to one needs copying into the
other by hand).

## A captured baseline

`docs/performance-baseline.html` is a dated snapshot of real numbers from an actual run of all
three scripts against both Docker Compose and local Kubernetes — useful for seeing what this
platform actually does without running anything yourself. It's a point-in-time capture, not a
live view (that's what the Grafana dashboard above is for), and it goes stale the moment the
code or the machine it ran on changes meaningfully — the date at the top of that page is load-
bearing information, not decoration; treat a snapshot without one, or an old one, with
proportionate skepticism.

## Interpreting results

`unexpected_errors` (5xx / connection failures) is the metric that actually means "the system
broke." `http_req_failed` also counts expected 4xx noise under load (rate limiting, a
soon-to-be-cancelled order hitting a state it's not in) — useful context, not a failure signal
by itself. Each script's own header comment explains what's specifically worth watching for
*that* test shape (peak: the last clean plateau before thresholds start failing; spike: the
recovery window after the burst, not the burst itself; soak: whether p95/error-rate drift
upward over the run's last 10% versus its first 10%).
