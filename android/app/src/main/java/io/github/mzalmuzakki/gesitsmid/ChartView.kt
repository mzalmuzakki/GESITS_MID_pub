package io.github.mzalmuzakki.gesitsmid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale

/** Minimal line / bar chart drawn with Canvas (no chart library). */
class ChartView(ctx: Context, private val heightDp: Int = 140) : View(ctx) {
    data class Series(val label: String, val color: Int, val values: List<Float?>)

    var bars = false
    var series: List<Series> = emptyList()
        set(v) { field = v; invalidate() }
    var xLabels: List<String> = emptyList()
    var unit = ""

    private val dp = resources.displayMetrics.density
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 66, 78); strokeWidth = dp }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 148, 160); textSize = 10 * dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (heightDp * dp).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = 34 * dp; val bottom = height - 16 * dp; val top = 14 * dp; val right = width - 4 * dp
        val all = series.flatMap { it.values }.filterNotNull()
        if (all.isEmpty()) {
            c.drawText(L.t("No data yet"), left, (top + bottom) / 2, text); return
        }
        var lo = minOf(0f, all.min()); var hi = all.max()
        if (hi - lo < 1e-3f) hi = lo + 1f
        val y = { v: Float -> bottom - (v - lo) / (hi - lo) * (bottom - top) }
        c.drawLine(left, bottom, right, bottom, axis)
        c.drawText(fmt(hi) + unit, 0f, top + 4 * dp, text)
        c.drawText(fmt(lo), 0f, bottom, text)
        if (lo < 0) c.drawLine(left, y(0f), right, y(0f), axis)
        // legend
        var lx = left
        series.forEach { s ->
            fill.color = s.color; c.drawRect(lx, 2 * dp, lx + 8 * dp, 10 * dp, fill)
            val lb = L.t(s.label); c.drawText(lb, lx + 11 * dp, 10 * dp, text); lx += text.measureText(lb) + 24 * dp
        }
        val n = series.maxOf { it.values.size }.coerceAtLeast(1)
        val step = (right - left) / n
        series.forEachIndexed { si, s ->
            if (bars) {
                val bw = step * 0.7f / series.size
                fill.color = s.color
                s.values.forEachIndexed { i, v ->
                    if (v != null) {
                        val x0 = left + i * step + step * 0.15f + si * bw
                        c.drawRoundRect(RectF(x0, minOf(y(v), y(0f)), x0 + bw, maxOf(y(v), y(0f))), 2 * dp, 2 * dp, fill)
                    }
                }
            } else {
                line.color = s.color
                val p = Path(); var started = false
                s.values.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = left + (i + 0.5f) * step
                    if (!started) { p.moveTo(x, y(v)); started = true } else p.lineTo(x, y(v))
                }
                c.drawPath(p, line)
            }
        }
        if (xLabels.isNotEmpty()) {
            val every = ((xLabels.size * 40 * dp) / (right - left)).toInt().coerceAtLeast(1)
            xLabels.forEachIndexed { i, l ->
                if (i % every == 0) c.drawText(l, left + i * step + 2 * dp, height - 3 * dp, text)
            }
        }
    }

    private fun fmt(v: Float) = if (kotlin.math.abs(v) >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
}
