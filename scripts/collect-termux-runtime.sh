#!/usr/bin/env bash
# collect-termux-runtime.sh —— 在 x86_64 Linux（如 GitHub Actions ubuntu runner）上
# 从 Termux 官方 apt 仓库收集 aarch64 Node.js 运行时闭包，产出可被 Android 端
# RuntimeInstaller 解压的 runtime.zip。
#
# 用法: ./collect-termux-runtime.sh <输出zip路径> [架构]（默认 aarch64）
#
# 设计说明：
# - 选择 Termux 仓库而非上游官方 Node 二进制：官方 linux-arm64 是 glibc 链接，
#   在 bionic 上无法直接运行；Termux 的 node 为 bionic 交叉编译，开箱即用。
# - 许可证：node (MIT) + 各依赖库（MIT/BSD/ISC/Zlib），允许再分发；
#   刻意不打包任何 GPL 工具链组件。
# - ⚠️ PKGS 清单基于 Termux 主仓库当前已知包名编写，仓库调整时按报错修正。
set -euo pipefail

OUT_ZIP="${1:?用法: $0 <输出zip路径> [架构]}"
ARCH="${2:-aarch64}"
case "$ARCH" in aarch64|x86_64) ;; *) echo "不支持的架构: $ARCH" >&2; exit 1;; esac
# 提前转为绝对路径：后面子 shell 会 cd 进 WORK，相对路径会写错位置
OUT_ZIP="$(realpath -m "$OUT_ZIP")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
ROOT="$WORK/root"

mkdir -p "$ROOT" "$WORK/lists/partial" "$WORK/cache/archives/partial"

# ---- 1. 配置 apt 源（仅 arm64 架构；trusted 免签仅限 CI 受控环境）----
cat > "$WORK/sources.list" <<EOF
deb [arch=${ARCH} trusted=yes] ${TERMUX_REPO} stable main
EOF
cat > "$WORK/apt.conf" <<EOF
Dir::Etc::sourcelist "${WORK}/sources.list";
Dir::State::lists "${WORK}/lists";
Dir::Cache "${WORK}/cache";
APT::Architecture "${ARCH}";
APT::Architectures {"${ARCH}"};
Acquire::AllowInsecureRepositories "true";
APT::Get::AllowUnauthenticated "true";
Acquire::Languages "none";
EOF

apt-get -c "$WORK/apt.conf" update

# ---- 2. 下载运行时依赖闭包 ----
# 包名已对照 termux-main binary-aarch64 Packages 索引逐个核实：
#   nodejs-lts 24.x Depends = libc++, openssl, c-ares, libicu, libsqlite, zlib
# bash 的依赖不能依赖 apt-get download 自动递归：bash -> readline -> ncurses，
# 另有 libiconv/termux-tools；ripgrep -> pcre2，全部显式列出以形成可审计闭包。
PKGS=(
  nodejs-lts          # node 本体（含 npm）
  bash readline ncurses libiconv termux-tools  # bash 执行闭包
  ripgrep pcre2       # Android/bionic 原生 rg 及其正则库
  openssl c-ares libicu libsqlite zlib libc++   # nodejs-lts 硬依赖闭包
  libuv brotli        # 静态链接兜底
  libandroid-support  # bionic 兼容层辅助
  ca-certificates     # HTTPS 根证书（dsh 调模型 API 必需）
)

# ---- 3. 解包合并 ----
# 注意：apt-get download 把 .deb 下到【当前目录】而非 Dir::Cache，
# 因此先 cd 进 WORK 再下载。
cd "$WORK"
apt-get -c "$WORK/apt.conf" download "${PKGS[@]}"

