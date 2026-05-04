// app/src/main/java/com/von/tracer/MainActivity.kt

package com.von.tracer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.commit
import com.von.tracer.injector.ApkInjector
import com.von.tracer.injector.InjectStep
import com.von.tracer.model.TraceNode
import com.von.tracer.server.TraceServer
import com.von.tracer.ui.LogFragment
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }

    // ── Views ──────────────────────────────────
    private lateinit var tvApkPath: TextView
    private lateinit var etPackageName: EditText
    private lateinit var tvProgress: TextView
    private lateinit var btnPickApk: TextView
    private lateinit var btnInject: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnLaunch: TextView
    private lateinit var btnStop: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnExport: TextView
    private lateinit var stepInject: TextView
    private lateinit var stepSave: TextView
    private lateinit var stepLaunch: TextView
    private lateinit var stepTrace: TextView

    // ── State ──────────────────────────────────
    private var selectedApkPath: String? = null
    private var injectedApkFile: File? = null
    private var isTracing = false

    // ── Core components ────────────────────────
    private lateinit var apkInjector: ApkInjector
    private lateinit var traceServer: TraceServer
    private lateinit var logFragment: LogFragment
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── APK picker ─────────────────────────────
    private val apkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleApkSelected(uri)
            }
        }
    }

    // ──────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupInjector()
        setupServer()
        setupFragment()
        setupClickListeners()
        setButtonState(PipelineState.IDLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        traceServer.stop()
        scope.cancel()
    }

    // ──────────────────────────────────────────────
    // SETUP
    // ──────────────────────────────────────────────

    private fun bindViews() {
        tvApkPath     = findViewById(R.id.tvApkPath)
        etPackageName = findViewById(R.id.etPackageName)
        tvProgress    = findViewById(R.id.tvProgress)
        btnPickApk    = findViewById(R.id.btnPickApk)
        btnInject     = findViewById(R.id.btnInject)
        btnSave    = findViewById(R.id.btnSave)
        btnLaunch     = findViewById(R.id.btnLaunch)
        btnStop       = findViewById(R.id.btnStop)
        btnClear      = findViewById(R.id.btnClear)
        btnExport     = findViewById(R.id.btnExport)
        stepInject    = findViewById(R.id.stepInject)
        stepSave   = findViewById(R.id.stepSave)
        stepLaunch    = findViewById(R.id.stepLaunch)
        stepTrace     = findViewById(R.id.stepTrace)
    }

    private fun setupInjector() {
        apkInjector = ApkInjector(this)

        apkInjector.onProgress = { step, message ->
            runOnUiThread {
                updateProgress(step, message)
            }
        }

        apkInjector.onError = { step, error ->
            runOnUiThread {
                showError("[$step] $error")
            }
        }
    }

    private fun setupServer() {
        traceServer = TraceServer(
            port = 27043,
            onNodeReceived = { node ->
                onTraceNodeReceived(node)
            },
            onServerLog = { msg ->
                runOnUiThread {
                    tvProgress.text = msg
                }
            }
        )
    }

    private fun setupFragment() {
        logFragment = LogFragment.newInstance()
        supportFragmentManager.commit {
            replace(R.id.fragmentContainer, logFragment)
        }
    }

    private fun setupClickListeners() {

        btnPickApk.setOnClickListener {
            openApkPicker()
        }

        btnInject.setOnClickListener {
            startInject()
        }

        btnSave.setOnClickListener {
            startSaveApk()
        }

        btnLaunch.setOnClickListener {
            startLaunch()
        }

        btnStop.setOnClickListener {
            stopTrace()
        }

        btnClear.setOnClickListener {
            logFragment.clearLog()
            progress("")
        }

        btnExport.setOnClickListener {
            exportLog()
        }
    }

    // ──────────────────────────────────────────────
    // APK PICKER
    // ──────────────────────────────────────────────

    private fun openApkPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        apkPickerLauncher.launch(intent)
    }

    private fun handleApkSelected(uri: Uri) {
        // Copy APK dari uri ke cache dir (bisa diakses langsung sebagai File)
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri) ?: "target.apk"
                val destFile = File(cacheDir, fileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    selectedApkPath = destFile.absolutePath
                    tvApkPath.text = fileName
                    tvApkPath.setTextColor(0xFFCDD9E5.toInt())

                    // Auto-detect package name dari APK
                    detectPackageName(destFile)

                    progress("APK siap: $fileName")
                    setButtonState(PipelineState.APK_SELECTED)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Gagal load APK: ${e.message}")
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun detectPackageName(apkFile: File) {
        try {
            val pm = packageManager
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            info?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                val packageName = info.packageName
                if (packageName.isNotEmpty()) {
                    etPackageName.setText(packageName)
                }
            }
        } catch (e: Exception) {
            // User isi manual
        }
    }

    // ──────────────────────────────────────────────
    // PIPELINE STEPS
    // ──────────────────────────────────────────────

    private fun startInject() {
        val apkPath = selectedApkPath
        val packageName = etPackageName.text.toString().trim()

        if (apkPath == null) {
            showError("Pilih APK dulu")
            return
        }
        if (packageName.isEmpty()) {
            showError("Isi package name dulu")
            return
        }

        setButtonState(PipelineState.INJECTING)
        setStepActive(stepInject)

        scope.launch(Dispatchers.IO) {
            val result = apkInjector.inject(apkPath)

            withContext(Dispatchers.Main) {
                if (result != null) {
                    injectedApkFile = result
                    progress("✓ Inject selesai → ${result.name}")
                    setButtonState(PipelineState.INJECTED)
                    setStepDone(stepInject)
                    setStepActive(stepSave)
                    toast("Inject berhasil, siap save APK")
                } else {
                    showError("Inject gagal, cek log")
                    setButtonState(PipelineState.APK_SELECTED)
                    resetStep(stepInject)
                }
            }
        }
    }

    private fun startSaveApk() {
    val apk = injectedApkFile
    if (apk == null || !apk.exists()) {
        showError("APK hasil inject tidak ditemukan")
        return
    }

    setButtonState(PipelineState.INSTALLING)
    progress("Menyimpan APK ke Downloads/VonTracer/...")

    scope.launch(Dispatchers.IO) {
        val savedFile = apkInjector.saveApk(apk)

        withContext(Dispatchers.Main) {
            if (savedFile != null) {
                setButtonState(PipelineState.INSTALLED)
                setStepDone(stepSave)
                setStepActive(stepLaunch)
                progress("✓ APK disimpan → ${savedFile.name}")
                toast("Saved: Downloads/VonTracer/${savedFile.name}")

                // Tawarkan buka folder via intent
                openDownloadFolder()
            } else {
                showError("Gagal simpan APK")
                setButtonState(PipelineState.INJECTED)
            }
        }
    }
}

