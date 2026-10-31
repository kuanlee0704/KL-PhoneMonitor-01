package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
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
    private val periodChips = Chips(a, listOf("今天", "近 7 天"), 0) { ri = it; expand = false; load() }

    init {
        col.addView(subChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    override fun load() {
        when (sub) { 0 -> loadUsage(); 1 -> { body.removeAllViews(); renderBlock() }; else -> { body.removeAllViews(); renderFeatures() } }
    }

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
    private fun loadUsage() {
        body.removeAllViews(); body.addView(periodChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))
        if (!UsageAccess.granted(ctx)) { body.addView(Ui.usageBanner(ctx)); return }
        body.addView(Ui.note(ctx, "讀取中…"))
        bg {
            val now = System.currentTimeMillis()
            val from = if (ri == 0) AppUsage.dayStart(now) else now - 7 * AppUsage.DAY
            val r = AppUsage.compute(ctx, from, now)
            ui.post { renderUsage(r) }
        }
    }

    private fun renderUsage(r: UsageResult) {
        body.removeAllViews(); body.addView(periodChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))
        val total = Prefs.totalLimit(ctx)
        val screenMin = r.screenOnMs / 60_000L

        val hero = Ui.card(ctx, 24)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val l = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        l.addView(Ui.tv(ctx, "螢幕使用時間", 12f, Theme.SUB))
        l.addView(Ui.tv(ctx, fmtDur(r.screenOnMs), 30f, Theme.TEXT, true))
        val rt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.END }
        rt.addView(Ui.tv(ctx, "解鎖次數", 12f, Theme.SUB))
        rt.addView(Ui.tv(ctx, "${r.unlocks}", 30f, Theme.BLUE, true))
        top.addView(l, LinearLayout.LayoutParams(0, Ui.WRAP, 1f)); top.addView(rt)
        hero.addView(top)
        if (ri == 0 && total > 0) {
            val p = screenMin.toFloat() / total
            hero.addView(MeterView(ctx).apply { value = p.coerceIn(0f, 1f); color = Theme.usage(p) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 12, 4))
            hero.addView(Ui.tv(ctx, "每日上限 $total 分鐘 · 已用 ${(p * 100).toInt()}%", 11f, Theme.SUB))
        }
        val avg = if (r.unlocks > 0) fmtDur(r.screenOnMs / r.unlocks) else "—"
        hero.addView(Ui.tv(ctx, "平均每次解鎖 $avg · 使用過 ${r.apps.size} 個 App" + if (ri == 1) " · 日均 ${fmtDur(r.screenOnMs / 7)}" else "", 11f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val bar = BarChartView(ctx).apply {
            color = Theme.BLUE; emptyText = "尚無螢幕使用紀錄"; fmt = { minFmt(it) }; maxFmt = { minFmt(it) }
        }
        if (ri == 0) { bar.title = "各時段螢幕使用"; bar.set((0 until 24).map { "$it" }, r.hourMs.map { it / 60_000f }) }
        else {
            bar.title = "每日螢幕時間"
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val origin = AppUsage.dayStart(System.currentTimeMillis()) - 6 * AppUsage.DAY
            bar.set((0 until 7).map { sdf.format(Date(origin + it * AppUsage.DAY + 3_600_000L)) }, r.dayMs.map { it / 60_000f })
        }
        body.addView(Ui.chartCard(ctx, bar, 180), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))

        body.addView(Ui.section(ctx, "App 使用排行 · 點選可封鎖 / 退出 / 設定上限"))
        val blocked = Prefs.blocked(ctx); val focus = Prefs.focusApps(ctx); val limits = Prefs.limits(ctx)
        val apps = r.apps.filter { Apps.launchable(ctx, it.pkg) }.take(30)
        val shown = if (expand) apps else apps.take(8)
        val list = Ui.card(ctx)
        if (apps.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有足夠的使用紀錄。"))
        val maxMs = (apps.firstOrNull()?.fgMs ?: 1L).toFloat()
        for (s in shown) {
            val lim = limits[s.pkg] ?: 0
            val over = ri == 0 && lim > 0 && s.fgMs / 60_000L >= lim
            val badge = when { s.pkg in blocked -> "封鎖中"; over -> "超過上限"; s.pkg in focus -> "專注"; lim > 0 -> "上限 $lim 分"; else -> "" }
            val row = AppRow(ctx)
            row.set(Apps.icon(ctx, s.pkg), Apps.label(ctx, s.pkg),
                "${s.launches} 次開啟 · 最後 " + fmtTime(s.last, if (ri == 0) "HH:mm" else "MM/dd HH:mm"),
                fmtDur(s.fgMs), s.fgMs / maxMs, if (over) Theme.RED else Theme.BLUE, badge,
                if (s.pkg in blocked || over) Theme.RED else Theme.AMBER)
            row.setOnClickListener { appMenu(s.pkg) }
            list.addView(row)
        }
        if (apps.size > 8) list.addView(Ui.btn(ctx, if (expand) "收合" else "顯示全部 ${apps.size} 個", Theme.CARD2, Theme.TEXT) { expand = !expand; renderUsage(r) },
            Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(list)
    }

    private fun pickLimit(title: String, onPick: (Int) -> Unit) {
        val names = arrayOf("不限制", "15 分鐘", "30 分鐘", "1 小時", "2 小時", "3 小時", "4 小時", "6 小時")
        val mins = intArrayOf(0, 15, 30, 60, 120, 180, 240, 360)
        AlertDialog.Builder(ctx).setTitle(title).setItems(names) { _, w -> onPick(mins[w]) }.show()
    }

    private fun appMenu(pkg: String) {
        val label = Apps.label(ctx, pkg)
        val blocked = pkg in Prefs.blocked(ctx); val focus = pkg in Prefs.focusApps(ctx); val netB = pkg in Prefs.netBlocked(ctx)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("退出此 App(回桌面並結束背景)" to { Guard.exit(ctx, pkg) })
        items.add((if (blocked) "解除永久封鎖" else "永久封鎖此 App") to { Prefs.toggleBlocked(ctx, pkg); checkService(); load() })
        items.add((if (focus) "移出專注清單" else "加入專注清單") to { Prefs.toggleFocus(ctx, pkg); checkService(); load() })
        items.add("設定每日使用上限" to { pickLimit("$label 每日上限") { Prefs.setLimit(ctx, pkg, it); load() } })
        items.add((if (netB) "解除網路封鎖" else "封鎖此 App 的網路(VPN)") to { toggleNetBlock(ctx, pkg); load() })
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
            fc.addView(Ui.btn(ctx, "結束專注", Theme.CARD2, Theme.RED) { Prefs.setFocusUntil(ctx, 0); load() })
        } else {
            fc.addView(Ui.tv(ctx, "開始後,「專注清單」內的 App 會被擋下,直到時間結束。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
            fun start(min: Int) = { checkService(); Prefs.setFocusUntil(ctx, System.currentTimeMillis() + min * 60_000L); load() }
            fc.addView(row(
                Ui.btn(ctx, "25 分鐘", Theme.GREEN, Ui.DARK_ON_ACCENT, start(25)),
                Ui.btn(ctx, "1 小時", Theme.GREEN, Ui.DARK_ON_ACCENT, start(60)),
                Ui.btn(ctx, "2 小時", Theme.GREEN, Ui.DARK_ON_ACCENT, start(120))))
        }
        val fl = Prefs.focusApps(ctx)
        fc.addView(Ui.tv(ctx, "專注清單:${fl.size} 個 App" + if (fl.isNotEmpty()) "(" + fl.take(4).joinToString("、") { Apps.label(ctx, it) } + if (fl.size > 4) "…)" else ")" else "", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 12, 6))
        fc.addView(Ui.btn(ctx, "選擇專注清單的 App", Theme.CARD2, Theme.TEXT) { pickApps(ctx, "專注清單(分心 App)", Prefs.focusApps(ctx)) { Prefs.setFocusApps(ctx, it); load() } })
        toggleRow(fc, "排程專注", if (Prefs.schedOn(ctx)) "每天 ${hm(Prefs.schedStart(ctx))} – ${hm(Prefs.schedEnd(ctx))}" else "例如睡前或上班時段自動封鎖", Prefs.schedOn(ctx)) {
            Prefs.setSchedOn(ctx, !Prefs.schedOn(ctx)); checkService(); load()
        }
        fc.addView(Ui.btn(ctx, "設定排程時段", Theme.CARD2, Theme.TEXT) {
            TimePickerDialog(ctx, { _, h1, m1 ->
                TimePickerDialog(ctx, { _, h2, m2 -> Prefs.setSched(ctx, h1 * 60 + m1, h2 * 60 + m2); load() },
                    Prefs.schedEnd(ctx) / 60, Prefs.schedEnd(ctx) % 60, true).apply { setTitle("結束時間") }.show()
            }, Prefs.schedStart(ctx) / 60, Prefs.schedStart(ctx) % 60, true).apply { setTitle("開始時間") }.show()
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(fc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 永久封鎖
        val blocked = Prefs.blocked(ctx)
        val bc = Ui.card(ctx)
        bc.addView(Ui.tv(ctx, "永久封鎖清單(${blocked.size})", 16f, Theme.TEXT, true))
        if (blocked.isEmpty()) bc.addView(Ui.note(ctx, "尚未封鎖任何 App。"))
        blocked.forEach { pkg ->
            val r = AppRow(ctx)
            r.set(Apps.icon(ctx, pkg), Apps.label(ctx, pkg), "點選解除封鎖", "", null, Theme.RED, "封鎖中", Theme.RED)
            r.setOnClickListener { Prefs.toggleBlocked(ctx, pkg); load() }
            bc.addView(r)
        }
        bc.addView(Ui.btn(ctx, "新增 / 調整封鎖 App", Theme.CARD2, Theme.TEXT) { pickApps(ctx, "永久封鎖的 App", Prefs.blocked(ctx)) { Prefs.setBlocked(ctx, it); checkService(); load() } }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        val tik = Features.TIKTOK.filter { Apps.launchable(ctx, it) && it !in blocked }
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
            r.setOnClickListener { pickLimit("${Apps.label(ctx, pkg)} 每日上限") { Prefs.setLimit(ctx, pkg, it); load() } }
            lc.addView(r)
        }
        lc.addView(Ui.btn(ctx, "每日螢幕總時間提醒:" + if (Prefs.totalLimit(ctx) > 0) "${Prefs.totalLimit(ctx)} 分鐘" else "未設定", Theme.CARD2, Theme.TEXT) {
            pickLimit("每日螢幕總時間(僅提醒)") { Prefs.setTotalLimit(ctx, it); load() }
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        toggleRow(lc, "超過個別上限時直接封鎖", if (Prefs.limitBlock(ctx)) "超過後會擋下該 App" else "目前只發通知提醒", Prefs.limitBlock(ctx)) {
            Prefs.setLimitBlock(ctx, !Prefs.limitBlock(ctx)); checkService(); load()
        }
        toggleRow(lc, "嚴格模式", if (Prefs.strict(ctx)) "封鎖畫面不能延長時間" else "封鎖畫面可「再給我 5 分鐘」(需等 10 秒)", Prefs.strict(ctx)) {
            Prefs.setStrict(ctx, !Prefs.strict(ctx)); load()
        }
        body.addView(lc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        body.addView(Ui.note(ctx, "提醒:這是自我管理工具,到無障礙設定仍可手動關閉服務,無法防止你自己解除。「退出 App」是回桌面並結束背景程序;Android 不允許一般 App 直接強制停止別的 App。"))
    }

    // ------------------------------------------------ 短影音防護
    private fun modeText(m: Int) = when { m < 0 -> "關閉"; m == 0 -> "完全封鎖"; else -> "每日額度 $m 分鐘" }

    private fun renderFeatures() {
        body.addView(serviceCard(), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        val intro = Ui.card(ctx)
        intro.addView(Ui.tv(ctx, "只擋短影音,不擋整個 App", 15f, Theme.TEXT, true))
        intro.addView(Ui.tv(ctx, "偵測到你進入 Reels / Shorts 分頁時,自動切回首頁,其他功能(貼文、限動、私訊、一般影片)照常使用。可選「完全封鎖」或「每日額度」,額度用完才擋。\n\n" +
            "注意:這靠辨識畫面元件,App 大改版後可能暫時失效,可用下方「擷取畫面元件」協助調整規則。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 6, 0))
        body.addView(intro, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        for (f in Features.all) {
            val mode = Prefs.feature(ctx, f.key)
            val installed = f.pkgs.any { Apps.launchable(ctx, it) }
            val c = Ui.card(ctx)
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            head.addView(Ui.tv(ctx, f.name, 16f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            head.addView(Ui.tv(ctx, modeText(mode), 12f, if (mode < 0) Theme.SUB else Theme.GREEN, true))
            c.addView(head)
            val used = Prefs.featUsedMs(ctx, f.key)
            c.addView(Ui.tv(ctx, (if (!installed) "未安裝 · " else "") +
                (if (mode > 0) "今日已看 ${fmtDur(used)} / $mode 分鐘 · " else "") + "今日攔截 ${Prefs.featBlocks(ctx, f.key)} 次", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
            if (mode > 0) c.addView(MeterView(ctx).apply {
                val p = used.toFloat() / (mode * 60_000f); value = p.coerceIn(0f, 1f); color = Theme.usage(p)
            }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 0, 10))
            c.addView(Ui.btn(ctx, "設定模式", Theme.CARD2, Theme.TEXT) {
                val names = arrayOf("關閉", "完全封鎖", "每天 5 分鐘", "每天 10 分鐘", "每天 15 分鐘", "每天 30 分鐘", "每天 60 分鐘")
                val vals = intArrayOf(-1, 0, 5, 10, 15, 30, 60)
                AlertDialog.Builder(ctx).setTitle(f.name).setItems(names) { _, w -> Prefs.setFeature(ctx, f.key, vals[w]); if (vals[w] >= 0) checkService(); load() }.show()
            })
            body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        }

        val tk = Ui.card(ctx)
        tk.addView(Ui.tv(ctx, "TikTok / 抖音", 16f, Theme.TEXT, true))
        tk.addView(Ui.tv(ctx, "整個 App 就是短影音,請直接用「永久封鎖」或「專注清單」。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
        val tik = Features.TIKTOK.filter { Apps.launchable(ctx, it) }
        if (tik.isEmpty()) tk.addView(Ui.note(ctx, "未偵測到已安裝。"))
        else tk.addView(Ui.btn(ctx, if (tik.all { it in Prefs.blocked(ctx) }) "已封鎖(點選解除)" else "加入永久封鎖", Theme.CARD2, Theme.AMBER) {
            val cur = Prefs.blocked(ctx)
            Prefs.setBlocked(ctx, if (tik.all { it in cur }) cur - tik.toSet() else cur + tik); checkService(); load()
        })
        body.addView(tk, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val dg = Ui.card(ctx)
        dg.addView(Ui.tv(ctx, "偵測診斷", 15f, Theme.TEXT, true))
        dg.addView(Ui.tv(ctx, "最近偵測:${Guard.lastDetect}", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 10))
        dg.addView(Ui.btn(ctx, "擷取畫面元件(按下後 6 秒內切到 Reels 畫面)", Theme.CARD2, Theme.TEXT) {
            val s = GuardService.instance
            if (s == null) Toast.makeText(ctx, "請先啟用專注防護服務", Toast.LENGTH_SHORT).show()
            else { s.dumpLater(); Toast.makeText(ctx, "請在 6 秒內切換到目標 App 的 Reels 畫面", Toast.LENGTH_LONG).show() }
        }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        dg.addView(Ui.btn(ctx, "查看擷取結果", Theme.CARD2, Theme.TEXT) {
            AlertDialog.Builder(ctx).setTitle("畫面元件").setMessage(if (Guard.lastDump.isEmpty()) "尚未擷取" else Guard.lastDump).setPositiveButton("關閉", null).show()
        })
        body.addView(dg)
    }
}

// =====================================================================
// 網路
// =====================================================================
class NetPage(a: Activity) : BasePage(a, "網路") {
    private var pi = 1       // 0 今天 / 1 7天 / 2 30天 / 3 本月
    private var ti = 0       // 0 行動網路 / 1 Wi-Fi
    private var expand = false
    private val periodChips = Chips(a, listOf("今天", "7 天", "30 天", "本月"), 1) { pi = it; expand = false; load() }
    private val typeChips = Chips(a, listOf("行動網路", "Wi-Fi"), 0) { ti = it; expand = false; load() }

    init {
        col.addView(periodChips.view, Ui.lp(a, Ui.MATCH, Ui.WRAP, 0, 10))
        col.addView(body)
    }

    private class Data(
        val mobile: LongArray?, val wifi: LongArray?, val cycleMobile: Long?,
        val apps: List<NetUsage.App>?, val barLabels: List<String>, val barValues: List<Float>, val err: String
    )

    override fun load() {
        body.removeAllViews()
        if (!UsageAccess.granted(ctx)) { renderBlocker(); body.addView(Ui.section(ctx, "用量統計")); body.addView(Ui.usageBanner(ctx)); return }
        loading()
        val period = pi; val type = if (ti == 0) NetUsage.MOBILE else NetUsage.WIFI
        bg {
            val now = System.currentTimeMillis()
            val today = AppUsage.dayStart(now)
            val cycle = NetUsage.cycleStart(now, Prefs.billingDay(ctx))
            val from = when (period) { 0 -> today; 1 -> today - 6 * AppUsage.DAY; 2 -> today - 29 * AppUsage.DAY; else -> cycle }
            var err = ""
            fun <T> tryq(f: () -> T): T? = try { f() } catch (e: Throwable) { err = "${e.javaClass.simpleName}: ${e.message}"; null }

            val mobile = tryq { NetUsage.total(ctx, NetUsage.MOBILE, from, now) }
            val wifi = tryq { NetUsage.total(ctx, NetUsage.WIFI, from, now) }
            val cm = tryq { NetUsage.total(ctx, NetUsage.MOBILE, cycle, now) }
            val apps = tryq { NetUsage.perApp(ctx, type, from, now) }

            val labels = ArrayList<String>(); val vals = ArrayList<Float>()
            if (period == 0) {
                val sdf = SimpleDateFormat("H", Locale.getDefault())
                for (h in 0 until 24) {
                    val a0 = today + h * 3_600_000L
                    if (a0 > now) break
                    val v = tryq { NetUsage.total(ctx, type, a0, minOf(a0 + 3_600_000L, now)) }
                    labels.add(sdf.format(Date(a0))); vals.add(if (v == null) 0f else (v[0] + v[1]).toFloat())
                }
            } else {
                val sdf = SimpleDateFormat("M/d", Locale.getDefault())
                var d0 = AppUsage.dayStart(from)
                while (d0 <= now && labels.size < 31) {
                    val v = tryq { NetUsage.total(ctx, type, d0, minOf(d0 + AppUsage.DAY, now)) }
                    labels.add(sdf.format(Date(d0 + 3_600_000L))); vals.add(if (v == null) 0f else (v[0] + v[1]).toFloat())
                    d0 += AppUsage.DAY
                }
            }
            val data = Data(mobile, wifi, cm?.let { it[0] + it[1] }, apps, labels, vals, err)
            ui.post { render(data, type) }
        }
    }

    private fun statCol(label: String, v: LongArray?, color: Int): LinearLayout {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Ui.tv(ctx, label, 12f, Theme.SUB))
        c.addView(Ui.tv(ctx, v?.let { fmtBytes(it[0] + it[1]) } ?: "—", 24f, color, true))
        c.addView(Ui.tv(ctx, v?.let { "↓ ${fmtBytes(it[0])}  ↑ ${fmtBytes(it[1])}" } ?: "不支援", 11f, Theme.SUB))
        return c
    }

    private fun render(d: Data, type: Int) {
        body.removeAllViews()
        if (d.mobile == null && d.wifi == null) {
            body.addView(Ui.note(ctx, "無法讀取網路用量:${d.err}\n(部分機型或系統版本限制了行動網路統計)", Theme.AMBER))
            renderBlocker(); return
        }
        val tname = if (type == 0) "行動網路" else "Wi-Fi"

        // 1) 總覽
        val hero = Ui.card(ctx, 24)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(statCol("行動網路", d.mobile, Theme.AMBER), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(statCol("Wi-Fi", d.wifi, Theme.BLUE), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        hero.addView(row)
        val mt = (d.mobile?.let { it[0] + it[1] } ?: 0L); val wt = (d.wifi?.let { it[0] + it[1] } ?: 0L)
        if (mt + wt > 0) {
            val frac = wt.toFloat() / (mt + wt)
            hero.addView(MeterView(ctx).apply { value = frac; color = Theme.BLUE }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 12, 4))
            hero.addView(Ui.tv(ctx, "Wi-Fi 占 ${(frac * 100).toInt()}% · 行動網路占 ${100 - (frac * 100).toInt()}%", 11f, Theme.SUB))
        }
        body.addView(hero, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 2) 月流量
        val limitGb = Prefs.dataLimitGb(ctx); val used = d.cycleMobile ?: 0L
        val mc = Ui.card(ctx)
        val mh = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        mh.addView(Ui.tv(ctx, "本月行動數據(每月 ${Prefs.billingDay(ctx)} 號結算)", 14f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        mh.addView(Ui.tv(ctx, "⚙ 設定", 13f, Theme.BLUE, true).apply {
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            setOnClickListener {
                AlertDialog.Builder(ctx).setTitle("流量設定").setItems(arrayOf("每月流量上限", "帳單結算日")) { _, w ->
                    if (w == 0) {
                        val names = arrayOf("不設定", "1 GB", "3 GB", "5 GB", "10 GB", "20 GB", "50 GB", "100 GB")
                        val gbs = floatArrayOf(0f, 1f, 3f, 5f, 10f, 20f, 50f, 100f)
                        AlertDialog.Builder(ctx).setTitle("每月行動數據上限").setItems(names) { _, i -> Prefs.setDataLimitGb(ctx, gbs[i]); load() }.show()
                    } else {
                        val days = intArrayOf(1, 5, 10, 15, 20, 25, 28)
                        AlertDialog.Builder(ctx).setTitle("每月幾號結算").setItems(days.map { "$it 號" }.toTypedArray()) { _, i -> Prefs.setBillingDay(ctx, days[i]); load() }.show()
                    }
                }.show()
            }
        })
        mc.addView(mh)
        if (limitGb > 0) {
            val p = used / (limitGb * 1_073_741_824f)
            mc.addView(Ui.tv(ctx, "${fmtBytes(used)} / %.0f GB · %d%%".format(limitGb, (p * 100).toInt()), 13f, Theme.TEXT), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            mc.addView(MeterView(ctx).apply { value = p.coerceIn(0f, 1f); color = Theme.usage(p) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 8, 0))
        } else mc.addView(Ui.tv(ctx, "${fmtBytes(used)} · 尚未設定月上限(點右上角設定)", 13f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(mc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 10))

        // 3) 切換網路類型(套用到圖表、背景流量、排行)
        body.addView(Ui.tv(ctx, "查看類型", 12f, Theme.SUB).apply { setPadding(ctx.dp(4), 0, 0, ctx.dp(6)) })
        body.addView(typeChips.view, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        val bar = BarChartView(ctx).apply {
            title = "$tname " + if (pi == 0) "每小時用量" else "每日用量"
            color = if (type == 0) Theme.AMBER else Theme.BLUE; emptyText = "這段期間沒有流量"
            fmt = { fmtBytes(it.toLong()) }; maxFmt = { fmtBytes(it.toLong()) }
            set(d.barLabels, d.barValues)
        }
        body.addView(Ui.chartCard(ctx, bar, 180), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 4) 前景 / 背景
        val apps = d.apps ?: emptyList()
        val fg = apps.sumOf { it.fg }; val bgT = apps.sumOf { it.bg }
        val ratio = if (fg + bgT > 0) bgT.toFloat() / (fg + bgT) else 0f
        val heavy = apps.count { it.bg > it.total * 0.5 && it.bg > 30L * 1_048_576 }
        val bc = Ui.card(ctx)
        bc.addView(Ui.tv(ctx, "背景流量占比($tname)", 14f, Theme.TEXT, true))
        bc.addView(Ui.tv(ctx, "%.0f%%".format(ratio * 100), 26f, Theme.usage(ratio / 0.6f), true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 0))
        bc.addView(MeterView(ctx).apply { value = ratio; color = Theme.usage(ratio / 0.6f) }, Ui.lp(ctx, Ui.MATCH, ctx.dp(6), 4, 6))
        bc.addView(Ui.tv(ctx, "前景 ${fmtBytes(fg)} · 背景 ${fmtBytes(bgT)}" + if (heavy > 0) " · ⚠ $heavy 個 App 背景流量偏高" else "", 11f, Theme.SUB))
        body.addView(bc, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 4))

        // 5) 排行
        body.addView(Ui.section(ctx, "各 App 流量排行(點選可封鎖網路)"))
        val list = Ui.card(ctx)
        val top = apps.take(25)
        val shown = if (expand) top else top.take(8)
        if (top.isEmpty()) list.addView(Ui.note(ctx, "這段期間沒有流量紀錄。"))
        val maxB = (top.firstOrNull()?.total ?: 1L).toFloat()
        val nb = Prefs.netBlocked(ctx)
        for (s in shown) {
            val (name, pkg) = NetUsage.uidName(ctx, s.uid)
            val heavyBg = s.bg > s.total * 0.5 && s.bg > 30L * 1_048_576
            val badge = when { pkg != null && pkg in nb -> "網路封鎖"; heavyBg -> "背景偏高"; else -> "" }
            val r = AppRow(ctx)
            r.set(pkg?.let { Apps.icon(ctx, it) }, name, "前景 ${fmtBytes(s.fg)} · 背景 ${fmtBytes(s.bg)}",
                fmtBytes(s.total), s.total / maxB, if (heavyBg) Theme.RED else Theme.BLUE, badge, Theme.RED)
            if (pkg != null) r.setOnClickListener {
                val blocked = pkg in Prefs.netBlocked(ctx)
                AlertDialog.Builder(ctx).setTitle(name)
                    .setItems(arrayOf(if (blocked) "解除網路封鎖" else "封鎖此 App 的網路(VPN)", "App 資訊(可關閉背景數據)")) { _, w ->
                        if (w == 0) { toggleNetBlock(ctx, pkg); load() } else Apps.openInfo(ctx, pkg)
                    }.show()
            }
            list.addView(r)
        }
        if (top.size > 8) list.addView(Ui.btn(ctx, if (expand) "收合" else "顯示全部 ${top.size} 個", Theme.CARD2, Theme.TEXT) { expand = !expand; render(d, type) }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        body.addView(list, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))

        // 6) 網路封鎖
        renderBlocker()
    }

    /** 以本機 VPN 封鎖指定 App 的網路 */
    private fun renderBlocker() {
        val on = Prefs.netBlockOn(ctx); val list = Prefs.netBlocked(ctx)
        val c = Ui.card(ctx)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(Ui.tv(ctx, "App 網路封鎖(防偷跑流量)", 16f, Theme.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.tv(ctx, if (on) "● 運作中" else "○ 未啟用", 12f, if (on) Theme.GREEN else Theme.SUB, true))
        c.addView(head)
        c.addView(Ui.tv(ctx, "被選中的 App 會完全無法上網(含背景同步)。原理是建立一條只給這些 App 使用的本機 VPN,封包全部丟棄,不會經過任何伺服器。" +
            "手機同時只能有一個 VPN,啟用時系統會跳出授權視窗。", 12f, Theme.SUB), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 4, 8))
        if (list.isNotEmpty()) c.addView(Ui.tv(ctx, "已選 ${list.size} 個:" + list.take(5).joinToString("、") { Apps.label(ctx, it) } + if (list.size > 5) "…" else "", 12f, Theme.TEXT), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
        c.addView(Ui.btn(ctx, "選擇要封鎖網路的 App", Theme.CARD2, Theme.TEXT) {
            pickApps(ctx, "封鎖網路的 App", Prefs.netBlocked(ctx)) {
                Prefs.setNetBlocked(ctx, it)
                if (it.isEmpty()) NetBlock.stop(ctx) else if (!Prefs.netBlockOn(ctx)) NetBlock.enable(ctx) else NetBlock.refresh(ctx)
                load()
            }
        })
        if (list.isNotEmpty()) {
            if (on) c.addView(Ui.btn(ctx, "停用網路封鎖", Theme.CARD2, Theme.RED) { NetBlock.stop(ctx); load() }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
            else c.addView(Ui.btn(ctx, "啟用網路封鎖", Theme.GREEN, Ui.DARK_ON_ACCENT) { NetBlock.enable(ctx) }, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 8, 0))
        }
        body.addView(c, Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 8))
    }
}
