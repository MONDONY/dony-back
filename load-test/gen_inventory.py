#!/usr/bin/env python3
"""
gen_inventory.py — extrait l'inventaire complet des endpoints REST du backend
dony en parsant les contrôleurs Spring (@RestController / @RequestMapping /
@GetMapping / @PostMapping / ...).

Produit load-test/endpoints.json :
  [ { "method": "GET", "path": "/api/v1/announcements/{id}/bids",
      "pathVars": ["id"], "klass": "read", "controller": "BidController" }, ... ]

Classification ("klass") — pilote ce que le test de charge a le droit de marteler :
  read        : GET → load réel (idempotent, sûr)
  safe_write  : POST/PUT idempotents/sans effet de bord (search, quote, autocomplete,
                estimate, market-price, validate, preview) → smoke léger autorisé
  mutating    : écritures qui modifient l'état (create/update génériques) → smoke 1x
  destructive : delete/cancel/accept/reject/capture/release/refund/payment/checkout/
                webhook/bootstrap/reset/deactivate/no-show/return/dispute/force/confirm
                → JAMAIS martelé, exclu par défaut

Usage:
  python3 gen_inventory.py [--src ../src/main/java] [--context /api/v1] [--out endpoints.json]
"""
import argparse
import json
import os
import re

CTX_DEFAULT = "/api/v1"

# annotation HTTP-method → verbe
MAPPING_VERB = {
    "GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
    "DeleteMapping": "DELETE", "PatchMapping": "PATCH",
}

DESTRUCTIVE_KW = re.compile(
    r"delete|cancel|accept|reject|capture|release|refund|payment|checkout|"
    r"webhook|bootstrap|reset|deactivate|reactivate|no-?show|noshow|return|"
    r"dispute|force|confirm|onboarding|connect|charge|payout|withdraw|topup|"
    r"finalize|contest|report|block|unblock|ban|suspend|immediately|invite",
    re.IGNORECASE,
)
SAFE_WRITE_KW = re.compile(
    r"search|autocomplete|quote|estimate|market-price|validate|preview|calculate|"
    r"check|details|match", re.IGNORECASE,
)

PATHVAR_RE = re.compile(r"\{([a-zA-Z0-9_]+)\}")


def extract_mapping_path(annotation_args: str):
    """Récupère le chemin d'une annotation @XxxMapping(...). Gère value=, path=, et
    le premier littéral nu. Retourne '' si l'annotation n'a pas de chemin."""
    if annotation_args is None:
        return ""
    # value = "..." / path = "..."
    m = re.search(r'(?:value|path)\s*=\s*"([^"]*)"', annotation_args)
    if m:
        return m.group(1)
    # premier littéral nu : @GetMapping("/x")
    m = re.search(r'"([^"]*)"', annotation_args)
    if m:
        return m.group(1)
    return ""


def classify(method: str, path: str) -> str:
    if method == "GET":
        return "read"
    if DESTRUCTIVE_KW.search(path):
        return "destructive"
    if SAFE_WRITE_KW.search(path):
        return "safe_write"
    return "mutating"


def join(ctx: str, prefix: str, sub: str) -> str:
    parts = [ctx.rstrip("/"), prefix.strip("/"), sub.strip("/")]
    full = "/".join(p for p in parts if p)
    if not full.startswith("/"):
        full = "/" + full
    return full.replace("//", "/")


def parse_controller(text: str):
    """Retourne (class_prefix, [(verb, subpath), ...]) pour un fichier contrôleur."""
    if "@RestController" not in text and "@Controller" not in text:
        return None
    # préfixe class-level : premier @RequestMapping("...") au niveau classe
    cls_prefix = ""
    m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', text)
    if m:
        cls_prefix = m.group(1)

    endpoints = []
    # @XxxMapping(...) possiblement multi-ligne → on capture jusqu'à la parenthèse fermante
    for am in re.finditer(r"@(" + "|".join(MAPPING_VERB.keys()) + r")\b(\s*\(([^)]*)\))?",
                          text, re.DOTALL):
        ann = am.group(1)
        args = am.group(3)  # contenu des parenthèses (peut être None)
        verb = MAPPING_VERB[ann]
        sub = extract_mapping_path(args)
        endpoints.append((verb, sub))

    # @RequestMapping(method = RequestMethod.X, ...) au niveau méthode (rare)
    for rm in re.finditer(
        r'@RequestMapping\(([^)]*method\s*=\s*RequestMethod\.([A-Z]+)[^)]*)\)', text, re.DOTALL
    ):
        args = rm.group(1)
        verb = rm.group(2)
        sub = extract_mapping_path(args)
        endpoints.append((verb, sub))
    return cls_prefix, endpoints


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--src", default=os.path.join(here, "..", "src", "main", "java"))
    ap.add_argument("--context", default=CTX_DEFAULT)
    ap.add_argument("--out", default=os.path.join(here, "endpoints.json"))
    args = ap.parse_args()

    inventory = []
    seen = set()
    for root, _dirs, files in os.walk(args.src):
        for fn in files:
            if not fn.endswith("Controller.java"):
                continue
            fp = os.path.join(root, fn)
            with open(fp, encoding="utf-8", errors="replace") as f:
                text = f.read()
            parsed = parse_controller(text)
            if not parsed:
                continue
            cls_prefix, endpoints = parsed
            controller = fn.replace(".java", "")
            for verb, sub in endpoints:
                path = join(args.context, cls_prefix, sub)
                key = (verb, path)
                if key in seen:
                    continue
                seen.add(key)
                inventory.append({
                    "method": verb,
                    "path": path,
                    "pathVars": PATHVAR_RE.findall(path),
                    "klass": classify(verb, path),
                    "controller": controller,
                })

    inventory.sort(key=lambda e: (e["path"], e["method"]))
    with open(args.out, "w") as f:
        json.dump(inventory, f, indent=2)

    # résumé
    from collections import Counter
    by_klass = Counter(e["klass"] for e in inventory)
    by_method = Counter(e["method"] for e in inventory)
    print(f"endpoints extraits : {len(inventory)} → {args.out}")
    print(f"  par méthode : {dict(by_method)}")
    print(f"  par classe  : {dict(by_klass)}")
    print(f"  controllers : {len({e['controller'] for e in inventory})}")


if __name__ == "__main__":
    main()