shopt -s nullglob
DEBS=("$WORK"/*.deb)
if [ ${#DEBS[@]} -eq 0 ]; then
  echo "错误：未下载到任何 .deb，请检查 PKGS 清单与 Termux 仓库可达性" >&2
  exit 1
fi
for deb in "${DEBS[@]}"; do
  dpkg-deb -x "$deb" "$ROOT"
done

# Termux deb 按【绝对路径】打包：文件位于 data/data/com.termux/files/usr/…
# （而不是 usr/）。定位真实 usr 后整体平铺为 zip 根 —— 与 Android 端
# EngineConfig 的 PATH(bin)/LD_LIBRARY_PATH(lib)/dshEntry(lib/node_modules)
# 以及 Termux 惯例 $PREFIX/etc/tls/cert.pem 证书路径完全对齐。
USR_DIR="$(find "$ROOT" -type d -path '*com.termux/files/usr' | head -n1)"
if [ -z "$USR_DIR" ]; then
  echo "错误：解包后未找到 com.termux/files/usr，Termux 打包布局可能已变更" >&2
  find "$ROOT" -maxdepth 6 -type d >&2 || true
  exit 1
fi
cp -a "$USR_DIR/." "$ROOT/"
rm -rf "$ROOT/data"

# ---- 3.5 集成 dsh 引擎（平台无关 JS 依赖闭包）----
# 用 runner 自带 npm 在 x64 环境解析整棵依赖树（JS 文件与运行架构无关）；
# --ignore-scripts 禁掉 postinstall，防Linux-x64 native 构建/二进制混入。
# 若未来引入真 native 依赖（如 better-sqlite3），需针对 bionic 十字编译，
# 到时按报错在此处做平台裁剪或替换实现。
# 锁定版本：与本地验证过的 runtime 完全一致。浮动 latest 曾撞上上游 0.1.5-rc.x
# 重构（补丁目标的源码形状变了 → 补丁断言失败 → CI 全挂）。
# 注意：当年 CI 全挂的原因【不是】"session-persistence-jsonl 被移出依赖树" ——
# 实测它仍在树里；真正原因是补丁期望的 import 字符串变了（新增 lstat）。
#
# 0.1.5-rc.1 的 Android 适配见下方 3.6 各补丁注释（4 处，其中 flock 与迁移硬链接
# 为本版新增，不处理会导致会话写不进磁盘）。会话格式 v0 → v3，老会话会走迁移链。
DSH_VERSION="${DSH_VERSION:-0.1.5-rc.1}"
mkdir -p "$WORK/bundle" && cd "$WORK/bundle"
printf '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"%s"}}' \
  "$DSH_VERSION" > package.json
# dsh 依赖闭包巨大，npm 理想树解析默认堆会 OOM（SIGABRT/134），放开到 5.5GB
export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=5632"
npm install --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error
mkdir -p "$ROOT/lib/node_modules"
cp -a "$WORK/bundle/node_modules/." "$ROOT/lib/node_modules/"

# 体积修剪：README/TS 类型/sourcemap 可安全删除；LICENSE 一律保留（分发合规）
find "$ROOT/lib/node_modules" \( -name '*.md' -o -name '*.map' \) -not -iname 'LICENSE*' -delete 2>/dev/null || true
find "$ROOT/lib/node_modules" -type f -name '*.d.ts' -delete 2>/dev/null || true

# ---- 3.6 Android (bionic) 兼容补丁 —— 唯一允许触碰上游的位置，逐条注明理由 ----
NM="$ROOT/lib/node_modules"

# ---- 3.6a D2: openPath/openTextFile Android 化（内联自包含，幂等）----
# 上游默认调原生 opener（Android 无）→ 点击文件引用报
# "path open failed: native path opener is unsupported on android"。
# 补丁脚本单一真源（与本地 build_runtime.py 同源），幂等可重跑。
if command -v python3 >/dev/null 2>&1; then
  SCRIPTS_DIR="$GITHUB_WORKSPACE/scripts"
  [ -d "$SCRIPTS_DIR" ] || SCRIPTS_DIR="$(cd "$(dirname "$0")/.." && pwd)/scripts"
  python3 "$SCRIPTS_DIR/patch-apiproxy.py" "$NM" || echo "WARN: apiproxy patch failed; openPath falls back to native opener"
fi

# ---- 3.6b D4: legacy WebView polyfill（Android 11 等，Object.hasOwn / .at 缺失致 WebUI 转圈）----
if command -v python3 >/dev/null 2>&1; then
  python3 "$SCRIPTS_DIR/patch-webview-polyfill.py" "$NM" || echo "WARN: webview polyfill patch failed"
fi

# [koffi] FFI 库：仅 glibc/x64 预编译，Android 无法加载。
#
# 历史上"惰性 Proxy 桩"就够用：上游只在模块顶层做类型注册，真实调用都发生在
# Windows 死代码里。**0.1.5 打破了这个前提**：上游新增 @deepseek-ai/dsh-win32-process，
# 它在【模块顶层】用 koffi 建 struct 并做 ABI 自检
# （STARTUPINFOW.size === 104、PROCESS_INFORMATION.size === 24），而该包被
# @deepseek-ai/dsh-subprocess-local 【静态 import】→ Android 上加载即执行
# → 惰性桩的 .size 是个函数 → 自检抛错：
#   STARTUPINFOW layout mismatch: koffi computed function () { return inertProxy(name); }, expected 104
#   → plugin tree failed to load → 引擎启动即崩。
#   真机症状极具迷惑性：HTTP 服务器先起来（前端短暂显示"引擎已就绪"），随后进程退出
#   → 网页打不开。详见 docs/PITFALLS.md G7。
#
# 所以桩必须能【真算出】LP64 布局。Windows x64 与 Android arm64 同为 LP64
# （指针 8 字节、8 字节对齐），算出的值与上游期望值真实一致 —— 这是真通过，
# 不是把校验绕过去（下面有 CI 自检，值不符就硬失败）。
# win32 路径在 Android 上仍是死代码；其余 FFI API 一律保持惰性。
K="$NM/koffi"
test -e "$K.orig" || mv "$K" "$K.orig"
mkdir -p "$K"
printf '%s\n' '{"name":"koffi","version":"0.0.0-android-inert","main":"index.js"}' > "$K/package.json"
cat > "$K/index.js" <<'JSEOF'
// Android inert koffi + 最小 LP64 布局计算。
// 惰性 Proxy 只保证"不抛错"，但上游 dsh-win32-process 在模块顶层校验 struct 的
// ABI 布局（STARTUPINFOW=104 / PROCESS_INFORMATION=24），惰性 Proxy 的 .size 是
// 函数，会让校验抛错。这里补上真实布局计算；真实 FFI 调用在 Android 上永不发生。
const PTR = 8;
const PRIM = {
  void: 0, bool: 1, char: 1, int8: 1, uint8: 1, uchar: 1,
  short: 2, int16: 2, uint16: 2, ushort: 2,
  int: 4, int32: 4, uint32: 4, uint: 4, long: 4, ulong: 4, float: 4,
  int64: 8, uint64: 8, longlong: 8, ulonglong: 8, double: 8, size_t: 8,
  str: 8, str16: 8, string: 8,
};
function sizeOfType(t) {
  if (typeof t === "string") {
    const s = PRIM[t];
    if (s === undefined) return { size: PTR, align: PTR };
    return { size: s, align: Math.min(Math.max(s, 1), PTR) };
  }
  if (Array.isArray(t)) {
    const e = sizeOfType(t[0]);
    return { size: e.size * (Number(t[1]) || 0), align: e.align };
  }
  if (t !== null && typeof t === "object" && typeof t.__size === "number") {
    return { size: t.__size, align: t.__align || PTR };
  }
  return { size: PTR, align: PTR };
}
function layoutStruct(fields) {
  let off = 0, maxAlign = 1;
  for (const t of Object.values(fields)) {
    const { size, align } = sizeOfType(t);
    const a = Math.max(align, 1);
    if (a > maxAlign) maxAlign = a;
    off = Math.ceil(off / a) * a + size;
  }
  return { size: Math.ceil(off / maxAlign) * maxAlign, align: maxAlign };
}
function makeInert(name) {
  const fn = function () { return inertProxy(name); };
  return fn;
}
function inertProxy(tag) {
  return new Proxy(makeInert(tag), {
    get(t, p) {
      if (p === "__esModule") return false;
      if (p === "then") return undefined;
      if (!t[p]) t[p] = makeInert(tag + "." + String(p));
      return t[p];
    },
    construct() { return {}; },
    apply() { return inertProxy(tag); },
  });
}
function typeObject(tag, size, align) {
  const real = { __size: size, __align: align, size, alignment: align, name: tag };
  return new Proxy(real, {
    get(t, p) {
      if (Object.prototype.hasOwnProperty.call(t, p)) return t[p];
      if (p === "__esModule") return false;
      if (p === "then") return undefined;
      return makeInert(tag + "." + String(p));
    },
  });
}
const real = {
  pointer: function () { return typeObject("pointer", PTR, PTR); },
  struct: function (name, fields) {
    const l = layoutStruct(fields || {});
    return typeObject(name, l.size, l.align);
  },
  array: function (t, n) {
    const e = sizeOfType(t);
    return typeObject("array", e.size * (Number(n) || 0), e.align);
  },
  alias: function (name, t) {
    const e = sizeOfType(t);
    return typeObject(name, e.size, e.align);
  },
  sizeof: function (t) { return sizeOfType(t).size; },
  alignof: function (t) { return sizeOfType(t).align; },
};
module.exports = new Proxy(makeInert("koffi"), {
  get(t, p) {
    if (Object.prototype.hasOwnProperty.call(real, p)) return real[p];
    if (p === "__esModule") return false;
    if (p === "then") return undefined;
    if (!t[p]) t[p] = makeInert("koffi." + String(p));
    return t[p];
  },
  construct() { return {}; },
  apply() { return inertProxy("koffi"); },
});
module.exports.default = module.exports;
JSEOF

# ---- koffi 桩的 ABI 布局自检（决定性；纯 JS，与平台无关，可在 x86_64 runner 上验）----
# 值必须等于上游 dsh-win32-process 的顶层期望值，否则真机启动即崩。
node -e '
const koffi = require(process.argv[1]);
const PVOID = koffi.pointer("void");
const SI = koffi.struct("DSH_STARTUPINFOW", {
  cb: "uint32", lpReserved: "str16", lpDesktop: "str16", lpTitle: "str16",
  dwX: "uint32", dwY: "uint32", dwXSize: "uint32", dwYSize: "uint32",
  dwXCountChars: "uint32", dwYCountChars: "uint32", dwFillAttribute: "uint32",
  dwFlags: "uint32", wShowWindow: "uint16", cbReserved2: "uint16",
  lpReserved2: koffi.pointer("uint8"), hStdInput: PVOID, hStdOutput: PVOID, hStdError: PVOID
});
const PI = koffi.struct("DSH_PROCESS_INFORMATION", {
  hProcess: PVOID, hThread: PVOID, dwProcessId: "uint32", dwThreadId: "uint32"
});
if (SI.size !== 104) { console.error("koffi 桩 ABI 错误: STARTUPINFOW.size=" + SI.size + ", 应为 104"); process.exit(1); }
if (PI.size !== 24) { console.error("koffi 桩 ABI 错误: PROCESS_INFORMATION.size=" + PI.size + ", 应为 24"); process.exit(1); }
console.log("koffi 桩 ABI 自检通过: STARTUPINFOW=" + SI.size + ", PROCESS_INFORMATION=" + PI.size);
' "$K" || exit 1

# 更强的一步：真把 dsh-win32-process 导入一次。它被 dsh-subprocess-local 静态 import，
# 导入失败就等于 Android 上引擎启动即崩 —— 这条断言直接在构建期挡住 G7 复发。
node --input-type=module -e '
const { pathToFileURL } = await import("node:url");
try {
  const m = await import(pathToFileURL(process.argv[1]).href);
  console.log("dsh-win32-process 导入成功，导出 " + Object.keys(m).length + " 个符号");
} catch (e) {
  console.error("dsh-win32-process 导入失败（真机会启动即崩）: " + e.message);
  process.exit(1);
}
' "$NM/@deepseek-ai/dsh-win32-process/lib/index.js" || exit 1

# [node-pty] 缺 android 平台 .node 预编译。App 层已有自研 libdshpty.so，
# M2 将桥接；在此桥接前提供 API 兼容空壳，真实调用时显式报错。
P="$NM/node-pty"
test -e "$P.orig" || mv "$P" "$P.orig"
mkdir -p "$P/lib"
printf '%s\n' '{"name":"node-pty","version":"0.0.0-android-shim","main":"lib/index.js"}' > "$P/package.json"
cat > "$P/lib/index.js" <<'JSEOF'
// Android shim until libdshpty.so bridge lands (roadmap M2).
module.exports.spawn = function () {
  throw new Error("node-pty unavailable in this Android build; PTY served by app-side libdshpty.so");
};
JSEOF

# [dsh-sandbox-local] 外科手术：仅摘除 Windows-only 的 dsh-sandbox-windows-acl import。
#
# ★ 关于 landlock（旧补丁 A，本版【整段删除】，别再按老写法加回来）：
#   0.1.5 上游把 node-addon-landlock-run 重组进了 @deepseek-ai/node-addon-system/landlock-run，
#   而新实现是【导入安全 + 诚实失败】的：纯 JS，导入时不 require 任何原生 .node，
#   launcherPath() 内部 catch 住解析失败，probe() 在拿不到二进制时返回 "unusable"
#   —— 自然走上游原版 fail-closed 路径（受限模式抛 SANDBOX_UNAVAILABLE，danger-full-access 放行）。
#   实测（android/arm64 真机 node v24.18.0）：导入成功，probe() === "unusable"。
#   老桩不但多余，还把 LAUNCHER_FAILURE_EXIT 写死成 126（上游现为 125），是错的常量。
#   若未来再引入"导入即 require 原生模块"的包，必须改回打桩 —— 下面的反向断言会拦住
#   "landlock import 消失"这种情况，避免静默变化。
#
# windows-acl 仍然摘除：其依赖链 (dsh-sandbox-windows-acl → dsh-win32-process → koffi)
# 在 Android 上全是 Windows 死代码；摘除后其四个绑定由下方桩提供，语义不变。
SL="$NM/@deepseek-ai/dsh-sandbox-local/lib/index.js"
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const ACL_RE = /^import\s*\{[^}]*\}\s*from\s*"@deepseek-ai\/dsh-sandbox-windows-acl";?\s*$/m;
if (!ACL_RE.test(s)) {
  console.error("sandbox-local patch failed: windows-acl import shape changed");
  process.exit(1);
}
s = s.replace(
  ACL_RE,
  `const AclWriteGrant = null;
const assertTempRootOutsideWorkspace = () => {};
const tempWriteSid = () => "";
const workspaceWriteSid = () => "";`
);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (/^import\s*\{[^}]*\}\s*from\s*"[^"]*dsh-sandbox-windows-acl/m.test(out)) {
  console.error("sandbox-local patch failed: windows-acl import still present");
  process.exit(1);
}
// 反向断言：landlock 必须以【上游原样】留在文件里。它一旦消失，说明上游又换了形状
// 或有人重新打了桩 —— 显式失败，好过静默地把错误常量带进运行时。
if (!/"@deepseek-ai\/node-addon-system\/landlock-run"/.test(out)) {
  console.error("sandbox-local patch failed: landlock import missing (上游形状变了，或有人重新打桩)");
  process.exit(1);
}
console.log("sandbox-local patched ok: windows-acl removed, landlock left upstream-pristine");
' "$SL"

# [dsh-session-persistence-jsonl] 本版共 4 处 Android 适配，逐条注明理由。
#
# (1) 首次会话落盘用 fs.promises.link(tmp, finalPath) 做原子发布。Android 沙箱内
#     硬链接不可用（EACCES，see docs/PITFALLS.md）；临时文件与目标同目录时 rename
#     具备同样的原子发布语义，且是 Android 允许的普通操作。
#     ⚠️ 本机实测（root 域 u:r:ksu:s0）link() 会成功，但那是 SELinux 域被污染的
#     无效实验 —— app 跑在 untrusted_app 域，setuid 不换域。故仍按仓库历史真机
#     结论走保守做法。
# (2) ★0.1.5 新增了【模块顶层】的 defaultFileSystem 字面量，其中 `link` 是简写属性
#     (`link,`)，在模块加载时即求值。因此 import 里的 link 【绝不能被删掉】：
#     老补丁的 newImport 会删 link，直接沿用 → 引擎启动即 ReferenceError: link is
#     not defined（本机实测复现）。同理 lstat 是新增引用（defaultFileSystem.lstat、
#     internals.fs.lstat），也必须保留。正解：link/lstat 都留，只新增 rename。
# (3) ★0.1.5 新增 flock 写锁：@deepseek-ai/node-addon-system/flock 只有 darwin/linux
#     平台包，android 上 tryLockExclusive() 抛 ERR_FLOCK_UNSUPPORTED_PLATFORM
#     （本机实测：flock is not supported on android-arm64）。它位于会话写入前的
#     ensureLease() 路径（690 行），且只有 EAGAIN/EWOULDBLOCK 被当作"被占用"转成
#     SessionAlreadyOwnedError，其他错误直接上抛 —— 不处理则【会话根本写不进磁盘】，
#     表现为"AI 无法对话"。按上游自己给浏览器 worker 的同款理由打桩为"立即成功"：
#     Android 应用内引擎是单进程，in-process write claim 已排除所有写入者。
# (4) ★0.1.5 迁移路径新增第二个硬链接点 publishCurrentExclusive → internals.fs.link
#     （旧版全文只有一处 link，没有这个点）。会话格式 SESSION_FORMAT_VERSION 由
#     v0 → v3，catalog 带 v0→v1→v2→v3 完整迁移链，用户既有老会话必定走迁移，
#     故该点必须同样 Android 化：用 lstat 预检 + rename 复刻 link(2) 的 no-overwrite
#     语义（目标已存在时抛 EEXIST，调用方据此转 published=false 走校验分支）。
#     仅对 process.platform === "android" 生效，其他平台行为完全不变。
SP="$NM/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js"
# 缺这个文件就是上游又重组了：必须硬失败。老版本这里是 WARN+跳过，
# 会静默产出一个没打补丁的 runtime（用户会拿到"装得上但写不进会话"的包）。
if [ ! -f "$SP" ]; then
  echo "错误：dsh-session-persistence-jsonl/lib/index.js 缺失（上游又重组了？）" >&2
  exit 1
fi
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");

// ---- (2) import：保留 link/lstat，只新增 rename ----
// oldImport = 0.1.5 上游的实际形状（相对 0.1.1 多了 lstat）；
// newImport = 在其基础上补 rename。两者差集只有"新增 rename"，link/lstat 都不动。
const oldImport = "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";";
const newImport = "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from \"node:fs/promises\";";
if (!s.includes(oldImport)) {
  console.error("session persistence patch failed: fs/promises import shape changed");
  process.exit(1);
}
s = s.replace(oldImport, newImport);

// ---- (1) 单次 link 调用 → rename ----
if ((s.match(/await link\(tmp, finalPath\);/g) || []).length !== 1) {
  console.error("session persistence patch failed: expected one link(tmp, finalPath) call");
  process.exit(1);
}
s = s.replace("await link(tmp, finalPath);", "await rename(tmp, finalPath);");

// ---- (3) flock 打桩（android 单进程）----
const flockImport = "import { tryLockExclusive } from \"@deepseek-ai/node-addon-system/flock\";";
if (!s.includes(flockImport)) {
  console.error("session persistence patch failed: flock import shape changed");
  process.exit(1);
}
s = s.replace(flockImport, [
  "/* Android: 该包只有 darwin/linux 平台包, tryLockExclusive() 在 android 抛",
  "   ERR_FLOCK_UNSUPPORTED_PLATFORM, 而它位于会话写入前的 ensureLease() 路径上。",
  "   应用内引擎是单进程, in-process write claim 已排除所有写入者 —— 与上游对",
  "   浏览器 worker 的处理同理由, 打桩为立即成功。 */",
  "const tryLockExclusive = async () => {};",
].join("\n"));

