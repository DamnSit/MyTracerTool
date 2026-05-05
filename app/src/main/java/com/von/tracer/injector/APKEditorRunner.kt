package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class APKEditorRunner(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "APKEditorRunner"
        private const val JAR_NAME = "APKEditor.jar"
    }

    private val jarFile = File(workDir, JAR_NAME)

    fun prepareJar() {
        if (jarFile.exists()) return

        context.assets.open(JAR_NAME).use { input ->
            FileOutputStream(jarFile).use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "APKEditor.jar ready: ${jarFile.absolutePath}")
    }

    fun decode(apk: File, outDir: File) {
        runCommand(
            "java -jar ${jarFile.absolutePath} d ${apk.absolutePath} -o ${outDir.absolutePath}"
        )
    }

    fun build(outDir: File, outputApk: File) {
        runCommand(
            "java -jar ${jarFile.absolutePath} b ${outDir.absolutePath} -o ${outputApk.absolutePath}"
        )
    }

    private fun runCommand(cmd: String) {
        Log.d(TAG, "Run: $cmd")

        val process = Runtime.getRuntime().exec(cmd)
        process.inputStream.bufferedReader().forEachLine {
            Log.d(TAG, it)
        }
        process.errorStream.bufferedReader().forEachLine {
            Log.e(TAG, it)
        }

        val code = process.waitFor()
        if (code != 0) {
            throw RuntimeException("APKEditor failed (code=$code)")
        }
    }
}