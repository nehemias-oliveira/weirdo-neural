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
    }

    private val alpineDir: File
        get() = File(context.filesDir, ALPINE_DIR).apply { mkdirs() }

    private val rootfsDir: File
        get() = File(alpineDir, ROOTFS_DIR)

    private val prootBinary: File
        get() = File(alpineDir, "proot")

    private val libTalloc: File
        get() = File(alpineDir, "libtalloc.so.2")

    private val libShmem: File
        get() = File(alpineDir, "libandroid-shmem.so")

    /**
     * Pasta compartilhada entre o Android e o Alpine.
     * No Android: getExternalFilesDir()/workspace/
     * No Alpine: /workspace/
     */
    val workspaceDir: File
        get() = File(context.getExternalFilesDir(null), "workspace").apply { mkdirs() }

    val isReady: Boolean
        get() = File(alpineDir, READY_FLAG).exists()
            && prootBinary.exists()
            && File(rootfsDir, "bin/busybox").exists()

    // -----------------------------------------------------------------------
    // Prepare (primeira execução)
    // -----------------------------------------------------------------------

    fun prepare(): Flow<PrepareProgress> = flow {
        if (isReady) {
            emit(PrepareProgress.Ready)
            return@flow
        }

        emit(PrepareProgress.Starting)

        try {
            // 1. Binários
            emit(PrepareProgress.ExtractingBinaries)
            copyAsset("proot", prootBinary)
            copyAsset("libtalloc.so.2", libTalloc)
            copyAsset("libandroid-shmem.so", libShmem)
            prootBinary.setExecutable(true, false)
            libTalloc.setReadable(true, false)
            libShmem.setReadable(true, false)

            // 2. Rootfs
            emit(PrepareProgress.ExtractingRootfs(0))
            rootfsDir.mkdirs()
            extractRootfs()
            emit(PrepareProgress.ExtractingRootfs(100))

            // 3. DNS padrão
            val resolv = File(rootfsDir, "etc/resolv.conf")
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // 4. Workspace dentro do rootfs (o bind cobre isso, mas garante o dir)
            File(rootfsDir, "workspace").mkdirs()

            // 5. Flag
            File(alpineDir, READY_FLAG).writeText(System.currentTimeMillis().toString())

            Log.i(TAG, "Alpine pronto em ${alpineDir.absolutePath}")
            emit(PrepareProgress.Ready)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao preparar Alpine", t)
            emit(PrepareProgress.Error(t.message ?: "Erro desconhecido", t))
        }
    }.flowOn(Dispatchers.IO)

    private fun copyAsset(assetName: String, target: File) {
        if (target.exists() && target.length() > 0) return
        context.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
    }

    private fun extractRootfs() {
        if (File(rootfsDir, "bin/busybox").exists()) {
            Log.i(TAG, "Rootfs já extraído, pulando")
            return
        }

        context.assets.open("alpine-rootfs.tar.gz").use { raw ->
            BufferedInputStream(raw).use { buffered ->
                GzipCompressorInputStream(buffered).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val outFile = File(rootfsDir, entry.name)
                            val canonicalTarget = outFile.canonicalPath
                            val canonicalRoot = rootfsDir.canonicalPath

                            if (!canonicalTarget.startsWith(canonicalRoot)) {
                                Log.w(TAG, "Path traversal ignorado: ${entry.name}")
                                entry = tar.nextEntry
                                continue
                            }

                            when {
                                entry.isDirectory -> outFile.mkdirs()

                                entry.isSymbolicLink -> {
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (outFile.exists() || outFile.isSymbolicLink) {
                                            outFile.delete()
                                        }
                                        android.system.Os.symlink(
                                            entry.linkName,
                                            outFile.absolutePath,
                                        )
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "symlink falhou ${entry.name}: ${t.message}")
                                    }
                                }

                                entry.isLink -> {
                                    outFile.parentFile?.mkdirs()
                                    try {
                                        if (outFile.exists()) outFile.delete()
                                        android.system.Os.link(
                                            File(rootfsDir, entry.linkName).absolutePath,
                                            outFile.absolutePath,
                                        )
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "hardlink falhou ${entry.name}: ${t.message}")
                                    }
                                }

                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out ->
                                        tar.copyTo(out, 64 * 1024)
                                    }
                                    val mode = entry.mode
                                    if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                    if (mode and 0b000_100_000 != 0) outFile.setReadable(true, false)
                                    if (mode and 0b000_010_000 != 0) outFile.setWritable(true, false)
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Execute
    // -----------------------------------------------------------------------

    fun execute(
        command: String,
        workingDir: String = "/workspace",
    ): Flow<TerminalLine> = callbackFlow {
        if (!isReady) {
            trySend(TerminalLine(TerminalStream.STDERR, "Alpine não preparado. Chame prepare()."))
            close()
            return@callbackFlow
        }

        // Garante o workspace no host
        workspaceDir.mkdirs()

        val args = listOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-0",
            "-w", workingDir,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "--kill-on-exit",
            "/bin/busybox", "env", "-i",
            "HOME=/root",
            "PATH=/sbin:/usr/sbin:/bin:/usr/bin",
            "TERM=xterm",
            "/bin/sh", "-c", command,
        )

        Log.d(TAG, "execute: $command")

        val pb = ProcessBuilder(args)
        pb.environment().clear()
        pb.environment()["LD_LIBRARY_PATH"] = alpineDir.absolutePath
        pb.environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        pb.directory(alpineDir)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            trySend(TerminalLine(TerminalStream.STDERR, "Falha ao iniciar proot: ${t.message}"))
            close()
            return@callbackFlow
        }

        // Thread 1: lê stdout
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        trySend(TerminalLine(TerminalStream.STDOUT, line))
                    }
                }
            } catch (_: Throwable) { /* pipe fechado */ }
        }

        // Thread 2: lê stderr
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        trySend(TerminalLine(TerminalStream.STDERR, line))
                    }
                }
            } catch (_: Throwable) { /* pipe fechado */ }
        }

        stdoutThread.start()
        stderrThread.start()

        // Thread 3: aguarda término e fecha o flow
        val waiterThread = Thread {
            try {
                stdoutThread.join()
                stderrThread.join()
                val exit = process.waitFor()
                trySend(TerminalLine(TerminalStream.EXIT, exit.toString()))
            } catch (_: Throwable) {
                // cancelado
            } finally {
                close()
            }
        }
        waiterThread.start()

        awaitClose {
            try {
                if (process.isAlive) {
                    process.destroy()
                    // Se em 2s não morrer, mata
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            } catch (_: Throwable) { /* ignorar */ }
        }
    }.flowOn(Dispatchers.IO)
}

// ---------------------------------------------------------------------------
// Tipos de domínio
// ---------------------------------------------------------------------------

sealed interface PrepareProgress {
    data object Starting : PrepareProgress
    data object ExtractingBinaries : PrepareProgress
    data class ExtractingRootfs(val percent: Int) : PrepareProgress
    data object Ready : PrepareProgress
    data class Error(val message: String, val cause: Throwable? = null) : PrepareProgress
}

enum class TerminalStream {
    STDOUT,
    STDERR,
    EXIT,
}

data class TerminalLine(
    val stream: TerminalStream,
    val text: String,
)
