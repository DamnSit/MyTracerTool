package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Signer(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "Signer"
    }

    fun zipalign(apkFile: File): File {
        Log.d(TAG, "Zipalign: ${apkFile.name}")
        val output = File(workDir, "target_aligned.apk")

        ZipFile(apkFile).use { sourceZip ->
            ZipOutputStream(FileOutputStream(output)).use { outZip ->
                sourceZip.entries().asSequence().forEach { entry ->
                    val newEntry = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.crc = entry.crc
                    } else {
                        newEntry.method = ZipEntry.DEFLATED
                    }
                    outZip.putNextEntry(newEntry)
                    sourceZip.getInputStream(entry).use { it.copyTo(outZip) }
                    outZip.closeEntry()
                }
            }
        }

        Log.d(TAG, "Zipalign done: ${output.length() / 1024}KB")
        return output
    }

    fun sign(apkFile: File): File {
    Log.d(TAG, "Output unsigned, META-INF preserved...")
    val output = File(workDir, "target_unsigned.apk")

    ZipFile(apkFile).use { sourceZip ->
        ZipOutputStream(FileOutputStream(output)).use { outZip ->
            sourceZip.entries().asSequence().forEach { entry ->
                val newEntry = ZipEntry(entry.name)
                newEntry.method = entry.method
                if (entry.method == ZipEntry.STORED) {
                    newEntry.size = entry.size
                    newEntry.compressedSize = entry.compressedSize
                    newEntry.crc = entry.crc
                }
                outZip.putNextEntry(newEntry)
                sourceZip.getInputStream(entry).use { it.copyTo(outZip) }
                outZip.closeEntry()
            }
        }
    }

    Log.d(TAG, "Done: ${output.name} (${output.length() / 1024}KB)")
    return output
}
}