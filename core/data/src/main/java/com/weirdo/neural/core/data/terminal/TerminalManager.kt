package com.weirdo.neural.core.data.terminal

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "TerminalManager"
        private const val ALPINE_DIR = "alpine"
        private const val ROOTFS_DIR = "rootfs"
        private const val READY_FLAG = ".ready"
        private const val ROOTFS_ASSET = "alpine-rootfs.bin"

        private const val PROROOT_NAME = "libproroot.so"
    }

    private val alpineDir: File
        get() = File(context.filesDir, ALPINE_DIR).apply { mkdirs() }

    private val rootfsDir: File
        get() = File(alpineDir, ROOTFS_DIR)

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val prorootBinary: File
        get() = File(nativeLibDir, PROROOT_NAME)

    val workspaceDir: File
        get() = File(context.getExternalFilesDir(null), "workspace").apply { mkdirs() }

    val isReady: Boolean
        get() = File(alpineDir, READY_FLAG).exists()
            && prorootBinary.exists()
            && File(rootfsDir, "bin/bash").exists()

    fun prepare(): Flow<PrepareProgress> = flow {
        if (isReady) {
            emit(PrepareProgress.Ready)
            return@flow
        }

        emit(PrepareProgress.Starting)

        try {
            emit(PrepareProgress.ExtractingBinaries)
            if (!prorootBinary.exists()) {
                throw IllegalStateException(
                    "proroot não encontrado em ${prorootBinary.absolutePath}"
                )
            }

            emit(PrepareProgress.ExtractingRootfs(0))
            rootfsDir.mkdirs()
            extractRootfs()
            emit(PrepareProgress.ExtractingRootfs(100))

            val resolv = File(rootfsDir, "etc/resolv.conf")
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            File(rootfsDir, "workspace").mkdirs()

            File(alpineDir, READY_FLAG).writeText(System.currentTimeMillis().toString())

            Log.i(TAG, "Rootfs pronto em ${rootfsDir.absolutePath}")
            emit(PrepareProgress.Ready)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao preparar rootfs", t)
            val detail = buildString {
                append(t.javaClass.simpleName)
                append(": ")
                append(t.message ?: "sem mensagem")
                var cause = t.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    append("\n↳ ")
                    append(cause.javaClass.simpleName)
                    append(": ")
                    append(cause.message ?: "sem mensagem")
                    cause = cause.cause
                    depth++
                }
            }
            emit(PrepareProgress.Error(detail, t))
        }
    }.flowOn(Dispatchers.IO)

    private fun extractRootfs() {
        if (File(rootfsDir, "bin/bash").exists()) {
            Log.i(TAG, "Rootfs já extraído, pulando")
            return
        }

        var entryCount = 0
        var skipped = 0
        var symlinks = 0

        context.assets.open(ROOTFS_ASSET).use { raw ->
            BufferedInputStream(raw).use { buffered ->
                GzipCompressorInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            entryCount++
                            val name = entry.name
                            if (name.contains("..")) {
                                Log.w(TAG, "Path traversal ignorado: $name")
                                skipped++
                                entry = tar.nextEntry
                                continue
                            }

                            val outFile = File(rootfsDir, name)

                            when {
                                entry.isDirectory -> outFile.mkdirs()

                                entry.linkFlag == TarConstants.LF_SYMLINK -> {
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (outFile.exists() || java.nio.file.Files.isSymbolicLink(outFile.toPath())) {
                                            outFile.delete()
                                        }
                                        android.system.Os.symlink(entry.linkName, outFile.absolutePath)
                                        symlinks++
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "symlink falhou $name -> ${entry.linkName}: ${t.message}")
                                    }
                                }

                                entry.linkFlag == TarConstants.LF_LINK -> {
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (outFile.exists()) outFile.delete()
                                        android.system.Os.link(
                                            File(rootfsDir, entry.linkName).absolutePath,
                                            outFile.absolutePath,
                                        )
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "hardlink falhou $name: ${t.message}")
                                    }
                                }

                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out ->
                                        tar.copyTo(out, 64 * 1024)
                                    }
                                    val mode = entry.mode
                                    if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                    outFile.setReadable(true, false)
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Extração: $entryCount entradas, $skipped ignoradas, $symlinks symlinks")
    }

    fun execute(
        command: String,
        workingDir: String = "/root",
    ): Flow<TerminalLine> = callbackFlow {
        if (!isReady) {
            trySend(TerminalLine(TerminalStream.STDERR, "Rootfs não preparado."))
            close()
            return@callbackFlow
        }

        workspaceDir.mkdirs()

        val args = listOf(
            prorootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-0",
            "--link2symlink",
            "-w", workingDir,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "/bin/sh", "-c", command,
        )

        Log.d(TAG, "execute: $command")

        val pb = ProcessBuilder(args)
        pb.environment().clear()
        pb.environment()["PROROOT_TMP_DIR"] = context.cacheDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        pb.directory(alpineDir)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            trySend(TerminalLine(TerminalStream.STDERR, "Falha ao iniciar proroot: ${t.message}"))
            close()
            return@callbackFlow
        }

        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> trySend(TerminalLine(TerminalStream.STDOUT, line)) }
                }
            } catch (_: Throwable) { }
        }

        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> trySend(TerminalLine(TerminalStream.STDERR, line)) }
                }
            } catch (_: Throwable) { }
        }

        stdoutThread.start()
        stderrThread.start()

        val waiterThread = Thread {
            try {
                stdoutThread.join()
                stderrThread.join()
                val exit = process.waitFor()
                trySend(TerminalLine(TerminalStream.EXIT, exit.toString()))
            } catch (_: Throwable) {
            } finally {
                close()
            }
        }
        waiterThread.start()

        awaitClose {
            try {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            } catch (_: Throwable) { }
        }
    }.flowOn(Dispatchers.IO)
}

sealed interface PrepareProgress {
    data object Starting : PrepareProgress
    data object ExtractingBinaries : PrepareProgress
    data class ExtractingRootfs(val percent: Int) : PrepareProgress
    data object Ready : PrepareProgress
    data class Error(val message: String, val cause: Throwable? = null) : PrepareProgress
}

enum class TerminalStream { STDOUT, STDERR, EXIT }

data class TerminalLine(val stream: TerminalStream, val text: String)
