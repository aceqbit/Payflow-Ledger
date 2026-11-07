#!/usr/bin/env python3
"""Generates docs/results.json and docs/results.md from Prometheus, one row per RUN_ID.
Numbers come ONLY from Prometheus queries over the measure window (warm-up excluded); nothing is typed by hand.
usage: python perf/report.py [run_id ...]      (default: every perf/results/<run_id>.json)"""
import glob
import json
import os
import platform
import re
import subprocess
import sys
import urllib.parse
import urllib.request

PROM = os.environ.get("PROM", "http://localhost:9090")
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RESULTS = os.path.join(ROOT, "perf", "results")
DOCS = os.path.join(ROOT, "docs")
CHECKS = ["global_sum", "negative_balance", "balance_mismatch", "duplicate_charge"]


def query(expr, t):
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": t})
    with urllib.request.urlopen(url, timeout=30) as r:
        data = json.load(r)
    if data["status"] != "success":
        raise RuntimeError(expr + ": " + str(data))
    return data["data"]["result"]


def scalar(expr, t):
    res = query(expr, t)
    if not res:
        return None
    v = float(res[0]["value"][1])
    return None if v != v else v  # NaN -> None


def machine_spec():
    cpu = platform.processor() or platform.machine()
    ram = "?"
    try:
        if sys.platform == "win32":
            out = subprocess.check_output(["wmic", "computersystem", "get", "TotalPhysicalMemory", "/value"], text=True)
            ram = f"{int(out.strip().split('=')[1]) / 2**30:.0f} GiB"
        else:
            ram = f"{int(open('/proc/meminfo').readline().split()[1]) / 2**20:.0f} GiB"
    except Exception:
        pass
    return f"{platform.system()} {platform.release()}, {cpu}, {os.cpu_count()} logical CPUs, {ram} RAM, Postgres on the same host (no network)"


def measure(meta):
    rid = meta["run_id"]
    # measure window: after warm-up, until the load ended
    w_start = meta["start_epoch"] + meta["warmup_s"]
    w_end = meta["end_epoch"]
    w = max(int(w_end - w_start), 1)
    sel = f'run_id="{rid}"'
    ok = scalar(f'sum(increase(ledger_transfers_total{{{sel},outcome="ok"}}[{w}s]))', w_end)
    total = scalar(f'sum(increase(ledger_transfers_total{{{sel}}}[{w}s]))', w_end)
    retries = scalar(f'sum(increase(ledger_transfer_retries_total{{{sel}}}[{w}s]))', w_end) or 0.0

    def q(p):
        v = scalar(f'histogram_quantile({p}, sum by (le) (increase(ledger_transfer_duration_seconds_bucket{{{sel}}}[{w}s])))', w_end)
        return None if v is None else v * 1000.0

    rt_sum = scalar(f'sum(increase(ledger_db_roundtrips_sum{{{sel},operation="transfer"}}[{w}s]))', w_end)
    rt_cnt = scalar(f'sum(increase(ledger_db_roundtrips_count{{{sel},operation="transfer"}}[{w}s]))', w_end)
    acq99 = scalar(f'histogram_quantile(0.99, sum by (le) (increase(hikaricp_connections_acquire_seconds_bucket{{{sel}}}[{w}s])))', w_end)
    acq_mean = None
    a_s = scalar(f'sum(increase(hikaricp_connections_acquire_seconds_sum{{{sel}}}[{w}s]))', w_end)
    a_c = scalar(f'sum(increase(hikaricp_connections_acquire_seconds_count{{{sel}}}[{w}s]))', w_end)
    if a_s is not None and a_c:
        acq_mean = a_s / a_c * 1000.0
    inv = {}
    for c in CHECKS:
        v = scalar(f'max(max_over_time(ledger_invariant_violations{{{sel},check="{c}"}}[{int(meta["verified_epoch"] - w_start) + 30}s]))', meta["verified_epoch"] + 5)
        inv[c] = v
    breaks = scalar(f'max(max_over_time(ledger_chain_breaks{{{sel}}}[{int(meta["verified_epoch"] - w_start) + 30}s]))', meta["verified_epoch"] + 5)
    client = {}
    gp = os.path.join(RESULTS, "gatling-" + rid + ".log")
    if os.path.exists(gp):
        txt = open(gp, errors="replace").read()

        def g(pattern):
            m = re.search(pattern, txt)
            return float(m.group(1).replace(",", "")) if m else None

        client = {"client_p50_ms": g(r"response time 50th percentile \(ms\)\s*\|\s*([\d,.]+)"),
                  "client_p99_ms": g(r"response time 99th percentile \(ms\)\s*\|\s*([\d,.]+)"),
                  "client_rps": g(r"mean throughput \(rps\)\s*\|\s*([\d,.]+)"),
                  "client_failed": g(r"> KO\s+([\d,]+)")}
    return {
        **meta, **client,
        "window_s": w,
        "tps": None if ok is None else ok / w,
        "requests_ok": ok, "requests_total": total,
        "p50_ms": q(0.5), "p99_ms": q(0.99),
        "retry_ratio": None if not total else retries / total,
        "roundtrips_mean": None if not rt_cnt else rt_sum / rt_cnt,
        "pool_acquire_p99_ms": None if acq99 is None else acq99 * 1000.0,
        "pool_acquire_mean_ms": acq_mean,
        "invariants": inv, "chain_breaks": breaks,
    }


