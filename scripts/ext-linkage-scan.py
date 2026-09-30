#!/usr/bin/env python3
"""统计扩展真正会链接到的类型，算出 android-stub 里可以安全排除的部分。

用法:
  python scripts/ext-linkage-scan.py --apk-dir <APK 目录> --app-jar <fat.jar> --stub-jar <android-stub.jar>

三份输入各管一件事：
  --apk-dir   扩展 APK 目录。读 `classes*.dex` 的 `type_ids`（DEX 里所有类型引用都经这张表），
              得到**扩展侧**引用过的类。这是可达面的主要来源。
  --app-jar   最终 fat jar。其中不属于桩的那些类（android-compat / sandbox / eu.kanade …）
              也引用桩里的类，扫描它们的常量池补上**应用侧**引用。
  --stub-jar  桩本身。用来算「哪些类没人要」，以及**继承闭包**。

为什么需要继承闭包：JVM 在**加载**类时就要解析它的父类与接口（不像方法体那样惰性解析）。
所以一个类即使没人直接引用，只要它是某个可达类的父类/接口，就不能排除。
本脚本把可达集沿 `super`/`interfaces` 边扩张到不动点，扩张后的补集才是可排除集。

**这只证明「可达面」，不证明「安全」**：排除集落地后仍要按 `docs/agent/sandbox.md` 的台架跑
`ext_survey.py` + `ext_consistency.py`，确认扩展照旧加载、源数不变。排除集每扩大一次就重跑一次。
"""
import argparse
import json
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict

DEX_MAGIC = b"dex\n"
CP_SKIP = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
           15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
NAME_RE = re.compile(rb"(?:android|androidx|dalvik)/[\w/$]+")


# ---------------------------------------------------------------- DEX

def _uleb128(buf: bytes, off: int):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def normalize(descriptor: str):
    """`Landroid/view/View$Inner;` -> `android/view/View$Inner`；数组剥 `[`；原始类型返回 None。"""
    d = descriptor
    while d.startswith("["):
        d = d[1:]
    if len(d) > 2 and d.startswith("L") and d.endswith(";"):
        return d[1:-1]
    return None


