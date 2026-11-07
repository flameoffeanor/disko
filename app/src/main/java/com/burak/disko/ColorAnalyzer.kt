package com.burak.disko

import android.graphics.Color

/**
 * Küçültülmüş ekran karesinden baskın rengi bulur.
 * - Siyaha yakın pikseller (letterbox barları) renk hesabına girmez.
 * - Doygun pikseller ton (hue) kovalarına ağırlıklı dağıtılır, en güçlü ton seçilir.
 * - Sahne çoğunlukla gri/beyazsa o nötr ortalama döner.
 */
class ColorAnalyzer {

    data class Result(
        /** Normalize renk (en parlak kanal 255) ya da kare tamamen karanlıksa null */
        val rgb: Int?,
        /** Karenin ortalama parlaklığı 0..1 */
        val brightness: Float
    )

    private val bins = 24
    private val w = FloatArray(bins)
    private val sr = FloatArray(bins)
    private val sg = FloatArray(bins)
    private val sb = FloatArray(bins)
    private val hsv = FloatArray(3)

    fun analyze(data: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): Result {
        w.fill(0f); sr.fill(0f); sg.fill(0f); sb.fill(0f)
        var lumaSum = 0.0
        var count = 0
        var nW = 0f; var nR = 0f; var nG = 0f; var nB = 0f

        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val i = row + x * pixelStride
                val r = data[i].toInt() and 0xFF
                val g = data[i + 1].toInt() and 0xFF
                val b = data[i + 2].toInt() and 0xFF
                count++
                lumaSum += 0.2126 * r + 0.7152 * g + 0.0722 * b

                val mx = maxOf(r, g, b)
                if (mx < 24) continue
                val mn = minOf(r, g, b)
                val v = mx / 255f
                val s = (mx - mn) / mx.toFloat()

                if (s < 0.22f) {
                    val wt = v * 0.6f
                    nW += wt; nR += r * wt; nG += g * wt; nB += b * wt
                    continue
                }
                Color.RGBToHSV(r, g, b, hsv)
                val bin = (hsv[0] / 360f * bins).toInt().coerceIn(0, bins - 1)
                val wt = s * s * v
                w[bin] += wt; sr[bin] += r * wt; sg[bin] += g * wt; sb[bin] += b * wt
            }
        }

        val brightness = if (count > 0) (lumaSum / count / 255.0).toFloat() else 0f
        val satTotal = w.sum()

        if (satTotal < 1e-3f && nW < 1e-3f) return Result(null, brightness)

        if (satTotal < 0.2f * nW) {
            return Result(normalize(nR / nW, nG / nW, nB / nW), brightness)
        }

        // Komşu kovalarla birlikte en güçlü tonu bul
        var best = 0
        var bestScore = -1f
        for (i in 0 until bins) {
            val score = w[i] + 0.5f * (w[(i + bins - 1) % bins] + w[(i + 1) % bins])
            if (score > bestScore) { bestScore = score; best = i }
        }
        var tw = 0f; var tr = 0f; var tg = 0f; var tb = 0f
        for (k in -1..1) {
            val i = (best + k + bins) % bins
            tw += w[i]; tr += sr[i]; tg += sg[i]; tb += sb[i]
        }
        return Result(boost(normalize(tr / tw, tg / tw, tb / tw)), brightness)
    }

    private fun normalize(r: Float, g: Float, b: Float): Int {
        val m = maxOf(r, g, b)
        if (m <= 0f) return WARM
        val k = 255f / m
        return Color.rgb(
            (r * k).toInt().coerceIn(0, 255),
            (g * k).toInt().coerceIn(0, 255),
            (b * k).toInt().coerceIn(0, 255)
        )
    }

    /** Ampulde renk solgun görünmesin diye doygunluğu biraz artır */
    private fun boost(c: Int): Int {
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    companion object {
        val WARM = Color.rgb(255, 170, 90)
    }
}
