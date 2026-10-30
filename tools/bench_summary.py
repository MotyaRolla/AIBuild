#!/usr/bin/env python3
"""Downloads bench reports from the server and prints one comparison table."""
import re, subprocess, sys
ROWS = ["Время, c", "Вызовов модели", "Токены: вход", "Токены: выход (из них мыслей)", "Раундов проверки",
        "Самооценка ИИ", "Анализатор: крит/серьёзн/мелкие", "Изменено блоков от старта", "Оценка судьи (0-100)"]
def report(bid):
    out = subprocess.run(["node", "tools/panel.mjs", "cat", f"/plugins/AIBuild/bench/{bid}/report.md"], capture_output=True, text=True).stdout
    if "| Метрика |" not in out: return None
    head = [l for l in out.splitlines() if l.startswith("| Метрика")][0].split("|")
    names = [h.strip() for h in head[2:-1]]
    vals = {}
    for l in out.splitlines():
        if not l.startswith("|") or l.startswith("|---") or l.startswith("| Метрика") or l.startswith("| metric"):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if cells and cells[0] in ROWS:
            vals[cells[0]] = cells[1:]
    for k in list(vals):
        while vals[k] and vals[k][-1] in ("", "-"):
            vals[k] = vals[k][:-1]
    win = re.search(r"Победитель по мнению судьи: (.+?)\*\*", out)
    return names, vals, win.group(1) if win else "?"
for spec in sys.argv[1:]:
    label, bid = spec.split("=")
    r = report(bid)
    if not r: print(f"{label} ({bid}): no report yet"); continue
    names, vals, win = r
    print(f"\n### {label} ({bid}) — winner: {win}")
    print("| metric | " + " | ".join(names) + " |")
    print("|---|" + "---|" * len(names))
    for k in ROWS:
        if k not in vals: continue
        v = vals[k]
        cells = []
        for i in range(len(names)):
            cells.append(v[i] if i < len(v) else "-")
        print(f"| {k} | " + " | ".join(cells) + " |")
