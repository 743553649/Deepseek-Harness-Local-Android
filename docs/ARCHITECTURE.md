# 架构导航（ARCHITECTURE）

> **用途**：让 Agent 不用满仓库 grep 就能定位代码。只写「哪个文件负责什么、流程怎么走」，
> 具体实现细节请读源码 —— 上游作者在代码里写了大量高质量中文注释，那才是第一手资料。
>
> 若本文与代码冲突，**以代码为准，并回来修本文**。

---

## 一句话架构

一个 Android 外壳（Kotlin）+ 一个塞进 `assets` 的 **Node 运行时**，运行时装进应用私有目录，
由外壳 fork 出 Node 进程跑完整的 DeepSeek Harness 引擎，WebView 加载 `127.0.0.1` 上的 Web UI
（dsh **0.1.5 起该 URL 必须带进程 token**，否则只有 401，见「WebUI 会话认证」）。
另有若干原生能力（无障碍读屏点击、通知、Shizuku/root 提权）以**环回 HTTP 桥**的形式暴露给引擎内的 Agent。

```
┌─ Android App (Kotlin) ────────────────────────────────┐
│  MainActivity ──WebView──► http://127.0.0.1:<端口>/?token=… │
│       │                                                │
│  EngineService（前台服务，保活）                        │
│       │                                                │
│  EngineSupervisor（状态机）                             │
│       ├─ RuntimeInstaller  ← assets/runtime.zip        │
│       ├─ EngineProcess.spawn ──fork──► node 引擎进程    │
│       ├─ AgentBridge       (环回 HTTP：通知/读屏/点击/扩展) │
│       └─ ShizukuHttpBridge (环回 HTTP：adb 身份执行)     │
└────────────────────────────────────────────────────────┘
```

---

## 引擎启动链路

按调用顺序，每一步的文件：

| # | 位置 | 做什么 |
|---|---|---|
| 1 | `DshApp.kt` | Application 入口（Manifest 的 `android:name`） |
| 2 | `MainActivity.kt` | 启动 `EngineService`，订阅状态流；Healthy 后 WebView 加载 `state.webUrl`（引擎宣布的**带认证 token 入口**，0.1.5+；未宣布时退回裸 `http://127.0.0.1:${state.port}/`） |
| 3 | `service/EngineService.kt` | 前台服务（`specialUse` 类型，规避 Android 14 的 dataSync 6 小时上限） |
| 4 | `engine/EngineSupervisor.kt` | **核心状态机**：`Idle → Installing → Starting → Healthy(port)`，失败走 `Backoff → Failed`；spawn 之前先等上一次停引擎的收尾跑完（v1.2.30 I1 —— `stop()` 的强杀走全局 PTY 句柄，新引擎先起来会被打死） |
| 5 | `engine/RuntimeInstaller.kt` | Installing 阶段：解压 `assets/runtime.zip` 到 `filesDir/engine`，恢复可执行位 |
| 6 | `engine/EngineConfig.kt` | 组装子进程环境变量（PATH / LD_LIBRARY_PATH / DSH_HOME / 端口常量…） |
| 7 | `engine/EngineProcess.kt` | **fork + exec**：`node --expose-internals <dsh bin.js> web --no-open --port <n>`（ROOT 模式下外层套 `su -c`）。⚠️ **带补丁时必须换形式**：`--patch <file> --profile web --no-open --port <n>` —— `web` 子命令显式拒收父级 `--patch`，照原样下会**引擎不启动**（详见 `EngineProcess.kt` 里的注释与提交 `133682e`） |
| 8 | `engine/Pty.kt` + `cpp/dsh_pty.c` | 原生 PTY，让引擎进程有终端（日志双写 logcat + `engine/engine.log`） |
| 9 | `EngineSupervisor.pollHealth()` + `awaitWebUrl()` | 轮询 `http://127.0.0.1:<port>/` 直到**有响应**（判定区间 `200..499` —— 0.1.5 的 401 也算，这是"假就绪"的来源，见「WebUI 会话认证」）→ 再等 stdout 打印 `dsh web: <url>`（带 token，最多 15s）→ 发布 `Healthy(port, webUrl)` |
| 10 | 同 4 | Healthy 后启动 `AgentBridge`；Shizuku 模式下启动 `ShizukuHttpBridge` |

