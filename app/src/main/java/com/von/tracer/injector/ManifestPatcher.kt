// app/src/main/java/com/von/tracer/injector/ManifestPatcher.kt

package com.von.tracer.injector

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ManifestPatcher(private val workDir: File) {

    companion object {
        private const val TAG = "ManifestPatcher"
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"

        // Permission yang wajib ada agar gadget bisa socket ke tool
        private val REQUIRED_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
        )
    }

    // ──────────────────────────────────────────────
    // MAIN ENTRY
    // ──────────────────────────────────────────────

    /**
     * Patch AndroidManifest.xml di dalam APK.
     * Yang di-patch:
     * 1. Tambah INTERNET permission kalau belum ada
     * 2. Set android:extractNativeLibs="true" supaya .so di-extract
     *    (wajib agar libgadget.so bisa di-load oleh linker)
     * 3. Set android:networkSecurityConfig (opsional)
     */
    fun patch(apkFile: File) {
    Log.d(TAG, "Manifest patch DISABLED (safe mode)")
}

    // ──────────────────────────────────────────────
    // EXTRACT MANIFEST
    // ──────────────────────────────────────────────

    private fun extractManifest(apkFile: File): ByteArray? {
        return ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: return null
            zip.getInputStream(entry).readBytes()
        }
    }

    // ──────────────────────────────────────────────
    // REPACK
    // ──────────────────────────────────────────────

    private fun repackWithManifest(
        sourceApk: File,
        outputApk: File,
        newManifest: ByteArray
    ) {
        ZipFile(sourceApk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
                sourceZip.entries().asSequence().forEach { entry ->
                    if (entry.name == MANIFEST_ENTRY) {
                        // Tulis manifest yang sudah di-patch
                        outZip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        outZip.write(newManifest)
                        outZip.closeEntry()
                        Log.d(TAG, "Manifest replaced (${newManifest.size} bytes)")
                    } else {
                        // Copy entry lain apa adanya
                        outZip.putNextEntry(ZipEntry(entry.name))
                        sourceZip.getInputStream(entry).use { it.copyTo(outZip) }
                        outZip.closeEntry()
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// BINARY XML PATCHER
// ──────────────────────────────────────────────

/**
 * AndroidManifest.xml di dalam APK adalah binary XML (AXML format),
 * bukan plain text. Kita tidak perlu parse full — cukup operasi
 * string-level pada byte array untuk inject permission dan flag.
 *
 * Format AXML:
 * - Magic: 0x00080003
 * - Chunk size
 * - String pool
 * - XML nodes (start/end element, attribute)
 *
 * Untuk keperluan kita, pakai pendekatan hybrid:
 * - Cek permission via string pool search
 * - Inject permission node jika belum ada
 * - Patch flag extractNativeLibs via attribute search
 */
class BinaryXmlPatcher(private val data: ByteArray) {

    companion object {
        private const val TAG = "BinaryXmlPatcher"

        // AXML chunk types
        private const val CHUNK_XML_START_ELEMENT = 0x00100102
        private const val CHUNK_XML_END_ELEMENT = 0x00100103
        private const val CHUNK_STRING_POOL = 0x001C0001

        // Marker bytes untuk uses-permission node di AXML
        // Ini adalah pattern yang konsisten di semua AXML
        private val USES_PERMISSION_BYTES = "uses-permission".toByteArray(Charsets.UTF_16LE)
        private val ANDROID_NAME_BYTES = "android:name".toByteArray(Charsets.UTF_16LE)
        private val EXTRACT_NATIVE_BYTES = "extractNativeLibs".toByteArray(Charsets.UTF_16LE)
        private val NETWORK_SECURITY_BYTES = "networkSecurityConfig".toByteArray(Charsets.UTF_16LE)
    }

    private val output = data.toMutableList()
    private val permissionsToAdd = mutableListOf<String>()
    private var shouldExtractNative = false
    private var shouldDisableNetSec = false

    fun ensurePermissions(permissions: List<String>): BinaryXmlPatcher {
        permissions.forEach { perm ->
            if (!hasPermission(perm)) {
                permissionsToAdd.add(perm)
                Log.d(TAG, "Will add permission: $perm")
            } else {
                Log.d(TAG, "Permission already exists: $perm")
            }
        }
        return this
    }

    fun setExtractNativeLibs(value: Boolean): BinaryXmlPatcher {
        shouldExtractNative = value
        return this
    }

    fun disableNetworkSecurityCheck(): BinaryXmlPatcher {
        shouldDisableNetSec = true
        return this
    }

    fun build(): ByteArray {
        var result = data.copyOf()

        // Patch extractNativeLibs = true
        if (shouldExtractNative) {
            result = patchExtractNativeLibs(result)
        }

        // Inject permission nodes
        if (permissionsToAdd.isNotEmpty()) {
            result = injectPermissions(result, permissionsToAdd)
        }

        return result
    }

    // ──────────────────────────────────────────────
    // CHECK PERMISSION
    // ──────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean {
        // Search permission string di byte array (UTF-16LE di string pool)
        val permBytes = permission.toByteArray(Charsets.UTF_16LE)
        return indexOfBytes(data, permBytes) >= 0
    }

    // ──────────────────────────────────────────────
    // PATCH extractNativeLibs
    // ──────────────────────────────────────────────

    /**
     * Di AXML, attribute boolean disimpan sebagai 4-byte value.
     * extractNativeLibs="false" → value bytes: 00 00 00 00
     * extractNativeLibs="true"  → value bytes: FF FF FF FF
     *
     * Cari string "extractNativeLibs" di string pool,
     * lalu patch value-nya ke 0xFFFFFFFF.
     */
    private fun patchExtractNativeLibs(bytes: ByteArray): ByteArray {
        val result = bytes.copyOf()
        val idx = indexOfBytes(result, EXTRACT_NATIVE_BYTES)

        if (idx < 0) {
            Log.d(TAG, "extractNativeLibs tidak ditemukan, skip patch")
            return result
        }

        // Setelah string ditemukan di string pool,
        // cari attribute value node yang mengacu ke index ini
        // Value node format: [type=0x12 (boolean)] [data=0x00000000 or 0xFFFFFFFF]
        // Kita cari pattern: 0x12000008 (boolean attr type) diikuti 0x00000000
        val falsePattern = byteArrayOf(0x12, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00)
        val trueValue = byteArrayOf(0x12, 0x00, 0x00, 0x08, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        // Cari dari posisi setelah string pool
        val attrIdx = indexOfBytes(result, falsePattern, startFrom = idx)
        if (attrIdx >= 0) {
            trueValue.copyInto(result, attrIdx)
            Log.d(TAG, "extractNativeLibs patched to true @ 0x${attrIdx.toString(16)}")
        } else {
            Log.d(TAG, "extractNativeLibs attr value tidak ditemukan")
        }

        return result
    }

    // ──────────────────────────────────────────────
    // INJECT PERMISSIONS
    // ──────────────────────────────────────────────

    /**
     * Inject uses-permission node ke binary XML.
     *
     * Strategi: cari end tag </manifest> (end element node untuk manifest),
     * insert permission nodes sebelumnya.
     *
     * Setiap uses-permission node di AXML format:
     * [START_ELEMENT chunk] [namespace] [name="uses-permission"] [android:name attr]
     * [END_ELEMENT chunk]
     *
     * Kita ambil template dari permission yang sudah ada (kalau ada),
     * atau build dari scratch.
     */
    private fun injectPermissions(bytes: ByteArray, permissions: List<String>): ByteArray {
        // Cari template permission node yang ada (clone dan ganti string)
        val existingPermNode = findExistingPermissionNode(bytes)

        val newNodes = mutableListOf<ByteArray>()
        permissions.forEach { perm ->
            if (existingPermNode != null) {
                // Clone template + replace permission string
                newNodes.add(clonePermissionNode(existingPermNode, perm))
            } else {
                // Build dari scratch
                newNodes.add(buildPermissionNode(perm, bytes))
            }
        }

        // Cari posisi end of manifest (cari end element node terakhir)
        val insertPos = findManifestEndPos(bytes)
        if (insertPos < 0) {
            Log.e(TAG, "Manifest end position tidak ditemukan, skip permission inject")
            return bytes
        }

        // Insert semua permission nodes sebelum </manifest>
        val result = mutableListOf<Byte>()
        result.addAll(bytes.take(insertPos).map { it })
        newNodes.forEach { node -> result.addAll(node.toList()) }
        result.addAll(bytes.drop(insertPos).map { it })

        // Update chunk size di header (4 bytes di offset 4)
        val newSize = result.size
        result[4] = (newSize and 0xFF).toByte()
        result[5] = ((newSize shr 8) and 0xFF).toByte()
        result[6] = ((newSize shr 16) and 0xFF).toByte()
        result[7] = ((newSize shr 24) and 0xFF).toByte()

        Log.d(TAG, "Injected ${permissions.size} permission(s), new size: ${result.size}")
        return result.toByteArray()
    }

    private fun findExistingPermissionNode(bytes: ByteArray): ByteArray? {
        val idx = indexOfBytes(bytes, USES_PERMISSION_BYTES)
        if (idx < 0) return null

        // Mundur ke awal START_ELEMENT chunk (cari 0x02011000)
        val startChunk = byteArrayOf(0x02, 0x01, 0x10, 0x00)
        var nodeStart = idx
        while (nodeStart > 0) {
            if (bytes[nodeStart] == startChunk[0] &&
                bytes[nodeStart + 1] == startChunk[1]) {
                break
            }
            nodeStart--
        }

        // Baca chunk size (4 bytes setelah type)
        if (nodeStart + 8 >= bytes.size) return null
        val chunkSize = readInt32LE(bytes, nodeStart + 4)
        if (chunkSize <= 0 || nodeStart + chunkSize + 8 > bytes.size) return null

        // Ambil full node (start element + end element = 2 chunks)
        val endChunkSize = 24 // END_ELEMENT selalu 24 bytes
        val totalSize = chunkSize + endChunkSize
        return bytes.copyOfRange(nodeStart, (nodeStart + totalSize).coerceAtMost(bytes.size))
    }

    private fun clonePermissionNode(template: ByteArray, newPermission: String): ByteArray {
        // Cari string permission lama di template
        val result = template.copyOf()
        val permPattern = findPermissionStringInNode(template) ?: return result

        // Replace dengan permission baru (UTF-16LE, sama panjangnya tidak dijamin)
        // Untuk simplicity: inject string baru dengan padding
        val newPermBytes = newPermission.toByteArray(Charsets.UTF_16LE)
        val oldPermBytes = permPattern

        return replaceBytes(result, oldPermBytes, newPermBytes)
    }

    private fun findPermissionStringInNode(node: ByteArray): ByteArray? {
        // Permission string di node dimulai setelah length prefix (2 bytes)
        val idx = indexOfBytes(node, "android.permission.".toByteArray(Charsets.UTF_16LE))
        if (idx < 0) return null

        // Baca panjang string (2 bytes sebelum string = length in chars)
        val lenOffset = idx - 2
        if (lenOffset < 0) return null
        val len = (node[lenOffset].toInt() and 0xFF) or ((node[lenOffset + 1].toInt() and 0xFF) shl 8)
        return node.copyOfRange(idx, (idx + len * 2).coerceAtMost(node.size))
    }

    private fun buildPermissionNode(permission: String, context: ByteArray): ByteArray {
        // Build minimal AXML uses-permission node
        // Ini adalah byte sequence standar untuk permission node
        val permBytes = permission.toByteArray(Charsets.UTF_16LE)
        val permLen = permission.length

        // Ambil namespace index dari string pool (biasanya index 0 = android ns)
        // Format: START_ELEMENT + attribute + END_ELEMENT
        val node = mutableListOf<Byte>()

        // START_ELEMENT: type=0x00100102, size=0x00000038 (56 bytes minimal)
        node.addAll(listOf(0x02, 0x01, 0x10, 0x00)) // chunk type
        node.addAll(int32LEBytes(56 + permBytes.size + 4)) // chunk size
        node.addAll(int32LEBytes(0)) // line number
        node.addAll(int32LEBytes(-1)) // comment = -1
        node.addAll(int32LEBytes(0)) // ns index
        node.addAll(int32LEBytes(getStringIndex(context, "uses-permission"))) // name index
        node.addAll(listOf(0x14, 0x00)) // attribute start
        node.addAll(listOf(0x14, 0x00)) // attribute size
        node.addAll(listOf(0x01, 0x00)) // attribute count = 1
        node.addAll(listOf(0x00, 0x00)) // id attr index
        node.addAll(listOf(0x00, 0x00)) // class attr index
        node.addAll(listOf(0x00, 0x00)) // style attr index

        // Attribute: android:name = permission string
        node.addAll(int32LEBytes(0)) // ns
        node.addAll(int32LEBytes(getStringIndex(context, "name"))) // name
        node.addAll(listOf(0x00, 0x00, 0x00, 0x00)) // raw value
        node.addAll(listOf(0x08, 0x00)) // value size
        node.addAll(listOf(0x00)) // res0
        node.addAll(listOf(0x03)) // value type = TYPE_STRING
        // String value — inject langsung sebagai inline bytes
        node.addAll(int32LEBytes(permLen))
        node.addAll(permBytes.toList())
        node.addAll(listOf(0x00, 0x00)) // null terminator

        // END_ELEMENT: type=0x00100103, size=24
        node.addAll(listOf(0x03, 0x01, 0x10, 0x00))
        node.addAll(int32LEBytes(24))
        node.addAll(int32LEBytes(0))
        node.addAll(int32LEBytes(-1))
        node.addAll(int32LEBytes(0))
        node.addAll(int32LEBytes(getStringIndex(context, "uses-permission")))

        return node.map { it }.toByteArray()
    }

    private fun findManifestEndPos(bytes: ByteArray): Int {
        // Cari END_ELEMENT chunk terakhir = penutup </manifest>
        val endChunk = byteArrayOf(0x03, 0x01, 0x10, 0x00)
        var lastPos = -1
        var i = bytes.size - 4
        while (i >= 0) {
            if (bytes[i] == endChunk[0] &&
                bytes[i + 1] == endChunk[1] &&
                bytes[i + 2] == endChunk[2] &&
                bytes[i + 3] == endChunk[3]
            ) {
                lastPos = i
                break
            }
            i--
        }
        return lastPos
    }

    // ──────────────────────────────────────────────
    // BYTE UTILS
    // ──────────────────────────────────────────────

    private fun indexOfBytes(
        haystack: ByteArray,
        needle: ByteArray,
        startFrom: Int = 0
    ): Int {
        outer@ for (i in startFrom..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun replaceBytes(src: ByteArray, old: ByteArray, new: ByteArray): ByteArray {
        val idx = indexOfBytes(src, old)
        if (idx < 0) return src
        val result = mutableListOf<Byte>()
        result.addAll(src.take(idx).map { it })
        result.addAll(new.toList())
        result.addAll(src.drop(idx + old.size).map { it })
        return result.toByteArray()
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun int32LEBytes(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun getStringIndex(bytes: ByteArray, str: String): Int {
        // Cari index string di string pool AXML
        // String pool dimulai di offset 8, format: count + strings
        val strBytes = str.toByteArray(Charsets.UTF_16LE)
        val idx = indexOfBytes(bytes, strBytes)
        if (idx < 0) return 0
        // Hitung index berdasarkan posisi relatif di pool
        // Simplified: return posisi / avg string size
        return idx / (str.length * 2 + 4)
    }
}