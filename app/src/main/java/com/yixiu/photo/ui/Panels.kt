package com.yixiu.photo.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yixiu.photo.editor.BeautyParams
import com.yixiu.photo.editor.BgMode
import com.yixiu.photo.editor.EditorViewModel
import com.yixiu.photo.editor.StickerOverlay
import com.yixiu.photo.editor.TextOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Tool(val label: String) {
    ENHANCE("增强"),
    FILTER("滤镜"),
    ADJUST("调色"),
    BEAUTY("美颜"),
    CUTOUT("抠图"),
    FACESWAP("换脸"),
    CROP("裁剪"),
    TEXT("文字"),
    STICKER("贴纸")
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    min: Float = 0f,
    max: Float = 1f,
    onFinished: () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(46.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = min..max,
            modifier = Modifier.weight(1f).height(26.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
            )
        )
        Text(
            String.format("%+.0f", value * 100),
            fontSize = 10.sp,
            color = TextPrimary.copy(alpha = 0.7f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp)
        )
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (selected) Color.Transparent else HairLine, RoundedCornerShape(13.dp))
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) Color.White else TextPrimary.copy(alpha = 0.8f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(Accent, Color(0xFF5A7BFF)))
                else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f)))
            )
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                    onClick = onClick
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else TextSecondary
        )
    }
}

@Composable
fun GhostButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, HairLine, RoundedCornerShape(15.dp))
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 12.sp, color = TextPrimary)
    }
}

// ---------------- 各工具面板 ----------------

@Composable
fun ToolPanel(vm: EditorViewModel, tool: Tool, onCropClick: () -> Unit) {
    when (tool) {
        Tool.ENHANCE -> EnhancePanel(vm)
        Tool.FILTER -> FilterPanel(vm)
        Tool.ADJUST -> AdjustPanel(vm)
        Tool.BEAUTY -> BeautyPanel(vm)
        Tool.CUTOUT -> CutoutPanel(vm)
        Tool.FACESWAP -> FaceSwapPanel(vm)
        Tool.CROP -> CropPanel(vm, onCropClick)
        Tool.TEXT -> TextPanel(vm)
        Tool.STICKER -> StickerPanel(vm)
    }
}

@Composable
private fun EnhancePanel(vm: EditorViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("一键分析画面", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "自动色阶 + 白平衡 + 细节锐化，适合欠曝、发灰、发黄的照片。",
            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (vm.params.enhanceApplied) "再增强一次" else "开始智能增强") { vm.applyEnhance() }
    }
}

@Composable
private fun FilterPanel(vm: EditorViewModel) {
    var thumbs by remember { mutableStateOf<List<Triple<String, String, Bitmap>>>(emptyList()) }
    val seed = vm.thumbSeed
    LaunchedEffect(seed, vm.preview) {
        val p = vm.preview ?: return@LaunchedEffect
        thumbs = withContext(Dispatchers.Default) { vm.filterThumbnails(p) }
    }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(thumbs) { (id, label, bmp) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(62.dp)
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null
                    ) { vm.applyFilter(id) }
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            if (vm.params.filterId == id) 2.dp else 1.dp,
                            if (vm.params.filterId == id) Accent else HairLine,
                            RoundedCornerShape(14.dp)
                        )
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = label,
                        modifier = Modifier.size(58.dp).clip(RoundedCornerShape(13.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    label,
                    fontSize = 10.sp,
                    color = if (vm.params.filterId == id) TextPrimary else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val a = vm.params.adjust
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SliderRow("亮度", a.brightness, { v -> vm.update { it.copy(adjust = it.adjust.copy(brightness = v)) } }, -1f, 1f)
        SliderRow("对比", a.contrast, { v -> vm.update { it.copy(adjust = it.adjust.copy(contrast = v)) } }, -1f, 1f)
        SliderRow("饱和", a.saturation, { v -> vm.update { it.copy(adjust = it.adjust.copy(saturation = v)) } }, -1f, 1f)
        SliderRow("色温", a.warmth, { v -> vm.update { it.copy(adjust = it.adjust.copy(warmth = v)) } }, -1f, 1f)
        SliderRow("锐化", a.sharpen, { v -> vm.update { it.copy(adjust = it.adjust.copy(sharpen = v)) } }, 0f, 1f)
        SliderRow("暗角", a.vignette, { v -> vm.update { it.copy(adjust = it.adjust.copy(vignette = v)) } }, 0f, 1f)
    }
}

