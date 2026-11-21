package com.mangareader.app.data.download

import android.content.Context
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangareader.app.MangaReaderApplication
import com.mangareader.app.data.extractor.UrlChapterAnalyzer
import com.mangareader.app.data.network.BitmapDecodeGuard
import com.mangareader.app.util.AppConstants
import com.mangareader.app.util.UrlSafety
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MangaDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val httpClient: OkHttpClient by lazy {
        (applicationContext as? MangaReaderApplication)?.okHttpClient
            ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }

    override suspend fun doWork(): Result {
        val chapterTitle = inputData.getString(KEY_CHAPTER_TITLE) ?: "Kapitel"
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: ""
        val jobFilePath = inputData.getString(KEY_JOB_FILE)
        val imageUrls: Array<String> = if (!jobFilePath.isNullOrBlank()) {
            try {
                val file = File(jobFilePath)
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    val arr = json.optJSONArray("images") ?: JSONArray()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    list.toTypedArray()
                } else {
                    inputData.getStringArray(KEY_IMAGE_URLS) ?: emptyArray()
                }
            } catch (e: Exception) {
                inputData.getStringArray(KEY_IMAGE_URLS) ?: emptyArray()
            }
        } else {
            inputData.getStringArray(KEY_IMAGE_URLS) ?: emptyArray()
        }

        if (imageUrls.isEmpty()) {
            jobFilePath?.let { File(it).delete() }
            return Result.failure(workDataOf("ERROR" to "Keine Bild-URLs übergeben"))
        }

        // Collision-safe, Unicode/CJK-preserving folder ID via FNV-1a hash
        val folderName = UrlChapterAnalyzer.buildDownloadFolderId(chapterTitle, sourceUrl)
        val downloadDir = File(applicationContext.filesDir, "manga_downloads/$folderName")
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        val downloadedFiles = mutableListOf<String>()
        val total = imageUrls.size

        val sourceCookie = runCatching {
            CookieManager.getInstance().getCookie(sourceUrl)
        }.getOrNull()

        for (i in imageUrls.indices) {
            val url = imageUrls[i]
            if (!UrlSafety.validateExternalUrl(url).valid) {
                continue
            }

            val extension = when {
                url.contains(".png", ignoreCase = true) -> ".png"
                url.contains(".webp", ignoreCase = true) -> ".webp"
                else -> ".jpg"
            }
            val fileName = String.format("page_%03d%s", i + 1, extension)
            val destFile = File(downloadDir, fileName)

            // Resume: Skip downloading if already exists and non-empty
            if (destFile.exists() && destFile.length() > 0) {
                downloadedFiles.add(destFile.absolutePath)
                continue
            }

            try {
                val imgCookie = runCatching {
                    CookieManager.getInstance().getCookie(url) ?: sourceCookie
                }.getOrDefault(sourceCookie)

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", AppConstants.USER_AGENT)
                    .apply {
                        if (sourceUrl.isNotBlank()) header("Referer", sourceUrl)
                        if (!imgCookie.isNullOrBlank()) header("Cookie", imgCookie)
                    }
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code != 404 && runAttemptCount < 2) {
                            return Result.retry()
                        }
                        return@use
                    }

                    val contentType = response.header("Content-Type")
                    val peekPrefix = runCatching { response.peekBody(256).string() }.getOrDefault("")
                    val challengeType = BitmapDecodeGuard.detectSecurityChallengeContentType(contentType, peekPrefix)
                    if (challengeType != null || (!contentType.isNullOrBlank() && !BitmapDecodeGuard.isValidImageContentType(contentType))) {
                        // Never save HTML/XML challenge pages as offline images
                        return@use
                    }

                    val body = response.body ?: return@use
                    val tempFile = File(downloadDir, "$fileName.tmp")
                    FileOutputStream(tempFile).use { out ->
                        body.byteStream().copyTo(out)
                    }
                    if (tempFile.length() > 0) {
                        if (destFile.exists()) destFile.delete()
                        tempFile.renameTo(destFile)
                        downloadedFiles.add(destFile.absolutePath)
                    } else {
                        tempFile.delete()
                    }
                }

                val progress = ((i + 1).toFloat() / total.toFloat())
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to progress,
                        KEY_CURRENT_PAGE to (i + 1),
                        KEY_TOTAL_PAGES to total,
                        KEY_CHAPTER_TITLE to chapterTitle
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                if (runAttemptCount < 2) {
                    return Result.retry()
                }
            }
        }

        if (downloadedFiles.isEmpty()) {
            jobFilePath?.let { File(it).delete() }
            return Result.failure(workDataOf("ERROR" to "Download fehlgeschlagen - keine gültigen Bilder geladen."))
        }

        try {
            val metaFile = File(downloadDir, "metadata.json")
            val meta = JSONObject().apply {
                put("id", folderName)
                put("title", chapterTitle)
                put("sourceUrl", sourceUrl)
                put("totalPages", downloadedFiles.size)
                put("timestamp", System.currentTimeMillis())
                val filesArr = JSONArray()
                downloadedFiles.forEach { filesArr.put(it) }
                put("files", filesArr)
            }
            metaFile.writeText(meta.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        jobFilePath?.let { File(it).delete() }

        return Result.success(
            workDataOf(
                KEY_CHAPTER_TITLE to chapterTitle,
                KEY_TOTAL_PAGES to downloadedFiles.size,
                KEY_DEST_DIR to downloadDir.absolutePath
            )
        )
    }

    companion object {
        const val KEY_JOB_FILE = "job_file"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_IMAGE_URLS = "image_urls"

        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_PAGE = "current_page"
        const val KEY_TOTAL_PAGES = "total_pages"
        const val KEY_DEST_DIR = "dest_dir"
    }
}
