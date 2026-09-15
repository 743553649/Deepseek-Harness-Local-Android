# 踩坑记录（PITFALLS）

> 这里记录**已经付过学费**的坑。代码只留下最后成功的那条路，试错中撞死的墙全丢了 ——
> 而 AI Agent 一定会重犯。每条格式：现象 → 根因 → 修法 → 验证。
>
> 新增条目请照此格式，并注明对应 commit（`git log --oneline` 可查）。

---

## A. 引擎与端口

### A1. 引擎不读 `PORT` 环境变量，只认 `--port` CLI ★最贵

- **现象**：自编译版装上后「进程异常退出，一直重启」，怎么都起不来。
  `engine.log` 里是 `Error: listen EADDRINUSE: address already in use 127.0.0.1:3080`。
- **根因**：引擎的端口来自 CLI 参数，见 `@deepseek-ai/dsh-web-app/lib/startup.js`
  —— 它只解析 `--port <n>`，**完全不读 `PORT` 环境变量**。
  而 `EngineProcess.spawn` 只传了 `web --no-open`，端口是靠 `PORT=` 环境变量注入的 → 被引擎忽略 → 回落到内置默认 **3080**。
- **为什么上游一直没暴露**：官方版的默认端口正好也是 3080，环境变量和默认值相同，
  这个 bug 被完美掩盖了 —— 直到共存版把期望端口改成 3180 才炸出来。
- **修法**：`EngineProcess.spawn` 增加 `port` 形参，args 追加 `"--port", port.toString()`；
  `EngineSupervisor` 调用点传 `EngineConfig.DEFAULT_PORT`。（commit `9bb0f99`）
- **验证**：直接跑引擎实测 ——
  `node bin.js web --no-open --port 3199` → `ss -ltn` 应显示监听 3199，
  日志应输出 `dsh web: http://127.0.0.1:3199`。
  再解出 APK 的 `classes*.dex`，`grep -a -- '--port'` 应命中 1 次、旧端口 `3080` 应为 0 次。

### A2. `engine.log` 在 `engine/` 子目录里

- **现象**：去 `filesDir/engine.log` 找日志，文件不存在。
- **根因**：实际路径是 **`filesDir/engine/engine.log`**。
- **修法**：记住完整路径 `/data/user/0/<包名>/files/engine/engine.log`。
- **验证**：`ls -la /data/user/0/app.dsh.mobile.dev/files/engine/engine.log`

### A3. 端口分配是派生的，改一处要连带想清楚

- **现状**：`EngineConfig.PORT_BASE` 是唯一真源
  - `+0` 引擎 web（`DEFAULT_PORT`）
  - `+2` Shizuku 桥（`ShizukuHttpBridge.port = enginePort + 2`，自动跟随）
  - `+3` 能力桥 / 扩展中心（`AgentBridge.PORT = AGENT_BRIDGE_PORT`）
- **坑**：注入给 AI 的 `notify` / `scr` 包装脚本里**曾经硬编码** `127.0.0.1:3083`，
  `AgentBridge` 的 `/diag` 自诊断页也硬编码 3080，`AgentContextSeed` 生成的 AGENTS.md 里同样写死了端口。
  **只改常量不改这些，AI 的通知 / 读屏 / 点击会静默失效**（连不上桥，但不报错）。
- **修法**：全部改为引用常量做字符串插值。（commit `b9273c4`）
- **验证**：解 dex 后 `grep -a '127.0.0.1:3183'` 应命中 7 次，旧值 `3083` 应为 0 次。

---

## B. 云端构建 / CI

### B1. 瓶颈是下载运行时，不是编译

- **现象**：一次构建 21 分钟，误以为编译慢。
- **根因**：`collect-runtime` 要从 Termux 仓库下载整套运行时依赖闭包（约 20 个 `.deb` + 索引），
  **约 18 分钟**；真正的 `assembleDebug` 只要 **2-3 分钟**。
- **修法**：用 `actions/cache` 缓存 `runtime.zip`，key 带
  `collect-termux-runtime.sh` + `patch-*.py` 的内容哈希 —— 脚本一改自动失效重建，不会用到过期运行时。
  `continue-on-error: true` 保证缓存服务抖动时回退为正常下载。（commit `7e8c4d9`）
