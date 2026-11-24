#!/usr/bin/env python3
"""Fail-closed tests for the Infrastructure-owned extracted-host patch runner."""
from __future__ import annotations
import ast
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPAIR = ROOT / "scripts" / "repair_sources.py"
APPROVED = "app/src/main/java/com/openjarvis/ui/MainActivity.kt"
DASHBOARD = "app/src/main/java/com/openjarvis/ui/dashboard/DashboardScreen.kt"

def load_patch_helpers():
    source = REPAIR.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(REPAIR))
    wanted = {
        "_feature_host_patch_target",
        "_feature_host_patch_hunk_lines",
        "_count_exact_sequence",
        "_path_without_symlink_components",
        "_apply_feature_host_patch",
    }
    nodes = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in wanted
    ]
    found = {node.name for node in nodes}
    if found != wanted:
        raise AssertionError("repair helper definitions missing: " + ", ".join(sorted(wanted - found)))
    namespace = {"Path": Path, "re": re, "SystemExit": SystemExit}
    module = ast.Module(body=nodes, type_ignores=[])
    exec(compile(module, str(REPAIR), "exec"), namespace)
    return namespace

def patch_text(path: str, old: str = "before", new: str = "after") -> bytes:
    return (
        f"diff --git a/{path} b/{path}\n"
        f"--- a/{path}\n"
        f"+++ b/{path}\n"
        "@@ -1 +1 @@\n"
        f"-{old}\n"
        f"+{new}\n"
    ).encode("utf-8")

def expect_failure(label, action):
    try:
        action()
    except (SystemExit, OSError):
        print("PASS:", label)
        return
    raise AssertionError(label + ": unsafe or invalid patch was accepted")

def make_project(root: Path, contents: str = "before\n") -> Path:
    target = root / APPROVED
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(contents, encoding="utf-8")
    return target

def main():
    helpers = load_patch_helpers()
    target_parser = helpers["_feature_host_patch_target"]
    apply_patch = helpers["_apply_feature_host_patch"]

    assert target_parser(patch_text(APPROVED)) == APPROVED
    assert target_parser(patch_text(DASHBOARD)) == DASHBOARD
    print("PASS: both exact reviewed host targets are allowed")

    for unsafe in (
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/values/strings.xml",
        "app/src/main/java/com/openjarvis/ui/OtherActivity.kt",
        "../app/src/main/java/com/openjarvis/ui/MainActivity.kt",
    ):
        expect_failure(
            "unapproved/traversal target rejected: " + unsafe,
            lambda unsafe=unsafe: target_parser(patch_text(unsafe)),
        )

    with tempfile.TemporaryDirectory(prefix="jarvis-host-patch-") as temporary:
        temp = Path(temporary)
        project = temp / "project"
        target = make_project(project)
        patch = temp / "patches" / "MainActivity.kt.patch"
        patch.parent.mkdir(parents=True)
        patch.write_bytes(patch_text(APPROVED))
        apply_patch(project, patch)
        assert target.read_text(encoding="utf-8") == "after\n"
        apply_patch(project, patch)
        print("PASS: unique preimage applies and unique postimage is idempotent")

        duplicate_project = temp / "duplicate-project"
        make_project(duplicate_project, "before\nbefore\n")
        expect_failure("duplicate preimage rejected", lambda: apply_patch(duplicate_project, patch))

        missing_project = temp / "missing-project"
        make_project(missing_project, "unrelated\n")
        expect_failure("missing preimage and postimage rejected", lambda: apply_patch(missing_project, patch))

        duplicate_post_project = temp / "duplicate-post-project"
        make_project(duplicate_post_project, "after\nafter\n")
        expect_failure("duplicate postimage rejected", lambda: apply_patch(duplicate_post_project, patch))

        inside_project = temp / "inside-symlink-project"
        actual = inside_project / "app/src/main/java/com/openjarvis/real"
        actual.mkdir(parents=True)
        (actual / "MainActivity.kt").write_text("before\n", encoding="utf-8")
        ui_link = inside_project / "app/src/main/java/com/openjarvis/ui"
        ui_link.symlink_to(actual, target_is_directory=True)
        expect_failure("in-root parent-directory symlink redirect rejected", lambda: apply_patch(inside_project, patch))

        outside_project = temp / "outside-symlink-project"
        outside = temp / "outside-host"
        outside.mkdir()
        (outside / "MainActivity.kt").write_text("before\n", encoding="utf-8")
        escaped_ui = outside_project / "app/src/main/java/com/openjarvis/ui"
        escaped_ui.parent.mkdir(parents=True)
        escaped_ui.symlink_to(outside, target_is_directory=True)
        expect_failure("escaping parent-directory symlink rejected", lambda: apply_patch(outside_project, patch))

        real_patch_root = temp / "real-patches"
        real_patch_root.mkdir()
        real_template = real_patch_root / "MainActivity.kt.patch"
        real_template.write_bytes(patch_text(APPROVED))
        linked_patch_root = temp / "linked-patches"
        linked_patch_root.symlink_to(real_patch_root, target_is_directory=True)
        expect_failure("symlinked patch root rejected", lambda: apply_patch(project, linked_patch_root / real_template.name))

        linked_template = temp / "linked-template.patch"
        linked_template.symlink_to(real_template)
        expect_failure("symlinked patch template rejected", lambda: apply_patch(project, linked_template))

        leaf_project = temp / "leaf-symlink-project"
        real_dir = leaf_project / "app/src/main/java/com/openjarvis/real"
        real_dir.mkdir(parents=True)
        (real_dir / "MainActivity.kt").write_text("before\n", encoding="utf-8")
        leaf_target = leaf_project / APPROVED
        leaf_target.parent.mkdir(parents=True, exist_ok=True)
        leaf_target.symlink_to(real_dir / "MainActivity.kt")
        expect_failure("symlinked target file rejected", lambda: apply_patch(leaf_project, patch))

    print("HOST-PATCH-SECURITY-OK: exact allowlist, symlink components, traversal, preimage and postimage gates")

if __name__ == "__main__":
    main()
