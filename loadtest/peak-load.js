// k6 peak-load test: a gradual staged ramp to find where the stack degrades under sustained,
// increasing load. Run entirely through the gateway. See loadtest/lib/flows.js for the actual
// traffic mix (browse/order_journey/repeat_login/admin_ops) — every script in this directory
// shares it, so results are comparable across test shapes.
//
// Usage:
//   k6 run loadtest/peak-load.js
//   k6 run --env BASE_URL=http://localhost:8080 --env PROFILE=smoke loadtest/peak-load.js
//
// PROFILE env var selects the `options.scenarios` stage list — see PROFILES below.
// smoke  : quick sanity pass (low VUs, ~1 min) — use this first.
// peak   : the full staged ramp used to find where the stack degrades (default, ~7 min).
//
// Related scripts, same shared flows, different traffic shape:
//   spike-load.js — sudden burst instead of a gradual ramp (circuit breakers/rate limiters)
//   soak-load.js  — sustained moderate load over a long duration (leaks/slow degradation)

import { setup, mixedTraffic } from './lib/flows.js';

export { setup };

const PROFILES = {
  // Fast sanity check that the script and the stack both work before committing to a full run.
  smoke: [
    { duration: '20s', target: 5 },
    { duration: '20s', target: 5 },
    { duration: '10s', target: 0 },
  ],
  // Staged ramp: hold each plateau long enough (45s) for p95/error-rate to stabilize before
  // judging it, then step up. Stop raising the target once http_req_failed/duration thresholds
  // start failing — the last clean plateau is the practical capacity of this stack on this machine.
  peak: [
    { duration: '30s', target: 20 },
    { duration: '45s', target: 20 },
    { duration: '30s', target: 50 },
    { duration: '45s', target: 50 },
    { duration: '30s', target: 100 },
    { duration: '45s', target: 100 },
    { duration: '30s', target: 200 },
    { duration: '45s', target: 200 },
    { duration: '30s', target: 350 },
    { duration: '45s', target: 350 },
    { duration: '30s', target: 500 },
    { duration: '45s', target: 500 },
    { duration: '30s', target: 0 },
  ],
};
const profile = PROFILES[__ENV.PROFILE] || PROFILES.peak;

export const options = {
  scenarios: {
    mixed_traffic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: profile,
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // Unexpected (5xx/network) errors are the real "the service broke" signal.
    unexpected_errors: ['rate<0.02'],
    // Read paths should stay snappy even under load.
    flow_browse_duration: ['p(95)<800'],
    // The full order journey does 4+ inter-service hops (product, inventory x2, payment) —
    // give it more room, but flag it once p95 crosses 3s as a real degradation.
    flow_order_journey_duration: ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default mixedTraffic;
