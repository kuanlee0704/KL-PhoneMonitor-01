package com.example.phonemonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Theme {
    val BG = Color.parseColor("#0E1116")
    val CARD = Color.parseColor("#181D25")
    val CARD2 = Color.parseColor("#232A35")
    val TRACK = Color.parseColor("#2A323D")
    val TEXT = Color.parseColor("#E8EAED")
    val SUB = Color.parseColor("#9AA4B2")
    val GREEN = Color.parseColor("#34D399")
    val AMBER = Color.parseColor("#FBBF24")
    val RED = Color.parseColor("#F87171")
    val BLUE = Color.parseColor("#60A5FA")
    val PURPLE = Color.parseColor("#A78BFA")
    val CYAN = Color.parseColor("#22D3EE")
    val PINK = Color.parseColor("#F472B6")

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
    }

    /** 高=好(健康度、剩餘量) */
    fun good(pct: Float) = when { pct >= 0.6f -> GREEN; pct >= 0.3f -> AMBER; else -> RED }
    /** 高=壞(使用率) */
    fun usage(pct: Float) = when { pct >= 0.85f -> RED; pct >= 0.7f -> AMBER; else -> GREEN }
}

/** 圓環進度(電量) */
class RingView(ctx: Context) : View(ctx) {
    private var progress = 0f
    private var ringColor = Theme.GREEN
    private var big = ""
    private var small = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Theme.TRACK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val tSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textAlign = Paint.Align.CENTER }

    fun set(pct: Float, bigText: String, smallText: String, color: Int) {
        progress = pct.coerceIn(0f, 100f); big = bigText; small = smallText; ringColor = color; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val sw = s * 0.085f
        track.strokeWidth = sw; arc.strokeWidth = sw; arc.color = ringColor
        val cx = width / 2f; val cy = height / 2f; val r = s / 2f - sw
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 0f, 360f, false, track)
        if (progress > 0f) c.drawArc(rect, -90f, 360f * progress / 100f, false, arc)
        tBig.textSize = s * 0.24f; tSmall.textSize = s * 0.075f
        c.drawText(big, cx, cy + s * 0.07f, tBig)
        c.drawText(small, cx, cy + s * 0.2f, tSmall)
    }
}

/** 橫向比例條 */
class MeterView(ctx: Context) : View(ctx) {
    var value = 0f
    var color = Theme.GREEN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = Theme.TRACK; c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = color; c.drawRoundRect(0f, 0f, maxOf(h, w * value.coerceIn(0f, 1f)), h, h / 2, h / 2, p)
    }
}

/** 指標卡:標題 / 大數字 / 說明 / 比例條 */
class MetricCard(ctx: Context, titleText: String, withMeter: Boolean = true) : LinearLayout(ctx) {
    private val vTitle = TextView(ctx)
    private val vValue = TextView(ctx)
    private val vSub = TextView(ctx)
    private val meter: MeterView? = if (withMeter) MeterView(ctx) else null

    init {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, ctx.dp(18).toFloat())
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        vTitle.text = titleText; vTitle.textSize = 12f; vTitle.setTextColor(Theme.SUB)
        vValue.textSize = 20f; vValue.setTextColor(Theme.TEXT); vValue.setTypeface(null, Typeface.BOLD); vValue.maxLines = 1
        vSub.textSize = 11f; vSub.setTextColor(Theme.SUB)
        addView(vTitle); addView(vValue); addView(vSub)
        meter?.let { addView(it, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(6)).apply { topMargin = ctx.dp(8) }) }
    }

    fun set(value: String, sub: String, progress: Float?, color: Int = Theme.GREEN) {
        vValue.text = value; vSub.text = sub
        meter?.let {
            if (progress == null) it.visibility = View.INVISIBLE
            else { it.visibility = View.VISIBLE; it.value = progress; it.color = color; it.invalidate() }
        }
    }
}

