package com.burak.disko

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var ipEdit: EditText
    private lateinit var maxEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("disko", MODE_PRIVATE) }
    private var localMsg: String? = null

    private val poll = object : Runnable {
        override fun run() {
            statusText.text = localMsg ?: DiskoService.status
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        root.addView(TextView(this).apply { text = "Disko"; textSize = 28f })

        root.addView(TextView(this).apply { text = "Ampul IP adresi"; setPadding(0, pad, 0, 0) })
        ipEdit = EditText(this).apply {
            setText(prefs.getString("ip", ""))
            hint = "192.168.1.50"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(ipEdit)

        root.addView(TextView(this).apply { text = "Maks. parlaklık (%)"; setPadding(0, pad / 2, 0, 0) })
        maxEdit = EditText(this).apply {
            setText(prefs.getInt("maxb", 100).toString())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(maxEdit)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, pad / 2, 0, 0)
        }
        startBtn = Button(this).apply { text = "Başlat"; setOnClickListener { onStartClicked() } }
        val stopBtn = Button(this).apply { text = "Durdur"; setOnClickListener { onStopClicked() } }
        val findBtn = Button(this).apply { text = "Ampul Bul"; setOnClickListener { onFindClicked() } }
        row.addView(startBtn); row.addView(stopBtn); row.addView(findBtn)
        root.addView(row)

        statusText = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, pad) }
        root.addView(statusText)

        root.addView(TextView(this).apply {
            alpha = 0.7f
            text = "1) Yeelight uygulamasında ampulün ayarlarından LAN Control'ü aç.\n" +
                    "2) Ampul Bul (ya da IP'yi elle yaz) → Başlat → izni onayla.\n" +
                    "3) Home tuşuna bas, Nuvio'yu aç. Kapatmak için buraya dönüp Durdur."
        })

        setContentView(ScrollView(this).apply { addView(root) })
        startBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    private fun onStartClicked() {
        localMsg = null
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) { localMsg = "Önce ampulün IP adresini gir ya da Ampul Bul'a bas."; return }
        if (DiskoService.running) { localMsg = "Zaten çalışıyor. Önce Durdur."; return }
        val maxb = maxEdit.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 100
        prefs.edit().putString("ip", ip).putInt("maxb", maxb).apply()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: ActivityNotFoundException) {
            localMsg = "Bu TV ekran yakalama izin ekranını desteklemiyor."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            localMsg = "Ekran yakalama izni verilmedi."
            return
        }
        val i = Intent(this, DiskoService::class.java)
            .putExtra(DiskoService.EXTRA_CODE, resultCode)
            .putExtra(DiskoService.EXTRA_DATA, data)
            .putExtra(DiskoService.EXTRA_IP, prefs.getString("ip", ""))
            .putExtra(DiskoService.EXTRA_MAXB, prefs.getInt("maxb", 100))
        DiskoService.status = "Başlatılıyor…"
        startForegroundService(i)
    }

    private fun onStopClicked() {
        localMsg = null
        stopService(Intent(this, DiskoService::class.java))
    }

    private fun onFindClicked() {
        localMsg = "Ampul aranıyor…"
        Thread {
            val list = try { YeelightDiscovery.discover(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                localMsg = if (list.isEmpty()) {
                    "Ampul bulunamadı. LAN Control açık mı? TV ve ampul aynı ağda mı?"
                } else {
                    ipEdit.setText(list[0])
                    "Bulundu: ${list.joinToString()}"
                }
            }
        }.start()
    }

    companion object {
        private const val REQ_CAPTURE = 42
    }
}
