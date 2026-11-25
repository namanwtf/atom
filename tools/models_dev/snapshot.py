#!/usr/bin/env python3
"""Builds Atom's bundled models.dev catalog snapshot.

The app ships a small copy of the models.dev community catalog so the "Add from models.dev" page works offline
before the first online refresh. The snapshot keeps only the fields `atom.llm.cloud.ModelsDev` parses, in the
same shape as https://models.dev/api.json, so the app reads it with the same parser it uses online.

Two sources are accepted:

    python3 -I tools/models_dev/snapshot.py --api api.json OUT_DIR        # a downloaded api.json
    python3 -I tools/models_dev/snapshot.py --repo models.dev-checkout OUT_DIR   # the catalog's TOML sources

The repository form follows the catalog's own v2-to-v1 rules for the fields kept here (base models, v2-only
models, provider aliases, modality names). It writes `models_dev_snapshot.json` and `models_dev_snapshot.meta`
(the fetch time and where the data came from) into OUT_DIR. Run it with `-I` so nothing next to the input loads.
"""

import argparse
import copy
import datetime
import json
import os
import sys
import tomllib


def v1_modality(m):
    if m == "application/pdf":
        return "pdf"
    if m in ("decision", "embedding", "reranking"):
        return "text"
    base = m.split("/")[0]
    if base in ("text", "image", "audio", "video"):
        return base
    return None


def v1_modalities(items):
    out = []
    for m in items or []:
        v = v1_modality(m)
        if v is not None and v not in out:
            out.append(v)
    return out


def drop_none(d):
    return {k: v for k, v in d.items() if v is not None}


def reasoning_options(reasoning):
    if not reasoning or not reasoning.get("supported"):
        return None
    opts = []
    if reasoning.get("toggle"):
        opts.append({"type": "toggle"})
    if reasoning.get("effort") is not None:
        opts.append({"type": "effort", "values": reasoning["effort"]})
    return opts


def model_fields(toml, with_options):
    caps = toml.get("capabilities") or {}
    modalities = toml.get("modalities")
    inputs = v1_modalities(modalities.get("input")) if modalities else None
    limit = toml.get("limit")
    reasoning = caps.get("reasoning")
    tools = caps.get("tools")
    return drop_none({
        "name": toml.get("name"),
        "attachment": None if inputs is None else any(m != "text" for m in inputs),
        "reasoning": None if reasoning is None else bool(reasoning.get("supported")),
        "reasoning_options": reasoning_options(reasoning) if with_options else None,
        "tool_call": None if tools is None else bool(tools.get("supported")),
        "modalities": None if inputs is None else {"input": inputs},
        "limit": None if not limit or limit.get("context") is None else {"context": limit["context"]},
    })


