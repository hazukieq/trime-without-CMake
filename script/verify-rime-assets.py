#!/usr/bin/env python3
"""校验 app/src/main/assets/rime 资源自洽性（不需要设备、不需要 Android 构建）。

检查项：
  1. default.custom.yaml 的 schema_list 里每个 schema_id 都有对应的 *.schema.yaml；
  2. 每个 schema 的 translator / reverse_lookup 等 `dictionary:` 都有 *.dict.yaml；
  3. schema 中 engine.filters 引用的 opencc_config（*.json）都存在；
  4. opencc/*.json 里引用的词典文件存在；若引用的是 *.ocd2 而仓库里只有对应
     *.txt，则视为合法（O 首次部署时 OpenCCDictManager.internalDeploy() 会生成）；
  5. ywz / lenth 两个 OpenCC 词典的多值语义抽查（OpenCC 用空格分隔多个值，
     librime simplifier 会把它们全部作为候选，见 librime
     src/rime/gear/simplifier.cc -> Opencc::ConvertWord）。

用法：
    python3 script/verify-rime-assets.py [--assets <dir>]
退出码 0 = 全部通过。
"""
import argparse
import json
import os
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print("需要 PyYAML: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

OK = []
FAIL = []


def ok(msg):
    OK.append(msg)


def fail(msg):
    FAIL.append(msg)


def load_yaml(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "rime"))
    args = ap.parse_args()
    root = os.path.abspath(args.assets)
    opencc = os.path.join(root, "opencc")
    print(f"assets/rime = {root}")

    if not os.path.isdir(root):
        print(f"FAIL: 目录不存在: {root}")
        return 1

    # 1. schema_list
    custom = os.path.join(root, "default.custom.yaml")
    schema_ids = []
    if os.path.exists(custom):
        data = load_yaml(custom) or {}
        patch = data.get("patch") or {}
        for item in patch.get("schema_list") or []:
            sid = item.get("schema") if isinstance(item, dict) else item
            if sid:
                schema_ids.append(sid)
        ok(f"default.custom.yaml schema_list = {schema_ids}")
    else:
        fail("缺少 default.custom.yaml")

    for sid in schema_ids:
        if os.path.exists(os.path.join(root, f"{sid}.schema.yaml")):
            ok(f"schema {sid}: 有 {sid}.schema.yaml")
        else:
            fail(f"schema_list 里的 {sid} 缺少 {sid}.schema.yaml")

    # 2./3. 每个 schema 的 dictionary 与 opencc 滤镜
    schema_files = sorted(
        f for f in os.listdir(root) if f.endswith(".schema.yaml"))
    dicts_needed = set()
    jsons_needed = set()
    for fname in schema_files:
        data = load_yaml(os.path.join(root, fname)) or {}
        stack = [data]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                for k, v in node.items():
                    if k == "dictionary" and isinstance(v, str) and v:
                        dicts_needed.add(v)
                    if k == "opencc_config" and isinstance(v, str) and v:
                        jsons_needed.add(v)
                    stack.append(v)
            elif isinstance(node, list):
                stack.extend(node)
    for name in sorted(dicts_needed):
        if os.path.exists(os.path.join(root, f"{name}.dict.yaml")):
            ok(f"词典 {name}.dict.yaml 存在")
        else:
            fail(f"schema 引用词典 {name}.dict.yaml 不存在")
    for name in sorted(jsons_needed):
        if os.path.exists(os.path.join(opencc, name)):
            ok(f"OpenCC 配置 opencc/{name} 存在")
        else:
            fail(f"schema 引用 OpenCC 配置 opencc/{name} 不存在")

    # 4. opencc json 里引用的词典文件
    for fname in sorted(os.listdir(opencc)):
        if not fname.endswith(".json"):
            continue
        try:
            with open(os.path.join(opencc, fname), encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception as e:  # noqa: BLE001
            fail(f"opencc/{fname} 不是合法 JSON: {e}")
            continue
        refs = set()

        def walk(node):
            if isinstance(node, dict):
                if isinstance(node.get("file"), str):
                    refs.add(node["file"])
                for v in node.values():
                    walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        walk(cfg)
        for ref in sorted(refs):
            if os.path.exists(os.path.join(opencc, ref)):
                continue
            if ref.endswith(".ocd2"):
                base = ref[:-5]
                if os.path.exists(os.path.join(opencc, base + ".txt")):
                    ok(f"opencc/{fname}: {ref} 由部署时 {base}.txt 生成（可接受）")
                    continue
            fail(f"opencc/{fname} 引用 {ref}，但文件不存在")
    ok(f"opencc/*.json 引用检查完成（共 {len([f for f in os.listdir(opencc) if f.endswith('.json')])} 个配置）")

    # 5. ywz / lenth 多值语义抽查
    for name, samples in (
        ("ywz", {"不屑": "😒"}),
        ("lenth", {"qq": "QQ会员"}),
    ):
        path = os.path.join(opencc, f"{name}.txt")
        if not os.path.exists(path):
            fail(f"opencc/{name}.txt 不存在")
            continue
        entries = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    continue
                key, _, value = line.partition("\t")
                entries[key] = value.split(" ")
        multi = sum(1 for v in entries.values() if len(v) > 1)
        ok(f"opencc/{name}.txt: {len(entries)} 词条，其中 {multi} 个多值词条")
        for key, expect in samples.items():
            values = entries.get(key)
            if not values:
                fail(f"opencc/{name}.txt 缺少词条 {key}")
            elif expect not in values:
                fail(f"opencc/{name}.txt 词条 {key} 的候选里没有 {expect}: {values}")
            else:
                ok(f"opencc/{name}.txt: {key} -> {values}")

    print()
    for line in OK:
        print(f"  PASS  {line}")
    for line in FAIL:
        print(f"  FAIL  {line}")
    print()
    print(f"结果: {len(OK)} passed, {len(FAIL)} failed")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
