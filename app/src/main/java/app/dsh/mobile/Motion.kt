package app.dsh.mobile

import android.content.Context
import android.provider.Settings

/**
 * 动效开关。
 *
 * docs/UI-REDESIGN.md §2：必须尊重系统的「减少动态效果」——
 * 开发人员选项 / 无障碍里的动画时长倍率被设成 0 时，所有动画都不播（直接跳到终态）。
 */
object Motion {

    fun reduced(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
