package app.dsh.mobile.engine

import org.json.JSONObject
import java.io.File

/**
 * 会话活动探测（流体云自动层的数据源）。
 *
 * 只看 `$DSH_HOME/sessions/<项目>/session-<uuid>/session.jsonl.zstd` 的**修改时间**：
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
                runCatching { session.listFiles() }.getOrNull()?.forEach { file ->
                    // 文件名随引擎版本变（实测：官方版 1.2.25 = session.jsonl.zstd，
                    // dev 版 1.2.26 = session.v3.jsonl.zstd）→ 按后缀匹配，不写死文件名
                    if (!file.name.endsWith(SESSION_SUFFIX)) return@forEach
                    val stamp = file.lastModified()
                    if (stamp > projectNewest) projectNewest = stamp
                }
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

    /**
     * 会话 id → 它所属工作区的**标题**（读 DSH_HOME/storages/workspace.json）。
     *
     * 用途（v1.2.31）：岛上优先显示"你正在用的那个会话的项目"。引擎侧插件把
     * 「用户发消息的会话 id」「Agent 正在跑的会话 id」报给 App，这里把它翻译成工作区标题。
     * 读不到（文件不存在 / root 属主读不了 / 没有匹配）就返回 null，
     * 调用方回落到 [snapshot] 的"最近有写入的项目"，不抛异常。
     */
    fun projectOfSession(sessionId: String): String? {
        val key = normalizeSessionId(sessionId)
        if (key.isEmpty()) return null
        val root = runCatching {
            JSONObject(File(home, "storages/workspace.json").readText())
        }.getOrNull() ?: return null
        val workspaces = root.optJSONObject("tables")?.optJSONObject("workspaces") ?: return null
        for (id in workspaces.keys()) {
            val ws = workspaces.optJSONObject(id) ?: continue
            val ids = ws.optJSONArray("sessionIds") ?: continue
            for (i in 0 until ids.length()) {
                if (normalizeSessionId(ids.optString(i)) != key) continue
                val path = ws.optString("path")
                val title = ws.optString("title").ifEmpty { path.substringAfterLast("/") }
                return title.ifEmpty { null }
            }
        }
        return null
    }

    /** 各处的会话 id 写法可能带/不带 session- 前缀，比较前统一去掉 */
    private fun normalizeSessionId(id: String): String = id.removePrefix("session-")

    /** 目录名 → 可显示的项目名（取路径最后一段） */
    private fun decodeName(encoded: String): String {
        val body = encoded.removePrefix("--").removeSuffix("--")
        // 编码形如 ~5F00~53D1~：相邻字符**共用中间的 ~**，所以按 ~ 切段后逐段解码；
        // 用正则匹配 ~XXXX~ 会漏掉第二个字（实测得到"开53D1"）
        val decoded = body.split('~').joinToString("") { seg ->
            if (seg.length == 4 && seg.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                seg.toInt(16).toChar().toString()
            } else {
                seg
            }
        }
        // `-` 既是路径分隔符也可能是名字里的连字符，无法区分：
        // 先按路径还原试试，真的存在就取末段；不存在就退回"最后一个连字符之后"。
        val asPath = File("/" + decoded.replace('-', '/'))
        if (asPath.isDirectory) return asPath.name.ifEmpty { decoded }
        return decoded.substringAfterLast('-').ifEmpty { decoded }
    }

    private companion object {
        /** 会话文件名后缀（引擎版本间会变，只匹配后缀） */
        const val SESSION_SUFFIX = ".jsonl.zstd"

        /** 多久内有写入就算这个项目"在活跃"（用户选定：5 分钟） */
        const val ACTIVE_WINDOW_MS = 5 * 60 * 1000L
    }
}
