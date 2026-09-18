package app.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 关于页（底栏第四个目的地）：项目信息 + 版本。
 * 内容来自原来的独立「关于」页 —— 那一页现在已无入口，所以并到这里。
 */
class AboutPage(private val host: Activity) {

    fun bind() {
        host.findViewById<LinearLayout>(R.id.rowAboutRepo).setOnClickListener {
            val cm = host.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("dsh-android", host.getString(R.string.about_repo))
            )
            Toast.makeText(host, "已复制仓库地址", Toast.LENGTH_SHORT).show()
        }
    }

    fun onShown() {
        host.findViewById<TextView>(R.id.txtAboutVersion).text =
            host.getString(R.string.about_version, versionName())
    }

    /** 从包管理器读取 versionName（AGP 8+ 默认关闭 BuildConfig，避免依赖它） */
    private fun versionName(): String =
        runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }
            .getOrNull() ?: "?"
}
