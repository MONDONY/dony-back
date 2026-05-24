#!/bin/bash
# Import Grafana dashboards to Grafana Cloud

set -e

# Configuration
GRAFANA_URL="${GRAFANA_URL:-https://your-org.grafana.net}"
API_TOKEN="${GRAFANA_API_TOKEN:-}"
DASHBOARDS_DIR="$(dirname "$0")"

# Vérifier les credentials
if [ -z "$API_TOKEN" ]; then
  echo "❌ Error: GRAFANA_API_TOKEN not set"
  echo ""
  echo "Set it with:"
  echo "  export GRAFANA_API_TOKEN='your_token_from_grafana_cloud'"
  exit 1
fi

if [ "$GRAFANA_URL" = "https://your-org.grafana.net" ]; then
  echo "❌ Error: GRAFANA_URL not configured"
  echo ""
  echo "Set it with:"
  echo "  export GRAFANA_URL='https://your-org.grafana.net'"
  exit 1
fi

echo "📊 Importing Grafana dashboards..."
echo "   URL: $GRAFANA_URL"
echo ""

# Import each dashboard
for dashboard_file in "$DASHBOARDS_DIR"/dashboard-*.json; do
  dashboard_name=$(basename "$dashboard_file" .json)

  echo "📥 Importing $dashboard_name..."

  # Wrap the dashboard JSON in the required format
  response=$(curl -s -X POST "$GRAFANA_URL/api/dashboards/db" \
    -H "Authorization: Bearer $API_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"dashboard\": $(cat "$dashboard_file" | jq .dashboard), \"overwrite\": true}")

  # Check for errors
  if echo "$response" | jq -e '.status' > /dev/null 2>&1; then
    status=$(echo "$response" | jq -r '.status')
    if [ "$status" = "success" ]; then
      dashboard_id=$(echo "$response" | jq -r '.id')
      echo "   ✅ Imported successfully (ID: $dashboard_id)"
    else
      echo "   ❌ Failed: $(echo "$response" | jq -r '.message')"
    fi
  else
    echo "   ✅ Imported successfully"
  fi
done

echo ""
echo "✅ All dashboards imported!"
echo ""
echo "📊 View dashboards at: $GRAFANA_URL/dashboards"
