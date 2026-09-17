package app.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 【一次性探针 · 验证完即删】ColorOS 16「流体云」（≈ Android 16 标准 Live Updates）是否接受
 * 本 App（targetSdk 28）发出的 live update 通知。
 *
 * 发一条独立的常驻通知：ProgressStyle 进度条 + 关键文字，每 2 秒涨 1%，跑满 100% 后转成可滑动清除。
 * 判据（装好后用 root 看）：
 *   1. `dumpsys notification` 里这条通知是否被打上 FLAG_PROMOTED_ONGOING（0x40000）
 *   2. logcat 里 SystemUI 的 PromotedNotifLog 流水
 *   3. 状态栏流体云 / 锁屏 / AOD 里肉眼可见
 *
 * 为什么用 extras 请求上岛：SDK（android.jar API 36）没有公开的「请求 promoted」方法
 * （实测只有 ProgressStyle / setShortCriticalText / NotificationManager.canPostPromotedNotifications），
 * 而框架 Notification.hasRequestedPromotedOngoing() 读的就是这个 extra；
 * NMS 的 fixNotification() 只剥离 summarization/mediaRemote*/substName 几个 key，不会剥它。
 */
object LiveUpdateProbe {

    private const val CHANNEL_ID = "probe_fluid_cloud"
    private const val NOTIF_ID = 4243
    private const val STEP_MS = 2000L
    private const val TOTAL = 100

    private val handler = Handler(Looper.getMainLooper())
    private var running: Runnable? = null

    fun start(context: Context) {
        // ProgressStyle 等 API 需要 Android 16（本探针只针对 ColorOS 16 / API 36）
        if (Build.VERSION.SDK_INT < 36) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "流体云探针", NotificationManager.IMPORTANCE_DEFAULT),
        )

        running?.let(handler::removeCallbacks)
        var percent = 0
        val tick = object : Runnable {
            override fun run() {
                nm.notify(NOTIF_ID, build(context, percent))
                if (percent >= TOTAL) {
                    running = null
                    return
                }
                percent += 1
                handler.postDelayed(this, STEP_MS)
            }
        }
        running = tick
        handler.post(tick)
    }

    private fun build(context: Context, percent: Int): Notification {
        val icon = Icon.createWithResource(context, R.drawable.ic_launcher)
        val style = Notification.ProgressStyle()
            .setProgress(percent)
            .setProgressTrackerIcon(icon)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("流体云探针")
            .setContentText("进度 $percent%")
            .setShortCriticalText("$percent%")
            .setStyle(style)
            // 跑满后放开常驻，留一条可滑掉的最终态供检查
            .setOngoing(percent < TOTAL)
            .build()
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        return notification
    }
}
