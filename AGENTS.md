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
- 长任务结束后推送系统通知：`notify "..."`。

---

## 去别处找细节

- **`docs/ARCHITECTURE.md`** —— 导航：引擎启动链路、目录布局、端口分配、关键文件职责、CI 流水线
- **`docs/PITFALLS.md`** —— 踩坑记录：现象 → 根因 → 修法 → 验证
- `README.md` / `README_EN.md` —— 面向用户的产品说明
