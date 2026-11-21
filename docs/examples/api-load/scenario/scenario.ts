import { APILoadDemoClient } from './client.ts';
import { check } from 'k6';

// Project-owned business flow. Edit this native script to choose mix, data, auth and SLA.
const iterations = Number(__ENV.LOAD_ITERATIONS || 0);
export const options = {
  ...(iterations > 0 ? { vus: 1, iterations } : {
    scenarios: { reads: { executor: 'constant-arrival-rate', rate: Number(__ENV.LOAD_RATE || 2),
      timeUnit: '1s', duration: __ENV.LOAD_DURATION || '10s', preAllocatedVUs: 2, maxVUs: 5 } },
  }),
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'], dropped_iterations: ['count==0'] },
};
const client = new APILoadDemoClient({
  baseUrl: __ENV.TESTFORGE_LOAD_BASE_URL,
  commonRequestParameters: { headers: __ENV.API_TOKEN ? { Authorization: `Bearer ${__ENV.API_TOKEN}` } : {} },
});

export default function () {
  const health = client.health({ tags: { name: 'GET /health' } });
  check(health.response, { 'health answers 200': r => r.status === 200 });
  const tasks = client.listTasks({ tags: { name: 'GET /api/v1/tasks' } });
  check(tasks.response, { 'tasks answer 200': r => r.status === 200,
    'tasks contain an items array': () => Array.isArray(tasks.data?.items) });
}

export function handleSummary(data) {
  return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
}
