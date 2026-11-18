#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from resolve_opencode_model import choose_model


def entry(model_id, *, input_price=0, output_price=0, tools=True, model_type="chat"):
    return {
        "id": model_id,
        "type": model_type,
        "capabilities": {"tools": {"supported": tools}},
        "cost": {"text": {"input": {"price": input_price}, "output": {"price": output_price}}},
    }


class ResolveOpenCodeModelTests(unittest.TestCase):
    def test_selects_first_catalogued_free_tool_model(self):
        catalog = {"data": [
            entry("deepseek-v4.1-flash-free", tools=False),
            entry("hy4-flash-free"),
            entry("mimo-v2.6-flash-free"),
        ]}
        self.assertEqual(choose_model(catalog), "opencode/hy4-flash-free")

    def test_rejects_paid_models(self):
        catalog = {"data": [
            entry("deepseek-v4.1-flash-free", input_price=0.1),
            entry("hy4-flash-free", output_price=0.1),
            entry("mimo-v2.6-flash-free", input_price=0.1),
        ]}
        with self.assertRaises(ValueError):
            choose_model(catalog)

    def test_rejects_non_chat_and_models_without_tools(self):
        catalog = {"data": [
            entry("deepseek-v4.1-flash-free", model_type="image"),
            entry("hy4-flash-free", tools=False),
            entry("mimo-v2.6-flash-free", tools=False),
        ]}
        with self.assertRaises(ValueError):
            choose_model(catalog)

    def test_fails_closed_when_provider_catalog_has_no_supported_candidate(self):
        with self.assertRaises(ValueError):
            choose_model({"data": [entry("some-other-model")]})

    def test_rejects_invalid_catalog_schema(self):
        with self.assertRaises(ValueError):
            choose_model({"models": []})


if __name__ == "__main__":
    unittest.main(verbosity=2)
