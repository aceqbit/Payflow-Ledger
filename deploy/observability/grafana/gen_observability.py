#!/usr/bin/env python3
"""Generates the provisioned dashboards (dashboards/*.json) and alert rules (provisioning/alerting).
Run: python deploy/observability/grafana/gen_observability.py   (output is committed; never hand-edit it)."""
import json
import pathlib

HERE = pathlib.Path(__file__).parent
DS = {"type": "prometheus", "uid": "prometheus"}
SEL = 'run_id=~"$run_id",scenario=~"$scenario"'
_ids = iter(range(1, 10_000))


def target(expr, legend="", ref="A", exemplar=False):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref}
    if exemplar:
        t["exemplar"] = True
    return t


def panel(kind, title, exprs, x, y, w=6, h=7, unit=None, thresholds=None, exemplar=False, desc=""):
    """exprs: list of (expr, legend)."""
    p = {
        "id": next(_ids), "type": kind, "title": title, "description": desc, "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [target(e, l, chr(65 + i), exemplar) for i, (e, l) in enumerate(exprs)],
        "fieldConfig": {"defaults": {}, "overrides": []},
    }
    if unit:
        p["fieldConfig"]["defaults"]["unit"] = unit
    if thresholds:
        p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": thresholds}
        p["fieldConfig"]["defaults"]["color"] = {"mode": "thresholds"}
    return p


GREEN_AT_ZERO = [{"color": "green", "value": None}, {"color": "red", "value": 1}]


def stat(title, expr, x, y, w=6, unit=None, thresholds=None, desc=""):
    return panel("stat", title, [(expr, "")], x, y, w, 4, unit, thresholds, desc=desc)


def ts(title, exprs, x, y, w=12, unit=None, exemplar=False, desc=""):
    return panel("timeseries", title, exprs, x, y, w, 8, unit, exemplar=exemplar, desc=desc)


def variable(name, label, query):
    return {"name": name, "label": label, "type": "query", "datasource": DS, "query": {"query": query, "refId": name},
            "refresh": 2, "includeAll": True, "allValue": ".*", "multi": True, "current": {"text": "All", "value": "$__all"}}


def dashboard(uid, title, panels):
    return {
        "uid": uid, "title": title, "schemaVersion": 39, "version": 1, "editable": False, "refresh": "10s",
        "time": {"from": "now-15m", "to": "now"}, "tags": ["payflow"], "panels": panels,
        "templating": {"list": [
            variable("run_id", "run_id", 'label_values(ledger_transfers_total, run_id)'),
            variable("scenario", "scenario", 'label_values(ledger_transfers_total, scenario)'),
            variable("strategy", "strategy", 'label_values(ledger_transfers_total, strategy)'),
        ]},
        "annotations": {"list": [{"name": "Gatling / chaos runs", "enable": True, "iconColor": "blue",
                                  "datasource": {"type": "grafana", "uid": "-- Grafana --"},
                                  "target": {"type": "tags", "tags": ["gatling"], "limit": 100}}]},
    }


def q(metric, extra=""):
    return f'{metric}{{{SEL}{("," + extra) if extra else ""}}}'


overview = dashboard("ledger-overview", "Ledger Overview", [
    stat("TPS", f'sum(rate({q("ledger_transfers_total", "outcome=\"ok\", strategy=~\"$strategy\"")}[1m]))', 0, 0, 6, "ops"),
    stat("p99 latency", f'histogram_quantile(0.99, sum by (le) (rate({q("ledger_transfer_duration_seconds_bucket", "strategy=~\"$strategy\"")}[1m])))', 6, 0, 6, "s"),
    stat("Error %", 'sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{uri=~"/v1/.*"}[5m])) * 100', 12, 0, 6, "percent"),
    stat("Latency SLO budget left", '(1 - (1 - ledger:sli_latency_50ms:6h) / 0.01) * 100', 18, 0, 6, "percent",
         [{"color": "red", "value": None}, {"color": "yellow", "value": 25}, {"color": "green", "value": 50}], desc="99% of transfers under 50 ms"),
    ts("RED by endpoint (rate)", [('sum by (uri, status) (rate(http_server_requests_seconds_count{uri=~"/v1/.*"}[1m]))', "{{uri}} {{status}}")], 0, 4, 12, "reqps"),
    ts("p50 / p95 / p99 by strategy",
       [(f'histogram_quantile({p}, sum by (le, strategy) (rate({q("ledger_transfer_duration_seconds_bucket", "strategy=~\"$strategy\"")}[1m])))', f"p{int(p*100)} {{{{strategy}}}}")
        for p in (0.5, 0.95, 0.99)], 12, 4, 12, "s", exemplar=True, desc="Exemplars on the p99 line link to the slow trace in Tempo"),
    ts("Availability SLO budget left", [('(1 - (1 - ledger:sli_availability:6h) / 0.001) * 100', "budget %")], 0, 12, 12, "percent"),
])

