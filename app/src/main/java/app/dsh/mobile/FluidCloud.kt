package app.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

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
    private const val NOTIF_ID = 4242

    /** 首帧补刷延迟：给系统留出打 promoted 标志的时间 */
    private const val REFRESH_DELAY_MS = 300L

    /** Android 16（API 36）才有 ProgressStyle / setShortCriticalText；更老的系统整块功能静默降级 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    /** 引擎插件上报的忙碌状态（思考中/执行中） */
    @Volatile private var engineBusy = false

    /** Agent 显式上报层：null = 未上报，自动层生效 */
    private var agentTitle: String? = null
    private var agentCritical: String? = null
    private var agentText: String? = null
    private var agentPercent: Int? = null

    /** 自动层文案（项目名 / 引擎状态），由 EngineService 定期写入 */
    private var autoTitle: String = ""
    private var autoCritical: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false

    fun isBusy(): Boolean = engineBusy

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

    /** 自动层：项目名 + 状态词（"就绪" / "工作中" / "启动中" / "引擎异常"） */
    fun setAuto(ctx: Context, title: String, critical: String) {
        if (title == autoTitle && critical == autoCritical) return
        autoTitle = title
        autoCritical = critical
        render(ctx)
    }

    /** Agent 显式上报（`island set "正在改 X" 60`） */
    fun report(ctx: Context, title: String, text: String?, percent: Int?) {
        agentTitle = title
        agentText = text
        agentPercent = percent
        agentCritical = percent?.let { "$it%" } ?: "工作中"
        render(ctx)
    }

    /** Agent 显式收尾（`island done`）→ 常驻「✓ 完成」直到下一次任务开始 */
    fun done(ctx: Context, text: String?) {
        agentTitle = "✓ 完成"
        agentText = text
        agentPercent = null
        agentCritical = "完成"
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
        autoCritical = ""
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    private fun clearAgentLayer() {
        agentTitle = null
        agentCritical = null
        agentText = null
        agentPercent = null
    }

    private fun render(ctx: Context) {
        if (!supported) return
        val title = agentTitle
        if (title != null) {
            post(ctx, title, agentCritical ?: "工作中", agentText, agentPercent)
        } else {
            post(
                ctx,
                autoTitle.ifEmpty { "DSH 就绪" },
                autoCritical,
                null,
                null,
            )
        }
    }

    private fun post(ctx: Context, title: String, critical: String, text: String?, percent: Int?) {
        // Android 13+ 无通知权限时前台服务仍合法，但 notify 不会显示 —— 直接跳过
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "no notification permission; island skipped")
            return
        }
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "流体云状态", NotificationManager.IMPORTANCE_LOW),
            )
            nm.notify(NOTIF_ID, build(ctx, title, critical, text, percent))
            // 补刷一帧：首帧常拿不到 promoted 标志（实测），补一次即可上岛
            if (!refreshScheduled) {
                refreshScheduled = true
                handler.postDelayed({
                    refreshScheduled = false
                    render(ctx)
                }, REFRESH_DELAY_MS)
            }
        }.onFailure { Log.w(TAG, "post failed: ${it.message}") }
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
