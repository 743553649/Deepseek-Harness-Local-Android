package app.dsh.mobile.engine

import java.io.File

/**
 * 会话活动探测（流体云自动层的数据源）。
 *
 * 只看 `$DSH_HOME/sessions/<项目>/session-*/session.jsonl.zstd` 的**修改时间**：
 * 不解压、不读内容，所以不绑定引擎的内部数据格式，引擎升级也不会碎。
 *
 * 实测依据（PKX110 / ColorOS 16）：
 *  - 模型生成（思考）期间会话文件**连续在写**（每秒 +91B~+6KB）→ 能盖住"思考中"；
 *  - 长命令执行期间可能几十秒到几分钟**完全不动**（实测一次 60 秒零写入）
 *    → 这一段的准确性靠引擎插件上报的 agent/status 补，不靠这个。
 *
 * 项目目录名是编码过的（`/`→`-`，非 ASCII 字符→`~XXXX~` 十六进制码元，两端包 `--`），
 * 例如 `--storage-emulated-0-~5F00~53D1--` = `/storage/emulated/0/开发`。
 */
class SessionWatcher(private val home: File) {

    /** @param project 最近活动的项目名（给岛上显示） @param activeProjects 窗口内活跃的项目个数 */
    data class Snapshot(val project: String?, val activeProjects: Int)

    private val sessionsRoot: File get() = File(home, "sessions")

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        val projects = runCatching { sessionsRoot.listFiles() }.getOrNull() ?: return Snapshot(null, 0)
        var newest = 0L
        var newestDir: File? = null
        var active = 0
        for (project in projects) {
            if (!project.isDirectory) continue
            var projectNewest = 0L
            runCatching { project.listFiles() }.getOrNull()?.forEach { session ->
                val stamp = File(session, SESSION_FILE).lastModified()
                if (stamp > projectNewest) projectNewest = stamp
            }
            if (projectNewest <= 0L) continue
            if (nowMs - projectNewest <= ACTIVE_WINDOW_MS) active++
            if (projectNewest > newest) {
                newest = projectNewest
                newestDir = project
            }
        }
        return Snapshot(newestDir?.let { decodeName(it.name) }, active)
    }

    /** 目录名 → 可显示的项目名（取路径最后一段） */
    private fun decodeName(encoded: String): String {
        val body = encoded.removePrefix("--").removeSuffix("--")
        val decoded = HEX_TOKEN.replace(body) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        // `-` 既是路径分隔符也可能是名字里的连字符，无法区分：
        // 先按路径还原试试，真的存在就取末段；不存在就退回"最后一个连字符之后"。
        val asPath = File("/" + decoded.replace('-', '/'))
        if (asPath.isDirectory) return asPath.name.ifEmpty { decoded }
        return decoded.substringAfterLast('-').ifEmpty { decoded }
    }

    private companion object {
        const val SESSION_FILE = "session.jsonl.zstd"

        /** 多久内有写入就算这个项目"在活跃"（用户选定：5 分钟） */
        const val ACTIVE_WINDOW_MS = 5 * 60 * 1000L

        val HEX_TOKEN = Regex("~([0-9A-Fa-f]{4})~")
    }
}
