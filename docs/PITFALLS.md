# 踩坑记录（PITFALLS）

> 这里记录**已经付过学费**的坑。代码只留下最后成功的那条路，试错中撞死的墙全丢了 ——
> 而 AI Agent 一定会重犯。每条格式：现象 → 根因 → 修法 → 验证。
>
> 新增条目请照此格式，并注明对应 commit（`git log --oneline` 可查）。

---

## 索引

| 节 | 讲什么 | 条目 |
|---|---|---|
| **A** | 引擎与端口（★ 端口只能走 CLI 参数，本项目最贵的坑） | 3 |
| **B** | 云端构建 / CI（触发方式、拉产物、缓存与镜像） | 5 |
| **C** | 签名（跨构建一致的固定密钥库） | 2 |
| **D** | 本机 Android / Termux 环境（bionic、toybox 差异、进程管理） | 5 |
| **E** | 文档与 Agent 上下文（两份 AGENTS.md 的关系） | 2 |
| **F** | 网络（国内、无代理；加速站） | 2 |
| **附** | x86_64 产物真机装不上 | — |
| **G** | 上游 dsh 引擎升级（0.1.1-rc.2 → 0.1.5-rc.1） | 12 |
| **H** | 前台服务 / 通知 / 退出链路（保活、通知身份、停引擎的竞态） | 5 |
| **I** | 退出与停引擎 v1.2.30~v1.2.32 修掉的 **I1、I6** + 真机验证配方 | 3 |
| **J** | 界面改版 v1.2.36~v1.2.44 修掉的 **J1~J7**（全屏 / 底栏 / 切页 / 键盘遮挡 / 胶囊删除） | 7 |

> 新增条目前先看对应节：**已修的进 H/I/J（写清验证方式）**，别再往「待修清单」里堆。

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

### B5. 分支名带 `/` 会让构建挂在"重命名 APK"一步，且看不到产物

- **现象**：用 `workflow_dispatch` 跑一个带斜杠的分支（如 `experiment/dsh-0.1.5-rc.1`），
  `collect-runtime` 全绿、gradle `assembleDebug` 也成功、so 16KB 对齐校验也过，
  但 `build-apk` 挂在 **「按版本+架构重命名 APK」**，随后的 `upload-artifact` 被跳过
  → **整条流水线失败且没有任何产物**。日志只有一行：
  `cp: cannot create regular file 'dist/dsh-android-experiment/dsh-0.1.5-rc.1-arm64-v8a.apk': No such file or directory`
- **根因**：那一步用 `GITHUB_REF_NAME` 拼文件名，而分支上的 `GITHUB_REF_NAME` 带 `/`，
  `/` 被当成目录分隔符 → 目标路径的中间目录不存在。**与代码改动无关**，纯 workflow bug。
- **为什么以前没暴露**：workflow 只在 `main` 和 tag `v*` 上自动触发，这两个 ref 名都不含斜杠；
  只有手动 dispatch 一个斜杠分支时才会踩到。
- **修法**：拼文件名前把斜杠换掉 —— `SAFE_REF="${GITHUB_REF_NAME//\//-}"`，
  并补 `test -f` + `ls -lh dist/` 让失败更显眼。
- **验证**：本地实测 `experiment/dsh-0.1.5-rc.1 → experiment-dsh-0.1.5-rc.1`；
  `main` / `v1.2.26` 展开后**保持不变**（不影响自动触发那两条路径）。
- **教训**：失败发生在**产物上传之前**，所以"构建成功≠有产物"。
  看到"gradle 成功但流水线红"，先看 rename/upload 这两步，别去怀疑引擎补丁。

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

---

## G. 上游 dsh 引擎升级（0.1.1-rc.2 → 0.1.5-rc.1）

> 本节是升级 0.1.5-rc.1 时逐条实测出来的。**升级上游版本前必读** ——
> 这五条都属于"CI 绿、装上去却起不来/不能用"的类型，静态断言不覆盖。

### G1. 「补丁断言失败 → CI 全挂」的真实根因，不是"包被移出依赖树"

- **现象**：collect 脚本里原注释称，当年 CI 全挂是因为上游 0.1.5-rc.1
  "把 session-persistence-jsonl 从依赖树移除 → 补丁断言失败"。
- **根因**：**这个说法不成立**。实测（`pnpm install --lockfile-only` 解析）
  `@deepseek-ai/dsh-session-persistence-jsonl` 仍在树里，版本 `0.1.5-rc.2`。
  真正原因是**补丁期望的 import 字符串变了** —— 上游在 `node:fs/promises` 的
  导入列表里**多了一个 `lstat`**，于是 `includes(oldImport)` 为假 → `exit 1`。
- **教训**：断言失败的信息（"shape changed"）是对的，但对根因的**推断**是错的。
  别把推断当结论写进注释，它会误导下一个 Agent 去查错误的方向。
- **验证**：`grep -c dsh-session-persistence-jsonl pnpm-lock.yaml` 非 0。

### G2. ★0.1.5 的 `defaultFileSystem` 顶层字面量：删 import 里的 `link` = 引擎启动即崩

- **现象**：按"老补丁的写法"（从 `node:fs/promises` 导入里**删掉 `link`**、
  加上 `rename`）打补丁，模块一加载就
  `ReferenceError: link is not defined`。
- **根因**：0.1.5 新增了一个**模块顶层**的对象字面量，其中 `link` 是**简写属性**：

  ```js
  const defaultFileSystem = {
  	...
  	lstat: (path) => lstat(path),
  	link,          // ← 顶层求值：import 里没有 link 就立刻 ReferenceError
  	rm: (path) => rm(path, { force: true })
  };
  ```

  简写属性在**模块求值时**就要取 `link` 的值，所以不是"调用时才炸"，而是**加载即炸**。
  旧版（0.1.1-rc.2）没有这个字面量、全文只有一处 `link`，所以老补丁删 `link` 是安全的。
- **修法**：`link` 和 `lstat` **都保留**，只**新增** `rename`，只替换那一次调用。
  即"差集只有新增 rename"，不做任何删除。
- **验证**：用真实依赖闭包（pnpm 装 `dsh-session-persistence-jsonl@0.1.5-rc.2`）
  在本机 android/arm64 上 `await import(...)`：
  新补丁 → 加载成功；老补丁 → `ReferenceError at lib/index.js:1608`。
- **通用教训**：**改动上游 import 列表前，先看这些绑定在文件里还有没有别的引用**
  （尤其顶层字面量/简写属性）。"只用到一次"的结论必须用 grep 验证，不能凭印象。

### G3. ★0.1.5 新增 flock：不处理则会话根本写不进磁盘

