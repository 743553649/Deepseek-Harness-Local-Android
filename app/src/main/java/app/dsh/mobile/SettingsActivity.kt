package app.dsh.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService

/**
 * 设置页（液态玻璃重做）。四个区块：
 *  - 引擎卡片（主角）：状态 / 地址 / 版本 / 重启引擎 —— 「引擎信息」从原抽屉并到这里
 *  - 显示：横屏模式、流体云状态岛、页面缩放
 *  - 权限中心：运行权限模式、Root 能力、Shizuku 状态、屏幕点击（无障碍）
 *  - 关于：应用名/版本、开源地址 —— 原独立「关于」页的内容并到这里
 *
 * 引擎状态取一次快照即可：本页是独立 Activity，拿不到 MainActivity 的状态流，
 * 所以 onCreate / onResume 时都从 (application as DshApp).supervisor 现取。
 *
 * 权限切换与缩放均复用既有决策（Root 双警告、缩放 −/＋ 步进、无障碍跳系统设置），
 * 落库到同一组 SharedPreferences，MainActivity 在 onResume 重新读取生效。
 */
class SettingsActivity : Activity() {

    private var landscape = false
    private var pageScale = DEFAULT_PAGE_SCALE

    private lateinit var swLandscape: GlassSwitch
    private lateinit var swIsland: GlassSwitch
    private lateinit var engineDot: View
    private lateinit var engineState: TextView
    private lateinit var engineVersion: TextView
    private lateinit var engineAddress: TextView

    /** 引擎卡片状态点的「呼吸」动画（仅就绪时播放，全页唯一一处自发动画） */
    private var dotAnimator: ValueAnimator? = null

    /** 当前打开的权限选择对话框（选完/切换中转时关闭） */
    private var privPickDialog: AlertDialog? = null

    /** Shizuku 授权结果监听（requestPermission 异步回调后刷新状态行） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshShizuku()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置页固定竖屏：即使主界面开了横屏模式，设置页也不跟随旋转
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // —— 引擎卡片 ——
        engineDot = findViewById(R.id.engineDot)
        engineState = findViewById(R.id.engineState)
        engineVersion = findViewById(R.id.engineVersion)
        engineAddress = findViewById(R.id.engineAddress)
        engineVersion.text = "v${versionName()}"
        findViewById<LinearLayout>(R.id.rowRestart).setOnClickListener {
            // 完整 stop→start；对话页顶部胶囊与状态会随 state 流转刷新
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(android.R.string.ok), Toast.LENGTH_SHORT).show()
        }

        // —— 顶部返回 ——
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // —— 显示：横屏模式（玻璃开关）——
        swLandscape = findViewById(R.id.swLandscape)
        swLandscape.onCheckedChange = { on -> applyLandscape(on) }
        findViewById<LinearLayout>(R.id.rowLandscape).setOnClickListener {
            applyLandscape(!swLandscape.isChecked)
        }

        // —— 显示：页面缩放 ——
        findViewById<LinearLayout>(R.id.rowScale).setOnClickListener {
            showScaleDialog()
        }

        // —— 显示：流体云状态岛开关（系统不支持时置灰，别给个点了没反应的开关）——
        swIsland = findViewById(R.id.swIsland)
        swIsland.onCheckedChange = { on -> applyIsland(on) }
        findViewById<LinearLayout>(R.id.rowIsland).setOnClickListener {
            if (FluidCloud.supported) applyIsland(!swIsland.isChecked)
        }

        // —— 权限中心：运行权限模式 ——
        findViewById<LinearLayout>(R.id.rowPriv).setOnClickListener {
            showPrivDialog()
        }

        // —— 权限中心：屏幕点击（无障碍） ——
        findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            handleAccessibility()
        }

        // —— 关于：版本 + 开源地址（点击复制，与原「关于」页行为一致） ——
        findViewById<TextView>(R.id.valAboutVersion).text = versionName()
        findViewById<LinearLayout>(R.id.rowRepo).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh-android", getString(R.string.about_repo)))
            Toast.makeText(this, "已复制仓库地址", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"

    override fun onResume() {
        super.onResume()
        // 从系统无障碍设置返回后刷新状态；Shizuku 授权结果回调也会刷新
        refreshAll()
    }

    override fun onDestroy() {
        dotAnimator?.cancel()
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 全量刷新：引擎卡片 + 两个开关值 + 权限状态行 + 无障碍 */
    private fun refreshAll() {
        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        landscape = prefs.getBoolean(KEY_LANDSCAPE, false)
        pageScale = prefs.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)

        swLandscape.isChecked = landscape
        swIsland.isChecked = FluidCloud.enabled(this)
        // 系统不支持流体云时：开关藏起来，右侧用文字说明，别给个点了没反应的开关
        val islandRow = findViewById<TextView>(R.id.valIsland)
        swIsland.visibility = if (FluidCloud.supported) View.VISIBLE else View.GONE
        islandRow.visibility = if (FluidCloud.supported) View.GONE else View.VISIBLE
        if (!FluidCloud.supported) islandRow.text = getString(R.string.setting_island_unsupported)

