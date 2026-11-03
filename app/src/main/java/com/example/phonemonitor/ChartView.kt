package com.example.phonemonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartView(ctx: Context) : View(ctx) {
    class Pt(val t: Long, val v: Float, val hi: Boolean)

    var title = ""
    var unit = ""
    var fixedMin: Float? = null
    var fixedMax: Float? = null
    var longRange = false
    var colorNormal = Theme.BLUE
    var colorHi: Int? = null          // 充電中區段的顏色(可選)
    var axisStart: Long? = null       // 固定時間軸(單日檢視用)
    var axisEnd: Long? = null

    private var pts: List<Pt> = emptyList()
    private var touchX = -1f
    private var downX = 0f
    private var downY = 0f
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Theme.TRACK; strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textSize = 10f * d }
    private val ttl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = 13f * d; isFakeBoldText = true }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; strokeWidth = 1f * d }

    fun setData(p: List<Pt>) {
        val step = if (p.size > 600) Math.ceil(p.size / 600.0).toInt() else 1
        pts = if (step > 1) p.filterIndexed { i, _ -> i % step == 0 || i == p.size - 1 } else p
        touchX = -1f
        invalidate()
    }

    private fun fmt(v: Float) = if (Math.abs(v) >= 100f) "%.0f".format(v) else "%.1f".format(v)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = 38f * d; val r = w - 10f * d; val t = 34f * d; val b = h - 20f * d
        c.drawText(if (unit.isEmpty()) title else "$title ($unit)", 6f * d, 18f * d, ttl)
        if (pts.size < 2) { c.drawText("資料不足,請先開始監測", l, (t + b) / 2, txt); return }

        var lo = fixedMin ?: pts.minOf { it.v }
        var hi = fixedMax ?: pts.maxOf { it.v }
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        if (fixedMin == null) { val pad = (hi - lo) * 0.1f; lo -= pad; hi += pad }
        val t0 = axisStart ?: pts.first().t; val t1 = maxOf(axisEnd ?: pts.last().t, t0 + 1)
        fun x(tt: Long) = l + (r - l) * (tt - t0).toFloat() / (t1 - t0).toFloat()
        fun y(v: Float) = b - (b - t) * (v - lo) / (hi - lo)

        for (k in 0..2) {
            val yy = t + (b - t) * k / 2f
            c.drawLine(l, yy, r, yy, grid)
            c.drawText(fmt(hi - (hi - lo) * k / 2f), 4f * d, yy + 4f * d, txt)
        }

        val path = Path()
        for (k in 1 until pts.size) {
            val a = pts[k - 1]; val q = pts[k]
            if (q.t - a.t > 65 * 60_000L) continue   // 資料中斷不連線
            val col = if (q.hi && colorHi != null) colorHi!! else colorNormal
            path.reset()
            path.moveTo(x(a.t), b); path.lineTo(x(a.t), y(a.v)); path.lineTo(x(q.t), y(q.v)); path.lineTo(x(q.t), b); path.close()
            fill.color = (col and 0x00FFFFFF) or 0x30000000
            c.drawPath(path, fill)
            line.color = col
            c.drawLine(x(a.t), y(a.v), x(q.t), y(q.v), line)
        }

        val f = SimpleDateFormat(if (longRange) "MM/dd HH:mm" else "HH:mm", Locale.getDefault())
        c.drawText(f.format(Date(t0)), l, h - 4f * d, txt)
        val e = f.format(Date(t1)); c.drawText(e, r - txt.measureText(e), h - 4f * d, txt)

        if (touchX >= l && touchX <= r) {
            val tt = t0 + ((touchX - l) / (r - l) * (t1 - t0)).toLong()
            val p = pts.minByOrNull { Math.abs(it.t - tt) } ?: return
            c.drawLine(x(p.t), t, x(p.t), b, mark)
            c.drawCircle(x(p.t), y(p.v), 4f * d, mark)
            val s = "${fmt(p.v)}$unit  ${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(p.t))}"
            c.drawText(s, r - ttl.measureText(s), 18f * d, ttl)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; touchX = e.x; invalidate() }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(e.x - downX) > Math.abs(e.y - downY) * 1.2f && Math.abs(e.x - downX) > 8f)
                    parent.requestDisallowInterceptTouchEvent(true)
                touchX = e.x; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
