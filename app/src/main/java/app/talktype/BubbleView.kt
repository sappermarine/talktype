package app.talktype

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.min

/** The floating mic button: dark when idle, red with a loudness halo while recording, spinner while transcribing. */
class BubbleView(context: Context) : View(context) {
    enum class State { IDLE, RECORDING, PROCESSING }

    var state = State.IDLE
        set(value) {
            field = value
            level = 0f
            contentDescription = when (value) {
                State.IDLE -> "Start dictation"
                State.RECORDING -> "Stop dictation"
                State.PROCESSING -> "Transcribing, tap to cancel"
            }
            invalidate()
        }

    /** Shows a small blue dot so the user can tell the cloud engine is selected. */
    var cloud = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Microphone loudness, 0..1. Only drawn while recording. */
    var level = 0f
        set(value) {
            // Rise fast, fall slowly so the halo doesn't flicker.
            field = if (value > field) value else field * 0.8f + value * 0.2f
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55E5484D }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4C9AFF.toInt() }
    private val badgeRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val micSource: Path = PathParser.createPathFromPathData(
        "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z " +
            "M17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V21h2v-3.08c3.39,-0.49 6,-3.39 6,-6.92h-2z",
    )
    private val mic = Path()
    private val matrix = Matrix()
    private val oval = RectF()

    init {
        state = State.IDLE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        val core = outer * 0.74f

        if (state == State.RECORDING) canvas.drawCircle(cx, cy, core + (outer - core) * level, halo)

        fill.color = if (state == State.RECORDING) 0xFFE5484D.toInt() else 0xF0202124.toInt()
        fill.setShadowLayer(outer * 0.12f, 0f, outer * 0.04f, 0x55000000)
        canvas.drawCircle(cx, cy, core, fill)

        if (state == State.PROCESSING) {
            val r = core * 0.5f
            arc.strokeWidth = core * 0.12f
            oval.set(cx - r, cy - r, cx + r, cy + r)
            val angle = (SystemClock.uptimeMillis() % 900) / 900f * 360f
            canvas.drawArc(oval, angle, 270f, false, arc)
            postInvalidateOnAnimation()
        } else {
            // The mic glyph is drawn on a 24x24 grid.
            val scale = core * 1.1f / 24f
            matrix.setScale(scale, scale)
            matrix.postTranslate(cx - 12f * scale, cy - 12f * scale)
            micSource.transform(matrix, mic)
            canvas.drawPath(mic, icon)
        }

        if (cloud) {
            val bx = cx + core * 0.68f
            val by = cy - core * 0.68f
            canvas.drawCircle(bx, by, core * 0.26f, badgeRim)
            canvas.drawCircle(bx, by, core * 0.19f, badge)
        }
    }
}
