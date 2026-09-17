package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示"引擎运行于安全模式"。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康。webUrl = 引擎宣布的带认证 token 的 WebUI 入口（0.1.5+；旧引擎/未宣布为 null） */
        data class Healthy(val port: Int, val webUrl: String? = null) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(val port: Int, val webUrl: String? = null) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 运行时解压进度 0f..1f；null = 不在解压中（UI 据此切换 indeterminate） */
    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.DEFAULT_PORT
    }

    /**
     * 当前健康引擎宣布的 WebUI 入口（含认证 token）。
     * 0.1.5 起 WebUI 强制会话认证：WebView 必须用它加载（303 种 cookie），
     * 用裸 `/` 只会得到 401 黑屏。null = 旧引擎或未宣布，退回裸 URL。
     */
    val healthyWebUrl: String? get() = when (val s = _state.value) {
        is State.Healthy -> s.webUrl
        is State.SafeMode -> s.webUrl
        else -> null
    }

    private var process: EngineProcess? = null

    /**
     * 上一次「异步停引擎」的收尾工作：**`EngineProcess.stop()` 整段跑完**才算完成（v1.2.30，修 I1）。
     *
     * 为什么"等旧进程退出"不够（本轮最重要的坑，真机实测 + 日志对时确认）：
     * `EngineProcess.stop()` 里最后那次强杀用的是 `Pty.nativeForceKill()` / `Pty.nativeChildPid()` ——
     * 那是 **PTY 里"当前子进程"的全局句柄，不是本进程对象自己的 pid**。于是：
     *   ① 退出时引擎收到 TERM，约 2 秒就退出了（端口也放开了）；
     *   ② 但 `stop()` 的等待循环看的是 exitFuture，它迟迟不返回 → `stop()` 一路数到 10 秒超时；
     *   ③ 这 10 秒里用户重开了 App，新引擎已经起来并**成为"当前子进程"**；
     *   ④ 10 秒一到，那句强杀（以及 su 的进程组击杀）**打在了新引擎身上**。
     * 观测到的正是：新引擎启动成功 → 10.4 秒后被 SIGKILL（`engine exited, raw status=0x9`）→
     * 再拉起又撞端口。只等 exitFuture 完全挡不住这一段。
     *
     * 处置：监督循环在 spawn 之前等这一个 future —— 它由 `stop()` 返回时兑现，
     * 天然覆盖「TERM → 等待 → 强杀 → 进程组清理」全程，之后才允许 spawn 新引擎。
     */
    @Volatile
    private var pendingShutdown: CompletableFuture<Unit>? = null

    private var loopJob: Job? = null
    private var userStop = false
    private var scopeRef: CoroutineScope? = null
    private val guardian by lazy { ProfileGuardian(ctx) }

    /**
     * 最近一次退避重启的序号（0 = 本轮没失败过；健康后清零）。
     *
     * 给 UI 用：`Installing` / `Starting` 这两个状态本身看不出"是首次启动还是崩溃后重启"，
     * 而真机上崩溃重启时岛上会一直显示「启动中」（Backoff 只存在 2 秒就被覆盖），
     * 用户根本察觉不到引擎在反复崩。有了这个值就能区分并显示「引擎异常」。
     */
    @Volatile
    var lastBackoffAttempt: Int = 0
        private set

    /**
     * 监督代际：每次 start/stop 递增。
     *
     * 作用：让**被取代的旧监督循环**彻底闭嘴。`loopJob.cancel()` 只能取消挂起点，
     * 若旧循环正阻塞在 `proc.exitFuture.get()`（不可取消），它会一直等到引擎退出才返回，
     * 此时若已有一轮新 start()（`userStop` 已被置回 false），旧循环就会继续往下走 ——
     * 状态被它写回 Backoff、甚至再拉起一个引擎（真机竞态，v1.2.28 修）。
     */
    private var epoch = 0

    fun start(scope: CoroutineScope) {
        scopeRef = scope
        if (loopJob?.isActive == true) return
        val token = ++epoch
        userStop = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop(token) }
    }

    fun stop() {
        epoch++
        userStop = true
        loopJob?.cancel()
        process?.stop()
        process = null
        _state.value = State.Stopped
    }

    /**
     * 退出专用（v1.2.28）：**不为等引擎死而阻塞调用线程**（实测优雅退出要 8 秒，
     * 在 Service 回调里同步等会 ANR）。
     *
     * ⚠️ 关键设计：**状态变更与阻塞等待必须分离**。
     * `process = null` / `state = Stopped` 立即完成；后台协程只对**快照到的那个进程**
     * 做 TERM→等待→兜底 KILL，**不再碰任何共享状态**。
     *
     * 曾经写错成「整个 stop() 丢到后台线程」，结果踩到竞态（真机实测）：
     * 用户在 8 秒停止窗口内重新打开 App → 新的监督循环刚起来就被迟到的 `stop()`
     * 取消、状态被打回 `Stopped` → 引擎还在跑但没人监督，岛上永远停在「启动中」。
     *
     * 残留风险与兜底：旧引擎可能还占着端口，新循环启动时若撞上 EADDRINUSE，
     * 监督循环已有专门的处置（用 su 清残留 node 后重试，见 supervisionLoop 的
     * `deterministicFailure.contains("EADDRINUSE")` 分支）。
     *
     * 【v1.2.30 / I1】这个"残留风险"在真机上被复现了：用户在 8 秒窗口内重开 App，
     * 新引擎启动即被上一轮的强杀打掉、再拉起又撞端口。所以这里把这次停引擎的**收尾工作**
     * 记进 [pendingShutdown]，由监督循环在 spawn 之前等它跑完（详见该字段的注释）。
     *
     * ⚠️ `dying == null` 时**绝不能**覆盖 [pendingShutdown]：退出路径会调本方法两次 ——
     * `exitCompletely()` 一次（此时 process 还在），紧接着 `stopSelf()` → `onDestroy()` 再一次
     * （此时 process 已被上一句置空）。若第二次照写就把刚记下的收尾工作抹掉了，
     * 等待逻辑空转、原问题原样复现（v1.2.30 真机实测踩到，logcat 里连等待日志都没有）。
     */
    fun stopAsync(scope: CoroutineScope) {
        epoch++
        userStop = true
        loopJob?.cancel()
        loopJob = null
        val dying = process
        process = null
        if (dying != null) {
            val finished = CompletableFuture<Unit>()
            pendingShutdown = finished
            scope.launch(Dispatchers.IO) {
                try {
                    dying.stop()
                } finally {
                    finished.complete(Unit)
                }
            }
        }
        _state.value = State.Stopped
    }

    /**
     * 等上一次停引擎的收尾工作跑完（最多 [SHUTDOWN_WAIT_SECONDS] 秒）。
     *
     * **必须在后台线程调用**（IO 线程）：这段时间纯粹在等引擎退出与清理，
     * 放主线程就是 ANR —— 那正是 [stopAsync] 当初改成异步的原因。
     * 没有待退引擎时立即返回；超时也放行，交给原有的 EADDRINUSE 兜底处置。
     */
    private fun awaitPendingShutdown() {
        val pending = pendingShutdown
        pendingShutdown = null
        if (pending == null) {
            Log.i(TAG, "no previous engine shutdown pending; spawning immediately")
            return
        }
        if (pending.isDone) {
            Log.i(TAG, "previous engine shutdown already finished; spawning immediately")
            return
        }
        val startedAt = System.currentTimeMillis()
        runCatching { pending.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS) }
            .onSuccess {
                Log.i(
                    TAG,
                    "previous engine shutdown finished after ${System.currentTimeMillis() - startedAt}ms; spawning new one",
                )
            }
            .onFailure {
                Log.w(
                    TAG,
                    "previous engine shutdown still running after ${SHUTDOWN_WAIT_SECONDS}s; " +
                        "spawning anyway (EADDRINUSE fallback armed)",
                )
            }
    }

    /**
     * 热重启：完整走一遍 stop → start（TERM→KILL 优雅停止 + 监督循环重进）。
     * 与进程被杀后的自动退避不同，这是用户显式动作：退避计数天然从零开始，
     * guardian 的 Healthy 快照/计数不受影响。
     */
    fun restart() {
        val scope = scopeRef ?: return
        stop()
        start(scope)
    }

    /**
     * 监督循环是否在跑（≈ 常驻服务在跑）。
     * 设置页改流体云开关时用它判断要不要立刻刷新通知 —— 服务没跑就别发意图，否则会把引擎拉起来。
     */
    val running: Boolean get() = loopJob?.isActive == true

    /** 手动导出引擎日志（用户反馈通道） */
    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    /**
     * 引擎日志尾部原文（最近的 4KB）。
     * ⚠️ 与 [failureSignature] 分开：签名是**哈希**（给 guardian 做"同签名连续"判定用），
     * 而"是不是 EADDRINUSE"这类判定**必须看原文** —— 早期实现拿哈希去 `contains("EADDRINUSE")`，
     * 于是清孤儿的兜底是**死代码**，从来没生效过（真机实测：残留引擎霸占 3180 后反复 spawn 撞车）。
     */
    private fun logTailText(): String = runCatching { logFile().readText().takeLast(4096) }.getOrDefault("")

    /**
     * **本轮新增**的引擎日志（从 mark 偏移开始）。
     *
     * 为什么不能直接看尾部 4KB：日志是追加的，旧的一轮失败留下的 `EADDRINUSE` 会一直待在
     * 尾部窗口里 → 每一轮都被判定为"端口冲突"，于是反复 `pkill -9`、反复把退避清零（实测）。
     * 判定必须只看本轮。
     *
     * @param mark 本轮开始时的日志长度（见 supervisionLoop 里的 logMark）。
     *   注意日志泵会做 2MB 环形截断（重写文件）→ mark 失效时退回尾部窗口，宁可多判也不少判。
     */
    private fun logTextSince(mark: Long): String = runCatching {
        val f = logFile()
        val len = f.length()
        val from = if (mark in 0..len) mark else (len - 4096).coerceAtLeast(0)
        if (len <= from) return@runCatching ""
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val n = (len - from).coerceAtMost(128 * 1024).toInt()
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")

    /**
     * 失败签名：用「引擎日志尾部 + 退出码」的哈希近似。
     * 确定性崩溃（坏配置）每次堆栈一致 → 同签名；
     * 偶发崩溃（OOM/被杀）尾部随机 → 不同签名不累计。
     */
    private fun failureSignature(status: Int?): String {
        // 签名必须稳定：崩溃堆栈里的行号/内存地址/时间戳每次都变，原文哈希会让
        // streak 永远重置 → 永远到不了阶段阈值（自愈失效实测根因）。
        // 数字全部归一为 #，保留错误结构与字面（不同异常仍然不同签名）。
        val tail = runCatching {
            logTailText()
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n") { it.replace(Regex("\\d+"), "#") }
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    private suspend fun supervisionLoop(token: Int) {
        var backoffIndex = 0
        // token != epoch 表示这一轮监督已被 stop/restart 取代 → 立刻收工：
        // 绝不再写状态，更不再拉引擎（否则会出现"两个引擎抢 3180"）
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !userStop && token == epoch) {
            // 仅当引擎「自行死亡」（fork 失败 / 启动期退出）才值得让 guardian 定罪；
            // 健康超时自杀、安装异常、Healthy 后运行中退出都不算配置崩溃。
            var deterministicFailure: String? = null
            // 本轮引擎是否真的死了（启动期死 / 健康后退出都算）。EADDRINUSE 判定用它，
            // 避免拿陈年日志误判成"端口又冲突了"。
            var engineDied = false
            // 本轮开始的日志位置：EADDRINUSE 判定只看这之后新增的内容（否则陈年错误会反复触发）
            val logMark = runCatching { logFile().length() }.getOrDefault(0L)
            try {
                // 启动前先把被误隔离的引擎内置 patch 恢复（自愈；防 ENOENT fail-loud）
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")

                // Agent 上下文种子（m1.35）：幂等预写 $HOME/AGENTS.md（dsh 原生 user-global
                // 指令），让 Agent 首轮就带 Android 世界观，省掉环境探索 token
                withContext(Dispatchers.IO) { AgentContextSeed.ensure(ctx) }

                // Agent 能力桥（v1.1.0）：notify/scr 的 HTTP 后端，全模式启动
                withContext(Dispatchers.IO) { AgentBridge.start(ctx) }

                // Root 提权自愈（m1.27）：非 Root 模式启动前，若 dsh-home 被上次 Root 引擎
                // 污染成 root 属主（EACCES 读不了），chown 回 app uid，否则引擎必崩。
                if (Privilege.getMode(ctx) != PrivMode.ROOT) {
                    val needFix = withContext(Dispatchers.IO) { Privilege.dshHomeNeedsOwnershipFix(ctx) }
                    if (needFix) {
                        Log.w(TAG, "found dsh-home files owned by non-app uid (likely Root-mode residue); fixing ownership")
                        withContext(Dispatchers.IO) { Privilege.fixHomeOwnership(ctx) }
                    }
                }

                _state.value = State.Installing
                withContext(Dispatchers.IO) {
                    var lastPct = -1
                    RuntimeInstaller(ctx).ensureInstalled { frac ->
                        // 仅整 1% 变化时发布，避免 2 万次无效状态更新
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _installProgress.value = frac
                        }
                    }
                }
                _installProgress.value = null

                // 【v1.2.30 / I1】spawn 之前先等上一轮异步停掉、还没退干净的引擎（约 8 秒）。
                // 后台等，不阻塞主线程；超时放行，交给下面的 EADDRINUSE 兜底处置。
                withContext(Dispatchers.IO) { awaitPendingShutdown() }
                // 等待期间可能又发生过 stop/restart（token 过期）→ 本循环已不在任，立刻收工
                if (token != epoch) return

                _state.value = State.Starting
                val proc = withContext(Dispatchers.IO) { spawnEngine() }
                // ⚠️ 认领前先确认这轮监督还"在任"：`spawnEngine()` 是 IO 线程上的慢操作，
                // 期间若发生过 stop/restart（token 已过期），这个刚 fork 出来的引擎就没人管了。
                // 实测后果：它继续霸占 3180 → 新引擎全部 EADDRINUSE → 进程越攒越多。
                if (token != epoch) {
                    withContext(Dispatchers.IO) { runCatching { proc.stop() } }
                    return
                }
                process = proc

                val healthy = pollHealth(EngineConfig.DEFAULT_PORT, proc)
                if (healthy) {
                    withContext(Dispatchers.IO) {
                        guardian.resetCrashStreak()
                        guardian.snapshotLastGood()
                    }
                    backoffIndex = 0
                    lastBackoffAttempt = 0   // 健康 → 清零，重新开始数
                    val safe = guardian.inSafeMode()
                    // 0.1.5+：HTTP 监听先于 WebUI 就绪（裸 / 也能应答 401，健康检查
                    // 探到即过），带 token 的入口行要等插件树加载完才打印 ——
                    // 此处稍作等待拿它，拿不到（旧引擎/进程死）退回裸 URL。
                    val webUrl = awaitWebUrl(proc)
                    Log.i(TAG, if (safe) "engine healthy in SAFE MODE on :${EngineConfig.DEFAULT_PORT}" else "engine healthy on :${EngineConfig.DEFAULT_PORT}")
                    _state.value =
                        if (safe) State.SafeMode(EngineConfig.DEFAULT_PORT, webUrl)
                        else State.Healthy(EngineConfig.DEFAULT_PORT, webUrl)
                    // Shizuku 模式：引擎就绪后启动 ADB 级访问桥（shz 包装器回呼用）；其他模式自动关停
                    withContext(Dispatchers.IO) {
                        ShizukuHttpBridge.start(ctx, EngineConfig.DEFAULT_PORT)
                    }
                    // 阻塞等待进程退出（被杀/崩溃）。Healthy 后退出视为资源类偶发，
                    // 不计入 guardian（那是普通退避该管的事，与配置无关）。
                    // ⚠️ 这个 get() 不可取消：若期间发生过 stop/restart（token 已过期），
                    // 就在这里收工，别把状态写回 Backoff、更别又拉一个引擎起来。
                    val status = proc.exitFuture.get()
                    if (userStop || token != epoch) break
                    engineDied = true
                    Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                } else if (proc.exitFuture.isDone) {
                    // 启动期内进程自己死了——唯一进入自愈判定的信号
                    engineDied = true
                    deterministicFailure = failureSignature(lastExitStatus)
                } else {
                    // 健康检查超时：引擎没死是我们主动停的——很可能是慢启动，
                    // 绝不计入崩溃计数（m1.6.4 真机误伤教训）
                    proc.stop()
                }
            } catch (e: EngineStartException) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
                engineDied = true
                deterministicFailure = failureSignature(null)
            } catch (e: Exception) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
            }

            // ---- EADDRINUSE 处置：端口被**残留引擎**占着，先清掉再重试 ----
            // 【v1.2.22】Root 模式下上一实例的引擎可能没死透，霸占 3180 →
            // 新引擎 listen 失败即死 → 无限重启（页面/AI 看似正常，因为残留引擎在服务）。
            //
            // ⚠️ 判定必须看**日志原文**：`failureSignature` 返回的是哈希字符串
            // （"$status:${hashCode}"），早期直接拿它 `.contains("EADDRINUSE")` →
            // 这个兜底**从来没生效过**（真机实测：反复 spawn 撞车、引擎进程越攒越多）。
            if (engineDied && logTextSince(logMark).contains("EADDRINUSE")) {
                val su = Privilege.findSu()
                if (su != null) {
                    // 模式**按包名限定**：官方版与本版共存时，裸 'files/engine/bin/node'
                    // 会连同官方版的引擎一起杀掉 —— 那是在杀正在服务另一个会话的引擎。
                    val pattern = ctx.packageName + "/files/engine/bin/node"
                    runCatching {
                        ProcessBuilder(su, "-c", "pkill -9 -f '$pattern' 2>/dev/null; true")
                            .start().waitFor()
                    }
                    Log.w(TAG, "EADDRINUSE: killed orphan engine node(s) of ${ctx.packageName}")
                    deterministicFailure = null   // 已处置，不计入 guardian（与配置无关）
                    backoffIndex = 0
                }
            }
            deterministicFailure?.let { sig ->
                when (
                    runCatching { guardian.onFailure(sig) }
                        .getOrElse { ProfileGuardian.Action.NONE }
                ) {
                    ProfileGuardian.Action.ROLLED_BACK -> {
                        Log.w(TAG, "guardian: rolled back profiles to last-good")
                        backoffIndex = 0 // 给恢复后的启动全新的退避额度
                    }
                    ProfileGuardian.Action.SAFE_MODE -> {
                        Log.e(TAG, "guardian: verified persistent crash, entering SAFE MODE")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.NONE -> {}
                }
            }

            // 统一走退避重启
            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed("连续 ${EngineConfig.MAX_RESTART} 次启动失败，已停止自动重启")
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            lastBackoffAttempt = backoffIndex   // 供 UI 区分「首次启动」与「反复失败后重启」
            delay(delayMs)
        }
    }

    /** 最近一次子进程退出码；仅在进程已退出后可读，避免阻塞 */
    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    private fun spawnEngine(): EngineProcess {
        val mode = Privilege.getMode(ctx)
        var suPath: String? = null
        if (mode == PrivMode.ROOT) {
            // Root 保护壳：先备份用户资产 dsh-home，再以 su 整体提权启动引擎。
            // 即便 Root 引擎误改 dsh-home，用户仍可回滚；备份失败不阻断启动，仅告警。
            val backup = Privilege.backupHome(ctx)
            Log.w(TAG, if (backup != null) "ROOT mode: dsh-home backed up to $backup" 
                  else "ROOT mode: WARNING — dsh-home backup failed")
            suPath = Privilege.findSu() ?: throw EngineStartException(
                "Root 模式已选，但未找到可用的 su 可执行文件（设备可能未 root）"
            )
        }
        return EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.DEFAULT_PORT),
            logFile = logFile(),
            suPath = suPath,
            port = EngineConfig.DEFAULT_PORT,
            // v1.2.27：流体云插件补丁层（流体云需要知道"Agent 在思考/在执行"）
            patchFile = EngineConfig.fluidCloudPatch(ctx),
        )
    }

    /** 稳定窗：windowMs 内进程退出返回 false（启动失败），挺过窗口返回 true */
    private suspend fun awaitStable(proc: EngineProcess, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (proc.exitFuture.isDone) return false
            delay(250)
        }
        return !proc.exitFuture.isDone
    }

    /** 轮询 http://127.0.0.1:port 直到响应/超时/子进程提前死亡 */
    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + EngineConfig.HEALTH_TIMEOUT_MS
        val url = "http://127.0.0.1:$port/"
        while (System.currentTimeMillis() < deadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            // 子进程已死就别干等超时——立即返回走快速退避重试
            if (proc.exitFuture.isDone) return@withContext false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 2_000
                conn.readTimeout = 2_000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return@withContext true // Web UI 起来即算就绪
            } catch (_: Exception) {
                // 引擎尚未监听，继续等
            }
            delay(EngineConfig.HEALTH_INTERVAL_MS)
        }
        false
    }

    /**
     * 等待引擎宣布 WebUI 入口（`dsh web: <url>` 行）。
     * 健康检查探到端口时该行往往尚未打印（HTTP 服务器先于插件树就绪），
     * 等一小窗即可。进程提前死亡立即放弃（进入退避重试，等也白等）。
     * @return 带 token 的入口 URL；超时/旧引擎返回 null（调用方退回裸 URL，行为同旧版）
     */
    private suspend fun awaitWebUrl(proc: EngineProcess): String? {
        val deadline = System.currentTimeMillis() + WEB_URL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline &&
            !proc.webUrlFuture.isDone && !proc.exitFuture.isDone &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            delay(200)
        }
        return proc.webUrlFuture.takeIf { it.isDone }?.get()
    }

    companion object {
        private const val TAG = "EngineSupervisor"

        /** 引擎打印 "dsh web: <url>" 与健康检查通过之间的最大等待窗 */
        private const val WEB_URL_TIMEOUT_MS = 15_000L

        /**
         * spawn 之前等待上一次停引擎收尾的上限。
         * `EngineProcess.stop()` 的兜底是「TERM 后等 10 秒再强杀」，外加 su 的进程组清理，
         * 取 15 秒覆盖全程；超时就让原有的 EADDRINUSE 兜底接手。
         */
        private const val SHUTDOWN_WAIT_SECONDS = 15L
    }
}
