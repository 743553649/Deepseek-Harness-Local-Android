package app.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.dsh.mobile.service.EngineService

/**
 * 流体云状态岛（ColorOS 16 对 Android 16 live update 的叫法）。
 *
 * 只用标准 Android 16 接口，不接 OPPO 私有 SDK：`ProgressStyle` + 关键文字 +
 * `extras["android.requestPromotedOngoing"]=true` + 常驻，系统就会把它放进流体云。
 * 所需权限 `POST_PROMOTED_NOTIFICATIONS` 是 normal 权限（装机自动授予，targetSdk 28 也给）。
 *
 * 三层内容，优先级从高到低：
 *  1. Agent 显式上报（`island set/done` 命令 → AgentBridge /island）
 *  2. 引擎插件报的 agent 状态（思考中/执行中都算 running）
 *  3. 自动层：服务每 5 秒算一次的项目名 + 引擎状态
 *
 * 两个实测结论（探针 v1.2.27 在 ColorOS 16 / PKX110 上验过）：
 *  - 首次 notify 时系统常来不及打 promoted 标志（8% 时没有、51% 时才有）→ post 后补刷一帧。
 *  - 通知一旦不 ongoing 就从岛上掉下来 → 常驻一律 ongoing（含"✓ 完成"，按用户要求留到下一次任务）。
 */
object FluidCloud {

    private const val TAG = "FluidCloud"
    private const val CHANNEL_ID = "fluid_cloud"

    /**
     * 岛通知 id。**它同时就是 EngineService 的前台服务通知**（见 `EngineService.startAsForeground`）：
     * 前台服务通知由系统托管 —— 进程一被杀系统就撤掉它，不会留下点不掉的僵尸胶囊
     * （实测坑：普通通知 + ongoing 在 App 被杀后会一直挂在通知栏）。
     */
    const val NOTIF_ID = 4242

    /** 首帧补刷延迟：给系统留出打 promoted 标志的时间 */
    private const val REFRESH_DELAY_MS = 300L

    /**
     * Agent 上报层的过期时间：引擎空闲且这么久没有新的上报 → 撤掉上报层回到自动层。
     *
     * 为什么需要：Agent 忘了调 `island done` 时，岛上会**一直挂着「正在改 X · 60%」**
     * 直到下一次任务开始 —— 任务早就结束了，岛上却在说谎（真机风险，实测确认无自动回落）。
     * 「✓ 完成」不参与过期（按设计它要留到下一次任务开始）。
     */
    private const val AGENT_TTL_MS = 10 * 60 * 1000L

    /** 设置页开关所在的偏好文件与 key（SettingsPage 直接引用这两个常量，避免字面量重复） */
    const val PREFS_UI = "dsh_ui"
    const val KEY_ISLAND_ENABLED = "island_enabled"

