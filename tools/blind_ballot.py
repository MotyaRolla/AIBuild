#!/usr/bin/env python3
"""Blind human A/B ballot for `/aib bench create` results, and its tally.

Make a ballot from any number of bench folders (each holds the two renders strategy-<variant>.png; the variant
names come from bench.json "a"/"b", e.g. script-v2 and script-v3; older folders compare compiled and script):
    python3 tools/blind_ballot.py make bench/bench-1a2b3c bench/bench-4d5e6f ... --output bench/ballot-20261103
Open bench/ballot-20261103/ballot.html, vote on every pair (left / right / tie), press "Export votes" and save
the JSON next to the ballot. Then:
    python3 tools/blind_ballot.py tally bench/ballot-20261103 votes.json

Left/right order is randomized per pair and kept in mapping.private.json, so the voter cannot know which
pipeline made which build. The tally reports wins per strategy and an exact two-sided sign test (ties dropped).
About 30 decided pairs give ~80% power for a 75/25 preference; fewer pairs cannot show a real difference.
"""
import argparse
import base64
import html
import json
import math
import secrets
from pathlib import Path

LEGACY = ("compiled", "script")


def variants(folder):
    meta = json.loads((folder / "bench.json").read_text()) if (folder / "bench.json").is_file() else {}
    if "a" in meta and "b" in meta:
        return meta["a"], meta["b"]
    return LEGACY


def make(args):
    out = args.output
    out.mkdir(parents=True, exist_ok=False)
    rnd = secrets.SystemRandom()
    pairs, mapping = [], {}
    for folder in args.benches:
        names = variants(folder)
        files = {s: folder / f"strategy-{s}.png" for s in names}
        if not all(f.is_file() for f in files.values()):
            print(f"skip {folder}: both renders are needed (one variant failed?)")
            continue
        meta = json.loads((folder / "bench.json").read_text()) if (folder / "bench.json").is_file() else {}
        order = list(names)
        rnd.shuffle(order)
        pid = f"p{len(pairs) + 1:03d}"
        mapping[pid] = {"bench": str(folder), "left": order[0], "right": order[1]}
        pairs.append({"id": pid, "prompt": meta.get("prompt", folder.name),
                      "left": base64.b64encode(files[order[0]].read_bytes()).decode(),
                      "right": base64.b64encode(files[order[1]].read_bytes()).decode()})
    rnd.shuffle(pairs)
    if not pairs:
        raise SystemExit("no complete bench folders")
    (out / "mapping.private.json").write_text(json.dumps(mapping, indent=2))
    (out / "mapping.private.json").chmod(0o600)
    blocks = []
    for p in pairs:
        blocks.append(f"""
<section class="pair" data-id="{p['id']}">
  <h2>{html.escape(p['prompt'])}</h2>
  <div class="imgs"><figure><img src="data:image/png;base64,{p['left']}"><figcaption>Left</figcaption></figure>
  <figure><img src="data:image/png;base64,{p['right']}"><figcaption>Right</figcaption></figure></div>
  <div class="vote"><button data-v="left">Left is better</button><button data-v="tie">Tie</button>
  <button data-v="right">Right is better</button></div>
</section>""")
    page = f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Blind build ballot</title><style>
:root{{--bg:#f6f7f9;--fg:#1d2433;--card:#fff;--line:#d6dbe3;--pick:#2f6fdf}}
@media (prefers-color-scheme:dark){{:root{{--bg:#14171c;--fg:#e6e9ef;--card:#1c2027;--line:#333a45;--pick:#6b9cff}}}}
body{{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}}
.pair{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;margin:0 auto 18px;max-width:1400px}}
h2{{font-size:16px;margin:0 0 8px}} .imgs{{display:grid;grid-template-columns:1fr 1fr;gap:10px}}
img{{width:100%;height:auto;border-radius:6px;background:#fff}} figure{{margin:0}} figcaption{{text-align:center;opacity:.7}}
@media (max-width:760px){{.imgs{{grid-template-columns:1fr}}}}
.vote{{display:flex;gap:8px;justify-content:center;margin-top:8px;flex-wrap:wrap}}
button{{font:inherit;padding:8px 14px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--fg);cursor:pointer}}
button.on{{background:var(--pick);color:#fff;border-color:var(--pick)}} #bar{{position:sticky;top:0;background:var(--bg);padding:8px 0;text-align:center}}
</style></head><body>
<div id="bar">Judge only what you see: fidelity to the prompt, recognizability, form and depth, palette, composition.
<button id="export">Export votes</button> <span id="count"></span></div>
{''.join(blocks)}
<script>
const KEY='aibuild-ballot-{out.name}';let votes={{}};try{{votes=JSON.parse(localStorage.getItem(KEY)||'{{}}')}}catch(e){{}}
function sync(){{document.querySelectorAll('.pair').forEach(p=>p.querySelectorAll('button').forEach(b=>b.classList.toggle('on',votes[p.dataset.id]===b.dataset.v)));
document.getElementById('count').textContent=Object.keys(votes).length+' / '+document.querySelectorAll('.pair').length+' voted'}}
document.querySelectorAll('.pair').forEach(p=>p.querySelectorAll('button').forEach(b=>b.onclick=()=>{{votes[p.dataset.id]=b.dataset.v;try{{localStorage.setItem(KEY,JSON.stringify(votes))}}catch(e){{}}sync()}}));
document.getElementById('export').onclick=()=>{{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(votes,null,2)],{{type:'application/json'}}));a.download='votes.json';a.click()}};
sync();
</script></body></html>"""
    (out / "ballot.html").write_text(page)
    print(f"{len(pairs)} pairs -> {out / 'ballot.html'} (mapping hidden in mapping.private.json)")


def sign_test(wins, losses):
    n = wins + losses
    if n == 0:
        return 1.0
    k = min(wins, losses)
    p = sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n
    return min(1.0, 2 * p)


def tally(args):
    mapping = json.loads((args.ballot / "mapping.private.json").read_text())
    votes = json.loads(args.votes.read_text())
    wins = {}
    ties = 0
    for pid, v in votes.items():
        m = mapping.get(pid)
        if not m:
            continue
        for side in ("left", "right"):
            wins.setdefault(m[side], 0)
        if v == "tie":
            ties += 1
        elif v in ("left", "right"):
            wins[m[v]] += 1
    if len(wins) != 2:
        print("the ballot must compare exactly two variants:", sorted(wins))
        return
    (na, a), (nb, b) = sorted(wins.items(), key=lambda kv: -kv[1])
    print(f"{na} {a} : {nb} {b}  (ties {ties}, pairs {len(votes)})")
    if a + b:
        print(f"{na} preferred in {a / (a + b):.0%} of decided pairs; exact two-sided sign test p = {sign_test(a, b):.4f}")
    if a + b < 30:
        print("fewer than 30 decided pairs: not enough to claim a preference")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("benches", type=Path, nargs="+")
    m.add_argument("--output", type=Path, required=True)
    t = sub.add_parser("tally")
    t.add_argument("ballot", type=Path)
    t.add_argument("votes", type=Path)
    args = ap.parse_args()
    make(args) if args.cmd == "make" else tally(args)


if __name__ == "__main__":
    main()
