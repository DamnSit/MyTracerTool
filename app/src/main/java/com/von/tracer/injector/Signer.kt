package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File

class Signer(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "Signer"
    }

    fun zipalign(apkFile: File): File {
        Log.d(TAG, "Skip zipalign (return as-is)")
        return apkFile
    }

    fun sign(apkFile: File): File {
        Log.d(TAG, "Skip sign (return as-is)")
        return apkFile
    }
}