- **现象**：升级后 AI 无法正常对话（会话落盘失败）。
- **根因**：0.1.5 从 `@deepseek-ai/node-addon-system/flock` 引入 `tryLockExclusive`，
  用在会话写入前的写锁上：
  `ensureLease() → acquireWriteLease() → SessionWriteLease.acquire() → tryLockExclusive(fd)`。
  该族包**只有 darwin/linux 平台包**，android 上调用即抛
  `ERR_FLOCK_UNSUPPORTED_PLATFORM`；而代码只把 `EAGAIN/EWOULDBLOCK` 当作
  "被他人占用"（转 `SessionAlreadyOwnedError`），其他错误**直接上抛**。
- **注意**：该包**导入是安全的**（源码注释明写 "importing it does not load a native addon"），
  炸的是**调用**。所以"能 import 就没事"是错的判断方式。
- **修法**：打桩为"立即成功"。依据是上游自己给浏览器 worker 的同款处理
  （源码注释：单进程，in-process write claim 已排除所有写入者）—— Android 应用内引擎同样是单进程。
- **验证**：本机 android/arm64 实跑真实 `flock.js`：
  `tryLockExclusive(0)` → `ERR_FLOCK_UNSUPPORTED_PLATFORM | flock is not supported on android-arm64`。

### G4. ★0.1.5 会话格式 v0 → v3：迁移路径上还有**第二个**硬链接点

- **现象**：老会话（v0 格式）在升级后加载/迁移时报 EACCES。
- **根因**：新版迁移发布路径 `publishPreparedMigration → publishCurrentExclusive`
  用的是 `internals.fs.link(staged, currentPath)`，**旧版全文没有这个调用点**。
  而 `SESSION_FORMAT_VERSION` 由 **0 → 3**，catalog 带 `v0→v1→v2→v3` 完整迁移链，
  所以**用户既有会话必定走迁移**，这个点一定会被走到。
- **修法**：`defaultFileSystem.link` 在 android 下换成 `androidLink`：
  `lstat` 预检 + 同目录 `rename`，**复刻 `link(2)` 的 no-overwrite 语义**
  （目标已存在时抛 `EEXIST`，调用方据此转 `published=false` 走校验分支，绝不覆盖）。
  非 android 平台走原生 `link`，行为不变。
- **为什么 rename 替代是等价的**：发布成功后代码会 `removeCommittedTemporary(staged.path)`，
  而该函数是 `try{rm}catch{}` 吞错，所以 rename 把源移走不会引发问题；
  `currentIdentity` 取 `stat(currentPath)`，rename 保留 inode，identity 语义一致。
- **验证**：单测 `androidLink` —— 新目标发布成功且内容正确 / 已存在目标抛 `EEXIST`
  且内容未被覆盖 / 源缺失时 `ENOENT` 原样上抛。

### G5. landlock 补丁可以整条删掉（新包已 import-safe + fail-soft）

- **现象**：`node-addon-landlock-run` 在 0.1.5 被重组进
  `@deepseek-ai/node-addon-system/landlock-run`（加子路径），老补丁正则不再匹配。
  且该补丁**原本没有断言** → 会**静默失效**（landlock import 摘不掉）。
- **根因/新事实**：新实现是**纯 JS + 诚实失败**：导入时不 require 任何原生 `.node`，
  `launcherPath()` 内部 catch 住解析失败，`probe()` 拿不到二进制时返回 `"unusable"`
  —— 自然走上游原版 fail-closed 路径（受限模式抛 `SANDBOX_UNAVAILABLE`）。
- **修法**：**整条删除**该补丁，让上游原样跑。
  老桩不但多余，还把 `LAUNCHER_FAILURE_EXIT` 写死成 **126**（上游现为 **125**），是错的常量。
- **验证**：本机 android/arm64 实跑真实 `node-addon-system/lib/index.js`：
  导入成功、`probe() === "unusable"`、`LAUNCHER_FAILURE_EXIT === 125`。
- **保留的反向断言**：补丁仍断言"landlock import 必须以上游原样存在"。
  将来上游再改形状、或有人按老写法重新打桩，会**显式失败**而不是静默通过 ——
  这正是 G5 一开始能静默失效的原因，必须堵住。

### G6. 别用 `link()` 实测来推翻仓库的 EACCES 结论（SELinux 域陷阱）

- **现象**：为验证"Android 禁硬链接"是否还成立，我在 /tmp 和 app 的 dsh-home 里
  实测 `fs.link()`，**都成功了**，一度以为可以删掉 rename 补丁。
- **根因**：**实验被污染**。`cat /proc/self/attr/current` 显示当前进程在
  `u:r:ksu:s0`（KernelSU root 域），而 app 跑在 `untrusted_app` 域。
  SELinux 按**域**判定 `link` 权限，**`process.setuid()` 只换 uid 不换域**，
  所以降权到 app uid 也复现不出 app 的真实处境。
- **修法/纪律**：这类"文件系统权限"结论，**在 shell 里当 root 测几乎必然测错**。
  要么真机装上去验证，要么明确标注"本实验域不匹配、不作为依据"。
- **遗留状态**：仓库历史结论（app 域下 `link()` → EACCES）**既未被证实也未被证伪**，
  升级时按保守做法保留 rename 替代。

### G7. ★★0.1.5 的顶层 ABI 自检 vs koffi 惰性桩 —— 引擎"假已就绪"然后死掉

- **现象（真机，2026-09-16）**：装上新 APK 后前端显示**「引擎已就绪」**，但**网页打不开**。
  `engine.log` 里连排多行 `---- engine start ----`，**后面却没有任何输出**。
- **根因**：上游 0.1.5 新增 `@deepseek-ai/dsh-win32-process`，它在**模块顶层**用 koffi
  建 struct 并做 ABI 自检：

  ```js
  const STARTUPINFOW = koffi.struct("DSH_STARTUPINFOW", { cb: "uint32", ... });
  if (STARTUPINFOW.size !== 104) throw new Error(`STARTUPINFOW layout mismatch: ...`);
  if (PROCESS_INFORMATION.size !== 24) throw new Error(`PROCESS_INFORMATION layout mismatch: ...`);
  ```

  而它被 `@deepseek-ai/dsh-subprocess-local` **静态 import** → Android 上加载即执行。
  仓库原来的 koffi 桩是**纯惰性 Proxy**：`koffi.struct()` 返回 Proxy，`.size` 取到的是
  一个**函数**（`function () { return inertProxy(name); }`）→ 自检必然抛错：
  `STARTUPINFOW layout mismatch: koffi computed function () { return inertProxy(name); }, expected 104`
  → `plugin tree failed to load` → 引擎退出。
- **为什么症状是"假已就绪"**：HTTP 服务器**先于**插件树加载完成就监听了。健康检查
  （`GET /` 只要有响应就算通过）探到了端口 → 前端显示"已就绪"；随后插件树加载失败、
  进程退出 → 网页 404/连不上。**这正好是 AGENTS.md「构建成功≠能用」的又一例。**
- **修法**：让 koffi 桩**真算 LP64 布局**（而非只做惰性代理）。Windows x64 与
  Android arm64 **同为 LP64**（指针 8 字节、8 字节对齐），所以算出的 104 / 24 与上游
  期望值**真实一致** —— 这是真通过，不是把校验绕过去。
  好处：**完全不碰上游源码**（比逐个外科手术更耐上游改版）。
