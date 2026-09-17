package app.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.dsh.mobile.DshApp
import app.dsh.mobile.FluidCloud
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.SessionWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引擎前台服务。
 *
 * 类型选择 specialUse（Android 14+ 生效）：dataSync 类型有 6 小时系统限时，
 * 长任务会被强杀；specialUse 无此限制且 sideload 分发无需 Play 审核豁免。
 */
class EngineService : Service() {

    private var stateJob: Job? = null
    private var islandJob: Job? = null
    private val stateScope by lazy { CoroutineScope(Dispatchers.Main) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏「退出」按钮：用户显式要求彻底停止（Termux 同款交互）
        if (intent?.action == ACTION_EXIT) {
            exitCompletely()
            return START_NOT_STICKY
        }

        val app = application as DshApp

        startAsForeground()

        // Android 13+ 通知权限需动态请求；无权限时前台服务仍合法，只是通知不可见
        if (Build.VERSION.SDK_INT >= 33) {
            MainActivity.maybeRequestNotificationPermission(this)
        }

        app.supervisor.start(app.appScope)

        // 流体云自动层（v1.2.27）：项目名 + 引擎状态 + agent 忙碌状态
        startIslandLoop(app)

        // 状态回写到常驻通知（仅 SDK<36 的老系统；有流体云时同一条通知由岛自己维护）
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect { updateNotification(it) }
            }
        }
        // 【v1.2.28】不再 START_STICKY：用户手动杀掉 App 后系统会立刻把它拉起来，
        // 表现就是「杀掉了但流体云胶囊和通知还在」（实测：kill 后 6 秒内进程复活、
        // 引擎重新 start、岛又贴回来）。用户想要的语义是「杀掉就停」，所以不自动复活，
        // 需要重启时由用户打开 App（MainActivity → EngineService.start）。
        return START_NOT_STICKY
    }

    /**
     * 从最近任务划掉 / 系统清理任务栈 → 视为用户显式退出（v1.2.28）。
     *
     * 不处理的话：进程虽被杀，但通知栏那条 ongoing 通知会留下（用户实测反馈）。
     * 这里与「退出」按钮走同一个出口，保证「图标没了 = 引擎停了 = 岛收了」三者一致。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        exitCompletely()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 流体云自动层：每 5 秒算一次，优先于「就绪/工作中」这类基线文案。
     *   - 引擎状态：启动中 / 引擎异常 / 就绪
     *   - 项目名：最近有会话写入的项目；5 分钟内有写入的项目多于 1 个时显示「N 个项目」
     *   - 忙碌：由引擎插件上报的 agent/status 决定（思考中、跑命令中都算忙）
     * Agent 显式上报（island set/done）会盖住这一层，见 FluidCloud。
     */
    private fun startIslandLoop(app: DshApp) {
        if (islandJob != null) return
        islandJob = stateScope.launch {
            val watcher = SessionWatcher(EngineConfig.dshHome(this@EngineService))
            while (true) {
                // 目录遍历放 IO 线程：主线程每 5 秒扫一次会话树会掉帧
                val snapshot = withContext(Dispatchers.IO) {
                    runCatching { watcher.snapshot() }.getOrNull()
                }
                val project = when {
                    snapshot == null -> "DSH"
                    snapshot.activeProjects > 1 -> "${snapshot.activeProjects} 个项目"
                    else -> snapshot.project ?: "DSH"
                }
                when (app.supervisor.state.value) {
                    // 忙碌词交给 FluidCloud 拼：agent/status 一到就立刻反映，不必等这轮轮询
                    is EngineSupervisor.State.Healthy ->
                        FluidCloud.setAuto(this@EngineService, project, "就绪", "工作中")
                    is EngineSupervisor.State.Backoff, is EngineSupervisor.State.Failed ->
                        FluidCloud.setAuto(this@EngineService, "DSH", "引擎异常")
                    else -> FluidCloud.setAuto(this@EngineService, "DSH", "启动中")
                }
                delay(ISLAND_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        islandJob?.cancel()
        islandJob = null
        stateScope.cancel()
        FluidCloud.hide(this)
        stopEngineAsync()
        super.onDestroy()
    }

    /**
     * 退出路径统一走这里停引擎。
     *
     * 【实测】引擎「优雅退出」要 **约 8 秒**（它在 flush 会话），而 `EngineSupervisor.stop()`
     * 内部会 `Thread.sleep` 等它死 —— 放在主线程必然 ANR（实测 ANR 堆栈：
     * `EngineService.onDestroy → EngineSupervisor.stop → EngineProcess.stop` 的 sleep，
     * 输入事件等 5 秒超时）。所以 TERM 发出后把「等待」挪到后台协程，UI 立即响应。
     *
     * 安全性：`EngineProcess.stop()` 第一件事就是发 SIGTERM（不是先 sleep），
     * 所以就算本进程随后被杀，引擎也已被通知退出，不会变成霸占 3180 的孤儿进程。
     */
    private fun stopEngineAsync() {
        val app = application as DshApp
        app.appScope.launch(Dispatchers.IO) {
            runCatching { app.supervisor.stop() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        // 流体云可用时，前台服务通知**就是岛通知**（同一个 id=4242）：
        // 前台服务通知由系统托管，进程被杀时自动撤掉 —— 普通通知做不到这点，
        // 会留下点不掉的僵尸胶囊（用户实测反馈）。老系统退回原有的引擎状态通知。
        val island = FluidCloud.supported
        val notification = if (island) FluidCloud.foregroundNotification(this)
        else buildNotification(getString(R.string.status_starting))
        val id = if (island) FluidCloud.NOTIF_ID else NOTIF_ID
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_engine_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private var lastNotifText: String = ""

    private fun buildNotification(text: String): Notification {
        // SINGLE_TOP：MainActivity 是 singleTask，复用已有实例走 onNewIntent，
        // 杜绝通知点击新建实例压出"双界面"（实测事故）
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Termux 式「退出」按钮：通知展开后可见（折叠态部分 ROM 精简 action）
        val exitPending = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val exitAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this, android.R.drawable.ic_menu_close_clear_cancel,
            ),
            getString(R.string.notif_action_exit),
            exitPending,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(exitAction)
            .build()
    }

    private fun updateNotification(state: EngineSupervisor.State) {
        // 有流体云时，通知栏里那条常驻通知就是岛本身，状态文案由岛自动层维护
        // （见 startIslandLoop）。这里只服务老系统，避免多出一条重复通知。
        if (FluidCloud.supported) return
        val text = when (state) {
            is EngineSupervisor.State.Healthy -> getString(R.string.status_healthy)
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> state.reason
            else -> return
        }
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    /** 彻底退出：杀引擎 → 移除通知 → 停服务（onDestroy 里的兜底清理幂等） */
    private fun exitCompletely() {
        stateJob?.cancel()
        stateJob = null
        islandJob?.cancel()
        islandJob = null
        // 顺序要紧：先撤前台服务通知（岛就是它），再 hide() 兜底。
        // 反过来会先 cancel 一条仍被前台服务持有的通知 —— 系统会忽略，岛就撤不掉了。
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        FluidCloud.hide(this)
        stopEngineAsync()
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 42

        /** 流体云自动层刷新间隔（用户选定：事件驱动 + 最低 5 秒节流） */
        private const val ISLAND_INTERVAL_MS = 5_000L

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