### 状态机

```
Idle → Installing → Starting → Healthy(port)
                  ↘ Backoff(delayMs, attempt)  ← 崩溃退避，序列 2s/4s/8s/16s/32s
                  ↘ Failed(reason) → Stopped   ← 连续失败达 MAX_RESTART(=14)
Healthy 连续一次即重置退避计数。
```

---

## 目录布局

### App 私有目录（`ctx.filesDir`，即 `/data/user/0/<包名>/files/`）

```
files/
├── engine/                    运行时根（升级时整体替换，绝不动 dsh-home）
│   ├── bin/node               引擎本体（bionic 编译的 Node）
│   ├── bin/{bash,rg,curl,pnpm,...}
│   ├── bin/{notify,scr,island,psx,killx,shz,su}   ← 运行时注入的闸门包装器（island = 流体云上报）
│   ├── lib/node_modules/@deepseek-ai/dsh/lib/bin.js   ← 引擎入口
│   ├── lib/                   动态库（LD_LIBRARY_PATH）
│   ├── etc/tls/cert.pem       CA 证书
│   ├── extensions/<id>/       ← 扩展中心安装的环境（git/python/jdk…）
│   └── engine.log             ★ 引擎日志在这里（不在 files/ 根）
├── dsh-home/                  $DSH_HOME —— 用户资产
│   ├── profiles/              插件/配置 YAML（profiles/web/fluid-cloud.mjs 是 App 每次启动从 assets 复制过来的流体云插件）
│   ├── .android-fluid-cloud.patch.yml   ← 流体云插件的 --patch 补丁层（App 每次启动重写）
│   ├── profiles.last-good/    ProfileGuardian 的健康快照
│   ├── sessions/              会话数据
│   ├── storages/              工具状态
│   ├── AGENTS.md              环境说明书（由 AgentContextSeed 生成，见下）
│   ├── settings.yaml
│   └── .credentials.yaml      模型 API 凭证
├── workspaces/                默认工作区根（引擎 cwd）
├── tmp/                       TMPDIR
├── profile-guardian.meta      自愈层计数元数据
└── root-backup/               ROOT 模式启动前对 dsh-home 的备份
```

**注意**：`engine/` 与 `dsh-home/` 是**两种性质**的目录。
升级引擎时会整体替换 `engine/`，但**绝不能碰 `dsh-home/` 和 `workspaces/`** —— 那是用户资产。

### 仓库内

```
app/src/main/java/app/dsh/mobile/     Kotlin 源码（见下方职责表）
app/src/test/java/app/dsh/mobile/     JVM 单元测试（不需要设备；CI 跑 testDebugUnitTest）
app/src/main/cpp/                     dsh_pty.c + CMakeLists.txt（原生 PTY）
app/src/main/assets/
  ├── runtime.zip                     ★ 不入库，CI 的 collect-runtime 注入
  ├── runtime/MANIFEST.json           运行时版本与校验值
  └── extensions/catalog.json         扩展中心清单
scripts/
  ├── collect-termux-runtime.sh       CI 收集 Termux 运行时（慢的那步）
  ├── patch-apiproxy.py               运行时补丁
  └── patch-webview-polyfill.py       运行时补丁
.github/workflows/android-build.yml   构建流水线
```

---

## 端口分配

**`EngineConfig.PORT_BASE` 是唯一真源**，其余全部派生：

| 端口 | 用途 | 定义 |
|---|---|---|
| `PORT_BASE + 0` | 引擎 web（WebView 加载它） | `EngineConfig.DEFAULT_PORT` |
| `PORT_BASE + 2` | Shizuku 桥（`POST /shizuku_exec`） | `ShizukuHttpBridge.port(enginePort) = enginePort + 2` |
| `PORT_BASE + 3` | 能力桥 / 扩展中心 API | `AgentBridge.PORT = EngineConfig.AGENT_BRIDGE_PORT` |

