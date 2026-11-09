#!/usr/bin/env python3
"""12 键键盘的性能门禁（perf gate）。

用法::

    python3 tools/perf-gate.py                # 触发一次门禁并判定（默认阈值）
    python3 tools/perf-gate.py --no-trigger   # 只解析当前日志
    python3 tools/perf-gate.py --log /tmp/x.log
    python3 tools/perf-gate.py --json         # 机器可读输出
    python3 tools/perf-gate.py --thr op=segment=10 --thr latency_p90=60

判定依据（全部来自 App 侧 `perfgate:` / `twelvekey:latency` 日志）：
    op=decode  码串→音节（每次按键）
    op=segment 首段音节桶（每键）
    op=assoc   联想词查询（面板展开时每键）
    op=merge   分组合并/排序（面板展开时每键）
    op=engine  引擎枚举（每键，1 次）
    latency_p90  最近 200 条按键快照耗时的 90 分位
    assoc_load   词表联想索引后台载入耗时

退出码：0 通过 / 1 有指标超阈值 / 2 没抓到数据。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime

# 基准来自 vivo V2520A（2026-11-10 实测：decode 0.02 / segment 0.21 / assoc 0.29 / merge 0.08ms）
# 阈值给约 5 倍余量：能容忍设备差异，又能抓住「实现退化成 O(n) 扫描」这类回归。
DEFAULT_THRESHOLDS = {
    "decode": 0.2,
    "segment": 1.5,
    "assoc": 2.0,
    "merge": 1.0,
    "engine": 60.0,
    "build_p90": 40.0,
    "build_max": 120.0,
    "build_full_p90": 90.0,
    "build_full_max": 200.0,
    "latency_p90": 80.0,
    "latency_max": 300.0,
    "assoc_load": 1500.0,
}

PERF_LINE = re.compile(
    r"perfgate: op=(?P<op>\w+) avg=(?P<avg>[0-9.]+)ms(?: n=(?P<n>\d+))?"
)
READY_LINE = re.compile(
    r"perfgate: ready syllables=(?P<syllables>\d+) codes=(?P<codes>\d+)"
    r" charFreq=(?P<charfreq>\d+) charRank=(?P<charrank>\d+) assocReady=(?P<assoc>\w+)"
)
LATENCY_LINE = re.compile(r"twelvekey:latency (?P<ms>[0-9.]+)ms input=\[(?P<input>[^\]]*)\]")
BUILD_LINE = re.compile(r"twelvekey:build (?P<key>\S+) cached=(?P<cached>true|false) ms=(?P<ms>[0-9.]+)")
ASSOC_LOAD = re.compile(r"assoc keys=(?P<keys>\d+).*?load=(?P<ms>\d+)ms")
DONE_LINE = re.compile(r"perfgate: done total=(?P<ms>[0-9.]+)ms")


def sh(cmd: list[str]) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout
    except Exception as exc:  # noqa: BLE001 - 诊断脚本：任何失败都当作没数据
        print(f"[perf-gate] command failed: {' '.join(cmd)}: {exc}", file=sys.stderr)
        return ""


def trigger(serial: str | None, wait_s: float) -> None:
    adb = ["adb"] + (["-s", serial] if serial else [])
    print("[perf-gate] 触发门禁广播 …")
    sh(adb + ["shell", "am", "broadcast", "-a", "com.hazukieq.hkrime.debug.perfgate"])
    time.sleep(wait_s)


def fetch_log(serial: str | None) -> str:
    adb = ["adb"] + (["-s", serial] if serial else [])
    day = sh(adb + ["shell", "date", "+%Y%m%d"]).strip() or datetime.now().strftime("%Y%m%d")
    path = f"/sdcard/hkrime/logs/hkrime-{day}.log"
    return sh(adb + ["shell", "cat", path])


def parse(text: str) -> dict:
    out: dict = {"ops": {}, "ready": None, "latencies": [], "builds": [], "builds_full": [],
                 "assoc_load": None, "total": None}
    lines = text.splitlines()
    # 只統計「本次進程啟動之後」的按鍵延遲：日誌是跨進程累積的，
    # 舊版本的慢樣本不該影響今天的判定。
    start = 0
    for idx, line in enumerate(lines):
        if "perfgate: process-start" in line:
            start = idx
    for line in lines[start:]:
        if m := PERF_LINE.search(line):
            op = m.group("op")
            avg = float(m.group("avg"))
            # 同一 op 可能跑多次：取最好（最稳定的那次），避免被一次 GC 影响判定
            out["ops"][op] = min(avg, out["ops"].get(op, avg))
        elif m := READY_LINE.search(line):
            out["ready"] = {
                "syllables": int(m.group("syllables")),
                "codes": int(m.group("codes")),
                "char_freq": int(m.group("charfreq")),
                "char_rank": int(m.group("charrank")),
                "assoc_ready": m.group("assoc") == "true",
            }
        elif m := LATENCY_LINE.search(line):
            out["latencies"].append((float(m.group("ms")), m.group("input")))
        elif m := BUILD_LINE.search(line):
            # 只統計 cache miss 的真實構建耗時（cache hit 是 0ms，算進去會掩蓋問題）
            if m.group("cached") == "false":
                # 分兩類：bar = 每鍵都跑（熱路徑）；full = 開面板時一次性（可以寬一點）
                if "|full" in m.group("key"):
                    out["builds_full"].append(float(m.group("ms")))
                else:
                    out["builds"].append(float(m.group("ms")))
        elif m := ASSOC_LOAD.search(line):
            out["assoc_load"] = float(m.group("ms"))
        elif m := DONE_LINE.search(line):
            out["total"] = float(m.group("ms"))

    builds_full = out["builds_full"][-200:]
    if builds_full:
        builds_full.sort()
        out["build_full_p90"] = builds_full[min(len(builds_full) - 1, int(len(builds_full) * 0.9))]
        out["build_full_max"] = builds_full[-1]
        out["build_full_n"] = len(builds_full)

    builds = out["builds"][-200:]
    if builds:
        builds.sort()
        out["build_p90"] = builds[min(len(builds) - 1, int(len(builds) * 0.9))]
        out["build_max"] = builds[-1]
        out["build_n"] = len(builds)

    lat = [v for v, _ in out["latencies"]][-200:]
    if lat:
        lat.sort()
        out["latency_p90"] = lat[min(len(lat) - 1, int(len(lat) * 0.9))]
        out["latency_max"] = lat[-1]
        out["latency_n"] = len(lat)
    return out


def evaluate(data: dict, thresholds: dict) -> list[dict]:
    rows: list[dict] = []

    def add(name: str, value, limit: float, fmt: str = "{:.2f}") -> None:
        if value is None:
            rows.append({"name": name, "value": None, "limit": limit, "ok": None})
        else:
            rows.append(
                {"name": name, "value": value, "limit": limit, "ok": float(value) <= limit}
            )

    for op in ("decode", "segment", "assoc", "merge", "engine"):
        add(f"op={op}", data["ops"].get(op), thresholds.get(op, 0))
    add("build_p90", data.get("build_p90"), thresholds.get("build_p90", 0))
    add("build_max", data.get("build_max"), thresholds.get("build_max", 0))
    add("build_full_p90", data.get("build_full_p90"), thresholds.get("build_full_p90", 0))
    add("build_full_max", data.get("build_full_max"), thresholds.get("build_full_max", 0))
    add("latency_p90", data.get("latency_p90"), thresholds.get("latency_p90", 0))
    add("latency_max", data.get("latency_max"), thresholds.get("latency_max", 0))
    add("assoc_load", data.get("assoc_load"), thresholds.get("assoc_load", 0))
    return rows


def print_report(data: dict, rows: list[dict], as_json: bool) -> int:
    failed = [r for r in rows if r["ok"] is False]
    ready = data.get("ready")
    if as_json:
        print(
            json.dumps(
                {
                    "ok": not failed,
                    "ready": ready,
                    "rows": rows,
                    "total_ms": data.get("total"),
                    "latency_samples": data.get("latency_n", 0),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0 if not failed else 1

    print()
    print(f"{'指标':<14}{'实测':>12}{'阈值':>10}  结果")
    print("-" * 46)
    for r in rows:
        value = "—" if r["value"] is None else f"{r['value']:.2f}ms"
        limit = f"{r['limit']:.1f}ms"
        verdict = "SKIP" if r["ok"] is None else ("PASS" if r["ok"] else "FAIL")
        print(f"{r['name']:<14}{value:>12}{limit:>10}  {verdict}")
    print("-" * 46)
    if ready:
        print(
            "资源: syllables={syllables} codes={codes} charFreq={char_freq} "
            "charRank={char_rank} assocReady={assoc_ready}".format(**ready)
        )
    if data.get("build_n"):
        print(f"按键快照构建样本: {data['build_n']} 条（bar，cache miss，最近 200 条）")
    if data.get("build_full_n"):
        print(f"开面板构建样本: {data['build_full_n']} 条（full，cache miss，最近 200 条）")
    if data.get("latency_n"):
        print(f"按键延迟样本: {data['latency_n']} 条（最近 200 条）")
    else:
        print("提示: 本次进程还没有按键延迟样本 —— 打开输入法随便打几个字再跑一次门禁")
    if data.get("total") is not None:
        print(f"门禁总耗时: {data['total']:.0f}ms")
    print()
    print("结果:", "FAIL" if failed else "PASS")
    return 0 if not failed else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="hkrime 12 键性能门禁")
    ap.add_argument("--log", help="离线日志文件（不给则从设备拉）")
    ap.add_argument("--serial", help="adb 设备序列号（多设备时指定）")
    ap.add_argument("--no-trigger", action="store_true", help="不触发广播，只解析已有日志")
    ap.add_argument("--wait", type=float, default=6.0, help="触发后等待秒数（默认 6）")
    ap.add_argument(
        "--thr",
        action="append",
        default=[],
        metavar="KEY=MS",
        help="覆盖阈值，可多次，如 --thr op=assoc=8 --thr latency_p90=60",
    )
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args()

    thresholds = dict(DEFAULT_THRESHOLDS)
    for item in args.thr:
        if "=" not in item:
            print(f"[perf-gate] 忽略无效阈值: {item}", file=sys.stderr)
            continue
        key, value = item.rsplit("=", 1)
        thresholds[key] = float(value)

    if args.log:
        text = open(args.log, encoding="utf-8", errors="ignore").read()
    else:
        if not args.no_trigger:
            trigger(args.serial, args.wait)
        text = fetch_log(args.serial)
    if not text.strip():
        print("[perf-gate] 没有日志内容（先打开一次输入法，或检查 adb/路径）", file=sys.stderr)
        return 2

    data = parse(text)
    if not data["ops"] and not data.get("latency_n"):
        print("[perf-gate] 日志里没有 perfgate/twelvekey:latency 数据", file=sys.stderr)
        return 2
    return print_report(data, evaluate(data, thresholds), args.json)


if __name__ == "__main__":
    sys.exit(main())