// ---- (4) 迁移路径第二个硬链接点 ----
// 只在 defaultFileSystem 字面量内部替换，避免误伤文件他处的 link 引用。
const dsoStart = s.indexOf("const defaultFileSystem = {");
if (dsoStart < 0) {
  console.error("session persistence patch failed: defaultFileSystem literal not found");
  process.exit(1);
}
const dsoEnd = s.indexOf("\n};", dsoStart);
if (dsoEnd < 0) {
  console.error("session persistence patch failed: defaultFileSystem literal unterminated");
  process.exit(1);
}
let dso = s.slice(dsoStart, dsoEnd);
if ((dso.match(/\n\tlink,\n/g) || []).length !== 1) {
  console.error("session persistence patch failed: defaultFileSystem link member shape changed");
  process.exit(1);
}
dso = dso.replace("\n\tlink,\n", "\n\tlink: process.platform === \"android\" ? androidLink : link,\n");
const androidLinkDef = [
  "/**",
  " * Android 复刻 link(2) 的 no-overwrite 发布语义: lstat 预检 + 同目录 rename。",
  " * 目标已存在时抛 EEXIST, 调用方 (publishCurrentExclusive) 据此返回 false。",
  " * 非 android 平台直接走原生 link, 行为完全不变。",
  " */",
  "const androidLink = async (existingPath, newPath) => {",
  "\tlet present = true;",
  "\ttry {",
  "\t\tawait lstat(newPath);",
  "\t} catch (error) {",
  "\t\tif (error?.code === \"ENOENT\") present = false;",
  "\t\telse throw error;",
  "\t}",
  "\tif (present) throw Object.assign(new Error(`EEXIST: file already exists, link '\''${existingPath}'\'' -> '\''${newPath}'\''`), {",
  "\t\tcode: \"EEXIST\",",
  "\t\terrno: -17,",
  "\t\tsyscall: \"link\",",
  "\t\tpath: newPath,",
  "\t\tdest: existingPath",
  "\t});",
  "\tawait rename(existingPath, newPath);",
  "};",
  "",
].join("\n");
s = s.slice(0, dsoStart) + androidLinkDef + dso + s.slice(dsoEnd);
fs.writeFileSync(p, s);

