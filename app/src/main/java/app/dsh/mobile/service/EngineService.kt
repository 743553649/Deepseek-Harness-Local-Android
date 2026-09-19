package app.dsh.mobile.service

import android.app.ActivityManager
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
import android.os.Process
import android.util.Log
import app.dsh.mobile.DshApp
import app.dsh.mobile.MainActivity
import app.dsh.mobile.R
import app.dsh.mobile.engine.EngineSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 引擎前台服务。
 *
 * 类型选择 specialUse（Android 14+ 生效）：dataSync 类型有 6 小时系统限时，
 * 长任务会被强杀；specialUse 无此限制且 sideload 分发无需 Play 审核豁免。
 *
 * 通知策略：只有这一条常驻通知（前台服务通知），文案跟着引擎状态走，
 * 自带「退出」按钮 —— 常驻通知由系统托管，进程被杀时自动撤掉，不会留下点不掉的僵尸条目。
 */
class EngineService : Service() {

    private var stateJob: Job? = null
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

        // 状态回写到常驻通知
        if (stateJob == null) {
            stateJob = stateScope.launch {
                app.supervisor.state.collect { updateNotification(it) }
            }
        }
        // 【v1.2.28】不再 START_STICKY：用户手动杀掉 App 后系统会立刻把它拉起来，
        // 表现就是「杀掉了但通知还在」（实测：kill 后 6 秒内进程复活、引擎重新 start）。
        // 用户想要的语义是「杀掉就停」，所以不自动复活，
        // 需要重启时由用户打开 App（MainActivity → EngineService.start）。
        return START_NOT_STICKY
    }

    /**
     * 从最近任务划掉 / 系统清理任务栈 → 视为用户显式退出（v1.2.28）。
     *
     * 不处理的话：进程虽被杀，但通知栏那条 ongoing 通知会留下（用户实测反馈）。
     * 这里与「退出」按钮走同一个出口，保证「图标没了 = 引擎停了 = 通知收了」三者一致。
     *
     * ⚠️ 与「按返回键」区分开：返回键走 MainActivity.moveTaskToBack（只退到后台，
     * 任务仍留在最近任务里，本回调不触发），所以引擎与通知继续常驻。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        exitCompletely()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        stateScope.cancel()
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

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.status_starting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
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
     * 把本 App 在「最近任务」里的卡片也撤掉（v1.2.32，用户要求：退出就是退出，别留空壳卡片）。
     *
     * 用 `ActivityManager.appTasks` + `AppTask.finishAndRemoveTask()`：这是**系统侧**的请求，
     * 既不需要界面进程还活着，也不要求用户当时正停在 App 界面上
     * （从通知栏点「退出」时界面通常在后台）。
     * 失败也不影响退出本身（撤不掉卡片也得把进程杀掉）。
     */
    private fun removeTaskFromRecents() {
        runCatching {
            val tasks = getSystemService(ActivityManager::class.java).appTasks
            Log.i(TAG, "removing ${tasks.size} task(s) from recents")
            tasks.forEach { it.finishAndRemoveTask() }
        }.onFailure { Log.w(TAG, "remove task from recents failed: ${it.message}") }
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
        // 先撤前台服务通知再停服务：反了会先 cancel 一条仍被前台服务持有的通知 —— 系统会忽略，通知撤不掉
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopEngineAsync()
        val app = application as DshApp
        app.supervisor.onShutdownFinished(app.appScope) {
            // 期间用户又把 App 打开（监督器重新在跑）= 改主意了 → 不杀
            if (!app.supervisor.running) {
                // 连"最近任务"里的卡片也撤掉：用户要求退出就是退出，不留空壳卡片
                removeTaskFromRecents()
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

        /** 通知「退出」按钮触发动作 */
        const val ACTION_EXIT = "app.dsh.mobile.service.action.EXIT"

        /** 便捷启动入口（供 Activity 调用） */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