- **验证**：CI 日志里「收集 Termux Node 运行时」那一步必须显示 **`skipped`**。
  ⚠️ **不能只看 job 是绿的** —— 显示 `success` 说明它**真的跑了**（18 分钟），缓存没命中。
  构建总时长应从 21 分钟降到 2-3 分钟。

### B2. Actions 产物只有 24 小时寿命

- **现象**：构建产物过一天就没了。
- **根因**：workflow 里 `retention-days: 1` 是**刻意的**（上游注释：曾堆到 32GB）。
- **修法**：想永久留存就**发 tag** → `release` job 把 APK 挂到 GitHub Releases。
- **验证**：Actions 运行页的 Artifacts 旁标有过期时间。

### B3. `release` job 曾经从没成功跑过

- **现象**：现存 Release 的 asset 名是 `dsh-mobile-<tag>-<abi>.apk`，
  而 workflow 产出的是 `dsh-android-<ref>-<abi>.apk` —— **格式对不上**。
- **根因**：上游最后一次 tag 运行（`v1.2.25`）失败在 `collect-runtime`，
  `build-apk` 与 `release` 全被 skip。也就是说那些 Release 不是这条流水线自动产出的。
- **修法**：无（`collect-runtime` 修好后自然通）。本 fork 首次跑通见 tag `v1.2.25-dev`。
- **验证**：打 tag 后确认 `release` job = `success`，且 Releases 页面出现 asset。

### B4. 矩阵多架构时缓存有竞态

- **现象**（预防性记录，未实际踩到）：若矩阵里有多个 `build-apk` job，它们**并行**运行，
  在固定 cache key 下如果都未命中，会**各自生成一把签名密钥**，随后只有先完成的那个能写入缓存
  → 两个产物签名不一致，且下次构建用哪把不确定。
- **修法**：`build-apk` 矩阵只保留单架构（同时也解决了 x86_64 对真机无用的问题）。
- **验证**：workflow 里 `build-apk.strategy.matrix.include` 长度应为 1。

---

## C. 签名

### C1. CI 每次构建都会重新生成 debug 签名密钥

- **现象**：每次构建的 APK 签名都不同 → 覆盖安装报签名冲突 → 必须先卸载旧版（Dev 版数据全丢）。
  实测指纹：`#2 73c9d893… #3 f46f0aba… #4 44e15d59… #5 514e012b…` 每次都不一样。
- **根因**：AGP 的 debug 密钥库是在构建时**现场生成**的，密钥材料随机；CI 每次都是全新 runner。
- **修法**：固定密钥库 + 跨构建缓存。（commit `311981a`）
- **验证**：`apksigner verify --print-certs` 比对两次独立构建的 APK 指纹，必须**逐字节相同**。
  实测 `#6 == #7 == 34c87d3b…`。

### C2. 猜 AGP 密钥库路径 —— 缓存机制正常，但缓存了个空目录

- **现象**：缓存 `~/.android` 后，日志明明显示
  `#4 Cache saved with key: android-debug-keystore-v1` 和 `#5 Cache hit for: …`，**但两次签名仍不同**。
- **根因**：AGP 在 CI 上的 debug 密钥库**不在 `~/.android`**，所以缓存到的是空目录。
  佐证：缓存条目 **0 字节**，而真实密钥库有 **2759 字节**。
- **教训**：**缓存机制「成功」不代表缓存到了有用的东西** —— 要核对缓存体积。
- **修法**：**不猜路径，显式指定**：
  CI 用 `keytool` 生成 `.ci/debug.keystore`，`app/build.gradle.kts` 的
  `signingConfigs.debug` 指向它（`if (exists())` 保证本地开发不受影响）。
  密钥库仍不入库（`.gitignore` 的 `*.keystore` 覆盖）。
- **验证**：把 `sha256sum .ci/debug.keystore` 打进 CI 日志，跨构建比对
  （实测 `#6 == #7 == 8ee9b66e…`）；缓存条目应约 2.7KB 而非 0 字节。

---

## D. 本机 Android / Termux 环境

> 这些是**在手机上跑 Agent 时的环境坑**，跟改 App 源码无关，但每次换设备都会重踩。

### D1. 引擎以 root 运行时 `HOME=/`

- **现象**：`git config --global` 报 `could not lock config file //.gitconfig: Read-only file system`。
- **根因**：root 身份的 `HOME` 是 `/`（只读根分区），git 去写 `//.gitconfig`。
- **修法**：把 `HOME` 显式指向 dsh-home。

