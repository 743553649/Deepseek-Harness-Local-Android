# 真机本地构建（Android / aarch64）

**一句话**：在手机自身（`arm64` 真机）上跑 `assembleDebug`，得到 APK。
**用途**：改 Kotlin/Java/资源后想立刻知道「编不编得过」，不必等 CI 那 2~20 分钟。
**不是用途**：产出可分发/可安装生效的包 —— 本地产物缺 `assets/runtime.zip`（见下）。

```bash
bash scripts/local-build.sh          # 出 debug APK
bash scripts/local-build.sh test     # 跑 CI 里那步 testDebugUnitTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 为什么需要单独的脚本

CI（`ubuntu-24.04`）能编是因为 runner 自带 SDK + NDK。真机上有两个硬约束：

1. **没有可用的 NDK。** Google 只发布 `linux-x86_64` / `darwin` / `windows` 宿主的 NDK 工具链，
   没有 `aarch64` 宿主版。`app/src/main/cpp/dsh_pty.c` 走 CMake 编 `libdshpty.so` 这条路，
   在真机上**物理上跑不了**。
2. **AGP 自带的 aapt2 是 x86_64。** 它会从 Google Maven 拉 `aapt2-8.5.2-*-linux`，
   在 arm64 上一执行就报 `aapt2: syntax error: unexpected '('`（其实是 loader 跑不动 x86_64 ELF，
   退化成被 shell 当脚本解析）。

对策：`-PusePrebuiltPty` 用 `app/prebuilt/pty/arm64-v8a/libdshpty.so`（取自官方 APK 的预编译产物）
代替 CMake 输出；aapt2 用扩展中心那份 arm64 版本覆盖。

**CI 完全不受影响**：不带 `-PusePrebuiltPty` 时，`externalNativeBuild` 照常配置、jniLibs 源目录不注册，
行为和改动前一致（也不会出现两份同名 `.so`）。

---

## 一次性准备

| 依赖 | 位置 | 说明 |
|---|---|---|
| Gradle **8.9** | `$DSH_HOME/work/tools/gradle-8.9/` | 必须与 CI 的 `setup-gradle(gradle-version: "8.9")` 同版本；AGP 8.5.2 与 Gradle 9 不兼容 |
| Android SDK | `/data/user/0/app.dsh.mobile/files/android-sdk` | 已有 `platforms/android-36`、`platform-tools` |
| build-tools **34.0.0** | 同上 `build-tools/34.0.0` | 官方完整包（`build-tools_r34-linux.zip`），其中 `aapt`/`aapt2`/`zipalign` 已换成指向扩展中心 arm64 版本的软链接 |
| arm64 aapt2 | `engine/extensions/android-buildtools/bin/aapt2` | 由扩展中心提供 |

> ⚠️ SDK 自带的 `build-tools/36.0.0` **不能用**：它是手工拼的（四个软链接 + 空 `lib/`），
> AGP 校验会直接判 `Installed Build Tools revision 36.0.0 is corrupted`。
> 别去"修"它，用 34.0.0 那份完整的。

---

## 关键参数（少一个就失败）

| 参数 | 不加会怎样 |
|---|---|
| `-PusePrebuiltPty` | 走 CMake → 真机没有 NDK，构建失败；且若 SDK 里被自动装了 x86_64 的 NDK，会在链接阶段报无法执行 |
| `-Pandroid.aapt2FromMavenOverride=<arm64 aapt2>` | `processDebugResources` 失败：`aapt2: syntax error: unexpected '('` |
| `-Pabi=arm64-v8a` | 与 CI 矩阵不一致（`app/build.gradle.kts` 默认已是 arm64-v8a，显式给更稳） |
| `GRADLE_USER_HOME=$DSH_HOME/work/.gradle` | 默认会落到 `HOME`（引擎里是 `/`）→ 依赖缓存散在只读根分区附近 |
| `--no-daemon` | 手机上留一个常驻 JVM 守护进程，白吃内存 |

---

## 已知边界

- **本地产物装上去引擎起不来**：`assets/` 下只有 `runtime/MANIFEST.json`，
  没有 `runtime.zip`（运行时闭包由 CI 的 `collect-runtime` job 注入，且该文件 `.gitignore` 掉了）。
  `AGENTS.md` 里"因此本地无法直接 assembleDebug"这句话只对了一半：**编译能过，产物不可用**。
- **签名与 CI 不同**：本机用的是 Termux 版 JDK 的隐式 debug 密钥库，
  实际路径 `/data/data/com.termux/files/home/.android/debug.keystore`
  （这套 JDK 把 `user.home` 写死在 Termux 家目录，**不是** `~/.android`）。
  CI 用的是仓库根的 `.ci/debug.keystore`。两者不同 → 两个包不能互相覆盖安装。
- **`libdshpty.so` 是快照**：改了 `dsh_pty.c` 后本机编不出来，要等 CI 出新的再替换这份 `.so`。

---

## 踩坑记录

| 现象 | 根因 | 修法 |
|---|---|---|
| `Installed Build Tools revision 36.0.0 is corrupted` | SDK 里那份 36.0.0 是手工拼的，缺 `aidl`/`d8`/`lib/*.jar` | 装官方 `build-tools_r34-linux.zip` 到 `build-tools/34.0.0` |
| `aapt2: syntax error: unexpected '('` | AGP 从 Maven 拉的 aapt2 是 x86_64 | `-Pandroid.aapt2FromMavenOverride` 指向 arm64 aapt2 |
| `cmake` / NDK 相关失败 | 真机没有 aarch64 宿主 NDK | `-PusePrebuiltPty` |
| 配置阶段 AGP 自动下载 2GB NDK（`ndk/26.1.10909125`） | AGP 的 SDK 自动下载默认开着 | 用开关绕开 CMake；下下来的那份是 x86_64 宿主，直接删 |
| `Observed package id 'platform-tools' in inconsistent location` | SDK 里多了个 `platform-tools-2` | 仅警告，不影响构建 |

---

## 实测记录（2026-09-19，本机）

- `BUILD SUCCESSFUL`，产物 1,683,789 字节
- `aapt2 dump badging`：`app.dsh.mobile.dev` / versionCode 92 / versionName 1.2.45 / compileSdk 36 / minSdk 26 / targetSdk 28
- `apksigner verify --print-certs`：V2 签名，`CN=Android Debug`
- `zipalign -c -v 4`：`Verification successful`
- APK 内含 `lib/arm64-v8a/libdshpty.so`（8144 字节）
