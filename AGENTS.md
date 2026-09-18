# AGENTS.md — AI Agent 仓库入口

> 任何进入本仓库的 AI Agent 会**自动读取本文件**。所以它只写「代码里读不出来的东西」：
> 意图、硬约束、失败历史、验证纪律。保持简短密集，不要在这里复述代码。

---

## 这是什么

`DSH Mobile` 的**共存版 fork** —— 把完整的 DeepSeek Harness（Node Agent 引擎）跑在 Android 应用沙箱内的壳。

- **上游**：`Soodok/Deepseek-Harness-Local-Android`（`upstream` remote 已配置好）
- **本 fork 的唯一目的性差异**：让**自编译版能与官方版同时安装、同时运行**，且数据互不干扰

| 差异点 | 上游 | 本 fork | 为什么 |
|---|---|---|---|
| `applicationId` | `app.dsh.mobile` | `app.dsh.mobile.dev` | 包名不同 → 数据目录 / 权限授予 / 无障碍服务 / Shizuku authority 全部自动独立 |
| 端口基线 | 3080 | **3180**（`EngineConfig.PORT_BASE`） | 两个引擎同时运行不能抢端口 |
| CI 架构矩阵 | arm64-v8a + x86_64 | 仅 arm64-v8a | x86_64 只对 PC 上的安卓模拟器有意义，真机装不上 |
| CI 缓存 | 无 | 运行时 + 签名密钥库 | 构建 21 分钟 → **2 分钟**；签名跨构建稳定 |

**其余代码与上游一致。** 动手前先确认你不是在重复上游已有的能力 —— 上游作者在代码里写了大量高质量中文注释，先读注释。

---

## 硬约束（碰了会坏）

- **`targetSdk = 28` 是刻意的，不要"顺手升级"。**
  sideload 分发，豁免 Android 10+ 的 W^X 限制，才能对 `filesDir` 内的 bionic 二进制 `execve`（Termux 同款策略）。

- **引擎端口只能走 CLI 参数。**
  引擎**完全不读 `PORT` 环境变量**，只认 `--port <n>`。详见 `docs/PITFALLS.md` #1 —— 这是本项目最贵的一个坑，曾经导致「进程异常退出、无限重启」。

- **`app/src/main/assets/runtime.zip` 不入库**，由 CI 的 `collect-runtime` job 注入。
  因此**本地无法直接 `assembleDebug`**（即使补齐了 SDK/NDK）。

- **签名密钥绝不入库**，`.gitignore` 覆盖 `*.keystore`。
  CI 用的固定密钥库是 `.ci/debug.keystore`，靠缓存跨构建复用，同样不入库。

- **仓库不含 gradle wrapper**（`gradle-wrapper.jar` 是二进制，不入库）。
  CI 用 `gradle/actions/setup-gradle@v4` 直接提供 gradle 命令。

- **流体云状态岛的生命周期不变量**（改回去就会复现僵尸胶囊 / ANR / 启动竞态 / 提示闪变，
  详见 `docs/PITFALLS.md` H2~H4 与 I1~I6）：
  ① 岛通知必须**同时就是** `EngineService` 的前台服务通知（同一个 id）—— 换成普通 ongoing 通知，
     进程被杀后胶囊会一直留在通知栏点不掉；
  ② 退出路径停引擎必须**异步**（状态同步变更、只有 kill 与等待在后台），
     且**停引擎的收尾工作没跑完之前不能 `spawnEngine()`**（等 `pendingShutdown` 那个 future，
     不是等进程退出）—— `EngineProcess.stop()` 的强杀走的是**全局 PTY 句柄**，
     新引擎先起来就会被打死（v1.2.30 真机实测，详见 `docs/PITFALLS.md` I1）；
  ③ 别把 `START_NOT_STICKY` 改回 `START_STICKY`（用户要求"杀掉就停"，不再自我复活）；
  ④ 服务被"再启动"时（`MainActivity.onResume()` 每次都调 `EngineService.start()`），
     前台通知**必须复用当前内容**（`FluidCloud.foregroundNotification`）——
     照贴写死的首帧「启动中」，用户每次切回 App 都会看到它闪一下（v1.2.31 I4）；
  ⑤ 「退出」的语义是**退出 App**：先等引擎优雅停完（`onShutdownFinished`）→
     撤掉最近任务里的卡片（`AppTask.finishAndRemoveTask()`）→ 才 `Process.killProcess`。
     顺序不能反（先杀进程 = 引擎来不及 flush 会话），见 `docs/PITFALLS.md` I6。

---

## 开发循环

```bash
cd /storage/emulated/0/开发
bash push.sh "改了啥"              # 提交 + 推送 main → 触发云端构建
bash push.sh "发版说明" v1.2.26    # 额外打 tag → 产出永久 Release
```

- 构建约 **2-3 分钟**（运行时缓存命中后；缓存失效时约 20 分钟）
- **先推 main 验证绿，再打 tag** —— tag 会走 `release` job，多一段失败面
- 构建只是「编译通过」，**不等于功能可用**，见下
- **分支推送不触发 CI**（workflow 只认 `main` 与 tag）：改代码走分支时用 `workflow_dispatch`
  指定 `ref` 手动触发；**必须用 node 的 fetch 调 GitHub API，别用本机 `curl`**（封装会弄坏
  Authorization 头，返回 401 Bad credentials）。token 在 `$DSH_HOME/.git-credentials`，别回显。
- 拉产物：`node $DSH_HOME/tmp/dl-resume.js <run_id>`（断点续传；403 = 签名地址过期会自动换新）。
  118MB 直连在弱网下会反复断，可改用加速站下 Release 附件（详见 `docs/PITFALLS.md` F3）。

