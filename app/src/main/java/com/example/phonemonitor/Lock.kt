package com.example.phonemonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Base64
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class VerifyResult(val ok: Boolean, val msg: String)

object Lock {
    const val SESSION_MS = 5 * 60_000L
    @Volatile private var unlockedUntil = 0L

    fun hasPin(c: Context) = Prefs.pinHash(c).isNotEmpty()
    fun sessionValid() = System.currentTimeMillis() < unlockedUntil
    /** 已設定密碼、且目前沒有在解鎖時間內 */
    fun locked(c: Context) = hasPin(c) && !sessionValid()
    fun lockNow() { unlockedUntil = 0L }
    private fun unlock() { unlockedUntil = System.currentTimeMillis() + SESSION_MS }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Prefs.savePin(c, b64(hash(pin, salt)), b64(salt), pin.length)
    }

    private fun matches(c: Context, pin: String): Boolean = try {
        val salt = Base64.decode(Prefs.pinSalt(c), Base64.NO_WRAP)
        MessageDigest.isEqual(hash(pin, salt), Base64.decode(Prefs.pinHash(c), Base64.NO_WRAP))
    } catch (e: Exception) { false }

    /** 驗證密碼;連續輸錯 5 次起會暫時鎖定,且時間逐次加倍(最長 10 分鐘) */
    fun verify(c: Context, pin: String): VerifyResult {
        val wait = Prefs.pinLockUntil(c) - System.currentTimeMillis()
        if (wait > 0) return VerifyResult(false, "嘗試次數過多,請等 ${wait / 1000 + 1} 秒")
        if (matches(c, pin)) { Prefs.setPinFails(c, 0); unlock(); return VerifyResult(true, "") }
        val f = Prefs.pinFails(c) + 1
        Prefs.setPinFails(c, f)
        if (f >= 5) {
            val sec = minOf(600, 30 * (1 shl minOf(f - 5, 5)))
            Prefs.setPinLockUntil(c, System.currentTimeMillis() + sec * 1000L)
            return VerifyResult(false, "密碼錯誤,已鎖定 $sec 秒")
        }
        return VerifyResult(false, "密碼錯誤(再錯 ${5 - f} 次將暫時鎖定)")
    }

    /** 跳出輸入密碼視窗,驗證成功後執行 onOk */
    fun prompt(act: Activity, title: String = "輸入密碼", onOk: () -> Unit) {
        var dlg: AlertDialog? = null
        lateinit var pad: PinPad
        pad = PinPad(act, 8, Prefs.pinLen(act)) { pin ->
            val r = verify(act, pin)
            if (r.ok) { dlg?.dismiss(); onOk() } else pad.setMessage(r.msg)
        }
        val d = AlertDialog.Builder(act).setTitle(title).setView(pad).setNegativeButton("取消", null).create()
        dlg = d
        d.show()
    }

    /** 需要時才要求密碼(needed=false 代表這次操作是「加嚴」,不用輸入) */
    fun gate(act: Activity, needed: Boolean = true, action: () -> Unit) {
        if (!needed || !locked(act)) action() else prompt(act, "此操作需要密碼", action)
    }

    /** 設定新密碼:輸入兩次確認 */
    fun setupFlow(act: Activity, onDone: () -> Unit) {
        var first = ""
        var step = 1
        var dlg: AlertDialog? = null
        lateinit var pad: PinPad
        pad = PinPad(act, 8, 0) { pin ->
            if (pin.length < 4) pad.setMessage("至少需要 4 位數字")
            else if (step == 1) {
                first = pin; step = 2; dlg?.setTitle("再輸入一次確認"); pad.setMessage("", Theme.SUB)
            } else if (pin == first) {
                setPin(act, pin); unlock(); dlg?.dismiss()
                Toast.makeText(act, "密碼已設定", Toast.LENGTH_SHORT).show(); onDone()
            } else {
                step = 1; first = ""; dlg?.setTitle("設定密碼(4–8 位數字)"); pad.setMessage("兩次不一致,請重新輸入")
            }
        }
        val d = AlertDialog.Builder(act).setTitle("設定密碼(4–8 位數字)").setView(pad).setNegativeButton("取消", null).create()
        dlg = d
        d.show()
    }
}

/** 數字鍵盤。autoLen > 0 時輸入滿該位數自動送出;任何時候按 ✓ 也可送出(至少 4 位) */
class PinPad(private val c: Context, private val maxLen: Int, var autoLen: Int, private val onSubmit: (String) -> Unit) : LinearLayout(c) {
    private val sb = StringBuilder()
    private val dots = TextView(c)
    private val msg = TextView(c)

    init {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
        dots.textSize = 26f; dots.setTextColor(Theme.TEXT); dots.gravity = Gravity.CENTER
        msg.textSize = 12f; msg.gravity = Gravity.CENTER; msg.setTextColor(Theme.RED)
        addView(dots, Ui.lp(c, Ui.MATCH, Ui.WRAP, 8, 4))
        addView(msg, Ui.lp(c, Ui.MATCH, Ui.WRAP, 0, 8))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        for (r in rows) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (k in r) {
                val t = TextView(c).apply {
                    text = k; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Theme.TEXT)
                    background = Theme.rounded(Theme.CARD2, c.dp(14).toFloat()); setOnClickListener { press(k) }
                }
                row.addView(t, LinearLayout.LayoutParams(c.dp(72), c.dp(54)).apply { setMargins(c.dp(5), c.dp(5), c.dp(5), c.dp(5)) })
            }
            addView(row)
        }
        refresh()
    }

    fun setMessage(s: String, color: Int = Theme.RED) { msg.text = s; msg.setTextColor(color) }

    private fun press(k: String) {
        when (k) {
            "⌫" -> if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            "✓" -> if (sb.length >= 4) { submit(); return }
            else -> if (sb.length < maxLen) {
                sb.append(k)
                if (autoLen > 0 && sb.length == autoLen) { submit(); return }
            }
        }
        refresh()
    }

    private fun submit() {
        val v = sb.toString(); sb.clear(); refresh(); onSubmit(v)
    }

    private fun refresh() {
        val n = sb.length
        val total = if (autoLen > 0) autoLen else maxOf(4, n)
        dots.text = (0 until total).joinToString(" ") { if (it < n) "●" else "○" }
    }
}
