package br.com.streamswitch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.conscrypt.Conscrypt
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
import java.io.IOException
import java.math.BigInteger
import java.net.Inet4Address
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object WirelessAdbController {
    private const val PREFS = "wireless_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private val commandQueue = Executors.newSingleThreadExecutor()
    private val managerLock = Any()
    private val connectionEpoch = AtomicLong()

    @Volatile
    private var connectionManager: SwitchAdbConnectionManager? = null

    private val allowedKeyCodes = setOf(4, 19, 20, 21, 22, 23, 66, 96, 97, 99, 100, 102, 103)

    fun wasPaired(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PAIRED, false)) return true
        if (!prefs.contains(KEY_PAIRED)) return false

        // Older versions cleared this flag on disconnect while keeping the ADB key on disk.
        val identityDir = java.io.File(appContext.filesDir, "wireless-adb")
        val hasSavedIdentity = java.io.File(identityDir, "adb-key.pk8").isFile &&
            java.io.File(identityDir, "adb-certificate.der").isFile
        if (hasSavedIdentity) {
            prefs.edit()
                .putBoolean(KEY_PAIRED, true)
                .putBoolean(KEY_AUTO_RECONNECT, false)
                .apply()
        }
        return hasSavedIdentity
    }

    fun shouldAutoReconnect(context: Context): Boolean =
        wasPaired(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RECONNECT, true)

    fun enableReconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT, true)
            .apply()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun pairAndConnect(context: Context, port: Int?, pairingCode: String) {
        require(port == null || port in 1..65535) { "A porta de pareamento não é válida." }
        require(pairingCode.matches(Regex("\\d{6}"))) { "O código precisa ter seis dígitos." }

        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
            ?: throw IllegalStateException("Conecte o Android ao Wi-Fi antes de parear.")
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            throw IllegalStateException("Conecte o Android ao Wi-Fi antes de parear.")
        }
        val localAddresses = connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filter { !it.isLoopbackAddress }
            .orEmpty()
        val host = localAddresses
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
            ?: throw IllegalStateException("Não encontrei o IP do Wi-Fi.")

        val pairingPort = port ?: AdbPairingDiscovery.findOwnPort(appContext, localAddresses)
            ?: throw IllegalStateException(
                "Não encontrei a porta de pareamento. Mantenha 'Parear usando código' aberta e envie porta + código."
            )

        SharedState.adbConnected = false
        SharedState.adbStatus = "Pareando a depuração sem fio…"
        val manager = getManager(appContext)
        try {
            if (!manager.pair(host, pairingPort, pairingCode)) {
                throw IllegalStateException("O servidor ADB não confirmou o pareamento.")
            }
        } catch (error: ConnectException) {
            throw IllegalStateException(
                "Não consegui acessar a porta $pairingPort. Mantenha a tela de pareamento aberta e confirme a porta.",
                error
            )
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException("A porta $pairingPort não respondeu. Gere um código novo e tente de novo.", error)
        } catch (error: Exception) {
            throw IllegalStateException(
                "O pareamento na porta $pairingPort falhou. Confira o código exibido agora nos Ajustes.",
                error
            )
        }

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAIRED, true)
            .putBoolean(KEY_AUTO_RECONNECT, true)
            .apply()
        if (!manager.connectTls(appContext, 12_000L)) {
            throw IllegalStateException("Pareamento concluído, mas a conexão ADB não apareceu. Volte ao app para reconectar.")
        }
        SharedState.adbConnected = true
        SharedState.adbStatus = "ADB Wi-Fi conectado · Joy-Cons ativos"
        TouchBridge.start(appContext, manager)
    }

    fun reconnect(context: Context): Boolean {
        if (!wasPaired(context)) return false
        val attemptEpoch = connectionEpoch.get()
        return try {
            val manager = freshManager(context.applicationContext)
            val connected = manager.connectTls(context.applicationContext, 6_000L)
            if (attemptEpoch != connectionEpoch.get() || !shouldAutoReconnect(context)) {
                synchronized(managerLock) {
                    if (connectionManager === manager) {
                        try { manager.close() } catch (_: Exception) { }
                        connectionManager = null
                    }
                }
                return false
            }
            SharedState.adbConnected = connected
            SharedState.adbStatus = if (connected) {
                "ADB Wi-Fi conectado · Joy-Cons ativos"
            } else {
                "Depuração sem fio desligada. Ative-a nos Ajustes."
            }
            if (connected) TouchBridge.start(context.applicationContext, manager)
            connected
        } catch (error: Exception) {
            if (attemptEpoch != connectionEpoch.get()) return false
            SharedState.adbConnected = false
            SharedState.adbStatus = "ADB Wi-Fi indisponível. Ative a depuração sem fio e tente de novo."
            false
        }
    }

    fun disconnect(context: Context) {
        connectionEpoch.incrementAndGet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT, false)
            .apply()
        SharedState.adbConnected = false
        SharedState.adbStatus = "Joy-Cons desconectados · pareamento ADB salvo"
        TouchBridge.stop()
        synchronized(managerLock) {
            try {
                connectionManager?.close()
            } catch (_: Exception) {
            }
            connectionManager = null
        }
    }

    fun key(keyCode: Int): Boolean {
        if (keyCode !in allowedKeyCodes || !SharedState.adbConnected) return false
        if (TouchBridge.send("k $keyCode\n")) return true
        return queueInput("input keyevent $keyCode")
    }

    fun tap(x: Int, y: Int): Boolean {
        if (!SharedState.adbConnected) return false
        val safeX = x.coerceAtLeast(0)
        val safeY = y.coerceAtLeast(0)
        if (TouchBridge.send("d 9 $safeX $safeY\nu 9\n")) return true
        return queueInput("input tap $safeX $safeY")
    }

    /** Used only when the multi-touch injector is not running. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (!SharedState.adbConnected) return false
        val duration = durationMs.coerceIn(50L, 3_000L)
        return queueInput(
            "input swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} " +
                "${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} $duration"
        )
    }

    private fun queueInput(command: String): Boolean {
        val manager = connectionManager ?: return false
        return try {
            commandQueue.execute {
                try {
                    manager.openStream("shell:$command").use { stream ->
                        val output = ByteArray(128)
                        val input = stream.openInputStream()
                        while (true) {
                            try {
                                if (input.read(output) < 0) break
                            } catch (closed: IOException) {
                                // libadb 3.1.1 can surface its normal close frame as an IOException.
                                if (closed.message?.contains("stream closed", ignoreCase = true) == true) break
                                throw closed
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (connectionManager === manager) {
                        SharedState.adbConnected = false
                        SharedState.adbStatus = "Conexão ADB interrompida · reconecte a depuração sem fio"
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getManager(context: Context): SwitchAdbConnectionManager = synchronized(managerLock) {
        connectionManager ?: SwitchAdbConnectionManager(context.applicationContext).also {
            connectionManager = it
        }
    }

    private fun freshManager(context: Context): SwitchAdbConnectionManager = synchronized(managerLock) {
        try { connectionManager?.close() } catch (_: Exception) { }
        connectionManager = null
        SwitchAdbConnectionManager(context.applicationContext).also { connectionManager = it }
    }
}

private class SwitchAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {
    private val identity: AdbIdentity = loadOrCreateIdentity(context.applicationContext)

    init {
        setApi(Build.VERSION.SDK_INT)
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = "Android Switch Mirror"

    private data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate)

    private fun loadOrCreateIdentity(context: Context): AdbIdentity {
        val directory = java.io.File(context.filesDir, "wireless-adb").apply { mkdirs() }
        val keyFile = java.io.File(directory, "adb-key.pk8")
        val certificateFile = java.io.File(directory, "adb-certificate.der")

        if (keyFile.isFile && certificateFile.isFile) {
            try {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val certificate = certificateFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
                return AdbIdentity(key, certificate)
            } catch (_: Exception) {
                keyFile.delete()
                certificateFile.delete()
            }
        }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val certificate = createCertificate(keyPair)
        keyFile.writeBytes(keyPair.private.encoded)
        certificateFile.writeBytes(certificate.encoded)
        return AdbIdentity(keyPair.private, certificate)
    }

    private fun createCertificate(keyPair: KeyPair): Certificate {
        val algorithm = "SHA512withRSA"
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=Android Switch Mirror")
        val extensions = CertificateExtensions().apply {
            set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(keyPair.public).identifier)
            )
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(BigInteger(64, SecureRandom())))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(subject))
            set("key", CertificateX509Key(keyPair.public))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(subject))
            set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(keyPair.private, algorithm) }
    }
}
