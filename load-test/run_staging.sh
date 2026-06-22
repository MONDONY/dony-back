#!/usr/bin/env bash
# run_staging.sh — load test all-endpoints contre STAGING.
#
# Diffère de run_all.sh : PAS de mint /dev/token (absent sur staging), token
# pré-minté requis, garde anti-prod renforcée, pas de cible localhost par défaut.
#
# Usage :
#   cp load-test/staging.env.example load-test/staging.env   # puis remplir
#   bash load-test/run_staging.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# ─── charge staging.env ───────────────────────────────────────────────────────
ENV_FILE="${STAGING_ENV:-$HERE/staging.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERREUR: $ENV_FILE introuvable. Copie staging.env.example → staging.env et remplis-le."
  exit 1
fi
set -a; source "$ENV_FILE"; set +a

BASE_URL="${BASE_URL:-}"
K6_ID_TOKEN="${K6_ID_TOKEN:-}"
INCLUDE_DESTRUCTIVE="${INCLUDE_DESTRUCTIVE:-false}"
I_KNOW_ITS_NOT_PROD="${I_KNOW_ITS_NOT_PROD:-false}"

# ─── gardes ───────────────────────────────────────────────────────────────────
if [[ -z "$BASE_URL" ]]; then echo "ERREUR: BASE_URL vide."; exit 1; fi
case "$BASE_URL" in
  *api.dony.app*|*api.dony.store*)
    echo "REFUS: BASE_URL ressemble à la PRODUCTION ($BASE_URL). Abandon."; exit 1;;
esac
# Refuse toute cible qui ne ressemble pas à staging/local, sauf override explicite.
if [[ "$I_KNOW_ITS_NOT_PROD" != "true" ]]; then
  case "$BASE_URL" in
    *staging*|*localhost*|*127.0.0.1*) : ;;
    *)
      echo "REFUS: BASE_URL ne contient ni 'staging' ni 'localhost' ($BASE_URL)."
      echo "       Si c'est volontairement une cible non-prod, mets I_KNOW_ITS_NOT_PROD=true."
      exit 1;;
  esac
fi
if [[ -z "$K6_ID_TOKEN" ]]; then
  echo "ERREUR: K6_ID_TOKEN vide. Staging n'expose pas /dev/token — fournis un token"
  echo "        Firebase ID pré-minté (voir STAGING.md § « Obtenir un token staging »)."
  exit 1
fi
export K6_ID_TOKEN

# Avertissement rate-limit si on passe par le domaine public nginx.
if [[ "$BASE_URL" == *staging*dony* && "$BASE_URL" == https://* ]]; then
  echo "⚠️  BASE_URL passe par le domaine public (nginx) → rate-limit 30 req/min/IP."
  echo "    Au-delà du burst, tout sera 429. Pour un vrai test de capacité, bypasse"
  echo "    nginx (backend direct :8080) ou lève le rate-limit (cf. STAGING.md)."
fi

# ─── préflight ────────────────────────────────────────────────────────────────
if ! curl -sk -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" | grep -q 200; then
  echo "ERREUR: staging injoignable sur $BASE_URL (health != 200)."; exit 2
fi

# ─── inventaire + k6 + rapport ────────────────────────────────────────────────
echo "[1/3] Inventaire des endpoints…"
python3 "$HERE/gen_inventory.py"

mkdir -p "$HERE/reports"
echo "[2/3] k6 run (BASE_URL=$BASE_URL)…"
cd "$ROOT"
set +e
k6 run --out json="$HERE/reports/staging-raw.json" "$HERE/scenarios/all_endpoints.js"
K6_RC=$?
set -e

echo "[3/3] Rapport par endpoint…"
python3 "$HERE/report_all.py" --raw "$HERE/reports/staging-raw.json" \
  --out "$HERE/reports/staging-report.md"
echo ""
echo "Rapport : $HERE/reports/staging-report.md"
exit $K6_RC
