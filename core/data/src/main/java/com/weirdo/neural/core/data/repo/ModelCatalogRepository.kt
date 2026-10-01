package com.weirdo.neural.core.data.repo

import android.content.Context
import android.util.Log
import com.weirdo.neural.core.data.model.ModelCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private companion object {
        const val TAG = "ModelCatalog"
        const val REMOTE_URL =
            "https://raw.githubusercontent.com/nehemias-oliveira/weirdo-neural/main/models.json"
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24h
        const val CACHE_FILENAME = "models_cache.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Retorna o catálogo, tentando o remoto primeiro (com TTL), depois o cache,
     * depois o asset.
     */
    suspend fun getCatalog(forceRefresh: Boolean = false): ModelCatalog =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, CACHE_FILENAME)

            // 1. Cache ainda fresco?
            if (!forceRefresh && cacheFile.exists()) {
                val age = System.currentTimeMillis() - cacheFile.lastModified()
                if (age < CACHE_TTL_MS) {
                    readCatalogFrom(cacheFile)?.let {
                        Log.d(TAG, "Catálogo servido do cache (${age / 1000}s de idade)")
                        return@withContext it
                    }
                }
            }

            // 2. Tenta fetch remoto
            fetchRemote(cacheFile)?.let {
                Log.i(TAG, "Catálogo atualizado do remoto (${it.models.size} modelos)")
                return@withContext it
            }

            // 3. Cache antigo (mesmo expirado)
            readCatalogFrom(cacheFile)?.let {
                Log.w(TAG, "Usando cache expirado por falha no fetch")
                return@withContext it
            }

            // 4. Asset embutido no APK
            readCatalogFromAssets()?.let {
                Log.w(TAG, "Usando catálogo do asset (primeira execução offline?)")
                return@withContext it
            }

            // 5. Vazio — nunca deveria chegar aqui
            Log.e(TAG, "Nenhum catálogo disponível")
            ModelCatalog()
        }

    private fun fetchRemote(cacheFile: File): ModelCatalog? = try {
        val req = Request.Builder().url(REMOTE_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} ao buscar catálogo")
                null
            } else {
                val body = resp.body?.string()
                if (body.isNullOrBlank()) null
                else {
                    val catalog = json.decodeFromString<ModelCatalog>(body)
                    // Salva no cache para uso futuro
                    cacheFile.writeText(body)
                    catalog
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Falha no fetch remoto: ${t.message}")
        null
    }

    private fun readCatalogFrom(file: File): ModelCatalog? = try {
        if (!file.exists()) null
        else json.decodeFromString<ModelCatalog>(file.readText())
    } catch (t: Throwable) {
        Log.w(TAG, "Erro lendo cache: ${t.message}")
        null
    }

    private fun readCatalogFromAssets(): ModelCatalog? = try {
        context.assets.open("models.json").bufferedReader().use {
            json.decodeFromString<ModelCatalog>(it.readText())
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Erro lendo asset: ${t.message}")
        null
    }
}