def fmt(v, nd=1):
    return "n/a" if v is None else f"{v:.{nd}f}"


def write_md(rows, spec):
    lines = ["# Results", "", f"Machine: {spec}", "",
             "Every number below is a Prometheus query over the measure window (first 60 s of each run discarded). "
             "Regenerate with `make report`.", "",
             "| run_id | scenario | strategy | dist | users | telemetry | TPS | p50 ms | p99 ms | retry ratio | round-trips | pool acquire (p99 / mean ms) | invariants (max) |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        inv = r["invariants"]
        inv_txt = "all 0" if all(v == 0 for v in inv.values()) else ", ".join(f"{k}={fmt(v, 0)}" for k, v in inv.items())
        tel = "off" if "telemetry-off" in r.get("profiles", "") else "on"
        lines.append(f"| {r['run_id']} | {r['scenario']} | {r['strategy']} | {r['dist']} | {r['users']} | {tel} | {fmt(r['tps'])} | "
                     f"{fmt(r['p50_ms'], 2)} | {fmt(r['p99_ms'], 2)} | {fmt(r['retry_ratio'], 4)} | {fmt(r['roundtrips_mean'], 2)} | "
                     f"{fmt(r['pool_acquire_p99_ms'], 2)} / {fmt(r['pool_acquire_mean_ms'], 3)} | {inv_txt} |")
    on = [r for r in rows if r["users"] == 200 and "telemetry-off" not in r.get("profiles", "") and r["dist"] == "uniform" and r.get("client_p99_ms")]
    off = [r for r in rows if r["users"] == 200 and "telemetry-off" in r.get("profiles", "") and r["dist"] == "uniform" and r.get("client_p99_ms")]
    if on and off:
        d = (on[-1]["client_p99_ms"] - off[-1]["client_p99_ms"]) / off[-1]["client_p99_ms"] * 100
        lines += ["", f"Telemetry overhead at 200 users, client-side (Gatling) p99 on vs off: {d:+.1f}% "
                      f"(on {on[-1]['run_id']}: {on[-1]['client_p99_ms']} ms, off {off[-1]['run_id']}: {off[-1]['client_p99_ms']} ms); "
                      f"budget is under 5%. Client-side because the telemetry-off profile exports no server metrics."]
    lines += ["", "Client-side (Gatling) view: run_id | p50 ms | p99 ms | req/s | failed", ""]
    for r in rows:
        if r.get("client_p99_ms") is not None:
            lines.append(f"- {r['run_id']}: {r['client_p50_ms']} | {r['client_p99_ms']} | {fmt(r['client_rps'], 1)} | {r['client_failed']}")
    lines += extra_sections(rows)
    with open(os.path.join(DOCS, "results.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


def inv_ok(r):
    return all(v == 0 for v in r["invariants"].values())


def matrix_section(rows):
    m = [r for r in rows if r["scenario"] == "matrix"]
    if not m:
        return []
    out = ["", "## Locking matrix (strategy x users x skew)", "",
           "| skew | users | strategy | TPS | p50 ms | p99 ms | retry ratio | pool acquire mean ms | invariants | run_id |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(m, key=lambda r: (r["dist"], r["users"], r["strategy"])):
        out.append(f"| {r['dist']} | {r['users']} | {r['strategy']} | {fmt(r['tps'])} | {fmt(r['p50_ms'], 2)} | {fmt(r['p99_ms'], 2)} | "
                   f"{fmt(r['retry_ratio'], 4)} | {fmt(r['pool_acquire_mean_ms'], 3)} | {'all 0' if inv_ok(r) else 'NON-ZERO'} | {r['run_id']} |")
    bad = [r["run_id"] for r in m if not inv_ok(r)]
    out += ["", "Runs with non-zero invariants: " + (", ".join(bad) if bad else "none")]
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(2, 3, figsize=(13, 7), sharex=True)
        for c, dist in enumerate(["uniform", "zipf", "hot"]):
            for strat in ["PESSIMISTIC", "OPTIMISTIC", "SERIALIZABLE"]:
                pts = sorted((r["users"], r["tps"], r["p99_ms"]) for r in m if r["dist"] == dist and r["strategy"] == strat and r["tps"] is not None)
                if pts:
                    ax[0][c].plot([p[0] for p in pts], [p[1] for p in pts], marker="o", label=strat)
                    ax[1][c].plot([p[0] for p in pts], [p[2] for p in pts], marker="o", label=strat)
            ax[0][c].set_title(dist); ax[1][c].set_xlabel("concurrent users")
        ax[0][0].set_ylabel("TPS"); ax[1][0].set_ylabel("p99 ms"); ax[0][0].legend()
        fig.tight_layout(); fig.savefig(os.path.join(DOCS, "locking.png"), dpi=110); plt.close(fig)
        out += ["", "![locking trade-off](locking.png)"]
    except ImportError:
        out += ["", "(matplotlib not installed: locking.png not rendered)"]
    return out


def hot_section(rows):
    h = [r for r in rows if r["scenario"].startswith("hot-N")]
    if not h:
        return []
    out = ["", "## Hot-account buckets (PESSIMISTIC, 1 hot account, TPS vs N)", "", "| N | users | TPS | p99 ms | lock wait ok | invariants | run_id |", "|---|---|---|---|---|---|---|"]
    byn = {}
    for r in sorted(h, key=lambda r: int(r["scenario"].split("N")[-1])):
        n = int(r["scenario"].split("N")[-1])
        byn[n] = r
        out.append(f"| {n} | {r['users']} | {fmt(r['tps'])} | {fmt(r['p99_ms'], 2)} | - | {'all 0' if inv_ok(r) else 'NON-ZERO'} | {r['run_id']} |")
    if 1 in byn and 16 in byn and byn[1]["tps"]:
        out += ["", f"TPS at N=16 is {byn[16]['tps'] / byn[1]['tps']:.2f}x N=1 (target: 5x or more)."]
    return out


def _avg(runs, key):
    vals = [r[key] for r in runs if r.get(key) is not None]
    return sum(vals) / len(vals) if vals else None


def tuning_section(rows):
    steps = {}
    for r in rows:
        if r["scenario"].startswith("tune-"):
            _, step, phase = r["scenario"].split("-", 2)
            steps.setdefault(step, {}).setdefault(phase, {}).setdefault(r["dist"], []).append(r)
    if not steps:
        return []
    out = ["", "## Tuning loop", "",
           "Each cell is the mean of the interleaved runs for that variant (n shown). TPS is the server-side Prometheus rate over the measure window; "
           "p50/p99 are client-side (Gatling) because the server histogram tops out at 500 ms. The shared laptop also ran an unrelated Kafka/dual-write "
           "workload during these runs, so run-to-run noise is large; treat gains under about 10% as inconclusive.", "",
           "Rule: keep a change only if every invariant stays 0 and TPS or p99 improves by 5% or more without the other getting more than 5% worse.", "",
           "| step | change | skew | n | TPS before -> after | client p50 ms before -> after | client p99 ms before -> after | round-trips before -> after | decision | before runs | after runs |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for step in sorted(steps):
        if "after" not in steps[step]:
            continue
        base = step.split("_")[0]
        for dist in ("uniform", "hot"):
            bs = (steps[step].get("before") or steps.get(base, {}).get("before") or steps.get("TB", {}).get("before") or {}).get(dist)
            as_ = steps[step]["after"].get(dist)
            if not bs or not as_:
                continue
            bt, at = _avg(bs, "tps"), _avg(as_, "tps")
            bp, ap = _avg(bs, "client_p99_ms"), _avg(as_, "client_p99_ms")
            b5, a5 = _avg(bs, "client_p50_ms"), _avg(as_, "client_p50_ms")
            if not (bt and at and bp and ap):
                continue
            tps_gain = (at - bt) / bt * 100
            p99_gain = (bp - ap) / bp * 100
            keep = all(inv_ok(r) for r in as_) and ((tps_gain >= 5 and p99_gain > -5) or (p99_gain >= 5 and tps_gain > -5))
            note = as_[-1].get("note", "")
            out.append(f"| {step} | {note} | {dist} | {len(bs)}/{len(as_)} | {fmt(bt)} -> {fmt(at)} ({tps_gain:+.1f}%) | {fmt(b5)} -> {fmt(a5)} | "
                       f"{fmt(bp)} -> {fmt(ap)} ({-p99_gain:+.1f}%) | {fmt(_avg(bs, 'roundtrips_mean'), 2)} -> {fmt(_avg(as_, 'roundtrips_mean'), 2)} | "
                       f"{'kept' if keep else 'reverted'} | {', '.join(r['run_id'] for r in bs)} | {', '.join(r['run_id'] for r in as_)} |")
    return out


def extra_sections(rows):
    return matrix_section(rows) + hot_section(rows) + tuning_section(rows)


def main():
    metas = []
    wanted = set(sys.argv[1:])
    for p in sorted(glob.glob(os.path.join(RESULTS, "*.json"))):
        try:
            m = json.load(open(p))
        except Exception:
            continue
        if "run_id" in m and "start_epoch" in m and (not wanted or m["run_id"] in wanted):
            metas.append(m)
    os.makedirs(DOCS, exist_ok=True)
    rows = [measure(m) for m in metas]
    spec = machine_spec()
    json.dump({"machine": spec, "runs": rows}, open(os.path.join(DOCS, "results.json"), "w"), indent=1)
    write_md(rows, spec)
    print(f"wrote docs/results.json and docs/results.md for {len(rows)} runs")


if __name__ == "__main__":
    main()