def merge_deep(base, over):
    out = copy.deepcopy(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge_deep(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def apply_omit(target, paths):
    for path in paths:
        parts = path.split(".")
        node = target
        ok = True
        for p in parts[:-1]:
            if not isinstance(node.get(p), dict):
                ok = False
                break
            node = node[p]
        if ok:
            node.pop(parts[-1], None)


def read_toml(path):
    with open(path, "rb") as f:
        return tomllib.load(f)


def toml_files(root):
    for dirpath, _dirs, files in os.walk(root, followlinks=False):
        for name in sorted(files):
            if name.endswith(".toml"):
                full = os.path.join(dirpath, name)
                yield os.path.relpath(full, root).replace(os.sep, "/")[:-5], full


def from_repo(repo):
    labs = {}
    for mid, path in toml_files(os.path.join(repo, "models")):
        labs[mid] = model_fields(read_toml(path), with_options=False)

    providers, aliases = {}, []
    pdir = os.path.join(repo, "providers")
    for pid in sorted(os.listdir(pdir)):
        ptoml = os.path.join(pdir, pid, "provider.toml")
        if not os.path.isfile(ptoml):
            continue
        raw = read_toml(ptoml)
        provider = drop_none({"id": pid, "name": raw.get("name"), "api": raw.get("api"), "npm": raw.get("npm"),
                              "doc": raw.get("doc")})
        if "v1_alias" in raw:
            aliases.append((provider, raw["v1_alias"]))
            providers[pid] = provider
            continue
        models = {}
        for mid, path in toml_files(os.path.join(pdir, pid, "models")):
            toml = read_toml(path)
            if toml.get("v2_only") is True:
                continue
            fields = model_fields(toml, with_options=True)
            base_id = toml.get("base_model")
            if base_id:
                base = labs.get(base_id)
                if base is None:
                    raise SystemExit(f"{pid}/{mid}: unknown base_model {base_id}")
                fields = merge_deep(base, fields)
                apply_omit(fields, toml.get("base_model_omit") or [])
            models[mid] = {"id": mid, **fields}
        if models:
            provider["models"] = models
            providers[pid] = provider

    for provider, alias in aliases:
        target = providers.get(alias["provider"])
        if target is None or "models" not in target:
            raise SystemExit(f"{provider['id']}: alias of unknown provider {alias['provider']}")
        renames = alias.get("env") or {}

        def rename(text):
            for a, b in renames.items():
                text = text.replace("${" + a + "}", "${" + b + "}")
            return text

        if "api" not in provider and "api" in target:
            provider["api"] = rename(target["api"])
        provider["models"] = json.loads(rename(json.dumps(target["models"])))
    return {k: v for k, v in providers.items() if v.get("models")}


KEEP_PROVIDER = ("id", "name", "api", "npm", "doc")
KEEP_MODEL = ("id", "name", "reasoning", "reasoning_options", "attachment", "tool_call", "limit", "modalities")


def from_api(path):
    with open(path, "rb") as f:
        return json.load(f)


def trim(catalog):
    out = {}
    for pid, p in sorted(catalog.items()):
        models = {}
        for mid, m in sorted((p.get("models") or {}).items()):
            kept = {k: m[k] for k in KEEP_MODEL if k in m and m[k] is not None}
            if "limit" in kept:
                kept["limit"] = {"context": kept["limit"].get("context")} if kept["limit"].get("context") else None
            if "modalities" in kept:
                kept["modalities"] = {"input": kept["modalities"].get("input") or []}
                kept.pop("attachment", None)  # the parser prefers modalities when present
            if kept.get("tool_call") is True:
                kept.pop("tool_call")  # the parser defaults to true
            if kept.get("name") == kept.get("id", mid):
                kept.pop("name")
            if kept.get("id") == mid:
                kept.pop("id")
            models[mid] = {k: v for k, v in kept.items() if v is not None}
        if not models:
            continue
        entry = {k: p[k] for k in KEEP_PROVIDER if p.get(k) is not None}
        if entry.get("id") == pid:
            entry.pop("id")
        entry["models"] = models
        out[pid] = entry
    return out


def main():
    ap = argparse.ArgumentParser()
    group = ap.add_mutually_exclusive_group(required=True)
    group.add_argument("--api")
    group.add_argument("--repo")
    ap.add_argument("--source", default="", help="text recorded as the snapshot's source")
    ap.add_argument("--fetched", default="", help="ISO-8601 time the data was fetched (default: now)")
    ap.add_argument("out")
    args = ap.parse_args()

    catalog = trim(from_api(args.api) if args.api else from_repo(args.repo))
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "models_dev_snapshot.json"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    fetched = args.fetched or datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat()
    source = args.source or ("https://models.dev/api.json" if args.api else "models.dev catalog sources")
    with open(os.path.join(args.out, "models_dev_snapshot.meta"), "w", encoding="utf-8") as f:
        f.write(f"fetched={fetched}\nsource={source}\n")
    models = sum(len(p["models"]) for p in catalog.values())
    print(f"{len(catalog)} providers, {models} models", file=sys.stderr)


if __name__ == "__main__":
    main()