        findViewById<TextView>(R.id.valScale).text = "$pageScale%"
        findViewById<TextView>(R.id.valPriv).text = privLabel(Privilege.getMode(this))

        val mute = getColor(R.color.muted)
        val ok = getColor(R.color.state_ok)
        findViewById<TextView>(R.id.valRootStatus).let {
            val rootOk = Privilege.rootAvailableMinimal()
            it.text = getString(
                if (rootOk) R.string.setting_root_status_yes else R.string.setting_root_status_no
            )
            it.setTextColor(if (rootOk) ok else mute)
        }
        refreshEngine()
        refreshShizuku()
        refreshAccess()
    }

    /** 引擎卡片：状态 + 状态点颜色 + 地址（拿不到状态流，每次进页面现取一次快照） */
    private fun refreshEngine() {
        val supervisor = (application as DshApp).supervisor
        val state = supervisor.state.value
        engineAddress.text = "127.0.0.1:${supervisor.healthyPort}"

        val label = when (state) {
            is EngineSupervisor.State.Healthy -> getString(R.string.engine_state_ready)
            is EngineSupervisor.State.SafeMode -> getString(R.string.engine_state_safe)
            is EngineSupervisor.State.Installing,
            is EngineSupervisor.State.Starting -> getString(R.string.engine_state_starting)
            is EngineSupervisor.State.Backoff ->
                getString(R.string.engine_state_backoff, state.delayMs / 1000)
            is EngineSupervisor.State.Failed -> getString(R.string.engine_state_failed, state.reason)
            else -> getString(R.string.engine_state_idle)
        }
        val ready = state is EngineSupervisor.State.Healthy ||
            state is EngineSupervisor.State.SafeMode
        engineState.text = label
        renderEngineDot(
            ready = ready,
            color = when {
                ready -> getColor(R.color.state_ok)
                state is EngineSupervisor.State.Installing ||
                    state is EngineSupervisor.State.Starting ||
                    state is EngineSupervisor.State.Backoff -> getColor(R.color.state_warn)
                else -> getColor(R.color.faint)
            },
        )
    }

    /** 状态点：就绪时绿色呼吸（尊重系统的「减少动态效果」），其余用黄/灰常亮 */
    private fun renderEngineDot(ready: Boolean, color: Int) {
        engineDot.backgroundTintList = ColorStateList.valueOf(color)
        dotAnimator?.cancel()
        dotAnimator = null
        engineDot.alpha = 1f
        if (!ready || Motion.reduced(this)) return
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
        val mute = getColor(R.color.muted)
        val ok = getColor(R.color.state_ok)
        val warn = getColor(R.color.state_warn)
        val v = findViewById<TextView>(R.id.valShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.setting_shizuku_granted)
                v.setTextColor(ok)
            }
            Privilege.shizukuServerRunning() -> {
                v.text = getString(R.string.setting_shizuku_request)
                v.setTextColor(warn)
                Privilege.requestShizukuPermission(SHIZUKU_REQ)
            }
            else -> {
                v.text = getString(R.string.setting_shizuku_absent)
                v.setTextColor(mute)
            }
        }
    }

    /** 无障碍状态刷新 */
    private fun refreshAccess() {
        val mute = getColor(R.color.muted)
        val ok = getColor(R.color.state_ok)
        val on = DshAccessibilityService.isEnabled()
        val v = findViewById<TextView>(R.id.valAccess)
        v.text = getString(if (on) R.string.setting_access_on else R.string.setting_access_off)
        v.setTextColor(if (on) ok else mute)
    }

    private fun privLabel(mode: PrivMode): String = when (mode) {
        PrivMode.ROOT -> getString(R.string.priv_root)
        PrivMode.SHIZUKU -> getString(R.string.priv_shizuku)
        PrivMode.NORMAL -> getString(R.string.priv_normal)
    }

    // ================= 显示：横屏 =================

    private fun applyLandscape(on: Boolean) {
        landscape = on
        swLandscape.isChecked = on
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .edit().putBoolean(KEY_LANDSCAPE, on).apply()
        // 注意：这里【不能】设置 requestedOrientation——它作用于设置页自身，
        // 会把本应锁竖屏的设置页也转横。朝向切换由 MainActivity.onResume
        // 检测偏好变化后统一应用（返回主界面才生效）。
    }

    // ================= 显示：流体云状态岛 =================

    /**
     * 流体云开关（默认开）。落库后让常驻服务**立刻换通知风格**（岛 ↔ 普通前台通知），
     * **不重启引擎** —— 开关不该打断正在跑的会话。
     * 服务没在跑时不发意图（免得"改个开关把引擎拉起来"），只提示下次启动生效。
     */
    private fun applyIsland(on: Boolean) {
        if (!FluidCloud.supported) return
        swIsland.isChecked = on
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .edit().putBoolean(FluidCloud.KEY_ISLAND_ENABLED, on).apply()
        if (!on) FluidCloud.hide(this)   // 关掉时立刻收岛；开着时由服务重新挂上
        if (!EngineService.refreshNotificationIfRunning(this)) {
            Toast.makeText(this, getString(R.string.setting_island_idle_hint), Toast.LENGTH_SHORT).show()
        }
    }

    // ================= 显示：页面缩放 =================

    /**
     * 页面缩放对话框：SeekBar 滑条连续拖动（范围 50–150，步长 5），实时显示百分比。
     * 落库后 MainActivity onResume 读取并 reload 生效。
     */
    private fun showScaleDialog() {
        val value = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            text = "$pageScale%"
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(8), 0, dp(4))
        }
        val accent = getColor(R.color.accent)
        val bar = SeekBar(this).apply {
            max = (MAX_PAGE_SCALE - MIN_PAGE_SCALE) / SCALE_STEP   // 索引 0..20 → 50..150 步长5
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(value)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scale_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pageScale = MIN_PAGE_SCALE + bar.progress * SCALE_STEP
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit().putInt(KEY_PAGE_SCALE, pageScale).apply()
                findViewById<TextView>(R.id.valScale).text = "$pageScale%"
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showStyled()
    }

    /** dp → px 小工具（对话框内边距用） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================= 权限中心：运行权限模式 =================

    /**
     * 运行权限模式选择：自定义单选列表（MIUI 行式）。
     * 能力未就绪的选项直接置灰（alpha 0.35）且不可点击：
     *  - Shizuku：server 未运行 → 置灰（无从授权）
     *  - Root：未检测到 su → 置灰
     * Root 仍保留双警告；切换后自动重启引擎。
     */
    private fun showPrivDialog() {
        val current = Privilege.getMode(this)
        val shizukuOk = Privilege.shizukuServerRunning()
        val rootOk = Privilege.rootAvailableMinimal()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun makeRow(
            label: String, sub: String, mode: PrivMode,
            enabled: Boolean, checked: Boolean,
        ): LinearLayout {
            val radio = android.widget.RadioButton(this).apply {
                isChecked = checked
                isEnabled = enabled
                isClickable = false   // 由整行接管点击
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(getColor(R.color.ink))
                })
                if (sub.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(getColor(R.color.muted))
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                if (enabled) {
                    // 解析主题的 ripple 背景为真实 resId 再取 drawable（attr 不能直接 getDrawable）
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    background = getDrawable(tv.resourceId)
                }
                addView(radio)
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
                alpha = if (enabled) 1f else 0.35f
                isEnabled = enabled
                if (enabled) {
                    setOnClickListener {
                        // 互斥：重画全部 radio
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
                                // server 在跑但未授权 → 先请求授权（弹 Shizuku 框）
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
            getString(R.string.priv_normal), "", PrivMode.NORMAL, true, current == PrivMode.NORMAL))
        box.addView(makeRow(
            getString(R.string.priv_shizuku),
            if (shizukuOk) "" else getString(R.string.setting_shizuku_absent),
            PrivMode.SHIZUKU, shizukuOk, current == PrivMode.SHIZUKU))
        box.addView(makeRow(
            getString(R.string.priv_root),
            if (rootOk) "" else getString(R.string.setting_root_status_no),
            PrivMode.ROOT, rootOk, current == PrivMode.ROOT))

        privPickDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.priv_pick_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        privPickDialog?.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        privPickDialog?.show()
    }

    /** 关闭权限选择对话框（选完/警告链中转用；null 安全） */
    private fun dismissPrivPick() {
        privPickDialog?.dismiss()
        privPickDialog = null
    }

    /** 切换运行权限模式并自动重启引擎（模式未变则不重启） */
    private fun applyModeAndRestart(target: PrivMode) {
        if (Privilege.getMode(this) == target) return
        Privilege.setMode(this, target)
        findViewById<TextView>(R.id.valPriv).text = privLabel(target)
        (application as DshApp).supervisor.restart()
        Toast.makeText(this, getString(R.string.setting_priv_mode), Toast.LENGTH_SHORT).show()
    }

    private fun warnRootSwitch(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_priv_root_warn2_title))
                    .setMessage(getString(R.string.ob_priv_root_warn2))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
                    .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
                    .showStyled()
            }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ -> }
            .showStyled()
    }

    // ================= 权限中心：无障碍 =================

    /** 无障碍：未开启则跳系统设置引导开启；已开启则提示已可用 */
    private fun handleAccessibility() {
        if (DshAccessibilityService.isEnabled()) {
            Toast.makeText(
                this, getString(R.string.menu_accessibility) + "：已开启",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show() }
    }

    /** 统一弹窗样式：覆盖自绘圆角背景，贴近原生安卓对话框的圆润观感 */
    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
            show()
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
