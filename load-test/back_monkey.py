#!/usr/bin/env python3
"""
back_monkey.py — robustness fuzzer ("monkey") for the dony backend.

Fires randomized HTTP requests at the API and asserts the server NEVER
returns 5xx. Proper handling = 4xx (401/403 bad auth, 400/422 bad input,
404 not found, 405 wrong method). Any 5xx, connection drop, or timeout is a
FINDING (unhandled path / robustness bug).

SAFETY: targets localhost ONLY. Never point this at production.

Usage:
  python3 back_monkey.py [--n 3000] [--seed 1337]
Writes a markdown report to load-test/reports/back-monkey-report.md
"""
import argparse
import json
import os
import random
import string
import time
import urllib.error
import urllib.request
from collections import Counter

BASE = "http://localhost:8080/api/v1"  # localhost ONLY — never prod

# Endpoint seeds: (method, path_template). {id} → fuzzed path param.
ENDPOINTS = [
    # public-ish
    ("GET", "/actuator/health"),
    ("GET", "/actuator/info"),
    ("GET", "/cities/autocomplete"),
    ("GET", "/cities"),
    ("GET", "/config"),
    ("GET", "/corridors/popular"),
    ("GET", "/content-categories"),
    ("GET", "/commission-rate"),
    ("GET", "/tracking/search"),
    ("GET", "/tracking/public/{id}"),
    ("GET", "/travelers/{id}/announcements"),
    ("GET", "/ratings/user/{id}"),
    ("POST", "/ratings/recipient"),
    ("POST", "/auth/email-otp"),
    ("POST", "/kyc/webhook"),
    ("POST", "/payments/webhook"),
    ("POST", "/payments/stripe/webhook"),
    ("POST", "/webhooks/mobile-money/notify"),
    # authed (expect 401/403 without a valid Firebase token)
    ("GET", "/users/me"),
    ("GET", "/users/me/roles"),
    ("GET", "/users/me/devices"),
    ("PUT", "/users/me/fcm-token"),
    ("GET", "/announcements"),
    ("GET", "/announcements?departureCity=Paris&arrivalCity=Dakar"),
    ("POST", "/announcements"),
    ("GET", "/announcements/{id}/bids"),
    ("GET", "/package-requests"),
    ("POST", "/package-requests"),
    ("GET", "/package-requests/{id}"),
    ("GET", "/bids/me"),
    ("POST", "/bids/quote"),
    ("POST", "/bids/checkout"),
    ("GET", "/bids/{id}"),
    ("POST", "/bids/{id}/accept"),
    ("POST", "/bids/{id}/reject"),
    ("POST", "/bids/{id}/cancel"),
    ("POST", "/bids/{id}/confirm-presence"),
    ("POST", "/bids/{id}/report-noshow"),
    ("GET", "/bids/{id}/return-code"),
    ("GET", "/favorites/ids"),
    ("GET", "/favorites/trips"),
    ("PUT", "/favorites/trip/{id}"),
    ("DELETE", "/favorites/trip/{id}"),
    ("GET", "/conversations"),
    ("GET", "/notifications"),
    ("GET", "/disputes"),
    ("GET", "/cancellations"),
    ("GET", "/negotiations"),
    ("GET", "/wallet/balance"),
    ("GET", "/payments/connect/account"),
    ("GET", "/kyc/status"),
    ("GET", "/addressbook/recipients"),
    ("GET", "/addresses"),
    ("GET", "/users/me/corridor-alerts"),
    ("GET", "/travelers/me/price-grid"),
    ("GET", "/trip-templates"),
    ("GET", "/trip-recurrences"),
    # admin (expect 401/403)
    ("GET", "/admin/users"),
    ("GET", "/admin/metrics"),
    ("GET", "/admin/payments"),
    ("POST", "/admin/bootstrap"),
]

METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH"]


def rand_str(n):
    return "".join(random.choice(string.ascii_letters + string.digits) for _ in range(n))


def rand_uuid_like():
    # sometimes a valid-looking uuid, sometimes garbage
    r = random.random()
    if r < 0.4:
        return "%s-%s-%s-%s-%s" % (rand_str(8), rand_str(4), rand_str(4), rand_str(4), rand_str(12))
    if r < 0.7:
        return rand_str(random.randint(1, 40))
    return random.choice(["", "null", "0", "../../etc/passwd", "%00", "' OR 1=1--", "🐒", "-1"])


def fuzz_path(tmpl):
    return tmpl.replace("{id}", urllib.request.quote(rand_uuid_like(), safe=""))


def rand_body():
    choice = random.random()
    if choice < 0.25:
        return b"{not valid json"
    if choice < 0.45:
        return json.dumps({rand_str(5): rand_str(random.randint(1, 50))}).encode()
    if choice < 0.6:
        # wrong types where the API likely expects structured data
        return json.dumps({"amount": rand_str(8), "weightKg": [1, 2, 3], "declaredValue": None,
                           "x": random.choice([True, 999999999999, -1, {}, []])}).encode()
    if choice < 0.72:
        return b""  # empty body
    if choice < 0.85:
        return ("\"" + rand_str(random.randint(500, 5000)) + "\"").encode()  # huge string
    return json.dumps([rand_str(3) for _ in range(random.randint(0, 20))]).encode()


