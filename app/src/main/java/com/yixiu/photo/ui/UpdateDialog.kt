package com.yixiu.photo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yixiu.photo.update.UpdateManager
import com.yixiu.photo.update.UpdateManager.State

/**
 * 更新相关弹窗的统一入口。状态由 [UpdateManager] 决定，这里只负责画。
 *
 * 布局约定：
 * - 宽度取屏幕 90%，但最大 380dp（平板/折叠屏上不至于拉成一条）；
 * - 高度最多占屏幕 82%，超出时整体滚动（横屏、系统大字号都不会被裁）；
 * - 按钮一律等宽铺满，避免只有单个按钮时被文字宽度压成窄条。
 */
@Composable
fun UpdateDialogHost() {
    val ctx = LocalContext.current
    val st = UpdateManager.state
    val open = UpdateManager.dialogOpen
    if (!open) return

    val force = (st as? State.Available)?.info?.force == true
    val cfg = LocalConfiguration.current
    val shape = RoundedCornerShape(24.dp)
    val dialogWidth = minOf(cfg.screenWidthDp * 0.9f, 380f).dp
    val maxHeight = (cfg.screenHeightDp * 0.82f).dp

    Dialog(
        onDismissRequest = { if (!force) UpdateManager.dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !force,
            dismissOnBackPress = !force
        )
    ) {
        Column(
            Modifier
                .width(dialogWidth)
                .heightIn(max = maxHeight)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E2029), Color(0xFF15161D))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 22.dp)
        ) {
            when (st) {
                is State.Checking -> {
                    Title("检查更新")
                    Spacer(Modifier.height(8.dp))
                    Body("正在获取最新版本…")
                    Spacer(Modifier.height(16.dp))
                    ProgressBar(0f, false)
                    Spacer(Modifier.height(22.dp))
                    ButtonRow {
                        DialogButton("关闭", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                    }
                }

                is State.UpToDate -> {
                    Title("已是最新版本")
                    Spacer(Modifier.height(8.dp))
                    Body("当前版本 v${st.version}，没有更新的版本了。")
                    Spacer(Modifier.height(22.dp))
                    ButtonRow {
                        DialogButton("好", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                    }
                }

                is State.Available -> {
                    val info = st.info
                    Title("发现新版本")
                    Spacer(Modifier.height(6.dp))
                    Body(buildString {
                        append(info.name)
                        append(" · 当前 v${UpdateManager.currentVersion}")
                        UpdateManager.sizeText(info.size).takeIf { it.isNotEmpty() }?.let {
                            append(" · 安装包 $it")
                        }
                    }, 11.5.sp)
                    Spacer(Modifier.height(14.dp))
                    Notes(info.notes)
                    Spacer(Modifier.height(20.dp))
                    ButtonRow {
                        if (!info.force) {
                            DialogButton("忽略此版本", primary = false, modifier = Modifier.weight(1f)) {
                                UpdateManager.ignore(ctx)
                            }
                        }
                        DialogButton("立即更新", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.download(ctx)
                        }
                    }
                }

                is State.Downloading -> {
                    val pct = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
                    Title("正在下载 ${st.info.name}")
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(pct, st.total > 0)
                    Spacer(Modifier.height(10.dp))
                    Body(
                        if (st.total > 0) {
                            "${UpdateManager.sizeText(st.done)} / ${UpdateManager.sizeText(st.total)} · ${(pct * 100).toInt()}%"
                        } else {
                            "已下载 ${UpdateManager.sizeText(st.done)}"
                        },
                        11.5.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    ButtonRow {
                        DialogButton("取消", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.cancelDownload()
                        }
                        DialogButton("后台下载", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                    }
                }

                is State.Ready -> {
                    Title("新版本已下载好")
                    Spacer(Modifier.height(6.dp))
                    Body("${st.info.name} · 点击安装即可完成更新", 11.5.sp)
                    Spacer(Modifier.height(14.dp))
                    Notes(st.info.notes)
                    Spacer(Modifier.height(20.dp))
                    ButtonRow {
                        DialogButton("稍后", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                        DialogButton("立即安装", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.install(ctx, st.file)
                        }
                    }
                }

                is State.NeedPermission -> {
                    Title("需要允许安装")
                    Spacer(Modifier.height(8.dp))
                    Body(
                        UpdateManager.tip
                            ?: "下载已完成。系统要求先允许「一修」安装应用（只需允许这一次），返回后会自动继续安装。"
                    )
                    Spacer(Modifier.height(22.dp))
                    ButtonRow {
                        DialogButton("取消", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                        DialogButton("去允许", primary = true, modifier = Modifier.weight(1f)) {
                            if (!UpdateManager.openInstallSettings(ctx)) {
                                UpdateManager.tip = "这台设备没有直达页面。请到 系统设置 → 应用 → 特殊权限 → 安装未知应用，打开「一修」后再点检查更新。"
                            }
                        }
                    }
                }

                is State.Failed -> {
                    Title(if (st.info == null) "检查更新失败" else "下载中断")
                    Spacer(Modifier.height(8.dp))
                    Body(st.message)
                    if (st.kept > 0) {
                        Spacer(Modifier.height(8.dp))
                        Body("已下载 ${UpdateManager.sizeText(st.kept)}，下次会接着下。", 11.5.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    ButtonRow {
                        DialogButton("关闭", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                        DialogButton("重试", primary = true, modifier = Modifier.weight(1f)) {
                            if (st.info != null) UpdateManager.download(ctx) else UpdateManager.recheck(ctx)
                        }
                    }
                }

                State.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        fontSize = 17.5.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary
    )
}

@Composable
private fun Body(text: String, size: androidx.compose.ui.unit.TextUnit = 13.sp) {
    Text(text, fontSize = size, lineHeight = (size.value + 7.5f).sp, color = TextSecondary)
}

/** 更新说明：整块浅底，随弹窗一起滚动（不再自己套一层滚动，避免滚动冲突）。 */
@Composable
private fun Notes(notes: String) {
    val text = notes.ifBlank { "修复已知问题，优化使用体验。" }
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        fontSize = 12.5.sp,
        lineHeight = 21.sp,
        color = TextPrimary.copy(alpha = 0.92f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
private fun ButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/** 进度条：确定进度按比例填充，不确定时跑一段来回移动的高光，避免看起来像卡住。 */
@Composable
private fun ProgressBar(progress: Float, determinate: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
    ) {
        val track = maxWidth
        if (determinate) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
            )
        } else {
            val transition = rememberInfiniteTransition(label = "update-bar")
            val ratio by transition.animateFloat(
                initialValue = -0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1300, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "update-bar-x"
            )
            Box(
                Modifier
                    .offset(x = track * ratio)
                    .width(track * 0.45f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
            )
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .then(
                if (primary) {
                    Modifier.background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                } else {
                    Modifier
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, HairLine, shape)
                }
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = if (primary) Color.White else TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
