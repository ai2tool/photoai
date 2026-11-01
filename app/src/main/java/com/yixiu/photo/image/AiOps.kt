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
import com.yixiu.photo.BuildConfig
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class FaceInfo(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val roll: Float,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val mouth: PointF?,
    val nose: PointF? = null
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
                    mouth = lm.firstOrNull { it.landmarkType == com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM }?.position,
                    nose = lm.firstOrNull { it.landmarkType == com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE }?.position
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

    /**
     * 「面部皮肤」蒙版，尺寸为分割模型的输出尺寸（约 256 见方），需由调用方按比例换算。
     * 白色（alpha=255）= 脸（含眉眼唇），透明 = 头发/背景/衣服。
     * 分割没认出脸时返回 null，调用方退回几何蒙版。
     */
    suspend fun faceSkinMask(context: Context, src: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val seg = runCatching { ensureSegmenter(context) }.getOrNull() ?: return@withContext null
        val input = BitmapUtils.scaleDown(src, 512)
        val result = runCatching { seg.segment(BitmapImageBuilder(input).build()) }.getOrNull()
            ?: return@withContext null
        val catImage = result.categoryMask().orElse(null) ?: return@withContext null
        val mw = catImage.width
        val mh = catImage.height
        val buf = runCatching { ByteBufferExtractor.extract(catImage) }.getOrNull()
            ?: return@withContext null
        // 类别：0 背景 / 1 头发 / 2 身体皮肤 / 3 面部皮肤 / 4 衣服 / 5 配饰
        val px = IntArray(mw * mh)
        var face = 0
        for (i in 0 until mw * mh) {
            val cat = buf.get(i).toInt() and 0xFF
            val a = if (cat == 3) 255 else 0
            if (cat == 3) face++
            px[i] = (a shl 24) or 0x00FFFFFF
        }
        // 太小说明没认出脸，太大说明整张图被判成皮肤，都不适合当蒙版
        if (face < 96 || face > mw * mh * 0.82f) {
            if (BuildConfig.DEBUG) Log.d("YixiuFace", "skinMask rejected face=$face of ${mw * mh}")
            return@withContext null
        }
        if (BuildConfig.DEBUG) Log.d("YixiuFace", "skinMask ok ${mw}x$mh face=$face")
        val small = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        small.setPixels(px, 0, mw, 0, 0, mw, mh)
        small
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

    /**
     * 把 [source] 里的人脸换到 [poster] 的 [targetFace] 上。
     *
     * 「融不进去」通常来自四件事，这里逐个处理：
     * 1) 位置和大小：用双眼连线对齐（眼距定缩放、眼角定旋转），比检测框稳得多——
     *    检测框包含头发、且会随头部角度明显变化，用它算缩放很容易把脸放大或缩小一截；
     * 2) 范围：用分割模型取「面部皮肤」区域当蒙版，只贴脸，不会把源图的碎发、耳朵、背景一起糊上来；
     * 3) 颜色：按均值 + 标准差做一次温和的肤色匹配，兜住整体色偏；
     * 4) 边缘：合成时做低频对齐——把源脸的低频明暗换成目标位置的低频，只保留五官细节（高频），
     *    再乘一层平滑过渡的软椭圆。椭圆边缘两侧的明暗本来就连着，看不出贴片。
     */
    suspend fun faceswap(
        context: Context,
        poster: Bitmap,
        source: Bitmap,
        targetFace: FaceInfo,
        srcFace: FaceInfo
    ): Bitmap = withContext(Dispatchers.Default) {
        val pw = poster.width
        val ph = poster.height

        val sL = srcFace.leftEye
        val sR = srcFace.rightEye
        val tL = targetFace.leftEye
        val tR = targetFace.rightEye
        val useEyes = sL != null && sR != null && tL != null && tR != null

        // 锚点：两眼连线中点；没有关键点时退到检测框中心偏上（大致是眼睛所在高度）
        val srcAnchor = if (useEyes) mid(sL!!, sR!!) else PointF(srcFace.cx, srcFace.cy - srcFace.h * 0.10f)
        val tgtAnchor = if (useEyes) mid(tL!!, tR!!) else PointF(targetFace.cx, targetFace.cy - targetFace.h * 0.10f)
        val srcSpan = if (useEyes) dist(sL!!, sR!!) else srcFace.w * 0.42f
        val tgtSpan = if (useEyes) dist(tL!!, tR!!) else targetFace.w * 0.42f
        val scale = (tgtSpan / srcSpan.coerceAtLeast(1f)).coerceIn(0.25f, 3.2f)
        val rot = (if (useEyes) angle(tL!!, tR!!) else targetFace.roll) -
            (if (useEyes) angle(sL!!, sR!!) else 0f)

        // 源图裁剪：以眼为中心，往下多留（下巴），往上少留（避开刘海和背景）
        val cropL = (srcAnchor.x - srcFace.w * 0.80f).toInt().coerceIn(0, source.width - 2)
        val cropT = (srcAnchor.y - srcFace.h * 0.58f).toInt().coerceIn(0, source.height - 2)
        val cropR = (srcAnchor.x + srcFace.w * 0.80f).toInt().coerceIn(cropL + 1, source.width)
        val cropB = (srcAnchor.y + srcFace.h * 0.88f).toInt().coerceIn(cropT + 1, source.height)
        val patch = Bitmap.createBitmap(source, cropL, cropT, cropR - cropL, cropB - cropT)

        // patch 局部坐标 -> 源图坐标 -> 以眼为中心 -> 缩放旋转 -> 目标眼中心
        val matrix = Matrix().apply {
            postTranslate(cropL - srcAnchor.x, cropT - srcAnchor.y)
            postScale(scale, scale)
            postRotate(rot, 0f, 0f)
            postTranslate(tgtAnchor.x, tgtAnchor.y)
        }

        // 融合椭圆：随头部倾斜一起转。故意取得比脸大一圈——真正的边界交给皮肤蒙版（也就是脸的轮廓），
        // 椭圆只做兜底，免得在脸中间画出一条弧线
        val ecx = tgtAnchor.x
        val ecy = tgtAnchor.y + targetFace.h * 0.10f
        val rx = (targetFace.w * 0.74f).coerceAtLeast(8f)
        val ry = (targetFace.h * 0.72f).coerceAtLeast(8f)
        val rollRad = Math.toRadians(-targetFace.roll.toDouble())

        // 只处理椭圆外扩后的矩形：整张海报逐像素跑太浪费
        val padX = rx * 1.3f
        val padY = ry * 1.3f
        val bx = (ecx - padX).toInt().coerceIn(0, pw - 2)
        val by = (ecy - padY).toInt().coerceIn(0, ph - 2)
        val bw = (ecx + padX).toInt().coerceIn(bx + 2, pw) - bx
        val bh = (ecy + padY).toInt().coerceIn(by + 2, ph) - by
        if (bw < 4 || bh < 4) return@withContext poster.copy(Bitmap.Config.ARGB_8888, true)

        val localMatrix = Matrix(matrix).apply { postTranslate(-bx.toFloat(), -by.toFloat()) }

        // 面部皮肤蒙版：只贴脸。源图两侧的碎发、耳朵、背景不跟着过来，否则脸边上会糊一层发丝。
        // 分割结果是 256 见方的小图，先裁出 patch 对应的区域再放大，别在整张源图的尺寸上分配内存
        val skinLayer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val skinSmall = runCatching { faceSkinMask(context, source) }.getOrNull()
        val hasSkin = skinSmall != null
        if (skinSmall != null) {
            val kx = skinSmall.width.toFloat() / source.width
            val ky = skinSmall.height.toFloat() / source.height
            val ml = (cropL * kx).toInt().coerceIn(0, skinSmall.width - 1)
            val mt = (cropT * ky).toInt().coerceIn(0, skinSmall.height - 1)
            val mr = (cropR * kx).toInt().coerceIn(ml + 1, skinSmall.width)
            val mb = (cropB * ky).toInt().coerceIn(mt + 1, skinSmall.height)
            val raw = Bitmap.createBitmap(skinSmall, ml, mt, mr - ml, mb - mt)
            val up = Bitmap.createScaledBitmap(raw, cropR - cropL, cropB - cropT, true)
            // 蒙版边缘要够宽，否则皮肤边界会变成一条硬边
            val soften = ((cropR - cropL) * 0.14f).toInt().coerceIn(1, 96)
            val softPatch = ImageOps.blur(up, soften, 2)
            Canvas(skinLayer).drawBitmap(softPatch, localMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val skinPx = skinLayer.pixels()
        if (BuildConfig.DEBUG) {
            Log.d("YixiuFace", "faceswap hasSkin=$hasSkin skin=${skinSmall?.width}x${skinSmall?.height} scale=$scale rot=$rot box=${bw}x$bh")
        }

        // 肤色匹配：均值 + 标准差，只做温和修正，剩下的交给低频对齐
        val srcStat = skinStats(patch, srcAnchor.x - cropL, srcAnchor.y - cropT, (cropR - cropL) * 0.34f, (cropB - cropT) * 0.30f)
        val tgtStat = skinStats(poster, tgtAnchor.x, tgtAnchor.y, targetFace.w * 0.34f, targetFace.h * 0.30f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(skinMatrix(srcStat, tgtStat))
            isAntiAlias = true
            isFilterBitmap = true
        }

        // 低频半径按目标尺度算，源图那侧按 scale 折算，保证两边频率一致。
        // 半径要够大才能把目标的光照（侧光、阴影）抓进低频，否则脸会显得又平又亮
        val radiusTgt = (maxOf(rx, ry) * 0.26f).toInt().coerceIn(4, 140)
        val radiusSrc = (radiusTgt / scale).toInt().coerceIn(1, 140)
        val lowPatch = ImageOps.blur(patch, radiusSrc, 2)

        val layer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(patch, localMatrix, paint)
        val lowLayer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(lowLayer).drawBitmap(lowPatch, localMatrix, paint)

        val posterRegion = Bitmap.createBitmap(poster, bx, by, bw, bh)
        val posterLow = ImageOps.blur(posterRegion, radiusTgt, 2)

        val base = posterRegion.pixels()
        val src = layer.pixels()
        val srcLow = lowLayer.pixels()
        val dstLow = posterLow.pixels()
        val out = base.copyOf()

        // 融合蒙版：整张脸（避开额头）+ 皮肤轮廓。
        // 只换五官会变成一块块"补丁"，因为源图的皮肤纹理和目标不连续；整脸换过去再靠低频对齐
        // 把明暗接上，过渡反而更整。额头那一段单独淡出——源图脸上的碎发会跟着过来，而半透明
        // 的细发丝跟皮肤亮度几乎一样，没法靠判据区分，索性让额头保持目标原样。
        fun ellipseAlpha(px: Float, py: Float): Float {
            val ox = px - ecx
            val oy = py - ecy
            val nx = (ox * cos(rollRad) - oy * sin(rollRad)) / rx
            val ny = (ox * sin(rollRad) + oy * cos(rollRad)) / ry
            val d = sqrt((nx * nx + ny * ny).toDouble()).toFloat()
            if (d >= 1f) return 0f
            val t = ((1f - d) / 0.30f).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        parallelFor(bh) { y ->
            val gy = y + by
            for (x in 0 until bw) {
                val i = y * bw + x
                val s = src[i]
                val sa = (s ushr 24) and 0xFF
                if (sa < 6) continue // patch 没覆盖到这里

                // 皮肤蒙版：低于一半的一律不贴——半透明叠加会把源图的碎发变成一层重影糊在脸上
                val skinRaw = if (hasSkin) (((skinPx[i] ushr 24) and 0xFF) / 255f) else 1f
                if (skinRaw < 0.42f) continue
                val skinW = ((skinRaw - 0.42f) / 0.58f).coerceIn(0f, 1f)

                val ell = ellipseAlpha((x + bx).toFloat(), gy.toFloat())
                if (ell < 0.02f) continue

                // 眼睛以上（额头）淡出到 0，碎发最密的地方直接保留目标原样
                val relY = if (targetFace.h > 1f) (gy - tgtAnchor.y) / targetFace.h else 0f
                val topFade = ((relY + 0.28f) / 0.14f).coerceIn(0f, 1f)

                // 低频对齐：源高频 + 目标低频（半透明边缘不参与，避免模糊把边缘拉暗）
                val low = if (sa >= 250) srcLow[i] else s

                val a = ell * skinW * topFade

                val b = base[i]
                // 源的高频只取八成：五官细节还在，但不会盖掉目标图的皮肤质感和光斑，减少"塑料脸"
                val adjR = ((dstLow[i] shr 16) and 0xFF) + (((s shr 16) and 0xFF) - ((low shr 16) and 0xFF)) * 0.82f
                val adjG = ((dstLow[i] shr 8) and 0xFF) + (((s shr 8) and 0xFF) - ((low shr 8) and 0xFF)) * 0.82f
                val adjB = (dstLow[i] and 0xFF) + ((s and 0xFF) - (low and 0xFF)) * 0.82f

                val r = mixChannel((b shr 16) and 0xFF, adjR, a) shl 16
                val g = mixChannel((b shr 8) and 0xFF, adjG, a) shl 8
                val bl = mixChannel(b and 0xFF, adjB, a)
                out[i] = (0xFF shl 24) or r or g or bl
            }
        }

        val out2 = poster.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out2).drawBitmap(out.toBitmap(bw, bh), bx.toFloat(), by.toFloat(), null)
        out2
    }

    private fun mixChannel(dst: Int, src: Float, a: Float): Int {
        val v = dst * (1f - a) + src.coerceIn(0f, 255f) * a
        return if (v <= 0f) 0 else if (v >= 255f) 255 else v.toInt()
    }

    private fun angle(l: PointF, r: PointF) =
        Math.toDegrees(Math.atan2((r.y - l.y).toDouble(), (r.x - l.x).toDouble())).toFloat()

    private fun mid(l: PointF, r: PointF) = PointF((l.x + r.x) / 2f, (l.y + r.y) / 2f)

    private fun dist(l: PointF, r: PointF): Float {
        val dx = r.x - l.x
        val dy = r.y - l.y
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * 脸部皮肤区的均值与标准差：`[r, g, b, stdR, stdG, stdB]`。
     * 只取椭圆中心 85% 范围，避开头发、背景和边缘过渡。
     */
    private fun skinStats(bmp: Bitmap, cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val ww = (rx * 2f).toInt().coerceAtLeast(2)
        val hh = (ry * 2f).toInt().coerceAtLeast(2)
        val x0 = (cx - rx).toInt().coerceIn(0, (bmp.width - ww).coerceAtLeast(0))
        val y0 = (cy - ry).toInt().coerceIn(0, (bmp.height - hh).coerceAtLeast(0))
        val w = ww.coerceAtMost(bmp.width - x0).coerceAtLeast(1)
        val h = hh.coerceAtMost(bmp.height - y0).coerceAtLeast(1)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)

        var n = 0
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var qr = 0.0
        var qg = 0.0
        var qb = 0.0
        val hx = w / 2f
        val hy = h / 2f
        for (y in 0 until h) {
            val ny = (y - hy) / hy
            for (x in 0 until w) {
                val nx = (x - hx) / hx
                if (nx * nx + ny * ny > 0.85f) continue
                val p = px[y * w + x]
                val r = ((p shr 16) and 0xFF).toDouble()
                val g = ((p shr 8) and 0xFF).toDouble()
                val b = (p and 0xFF).toDouble()
                sr += r; sg += g; sb += b
                qr += r * r; qg += g * g; qb += b * b
                n++
            }
        }
        if (n < 16) return floatArrayOf(128f, 128f, 128f, 40f, 40f, 40f)
        val mr = sr / n; val mg = sg / n; val mb = sb / n
        fun sd(q: Double, m: Double) = sqrt((q / n - m * m).coerceAtLeast(1.0)).toFloat()
        return floatArrayOf(mr.toFloat(), mg.toFloat(), mb.toFloat(), sd(qr, mr), sd(qg, mg), sd(qb, mb))
    }

    /** 按均值/标准差把源肤色温和地掰向目标肤色；幅度收着点，避免把五官染色。 */
    private fun skinMatrix(src: FloatArray, tgt: FloatArray): ColorMatrix {
        val gain = FloatArray(3) { c ->
            val s = src[c + 3].coerceAtLeast(4f)
            val t = tgt[c + 3].coerceAtLeast(4f)
            (1f + (t / s - 1f) * 0.55f).coerceIn(0.86f, 1.16f)
        }
        val offset = FloatArray(3) { c ->
            ((tgt[c] - gain[c] * src[c]) * 0.55f).coerceIn(-40f, 40f)
        }
        return ColorMatrix(
            floatArrayOf(
                gain[0], 0f, 0f, 0f, offset[0],
                0f, gain[1], 0f, 0f, offset[1],
                0f, 0f, gain[2], 0f, offset[2],
                0f, 0f, 0f, 1f, 0f
            )
        )
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
