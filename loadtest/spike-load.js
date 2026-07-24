// k6 spike test: a near-instant burst from baseline to a high VU count, held briefly, then
// dropped back — instead of peak-load.js's gradual staged ramp. Same traffic mix (see
// loadtest/lib/flows.js), completely different shape, because a gradual ramp and a sudden
// burst exercise different failure modes:
//   - peak-load.js asks "where does capacity run out under sustained, increasing load?"
//   - spike-load.js asks "what happens in the first few seconds of an unannounced burst, and
//     does the system recover cleanly once it's over?" — this is what actually happens during
//     a flash sale, a marketing push, or a retry storm from a downstream client, not something
//     a gradual ramp ever exercises.
//
// What to actually look at in the results: some rejected/rate-limited/circuit-broken requests
// *during* the spike window are expected and not a failure by themselves — the platform's own
// resilience config (Resilience4j circuit breakers, the gateway's Redis-backed rate limiters)
// is supposed to shed load under a shock like this rather than fall over completely. The
// signal that actually matters is the RECOVERY stage after the spike: request success rate and
// latency should return to baseline within the recovery window below, not stay degraded.
//
// Usage:
//   k6 run loadtest/spike-load.js
//   k6 run --env BASE_URL=http://localhost:8080 --env PROFILE=smoke loadtest/spike-load.js

import { setup, mixedTraffic } from './lib/flows.js';

export { setup };

const PROFILES = {
  // Fast sanity check that the script and the stack both work before committing to a full run.
  smoke: [
    { duration: '5s', target: 5 },
    { duration: '10s', target: 5 },
    { duration: '3s', target: 60 },
    { duration: '10s', target: 60 },
    { duration: '3s', target: 5 },
    { duration: '10s', target: 5 },
    { duration: '5s', target: 0 },
  ],
  spike: [
    { duration: '20s', target: 15 },   // baseline traffic
    { duration: '40s', target: 15 },   // hold, let it settle
    { duration: '5s', target: 400 },   // the spike — as close to instant as k6's ramp allows
    { duration: '45s', target: 400 },  // hold at the spike — this is where shedding should kick in
    { duration: '10s', target: 15 },   // drop back to baseline just as suddenly
    { duration: '60s', target: 15 },   // recovery window — does it return to normal, or stay degraded?
    { duration: '20s', target: 0 },
  ],
};
const profile = PROFILES[__ENV.PROFILE] || PROFILES.spike;

export const options = {
  scenarios: {
    mixed_traffic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: profile,
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // Deliberately looser than peak-load.js's 2% — a spike is expected to produce some
    // shed/rejected requests by design. This threshold exists to catch the platform falling
    // over outright (a real outage), not to demand zero friction during an intentional shock.
    unexpected_errors: ['rate<0.10'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default mixedTraffic;