correctness = dashboard("money-correctness", "Money Correctness", [
    *[stat(c, f'max(ledger_invariant_violations{{check="{c}"}})', i * 6, 0, 6, None, GREEN_AT_ZERO, "must be 0")
      for i, c in enumerate(["global_sum", "negative_balance", "balance_mismatch", "duplicate_charge"])],
    stat("Chain breaks", 'sum(ledger_chain_breaks)', 0, 4, 6, None, GREEN_AT_ZERO, "0 outside tamper runs"),
    stat("Tamper detected / injected %", 'sum(ledger_chain_breaks) / clamp_min(sum(ledger_tamper_injected), 1) * 100', 6, 4, 6, "percent",
         [{"color": "red", "value": None}, {"color": "green", "value": 100}], "must be 100 during tamper runs"),
    stat("Checker freshness (s)", 'time() - max(ledger_invariant_last_run_timestamp_seconds)', 12, 4, 6, "s",
         [{"color": "green", "value": None}, {"color": "red", "value": 120}], "alert if older than 2 min"),
    ts("Idempotency outcomes", [(f'sum by (result) (rate({q("ledger_idempotency_total")}[1m]))', "{{result}}")], 0, 8, 12, "ops"),
    ts("Rejected payment transitions", [('sum by (from, to) (rate(ledger_payment_transitions_total{result="rejected"}[1m]))', "{{from}} -> {{to}}")], 12, 8, 12, "ops"),
])

contention = dashboard("contention", "Contention", [
    stat("Retry ratio", f'sum(rate({q("ledger_transfer_retries_total", "strategy=~\"$strategy\"")}[1m])) / sum(rate({q("ledger_transfers_total", "strategy=~\"$strategy\"")}[1m]))', 0, 0, 8),
    stat("Lock wait p99", f'histogram_quantile(0.99, sum by (le) (rate({q("ledger_lock_wait_seconds_bucket", "strategy=~\"$strategy\"")}[1m])))', 8, 0, 8, "s"),
    stat("Waiting locks", 'max(pg_locks_waiting)', 16, 0, 8),
    ts("Retries by strategy and reason", [(f'sum by (strategy, reason) (rate({q("ledger_transfer_retries_total")}[1m]))', "{{strategy}} {{reason}}")], 0, 4, 12, "ops"),
    ts("Attempts per transfer (mean)", [(f'sum by (strategy) (rate({q("ledger_transfer_attempts_sum")}[1m])) / sum by (strategy) (rate({q("ledger_transfer_attempts_count")}[1m]))', "{{strategy}}")], 12, 4, 12),
    ts("Deadlocks", [('increase(postgresql_deadlocks_total[1m])', "deadlocks")], 0, 12, 12),
    ts("Hikari pending / acquire p99", [('max(hikaricp_connections_pending)', "pending"),
       ('histogram_quantile(0.99, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket[1m])))', "acquire p99")], 12, 12, 12),
    ts("Hot-bucket lock wait by N", [(f'histogram_quantile(0.99, sum by (le, buckets) (rate({q("ledger_hot_bucket_lock_seconds_bucket")}[1m])))', "N={{buckets}}")], 0, 20, 24, "s"),
])

postgres = dashboard("postgres", "Postgres", [
    stat("Commits/s", 'sum(rate(postgresql_commits_total[1m]))', 0, 0, 8, "ops"),
    stat("Rollback %", 'sum(rate(postgresql_rollbacks_total[1m])) / clamp_min(sum(rate(postgresql_commits_total[1m])) + sum(rate(postgresql_rollbacks_total[1m])), 1) * 100', 8, 0, 8, "percent"),
    stat("Backends", 'max(postgresql_backends)', 16, 0, 8),
    ts("Wait events", [('sum by (wait_event_type) (pg_wait_events)', "{{wait_event_type}}")], 0, 4, 12),
    ts("Top 10 statements mean time", [('topk(10, pg_statement_mean_seconds)', "{{queryid}}")], 12, 4, 12, "s"),
    ts("WAL replication slot lag", [('max by (slot) (pg_replication_slot_lag_bytes)', "{{slot}}")], 0, 12, 12, "bytes"),
    ts("DB container CPU", [('container_cpu_utilization{container=~".*postgres.*"}', "{{container}}")], 12, 12, 12, "percentunit"),
])