- **验证**：
  1. CI 内布局自检（纯 JS、与平台无关）：`STARTUPINFOW.size === 104` 且 `PROCESS_INFORMATION.size === 24`；
  2. CI 内**真导入** `dsh-win32-process` 一次，导入失败即硬失败（挡住复发）；
  3. 真机实测：替换桩后 `import("@deepseek-ai/dsh-win32-process")` 成功（21 个符号），
     手动跑引擎输出 `dsh web: http://127.0.0.1:3199/?token=...`、**零报错**、端口正常监听。
- **通用教训**：**「惰性 Proxy 桩」只能保证"调用不抛错"，挡不住上游把"求值结果"用于
  自检**。上游每次新增顶层自检/布局校验，惰性桩都可能翻车。审计方法：全树扫描
  `^koffi\.` 这类**行首无缩进**的顶层调用，以及顶层 `if (... .size !== N) throw`。

### G8. ★0.1.5 新增 WebUI token 认证：App 加载裸 `/` 会 401

- **现象**：引擎明明起来了（端口在监听、日志零报错），WebView 仍然打不开页面。
- **根因**：0.1.5 引入浏览器会话认证（`@deepseek-ai/dsh-client-connection` 的
  `BrowserAuth`）。规则：
  - 引擎启动时打印带进程 token 的 URL：`dsh web: http://127.0.0.1:<port>/?token=<43 字符>`
  - 用这个 URL 访问 → **303 重定向到 `/` + `set-cookie`（签名 cookie，绑定 Host authority）**
  - 之后带该 cookie 才放行；否则一律 **401** + 正文
    `dsh web authentication required; reopen the URL printed by dsh web.`
  - 认证参数名是 `token`（`TOKEN_QUERY = "token"`）
  - ⚠️ **健康检查不受影响**：裸 `/` 返回 401 也算"有响应"，所以 `EngineSupervisor`
    依旧判定 healthy —— 又一次"假已就绪"。
- **修法（已实施，v1.2.26）**：三处改动 ——
  1. `EngineProcess`：日志泵逐行扫描 stdout 的 `dsh web: <url>` 行（带行缓冲防 chunk 截断），
     捕获到 `webUrlFuture`；
  2. `EngineSupervisor`：`State.Healthy/SafeMode` 增加 `webUrl` 字段；健康检查通过后
     等 token 行最多 15s（进程死则立即放弃），随状态发布；
  3. `MainActivity`：三处 WebView 加载点全部 `webUrl ?: 裸URL`（旧引擎/捕获失败退回原行为）。
  兼容性：0.1.1 引擎打印的行不带 token，同一通路捕获后等价于现状，不会变差。
- **排查时容易自误的坑**：用 `fetch()` 测"带 token 的 URL"会得到 **401**，
  因为 Node 的 `fetch` 默认 `redirect:"follow"` 却**不保存 cookie** → 跟随后的请求没 cookie。
  必须用 `redirect: "manual"` 看 303，或用真实浏览器/WebView 测。
- **验证**：`fetch(url, {redirect:"manual"})` → `status=303`、`set-cookie` 存在、`location=/`；
  带 cookie 再 GET `/` → `200` + 27724 字节 HTML（真机 0.1.5 实测）。

### G9. 「以 root 身份手动跑引擎」会污染 app 的 dsh-home（域/属主双重坑）

- **现象**：排查 G7 时手动跑了一次引擎做实验，随后 App 反而起不来了，日志变成
  `EACCES: permission denied, open '<app>/files/dsh-home/.credentials.yaml'`
  → `plugin tree failed to load`。
- **根因**：实验时把 `DSH_HOME` 指向了 **app 自己的 dsh-home**，而进程身份是 **root**。
  引擎初始化 credentials 插件时会**创建** `.credentials.yaml`，于是该文件属主成了
  `root:root 0600`；App 以 `u0_a491` 启动后**读不了自己的凭据文件** → 启动即崩。
  同理还会留下 root 属主的 `tmp/dsh-spill-*` 等残渣。
- **修法/纪律**：
  - 手动跑引擎做实验时，**`DSH_HOME` 一律指到独立临时目录**（如复制一份到
    `/data/local/tmp/`），**绝不指向 app 真实的 dsh-home**；
  - 实验后**核对 `find <app数据目录> -user root`** 必须为空，`kill` 干净并删除临时目录；
  - 一旦中招：删掉/改属主那个文件即可恢复（App 随后会自己重建正确的）。
- **验证**：`find /data/user/0/app.dsh.mobile.dev -user root` 无输出。
- **附带教训**：**不要用 `pkill -f <模式>` / `ps | grep <命令行>` 再 kill** —— 自己的
  命令行里就含那个模式，会**杀掉自己**（本次实测：整条命令被 SIGTERM，无任何输出）。
  按端口定位再 kill 才安全（`ss -ltnp | awk '/:<port>/'`）。

### G10. ★★MANIFEST version 无内容指纹：同日构建撞号 → 覆盖安装永远不重装 runtime

- **现象（真机，2026-09-16）**：koffi 修复已确认进 APK（`runtime.zip` 里的新桩逐字节核对过），
  `pm install -r` 也返回 Success，但设备引擎**仍按旧桩崩溃循环**（`engine.log` 里
  STARTUPINFOW 报错涨到 26 次、18 次重启）—— **修复根本没有落地**。
- **根因**：CI 给 `MANIFEST.json` 写的 version 是 `"分支-日期"`（如
  `experiment/dsh-0.1.5-rc.1-20260916`），**不含 runtime 内容指纹**。而
  `RuntimeInstaller.ensureInstalled()` 的闸门是「`.runtime-version` 戳 == MANIFEST.version
  且资产完整 → 跳过重装」。同一天内推送多个构建 → version 相同 → 覆盖安装后闸门判定
  "版本没变" → **跳过解压，设备上还是旧 runtime**。
  讽刺的是：CI 明明算出了 `runtime.zip` 的 SHA-256，却只写进远程校验字段（`.sha256`），
  没进 version。
- **修法（已实施）**：version 改为 `"分支-日期-<sha256 前 12 位>"` ——
  runtime 内容变一个字节，指纹就变，闸门必然触发重装。
- **验证**：装机后 `grep -c layoutStruct .../node_modules/koffi/index.js` 应 > 0
  （新桩落地的直接证据）。
- **通用教训**：**凡"内容更新但版本戳不变"的发布链路，都会把修复静默吞掉。**
  版本戳必须由内容派生（或至少含单调递增的构建序号），"分支-日期"粒度不够。

### G11. ★★`$DSH_HOME/AGENTS.md` 的动态行永不刷新（用户报告）

- **现象（2026-09-16 用户报告）**：seed v8 模板里 `- Currently activated:` 一行，在装了
  openjdk-17 之后仍然长期写着 `none yet`。Agent 读到过时事实 —— 会以为自己没有 JDK，
  进而重复安装或给出错误建议。
