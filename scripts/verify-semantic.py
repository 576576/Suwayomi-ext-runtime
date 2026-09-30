#!/usr/bin/env python3
"""按「逐方法语义」比对两个 jar 里同名 class，用来证明一次重构没有改变行为。

用法: python scripts/verify-semantic.py <旧.jar> <新.jar>

只比对两个 jar 都有的条目；CRC 不同才深入，用 `javap -c -p` 反汇编后：
  1) 抹掉模块名与常量池索引（`#12` → `#N`）——这两样随构建环境漂移，与语义无关；
  2) 丢弃只含 `}` 的行 —— 类的收尾花括号会被归进最后一个方法的块，
     方法声明顺序一变归属就变，否则会误报「最后一个方法体不同」；
  3) 依次比 类头部/字段集合 → 成员集合 → 逐方法体。
仅成员声明顺序不同、方法体逐一相同的，单独归一类（不影响 JVM 语义）。

依赖 `javap`（JDK 25 自带）。
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

MODULES = [
    "suwayomi-jvm-sandbox.android-compat:config", "suwayomi-jvm-sandbox:android-compat:config",
    "suwayomi-jvm-sandbox:android-compat", "suwayomi-jvm-sandbox",
    "com.github.576576.suwayomi-ext-runtime:ext-runtime",
    "suwayomi-ext-runtime.ext-runtime.android-compat:config",
    "suwayomi-ext-runtime.ext-runtime:android-compat", "suwayomi-ext-runtime.ext-runtime",
]


def norm(text: str) -> str:
    for m in MODULES:
        text = text.replace(m, "<MODULE>")
    text = re.sub(r"#\d+", "#N", text)
    return "\n".join(x.rstrip() for x in text.splitlines() if x.strip())


def parse(text: str):
    """返回 (头部行集合, {方法签名: 方法体})。"""
    head, meth, cur = [], {}, None
    for ln in text.splitlines():
        if ln.strip() == "}":
            continue
        if re.match(r"^\s{2}[A-Za-z<>].*\(.*\);$", ln):
            if cur:
                meth[cur[0]] = "\n".join(cur[1:])
            cur = [ln.strip(), ln]
        elif cur is not None:
            cur.append(ln)
        else:
            head.append(ln)
    if cur:
        meth[cur[0]] = "\n".join(cur[1:])
    return set(head), meth


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    old, new = sys.argv[1], sys.argv[2]

    zo, zn = zipfile.ZipFile(old), zipfile.ZipFile(new)
    names = sorted(set(zo.namelist()) & set(zn.namelist()))
    diffs = [n for n in names
             if not n.endswith("/") and zo.getinfo(n).CRC != zn.getinfo(n).CRC]

    tmp = tempfile.mkdtemp()
    bad, order_only, identical = [], [], 0
    for n in diffs:
        if not n.endswith(".class"):
            bad.append((n, "非 class"))
            continue
        po, pn = os.path.join(tmp, "o.class"), os.path.join(tmp, "n.class")
        open(po, "wb").write(zo.read(n))
        open(pn, "wb").write(zn.read(n))
        a = subprocess.run(["javap", "-c", "-p", po], capture_output=True, text=True,
                           encoding="utf-8", errors="replace").stdout
        b = subprocess.run(["javap", "-c", "-p", pn], capture_output=True, text=True,
                           encoding="utf-8", errors="replace").stdout
        ha, ma = parse(norm(a))
        hb, mb = parse(norm(b))
        if ha != hb:
            bad.append((n, "类头部/字段不同"))
            continue
        if set(ma) != set(mb):
            bad.append((n, f"成员集合不同 仅旧={sorted(set(ma) - set(mb))[:3]} "
                           f"仅新={sorted(set(mb) - set(ma))[:3]}"))
            continue
        dm = [k for k in ma if ma[k] != mb[k]]
        if dm:
            bad.append((n, f"方法体不同 {dm[:3]}"))
            continue
        if list(ma) != list(mb):
            order_only.append(n)
        else:
            identical += 1

    print(f"CRC 不同的 class 条目: {len([x for x in diffs if x.endswith('.class')])}")
    print(f"  逐字节级等价（模块名/池索引归一化后）: {identical}")
    print(f"  仅成员声明顺序不同、方法体逐一相同  : {len(order_only)}")
    for x in order_only:
        print("    ~", x)
    print(f"  语义不一致: {len(bad)}")
    for n, why in bad:
        print("    !", n, "|", why)
    shutil.rmtree(tmp, ignore_errors=True)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
