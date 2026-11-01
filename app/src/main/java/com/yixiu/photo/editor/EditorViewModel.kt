package com.yixiu.photo.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yixiu.photo.image.AiEngine
import com.yixiu.photo.image.BitmapUtils
import com.yixiu.photo.image.FaceInfo
import com.yixiu.photo.image.ImageOps
import com.yixiu.photo.image.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PREVIEW_MAX = 1280
        const val EXPORT_MAX = 3000
        const val LOAD_MAX = 4096
    }

    var preview by mutableStateOf<Bitmap?>(null); private set
    var original by mutableStateOf<Bitmap?>(null); private set
    var params by mutableStateOf(EditorParams()); private set
    var busyLabel by mutableStateOf<String?>(null); private set
    var faceCount by mutableStateOf(0); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var selectedOverlayId by mutableStateOf<String?>(null); private set
    var savedUri by mutableStateOf<Uri?>(null)
    var thumbSeed by mutableStateOf(0); private set
    var baseVersion by mutableStateOf(0); private set

    fun currentBase(): Bitmap? = base

    private var base: Bitmap? = null
    private var previewBase: Bitmap? = null
    private var faces: List<FaceInfo> = emptyList()
    private var mask: Bitmap? = null
    private var bgImage: Bitmap? = null
    private var showOriginal = false

    private data class Snapshot(val base: Bitmap, val params: EditorParams)

    private val history = ArrayList<Snapshot>()
    private var cursor = -1
    private var commitJob: Job? = null

    private var rendering = false
    private var pendingRender = false
    private var renderJob: Job? = null

    // 分级缓存，避免调色时重算美颜
    private var beautyStageKey: String? = null
    private var beautyStage: Bitmap? = null
    private var colorStageKey: String? = null
    private var colorStage: Bitmap? = null

    // ---------------- 打开图片 ----------------

    fun open(uri: Uri) {
        val ctx = getApplication<Application>()
        busyLabel = "读取图片"
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, uri, LOAD_MAX) }
            if (loaded == null) {
                android.util.Log.e("YixiuEntry", "load failed: $uri")
                busyLabel = null
                return@launch
            }
            original = loaded
            base = loaded
            thumbSeed++
            baseVersion++
            previewBase = BitmapUtils.scaleDown(loaded, PREVIEW_MAX)
            history.clear()
            cursor = -1
            beautyStage = null; colorStage = null
            beautyStageKey = null; colorStageKey = null
            params = EditorParams()
            selectedOverlayId = null
            mask = null
            AiEngine.invalidateMask()
            busyLabel = "识别人像"
            faces = withContext(Dispatchers.Default) { AiEngine.detectFaces(previewBase!!) }
            faceCount = faces.size
            busyLabel = null
            commitNow()
            requestRender()
        }
    }

    fun close() {
        history.clear()
        cursor = -1
        beautyStage = null
        colorStage = null
        mask = null
        base = null
        previewBase = null
        preview = null
        original = null
        faces = emptyList()
        params = EditorParams()
        AiEngine.invalidateMask()
        canUndo = false; canRedo = false
        faceCount = 0
    }

    // ---------------- 参数 ----------------

    fun update(block: (EditorParams) -> EditorParams) {
        params = block(params)
        scheduleCommit()
        requestRender()
    }

    fun toggleOriginal(on: Boolean) {
        showOriginal = on
        requestRender()
    }

    fun selectOverlay(id: String?) { selectedOverlayId = id }

    fun addText(text: String) {
        if (text.isBlank()) return
        val o = TextOverlay(text = text.trim())
        update { it.copy(overlays = it.overlays + o) }
        selectedOverlayId = o.id
    }

    fun addSticker(emoji: String) {
        val o = StickerOverlay(emoji = emoji)
        update { it.copy(overlays = it.overlays + o) }
        selectedOverlayId = o.id
    }

    fun removeOverlay(id: String) {
        update { it.copy(overlays = it.overlays.filterNot { it.id == id }) }
        if (selectedOverlayId == id) selectedOverlayId = null
    }

    fun updateOverlay(o: Overlay) {
        update { p -> p.copy(overlays = p.overlays.map { if (it.id == o.id) o else it }) }
    }

    fun applyFilter(id: String) {
        update { it.copy(filterId = id) }
    }

    fun setBeauty(b: BeautyParams) = update { it.copy(beauty = b) }

    /** 滤镜缩略图 */
    fun filterThumbnails(src: Bitmap): List<Triple<String, String, Bitmap>> {
        val t = BitmapUtils.scaleDown(src, 160)
        return Filters.all.map { f ->
            Triple(f.id, f.label, ImageOps.applyColor(t, f, 0f, 0f, 0f, 0f, 0f))
        }
    }

    // ---------------- AI 增强 ----------------

    fun applyEnhance() {
        val b = base ?: return
        busyLabel = "分析画面"
        viewModelScope.launch {
            val enhanced = withContext(Dispatchers.Default) { ImageOps.autoEnhance(b) }
            setBase(enhanced)
            params = params.copy(enhanceApplied = true)
            commitNow()
            busyLabel = null
            requestRender()
        }
    }

    // ---------------- 抠图背景 ----------------

    fun setBgMode(mode: BgMode) {
        if (mode == BgMode.NONE) {
            update { it.copy(bg = it.bg.copy(mode = mode)) }
            return
        }
        val pb = previewBase ?: return
        busyLabel = "AI 分割中"
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) {
                AiEngine.personMask(getApplication(), pb, "pv${System.identityHashCode(pb)}")
            }
            if (m == null) {
                android.util.Log.e("YixiuSeg", "personMask returned null")
                busyLabel = null
                return@launch
            }
            mask = m
            busyLabel = null
            update { it.copy(bg = it.bg.copy(mode = mode)) }
        }
    }

    fun setBgColor(color: Int) = update { it.copy(bg = it.bg.copy(color = color, mode = BgMode.COLOR)) }

    fun setBgBlur(v: Float) = update { it.copy(bg = it.bg.copy(blur = v)) }

    fun setBgImage(uri: Uri) {
        val ctx = getApplication<Application>()
        busyLabel = "载入背景"
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, uri, 2000) }
            bgImage = bmp
            busyLabel = null
            if (bmp != null) update { it.copy(bg = it.bg.copy(mode = BgMode.IMAGE, imageKey = uri.toString())) }
        }
    }

    // ---------------- 裁剪 / 旋转 ----------------

    fun cropTo(nl: Float, nt: Float, nr: Float, nb: Float) {
        val b = base ?: return
        val l = (nl * b.width).toInt().coerceIn(0, b.width - 1)
        val t = (nt * b.height).toInt().coerceIn(0, b.height - 1)
        val r = (nr * b.width).toInt().coerceIn(l + 1, b.width)
        val bo = (nb * b.height).toInt().coerceIn(t + 1, b.height)
        if (r - l < 16 || bo - t < 16) return
        setBase(BitmapUtils.crop(b, l, t, r, bo))
        commitNow()
    }

    fun rotate(degrees: Float) {
        val b = base ?: return
        setBase(BitmapUtils.rotate(b, degrees))
        commitNow()
    }

    fun flip() {
        val b = base ?: return
        setBase(BitmapUtils.flip(b))
        commitNow()
    }

    private fun setBase(nb: Bitmap) {
        base = nb
        thumbSeed++
        baseVersion++
        previewBase = BitmapUtils.scaleDown(nb, PREVIEW_MAX)
        mask = null
        beautyStage = null; colorStage = null
        beautyStageKey = null; colorStageKey = null
        AiEngine.invalidateMask()
        viewModelScope.launch {
            busyLabel = "识别人像"
            faces = withContext(Dispatchers.Default) { AiEngine.detectFaces(previewBase!!) }
            faceCount = faces.size
            busyLabel = null
            requestRender()
        }
    }

    // ---------------- 换脸 ----------------

    fun faceSwap(posterUri: Uri, sourceUri: Uri, onDone: (String?) -> Unit) {
        val ctx = getApplication<Application>()
        busyLabel = "解析人脸"
        viewModelScope.launch {
            try {
                val poster = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, posterUri, LOAD_MAX) }
                    ?: return@launch onDone("海报读取失败")
                val source = withContext(Dispatchers.IO) { BitmapUtils.load(ctx, sourceUri, LOAD_MAX) }
                    ?: return@launch onDone("自拍读取失败")
                val pFaces = withContext(Dispatchers.Default) { AiEngine.detectFaces(poster) }
                val sFaces = withContext(Dispatchers.Default) { AiEngine.detectFaces(source) }
                if (pFaces.isEmpty()) return@launch onDone("海报里没找到人脸")
                if (sFaces.isEmpty()) return@launch onDone("自拍里没找到人脸")
                busyLabel = "融合中"
                val result = withContext(Dispatchers.Default) {
                    AiEngine.faceswap(poster, source, pFaces.first(), sFaces.first())
                }
                setBase(result)
                params = params.copy(bg = BgParams())
                commitNow()
                busyLabel = null
                onDone(null)
            } catch (t: Throwable) {
                android.util.Log.e("YixiuFace", "faceswap error", t)
                busyLabel = null
                onDone(t.message ?: "换脸失败")
            }
        }
    }

    // ---------------- 历史 ----------------

    private fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(600)
            commitNow()
        }
    }

    private fun commitNow() {
        val b = base ?: return
        commitJob?.cancel()
        val snap = Snapshot(b, params)
        if (cursor >= 0 && cursor < history.size) {
            val cur = history[cursor]
            if (cur.base === b && cur.params == params) return
        }
        while (history.size > cursor + 1) history.removeAt(history.size - 1)
        history.add(snap)
        if (history.size > 14) history.removeAt(0)
        cursor = history.size - 1
        canUndo = cursor > 0
        canRedo = false
    }

    fun undo() {
        commitJob?.cancel()
        commitNow()
        if (cursor <= 0) return
        cursor--
        applySnapshot(history[cursor])
    }

    fun redo() {
        if (cursor >= history.size - 1) return
        cursor++
        applySnapshot(history[cursor])
    }

    private fun applySnapshot(s: Snapshot) {
        base = s.base
        baseVersion++
        previewBase = BitmapUtils.scaleDown(s.base, PREVIEW_MAX)
        params = s.params
        mask = null
        beautyStage = null; colorStage = null
        beautyStageKey = null; colorStageKey = null
        AiEngine.invalidateMask()
        canUndo = cursor > 0
        canRedo = cursor < history.size - 1
        viewModelScope.launch {
            faces = withContext(Dispatchers.Default) { AiEngine.detectFaces(previewBase!!) }
            faceCount = faces.size
            requestRender()
        }
    }

    // ---------------- 渲染 ----------------

    fun requestRender() {
        if (rendering) { pendingRender = true; return }
        renderJob = viewModelScope.launch {
            rendering = true
            try {
                do {
                    pendingRender = false
                    delay(45)
                    val pb = previewBase ?: break
                    val p = params
                    val f = faces
                    val m = if (p.bg.mode == BgMode.NONE) null else mask
                    val bgI = if (p.bg.mode == BgMode.IMAGE) bgImage else null
                    val looksOriginal = showOriginal
                    val result = withContext(Dispatchers.Default) {
                        if (looksOriginal && original != null) {
                            BitmapUtils.scaleDown(original!!, PREVIEW_MAX)
                        } else {
                            compose(pb, p, f, m, bgI, 1f, cache = true)
                        }
                    }
                    preview = result
                } while (pendingRender)
            } finally {
                rendering = false
            }
        }
    }

    private fun compose(
        b: Bitmap,
        p: EditorParams,
        f: List<FaceInfo>,
        m: Bitmap?,
        bgI: Bitmap?,
        faceScale: Float,
        cache: Boolean
    ): Bitmap {
        var work = b

        val bKey = "${System.identityHashCode(b)}|${p.beauty}|${f.size}"
        if (!p.beauty.isDefault && f.isNotEmpty()) {
            val cached = if (cache && beautyStageKey == bKey) beautyStage else null
            work = cached ?: AiEngine.beauty(
                b, if (faceScale == 1f) f else f.map {
                    it.copy(cx = it.cx * faceScale, cy = it.cy * faceScale, w = it.w * faceScale, h = it.h * faceScale)
                },
                p.beauty.smooth, p.beauty.whiten, p.beauty.blemish, p.beauty.slim
            ).also { if (cache) { beautyStage = it; beautyStageKey = bKey } }
        }

        val cKey = "$bKey|${p.filterId}|${p.adjust}"
        val cachedColor = if (cache && colorStageKey == cKey) colorStage else null
        work = cachedColor ?: run {
            var x = ImageOps.applyColor(
                work, p.filter, p.adjust.brightness, p.adjust.contrast,
                p.adjust.saturation, p.adjust.warmth, p.adjust.vignette
            )
            if (p.adjust.sharpen > 0.01f) x = ImageOps.sharpen(x, p.adjust.sharpen)
            if (cache && p.overlays.isEmpty() && p.bg.mode == BgMode.NONE) {
                colorStage = x; colorStageKey = cKey
            }
            x
        }

        if (p.bg.mode != BgMode.NONE && m != null) {
            val mm = if (m.width == work.width && m.height == work.height) m
            else Bitmap.createScaledBitmap(m, work.width, work.height, true)
            work = AiEngine.compositeBackground(
                work, mm, p.bg.mode.name.lowercase(), p.bg.color, bgI, p.bg.blur
            )
        }

        if (p.overlays.isNotEmpty()) work = Renderer.drawOverlays(work, p.overlays)
        return work
    }

    // ---------------- 导出 ----------------

    fun export(onDone: (Uri?) -> Unit) {
        val b = base ?: return onDone(null)
        busyLabel = "导出中"
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                val outBase = BitmapUtils.scaleDown(b, EXPORT_MAX)
                val scale = outBase.width.toFloat() / (previewBase?.width ?: outBase.width)
                val m = if (params.bg.mode == BgMode.NONE) null else mask
                val out = compose(outBase, params, faces, m, bgImage, scale, cache = false)
                BitmapUtils.saveToGallery(
                    getApplication(),
                    out,
                    isPng = params.bg.mode == BgMode.TRANSPARENT
                )
            }
            busyLabel = null
            savedUri = uri
            onDone(uri)
        }
    }
}
