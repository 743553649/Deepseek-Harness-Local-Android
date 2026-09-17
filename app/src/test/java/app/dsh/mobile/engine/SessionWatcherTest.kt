package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SessionWatcher 的纯逻辑回归测试（JVM，无需设备）。
 *
 * 为什么专门测它：目录名编码（`/`→`-`、非 ASCII → `~XXXX~`，相邻字符合并中间的 `~`）
 * 与"会话文件名随引擎版本变"这两处都**踩过坑**（曾把"开发"解成"开53D1"；
 * 曾写死 `session.jsonl.zstd` 而 dev 引擎用的是 `session.v3.jsonl.zstd`）。
 * 这段逻辑跑在 App 进程里、只靠人工真机验证，回归必漏 —— 所以固定成测试。
 */
class SessionWatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个"项目目录/会话目录/session 文件"结构，返回 DSH_HOME */
    private fun home(
        projectDir: String,
        sessionDir: String = "session-0000",
        fileName: String = "session.v3.jsonl.zstd",
        mtime: Long = System.currentTimeMillis(),
    ): File {
        // 注意用无参 newFolder()：带名字的版本在同一个测试里调第二次会因目录已存在而抛异常（CI 实测踩到）
        val home = tmp.newFolder()
        val f = File(File(File(home, "sessions"), projectDir), sessionDir).apply { mkdirs() }
        File(f, fileName).writeText("x")
        File(f, fileName).setLastModified(mtime)
        return home
    }

    @Test
    fun `目录名解码中文（相邻字符共用中间的波浪号）`() {
        // `--storage-emulated-0-~5F00~53D1--` = /storage/emulated/0/开发
        val w = SessionWatcher(home("--storage-emulated-0-~5F00~53D1--"))
        assertEquals("开发", w.snapshot().project)
    }

    @Test
    fun `会话文件名随引擎版本变 —— 两种后缀都要认`() {
        // dev 引擎 1.2.26 用 session.v3.jsonl.zstd
        assertEquals(
            "proj",
            SessionWatcher(home("--storage-emulated-0-proj--", fileName = "session.v3.jsonl.zstd"))
                .snapshot().project,
        )
        // 官方版 1.2.25 用 session.jsonl.zstd
        assertEquals(
            "proj",
            SessionWatcher(home("--storage-emulated-0-proj--", fileName = "session.jsonl.zstd"))
                .snapshot().project,
        )
    }

    @Test
    fun `无关文件不算会话（避免把 lock 等当活跃信号）`() {
        val w = SessionWatcher(home("--storage-emulated-0-proj--", fileName = "session.lock"))
        assertNull(w.snapshot().project)
        assertEquals(0, w.snapshot().activeProjects)
    }

    @Test
    fun `活跃项目计数按 5 分钟窗口`() {
        val home = tmp.newFolder("dsh-home-2")
        val now = System.currentTimeMillis()
        fun put(project: String, ageMs: Long) {
            val f = File(File(File(home, "sessions"), project), "session-1").apply { mkdirs() }
            File(f, "session.v3.jsonl.zstd").writeText("x")
            File(f, "session.v3.jsonl.zstd").setLastModified(now - ageMs)
        }
        put("--storage-emulated-0-a--", 60_000)          // 1 分钟前 → 活跃
        put("--storage-emulated-0-b--", 10 * 60_000)     // 10 分钟前 → 不活跃
        val snap = SessionWatcher(home).snapshot(now)
        assertEquals(1, snap.activeProjects)
        // 最近写入的是最"新"的那个项目（这里是 a）
        assertEquals("a", snap.project)
    }

    @Test
    fun `没有会话时返回空快照（岛上回落到 DSH）`() {
        val home = tmp.newFolder("empty-home")
        File(home, "sessions").mkdirs()
        val snap = SessionWatcher(home).snapshot()
        assertNull(snap.project)
        assertEquals(0, snap.activeProjects)
    }
}