/** 長條圖(用量) */
class BarChartView(ctx: Context) : View(ctx) {
    var title = ""
    var color = Theme.BLUE
    var emptyText = "此區間沒有資料"
    var fmt: (Float) -> String = { "%.0f".format(it) }
    var maxFmt: (Float) -> String = { "%.0f%%".format(it) }
    private var labels: List<String> = emptyList()
    private var values: List<Float> = emptyList()
    private val d = ctx.resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textSize = 10f * d; textAlign = Paint.Align.CENTER }
    private val ttl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = 13f * d; isFakeBoldText = true }
    private val mx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textSize = 11f * d; textAlign = Paint.Align.RIGHT }

    fun set(l: List<String>, v: List<Float>) { labels = l; values = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = 8f * d; val r = w - 8f * d; val t = 34f * d; val b = h - 20f * d
        c.drawText(title, 6f * d, 18f * d, ttl)
        val max = values.maxOrNull() ?: 0f
        if (values.isEmpty() || max <= 0f) {
            txt.textAlign = Paint.Align.LEFT
            c.drawText(emptyText, l, (t + b) / 2, txt); txt.textAlign = Paint.Align.CENTER; return
        }
        c.drawText("最高 ${maxFmt(max)}", w - 6f * d, 18f * d, mx)
        val n = values.size; val slot = (r - l) / n; val bw = slot * 0.62f
        val step = Math.ceil(n / 7.0).toInt()
        for (i in 0 until n) {
            val cx = l + slot * (i + 0.5f)
            val bh = (b - t) * values[i] / max
            bar.color = if (values[i] == max) Theme.AMBER else color
            if (values[i] > 0f) c.drawRoundRect(RectF(cx - bw / 2, b - bh, cx + bw / 2, b), 4f * d, 4f * d, bar)
            else { bar.color = Theme.TRACK; c.drawRect(cx - bw / 2, b - 2f * d, cx + bw / 2, b, bar) }
            if (n <= 12 && values[i] > 0f) c.drawText(fmt(values[i]), cx, b - bh - 3f * d, txt)
            if (i % step == 0) c.drawText(labels[i], cx, h - 4f * d, txt)
        }
    }
}

class Series(val name: String, val color: Int, val xs: FloatArray, val ys: FloatArray)

/** 通用折線圖(X 為數值,例如「小時」「電量%」):支援多條線、圖例、觸控查看 */
class LineChartView(ctx: Context) : View(ctx) {
    var title = ""
    var xUnit = ""
    var yUnit = ""
    var yMin: Float? = null
    var yMax: Float? = null
    var xFmt: (Float) -> String = { "%.0f".format(it) }
    var yFmt: (Float) -> String = { if (Math.abs(it) >= 100f) "%.0f".format(it) else "%.1f".format(it) }
    var emptyText = "資料不足"
    private var data: List<Series> = emptyList()
    private var touchX = -1f
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val grid = Paint().apply { color = Theme.TRACK; strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textSize = 10f * d }
    private val ttl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = 13f * d; isFakeBoldText = true }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(s: List<Series>) { data = s.filter { it.xs.size >= 2 }; touchX = -1f; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = 40f * d; val r = w - 10f * d; val t = 34f * d; val b = h - 20f * d
        c.drawText(title, 6f * d, 18f * d, ttl)
        if (data.isEmpty()) { c.drawText(emptyText, l, (t + b) / 2, txt); return }
        val x0 = data.minOf { it.xs.minOrNull() ?: 0f }; var x1 = data.maxOf { it.xs.maxOrNull() ?: 1f }
        if (x1 - x0 < 1e-3f) x1 = x0 + 1f
        var lo = yMin ?: data.minOf { it.ys.minOrNull() ?: 0f }; var hi = yMax ?: data.maxOf { it.ys.maxOrNull() ?: 1f }
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        if (yMin == null) lo -= (hi - lo) * 0.08f
        if (yMax == null) hi += (hi - lo) * 0.08f
        fun px(x: Float) = l + (r - l) * (x - x0) / (x1 - x0)
        fun py(y: Float) = b - (b - t) * (y - lo) / (hi - lo)
        for (k in 0..2) {
            val yy = t + (b - t) * k / 2f
            c.drawLine(l, yy, r, yy, grid)
            c.drawText(yFmt(hi - (hi - lo) * k / 2f), 3f * d, yy + 4f * d, txt)
        }
        c.drawText(xFmt(x0) + xUnit, l, h - 4f * d, txt)
        val e = xFmt(x1) + xUnit; c.drawText(e, r - txt.measureText(e), h - 4f * d, txt)
        for (s in data) {
            line.color = s.color
            for (i in 1 until s.xs.size) c.drawLine(px(s.xs[i - 1]), py(s.ys[i - 1]), px(s.xs[i]), py(s.ys[i]), line)
        }
        if (data.size > 1) {
            var lx = r
            for (s in data.reversed()) {
                val wd = txt.measureText(s.name) + 16f * d
                lx -= wd; dot.color = s.color
                c.drawCircle(lx + 4f * d, 14f * d, 3.5f * d, dot); c.drawText(s.name, lx + 11f * d, 18f * d, txt)
            }
        }
        if (touchX in l..r) {
            val xv = x0 + (touchX - l) / (r - l) * (x1 - x0)
            val sb = StringBuilder(xFmt(xv) + xUnit + "  ")
            for (s in data) {
                var bi = 0; var bd = Float.MAX_VALUE
                for (i in s.xs.indices) { val dd = Math.abs(s.xs[i] - xv); if (dd < bd) { bd = dd; bi = i } }
                dot.color = s.color; c.drawCircle(px(s.xs[bi]), py(s.ys[bi]), 4f * d, dot)
                sb.append(if (data.size > 1) "${s.name}${yFmt(s.ys[bi])}$yUnit " else "${yFmt(s.ys[bi])}$yUnit")
            }
            grid.color = Theme.SUB; c.drawLine(touchX, t, touchX, b, grid); grid.color = Theme.TRACK
            c.drawText(sb.toString(), 6f * d, 30f * d, ttl.apply { textSize = 11f * d })
            ttl.textSize = 13f * d
        }
    }

    private var downX = 0f; private var downY = 0f
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; touchX = e.x; invalidate() }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (Math.abs(e.x - downX) > Math.abs(e.y - downY) * 1.2f && Math.abs(e.x - downX) > 8f) parent.requestDisallowInterceptTouchEvent(true)
                touchX = e.x; invalidate()
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