// ---------------- 断言（全绿才算补丁成立）----------------
const out = fs.readFileSync(p, "utf8");
const fail = (m) => { console.error("session persistence patch failed: " + m); process.exit(1); };
if (!out.includes(newImport)) fail("link/lstat 未被完整保留 (ReferenceError 风险)");
if (out.includes("await link(tmp, finalPath);")) fail("旧的 link 调用仍在");
if (!out.includes("await rename(tmp, finalPath);")) fail("rename 调用未安装");
if (out.includes("@deepseek-ai/node-addon-system/flock")) fail("flock import 未被摘除");
if (!out.includes("const tryLockExclusive = async () => {};")) fail("flock 桩未安装");
if (!out.includes("link: process.platform === \"android\" ? androidLink : link,")) fail("迁移路径 link 未被 Android 化");
if (!out.includes("const androidLink = async (existingPath, newPath) => {")) fail("androidLink 定义缺失");
// 反向断言：link/lstat/rename 三个绑定在 import 里必须同时存在。
for (const id of ["link", "lstat", "rename"]) {
  const re = new RegExp("^import \\{[^}]*\\b" + id + "\\b[^}]*\\} from \"node:fs/promises\";$", "m");
  if (!re.test(out)) fail("node:fs/promises 里缺少绑定: " + id);
}
console.log("session persistence patched ok: rename + flock stub + android link shim");
' "$SP"

