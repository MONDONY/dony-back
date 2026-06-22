#!/usr/bin/env bash
# run_all.sh — test de charge de TOUS les endpoints du backend dony.
#
# Pipeline : mint token admin (via /dev/token) → génère l'inventaire → k6 → rapport.
#
# SÉCURITÉ : cible localhost/staging seulement. Refuse api.dony.app et BASE_URL vide.
#
# Usage :
#   bash load-test/run_all.sh
#   BASE_URL="http://staging…/api/v1" READ_VUS=50 READ_DURATION=3m bash load-test/run_all.sh
#   INCLUDE_DESTRUCTIVE=true bash load-test/run_all.sh   # (DANGER : smoke les delete/cancel/payment)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
TOKEN_ROLE="${TOKEN_ROLE:-ADMIN}"

# --- garde anti-prod ---
case "$BASE_URL" in
  ""|*api.dony.app*)
    echo "REFUS: BASE_URL vide ou pointe la production ($BASE_URL). Abandon."; exit 1;;
esac

# --- préflight : backend joignable ---
if ! curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" | grep -q 200; then
  echo "ERREUR: backend injoignable sur $BASE_URL (health != 200). Démarre-le d'abord."; exit 2
fi

# --- 0. mint token admin (frais, ~1h) via le dev endpoint ---
if [[ -z "${K6_ID_TOKEN:-}" ]]; then
  echo "[0/3] Mint token $TOKEN_ROLE via /dev/token…"
  K6_ID_TOKEN="$(curl -s "$BASE_URL/dev/token?role=$TOKEN_ROLE" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['idToken'])")"
  export K6_ID_TOKEN
fi
if [[ -z "${K6_ID_TOKEN:-}" ]]; then
  echo "ERREUR: token vide (le dev endpoint a-t-il répondu ?)."; exit 3
fi
echo "  token: ${K6_ID_TOKEN:0:24}… (${#K6_ID_TOKEN} caractères)"

# --- 1. inventaire ---
echo "[1/3] Génération de l'inventaire des endpoints…"
python3 "$HERE/gen_inventory.py"

# --- 2. k6 (CWD = repo root pour que handleSummary écrive load-test/reports/…) ---
mkdir -p "$HERE/reports"
echo "[2/3] k6 run (BASE_URL=$BASE_URL)…"
cd "$ROOT"
set +e
k6 run --out json="$HERE/reports/all-endpoints-raw.json" "$HERE/scenarios/all_endpoints.js"
K6_RC=$?
set -e

# --- 3. rapport par endpoint ---
echo "[3/3] Agrégation du rapport par endpoint…"
python3 "$HERE/report_all.py"
echo ""
echo "Rapports :"
echo "  - $HERE/reports/all-endpoints-report.md   (par endpoint + 5xx localisés)"
echo "  - $HERE/reports/all-endpoints-summary.json (résumé k6)"
exit $K6_RC