- **根因**：`AgentContextSeed.ensure()` 的幂等判据**只看版本号**：
  ```kotlin
  if (existing != null && containsVersion(existing, SEED_VERSION)) return   // ← 直接返回
  ```
  而 `render()` 里有**三类随环境变化的动态值**：`$active`（已激活扩展列表）、
  `$mode`（特权模式）、`$shz`（仅 shizuku 模式的段落）。SEED_VERSION 不变 → 永不重写
  → 这三个值全部冻结在首次写盘那一刻。
  实测证据：设备 `shared_prefs/dsh_extensions.xml` 里 `activated_openjdk-17=true`、
  `extensions/openjdk-17/` 也在，而 AGENTS.md 第 30 行还是 `none yet`。
- **影响面比报告的一行更大**：`$mode` 过时会说错特权模式（切到 root 后仍写 normal），
  Agent 会以为自己没有 root 权限。**过时的权威事实比缺失更有害** —— Agent 会信它。
- **修法（已实施）**：`ensure()` 改为三层判定 ——
  1. 无种子标记（用户手写）→ 绝不碰；
  2. 版本旧 → 全文覆盖升级（既有语义，仍靠 `SEED_VERSION` 触发）；
  3. 版本相同 → **只就地同步那几行**（三个正则：`ACTIVE_LINE_RE`/`MODE_LINE_RE`/`SHZ_LINE_RE`），
     用户/Agent 追加的其它内容原样保留。
  **不选"内容变了就全文重写"**：那会在文件里有用户笔记时每次启动都把它抹掉。
  也**不递增 SEED_VERSION**：本次改的是刷新逻辑而非模板文案，递增会导致全文覆盖、
  反而丢掉用户编辑，且旧文件也照样能被新逻辑修正。
- **实现细节（踩过的）**：
  - 替换值必须用 **lambda 形式** `replace(re) { ... }`，否则替换串里的 `$` 会被当正则组引用；
  - shz 行的删除正则必须**连前后各一个换行一起匹配**（`\n?- shz: [^\n]*\n?`）。
    只删行本身会残留空行，且**反复切换特权模式会不断累积空行**
    （实测行数序列 50→52→51→53→52）。插入串与删除正则必须是严格逆运算；
  - 插入的 shz 段落要**与 `render()` 的 `$shz` 占位产生相同排版**，
    否则"首次渲染"和"后续同步"产出的文件不一致。
- **验证（无法本地编译 Kotlin，故用 JS 复刻正则逐条验）**：
  1. 对真实设备文件跑同步 → `activated` 变 `openjdk-17.`、`mode` 变 `` `root` ``、
     版本头保留、行数 50→50（只改内容不增删行）；
  2. `shizuku → normal` 切换与原文**逐字节一致**（严格互逆）；
  3. 反复切换 6 次，行数只在 50/52 间摆动，**零累积**；
  4. `render()` 产出再喂给同步 → **零改动**（真幂等）；
  5. 文件末尾追加的用户笔记在同步后**原样保留**；
  6. 空列表回退仍为 `none yet`。
- **通用教训**：**幂等判据要覆盖"全部输入"，而不只是"版本号"。**
  一旦渲染函数里混入了环境相关值，只比版本号的幂等检查就会退化为"永远不更新"。
  设计这类"受管文件"时，先分清**静态模板**（版本号触发）与**动态字段**（每次同步）。

### G12. ★★`notify` 发中文消息必然失败：`Content-Length` 是字节，却被当字符读

- **现象**：AI 调 `notify "中文消息"` 必失败 —— HTTP **500** + `{"ok":false,"error":"Read timed out"}`，
  且固定卡 **5 秒**（正好是 `AgentBridge.handle()` 的 `client.soTimeout = 5_000`）。
  因为 seed 里就要求 AI「长任务结束用 `notify` 告知」，而 AI 写的消息**总是中文**
  → **这个功能在真机上从未成功过**（失败是静默的，只看退出码 1）。
- **根因**：`handle()` 用 `BufferedReader`（**字符**流）读请求体，却拿 `Content-Length`（**字节**数）
  当**字符数**：
  ```kotlin
  val buf = CharArray(contentLength)              // contentLength 是字节数
  while (n < contentLength) { ... reader.read(...) }   // 却想读这么多「字符」
  ```
  UTF-8 中文一个字 3 字节 → **实际字符数 < 声明的字节数** → 循环永远读不满 →
  阻塞到 `soTimeout` → `SocketTimeoutException("Read timed out")`。
- **决定性证据**（同一端点，只差是否含多字节字符）：
  | body | Content-Length | 实际字符数 | 结果 |
  |---|---|---|---|
  | 纯 ASCII | 45 | 45 | **200 `{"ok":true}`**（71ms）|
  | 含中文 | 44 | 32 | 500 `Read timed out`（5019ms）|
  | 单个汉字 | 27 | 25 | 500 `Read timed out`（5024ms）|
  对照：`/ext/install` 的 body 是**纯 ASCII** JSON → 7ms 正常返回，所以这个坑只在
  「body 含非 ASCII」时暴露，容易长期潜伏。
- **归属**：fork 继承的**老 bug**，**不是 0.1.5 升级引入** —— 官方版（引擎 0.1.1）实测同一错误，
  且 `AgentBridge.kt` 在本次升级中只改了端口常量，body 读取那段一个字没动。
- **修法**：请求侧也按**字节**处理 ——
  1. header 改为**逐字节**读到 `\r\n\r\n`（HTTP 头是 ASCII，安全）；
  2. body 按 `contentLength` **字节**读满，再整体 UTF-8 解码。
  ⚠️ **不能"用 BufferedReader 读 header + 用底层 InputStream 读 body"**：`BufferedReader`
  会预读，body 的一部分已被吞进它的字符缓冲区，这样读会错位 —— 所以必须连 header 一起改成字节读。
  （响应侧 `respond()` 本来就用 `bytes.size`，是对的。）
- **验证**：
  1. 本地用 JS 精确复刻新逻辑，喂真实 HTTP 请求字节流跑 8 个用例 ——
     中文 / emoji(4 字节) / 单汉字 / 小写 `content-length` / 多 header / 空 body 全部正确，
     且**读完后无残留字节**（证明字节数精确匹配）；
  2. 真机：发中文 body 的 `POST /notify` 应返回 **200 `{"ok":true}`**，并能在
     `dumpsys notification` 里看到 `app.dsh.mobile.dev` 的通知（渠道 `agent_notify`）。
- **通用教训**：**`Content-Length` 永远是字节数。** 只要中间经过 `Reader`/`Writer` 这类**字符**
  抽象，就必须自己做字节↔字符的换算，否则"英文测试全过、中文必挂"——这类 bug 的隐蔽性极高。


---

## H. 前台服务 / 通知 / 退出链路（保活、通知身份、停引擎的竞态）

### H2. ★★普通 ongoing 通知不随进程死亡消失；`START_STICKY` 还会让 App 自己复活