private fun openDownloadFolder() {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/document/primary:Downloads/VonTracer"),
                "resource/folder"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (e: Exception) {
    toast("APK sudah disimpan di Downloads/VonTracer")
   }
}

    private fun startLaunch() {
        val packageName = etPackageName.text.toString().trim()
        if (packageName.isEmpty()) {
            showError("Package name kosong")
            return
        }

        // Cek apakah app sudah terinstall
        if (!isPackageInstalled(packageName)) {
            showError("$packageName belum terinstall")
            return
        }

        setButtonState(PipelineState.LAUNCHING)
        setStepActive(stepLaunch)

        // Start trace server dulu
        traceServer.start()
        logFragment.setServerStatus(true)
        progress("Server listening port 27043...")

        scope.launch {
            delay(500) // beri waktu server ready

            val launched = apkInjector.launchApp(packageName)

            if (launched) {
                isTracing = true
                setButtonState(PipelineState.TRACING)
                setStepDone(stepLaunch)
                setStepActive(stepTrace)
                progress("✓ App launched, menunggu trace...")
                toast("Sekarang gunakan app target untuk mulai trace")
            } else {
                showError("Gagal launch $packageName")
                setButtonState(PipelineState.INSTALLED)
            }
        }
    }

    private fun stopTrace() {
        val packageName = etPackageName.text.toString().trim()
        apkInjector.stopTrace(packageName)
        traceServer.stop()
        logFragment.setServerStatus(false)
        isTracing = false
        setButtonState(PipelineState.INSTALLED)
        resetStep(stepTrace)
        progress("Tracing stopped")
        toast("Trace dihentikan")
    }

    // ──────────────────────────────────────────────
    // TRACE RECEIVER
    // ──────────────────────────────────────────────

    private fun onTraceNodeReceived(node: TraceNode) {
        logFragment.addTraceNode(node)
    }

    // ──────────────────────────────────────────────
    // EXPORT
    // ──────────────────────────────────────────────

    private fun exportLog() {
        scope.launch(Dispatchers.IO) {
            try {
                val content = withContext(Dispatchers.Main) {
                logFragment.exportLog()
                }
                val fileName = "von_trace_${System.currentTimeMillis()}.txt"
                val exportFile = File(
                    getExternalFilesDir(null),
                    fileName
                )
                exportFile.writeText(content)

                withContext(Dispatchers.Main) {
                    shareFile(exportFile)
                    toast("Exported: $fileName")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Export gagal: ${e.message}")
                }
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export Trace Log"))
    }

    // ──────────────────────────────────────────────
    // BUTTON STATE MACHINE
    // ──────────────────────────────────────────────

    private enum class PipelineState {
        IDLE,
        APK_SELECTED,
        INJECTING,
        INJECTED,
        INSTALLING,
        INSTALLED,
        LAUNCHING,
        TRACING
    }

    private fun setButtonState(state: PipelineState) {
    val activeColor   = 0xFF58A6FF.toInt()
    val inactiveColor = 0xFF3D444D.toInt()
    val dangerColor   = 0xFFF85149.toInt()
    val successColor  = 0xFF3FB950.toInt()

    when (state) {
        PipelineState.IDLE -> {
            setBtn(btnInject,  true,  inactiveColor, "INJECT")
            setBtn(btnSave, false, inactiveColor, "SAVE APK")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.APK_SELECTED -> {
            setBtn(btnInject,  true,  activeColor,   "INJECT")
            setBtn(btnSave, false, inactiveColor, "SAVE APK")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.INJECTING -> {
            setBtn(btnInject,  false, inactiveColor, "INJECT...")
            setBtn(btnSave, false, inactiveColor, "SAVE APK")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.INJECTED -> {
            setBtn(btnInject,  true,  successColor,  "INJECT ✓")
            setBtn(btnSave, true,  activeColor,   "SAVE APK")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.INSTALLING -> {
            setBtn(btnInject,  true,  successColor,  "INJECT ✓")
            setBtn(btnSave, false, inactiveColor, "SAVING...")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.INSTALLED -> {
            setBtn(btnInject,  true,  successColor,  "INJECT ✓")
            setBtn(btnSave, true,  successColor,  "SAVED ✓")
            setBtn(btnLaunch,  true,  activeColor,   "LAUNCH")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.LAUNCHING -> {
            setBtn(btnInject,  true,  successColor,  "INJECT ✓")
            setBtn(btnSave, true,  successColor,  "SAVED ✓")
            setBtn(btnLaunch,  false, inactiveColor, "LAUNCH...")
            setBtn(btnStop,    false, inactiveColor, "STOP")
        }
        PipelineState.TRACING -> {
            setBtn(btnInject,  true,  successColor,  "INJECT ✓")
            setBtn(btnSave, true,  successColor,  "SAVED ✓")
            setBtn(btnLaunch,  true,  successColor,  "LAUNCH ✓")
            setBtn(btnStop,    true,  dangerColor,   "STOP")
        }
    }
}

    private fun setBtn(
        btn: TextView,
        enabled: Boolean,
        color: Int,
        text: String
    ) {
        btn.isEnabled = enabled
        btn.setTextColor(color)
        btn.text = text
        btn.alpha = if (enabled) 1.0f else 0.4f
    }

    // ──────────────────────────────────────────────
    // STEP INDICATORS
    // ──────────────────────────────────────────────

    private fun setStepActive(step: TextView) {
        step.setTextColor(0xFF58A6FF.toInt())
        step.alpha = 1.0f
    }

    private fun setStepDone(step: TextView) {
        step.setTextColor(0xFF3FB950.toInt())
        step.alpha = 1.0f
    }

    private fun resetStep(step: TextView) {
        step.setTextColor(0xFF3D444D.toInt())
        step.alpha = 0.6f
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun updateProgress(step: InjectStep, message: String) {
        val prefix = when (step) {
            InjectStep.SETUP         -> "⚙"
            InjectStep.COPY_APK      -> "📋"
            InjectStep.INJECT_GADGET -> "💉"
            InjectStep.PATCH_MANIFEST -> "📝"
            InjectStep.ZIPALIGN      -> "🔧"
            InjectStep.SIGN          -> "🔑"
            InjectStep.INSTALL       -> "💾"
            InjectStep.PUSH_AGENT    -> "📤"
            InjectStep.LAUNCH        -> "🚀"
            InjectStep.STOPPED       -> "⏹"
            InjectStep.UNKNOWN       -> "❓"
        }
        progress("$prefix $message")
    }

    private fun progress(msg: String) {
        tvProgress.text = msg
    }

    private fun showError(msg: String) {
        tvProgress.text = "✗ $msg"
        tvProgress.setTextColor(0xFFF85149.toInt())
        toast(msg)
        // Reset warna setelah 3 detik
        scope.launch {
            delay(3000)
            tvProgress.setTextColor(0xFF58A6FF.toInt())
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}