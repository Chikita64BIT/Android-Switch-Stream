# Android → Switch

Transmita a tela do seu celular Android para o **Nintendo Switch sem modificação** e jogue por ele, com **tela de toque multitoque** e **Joy-Con**. Feito pensando em **Wuthering Waves**, mas funciona com qualquer app.

Tudo roda na sua rede Wi-Fi local: o celular serve uma página que o navegador oculto do Switch abre.

## Download

Baixe o APK mais recente em **Releases** (`AndroidSwitchStream-v0.2.0-release.apk`) e instale no celular (Android 11 ou superior).

## Arquitetura

```text
+----------------------------------------------------------------------+
|             CELULAR ANDROID  (app Android -> Switch, um processo)    |
|                                                                      |
|   +----------------+       +--------------------------------------+  |
|   | Jogo / tela    |------>| MediaProjection + VirtualDisplay     |  |
|   | (ex.: Wuthering|       | acompanha a rotacao da tela          |  |
|   |  Waves)        |       | ate 60 FPS, sem som                  |  |
|   +-------^--------+       +------------------+-------------------+  |
|           |                    quadro 924x416 |  (qualidade Media)   |
|           | toque          JPEG em 2 nucleos  v                      |
|           | multitoque                        |                      |
|   +-------+---------------+   +---------------+------------------+   |
|   | TouchInjector (shell  |<--| HTTP :8765  (pagina, ajustes)    |   |
|   | via ADB Wi-Fi local)  |   | WebSocket :8767 (video + toque)  |   |
|   +-----------------------+   +--------------+-----------^-------+   |
+----------------------------------------------+-----------+-----------+
                     JPEG binario (WebSocket) |           | toque, Joy-Con
                     1 quadro por confirmacao v           | e confirmacoes
+----------------------------------------------+-----------+-----------+
|             NINTENDO SWITCH / SWITCH LITE / OLED (sem modificacao)   |
|   Navegador WebKit oculto (aberto pelo teste de DNS / portal cativo) |
|   pagina servida pelo celular em http://IP-DO-CELULAR:8765           |
|    - Imagem: JPEG -> Image -> <canvas> (proporcao de cada quadro)    |
|    - Tela de toque: varios dedos -> toques reais no Android          |
|    - Gamepad API: Joy-Con lidos a cada quadro (modo Jogo ou Cursor)  |
+----------------------------------------------------------------------+
```

- **Vídeo**: o celular captura a tela, codifica JPEG em dois núcleos e envia pelo WebSocket. O Switch confirma cada quadro desenhado e o celular nunca deixa mais de 2 quadros em trânsito, então não acumula atraso: o Switch sempre recebe o quadro mais novo.
- **Toque**: os dedos na tela do Switch viram toques reais no Android pelo `TouchInjector`, que roda como usuário shell pela depuração sem fio (ADB Wi-Fi) do próprio celular. Aceita vários dedos ao mesmo tempo (andar + girar a câmera + atacar).
- **Configurações** (modo, qualidade, posições dos botões) ficam salvas **no celular**, porque o navegador do Switch esquece o `localStorage`.

## Recursos

- Tela de toque multitoque no modo portátil.
- **Modo Jogo (Wuthering Waves)** com Joy-Con:

  | Joy-Con | Ação |
  |---|---|
  | Analógico esquerdo | Andar |
  | Analógico direito | Câmera (sensibilidade no menu ☰) |
  | A / B / X / Y | Ataque / Pulo / Habilidade / Interagir |
  | L / R | Eco / Liberação |
  | ZL / ZR | Mira / Esquiva |
  | ← ↑ → | Personagem 1 / 2 / 3 |
  | ↓ | Ferramenta |
  | + | Menu (voltar) |

- **Modo Cursor**: o analógico move um cursor, A toca, B volta, direcional envia setas.
- **Ajustar posições**: ☰ › *Ajustar posições dos botões* e arraste cada círculo para cima do botão do jogo.
- **Qualidade**: Baixa 640, Média 924 (padrão), Alta 1136, Máx 1280. Menor = mais FPS no Switch.
- **Tela**: *Ajustar* (mostra tudo) ou *Preencher* (ocupa a tela toda e corta as bordas).
- Medidor de FPS no botão ☰ e, dentro do menu, quanto o celular gera e quanto o Switch desenha.
- A imagem acompanha a rotação do celular.

## Como usar

1. Ative as **Opções do desenvolvedor** no Android.
2. Ligue o celular e o Switch na **mesma rede Wi-Fi**.
3. No app, toque em **Ativar ADB Wi-Fi**. Nos Ajustes, ligue **Depuração sem fio** e escolha **Parear usando código**. Responda à notificação do app com os seis dígitos (ou `PORTA CÓDIGO`, por exemplo `37123 123456`, se o app não achar a porta).
4. Toque em **Iniciar transmissão**. O app mostra um endereço no formato `http://IP-DO-CELULAR:8765`.
5. No Switch, abra o navegador oculto (pelo teste de conexão com um DNS de portal cativo) e acesse esse endereço.
6. Abra o jogo no celular. No Switch, toque em **☰** no topo para escolher o modo e ajustar os botões.

Desligue a **Depuração sem fio** quando terminar.

## Compilar

Abra a pasta no Android Studio e sincronize o Gradle, ou rode:

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/AndroidSwitchStream-v0.2.0-debug.apk`. Para um release assinado, crie um `keystore.properties` na raiz (fora do Git) com `storeFile`, `storePassword`, `keyAlias` e `keyPassword`.

## Portas

| Porta | Uso |
|---|---|
| 8765 | Página e ajustes (HTTP) |
| 8767 | Vídeo e toque (WebSocket) |
| 8766 | Injetor de toque, só dentro do celular (localhost) |

## Segurança e limitações

- Qualquer aparelho na mesma rede que abrir o endereço pode ver a tela e tocar nela enquanto a transmissão estiver ativa. Use só em Wi-Fi de confiança.
- O injetor de toque só aceita conexões de dentro do celular e exige um token aleatório gerado a cada conexão.
- Sem som: o navegador do Switch não reproduz áudio.
- Apps com janela segura ou DRM aparecem pretos.
- O navegador oculto não é oficial; FPS e latência dependem do celular, do Wi-Fi e do Switch. Se ficar lento, baixe a qualidade.

## Créditos

O transporte de vídeo (WebSocket com confirmação por quadro, canvas, dicas do navegador do Switch) foi inspirado no [Switch-PC-Stream](https://github.com/RaZOr519/Switch-PC-Stream).