- **现象（用户反馈）**：手动把 Dev 版 App 杀掉后，通知栏那条常驻通知还在。
- **根因（实测两段，缺一不可）**：
  1. 那条常驻通知原来是**普通通知** + `setOngoing(true)`：Android 只保证"用户划不掉"，
     **不保证进程死后撤掉** —— 它就此变成点不掉的僵尸通知；
     只有**前台服务通知**（`startForeground` 的那条）才由系统托管、随进程死亡被撤。
  2. 更主要的一条：`EngineService` 返回 `START_STICKY`，前台服务被杀后**系统会立刻把它拉起来**
     → 引擎重新启动 → 通知又被贴回来。实测：`kill -9 <app pid>` 后 6 秒内进程 PID 已变
     （8051 → 18958）、`engine.log` 多出一条 `---- engine start ----`、
     通知的 `when` 是**新发**的时间戳（不是旧通知没撤）。
- **修法（v1.2.28）**：
  1. 引擎状态通知**就当前台服务通知**（`EngineService.startAsForeground()` 用 `NOTIF_ID`；
     后续 `updateNotification()` 仍是同一个 id → 保持前台服务身份）。
     ⚠️ `startForeground` 要求渠道**已存在**，所以建渠道必须提前到 `createChannel()` 里，
     不能等到通知更新时才建。
  2. `START_STICKY` → **`START_NOT_STICKY`**：用户杀掉就停，不自我复活。
  3. 加 `onTaskRemoved()` → 与通知栏「退出」按钮同一个出口
     （停引擎 + `stopSelf`）。
  4. `MainActivity.onBackPressed` 在 WebView 无历史时改走 **`moveTaskToBack(true)`**：
     返回键只是"离开界面"（任务留在最近任务里 → `onTaskRemoved` 不触发 → 引擎与通知继续常驻），
     只有**从最近任务划掉 / 强制停止**才算显式退出。
     ⚠️ 不改这里的话，返回键会 finish 掉唯一的 Activity → 任务被移除 → `onTaskRemoved` →
     引擎被停（用户明确不要这个行为）。
- **顺序坑**：`exitCompletely()` 必须**先** `stopForeground(STOP_FOREGROUND_REMOVE)` **再**撤通知 / `stopSelf()`；
  反过来的话，`cancel()` 的对象仍被前台服务持有 → 系统忽略 → 通知撤不掉。
- **验证**：杀进程后进程/引擎/通知三者应同时消失；从最近任务划掉后同样；
  `dumpsys notification` 里 `id=42` 应再无记录、且 `flags` 含 `FOREGROUND_SERVICE`。

### H3. ★★在 Service 回调里同步停引擎 → ANR（引擎优雅退出要 8 秒）

- **现象**：停服务 / 点通知栏「退出」/ 从最近任务划掉 App 时，界面会卡死数秒，
  随后系统弹「App 无响应」，严重时进程被直接杀死。
- **根因（实测 ANR 堆栈）**：
  ```
  "main" ... Sleeping
    at java.lang.Thread.sleep(Native method)
    at app.dsh.mobile.engine.EngineProcess.stop(EngineProcess.kt:110)
    at app.dsh.mobile.engine.EngineSupervisor.stop(EngineSupervisor.kt:88)
    at app.dsh.mobile.service.EngineService.onDestroy(EngineService.kt:132)
  ```
  `EngineProcess.stop()` 发完 SIGTERM 后会在主线程 `sleep` 轮询等引擎退出（最多 10 秒，
  超时才 SIGKILL）。而**真机实测引擎优雅退出要 8078 ms**（它在 flush 会话/关连接），
  主线程被阻塞 5 秒以上即触发输入超时 → ANR。
- **修法（v1.2.28）**：退出路径（`onDestroy` / `exitCompletely`）改为 `stopEngineAsync()` ——
  `app.appScope.launch(Dispatchers.IO) { supervisor.stop() }`。
  安全性依据：`EngineProcess.stop()` **第一件事就是发 SIGTERM**，不是先 sleep，
  所以即使本进程随后被杀，引擎也已被通知退出，不会留下霸占 3180 的孤儿进程。
  ⚠️ **热重启路径（`EngineSupervisor.restart`）保持同步**：那里必须等旧引擎死透再 spawn，
  否则新引擎 EADDRINUSE（见 A1 的无限重启事故），不能一起改成异步。
- **验证**：`am stopservice` 后界面不再无响应；`logcat -b events | grep am_anr` 无该包记录；
  `/data/anr/` 不再生成该 pid 的堆栈。

### F3. Actions 产物（artifact）用不了国内加速站 —— 要镜像就走 Release 附件

- **现象**：每次验证都要下 ~118MB 的 artifact，国内直连会反复中断
  （实测一轮里出现 `ECONNRESET` / `ETIMEDOUT` / `getaddrinfo` 失败共 4 次）。
- **根因（两条，都要知道）**：
  1. **链路**：artifact 不能直接下 —— 先要带令牌调 `api.github.com` 拿一个**短期签名地址**
     （指向 Azure blob）。公开加速站（ghproxy 之类）只代理 `github.com` /
     `raw.githubusercontent.com` 这类地址，**也不会替我们带令牌** → 镜像走不通。
  2. **签名地址会过期**：实测下到 **93MB（约 10 分钟）**后开始**持续 403**；
     如果续传脚本不换新签名地址，就会在 403 上空转（首次实现就卡了 40 次）。
- **修法**：
  1. **断点续传脚本**（`dl-resume.js`）：按 `Range` 从已下载字节继续；
     **收到 403/401/410 就重新调 API 换新签名地址再续**。
     实测：8.6MB → 93MB（断 6 次）→ 换签名地址 → 117.7MB 完成，全程没有从头再来。
  2. **需要镜像时走 Release 附件**：给 main 打 tag → workflow 的 `release` job 把 APK 挂到
     Releases → 用加速站下这个地址（PITFALLS F1 实测 ghproxy.net / ghfast.top 可用）：
     `https://ghproxy.net/https://github.com/<owner>/<repo>/releases/download/<tag>/<apk>`
- **验证**：`git tag` 后确认 release job = success、Releases 页面出现 asset；再实测镜像地址能下完。
- **附**：APK 里 **121MB 是引擎运行时**（`runtime.zip`），代码改动只有几 KB ——
  所以"每改一行就要下 118MB"是这条流程的固有成本，日常小改可先用
  「CI 绿 + 解 dex 做二进制层检查」，只在需要真机验证时才下载。

### H4. ★★把「异步停引擎」写成整个 stop() 丢后台 → 竞态：新引擎无人监督、界面永远「启动中」

- **现象**（H3 的修法引入的新 bug，真机实测）：停服务后立刻重新打开 App，
  引擎**明明在跑**（3180 有响应、node 进程在），但界面一直盖着「正在启动引擎…」的加载页
  （进不了对话页），监督器像"躺平"了一样不再更新状态。
