package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Agent 上下文种子（m1.35）：把「Android 环境说明书」预写入 `$DSH_HOME/AGENTS.md`。
 *
 * 动机：dsh Agent 冷启动到一个陌生沙箱时，会花大量 token 自行探索环境
 * （uname、which、ls /、试探 apt…），且探索结论常常错误（把 bionic 当 glibc、
 * 试图 apt install）。dsh-agent-instructions 原生发现 `$DSH_HOME/AGENTS.md`
 * （user-global 层，无条件注入每个会话），故在此预置一份高密度环境事实，
 * 让 Agent 从第一轮就带着正确世界观干活。
 *
 * 幂等策略（保护用户/AI 的手工编辑）：
 *  - 文件不存在               → 写入最新模板
 *  - 存在，含种子标记，版本旧  → 升级：全文覆盖
 *  - 存在，含种子标记，版本同  → 只同步随环境变化的几行（已激活扩展 / 特权模式 /
 *    shz 段落），用户追加的其它内容原样保留
 *  - 存在但无标记（用户自己写的）→ 绝不覆盖
 *
 * 【为何版本相同还要同步】v8 实测 bug：`- Currently activated:` 的值由
 * ExtensionManager 在渲染时拼接，若判据只比版本号，则首次写盘后无论装多少扩展
 * 该行都永远停在 `none yet`；特权模式同理。Agent 会读到过时事实（以为没装 JDK、
 * 以为自己没有 root），比"少一行信息"更有害 —— 它会据此做出错误决策。
 */
object AgentContextSeed {

    private const val TAG = "AgentContextSeed"
    private const val FILE_NAME = "AGENTS.md"
    private const val MARKER_PREFIX = "<!-- dsh-android AGENTS seed v"
    /** 当前模板版本：改文案必须同步递增，旧版才会被升级覆盖 */
    private const val SEED_VERSION = 10

    /** Extension Center 段落标题：既用于渲染，也作为 shz 行的插入锚点 */
    private const val EXT_CENTER_HEADING =
        "## Extension Center (on-demand runtimes & tools, China-direct)"

    /** shizuku 专属段落（单行）；渲染与就地同步共用同一份文案，避免两处漂移 */
    private val SHZ_LINE = """- shz: run `shz <command>` to execute a command as the adb identity (uid 2000) — process management (`shz "ps -A"`, `shz "am force-stop <pkg>"`), system properties, package queries. Only available in Shizuku mode."""

    /** 随环境变化的行：版本不变也要就地刷新 */
    // 注意 ${'$'} 与 \n：前者是正则的行尾锚点（写成 ${'$'} 以免被当成模板插值），
    // 后者在 raw string 里是字面反斜杠+n，由正则引擎解释为换行（不是 Kotlin 转义）。
    private val ACTIVE_LINE_RE = Regex("""(?m)^- Currently activated: .*${'$'}""")
    private val MODE_LINE_RE = Regex("""(?m)^## Privilege mode: `.*${'$'}""")
    /**
     * shz 段落行，连它前、后各一个换行一起匹配（插入串的严格逆运算）。
     * 从 shizuku 切走时必须删得干净：少删一个换行就会残留空行，
     * 且反复切换模式会不断累积空行（实测行数 50→52→51→53→52）。
     */
    private val SHZ_LINE_RE = Regex("""\n?- shz: run [^\n]*\n?""")

    fun ensure(ctx: Context) {
        val file = File(EngineConfig.dshHome(ctx), FILE_NAME)
        val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (existing != null && !existing.contains(MARKER_PREFIX)) {
            Log.i(TAG, "AGENTS.md exists without seed marker (user-authored); leaving untouched")
            return
        }
        if (existing == null || !containsVersion(existing, SEED_VERSION)) {
            writeSeed(file, render(ctx), "seeded")
            return
        }
        val synced = syncDynamicFields(existing, ctx)
        if (synced != existing) writeSeed(file, synced, "dynamic fields refreshed")
    }