| | 官方版 | 本 fork |
|---|---|---|
| `PORT_BASE` | 3080 | **3180** |

⚠️ **引擎只认 `--port` CLI 参数**，不读 `PORT` 环境变量 —— 详见 `docs/PITFALLS.md` A1。
⚠️ 改端口时，注入给 AI 的包装脚本与 `AgentContextSeed` 的文案里有硬编码字符串，**必须一起改**。

---

## 关键文件职责

按「你要改什么 → 看哪个文件」组织。行数为撰写时快照，仅供参考。

| 文件 | 行数 | 职责 | 什么时候会改它 |
|---|---|---|---|
| `engine/ExtensionManager.kt` | 767 | 扩展中心：Termux 仓库实时安装（索引 → 依赖闭包 → .deb → 解包 → 原子发布） | 改扩展机制 |
| `SettingsActivity.kt` | 458 | 设置页（权限模式 / 显示 / **流体云开关** / 扩展入口） | 加设置项 |
| `MainActivity.kt` | 397 | WebView 外壳 + 状态条 + 预览模式；Healthy 后用 `state.webUrl`（带 token）加载；**返回键 = `moveTaskToBack`（只退到后台，不停引擎）** | 改主界面 |
| `ExtensionStoreActivity.kt` | 390 | 扩展中心 UI | 改扩展 UI |
| `engine/EngineConfig.kt` | 445 | **目录拓扑 + 端口常量 + 子进程环境 + 闸门包装器注入**（含 `island` 命令、**流体云插件与补丁层**；插件源码在 `assets/fluid-cloud.mjs`，启动时复制并替换端口占位符） | 改端口 / 环境变量 / 注入脚本 |
| `engine/AgentBridge.kt` | 405 | 环回 HTTP：通知 / 读屏 / 点击 / **`/island`（流体云三层上报）** / 扩展 API / `/diag` 自诊断 | 加原生能力给 AI |
| `engine/EngineSupervisor.kt` | 553 | 状态机 + 健康检查 + **等 WebUI 入口（token）** + 退避重启；**异步停止 / 停引擎收尾跑完才 spawn（pendingShutdown）/ 监督代际 epoch / 只按本轮日志判 EADDRINUSE** | 改启动/自愈逻辑 |
| `engine/ProfileGuardian.kt` | 308 | 自愈层：健康快照 / last-good 回滚 / 安全模式 | 改自愈策略 |
| `engine/Privilege.kt` | 244 | NORMAL / SHIZUKU / ROOT 三模式探测与切换，dsh-home 保护 | 改权限模式 |
| `OnboardingActivity.kt` | 232 | 首次启动引导 | 改引导流程 |
| `engine/RuntimeInstaller.kt` | 208 | 安装 `assets/runtime.zip`（或 MANIFEST 远程包） | 改安装逻辑 |
| `FluidCloud.kt` | 303 | **流体云状态岛**：三层优先级（Agent 上报 > 引擎忙碌 > 自动层）、过期回落、设置开关、岛通知即前台服务通知；折叠态文案 = `动作 空格 百分比`（v1.2.30 I3，动态拼不写死） | 改岛上显示什么 |
| `service/EngineService.kt` | 346 | 前台服务：保活 + **岛自动层（状态一变立刻刷 + 5 秒轮询兜底，v1.2.30 I2）** + `onTaskRemoved`（划掉=退出）+ `START_NOT_STICKY` | 改保活 / 退出与通知行为 |
| `engine/SessionWatcher.kt` | 79 | 会话活动探测（只 stat 文件拿「项目名 + 活跃项目数」，不解压不读内容） | 改岛的项目名来源 |
| `src/test/.../SessionWatcherTest.kt` | 93 | SessionWatcher 的 JVM 单测（目录名解码 / 文件名版本差异 / 活跃窗口） | 改探测逻辑时同步补 |
| `engine/EngineProcess.kt` | 204 | fork + exec 引擎进程（`--port` 在这里）；**扫描 stdout 捕获 `dsh web:` 入口** | 改启动参数 |
| `DshAccessibilityService.kt` | 153 | 无障碍服务（模拟点击/滑动） | 改读屏点击 |
| `engine/AgentContextSeed.kt` | 161 | 生成并**增量同步** `$DSH_HOME/AGENTS.md`：静态模板靠 `SEED_VERSION` 升级覆盖，动态行（已激活扩展 / 特权模式 / shz）每次启动就地同步 | 改环境事实说明 |
| `engine/ShizukuHttpBridge.kt` | — | Shizuku 模式的 adb 身份执行桥 | 改 Shizuku |
| `engine/Pty.kt` + `cpp/dsh_pty.c` | — | 原生 PTY | 少动 |