- **根因**：H3 的修法把 **整个** `EngineSupervisor.stop()` 丢到后台线程。而 `stop()` 里
  发完 TERM 后会 **阻塞约 8 秒**等引擎死，然后才执行 `process = null` + `state = Stopped`。
  于是出现窗口期：用户在这 8 秒内重开 App → 新的监督循环已经起来并成功拉起引擎，
  **迟到的 stop() 才回来** → 它把 `loopJob`（新循环）cancel 掉、把 `process` 清空、
  把状态打回 `Stopped` → 新循环死掉、引擎无人监督、状态再也不更新
  （`Stopped` 不是 `Healthy` → 加载页一直盖着 = 「正在启动引擎…」）。
- **修法（v1.2.28）**：
  1. **状态变更与阻塞等待彻底分离** —— 新增 `EngineSupervisor.stopAsync()`：
     `userStop/loopJob/process/state` 的变更**同步立即完成**；后台协程只对
     **快照到的那个进程**做 `stop()`，**不再碰任何共享状态**。
  2. 加**监督代际 `epoch`**：`start()/stop()/stopAsync()` 都递增；循环捕获自己的 token，
     在**循环入口**与**`proc.exitFuture.get()` 返回后**各检一次。原因：
     `loopJob.cancel()` 只能取消挂起点，而 `exitFuture.get()` 是阻塞不可取消的 ——
     旧循环会一直等到引擎退出才返回，那时 `userStop` 可能已被新一轮 start() 置回 false，
     旧循环就会继续往下走：写回 `Backoff`、甚至**再拉起一个引擎抢 3180**。
- **验证**：`am stopservice` → **立刻**（2 秒内）`am start MainActivity` → 界面应恢复正常
  （加载页退场、进入对话页），且**只有一个** dev 引擎 node；`engine.log` 里不该出现两轮
  连续 `engine start`。修复前该场景稳定复现「永远启动中」。

### H5. ★★EADDRINUSE「清孤儿」是死代码；且它的清除模式会误杀官方版引擎

- **现象**（真机实测）：某次停止/重启后，dev 版出现 **2~3 个引擎 node 进程**同时存在，
  新的引擎全部 `listen EADDRINUSE 127.0.0.1:3180` 即死，监督器在"健康→引擎退出→重试"
  之间反复循环；而端口 3180 一直有响应（残留引擎在应答），界面上看起来"能用"。
- **根因（三层，都要修）**：
  1. **判定用了哈希**：`failureSignature()` 返回 `"$status:${tail.hashCode()}"`，
     而清理分支写的是 `deterministicFailure?.contains("EADDRINUSE")` ——
     **哈希永远不可能包含这个子串** → 这个兜底自诞生起就没执行过（v1.2.22 事故的补丁其实无效）。
  2. **健康检查只探端口**：`pollHealth()` 只要 3180 有 200..499 应答就算"健康"，
     于是**残留引擎的应答被当成本轮引擎就绪** → 监督器认为自己成功了，直到它 spawn 的
     那个（已 EADDRINUSE 死掉的）进程退出才回神 → 白等一轮。
  3. **spawn 后可能失联**：`spawnEngine()` 在 IO 线程上执行，期间若发生 stop/restart，
     旧实现紧接着无条件 `process = proc` 认领 —— 这个刚 fork 的引擎就再也没人管，
     它会继续霸占端口，喂给下一轮一个 EADDRINUSE。
- **修法（v1.2.28）**：
  1. 新增 `logTailText()` 取**日志原文**，`EADDRINUSE` 判定改用它；并加"本轮确有引擎死亡"
     的 `engineDied` 门闸，避免拿陈年日志误判；
  2. 认领前校验代际：`if (token != epoch) { proc.stop(); return }`；
  3. 清除模式**按包名限定** `"${'$'}{ctx.packageName}/files/engine/bin/node"` ——
     原来的裸 `files/engine/bin/node` 在**官方版与本版共存**时会同时命中官方版引擎，
     等于把正在服务另一个会话的引擎杀掉（本机两个 App 同时装的场景必踩）。
- **验证**：制造残留（手动留一个 engine node）后重启引擎，日志应出现
  `EADDRINUSE: killed orphan engine node(s) of app.dsh.mobile.dev`，且**官方版引擎 PID 不变**；
  随后只应有 **1 个** dev 引擎进程（`ps` 核对）。

### H5 补充：EADDRINUSE 判定还必须**只看本轮新增日志**

- 修掉"哈希 vs 字符串"之后还有个坑：日志是**追加**的，旧一轮失败留下的 `EADDRINUSE`
  会一直待在 `takeLast(4096)` 窗口里 → **每一轮都被判成端口冲突**，于是反复 `pkill -9`、
  反复把退避清零（实测：一次与端口无关的引擎崩溃也触发了清孤儿，还误杀了我的调试 shell）。
- 修法：`supervisionLoop` 每轮开始时记 `logMark = logFile().length()`，
  判定改用 `logTextSince(logMark)`（日志泵 2MB 环形截断导致 mark 失效时退回尾部窗口）。
- 验证：连杀引擎两次（不产生 EADDRINUSE）→ 日志里**不应出现**
  `EADDRINUSE: killed orphan engine node(s)`；制造真冲突时才出现。

---

## I. 退出与停引擎 · v1.2.30~v1.2.32 修掉的问题（I1、I6）与真机验证配方

> 本节原本是「下一轮开工清单」。已在 **v1.2.30~v1.2.32** 修完并真机验证，现按
> 「现象 → 根因 → 修法 → 验证」归档，供以后回看。前台服务与通知那一组见 H2~H5。

### I1. ★退出后 ~8 秒内重开 App → 新引擎被杀 / 撞 EADDRINUSE（v1.2.30 已修）

- **现象**（用户实测）：点通知栏「退出」后立刻打开 App，加载页显示「进程异常退出…N 秒后自动重启（第 1 次）」，
  约 3 秒后第二次启动才成功。
- **根因**（v1.2.30 真机日志对时，比初判深一层）：
  - 表层：H3 把「退出」时的停引擎改成**后台异步**（为了修 ANR），而引擎优雅退出要几秒，
    用户在窗口内重开 → 新引擎与旧引擎抢 3180。
  - **真正的元凶**：`EngineProcess.stop()` 的强杀用的是 `Pty.nativeForceKill()` /
    `Pty.nativeChildPid()` —— 那是 **PTY「当前子进程」的全局句柄，不是这个进程对象自己的 pid**。
    实测时间线：老引擎收到 TERM 后约 2 秒就退出、也放开了 3180，但 `stop()` 的等待循环迟迟不返回，
    一路数到 10 秒超时才发强杀；而 App 早在第 3 秒就重开、新引擎已经起来并**成为"当前子进程"**
    → 那句强杀（以及 su 的进程组击杀）**打在了新引擎身上**：
    `engine exited, raw status=0x9`（0x9 = SIGKILL）→ 再拉起又撞端口。
  - 附带坑①：退出路径会调**两次** `stopAsync`（`exitCompletely()` 一次，紧接着
    `stopSelf()` → `onDestroy()` 再一次）。第二次调用时 `process` 已经是 null，
    若无条件覆盖状态就会把第一次刚记下的东西抹掉，等待逻辑直接空转
    （v1.2.30 开发中踩到：logcat 里连等待日志都没有）。
  - 附带坑②：**只等 `exitFuture` 不够** —— 它只代表"进程退出"，盖不住 `stop()` 之后的强杀与进程组清理。
