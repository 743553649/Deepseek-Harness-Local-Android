# UI 改版设计文档 · 液态玻璃 + 底部导航

> **状态**：**已实现并真机验证**（v1.2.36 ~ v1.2.41）。⚠️ 实现过程中用户又改了几处设计，
> 最终形态与本文的「定稿」有差异 —— **以 §9 为准**，前面各节保留的是当初的设计思路与依据。
> **适用仓库**：`Deepseek-Harness-Local-Android`（DSH Mobile Dev，共存版 fork）
> **覆盖范围**：主界面导航结构改版 + 设置页 / 扩展中心 / 关于页视觉重做

---

## 0. 一分钟上手（给新窗口的 Agent）

要做四件事（**四件都已完成，见 §9**）：

1. ✅ **删掉左侧抽屉和顶部汉堡导航栏**（`activity_main.xml` 里的 `navbar` / `drawer` / `scrim` 三块 + `MainActivity` 里全套抽屉逻辑）。
2. ✅ **加一个底部导航**：三个目的地 —— 对话 / 扩展 / 设置（**最终是四个**，多了「关于」，且玻璃条铺满底栏）。
3. ✅ **「引擎信息」并进设置页**：设置页顶部一张引擎卡片（状态 / 地址 / 版本 / 重启引擎）。
   「关于」最终**没有并进设置页**，而是单独占底栏第四格（用户要求）。
4. ✅ **设置页、扩展中心和关于页按液态玻璃重做**（玻璃面板、玻璃开关、玻璃按钮）。

**设计参考实现（先看这个，再动手）**：

| 文件 | 内容 |
|---|---|
| `/data/user/0/app.dsh.mobile/files/dsh-home/work/dsh-mobile-ui/live.html` | **定稿**：对话页嵌真实引擎界面（`127.0.0.1:3080`） |
| `…/work/dsh-mobile-ui/final.html` | 同一版设计，对话页用假数据（排版看得更清楚） |
| `…/work/dsh-mobile-ui/liquid.css` | **所有设计令牌的权威实现**（颜色 / 尺寸 / 圆角 / 阴影 / 动效） |
| `…/work/dsh-mobile-ui/ui.css` | 页面骨架 + 引擎界面 mock（三个方向共用） |
| `…/work/dsh-mobile-ui/serve.js` | 预览服务器 |

预览服务器可能已随上一个会话结束而停掉，重启命令：

```bash
cd /data/user/0/app.dsh.mobile/files/dsh-home/work/dsh-mobile-ui && node serve.js
# 然后打开 http://127.0.0.1:3210
```

⚠️ 这些文件在 **DSH 的 work 目录**里，不在仓库内，不要提交进仓库。要长期留着就复制到 `design/` 下再决定是否入库。

---

## 1. 定稿长什么样

```
┌────────────────────────────────┐
│  WebView（引擎的对话网页）        │  ← 全屏，顶部不加任何东西
│                                │
│  …对话内容…                     │
│  ┌──────────────────────────┐  │
│  │ 输入框          [发送]     │  │  ← 引擎网页自己的输入条
│  └──────────────────────────┘  │
│                                │  ← 72dp 纯白留白区（不是灰条！）
│      ╭──────────────────╮      │
│      │ 对话   扩展   设置 │      │  ← 174×45dp 悬浮玻璃岛
│      ╰──────────────────╯      │
└────────────────────────────────┘
```

三个页面：

| 页面 | 内容 |
|---|---|
| **对话** | 纯 WebView。**不放任何 App 自己的东西**（不放状态条、不放标题） |
| **扩展中心** | 一块玻璃面板装扩展列表，状态点 + 玻璃按钮 |
| **设置** | 引擎卡片（主角）→ 显示 → 权限中心 → 关于 |

导航只有三个格子，理由：这是真正会反复去的地方。「关于」并进设置，「重启引擎」并进设置页的引擎卡片，都不占格子。

---

## 2. 设计令牌（可直接抄进 Android）

### 颜色

