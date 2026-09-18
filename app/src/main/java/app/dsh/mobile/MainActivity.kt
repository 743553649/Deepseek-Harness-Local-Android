package app.dsh.mobile

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * WebView 容器。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面。
 *
 * 导航（浅色改版）：顶部常驻导航栏（左汉堡 + 引擎状态），左侧抽屉承载引擎信息与功能入口；
 * 抽屉展开时内容层等比缩小退后、遮罩淡入。原「半透明悬浮工具栏 + 可拖把手」已整体移除，
 * 引擎状态与设置入口分别落到导航栏和抽屉里。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var drawerStatus: TextView
    private var urlLoaded = false

    // —— 引擎就绪前的加载页 ——
    private lateinit var loading: View
    private lateinit var loadingStatus: TextView

    // —— 抽屉相关：stage 是会被整体「退后」的内容层（导航栏 + 网页 + 进度条）——
    private lateinit var stage: View
    private lateinit var drawer: View
    private lateinit var scrim: View
    private var drawerWidthPx = 0
    private var drawerProgress = 0f
    private var drawerAnimator: ValueAnimator? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val drawerInterpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)

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

        drawerStatus = findViewById(R.id.drawerStatus)
        loading = findViewById(R.id.loading)
        loadingStatus = findViewById(R.id.loadingStatus)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()
        setupDrawer()
        // 读回用户保存的页面缩放与横竖屏偏好
        readUiPrefs()
        if (landscapeMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // 抽屉头部常驻的引擎信息（原悬浮工具栏展示的内容）
        findViewById<TextView>(R.id.drawerAddress).text =
            getString(R.string.drawer_engine_address, "http://127.0.0.1:${EngineConfig.PORT_BASE}")
        findViewById<TextView>(R.id.drawerVersion).text =
            getString(R.string.drawer_engine_version, packageVersion())

        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            // 0.1.5+：优先用引擎宣布的带 token 入口（会话 cookie 可能已过期，重走 token 链换新）
            val sup = (application as DshApp).supervisor
            loadLocalUrl(sup.healthyWebUrl ?: "http://127.0.0.1:${sup.healthyPort}/")
        }

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
        // singleTask：从通知或设置页回来时抽屉可能还开着，收一下（不播动画）
        if (drawerProgress > 0f) setDrawerOpen(false, animate = false)
        // 从设置页返回：重新读取横竖屏/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldLandscape = landscapeMode
        readUiPrefs()
        if (oldLandscape != landscapeMode) {
            setDesktop(landscapeMode)
            requestedOrientation =
                if (landscapeMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (oldScale != pageScale || oldLandscape != landscapeMode) {
            webView.reload()
        }
    }

    /** 读取用户持久化的横竖屏与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        landscapeMode = p.getBoolean(KEY_LANDSCAPE, false)
    }

    /** 从包管理器读 versionName（AGP 8+ 默认关闭 BuildConfig，本工程统一这么做） */
    private fun packageVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun setupWebView() {
        defaultUa = webView.settings.userAgentString
        // 浅色底：WebView 加载完成前透出的是自己背景而不是 windowBackground，避免闪一下深色
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
     * http://127.0.0.1:PORT 链接）时，导航栏亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    private fun updatePreviewChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        val preview = loopback && uri.port != enginePort
        findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        webView.loadUrl(url)
    }

    // ---------------- 导航栏 + 抽屉 ----------------

    private fun setupDrawer() {
        stage = findViewById(R.id.stage)
        drawer = findViewById(R.id.drawer)
        scrim = findViewById(R.id.scrim)

        drawerWidthPx = drawerWidth()
        drawer.layoutParams.width = drawerWidthPx
        drawer.requestLayout()

        findViewById<View>(R.id.btnMenu).setOnClickListener { setDrawerOpen(true) }
        scrim.setOnClickListener { setDrawerOpen(false) }
        setupDrawerTouch()
        renderDrawer(0f)
    }

    /** 抽屉宽度按屏宽比例算，不写死 dp（兼容平板与横屏） */
    private fun drawerWidth(): Int =
        (resources.displayMetrics.widthPixels * DRAWER_WIDTH_RATIO).toInt()

    private fun setDrawerOpen(open: Boolean, animate: Boolean = true) {
        drawerAnimator?.cancel()
        val target = if (open) 1f else 0f
        if (!animate) {
            renderDrawer(target)
            return
        }
        drawerAnimator = ValueAnimator.ofFloat(drawerProgress, target).apply {
            duration = DRAWER_DURATION_MS
            interpolator = drawerInterpolator
            addUpdateListener { renderDrawer(it.animatedValue as Float) }
            start()
        }
    }

    /** 抽屉进度 0=关闭、1=全开：统一驱动「内容层退后 + 遮罩 + 抽屉位移」三件事 */
    private fun renderDrawer(p: Float) {
        drawerProgress = p.coerceIn(0f, 1f)
        val recede = 1f - (1f - RECEDE_SCALE) * drawerProgress
        stage.scaleX = recede
        stage.scaleY = recede
        stage.translationX = resources.displayMetrics.widthPixels * STAGE_SHIFT_RATIO * drawerProgress
        drawer.translationX = -drawerWidthPx * (1f - drawerProgress)
        // 完全关闭时必须隐藏遮罩，否则它会挡住网页的一切触摸
        scrim.visibility = if (drawerProgress > 0f) View.VISIBLE else View.GONE
        scrim.alpha = SCRIM_ALPHA * drawerProgress
    }

    /**
     * 抽屉手势：菜单项一律不设 clickable，点击与「按住右滑关闭」都在这里统一分发。
     * 监听挂在抽屉上而不是根容器 —— WebView 会吃掉横向 MOVE，挂在根容器收不到。
     * 不做「从左边缘滑开」：Android 10+ 手势导航把左边缘判给系统返回，会打架。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrawerTouch() {
        drawer.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawerAnimator?.cancel()
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchStartX).coerceAtLeast(0f)
                    renderDrawer(1f - dx / drawerWidthPx)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - touchStartX
                    val dy = ev.rawY - touchStartY
                    if (abs(dx) < DRAG_SLOP && abs(dy) < DRAG_SLOP) {
                        handleDrawerItem(ev.x, ev.y)
                    } else {
                        setDrawerOpen(drawerProgress > DRAWER_SNAP_RATIO)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    setDrawerOpen(drawerProgress > DRAWER_SNAP_RATIO)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 命中哪个菜单项就执行哪个动作（省掉给每一项加 clickable）。
     *
     * 坐标坑（这就是"菜单点了没反应"的原因）：菜单项挂在「菜单容器」下、容器还有内边距，
     * getHitRect() 给的是**相对各自父容器**的坐标（y 从 10dp 起），而触摸事件的 x/y 是
     * **相对抽屉**的坐标（y 要从抽屉头约 150dp 起算）—— 两套坐标系差了一个抽屉头的高度，
     * 永远比不中。统一用 getLocationInWindow() 换算到窗口坐标再比。
     */
    private fun handleDrawerItem(x: Float, y: Float) {
        val drawerLoc = IntArray(2)
        val itemLoc = IntArray(2)
        drawer.getLocationInWindow(drawerLoc)
        for ((id, action) in drawerActions) {
            val item = findViewById<View>(id)
            item.getLocationInWindow(itemLoc)
            val left = (itemLoc[0] - drawerLoc[0]).toFloat()
            val top = (itemLoc[1] - drawerLoc[1]).toFloat()
            if (x >= left && x < left + item.width && y >= top && y < top + item.height) {
                action()
                return
            }
        }
    }

    private val drawerActions: List<Pair<Int, () -> Unit>>
        get() = listOf(
            R.id.drawerItemChat to { setDrawerOpen(false) },
            R.id.drawerItemSettings to { openPage(SettingsActivity::class.java) },
            R.id.drawerItemExt to { openPage(ExtensionStoreActivity::class.java) },
            R.id.drawerItemAbout to { openPage(AboutActivity::class.java) },
            R.id.drawerItemRestart to {
                // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载引擎页
                urlLoaded = false
                (application as DshApp).supervisor.restart()
                setDrawerOpen(false)
            },
        )

    /** 抽屉里点开独立页面：先收抽屉再跳页 */
    private fun openPage(cls: Class<*>) {
        setDrawerOpen(false)
        startActivity(Intent(this, cls))
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

    /** 只改桌面渲染的 UA + 手势缩放开关，不 reload——reload 统一由旋转/onResume 回调做（避免双重整页重载卡顿）。
     *  viewport 改写统一在 onPageFinished 里做。 */
    private fun setDesktop(enable: Boolean) {
        desktopMode = enable
        webView.settings.userAgentString = if (enable) DESKTOP_UA else defaultUa
        applyZoomControls(enable)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → 需要重算适配视口（reload 后 onPageFinished 会按当前模式
        // 与 pageScale 重写 viewport meta）。竖屏旋转屏宽同样变，故统一 reload。
        // 同时同步手势缩放开关（竖屏锁死、桌面保留），防止切屏后对手势失效。
        applyZoomControls(desktopMode)
        // 抽屉宽度与退后位移都按屏宽算，屏宽变了要重算并收起
        drawerWidthPx = drawerWidth()
        drawer.layoutParams.width = drawerWidthPx
        drawer.requestLayout()
        renderDrawer(0f)
        webView.reload()
    }

    /**
     * 竖屏视口缩放 JS：把 viewport meta 写成
     * `width=device-width, initial-scale=R, minimum-scale=R, maximum-scale=R, user-scalable=no`。
     * - width=device-width → 布局宽=屏宽，不产生横向滚动；
     * - initial-scale=R   → 浏览器 Ctrl- 式显示缩放（R<1 缩小看更多，R>1 放大）；
     * - min=max=R         → 锁死缩放（既不会被 clamp 回 100%，也不许用户手动捏合）；
     * ratio 由用户 pageScale 推导，范围 0.5–1.5。
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
     * 重新盖回去由 render() 负责（引擎侧一旦不就绪就复位 urlLoaded 并把加载页盖回来）。
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
        // 引擎侧还没就绪 → 把加载页盖回来，并复位 urlLoaded：
        // 等重新就绪时 Healthy 分支会再加载一次引擎页，加载完成由 onPageFinished 收起加载页。
        if (state !is EngineSupervisor.State.Healthy && state !is EngineSupervisor.State.SafeMode) {
            urlLoaded = false
            setLoading(true)
        }
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        // 同一份状态文字同时喂导航栏与抽屉头部
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
        drawerStatus.text = text
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
        // 抽屉开着时先收抽屉：不碰 WebView 历史，更不能改成 finish()。
        // WebView 有历史则先回退，保持类原生浏览体验；没有历史可退时**退到后台**，而不是结束任务（Termux 同款）。
        // 区别很关键：结束任务会触发 EngineService.onTaskRemoved（= 用户显式退出：停引擎 + 收岛），
        // 而按返回键只是"离开界面"，引擎与流体云应当继续常驻。
        if (drawerProgress > 0f) {
            setDrawerOpen(false)
            return
        }
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        uiScope.cancel()
        drawerAnimator?.cancel()
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

        /** 抽屉：宽度占屏宽 78%；展开/收起 280ms，曲线 0.32,0.72,0,1（丝滑出场、无回弹） */
        private const val DRAWER_WIDTH_RATIO = 0.78f
        private const val DRAWER_DURATION_MS = 280L

        /** 抽屉展开时内容层退后：缩到 88% + 右移 6% 屏宽；遮罩最深 28% 黑 */
        private const val RECEDE_SCALE = 0.88f
        private const val STAGE_SHIFT_RATIO = 0.06f
        private const val SCRIM_ALPHA = 0.28f

        /** 加载页淡出时长（引擎就绪 + 网页首帧渲染完成之后） */
        private const val LOADING_FADE_MS = 320L

        /** 手指位移小于此值视为点击；松手时展开超过 35% 就吸附到打开 */
        private const val DRAG_SLOP = 12f
        private const val DRAWER_SNAP_RATIO = 0.35f

        /** 网页底色：与 windowBackground 一致，避免加载前闪一下 */
        private val WEBVIEW_BG = 0xFFF2F4F7.toInt()

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
