package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主界面：底栏三页合一（对话 / 扩展 / 设置）。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面。
 *
 * 第 2 步（本版）：设置页与扩展中心不再是独立 Activity，而是本 Activity 里的两个页面
 * （[SettingsPage] / [ExtensionPage]）。切页只动可见性 + 药丸平移，所以是平滑过渡、底栏常驻；
 * 返回键在非对话页 = 回对话页。
 *
 * 详见 docs/UI-REDESIGN.md。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var urlLoaded = false

    // —— 引擎就绪前的加载页 ——
    private lateinit var loading: View
    private lateinit var loadingStatus: TextView

    // —— 三个页面（同一时刻只显示一个）——
    private lateinit var chatPage: View
    private lateinit var settingsPageView: View
    private lateinit var extensionsPageView: View
    private lateinit var settingsPage: SettingsPage
    private lateinit var extensionPage: ExtensionPage
    private var pageIndex = 0

    // —— 底部玻璃岛导航 ——
    private lateinit var navArea: View
    private lateinit var navIsland: View
    private lateinit var navPill: View
    private var navIndex = 0

    // —— 对话页顶部小胶囊（引擎状态 / 预览返回）——
    private lateinit var capsule: View
    private lateinit var capsuleText: TextView

    /** 引擎最新状态（顶部胶囊与设置页的引擎卡片共用） */
    private var engineState: EngineSupervisor.State = EngineSupervisor.State.Idle

    /** WebView 是否停在非引擎端口的回环页（预览模式）—— 胶囊变成「← 主页」 */
    private var previewMode = false

    /**
     * 底栏占位区在对话页要涂的颜色 = 引擎网页自己的底色（§4.2 的"无缝"）。
     * 引擎网页不是纯白（用户实测：底栏白条与网页对不齐、很突兀），所以运行时问它要；
     * 拿不到就退回白色 —— 绝不能因为它影响功能。
     */
    private var chatStripColor = Color.WHITE

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器"电脑模式"等价物） */
    private var desktopMode = false
    private var defaultUa: String = ""

    /** 横屏模式：锁横屏模拟电脑屏幕比例；关闭交还系统 */
    private var landscapeMode = false

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    private val uiScope = CoroutineScope(Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        loading = findViewById(R.id.loading)
        loadingStatus = findViewById(R.id.loadingStatus)
        chatPage = findViewById(R.id.chatPage)
        settingsPageView = findViewById(R.id.settingsPage)
        extensionsPageView = findViewById(R.id.extensionsPage)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()

        // 第 2 步：设置页在同一个 Activity 里，所以横竖屏/缩放可以立刻生效
        settingsPage = SettingsPage(
            host = this,
            onLandscapeChanged = { on -> applyLandscapePref(on) },
            onPageScaleChanged = { scale -> pageScale = scale; webView.reload() },
        )
        extensionPage = ExtensionPage(this)
        settingsPage.bind()
        extensionPage.bind()

        setupNav()
        setupCapsule()
        readUiPrefs()
        if (landscapeMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        showPage(0, animate = false)

        val app = application as DshApp
        uiScope.launch {
            app.supervisor.state.collectLatest { render(it) }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台进入即拉起前台服务；服务存在则幂等
        EngineService.start(this)
        // 从别的 App 回来：偏好兜底同步（在同一 Activity 内改的会立刻生效）
        val oldScale = pageScale
        val oldLandscape = landscapeMode
        readUiPrefs()
        if (oldLandscape != landscapeMode) applyLandscapePref(landscapeMode)
        if (oldScale != pageScale) webView.reload()
    }

    /** 读取用户持久化的横竖屏与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        landscapeMode = p.getBoolean(KEY_LANDSCAPE, false)
    }

    /** 横屏偏好变了：换 UA/缩放策略 + 锁朝向（朝向变更会触发 onConfigurationChanged 里的 reload） */
    private fun applyLandscapePref(on: Boolean) {
        landscapeMode = on
        setDesktop(on)
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        webView.reload()
    }

    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        // WebView 背景必须显式设白（§4.4）：默认背景在页面首帧前可能是透明白/系统色，
        // 会在底栏留白区闪一下，破坏"网页与留白区无缝"。
        webView.setBackgroundColor(WEBVIEW_BG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // 竖屏页面缩小靠 viewport meta 改写（同桌面模式机制），useWideViewPort 必须开。
            useWideViewPort = true
            loadWithOverviewMode = true
            applyZoomControls(desktopMode)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // 仅允许回环导航；外部链接交给系统浏览器
                if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null) return
                // 引擎页首帧渲染完成 → 收起加载页（about:blank 之类的空导航不算，
                // 所以要求 urlLoaded 已置位且地址是回环页）
                if (urlLoaded && url != null &&
                    (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))
                ) {
                    setLoading(false)
                }
                // 底栏占位区要跟网页底色一致：直接问网页它自己的背景色（拿不到就保持原色）
                samplePageColor()
                // 桌面模式：视口改写为固定 1280px（响应式走桌面分支，侧栏完整展开）。
                if (desktopMode) {
                    view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                    return
                }
                // 竖屏：全交给 viewport meta 的 initial-scale 控制显示缩放（浏览器 Ctrl- 做法）。
                // width=device-width → 布局宽=屏宽（无横向滚动）；initial-scale=R 直接缩放显示
                //（缩小→按钮变小看更多、整页塞进屏；放大→内容变大）；min=max=R+user-scalable=no
                // 彻底锁死（既不 clamp 到 100%，也不许用户手动改）。setInitialScale 易被 meta
                // 干扰（maximum-scale=1 会把它 clamp 回 100%），故弃用。
                view.evaluateJavascript(portraitViewportJs(pageScale / 100f), null)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updatePreviewChrome(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 主文档加载失败也得把加载页收起来，否则会一直停在转圈上
                if (request?.isForMainFrame == true) setLoading(false)
            }
        }
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，顶部胶囊变成「← 主页」；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    private fun updatePreviewChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        previewMode = loopback && uri.port != enginePort
        renderCapsule()
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        webView.loadUrl(url)
    }

    // ---------------- 底栏三页 ----------------

    /**
     * 切换页面：三页在同一个 Activity 里，切页只是换可见性 + 药丸平移（第 2 步的核心）。
     * 底栏占位区的颜色跟着页面走：对话页 = 网页底色；扩展/设置页 = 透明（露出根节点渐变）。
     */
    private fun showPage(index: Int, animate: Boolean = true) {
        if (index !in 0..2) return
        pageIndex = index
        chatPage.visibility = if (index == 0) View.VISIBLE else View.GONE
        extensionsPageView.visibility = if (index == 1) View.VISIBLE else View.GONE
        settingsPageView.visibility = if (index == 2) View.VISIBLE else View.GONE
        selectNav(index, animate)
        applyStripColor()
        renderCapsule()
        when (index) {
            1 -> extensionPage.onShown()
            2 -> settingsPage.onShown()
        }
    }

    private fun applyStripColor() {
        // 对话页：跟网页底色一致；其它页：透明，让根节点的页面渐变一路铺到底
        navArea.setBackgroundColor(if (pageIndex == 0) chatStripColor else Color.TRANSPARENT)
    }

    /** 问网页"你自己的背景是什么色"，用来把底栏占位区染成同色（拿不到就保持原色） */
    private fun samplePageColor() {
        webView.evaluateJavascript(PAGE_BG_JS) { raw ->
            val color = parseCssColor(raw) ?: return@evaluateJavascript
            if (color != chatStripColor) {
                chatStripColor = color
                applyStripColor()
            }
        }
    }

    /** 解析 "rgb(r, g, b)"（evaluateJavascript 回传的是带引号的 JSON 串） */
    private fun parseCssColor(raw: String?): Int? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (!s.startsWith("rgb")) return null
        val m = Regex("""(\d{1,3})\D+(\d{1,3})\D+(\d{1,3})""").find(s) ?: return null
        val (r, g, b) = m.destructured
        return Color.rgb(
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            b.toInt().coerceIn(0, 255),
        )
    }

    private fun setupNav() {
        navArea = findViewById(R.id.navArea)
        navIsland = findViewById(R.id.navIsland)
        navPill = findViewById(R.id.navPill)

        // 阴影：直接给圆角 outline（layer-list 背景不一定自带 outline，不保险）
        navIsland.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = ISLAND_RADIUS_DP * resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        // 药丸宽度按岛内容宽 / 3 算（别写死 dp），屏宽变化/旋转后要重算
        navIsland.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPill() }

        findViewById<View>(R.id.navChat).setOnClickListener { showPage(0) }
        findViewById<View>(R.id.navExt).setOnClickListener { showPage(1) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showPage(2) }
        selectNav(0, animate = false)
        applyStripColor()
    }

    /** 药丸宽度 = 岛内容宽 / 3（§3.2），并按当前选中格摆好位置 */
    private fun layoutPill() {
        val content = navIsland.width - navIsland.paddingLeft - navIsland.paddingRight
        if (content <= 0) return
        val w = content / 3
        // 只有宽度真的变了才改布局参数：本方法由 layout 回调触发，
        // 无条件 requestLayout 会让"布局→回调→再布局"转不停
        if (navPill.layoutParams.width != w) {
            navPill.layoutParams = navPill.layoutParams.apply { width = w }
        }
        navPill.translationX = (w * navIndex).toFloat()
    }

    /** 选中某一格：药丸平移过去 + 图标/文字换色 */
    private fun selectNav(index: Int, animate: Boolean = true) {
        navIndex = index
        navCells.forEachIndexed { i, (_, iconId, textId) ->
            val color = if (i == index) NAV_ON else NAV_OFF
            findViewById<TextView>(textId).setTextColor(color)
            findViewById<ImageView>(iconId).setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        val content = navIsland.width - navIsland.paddingLeft - navIsland.paddingRight
        val to = ((content / 3) * index).toFloat()
        navPill.animate().cancel()
        if (!animate || Motion.reduced(this) || content <= 0) {
            navPill.translationX = to
            return
        }
        // 只动 translationX，曲线带轻微过冲（§2）
        navPill.animate()
            .translationX(to)
            .setDuration(PILL_MS)
            .setInterpolator(pillInterpolator)
            .start()
    }

    /** 手势缩放开关：竖屏关闭（锁死固定全屏，禁止双指捏合/拖动移动），桌面模式开启（保留双指缩放）。
     *  displayZoomControls 恒 false，只保留捏合不显示 +/- 浮层按钮。 */
    private fun applyZoomControls(enable: Boolean) {
        webView.settings.apply {
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false
        }
    }

    /** 只改桌面渲染的 UA + 手势缩放开关，不 reload——reload 由调用方统一做（避免双重整页重载卡顿）。
     *  viewport 改写统一在 onPageFinished 里做。 */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        webView.settings.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    // ---------------- 顶部小胶囊 ----------------

    private fun setupCapsule() {
        capsule = findViewById(R.id.capsule)
        capsuleText = findViewById(R.id.capsuleText)
        // 预览模式点它回引擎主界面：必须重取引擎宣布的带 token 入口（会话 cookie 可能已过期）
        capsule.setOnClickListener {
            val sup = (application as DshApp).supervisor
            loadLocalUrl(sup.healthyWebUrl ?: "http://127.0.0.1:${sup.healthyPort}/")
        }
    }

    /**
     * 胶囊的三态（§4.5 / §4.6）：预览模式 → 「← 主页」；引擎未就绪 → 说明原因；否则不显示。
     * 它是唯一允许出现在对话页上的 App 元素，所以只在对话页显示。
     */
    private fun renderCapsule() {
        if (pageIndex != 0) {
            capsule.visibility = View.GONE
            return
        }
        if (previewMode) {
            capsuleText.text = getString(R.string.btn_back)
            capsule.isClickable = true
            capsule.visibility = View.VISIBLE
            return
        }
        val state = engineState
        val ready = state is EngineSupervisor.State.Healthy ||
            state is EngineSupervisor.State.SafeMode
        if (ready) {
            capsule.isClickable = false
            capsule.visibility = View.GONE
            return
        }
        capsuleText.text = when (state) {
            is EngineSupervisor.State.Backoff ->
                getString(R.string.engine_state_backoff, state.delayMs / 1000)
            is EngineSupervisor.State.Failed -> getString(R.string.engine_state_failed, state.reason)
            is EngineSupervisor.State.Installing,
            is EngineSupervisor.State.Starting -> getString(R.string.engine_state_starting)
            else -> getString(R.string.engine_state_idle)
        }
        capsule.isClickable = false
        capsule.visibility = View.VISIBLE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → 需要重算适配视口（reload 后 onPageFinished 会按当前模式
        // 与 pageScale 重写 viewport meta）。竖屏旋转屏宽同样变，故统一 reload。
        // 同时同步手势缩放开关（竖屏锁死、桌面保留），防止切屏后对手势失效。
        applyZoomControls(desktopMode)
        layoutPill()
        webView.reload()
    }

    /**
     * 竖屏视口缩放 JS：把 viewport meta 写成
     * `width=device-width, initial-scale=R, minimum-scale=R, maximum-scale=R, user-scalable=no`。
     */
    private fun portraitViewportJs(ratio: Float): String {
        val r = ratio.coerceIn(0.5f, 1.5f)
        return "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=device-width, initial-scale=$r, minimum-scale=$r, maximum-scale=$r, user-scalable=no');" +
            "})()"
    }

    /**
     * 加载页显隐：显示瞬时生效，收起时淡出 —— 引擎启动完成、网页首帧渲染完成后才"跳转进入"。
     * 重新盖回去由 render() 负责。
     */
    private fun setLoading(show: Boolean) {
        if (show) {
            if (loading.visibility != View.VISIBLE) {
                loading.animate().cancel()
                loading.alpha = 1f
                loading.visibility = View.VISIBLE
            }
        } else if (loading.visibility == View.VISIBLE) {
            loading.animate().alpha(0f).setDuration(LOADING_FADE_MS)
                .withEndAction { loading.visibility = View.GONE }.start()
        }
    }

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
    }

    private fun render(state: EngineSupervisor.State) {
        engineState = state
        // 引擎侧还没就绪 → 把加载页盖回来（只盖对话页，底栏与设置/扩展页照常可用）
        if (state !is EngineSupervisor.State.Healthy && state !is EngineSupervisor.State.SafeMode) {
            urlLoaded = false
            setLoading(true)
        }
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        val text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting)
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    // 0.1.5+ WebUI 强制会话认证：裸 / 是 401 黑屏；必须用引擎打印的
                    // 带 token 入口（WebView 跟随 303 自动种 cookie）。旧引擎无 token 退回裸 URL。
                    loadLocalUrl(state.webUrl ?: "http://127.0.0.1:${state.port}/")
                }
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl(state.webUrl ?: "http://127.0.0.1:${state.port}/")
                }
                getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                getString(R.string.status_idle)
            }
        }
        renderCapsule()
        if (::settingsPage.isInitialized) settingsPage.renderEngine(state)
        // 加载页不显示端口/域名：就绪态换成"正在进入界面…"，未启动态换成"正在启动引擎…"
        loadingStatus.text = when (state) {
            is EngineSupervisor.State.Healthy,
            is EngineSupervisor.State.SafeMode -> getString(R.string.loading_ready)
            is EngineSupervisor.State.Idle,
            is EngineSupervisor.State.Stopped -> getString(R.string.loading_boot)
            else -> text
        }
    }

    override fun onBackPressed() {
        // 非对话页：返回键 = 回对话页（不再退到后台）
        if (pageIndex != 0) {
            showPage(0)
            return
        }
        // 对话页：WebView 有历史则先回退，保持类原生浏览体验；没有历史可退时**退到后台**，
        // 而不是结束任务（Termux 同款）。区别很关键：结束任务会触发 EngineService.onTaskRemoved
        // （= 用户显式退出：停引擎 + 收岛），而按返回键只是"离开界面"，引擎与流体云应当继续常驻。
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        uiScope.cancel()
        if (::navPill.isInitialized) navPill.animate().cancel()
        if (::extensionPage.isInitialized) extensionPage.onDestroy()
        if (::settingsPage.isInitialized) settingsPage.onDestroy()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Android 13+ 通知运行时权限请求（Service 启动路径回调到 Activity） */
        fun maybeRequestNotificationPermission(activity: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && activity is Activity) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        /** 页面缩放/横竖屏持久化：SharedPreferences 名 + key（设置页与引导共用） */
        private const val PREFS_UI = "dsh_ui"
        private const val KEY_PAGE_SCALE = "page_scale"
        private const val KEY_LANDSCAPE = "landscape"

        /** 竖屏页面缩放范围/步长/默认值（等价浏览器 Ctrl- 缩小一点，用户可再调） */
        private const val DEFAULT_PAGE_SCALE = 90
        private const val MIN_PAGE_SCALE = 50
        private const val MAX_PAGE_SCALE = 150
        private const val SCALE_STEP = 5

        /** 加载页淡出时长（引擎就绪 + 网页首帧渲染完成之后） */
        private const val LOADING_FADE_MS = 320L

        /** 底栏：药丸平移 460ms（只动 translationX）；岛圆角 24dp（改这里要同步 bg_glass_island） */
        private const val PILL_MS = 460L
        private const val ISLAND_RADIUS_DP = 24f

        /** 底栏三个格子：内容 id → 图标 id → 文字 id（顺序即药丸的格子顺序） */
        private val navCells = listOf(
            Triple(R.id.navChat, R.id.navChatIcon, R.id.navChatText),
            Triple(R.id.navExt, R.id.navExtIcon, R.id.navExtText),
            Triple(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsText),
        )

        /** 导航未选中 / 选中态颜色（未选中值是 @color/nav_off，两处要一致） */
        private val NAV_OFF = 0xFF7A8292.toInt()
        private val NAV_ON = 0xFFFFFFFF.toInt()

        private val pillInterpolator = PathInterpolator(0.32f, 1.28f, 0.36f, 1f)

        /** 网页底色：纯白，与底栏留白区一致，避免加载前闪一下（§4.4） */
        private val WEBVIEW_BG = 0xFFFFFFFF.toInt()

        /**
         * 取网页自己的背景色（body → html → body 的第一个子元素），交给原生把底栏占位区染成同色。
         * 全是透明就返回空串，Kotlin 那边保持原色 —— 绝不因为读不到而影响功能。
         */
        private const val PAGE_BG_JS =
            "(function(){var d=document;var c=[d.body,d.documentElement];" +
                "if(d.body&&d.body.firstElementChild)c.push(d.body.firstElementChild);" +
                "for(var i=0;i<c.length;i++){var e=c[i];if(!e)continue;" +
                "var v=getComputedStyle(e).backgroundColor;" +
                "if(v&&v!=='rgba(0, 0, 0, 0)'&&v!=='transparent'&&v.indexOf('rgba(')!==0)return v;}" +
                "return '';})()"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 桌面视口改写：width=1280 让响应式 CSS 命中桌面断点；
         * initial-scale = 屏宽/1280 整页缩进屏内；maximum-scale=5 + user-scalable
         * 允许双指缩放细看。执行时机 onPageFinished（此时 clientWidth=设备宽）。
         */
        private const val DESKTOP_VIEWPORT_JS =
            "(function(){" +
                "var W=1280;" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "var cw=document.documentElement.clientWidth||412;" +
                "m.setAttribute('content','width='+W+', initial-scale='+(cw/W).toFixed(4)+', maximum-scale=5, user-scalable=yes');" +
                "})()"
    }
}
