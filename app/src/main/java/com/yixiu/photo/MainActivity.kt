package com.yixiu.photo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.yixiu.photo.editor.EditorViewModel
import com.yixiu.photo.ui.EditorScreen
import com.yixiu.photo.ui.HomeScreen
import com.yixiu.photo.ui.UpdateDialogHost
import com.yixiu.photo.ui.YixiuTheme
import com.yixiu.photo.update.UpdateManager
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        when (intent?.action) {
            android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_EDIT -> {
                @Suppress("DEPRECATION")
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
                uri?.let { vm.open(it) }
            }
        }
        setContent {
            YixiuTheme { AppRoot(vm) }
        }
        UpdateManager.checkOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.onResume(this)
        UpdateManager.offerPendingInstall(this)
    }
}

@Composable
private fun AppRoot(vm: EditorViewModel) {
    val context = LocalContext.current
    var inEditor by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.open(uri)
            inEditor = true
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            pendingCameraUri?.let {
                vm.open(it)
                inEditor = true
            }
        }
    }

    fun launchPicker() {
        picker.launch(
            androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        camera.launch(uri)
    }

    LaunchedEffect(vm.preview) {
        if (vm.preview != null) inEditor = true
    }

    if (inEditor && (vm.preview != null || vm.busyLabel != null)) {
        EditorScreen(
            vm = vm,
            onClose = {
                inEditor = false
                vm.close()
            }
        )
    } else {
        HomeScreen(
            onPick = { launchPicker() },
            onCamera = { launchCamera() },
            onCheckUpdate = { UpdateManager.recheck(context) }
        )
    }
    UpdateDialogHost()
}
