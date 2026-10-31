#!/usr/bin/env python3
"""Summarize local build timings and analyzer findings without making API calls."""

import argparse
import gzip
import json
import re
from pathlib import Path


def summarize(data: Path, build_id: str) -> dict:
    with gzip.open(data / "builds" / f"{build_id}.json.gz", "rt") as stream:
        build = json.load(stream)
    runs = sorted((data / "logs" / build_id).iterdir())
    run = runs[-1]
    metrics_file = run / "metrics.json"
    metrics = json.loads(metrics_file.read_text()) if metrics_file.exists() else {}
    calls = []
    for path in sorted(run.glob("*.md")):
        text = path.read_text()
        usage = re.search(r"## usage\n([^\n]+)", text)
        if not usage:
            continue
        values = dict(re.findall(r"(\w+)=([^ ]+)", usage[1]))
        elapsed = int(values["ms"])
        end = round(path.stat().st_mtime * 1000)
        calls.append({
            "tag": text.splitlines()[0].removeprefix("# "),
            "elapsedMs": elapsed,
            "startMs": end - elapsed - build["createdAt"],
            "endMs": end - build["createdAt"],
            "promptTokens": int(values["prompt"]),
            "completionTokens": int(values["completion"]),
            "reasoningTokens": int(values["reasoning"]),
            "finish": values["finish"],
        })
    final_paths = sorted(run.glob("*final.txt"))
    final = final_paths[-1].read_text() if final_paths else ""
    model = build["versions"][build["currentVersion"]]["model"]
    palette, cells = model["palette"], model["cells"]
    block_count = sum(palette[index] not in {"air", "minecraft:air"} for index in cells[3::4])
    return {
        "buildId": build_id,
        "request": build.get("originalPrompt"),
        "elapsedMs": metrics.get("elapsedMs", build["updatedAt"] - build["createdAt"]),
        "calls": metrics.get("calls", len(calls)),
        "callsWithoutUsage": metrics.get("callsWithoutUsage", len(list(run.glob("*aborted.txt")))),
        "promptTokens": sum(call["promptTokens"] for call in calls),
        "completionTokens": sum(call["completionTokens"] for call in calls),
        "reasoningTokens": sum(call["reasoningTokens"] for call in calls),
        "blocks": block_count,
        "critical": len(re.findall(r"^CRITICAL ", final, re.M)),
        "major": len(re.findall(r"^MAJOR ", final, re.M)),
        "analyzer": final,
        "metrics": metrics,
        "timeline": calls,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("build_ids", nargs="+")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    results = [summarize(args.server / "plugins" / "AIBuild", build_id) for build_id in args.build_ids]
    text = json.dumps(results, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n")
    for result in results:
        print(f"{result['buildId']}: {result['elapsedMs']/1000:.2f}s, {result['calls']} calls, "
              f"{result['completionTokens']} output tokens, {result['blocks']} blocks, "
              f"{result['critical']}/{result['major']} critical/major")


if __name__ == "__main__":
    main()