| 用途 | 值 | 备注 |
|---|---|---|
| 主色 | `#96B9F6` | 用在**面**上：开关打开态、主按钮底、扩展图标块 |
| 主色·深 | `#7BA6F0` | 渐变的下一档（按钮/开关的下沿） |
| 主色·文字 | `#2E5AA8` | **白底上的彩色文字必须用它**，`#96B9F6` 直接当文字色对比度只有约 2:1，看不清 |
| 主色·按钮字 | `#12325F` | 浅蓝底上**不能写白字**，会糊；用深蓝 |
| 墨（正文） | `#0F1216` | |
| 次（次要文字） | `#6E7686` | |
| 淡（占位/未下载） | `#9AA2B0` | |
| 细线 | `rgba(15,18,22,.06)` | 行分隔 |
| 状态·可用 | `#1F9D55` | 绿：已激活可用 / 引擎已就绪 |
| 状态·未激活 | `#C08A18` | 黄：已下载未激活 |
| 页面底色 | `#F5F7FB` → `#E9EDF5` | 竖渐变 |
| 光晕 | `#96B9F6` @ 14~20% 不透明度 | 左上、右中、下方各一团（radial gradient） |

> **状态色不要改成蓝**：绿/黄/灰三态是仓库 `strings.xml` 里写明的既有约定（红=未下载 黄=已下载未激活 绿=已激活可用），换掉会丢语义。

### 尺寸

| 元素 | 值 |
|---|---|
| 底栏占位区高度 | **72dp**（含底部 14dp 间距） |
| 玻璃岛 | **174 × 45dp**，圆角 24dp，1dp 描边 `#C7FFFFFF` |
| 岛内药丸（选中态） | 高 39dp，圆角 21dp，色 `rgba(18,21,26,.94)` → `.80` 竖渐变 |
| 导航图标 | 17dp（描边 1.8dp） |
| 导航文字 | 9.5sp（`sans-serif-medium`） |
| 玻璃面板圆角 / 行高 | 22dp / 56dp（左右内边距 16dp） |
| 引擎卡片 | 圆角 26dp，内边距 18/18/4 |
| 开关 | 44 × 26dp，圆钮 22dp |
| 按钮 | 高 34dp，圆角全圆；图标块 34dp / 圆角 12dp |

> 岛是「原设计 248×64 再压 30%」的结果。**别再放大**：真实引擎界面的排版密度比我画的 mock 更高，岛一大就会顶到输入框。

### 字体

- 界面文字：系统 sans（`sans-serif` / `sans-serif-medium`）。
- 端口、版本、状态这类**机器数据**：`monospace`。
- **不要引入字体文件**（体积 + 授权），Android 端用系统字体即可。
- 分组标题 11.5sp / 权重 600 / 字距 .02em；行标签 14sp；行值 12.5sp。

### 动效

- 药丸在三个格子间平移：460ms，`cubic-bezier(.32,1.28,.36,1)`（带轻微过冲），只动 `translationX`。
- 状态点「呼吸」：2.4s 循环，仅用在引擎卡片的状态点上（全页唯一一处自发动画）。
- **必须尊重系统的「减少动态效果」**：`Settings.Global.ANIMATOR_DURATION_SCALE == 0` 时不播动画。

---

## 3. Android 端怎么画（逐项）

### 3.1 玻璃条 `bg_glass_bar.xml`（初版叫 `bg_glass_island.xml`，v1.2.40 已删）

> v1.2.40 起底栏铺满、不留边距、直角，所以下面这份「圆形悬浮岛」的 layer-list
> 只保留作参考；实际文件 `bg_glass_bar.xml` 是「竖渐变 + 顶沿 1dp 高光」，
> 且透明度更高（44%→24%）。

```xml
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 外圈 1dp 白描边：玻璃的边 -->
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#C7FFFFFF"/>
            <stroke android:width="1dp" android:color="#C7FFFFFF"/>
            <corners android:radius="24dp"/>
        </shape>
    </item>
    <!-- 内层：72%→54% 白竖渐变（angle=270 才是从上到下） -->
    <item android:left="1dp" android:top="1dp" android:right="1dp" android:bottom="1dp">
        <shape android:shape="rectangle">
            <gradient android:type="linear" android:angle="270"
                      android:startColor="#B8FFFFFF" android:endColor="#8AFFFFFF"/>
            <corners android:radius="23dp"/>
        </shape>
    </item>
    <!-- 顶部 1dp 高光线 -->
    <item android:gravity="top" android:top="2dp" android:height="1dp"
          android:left="14dp" android:right="14dp">
        <shape android:shape="rectangle">
            <solid android:color="#F2FFFFFF"/>
            <corners android:radius="1dp"/>
        </shape>
    </item>
</layer-list>
```

