package com.yixiu.photo.image

import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 滤镜预设 */
data class FilterPreset(
    val id: String,
    val label: String,
    val matrix: FloatArray,     // 3x3, row major
    val bias: FloatArray,       // 3, 0..255 尺度
    val gamma: Float = 1f,
    val lift: Float = 0f,       // 抬黑位 0..0.15
    val sat: Float = 1f
)

object Filters {

    private fun diag(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f,
        0f, g, 0f,
        0f, 0f, b
    )

    val all: List<FilterPreset> = listOf(
        FilterPreset("none", "原图", diag(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f)),
        FilterPreset("clear", "清透", diag(1.02f, 1.02f, 1.06f), floatArrayOf(-2f, 2f, 4f), gamma = 1.06f, sat = 1.06f),
        FilterPreset("cream", "奶油", diag(1.06f, 1.02f, 0.98f), floatArrayOf(6f, 4f, 8f), gamma = 1.08f, lift = 0.05f, sat = 0.9f),
        FilterPreset("film", "胶片", diag(1.02f, 1.0f, 0.98f), floatArrayOf(2f, 4f, 2f), gamma = 1.02f, lift = 0.075f, sat = 0.86f),
        FilterPreset("cool", "冷调", diag(0.96f, 1.0f, 1.09f), floatArrayOf(-4f, 0f, 6f), gamma = 1.02f, sat = 0.95f),
        FilterPreset("warm", "暖阳", diag(1.09f, 1.02f, 0.92f), floatArrayOf(6f, 2f, -6f), gamma = 1.03f, sat = 1.08f),
        FilterPreset("bw", "黑白", diag(0f, 0f, 0f).also { it[0] = 0f },
            floatArrayOf(0f, 0f, 0f), gamma = 1.0f, sat = 0f),
        FilterPreset("mono", "灰调", diag(0.95f, 0.95f, 0.97f), floatArrayOf(4f, 4f, 6f), gamma = 1.05f, lift = 0.03f, sat = 0.15f),
        FilterPreset("vintage", "复古",
            floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f),
            floatArrayOf(0f, 0f, 0f), gamma = 1.05f, lift = 0.06f, sat = 0.95f),
        FilterPreset("cyber", "赛博", diag(1.02f, 0.98f, 1.12f), floatArrayOf(-6f, -2f, 10f), gamma = 0.96f, sat = 1.22f),
        FilterPreset("forest", "森系", diag(0.97f, 1.06f, 0.98f), floatArrayOf(-2f, 6f, 0f), gamma = 1.05f, lift = 0.04f, sat = 1.05f),
        FilterPreset("sunset", "落日", diag(1.12f, 1.0f, 0.9f), floatArrayOf(10f, 0f, -4f), gamma = 0.98f, sat = 1.15f),
        FilterPreset("night", "夜览", diag(0.94f, 0.97f, 1.08f), floatArrayOf(-10f, -6f, 4f), gamma = 0.94f, sat = 1.0f),
        FilterPreset("peach", "蜜桃", diag(1.1f, 0.97f, 1.0f), floatArrayOf(8f, 0f, 4f), gamma = 1.06f, lift = 0.03f, sat = 0.98f)
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

object ImageOps {

    internal val pool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    )

    internal inline fun parallelFor(height: Int, crossinline body: (Int) -> Unit) {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val chunk = (height + cores - 1) / cores
        val tasks = ArrayList<Callable<Void>>(cores)
        for (i in 0 until cores) {
            val start = i * chunk
            val end = minOf(height, start + chunk)
            if (start < end) {
                tasks.add(Callable {
                    for (y in start until end) body(y)
                    null
                })
            }
        }
        pool.invokeAll(tasks)
    }

    fun IntArray.toBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(this, w, h, Bitmap.Config.ARGB_8888)

    fun Bitmap.pixels(): IntArray {
        val arr = IntArray(width * height)
        getPixels(arr, 0, width, 0, 0, width, height)
        return arr
    }

    private fun clamp(v: Float): Float = if (v < 0f) 0f else if (v > 255f) 255f else v

