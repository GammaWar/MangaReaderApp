package com.mangareader.app.data.download

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.DownloadState
import com.mangareader.app.data.model.DownloadedChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DownloadRepository(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun startDownload(chapter: Chapter): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workId = UUID.randomUUID()

        // Write image URLs to a disk file to completely avoid WorkManager's 10KB Data limit!
        val jobFile = File(context.cacheDir, "download_jobs/$workId.json")
        jobFile.parentFile?.mkdirs()
        try {
            val jobJson = JSONObject().apply {
                put("title", chapter.title)
                put("sourceUrl", chapter.chapterUrl)
                val arr = JSONArray()
                chapter.images.forEach { arr.put(it) }
                put("images", arr)
            }
            jobFile.writeText(jobJson.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val inputData = workDataOf(
            MangaDownloadWorker.KEY_JOB_FILE to jobFile.absolutePath,
            MangaDownloadWorker.KEY_CHAPTER_TITLE to chapter.title,
            MangaDownloadWorker.KEY_SOURCE_URL to chapter.chapterUrl
        )

        val workRequest = OneTimeWorkRequestBuilder<MangaDownloadWorker>()
            .setId(workId)
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("manga_download")
            .build()

        workManager.enqueue(workRequest)
        return workId
    }

    fun observeWork(workId: UUID): Flow<DownloadState> {
        return workManager.getWorkInfoByIdLiveData(workId).asFlow().map { workInfo ->
            if (workInfo == null) return@map DownloadState.Idle

            when (workInfo.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getFloat(MangaDownloadWorker.KEY_PROGRESS, 0f)
                    val current = workInfo.progress.getInt(MangaDownloadWorker.KEY_CURRENT_PAGE, 0)
                    val total = workInfo.progress.getInt(MangaDownloadWorker.KEY_TOTAL_PAGES, 0)
                    val title = workInfo.progress.getString(MangaDownloadWorker.KEY_CHAPTER_TITLE) ?: "Kapitel"
                    DownloadState.InProgress(progress, current, total, title)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val title = workInfo.outputData.getString(MangaDownloadWorker.KEY_CHAPTER_TITLE) ?: "Kapitel"
                    DownloadState.Completed(title)
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString("ERROR") ?: "Download fehlgeschlagen"
                    DownloadState.Failed(error)
                }
                WorkInfo.State.CANCELLED -> {
                    DownloadState.Failed("Download abgebrochen")
                }
                else -> DownloadState.Idle
            }
        }
    }

    suspend fun getDownloadedChapters(): List<DownloadedChapter> = withContext(Dispatchers.IO) {
        val baseDir = File(context.filesDir, "manga_downloads")
        if (!baseDir.exists() || !baseDir.isDirectory) return@withContext emptyList()

        val chapters = mutableListOf<DownloadedChapter>()
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val metaFile = File(dir, "metadata.json")
                if (metaFile.exists()) {
                    try {
                        val json = JSONObject(metaFile.readText())
                        val id = json.optString("id", dir.name)
                        val title = json.optString("title", dir.name)
                        val sourceUrl = json.optString("sourceUrl", "")
                        val timestamp = json.optLong("timestamp", dir.lastModified())
                        
                        val imageFiles = mutableListOf<String>()
                        val filesArr = json.optJSONArray("files")
                        if (filesArr != null) {
                            for (i in 0 until filesArr.length()) {
                                imageFiles.add(filesArr.getString(i))
                            }
                        } else {
                            dir.listFiles { _, name ->
                                name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp")
                            }?.sortedBy { it.name }?.forEach { imageFiles.add(it.absolutePath) }
                        }

                        chapters.add(
                            DownloadedChapter(
                                id = id,
                                title = title,
                                sourceUrl = sourceUrl,
                                imageFilePaths = imageFiles,
                                downloadTimestamp = timestamp,
                                totalPages = imageFiles.size
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        chapters.sortedByDescending { it.downloadTimestamp }
    }

    suspend fun deleteChapter(chapterId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "manga_downloads/$chapterId")
        if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            false
        }
    }
}