**阴影**分两级做，别一开始就上重活：
1. 先给岛设 `android:elevation="12dp"` + `outlineProvider` 用背景（shape 自带圆角 outline）。便宜，但阴影颜色随主题、扩散小。
2. 效果不够再叠一层自绘阴影 drawable（多层半透明圆角矩形，向下偏移 6/12/20dp，透明度递减）。**不要用 9-patch**，没必要。

### 3.2 药丸滑动

用属性动画动 `translationX`（`ObjectAnimator` / `ValueAnimator`），插值器 `PathInterpolator(0.32f, 1.28f, 0.36f, 1f)`。药丸宽度 = 岛内容宽 / 3，别写死 dp。

### 3.3 玻璃面板 / 引擎卡片

同一套 layer-list：白 80%→58% 渐变 + 1dp 白描边 + 圆角 22dp（卡片）/ 26dp（引擎卡片）+ 内层斜向高光（可以省略，Android 里做斜向高光要写 `LinearGradient` 角度，收益不大）。

### 3.4 开关

不要用 `Switch`/`SwitchCompat`（样式改不动、且仓库没有 Material 依赖）。**自绘**：两个 View（轨道 + 圆钮）+ `translationX` 动画，可点击切换。轨道开 = `#96B9F6`→`#7BA6F0` 渐变，关 = `rgba(15,18,22,.10)`。

### 3.5 页面底色

`<gradient>` 竖渐变 + 三团 radial gradient 光晕（layer-list，`<gradient android:type="radial" android:gradientRadius="...">`）。**光晕不是装饰**：玻璃垫在纯色上会看起来像白板，必须有东西可折射。

### 3.6 系统栏

`themes.xml` 里 `statusBarColor` / `navigationBarColor` / `windowBackground` 现在是 `#FFF2F4F7`，新底色是 `#F5F7FB` → **必须一起改**，否则顶部系统栏和页面之间会有一条色差。
`forceDarkAllowed=false`、`windowLightStatusBar=true` 保持不变。

> **v1.2.42 起主界面改成全屏**：窗口覆盖到状态栏底下，`statusBarColor` 在代码里改成**透明**，
> 内容整体下移一个状态栏高度（`MainActivity.setupEdgeToEdge` / `applyStatusBarInset`）——
> 状态栏那一块显示的是**页面自己的底色**。引导页不开全屏，仍用 `themes.xml` 里的底色。
> 注意：引擎网页能看到的区域高度**没变**（窗口多了一截，内容又下移同样多）。

---

## 4. 把引擎网页搬进 App 的注意事项（重点）

> 这一节是本项目最容易翻车的地方。逐条都有"为什么"。

### 4.1 必须用「引擎宣布的带 token 地址」，不要自己拼 URL

`MainActivity` 现在用的是：

```kotlin
loadLocalUrl(sup.healthyWebUrl ?: "http://127.0.0.1:${sup.healthyPort}/")
```

**不要把它简化成裸 `http://127.0.0.1:3180/`**。dsh 0.1.5 起 WebUI 强制浏览器会话认证，裸 `/` 只有 401（正文是 `dsh web authentication required; reopen the URL printed by dsh web.`）；token 是**进程级**的，引擎重启就换新的。详见 `docs/ARCHITECTURE.md`「WebUI 会话认证」。
（现象上还有一种"假就绪"：健康检查把 401 也算"有响应"，所以端口通了 ≠ 页面打得开。）

### 4.2 底栏**绝对不能盖住网页的输入框**

引擎网页最底下就是输入框 + 发送键。做法：用 `LinearLayout` 竖向排 `WebView(weight=1)` + `底栏区(72dp)`，**让 WebView 自己变矮**，而不是让玻璃岛浮在 WebView 上面。
这就是「无缝」的全部秘密：留白区底色设成**纯白**，和网页白底连成一片，交界处不画任何线 → 之前那版"两条横线叠一起"就是这么消掉的。

