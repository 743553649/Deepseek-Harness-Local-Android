package app.dsh.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege

/**
 * 设置页（底栏第二个目的地）。
 *
 * 第 2 步（三页合一）之前这是一个独立 Activity；现在它是 MainActivity 里的一个页面，
 * 所以：① 没有返回箭头，靠底栏切走；② 引擎状态可以直接吃 MainActivity 的状态流，实时刷新
 * （之前拿不到 collector，只能每次进页面取一次快照）。
 *
 * 三个区块：引擎卡片（主角）→ 显示 → 权限中心。（「关于」已单独占底栏一格）
 */
class SettingsPage(
    private val host: Activity,
    private val onLandscapeChanged: (Boolean) -> Unit,
    private val onPageScaleChanged: (Int) -> Unit,
) {

    private var landscape = false
    private var pageScale = DEFAULT_PAGE_SCALE

    private lateinit var swLandscape: GlassSwitch
    private lateinit var engineDot: View
    private lateinit var engineState: TextView
    private lateinit var engineAddress: TextView

    /** 引擎卡片状态点的「呼吸」动画（仅就绪时播放，本页唯一一处自发动画） */
    private var dotAnimator: ValueAnimator? = null

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** 最近一次引擎状态（本页可能没显示，但状态流会一直喂进来） */
    private var lastState: EngineSupervisor.State = EngineSupervisor.State.Idle

    /** bind() 是否已跑过（状态流可能在 bind 之前就推来） */
    private var bound = false

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    /** 找控件 + 挂点击（由 MainActivity.onCreate 调一次） */
    fun bind() {
        val prefs = host.getSharedPreferences(PREFS_UI, Activity.MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // —— 引擎卡片 ——
        engineDot = host.findViewById(R.id.engineDot)
        engineState = host.findViewById(R.id.engineState)
        engineAddress = host.findViewById(R.id.engineAddress)
        host.findViewById<TextView>(R.id.engineVersion).text = "v${versionName()}"
        host.findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            // 异步重启：绝不在界面线程等引擎退出（那会卡死 5~10 秒）
            (host.application as DshApp).supervisor.restartAsync()
            Toast.makeText(host, host.getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
        }

        // —— 显示：横屏模式（玻璃开关；同一 Activity 内可立即生效）——
        swLandscape = host.findViewById(R.id.swLandscape)
        swLandscape.onCheckedChange = { on -> applyLandscape(on) }
        host.findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            applyLandscape(!swLandscape.isChecked)
        }

        // —— 显示：页面缩放 ——
        host.findViewById<LinearLayout>(R.id.rowScale).setOnClickListener { showScaleDialog() }

        // —— 权限中心 ——
        host.findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener { showPrivDialog() }
        host.findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener { handleAccessibility() }

        bound = true
    }

    /** 每次切到本页时刷新：开关值 + 权限状态行（引擎卡片由状态流实时驱动） */
    fun onShown() {
        val prefs = host.getSharedPreferences(PREFS_UI, Activity.MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        swLandscape.isChecked = landscape

        host.findViewById<TextView>(R.id.valScale).text = "$pageScale%"
        host.findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(host))

        val rootOk = Privilege.rootAvailableMinimal()
        host.findViewById<TextView>(R.id.valRootStatus).let {
            it.text = host.getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.setTextColor(host.getColor(if (rootOk) R.color.state_ok else R.color.muted))
        }
        refreshShizuku()
        refreshAccess()
        renderEngine(lastState)
    }

    /** 引擎卡片：状态文字 + 状态点（由 MainActivity 的状态流驱动，实时） */
    fun renderEngine(state: EngineSupervisor.State) {
        lastState = state
        if (!bound) return
        val supervisor = (host.application as DshApp).supervisor
        engineAddress.text = "127.0.0.1:${supervisor.healthyPort}"

        val ready = state is EngineSupervisor.State.Healthy ||
            state is EngineSupervisor.State.SafeMode
        engineState.text = when (state) {
            is EngineSupervisor.State.Healthy -> host.getString(R.string.engine_state_ready)
            is EngineSupervisor.State.SafeMode -> host.getString(R.string.engine_state_safe)
            is EngineSupervisor.State.Installing,
            is EngineSupervisor.State.Starting -> host.getString(R.string.engine_state_starting)
            is EngineSupervisor.State.Backoff ->
                host.getString(R.string.engine_state_backoff, state.delayMs / 1000)
            is EngineSupervisor.State.Failed -> host.getString(R.string.engine_state_failed, state.reason)
            else -> host.getString(R.string.engine_state_idle)
        }
        renderEngineDot(
            ready = ready,
            color = when {
                ready -> host.getColor(R.color.state_ok)
                state is EngineSupervisor.State.Installing ||
                    state is EngineSupervisor.State.Starting ||
                    state is EngineSupervisor.State.Backoff -> host.getColor(R.color.state_warn)
                else -> host.getColor(R.color.faint)
            },
        )
    }

    /** 状态点：就绪时绿色呼吸（尊重系统的「减少动态效果」），其余用黄/灰常亮 */
    private fun renderEngineDot(ready: Boolean, color: Int) {
        engineDot.backgroundTintList = ColorStateList.valueOf(color)
        dotAnimator?.cancel()
        dotAnimator = null
        engineDot.alpha = 1f
        if (!ready || Motion.reduced(host)) return
        dotAnimator = ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { engineDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    /** Shizuku 三态刷新（已授权绿 / 等待授权黄 / 未运行灰） */
    private fun refreshShizuku() {
        if (!bound) return
        val v = host.findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = host.getString(R.string.setting_shizuku_granted)
                v.setTextColor(host.getColor(R.color.state_ok))
            }
            Privilege.shizukuServerRunning() -> {
                v.text = host.getString(R.string.setting_shizuku_request)
                v.setTextColor(host.getColor(R.color.state_warn))
                Privilege.requestShizukuPermission(SHIZUKU_REQ)
            }
            else -> {
                v.text = host.getString(R.string.setting_shizuku_absent)
                v.setTextColor(host.getColor(R.color.muted))
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val on = DshAccessibilityService.isEnabled()
        val v = host.findViewById<TextView>(R.id.valAccess)
        v.text = host.getString(if (on) R.string.setting_access_on else R.string.setting_access_off)
        v.setTextColor(host.getColor(if (on) R.color.state_ok else R.color.muted))
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> host.getString(R.string.priv_root)
        PrivMode.SHIZUKU -> host.getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> host.getString(R.string.priv_normal)
    }

    fun onDestroy() {
        dotAnimator?.cancel()
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }
            .getOrNull() ?: "?"

    // ================= 显示：横屏 =================

    private fun applyLandscape(on: Boolean) {
        landscape = on
        swLandscape.isChecked = on
        host.getSharedPreferences(PREFS_UI, Activity.MODE_PRIVATE)
            .edit().putBoolean(KEY_LANDSCAPE, on).apply()
        // 第 2 步：设置页和对话页在同一个 Activity 里，所以可以**立刻**生效
        onLandscapeChanged(on)
    }

    // ================= 显示：页面缩放 =================

    /** 页面缩放：滑条 50–150、步长 5；确定后立刻生效（reload 由 MainActivity 做） */
    private fun showScaleDialog() {
        val value = TextView(host).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setTextColor(host.getColor(R.color.ink))
            setPadding(0, dp(8), 0, dp(4))
        }
        val accent = host.getColor(R.color.accent)
        val bar = SeekBar(host).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP
            progress = (pageScale - MIN_PAGE_SCALE) / SCALE_STEP
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setPadding(dp(24), 0, dp(24), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${MIN_PAGE_SCALE + progress * SCALE_STEP}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(host)
            .setTitle(host.getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                host.getSharedPreferences(PREFS_UI, Activity.MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                host.findViewById<TextView>(R.id.valScale).text = "$pageScale%"
                onPageScaleChanged(pageScale)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()

    // ================= 权限中心：运行权限模式 =================

    /**
     * 运行权限模式选择：自定义单选列表。
     * 能力未就绪的选项置灰（alpha 0.35）且不可点击；Root 保留双警告；切换后自动重启引擎。
     */
    private fun showPrivDialog() {
        val current = Privilege.getMode(host)
        val shizukuOk = Privilege.shizukuServerRunning()
        val rootOk = Privilege.rootAvailableMinimal()

        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun makeRow(
            label: String, sub: String, mode: PrivMode,
            enabled: Boolean, checked: Boolean,
        ): LinearLayout {
            val radio = android.widget.RadioButton(host).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
            }
            val textCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(host).apply {
                    text = label
                    textSize = 16f
                    setTextColor(host.getColor(R.color.ink))
                })
                if (sub.isNotEmpty()) addView(TextView(host).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(host.getColor(R.color.muted))
                })
            }
            return LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (enabled) {
                    val tv = android.util.TypedValue()
                    host.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    background = host.getDrawable(tv.resourceId)
                }
                addView(radio)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                if (enabled) {
                    setOnClickListener {
                        for (i in 0 until box.childCount) {
                            val row = box.getChildAt(i) as LinearLayout
                            (row.getChildAt(0) as android.widget.RadioButton).isChecked = row === this
                        }
                        when (mode) {
                            PrivMode.ROOT -> warnRootSwitch {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.ROOT)
                            }
                            PrivMode.SHIZUKU -> {
                                if (!Privilege.shizukuGranted()) {
                                    Privilege.requestShizukuPermission(SHIZUKU_REQ)
                                }
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.SHIZUKU)
                            }
                            PrivMode.NORMAL -> {
                                dismissPrivPick()
                                applyModeAndRestart(PrivMode.NORMAL)
                            }
                        }
                    }
                }
            }
        }

        box.addView(makeRow(
            host.getString(R.string.priv_normal), "", PrivMode.NORMAL, true, current == PrivMode.NORMAL))
        box.addView(makeRow(
            host.getString(R.string.priv_shizuku),
            if (shizukuOk) "" else host.getString(R.string.setting_shizuku_absent),
            PrivMode.SHIZUKU, shizukuOk, current == PrivMode.SHIZUKU))
        box.addView(makeRow(
            host.getString(R.string.priv_root),
            if (rootOk) "" else host.getString(R.string.setting_root_status_no),
            PrivMode.ROOT, rootOk, current == PrivMode.ROOT))

        privPickDialog = AlertDialog.Builder(host)
            .setTitle(host.getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        privPickDialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        privPickDialog?.show()
    }

    private fun dismissPrivPick() {
        privPickDialog?.dismiss()
        privPickDialog = null
    }

    /** 切换运行权限模式并自动重启引擎（模式未变则不重启） */
    private fun applyModeAndRestart(target: PrivMode) {
        if (Privilege.getMode(host) == target) return
        Privilege.setMode(host, target)
        host.findViewById<TextView>(R.id.valPriv).text = privLabel(target)
        (host.application as DshApp).supervisor.restartAsync()
        Toast.makeText(host, host.getString(R.string.setting_priv_mode), Toast.LENGTH_SHORT).show()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(host)
            .setTitle(host.getString(R.string.ob_priv_root_warn_title))
            .setMessage(host.getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(host.getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(host)
                    .setTitle(host.getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(host.getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(host.getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(host.getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(host.getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    // ================= 权限中心：无障碍 =================

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                host, host.getString(R.string.menu_accessibility) + "：已开启",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching { host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { Toast.makeText(host, "无法打开无障碍设置", Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val SHIZUKU_REQ = 4202
        const val PREFS_UI = "dsh_ui"
        const val KEY_PAGE_SCALE = "page_scale"
        const val KEY_LANDSCAPE = "landscape"
        const val DEFAULT_PAGE_SCALE = 90
        const val MIN_PAGE_SCALE = 50
        const val MAX_PAGE_SCALE = 150
        const val SCALE_STEP = 5

        /** 引擎卡片状态点呼吸一轮的时长（§2：2.4s 循环） */
        const val BREATH_MS = 1200L
    }
}

/** 统一弹窗样式：覆盖自绘圆角背景（原 SettingsActivity 的扩展函数挪到这里） */
private fun AlertDialog.Builder.showStyled(): AlertDialog =
    create().apply {
        setOnShowListener {
            window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        }
        show()
    }
