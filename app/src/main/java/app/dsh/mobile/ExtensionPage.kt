package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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
 * 扩展中心（底栏第三个目的地）。
 *
 * 第 2 步（三页合一）之前这是一个独立 Activity；现在它是 MainActivity 里的一个页面。
 * 附带好处：切到别的页面**不会**取消下载 —— 协程现在挂在 MainActivity 上，
 * 只有真正退出 App 才会停（以前一离开页面，下载就被 Activity 销毁带走）。
 *
 * 三态（沿用 strings.xml 里写明的既有约定）：
 *  - 灰  未下载        → 按钮【下载】
 *  - 黄  已下载未激活  → 按钮【激活】（并入引擎 PATH，自动重启引擎）
 *  - 绿  已激活可用    → 按钮【停用】；长按整行可卸载
 */
class ExtensionPage(private val host: Activity) {

    private val manager by lazy { ExtensionManager(host) }
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

    /** 找控件 + 建列表（由 MainActivity.onCreate 调一次） */
    fun bind() {
        container = host.findViewById(R.id.listContainer)
        tvSubtitle = host.findViewById(R.id.tvSubtitle)
        items = manager.loadCatalog()
        buildList()
        refreshHeader()
    }

    /** 每次切到本页时刷新计数（激活数可能被别处改过） */
    fun onShown() = refreshHeader()

    fun onDestroy() {
        uiScope.cancel()
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
    private fun sectionHeader(title: String): TextView = TextView(host).apply {
        text = title
        setTextColor(host.getColor(R.color.section))
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(22), dp(4), dp(9))
    }

    private fun glassPanel(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        background = host.getDrawable(R.drawable.bg_glass_card)
    }

    /** 行分隔线：与面板左右内边距对齐，别贴到圆角上 */
    private fun divider(): View = View(host).apply {
        setBackgroundColor(host.getColor(R.color.hair))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { marginStart = dp(16); marginEnd = dp(16) }
    }

