// all_endpoints.js — test de charge couvrant TOUS les endpoints du backend dony.
//
// Source : load-test/endpoints.json (généré par gen_inventory.py).
//
// Deux scénarios :
//   reads_load    — ramping-VUs qui martèlent les endpoints GET (idempotents, hot
//                   paths). C'est le vrai test de charge.
//   writes_smoke  — 1 VU qui passe une fois sur chaque endpoint d'écriture
//                   (safe_write + mutating) avec un body minimal, pour valider
//                   qu'ils répondent sans 5xx sous charge concurrente. Les
//                   endpoints DESTRUCTIFS (delete/cancel/payment/capture/webhook…)
//                   sont EXCLUS par défaut (INCLUDE_DESTRUCTIVE=true pour les inclure).
//
// Échec réel = 5xx uniquement. Les 4xx (IDs random → 404, body garbage → 400/422,
// rôle manquant → 403) sont ATTENDUS sur un balayage exhaustif et ne comptent pas.
//
// SÉCURITÉ : cible localhost/staging seulement. JAMAIS api.dony.app.
//
// Lancement (via run_all.sh, ou directement) :
//   K6_ID_TOKEN="<admin-jwt>" BASE_URL="http://localhost:8080/api/v1" \
//     k6 run load-test/scenarios/all_endpoints.js
//
// Env :
//   BASE_URL              défaut http://localhost:8080/api/v1
//   K6_ID_TOKEN           JWT Firebase (admin) — requis pour les endpoints protégés
//   READ_VUS              VUs du scénario reads_load (défaut 20)
//   READ_DURATION         durée du plateau de charge (défaut 1m)
//   TEST_ID               UUID injecté dans les {pathVars} (défaut UUID nul)
//   INCLUDE_DESTRUCTIVE   "true" pour aussi smoke-tester les endpoints destructifs

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const TOKEN = __ENV.K6_ID_TOKEN || '';
const TEST_ID = __ENV.TEST_ID || '00000000-0000-0000-0000-000000000000';
const INCLUDE_DESTRUCTIVE = (__ENV.INCLUDE_DESTRUCTIVE || 'false') === 'true';
const READ_VUS = parseInt(__ENV.READ_VUS || '20', 10);
const READ_DURATION = __ENV.READ_DURATION || '1m';

// Garde anti-prod (défense en profondeur, run_all.sh garde aussi).
if (BASE_URL.includes('api.dony.app')) {
  throw new Error('REFUS: ne jamais charger la prod (api.dony.app).');
}

// Inventaire généré. Chemins déjà préfixés /api/v1 → on retire le contexte car
// BASE_URL le contient déjà.
const RAW = JSON.parse(open('../endpoints.json'));
function stripCtx(p) {
  return p.replace(/^\/api\/v1/, '');
}
const ALL = RAW.map((e) => ({ ...e, sub: stripCtx(e.path) }));

const READS = ALL.filter((e) => e.klass === 'read');
const WRITES = ALL.filter((e) =>
  e.klass === 'safe_write' || e.klass === 'mutating' ||
  (INCLUDE_DESTRUCTIVE && e.klass === 'destructive')
);

// Querystrings connus pour obtenir des 2xx sur les reads clés (sinon 400/404,
// toujours mesurés mais moins représentatifs).
const QUERY = {
  '/announcements': 'departureCity=Paris&arrivalCity=Dakar',
  '/package-requests': 'departureCity=Paris&arrivalCity=Dakar',
  '/cities/autocomplete': 'query=Par',
  '/cities': 'query=Paris',
  '/cities/search': 'q=Paris&limit=10',
  '/addresses/autocomplete': 'query=Paris',
  '/tracking/search': 'number=DONY-TEST-0001',
  '/package-requests/estimate': 'from=Paris&to=Dakar&weight=5',
  '/announcements/market-price': 'corridor=Paris-Dakar',
  '/travelers/me/fiscal-export': 'year=2024&format=csv&type=transactions',
};

