package com.weirdo.neural.core.data.repo

import java.io.File

sealed class DownloadProgress {
    data class Started(val totalBytes: Long) : DownloadProgress()
    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long = 0,
    ) : DownloadProgress() {
        val percent: Int get() = if (totalBytes <= 0) 0
            else ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }
    data object Verifying : DownloadProgress()
    data class Completed(val file: File) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
    data object Cancelled : DownloadProgress()
}