---

## 四条重要的数据流

### 运行时安装（`RuntimeInstaller`）

优先级从高到低：

1. **`assets/runtime.zip`** —— CI 构建时注入的离线包（推荐，无网络依赖）
2. **`MANIFEST.json` 声明的远程 URL** —— 开发期热更新，强制 SHA-256 校验

安装 = 解压到 `filesDir/engine` + **恢复可执行位**
（zip 不保存 Unix 权限，所以解压后对 `bin/` 下所有文件 `chmod 755`）。

`targetSdk 28` 下 SELinux 允许对 `filesDir` 内文件 `execve`，不需要 jniLibs 伪装 ——
**这就是 `targetSdk` 不能升的原因**。

### 扩展安装（`ExtensionManager`）

国内直连优化：下载源用 **Termux 国内镜像（TUNA/USTC/BFSU，官方兜底）** ——
解决「GitHub Releases 全超时」的问题。链路：

```
Packages.gz 索引 → 依赖闭包解析 → 逐包 .deb（SHA-256 强校验）
  → ar 归档 → data.tar.xz → tar 解包（GNU longname / PAX / symlink / 硬链接）
  → usr/ 前缀拍平 → 恢复可执行位 → rename 原子发布
```

安装到 `filesDir/engine/extensions/<id>/`，激活后其 `bin/` `lib/` 并入引擎的
`PATH` / `LD_LIBRARY_PATH`（**要重启引擎才生效**，见 `docs/PITFALLS.md` D5）。

三态：🔴 未下载 / 🟡 已下载未激活 / 🟢 已激活可用。

### WebUI 会话认证（dsh 0.1.5+，`BrowserAuth`）

**0.1.5 起 WebUI 强制浏览器会话认证，直接开裸 `/` 一律 401**（正文
`dsh web authentication required; reopen the URL printed by dsh web.`）。
所以「引擎起来了」和「页面打得开」在 0.1.5 里是两件事：

```
引擎启动 → stdout 打印  dsh web: http://127.0.0.1:<port>/?token=<进程 launch token>
   ↓ EngineProcess.scanForWebUrl()：逐行扫描 stdout（行缓冲，防 chunk 从行中间截断）
webUrlFuture 完成
   ↓ EngineSupervisor.awaitWebUrl()：最多等 15s；进程提前死亡则立刻放弃
State.Healthy(port, webUrl)
   ↓ MainActivity.render()
WebView.loadUrl(webUrl)
   ↓ 引擎回 303 → 重定向到 / 并下发签名 cookie（绑定 Host authority）
之后带 cookie 才放行 → 200 + HTML
```

三个容易踩的点：

- **健康检查会"假就绪"**：`pollHealth` 的判定区间是 `200..499`，0.1.5 的 401 正落在里面，
  于是"HTTP 服务器已监听"被当成"WebUI 可用"，而 WebView 其实只会黑屏。
  **就绪 ≠ 可用**，详见 `docs/PITFALLS.md` G8。
