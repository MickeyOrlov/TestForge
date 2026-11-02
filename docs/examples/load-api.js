import http from 'k6/http';
import { check } from 'k6';

// Run only against an explicitly selected, owned environment. These thresholds are examples.
export const options = {
  scenarios: {
    api: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 2,
      maxVUs: 5,
      stages: [
        { duration: '5s', target: Number(__ENV.LOAD_RATE || 2) },
        { duration: '15s', target: Number(__ENV.LOAD_RATE || 2) },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const response = http.get(__ENV.TESTFORGE_LOAD_BASE_URL + '/health', {
    tags: { name: 'GET /health' },
    headers: { 'X-Test-Run': __ENV.TESTFORGE_LOAD_RUN_ID },
  });
  check(response, { healthy: r => r.status === 200 });
}

export function handleSummary(data) {
  return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
}