/** 熱圖:列 = 日期,欄 = 0~23 時;可疊加「充電中」標記 */
class HeatmapView(ctx: Context) : View(ctx) {
    var title = ""
    var color = Theme.AMBER
    var unit = "%"
    private var rowLabels: List<String> = emptyList()
    private var values: Array<FloatArray> = emptyArray()
    private var marks: Array<BooleanArray>? = null
    private var selected = ""
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.SUB; textSize = 9f * d }
    private val ttl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = 13f * d; isFakeBoldText = true }
    private var cell = 0f; private var left = 0f; private var top = 0f

    fun set(labels: List<String>, v: Array<FloatArray>, m: Array<BooleanArray>? = null) {
        rowLabels = labels; values = v; marks = m; selected = ""; invalidate()
    }

    fun neededHeight(): Int = ((rowLabels.size * 14f + 56f) * d).toInt()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        c.drawText(title, 6f * d, 18f * d, ttl)
        if (selected.isNotEmpty()) { val tw = txt.measureText(selected); c.drawText(selected, w - tw - 6f * d, 18f * d, txt) }
        if (values.isEmpty()) return
        left = 40f * d; top = 28f * d
        cell = (w - left - 6f * d) / 24f
        val rh = 14f * d
        val max = values.maxOf { it.maxOrNull() ?: 0f }.coerceAtLeast(0.001f)
        for (r in values.indices) {
            val y = top + r * rh
            c.drawText(rowLabels[r], 4f * d, y + rh * 0.75f, txt)
            for (h in 0 until 24) {
                val v = values[r][h]
                val a = (v / max).coerceIn(0f, 1f)
                p.color = if (v <= 0f) Theme.TRACK else (color and 0x00FFFFFF) or ((60 + (195 * a).toInt()) shl 24)
                c.drawRoundRect(left + h * cell + 1f, y + 1f, left + (h + 1) * cell - 1f, y + rh - 1f, 2f * d, 2f * d, p)
                if (marks?.get(r)?.get(h) == true) { p.color = Theme.CYAN; c.drawRect(left + h * cell + 1f, y + rh - 3f * d, left + (h + 1) * cell - 1f, y + rh - 1f, p) }
            }
        }
        for (h in 0 until 24 step 3) c.drawText("$h", left + h * cell, top + values.size * rh + 11f * d, txt)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            val rh = 14f * d
            val r = ((e.y - top) / rh).toInt(); val h = ((e.x - left) / cell).toInt()
            if (r in values.indices && h in 0..23) {
                selected = "${rowLabels[r]} ${h}時  ${"%.1f".format(values[r][h])}$unit" + if (marks?.get(r)?.get(h) == true) " · 充電中" else ""
                invalidate()
            }
        }
        return true
    }
}