- **token 是进程级的**：引擎重启就换新的；`MainActivity` 的「预览返回」按钮特意走
  `healthyWebUrl` 重取一次（会话 cookie 可能已过期，重走 token 链换新）。
- **旧引擎兼容**：0.1.1 打印同一行但不带 token，走同一条通路后等价于裸 URL，行为不变 ——
  所以这套适配对新旧引擎都安全。

---

### 流体云状态上报（v1.2.27+，`FluidCloud`）

```
引擎进程内插件 profiles/web/fluid-cloud.mjs（App 每次启动重写；经 --patch 注入）
  ├─ 订阅 agent/status（running/idle）→ POST 127.0.0.1:3183/island {"action":"status",...}
  └─ 每 3 秒把 sessions/<项目>/ 与 <项目>/<会话>/ 两级 chmod 0755
         （root 模式下引擎以 uid 0 建目录是 0700，App 进程读不到 → 岛上项目名会退化）
        ↓
AgentBridge POST /island → FluidCloud 三层优先级
        ① Agent 显式上报（island set/done；引擎空闲且 10 分钟无更新则回落自动层）
        ② 引擎忙碌态（插件上报的 running/idle）
        ③ 自动层（EngineService 每 5 秒写入：项目名 / N 个项目 + 就绪·工作中·启动中·引擎异常）
        ↓
NotificationManager.notify(4242, ProgressStyle + setShortCriticalText + extras["android.requestPromotedOngoing"])
        ↓
ColorOS 16 流体云胶囊（该通知**同时就是 EngineService 的前台服务通知** —— 进程被杀由系统撤掉）
```

- 设置页「流体云状态岛」开关（`dsh_ui/island_enabled`）关掉 → 退回普通前台通知，**不重启引擎**。
- 无通知权限 / 系统不支持时同样退回普通前台通知（否则岛会卡在首帧「启动中」）。
- 折叠态只显示：左 = 小图标，右 = `shortCriticalText`（就绪/工作中/45%）；标题与进度条正文只在**展开态**可见。
- 判定与验证配方见 `docs/PITFALLS.md` H 节（已修项）与 **I 节**（待修项 + 折叠/展开字段对照表）。

---

## 自愈层（`ProfileGuardian`）


dsh 的插件配置是 AI/用户可写的 YAML，形状错误会让引擎在加载阶段 fail-loud 循环崩溃。
自愈层的设计**极度保守**（上游注释说明：任何"主动预防"的误伤率都高到不可接受）：

- 只在**引擎真实死亡**（非超时自杀、非安装异常）且**崩溃签名连续一致**时才介入
- 两阶段升级，累计需 **≥10 次连续同签名真死**才可能归档用户配置
- 阶段 0（≥5 次）：有 `last-good` 就回滚，没有就只观望
- 仍失败 → **安全模式**（`State.SafeMode`）：归档坏配置、空配置启动

---

## CI 流水线（`.github/workflows/android-build.yml`）

触发：`push` 到 `main` / `push` tag `v*` / 手动 `workflow_dispatch`。

```
collect-runtime（单架构 aarch64）
  ├─ 恢复 runtime.zip 缓存（key = collect 脚本 + patch 脚本内容哈希）
  ├─ 未命中 → 跑 collect-termux-runtime.sh（约 18 分钟，全流程瓶颈）
  ├─ 校验命令闭包（bash / rg / SONAME 库 / ripgrep 平台包）
  └─ 上传 artifact

build-apk（needs: collect-runtime）
  ├─ 下载 runtime artifact → 注入 assets/
  ├─ 回填 MANIFEST 的 version / sha256
  │    version = "<分支>-<日期>-<sha256 前 12 位>"，**含 runtime 内容指纹** ——
  │    安装器以 version 为闸门决定是否重装，只精确到日期会让同日重建的修复装不进去（G10）
  ├─ 恢复 .ci/debug.keystore 缓存（固定签名的关键）
  ├─ 未命中 → keytool 现场生成 + 打印密钥库指纹
  ├─ gradle testDebugUnitTest -Pabi=arm64-v8a（JVM 单测；失败即整条流水线红）
  ├─ gradle assembleDebug -Pabi=arm64-v8a
  ├─ 校验所有 .so 的 LOAD 段 16KB 页对齐（真机事故防线）
  └─ 上传 artifact

release（if: tag v*，needs: build-apk）
  └─ 合并 artifact → softprops/action-gh-release 挂到 Releases
```

