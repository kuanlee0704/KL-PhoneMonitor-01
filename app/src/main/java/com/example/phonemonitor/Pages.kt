package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class BasePage(protected val ctx: Activity, title: String) {
    private val built = Ui.page(ctx)
    val view: ScrollView = built.first
    protected val col: LinearLayout = built.second
    protected val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    protected val ui = Handler(Looper.getMainLooper())

    init { col.addView(Ui.tv(ctx, title, 24f, Theme.TEXT, true), Ui.lp(ctx, Ui.MATCH, Ui.WRAP, 0, 12)) }

    abstract fun load()
    open fun onHide() {}

    private var loadedAt = 0L
    /** 切換分頁時 30 秒內不重複載入;force=true(回到 App)一律重載 */
    fun show(force: Boolean) {
        val n = System.currentTimeMillis()
        if (force || n - loadedAt > 30_000L) { loadedAt = n; load() }
    }

    protected fun bg(work: () -> Unit) {
        Thread {
            try { work() } catch (e: Throwable) {
                ui.post { fail("${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    protected open fun fail(msg: String) {
        body.removeAllViews()
        body.addView(Ui.note(ctx, "讀取失敗:$msg", Theme.RED))
    }

    protected fun loading() { body.removeAllViews(); body.addView(Ui.note(ctx, "讀取中…")) }
    protected fun fmtTime(ts: Long, pattern: String = "MM/dd HH:mm") = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    protected fun minFmt(v: Float) = if (v >= 60f) "%.1f 時".format(v / 60f) else "%.0f 分".format(v)
}
