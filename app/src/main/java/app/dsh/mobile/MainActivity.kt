package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
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
 * 主界面：底栏四页合一（对话 / 扩展 / 设置 / 关于）。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面。
 *
 * 第 2 步（本版）：设置/扩展/关于不再是独立 Activity，而是本 Activity 里的三个页面
 * （[SettingsPage] / [ExtensionPage] / [AboutPage]）。切页只动可见性 + 药丸平移，所以是平滑过渡、
 * 底栏常驻；返回键在非对话页 = 回对话页。
 *
 * 全屏（v1.2.42）：窗口覆盖到状态栏底下（状态栏透明、内容整体下移一个状态栏高度），
 * 所以状态栏那一块显示的是页面自己的底色。
 *
 * 详见 docs/UI-REDESIGN.md。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var urlLoaded = false

    // —— 全屏（覆盖状态栏）——
    private lateinit var rootView: View
    private var statusBarInset = -1

    /** 当前键盘高度（px）。窗口是全屏的，系统不再自己压缩，改由 [applyImeInset] 把内容顶上去 */
    private var imeInset = 0

    // —— 引擎就绪前的加载页 ——
    private lateinit var loading: View
    private lateinit var loadingStatus: TextView
    private lateinit var loadingSpinner: GlassSpinner
    private lateinit var installProgress: GlassProgress

    // —— 四个页面（同一时刻只显示一个）——
    private lateinit var chatPage: View
    private lateinit var settingsPageView: View
    private lateinit var extensionsPageView: View
    private lateinit var aboutPageView: View
    private lateinit var settingsPage: SettingsPage
    private lateinit var extensionPage: ExtensionPage
    private lateinit var aboutPage: AboutPage
    private var pageIndex = 0

    /** 首次进入时不做过渡（onCreate 里那一发） */
    private var pagesReady = false

    /** 引擎未就绪时的启动画面是否盖着（底栏要等它退场后再一起出现） */
    private var splashVisible = true

    // —— 底栏（铺满的玻璃条 + 三格导航）——
    private lateinit var navArea: View
    private lateinit var navBar: View
    private lateinit var navPill: View
    private var navIndex = 0

    // —— 对话页顶部小胶囊（引擎状态 / 预览返回）——
    private lateinit var capsule: View
    private lateinit var capsuleText: TextView

    /** WebView 是否停在非引擎端口的回环页（预览模式）—— 胶囊变成「← 主页」 */
    private var previewMode = false

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

        rootView = findViewById(R.id.root)
        loading = findViewById(R.id.loading)
        loadingStatus = findViewById(R.id.loadingStatus)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        installProgress = findViewById(R.id.installProgress)
        chatPage = findViewById(R.id.chatPage)
        settingsPageView = findViewById(R.id.settingsPage)
        extensionsPageView = findViewById(R.id.extensionsPage)
        aboutPageView = findViewById(R.id.aboutPage)
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
        aboutPage = AboutPage(this)
        settingsPage.bind()
        extensionPage.bind()
        aboutPage.bind()

        setupNav()
        setupCapsule()
        setupEdgeToEdge()
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

    /**
     * 全屏：窗口延伸到状态栏底下、状态栏底色透明，页面内容整体下移一个状态栏高度。
     * 效果 = 状态栏那一块显示的是**页面自己的底色**（对话页是白的，其它页是页面渐变），
     * 同时网页/内容不会被状态栏图标压住。
     *
     * 注意：引擎网页能看到的区域和改之前**一样高** —— 窗口多了 ~38dp，内容又下移了同样多。
     * 顶部胶囊是根节点上的浮层，也要跟着下移。
     */
    private fun setupEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        rootView.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop.takeIf { it > 0 } ?: statusBarHeightRes()
            applyStatusBarInset(top)
            applyImeInset(insets)
            insets
        }
        rootView.requestApplyInsets()
    }

    /**
     * 键盘：窗口是全屏的（见 [setupEdgeToEdge]），Android 15+ 对这种窗口不再自动把窗口压矮，
     * 键盘直接盖在页面上 —— 网页底部的输入框被挡住，看不见自己在打什么。
     * 这里自己把内容整体顶上去：底部留出键盘那么高的一块空位，键盘正好填进去。
     *
     * 只在 API 30+ 处理：更早的系统窗口本来就会随键盘自动压缩，平台也不分发 ime inset。
     */
    private fun applyImeInset(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (ime == imeInset) return
        imeInset = ime
        rootView.setPadding(0, 0, 0, ime)
    }

    /** 状态栏内边距变了（首次布局 / 旋转 / 刘海机型）：把四个页面和顶部胶囊一起下移 */
    private fun applyStatusBarInset(top: Int) {
        if (top == statusBarInset) return
        statusBarInset = top
        for (page in listOf(chatPage, extensionsPageView, settingsPageView, aboutPageView)) {
            page.setPadding(0, top, 0, 0)
        }
        if (::capsule.isInitialized) {
            (capsule.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(CAPSULE_TOP_DP) + top
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 兜底：万一系统不给 inset（HDR/新版本兼容路径），直接读状态栏高度资源 */
    private fun statusBarHeightRes(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 导航一开始就校准胶囊：doUpdateVisitedHistory 只在历史真的变化时来，
                // 单靠它就有"回了主会话胶囊还挂着"的窗口（用户报障）。
                updatePreviewChrome(url)
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

    // ---------------- 底栏四页 ----------------

    /**
     * 切换页面：四页在同一个 Activity 里，切页只是换可见性 + 药丸平移（第 2 步的核心）。
     * 底栏占位区始终透明 —— 玻璃条的半透明要把根节点的页面渐变透上来，才看得出玻璃材质。
     */
    private fun showPage(index: Int, animate: Boolean = true) {
        if (index !in 0..3) return
        val from = pageIndex
        // 点当前这一格：什么都不用动（第一次进来除外）
        if (pagesReady && from == index) {
            onPageShown(index)
            return
        }
        pagesReady = true
        pageIndex = index
        val target = pageAt(index)
        val previous = pageAt(from)

        // 先把"既不是旧页也不是新页"的那些页面收干净，连点也不会打架
        for (i in 0..3) {
            val v = pageAt(i)
            if (v !== target && v !== previous) {
                v.animate().cancel()
                v.alpha = 1f
                v.translationX = 0f
                v.visibility = View.GONE
            }
        }

        if (animate && !Motion.reduced(this)) {
            // 交叉淡入淡出 + 轻微横向位移：新页从手势方向滑进来一点
            val dir = if (index > from) 1f else -1f
            val shift = PAGE_SHIFT_RATIO * resources.displayMetrics.widthPixels
            target.animate().cancel()
            previous.animate().cancel()
            target.visibility = View.VISIBLE
            target.alpha = 0f
            target.translationX = dir * shift
            target.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(PAGE_MS)
                .setInterpolator(pageInterpolator)
                .start()
            previous.animate()
                .alpha(0f)
                .translationX(-dir * shift)
                .setDuration(PAGE_MS)
                .setInterpolator(pageInterpolator)
                .withEndAction {
                    previous.visibility = View.GONE
                    previous.alpha = 1f
                    previous.translationX = 0f
                }
                .start()
        } else {
            target.animate().cancel()
            previous.animate().cancel()
            previous.alpha = 1f
            previous.translationX = 0f
            previous.visibility = View.GONE
            target.alpha = 1f
            target.translationX = 0f
            target.visibility = View.VISIBLE
        }

        selectNav(index, animate)
        applyBarBackdrop()
        updateBarVisibility(animate)
        renderCapsule()
        onPageShown(index)
    }

    private fun pageAt(index: Int): View = when (index) {
        0 -> chatPage
        1 -> extensionsPageView
        2 -> settingsPageView
        else -> aboutPageView
    }

    /** 底栏垫色：对话页纯白（跟白色网页连成一片），其它页透明（露出页面渐变） */
    private fun applyBarBackdrop() {
        navArea.setBackgroundColor(if (pageIndex == 0) Color.WHITE else Color.TRANSPARENT)
    }

    private fun onPageShown(index: Int) {
        when (index) {
            1 -> extensionPage.onShown()
            2 -> settingsPage.onShown()
            3 -> aboutPage.onShown()
        }
    }

    /**
     * 底栏显隐：启动画面还盖着对话页时不显示底栏 —— 等加载动画退场时再和页面一起淡入
     * （用户报障：启动加载时就冒出底栏，时机不对）。
     * 其它页面不会被启动画面盖住，所以那时底栏照常可用，不会把人困在设置页里。
     */
    private fun updateBarVisibility(animate: Boolean = true) {
        if (pageIndex == 0 && splashVisible) {
            navArea.animate().cancel()
            navArea.alpha = 0f
            navArea.visibility = View.INVISIBLE
            return
        }
        if (navArea.visibility == View.VISIBLE && navArea.alpha >= 1f) return
        navArea.visibility = View.VISIBLE
        if (animate && !Motion.reduced(this)) {
            navArea.alpha = 0f
            navArea.animate().alpha(1f).setDuration(BAR_FADE_MS).start()
        } else {
            navArea.alpha = 1f
        }
    }

    private fun setupNav() {
        navArea = findViewById(R.id.navArea)
        navBar = findViewById(R.id.navBar)
        navPill = findViewById(R.id.navPill)

        // 药丸宽度按玻璃条内容宽 / 格子数 算（别写死 dp），屏宽变化/旋转后要重算
        navBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPill() }

        findViewById<View>(R.id.navChat).setOnClickListener { showPage(0) }
        findViewById<View>(R.id.navExt).setOnClickListener { showPage(1) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showPage(2) }
        findViewById<View>(R.id.navAbout).setOnClickListener { showPage(3) }
        selectNav(0, animate = false)
        applyBarBackdrop()
        updateBarVisibility(animate = false)
    }

    /** 药丸宽度 = 玻璃条内容宽 / 格子数，并按当前选中格摆好位置 */
    private fun layoutPill() {
        val content = navBar.width - navBar.paddingLeft - navBar.paddingRight
        if (content <= 0) return
        val w = content / navCells.size
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
        val content = navBar.width - navBar.paddingLeft - navBar.paddingRight
        val to = ((content / navCells.size) * index).toFloat()
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
            // 引擎没就绪时连 URL 都拼不出来（healthyPort 可能是 0）：那就别假装跳转，
            // 把胶囊原样显示回去，用户至少还知道自己停在预览页里。
            val target = sup.healthyWebUrl
                ?: sup.healthyPort.takeIf { it > 0 }?.let { "http://127.0.0.1:$it/" }
            if (target == null) {
                renderCapsule()
                return@setOnClickListener
            }
            // 点了就先消失（乐观隐藏）：胶囊的显隐原本只靠 WebView 导航事件回调，
            // 万一那一次回调不来，它就赖在屏幕上（用户报障：点了返回胶囊不消失）。
            // 现在点击即收起，真跳出去了再由 onPageStarted / doUpdateVisitedHistory 校准。
            capsule.visibility = View.GONE
            loadLocalUrl(target)
        }
    }

    /**
     * 顶部胶囊：**只在对话页的预览模式下出现**（「← 主页」）。
     *
     * 它曾经还兼任「引擎未就绪时说明原因」（§4.5），但实践证明这一半是多余的：
     * 引擎只要不就绪，加载页就会盖住对话页，原因已经写在加载页上了；
     * 再浮一枚胶囊反而重复（用户报障：启动页顶部多了一枚「引擎启动中」胶囊）。
     * 所以状态那一半已删除 —— 要改回来看 §4.5 与 PITFALLS J2 的教训。
     */
    private fun renderCapsule() {
        if (pageIndex == 0 && previewMode) {
            capsuleText.text = getString(R.string.btn_back)
            capsule.isClickable = true
            capsule.visibility = View.VISIBLE
            return
        }
        capsule.isClickable = false
        capsule.visibility = View.GONE
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
        splashVisible = show
        // 底栏跟着启动画面走：盖着时隐藏，退场时和页面一起淡入
        updateBarVisibility()
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

    /** 解压进度：null = 不确定态（进度条来回扫），有值 = 真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        installProgress.progress = frac
    }

    private fun render(state: EngineSupervisor.State) {
        // 引擎侧还没就绪 → 把加载页盖回来（只盖对话页，底栏与设置/扩展页照常可用）
        if (state !is EngineSupervisor.State.Healthy && state !is EngineSupervisor.State.SafeMode) {
            urlLoaded = false
            setLoading(true)
        }
        // 转圈和进度条二选一：启动/安装这类"有进度可量"的阶段显示进度条，其余显示转圈
        val busy = state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting
        installProgress.visibility = if (busy) View.VISIBLE else View.GONE
        loadingSpinner.visibility = if (busy) View.GONE else View.VISIBLE
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

        /** 底栏：药丸平移 460ms（只动 translationX） */
        private const val PILL_MS = 460L

        /** 启动画面退场后底栏淡入的时长（和页面一起出现，别在加载时就冒出来） */
        private const val BAR_FADE_MS = 260L

        /** 顶部胶囊离状态栏的间距（全屏后由代码加上状态栏高度） */
        private const val CAPSULE_TOP_DP = 10

        /** 切页过渡：260ms 交叉淡入淡出 + 屏宽 6% 的横向位移 */
        private const val PAGE_MS = 260L
        private const val PAGE_SHIFT_RATIO = 0.06f

        private val pageInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** 底栏三个格子：内容 id → 图标 id → 文字 id（顺序即药丸的格子顺序） */
        private val navCells = listOf(
            Triple(R.id.navChat, R.id.navChatIcon, R.id.navChatText),
            Triple(R.id.navExt, R.id.navExtIcon, R.id.navExtText),
            Triple(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsText),
            Triple(R.id.navAbout, R.id.navAboutIcon, R.id.navAboutText),
        )

        /** 导航未选中 / 选中态颜色（未选中值是 @color/nav_off，两处要一致） */
        private val NAV_OFF = 0xFF7A8292.toInt()
        private val NAV_ON = 0xFFFFFFFF.toInt()

        private val pillInterpolator = PathInterpolator(0.32f, 1.28f, 0.36f, 1f)

        /** 网页底色：纯白，与底栏留白区一致，避免加载前闪一下（§4.4） */
        private val WEBVIEW_BG = 0xFFFFFFFF.toInt()

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
