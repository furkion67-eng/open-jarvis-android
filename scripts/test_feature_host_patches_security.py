#!/usr/bin/env python3
"""Security regression tests for the generic Feature host patch applicator."""
from __future__ import annotations

import tempfile
from pathlib import Path

from apply_feature_host_patches import apply_feature_host_patches


TARGET = "app/src/main/java/com/openjarvis/ui/MainActivity.kt"


def patch_text(target: str = TARGET, old: str = "before", new: str = "after") -> str:
    return (
        f"diff --git a/{target} b/{target}\n"
        f"--- a/{target}\n"
        f"+++ b/{target}\n"
        "@@ -1,1 +1,1 @@\n"
        f"-{old}\n"
        f"+{new}\n"
    )


def make_layout(root: Path, contents: str = "before\n") -> tuple[Path, Path, Path]:
    project = root / "project"
    features = root / "features"
    target = project / TARGET
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(contents, encoding="utf-8")
    patch_root = features / "host-integration" / "patches" / "app" / "src" / "main" / "java" / "com" / "openjarvis" / "ui"
    patch_root.mkdir(parents=True, exist_ok=True)
    return project, features, patch_root


def expect_failure(label: str, action) -> None:
    try:
        action()
    except (SystemExit, OSError):
        print("PASS:", label)
        return
    raise AssertionError(label + ": unsafe patch was accepted")


def test_applies_exact_patch_and_is_idempotent() -> None:
    with tempfile.TemporaryDirectory(prefix="jarvis-host-patch-") as temp:
        root = Path(temp)
        project, features, patch_root = make_layout(root)
        (patch_root / "MainActivity.kt.patch").write_text(patch_text(), encoding="utf-8")
        first = apply_feature_host_patches(project, features)
        assert first == [f"applied: {TARGET}"]
        assert (project / TARGET).read_text(encoding="utf-8") == "after\n"
        second = apply_feature_host_patches(project, features)
        assert second == [f"already-applied: {TARGET}"]


def test_rejects_traversal_and_unapproved_targets() -> None:
    for unsafe in (
        "../outside.kt",
        "app/src/main/../../outside.kt",
        "app/src/test/java/Example.kt",
        "/tmp/Outside.kt",
        r"app\\src\\main\\Example.kt",
    ):
        with tempfile.TemporaryDirectory(prefix="jarvis-host-unsafe-") as temp:
            root = Path(temp)
            project, features, patch_root = make_layout(root)
            (patch_root / "unsafe.patch").write_text(patch_text(unsafe), encoding="utf-8")
            expect_failure("reject target " + unsafe, lambda: apply_feature_host_patches(project, features))


def test_rejects_ambiguous_or_stale_preimages() -> None:
    for label, contents in (
        ("duplicate preimage", "before\nbefore\n"),
        ("missing preimage", "unrelated\n"),
        ("duplicate postimage", "after\nafter\n"),
    ):
        with tempfile.TemporaryDirectory(prefix="jarvis-host-preimage-") as temp:
            root = Path(temp)
            project, features, patch_root = make_layout(root, contents)
            (patch_root / "MainActivity.kt.patch").write_text(patch_text(), encoding="utf-8")
            expect_failure(label, lambda: apply_feature_host_patches(project, features))


def test_rejects_symlinked_patch_root_and_target_components() -> None:
    with tempfile.TemporaryDirectory(prefix="jarvis-host-symlink-") as temp:
        root = Path(temp)
        project, features, patch_root = make_layout(root)
        patch_file = patch_root / "MainActivity.kt.patch"
        patch_file.write_text(patch_text(), encoding="utf-8")
        real_patches = features / "real-patches"
        real_patches.mkdir()
        linked_patches = features / "host-integration" / "patches"
        linked_patches.unlink(missing_ok=True)
        linked_patches.symlink_to(real_patches, target_is_directory=True)
        expect_failure("symlinked patch root", lambda: apply_feature_host_patches(project, features))

    with tempfile.TemporaryDirectory(prefix="jarvis-host-target-link-") as temp:
        root = Path(temp)
        project, features, patch_root = make_layout(root)
        (patch_root / "MainActivity.kt.patch").write_text(patch_text(), encoding="utf-8")
        ui = project / "app/src/main/java/com/openjarvis/ui"
        outside = root / "outside"
        outside.mkdir()
        (outside / "MainActivity.kt").write_text("before\n", encoding="utf-8")
        ui.rmdir()
        ui.symlink_to(outside, target_is_directory=True)
        expect_failure("symlinked target parent", lambda: apply_feature_host_patches(project, features))


def test_rejects_symlinked_template_and_unexpected_files() -> None:
    with tempfile.TemporaryDirectory(prefix="jarvis-host-template-link-") as temp:
        root = Path(temp)
        project, features, patch_root = make_layout(root)
        real = root / "real.patch"
        real.write_text(patch_text(), encoding="utf-8")
        (patch_root / "MainActivity.kt.patch").symlink_to(real)
        expect_failure("symlinked patch template", lambda: apply_feature_host_patches(project, features))

    with tempfile.TemporaryDirectory(prefix="jarvis-host-extra-file-") as temp:
        root = Path(temp)
        project, features, patch_root = make_layout(root)
        (patch_root / "notes.txt").write_text("not a patch", encoding="utf-8")
        expect_failure("unexpected file in patch tree", lambda: apply_feature_host_patches(project, features))


def main() -> None:
    test_applies_exact_patch_and_is_idempotent()
    test_rejects_traversal_and_unapproved_targets()
    test_rejects_ambiguous_or_stale_preimages()
    test_rejects_symlinked_patch_root_and_target_components()
    test_rejects_symlinked_template_and_unexpected_files()
    print("FEATURE-HOST-PATCH-SECURITY-OK")


if __name__ == "__main__":
    main()