### D2. FUSE 挂载点不支持可执行位

- **现象**：`./script.sh` 报 `Permission denied`，`chmod +x` 也不生效。
- **根因**：`/storage/emulated/0` 是 FUSE，权限位由挂载决定，不能设可执行位。
- **修法**：一律用 `bash script.sh` 调用。

### D3. git 报 `dubious ownership`

- **现象**：所有 git 命令 fatal: `detected dubious ownership in repository`。
- **根因**：FUSE 挂载点的 owner 与 root 身份不一致。
- **修法**：`git config --global --add safe.directory '*'`

### D4. `http.lowSpeedLimit` 设太激进会误杀正常推送

- **现象**：`fatal: unable to access …: Operation too slow. Less than 1000 bytes/sec transferred the last 60 seconds`。
- **根因**：为抗弱网设了 `http.lowSpeedLimit 1000` + `http.lowSpeedTime 60`，
  但国内到 GitHub 抖动大，**正常推送会被误判成卡死**。
- **修法**：`git config --global http.lowSpeedLimit 0`（不因慢而中断）。

### D5. 扩展中心显示 green ≠ 命令在 PATH 里

- **现象**：扩展中心里 git 是 green（已激活），但 `which git` → `command not found`。
- **根因**：新激活的扩展，其二进制要**引擎重启**后才进 PATH。
- **修法**：直接用绝对路径 `engine/extensions/<id>/bin/<cmd>`；或重启引擎。
  注意：`aapt2` / `apksigner` 等构建工具同样**不在 PATH**，验证脚本里要用绝对路径。

---

## E. 文档与 Agent 上下文

### E1. 改了 `AgentContextSeed` 的文案不生效

- **现象**：修改了种子文案，用户手机上没变化。
- **根因**：`SEED_VERSION` 没递增，而幂等策略是「版本旧才覆盖」。
- **修法**：**改文案必须同步递增 `SEED_VERSION`**（改端口那次从 7 升到 8）。
- **验证**：看 `$DSH_HOME/AGENTS.md` 首行的 `seed v<N>` 标记。

### E2. 两个 `AGENTS.md` 容易搞混

- 仓库根 `AGENTS.md` = 给**改源码**的 Agent 看（手写）
- `$DSH_HOME/AGENTS.md` = 给**跑在 App 里**的 Agent 看（自动生成，见 E1）

改错文件白费功夫。

---

## F. 网络（国内、无代理）

### F1. 国内 GitHub 镜像大面积失效

- **实测结论**（无代理直连）：

  | 源 | 结果 |
  |---|---|
  | github.com 直连 | ✅ 可用（约 2.2s） |
  | gh-proxy.com / ghfast.top / ghproxy.net | ✅ 可用（加速代理） |
  | gitclone.com | ❌ 502 / 超时 |
  | gitcode.com gh_mirrors | ❌ 无此镜像仓库 |
  | hub.fastgit.xyz | ❌ 已停止服务 |
  | kkgithub.com | ❌ 证书过期 |
  | bgithub.xyz / github.moeyy.xyz | ❌ 连接超时 |

- **修法**：**直连优先，三个代理作兜底**。仓库外的 `clone-repo.sh` 封装了这个回退链。
- **验证**：`git ls-remote <url>` 能返回 ref 才算可用。

### F2. Release 附件偶发连接超时

- **现象**：下载 Release 附件时 `release-assets.githubusercontent.com` 连接超时。
- **根因**：**偶发抖动，不是封锁** —— 重试即通（第二次 378ms）。
- **修法**：重试；或走 API 方式下载
  `GET /repos/{owner}/{repo}/releases/assets/{id}`（`Accept: application/octet-stream`）。
- **注意**：Actions 的 artifact 走另一条链路（`api.github.com` → 签名 URL），一直很稳。

---

## 附：x86_64 产物真机装不上

- **现象**：装 x86_64 的 APK 报 `INSTALL_FAILED_NO_MATCHING_ABIS`。
- **根因**：真机是 arm64，该 APK 只含 x86_64 原生库。x86_64 只对 PC 上的安卓模拟器有意义。
- **修法**：矩阵只保留 arm64-v8a。（commit `1f016f1`）