    /** 由滤镜 + 基础调色构建 3 条 256 项查表 */
    fun buildLut(filter: FilterPreset, brightness: Float, contrast: Float, warmth: Float): IntArray {
        val lut = IntArray(768)
        val gammaFix = 1f / filter.gamma
        val warmR = 1f + warmth * 0.20f
        val warmB = 1f - warmth * 0.20f
        val bMul = 1f + brightness * 0.55f
        val cFac = 1f + contrast * 0.9f
        val gains = floatArrayOf(warmR, 1f, warmB)
        for (c in 0 until 3) {
            for (v in 0 until 256) {
                var x = v / 255f
                x = (x + filter.bias[c] / 255f).coerceIn(0f, 1f)
                if (filter.gamma != 1f) x = Math.pow(x.toDouble(), gammaFix.toDouble()).toFloat()
                if (filter.lift > 0f) x = filter.lift + x * (1f - filter.lift)
                // 亮度
                x *= bMul
                // 对比度
                x = (x - 0.5f) * cFac + 0.5f
                // 色温
                x *= gains[c]
                x = x.coerceIn(0f, 1f)
                lut[c * 256 + v] = (x * 255f + 0.5f).toInt()
            }
        }
        return lut
    }

    /** 一次性完成：滤镜矩阵 + 调色 + 饱和度 + 暗角 */
    fun applyColor(
        src: Bitmap,
        filter: FilterPreset,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float,
        vignette: Float
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = src.pixels()
        val lut = buildLut(filter, brightness, contrast, warmth)
        val m = filter.matrix
        val sat = (1f + saturation) * filter.sat
        val cx = w / 2f
        val cy = h / 2f
        val invMax = 1f / (cx * cx + cy * cy)
        parallelFor(h) { y ->
            val ny = (y - cy)
            val ny2 = ny * ny
            var i = y * w
            for (x in 0 until w) {
                val p = px[i]
                var r = ((p shr 16) and 0xFF).toFloat()
                var g = ((p shr 8) and 0xFF).toFloat()
                var b = (p and 0xFF).toFloat()

                if (m[0] != 1f || m[4] != 1f || m[8] != 1f || m[1] != 0f || m[3] != 0f) {
                    val nr = r * m[0] + g * m[1] + b * m[2]
                    val ng = r * m[3] + g * m[4] + b * m[5]
                    val nb = r * m[6] + g * m[7] + b * m[8]
                    r = nr; g = ng; b = nb
                }

                var ri = r.toInt().coerceIn(0, 255)
                var gi = g.toInt().coerceIn(0, 255)
                var bi = b.toInt().coerceIn(0, 255)
                r = lut[ri].toFloat()
                g = lut[256 + gi].toFloat()
                b = lut[512 + bi].toFloat()

                if (sat != 1f) {
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    r = lum + (r - lum) * sat
                    g = lum + (g - lum) * sat
                    b = lum + (b - lum) * sat
                }

                if (vignette > 0.001f) {
                    val nx = (x - cx)
                    val d2 = (nx * nx + ny2) * invMax
                    var f = 1f - vignette * 0.95f * ((d2 - 0.35f) / 0.9f)
                    if (f < 0.15f) f = 0.15f
                    if (f < 1f) { r *= f; g *= f; b *= f }
                }

                px[i] = (p and 0xFF000000.toInt()) or
                        (clamp(r).toInt() shl 16) or
                        (clamp(g).toInt() shl 8) or
                        clamp(b).toInt()
                i++
            }
        }
        return px.toBitmap(w, h)
    }

