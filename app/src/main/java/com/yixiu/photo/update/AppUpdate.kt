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
        val force: Boolean
    )

    /** [info] 为空表示：已是最新、用户已忽略该版本，或清单不存在。 */
    class Check(val info: Info?, val error: String?)

    const val DEFAULT_BASE = "https://raw.githubusercontent.com/ai2tool/photoai/main/update"

    fun manifestUrl(): String = BuildConfig.UPDATE_BASE.trim().trimEnd('/') + "/app-version.json"

    private const val GH_PATH = "ai2tool/photoai/main/update/app-version.json"
    private const val GH_PROXY = "https://gh-proxy.com/https://raw.githubusercontent.com/"

    /** 默认源在 GitHub，国内直连不稳，同一份文件同时问几个代理，谁快用谁。 */
    private val MIRRORS: List<String> = mirrorsOf(GH_PROXY + GH_PATH)

    private fun mirrorsOf(url: String): List<String> {
        val prefix = GH_PROXY
        if (!url.startsWith(prefix)) return listOf(url)
        val path = url.removePrefix(prefix)
        return listOf(
            prefix + path,
            "https://ghfast.top/https://raw.githubusercontent.com/" + path,
            "https://cdn.jsdelivr.net/gh/" + path.replaceFirst("/main/", "@main/"),
            "https://raw.githubusercontent.com/" + path,
            "https://ghproxy.net/https://raw.githubusercontent.com/" + path
        )
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
        val info = Info(
            code = code,
            name = o.optString("versionName").ifEmpty { code.toString() },
            url = url,
            sha256 = o.optString("sha256").lowercase(),
            notes = o.optString("notes"),
            size = o.optLong("size"),
            force = o.optBoolean("force")
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
        null to (e.message ?: e.javaClass.simpleName)
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
     * 断点续传：上次留下的 `.part` 用 Range 请求接着下，先把已有字节哈希进去，校验仍覆盖整个文件；
     * 镜像忽略 Range 返回 200 时，重新从头下载。
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
        if (have > 0) part.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }

        val urls = mirrorsOf(info.url)
        var lastError: String? = null
        var response: okhttp3.Response? = null
        for (u in urls) {
            val req = Request.Builder().url(u).header("User-Agent", UA)
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            val r = runCatching {
                client.newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(req).execute()
            }.getOrElse { e ->
                lastError = e.message ?: "连接失败"
                null
            } ?: continue
            if (r.isSuccessful || r.code == 206) {
                response = r
                break
            }
            lastError = "HTTP ${r.code}"
            // 416：服务端认为续传位置不合法，丢掉残留重来
            if (r.code == 416 && have > 0) {
                r.close()
                part.delete()
                return@withContext download(context, info, onProgress)
            }
            r.close()
        }
        val r = response ?: throw IllegalStateException("下载失败（" + (lastError ?: "所有线路都连不上") + "）")

        r.use { resp ->
            val body = resp.body ?: throw IllegalStateException("下载失败：没有内容")
            val resuming = have > 0 && resp.code == 206
            if (!resuming) {
                digest.reset()
                have = 0
            }
            val total = body.contentLength().takeIf { it > 0 }?.plus(have) ?: info.size
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resuming).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    onProgress(done, total)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotEmpty() && hex != info.sha256) {
            part.delete()
            throw IllegalStateException("校验失败：下载的文件和发布信息对不上，已丢弃")
        }
        if (!part.renameTo(file)) throw IllegalStateException("保存更新文件失败")
        file
    }

    // ---------------- 安装 ----------------

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
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
