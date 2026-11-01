package com.yixiu.photo.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.PointF
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.yixiu.photo.image.ImageOps.parallelFor
import com.yixiu.photo.image.ImageOps.pixels
import com.yixiu.photo.image.ImageOps.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class FaceInfo(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val roll: Float,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val mouth: PointF?
) {
    val left get() = cx - w / 2f
    val top get() = cy - h / 2f
    val right get() = cx + w / 2f
    val bottom get() = cy + h / 2f
    val area get() = w * h
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    addOnCanceledListener { cont.cancel() }
}

object AiEngine {

    private var maskCacheKey: String? = null
    private var maskCache: Bitmap? = null

    fun invalidateMask() {
        maskCacheKey = null
        maskCache = null
    }

    // ---------------- 人脸检测 ----------------

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        )
    }

    suspend fun detectFaces(bitmap: Bitmap): List<FaceInfo> = withContext(Dispatchers.Default) {
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image).awaitTask().map { f ->
                val lm = f.allLandmarks
                FaceInfo(
                    cx = f.boundingBox.centerX().toFloat(),
                    cy = f.boundingBox.centerY().toFloat(),
                    w = f.boundingBox.width().toFloat(),
                    h = f.boundingBox.height().toFloat(),
                    roll = f.headEulerAngleZ,
                    leftEye = lm.firstOrNull { it.landmarkType == com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE }?.position,
                    rightEye = lm.firstOrNull { it.landmarkType == com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE }?.position,
                    mouth = lm.firstOrNull { it.landmarkType == com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM }?.position
                )
            }.sortedByDescending { it.area }
        }.getOrDefault(emptyList())
    }

    // ---------------- 人物分割 ----------------

    private var segmenter: ImageSegmenter? = null
    private var segmenterFailed = false

    private fun ensureSegmenter(context: Context): ImageSegmenter? {
        if (segmenterFailed) return null
        segmenter?.let { return it }
        return runCatching {
            val options = ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_multiclass.tflite").build())
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .setRunningMode(RunningMode.IMAGE)
                .build()
            ImageSegmenter.createFromOptions(context, options).also { segmenter = it }
        }.onFailure { segmenterFailed = true }.getOrNull()
    }

    /** 返回与输入同尺寸的 alpha 蒙版：白色=人物，黑色=背景 */
    suspend fun personMask(context: Context, src: Bitmap, cacheKey: String? = null): Bitmap? =
        withContext(Dispatchers.Default) {
            if (cacheKey != null && cacheKey == maskCacheKey && maskCache != null) {
                return@withContext maskCache
            }
            val seg = runCatching { ensureSegmenter(context) }.onFailure { Log.e("YixiuSeg", "ensure failed", it) }.getOrNull()
            if (seg == null) { Log.e("YixiuSeg", "segmenter null"); return@withContext null }
            val input = BitmapUtils.scaleDown(src, 512)
            val result = runCatching {
                val mpImage = BitmapImageBuilder(input).build()
                seg.segment(mpImage)
            }.onFailure { Log.e("YixiuSeg", "segment failed", it) }.getOrNull()
            if (result == null) { Log.e("YixiuSeg", "segment result null"); return@withContext null }

            val catImage = result.categoryMask().orElse(null) ?: return@withContext null
            val mw = catImage.width
            val mh = catImage.height
            val buf = runCatching { ByteBufferExtractor.extract(catImage) }
                .onFailure { Log.e("YixiuSeg", "extract failed", it) }.getOrNull()
            if (buf == null) { Log.e("YixiuSeg", "catBuffer null"); return@withContext null }

            // 类别：0 背景 / 1 头发 / 2 身体皮肤 / 3 面部皮肤 / 4 衣服 / 5 配饰
            val px = IntArray(mw * mh)
            for (i in 0 until mw * mh) {
                val cat = buf.get(i).toInt() and 0xFF
                val a = if (cat == 0) 0 else 255
                px[i] = a shl 24 or 0x00FFFFFF
            }
            val small = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            small.setPixels(px, 0, mw, 0, 0, mw, mh)
            val scaled = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            val soft = ImageOps.blur(scaled, (minOf(src.width, src.height) / 260).coerceAtLeast(1), 2)
            if (cacheKey != null) {
                maskCacheKey = cacheKey
                maskCache = soft
            }
            soft
        }

    // ---------------- 美颜 ----------------

    fun beauty(
        src: Bitmap,
        faces: List<FaceInfo>,
        smooth: Float,
        whiten: Float,
        blemish: Float,
        slim: Float
    ): Bitmap {
        if (faces.isEmpty()) return src
        var work = if (slim > 0.01f) slimFace(src, faces, slim) else src
        val w = work.width
        val h = work.height
        val mask = buildFaceMask(faces, w, h)
        if (smooth < 0.01f && whiten < 0.01f && blemish < 0.01f) return work

        val px = work.pixels()
        val out = px.copyOf()

        if (smooth > 0.01f) {
            val radius = ((minOf(w, h) / 240f) * (0.6f + smooth)).toInt().coerceIn(1, 8)
            val blurred = ImageOps.blur(work, radius, 2).pixels()
            val amt = smooth * 0.9f
            parallelFor(h) { y ->
                var i = y * w
                for (x in 0 until w) {
                    val m = mask[i]
                    if (m > 0.01f) {
                        val p = px[i]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val bp = blurred[i]
                        val br = (bp shr 16) and 0xFF
                        val bg = (bp shr 8) and 0xFF
                        val bb = bp and 0xFF
                        val t = (amt * m).coerceIn(0f, 1f)
                        // 保留少量皮肤纹理，避免塑料感
                        val nr = (r + (br - r) * t + (r - br) * 0.12f * (1 - t)).toInt().coerceIn(0, 255)
                        val ng = (g + (bg - g) * t + (g - bg) * 0.12f * (1 - t)).toInt().coerceIn(0, 255)
                        val nb = (b + (bb - b) * t + (b - bb) * 0.12f * (1 - t)).toInt().coerceIn(0, 255)
                        out[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                    }
                    i++
                }
            }
        }

        if (blemish > 0.01f) {
            val med = medianPass(out, w, h)
            val amt = blemish * 0.85f
            parallelFor(h) { y ->
                var i = y * w
                for (x in 0 until w) {
                    val m = mask[i]
                    if (m > 0.05f) {
                        val p = out[i]
                        val mp = med[i]
                        val t = (amt * m).coerceIn(0f, 1f)
                        val r = ((p shr 16) and 0xFF) + (((mp shr 16) and 0xFF) - ((p shr 16) and 0xFF)) * t
                        val g = ((p shr 8) and 0xFF) + (((mp shr 8) and 0xFF) - ((p shr 8) and 0xFF)) * t
                        val b = (p and 0xFF) + ((mp and 0xFF) - (p and 0xFF)) * t
                        out[i] = (p and 0xFF000000.toInt()) or
                                (r.toInt().coerceIn(0, 255) shl 16) or
                                (g.toInt().coerceIn(0, 255) shl 8) or
                                b.toInt().coerceIn(0, 255)
                    }
                    i++
                }
            }
        }

        if (whiten > 0.01f) {
            val amt = whiten * 0.30f
            parallelFor(h) { y ->
                var i = y * w
                for (x in 0 until w) {
                    val m = mask[i]
                    if (m > 0.01f) {
                        val p = out[i]
                        var r = ((p shr 16) and 0xFF).toFloat()
                        var g = ((p shr 8) and 0xFF).toFloat()
                        var b = (p and 0xFF).toFloat()
                        val t = (amt * m).coerceIn(0f, 0.32f)
                        r += (255f - r) * t * 0.92f
                        g += (255f - g) * t
                        b += (255f - b) * t
                        // 去一点黄气
                        r *= (1f - t * 0.02f)
                        out[i] = (p and 0xFF000000.toInt()) or
                                (r.toInt().coerceIn(0, 255) shl 16) or
                                (g.toInt().coerceIn(0, 255) shl 8) or
                                b.toInt().coerceIn(0, 255)
                    }
                    i++
                }
            }
        }
        return out.toBitmap(w, h)
    }

    private fun buildFaceMask(faces: List<FaceInfo>, w: Int, h: Int): FloatArray {
        val mask = FloatArray(w * h)
        for (f in faces) {
            val rx = f.w * 0.66f
            val ry = f.h * 0.80f
            val cy = f.cy + f.h * 0.04f
            val rad = Math.toRadians(f.roll.toDouble())
            val cosA = Math.cos(rad).toFloat()
            val sinA = Math.sin(rad).toFloat()
            val x0 = (f.cx - rx * 1.3f).toInt().coerceIn(0, w - 1)
            val x1 = (f.cx + rx * 1.3f).toInt().coerceIn(0, w - 1)
            val y0 = (cy - ry * 1.3f).toInt().coerceIn(0, h - 1)
            val y1 = (cy + ry * 1.3f).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) {
                val dy = y - cy
                val rowBase = y * w
                for (x in x0..x1) {
                    val dx = x - f.cx
                    val u = (dx * cosA + dy * sinA) / rx
                    val v = (-dx * sinA + dy * cosA) / ry
                    val d = u * u + v * v
                    if (d < 1.35f) {
                        var a = ((1.2f - d) / 0.55f).coerceIn(0f, 1f)
                        a = a * a * (3 - 2 * a)
                        if (a > mask[rowBase + x]) mask[rowBase + x] = a
                    }
                }
            }
        }
        return mask
    }

    private fun medianPass(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        parallelFor(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { out[i] = px[i]; continue }
                var r0 = Int.MAX_VALUE; var g0 = Int.MAX_VALUE; var b0 = Int.MAX_VALUE
                var r1 = Int.MIN_VALUE; var g1 = Int.MIN_VALUE; var b1 = Int.MIN_VALUE
                for (dy in -1..1) for (dx in -1..1) {
                    val p = px[i + dy * w + dx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (r < r0) r0 = r; if (r > r1) r1 = r
                    if (g < g0) g0 = g; if (g > g1) g1 = g
                    if (b < b0) b0 = b; if (b > b1) b1 = b
                }
                // 取中值附近的平均，比纯中值更自然
                val r = (r0 + r1) / 2
                val g = (g0 + g1) / 2
                val b = (b0 + b1) / 2
                out[i] = (px[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun slimFace(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = src.pixels()
        val out = px.copyOf()
        val k = strength * 0.16f
        for (f in faces) {
            val rx = f.w * 0.5f
            val ry = f.h * 0.55f
            val x0 = (f.cx - rx * 1.4f).toInt().coerceIn(0, w - 1)
            val x1 = (f.cx + rx * 1.4f).toInt().coerceIn(0, w - 1)
            val y0 = (f.cy - ry).toInt().coerceIn(0, h - 1)
            val y1 = (f.cy + ry * 1.15f).toInt().coerceIn(0, h - 1)
            for (y in y0..y1) {
                val dy = ((y - f.cy) / ry)
                val vfall = (1f - dy * dy).coerceIn(0f, 1f)
                if (vfall <= 0.01f) continue
                for (x in x0..x1) {
                    val dx = (x - f.cx) / rx
                    val hfall = (1f - dx * dx * 0.7f).coerceIn(0f, 1f)
                    val fall = vfall * hfall
                    if (fall <= 0.01f) continue
                    val sx = f.cx + (x - f.cx) / (1f - k * fall)
                    val sy = y.toFloat()
                    out[y * w + x] = sampleBilinear(px, w, h, sx, sy)
                }
            }
        }
        return out.toBitmap(w, h)
    }

    private fun sampleBilinear(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val p00 = px[y0 * w + x0]; val p10 = px[y0 * w + x1]
        val p01 = px[y1 * w + x0]; val p11 = px[y1 * w + x1]
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        fun mix(s: Int): Int {
            val a = ch(p00, s) * (1 - tx) + ch(p10, s) * tx
            val b = ch(p01, s) * (1 - tx) + ch(p11, s) * tx
            return (a * (1 - ty) + b * ty).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // ---------------- 换脸：把自拍人脸贴合到海报人脸 ----------------

    fun faceswap(poster: Bitmap, source: Bitmap, targetFace: FaceInfo, srcFace: FaceInfo): Bitmap {
        val pw = poster.width
        val ph = poster.height

        // 用检测框对齐（海报/插画上关键点不稳），旋转用眼角
        val srcEyeAngle = srcFace.leftEye?.let { l -> srcFace.rightEye?.let { r -> angle(l, r) } } ?: 0f
        val tgtEyeAngle = targetFace.leftEye?.let { l -> targetFace.rightEye?.let { r -> angle(l, r) } } ?: targetFace.roll
        val scale = (targetFace.h / srcFace.h.coerceAtLeast(1f)).coerceIn(0.3f, 3f)
        val rot = tgtEyeAngle - srcEyeAngle

        val srcCx = srcFace.cx
        val srcCy = srcFace.cy
        val tgtCx = targetFace.cx
        val tgtCy = targetFace.cy

        // 取源图人脸区域
        val marginX = srcFace.w * 0.62f
        val marginTop = srcFace.h * 0.72f
        val marginBottom = srcFace.h * 0.62f
        val cropL = (srcCx - marginX).toInt().coerceIn(0, source.width - 1)
        val cropT = (srcCy - marginTop).toInt().coerceIn(0, source.height - 1)
        val cropR = (srcCx + marginX).toInt().coerceIn(cropL + 1, source.width)
        val cropB = (srcCy + marginBottom).toInt().coerceIn(cropT + 1, source.height)
        val patch = Bitmap.createBitmap(source, cropL, cropT, cropR - cropL, cropT.let { cropB - cropT })

        val matrix = Matrix().apply {
            // patch 局部坐标 -> 源图坐标 -> 以人脸中心为原点 -> 缩放旋转 -> 贴到目标脸中心
            postTranslate(cropL - srcCx, cropT - srcCy)
            postScale(scale, scale)
            postRotate(rot, 0f, 0f)
            postTranslate(tgtCx, tgtCy)
        }

        // 肤色匹配
        val srcMean = meanColor(source, srcFace)
        val tgtMean = meanColor(poster, targetFace)
        val gains = FloatArray(3) { c ->
            if (srcMean[c] < 4f) 1f else (tgtMean[c] / srcMean[c]).coerceIn(0.82f, 1.18f)
        }
        val cm = ColorMatrix(
            floatArrayOf(
                gains[0], 0f, 0f, 0f, 0f,
                0f, gains[1], 0f, 0f, 0f,
                0f, 0f, gains[2], 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val layer = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        android.util.Log.i("YixiuFace", "matrix=$matrix values=${FloatArray(9).also { matrix.getValues(it) }.contentToString()}")
        lc.drawBitmap(patch, matrix, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
            isAntiAlias = true
        })

        // 紧贴目标脸的椭圆羽化蒙版
        val mask = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        val ecx = tgtCx
        val ecy = tgtCy + targetFace.h * 0.04f
        val rx = targetFace.w * 0.56f
        val ry = targetFace.h * 0.62f
        val mp = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            maskFilter = BlurMaskFilter(maxOf(rx, ry) * 0.13f, BlurMaskFilter.Blur.NORMAL)
        }
        mc.save()
        mc.rotate(targetFace.roll, ecx, ecy)
        mc.drawOval(RectF(ecx - rx, ecy - ry, ecx + rx, ecy + ry * 0.98f), mp)
        mc.restore()

        lc.drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })

        val out = poster.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(layer, 0f, 0f, null)
        return out
    }

    private fun angle(l: PointF, r: PointF) =
        Math.toDegrees(Math.atan2((r.y - l.y).toDouble(), (r.x - l.x).toDouble())).toFloat()

    private fun meanColor(bmp: Bitmap, face: FaceInfo): FloatArray {
        val cx = face.cx.toInt().coerceIn(0, bmp.width - 1)
        val cy = face.cy.toInt().coerceIn(0, bmp.height - 1)
        val hw = (face.w * 0.28f).toInt().coerceAtLeast(2)
        val hh = (face.h * 0.28f).toInt().coerceAtLeast(2)
        val x0 = (cx - hw).coerceAtLeast(0)
        val y0 = (cy - hh).coerceAtLeast(0)
        val x1 = (cx + hw).coerceAtMost(bmp.width)
        val y1 = (cy + hh).coerceAtMost(bmp.height)
        if (x1 <= x0 || y1 <= y0) return floatArrayOf(128f, 128f, 128f)
        val region = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val px = region.pixels()
        var r = 0L; var g = 0L; var b = 0L
        for (p in px) {
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        val n = px.size.coerceAtLeast(1)
        return floatArrayOf(r.toFloat() / n, g.toFloat() / n, b.toFloat() / n)
    }

    // ---------------- 背景替换 ----------------

    fun compositeBackground(
        src: Bitmap,
        mask: Bitmap,
        mode: String,
        colorHex: Int,
        bgImage: Bitmap?,
        blurAmount: Float
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = src.pixels()
        val mpx = mask.pixels()
        val bg: IntArray = when (mode) {
            "blur" -> {
                val r = ((minOf(w, h) / 90f) * (0.4f + blurAmount)).toInt().coerceIn(2, 30)
                ImageOps.blur(src, r, 3).pixels()
            }
            "image" -> {
                if (bgImage == null) {
                    IntArray(w * h) { 0xFF000000.toInt() }
                } else {
                    val scaled = fillCrop(bgImage, w, h)
                    scaled.pixels().also { if (scaled !== bgImage) scaled.recycle() }
                }
            }
            "transparent" -> IntArray(w * h) { 0 }
            else -> IntArray(w * h) { colorHex }
        }
        val out = IntArray(w * h)
        parallelFor(h) { y ->
            var i = y * w
            for (x in 0 until w) {
                val a = (mpx[i] ushr 24) and 0xFF
                val p = px[i]
                if (a >= 254) {
                    out[i] = p
                } else {
                    val bgp = bg[i]
                    val inv = 255 - a
                    val r = (((p shr 16) and 0xFF) * a + ((bgp shr 16) and 0xFF) * inv) / 255
                    val g = (((p shr 8) and 0xFF) * a + ((bgp shr 8) and 0xFF) * inv) / 255
                    val b = ((p and 0xFF) * a + (bgp and 0xFF) * inv) / 255
                    val alpha = if (mode == "transparent") a else 255
                    out[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                }
                i++
            }
        }
        return out.toBitmap(w, h)
    }

    fun fillCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (out !== scaled) scaled.recycle()
        return out
    }
}