- **修法**：`stopAsync()` 把这次停引擎的**收尾工作**记成 `pendingShutdown: CompletableFuture`，
  由 `stop()` 返回时（`finally`）兑现；监督循环在 `spawnEngine()` **之前** `get(15s)`
  （后台等，不阻塞主线程 —— ANR 不能修回来），跑完才允许 spawn 新引擎；
  超时放行，原有的 EADDRINUSE 兜底处置原样保留。
- **怎么验（已实测，两轮一致）**：
  ```bash
  # 1) 点「退出」（与通知栏按钮同一个入口）
  am startservice -n app.dsh.mobile.dev/app.dsh.mobile.service.EngineService \
    -a app.dsh.mobile.service.action.EXIT
  # 2) 3 秒内重开 App（原来就死在这个窗口）
  sleep 3 && am start -n app.dsh.mobile.dev/app.dsh.mobile.MainActivity
  ```
  - `logcat -d | grep EngineSupervisor` 应出现
    `previous engine shutdown finished after 6746ms; spawning new one`（第二轮 6901ms）
  - 本轮新增的 `engine.log` 里 `EADDRINUSE` **0 次**（修前必现）
  - `logcat -b events | grep am_anr` **0 条**（没有把 ANR 修回来）
  - 重开后约 18 秒进入就绪（修前是弹「进程异常退出」+ 更久的折腾）

### I6. 「退出」的语义变了：现在是"退出 App"而不是"只停引擎"（v1.2.31 用户要求）

- **用户原话**：点「退出」要的是退出 App，而不是只把引擎停掉、App 还挂在后台。
- **实现**：`exitCompletely()` 先让引擎**优雅停完**（复用 I1 的 `pendingShutdown`：
  `EngineSupervisor.onShutdownFinished()`），**再** `Process.killProcess(Process.myPid())`。
  ⚠️ 顺序不能反：一上来就杀进程，引擎是被 PTY 断开带走的，会话来不及落盘。
  等待期间用户若又打开了 App（监督器重新在跑）就**不杀**（用户改主意了）。
- **怎么验（已实测）**：点「退出」后第 11 秒 —— App 进程消失、通知 0 条、
  3180 无监听、服务记录 0；logcat：
  `engine stopped; killing app process (user asked for full exit)` → `Quit itself, Pid:...`。
- **v1.2.32 补充**：退出时连「最近任务」里的卡片也一并撤掉（用户要求"别留空壳卡片"）——
  杀进程之前调 `ActivityManager.appTasks` 的 `AppTask.finishAndRemoveTask()`：
  这是**系统侧**请求，不需要界面进程还活着，也不要求用户当时正停在 App 界面上
  （从通知栏点退出时界面在后台）；撤不掉也不影响退出。
  实测：退出前 `dumpsys activity recents | grep -c app.dsh.mobile.dev` = 10 行（卡片在），
  退出后 = **0**，日志 `removing 1 task(s) from recents`。

### 附：本轮实测出来的验证配方（下次直接用）

```bash
# 前台服务通知身份（通知文案：待启动 / 正在启动引擎… / 引擎已就绪 / 进程异常退出…）
dumpsys notification --noredact | grep -A 3 'pkg=app.dsh.mobile.dev.*id=42' | grep 'flags='
#   期望：ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE
# 引擎侧监督器日志（含 I1 的等待证据）
logcat -d | grep EngineSupervisor        # healthy / exited / previous engine shutdown finished after ...
# 单测（本地跑不了，看 CI 的 testDebugUnitTest 步骤）
```

> ⚠️ 下载 CI 产物时注意：`dl-resume.js` 是**按字节续传**的，
> 换一个 run 重新下载前**必须先删掉 `artifact.zip`**，否则会把新包续在旧包后面，
> 解出来还是上一版的内容（v1.2.30 实测踩到：dex 里搜不到新加的日志字符串）。
> **更进一步：续传本身也会拼坏（v1.2.41 实测，见 J4）—— 现在一律整包重下 + 核对 digest。**

---

## J. 界面改版 · v1.2.36~v1.2.44 修掉的问题（J1~J7）

> 界面改版（底部玻璃导航 + 四页合一 + 全屏）过程中真机踩到的坑。用户报障 → 查根因 → 修法 → 验证，
> 格式与 I 节一致。**改界面、切页、底栏、重启引擎之前建议先读这一节。**

### J1. ★★界面线程调 `supervisor.restart()` → 整屏卡死 5~10 秒（v1.2.36 已修）

- **现象**（用户实测）：设置页点「重启引擎」、扩展中心点「激活 / 停用」后，
  **画面完全不动 5 秒左右**，然后引擎正常重启。
- **根因**：`EngineSupervisor.restart()` 内部是**同步** `stop()` →
  `EngineProcess.stop()` 要等引擎优雅退出（`deadline = 现在 + 10_000`，最长 10 秒）。
  这三处界面都在**主线程**直接调它 —— 主线程被占住就是画面完全不动。
  **这是历史遗留 bug**（老的设置页 / 老抽屉里的「重启引擎」用的是同一个方法），
  只是新界面把它摆到了用户眼前。
- **修法**：新增 `EngineSupervisor.restartAsync()` = `stopAsync(scope)` + `start(scope)`。
  走的是**退出路径同款的异步停法**：状态立刻变更、收尾丢后台，并把这次收尾记进
  `pendingShutdown`，监督循环 spawn 之前会等它跑完（I1 的既有保证），所以不会复现"新引擎被旧强杀打掉"。
  界面三处（设置页 2 处 / 扩展页 1 处）改调 `restartAsync()`。
- **验证**：重启过程中 `logcat` 不再出现 `Skipped N frames!`；
  引擎能在 3180 正常起来、`engine.log` 里没有 EADDRINUSE 循环。
  **用户已实测确认：不再卡死。**

### J2. ★设计前提翻车：「留白区涂纯白 = 和引擎网页连成一片」并不成立

- **现象**（用户报障）：对话页底栏旁边"好大一片白色的背景，很突兀"。
- **根因**：`UI-REDESIGN.md` §4.2 的方案是「底栏留白区涂**纯白**，与网页白底连成一片」。
  但真机上**引擎网页最底部的底色不是纯白** —— 像素实测（Dev 版对话页截图）：
  网页底部是浅蓝灰（`#ddddde`~`#e2e4eb`），而底栏是 `#ffffff`，交界处就是一条明显的白条。
  中途试过"运行时用 JS 读网页底色再染底栏"（v1.2.38），但网页的 `body` 是白的、
  真正显色的是里层容器，收益不稳定，最后**改成按页面固定垫色**：
  对话页纯白、其它页透明（`UI-REDESIGN.md` §9.2 第 3 条）。