- ❌ 别用 `FrameLayout` 把岛叠在 WebView 上（会挡住发送键）。
- ❌ 别给 WebView 设 `paddingBottom`（WebView 的 padding 不改变网页视口，网页不会让位）。
- ❌ 别往引擎网页注入 CSS/JS 去"腾出底部空间"（引擎升级就会失效，失效时正好压住发送键，属于埋雷）。**如果哪天真要做，必须带兜底：注入后检测，失败就退回不覆盖。**

### 4.3 真·模糊在 Android 上做不到（重要，别被预览骗了）

预览里的模糊是浏览器 `backdrop-filter` 做的。Android 上：

- `View.setRenderEffect(blur)` 模糊的是**这个 View 自己**，不是它背后的东西。
- `Window.setBackgroundBlurRadius()` 只对**独立窗口**（Dialog / PopupWindow）生效，而且 **API 31+**，机型差异大；对 WebView 内容是否生效不可靠。
- 截屏后模糊再贴回去：滚动时要持续重截，性能灾难，不推荐。

**结论**：玻璃的观感靠 **半透明 + 渐变 + 1dp 高光描边 + 柔影** 实现，这套在任何机型都能做。真正的模糊是**可选增强**，先不做，把 drawable 抽出来留着以后换。
→ 也就是说：**别把"内容从玻璃底下滑过被糊住"当成验收标准**，它在真机上大概率不会发生。

### 4.4 WebView 背景色必须显式设白

`webView.setBackgroundColor(Color.WHITE)`。默认背景在页面首帧前可能是透明白或系统色，会在留白区闪一下，破坏"无缝"。

### 4.5 ~~引擎状态文本搬家后，要留一个"出问题时看得见"的出口~~（状态那一半已回退，v1.2.43）

> **后续变化**：胶囊的「说明原因」这一半**已删除**，只保留「← 主页」（§4.6）。
> 原因：引擎只要不就绪，加载页就会盖住对话页、并把原因写在加载页上，
> 再浮一枚胶囊纯属重复表达（用户报障「启动页顶部多了个浮岛显示引擎启动中」）。
> 下面是当初的设计理由，保留备查。

现在引擎状态文字在顶部导航栏里（`statusBar`，由 `EngineSupervisor.state` 驱动）。搬进设置页之后：

- 引擎 `Backoff` / `Failed` 时用户只看到空白对话页，不知道发生了什么。
- **建议**：状态 ≠ Ready 时，在对话页顶部浮一枚玻璃小胶囊（「引擎启动中…」/「进程异常退出，%d 秒后重启」）。正常时不显示。这个胶囊是**唯一**允许出现在对话页上的 App 元素。
- 设置页的引擎卡片要能拿到状态：设置页是独立 Activity，拿不到 `MainActivity` 的 collector → 打开设置页时从 `(application as DshApp).supervisor` 取一次快照，并在 `onResume` 刷新。

### 4.6 「预览模式返回按钮」要重新安置

`btnBack` 现在也在顶部导航栏里（WebView 落在非引擎端口的回环页面时才显示）。导航栏删掉后它没有家了。
**建议**：同一枚「引擎状态胶囊」兼任——预览模式时变成「← 主页」（复用 `healthyWebUrl` 重取，见 `ARCHITECTURE.md` 关于会话 cookie 过期的说明）。

### 4.7 横屏 / 桌面布局要一起验

设置里有「横屏模式」（锁横屏 + 引擎侧渲染桌面布局）。174dp 的岛在横屏里会显得很小、离手指很远。
**建议**：横屏时岛宽按屏宽算（约 34%），并保持居中；三种模式（竖屏 / 横屏 / 桌面布局）都要点一遍。

### 4.8 系统栏与手势区

`targetSdk = 28`，窗口**不延伸到系统栏下面**，所以底栏不需要额外处理 inset。
→ **不要顺手开 edge-to-edge / `setDecorFitsSystemWindows(false)`**，那会连带影响引擎页面的安全区，属于另一件事。

### 4.9 不要碰流体云状态岛的生命周期不变量

`EngineService` 的前台通知、退出顺序、`START_NOT_STICKY` 等（`AGENTS.md` 与 `docs/PITFALLS.md` I 节）。这次改版**完全不需要动 `EngineService`**，如果发现"非动不可"，先停下来核对 §I1~I6。

### 4.10 ~~别删 AboutActivity，先留着~~（已在 v1.2.39 删除）

