#!/usr/bin/env python3
"""Apply reviewed Feature-owned host patch templates with fail-closed checks.

Infrastructure owns this generic applicator only. Product behavior belongs in
features/host-integration/patches/**, never in this script.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path, PurePosixPath


def _assert_no_symlink_components(root: Path, candidate: Path) -> None:
    if root.is_symlink():
        raise SystemExit(f"approved root is symlinked: {root}")
    root = root.resolve(strict=True)
    try:
        relative = candidate.relative_to(root)
    except ValueError as exc:
        raise SystemExit(f"host patch path escapes approved root: {candidate}") from exc
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise SystemExit(f"symlink component rejected: {current}")


def _validated_target_path(project_root: Path, target: str) -> Path:
    if "\x00" in target or "\\" in target:
        raise SystemExit("host patch target contains an invalid separator")
    relative = PurePosixPath(target)
    if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
        raise SystemExit(f"unsafe host patch target: {target}")
    if not target.startswith("app/src/main/"):
        raise SystemExit(f"host patch target is outside app/src/main: {target}")

    root = project_root.resolve(strict=True)
    candidate = root.joinpath(*relative.parts)
    _assert_no_symlink_components(root, candidate)
    if not candidate.is_file():
        raise SystemExit(f"host patch target is not a regular file: {target}")
    try:
        candidate.resolve(strict=True).relative_to(root)
    except ValueError as exc:
        raise SystemExit(f"host patch target escapes project root: {target}") from exc
    return candidate


def _parse_single_hunk_patch(patch_file: Path) -> tuple[str, list[str], list[str]]:
    if patch_file.is_symlink() or not patch_file.is_file():
        raise SystemExit(f"patch template is not a regular file: {patch_file}")
    try:
        lines = patch_file.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise SystemExit(f"cannot read UTF-8 patch template {patch_file}: {exc}") from exc

    diff_headers = [line for line in lines if line.startswith("diff --git ")]
    old_headers = [line for line in lines if line.startswith("--- ")]
    new_headers = [line for line in lines if line.startswith("+++ ")]
    hunk_indexes = [index for index, line in enumerate(lines) if line.startswith("@@ ")]
    if len(diff_headers) != 1 or len(old_headers) != 1 or len(new_headers) != 1 or len(hunk_indexes) != 1:
        raise SystemExit(f"patch must contain exactly one file and one hunk: {patch_file}")

    diff_match = re.fullmatch(r"diff --git a/(.+) b/(.+)", diff_headers[0])
    old_match = re.fullmatch(r"--- a/(.+)", old_headers[0])
    new_match = re.fullmatch(r"\+\+\+ b/(.+)", new_headers[0])
    if not diff_match or not old_match or not new_match:
        raise SystemExit(f"unsupported patch header: {patch_file}")
    target = diff_match.group(1)
    if diff_match.group(2) != target or old_match.group(1) != target or new_match.group(1) != target:
        raise SystemExit(f"patch renames or mismatches its target: {patch_file}")

    hunk_index = hunk_indexes[0]
    hunk_match = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", lines[hunk_index])
    if not hunk_match:
        raise SystemExit(f"unsupported hunk header: {patch_file}")

    old_lines: list[str] = []
    new_lines: list[str] = []
    for line in lines[hunk_index + 1:]:
        if line.startswith(" "):
            old_lines.append(line[1:])
            new_lines.append(line[1:])
        elif line.startswith("-"):
            old_lines.append(line[1:])
        elif line.startswith("+"):
            new_lines.append(line[1:])
        elif line.startswith("\\ No newline at end of file"):
            continue
        else:
            raise SystemExit(f"unsupported patch directive in {patch_file}: {line!r}")

    expected_old = int(hunk_match.group(2) or "1")
    expected_new = int(hunk_match.group(4) or "1")
    if len(old_lines) != expected_old or len(new_lines) != expected_new:
        raise SystemExit(f"hunk line counts do not match in {patch_file}")
    if not old_lines or not new_lines or old_lines == new_lines:
        raise SystemExit(f"empty or no-op patch rejected: {patch_file}")
    return target, old_lines, new_lines


def _count_exact_sequence(lines: list[str], sequence: list[str]) -> list[int]:
    width = len(sequence)
    return [
        index for index in range(len(lines) - width + 1)
        if lines[index:index + width] == sequence
    ]


def _apply_one(project_root: Path, patch_file: Path) -> str:
    target_rel, old_lines, new_lines = _parse_single_hunk_patch(patch_file)
    target = _validated_target_path(project_root, target_rel)
    original = target.read_text(encoding="utf-8")
    lines = original.splitlines()
    old_matches = _count_exact_sequence(lines, old_lines)
    new_matches = _count_exact_sequence(lines, new_lines)

    if len(old_matches) == 0 and len(new_matches) == 1:
        return f"already-applied: {target_rel}"
    if len(old_matches) != 1 or len(new_matches) != 0:
        raise SystemExit(
            f"refusing ambiguous/stale patch {patch_file.name}: "
            f"preimage_count={len(old_matches)} postimage_count={len(new_matches)}"
        )

    start = old_matches[0]
    updated_lines = lines[:start] + new_lines + lines[start + len(old_lines):]
    trailing_newline = "\n" if original.endswith("\n") else ""
    target.write_text("\n".join(updated_lines) + trailing_newline, encoding="utf-8")
    return f"applied: {target_rel}"


def _list_patch_files(patch_root: Path) -> list[Path]:
    if patch_root.is_symlink() or not patch_root.is_dir():
        raise SystemExit(f"Feature host patch root is missing or symlinked: {patch_root}")
    patches: list[Path] = []
    for current_root, directories, filenames in os.walk(patch_root, followlinks=False):
        current = Path(current_root)
        for directory in list(directories):
            child = current / directory
            if child.is_symlink():
                raise SystemExit(f"symlink directory rejected in patch tree: {child}")
        for filename in filenames:
            child = current / filename
            if child.is_symlink():
                raise SystemExit(f"symlink file rejected in patch tree: {child}")
            if child.suffix != ".patch":
                raise SystemExit(f"unexpected non-patch file in Feature host patch tree: {child}")
            if not child.is_file():
                raise SystemExit(f"non-regular patch entry rejected: {child}")
            patches.append(child)
    return sorted(patches, key=lambda item: item.as_posix())


def apply_feature_host_patches(project_root: Path, features_root: Path) -> list[str]:
    if project_root.is_symlink():
        raise SystemExit(f"project root is symlinked: {project_root}")
    if features_root.is_symlink():
        raise SystemExit(f"Feature source root is symlinked: {features_root}")
    project_root = project_root.resolve(strict=True)
    features_root = features_root.resolve(strict=True)
    patch_root = features_root / "host-integration" / "patches"
    _assert_no_symlink_components(features_root, patch_root)
    patch_files = _list_patch_files(patch_root)
    if not patch_files:
        raise SystemExit("no reviewed Feature host patch templates were found")
    return [_apply_one(project_root, patch_file) for patch_file in patch_files]


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_feature_host_patches.py <extracted-project-root>")
    repository_root = Path(__file__).resolve().parents[1]
    results = apply_feature_host_patches(Path(sys.argv[1]), repository_root / "features")
    for result in results:
        print(result)
    print(f"FEATURE-HOST-PATCHES-OK: {len(results)} reviewed templates")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