- **教训**：**"看起来应该一样"的两种颜色，必须量像素确认**。
  截图 → 解 PNG 采样是最省事的办法（见 `$DSH_HOME/tmp/png-probe2.cjs`）。
  注意这台机器是 HDR / wide-gamut 屏，绝对色值可能被色彩管线影响，
  **只信同一张图内的相对差异**。

### J3. ★玻璃按钮落在玻璃面板上 = 隐身（白底 + 白描边）

- **现象**（用户报障）：扩展中心「停用」按钮"和背景一样颜色，看不到"。
- **根因**：次要按钮照预览稿做成「白 66% + 白描边」，预览稿里它落在**页面渐变**上所以看得见；
  真机上它落在**玻璃面板**（白 80%→58%）上 —— 白叠白，对比度约 1.0。
- **修法**：描边改用既有「细线」令牌同款的淡墨色 `#1F0F1216`，底色微调 `#B8FFFFFF`。
- **教训**：**叠层底色要一起算** —— 设计稿的"底"和实现的"底"不是同一层。

### J4. ★★`dl-resume.js` 断点续传会把产物拼坏（必须整包重下 + 校验 digest）

- **现象**：拉 CI 产物时脚本报告"下载完成 123481031 字节"，`unzip` 却报
  `bad CRC`、解出来的 APK 少了 2 万字节。
- **根因**：网络中断后按 `Range: bytes=<已有字节>-` 续传，实际拿到的那一段与已有内容**对不齐**
  （中间有一次 403 换签名地址后重试），文件长度对但内容错位。
- **修法/纪律**：**产物一律整包重下一次**，并用 GitHub API 给的
  `digest`（`sha256:...`）核对；对不上就重来。
  现成脚本：`$DSH_HOME/tmp/dl-full.cjs <run_id> <输出路径>`（每次都从头下 + 校验）。
- **验证**：下载完的 sha256 与 `artifacts` 接口返回的 `digest` 逐字符相同，`unzip -t` 无错。

### J5. ★启动页顶部多了一枚重复的「引擎启动中」胶囊（v1.2.43 已删）

- **现象**（用户报障）：启动页（加载动画）顶部浮着一枚玻璃胶囊写着「引擎启动中」，
  而加载页正中间本来就写着「正在启动引擎…」。
- **根因**：设计稿 §4.5 给顶部胶囊安排了两个职责 ——「预览模式返回」和「引擎未就绪时说明原因」。
  但**后一半的前提不成立**：引擎只要不就绪，加载页就会盖住对话页、并把原因写在加载页上，
  所以状态胶囊在任何情况下都不会"雪中送炭"，只会重复表达。
- **修法**：`MainActivity.renderCapsule()` 删掉状态那一支，胶囊只在
  「对话页 + 预览模式」出现（「← 主页」）；顺带删掉只服务于它的 `engineState` 字段。
- **验证**：启动过程中截图，顶部区域不再有任何玻璃块；预览模式下「← 主页」照常出现。
  （v1.2.44 连预览模式那一枚也整条删掉了，见 J7。）
- **教训**：**同一个信息不要有两个出口**；设计稿里的"兼任"要先问一句"这个场景真的会出现吗"。

### J6. ★★全屏窗口漏处理 IME inset → 键盘盖住底部输入框（v1.2.44 已修）

- **现象**（用户报障）：点开输入法，底部输入栏被键盘整个盖住，看不见自己在打什么。
- **根因**：`MainActivity.setupEdgeToEdge()` 为「覆盖状态栏」把窗口设成全屏
  （`SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`），
  但它的 `setOnApplyWindowInsetsListener` **只取了 `systemWindowInsetTop` 把内容下移，底部 IME 一字未提**。
  窗口一旦是全屏的，**Android 15+ 就不再自动把窗口压矮** —— `adjustResize` 只是个请求，不生效。
  真机实测（键盘弹起时 `dumpsys window`）：窗口 `mAppBounds=Rect(0, 135 - 1216, 2639)`，
  底边 2639 就是屏幕底，纹丝不动。网页 `height:100%` 跟着窗口走，输入栏于是钉在屏幕底、被键盘压住。
- **修法**：新增 `applyImeInset(insets)` —— 取 `WindowInsets.Type.ime().bottom`（键盘高度），
  给 `rootView` 加等高的底部 padding，内容整体顶上去，键盘正好填进那块空位。
  只在 API 30+ 生效：更早的系统窗口本来就会自动压缩，平台也不分发 ime inset。
- **验证**：CI 产物解出 `classes4.dex` 后 `rg -a applyImeInset` 能搜到（改动确实进了二进制）；
  包名 `app.dsh.mobile.dev`、签名与已装版 SHA-256 一致、versionCode 91 > 90 故可覆盖安装。
  **用户已实测确认：不再遮挡。**
- **教训**：**接管 insets 就要接管全部四条边**。开了 edge-to-edge 之后，顶部状态栏、底部导航栏、
  键盘三样都得自己摆位，漏一个就是这个症状；而且新系统上「设了 `adjustResize` 就万事大吉」不再成立。


### J7. ★预览返回胶囊「点了不消失」→ 整条删掉（v1.2.44 已删）

- **现象**（用户报障）：打开 AI 给的预览链接后，屏幕顶部出现一枚悬浮框（返回 / 重启），
  点「返回主会话」之后它不跟着消失。
- **根因**：这枚悬浮框的显隐**只**由 WebView 的 `doUpdateVisitedHistory` 驱动
  （`updatePreviewChrome()` → `renderCapsule()`）。这条链路里只要有一次导航事件没来，
  或者引擎没就绪、连"回主会话"的 URL 都拼不出来（`healthyPort == 0`），胶囊就留在屏幕上。
  官方版那条悬浮工具栏是同一套逻辑，所以它也有同样的毛病。
- **修法**：**不再自造这个控件** —— `MainActivity` 删掉 `capsule` / `capsuleText` / `previewMode` /
  `CAPSULE_TOP_DP` 字段与 `setupCapsule()` / `renderCapsule()` / `updatePreviewChrome()` 及其全部调用点；
  布局里的胶囊、以及随它失去引用的 `bg_glass_pill.xml` 与 `btn_back` 字符串一并删除。
  从预览页回主会话交给**系统返回键**：`onBackPressed()` 本来就是"能退网页就退网页，
  退不了才最小化"，不需要额外 UI。
- **验证**：CI 编译通过；解包核对 `resources.arsc` 里 `btn_back` / `capsule` 已无命中。
- **教训**：给"回上一屏"做的临时浮层，**能复用系统返回键就别自造控件**；
  靠导航事件维持显隐的悬浮控件，失效模式是"关不掉"，比没有还糟。