# [@vscode/ripgrep] npm 在 Ubuntu runner 上会选择 linux-x64 optional binary，
# 不适用于 Android。m1.7 重构：不再对上游 index.js 做文本块替换（上游改版即碎，
# 曾先后在本地构建与 CI 两次翻车），改为【注入平台包】：
#   lib/node_modules/@vscode/ripgrep-android-<arch>/bin/rg  ← Termux bionic rg 副本
# 上游 resolver 在 android 平台 require.resolve("@vscode/ripgrep-android-arm64/bin/rg")
# 时沿 node_modules 目录查找天然命中，零代码补丁，对上游 shape 免疫。
NODE_ARCH=""
case "$ARCH" in aarch64) NODE_ARCH=arm64 ;; x86_64) NODE_ARCH=x64 ;; esac
RGP="$NM/@vscode/ripgrep-android-$NODE_ARCH"
mkdir -p "$RGP/bin"
cp "$ROOT/bin/rg" "$RGP/bin/rg"
chmod 0755 "$RGP/bin/rg"
printf '%s\n' "{\"name\":\"@vscode/ripgrep-android-$NODE_ARCH\",\"version\":\"1.0.0-android-native\"}" > "$RGP/package.json"
test -x "$RGP/bin/rg" || { echo "错误：ripgrep 平台包注入失败" >&2; exit 1; }
echo "ripgrep 平台包注入 ok: @vscode/ripgrep-android-$NODE_ARCH/bin/rg"

