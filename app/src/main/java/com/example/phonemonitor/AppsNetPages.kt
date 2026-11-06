package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ 共用
fun installedApps(act: Activity): List<Pair<String, String>> {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return act.packageManager.queryIntentActivities(i, 0)
        .map { it.activityInfo.packageName }.distinct().filter { it != act.packageName }
        .map { Pair(it, Apps.label(act, it)) }.sortedBy { it.second.lowercase() }
}

fun pickApps(act: Activity, title: String, current: Set<String>, onDone: (Set<String>) -> Unit) {
    Thread {
        val list = installedApps(act)
        act.runOnUiThread {
            val labels = list.map { it.second }.toTypedArray()
            val checked = BooleanArray(list.size) { list[it].first in current }
            AlertDialog.Builder(act).setTitle(title)
                .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setPositiveButton("完成") { _, _ ->
                    val res = HashSet<String>(current.filter { c -> list.none { it.first == c } })
                    list.forEachIndexed { i, p -> if (checked[i]) res.add(p.first) }
                    onDone(res)
                }
                .setNegativeButton("取消", null).show()
        }
    }.start()
}

fun toggleNetBlock(act: Activity, pkg: String) {
    Prefs.toggleNetBlocked(act, pkg)
    if (Prefs.netBlocked(act).isEmpty()) NetBlock.stop(act)
    else if (!Prefs.netBlockOn(act)) NetBlock.enable(act) else NetBlock.refresh(act)
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

// =====================================================================
// 應用與專注
// =====================================================================
class AppsPage(a: Activity) : BasePage(a, "應用與專注") {
    private var sub = 0
    private var ri = 0
    private var expand = false
    private val subChips = Chips(a, listOf("使用統計", "封鎖專注", "短影音防護"), 0) { sub = it; expand = false; load() }
    private val periodChips = Chips(a, listOf("今天", "昨天", "7 天", "30 天", "90 天"), 0) { ri = it; customDay = 0L; expand = false; load() }

    init {
        col.addView(subChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        when (sub) { 0 -> loadUsage(); 1 -> { body.removeAllViews(); renderBlock() }; else -> { body.removeAllViews(); renderFeatures() } }
    }

    private fun g(needed: Boolean = true, f: () -> Unit) = Lock.gate(ctx, needed, f)

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        v.forEachIndexed { i, x -> r.addView(x, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) marginStart = ctx.dp(6) }) }
        return r
    }

    private fun toggleRow(parent: LinearLayout, title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.tv(ctx, title, 14f, Theme.TEXT, true))
        left.addView(Ui.tv(ctx, desc, 11f, Theme.SUB))
        r.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val pill = Ui.tv(ctx, if (on) "開" else "關", 13f, if (on) Ui.DARK_ON_ACCENT else Theme.SUB, true).apply {
            setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), ctx.dp(8))
            background = Theme.rounded(if (on) Theme.GREEN else Theme.CARD2, ctx.dp(16).toFloat())
            setOnClickListener { onToggle() }
        }
        r.addView(pill)
        parent.addView(r, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
    }

    // ------------------------------------------------ 使用統計
    private var customDay = 0L

    private class UView(
        val screenMs: Long, val unlocks: Int, val apps: List<AppStat>, val barTitle: String,
        val labels: List<String>, val values: List<Float>, val note: String, val days: Int, val single: Boolean
    )

    private fun periodRow() {
        body.addView(periodChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.btn(ctx, if (customDay > 0) "📅 " + fmtTime(customDay, "yyyy/MM/dd") + "(點選更換)" else "📅 選擇特定日期", Theme.CARD2, Theme.TEXT) { pickDay() },
            Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))
    }

    private fun pickDay() {
        val c = Calendar.getInstance(); if (customDay > 0) c.timeInMillis = customDay + 12 * 3_600_000L
        DatePickerDialog(ctx, { _, y, m, d ->
            val cc = Calendar.getInstance(); cc.set(y, m, d, 12, 0, 0)
            customDay = AppUsage.dayStart(cc.timeInMillis); expand = false; load()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    private fun loadUsage() {
        body.removeAllViews(); periodRow()
        if (!UsageAccess.granted(ctx)) { body.addView(Ui.usageBanner(ctx)); return }
        body.addView(Ui.note(ctx, "讀取中…"))
        val idx = ri; val cd = customDay
        bg {
            UsageArchive.sync(ctx)
            val now = System.currentTimeMillis(); val today = AppUsage.dayStart(now); val db = Db.get(ctx)
            val v = if (cd > 0 || idx <= 1) singleDay(if (cd > 0) cd else if (idx == 0) today else today - AppUsage.DAY, now, db)
            else multiDay(if (idx == 2) 7 else if (idx == 3) 30 else 90, today, db)
            ui.post { renderUsage(v) }
        }
    }

    private fun singleDay(day: Long, now: Long, db: Db): UView {
        val live = try { AppUsage.compute(ctx, day, minOf(day + AppUsage.DAY, now)) } catch (e: Throwable) { null }
        if (live != null && (live.apps.isNotEmpty() || live.screenOnMs > 0)) {
            return UView(live.screenOnMs, live.unlocks, live.apps, "各時段螢幕使用", (0 until 24).map { "$it" }, live.hourMs.map { it / 60_000f }, "", 1, true)
        }
        val apps = db.usageRange(day, day + AppUsage.DAY)
        val sd = db.screenDays(day, day + AppUsage.DAY).firstOrNull()
        val note = if (apps.isEmpty() && sd == null) "這一天沒有資料。系統只保留約 7 天,更早的只有本 App 封存過的才看得到。" else "這一天只剩統計摘要(系統已清除詳細事件,無法顯示各時段)。"
        return UView(sd?.second ?: 0L, sd?.third ?: 0, apps, "", emptyList(), emptyList(), note, 1, true)
    }

    private fun multiDay(n: Int, today: Long, db: Db): UView {
        val from = today - (n - 1) * AppUsage.DAY
        val apps = db.usageRange(from, today + AppUsage.DAY)
        val sd = db.screenDays(from, today + AppUsage.DAY).associateBy { it.first }
        val sdf = SimpleDateFormat("M/d", Locale.getDefault())
        val labels = (0 until n).map { sdf.format(Date(from + it * AppUsage.DAY + 12 * 3_600_000L)) }
        val values = (0 until n).map { (sd[from + it * AppUsage.DAY]?.second ?: 0L) / 60_000f }
        val note = "已累積 ${sd.size} / $n 天的資料。系統只保留約 7 天,更早的由本 App 持續封存,之後會越來越完整。"
        return UView(sd.values.sumOf { it.second }, sd.values.sumOf { it.third }, apps, "每日螢幕時間", labels, values, note, maxOf(1, sd.size), false)
    }

    private fun renderUsage(v: UView) {
        body.removeAllViews(); periodRow()
        val isToday = customDay == 0L && ri == 0
        val total = Prefs.totalLimit(ctx)
        val screenMin = v.screenMs / 60_000L

        val hero = Ui.card(ctx, 24)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val l = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        l.addView(Ui.tv(ctx, if (v.single) "螢幕使用時間" else "螢幕使用時間(累計)", 12f, Theme.SUB))
        l.addView(Ui.tv(ctx, fmtDur(v.screenMs), 30f, Theme.TEXT, true))
        val rt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.END }
        rt.addView(Ui.tv(ctx, "解鎖次數", 12f, Theme.SUB))
        rt.addView(Ui.tv(ctx, "${v.unlocks}", 30f, Theme.BLUE, true))
        top.addView(l, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)); top.addView(rt)
        hero.addView(top)
        if (isToday && total > 0) {
            val p = screenMin.toFloat() / total
            hero.addView(MeterView(ctx).apply { value = p.coerceIn(0f, 1f); color = Theme.usage(p) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 12, 4))
            hero.addView(Ui.tv(ctx, "每日上限 $total 分鐘 · 已用 ${(p * 100).toInt()}%", 11f, Theme.SUB))
        }
        val sub = if (v.single) "平均每次解鎖 ${if (v.unlocks > 0) fmtDur(v.screenMs / v.unlocks) else "—"} · 使用過 ${v.apps.size} 個 App"
        else "日均 ${fmtDur(v.screenMs / v.days)} · 日均解鎖 ${v.unlocks / v.days} 次 · 使用過 ${v.apps.size} 個 App"
        hero.addView(Ui.tv(ctx, sub, 11f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        if (v.note.isNotEmpty()) body.addView(Ui.note(ctx, v.note, Theme.AMBER))

        if (v.labels.isNotEmpty()) {
            val bar = BarChartView(ctx).apply {
                title = v.barTitle; color = Theme.BLUE; emptyText = "尚無螢幕使用紀錄"; fmt = { minFmt(it) }; maxFmt = { minFmt(it) }; set(v.labels, v.values)
            }
            body.addView(Ui.chartCard(ctx, bar, 180), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))
        }

        body.addView(Ui.section(ctx, "App 使用排行 · 點選可封鎖 / 退出 / 設定上限"))
        val blocked = Prefs.blocked(ctx); val focus = Prefs.focusApps(ctx); val limits = Prefs.limits(ctx)
        val apps = v.apps.filter { Apps.launchable(ctx, it.pkg) }.take(40)
        val shown = if (expand) apps else apps.take(8)
        val list = Ui.card(ctx)
        if (apps.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有足夠的使用紀錄。"))
        val maxMs = (apps.firstOrNull()?.fgMs ?: 1L).toFloat()
        for (s in shown) {
            val lim = limits[s.pkg] ?: 0
            val over = isToday && lim > 0 && s.fgMs / 60_000L >= lim
            val badge = when { s.pkg in blocked -> "封鎖中"; over -> "超過上限"; s.pkg in focus -> "專注"; lim > 0 -> "上限 $lim 分"; else -> "" }
            val subText = if (v.single) "${s.launches} 次開啟" + (if (s.last > 0) " · 最後 " + fmtTime(s.last, "HH:mm") else "")
            else "${s.launches} 次開啟 · 日均 ${fmtDur(s.fgMs / v.days)}"
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg), subText, fmtDur(s.fgMs), s.fgMs / maxMs, if (over) Theme.RED else Theme.BLUE, badge,
                if (s.pkg in blocked || over) Theme.RED else Theme.AMBER)
            row.setOnClickListener { appMenu(s.pkg) }
            list.addView(row)
        }
        if (apps.size > 8) list.addView(Ui.btn(ctx, if (expand) "收合" else "顯示全部 ${apps.size} 個", Theme.CARD2, Theme.TEXT) { expand = !expand; renderUsage(v) },
            Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(list)
    }

    private fun pickLimit(title: String, current: Int = 0, onPick: (Int) -> Unit) =
        Ui.pickMinutes(ctx, title, current, "不限制", onPick = onPick)

    private fun appMenu(pkg: String) {
        val label = Apps.label(ctx, pkg)
        val blocked = pkg in Prefs.blocked(ctx); val focus = pkg in Prefs.focusApps(ctx); val netB = pkg in Prefs.netBlocked(ctx)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("退出此 App(回桌面並結束背景)" to { Guard.exit(ctx, pkg) })
        items.add((if (blocked) "解除永久封鎖" else "永久封鎖此 App") to { g(blocked) { Prefs.toggleBlocked(ctx, pkg); checkService(); load() } })
        items.add((if (focus) "移出專注清單" else "加入專注清單") to { g(focus) { Prefs.toggleFocus(ctx, pkg); checkService(); load() } })
        items.add("設定每日使用上限" to { g((Prefs.limits(ctx)[pkg] ?: 0) > 0) { pickLimit("$label 每日上限", Prefs.limits(ctx)[pkg] ?: 0) { Prefs.setLimit(ctx, pkg, it); load() } } })
        items.add((if (netB) "解除網路封鎖" else "封鎖此 App 的網路(VPN)") to { g(netB) { toggleNetBlock(ctx, pkg); load() } })
        items.add("App 資訊(強制停止 / 權限)" to { Apps.openInfo(ctx, pkg) })
        items.add("解除安裝" to { Apps.uninstall(ctx, pkg) })
        AlertDialog.Builder(ctx).setTitle(label).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun checkService() {
        if (!GuardService.enabled(ctx)) Toast.makeText(ctx, "已儲存。但「專注防護」服務尚未啟用,規則要等啟用後才會生效(到「封鎖專注」分頁開啟)", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------ 封鎖專注
    private fun serviceCard(): LinearLayout {
        val on = GuardService.enabled(ctx)
        val c = Ui.card(ctx)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.tv(ctx, "專注防護服務", 16f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.tv(ctx, if (on) "● 已啟用" else "○ 未啟用", 12f, if (on) Theme.GREEN else Theme.RED, true))
        c.addView(head)
        if (on) c.addView(Ui.tv(ctx, "封鎖、專注模式與短影音防護都已可使用。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 0))
        else {
            c.addView(Ui.tv(ctx,
                "封鎖 App、專注模式、短影音防護都靠 Android「無障礙服務」偵測前景畫面。它只在本機判斷,不記錄也不傳送內容。\n\n" +
                    "① 按「開啟無障礙設定」→ 已下載/已安裝的服務 →「手機監測 · 專注防護」→ 開啟\n" +
                    "② 若開關是灰色(Android 13+ 對側載 App 的限制):先按「App 資訊」→ 右上角「⋮」→「允許受限制的設定」,再回去開啟", 12f, Theme.SUB),
                Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 10))
            c.addView(row(
                Ui.btn(ctx, "開啟無障礙設定", Theme.BLUE, Ui.DARK_ON_ACCENT) { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} },
                Ui.btn(ctx, "App 資訊", Theme.CARD2, Theme.TEXT) { Apps.openInfo(ctx, ctx.packageName) }))
        }
        return c
    }

    private fun renderBlock() {
        body.addView(serviceCard(), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 專注模式
        val fc = Ui.card(ctx)
        fc.addView(Ui.tv(ctx, "專注模式", 16f, Theme.TEXT, true))
        val left = Prefs.focusUntil(ctx) - System.currentTimeMillis()
        if (left > 0) {
            fc.addView(Ui.tv(ctx, "進行中 · 剩餘 ${fmtDur(left)}", 13f, Theme.GREEN, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))
            fc.addView(Ui.btn(ctx, "結束專注", Theme.CARD2, Theme.RED) { g { Prefs.setFocusUntil(ctx, 0); load() } })
        } else {
            fc.addView(Ui.tv(ctx, "開始後,「專注清單」內的 App 會被擋下,直到時間結束。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
            fun start(min: Int) = { checkService(); Prefs.setFocusUntil(ctx, System.currentTimeMillis() + min * 60_000L); load() }
            fc.addView(row(
                Ui.btn(ctx, "25 分鐘", Theme.GREEN, Ui.DARK_ON_ACCENT, start(25)),
                Ui.btn(ctx, "1 小時", Theme.GREEN, Ui.DARK_ON_ACCENT, start(60)),
                Ui.btn(ctx, "2 小時", Theme.GREEN, Ui.DARK_ON_ACCENT, start(120))))
            fc.addView(Ui.btn(ctx, "自訂專注時間…", Theme.CARD2, Theme.TEXT) {
                Ui.pickMinutes(ctx, "專注多久?", 45, null, listOf(10, 15, 25, 45, 60, 90, 120, 180)) { start(it)() }
            }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        }
        val fl = Prefs.focusApps(ctx)
        fc.addView(Ui.tv(ctx, "專注清單:${fl.size} 個 App" + if (fl.isNotEmpty()) "(" + fl.take(4).joinToString("、") { Apps.label(ctx, it) } + if (fl.size > 4) "…)" else ")" else "", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 12, 6))
        fc.addView(Ui.btn(ctx, "選擇專注清單的 App", Theme.CARD2, Theme.TEXT) { g(Prefs.focusApps(ctx).isNotEmpty()) { pickApps(ctx, "專注清單(分心 App)", Prefs.focusApps(ctx)) { Prefs.setFocusApps(ctx, it); load() } } })
        toggleRow(fc, "排程專注", if (Prefs.schedOn(ctx)) "每天 ${hm(Prefs.schedStart(ctx))} – ${hm(Prefs.schedEnd(ctx))}" else "例如睡前或上班時段自動封鎖", Prefs.schedOn(ctx)) {
            g(Prefs.schedOn(ctx)) { Prefs.setSchedOn(ctx, !Prefs.schedOn(ctx)); checkService(); load() }
        }
        fc.addView(Ui.btn(ctx, "設定排程時段", Theme.CARD2, Theme.TEXT) { g(Prefs.schedOn(ctx)) {
            TimePickerDialog(ctx, { _, h1, m1 ->
                TimePickerDialog(ctx, { _, h2, m2 -> Prefs.setSched(ctx, h1 * 60 + m1, h2 * 60 + m2); load() },
                    Prefs.schedEnd(ctx) / 60, Prefs.schedEnd(ctx) % 60, true).apply { setTitle("結束時間") }.show()
            }, Prefs.schedStart(ctx) / 60, Prefs.schedStart(ctx) % 60, true).apply { setTitle("開始時間") }.show()
        } }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(fc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 永久封鎖
        val blocked = Prefs.blocked(ctx)
        val bc = Ui.card(ctx)
        bc.addView(Ui.tv(ctx, "永久封鎖清單(${blocked.size})", 16f, Theme.TEXT, true))
        if (blocked.isEmpty()) bc.addView(Ui.note(ctx, "尚未封鎖任何 App。"))
        blocked.forEach { pkg ->
            val r = AppRow(ctx)
            r.set(Apps.icon(ctx, pkg), Apps.label(ctx, pkg), "點選解除封鎖", "", null, Theme.RED, "封鎖中", Theme.RED)
            r.setOnClickListener { g { Prefs.toggleBlocked(ctx, pkg); load() } }
            bc.addView(r)
        }
        bc.addView(Ui.btn(ctx, "新增 / 調整封鎖 App", Theme.CARD2, Theme.TEXT) { g(Prefs.blocked(ctx).isNotEmpty()) { pickApps(ctx, "永久封鎖的 App", Prefs.blocked(ctx)) { Prefs.setBlocked(ctx, it); checkService(); load() } } }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        val tik = Surfaces.TIKTOK.filter { Apps.launchable(ctx, it) && it !in blocked }
        if (tik.isNotEmpty()) bc.addView(Ui.btn(ctx, "一鍵封鎖 TikTok(整個 App 都是短影音)", Theme.CARD2, Theme.AMBER) {
            Prefs.setBlocked(ctx, Prefs.blocked(ctx) + tik); checkService(); load()
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(bc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 上限與規則
        val lc = Ui.card(ctx)
        lc.addView(Ui.tv(ctx, "使用上限與規則", 16f, Theme.TEXT, true))
        if (!UsageAccess.granted(ctx)) lc.addView(Ui.note(ctx, "每日上限需要「使用情況存取權」(到「使用統計」分頁開啟)。", Theme.AMBER))
        val limits = Prefs.limits(ctx)
        if (limits.isEmpty()) lc.addView(Ui.note(ctx, "尚未設定個別上限。到「使用統計」點選 App 即可設定。"))
        limits.forEach { (pkg, min) ->
            val r = AppRow(ctx)
            r.set(Apps.icon(ctx, pkg), Apps.label(ctx, pkg), "每日上限(點選修改)", "$min 分", null)
            r.setOnClickListener { g { pickLimit("${Apps.label(ctx, pkg)} 每日上限", min) { Prefs.setLimit(ctx, pkg, it); load() } } }
            lc.addView(r)
        }
        lc.addView(Ui.btn(ctx, "每日螢幕總時間提醒:" + if (Prefs.totalLimit(ctx) > 0) "${Prefs.totalLimit(ctx)} 分鐘" else "未設定", Theme.CARD2, Theme.TEXT) {
            g(Prefs.totalLimit(ctx) > 0) { pickLimit("每日螢幕總時間(僅提醒)", Prefs.totalLimit(ctx)) { Prefs.setTotalLimit(ctx, it); load() } }
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        toggleRow(lc, "超過個別上限時直接封鎖", if (Prefs.limitBlock(ctx)) "超過後會擋下該 App" else "目前只發通知提醒", Prefs.limitBlock(ctx)) {
            g(Prefs.limitBlock(ctx)) { Prefs.setLimitBlock(ctx, !Prefs.limitBlock(ctx)); checkService(); load() }
        }
        toggleRow(lc, "嚴格模式", if (Prefs.strict(ctx)) "封鎖畫面不能延長時間" else "封鎖畫面可「再給我 5 分鐘」(需等 10 秒)", Prefs.strict(ctx)) {
            g(Prefs.strict(ctx)) { Prefs.setStrict(ctx, !Prefs.strict(ctx)); load() }
        }
        lc.addView(Ui.btn(ctx, "封鎖畫面可延長:${Prefs.snoozeMin(ctx)} 分鐘(點選修改)", Theme.CARD2, Theme.TEXT) {
            g { Ui.pickMinutes(ctx, "每次延長多久", Prefs.snoozeMin(ctx), null, listOf(1, 3, 5, 10, 15, 30)) { Prefs.setSnoozeMin(ctx, it); load() } }
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(lc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.note(ctx, "提醒:這是自我管理工具,到無障礙設定仍可手動關閉服務,無法防止你自己解除。「退出 App」是回桌面並結束背景程序;Android 不允許一般 App 直接強制停止別的 App。"))
    }

    // ------------------------------------------------ App 內區塊防護(Reels / 主頁 / 探索)
    private fun modeText(m: Int) = when { m < 0 -> "未封鎖"; m == 0 -> "完全封鎖"; else -> "額度 $m 分" }

    private fun surfaceDialog(s: Surface) {
        val mode = Prefs.feature(ctx, s.key)
        AlertDialog.Builder(ctx).setTitle("${s.app} · ${s.name}")
            .setItems(arrayOf("不封鎖", "完全封鎖", "每天額度(自訂時間)…")) { _, w ->
                g(mode >= 0) {
                    when (w) {
                        0 -> { Prefs.setFeature(ctx, s.key, -1); load() }
                        1 -> { Prefs.setFeature(ctx, s.key, 0); checkService(); load() }
                        else -> Ui.pickMinutes(ctx, "每天可使用多久?", if (mode > 0) mode else 0, null, listOf(5, 10, 15, 30, 45, 60, 90)) {
                            Prefs.setFeature(ctx, s.key, it); checkService(); load()
                        }
                    }
                }
            }.show()
    }

    private fun surfaceRow(s: Surface): View {
        val mode = Prefs.feature(ctx, s.key)
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, ctx.dp(10)) }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.tv(ctx, s.name, 14f, Theme.TEXT, true))
        val used = Prefs.featUsedMs(ctx, s.key)
        left.addView(Ui.tv(ctx, (if (mode > 0) "今日 ${fmtDur(used)} / $mode 分 · " else "") + "今日攔截 ${Prefs.featBlocks(ctx, s.key)} 次", 11f, Theme.SUB))
        if (mode > 0) left.addView(MeterView(ctx).apply { val p = used.toFloat() / (mode * 60_000f); value = p.coerceIn(0f, 1f); color = Theme.usage(p) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(4), 6, 0))
        r.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        r.addView(Ui.tv(ctx, modeText(mode), 12f, if (mode < 0) Theme.SUB else Ui.DARK_ON_ACCENT, true).apply {
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = Theme.rounded(if (mode < 0) Theme.CARD2 else Theme.GREEN, ctx.dp(14).toFloat())
        })
        r.setOnClickListener { surfaceDialog(s) }
        return r
    }

    private fun renderFeatures() {
        body.addView(serviceCard(), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        val intro = Ui.card(ctx)
        intro.addView(Ui.tv(ctx, "App 內分區封鎖", 15f, Theme.TEXT, true))
        intro.addView(Ui.tv(ctx, "Instagram / YouTube / Facebook 內的區塊可「分開鎖」:Reels(Shorts)、主頁動態、探索各自設定,例如只擋 Reels 但保留主頁,或主頁也鎖起來。" +
            "偵測到你進入被鎖的區塊,會自動帶你去別的分頁;可選「完全封鎖」或「每日額度」,額度用完才擋。\n\n" +
            "你一直點進去,每一次都會擋,不會因為次數多就自動放行。若擋不掉(動作沒生效),會自動加強:先點分頁 → 返回鍵 → 回桌面。\n\n" +
            "注意:這靠辨識畫面元件,App 大改版後可能暫時失效,可用下方「擷取畫面元件」協助調整規則。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0))
        body.addView(intro, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        for (app in Surfaces.apps) {
            val list = Surfaces.all.filter { it.app == app }
            val installed = list.first().pkgs.any { Apps.launchable(ctx, it) }
            val c = Ui.card(ctx)
            c.addView(Ui.tv(ctx, app + if (installed) "" else "(未安裝)", 16f, Theme.TEXT, true))
            list.forEach { c.addView(surfaceRow(it)) }
            body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        }

        val tk = Ui.card(ctx)
        tk.addView(Ui.tv(ctx, "TikTok / 抖音", 16f, Theme.TEXT, true))
        tk.addView(Ui.tv(ctx, "整個 App 就是短影音,請直接用「永久封鎖」或「專注清單」。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
        val tik = Surfaces.TIKTOK.filter { Apps.launchable(ctx, it) }
        if (tik.isEmpty()) tk.addView(Ui.note(ctx, "未偵測到已安裝。"))
        else tk.addView(Ui.btn(ctx, if (tik.all { it in Prefs.blocked(ctx) }) "已封鎖(點選解除)" else "加入永久封鎖", Theme.CARD2, Theme.AMBER) { g(tik.all { it in Prefs.blocked(ctx) }) {
            val cur = Prefs.blocked(ctx)
            Prefs.setBlocked(ctx, if (tik.all { it in cur }) cur - tik.toSet() else cur + tik); checkService(); load()
        } })
        body.addView(tk, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val dg = Ui.card(ctx)
        dg.addView(Ui.tv(ctx, "偵測診斷", 15f, Theme.TEXT, true))
        dg.addView(Ui.tv(ctx, "最近偵測:${Guard.lastDetect}", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
        dg.addView(Ui.btn(ctx, "擷取畫面元件(按下後 6 秒內切到目標畫面)", Theme.CARD2, Theme.TEXT) {
            val svc = GuardService.instance
            if (svc == null) Toast.makeText(ctx, "請先啟用專注防護服務", Toast.LENGTH_SHORT).show()
            else { svc.dumpLater(); Toast.makeText(ctx, "請在 6 秒內切換到目標 App 的畫面", Toast.LENGTH_LONG).show() }
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        dg.addView(Ui.btn(ctx, "查看擷取結果", Theme.CARD2, Theme.TEXT) {
            AlertDialog.Builder(ctx).setTitle("畫面元件").setMessage(if (Guard.lastDump.isEmpty()) "尚未擷取" else Guard.lastDump).setPositiveButton("關閉", null).show()
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        dg.addView(Ui.btn(ctx, "自訂偵測元件 ID(進階)", Theme.CARD2, Theme.TEXT) {
            val names = Surfaces.all.map { "${it.app} ${it.name}" }.toTypedArray()
            AlertDialog.Builder(ctx).setTitle("要設定哪一項?").setItems(names) { _, i ->
                val f = Surfaces.all[i]
                Ui.askText(ctx, "${f.app} ${f.name} 元件 ID", "多個用逗號分隔", Prefs.customIds(ctx, f.key)) { Prefs.setCustomIds(ctx, f.key, it); load() }
            }.show()
        })
        body.addView(dg)
    }
}
