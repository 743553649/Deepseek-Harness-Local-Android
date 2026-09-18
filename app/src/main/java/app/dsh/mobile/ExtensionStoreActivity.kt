package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.widget.ImageView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.ExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扩展中心（v1.2.0）：自由下载环境扩展（Python / Git / JDK / FFmpeg…）。
 *
 * 三态红绿灯（沿用 strings.xml 里写明的既有约定）：
 *  - 灰  未下载        → 按钮【下载】
 *  - 黄  已下载未激活  → 按钮【激活】（并入引擎 PATH，自动重启引擎）
 *  - 绿  已激活可用    → 按钮【停用】；长按整行可卸载
 *
 * 列表为程序化构建（清单约 18 项，无需引入 RecyclerView），按分类各包一块玻璃面板；
 * 下载在协程 IO 线程执行，进度经 runOnUiThread 回刷行内 ProgressBar。
 */
class ExtensionStoreActivity : Activity() {

    private val manager by lazy { ExtensionManager(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在下载的扩展 id（防重复点击） */
    private val downloading = mutableSetOf<String>()

    private lateinit var container: LinearLayout
    private lateinit var tvSubtitle: TextView
    private var items: List<ExtensionManager.Extension> = emptyList()
    private val rowRefs = mutableMapOf<String, RowRefs>()

    /** 行内可变控件的引用集（刷新单行用） */
    private class RowRefs(
        val dot: View,
        val stateText: TextView,
        val action: TextView,
        val progress: ProgressBar,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_extension_store)

        container = findViewById(R.id.listContainer)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        items = manager.loadCatalog()
        buildList()
        refreshHeader()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    // ================= 列表构建 =================

    /** 按分类分组：每个分类 = 一个分组标题 + 一块玻璃面板（面板里装该分类的行） */
    private fun buildList() {
        container.removeAllViews()
        rowRefs.clear()
        var lastCategory: String? = null
        var panel: LinearLayout? = null
        items.forEach { ext ->
            if (ext.category != lastCategory) {
                lastCategory = ext.category
                container.addView(sectionHeader(ext.category))
                panel = glassPanel().also { container.addView(it) }
            }
            val target = panel ?: return@forEach
            if (target.childCount > 0) target.addView(divider())
            target.addView(buildRow(ext))
        }
    }

    /** 分组标题（不是面板的一部分，所以放在面板外面） */
    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(getColor(R.color.section))
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(22), dp(4), dp(9))
    }

