package com.burak.disko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class DiskoService : Service() {

    companion object {
        @Volatile var status: String = "Durduruldu"
        @Volatile var running = false

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_IP = "ip"
        const val EXTRA_MAXB = "maxb"

        private const val CAP_W = 96
        private const val CAP_H = 54
        private const val FRAME_MS = 80L     // ~12 analiz/sn
        private const val SEND_MS = 110L     // ampule en fazla ~9 güncelleme/sn
        private const val TRANSITION_MS = 150
        private const val ALPHA = 0.4f       // yumuşatma (0..1, büyük = hızlı tepki)
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bulb: YeelightClient? = null
    private val analyzer = ColorAnalyzer()

    private var ip = ""
    private var maxBright = 100
    private var failed = false

    private var frameBytes = ByteArray(0)
    private var lastFrame = 0L
    private var lastSend = 0L

    // hedef ve anlık (yumuşatılmış) renk
    private var tR = 255f; private var tG = 170f; private var tB = 90f
    private var cR = 255f; private var cG = 170f; private var cB = 90f
    private var cBright = 30f
    // en son gönderilen
    private var sR = -100f; private var sG = -100f; private var sB = -100f
    private var sBright = -100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent == null || running) return START_NOT_STICKY

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        ip = intent.getStringExtra(EXTRA_IP) ?: ""
        maxBright = intent.getIntExtra(EXTRA_MAXB, 100).coerceIn(1, 100)

        if (data == null || ip.isEmpty()) {
            fail("Eksik bilgi (izin ya da IP)")
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            fail("Ekran yakalama başlatılamadı")
            return START_NOT_STICKY
        }
        projection = proj
        running = true
        status = "Ampule bağlanıyor ($ip)…"

        val t = HandlerThread("disko").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, h)

        h.post {
            try {
                bulb = YeelightClient(ip).also { it.connect() }
                status = if (bulb!!.musicMode) "Çalışıyor — $ip (music mode)"
                else "Çalışıyor — $ip (YAVAŞ MOD: music mode açılamadı)"
                startCapture(h)
            } catch (e: Exception) {
                fail("Ampule bağlanamadı ($ip): ${e.message}")
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(h: Handler) {
        val r = ImageReader.newInstance(CAP_W, CAP_H, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
                ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrame >= FRAME_MS) {
                    lastFrame = now
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val n = buf.remaining()
                    if (frameBytes.size < n) frameBytes = ByteArray(n)
                    buf.get(frameBytes, 0, n)
                    val res = analyzer.analyze(frameBytes, img.width, img.height, plane.rowStride, plane.pixelStride)
                    update(res, now)
                }
            } catch (e: Exception) {
                status = "Hata: ${e.message}"
            } finally {
                img.close()
            }
        }, h)

        val dpi = resources.displayMetrics.densityDpi
        display = projection?.createVirtualDisplay(
            "Disko", CAP_W, CAP_H, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )
    }

    private fun update(res: ColorAnalyzer.Result, now: Long) {
        res.rgb?.let {
            tR = Color.red(it).toFloat(); tG = Color.green(it).toFloat(); tB = Color.blue(it).toFloat()
        }
        // Film karelerinin ortalama parlaklığı genelde 0.05–0.35 arası → 0.45'i tavan kabul et
        val lvl = (res.brightness / 0.45f).coerceIn(0f, 1f)
        val tBright = 1f + (maxBright - 1) * lvl.pow(0.8f)

        cR += (tR - cR) * ALPHA
        cG += (tG - cG) * ALPHA
        cB += (tB - cB) * ALPHA
        cBright += (tBright - cBright) * ALPHA

        val bi = cBright.roundToInt().coerceIn(1, 100)
        val diff = abs(cR - sR) + abs(cG - sG) + abs(cB - sB)
        if ((diff > 10f || abs(bi - sBright) >= 2) && now - lastSend >= SEND_MS) {
            val rgb = Color.rgb(
                cR.roundToInt().coerceIn(0, 255),
                cG.roundToInt().coerceIn(0, 255),
                cB.roundToInt().coerceIn(0, 255)
            ) and 0xFFFFFF
            try {
                bulb?.setColor(rgb, bi, TRANSITION_MS)
            } catch (e: Exception) {
                reconnect()
                return
            }
            sR = cR; sG = cG; sB = cB; sBright = bi
            lastSend = now
        }
    }

    private fun reconnect() {
        status = "Bağlantı koptu, yeniden bağlanıyor…"
        bulb?.close()
        bulb = null
        try {
            bulb = YeelightClient(ip).also { it.connect() }
            sBright = -100; sR = -100f
            status = if (bulb!!.musicMode) "Çalışıyor — $ip (music mode)"
            else "Çalışıyor — $ip (YAVAŞ MOD)"
        } catch (e: Exception) {
            fail("Ampul bağlantısı koptu: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        failed = true
        status = msg
        stopSelf()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("disko", "Disko", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "disko")
            .setContentTitle("Disko çalışıyor")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        running = false
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        val h = handler
        val teardown = Runnable {
            try { display?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            bulb?.close()
            display = null; reader = null; bulb = null
        }
        if (h != null) {
            h.post(teardown)
            thread?.quitSafely()
        } else {
            teardown.run()
        }
        if (!failed) status = "Durduruldu"
        super.onDestroy()
    }
}