> 后续变化：「关于」后来没并进设置页，而是单独占底栏第四格 —— 于是
> `AboutActivity.kt` + `activity_about.xml` 被 `AboutPage.kt` + `view_about.xml` 取代，
> v1.2.39 连同 Manifest 条目一起删掉了。下面是当初的取舍记录。

「关于」并进设置页后，`AboutActivity` 与 `activity_about.xml` 就成了没入口的死代码。**第 1 步不要删**——删 Activity 要动 Manifest，一次改动别做两件事。确认新版跑通后再单独清理。

### 4.11 别碰官方版的数据

手机上有两个 App：官方版 `DSH Mobile`（装着全部真实会话，**一个字都别动**）和自编译的 `DSH Mobile Dev`。装包、卸载、测试一律只针对 **Dev 版**。

---

## 5. 分两步走

**第 1 步（本次）**：主界面有底栏；**设置 / 扩展中心仍是独立 Activity**（进去后是整页，没有底栏，靠返回箭头回来）。
→ 改动小、风险低，视觉一次到位。

**第 2 步（后续，另开一次）**：让底栏常驻——把设置/扩展改成同一个 Activity 内的视图切换。
→ 要处理返回栈、引擎状态回调、以及 `MainActivity.onResume` 里那一堆偏好同步逻辑，属于结构性改动，单独做。

---

## 6. 要改的文件

> ⚠️ 本表是**开工前的改动计划**。文件后来有增删（多个 Activity 被删、页面拆成 include），
> **最终文件清单以 §9.3 为准**；这里的行内批注只标了最容易被误导的那几处。

| 文件 | 动作 |
|---|---|
| `app/src/main/res/layout/activity_main.xml` | 删 `navbar`（含 `btnMenu`/`statusBar`/`btnBack`）、`drawer`、`scrim`；加底部玻璃岛；`loading` 覆盖层保留 |
| `app/src/main/java/app/dsh/mobile/MainActivity.kt` | 删抽屉全套：`setupDrawer`(241) `drawerWidth`(257) `setDrawerOpen`(260) `renderDrawer`(276) `setupDrawerTouch`(294) `handleDrawerItem`(335) `drawerActions`(351) 及相关字段/常量；`onResume` 里 `drawerProgress` 那句(130)一并去掉；`btnBack` 逻辑挪到新胶囊 |
| `app/src/main/res/layout/activity_settings.xml` | 整页重做：引擎卡片 → 显示 → 权限中心 → 关于（**该文件与下面的 Activity 已在 v1.2.36 删除**，现在是 `view_settings.xml` + `SettingsPage.kt`，见 §9） |
| `app/src/main/java/app/dsh/mobile/SettingsActivity.kt` | 接引擎信息（状态/地址/版本）+ 重启引擎 + 关于区块（已删除 → `SettingsPage.kt`） |
| `app/src/main/res/layout/activity_extension_store.xml` | 列表包进玻璃面板；**行本身是代码拼的**（**已删除** → `view_extensions.xml` + `ExtensionPage.kt`） |
| `app/src/main/java/app/dsh/mobile/ExtensionStoreActivity.kt` | 行样式改玻璃风；**保留现有图标做法**（见下）（已删除 → `ExtensionPage.kt`） |

### 6.1 扩展图标不要改掉

`ExtensionPage.kt`（原 `ExtensionStoreActivity.kt:133-147`）现在的做法是：从 `catalog.json` 的 `iconRes` 取 17 个官方品牌矢量图
（Python / Git / Rust…），统一 `SRC_IN` 染成白色剪影，垫在一块 `bg_icon_chip` 上，chip 颜色由
`categoryColor(ext.category)` 按分类给色。

**保持这个做法**，只改 chip 的圆角（12dp）和尺寸（36dp），别按预览里的"文字缩写（Py/Git）"去做 ——
预览里写缩写是因为我懒得画 14 个图标，真机上现成的图标更有信息量。白色剪影 + 彩色 chip 的对比度也是对的。

### 6.2 色值是硬编码散落的（本次必须一起改）

仓库没有 `colors.xml`，颜色直接写在 XML 和 Kotlin 里，旧主色 `#2F6BFF` 出现在 **9 个文件**：