def auth_header():
    r = random.random()
    if r < 0.45:
        return None  # no auth
    if r < 0.8:
        return "Bearer " + rand_str(random.randint(10, 400))  # garbage token
    return random.choice(["Bearer", "Bearer ", "Basic " + rand_str(20), rand_str(30)])  # malformed scheme


def do_request(method, path, body, auth, timeout=10):
    url = BASE + path
    headers = {}
    if auth is not None:
        headers["Authorization"] = auth
    data = None
    if method in ("POST", "PUT", "PATCH"):
        data = body
        if random.random() < 0.8:
            headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", "") if e.headers else ""
    except urllib.error.URLError as e:
        return ("ERR", str(e.reason))
    except Exception as e:  # noqa
        return ("ERR", repr(e))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=1337)
    args = ap.parse_args()
    random.seed(args.seed)

    # preflight: backend reachable?
    st, _ = do_request("GET", "/actuator/health", b"", None)
    if st != 200:
        print(f"ABORT: backend health = {st} (expected 200). Is it running on {BASE}?")
        raise SystemExit(2)

    print(f"back_monkey: {args.n} requests, seed={args.seed}, target={BASE}")
    status_counter = Counter()
    findings = []  # 5xx / ERR
    ctype_problem_ok = 0
    ctype_problem_missing = 0
    t0 = time.time()

    for i in range(args.n):
        method, tmpl = random.choice(ENDPOINTS)
        # 20% of the time, override the method with a random one (wrong-method fuzz)
        if random.random() < 0.20:
            method = random.choice(METHODS)
        # 8% of the time, fuzz an entirely random garbage path
        if random.random() < 0.08:
            tmpl = "/" + "/".join(rand_str(random.randint(1, 8)) for _ in range(random.randint(1, 4)))
        path = fuzz_path(tmpl)
        body = rand_body()
        auth = auth_header()
        status, ctype = do_request(method, path, body, auth)
        status_counter[status] += 1

        is_finding = (isinstance(status, int) and status >= 500) or status == "ERR"
        if is_finding:
            findings.append((method, path, status, str(ctype)[:120],
                             body[:160].decode("utf-8", "replace"), auth))
        # for error responses, check RFC 7807 content type (CLAUDE.md requirement)
        if isinstance(status, int) and 400 <= status < 500:
            if "application/problem+json" in str(ctype):
                ctype_problem_ok += 1
            else:
                ctype_problem_missing += 1

        if (i + 1) % 500 == 0:
            print(f"  {i+1}/{args.n} … 5xx/err so far: {len(findings)}")

    elapsed = time.time() - t0

    # report
    reports_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reports")
    os.makedirs(reports_dir, exist_ok=True)
    out = os.path.join(reports_dir, "back-monkey-report.md")
    lines = []
    lines.append("# Back Monkey — Backend Robustness Fuzz Report")
    lines.append("")
    lines.append(f"- Target: `{BASE}` (localhost — never prod)")
    lines.append(f"- Requests: **{args.n}**  ·  seed: `{args.seed}`  ·  duration: {elapsed:.1f}s  "
                 f"·  ~{args.n/elapsed:.0f} req/s")
    lines.append("")
    verdict = "✅ ROBUST — zero 5xx / errors" if not findings else f"❌ {len(findings)} FINDING(S) (5xx / connection errors)"
    lines.append(f"## Verdict: {verdict}")
    lines.append("")
    lines.append("## Status code distribution")
    lines.append("")
    lines.append("| Status | Count |")
    lines.append("|--------|-------|")
    for code, cnt in sorted(status_counter.items(), key=lambda kv: str(kv[0])):
        lines.append(f"| {code} | {cnt} |")
    lines.append("")
    total_4xx = ctype_problem_ok + ctype_problem_missing
    if total_4xx:
        pct = 100.0 * ctype_problem_ok / total_4xx
        lines.append(f"## RFC 7807 (`application/problem+json`) on 4xx errors")
        lines.append("")
        lines.append(f"- {ctype_problem_ok}/{total_4xx} ({pct:.0f}%) of 4xx responses used `application/problem+json`")
        if ctype_problem_missing:
            lines.append(f"- ⚠️ {ctype_problem_missing} 4xx responses did NOT use problem+json "
                         f"(CLAUDE.md requires RFC 7807 for controller errors)")
        lines.append("")
    if findings:
        lines.append("## Findings (5xx / errors)")
        lines.append("")
        lines.append("| Method | Path | Status | Content-Type | Body sent (truncated) |")
        lines.append("|--------|------|--------|--------------|------------------------|")
        for m, p, s, ct, b, a in findings[:200]:
            b = b.replace("|", "\\|").replace("\n", " ")
            p = p.replace("|", "\\|")
            lines.append(f"| {m} | `{p}` | **{s}** | {ct} | `{b}` |")
        lines.append("")
        if len(findings) > 200:
            lines.append(f"_(+{len(findings)-200} more findings omitted)_")
    else:
        lines.append("No 5xx, connection drops, or timeouts across the run. "
                     "Every fuzzed request was rejected with a proper 4xx (auth/validation/not-found/method) "
                     "or served a 2xx for valid public reads.")
    report = "\n".join(lines) + "\n"
    with open(out, "w") as f:
        f.write(report)
    print(f"\n{'='*50}")
    print(verdict)
    print(f"status dist: {dict(status_counter)}")
    print(f"report → {out}")
    if findings:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