def dex_types(buf: bytes):
    if not buf.startswith(DEX_MAGIC):
        return set()
    string_ids_size, string_ids_off = struct.unpack_from("<II", buf, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", buf, 64)
    out = set()
    for i in range(type_ids_size):
        (desc_idx,) = struct.unpack_from("<I", buf, type_ids_off + 4 * i)
        if desc_idx >= string_ids_size:
            continue
        (str_off,) = struct.unpack_from("<I", buf, string_ids_off + 4 * desc_idx)
        _, p = _uleb128(buf, str_off)
        name = normalize(buf[p:buf.index(b"\x00", p)].decode("utf-8", "replace"))
        if name:
            out.add(name)
    return out


def apk_types(path: str):
    found = set()
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if name.endswith(".dex"):
                found |= dex_types(zf.read(name))
    return found


# ------------------------------------------------------- Java class file

def _cp_utf8(buf: bytes, off: int, count: int):
    """解常量池，返回 (utf8 列表, 类名索引列表)。utf8[0] 恒为 None 占位。"""
    utf8 = [None] * count
    cls_idx = [0] * count
    p = off
    i = 1
    while i < count:
        tag = buf[p]
        p += 1
        if tag == 1:
            ln = struct.unpack_from(">H", buf, p)[0]
            p += 2
            utf8[i] = buf[p:p + ln].decode("utf-8", "replace")
            p += ln
        elif tag == 7:
            cls_idx[i] = struct.unpack_from(">H", buf, p)[0]
            p += 2
        else:
            p += CP_SKIP.get(tag, 0)
        i += 2 if tag in (5, 6) else 1
    return utf8, cls_idx


def class_parents(buf: bytes):
    """返回 (本类名, [父类名, 接口名...])，内部形式；失败返回 (None, [])。"""
    if len(buf) < 10 or buf[:4] != b"\xca\xfe\xba\xbe":
        return None, []
    cp_count = struct.unpack_from(">H", buf, 8)[0]
    utf8, cls_idx = _cp_utf8(buf, 10, cp_count)
    p = 10
    i = 1
    while i < cp_count:
        tag = buf[p]
        p += 1
        if tag == 1:
            p += 2 + struct.unpack_from(">H", buf, p)[0]
        else:
            p += CP_SKIP.get(tag, 0)
        i += 2 if tag in (5, 6) else 1

    def name_of(ci):
        u = cls_idx[ci] if ci < len(cls_idx) else 0
        return utf8[u] if 0 < u < len(utf8) else None

    this_ci, super_ci = struct.unpack_from(">HH", buf, p + 2)
    iface_n = struct.unpack_from(">H", buf, p + 6)[0]
    ifaces = [struct.unpack_from(">H", buf, p + 8 + 2 * k)[0] for k in range(iface_n)]
    parents = [x for x in ([name_of(super_ci)] + [name_of(c) for c in ifaces]) if x]
    return name_of(this_ci), parents


# ------------------------------------------------------------------ main

def pkg_of(class_name: str, depth: int) -> str:
    parts = class_name.split("/")
    if len(parts) <= 1:
        return "(默认包)"
    return "/".join(parts[: min(depth, len(parts) - 1)])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk-dir", required=True)
    ap.add_argument("--stub-jar", required=True)
    ap.add_argument("--app-jar", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--top", type=int, default=45)
    args = ap.parse_args()

    with zipfile.ZipFile(args.stub_jar) as zf:
        stub = {i.filename[:-6]: zf.read(i.filename)
                for i in zf.infolist() if i.filename.endswith(".class")}
    print(f"桩 {len(stub):,} 个类")

    # --- 扩展侧可达面
    apks = sorted(f for f in os.listdir(args.apk_dir) if f.endswith(".apk"))
    ext_reach = set()
    for i, f in enumerate(apks, 1):
        try:
            ext_reach |= apk_types(os.path.join(args.apk_dir, f))
        except Exception as e:  # noqa: BLE001
            print(f"  跳过 {f}: {type(e).__name__}: {e}", file=sys.stderr)
        if i % 300 == 0 or i == len(apks):
            print(f"  扩展 {i}/{len(apks)}，累计可达 {len(ext_reach):,}", flush=True)
    ext_in_stub = ext_reach & set(stub)
    print(f"扩展链接到的类 {len(ext_reach):,}，其中落在桩里的 {len(ext_in_stub):,}")

    # --- 应用侧可达面（fat jar 里不属于桩的那些类）
    app_in_stub = set()
    if args.app_jar:
        with zipfile.ZipFile(args.app_jar) as zf:
            names = [i.filename for i in zf.infolist()
                     if i.filename.endswith(".class") and i.filename[:-6] not in stub]
            for i, n in enumerate(names, 1):
                for m in NAME_RE.findall(zf.read(n)):
                    s = m.decode("utf-8", "replace")
                    if s in stub:
                        app_in_stub.add(s)
                if i % 5000 == 0:
                    print(f"  应用侧 {i}/{len(names)}，累计 {len(app_in_stub):,}", flush=True)
        print(f"应用侧（非桩类）链接到的桩内类 {len(app_in_stub):,}")

    # --- 继承闭包：JVM 加载类时就要解析父类/接口，不能只看到达面
    direct = ext_in_stub | app_in_stub
    parents = {}
    for cls in stub:
        _, par = class_parents(stub[cls])
        parents[cls] = par
    keep = set(direct)
    queue = list(direct)
    while queue:
        cur = queue.pop()
        for par in parents.get(cur, ()):
            if par in stub and par not in keep:
                keep.add(par)
                queue.append(par)
    added = len(keep) - len(direct)
    print(f"继承闭包补进 {added:,} 个父类/接口 -> 必须保留 {len(keep):,} 个类")

    dead = set(stub) - keep
    print(f"可排除 {len(dead):,} 个类 / "
          f"{sum(len(stub[c]) for c in dead)/1048576:.2f} MB（未压缩，含 class 文件开销）")

    rows = defaultdict(lambda: [0, 0, 0, 0])
    for cls, data in stub.items():
        key = pkg_of(cls, args.depth)
        rows[key][0] += 1
        rows[key][2] += len(data)
        if cls in dead:
            rows[key][1] += 1
            rows[key][3] += len(data)

    print(f"\n{'桩里的包':<40}{'类数':>7}{'可排除':>8}{'可省 KB':>10}  整包可排除?")
    print("-" * 78)
    for k, (total, d, _sz, dsz) in sorted(rows.items(), key=lambda kv: -kv[1][3]):
        if d == 0:
            continue
        whole = "是" if d == total else f"否（留 {total-d}）"
        print(f"{k:<40}{total:>7,}{d:>8,}{dsz/1024:>10.0f}  {whole}")
    whole_pkgs = [k for k, v in rows.items() if v[1] == v[0]]
    print("-" * 78)
    print(f"整包可排除 {len(whole_pkgs)} 个；部分可排除 {len([k for k,v in rows.items() if 0<v[1]<v[0]])} 个")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump({"extension_reachable_all": sorted(ext_reach),
                       "reachable_from_extensions": sorted(ext_in_stub),
                       "reachable_from_app": sorted(app_in_stub),
                       "must_keep": sorted(keep),
                       "excludable": sorted(dead),
                       "excludable_packages": sorted(whole_pkgs)},
                      fh, ensure_ascii=False, indent=0)
        print(f"\n写出 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
