#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-static-review}"
mkdir -p "$OUT_DIR"

python3 - "$OUT_DIR" <<'PY'
import os
import re
import sys

out_dir = sys.argv[1]
root = "app/src/main"

bom_files = []
todo_hits = []
raw_threads = []
sync_sources = []

for dirpath, _, filenames in os.walk(root):
    for fn in filenames:
        p = os.path.join(dirpath, fn)
        try:
            with open(p, "rb") as f:
                raw = f.read()
        except OSError:
            continue
        if raw.startswith(b"\xef\xbb\xbf"):
            bom_files.append(p)
        if fn.endswith((".kt", ".java", ".xml")):
            txt = raw.decode("utf-8", errors="replace")
            for idx, line in enumerate(txt.splitlines(), 1):
                if re.search(r"\b(TODO|FIXME|HACK)\b", line):
                    todo_hits.append(f"{p}:{idx}: {line.strip()}")
                if fn.endswith(".kt") and ("GlobalScope" in line or "Thread.sleep" in line):
                    raw_threads.append(f"{p}:{idx}: {line.strip()}")
            if fn in ("ZipSource.kt", "RarSource.kt", "SevenZipSource.kt", "PdfSource.kt"):
                has_sync = "@Synchronized" in txt
                sync_sources.append(f"{fn}: @Synchronized={'YES' if has_sync else 'NO'}")

with open(os.path.join(out_dir, "bom-check.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(bom_files) if bom_files else "0 files with UTF-8 BOM.\n")

with open(os.path.join(out_dir, "todos.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(todo_hits) if todo_hits else "No TODO/FIXME markers found.\n")

with open(os.path.join(out_dir, "concurrency-hints.txt"), "w", encoding="utf-8") as f:
    f.write("PageSource synchronization:\n  " + "\n  ".join(sorted(sync_sources)) + "\n\n")
    f.write("Raw thread / GlobalScope hits:\n  " + ("\n  ".join(raw_threads) if raw_threads else "None") + "\n")

summary = [
    "ComicReader Static Review Hints",
    "===============================",
    f"UTF-8 BOM files: {len(bom_files)}",
    f"TODO/FIXME markers: {len(todo_hits)}",
    f"GlobalScope / Thread.sleep hits: {len(raw_threads)}",
    "PageSource thread-safety: " + ", ".join(sorted(sync_sources)),
]
with open(os.path.join(out_dir, "summary.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(summary) + "\n")

print("\n".join(summary))
PY
