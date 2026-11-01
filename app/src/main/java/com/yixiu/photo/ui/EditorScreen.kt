package com.yixiu.photo.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yixiu.photo.editor.EditorViewModel
import com.yixiu.photo.editor.StickerOverlay
import com.yixiu.photo.editor.TextOverlay

@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    var tool by remember { mutableStateOf<Tool?>(null) }
    var showCrop by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    BackHandler { onClose() }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        EditorTopBar(
            vm = vm,
            onClose = onClose,
            onSave = {
                vm.export { uri ->
                    toastMsg = if (uri != null) "已保存到相册「Pictures/一修」" else "保存失败"
                }
            }
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF050608))
        ) {
            ImageCanvas(vm)
            vm.busyLabel?.let { BusyOverlay(it) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(178.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Panel)
                .border(1.dp, HairLine, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
            if (tool == null) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("选择下方工具开始编辑", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (vm.faceCount > 0) "已识别 ${vm.faceCount} 张人脸，可用美颜与换脸" else "未识别人脸，仍可用滤镜 / 调色 / 抠图",
                        fontSize = 11.sp,
                        color = TextSecondary.copy(alpha = 0.6f)
                    )
                }
            } else {
                ToolPanel(vm = vm, tool = tool!!, onCropClick = { showCrop = true })
            }
        }

        ToolRail(selected = tool, onSelect = { t -> tool = if (tool == t) null else t })
    }

    if (showCrop) {
        CropDialog(vm = vm, onDismiss = { showCrop = false })
    }
}

@Composable
private fun EditorTopBar(vm: EditorViewModel, onClose: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Close,
            "关闭",
            tint = TextPrimary,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable(interactionSource = MutableInteractionSource(), indication = null) { onClose() }
        )
        Spacer(Modifier.width(6.dp))

        IconBtn(Icons.Outlined.Undo, vm.canUndo) { vm.undo() }
        IconBtn(Icons.Outlined.Redo, vm.canRedo) { vm.redo() }

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, HairLine, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        vm.toggleOriginal(true)
                        tryAwaitRelease()
                        vm.toggleOriginal(false)
                    })
                }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, tint = TextPrimary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("对比", fontSize = 11.sp, color = TextPrimary)
            }
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Accent, Color(0xFF5A7BFF))))
                .clickable(interactionSource = MutableInteractionSource(), indication = null) { onSave() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("保存", fontSize = 12.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon,
        null,
        tint = if (enabled) TextPrimary else TextPrimary.copy(alpha = 0.25f),
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, interactionSource = MutableInteractionSource(), indication = null) { onClick() }
            .padding(7.dp)
    )
}

@Composable
private fun ImageCanvas(vm: EditorViewModel) {
    val bmp = vm.preview ?: return
    BoxWithConstraints(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val aspect = bmp.width.toFloat() / bmp.height.toFloat()
        val dispW: Dp
        val dispH: Dp
        if (wPx / hPx > aspect) {
            dispH = maxHeight
            dispW = with(density) { (hPx * aspect).toDp() }
        } else {
            dispW = maxWidth
            dispH = with(density) { (wPx / aspect).toDp() }
        }

        val selected = vm.params.overlays.firstOrNull { it.id == vm.selectedOverlayId }

        Box(
            Modifier
                .size(dispW, dispH)
                .pointerInput(vm.selectedOverlayId) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val ov = vm.params.overlays.firstOrNull { it.id == vm.selectedOverlayId } ?: return@detectDragGestures
                        val nx = (ov.nx + drag.x / size.width).coerceIn(0.02f, 0.98f)
                        val ny = (ov.ny + drag.y / size.height).coerceIn(0.02f, 0.98f)
                        vm.updateOverlay(ov.move(nx, ny))
                    }
                }
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "编辑画布",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            if (selected != null) {
                SelectionOverlay(selected, vm)
            }
        }
    }
}

@Composable
private fun SelectionOverlay(overlay: com.yixiu.photo.editor.Overlay, vm: EditorViewModel) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        val cx = overlay.nx * size.width
        val cy = overlay.ny * size.height
        val fsPx = overlay.sizeRatio * size.width
        val w: Float
        val h: Float
        when (overlay) {
            is TextOverlay -> {
                val st = TextStyle(fontSize = with(density) { fsPx.toSp() }, fontWeight = FontWeight.Bold)
                val lines = overlay.text.split("\n")
                val measured = lines.maxOfOrNull { measurer.measure(it, st).size.width } ?: 0
                w = measured.toFloat().coerceAtLeast(fsPx * 0.6f) + fsPx * 0.3f
                h = fsPx * 1.25f * lines.size + fsPx * 0.2f
            }
            is StickerOverlay -> {
                w = fsPx * 1.1f
                h = fsPx * 1.1f
            }
            else -> return@Canvas
        }
        val left = (cx - w / 2).coerceIn(0f, size.width)
        val top = (cy - h / 2).coerceIn(0f, size.height)
        drawRoundRect(
            color = Accent2,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            )
        )
    }
}

@Composable
private fun BusyOverlay(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent2, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 12.sp, color = Color.White)
        }
    }
}

@Composable
private fun ToolRail(selected: Tool?, onSelect: (Tool) -> Unit) {
    val items = listOf(
        Tool.ENHANCE to Icons.Outlined.AutoAwesome,
        Tool.FILTER to Icons.Outlined.FilterVintage,
        Tool.ADJUST to Icons.Outlined.Tune,
        Tool.BEAUTY to Icons.Outlined.Face,
        Tool.CUTOUT to Icons.Outlined.ContentCut,
        Tool.FACESWAP to Icons.Outlined.SwapHoriz,
        Tool.CROP to Icons.Outlined.Crop,
        Tool.TEXT to Icons.Outlined.TextFields,
        Tool.STICKER to Icons.Outlined.EmojiEmotions
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (tool, icon) ->
            val active = selected == tool
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(interactionSource = MutableInteractionSource(), indication = null) { onSelect(tool) }
                    .padding(vertical = 7.dp)
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (active) Color(0xFFB9A8FF) else TextSecondary,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tool.label,
                    fontSize = 10.sp,
                    color = if (active) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
