package com.von.tracer.injector

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Signer(
    private val context: Context,
    private val workDir: File
) {

    companion object {
        private const val TAG = "Signer"
        private const val KEYSTORE_FILE = "debug.keystore"
        private const val KEY_ALIAS = "von_debug"
        private const val KEY_PASSWORD = "von_tracer"
    }

    private val keystoreFile = File(context.filesDir, KEYSTORE_FILE)

    // ──────────────────────────────────────────────
    // ZIPALIGN
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // SIGN
    // ──────────────────────────────────────────────

    fun sign(apkFile: File): File {
        Log.d(TAG, "Signing: ${apkFile.name}")

        val keyPair = getOrCreateKeyPair()
        val output = File(workDir, "target_signed.apk")

        signV1(apkFile, output, keyPair.privateKey, keyPair.certificate)

        Log.d(TAG, "Signed: ${output.name} (${output.length() / 1024}KB)")
        return output
    }

    // ──────────────────────────────────────────────
    // KEY MANAGEMENT — pakai BouncyCastle via SpongyCastle
    // atau fallback ke JKS keystore generate via keytool-style
    // ──────────────────────────────────────────────

    private data class KeyPairResult(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private fun getOrCreateKeyPair(): KeyPairResult {
        // Coba load dari keystore yang sudah ada
        if (keystoreFile.exists()) {
            try {
                val result = loadKeyPair()
                Log.d(TAG, "Loaded existing keypair")
                return result
            } catch (e: Exception) {
                Log.w(TAG, "Load keypair failed, regenerating: ${e.message}")
                keystoreFile.delete()
            }
        }
        return generateKeyPair()
    }

    private fun generateKeyPair(): KeyPairResult {
        Log.d(TAG, "Generating keypair via Android APIs...")

        // Generate RSA keypair
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        // Generate self-signed cert via sun.security (tersedia di Android)
        val cert = generateCertViaSunSecurity(keyPair)
        Log.d(TAG, "Cert generated: ${cert.subjectDN}")

        // Simpan ke PKCS12 keystore
        saveKeystore(keyPair.private, cert)

        return KeyPairResult(keyPair.private, cert)
    }

    /**
     * Generate self-signed X509 cert menggunakan sun.security.x509
     * yang tersedia di Android runtime (meskipun hidden API).
     * Ini jauh lebih reliable daripada manual DER encoding.
     */
    private fun generateCertViaSunSecurity(
        keyPair: java.security.KeyPair
    ): X509Certificate {

        return try {
            generateViaSunX509(keyPair)
        } catch (e: Exception) {
            Log.w(TAG, "sun.security failed: ${e.message}, trying OpenSSL path...")
            try {
                generateViaOpenSSLProvider(keyPair)
            } catch (e2: Exception) {
                Log.w(TAG, "OpenSSL path failed: ${e2.message}, using minimal DER...")
                generateViaMinimalDer(keyPair)
            }
        }
    }

    // ── Approach 1: sun.security.x509 reflection ──

    private fun generateViaSunX509(keyPair: java.security.KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val from = java.util.Date(now - 1000L)
        val to   = java.util.Date(now + 25L * 365 * 24 * 3600 * 1000)

        // Load semua class via reflection
        val x500Name       = Class.forName("sun.security.x509.X500Name")
        val certInfo       = Class.forName("sun.security.x509.X509CertInfo")
        val certImpl       = Class.forName("sun.security.x509.X509CertImpl")
        val validity       = Class.forName("sun.security.x509.CertificateValidity")
        val certKey        = Class.forName("sun.security.x509.CertificateX509Key")
        val algId          = Class.forName("sun.security.x509.AlgorithmId")
        val certSerial     = Class.forName("sun.security.x509.CertificateSerialNumber")
        val certVersion    = Class.forName("sun.security.x509.CertificateVersion")
        val certAlgId      = Class.forName("sun.security.x509.CertificateAlgorithmId")

        val name = x500Name
            .getConstructor(String::class.java)
            .newInstance("CN=VON Tracer, O=VON, C=ID")

        val interval = validity
            .getConstructor(java.util.Date::class.java, java.util.Date::class.java)
            .newInstance(from, to)

        val pubKey = certKey
            .getConstructor(java.security.PublicKey::class.java)
            .newInstance(keyPair.public)

        val sha256rsa = algId
            .getMethod("get", String::class.java)
            .invoke(null, "SHA256withRSA")

        val info = certInfo.newInstance()
        val setMethod = certInfo.getMethod("set", String::class.java, Any::class.java)

        setMethod.invoke(info, "version",
            certVersion.newInstance())
        setMethod.invoke(info, "serialNumber",
            certSerial.getConstructor(Int::class.java).newInstance(1))
        setMethod.invoke(info, "algorithmID",
            certAlgId.getConstructor(algId).newInstance(sha256rsa))
        setMethod.invoke(info, "subject", name)
        setMethod.invoke(info, "key", pubKey)
        setMethod.invoke(info, "validity", interval)
        setMethod.invoke(info, "issuer", name)

        val cert = certImpl
            .getConstructor(certInfo)
            .newInstance(info)

        certImpl
            .getMethod("sign", PrivateKey::class.java, String::class.java)
            .invoke(cert, keyPair.private, "SHA256withRSA")

        return cert as X509Certificate
    }

    // ── Approach 2: Conscrypt / OpenSSL provider ──

    private fun generateViaOpenSSLProvider(keyPair: java.security.KeyPair): X509Certificate {
        // Android 7+ punya com.android.org.conscrypt
        // Kita pakai X509V3CertificateGenerator style via reflection
        val genClass = try {
            Class.forName("org.bouncycastle.x509.X509V1CertificateGenerator")
        } catch (e: Exception) {
            Class.forName("org.spongycastle.x509.X509V1CertificateGenerator")
        }

        val now  = System.currentTimeMillis()
        val gen  = genClass.newInstance()

        val bigIntClass = java.math.BigInteger::class.java
        val dateClass   = java.util.Date::class.java
        val x500Class   = try {
            Class.forName("org.bouncycastle.asn1.x500.X500Name")
        } catch (e: Exception) {
            Class.forName("org.spongycastle.asn1.x500.X500Name")
        }

        val dn = x500Class.getConstructor(String::class.java)
            .newInstance("CN=VON Tracer, O=VON, C=ID")

        genClass.getMethod("setSerialNumber", bigIntClass)
            .invoke(gen, java.math.BigInteger.ONE)
        genClass.getMethod("setIssuerDN", x500Class)
            .invoke(gen, dn)
        genClass.getMethod("setNotBefore", dateClass)
            .invoke(gen, java.util.Date(now - 1000L))
        genClass.getMethod("setNotAfter", dateClass)
            .invoke(gen, java.util.Date(now + 25L * 365 * 24 * 3600 * 1000))
        genClass.getMethod("setSubjectDN", x500Class)
            .invoke(gen, dn)
        genClass.getMethod("setPublicKey", java.security.PublicKey::class.java)
            .invoke(gen, keyPair.public)
        genClass.getMethod("setSignatureAlgorithm", String::class.java)
            .invoke(gen, "SHA256WithRSAEncryption")

        val cert = genClass.getMethod("generate", PrivateKey::class.java)
            .invoke(gen, keyPair.private)

        // Convert ke standard X509Certificate
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val encoded = (cert as java.security.cert.Certificate).encoded
        return cf.generateCertificate(encoded.inputStream()) as X509Certificate
    }

    // ── Approach 3: Minimal DER (diperbaiki) ──

    /**
     * Build X.509 cert dengan DER encoding yang benar.
     * Versi ini diperbaiki dari sebelumnya — struktur lebih ketat.
     */
    private fun generateViaMinimalDer(keyPair: java.security.KeyPair): X509Certificate {
        Log.d(TAG, "Building cert via minimal DER...")

        val now     = System.currentTimeMillis()
        val notBefore = java.util.Date(now - 1000L)
        val notAfter  = java.util.Date(now + 25L * 365 * 24 * 3600 * 1000)

        // OID SHA256withRSA = 1.2.840.113549.1.1.11
        val sha256WithRSAOid = byteArrayOf(
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x01, 0x0b
        )
        val nullParam = byteArrayOf(0x05, 0x00)
        val sigAlg = derSeq(sha256WithRSAOid + nullParam)

        // Subject / Issuer DN: CN=VON Tracer
        val cnOid = byteArrayOf(
            0x06, 0x03, 0x55, 0x04, 0x03 // OID 2.5.4.3 = commonName
        )
        val cnVal = derUtf8("VON Tracer")
        val dn = derSeq(derSet(derSeq(cnOid + cnVal)))

        // Validity
        val validity = derSeq(
            derUtcTime(notBefore) +
            derUtcTime(notAfter)
        )

        // SubjectPublicKeyInfo — ambil dari encoded public key
        val spki = keyPair.public.encoded

        // Serial number
        val serial = derInt(byteArrayOf(0x01))

        // Version v3 = [0] EXPLICIT INTEGER 2
        val version = byteArrayOf(
            0xa0.toByte(), 0x03, 0x02, 0x01, 0x02
        )

        // TBSCertificate
        val tbs = derSeq(
            version +
            serial +
            sigAlg +
            dn +        // issuer
            validity +
            dn +        // subject
            spki
        )

        // Sign TBS
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)
        val sigBytes = sig.sign()

        // Full certificate
        val certDer = derSeq(
            tbs +
            sigAlg +
            derBitStr(sigBytes)
        )

        Log.d(TAG, "DER cert size: ${certDer.size} bytes")

        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certDer.inputStream()) as X509Certificate
    }

    // ──────────────────────────────────────────────
    // KEYSTORE SAVE / LOAD
    // ──────────────────────────────────────────────

    private fun saveKeystore(privateKey: PrivateKey, cert: X509Certificate) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEY_PASSWORD.toCharArray())
        ks.setKeyEntry(
            KEY_ALIAS,
            privateKey,
            KEY_PASSWORD.toCharArray(),
            arrayOf(cert)
        )
        keystoreFile.outputStream().use {
            ks.store(it, KEY_PASSWORD.toCharArray())
        }
        Log.d(TAG, "Keystore saved: ${keystoreFile.absolutePath}")
    }

    private fun loadKeyPair(): KeyPairResult {
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use {
            ks.load(it, KEY_PASSWORD.toCharArray())
        }
        val privateKey = ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        Log.d(TAG, "Keypair loaded from keystore")
        return KeyPairResult(privateKey, cert)
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
        val entries = mutableMapOf<String, ByteArray>()

        // Pass 1: digest semua entry
        ZipFile(inputApk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.name.startsWith("META-INF/")) return@forEach
                val bytes = zip.getInputStream(entry).readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                entries[entry.name] = digest
            }
        }

        val manifest = buildManifest(entries)
        val certSf   = buildCertSf(manifest)
        val certRsa  = buildCertRsa(certSf, privateKey, certificate)

        // Pass 2: repack dengan META-INF baru
        ZipFile(inputApk).use { sourceZip ->
            ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
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

                writeEntry(outZip, "META-INF/MANIFEST.MF", manifest)
                writeEntry(outZip, "META-INF/CERT.SF", certSf)
                writeEntry(outZip, "META-INF/CERT.RSA", certRsa)
            }
        }
    }

    private fun buildManifest(entries: Map<String, ByteArray>): ByteArray {
        val sb = StringBuilder()
        sb.append("Manifest-Version: 1.0\r\n")
        sb.append("Created-By: VON Tracer\r\n")
        sb.append("\r\n")
        entries.forEach { (name, digest) ->
            val b64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            sb.append("Name: $name\r\n")
            sb.append("SHA-256-Digest: $b64\r\n")
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildCertSf(manifest: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(manifest)
        val b64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
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
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(certSf)
        val sigBytes = sig.sign()
        return buildPkcs7(sigBytes, certificate)
    }

    private fun buildPkcs7(signature: ByteArray, cert: X509Certificate): ByteArray {
        val certBytes = cert.encoded

        // OID 1.2.840.113549.1.7.2 = pkcs7-signedData
        val contentTypeOid = byteArrayOf(
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x07, 0x02
        )

        // OID SHA-256 = 2.16.840.1.101.3.4.2.1
        val sha256Oid = byteArrayOf(
            0x06, 0x09,
            0x60, 0x86.toByte(), 0x48, 0x01, 0x65,
            0x03, 0x04, 0x02, 0x01
        )

        // OID RSA = 1.2.840.113549.1.1.1
        val rsaOid = byteArrayOf(
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x01, 0x01
        )

        // OID pkcs7-data = 1.2.840.113549.1.7.1
        val dataOid = byteArrayOf(
            0x06, 0x09,
            0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x07, 0x01
        )

        // Issuer serial dari cert
        val issuerBytes  = cert.issuerX500Principal.encoded
        val serialBytes  = cert.serialNumber.toByteArray().let { b ->
            // Pastikan tidak ada leading zero yang tidak perlu
            if (b.size > 1 && b[0] == 0.toByte()) b.drop(1).toByteArray() else b
        }

        val issuerAndSerial = derSeq(issuerBytes + derInt(serialBytes))

        val signerInfo = derSeq(
            derInt(byteArrayOf(0x01)) +                   // version = 1
            issuerAndSerial +
            derSeq(sha256Oid + nullBytes()) +              // digestAlgorithm
            derSeq(rsaOid + nullBytes()) +                 // signatureAlgorithm
            derOctetStr(signature)                         // signature
        )

        val signedData = derSeq(
            derInt(byteArrayOf(0x01)) +                   // version
            derSet(derSeq(sha256Oid + nullBytes())) +      // digestAlgorithms
            derSeq(dataOid) +                              // encapContentInfo
            derContextImplicit(0, certBytes) +             // certificates [0]
            derSet(signerInfo)                             // signerInfos
        )

        return derSeq(
            contentTypeOid +
            derContextExplicit(0, signedData)
        )
    }

    // ──────────────────────────────────────────────
    // DER HELPERS — versi clean & benar
    // ──────────────────────────────────────────────

    private fun derLen(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(
            0x82.toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        else -> byteArrayOf(
            0x83.toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
    }

    private fun derTag(tag: Byte, content: ByteArray) =
        byteArrayOf(tag) + derLen(content.size) + content

    private fun derSeq(content: ByteArray)     = derTag(0x30, content)
    private fun derSet(content: ByteArray)     = derTag(0x31, content)
    private fun derOctetStr(content: ByteArray) = derTag(0x04, content)
    private fun derBitStr(content: ByteArray)  = derTag(0x03, byteArrayOf(0x00) + content)
    private fun derUtf8(s: String)             = derTag(0x0c, s.toByteArray(Charsets.UTF_8))
    private fun nullBytes()                    = byteArrayOf(0x05, 0x00)

    private fun derInt(bytes: ByteArray): ByteArray {
        // Pastikan integer tidak negatif (prepend 0x00 jika MSB set)
        val b = if (bytes.isNotEmpty() && bytes[0].toInt() and 0x80 != 0) {
            byteArrayOf(0x00) + bytes
        } else bytes
        return derTag(0x02, b)
    }

    private fun derContextExplicit(tag: Int, content: ByteArray) =
        byteArrayOf((0xA0 or tag).toByte()) + derLen(content.size) + content

    private fun derContextImplicit(tag: Int, content: ByteArray) =
        byteArrayOf((0xA0 or tag).toByte()) + derLen(content.size) + content

    private fun derUtcTime(date: java.util.Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out)
        other.copyInto(out, size)
        return out
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }
}