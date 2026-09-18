package app.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
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

    /** 会话树扫描器（两个调用方共用，见 [applyIslandAuto]）；懒建，避免每次刷岛都重扫根目录 */
    private var islandWatcher: SessionWatcher? = null
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
        // 设置页改了「流体云开关」→ 只把前台通知换一种风格，**不重启引擎**
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            startAsForeground()
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
        // 用户切到别的会话 / Agent 开始干活 → 立刻用新项目名刷岛（不等 5 秒轮询，同 I2 的教训）
        FluidCloud.setSessionListener {
            stateScope.launch { applyIslandAuto(app) }
        }

        // 状态回写到常驻通知（仅 SDK<36 的老系统；有流体云时同一条通知由岛自己维护），
        // 并且【v1.2.30 / I2】状态一变就立刻刷岛 —— 岛上文案不能再等 5 秒轮询。
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect {
                    updateNotification(it)
                    applyIslandAuto(app)
                }
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
     *
     * ⚠️ 与「按返回键」区分开：返回键走 MainActivity.moveTaskToBack（只退到后台，
     * 任务仍留在最近任务里，本回调不触发），所以引擎与流体云继续常驻。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        exitCompletely()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 流体云自动层：算一次「项目名 + 引擎状态」写进岛。
     *   - 引擎状态：启动中 / 引擎异常 / 就绪
     *   - 项目名：最近有会话写入的项目；5 分钟内有写入的项目多于 1 个时显示「N 个项目」
     *   - 忙碌：由引擎插件上报的 agent/status 决定（思考中、跑命令中都算忙）
     * Agent 显式上报（island set/done）会盖住这一层，见 FluidCloud。
     *
     * 【v1.2.30 / I2】本函数有两个调用方：
     *  1. 监督器状态收集回调（每次状态变化）—— 引擎就绪的**瞬间**就刷岛；
     *  2. 5 秒轮询 —— 只是兜底（会话树变化、Agent 层过期回落这类状态之外的变化）。
     * 旧实现只有第 2 条，于是引擎明明已就绪，岛上还可能挂着「启动中」最多 5 秒（真机实测）。
     * `setAuto` 自带幂等去重（内容相同直接 return），重复调用无副作用。
     */
    private suspend fun applyIslandAuto(app: DshApp) {
        val watcher = islandWatcher ?: SessionWatcher(EngineConfig.dshHome(this)).also { islandWatcher = it }
        // 目录遍历 + 读工作区清单都放 IO 线程：主线程每 5 秒扫一次会话树会掉帧
        val activeSession = FluidCloud.activeSessionId
        val (snapshot, sessionProject) = withContext(Dispatchers.IO) {
            runCatching { watcher.snapshot() }.getOrNull() to
                activeSession?.let { id -> runCatching { watcher.projectOfSession(id) }.getOrNull() }
        }
        // 【v1.2.31】优先显示"用户当前在用的那个会话"的项目名（引擎侧插件上报会话 id，
        // 再查工作区清单翻译成标题）—— 实测只看"最近有写入"会在两个项目都活跃时指错。
        // 查不到（插件没报 / 清单读不了 / 没匹配）就回落到原来那套启发式。
        val project = sessionProject ?: when {
            snapshot == null -> getString(R.string.island_dsh)
            snapshot.activeProjects > 1 ->
                getString(R.string.island_projects, snapshot.activeProjects)
            else -> snapshot.project ?: getString(R.string.island_dsh)
        }
        when (app.supervisor.state.value) {
            // 忙碌词交给 FluidCloud 拼：agent/status 一到就立刻反映，不必等这轮轮询
            is EngineSupervisor.State.Healthy ->
                FluidCloud.setAuto(
                    this, project,
                    getString(R.string.island_ready), getString(R.string.island_busy),
                )
            is EngineSupervisor.State.Backoff, is EngineSupervisor.State.Failed ->
                FluidCloud.setAuto(
                    this, getString(R.string.island_dsh), getString(R.string.island_error),
                )
            // Installing / Starting：本身看不出"首次启动"还是"崩溃后重启"，
            // 而 Backoff 只存在 2 秒就被这两个状态覆盖 —— 真机上引擎反复崩，
            // 岛却一直说「启动中」，用户以为一切正常（实测）。连续失败 ≥2 次就报异常。
            else -> if (app.supervisor.lastBackoffAttempt >= 2) {
                FluidCloud.setAuto(
                    this, getString(R.string.island_dsh), getString(R.string.island_error),
                )
            } else {
                FluidCloud.setAuto(
                    this, getString(R.string.island_dsh), getString(R.string.island_starting),
                )
            }
        }
    }

    private fun startIslandLoop(app: DshApp) {
        if (islandJob != null) return
        islandJob = stateScope.launch {
            while (true) {
                applyIslandAuto(app)
                // Agent 忘了调 island done 时别让「60%」一直挂着（引擎空闲 + 10 分钟无更新 → 回自动层）
                FluidCloud.expireStaleAgent(this@EngineService)
                delay(ISLAND_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        islandJob?.cancel()
        islandJob = null
        FluidCloud.setSessionListener(null)
        stateScope.cancel()
        FluidCloud.hide(this)
        stopEngineAsync()
        super.onDestroy()
    }

    /**
     * 退出路径统一走这里停引擎。
     *
     * 【实测】引擎「优雅退出」要 **约 8 秒**（它在 flush 会话），而 `EngineSupervisor.stop()`
     * 会 `Thread.sleep` 等它死 —— 放在主线程必然 ANR（实测 ANR 堆栈：
     * `EngineService.onDestroy → EngineSupervisor.stop → EngineProcess.stop` 的 sleep，
     * 输入事件等 5 秒超时）。
     *
     * 状态变更与阻塞等待的分离、以及"不能整个 stop() 丢后台"的原因（会踩竞态），
     * 见 `EngineSupervisor.stopAsync` 的注释。
     */
    private fun stopEngineAsync() {
        val app = application as DshApp
        app.supervisor.stopAsync(app.appScope)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 是否用「岛通知」当前台服务通知（而不是老的引擎状态通知）。三个条件缺一不可：
     *  1. 系统支持（Android 16 / ColorOS 16 才有 ProgressStyle）
     *  2. 设置页开关是开的（用户要求可关；关掉后回到普通前台通知）
     *  3. 通知权限已授予 —— Android 13+ 无权限时 `notify()` 会被系统丢弃，
     *     岛内容会**永远停在首帧「DSH · 启动中」**（代码路径如此），这种宁可不上岛。
     */
    private fun useIslandNotification(): Boolean {
        if (!FluidCloud.active(this)) return false
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun startAsForeground() {
        // 流体云可用时，前台服务通知**就是岛通知**（同一个 id=4242）：
        // 前台服务通知由系统托管，进程被杀时自动撤掉 —— 普通通知做不到这点，
        // 会留下点不掉的僵尸胶囊（用户实测反馈）。其余情况退回原有的引擎状态通知。
        val island = useIslandNotification()
        val notification = if (island) FluidCloud.foregroundNotification(this)
        else buildNotification(getString(R.string.status_starting))
        val id = if (island) FluidCloud.NOTIF_ID else NOTIF_ID
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
        // 换风格时把另一条撤掉，别留两条常驻通知
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(if (island) NOTIF_ID else FluidCloud.NOTIF_ID)
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
        // 用岛通知时，常驻通知就是岛本身，文案由岛自动层维护（见 startIslandLoop）；
        // 这里只服务"没上岛"的情况（老系统 / 开关关闭 / 无通知权限），避免多出一条重复通知。
        if (useIslandNotification()) return
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

    /**
     * 彻底退出：停引擎 → 移除通知 → 停服务 → **连 App 进程一起杀掉**（v1.2.31 用户要求）。
     *
     * 用户原话：点「退出」要的是"退出 App"，而不是只把引擎停掉、App 还挂在后台。
     * 顺序要紧：先让引擎优雅停完（flush 会话，最多 15 秒）**再**杀进程 ——
     * 一上来就杀会让引擎来不及落盘（它只是被 PTY 断开带走的）。
     * 等待期间用户若又打开了 App，监督器会重新在跑，那时就不杀了（见回调里的判断）。
     */
    private fun exitCompletely() {
        stateJob?.cancel()
        stateJob = null
        islandJob?.cancel()
        islandJob = null
        FluidCloud.setSessionListener(null)
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
        val app = application as DshApp
        app.supervisor.onShutdownFinished(app.appScope) {
            // 期间用户又把 App 打开（监督器重新在跑）= 改主意了 → 不杀
            if (!app.supervisor.running) {
                Log.i(TAG, "engine stopped; killing app process (user asked for full exit)")
                Process.killProcess(Process.myPid())
            }
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL_ID = "engine"
        private const val NOTIF_ID = 42

        /** 流体云自动层刷新间隔（用户选定：事件驱动 + 最低 5 秒节流） */
        private const val ISLAND_INTERVAL_MS = 5_000L

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /** 设置页改了流体云开关后，让常驻通知换风格（不重启引擎） */
        const val ACTION_REFRESH_NOTIFICATION = "app.dsh.mobile.service.action.REFRESH_NOTIFICATION"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        /**
         * 流体云开关切换后调用。**只在服务已在跑时才发意图** —— 否则"改个开关"
         * 会把前台服务（进而引擎）一起拉起来，用户会莫名其妙看到引擎启动。
         * @return 是否已发出刷新意图
         */
        fun refreshNotificationIfRunning(context: Context): Boolean {
            val app = context.applicationContext as? DshApp ?: return false
            if (!app.supervisor.running) return false
            return runCatching {
                context.startService(
                    Intent(context, EngineService::class.java).setAction(ACTION_REFRESH_NOTIFICATION),
                )
                true
            }.getOrDefault(false)
        }
    }
}
