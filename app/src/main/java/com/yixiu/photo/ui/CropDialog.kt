package com.yixiu.photo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import com.yixiu.photo.editor.EditorViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class CropRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
}

private data class RatioOption(val label: String, val ratio: Float?)

@Composable
fun CropDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val version = vm.baseVersion
    val bitmap = remember(version) { vm.currentBase() }
    android.util.Log.i("YixiuCrop", "compose version=$version base=${bitmap?.width}x${bitmap?.height}")
    if (bitmap == null) return

    var rect by remember(version) { mutableStateOf(CropRect(0f, 0f, 1f, 1f)) }
    val imgAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    var ratio by remember { mutableStateOf<Float?>(null) }
    var dragMode by remember { mutableStateOf(-1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF08090D))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "取消",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(interactionSource = MutableInteractionSource(), indication = null) { onDismiss() }
                        .padding(8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text("裁剪", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(Accent)
                        .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                            vm.cropTo(rect.l, rect.t, rect.r, rect.b)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("完成", fontSize = 12.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val dw = if (wPx / hPx > imgAspect) (hPx * imgAspect).toInt() else wPx.toInt()
                val dh = if (wPx / hPx > imgAspect) hPx.toInt() else (wPx / imgAspect).toInt()

                Box(
                    Modifier
                        .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                        .pointerInput(ratio, dw, dh) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragMode = pickHandle(rect, start, dw.toFloat(), dh.toFloat())
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val ndx = drag.x / dw
                                    val ndy = drag.y / dh
                                    rect = applyDrag(rect, dragMode, ndx, ndy, ratio, imgAspect)
                                },
                                onDragEnd = { dragMode = -1 },
                                onDragCancel = { dragMode = -1 }
                            )
                        }
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val l = rect.l * size.width
                        val t = rect.t * size.height
                        val r = rect.r * size.width
                        val b = rect.b * size.height
                        val dim = Color.Black.copy(alpha = 0.5f)
                        drawRect(dim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, t))
                        drawRect(dim, Offset(0f, b), androidx.compose.ui.geometry.Size(size.width, size.height - b))
                        drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l, b - t))
                        drawRect(dim, Offset(r, t), androidx.compose.ui.geometry.Size(size.width - r, b - t))

                        drawRect(
                            color = Color.White,
                            topLeft = Offset(l, t),
                            size = androidx.compose.ui.geometry.Size(r - l, b - t),
                            style = Stroke(width = 2f)
                        )
                        val g = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            val x = l + (r - l) * i / 3f
                            val y = t + (b - t) * i / 3f
                            drawLine(g, Offset(x, t), Offset(x, b), strokeWidth = 1f)
                            drawLine(g, Offset(l, y), Offset(r, y), strokeWidth = 1f)
                        }
                        // 角标
                        val arm = min(r - l, b - t) * 0.12f
                        val corners = listOf(l to t, r to t, l to b, r to b)
                        corners.forEachIndexed { idx, (cx, cy) ->
                            val sx = if (idx % 2 == 0) 1f else -1f
                            val sy = if (idx < 2) 1f else -1f
                            drawLine(Color.White, Offset(cx, cy), Offset(cx + arm * sx, cy), strokeWidth = 4f)
                            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + arm * sy), strokeWidth = 4f)
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val options = listOf(
                    RatioOption("自由", null),
                    RatioOption("1:1", 1f),
                    RatioOption("4:5", 0.8f),
                    RatioOption("3:4", 0.75f),
                    RatioOption("16:9", 16f / 9f)
                )
                options.forEach { opt ->
                    PillChip(opt.label, ratio == opt.ratio) {
                        ratio = opt.ratio
                        rect = if (opt.ratio == null) CropRect(0f, 0f, 1f, 1f) else fitRatio(opt.ratio!!, imgAspect)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GhostButton("旋转 90°", Icons.Outlined.RotateRight) {
                    vm.rotate(90f)
                    rect = CropRect(0f, 0f, 1f, 1f)
                }
                GhostButton("镜像", Icons.Outlined.Flip) {
                    vm.flip()
                    rect = CropRect(0f, 0f, 1f, 1f)
                }
            }
        }
    }
}

/** 0..3 = 四角，4 = 整体移动 */
private fun pickHandle(rect: CropRect, p: Offset, w: Float, h: Float): Int {
    val cx = listOf(rect.l * w, rect.r * w, rect.l * w, rect.r * w)
    val cy = listOf(rect.t * h, rect.t * h, rect.b * h, rect.b * h)
    val threshold = w * 0.12f
    var best = -1
    var bestD = Float.MAX_VALUE
    for (i in 0..3) {
        val d = abs(p.x - cx[i]) + abs(p.y - cy[i])
        if (d < bestD) { bestD = d; best = i }
    }
    return if (bestD < threshold) best else 4
}

private fun applyDrag(
    rect: CropRect,
    mode: Int,
    dx: Float,
    dy: Float,
    ratio: Float?,
    imgAspect: Float
): CropRect {
    val minW = 0.08f
    return when (mode) {
        0 -> {
            var l = (rect.l + dx).coerceIn(0f, rect.r - minW)
            var t = (rect.t + dy).coerceIn(0f, rect.b - minW)
            if (ratio != null) {
                val hNorm = (rect.r - l) * imgAspect / ratio / 1f
                t = (rect.b - hNorm).coerceAtLeast(0f)
            }
            rect.copy(l = l, t = t)
        }
        1 -> {
            val r = (rect.r + dx).coerceIn(rect.l + minW, 1f)
            var t = (rect.t + dy).coerceIn(0f, rect.b - minW)
            if (ratio != null) {
                val hNorm = (r - rect.l) * imgAspect / ratio
                t = (rect.b - hNorm).coerceAtLeast(0f)
            }
            rect.copy(r = r, t = t)
        }
        2 -> {
            var l = (rect.l + dx).coerceIn(0f, rect.r - minW)
            val b = (rect.b + dy).coerceIn(rect.t + minW, 1f)
            var t = rect.t
            if (ratio != null) {
                val hNorm = (rect.r - l) * imgAspect / ratio
                t = (b - hNorm).coerceAtLeast(0f)
            }
            rect.copy(l = l, t = t, b = b)
        }
        3 -> {
            val r = (rect.r + dx).coerceIn(rect.l + minW, 1f)
            val b = (rect.b + dy).coerceIn(rect.t + minW, 1f)
            var t = rect.t
            if (ratio != null) {
                val hNorm = (r - rect.l) * imgAspect / ratio
                t = (b - hNorm).coerceAtLeast(0f)
            }
            rect.copy(r = r, t = t, b = b)
        }
        else -> {
            val w = rect.w
            val h = rect.h
            val l = (rect.l + dx).coerceIn(0f, 1f - w)
            val t = (rect.t + dy).coerceIn(0f, 1f - h)
            rect.copy(l = l, t = t, r = l + w, b = t + h)
        }
    }
}

private fun fitRatio(ratio: Float, imgAspect: Float): CropRect {
    // ratio 为宽/高（像素）；转换为归一化后需除以图片纵横比
    val nw = if (ratio >= imgAspect) 1f else ratio / imgAspect
    val nh = if (ratio >= imgAspect) imgAspect / ratio else 1f
    val l = (1f - nw) / 2f
    val t = (1f - nh) / 2f
    return CropRect(l, t, l + nw, t + nh)
}
