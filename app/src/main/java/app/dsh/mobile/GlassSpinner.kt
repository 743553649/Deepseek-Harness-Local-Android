package app.dsh.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 启动页的转圈：一圈淡色轨道 + 一段带主色渐变的圆弧匀速转。
 *
 * 为什么自绘：系统自带的 `?android:attr/progressBarStyle` 是十年前的 Material 圆环，
 * 和本项目的液态玻璃观感完全不搭（用户报障：启动动画"很简陋廉价"）。
 */
class GlassSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var angle = 0f
    private var animator: ValueAnimator? = null

    private val stroke = dp(3f)
    private val rect = RectF()

    private val accent = context.getColor(R.color.accent)
    private val accentDeep = context.getColor(R.color.accent_deep)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = COLOR_TRACK
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        // 渐变跟着画布转（见 onDraw 的 canvas.rotate），所以固定按 0° 起算
        arcPaint.shader = SweepGradient(
            cx, cy, intArrayOf(accent, accentDeep, accent), null,
        )
        val inset = stroke / 2f
        rect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        val r = (width - stroke) / 2f
        canvas.drawCircle(width / 2f, height / 2f, r, trackPaint)

        canvas.save()
        // 让渐变跟着圆弧一起转：圆弧头部永远是主色最亮的那一端
        canvas.rotate(angle, width / 2f, height / 2f)
        canvas.drawArc(rect, 0f, SWEEP_DEG, false, arcPaint)
        canvas.restore()
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

    /** 系统开了「减少动态效果」就停在静止圆弧上（§2 的硬要求） */
    private fun syncAnimation() {
        val wantAnim = isAttachedToWindow && visibility == VISIBLE && !Motion.reduced(context)
        if (!wantAnim) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = TURN_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        /** 圆弧占整圈的比例：留个缺口才看得出在转 */
        const val SWEEP_DEG = 250f
        const val TURN_MS = 1100L
        const val COLOR_TRACK = 0x140F1216
    }
}
