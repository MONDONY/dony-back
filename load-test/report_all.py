#!/usr/bin/env python3
"""
report_all.py — agrège la sortie NDJSON de k6 (--out json) du test all_endpoints
en un rapport markdown par endpoint : latence p50/p95/max + erreurs serveur 5xx
localisées.

Usage:
  python3 report_all.py --raw reports/all-endpoints-raw.json --out reports/all-endpoints-report.md
"""
import argparse
import json
import os
from collections import defaultdict


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    idx = int(round((len(sorted_vals) - 1) * p))
    return sorted_vals[idx]


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--raw", default=os.path.join(here, "reports", "all-endpoints-raw.json"))
    ap.add_argument("--out", default=os.path.join(here, "reports", "all-endpoints-report.md"))
    args = ap.parse_args()

    lat = defaultdict(list)        # endpoint -> [durations]
    errs = defaultdict(int)        # endpoint -> 5xx count
    overall = []                   # all durations
    total_reqs = 0

    with open(args.raw, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("type") != "Point":
                continue
            metric = obj.get("metric")
            data = obj.get("data", {})
            tags = data.get("tags", {})
            ep = tags.get("endpoint")
            val = data.get("value", 0)
            if metric == "endpoint_latency" and ep:
                lat[ep].append(val)
                overall.append(val)
                total_reqs += 1
            elif metric == "server_errors" and ep:
                errs[ep] += int(val)

    rows = []
    for ep, vals in lat.items():
        s = sorted(vals)
        rows.append({
            "endpoint": ep,
            "count": len(s),
            "p50": pct(s, 0.50),
            "p95": pct(s, 0.95),
            "max": s[-1] if s else 0,
            "errs": errs.get(ep, 0),
        })
    rows.sort(key=lambda r: r["p95"], reverse=True)
    total_5xx = sum(errs.values())
    offenders = sorted([r for r in rows if r["errs"] > 0], key=lambda r: r["errs"], reverse=True)

    o = sorted(overall)
    lines = []
    lines.append("# All-Endpoints Load Test — Rapport")
    lines.append("")
    lines.append(f"- Endpoints touchés : **{len(rows)}**  ·  requêtes totales : **{total_reqs}**")
    if o:
        lines.append(f"- Latence globale : p50 {pct(o,0.5):.0f}ms · p95 {pct(o,0.95):.0f}ms · "
                     f"p99 {pct(o,0.99):.0f}ms · max {o[-1]:.0f}ms")
    verdict = "✅ aucune erreur serveur 5xx" if total_5xx == 0 else f"❌ {total_5xx} erreur(s) serveur 5xx"
    lines.append(f"- **Verdict : {verdict}**")
    lines.append("")

    if offenders:
        lines.append("## ❌ Endpoints renvoyant des 5xx (à corriger)")
        lines.append("")
        lines.append("| Endpoint | 5xx | requêtes | p95 (ms) |")
        lines.append("|----------|-----|----------|----------|")
        for r in offenders:
            lines.append(f"| `{r['endpoint']}` | {r['errs']} | {r['count']} | {r['p95']:.0f} |")
        lines.append("")

    lines.append("## Top 25 endpoints les plus lents (p95)")
    lines.append("")
    lines.append("| Endpoint | p50 | p95 | max | n |")
    lines.append("|----------|-----|-----|-----|---|")
    for r in rows[:25]:
        lines.append(f"| `{r['endpoint']}` | {r['p50']:.0f} | {r['p95']:.0f} | {r['max']:.0f} | {r['count']} |")
    lines.append("")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        f.write("\n".join(lines) + "\n")

    print(f"endpoints: {len(rows)} · requêtes: {total_reqs} · 5xx: {total_5xx}")
    print(f"rapport → {args.out}")


if __name__ == "__main__":
    main()
