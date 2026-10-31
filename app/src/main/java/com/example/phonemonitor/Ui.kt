package com.example.phonemonitor

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    val DARK_ON_ACCENT = Color.parseColor("#06182B")

    fun tv(ctx: Context, t: String, sp: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun lp(ctx: Context, w: Int, h: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = ctx.dp(top); bottomMargin = ctx.dp(bottom) }

    fun card(ctx: Context, radius: Int = 20) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, ctx.dp(radius).toFloat())
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
    }

    fun btn(ctx: Context, text: String, bg: Int, fg: Int = Color.WHITE, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD); background = Theme.rounded(bg, ctx.dp(14).toFloat())
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12)); setOnClickListener { onClick() }
    }

    fun section(ctx: Context, t: String) = tv(ctx, t, 13f, Theme.SUB, true).apply {
        setPadding(ctx.dp(4), ctx.dp(14), 0, ctx.dp(6))
    }

    fun note(ctx: Context, t: String, color: Int = Theme.SUB) = tv(ctx, t, 12f, color).apply {
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(8))
    }

    fun page(ctx: Context): Pair<ScrollView, LinearLayout> {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(28))
        }
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(inner) }
        return Pair(sv, inner)
    }

    fun grid(ctx: Context, parent: LinearLayout, vararg cards: View) {
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val p = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    if (j == 0) marginEnd = ctx.dp(4) else marginStart = ctx.dp(4)
                }
                row.addView(cards.getOrNull(i + j) ?: View(ctx), p)
            }
            parent.addView(row, lp(ctx, MATCH, WRAP, 0, 8))
            i += 2
        }
    }

    fun chartCard(ctx: Context, v: View, h: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, ctx.dp(18).toFloat())
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(6))
        addView(v, LinearLayout.LayoutParams(MATCH, ctx.dp(h)))
    }

    /** 需要「使用情況存取權」時顯示的引導卡 */
    fun usageBanner(act: Activity): View {
        val c = card(act)
        c.addView(tv(act, "需要「使用情況存取權」", 16f, Theme.TEXT, true))
        c.addView(
            tv(act, "這是 Android 的特殊權限,讓 App 能讀取「各 App 使用時間、網路用量」等系統統計。" +
                "資料只在本機計算,本 App 沒有網路權限,不會外傳。\n\n步驟:按下方按鈕 → 找到「手機監測」→ 開啟「允許存取使用情況」→ 返回。", 12f, Theme.SUB),
            lp(act, MATCH, WRAP, 6, 12)
        )
        c.addView(btn(act, "前往開啟權限", Theme.BLUE, DARK_ON_ACCENT) { UsageAccess.open(act) })
        return c
    }
}

/** 分段選擇器 */
class Chips(private val ctx: Context, names: List<String>, var sel: Int = 0, private val onSelect: (Int) -> Unit) {
    val view = LinearLayout(ctx)
    private val items: List<TextView>

    init {
        view.orientation = LinearLayout.HORIZONTAL
        view.background = Theme.rounded(Theme.CARD, ctx.dp(14).toFloat())
        view.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        items = names.mapIndexed { i, n ->
            Ui.tv(ctx, n, 13f, Theme.SUB, true).apply {
                gravity = Gravity.CENTER; setPadding(0, ctx.dp(9), 0, ctx.dp(9))
                setOnClickListener { sel = i; style(); onSelect(i) }
            }
        }
        items.forEach { view.addView(it, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)) }
        style()
    }

    private fun style() {
        items.forEachIndexed { i, t ->
            t.setTextColor(if (i == sel) Ui.DARK_ON_ACCENT else Theme.SUB)
            t.background = if (i == sel) Theme.rounded(Theme.BLUE, ctx.dp(10).toFloat()) else null
        }
    }
}

/** 列表列:圖示 / 名稱 / 徽章 / 說明 / 右側數值 / 比例條 */
class AppRow(private val c: Context) : LinearLayout(c) {
    private val icon = ImageView(c)
    private val name = TextView(c)
    private val badge = TextView(c)
    private val sub = TextView(c)
    private val value = TextView(c)
    private val meter = MeterView(c)

    init {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, c.dp(8), 0, c.dp(8))
        addView(icon, LinearLayout.LayoutParams(c.dp(40), c.dp(40)).apply { marginEnd = c.dp(12) })

        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        name.textSize = 14f; name.setTextColor(Theme.TEXT); name.setTypeface(null, Typeface.BOLD)
        name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
        badge.textSize = 10f; badge.setTypeface(null, Typeface.BOLD); badge.visibility = View.GONE
        badge.setPadding(c.dp(6), c.dp(1), c.dp(6), c.dp(1))
        nameRow.addView(name, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP, 0f))
        nameRow.addView(badge, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = c.dp(6) })
        sub.textSize = 11f; sub.setTextColor(Theme.SUB)
        mid.addView(nameRow); mid.addView(sub)
        mid.addView(meter, LinearLayout.LayoutParams(Ui.MATCH, c.dp(4)).apply { topMargin = c.dp(6) })
        addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        value.textSize = 14f; value.setTextColor(Theme.TEXT); value.setTypeface(null, Typeface.BOLD)
        addView(value, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = c.dp(10) })
    }

    fun set(
        ic: Drawable?, n: String, s: String, v: String, progress: Float?, color: Int = Theme.BLUE,
        badgeText: String = "", badgeColor: Int = Theme.AMBER
    ) {
        icon.setImageDrawable(ic); name.text = n; sub.text = s; value.text = v
        if (progress == null) meter.visibility = View.GONE
        else { meter.visibility = View.VISIBLE; meter.value = progress; meter.color = color; meter.invalidate() }
        if (badgeText.isEmpty()) badge.visibility = View.GONE
        else {
            badge.visibility = View.VISIBLE; badge.text = badgeText
            badge.setTextColor(Ui.DARK_ON_ACCENT); badge.background = Theme.rounded(badgeColor, c.dp(8).toFloat())
        }
    }
}

/** 鍵值明細卡 */
class KvCard(private val c: Context, title: String) : LinearLayout(c) {
    private val body = LinearLayout(c)

    init {
        orientation = LinearLayout.VERTICAL
        background = Theme.rounded(Theme.CARD, c.dp(20).toFloat())
        setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(12))
        addView(Ui.tv(c, title, 15f, Theme.TEXT, true))
        body.orientation = LinearLayout.VERTICAL
        addView(body, Ui.lp(c, Ui.MATCH, Ui.WRAP, 6, 0))
    }

    fun set(rows: List<Pair<String, String>>) {
        body.removeAllViews()
        for ((k, v) in rows) {
            val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, c.dp(4), 0, c.dp(4)) }
            r.addView(Ui.tv(c, k, 12f, Theme.SUB), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.tv(c, v, 12f, Theme.TEXT).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, Ui.WRAP, 1.5f))
            body.addView(r)
        }
    }
}
