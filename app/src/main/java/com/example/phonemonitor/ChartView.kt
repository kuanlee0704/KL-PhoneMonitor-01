package com.example.phonemonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartView(ctx: Context) : View(ctx) {
    class Pt(val t: Long, val v: Float, val hi: Boolean)

    var title = ""
    var unit = ""
    var pts: List<Pt> = emptyList()
    var fixedMin: Float? = null
    var fixedMax: Float? = null
    var longRange = false
    var colorNormal = Color.parseColor("#1565C0")
    var colorHi = Color.parseColor("#2E7D32")   // 充電中

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; style = Paint.Style.STROKE }
    private val grid = Paint().apply { color = Color.parseColor("#DDDDDD"); strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 11f * d }
    private val ttl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13f * d; isFakeBoldText = true }

    fun setData(p: List<Pt>) { pts = p; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = 42f * d; val r = w - 8f * d; val t = 24f * d; val b = h - 18f * d
        c.drawText("$title ($unit)", 4f * d, 16f * d, ttl)
        if (pts.size < 2) {
            c.drawText("資料不足,請先開始監測", l, (t + b) / 2, txt); return
        }
        var lo = fixedMin ?: pts.minOf { it.v }
        var hi = fixedMax ?: pts.maxOf { it.v }
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        if (fixedMin == null) { val pad = (hi - lo) * 0.08f; lo -= pad; hi += pad }
        val t0 = pts.first().t; val t1 = maxOf(pts.last().t, t0 + 1)

        for (k in 0..2) {
            val y = t + (b - t) * k / 2f
            c.drawLine(l, y, r, y, grid)
            val v = hi - (hi - lo) * k / 2f
            c.drawText(if (hi - lo > 20) "%.0f".format(v) else "%.1f".format(v), 2f * d, y + 4f * d, txt)
        }
        fun x(tt: Long) = l + (r - l) * (tt - t0).toFloat() / (t1 - t0).toFloat()
        fun y(v: Float) = b - (b - t) * (v - lo) / (hi - lo)

        for (k in 1 until pts.size) {
            val a = pts[k - 1]; val q = pts[k]
            // 資料中斷(超過 1 小時沒紀錄)就不連線
            if (q.t - a.t > 3600_000L) continue
            line.color = if (q.hi) colorHi else colorNormal
            c.drawLine(x(a.t), y(a.v), x(q.t), y(q.v), line)
        }
        val f = SimpleDateFormat(if (longRange) "MM/dd HH:mm" else "HH:mm", Locale.getDefault())
        c.drawText(f.format(Date(t0)), l, h - 4f * d, txt)
        val e = f.format(Date(t1))
        c.drawText(e, r - txt.measureText(e), h - 4f * d, txt)
    }
}