---

## 验证纪律（本项目最重要的约定）

**「构建成功」和「能用」是两件事。** 本项目历史上多次出现「CI 全绿但装上去跑不起来」。
所以任何影响产物的改动，都要验证到二进制层，而不是靠推断：

| 要验证什么 | 怎么验 |
|---|---|
| 包名 / 应用名 / ABI | `aapt2 dump badging app.apk` |
| 签名是否跨构建一致 | `apksigner verify --print-certs app.apk` |
| 改动是否真进了二进制 | 解出 `classes*.dex` 后 `grep -a` 关键字（例：`--port`、`127.0.0.1:3180`） |
| 引擎能否在指定端口起来 | 直接跑引擎加 `--port`，看监听端口与日志 |
| 引擎为什么起不来 | 读 `/data/user/0/<pkg>/files/engine/engine.log` |
| 流体云状态岛上显示什么 | `dumpsys notification --noredact`（判据与配方见 `docs/PITFALLS.md` H 节已修项 + **I 节：v1.2.30~v1.2.32 修掉的 I1~I6 / 折叠与展开的字段对照表 / 验证命令**） |

工具位置：`engine/extensions/android-buildtools/bin/{aapt2,apksigner}`（扩展中心的工具**不在 PATH 里**，要用绝对路径）。

**不要猜路径、不要猜配置项。** 本项目已经因为「猜 AGP 把 debug 密钥库放在 `~/.android`」浪费过一整轮构建。猜不出来就去读依赖的源码或打日志实测。

---

## 两个 `AGENTS.md`，别搞混

| 位置 | 谁生成 | 给谁看 | 能不能手改 |
|---|---|---|---|
| **仓库根 `AGENTS.md`**（本文件） | 人写的 | 改**源码**的 Agent | ✅ 随便改 |
| `$DSH_HOME/AGENTS.md`（手机里） | App 自动生成，见 `AgentContextSeed.kt` | 跑在**手机 App 里**的 Agent | ⚠️ 会被自动更新，见下 |

改错文件会白费功夫，甚至误导 Agent。

第二个文件的更新规则（`AgentContextSeed.ensure`）分三层，改动时别破坏这个分层：

1. **模板文案变了** → 必须递增 `SEED_VERSION`，旧文件才会被全文覆盖升级。
2. **随环境变化的行**（`- Currently activated:` 已激活扩展、`## Privilege mode:` 特权模式、
   以及 shizuku 专属的 `- shz:` 行）→ **不需要**递增版本号，每次引擎启动会**就地同步**这三行，
   其余内容（含用户/Agent 追加的笔记）保持不动。
   历史教训：v8 早期只比版本号就 return，导致装完 openjdk-17 后该行仍长期写着 `none yet`，
   Agent 据此以为自己没有 JDK / 没有 root —— 见 `docs/PITFALLS.md` G11。
3. **用户自己写的文件**（无种子标记）→ 绝不覆盖。

---

## 与用户协作的约定

- 用户是**编程小白**。用大白话解释，先说结论再说原因，涉及操作要给能直接复制的命令。
- **全程用中文**：**思考（推理）过程**与**回复**都用中文。专业术语可保留英文原文
  （如 `EACCES` / SELinux / ESM / koffi / ABI），但解释必须用中文讲清楚。
- 用户**不挂代理**，网络以国内直连为准。GitHub 直连实测可用；多数国内镜像已失效（详见 `docs/PITFALLS.md`）。
- 用户手机上有**两个 App 并存**：官方的 `DSH Mobile`（装着全部真实会话数据，**一个字都别动**）和自编译的 `DSH Mobile Dev`。
  涉及「卸载」的操作**必须明确提醒别卸错**。
- 长任务结束后推送系统通知：`notify "..."`。⚠️ 官方版引擎的 `/notify` 有 G12 那个
  「Content-Length 按字符读」的老 bug → **中文 body 必失败**（dev 版已修）；在官方版里发通知用英文，
  或走 dev 版桥 `POST 127.0.0.1:3183/notify`。
- 用户会**自己动手操作手机**（点设置开关、从最近任务划掉 App、按返回键）。观测到异常先把
  `logcat -b events | grep -E 'am_proc_start|am_proc_died|am_kill'` 拉出来对时间线，**别急着当代码 bug**；
  需要干净观测时，先请他这几分钟别碰手机。
- **视觉类验收（胶囊长什么样、锁屏排版、文案顺不顺眼）只能由用户判断** —— Agent 可能读不了图片，
  截图不等于看过；这类结论必须请用户确认，不能替他下。

---

## 去别处找细节

- **`docs/ARCHITECTURE.md`** —— 导航：引擎启动链路、目录布局、端口分配、**关键文件职责表**、
  四条数据流（含流体云上报）、CI 流水线、与上游的差异清单（改前先查这里找「该改哪个文件」）
- **`docs/PITFALLS.md`** —— 踩坑记录（A~I 节，格式：现象 → 根因 → 修法 → 验证）；
  **I 节 = v1.2.30~v1.2.32 修掉的 I1~I6（含根因）+ 折叠/展开的字段对照表 + 真机验证配方**
- **`docs/UI-REDESIGN.md`** —— 界面改版设计文档（底部玻璃岛导航替换左侧抽屉 + 液态玻璃视觉 +
  **把引擎网页装进 App 的注意事项**）。改界面（`activity_main.xml` / `MainActivity` 导航部分 /
  设置页 / 扩展中心）之前先读这份
- `README.md` / `README_EN.md` —— 面向用户的产品说明