    private fun glassPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_glass_card)
    }

    /** 行分隔线：注意与面板左右内边距对齐，别贴到圆角上 */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.hair))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { marginStart = dp(16); marginEnd = dp(16) }
    }

    private fun buildRow(ext: ExtensionManager.Extension): View {
        val refs = RowRefs(
            dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_status_dot)
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            },
            stateText = TextView(this).apply {
                textSize = 11.5f
                setTextColor(getColor(R.color.muted))
            },
            action = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                minHeight = dp(34)
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { marginStart = dp(10) }
            },
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
                ).apply { topMargin = dp(8) }
            },
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            // 行内主体：图标 + 文案 + 状态 + 按钮
            val main = LinearLayout(this@ExtensionStoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@ExtensionStoreActivity).apply {
                    // 官方品牌图标：17 个 Simple Icons/Material 矢量 + ImageMagick 官方 logo PNG，
                    // 统一 SRC_IN 白色（彩色 chip 上剪影风格）；catalog iconRes 字段驱动
                    val resId = ext.iconRes.takeIf { it.isNotEmpty() }
                        ?.let { resources.getIdentifier(it, "drawable", packageName) } ?: 0
                    if (resId != 0) {
                        setImageResource(resId)
                        setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = getDrawable(R.drawable.bg_icon_chip)
                    backgroundTintList = ColorStateList.valueOf(categoryColor(ext.category))
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
                addView(LinearLayout(this@ExtensionStoreActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(13); marginEnd = dp(8) }
                    addView(TextView(this@ExtensionStoreActivity).apply {
                        text = ext.name
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(getColor(R.color.ink))
                    })
                    addView(TextView(this@ExtensionStoreActivity).apply {
                        text = subLine(ext)
                        textSize = 11.5f
                        setTextColor(getColor(R.color.faint))
                        setPadding(0, dp(3), 0, 0)
                    })
                    // 状态行：圆点 + 文案（与预览稿一致，点跟着文字走）
                    addView(LinearLayout(this@ExtensionStoreActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, 0)
                        addView(refs.dot)
                        addView(refs.stateText.apply { setPadding(dp(6), 0, 0, 0) })
                    })
                })
                addView(refs.action)
            }
            addView(main, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(refs.progress)
        }

        refs.action.setOnClickListener { onAction(ext) }
        row.setOnLongClickListener {
            confirmUninstall(ext)
            true
        }
        rowRefs[ext.id] = refs
        refreshRow(ext)
        return row
    }

    private fun subLine(ext: ExtensionManager.Extension): String =
        "Termux 仓库 · ${ext.packages.joinToString(" + ")}"

    // ================= 状态刷新 =================

    private fun refreshRow(ext: ExtensionManager.Extension) {
        val refs = rowRefs[ext.id] ?: return
        // AI 通道（/ext/install）与 UI 共享 installing 状态源。
        // ⚠️ 必须并入 UI 自己的 downloading 集合：startDownload 时 IO 线程尚未跑
        // installing.add，仅查 manager 会误判"未在装"→ 进度条被设 GONE，
        // onProgress 回调只改数值不改可见性 → 进度条整场不可见（用户实测事故）
        val downloadingNow = ext.id in downloading || manager.isInstalling(ext.id)
        val state = manager.state(ext.id)

        val (stateLabel, dotColor) = when {
            downloadingNow -> "安装中…" to getColor(R.color.state_warn)
            state == ExtensionManager.ExtState.ACTIVATED ->
                getString(R.string.ext_state_activated) to getColor(R.color.state_ok)
            state == ExtensionManager.ExtState.DOWNLOADED ->
                getString(R.string.ext_state_downloaded) to getColor(R.color.state_warn)
            else -> getString(R.string.ext_state_none) to getColor(R.color.faint)
        }
        // 已装扩展在状态行追加实际版本（安装时从 Termux 仓库索引记录）
        val ver = if (!downloadingNow && state != ExtensionManager.ExtState.NOT_DOWNLOADED)
            manager.installedVersion(ext.id)?.let { " · v$it" } ?: "" else ""
        refs.stateText.text = stateLabel + ver
        refs.stateText.setTextColor(dotColor)
        refs.dot.backgroundTintList = ColorStateList.valueOf(dotColor)

        // 按钮：激活 = 主按钮（主色渐变）；下载 / 停用 = 次要玻璃按钮；下载中隐藏
        when {
            downloadingNow -> {
                refs.action.visibility = View.GONE
                refs.progress.visibility = View.VISIBLE
                refs.action.isClickable = false
            }
            else -> {
                refs.progress.visibility = View.GONE
                refs.action.visibility = View.VISIBLE
                when (state) {
                    ExtensionManager.ExtState.NOT_DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_download), false)
                    }
                    ExtensionManager.ExtState.DOWNLOADED -> {
                        styleAction(refs.action, getString(R.string.ext_action_activate), true)
                    }
                    ExtensionManager.ExtState.ACTIVATED -> {
                        styleAction(refs.action, getString(R.string.ext_action_deactivate), false)
                    }
                }
            }
        }
    }

    /** 主按钮=主色渐变 + 深字（浅蓝底上白字会糊）；次要按钮=白玻璃 + 描边 */
    private fun styleAction(btn: TextView, label: String, primary: Boolean) {
        btn.text = label
        btn.visibility = View.VISIBLE
        btn.isClickable = true
        btn.setBackgroundResource(if (primary) R.drawable.bg_btn_accent else R.drawable.bg_btn_outline)
        btn.backgroundTintList = null
        btn.setTextColor(getColor(if (primary) R.color.accent_on else R.color.muted))
    }

    private fun refreshHeader() {
        val active = manager.activeCount()
        tvSubtitle.text = getString(
            R.string.ext_subtitle, manager.deviceAbiKey(), active, items.size
        )
    }

    // ================= 动作 =================

    private fun onAction(ext: ExtensionManager.Extension) {
        when (manager.state(ext.id)) {
            ExtensionManager.ExtState.NOT_DOWNLOADED -> startDownload(ext)
            ExtensionManager.ExtState.DOWNLOADED -> activate(ext)
            ExtensionManager.ExtState.ACTIVATED -> deactivate(ext)
        }
    }

    private fun startDownload(ext: ExtensionManager.Extension) {
        if (ext.id in downloading) return
        downloading.add(ext.id)
        // 点击即时反馈：进入「安装中」态 + Toast，避免误以为没反应
        Toast.makeText(this, getString(R.string.ext_download_start, ext.name), Toast.LENGTH_SHORT).show()
        refreshRow(ext)
        uiScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manager.download(ext,
                        onProgress = { p ->
                            runOnUiThread {
                                val refs = rowRefs[ext.id] ?: return@runOnUiThread
                                // 防御性自愈：refreshRow 的时序竞态可能把进度条设成 GONE，
                                // 任何进度回调到达都强制恢复可见（visibility 只在此处维护）
                                refs.progress.visibility = View.VISIBLE
                                refs.action.visibility = View.GONE
                                val bar = refs.progress
                                if (p <= 0f) {
                                    // 0 = indeterminate 信号（解析闭包/索引阶段）：转旋转动画
                                    bar.isIndeterminate = true
                                } else {
                                    bar.isIndeterminate = false
                                    bar.progress = (p * 100).toInt()
                                    // 下载段（<95%）stateText 同步百分比；解包段让位给阶段文案
                                    if (p < 0.95f) {
                                        refs.stateText.text =
                                            "${ext.name} 下载中 ${(p * 100).toInt()}%"
                                    }
                                }
                            }
                        },
                        onStage = { stage ->
                            runOnUiThread {
                                val refs = rowRefs[ext.id] ?: return@runOnUiThread
                                refs.progress.visibility = View.VISIBLE
                                refs.action.visibility = View.GONE
                                refs.stateText.text = stage
                            }
                        })
                }
            }
            downloading.remove(ext.id)
            result
                .onSuccess {
                    Toast.makeText(
                        this@ExtensionStoreActivity,
                        "${ext.name} 下载完成，可在下方激活", Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure { e ->
                    AlertDialog.Builder(this@ExtensionStoreActivity)
                        .setTitle(getString(R.string.ext_download_failed, ext.name))
                        .setMessage(e.message ?: "未知错误")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            refreshRow(ext)
            refreshHeader()
        }
    }

    private fun activate(ext: ExtensionManager.Extension) {
        runCatching { manager.activate(ext.id) }
            .onSuccess {
                Toast.makeText(this, getString(R.string.ext_activate_toast), Toast.LENGTH_SHORT).show()
                restartEngine()
                refreshRow(ext)
                refreshHeader()
            }
    }

    private fun deactivate(ext: ExtensionManager.Extension) {
        manager.deactivate(ext.id)
        Toast.makeText(this, getString(R.string.ext_deactivate_toast), Toast.LENGTH_SHORT).show()
        restartEngine()
        refreshRow(ext)
        refreshHeader()
    }

    private fun confirmUninstall(ext: ExtensionManager.Extension) {
        if (manager.state(ext.id) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_uninstall_title))
            .setMessage(getString(R.string.ext_uninstall_msg, ext.name))
            .setPositiveButton(getString(R.string.ext_dialog_uninstall)) { _, _ ->
                val wasActive = manager.state(ext.id) == ExtensionManager.ExtState.ACTIVATED
                manager.remove(ext.id)
                if (wasActive) restartEngine()
                refreshRow(ext)
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 引擎重启：已激活扩展的 bin/lib 需要随新进程环境生效 */
    private fun restartEngine() {
        (application as DshApp).supervisor.restart()
    }

    // ================= 杂项 =================

    private fun categoryColor(category: String): Int = when (category) {
        "语言运行时" -> 0xFF10B981.toInt()
        "编译构建" -> 0xFFF59E0B.toInt()
        else -> 0xFFA78BFA.toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