# [dsh-fs-local] Android SELinux 禁止普通 App 创建硬链接（link() → EACCES）。
# write 工位 createIfAbsent 的原子发布走 fs.promises.link → 真机 EACCES。
# Android 分支改为 lstat 预检 + rename 原子发布，保留原 FS_NOT_OBSERVED /
# FS_NOT_REGULAR_FILE 语义（同 build_runtime.py 真机验证过的实现）。
FSL="$NM/@deepseek-ai/dsh-fs-local/lib/index.js"
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const pat = /[ \t]*await linkFile\(tempPath, absolutePath\);/;
if (!pat.test(s)) {
  console.error("dsh-fs-local patch failed: linkFile call not found");
  process.exit(1);
}
const andr = [
"\t\t\tif (process.platform === \"android\") {",
"\t\t\t\t// Android SELinux forbids hard links (EACCES). Keep the",
"\t\t\t\t// no-overwrite-create semantics with an lstat guard + atomic rename.",
"\t\t\t\tlet existing;",
"\t\t\t\ttry {",
"\t\t\t\t\texisting = await inspectPublicationTarget(absolutePath);",
"\t\t\t\t} catch (metadataError) {",
"\t\t\t\t\tif (!isENOENT(metadataError) && !isENOTDIR(metadataError)) throw new FsError(`cannot write \"${createIfAbsent.displayPath}\": ${errorMessage(metadataError)}`, \"FS_IO_ERROR\", { cause: metadataError });",
"\t\t\t\t}",
"\t\t\t\tif (existing !== void 0) {",
"\t\t\t\t\tif (!existing.isFile()) throw new FsError(`cannot write \"${createIfAbsent.displayPath}\": not a regular file`, \"FS_NOT_REGULAR_FILE\");",
"\t\t\t\t\tthrow new FsError(`cannot overwrite existing \"${createIfAbsent.displayPath}\" without reading it first`, \"FS_NOT_OBSERVED\");",
"\t\t\t\t}",
"\t\t\t\tawait rename(tempPath, absolutePath);",
"\t\t\t} else {",
"\t\t\t\tawait linkFile(tempPath, absolutePath);",
"\t\t\t}",
].join("\n");
s = s.replace(pat, andr);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (!out.includes("await rename(tempPath, absolutePath);")) {
  console.error("dsh-fs-local patch failed: android branch not installed");
  process.exit(1);
}
console.log("dsh-fs-local patched ok: createIfAbsent -> lstat+rename");
' "$FSL"