    private fun buildRow(ext: ExtensionManager.Extension): View {
        val refs = RowRefs(
            dot = View(host).apply {
                setBackgroundResource(R.drawable.bg_status_dot)
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
            },
            stateText = TextView(host).apply {
                textSize = 11.5f
                setTextColor(host.getColor(R.color.muted))
            },
            action = TextView(host).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { marginStart = dp(10) }
            },
            progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList = ColorStateList.valueOf(host.getColor(R.color.accent))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
                ).apply { topMargin = dp(8) }
            },
        )

        val row = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            // 行内主体：图标 + 文案 + 状态 + 按钮
            val main = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(host).apply {
                    // 官方品牌图标：17 个 Simple Icons/Material 矢量 + ImageMagick 官方 logo PNG，
                    // 统一 SRC_IN 白色（彩色 chip 上剪影风格）；catalog iconRes 字段驱动
                    val resId = ext.iconRes.takeIf { it.isNotEmpty() }
                        ?.let { host.resources.getIdentifier(it, "drawable", host.packageName) } ?: 0
                    if (resId != 0) {
                        setImageResource(resId)
                        setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = host.getDrawable(R.drawable.bg_icon_chip)
                    backgroundTintList = ColorStateList.valueOf(categoryColor(ext.category))
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { marginStart = dp(13); marginEnd = dp(8) }
                    addView(TextView(host).apply {
                        text = ext.name
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(host.getColor(R.color.ink))
                    })
                    addView(TextView(host).apply {
                        text = subLine(ext)
                        textSize = 11.5f
                        setTextColor(host.getColor(R.color.faint))
                        setPadding(0, dp(3), 0, 0)
                    })
                    // 状态行：圆点 + 文案（与预览稿一致，点跟着文字走）
                    addView(LinearLayout(host).apply {
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
            downloadingNow -> "安装中…" to host.getColor(R.color.state_warn)
            state == ExtensionManager.ExtState.ACTIVATED ->
                host.getString(R.string.ext_state_activated) to host.getColor(R.color.state_ok)
            state == ExtensionManager.ExtState.DOWNLOADED ->
                host.getString(R.string.ext_state_downloaded) to host.getColor(R.color.state_warn)
            else -> host.getString(R.string.ext_state_none) to host.getColor(R.color.faint)
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
                    ExtensionManager.ExtState.NOT_DOWNLOADED ->
                        styleAction(refs.action, host.getString(R.string.ext_action_download), false)
                    ExtensionManager.ExtState.DOWNLOADED ->
                        styleAction(refs.action, host.getString(R.string.ext_action_activate), true)
                    ExtensionManager.ExtState.ACTIVATED ->
                        styleAction(refs.action, host.getString(R.string.ext_action_deactivate), false)
                }
            }
        }
    }

    /** 主按钮=主色渐变 + 深字（浅蓝底上白字会糊）；次要按钮=白玻璃 + 淡墨细线 + 深灰字 */
    private fun styleAction(btn: TextView, label: String, primary: Boolean) {
        btn.text = label
        btn.visibility = View.VISIBLE
        btn.isClickable = true
        btn.setBackgroundResource(if (primary) R.drawable.bg_btn_accent else R.drawable.bg_btn_outline)
        btn.backgroundTintList = null
        btn.setTextColor(host.getColor(if (primary) R.color.accent_on else R.color.muted))
    }

    private fun refreshHeader() {
        val active = manager.activeCount()
        tvSubtitle.text = host.getString(
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
        Toast.makeText(host, host.getString(R.string.ext_download_start, ext.name), Toast.LENGTH_SHORT).show()
        refreshRow(ext)
        uiScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manager.download(ext,
                        onProgress = { p ->
                            host.runOnUiThread {
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
                            host.runOnUiThread {
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
                        host, "${ext.name} 下载完成，可在下方激活", Toast.LENGTH_SHORT
                    ).show()
                }
                .onFailure { e ->
                    AlertDialog.Builder(host)
                        .setTitle(host.getString(R.string.ext_download_failed, ext.name))
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
                // 激活后才需要把 bin/lib 并进引擎环境 → 异步重启，别卡界面
                Toast.makeText(host, host.getString(R.string.ext_activate_toast), Toast.LENGTH_SHORT).show()
                restartEngine()
                refreshRow(ext)
                refreshHeader()
            }
    }

    private fun deactivate(ext: ExtensionManager.Extension) {
        manager.deactivate(ext.id)
        Toast.makeText(host, host.getString(R.string.ext_deactivate_toast), Toast.LENGTH_SHORT).show()
        restartEngine()
        refreshRow(ext)
        refreshHeader()
    }

    private fun confirmUninstall(ext: ExtensionManager.Extension) {
        if (manager.state(ext.id) == ExtensionManager.ExtState.NOT_DOWNLOADED) return
        AlertDialog.Builder(host)
            .setTitle(host.getString(R.string.ext_uninstall_title))
            .setMessage(host.getString(R.string.ext_uninstall_msg, ext.name))
            .setPositiveButton(host.getString(R.string.ext_dialog_uninstall)) { _, _ ->
                val wasActive = manager.state(ext.id) == ExtensionManager.ExtState.ACTIVATED
                manager.remove(ext.id)
                if (wasActive) restartEngine()
                refreshRow(ext)
                refreshHeader()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 引擎重启（异步）：已激活扩展的 bin/lib 需要随新进程环境生效 */
    private fun restartEngine() {
        (host.application as DshApp).supervisor.restartAsync()
    }

    // ================= 杂项 =================

    private fun categoryColor(category: String): Int = when (category) {
        "语言运行时" -> 0xFF10B981.toInt()
        "编译构建" -> 0xFFF59E0B.toInt()
        else -> 0xFFA78BFA.toInt()
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
}