**两个缓存**是本 fork 加的核心优化：

| 缓存 | key | 作用 |
|---|---|---|
| `runtime.zip` | 脚本内容哈希（脚本一改自动失效） | 21 分钟 → 2-3 分钟 |
| `.ci/debug.keystore` | 固定 key（一写入永远复用） | 签名跨构建稳定，可覆盖安装 |

---

## 本 fork 相对上游的差异清单

`git diff --stat upstream/main...HEAD` 实测为 **22 个文件**，便于将来 `git merge upstream/main` 时定位冲突。

**本 fork 新增的文件**（上游没有，不会冲突，但别当成上游代码）：`FluidCloud.kt`（流体云状态岛）、
`engine/SessionWatcher.kt`（会话活动探测）、`app/src/test/java/.../SessionWatcherTest.kt`（JVM 单测）、
`docs/PITFALLS.md` 的 G/H/I 节、`docs/ARCHITECTURE.md` 的流体云段。

**与上游同名但已改动的文件**：

| 文件 | 改了什么 |
|---|---|
| `app/build.gradle.kts` | `applicationId` → `app.dsh.mobile.dev`；新增 `signingConfigs.debug` 指向 `.ci/debug.keystore` |
| `engine/EngineConfig.kt` | 新增 `PORT_BASE = 3180` / `AGENT_BRIDGE_PORT`；把散落的硬编码端口改为常量插值 |
| `engine/AgentBridge.kt` | 桥端口 `3083` 与自诊断探测用的 `3080` 改为 `EngineConfig` 常量 |
| `engine/EngineProcess.kt` | `spawn()` 增加 `port` 形参、args 追加 `--port`；新增 stdout 扫描捕获 WebUI 入口 |
| `engine/EngineSupervisor.kt` | 调用点传 `port`；`Healthy`/`SafeMode` 携带 `webUrl`；新增 `awaitWebUrl()` |
| `MainActivity.kt` | 三处 WebView 加载点改用 `webUrl ?: 裸URL`（0.1.5 会话认证） |
| `engine/AgentContextSeed.kt` | 端口文案改为插值；`SEED_VERSION` 7 → 8；动态行每次启动就地同步 |
| `app/src/main/res/values/strings.xml` | 应用名/无障碍标签/通知标题加 `Dev` 后缀；端口文案 |
| `.github/workflows/android-build.yml` | 单架构矩阵；两个缓存；固定密钥库生成与指纹打印；`MANIFEST.version` 并入 runtime 内容指纹（否则同日重建不重装，见 G10）；产物重命名用 `SAFE_REF`（分支名带斜杠会让重命名失败） |
| `scripts/collect-termux-runtime.sh` | 0.1.5 的 Android 适配：koffi 桩改为真算 LP64 布局（G7）；session-persistence 的 flock 打桩 + 迁移硬链接改 `rename`；sandbox-local 删掉冗余的 landlock 补丁 |

**合并上游时的典型冲突点**：引擎升级适配与端口/token 通路那几行（`EngineProcess` / `EngineSupervisor` /
`MainActivity` / `AgentContextSeed` / workflow / collect 脚本）。合并后务必回归验证两件事：

1. **`EngineProcess` 的 `--port` 还在** —— 这是最容易被上游覆盖掉的改动（见 `docs/PITFALLS.md` A1）；
2. **引擎能真正起来** —— 若 `collect-termux-runtime.sh` 里的 koffi 桩被上游版本覆盖，Android 上会立刻
   崩在模块顶层的 ABI 自检；而 CI 绿、健康检查也照样通过，症状是"显示已就绪但页面打不开"（G7）。