@Composable
private fun BeautyPanel(vm: EditorViewModel) {
    val b = vm.params.beauty
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (vm.faceCount == 0) {
            Text("这张图里没识别到人脸，美颜效果不会生效", fontSize = 11.sp, color = Color(0xFFFFB86B))
            Spacer(Modifier.height(8.dp))
        }
        SliderRow("磨皮", b.smooth, onChange = { v -> vm.setBeauty(b.copy(smooth = v)) })
        SliderRow("美白", b.whiten, onChange = { v -> vm.setBeauty(b.copy(whiten = v)) })
        SliderRow("祛斑", b.blemish, onChange = { v -> vm.setBeauty(b.copy(blemish = v)) })
        SliderRow("瘦脸", b.slim, onChange = { v -> vm.setBeauty(b.copy(slim = v)) })
    }
}

@Composable
private fun CutoutPanel(vm: EditorViewModel) {
    val bg = vm.params.bg
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip("透明", bg.mode == BgMode.TRANSPARENT) { vm.setBgMode(BgMode.TRANSPARENT) }
            PillChip("纯色", bg.mode == BgMode.COLOR) { vm.setBgMode(BgMode.COLOR) }
            PillChip("虚化", bg.mode == BgMode.BLUR) { vm.setBgMode(BgMode.BLUR) }
            PillChip("图片", bg.mode == BgMode.IMAGE) { vm.setBgMode(BgMode.IMAGE) }
            if (bg.mode != BgMode.NONE) {
                PillChip("关闭", false) { vm.setBgMode(BgMode.NONE) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (bg.mode) {
            BgMode.BLUR -> SliderRow("虚化", bg.blur, onChange = { v -> vm.setBgBlur(v) })
            BgMode.COLOR -> ColorRow(bg.color) { vm.setBgColor(it) }
            BgMode.IMAGE -> {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
                    uri?.let { vm.setBgImage(it) }
                }
                GhostButton("选择背景图", Icons.Outlined.Image) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            else -> Text(
                "AI 会先分离人物，再替换背景，发丝边缘也会保留。",
                fontSize = 11.sp, color = TextSecondary
            )
        }
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    val colors = listOf(
        0xFFF5F5F7, 0xFFFFFFFF, 0xFF111318, 0xFF1F2A44, 0xFF2F4858,
        0xFFE8D5C4, 0xFFF3D9DC, 0xFFDCE9D5, 0xFFE7DFF5, 0xFFF6E7C1
    ).map { it.toInt() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (c == selected) 2.dp else 1.dp,
                        if (c == selected) Accent else Color.White.copy(alpha = 0.18f),
                        CircleShape
                    )
                    .clickable(interactionSource = MutableInteractionSource(), indication = null) { onPick(c) }
            )
        }
    }
}

