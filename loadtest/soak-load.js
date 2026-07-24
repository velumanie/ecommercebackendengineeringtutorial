// k6 soak test: a moderate, sustainable load held for a long duration, instead of
// peak-load.js's short high-intensity ramp. Same traffic mix (see loadtest/lib/flows.js),
// completely different question being asked:
//   - peak-load.js asks "how much load can this take, right now, at its peak?"
//   - soak-load.js asks "does it stay healthy after an hour of ordinary traffic?" — connection
//     pool leaks, slow memory growth, log/disk fill-up, a scheduled job (the outbox poller,
//     Kafka consumer rebalances) that degrades under accumulated state — none of these show up
//     in a 7-minute ramp. They only show up if you leave it running.
//
// What to actually look at in the results: p95 latency and the unexpected-error rate in the
// LAST 10% of the run compared to the first 10%. Both flat across the whole run is a pass.
// Either one drifting upward over time — even if every individual request still "succeeds" —
// is the actual finding a soak test exists to catch, and won't trip a simple aggregate
// threshold the way a hard failure would.
//
// Usage:
//   k6 run loadtest/soak-load.js                                    # full 30-minute soak
//   k6 run --env BASE_URL=http://localhost:8080 --env PROFILE=smoke loadtest/soak-load.js

import { setup, mixedTraffic } from './lib/flows.js';

export { setup };

const PROFILES = {
  // Fast sanity check that the script and the stack both work before committing to a full run
  // — obviously too short to catch anything a soak test is actually looking for, it just
  // proves the scenario/flows are wired correctly.
  smoke: [
    { duration: '10s', target: 10 },
    { duration: '30s', target: 10 },
    { duration: '10s', target: 0 },
  ],
  // A moderate, sustainable load (well below peak-load.js's breaking point) held for 30
  // minutes. Override the hold duration for a shorter/longer soak without editing the file:
  //   k6 run --env SOAK_MINUTES=90 loadtest/soak-load.js
  soak: [
    { duration: '1m', target: 30 },
    { duration: `${__ENV.SOAK_MINUTES || 30}m`, target: 30 },
    { duration: '1m', target: 0 },
  ],
};
const profile = PROFILES[__ENV.PROFILE] || PROFILES.soak;

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
    // Same bar as peak-load.js's sustained plateaus — at this moderate a load, degrading at
    // all over a long run is itself the finding, not something to tolerate.
    unexpected_errors: ['rate<0.02'],
    flow_browse_duration: ['p(95)<800'],
    flow_order_journey_duration: ['p(95)<3000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default mixedTraffic;
