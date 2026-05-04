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

            val gadgetInjector = GadgetInjector(context, workDir)
            gadgetInjector.inject(workApk)
            report(InjectStep.INJECT_GADGET, "Gadget injected")

            val manifestPatcher = ManifestPatcher(workDir)
            manifestPatcher.patch(workApk)
            report(InjectStep.PATCH_MANIFEST, "Manifest patched")

            val signer = Signer(context, workDir)
            val aligned = signer.zipalign(workApk)
            report(InjectStep.ZIPALIGN, "Zipalign done")

            val signed = signer.sign(aligned)
            report(InjectStep.SIGN, "APK signed: ${signed.name}")

            signed

        } catch (e: Exception) {
            Log.e(TAG, "Inject failed", e)
            onError?.invoke(InjectStep.UNKNOWN, e.message ?: "Unknown error")
            null
        }
    }

    // ──────────────────────────────────────────────
    // INSTALL — PackageInstaller API (no root)
    // ──────────────────────────────────────────────

    fun install(apkFile: File) {
        report(InjectStep.INSTALL, "Memulai install via PackageInstaller...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ — pakai FileProvider
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            report(InjectStep.INSTALL, "Dialog install dibuka — tunggu user konfirmasi")

        } catch (e: Exception) {
            onError?.invoke(InjectStep.INSTALL, "Install gagal: ${e.message}")
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
            // Push agent dulu sebelum launch
            pushAgentScript()

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                onError?.invoke(
                    InjectStep.LAUNCH,
                    "App $packageName tidak ditemukan / belum diinstall"
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
}

enum class InjectStep {
    SETUP,
    COPY_APK,
    INJECT_GADGET,
    PATCH_MANIFEST,
    ZIPALIGN,
    SIGN,
    INSTALL,
    PUSH_AGENT,
    LAUNCH,
    STOPPED,
    UNKNOWN
}