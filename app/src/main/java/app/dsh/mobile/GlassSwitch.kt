package app.dsh.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * 玻璃开关（docs/UI-REDESIGN.md §3.4）：44×26dp 轨道 + 22dp 圆钮，点击切换、圆钮滑动。
 *
 * 为什么自绘：仓库没有 Material 依赖，系统 Switch / SwitchCompat 的样式改不动。
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 程序化同步（进页面时回填状态）：不触发 [OnCheckedChange]，避免回调里再写库的死循环 */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    /** 用户点开关后的回调（只有点击路径才会触发） */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    /** 0=关 1=开，动画的中间值 */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val trackW = dp(44f)
    private val trackH = dp(26f)
    private val knobR = dp(11f)

    private val accent = context.getColor(R.color.accent)
    private val accentDeep = context.getColor(R.color.accent_deep)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        // 轨道：先铺「关」的灰底，再按动画进度叠「开」的主色渐变
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        trackPaint.shader = null
        trackPaint.color = COLOR_TRACK_OFF
        canvas.drawRoundRect(rect, height / 2f, height / 2f, trackPaint)

        if (progress > 0f) {
            trackPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), accent, accentDeep, Shader.TileMode.CLAMP,
            )
            trackPaint.alpha = (progress * 255).toInt()
            canvas.drawRoundRect(rect, height / 2f, height / 2f, trackPaint)
            trackPaint.alpha = 255
            trackPaint.shader = null
        }

        // 圆钮：从左 2dp 滑到右 2dp
        val cx = 2 * density + knobR + progress * (width - 4 * density - 2 * knobR)
        knobPaint.shader = LinearGradient(
            cx, 0f, cx, height.toFloat(), COLOR_KNOB_TOP, COLOR_KNOB_BOTTOM, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, height / 2f, knobR, knobPaint)
        knobPaint.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> performClick()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        isChecked = !isChecked
        onCheckedChange?.invoke(isChecked)
        return true
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        // 还没上屏（进页面时回填）或系统关了动效 → 直接落到终态
        if (!isLaidOut || Motion.reduced(context)) {
            setProgress(target)
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ANIM_MS
            interpolator = PathInterpolator(0.34f, 1.3f, 0.4f, 1f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setProgress(value: Float) {
        progress = value
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private val density get() = resources.displayMetrics.density

    private companion object {
        const val ANIM_MS = 240L

        /** 关：rgba(15,18,22,.10)；圆钮：白 → #EAEEF3 竖渐变（§3.4） */
        const val COLOR_TRACK_OFF = 0x1A0F1216
        const val COLOR_KNOB_TOP = 0xFFFFFFFF.toInt()
        const val COLOR_KNOB_BOTTOM = 0xFFEAEEF3.toInt()
    }
}
