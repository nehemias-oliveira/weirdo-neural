package com.weirdo.neural.core.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.weirdo.neural.core.data.db.dao.InstalledModelDao
import com.weirdo.neural.core.data.db.entity.InstalledModelEntity
import com.weirdo.neural.core.data.model.ModelCatalog
import com.weirdo.neural.core.data.model.ModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepo: ModelCatalogRepository,
    private val downloader: ModelDownloader,
    private val installedDao: InstalledModelDao,
) {
    private companion object {
        const val TAG = "ModelsRepository"
    }

    private val modelsDir: File by lazy {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    fun getModelsDir(): File = modelsDir

    fun getPartialFile(fileName: String): File = File(modelsDir, "$fileName.part")

    // -----------------------------------------------------------------------
    // Catálogo
    // -----------------------------------------------------------------------

    suspend fun getCatalog(forceRefresh: Boolean = false): ModelCatalog =
        catalogRepo.getCatalog(forceRefresh)

    // -----------------------------------------------------------------------
    // Instalados (Room)
    // -----------------------------------------------------------------------

    fun observeInstalled(): Flow<List<InstalledModelEntity>> = installedDao.observeAll()

    suspend fun findInstalled(modelId: String): InstalledModelEntity? =
        installedDao.findById(modelId)

    // -----------------------------------------------------------------------
    // Download do catálogo
    // -----------------------------------------------------------------------

    fun download(info: ModelInfo): Flow<DownloadProgress> = flow {
        val target = File(modelsDir, info.fileName)

        // Já existe completo? Registra e encerra.
        if (target.exists() && target.length() == info.sizeBytes) {
            Log.i(TAG, "${info.fileName} já existe, registrando")
            installedDao.upsert(info.toEntity(target))
            emit(DownloadProgress.Completed(target))
            return@flow
        }

        downloader.download(info.url, target, info.sha256).collect { progress ->
            emit(progress)
            if (progress is DownloadProgress.Completed) {
                installedDao.upsert(info.toEntity(progress.file))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Exclusão
    // -----------------------------------------------------------------------

    suspend fun delete(modelId: String) {
        val entity = installedDao.findById(modelId) ?: return
        withContext(Dispatchers.IO) {
            File(entity.absolutePath).delete()
            File(entity.absolutePath + ".part").delete()
        }
        installedDao.deleteById(modelId)
    }

    // -----------------------------------------------------------------------
    // Importação via SAF (compartilhada com o Chat)
    // -----------------------------------------------------------------------

    suspend fun importFromUri(uri: Uri): InstalledModelEntity = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
            ?: "imported-${System.currentTimeMillis()}.gguf"
        val target = File(modelsDir, displayName)

        if (!target.exists() || target.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            } ?: throw IllegalStateException("Não foi possível ler o arquivo")
        }

        val id = "imported-${displayName}"
        val entity = InstalledModelEntity(
            modelId = id,
            displayName = displayName.removeSuffix(".gguf"),
            fileName = displayName,
            absolutePath = target.absolutePath,
            sizeBytes = target.length(),
            contextSize = 4096,
            sha256 = null,
            source = "imported",
        )
        installedDao.upsert(entity)
        entity
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun ModelInfo.toEntity(file: File) = InstalledModelEntity(
        modelId = id,
        displayName = name,
        fileName = file.name,
        absolutePath = file.absolutePath,
        sizeBytes = file.length(),
        contextSize = contextSize,
        sha256 = sha256,
        source = "catalog",
    )
}