| 文件 | 说明 |
|---|---|
| `res/layout/activity_main.xml`（5 处） | 主界面，本次重做 |
| `res/layout/activity_settings.xml`（4 处） | 本次重做 |
| `res/layout/activity_onboarding.xml`（3 处）+ `OnboardingActivity.kt:161` | 首次引导页，**不在本次范围**，但主色变了会显得不一致 —— 建议本次一起换色（只换色，不改排版） |
| `res/layout/activity_about.xml`（1 处）+ `res/drawable/bg_btn_accent.xml`（1 处） | 关于页 / 强调按钮底 |
| `SettingsPage.kt`（原 `SettingsActivity.kt:250-251`） | 给进度条/滑块上色的两行，**必须改**，否则设置页里会跳出旧蓝 |
| `ExtensionPage.kt`（原 `ExtensionStoreActivity.kt`，3 处） | 下载进度条 + 文字色等 |
| `engine/AgentBridge.kt:234` | ⚠️ **这不是 App 界面**，是桥接服务返回的一张 HTML 诊断页的样式。**本次不要动**，要改也是单独一次 |

建议：先建 `res/values/colors.xml` 把 §2 的颜色落成资源，再把上面这些**受影响的位置**换成引用。
不要在本次顺手重构所有硬编码颜色 —— 范围会失控。
| `app/src/main/res/drawable/` | 新增 `bg_glass_island.xml`（**后被 `bg_glass_bar.xml` 取代**）`bg_glass_card.xml` `bg_nav_pill.xml` `bg_switch_track.xml`（**实际没建：开关是代码自绘的**，见 `GlassSwitch.kt`）`ic_nav_chat.xml` `ic_nav_ext.xml` `ic_nav_settings.xml`（+ 后加的 `ic_nav_about.xml`）`bg_glass_pill.xml` `bg_glass_hero.xml` `bg_page.xml`；旧的 `bg_drawer.xml` `ic_menu.xml` 变成死文件（留着没删） |
| `app/src/main/res/values/colors.xml` | **新建**：把 §2 的颜色落成资源 |
| `app/src/main/res/values/themes.xml` | `statusBarColor` / `navigationBarColor` / `windowBackground` → `#FFF5F7FB` |
| `app/src/main/res/values/strings.xml` | 新增底栏三项、设置页分组、引擎卡片文案；旧的 `nav_menu` / `drawer_*` 系列可删 |

---

## 7. 验证清单（别只看编译过）

**先推 main 走 CI**（约 2-3 分钟），拉产物装机，然后**逐项确认**（下为 v1.2.41 的实测结果）：

- [x] 对话页：底栏和输入框之间没有多余的横线；底栏是白的，和白色网页连成一片（像素实测底栏区 `#ffffff`）
- [x] 底栏和输入框之间**不重叠**，发送键点得到（WebView 用 weight 自己变矮，不是同层叠加）
- [x] 四个格子点击正确、药丸滑动到位（对话 / 扩展 / 设置 / 关于）
- [x] 没有抽屉了：左边缘横滑、点原来的汉堡位置都没有东西出来（dex 里已无 `setupDrawer` / `drawerItem*`）
- [x] 返回键：非对话页 = 回对话页；对话页 = 退到后台，不停引擎（用户已确认）
- [x] 引擎未就绪时：启动画面盖住对话页，**底栏此时完全不出现**（像素实测底栏区无任何图标/药丸），
      加载完成后底栏与页面一起淡入
- [x] 设置页：引擎状态 / 地址 / 版本正确，重启引擎能用（且不再卡死，见 PITFALLS J1）
- [x] 扩展中心：三种状态颜色对，下载 / 激活 / 停用按钮都能用（激活/停用不再卡界面）
- [x] 切页过渡：不闪（用户已确认）
- [x] 启动页观感：自绘转圈 + 胶囊进度条（用户已确认"没问题"）
- [x] 全屏：状态栏那一块显示页面底色、内容不被状态栏压住（用户已确认"没问题"）
- [x] 启动页顶部不再有重复的状态胶囊（胶囊只在预览模式出现）
- [ ] 横屏 + 桌面布局下底栏不歪、不顶到内容 —— **待验**（这轮没测横屏）
- [ ] 系统开了「减少动态效果」时没有动画 —— 代码里已按 `ANIMATOR_DURATION_SCALE == 0` 短路，**未真机验**
- [x] 深色模式仍是关闭状态（`forceDarkAllowed=false` 未动）
- [x] `aapt2 dump badging`：包名仍是 `app.dsh.mobile.dev`

