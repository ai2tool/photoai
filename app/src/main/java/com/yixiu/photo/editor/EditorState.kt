package com.yixiu.photo.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import com.yixiu.photo.image.AiEngine
import com.yixiu.photo.image.FaceInfo
import com.yixiu.photo.image.Filters
import com.yixiu.photo.image.ImageOps
import java.util.UUID

@Immutable
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f
) {
    val isDefault: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
                warmth == 0f && sharpen == 0f && vignette == 0f
}

@Immutable
data class BeautyParams(
    val smooth: Float = 0f,
    val whiten: Float = 0f,
    val blemish: Float = 0f,
    val slim: Float = 0f
) {
    val isDefault: Boolean
        get() = smooth == 0f && whiten == 0f && blemish == 0f && slim == 0f
}

enum class BgMode(val label: String) {
    NONE("无"), TRANSPARENT("透明"), COLOR("纯色"), BLUR("虚化"), IMAGE("图片")
}

@Immutable
data class BgParams(
    val mode: BgMode = BgMode.NONE,
    val color: Int = 0xFFF5F5F7.toInt(),
    val imageKey: String? = null,
    val blur: Float = 0.6f
)

@Immutable
sealed class Overlay {
    abstract val id: String
    abstract val nx: Float
    abstract val ny: Float
    abstract val sizeRatio: Float
    abstract val rotation: Float
    abstract fun move(nx: Float, ny: Float): Overlay
    abstract fun resize(sizeRatio: Float): Overlay
}

@Immutable
data class TextOverlay(
    override val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    override val nx: Float = 0.5f,
    override val ny: Float = 0.5f,
    override val sizeRatio: Float = 0.075f,
    override val rotation: Float = 0f,
    val color: Int = 0xFFFFFFFF.toInt()
) : Overlay() {
    override fun move(nx: Float, ny: Float) = copy(nx = nx, ny = ny)
    override fun resize(sizeRatio: Float) = copy(sizeRatio = sizeRatio)
}

@Immutable
data class StickerOverlay(
    override val id: String = UUID.randomUUID().toString(),
    val emoji: String = "✨",
    override val nx: Float = 0.5f,
    override val ny: Float = 0.5f,
    override val sizeRatio: Float = 0.14f,
    override val rotation: Float = 0f
) : Overlay() {
    override fun move(nx: Float, ny: Float) = copy(nx = nx, ny = ny)
    override fun resize(sizeRatio: Float) = copy(sizeRatio = sizeRatio)
}

@Immutable
data class EditorParams(
    val adjust: Adjust = Adjust(),
    val beauty: BeautyParams = BeautyParams(),
    val bg: BgParams = BgParams(),
    val filterId: String = "none",
    val overlays: List<Overlay> = emptyList(),
    val enhanceApplied: Boolean = false
) {
    val filter get() = Filters.byId(filterId)
}

object Renderer {

    /** 完整渲染管线；previewScale 用于把预览尺寸下的人脸坐标换算到目标尺寸 */
    fun render(
        base: Bitmap,
        params: EditorParams,
        faces: List<FaceInfo>,
        mask: Bitmap?,
        bgImage: Bitmap?,
        faceScale: Float
    ): Bitmap {
        var bmp = base

        if (!params.beauty.isDefault && faces.isNotEmpty()) {
            val scaledFaces = if (faceScale == 1f) faces else faces.map { f ->
                f.copy(
                    cx = f.cx * faceScale, cy = f.cy * faceScale,
                    w = f.w * faceScale, h = f.h * faceScale
                )
            }
            bmp = AiEngine.beauty(
                bmp, scaledFaces,
                params.beauty.smooth, params.beauty.whiten,
                params.beauty.blemish, params.beauty.slim
            )
        }

        bmp = ImageOps.applyColor(
            bmp, params.filter,
            params.adjust.brightness, params.adjust.contrast,
            params.adjust.saturation, params.adjust.warmth,
            params.adjust.vignette
        )

        if (params.adjust.sharpen > 0.01f) {
            bmp = ImageOps.sharpen(bmp, params.adjust.sharpen)
        }

        if (params.bg.mode != BgMode.NONE && mask != null) {
            val m = if (mask.width == bmp.width && mask.height == bmp.height) mask
            else Bitmap.createScaledBitmap(mask, bmp.width, bmp.height, true)
            bmp = AiEngine.compositeBackground(
                bmp, m, params.bg.mode.name.lowercase(), params.bg.color, bgImage, params.bg.blur
            )
        }

        if (params.overlays.isNotEmpty()) {
            bmp = drawOverlays(bmp, params.overlays)
        }
        return bmp
    }

    fun drawOverlays(src: Bitmap, overlays: List<Overlay>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (o in overlays) {
            val cx = o.nx * out.width
            val cy = o.ny * out.height
            val size = o.sizeRatio * out.width
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(size * 0.10f, 0f, size * 0.03f, 0x55000000)
            }
            canvas.save()
            canvas.rotate(o.rotation, cx, cy)
            when (o) {
                is TextOverlay -> {
                    paint.color = o.color
                    val lines = o.text.split("\n")
                    val lh = size * 1.22f
                    val startY = cy - (lines.size - 1) * lh / 2f
                    lines.forEachIndexed { i, line ->
                        canvas.drawText(line, cx, startY + i * lh + size * 0.34f, paint)
                    }
                }
                is StickerOverlay -> {
                    paint.typeface = Typeface.DEFAULT
                    paint.clearShadowLayer()
                    canvas.drawText(o.emoji, cx, cy + size * 0.36f, paint)
                }
            }
            canvas.restore()
        }
        return out
    }
}