const serverErrors = new Counter('server_errors');
const latency = new Trend('endpoint_latency', true);

export const options = {
  scenarios: {
    reads_load: {
      executor: 'ramping-vus',
      exec: 'readLoad',
      startVUs: 0,
      stages: [
        { duration: '15s', target: READ_VUS },
        { duration: READ_DURATION, target: READ_VUS },
        { duration: '10s', target: 0 },
      ],
      tags: { scenario: 'reads_load' },
    },
    writes_smoke: {
      executor: 'per-vu-iterations',
      exec: 'writeSmoke',
      vus: 1,
      iterations: Math.max(WRITES.length, 1),
      maxDuration: '5m',
      startTime: '5s',
      tags: { scenario: 'writes_smoke' },
    },
  },
  thresholds: {
    // Seul vrai défaut : une erreur serveur 5xx.
    server_errors: ['count<1'],
    // Latence des reads sous charge.
    'endpoint_latency{scenario:reads_load}': ['p(95)<800', 'p(99)<1500'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (TOKEN) h.Authorization = `Bearer ${TOKEN}`;
  return h;
}

function buildUrl(e) {
  let sub = e.sub;
  for (const v of e.pathVars) {
    sub = sub.replace(`{${v}}`, TEST_ID);
  }
  const qs = QUERY[sub];
  return BASE_URL + sub + (qs ? `?${qs}` : '');
}

function fire(e, body) {
  const url = buildUrl(e);
  const tag = `${e.method} ${e.sub}`;
  const params = { headers: headers(), tags: { endpoint: tag, klass: e.klass } };
  let res;
  switch (e.method) {
    case 'GET':
      res = http.get(url, params);
      break;
    case 'DELETE':
      res = http.del(url, null, params);
      break;
    case 'POST':
      res = http.post(url, body, params);
      break;
    case 'PUT':
      res = http.put(url, body, params);
      break;
    case 'PATCH':
      res = http.patch(url, body, params);
      break;
    default:
      res = http.get(url, params);
  }
  latency.add(res.timings.duration, { endpoint: tag });
  if (res.status >= 500) {
    serverErrors.add(1, { endpoint: tag });
  }
  check(res, { 'pas de 5xx': (r) => r.status < 500 });
  return res;
}

// Scénario charge : chaque itération frappe un GET au hasard.
export function readLoad() {
  if (READS.length === 0) return;
  const e = READS[Math.floor(Math.random() * READS.length)];
  fire(e, null);
}

// Scénario smoke écritures : itère une fois sur chaque endpoint d'écriture.
export function writeSmoke() {
  if (WRITES.length === 0) return;
  const idx = (typeof __ITER === 'number' ? __ITER : 0) % WRITES.length;
  const e = WRITES[idx];
  fire(e, JSON.stringify({}));
}

// Résumé console + fichier JSON brut.
export function handleSummary(data) {
  const se = (data.metrics.server_errors && data.metrics.server_errors.values.count) || 0;
  const lat = data.metrics.endpoint_latency ? data.metrics.endpoint_latency.values : {};
  const lines = [];
  lines.push('=== all_endpoints load test ===');
  lines.push(`reads: ${READS.length} GET · writes(smoke): ${WRITES.length}` +
             (INCLUDE_DESTRUCTIVE ? ' (destructifs INCLUS)' : ' (destructifs exclus)'));
  lines.push(`erreurs serveur 5xx : ${se}`);
  if (lat['p(95)'] !== undefined) {
    lines.push(`latence p95 : ${Math.round(lat['p(95)'])}ms · p99 : ${Math.round(lat['p(99)'])}ms · max : ${Math.round(lat.max)}ms`);
  }
  lines.push(se === 0 ? 'VERDICT: ✅ aucun 5xx' : `VERDICT: ❌ ${se} erreur(s) serveur`);
  return {
    stdout: '\n' + lines.join('\n') + '\n',
    'load-test/reports/all-endpoints-summary.json': JSON.stringify(data, null, 2),
  };
}
