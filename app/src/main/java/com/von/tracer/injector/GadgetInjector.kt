// app/src/main/java/com/von/tracer/injector/GadgetInjector.kt

package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class GadgetInjector(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "GadgetInjector"

        // Path di dalam APK zip
        private const val GADGET_ENTRY_ARM64 = "lib/arm64-v8a/libgadget.so"
        private const val GADGET_ENTRY_ARM32 = "lib/armeabi-v7a/libgadget.so"
        private const val CONFIG_ENTRY = "assets/gadget-config.json"

        // Nama file gadget di assets tool kamu
        private const val ASSET_GADGET = "frida-gadget-arm64.so"
        private const val ASSET_CONFIG = "gadget-config.json"
    }

    // ──────────────────────────────────────────────
    // MAIN ENTRY
    // ──────────────────────────────────────────────

    /**
     * Inject frida-gadget ke APK target.
     * Cara kerja:
     * 1. Baca semua entry dari APK asli
     * 2. Tulis ulang ke APK baru
     * 3. Tambah / replace libgadget.so di lib/arm64-v8a/
     * 4. Tambah gadget-config.json di assets/
     * 5. Patch app_name di lib entry agar gadget di-load
     */
    fun inject(apkFile: File) {
        Log.d(TAG, "Injecting gadget ke ${apkFile.name}")

        val tempOutput = File(workDir, "target_gadget.apk")

        // Extract gadget dari assets tool ke temp file
        val gadgetFile = extractGadgetFromAssets()
        val configFile = extractConfigFromAssets()

        // Deteksi arsitektur yang ada di APK target
        val archs = detectArchitectures(apkFile)
        Log.d(TAG, "Detected archs: $archs")

        // Repack APK dengan gadget di dalamnya
        repackApk(
            sourceApk = apkFile,
            outputApk = tempOutput,
            gadgetFile = gadgetFile,
            configFile = configFile,
            archs = archs
        )

        // Replace APK asli dengan yang sudah diinjeksi
        tempOutput.copyTo(apkFile, overwrite = true)
        tempOutput.delete()

        Log.d(TAG, "Gadget injection selesai")
    }

    // ──────────────────────────────────────────────
    // DETECT ARCH
    // ──────────────────────────────────────────────

    /**
     * Cek arsitektur apa saja yang ada di APK target.
     * Penting: kita hanya inject ke arch yang memang ada
     * supaya tidak crash di device yang tidak support.
     */
    private fun detectArchitectures(apkFile: File): Set<String> {
        val archs = mutableSetOf<String>()
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                when {
                    entry.name.startsWith("lib/arm64-v8a/") -> archs.add("arm64-v8a")
                    entry.name.startsWith("lib/armeabi-v7a/") -> archs.add("armeabi-v7a")
                    entry.name.startsWith("lib/x86_64/") -> archs.add("x86_64")
                    entry.name.startsWith("lib/x86/") -> archs.add("x86")
                }
            }
        }
        // Kalau tidak ada lib folder sama sekali, default ke arm64
        if (archs.isEmpty()) archs.add("arm64-v8a")
        return archs
    }

    // ──────────────────────────────────────────────
    // REPACK APK
    // ──────────────────────────────────────────────

    private fun repackApk(
        sourceApk: File,
        outputApk: File,
        gadgetFile: File,
        configFile: File,
        archs: Set<String>
    ) {
        ZipFile(sourceApk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->

                // Entry yang akan di-skip (akan kita replace)
                val skipEntries = mutableSetOf<String>()

// Skip gadget lama (biar ke-replace)
archs.forEach { arch ->
    skipEntries.add("lib/$arch/libgadget.so")
    skipEntries.add("lib/$arch/libgadget.config.so")
}

// Biar aman dari duplicate entry saat repack
val writtenEntries = mutableSetOf<String>()

sourceZip.entries().asSequence().forEach { entry ->
    val name = entry.name
    val upper = name.uppercase()

    val isSignature = name.startsWith("META-INF/") && (
        upper.endsWith(".RSA") ||
        upper.endsWith(".DSA") ||
        upper.endsWith(".EC")  ||
        upper.endsWith(".SF")  ||
        name == "META-INF/MANIFEST.MF"
    )

    if (name in skipEntries || isSignature) {
        Log.d(TAG, "Skip entry: $name")
        return@forEach
    }

    // Hindari duplicate (beberapa APK punya entry dobel)
    if (!writtenEntries.add(name)) {
        Log.d(TAG, "Duplicate skip: $name")
        return@forEach
    }

    val newEntry = ZipEntry(name)
    newEntry.method = entry.method

    if (entry.method == ZipEntry.STORED) {
        newEntry.size = entry.size
        newEntry.compressedSize = entry.compressedSize
        newEntry.crc = entry.crc
    }

    // Preserve extra + time (opsional tapi bagus)
    newEntry.time = entry.time
    newEntry.extra = entry.extra

    outZip.putNextEntry(newEntry)
    sourceZip.getInputStream(entry).use { it.copyTo(outZip) }
    outZip.closeEntry()
}

                // Inject libgadget.so ke setiap arch yang ada
                archs.forEach { arch ->
    val gadgetEntryName = "lib/$arch/libgadget.so"
    Log.d(TAG, "Injecting: $gadgetEntryName")

    if (!writtenEntries.add(gadgetEntryName)) {
        Log.d(TAG, "Duplicate skip (inject): $gadgetEntryName")
    } else {
        val gadgetEntry = ZipEntry(gadgetEntryName).apply {
            method = ZipEntry.DEFLATED
            time = System.currentTimeMillis()
        }
        outZip.putNextEntry(gadgetEntry)
        gadgetFile.inputStream().use { it.copyTo(outZip) }
        outZip.closeEntry()
    }

    val configEntryName = "lib/$arch/libgadget.config.so"
    Log.d(TAG, "Injecting config: $configEntryName")

    if (!writtenEntries.add(configEntryName)) {
        Log.d(TAG, "Duplicate skip (inject): $configEntryName")
    } else {
        val configEntry = ZipEntry(configEntryName).apply {
            method = ZipEntry.DEFLATED
            time = System.currentTimeMillis()
        }
        outZip.putNextEntry(configEntry)
        configFile.inputStream().use { it.copyTo(outZip) }
        outZip.closeEntry()
    }
}

                Log.d(TAG, "Repack selesai")
            }
        }
    }

    // ──────────────────────────────────────────────
    // EXTRACT FROM ASSETS
    // ──────────────────────────────────────────────

    private fun extractGadgetFromAssets(): File {
        val dest = File(workDir, "libgadget.so")
        if (dest.exists()) return dest // cache

        context.assets.open(ASSET_GADGET).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Gadget extracted: ${dest.length() / 1024}KB")
        return dest
    }

    private fun extractConfigFromAssets(): File {
        val dest = File(workDir, "libgadget.config.so")
        if (dest.exists()) return dest

        context.assets.open(ASSET_CONFIG).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Config extracted")
        return dest
    }

    // ──────────────────────────────────────────────
    // VERIFY
    // ──────────────────────────────────────────────

    /**
     * Verifikasi hasil injeksi — cek apakah gadget entry ada.
     */
    fun verify(apkFile: File): Boolean {
        return try {
            ZipFile(apkFile).use { zip ->
                val hasGadget = zip.entries().asSequence().any { entry ->
                    entry.name.contains("libgadget.so")
                }
                Log.d(TAG, "Verify: gadget found = $hasGadget")
                hasGadget
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verify failed", e)
            false
        }
    }
}