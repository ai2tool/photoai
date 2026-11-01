package com.yixiu.photo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yixiu.photo.update.UpdateManager

@Composable
fun HomeScreen(onPick: () -> Unit, onCamera: () -> Unit, onCheckUpdate: () -> Unit = {}) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF08090D), Color(0xFF121020), Color(0xFF08090D))
                )
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.06f),
                        radius = size.width * 0.75f
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(size.width * 0.85f, size.height * 0.06f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Accent2.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.55f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.05f, size.height * 0.55f)
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Accent, Accent2))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("一修", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("本地 AI 修图 · 全程离线", fontSize = 11.sp, color = TextSecondary)
                }
            }

            Spacer(Modifier.height(34.dp))
            Text(
                "把照片交给我，\n剩下的一键搞定。",
                fontSize = 30.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "人像美颜 · 智能抠图 · 换脸海报 · 画质修复\n模型全部跑在手机上，照片不出设备。",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = TextSecondary
            )

            Spacer(Modifier.height(26.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF5A7BFF), Accent2)))
                    .clickableNoRipple(onPick)
                    .padding(20.dp)
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("选择照片开始", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text("支持 JPG / PNG / HEIC，自动摆正", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Light)
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairLine, RoundedCornerShape(18.dp))
                    .clickableNoRipple(onCamera),
                contentAlignment = Alignment.Center
            ) {
                Text("直接拍摄", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(30.dp))
            Text("能做什么", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))

            val features = listOf(
                Triple(Icons.Outlined.Face, "人像美颜", "磨皮、美白、祛斑、瘦脸，只作用于皮肤区域"),
                Triple(Icons.Outlined.ContentCut, "一键抠图", "更换透明 / 纯色 / 虚化 / 自定义背景"),
                Triple(Icons.Outlined.AutoAwesome, "换脸海报", "把自己的脸贴到海报人物上，自动对齐肤色"),
                Triple(Icons.Outlined.HighQuality, "画质增强", "自动色阶、白平衡与细节锐化，救回老照片")
            )
            features.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { (icon, title, desc) ->
                        FeatureCard(icon, title, desc, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Accent2)
                )
                Spacer(Modifier.width(8.dp))
                Text("模型已内置，无需联网，不上传任何照片", fontSize = 11.sp, color = TextSecondary)
            }

            Spacer(Modifier.height(18.dp))
            UpdateRow(onCheckUpdate = onCheckUpdate)
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, desc: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairLine, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color(0xFFB9A8FF), modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(desc, fontSize = 10.5.sp, lineHeight = 15.sp, color = TextSecondary)
    }
}

@Composable
private fun UpdateRow(onCheckUpdate: () -> Unit) {
    val st = UpdateManager.state
    val hasNew = st is UpdateManager.State.Available || st is UpdateManager.State.Ready
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairLine, RoundedCornerShape(16.dp))
            .clickableNoRipple {
                if (hasNew) UpdateManager.reopen() else onCheckUpdate()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Refresh,
            null,
            tint = if (hasNew) Accent2 else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                st is UpdateManager.State.Downloading -> "正在下载新版本…"
                st is UpdateManager.State.Available -> "发现新版本 ${st.info.name}"
                st is UpdateManager.State.Ready -> "新版本已下载好，点此安装"
                else -> "检查更新"
            },
            fontSize = 12.sp,
            color = if (hasNew) TextPrimary else TextSecondary,
            fontWeight = if (hasNew) FontWeight.Medium else FontWeight.Normal
        )
        Spacer(Modifier.weight(1f))
        Text(
            "v${UpdateManager.currentVersion}",
            fontSize = 11.sp,
            color = TextSecondary.copy(alpha = 0.7f)
        )
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick
)
