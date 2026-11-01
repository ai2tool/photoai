package com.yixiu.photo.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.yixiu.photo.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新。版本清单放在仓库 `update/app-version.json`：
 *
 * ```json
 * {"versionCode":2,"versionName":"1.1","url":"https://…/photoai-1.1.apk",
 *  "sha256":"…","size":71303168,"notes":"新增自动更新","force":false}
 * ```
 *
 * 下载完成后按 sha256 校验；即便校验被绕过，Android 也只接受与已装应用同一签名的安装包，
 * 被篡改的包无法覆盖安装。
 */
object AppUpdate {

    class Info(
        val code: Int,
        val name: String,
        val url: String,
        val sha256: String,
        val notes: String,
        val size: Long,
        val force: Boolean,
        /** 清单里显式给的备用下载地址（可选），会排在自动展开的镜像前面。 */
        val mirrors: List<String> = emptyList()
    ) {
        /** 这条版本可以尝试的所有下载地址：显式备用地址 → 直连 → 各代理。 */
        fun candidates(): List<String> {
            val out = LinkedHashSet<String>()
            mirrors.filter { it.startsWith("http://") || it.startsWith("https://") }.forEach { out.add(it) }
            out.addAll(mirrorsOf(url, size))
            return out.toList().ifEmpty { listOf(url) }
        }
    }

    /** [info] 为空表示：已是最新、用户已忽略该版本，或清单不存在。 */
    class Check(val info: Info?, val error: String?)

    const val DEFAULT_BASE = "https://raw.githubusercontent.com/ai2tool/photoai/main/update"

    fun manifestUrl(): String = BuildConfig.UPDATE_BASE.trim().trimEnd('/') + "/app-version.json"

    private const val GH_PATH = "ai2tool/photoai/main/update/app-version.json"
    private const val RAW_PREFIX = "https://raw.githubusercontent.com/"
    private const val GH_PROXY = "https://gh-proxy.com/" + RAW_PREFIX

    /** 默认源在 GitHub，国内直连不稳，同一份文件同时问几个代理，谁快用谁。 */
    private val MIRRORS: List<String> = mirrorsOf(GH_PROXY + GH_PATH)

    /**
     * 把 GitHub raw 地址展开成「代理 → 直连」的候选列表，非 GitHub 地址原样返回。
     * [size] 已知大小时用来跳过装不下大文件的线路（jsDelivr 单文件上限约 20MB）。
     */
    private fun mirrorsOf(url: String, size: Long = -1L): List<String> {
        val path = when {
            url.startsWith(GH_PROXY) -> url.removePrefix(GH_PROXY)
            url.startsWith(RAW_PREFIX) -> url.removePrefix(RAW_PREFIX)
            else -> return listOf(url)
        }
        val out = mutableListOf(
            GH_PROXY + path,
            "https://ghfast.top/" + RAW_PREFIX + path,
            "https://ghproxy.net/" + RAW_PREFIX + path,
            RAW_PREFIX + path
        )
        if (size <= 0 || size <= 20L * 1024 * 1024) {
            out.add("https://cdn.jsdelivr.net/gh/" + path.replaceFirst("/main/", "@main/"))
        }
        return out
    }

    private fun manifestCandidates(): List<String> =
        if (BuildConfig.UPDATE_BASE.trimEnd('/') == DEFAULT_BASE) MIRRORS else listOf(manifestUrl())

    private const val UA = "Yixiu/${BuildConfig.VERSION_NAME} (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ---------------- 检查 ----------------

    suspend fun check(ignoredCode: Int?): Check = withContext(Dispatchers.IO) {
        var firstError: String? = null
        var newest: JSONObject? = null
        for (url in manifestCandidates()) {
            val (o, err) = fetchManifest(url)
            if (o == null) {
                if (err != null) firstError = firstError ?: err
                continue
            }
            if (newest == null || o.optInt("versionCode", 0) > newest.optInt("versionCode", 0)) newest = o
            // 代理之间版本可能不同步，但拿到一份比当前新的就够了，不必等齐所有镜像
            if (o.optInt("versionCode", 0) > BuildConfig.VERSION_CODE) break
        }
        val o = newest ?: return@withContext Check(null, firstError)
        val code = o.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return@withContext Check(null, null)
        val url = o.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext Check(null, "更新信息里没有下载地址")
        }
        // 清单可以额外给出备用下载地址（自建镜像 / 临时提速），有就排在自动展开的线路前面
        val mirrors = o.optJSONArray("mirrors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        } ?: emptyList()
        val info = Info(
            code = code,
            name = o.optString("versionName").ifEmpty { code.toString() },
            url = url,
            sha256 = o.optString("sha256").lowercase(),
            notes = o.optString("notes"),
            size = o.optLong("size"),
            force = o.optBoolean("force"),
            mirrors = mirrors
        )
        if (!info.force && ignoredCode == code) Check(null, null) else Check(info, null)
    }

    private fun fetchManifest(url: String): Pair<JSONObject?, String?> = try {
        client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { r ->
                when {
                    r.code == 404 -> null to null // 这个镜像没有清单，不算错误
                    !r.isSuccessful -> null to "HTTP ${r.code}"
                    else -> {
                        val text = r.body?.string().orEmpty()
                        runCatching { JSONObject(text) to null }
                            .getOrElse { null to "更新信息格式不对" }
                    }
                }
            }
    } catch (e: Exception) {
        null to reason(e)
    }

