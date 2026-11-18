#!/usr/bin/env python3
"""Select only a currently listed, zero-cost, tool-capable OpenCode chat model."""
from __future__ import annotations
import json
import sys
from pathlib import Path

# These IDs came from OpenCode's own "Did you mean" response in run #44370849303.
# The live catalogue, not this preference order, is authoritative.
PREFERRED_MODEL_IDS = (
    "deepseek-v4.1-flash-free",
    "hy4-flash-free",
    "mimo-v2.6-flash-free",
)


def _price(model: dict, direction: str):
    return model.get("cost", {}).get("text", {}).get(direction, {}).get("price")


def choose_model(catalog: dict) -> str:
    entries = catalog.get("data")
    if not isinstance(entries, list):
        raise ValueError("OpenCode inference catalogue must contain a data array")
    by_id = {
        entry.get("id"): entry
        for entry in entries
        if isinstance(entry, dict) and isinstance(entry.get("id"), str)
    }
    for model_id in PREFERRED_MODEL_IDS:
        model = by_id.get(model_id)
        if not model or model.get("type") != "chat":
            continue
        tools = model.get("capabilities", {}).get("tools", {})
        if not isinstance(tools, dict) or tools.get("supported") is not True:
            continue
        if _price(model, "input") != 0 or _price(model, "output") != 0:
            continue
        return f"opencode/{model_id}"
    raise ValueError(
        "No preferred zero-cost, tool-capable chat model is present in the live OpenCode catalogue"
    )


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: resolve_opencode_model.py <catalog.json>", file=sys.stderr)
        return 2
    try:
        catalog = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
        print(choose_model(catalog))
        return 0
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"OpenCode model catalogue validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
