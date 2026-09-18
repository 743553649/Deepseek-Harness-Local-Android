package app.dsh.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max

/**
 * 启动页的进度条：圆角胶囊轨道 + 主色渐变填充。
 *
 * - `progress = 0f..1f` → 确定性进度（解压/安装有真实百分比时）
 * - `progress = null`  → 不确定态：一段渐变在轨道上反复扫过（而不是系统那根又细又暗的线）
 *
 * 同样是为了摆脱系统 ProgressBar 的廉价观感而自绘。
 */
class GlassProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** null = 不确定态（来回扫），0f..1f = 真实进度 */
    var progress: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            syncAnimation()
            invalidate()
        }

    private var sweep = 0f
    private var animator: ValueAnimator? = null

    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TRACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val accent = context.getColor(R.color.accent)
    private val accentDeep = context.getColor(R.color.accent_deep)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, accent, accentDeep, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val p = progress
        if (p != null) {
            drawSegment(canvas, r, 0f, max(width * p, height.toFloat()))
        } else {
            // 不确定态：一段 30% 宽的渐变从左扫到右
            val segW = width * SEG_RATIO
            val x = -segW + sweep * (width + segW)
            drawSegment(canvas, r, x, x + segW)
        }
    }

    private fun drawSegment(canvas: Canvas, r: Float, left: Float, right: Float) {
        if (right - left <= 0f) return
        rect.set(left, 0f, right, height.toFloat())
        canvas.drawRoundRect(rect, r, r, fillPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        syncAnimation()
    }

    /** 系统开了「减少动态效果」就不扫，停在中间那段 */
    private fun syncAnimation() {
        val wantAnim = isAttachedToWindow && visibility == VISIBLE &&
            progress == null && !Motion.reduced(context)
        if (!wantAnim) {
            animator?.cancel()
            animator = null
            if (progress == null && Motion.reduced(context)) {
                sweep = 0.5f - SEG_RATIO / 2f
                invalidate()
            }
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private companion object {
        const val SEG_RATIO = 0.3f
        const val SWEEP_MS = 1400L
        const val COLOR_TRACK = 0x140F1216
    }
}
