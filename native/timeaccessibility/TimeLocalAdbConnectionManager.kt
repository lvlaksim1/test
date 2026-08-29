// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** Persistent ADB identity used only for the app's local Wireless Debugging connection. */
class TimeLocalAdbConnectionManager private constructor(private val context: Context) : AbsAdbConnectionManager() {
    companion object {
        @Volatile private var instance: TimeLocalAdbConnectionManager? = null

        fun getInstance(context: Context): TimeLocalAdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: TimeLocalAdbConnectionManager(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "time_machine_adb_keys"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_CERTIFICATE = "certificate"
    }

    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private lateinit var privateKey: PrivateKey
    private lateinit var certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        loadOrGenerateKeys()
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "TimeMachine"

    private fun loadOrGenerateKeys() {
        val privateValue = preferences.getString(KEY_PRIVATE, null)
        val certificateValue = preferences.getString(KEY_CERTIFICATE, null)
        if (privateValue != null && certificateValue != null) {
            val loaded = runCatching {
                privateKey = loadPrivateKey(privateValue)
                certificate = loadCertificate(certificateValue)
            }.isSuccess
            if (loaded) return
        }
        generateAndSaveKeys()
    }

    private fun generateAndSaveKeys() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val pair = generator.generateKeyPair()
        privateKey = pair.private

        val subject = X500Name("CN=TimeMachine")
        val algorithmName = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365L * 24L * 60L * 60L * 1000L)
        val extensions = CertificateExtensions()
        extensions.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Integer.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
        info.set("subject", CertificateSubjectName(subject))
        info.set("key", CertificateX509Key(pair.public))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(subject))
        info.set("extensions", extensions)

        val generated = X509CertImpl(info)
        generated.sign(privateKey, algorithmName)
        certificate = generated

        preferences.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP))
            .putString(KEY_CERTIFICATE, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
            .apply()
    }

    private fun loadPrivateKey(encoded: String): PrivateKey {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun loadCertificate(encoded: String): Certificate {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
    }
}
