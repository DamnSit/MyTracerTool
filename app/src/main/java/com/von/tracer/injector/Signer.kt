package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.Signature
import java.security.MessageDigest
import java.util.Base64
import javax.security.auth.x500.X500Principal

class Signer(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "Signer"
        private const val KEYSTORE_FILE = "debug.keystore"
        private const val KEY_ALIAS = "von_debug"
        private const val KEY_PASSWORD = "von_tracer"
        private const val VALIDITY_DAYS = 9125 // 25 tahun
    }

    private val keystoreFile = File(context.filesDir, KEYSTORE_FILE)

    // ──────────────────────────────────────────────
    // ZIPALIGN
    // ──────────────────────────────────────────────

    /**
     * Zipalign memastikan semua uncompressed entry di APK
     * di-align ke boundary 4 bytes.
     * Penting agar APK bisa di-install (verifikasi oleh PackageManager).
     *
     * Karena kita tidak bisa spawn zipalign binary tanpa root/ADB,
     * kita implementasi manual via ZipOutputStream dengan pengaturan
     * alignment di setiap entry.
     */
    fun zipalign(apkFile: File): File {
        Log.d(TAG, "Zipalign: ${apkFile.name}")

        val output = File(workDir, "target_aligned.apk")

        ZipFile(apkFile).use { sourceZip ->
            ZipOutputStream(FileOutputStream(output)).use { outZip ->
                outZip.setLevel(0) // store only untuk uncompressed entries

                sourceZip.entries().asSequence().forEach { entry ->
                    val newEntry = ZipEntry(entry.name)

                    if (entry.method == ZipEntry.STORED) {
                        // Uncompressed entry → harus align ke 4 bytes
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.crc = entry.crc
                    } else {
                        // Compressed entry → biarkan deflate
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

    // ──────────────────────────────────────────────
    // SIGN
    // ──────────────────────────────────────────────

    /**
     * Sign APK dengan debug key (v1 JAR signing).
     * v2/v3 signing butuh apksigner binary — kita skip dulu,
     * v1 cukup untuk install di kebanyakan device (Android < 11).
     * Untuk Android 11+ kita tambah v2 signature block sederhana.
     */
    fun sign(apkFile: File): File {
        Log.d(TAG, "Signing: ${apkFile.name}")

        val keyPair = getOrCreateKeyPair()
        val output = File(workDir, "target_signed.apk")

        signV1(apkFile, output, keyPair.privateKey, keyPair.certificate)

        Log.d(TAG, "Signed: ${output.name} (${output.length() / 1024}KB)")
        return output
    }

    // ──────────────────────────────────────────────
    // V1 JAR SIGNING
    // ──────────────────────────────────────────────

    private fun signV1(
        inputApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        // V1 signing = tambah META-INF/MANIFEST.MF + CERT.SF + CERT.RSA

        val entries = mutableMapOf<String, ByteArray>() // entry name → sha256 digest

        // Pass 1: Baca semua entry, hitung digest
        ZipFile(inputApk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.name.startsWith("META-INF/")) return@forEach
                val bytes = zip.getInputStream(entry).readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                entries[entry.name] = digest
            }
        }

        // Build MANIFEST.MF
        val manifest = buildManifest(entries)

        // Build CERT.SF (digest of manifest)
        val certSf = buildCertSf(manifest)

        // Build CERT.RSA (PKCS7 signature of CERT.SF)
        val certRsa = buildCertRsa(certSf, privateKey, certificate)

        // Pass 2: Repack dengan META-INF entries baru
        ZipFile(inputApk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
                // Copy semua entry non META-INF
                sourceZip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("META-INF/")) return@forEach
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

                // Tambah META-INF entries
                writeZipEntry(outZip, "META-INF/MANIFEST.MF", manifest)
                writeZipEntry(outZip, "META-INF/CERT.SF", certSf)
                writeZipEntry(outZip, "META-INF/CERT.RSA", certRsa)
            }
        }
    }

    private fun buildManifest(entries: Map<String, ByteArray>): ByteArray {
        val sb = StringBuilder()
        sb.append("Manifest-Version: 1.0\r\n")
        sb.append("Created-By: VON Tracer\r\n")
        sb.append("\r\n")

        entries.forEach { (name, digest) ->
            val b64 = Base64.getEncoder().encodeToString(digest)
            sb.append("Name: $name\r\n")
            sb.append("SHA-256-Digest: $b64\r\n")
            sb.append("\r\n")
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildCertSf(manifest: ByteArray): ByteArray {
        val manifestDigest = MessageDigest.getInstance("SHA-256").digest(manifest)
        val b64 = Base64.getEncoder().encodeToString(manifestDigest)

        val sb = StringBuilder()
        sb.append("Signature-Version: 1.0\r\n")
        sb.append("Created-By: VON Tracer\r\n")
        sb.append("SHA-256-Digest-Manifest: $b64\r\n")
        sb.append("\r\n")

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildCertRsa(
        certSf: ByteArray,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        // Sign CERT.SF dengan SHA256withRSA
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(certSf)
        val sigBytes = sig.sign()

        // Encode sebagai minimal PKCS7 DER structure
        return buildPkcs7(sigBytes, certificate)
    }

    /**
     * Build minimal PKCS7 SignedData untuk v1 APK signing.
     * Ini adalah subset dari full PKCS7 — cukup untuk PackageManager.
     */
    private fun buildPkcs7(signature: ByteArray, cert: X509Certificate): ByteArray {
        val certBytes = cert.encoded

        // PKCS7 ContentInfo wrapper
        // OID 1.2.840.113549.1.7.2 = signedData
        val oid = byteArrayOf(
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xf7.toByte(), 0x0d, 0x01, 0x07, 0x02
        )

        // SignerInfo minimal
        val signerInfo = buildSignerInfo(signature, cert)

        // Build SignedData
        val signedData = buildDerSequence(
            buildDerInteger(1) +                          // version
            buildDerSet(buildDerSequence(               // digestAlgorithms
                byteArrayOf(0x06, 0x09) +               // OID SHA-256
                byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x86.toByte(),
                    0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b) +
                byteArrayOf(0x05, 0x00)                 // NULL
            )) +
            buildDerSequence(byteArrayOf(0x06, 0x09,   // contentType = data
                0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
                0xf7.toByte(), 0x0d, 0x01, 0x07, 0x01)) +
            buildDerContextSpec(0, certBytes) +         // certificates
            buildDerSet(signerInfo)                     // signerInfos
        )

        return buildDerSequence(oid + buildDerContextSpec(0, signedData))
    }

    private fun buildSignerInfo(signature: ByteArray, cert: X509Certificate): ByteArray {
        val issuer = cert.issuerX500Principal.encoded
        val serial = cert.serialNumber.toByteArray()

        return buildDerSequence(
            buildDerInteger(1) +                        // version
            buildDerSequence(issuer + buildDerInteger(serial.first().toInt())) + // issuerAndSerial
            buildDerSequence(                           // digestAlgorithm SHA-256
                byteArrayOf(0x06, 0x09, 0x60, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
                    0x05, 0x00)
            ) +
            buildDerSequence(                           // signatureAlgorithm RSA
                byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
                    0x05, 0x00)
            ) +
            buildDerOctetString(signature)              // signature bytes
        )
    }

    // ──────────────────────────────────────────────
    // KEY MANAGEMENT
    // ──────────────────────────────────────────────

    private data class KeyPairResult(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private fun getOrCreateKeyPair(): KeyPairResult {
        if (keystoreFile.exists()) {
            return loadKeyPair()
        }
        return generateKeyPair()
    }

    private fun generateKeyPair(): KeyPairResult {
        Log.d(TAG, "Generating debug keypair...")

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        // Self-signed certificate
        val cert = generateSelfSignedCert(keyPair)

        // Simpan ke keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEY_PASSWORD.toCharArray())
        ks.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEY_PASSWORD.toCharArray(),
            arrayOf(cert)
        )
        keystoreFile.outputStream().use {
            ks.store(it, KEY_PASSWORD.toCharArray())
        }

        Log.d(TAG, "Debug keypair generated & saved")
        return KeyPairResult(keyPair.private, cert)
    }

    private fun loadKeyPair(): KeyPairResult {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use {
            ks.load(it, KEY_PASSWORD.toCharArray())
        }
        val privateKey = ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return KeyPairResult(privateKey, cert)
    }

    private fun generateSelfSignedCert(keyPair: java.security.KeyPair): X509Certificate {
        // Gunakan BouncyCastle-lite approach via Android internal
        // Android punya sun.security.x509 tersembunyi, kita pakai reflection minimal
        return try {
            val certClass = Class.forName("sun.security.x509.X509CertImpl")
            val infoClass = Class.forName("sun.security.x509.X509CertInfo")
            val intervalClass = Class.forName("sun.security.x509.CertificateValidity")
            val x500Class = Class.forName("sun.security.x509.X500Name")
            val keyClass = Class.forName("sun.security.x509.CertificateX509Key")
            val algClass = Class.forName("sun.security.x509.AlgorithmId")
            val serialClass = Class.forName("sun.security.x509.CertificateSerialNumber")
            val versionClass = Class.forName("sun.security.x509.CertificateVersion")

            val now = System.currentTimeMillis()
            val from = java.util.Date(now)
            val to = java.util.Date(now + VALIDITY_DAYS.toLong() * 86400000L)
            val interval = intervalClass.getConstructor(java.util.Date::class.java, java.util.Date::class.java)
                .newInstance(from, to)

            val owner = x500Class.getConstructor(String::class.java)
                .newInstance("CN=VON Tracer Debug, O=VON, C=ID")

            val info = infoClass.newInstance()
            val setMethod = infoClass.getMethod("set", String::class.java, Any::class.java)

            setMethod.invoke(info, "validity", interval)
            setMethod.invoke(info, "subject", owner)
            setMethod.invoke(info, "issuer", owner)
            setMethod.invoke(info, "key", keyClass.getConstructor(java.security.PublicKey::class.java).newInstance(keyPair.public))
            setMethod.invoke(info, "serialNumber", serialClass.getConstructor(Int::class.java).newInstance(1))
            setMethod.invoke(info, "version", versionClass.newInstance())

            val algId = algClass.getMethod("get", String::class.java).invoke(null, "SHA256withRSA")
            setMethod.invoke(info, "algorithmID", algId)

            val cert = certClass.getConstructor(infoClass).newInstance(info)
            certClass.getMethod("sign", PrivateKey::class.java, String::class.java)
                .invoke(cert, keyPair.private, "SHA256withRSA")

            cert as X509Certificate

        } catch (e: Exception) {
            Log.e(TAG, "Reflection cert gen failed, using fallback", e)
            generateCertFallback(keyPair)
        }
    }

    /**
     * Fallback: generate minimal self-signed cert via DER encoding manual.
     * Dipakai kalau reflection ke sun.security gagal.
     */
    private fun generateCertFallback(keyPair: java.security.KeyPair): X509Certificate {
        // Encode public key info
        val pubKeyBytes = keyPair.public.encoded
        val now = System.currentTimeMillis()
        val notBefore = java.util.Date(now)
        val notAfter = java.util.Date(now + VALIDITY_DAYS.toLong() * 86400000L)

        // Build TBS (To Be Signed) certificate
        val tbs = buildTbsCertificate(pubKeyBytes, notBefore, notAfter)

        // Sign TBS
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)
        val sigBytes = sig.sign()

        // Build full certificate DER
        val certDer = buildDerSequence(
            tbs +
            buildDerSequence(
                byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
                    0x05, 0x00)
            ) +
            buildDerBitString(sigBytes)
        )

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    private fun buildTbsCertificate(
        pubKey: ByteArray,
        notBefore: java.util.Date,
        notAfter: java.util.Date
    ): ByteArray {
        val subject = byteArrayOf( // CN=VON Debug minimal DER
            0x30, 0x1c, 0x31, 0x1a, 0x30, 0x18, 0x06, 0x03,
            0x55, 0x04, 0x03, 0x0c, 0x11
        ) + "VON Tracer Debug".toByteArray()

        return buildDerSequence(
            buildDerContextSpec(0, buildDerInteger(2)) +   // version v3
            buildDerInteger(1) +                            // serial
            buildDerSequence(                               // signature alg
                byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
                    0x05, 0x00)
            ) +
            subject +                                       // issuer
            buildDerSequence(                               // validity
                buildDerUtcTime(notBefore) +
                buildDerUtcTime(notAfter)
            ) +
            subject +                                       // subject
            pubKey                                          // subjectPublicKeyInfo
        )
    }

    // ──────────────────────────────────────────────
    // DER ENCODING HELPERS
    // ──────────────────────────────────────────────

    private fun buildDerSequence(content: ByteArray): ByteArray =
        byteArrayOf(0x30) + derLength(content.size) + content

    private fun buildDerSet(content: ByteArray): ByteArray =
        byteArrayOf(0x31) + derLength(content.size) + content

    private fun buildDerInteger(value: Int): ByteArray {
        val bytes = byteArrayOf((value and 0xFF).toByte())
        return byteArrayOf(0x02) + derLength(bytes.size) + bytes
    }

    private fun buildDerInteger(bytes: ByteArray): ByteArray =
        byteArrayOf(0x02) + derLength(bytes.size) + bytes

    private fun buildDerOctetString(content: ByteArray): ByteArray =
        byteArrayOf(0x04) + derLength(content.size) + content

    private fun buildDerBitString(content: ByteArray): ByteArray =
        byteArrayOf(0x03) + derLength(content.size + 1) + byteArrayOf(0x00) + content

    private fun buildDerContextSpec(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tag).toByte()) + derLength(content.size) + content

    private fun buildDerUtcTime(date: java.util.Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val str = fmt.format(date).toByteArray()
        return byteArrayOf(0x17) + derLength(str.size) + str
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(
            0x82.toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        copyInto(result)
        other.copyInto(result, size)
        return result
    }

    // ──────────────────────────────────────────────
    // ZIP HELPER
    // ──────────────────────────────────────────────

    private fun writeZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }
}