**视觉类结论只能由用户判断**（Agent 读不了图）：岛的大小、玻璃的观感、颜色对不对，必须请他确认，不能替他下结论。

---

## 8. 已知取舍与未决项

| 项 | 现状 |
|---|---|
| 对话页的玻璃是"形状玻璃"（底下是白，没有东西可糊） | **接受**。4.2 决定了它不能盖住网页，4.3 决定了真模糊不指望 |
| 设置/扩展页的真模糊 | **降级为观感玻璃**（见 4.3）。真模糊留作以后的可选增强 |
| 引擎异常状态怎么让用户看见 | 待实现（4.5 给了建议方案） |
| 预览返回按钮的新位置 | 待实现（4.6 给了建议方案） |
| 横屏时岛宽 | **已作废**：底栏改成铺满整宽，不存在"岛宽"问题（§9） |
| `AboutActivity` 死代码清理 | **已做**（v1.2.39 删除；「关于」改成底栏第四格） |
| 底栏常驻 | **已做**（v1.2.36 四页合一 + 切页过渡动画） |


---

## 9. 实际落地（v1.2.36 ~ v1.2.41）与定稿的差异

> 实现过程中用户看了真机效果后又改了几处设计。**本节是最终形态的唯一依据**；
> 前面各节保留的是当初的设计理由，凡与本节冲突的，以本节为准。

### 9.1 最终形态

```
┌──────────────────────────────────┐
│  WebView（引擎对话网页，全屏）      │
│                                  │
│  …对话内容…                       │
│  ┌────────────────────────────┐  │
│  │ 输入框              [发送]  │  │
│  └────────────────────────────┘  │
│   对话    扩展    设置    关于     │  ← 64dp 玻璃条：铺满整宽、直角、无投影
└──────────────────────────────────┘
```

四个页面都在 **MainActivity 内**（底栏常驻、切页只换可见性）：

| 页面 | 内容 | 实现 |
|---|---|---|
| 对话 | 纯 WebView（不放任何 App 元素，只有引擎未就绪时顶部那枚胶囊） | `activity_main.xml` 的 `chatPage` |
| 扩展 | 扩展列表，按分类各包一块玻璃面板 | `view_extensions.xml` + `ExtensionPage.kt` |
| 设置 | 引擎卡片（主角）→ 显示 → 权限中心 | `view_settings.xml` + `SettingsPage.kt` |
| 关于 | 项目信息 / 版本 / 开源地址 | `view_about.xml` + `AboutPage.kt` |

### 9.2 与定稿的逐条差异

| # | 定稿（§1~§8） | 最终 | 为什么改 |
|---|---|---|---|
| 1 | 三格导航，「关于」并进设置页 | **四格**，「关于」单独一格 | 用户要求，且底栏铺满后格子少了显空 |
| 2 | 174×45dp 悬浮玻璃岛（圆角 24dp + elevation 12dp） | **铺满整宽、直角、无投影、64dp 高**的玻璃条 | 用户：悬浮岛旁边一大片底色"很突兀" —— 铺满之后就没有"旁边"了 |
| 3 | 留白区涂纯白与网页"连成一片"（§4.2） | 对话页**保留纯白垫色**；其它页透明（露出页面渐变） | 用户要求"把会话界面的底栏渲染成白色"；其它页配渐变才看得出玻璃材质 |
| 4 | 玻璃白渐变 72%→54% | **44%→24%** | 用户：透明一点才看得出是液态玻璃 |
| 5 | 选中态 = 39dp 深色药丸 | 居中**胶囊**（上下各留 12dp、左右各留 8dp） | 用户：那块黑东西贴着屏幕左下/右下角"割裂感很强" |
| 6 | 启动时底栏立即可见 | **启动画面期间不显示**，加载完成后与页面一起淡入（260ms） | 用户：底栏出现时机不对 |
| 7 | 第 1 步先独立 Activity、底栏常驻留给第 2 步 | **两步一起做**（v1.2.36） | 用户：独立页面之间是"硬切"，要求直接做常驻 |
| 8 | —（没提过渡） | 切页**交叉淡入淡出 + 屏宽 6% 横向位移**（260ms） | 用户要求加动画（已确认不闪） |
| 9 | —（v1.2.38 试过"运行时取网页底色"） | **已废弃**，改成按页面固定垫色 | 取网页底色对"白 vs 蓝"的观感没帮助，代码还多一层耦合 |
| 10 | 启动页用系统 ProgressBar（转圈 + 细线进度条） | **两个都自绘**：`GlassSpinner`（轨道 + 250° 主色渐变圆弧匀速转）、`GlassProgress`（胶囊轨道 + 渐变，不确定态来回扫）；两者**二选一**，不再同时出现 | 用户：系统那套"很简陋廉价" |
| 11 | 窗口不延伸到系统栏（§4.8 明确要求不开 edge-to-edge） | **主界面开全屏**：覆盖状态栏、状态栏透明、内容下移一个状态栏高度 | 用户要求"App 全屏覆盖，状态栏也要覆盖到" |
| 12 | 顶部胶囊兼任「引擎未就绪时说明原因」（§4.5） | **只保留「← 主页」**，状态那一半删除 | 用户：启动页顶部多了一枚重复的「引擎启动中」胶囊 |