# ---- 3.7 SONAME 别名副本（真机 m1.5 事故：bash 报 CANNOT LINK libreadline.so.8）----
# Android linker 按 NEEDED 里记录的 SONAME 精确文件名查找；deb 只带完整版本号
# 文件（如 .8.3）。Android SELinux 禁 symlink/link()，必须 cp 出普通文件别名。
copy_soname() {
  dst="$ROOT/lib/$2"
  [ -e "$dst" ] && return 0
  for s in "$ROOT"/lib/"$1"; do
    [ -f "$s" ] || continue
    cp -p "$s" "$dst"
    echo "soname alias $(basename "$s") -> $2"
    return 0
  done
  echo "错误：SONAME 别名 $2 无源文件（glob: lib/$1）" >&2
  exit 1
}
copy_soname 'libreadline.so.[0-9]*'  libreadline.so.8
copy_soname 'libhistory.so.[0-9]*'   libhistory.so.8
copy_soname 'libncursesw.so.[0-9]*'  libncursesw.so.6
copy_soname 'libncursesw.so.[0-9]*'  libncurses.so.6
copy_soname 'libncursesw.so.[0-9]*'  libncurses.so

# ---- 3.8 bin 工具 wrapper（真机 m1.6.7/8 验证版，与 build_runtime.py 对齐）----
# pnpm：corepack 的 shebang 指向 Termux 绝对路径且依赖 $PREFIX；
# wrapper 用 $(dirname "$0") 自推导 node 与 pnpm.js，环境无关。
# curl：系统 /system/bin/curl 链接旧 OpenSSL（缺 EVP_MD_CTX_CREATE）不可用；
# node fetch 垫片，sh 侧解析参数 + 环境变量传值（node 不接触原始 argv）。
if [ -f "$ROOT/lib/node_modules/corepack/dist/pnpm.js" ]; then
  cat > "$ROOT/bin/pnpm" <<'SHEOF'
#!/system/bin/sh
# Android corepack pnpm wrapper (shebang-safe, PATH-independent)
exec "$(dirname "$0")/node" "$(dirname "$0")/../lib/node_modules/corepack/dist/pnpm.js" "$@"
SHEOF
  chmod 0755 "$ROOT/bin/pnpm"
  echo "added bin/pnpm wrapper -> corepack dist pnpm.js"
else
  echo "错误：corepack/dist/pnpm.js 不存在，pnpm wrapper 未生成" >&2
  exit 1
fi

cat > "$ROOT/bin/curl" <<'SHEOF'
#!/system/bin/sh
# Android curl -> node fetch (system curl cannot link due to broken system OpenSSL).
# http(s) only, first bare arg = URL. Options: -s/-sS silent, -o FILE,
#   -X METHOD, -H "K: V" (repeatable), -d DATA, --max-time/-w accepted-and-ignored.
URL="" METHOD="" OUT="" SILENT="" DATA=""
HDRS=""
nextval=""
for word in "$@"; do
  if [ -n "$nextval" ]; then
    case "$nextval" in
      -o|--output) OUT="$word" ;;
      -X|--request) METHOD="$word" ;;
      -H|--header) HDRS="$HDRS$word\n" ;;
      -d|--data|--data-raw) DATA="$word" ;;
    esac
    nextval=""
    continue
  fi
  case "$word" in
    -o|--output|-X|--request|-H|--header|-d|--data|--data-raw|--max-time|-w) nextval="$word" ;;
    -s|-sS|-S|--silent) SILENT=1 ;;
    -*) : ;;
    *) if [ -z "$URL" ]; then URL="$word"; fi ;;
  esac
done
export CURL_URL="$URL" CURL_METHOD="$METHOD" CURL_OUT="$OUT" \
  CURL_SILENT="$SILENT" CURL_DATA="$DATA" CURL_HDRS="$HDRS"
