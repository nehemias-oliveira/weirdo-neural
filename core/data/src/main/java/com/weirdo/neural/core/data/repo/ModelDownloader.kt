package com.weirdo.neural.core.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class ModelDownloader @Inject constructor(
    private val http: OkHttpClient,
) {
    private companion object {
        const val TAG = "ModelDownloader"
        const val CHUNK_SIZE = 64 * 1024 // 64 KB
    }

    /**
     * Baixa (ou retoma) um arquivo de [url] para [target].
     * Retorna um Flow com o progresso. Não bloqueia.
     *
     * Se o download for cancelado, o arquivo .part permanece no disco
     * e pode ser retomado na próxima chamada (via HTTP Range).
     */
    fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
    ): Flow<DownloadProgress> = flow {
        val partFile = File(target.absolutePath + ".part")

        try {
            // Descobre se pode retomar
            val existingBytes = if (partFile.exists()) partFile.length() else 0L

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (existingBytes > 0) {
                        addHeader("Range", "bytes=$existingBytes-")
                    }
                }
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(DownloadProgress.Failed("HTTP ${resp.code}"))
                    return@flow
                }

                // Se o servidor respondeu 200 (ignorou o Range), recomeça do zero
                val resuming = resp.code == 206 && existingBytes > 0
                val totalBytes = if (resuming) {
                    // Content-Range: bytes START-END/TOTAL
                    val contentRange = resp.header("Content-Range") ?: ""
                    contentRange.substringAfterLast('/').toLongOrNull() ?: 0L
                } else {
                    resp.body?.contentLength() ?: -1L
                }

                val body = resp.body ?: run {
                    emit(DownloadProgress.Failed("Resposta sem corpo"))
                    return@flow
                }

                emit(DownloadProgress.Started(totalBytes))

                var downloaded = if (resuming) existingBytes else 0L
                if (!resuming) {
                    // Se não está retomando, trunca o .part
                    partFile.delete()
                }

                val output = FileOutputStream(partFile, resuming)
                val input = body.byteStream()

                // Timer para calcular velocidade
                var lastReport = System.currentTimeMillis()
                var lastBytes = downloaded

                val buffer = ByteArray(CHUNK_SIZE)
                var read: Int

                try {
                    while (input.read(buffer).also { read = it } > 0) {
                        coroutineContext.ensureActive() // Cancellation-aware
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 500) {
                            val elapsed = (now - lastReport) / 1000.0
                            val speed = if (elapsed > 0)
                                ((downloaded - lastBytes) / elapsed).toLong() else 0L
                            emit(DownloadProgress.Progress(downloaded, totalBytes, speed))
                            lastReport = now
                            lastBytes = downloaded
                        }
                    }
                } finally {
                    output.flush()
                    output.close()
                    input.close()
                }

                emit(DownloadProgress.Progress(downloaded, totalBytes, 0))

                // Verificação SHA256
                if (!expectedSha256.isNullOrBlank()) {
                    emit(DownloadProgress.Verifying)
                    val actual = sha256Of(partFile)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        emit(DownloadProgress.Failed(
                            "Hash SHA256 não confere.\nEsperado: $expectedSha256\nObtido:   $actual"
                        ))
                        return@flow
                    }
                } else {
                    Log.w(TAG, "Nenhum SHA256 fornecido para $url — pulando verificação")
                }

                // Renomeia .part → final
                if (target.exists()) target.delete()
                if (!partFile.renameTo(target)) {
                    emit(DownloadProgress.Failed("Não foi possível mover o arquivo final"))
                    return@flow
                }

                emit(DownloadProgress.Completed(target))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Erro no download", t)
            emit(DownloadProgress.Failed(t.message ?: "Erro desconhecido"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
