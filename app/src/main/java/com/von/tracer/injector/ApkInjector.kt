// app/src/main/java/com/von/tracer/injector/ApkInjector.kt

package com.von.tracer.injector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class ApkInjector(private val context: Context) {

    companion object {
        private const val TAG = "ApkInjector"
        private const val WORK_DIR = "injector_work"
        // Path yang bisa ditulis tanpa root, dibaca gadget via config
        private const val AGENT_FILENAME = "von_agent.js"
    }

    private val workDir = File(context.filesDir, WORK_DIR)

    // Agent disimpan di external files dir (readable by gadget via config)
    private val agentFile = File(context.getExternalFilesDir(null), AGENT_FILENAME)

    var onProgress: ((step: InjectStep, message: String) -> Unit)? = null
    var onError: ((step: InjectStep, error: String) -> Unit)? = null

    // ──────────────────────────────────────────────
    // MAIN PIPELINE
    // ──────────────────────────────────────────────

    fun inject(sourceApkPath: String): File? {
    return try {
        setupWorkDir()

        val workApk = copyApk(sourceApkPath)

        // 🔥 Inject langsung (tanpa APKEditor)
        val gadgetInjector = GadgetInjector(context, workDir)
        gadgetInjector.inject(workApk)
        report(InjectStep.INJECT_GADGET, "Gadget injected")

        // 🔥 Patch manifest (kalau perlu)
        val manifestPatcher = ManifestPatcher(workDir)
        manifestPatcher.patch(workApk)
        report(InjectStep.PATCH_MANIFEST, "Manifest patched")

        // ⚠️ JANGAN sign di sini (signer lu belum valid)
        report(InjectStep.SIGN, "Unsigned APK ready")

        workApk

    } catch (e: Exception) {
        Log.e(TAG, "Inject failed", e)
        onError?.invoke(InjectStep.UNKNOWN, e.message ?: "Unknown error")
        null
    }
}

    fun saveApk(apkFile: File): File? {
    return try {
        val destDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "VonTracer"
        )
        destDir.mkdirs()

        val destFile = File(destDir, "patched_${System.currentTimeMillis()}.apk")
        apkFile.copyTo(destFile, overwrite = true)

        report(InjectStep.INSTALL, "APK disimpan: ${destFile.absolutePath}")
        destFile

    } catch (e: Exception) {
        onError?.invoke(InjectStep.INSTALL, "Simpan APK gagal: ${e.message}")
        null
    }
}

    // ──────────────────────────────────────────────
    // PUSH AGENT — simpan ke external files dir
    // ──────────────────────────────────────────────

    fun pushAgentScript(): String {
        return try {
            context.assets.open("agent.js").use { input ->
                FileOutputStream(agentFile).use { output ->
                    input.copyTo(output)
                }
            }
            report(InjectStep.PUSH_AGENT, "agent.js → ${agentFile.absolutePath}")
            agentFile.absolutePath

        } catch (e: Exception) {
            onError?.invoke(InjectStep.PUSH_AGENT, "Push agent gagal: ${e.message}")
            ""
        }
    }

    // ──────────────────────────────────────────────
    // LAUNCH — startActivity biasa (no root)
    // ──────────────────────────────────────────────

    fun launchApp(packageName: String): Boolean {
    return try {
        pushAgentScript()

        if (!isAppInstalled(packageName)) {
            onError?.invoke(
                InjectStep.LAUNCH,
                "App $packageName belum terinstall"
            )
            return false
        }

        val pm = context.packageManager

        val launchIntent =
            pm.getLaunchIntentForPackage(packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(packageName)

        if (launchIntent == null) {
            onError?.invoke(
                InjectStep.LAUNCH,
                "Launch intent null (split / protected app)"
            )
            return false
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        report(InjectStep.LAUNCH, "Launched: $packageName")
        true

    } catch (e: Exception) {
        onError?.invoke(InjectStep.LAUNCH, e.message ?: "Launch error")
        false
    }
}

    // ──────────────────────────────────────────────
    // STOP TRACE
    // ──────────────────────────────────────────────

    fun stopTrace(packageName: String) {
        // Hapus agent → gadget tidak akan reload script
        if (agentFile.exists()) agentFile.delete()
        report(InjectStep.STOPPED, "Agent dihapus, tracing off")

        // Tidak bisa force-stop tanpa root/Shizuku
        // Kasih tahu user untuk tutup manual atau via recent apps
        report(InjectStep.STOPPED, "Tutup $packageName manual untuk stop trace")
    }

    fun getAgentPath(): String = agentFile.absolutePath

    fun cleanup() {
        workDir.deleteRecursively()
        report(InjectStep.SETUP, "Work dir cleaned")
    }

    // ──────────────────────────────────────────────
    // PRIVATE HELPERS
    // ──────────────────────────────────────────────

    private fun setupWorkDir() {
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()
        report(InjectStep.SETUP, "Work dir: ${workDir.absolutePath}")
    }

    private fun copyApk(sourceApkPath: String): File {
        report(InjectStep.COPY_APK, "Copying APK...")
        val source = File(sourceApkPath)
        if (!source.exists()) throw IllegalArgumentException("APK tidak ditemukan: $sourceApkPath")

        val dest = File(workDir, "target.apk")
        source.copyTo(dest, overwrite = true)
        report(InjectStep.COPY_APK, "Copied: ${dest.length() / 1024}KB")
        return dest
    }

    private fun report(step: InjectStep, message: String) {
        Log.d(TAG, "[${step.name}] $message")
        onProgress?.invoke(step, message)
    }
    
    private fun isAppInstalled(pkg: String): Boolean {
    return try {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(0)
        apps.any { it.packageName == pkg }
    } catch (e: Exception) {
        false
    }
}
}

enum class InjectStep {
    SETUP,
    COPY_APK,
    INJECT_GADGET,
    PATCH_MANIFEST,
    SIGN,
    INSTALL,
    PUSH_AGENT,
    LAUNCH,
    STOPPED,
    UNKNOWN
}