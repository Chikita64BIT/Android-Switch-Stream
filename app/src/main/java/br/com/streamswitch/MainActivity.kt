package br.com.streamswitch

import android.app.Activity
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var notice: TextView
    private lateinit var streamBadge: TextView
    private lateinit var streamTitle: TextView
    private lateinit var streamDescription: TextView
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var url: TextView
    private lateinit var controlValue: TextView
    private lateinit var controlHelp: TextView
    private lateinit var controlButton: TextView
    private lateinit var pairAgainButton: TextView
    private lateinit var wifiValue: TextView
    private lateinit var internetValue: TextView
    private lateinit var latencyValue: TextView
    private lateinit var ipValue: TextView
    private lateinit var networkSummary: TextView
    private lateinit var networkHint: TextView
    private lateinit var networkTestButton: TextView

    private var lastDisplayedUrl: String? = null
    private var reconnectingAdb = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val notificationPermissionRequestCode = 4201

    private val screenBackground = Color.rgb(10, 12, 20)
    private val surface = Color.rgb(19, 22, 34)
    private val surfaceRaised = Color.rgb(26, 30, 45)
    private val border = Color.rgb(43, 49, 68)
    private val textPrimary = Color.rgb(244, 246, 255)
    private val textSecondary = Color.rgb(160, 169, 192)
    private val mint = Color.rgb(98, 230, 190)
    private val purple = Color.rgb(150, 129, 255)

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStreamState()
            refreshControlState()
            uiHandler.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        buildScreen()
        uiHandler.post(refreshTask)
        refreshControlState()
        updateNetworkSnapshot()
        reconnectAdbIfPaired()
    }

    override fun onResume() {
        super.onResume()
        if (::wifiValue.isInitialized) updateNetworkSnapshot()
        if (::controlValue.isInitialized) {
            refreshControlState()
            reconnectAdbIfPaired()
        }
    }

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(screenBackground)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = text("S", 18f, textPrimary, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(108, 93, 225), 13)
        }
        topBar.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(text("SWITCH STREAM", 13f, textPrimary, medium = true))
        brand.addView(text("ANDROID MIRROR", 10f, textSecondary, medium = true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(3), 0, 0)
        })
        topBar.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(makePill("REDE LOCAL", mint))
        content.addView(topBar)

        addGap(content, 26)
        content.addView(text("Seu painel de\ntransmissão", 30f, textPrimary, medium = true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
        })
        content.addView(text(
            "Veja o Android no Switch e controle tudo pela mesma rede Wi-Fi.",
            14f,
            textSecondary
        ).apply { setPadding(0, dp(9), 0, dp(19)) })

        val hero = card(content, padding = 18, color = Color.rgb(24, 27, 47), gradient = true)
        hero.addView(text("ESPELHAMENTO DE TELA", 11f, mint, medium = true).apply {
            letterSpacing = 0.08f
        })
        hero.addView(text("", 1f, textPrimary).apply { height = dp(3) })
        streamBadge = text("PRONTO PARA COMEÇAR", 11f, textSecondary, medium = true).apply {
            setPadding(0, dp(5), 0, dp(8))
        }
        hero.addView(streamBadge)
        streamTitle = text("Sua tela, no Switch", 22f, textPrimary, medium = true)
        hero.addView(streamTitle)
        streamDescription = text(
            "Inicie a captura e aprove a solicitação do Android para criar o acesso local.",
            13f,
            textSecondary
        ).apply {
            setPadding(0, dp(7), 0, dp(17))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        hero.addView(streamDescription)
        val streamActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = actionButton("Iniciar transmissão", primary = true) { requestScreenCapture() }
        stopButton = actionButton("Parar", primary = false) { stopTransmission() }
        streamActions.addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(8)
        })
        streamActions.addView(stopButton, LinearLayout.LayoutParams(dp(88), dp(48)))
        hero.addView(streamActions)

        addSectionHeading(content, "Conexão", "Confira a rede antes de abrir no Switch")
        val networkCard = card(content)
        networkCard.addView(text("SAÚDE DA REDE", 11f, textSecondary, medium = true))
        networkCard.addView(text("Sua conexão local", 18f, textPrimary, medium = true).apply {
            setPadding(0, dp(5), 0, dp(3))
        })
        networkSummary = text("Faça um teste rápido de Wi-Fi e internet.", 12f, textSecondary)
        networkCard.addView(networkSummary)

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(12))
        }
        val wifiTile = metricTile("WI-FI", "Verificando")
        val internetTile = metricTile("INTERNET", "Verificando")
        val latencyTile = metricTile("RESPOSTA", "—")
        wifiValue = wifiTile.value
        internetValue = internetTile.value
        latencyValue = latencyTile.value
        metrics.addView(wifiTile.container, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(7) })
        metrics.addView(internetTile.container, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(7) })
        metrics.addView(latencyTile.container, LinearLayout.LayoutParams(0, dp(76), 1f))
        networkCard.addView(metrics)

        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        ipRow.addView(text("IP deste Android", 12f, textSecondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ipValue = text("—", 12f, textPrimary, medium = true).apply { gravity = Gravity.END }
        ipRow.addView(ipValue)
        networkCard.addView(ipRow)
        networkHint = text(
            "O Switch e o Android precisam estar na mesma rede Wi-Fi.",
            12f,
            textSecondary
        ).apply {
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = rounded(surfaceRaised, 12)
        }
        networkCard.addView(networkHint)
        networkTestButton = actionButton("Testar conexão", primary = false) { runNetworkTest() }.apply {
            setPadding(dp(14), 0, dp(14), 0)
        }
        networkCard.addView(networkTestButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply { topMargin = dp(12) })

        addSectionHeading(content, "Controles dos Joy-Cons", "ADB Wi-Fi · apenas comandos de controle")
        val controlCard = card(content)
        val controlHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val controlIcon = text("⌘", 20f, purple, medium = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(39, 34, 67), 13)
        }
        controlHeader.addView(controlIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val controlLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        controlLabels.addView(text("Modo Joy-Con", 15f, textPrimary, medium = true))
        controlValue = text("Depuração sem fio desligada", 12f, textSecondary).apply {
            setPadding(0, dp(3), 0, 0)
        }
        controlLabels.addView(controlValue)
        controlHeader.addView(controlLabels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controlCard.addView(controlHeader)
        controlHelp = text(
            "Toque para abrir as opções do desenvolvedor. Ative Depuração sem fio e gere um código de pareamento. " +
                "Depois, responda à notificação com os seis dígitos do código. O app só envia teclas e seleções dos Joy-Cons.",
            12f,
            textSecondary
        ).apply {
            setPadding(0, dp(12), 0, dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        controlCard.addView(controlHelp)
        controlButton = actionButton("Ativar ADB Wi-Fi", primary = false) {
            if (SharedState.adbConnected) disconnectWirelessAdb() else requestWirelessAdb()
        }
        controlCard.addView(controlButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        pairAgainButton = actionButton("Parear novamente", primary = false) { requestNewPairing() }
        controlCard.addView(pairAgainButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(8)
            })

        addSectionHeading(content, "Abrir no Switch", "Digite apenas o IP e a porta no navegador")
        val addressCard = card(content)
        addressCard.addView(text("ENDEREÇO DA TRANSMISSÃO", 11f, textSecondary, medium = true))
        url = text("Inicie a transmissão para gerar o endereço local.", 13f, textPrimary, medium = true).apply {
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
            background = rounded(surfaceRaised, 12)
        }
        addressCard.addView(url, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) })
        addressCard.addView(actionButton("Copiar endereço", primary = false) { copyStreamUrl() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(10)
            })

        addGap(content, 14)
        notice = text("Tudo pronto. Teste a rede e conecte o Switch.", 12f, textSecondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(surface, 12, border)
        }
        content.addView(notice, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(text("STREAM LOCAL  ·  SEM SERVIDOR EXTERNO", 10f, textSecondary, medium = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setPadding(0, dp(19), 0, 0)
        })

        setContentView(scroll)
        refreshStreamState()
    }

    private fun addSectionHeading(parent: LinearLayout, title: String, subtitle: String) {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(10))
        }
        heading.addView(text(title, 17f, textPrimary, medium = true))
        heading.addView(text(subtitle, 12f, textSecondary).apply { setPadding(0, dp(3), 0, 0) })
        parent.addView(heading)
    }

    private fun card(
        parent: LinearLayout,
        padding: Int = 16,
        color: Int = surface,
        gradient: Boolean = false
    ): LinearLayout {
        val backgroundDrawable = if (gradient) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(34, 39, 67), Color.rgb(22, 26, 43))
            ).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(58, 63, 96)) }
        } else {
            rounded(color, 18, border)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = backgroundDrawable
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private data class MetricTile(val container: LinearLayout, val value: TextView)

    private fun metricTile(label: String, value: String): MetricTile {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = rounded(surfaceRaised, 13)
        }
        tile.addView(text(label, 9f, textSecondary, medium = true))
        val valueView = text(value, 12f, textPrimary, medium = true).apply {
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
        }
        tile.addView(valueView)
        return MetricTile(tile, valueView)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): TextView {
        val fill = if (primary) Color.rgb(115, 99, 237) else surfaceRaised
        val foreground = if (primary) Color.WHITE else textPrimary
        val shape = rounded(fill, 14, if (primary) null else border)
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(38, 255, 255, 255)), shape, null)
            setOnClickListener { action() }
        }
    }

    private fun makePill(label: String, color: Int): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(23, 39, 40), 20)
        }
        val dot = View(this).apply { background = circle(color) }
        pill.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
        val labelView = text(label, 9f, color, medium = true)
        pill.addView(labelView)
        pill.tag = labelView
        return pill
    }

    private fun text(value: String, size: Float, color: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                if (medium) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun addGap(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun refreshStreamState() {
        if (!::url.isInitialized) return
        val currentUrl = SharedState.pageUrl
        val active = currentUrl?.startsWith("http://") == true
        url.text = currentUrl ?: "Inicie a transmissão para gerar o endereço local."
        streamBadge.text = if (active) "TRANSMISSÃO ATIVA" else "PRONTO PARA COMEÇAR"
        streamBadge.setTextColor(if (active) mint else textSecondary)
        streamTitle.text = if (active) "Seu Switch já pode conectar" else "Sua tela, no Switch"
        streamDescription.text = if (active) {
            "Abra o endereço abaixo no navegador do Switch. Mantenha este app em execução."
        } else {
            "Inicie a captura e aprove a solicitação do Android para criar o acesso local."
        }
        startButton.isEnabled = !active
        startButton.alpha = if (active) 0.55f else 1f
        stopButton.isEnabled = active || SharedState.pageUrl != null
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.55f
        if (active && currentUrl != lastDisplayedUrl) {
            setNotice("Servidor local pronto. Digite o endereço no Switch e mantenha os aparelhos no mesmo Wi-Fi.")
        }
        lastDisplayedUrl = currentUrl
    }

    private fun updateNetworkSnapshot() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        wifiValue.text = when {
            wifi -> "Conectado"
            ethernet -> "Ethernet"
            else -> "Sem Wi-Fi"
        }
        internetValue.text = if (hasInternet) "Disponível" else "Sem rede"
        val addresses = network?.let(manager::getLinkProperties)?.linkAddresses.orEmpty()
        ipValue.text = addresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: "Não encontrada"
        if (networkSummary.text == "Faça um teste rápido de Wi-Fi e internet.") {
            networkSummary.text = if (wifi || ethernet) {
                "Rede local detectada. Rode o teste para medir a resposta da internet."
            } else {
                "Conecte o Android ao Wi-Fi que será usado pelo Switch."
            }
        }
    }

    private data class NetworkTestResult(
        val wifi: Boolean,
        val ethernet: Boolean,
        val internetReachable: Boolean,
        val latencyMs: Long?,
        val localIp: String?
    )

    private fun runNetworkTest() {
        networkTestButton.isEnabled = false
        networkTestButton.text = "Testando conexão…"
        networkSummary.text = "Verificando Wi-Fi, internet e tempo de resposta."
        networkHint.text = "O teste consulta um endereço leve de conectividade."
        networkExecutor.execute {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            var reachable = false
            var latency: Long? = null
            try {
                val connection = (URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 4500
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }
                val started = SystemClock.elapsedRealtime()
                val response = connection.responseCode
                latency = SystemClock.elapsedRealtime() - started
                reachable = response in 200..299
                connection.disconnect()
            } catch (_: Exception) {
                reachable = false
            }
            val addresses = network?.let(manager::getLinkProperties)?.linkAddresses.orEmpty()
            val localIp = addresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
            val result = NetworkTestResult(wifi, ethernet, reachable, latency, localIp)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                networkTestButton.isEnabled = true
                networkTestButton.text = "Testar novamente"
                wifiValue.text = when {
                    result.wifi -> "Conectado"
                    result.ethernet -> "Ethernet"
                    else -> "Sem Wi-Fi"
                }
                internetValue.text = if (result.internetReachable) "Online" else "Sem acesso"
                latencyValue.text = result.latencyMs?.let { "${it} ms" } ?: "—"
                ipValue.text = result.localIp ?: "Não encontrada"
                networkSummary.text = when {
                    result.wifi && result.internetReachable -> "Rede pronta. O Switch deve usar este mesmo Wi-Fi."
                    (result.wifi || result.ethernet) && !result.internetReachable -> "Rede local ativa, mas a verificação de internet falhou."
                    result.internetReachable -> "Internet disponível; para transmitir ao Switch, use Wi-Fi."
                    else -> "Não foi possível validar a conexão. Verifique o Wi-Fi e tente de novo."
                }
                networkHint.text = when {
                    result.latencyMs == null -> "Sem resposta do teste externo. A transmissão local pode funcionar mesmo assim."
                    result.latencyMs < 100 -> "Resposta rápida · ${result.latencyMs} ms."
                    result.latencyMs < 250 -> "Resposta estável · ${result.latencyMs} ms."
                    else -> "Resposta lenta · ${result.latencyMs} ms. A imagem pode atrasar."
                }
                setNotice(if (result.wifi) "Wi-Fi detectado. Confirme que o Switch está na mesma rede." else "Conecte o Android ao Wi-Fi do Switch.", warning = !result.wifi)
            }
        }
    }

    private fun requestWirelessAdb() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setNotice("O modo Joy-Con por depuração sem fio exige Android 11 ou superior.", warning = true)
            return
        }
        if (WirelessAdbController.wasPaired(applicationContext)) {
            connectStoredAdb(manual = true)
            return
        }
        requestNewPairing()
    }

    private fun requestNewPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setNotice("O modo Joy-Con por depuração sem fio exige Android 11 ou superior.", warning = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequestCode
            )
            return
        }
        beginWirelessAdbPairing()
    }

    private fun beginWirelessAdbPairing() {
        SharedState.adbStatus = "Aguardando código de pareamento…"
        SharedState.adbConnected = false
        AdbPairingNotifications.showPairingPrompt(this)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            setNotice(
                "Nos Ajustes, ative Depuração sem fio e escolha Parear usando código. Envie os seis dígitos pela notificação.",
                warning = true
            )
        } catch (_: Exception) {
            setNotice("Não consegui abrir as opções do desenvolvedor. Abra-as manualmente e tente de novo.", warning = true)
        }
        refreshControlState()
    }

    private fun refreshControlState() {
        if (!::controlValue.isInitialized) return
        val connected = SharedState.adbConnected
        val message = SharedState.adbStatus
        controlValue.text = message
        controlValue.setTextColor(when {
            connected -> mint
            message.contains("aguardando", ignoreCase = true) || message.contains("pareando", ignoreCase = true) ->
                Color.rgb(255, 194, 112)
            else -> textSecondary
        })
        val paired = WirelessAdbController.wasPaired(applicationContext)
        controlButton.text = when {
            connected -> "Desconectar Joy-Cons"
            paired -> "Reconectar Joy-Cons"
            else -> "Ativar ADB Wi-Fi"
        }
        controlButton.isEnabled = !reconnectingAdb
        controlButton.alpha = if (reconnectingAdb) 0.55f else 1f
        pairAgainButton.visibility = if (paired && !connected) View.VISIBLE else View.GONE
        pairAgainButton.isEnabled = !reconnectingAdb
        pairAgainButton.alpha = if (reconnectingAdb) 0.55f else 1f
        controlHelp.text = when {
            connected -> "Os Joy-Cons usam o pareamento ADB salvo. Você pode desconectar e reconectar sem digitar outro código."
            paired -> "Pareamento ADB salvo. Ative Depuração sem fio nos Ajustes e toque em Reconectar. Use Parear novamente se o Android esqueceu a autorização."
            else -> "Toque para abrir as opções do desenvolvedor. Ative Depuração sem fio e gere um código de pareamento. Depois, responda à notificação com os seis dígitos."
        }
    }

    private fun reconnectAdbIfPaired() {
        if (!::controlValue.isInitialized || reconnectingAdb || SharedState.adbConnected ||
            !WirelessAdbController.shouldAutoReconnect(applicationContext)
        ) return
        connectStoredAdb(manual = false)
    }

    private fun connectStoredAdb(manual: Boolean) {
        if (reconnectingAdb) return
        if (manual) WirelessAdbController.enableReconnect(applicationContext)
        reconnectingAdb = true
        SharedState.adbStatus = "Reconectando Joy-Cons pelo ADB Wi-Fi…"
        refreshControlState()
        networkExecutor.execute {
            val connected = WirelessAdbController.reconnect(applicationContext)
            if (connected) {
                try { AdbPairingNotifications.showConnected(applicationContext) } catch (_: Exception) { }
            }
            runOnUiThread {
                reconnectingAdb = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshControlState()
                if (!connected && manual) {
                    setNotice("Ative Depuração sem fio nos Ajustes. Se o Android esqueceu este app, toque em Parear novamente.", warning = true)
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun disconnectWirelessAdb() {
        WirelessAdbController.disconnect(applicationContext)
        AdbPairingNotifications.cancel(this)
        refreshControlState()
        setNotice("Joy-Cons desconectados. O pareamento ficou salvo para a próxima reconexão.")
    }

    @Deprecated("Permission result callback is used for the optional notification permission.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequestCode) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginWirelessAdbPairing()
        } else {
            setNotice("Permita notificações para enviar o código sem fechar a tela de pareamento.", warning = true)
        }
    }

    private fun requestScreenCapture() {
        if (SharedState.pageUrl != null) {
            setNotice("A transmissão já está ativa. Use Parar antes de iniciar outra.", warning = true)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 4202)
    }

    private fun stopTransmission() {
        stopService(Intent(this, CaptureService::class.java))
        SharedState.pageUrl = null
        SharedState.latestJpeg = null
        refreshStreamState()
        setNotice("Transmissão encerrada.")
    }

    private fun copyStreamUrl() {
        val currentUrl = SharedState.pageUrl?.takeIf { it.startsWith("http://") }
        if (currentUrl == null) {
            Toast.makeText(this, "Inicie a transmissão para gerar o endereço.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Endereço Android → Switch", currentUrl))
        Toast.makeText(this, "Endereço copiado.", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Screen-capture result callback is still supported by Android.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4202) return
        if (resultCode != RESULT_OK || data == null) {
            setNotice("A captura foi cancelada. Você pode tentar novamente.", warning = true)
            return
        }
        val serviceIntent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        setNotice("Preparando a captura e o endereço local…")
    }

    private fun setNotice(message: String, warning: Boolean = false) {
        if (!::notice.isInitialized) return
        notice.text = message
        notice.setTextColor(if (warning) Color.rgb(255, 194, 112) else textSecondary)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshTask)
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}