    /** 锐化：亮度通道 USM */
    fun sharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return src
        val w = src.width
        val h = src.height
        val px = src.pixels()
        val luma = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            luma[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val out = IntArray(w * h)
        val k = amount * 1.6f
        parallelFor(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { out[i] = p; continue }
                val c = luma[i]
                val lap = 4f * c - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                val d = lap * k * 0.25f
                var r = ((p shr 16) and 0xFF) + d
                var g = ((p shr 8) and 0xFF) + d
                var b = (p and 0xFF) + d
                out[i] = (p and 0xFF000000.toInt()) or
                        (clamp(r).toInt() shl 16) or (clamp(g).toInt() shl 8) or clamp(b).toInt()
            }
        }
        return out.toBitmap(w, h)
    }

    /** 三次分离式均值模糊 = 高斯近似 */
    fun blur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        if (radius < 1) return src
        val w = src.width
        val h = src.height
        var px = src.pixels()
        repeat(passes) {
            px = boxH(px, w, h, radius)
            px = boxV(px, w, h, radius)
        }
        return px.toBitmap(w, h)
    }

    private fun boxH(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val div = 2 * r + 1
        parallelFor(h) { y ->
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            val base = y * w
            for (i in -r..r) {
                val p = px[base + i.coerceIn(0, w - 1)]
                sa += (p ushr 24) and 0xFF
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                out[base + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = px[base + (x + r + 1).coerceAtMost(w - 1)]
                val sub = px[base + (x - r).coerceAtLeast(0)]
                sa += ((add ushr 24) and 0xFF) - ((sub ushr 24) and 0xFF)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    private fun boxV(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val div = 2 * r + 1
        val cols = w
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val chunk = (cols + cores - 1) / cores
        val tasks = ArrayList<Callable<Void>>(cores)
        for (ci in 0 until cores) {
            val xs = ci * chunk
            val xe = minOf(cols, xs + chunk)
            if (xs >= xe) continue
            tasks.add(Callable {
                for (x in xs until xe) {
                    var sa = 0; var sr = 0; var sg = 0; var sb = 0
                    for (i in -r..r) {
                        val p = px[i.coerceIn(0, h - 1) * w + x]
                        sa += (p ushr 24) and 0xFF
                        sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                    }
                    for (y in 0 until h) {
                        out[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                        val add = px[(y + r + 1).coerceAtMost(h - 1) * w + x]
                        val sub = px[(y - r).coerceAtLeast(0) * w + x]
                        sa += ((add ushr 24) and 0xFF) - ((sub ushr 24) and 0xFF)
                        sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                        sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                        sb += (add and 0xFF) - (sub and 0xFF)
                    }
                }
                null
            })
        }
        pool.invokeAll(tasks)
        return out
    }

    /** 智能增强：自动色阶 + 自动白平衡 + 局部对比 + 轻锐化 */
    fun autoEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = src.pixels()
        val hist = IntArray(256)
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var count = 0
        val step = if (w * h > 1_200_000) 3 else 1
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[((0.299f * r + 0.587f * g + 0.114f * b).toInt()).coerceIn(0, 255)]++
            histR[r]++; histG[g]++; histB[b]++
            sumR += r; sumG += g; sumB += b
            count++
            i += step
        }
        fun percentile(target: Int): Int {
            var acc = 0
            for (v in 0 until 256) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }
        val total = count
        val low = percentile((total * 0.004f).toInt().coerceAtLeast(1))
        val high = percentile((total * 0.996f).toInt().coerceAtMost(total))
        val maxV = if (high - low < 12) low + 12 else high
        val avgR = sumR.toFloat() / total
        val avgG = sumG.toFloat() / total
        val avgB = sumB.toFloat() / total
        val gray = (avgR + avgG + avgB) / 3f
        val wbR = (gray / avgR.coerceAtLeast(1f)).coerceIn(0.9f, 1.1f)
        val wbG = (gray / avgG.coerceAtLeast(1f)).coerceIn(0.9f, 1.1f)
        val wbB = (gray / avgB.coerceAtLeast(1f)).coerceIn(0.9f, 1.1f)

        val lut = IntArray(768)
        for (c in 0 until 3) {
            val wb = when (c) { 0 -> wbR; 1 -> wbG; else -> wbB }
            for (v in 0 until 256) {
                var x = ((v - low).toFloat() / (maxV - low)).coerceIn(0f, 1f)
                x = Math.pow(x.toDouble(), 0.96).toFloat()
                x = (x * x * (3 - 2 * x)) * 0.35f + x * 0.65f   // 轻 S 曲线
                x = (x * wb).coerceIn(0f, 1f)
                lut[c * 256 + v] = (x * 255f + 0.5f).toInt()
            }
        }
        val out = IntArray(px.size)
        parallelFor(h) { y ->
            var idx = y * w
            for (x in 0 until w) {
                val p = px[idx]
                val r = ((p shr 16) and 0xFF).let { lut[it] }
                val g = ((p shr 8) and 0xFF).let { lut[256 + it] }
                val b = (p and 0xFF).let { lut[512 + it] }
                // 轻微提饱和
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val rr = clamp(lum + (r - lum) * 1.08f).toInt()
                val gg = clamp(lum + (g - lum) * 1.08f).toInt()
                val bb = clamp(lum + (b - lum) * 1.08f).toInt()
                out[idx] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
                idx++
            }
        }
        return sharpen(out.toBitmap(w, h), 0.35f)
    }
}