exec "$(dirname "$0")/node" -e '
(async()=>{
  try{
    const url=process.env.CURL_URL.trim();
    const h={};
    // sh 双引号内 \n 是字面反斜杠+n，故 JS 侧也按字面 \\n 切分
    (process.env.CURL_HDRS||"").split("\\n").filter(Boolean).forEach(l=>{const c=l.indexOf(":");if(c>0)h[l.slice(0,c).trim()]=l.slice(c+1).trim()});
    const d=process.env.CURL_DATA||null;
    const m=(d&&!process.env.CURL_METHOD)?"POST":(process.env.CURL_METHOD||"GET");
    const r=await fetch(url,{method:m,headers:h,body:d||void 0});
    const out=process.env.CURL_OUT;
    if(out){require("fs").writeFileSync(out,await r.text())}else if(!process.env.CURL_SILENT){process.stdout.write(await r.text())}
    process.exit(r.ok?0:1);
  }catch(e){if(!process.env.CURL_SILENT)console.error(e.message);process.exit(2)}
})()
'
SHEOF
chmod 0755 "$ROOT/bin/curl"
echo "added bin/curl wrapper -> node fetch (env-passing)"

# usr/bin 必须真实存在（EngineConfig PATH 声明了它；空目录会被 zip 丢弃）
mkdir -p "$ROOT/usr/bin"
printf 'keep engine/usr/bin on PATH\n' > "$ROOT/usr/bin/.keep"

# 打包前闭包校验：防止 bash/rg 在 CI 产出后才于真机失败。
test -x "$ROOT/bin/bash" || { echo "错误：缺少可执行 bin/bash" >&2; exit 1; }
test -x "$ROOT/bin/rg" || { echo "错误：缺少可执行 bin/rg（Termux ripgrep）" >&2; exit 1; }
# SONAME 精确文件名（Android linker 按 NEEDED 逐字查找，模糊存在不算数）
for so in libreadline.so.8 libhistory.so.8 libncursesw.so.6 libncurses.so.6 \
          libiconv.so libpcre2-8.so; do
  test -e "$ROOT/lib/$so" || { echo "错误：SONAME 库 lib/$so 缺失" >&2; exit 1; }
done
# 工具 wrapper
test -x "$ROOT/bin/pnpm" || { echo "错误：bin/pnpm wrapper 缺失" >&2; exit 1; }
test -x "$ROOT/bin/curl" || { echo "错误：bin/curl wrapper 缺失" >&2; exit 1; }
test -e "$ROOT/usr/bin/.keep" || { echo "错误：usr/bin/.keep 缺失" >&2; exit 1; }
# ripgrep 平台包（android resolver 的 require.resolve 目标）
NODE_ARCH=""
case "$ARCH" in aarch64) NODE_ARCH=arm64 ;; x86_64) NODE_ARCH=x64 ;; esac
test -x "$NM/@vscode/ripgrep-android-$NODE_ARCH/bin/rg" || {
  echo "错误：ripgrep 平台包 @vscode/ripgrep-android-$NODE_ARCH/bin/rg 缺失" >&2; exit 1;
}
find "$ROOT" -type f -name 'libreadline.so*' -print -quit | grep -q . || {
  echo "错误：bash 依赖 libreadline.so* 未打包" >&2; exit 1;
}
# 删除 npm 根据 Ubuntu runner 拉入的宿主 Linux rg 二进制，保留 JS 解析器；
# 上面的 Android 分支会将 rgPath 指向 Termux 的 $ROOT/bin/rg。
find "$NM/@vscode" -maxdepth 1 -type d -name 'ripgrep-linux-*' -exec rm -rf {} + 2>/dev/null || true

# 打包前闭包校验：禁止宿主 Linux rg 残留，要求 Android 原生 rg 到位。
if find "$ROOT" -type f -path '*@vscode/ripgrep-linux-*/*/rg' -print -quit | grep -q .; then
  echo "错误：runtime 混入宿主 Linux ripgrep" >&2
  exit 1
fi

echo "Android 补丁完成：koffi/inert, node-pty/shim, sandbox-local/windows-acl-only, session-persistence/{rename,flock-stub,link-shim}, dsh-fs-local/rename, ripgrep/平台包注入, soname-aliases, pnpm+curl wrapper"
echo "dsh 引擎已集成：$(du -sh "$ROOT/lib/node_modules" | cut -f1)，样例 $(ls "$ROOT/lib/node_modules/@deepseek-ai" 2>/dev/null | head -n4 | tr '\n' ' ')"

# ---- 4. 精简：剔除文档/头文件/npm 冗余，控制体积 ----
rm -rf "$ROOT/share/man" "$ROOT/share/doc" "$ROOT/include" \
       "$ROOT/var/cache" "$ROOT/var/log" \
       "$ROOT/lib/node_modules/npm/docs" \
       "$ROOT/lib/node_modules/npm/man" \
       "$ROOT/lib/node_modules/npm/html" 2>/dev/null || true
find "$ROOT" \( -name "*.a" -o -name "*.map" \) -delete 2>/dev/null || true

# ---- 5. 打 zip ----
( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