### 9.3 文件增删（相对 §6）

**新增**

- 页面：`res/layout/view_settings.xml` / `view_extensions.xml` / `view_about.xml`（由 `activity_main.xml` include）
- 逻辑：`SettingsPage.kt` / `ExtensionPage.kt` / `AboutPage.kt` / `GlassSwitch.kt`（自绘玻璃开关）/ `Motion.kt`（「减少动态效果」判定的唯一入口）/ `GlassSpinner.kt` + `GlassProgress.kt`（启动页自绘转圈与进度条）
- drawable：`bg_glass_bar.xml`（底栏玻璃）、`bg_glass_card.xml`、`bg_glass_hero.xml`（引擎卡片）、`bg_glass_pill.xml`（顶部胶囊）、`bg_nav_pill.xml`（选中态胶囊）、`bg_page.xml`（页面渐变 + 三团光晕）、`ic_nav_chat/ext/settings/about.xml`
- `res/values/colors.xml`（§2 的设计令牌收口）

**删除**

- `SettingsActivity.kt` / `ExtensionStoreActivity.kt`（v1.2.36）、`AboutActivity.kt`（v1.2.39）+ 各自的 layout + Manifest 条目
- `bg_glass_island.xml`（v1.2.40，被 `bg_glass_bar.xml` 取代）
- 抽屉遗留：`bg_drawer.xml`、`ic_menu.xml`（死文件，留着没删）

### 9.4 以后再改界面，这四条是硬约束

1. **底栏绝不能盖住网页输入框**：竖向 LinearLayout 里 WebView `weight=1` 自己变矮；不叠层、不给 WebView 设 `paddingBottom`、不往引擎网页注入 CSS/JS（§4.2）。
2. **界面里重启引擎只能用 `supervisor.restartAsync()`**：`restart()` 是同步的，会在主线程等引擎退出 5~10 秒（`docs/PITFALLS.md` J1）。
3. **玻璃开关别用 `Switch` / `SwitchCompat`**：仓库没有 Material 依赖，样式改不动 —— 用 `GlassSwitch`（§3.4）。
4. **所有动画先过 `Motion.reduced(this)`**：系统开了「减少动态效果」就全部短路成终态（§2）。
5. **全屏后的状态栏内边距由代码统一加**（`MainActivity.applyStatusBarInset`）：
   别在 XML 里给页面写死 top padding，也别给 `pageHost` 加 —— 加载页要盖到状态栏底下（全屏启动图）。

---

## 附：相关文档

- `docs/ARCHITECTURE.md` —— 引擎启动链路、WebUI 会话认证（token）、关键文件职责表
- `docs/PITFALLS.md` —— 踩坑记录（A~J 节）。改通知/引擎相关代码前必读 I 节；
  **改界面/切页/底栏前必读 J 节**（界面线程停引擎、设计前提翻车、玻璃按钮隐身、产物下载损坏）
- 仓库根 `AGENTS.md` —— 硬约束（`targetSdk=28` 不可动）、开发循环、验证纪律