@Composable
private fun FaceSwapPanel(vm: EditorViewModel) {
    // rememberSaveable：选择器打开期间 Activity 可能被系统回收重建，丢失后表现为“只能选中一张”
    var posterUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var sourceUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var msg by rememberSaveable { mutableStateOf<String?>(null) }
    // 下一次选择归到哪张：0=海报 1=自拍
    var nextSlot by rememberSaveable { mutableStateOf(0) }

    // 整个面板只注册一个选择器：部分机型的相册对同一界面里的第二个选择器会直接返回取消，
    // 表现为“第二张选不上”。两张图共用它，按 nextSlot 归位。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            if (nextSlot == 0) posterUri = uri else sourceUri = uri
            msg = null
        }
    }

    fun launchPick(slot: Int) {
        nextSlot = slot
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            "选一张海报，再选一张自己的正脸照，自动对齐五官并匹配肤色。",
            fontSize = 11.sp, color = TextSecondary, lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (posterUri == null) "① 选海报" else "① 已选海报") { launchPick(0) }
            GhostButton(if (sourceUri == null) "② 选自拍" else "② 已选自拍") { launchPick(1) }
        }
        if (msg != null) {
            Spacer(Modifier.height(8.dp))
            Text(msg!!, fontSize = 11.sp, color = Color(0xFFFF8A8A))
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("开始换脸", enabled = posterUri != null && sourceUri != null) {
            vm.faceSwap(posterUri!!, sourceUri!!) { err ->
                msg = err ?: "已贴合，可继续调色或保存"
                if (err == null) {
                    posterUri = null
                    sourceUri = null
                    nextSlot = 0
                }
            }
        }
    }
}

@Composable
private fun CropPanel(vm: EditorViewModel, onCropClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GhostButton("自由裁剪", Icons.Outlined.AutoFixHigh, onCropClick)
        GhostButton("旋转 90°", Icons.Outlined.RotateRight) { vm.rotate(90f) }
        GhostButton("镜像", Icons.Outlined.Flip) { vm.flip() }
    }
}

@Composable
private fun TextPanel(vm: EditorViewModel) {
    val selected = vm.params.overlays.firstOrNull { it.id == vm.selectedOverlayId } as? TextOverlay
    var text by remember(selected?.id) { mutableStateOf(selected?.text ?: "") }
    val colors = listOf(0xFFFFFFFF, 0xFF111318, 0xFF7C5CFF, 0xFFFF5C8A, 0xFFFFC85C, 0xFF29D3E8).map { it.toInt() }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.isEmpty()) {
                    Text("输入文字…", fontSize = 13.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Accent)
                    .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                        if (selected != null) {
                            vm.updateOverlay(selected.copy(text = text))
                        } else {
                            vm.addText(text)
                        }
                        text = ""
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (selected != null) "更新" else "添加", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { c ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            if (selected?.color == c) 2.dp else 1.dp,
                            if (selected?.color == c) Accent2 else Color.White.copy(alpha = 0.2f),
                            CircleShape
                        )
                        .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                            if (selected != null) vm.updateOverlay(selected.copy(color = c))
                            else vm.update { it }
                        }
                )
                Spacer(Modifier.width(10.dp))
            }
            if (selected != null) {
                Spacer(Modifier.weight(1f))
                GhostButton("删除") { vm.removeOverlay(selected.id) }
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(6.dp))
            SliderRow("大小", selected.sizeRatio, { v ->
                vm.updateOverlay(selected.copy(sizeRatio = v))
            }, 0.03f, 0.30f)
        }
    }
}

@Composable
private fun StickerPanel(vm: EditorViewModel) {
    val emojis = listOf(
        "✨", "💫", "🌸", "🍃", "☁️", "🌙", "⭐️", "🔥", "💧", "🎀",
        "🖤", "🤍", "💜", "🦋", "🌷", "🍒", "☕️", "🎧", "📷", "🌼"
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("点一下就能贴到画面中央，拖动可换位置", fontSize = 11.sp, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emojis) { e ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable(interactionSource = MutableInteractionSource(), indication = null) { vm.addSticker(e) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(e, fontSize = 22.sp)
                }
            }
        }
        val sel = vm.params.overlays.firstOrNull { it.id == vm.selectedOverlayId } as? StickerOverlay
        if (sel != null) {
            Spacer(Modifier.height(8.dp))
            SliderRow("大小", sel.sizeRatio, { v -> vm.updateOverlay(sel.copy(sizeRatio = v)) }, 0.05f, 0.5f)
            Spacer(Modifier.height(4.dp))
            GhostButton("删除贴纸") { vm.removeOverlay(sel.id) }
        }
    }
}