    private fun writeSeed(file: File, text: String, what: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            Log.i(TAG, "AGENTS.md $what (v$SEED_VERSION, ${file.length()} bytes)")
        }.onFailure { Log.w(TAG, "seed AGENTS.md failed: ${it.message}") }
    }

    /**
     * 就地同步会随环境变化的那几行，其余内容（含用户/Agent 追加的笔记）保持原样。
     * 用 lambda 形式的 replace，避免替换串里的 $ 被当作正则组引用。
     */
    private fun syncDynamicFields(text: String, ctx: Context): String {
        val mode = Privilege.getMode(ctx).name.lowercase()
        val active = activeList(ctx)
        var out = text
            .replace(ACTIVE_LINE_RE) { "- Currently activated: $active." }
            .replace(MODE_LINE_RE) { "## Privilege mode: `$mode` (env DSH_ANDROID_PRIV_MODE)" }
        out = if (mode == "shizuku") {
            // 切到 shizuku：补上 shz 指引。空行数与 render() 的 $shz 占位保持一致，
            // 否则"首次渲染"与"后续同步"产出的排版会不同
            if (SHZ_LINE_RE.containsMatchIn(out)) out
            else out.replaceFirst(EXT_CENTER_HEADING, "$SHZ_LINE\n\n$EXT_CENTER_HEADING")
        } else {
            // 切离 shizuku：该段落已不适用，移除以免误导
            out.replace(SHZ_LINE_RE, "")
        }
        return out
    }

    private fun activeList(ctx: Context): String =
        ExtensionManager.activeRoots(ctx).joinToString(", ") { it.name }.ifEmpty { "none yet" }

    private fun containsVersion(text: String, v: Int): Boolean =
        text.contains("$MARKER_PREFIX$v ")

    private fun render(ctx: Context): String {
        val mode = Privilege.getMode(ctx).name.lowercase()
        val shz = if (mode == "shizuku") "\n$SHZ_LINE\n" else ""
        val active = activeList(ctx)
        return """$MARKER_PREFIX$SEED_VERSION — managed by DSH Mobile. Edits below this line are preserved until the app upgrades this seed.
# Environment: Android (read this first — do not re-explore)

You are running inside the DSH Mobile app on Android. This file is a complete, verified map of your host — trust it and start the user's task immediately. Do NOT probe the environment: skip `uname`, `cat /etc/os-release`, `which -a`, `ls /`, `apt-get`, and any "let me see what's available" sweeps entirely; every fact you could discover is already below.

## Platform facts
- Kernel: Linux (Android). libc is **bionic**, NOT glibc/musl. Userland is a Termux-built toolchain.
- There is **no**: systemd, sudo-as-you-know-it, apt/dpkg, xdg-open, X11/Wayland, Python (unless you install it), gcc/clang toolchain.
- SELinux is enforcing: writes outside the app sandbox and `link()` syscalls are denied. Atomic publish = write temp + `rename()`.
- System utilities live in `/system/bin` (toybox: ls/cp/mv/rm/grep/find/sed/tar/ps with limited flags). Our toolchain lives in `bin/` and takes PATH precedence.

## Working environment (already mapped — do not re-explore)
- Your cwd starts at `${'$'}HOME` (the app's dsh-home). Inside: `profiles/` (config), `sessions/` (conversation data), `storages/` (tool state). Your work products belong here too.
- The Node runtime and its npm packages (`lib/node_modules`) live in the sibling engine directory — `which node` shows the path if you ever need it; you never need to go there directly.
- Beyond `${'$'}HOME` and the engine directory there is nothing to discover: the rest of the filesystem is the read-only Android system image (`/system`, `/vendor`, `/data` of other apps is inaccessible). Exploring it yields nothing useful.

## Toolchain (preinstalled, on PATH)
- `node` — the engine itself is node; same binary for your scripts.
- `bash` — bionic build (readline/ncurses closure complete); POSIX-ish, no bash-specific loadables from /system.
- `rg` — ripgrep, native arm64. Use it for search; faster than `grep -r` on toybox.
- `pnpm` / `corepack` — package management works offline via the bundled corepack shim.
- `curl` — binary-safe wrapper around node fetch: -s/-sS/-I/--json/-L/-X/-H*/-d/-o/--max-time; `-o` writes raw bytes (safe for binaries).
- `psx <pattern>` / `killx <pattern>` — list/kill processes matched **by command NAME (comm) only**. **NEVER use `pkill -f` or `ps | grep <full-cmdline>` + kill**: your own bash -c / node -e command line contains the pattern and kills itself (SIGKILL / no output).
$shz
$EXT_CENTER_HEADING
- Python / Git / OpenJDK-17 / Clang / Go / Rust / Ruby / PHP / Lua / Perl / FFmpeg / ImageMagick / OpenSSH / adb / aapt+apksigner+gradle / vim are **NOT preinstalled but installable on demand** from Termux mirrors — no GitHub dependency, China-direct fast.
- Check what exists: `curl -s http://127.0.0.1:${EngineConfig.AGENT_BRIDGE_PORT}/ext/list` → JSON array of {id, name, category, state, version, installing}; state: red=not installed, yellow=installed but inactive, green=activated. Always check here before claiming a tool is missing.
- Install on demand: `curl -s -X POST http://127.0.0.1:${EngineConfig.AGENT_BRIDGE_PORT}/ext/install -d '{"id":"python"}'` → HTTP 202 started (200 = already green, 409 = installing). The app resolves the full dependency closure, verifies SHA-256, installs, activates and pushes a system notification when done. Poll /ext/list until state=green. Reinstall a broken/legacy-layout extension with `force:true` (wipes that extension dir, incl. anything hand-installed inside it), then remind the user to restart the engine.
- After a fresh activation, new binaries enter PATH only after an engine restart — remind the user to tap 设置 → 重启引擎.
- Currently activated: $active.
## Privilege mode: `$mode` (env DSH_ANDROID_PRIV_MODE)
- normal: sandboxed app uid. Everything under ${'$'}HOME works; system-level changes are impossible by design.
- shizuku: same sandbox + the `shz` bridge above.
- root: the engine itself runs as uid 0 — full device access, but stay inside ${'$'}HOME unless the user asks otherwise; breaking the host breaks your own workspace.

## GUI / preview
There is no display server. To show the user anything visual, start a web server **with node** — the built-in `node:http` module or a pure-JS framework installed via `pnpm` — bound to a loopback port (e.g. `node server.js` listening on 127.0.0.1:3000), then reply with the plain URL `http://127.0.0.1:<port>`; the app's WebView opens it as a live preview when the user taps it. Never reach for `python -m http.server` or other interpreters' servers — there is no Python/PHP/busybox httpd here; **node is the only first-class server runtime**.

## Notifications & screen control (Android powers, use them)
- `notify <message>` — push an Android system notification. **You MUST call this when a long task finishes** (or when you need the user's attention while they may be away): `notify 构建完成，测试全部通过`.
- `scr dump` — read the current phone screen: JSON of visible texts with coordinates and clickability. Requires the user to have enabled the accessibility service in system settings (returns an error otherwise).
- `scr tap <x> <y>` — tap the phone screen at pixel coordinates.
- `scr tap-text <text>` — find a node containing that text and tap it.
Typical flow: `scr dump` → pick a target → `scr tap-text "允许"`. Use it to operate other apps when the user asks you to automate something on the phone.

## Working agreements
- Start the user's task now. This file has already answered "where am I".
- Never install glibc/native binaries (npm rebuild, prebuilt .so for linux-x64) — they cannot run here; prefer pure-JS packages or the bundled tools.
- Need a language/tool you don't see on PATH? Check `/ext/list` and install via `/ext/install` (see Extension Center above). Only if neither the list nor PATH has it, say so plainly instead of improvising package installs.
"""
    }
}