jvm = dashboard("jvm", "JVM", [
    stat("CPU", 'max(process_cpu_usage) * 100', 0, 0, 8, "percent"),
    stat("Heap used", 'sum(jvm_memory_used_bytes{area="heap"})', 8, 0, 8, "bytes"),
    stat("GC pause p99", 'histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket[1m])))', 16, 0, 8, "s"),
    ts("Virtual-thread pinning", [('sum(rate(jvm_threads_virtual_pinned_seconds_sum[1m]))', "pinned s/s")], 0, 4, 12, desc="must stay near 0 on Java 25"),
    ts("Live threads", [('max(jvm_threads_live_threads)', "live")], 12, 4, 12),
    ts("Allocation rate", [('sum(rate(jvm_gc_memory_allocated_bytes_total[1m]))', "alloc")], 0, 12, 12, "Bps"),
])

(HERE / "dashboards").mkdir(exist_ok=True)
for d in (overview, correctness, contention, postgres, jvm):
    (HERE / "dashboards" / f"{d['uid']}.json").write_text(json.dumps(d, indent=1, sort_keys=True) + "\n")

# ---- alert rules: (uid, title, promql, threshold, for, severity, nodata) ----
ALERTS = [
    ("invariant-violation", "InvariantViolation", "max(ledger_invariant_violations)", 0, "0s", "critical", "OK"),
    ("invariant-stale", "InvariantCheckerStale", "time() - max(ledger_invariant_last_run_timestamp_seconds)", 120, "2m", "critical", "Alerting"),
    ("chain-break-outside-tamper", "ChainBreakOutsideTamperTest", 'max(ledger_chain_breaks{scenario!="tamper"})', 0, "0s", "critical", "OK"),
    ("availability-fast-burn", "AvailabilityFastBurn",
     "count((1 - ledger:sli_availability:1h) > 14.4 * 0.001 and (1 - ledger:sli_availability:5m) > 14.4 * 0.001)", 0, "2m", "page", "OK"),
    ("availability-slow-burn", "AvailabilitySlowBurn",
     "count((1 - ledger:sli_availability:6h) > 6 * 0.001 and (1 - ledger:sli_availability:30m) > 6 * 0.001)", 0, "15m", "ticket", "OK"),
    ("latency-fast-burn", "LatencyFastBurn",
     "count((1 - ledger:sli_latency_50ms:1h) > 14.4 * 0.01 and (1 - ledger:sli_latency_50ms:5m) > 14.4 * 0.01)", 0, "2m", "page", "OK"),
    ("deadlocks", "Deadlocks", "sum(increase(postgresql_deadlocks_total[5m]))", 0, "0s", "warning", "OK"),
    ("pool-starved", "PoolStarved", "max(hikaricp_connections_pending)", 0, "1m", "warning", "OK"),
    ("replication-slot-lag", "ReplicationSlotLag", "max(pg_replication_slot_lag_bytes)", 1e9, "5m", "warning", "OK"),
]


def rule(uid, title, expr, threshold, for_, sev, nodata):
    return {
        "uid": uid, "title": title, "condition": "C", "for": for_, "noDataState": nodata, "execErrState": "Error",
        "labels": {"severity": sev}, "annotations": {"summary": f"{title}: {expr} > {threshold}"},
        "data": [
            {"refId": "A", "relativeTimeRange": {"from": 600, "to": 0}, "datasourceUid": "prometheus",
             "model": {"expr": expr, "instant": True, "refId": "A"}},
            {"refId": "B", "datasourceUid": "__expr__", "model": {"type": "reduce", "expression": "A", "reducer": "last", "refId": "B"}},
            {"refId": "C", "datasourceUid": "__expr__", "model": {
                "type": "threshold", "expression": "B", "refId": "C",
                "conditions": [{"evaluator": {"type": "gt", "params": [threshold]}}]}},
        ],
    }


alerts = {"apiVersion": 1, "groups": [{
    "orgId": 1, "name": "ledger", "folder": "PayFlow", "interval": "30s", "rules": [rule(*a) for a in ALERTS]}]}
out = HERE / "provisioning" / "alerting"
out.mkdir(parents=True, exist_ok=True)
(out / "ledger-alerts.yml").write_text(json.dumps(alerts, indent=1) + "\n")  # JSON is valid YAML
print("generated", len(ALERTS), "alerts and 5 dashboards")