    /** Android 16（API 36）才有 ProgressStyle / setShortCriticalText；更老的系统整块功能静默降级 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    /** 用户在设置页的开关（默认开）。关掉后回到普通前台通知，不再上岛。 */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY_ISLAND_ENABLED, true)

    /** 实际是否启用：系统支持 **且** 用户没关 */
    fun active(ctx: Context): Boolean = supported && enabled(ctx)

    /** 引擎插件上报的忙碌状态（思考中/执行中） */
    @Volatile private var engineBusy = false

    /** Agent 显式上报层：null = 未上报，自动层生效 */
    private var agentTitle: String? = null
    private var agentCritical: String? = null
    private var agentText: String? = null
    private var agentPercent: Int? = null

    /** Agent 层的最后更新时间（单调时钟）与"是否为完成态"，用于过期回落 */
    private var agentUpdatedAt = 0L
    private var agentIsDone = false

    /**
     * 用户"当前正在用的会话"（引擎侧插件在用户发消息 / Agent 状态变化时上报，v1.2.31）。
     *
     * 用途：**只影响自动层的项目名** —— 让岛上显示"你正在弄的那个项目"，
     * 而不是"最近有写入的项目"（后者在两个项目都活跃时会指错，用户实测）。
     * null = 还不知道，回落到 SessionWatcher 的启发式。三层优先级不受它影响。
     */
    @Volatile
    var activeSessionId: String? = null
        private set

    /** 会话变化时的回调（EngineService 注册：立刻用新项目名刷岛，不等 5 秒轮询） */
    @Volatile
    private var onSessionChanged: (() -> Unit)? = null

    fun setSessionListener(listener: (() -> Unit)?) {
        onSessionChanged = listener
    }

    /** 引擎侧插件上报：用户在某会话里发消息 / Agent 在跑 */
    fun setActiveSession(sessionId: String) {
        if (sessionId.isEmpty() || sessionId == activeSessionId) return
        activeSessionId = sessionId
        onSessionChanged?.invoke()
    }

    /** 自动层文案（项目名 + 状态词），由 EngineService 定期写入 */
    private var autoTitle: String = ""
    private var autoIdleWord: String = ""
    /** 忙碌时替换成这个词；null = 该状态不随忙碌切换（启动中 / 引擎异常） */
    private var autoBusyWord: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false

    /**
     * 引擎插件上报的 agent 状态（`"running"` / `"idle"`）。
     * running 是一次"新任务开始"的信号 → 清掉上一轮的 Agent 上报层
     * （这样「✓ 完成」能一直留到下一次任务真正开始，而不是永远挂着）。
     */
    fun onAgentStatus(ctx: Context, status: String) {
        val busy = status == "running"
        val wasBusy = engineBusy
        engineBusy = busy
        if (busy && !wasBusy) clearAgentLayer()
        render(ctx)
    }

    /**
     * 自动层：项目名 + 空闲词 + 忙碌词。
     * 忙碌词为 null 表示该状态不随忙碌切换（启动中 / 引擎异常）。
     * 忙碌词在本对象内拼，是为了让 agent/status 一到就立刻反映，而不是等下一次轮询。
     */
    fun setAuto(ctx: Context, title: String, idleWord: String, busyWord: String? = null) {
        if (title == autoTitle && idleWord == autoIdleWord && busyWord == autoBusyWord) return
        autoTitle = title
        autoIdleWord = idleWord
        autoBusyWord = busyWord
        render(ctx)
    }

    /** Agent 显式上报（`island set "正在改 X" 60`） */
    fun report(ctx: Context, title: String, text: String?, percent: Int?) {
        agentTitle = title
        agentText = text
        agentPercent = percent
        // 【v1.2.30 / I3】折叠态只显示 SmallIcon + shortCriticalText（title 只有展开态才看得到），
        // 所以动作名必须进 shortCriticalText，否则用户只看到一条光秃秃的进度线（真机实测）。
        // 文案格式按用户定稿的 A：`动作 空格 百分比`；没有百分比时只显示动作名本身。
        // ⚠️ 动作名与百分比都是**运行时数据**（前者来自 Agent 上报、后者来自进度值），
        // 这里只有一份格式模板，禁止把具体动作名/数字硬编码进代码或资源。
        agentCritical = percent?.let { ctx.getString(R.string.island_action_progress, title, it) } ?: title
        agentUpdatedAt = android.os.SystemClock.elapsedRealtime()
        agentIsDone = false
        render(ctx)
    }

    /** Agent 显式收尾（`island done`）→ 常驻「✓ 完成」直到下一次任务开始 */
    fun done(ctx: Context, text: String?) {
        agentTitle = ctx.getString(R.string.island_done)
        agentText = text
        agentPercent = null
        agentCritical = ctx.getString(R.string.island_done_short)
        agentUpdatedAt = android.os.SystemClock.elapsedRealtime()
        agentIsDone = true
        render(ctx)
    }

    /**
     * 过期回落（由 EngineService 的 5 秒轮询调用）：Agent 忘了 `island done` 时，
     * 岛上会一直挂着「正在改 X · 60%」直到下一次任务 —— 任务早结束了却在说谎（实测风险）。
     * 规则：引擎空闲 + 距上次上报超过 [ttlMs] → 撤掉上报层，回到自动层。
     * 「✓ 完成」按设计不参与过期（它要留到下一次任务开始）。
     */
    fun expireStaleAgent(ctx: Context, ttlMs: Long = AGENT_TTL_MS) {
        if (agentTitle == null || agentIsDone || engineBusy) return
        if (android.os.SystemClock.elapsedRealtime() - agentUpdatedAt < ttlMs) return
        Log.i(TAG, "agent layer stale > ${ttlMs / 60_000}min with engine idle; falling back to auto layer")
        clearAgentLayer()
        render(ctx)
    }

    fun clearAgent(ctx: Context) {
        clearAgentLayer()
        render(ctx)
    }

    /** 引擎停止 / App 退出：整条通知收掉 */
    fun hide(ctx: Context) {
        clearAgentLayer()
        engineBusy = false
        autoTitle = ""
        autoIdleWord = ""
        autoBusyWord = null
        handler.removeCallbacksAndMessages(null)
        refreshScheduled = false
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    /**
     * 供 `EngineService.startForeground` 用的首帧岛通知。
     *
     * 服务启动时自动层还没算过（要等 5 秒轮询），所以这里按设计文案先给「DSH · 启动中」。
     * 之后所有更新都走 [render] → `notify(NOTIF_ID)`，**同一个 id** 才能保持"前台服务通知"身份
     * （换成另一个 id 就退化成普通通知，进程被杀时不会被系统撤掉）。
     */
    fun foregroundNotification(ctx: Context): Notification {
        // startForeground 要求通知渠道**已存在**，否则这条通知根本不显示
        // （此时还没跑过任何一轮 render，post() 里的建渠道还没执行过）
        ensureChannel(ctx)
        // 【v1.2.30 修 / 真机实测】服务被"再启动"时必须复用**当前内容**，不能照贴写死的首帧
        // 「启动中」：MainActivity.onResume() 每次都调 EngineService.start()，于是用户每次切回
        // App，岛上就会闪一下「启动中」，直到下一次自动层轮询（最长 5 秒）才纠正回来
        // ——用户报"时不时出现启动中"，就是这里。真正冷启动时下列内容都还是空的，仍然显示「启动中」。
        val title = agentTitle
        return when {
            title != null ->
                build(ctx, title, agentCritical ?: ctx.getString(R.string.island_busy), agentText, agentPercent)
            autoTitle.isNotEmpty() ->
                build(ctx, autoTitle, if (engineBusy) autoBusyWord ?: autoIdleWord else autoIdleWord, null, null)
            else ->
                build(ctx, ctx.getString(R.string.island_dsh), ctx.getString(R.string.island_starting), null, null)
        }
    }

    /** 建渠道（幂等：同 id 重复创建是 no-op，不会覆盖用户改过的设置） */
    private fun ensureChannel(ctx: Context) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "流体云状态", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** 「退出」按钮：与 EngineService.exitCompletely() 同一个入口（停引擎 + 收岛 + 停服务） */
    private fun exitAction(ctx: Context): Notification.Action {
        val exitPending = PendingIntent.getService(
            ctx, 2,
            Intent(ctx, EngineService::class.java).setAction(EngineService.ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
            ctx.getString(R.string.notif_action_exit),
            exitPending,
        ).build()
    }

    private fun clearAgentLayer() {
        agentTitle = null
        agentCritical = null
        agentText = null
        agentPercent = null
        agentIsDone = false
    }

    /**
     * 渲染当前状态。
     *
     * @param allowRefresh 是否允许"补刷一帧"：补刷那一次必须传 false，
     *   否则补刷会再次安排补刷 → 每 300ms 无限刷通知（自激循环，实测于首次实现）。
     */
    private fun render(ctx: Context, allowRefresh: Boolean = true) {
        // active = 系统支持 + 设置页开关打开；关掉后这里不再动通知（服务改用普通前台通知）
        if (!active(ctx)) return
        val title = agentTitle
        val built = if (title != null) {
            post(ctx, title, agentCritical ?: ctx.getString(R.string.island_busy), agentText, agentPercent)
        } else {
            val word = if (engineBusy) autoBusyWord ?: autoIdleWord else autoIdleWord
            post(ctx, autoTitle.ifEmpty { ctx.getString(R.string.island_fallback) }, word, null, null)
        }
        if (!built || !allowRefresh || refreshScheduled) return
        // 补刷一帧：首帧常拿不到 promoted 标志（实测），补一次即可上岛
        refreshScheduled = true
        handler.postDelayed({
            refreshScheduled = false
            render(ctx, allowRefresh = false)
        }, REFRESH_DELAY_MS)
    }

    private fun post(ctx: Context, title: String, critical: String, text: String?, percent: Int?): Boolean {
        // Android 13+ 无通知权限时前台服务仍合法，但 notify 不会显示 —— 直接跳过
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "no notification permission; island skipped")
            return false
        }
        return runCatching {
            ensureChannel(ctx)
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, build(ctx, title, critical, text, percent))
        }.onFailure { Log.w(TAG, "post failed: ${it.message}") }.isSuccess
    }

    private fun build(
        ctx: Context,
        title: String,
        critical: String,
        text: String?,
        percent: Int?,
    ): Notification {
        val pending = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentIntent(pending)
            .setOngoing(true)
            // 用户明确要求：锁屏显示全部信息（含具体动作），不做脱敏
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // 岛通知同时是前台服务通知 → 通知栏里只有这一条常驻通知，
            // 所以必须自带「退出」按钮（Termux 同款交互），否则用户无处彻底停止引擎
            .addAction(exitAction(ctx))
        if (!text.isNullOrEmpty()) builder.setContentText(text)
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
            if (percent != null) style.setProgress(percent.coerceIn(0, 100))
            else style.setProgressIndeterminate(true)
            builder.setStyle(style).setShortCriticalText(critical)
        }
        val notification = builder.build()
        // SDK 没有公开的"请求上岛"接口（javap 实测），框架内部读的就是这个 extra；
        // NMS 的 fixNotification() 不会剥它。
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        return notification
    }
}
