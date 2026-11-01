package com.yixiu.photo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yixiu.photo.update.UpdateManager
import com.yixiu.photo.update.UpdateManager.State

/**
 * 更新相关弹窗的统一入口。状态由 [UpdateManager] 决定，这里只负责画。
 */
@Composable
fun UpdateDialogHost() {
    val ctx = LocalContext.current
    val st = UpdateManager.state
    val open = UpdateManager.dialogOpen
    if (!open) return

    val force = (st as? State.Available)?.info?.force == true
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
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(26.dp))
                .background(Panel)
                .border(1.dp, HairLine, RoundedCornerShape(26.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            when (st) {
                is State.Checking -> {
                    Title("检查更新")
                    Spacer(Modifier.height(8.dp))
                    Body("正在获取最新版本…")
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(0f, false)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogButton("关闭", primary = false) { UpdateManager.dismiss() }
                    }
                }

                is State.UpToDate -> {
                    Title("已是最新版本")
                    Spacer(Modifier.height(8.dp))
                    Body("当前版本 v${st.version}，没有更新的版本了。")
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DialogButton("好", primary = true) { UpdateManager.dismiss() }
                    }
                }

                is State.Available -> {
                    val info = st.info
                    Title("发现新版本 ${info.name}")
                    Spacer(Modifier.height(10.dp))
                    Notes(info.notes)
                    val meta = listOfNotNull(
                        UpdateManager.sizeText(info.size).takeIf { it.isNotEmpty() }?.let { "安装包 $it" },
                        "当前 v${UpdateManager.currentVersion}"
                    ).joinToString(" · ")
                    Spacer(Modifier.height(10.dp))
                    Body(meta, 11.5.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("取消", primary = false, modifier = Modifier.weight(1f)) {
                            UpdateManager.cancelDownload()
                        }
                        DialogButton("后台下载", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.dismiss()
                        }
                    }
                }

                is State.Ready -> {
                    Title("新版本 ${st.info.name} 已下载好")
                    Spacer(Modifier.height(10.dp))
                    Notes(st.info.notes)
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("稍后", primary = false, modifier = Modifier.weight(1f)) { UpdateManager.dismiss() }
                        DialogButton("立即安装", primary = true, modifier = Modifier.weight(1f)) {
                            UpdateManager.install(ctx, st.file)
                        }
                    }
                }

                is State.NeedPermission -> {
                    Title("需要允许安装")
                    Spacer(Modifier.height(10.dp))
                    Body(UpdateManager.tip ?: "下载已完成。系统要求先允许「一修」安装应用（只需允许这一次），返回后会自动继续安装。")
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("取消", primary = false, modifier = Modifier.weight(1f)) { UpdateManager.dismiss() }
                        DialogButton("去允许", primary = true, modifier = Modifier.weight(1f)) {
                            if (!UpdateManager.openInstallSettings(ctx)) {
                                UpdateManager.tip = "这台设备没有直达页面。请到 系统设置 → 应用 → 特殊权限 → 安装未知应用，打开「一修」后再点检查更新。"
                            }
                        }
                    }
                }

                is State.Failed -> {
                    Title(if (st.info == null) "检查失败" else "下载中断")
                    Spacer(Modifier.height(10.dp))
                    Body(st.message)
                    if (st.kept > 0) {
                        Spacer(Modifier.height(6.dp))
                        Body("已下载 ${UpdateManager.sizeText(st.kept)}，下次会接着下。", 11.5.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("关闭", primary = false, modifier = Modifier.weight(1f)) { UpdateManager.dismiss() }
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
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
}

@Composable
private fun Body(text: String, size: androidx.compose.ui.unit.TextUnit = 13.sp) {
    Text(text, fontSize = size, lineHeight = (size.value + 7).sp, color = TextSecondary)
}

/** 更新说明可能有好几行，超过一屏时可滚。 */
@Composable
private fun Notes(notes: String) {
    val text = notes.ifBlank { "修复已知问题，优化使用体验。" }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(12.dp)
            .heightIn(max = 150.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text, fontSize = 12.5.sp, lineHeight = 20.sp, color = TextPrimary.copy(alpha = 0.9f))
    }
}

@Composable
private fun ProgressBar(progress: Float, determinate: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.10f))
    ) {
        val f = if (determinate) progress.coerceIn(0f, 1f) else 0.35f
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
        )
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (primary) Modifier.background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                else Modifier.background(Color.White.copy(alpha = 0.07f)).border(1.dp, HairLine, RoundedCornerShape(14.dp))
            )
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = if (primary) Color.White else TextPrimary,
            textAlign = TextAlign.Center
        )
    }
}