    /** 底层网络异常翻成用户看得懂的话；弹窗里直接显示，别再抛英文栈信息给人看。 */
    private fun reason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "连不上服务器，域名解析失败"
        is java.net.SocketTimeoutException -> "连接超时，换个网络试试"
        is java.net.ConnectException -> "服务器拒绝连接"
        is javax.net.ssl.SSLException -> "安全连接失败，稍后再试"
        is java.io.IOException -> "网络中断"
        else -> "网络异常（${e.javaClass.simpleName}）"
    }

    // ---------------- 下载 ----------------

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * 缓存里已下载完成且校验通过的包；同一版本号可能被重新发布，所以每次都重新算 sha256。
     * 会读整个文件，别在主线程调用。
     */
    fun ready(context: Context, info: Info): File? {
        val file = File(updatesDir(context), "photoai-${info.code}.apk")
        if (!file.isFile || file.length() == 0L) return null
        if (info.size > 0 && file.length() != info.size) return null
        if (info.sha256.isNotEmpty() && sha256(file) != info.sha256) {
            file.delete()
            return null
        }
        return file
    }

    /** 上次中断时残留的字节数；下次从这里接着下。 */
    fun partialBytes(context: Context, info: Info): Long =
        File(updatesDir(context), "photoai-${info.code}.apk.part").length()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 下载到缓存目录并校验 sha256，失败抛带可读中文原因的异常。
     *
     * 线路：清单里显式给的备用地址 + 直连 + 各代理，哪条先连上先用。
     * 断点续传：上次留下的 `.part` 用 Range 请求接着下；这次下载中某条线路断流，已落盘的字节保留，
     * 换下一条线路继续 Range；镜像忽略 Range 返回 200 时从头重下（摘要同步重置）。
     */
    suspend fun download(
        context: Context,
        info: Info,
        onProgress: (done: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context)
        val part = File(dir, "photoai-${info.code}.apk.part")
        val file = File(dir, "photoai-${info.code}.apk")
        dir.listFiles()?.forEach { if (it != part) it.delete() } // 其它版本、或上次校验失败的包

        var have = part.length()
        if (info.size in 1..have) { // 残留比整包还大，不能用
            part.delete()
            have = 0
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (have > 0) part.inputStream().use { hashInto(digest, it) }

        val urls = info.candidates()
        var lastError: String? = null
        var completed = false
        var round = 0
        while (!completed && round < 2) { // 一轮走完所有线路还没下完，再来一轮（网络抖动）
            round++
            var progressed = false
            for (u in urls) {
                ensureActive()
                val start = part.length()
                if (info.size > 0 && start >= info.size) {
                    completed = true
                    break
                }
                when (val r = streamTo(u, part, digest, start, info.size, onProgress)) {
                    Attempt.Done -> {
                        completed = true
                        lastError = null
                    }
                    is Attempt.Partial -> {
                        progressed = true // 断流也算推进，part 里多了一些字节
                        lastError = r.reason
                    }
                    is Attempt.Failed -> lastError = r.reason
                }
                if (completed) break
            }
            if (!progressed && !completed) break // 一条线路都没能推进，别空转
        }
        if (!completed) {
            val saved = part.length()
            // 已保存的字节数由弹窗单独展示，这里只说原因，避免同一句话出现两遍
            throw IllegalStateException(
                if (saved > 0) "网络不稳定，没有下完（${lastError ?: "所有线路都不通"}）"
                else "下载失败（${lastError ?: "所有线路都连不上"}）"
            )
        }

        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotEmpty() && hex != info.sha256) {
            part.delete()
            throw IllegalStateException("校验失败：下载的文件和发布信息对不上，已丢弃")
        }
        if (!part.renameTo(file)) throw IllegalStateException("保存更新文件失败")
        file
    }

    private sealed class Attempt {
        /** 读到文件尾。 */
        object Done : Attempt()
        /** 中途断了，已写入的字节留在 .part 里。 */
        class Partial(val reason: String) : Attempt()
        /** 这条线路没打通，一个字节都没写。 */
        class Failed(val reason: String) : Attempt()
    }

    private fun hashInto(digest: MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }

    /** 用一条线路把 [part] 下到文件尾；[start] 是已有字节数，>0 时带 Range 续传。 */
    private suspend fun streamTo(
        url: String,
        part: File,
        digest: MessageDigest,
        start: Long,
        size: Long,
        onProgress: (done: Long, total: Long) -> Unit
    ): Attempt {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .apply { if (start > 0) header("Range", "bytes=$start-") }
            .build()
        val resp = try {
            client.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(req).execute()
        } catch (e: Exception) {
            return Attempt.Failed(reason(e))
        }

        resp.use { r ->
            if (!r.isSuccessful && r.code != 206) {
                // 416：服务端认为续传位置不合法，丢掉残留从头来
                if (r.code == 416 && start > 0) {
                    part.delete()
                    return Attempt.Failed("续传位置无效，已丢弃残留")
                }
                return Attempt.Failed("HTTP ${r.code}")
            }
            val body = r.body ?: return Attempt.Failed("下载失败：没有内容")
            val resuming = start > 0 && r.code == 206
            if (!resuming) digest.reset() // 线路不支持 Range，整包重下
            var done = if (resuming) start else 0L
            val total = body.contentLength().takeIf { it > 0 }?.plus(done) ?: size
            try {
                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, resuming).use { out ->
                        val buf = ByteArray(64 * 1024)
                        onProgress(done, total)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Attempt.Partial(reason(e))
            }
        }
        return Attempt.Done
    }

    // ---------------- 安装 ----------------

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 拉起系统安装器。传 Application 也行（下载完自动装的时候就是它），会自动补 NEW_TASK。 */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 少数机型没有直达页面，返回 false 时由界面引导用户手动去开。 */
    fun openInstallSettings(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}
