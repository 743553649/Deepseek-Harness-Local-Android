# 架构导航（ARCHITECTURE）

> **用途**：让 Agent 不用满仓库 grep 就能定位代码。只写「哪个文件负责什么、流程怎么走」，
> 具体实现细节请读源码 —— 上游作者在代码里写了大量高质量中文注释，那才是第一手资料。
>
> 若本文与代码冲突，**以代码为准，并回来修本文**。

---

## 一句话架构

一个 Android 外壳（Kotlin）+ 一个塞进 `assets` 的 **Node 运行时**，运行时装进应用私有目录，
由外壳 fork 出 Node 进程跑完整的 DeepSeek Harness 引擎，WebView 加载 `127.0.0.1` 上的 Web UI。
另有若干原生能力（无障碍读屏点击、通知、Shizuku/root 提权）以**环回 HTTP 桥**的形式暴露给引擎内的 Agent。

```
┌─ Android App (Kotlin) ────────────────────────────────┐
│  MainActivity ──WebView──► http://127.0.0.1:<引擎端口>/ │
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
| 2 | `MainActivity.kt` | 启动 `EngineService`，订阅状态流；Healthy 后 WebView 加载 `http://127.0.0.1:${state.port}/` |
| 3 | `service/EngineService.kt` | 前台服务（`specialUse` 类型，规避 Android 14 的 dataSync 6 小时上限） |
| 4 | `engine/EngineSupervisor.kt` | **核心状态机**：`Idle → Installing → Starting → Healthy(port)`，失败走 `Backoff → Failed` |
| 5 | `engine/RuntimeInstaller.kt` | Installing 阶段：解压 `assets/runtime.zip` 到 `filesDir/engine`，恢复可执行位 |
| 6 | `engine/EngineConfig.kt` | 组装子进程环境变量（PATH / LD_LIBRARY_PATH / DSH_HOME / 端口常量…） |
| 7 | `engine/EngineProcess.kt` | **fork + exec**：`node --expose-internals <dsh bin.js> web --no-open --port <n>`（ROOT 模式下外层套 `su -c`） |
| 8 | `engine/Pty.kt` + `cpp/dsh_pty.c` | 原生 PTY，让引擎进程有终端（日志双写 logcat + `engine/engine.log`） |
| 9 | `EngineSupervisor.pollHealth()` | 轮询 `http://127.0.0.1:<port>/` 直到响应 → `Healthy(port)` |
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
│   ├── bin/{notify,scr,psx,killx,shz,su}   ← 运行时注入的闸门包装器
│   ├── lib/node_modules/@deepseek-ai/dsh/lib/bin.js   ← 引擎入口
│   ├── lib/                   动态库（LD_LIBRARY_PATH）
│   ├── etc/tls/cert.pem       CA 证书
│   ├── extensions/<id>/       ← 扩展中心安装的环境（git/python/jdk…）
│   └── engine.log             ★ 引擎日志在这里（不在 files/ 根）
├── dsh-home/                  $DSH_HOME —— 用户资产
│   ├── profiles/              插件/配置 YAML
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
| `SettingsActivity.kt` | 423 | 设置页（权限模式 / 显示 / 扩展入口） | 加设置项 |
| `MainActivity.kt` | 391 | WebView 外壳 + 状态条 + 预览模式 | 改主界面 |
| `ExtensionStoreActivity.kt` | 390 | 扩展中心 UI | 改扩展 UI |
| `engine/EngineConfig.kt` | 347 | **目录拓扑 + 端口常量 + 子进程环境 + 闸门包装器注入** | 改端口 / 环境变量 / 注入脚本 |
| `engine/AgentBridge.kt` | 345 | 环回 HTTP：通知 / 读屏 / 点击 / 扩展 API / `/diag` 自诊断 | 加原生能力给 AI |
| `engine/EngineSupervisor.kt` | 313 | 状态机 + 健康检查 + 退避重启 | 改启动/自愈逻辑 |
| `engine/ProfileGuardian.kt` | 308 | 自愈层：健康快照 / last-good 回滚 / 安全模式 | 改自愈策略 |
| `engine/Privilege.kt` | 244 | NORMAL / SHIZUKU / ROOT 三模式探测与切换，dsh-home 保护 | 改权限模式 |
| `OnboardingActivity.kt` | 232 | 首次启动引导 | 改引导流程 |
| `engine/RuntimeInstaller.kt` | 208 | 安装 `assets/runtime.zip`（或 MANIFEST 远程包） | 改安装逻辑 |
| `service/EngineService.kt` | 169 | 前台服务 | 改保活 |
| `engine/EngineProcess.kt` | 157 | fork + exec 引擎进程（`--port` 在这里） | 改启动参数 |
| `DshAccessibilityService.kt` | 153 | 无障碍服务（模拟点击/滑动） | 改读屏点击 |
| `engine/AgentContextSeed.kt` | ~105 | 生成 `$DSH_HOME/AGENTS.md`（给 App 内 Agent 的环境说明书） | 改环境事实说明 |
| `engine/ShizukuHttpBridge.kt` | — | Shizuku 模式的 adb 身份执行桥 | 改 Shizuku |
| `engine/Pty.kt` + `cpp/dsh_pty.c` | — | 原生 PTY | 少动 |

---

## 两条重要的数据流

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
  ├─ 恢复 .ci/debug.keystore 缓存（固定签名的关键）
  ├─ 未命中 → keytool 现场生成 + 打印密钥库指纹
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

改动集中在 5 个文件，便于将来 `git merge upstream/main` 时定位冲突：

| 文件 | 改了什么 |
|---|---|
| `app/build.gradle.kts` | `applicationId` → `app.dsh.mobile.dev`；新增 `signingConfigs.debug` 指向 `.ci/debug.keystore` |
| `engine/EngineConfig.kt` | 新增 `PORT_BASE = 3180` / `AGENT_BRIDGE_PORT`；把散落的硬编码端口改为常量插值 |
| `engine/EngineProcess.kt` | `spawn()` 增加 `port` 形参，args 追加 `--port` |
| `engine/EngineSupervisor.kt` | 调用点传 `port = EngineConfig.DEFAULT_PORT` |
| `engine/AgentContextSeed.kt` | 端口文案改为插值；`SEED_VERSION` 7 → 8 |
| `app/src/main/res/values/strings.xml` | 应用名/无障碍标签/通知标题加 `Dev` 后缀；端口文案 |
| `.github/workflows/android-build.yml` | 单架构矩阵；两个缓存；固定密钥库生成与指纹打印 |

**合并上游时的典型冲突点**：前三行文件。合并后务必回归验证
`EngineProcess` 的 `--port` 还在（这是最容易被上游覆盖掉的改动）。
