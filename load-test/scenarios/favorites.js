import http from 'k6/http';
import { check, sleep } from 'k6';
import { getToken } from '../lib/auth.js';
import { thresholds } from '../lib/thresholds.js';

const BASE = __ENV.BASE_URL; // staging/dev only — NEVER prod

// Sequential profiles: each starts after the previous finishes.
// To run a single profile: k6 run --env PROFILE=load scenarios/favorites.js
// (filtering is documented in README; startTime offsets ensure sequential execution
//  when all scenarios are active simultaneously)
//
// Timeline:
//   smoke  : 0s     → 30s
//   load   : 31s    → ~9m31s  (ramp 5min + steady 3min + ramp-down 1min = 9min)
//   stress : 10m    → ~15m    (ramp 5min)
//   soak   : 15m30s → 30m30s  (constant 50 VUs for 15min)
export const options = {
  thresholds,
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
      startTime: '0s',
    },
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 50 },
        { duration: '3m', target: 50 },
        { duration: '1m', target: 0 },
      ],
      startTime: '31s',
    },
    stress: {
      // Ramp up to 200 VUs over 5 minutes to find the breaking point
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 200 },
      ],
      startTime: '10m',
    },
    soak: {
      // Constant 50 VUs for 15 minutes — detect memory leaks / degradation over time
      executor: 'constant-vus',
      vus: 50,
      duration: '15m',
      startTime: '15m30s',
    },
  },
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const h = { headers: { Authorization: `Bearer ${data.token}` } };

  // READ: favorites lists
  const tripsRes = http.get(`${BASE}/favorites/trips`, h);
  check(tripsRes, { 'favorites/trips 200': (r) => r.status === 200 });

  const pkgRes = http.get(`${BASE}/favorites/package-requests`, h);
  check(pkgRes, { 'favorites/package-requests 200': (r) => r.status === 200 });

  // WRITE CYCLE (idempotent): only runs when FAV_TRIP_ID is provided.
  // PUT adds the trip to favorites; DELETE removes it — net effect is zero
  // (idempotent, non-destructive, safe to repeat).
  if (__ENV.FAV_TRIP_ID) {
    const putRes = http.put(`${BASE}/favorites/trip/${__ENV.FAV_TRIP_ID}`, null, h);
    check(putRes, { 'PUT favorite 200/201': (r) => r.status === 200 || r.status === 201 });

    const delRes = http.del(`${BASE}/favorites/trip/${__ENV.FAV_TRIP_ID}`, null, h);
    check(delRes, { 'DELETE favorite 204': (r) => r.status === 204 });
  }

  sleep(1);
}
