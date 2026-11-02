package br.com.streamswitch

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the Switch page, a per-request frame fallback and small control endpoints.
 * The main video path is [StreamSocketServer]; settings live on the phone because the
 * Switch browser does not keep localStorage between visits.
 */
class LocalControlServer(port: Int, private val configFile: File) : NanoHTTPD(port) {
    private val lastFrameServed = ConcurrentHashMap<String, Long>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri.isEmpty()) {
            return htmlResponse(PAGE)
        }
        if (uri == "/frame.jpg") {
            return frameResponse(session.getParms()["c"])
        }
        if (uri == "/touch") {
            val result = RemoteInput.touch(session.getParms()["e"] ?: return badRequest())
            val status = if (result == "off") Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK
            return textResponse(result, status)
        }
        if (uri == "/input") {
            return handleInput(session.getParms())
        }
        if (uri == "/quality") {
            val quality = StreamQuality.fromId(session.getParms()["level"]) ?: return badRequest()
            SharedState.quality = quality
            SharedState.onQualityChanged?.invoke()
            return textResponse(quality.id, Response.Status.OK)
        }
        if (uri == "/config") {
            return handleConfig(session)
        }
        if (uri == "/status") {
            val touch = if (TouchBridge.isReady) "multi" else if (SharedState.adbConnected) "basic" else "off"
            val status = listOf(
                touch,
                SharedState.quality.id,
                SharedState.encodedFps.current(),
                SharedState.deliveredFps.current()
            ).joinToString("|")
            return textResponse(status, Response.Status.OK)
        }
        return textResponse("Not found", Response.Status.NOT_FOUND)
    }

    private fun handleConfig(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            try {
                session.parseBody(body)
            } catch (_: Exception) {
                return badRequest()
            }
            val json = body["postData"]?.trim() ?: return badRequest()
            if (json.length > 16_384 || !json.startsWith("{")) return badRequest()
            synchronized(configFile) { configFile.writeText(json) }
            return textResponse("OK", Response.Status.OK)
        }
        val json = synchronized(configFile) {
            if (configFile.isFile) configFile.readText() else "{}"
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    /** Long-polls so each client only downloads frames it has not seen yet. */
    private fun frameResponse(clientId: String?): Response {
        val client = clientId?.take(32)
        if (client != null) {
            if (lastFrameServed.size > 64) lastFrameServed.clear()
            val seen = lastFrameServed[client] ?: -1L
            synchronized(SharedState.frameLock) {
                val deadline = System.currentTimeMillis() + 1_000L
                while (SharedState.frameSequence <= seen) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    SharedState.frameLock.wait(remaining)
                }
                lastFrameServed[client] = SharedState.frameSequence
            }
        }
        val frame = SharedState.latestJpeg
            ?: return textResponse("Aguardando o primeiro quadro…", Response.Status.SERVICE_UNAVAILABLE)
        SharedState.deliveredFps.tick()
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(frame),
            frame.size.toLong()
        ).apply { addHeader("Cache-Control", "no-store, no-cache, must-revalidate") }
    }

    private fun handleInput(params: Map<String, String>): Response {
        if (!SharedState.adbConnected) {
            return textResponse("Ative os controles Joy-Con por ADB Wi-Fi no Android.", Response.Status.SERVICE_UNAVAILABLE)
        }
        if (params["type"] != "key") return badRequest()
        val code = params["code"]?.toIntOrNull() ?: return badRequest()
        if (code !in RemoteInput.ALLOWED_KEY_CODES) return badRequest()
        return if (RemoteInput.key(code)) {
            textResponse("OK", Response.Status.OK)
        } else {
            textResponse("Comando Joy-Con não enviado.", Response.Status.SERVICE_UNAVAILABLE)
        }
    }

    private fun htmlResponse(body: String): Response {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun textResponse(body: String, status: Response.Status): Response {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
    }

    private fun badRequest(): Response =
        textResponse("Comando inválido.", Response.Status.BAD_REQUEST)

    companion object {
        private const val PAGE = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>Android → Switch</title>
<style>
*{cursor:none !important;-webkit-tap-highlight-color:rgba(0,0,0,0);-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000;color:#fff;font-family:Arial,sans-serif}
#screen{position:absolute;left:0;top:0;width:0;height:0;display:block;touch-action:none;-ms-touch-action:none}
#aim{position:fixed;z-index:3;width:18px;height:18px;margin:-11px 0 0 -11px;border:2px solid #50e4b0;border-radius:50%;box-shadow:0 0 10px #50e4b0;pointer-events:none;display:none}
#menuBtn{position:fixed;z-index:5;left:50%;top:0;margin-left:-34px;width:68px;height:20px;border-radius:0 0 10px 10px;background:rgba(0,0,0,.45);color:#cfe0ff;font-size:13px;text-align:center;line-height:20px}
#hud{position:fixed;z-index:4;left:0;right:0;top:24px;text-align:center;pointer-events:none;display:none}
#hud span{display:inline-block;padding:4px 10px;border-radius:8px;background:rgba(0,0,0,.65);font-size:12px;color:#dbe7ff}
#menu{position:fixed;z-index:6;left:50%;top:22px;margin-left:-185px;width:350px;max-height:90%;overflow-y:auto;display:none;background:rgba(10,14,24,.96);border:1px solid #2a3550;border-radius:12px;padding:10px;font-size:13px}
#menu .row{margin:8px 0}
#menu .title{color:#9fb0d0;font-size:12px;margin-bottom:3px}
button{background:#1d2740;color:#fff;border:1px solid #33456e;border-radius:8px;padding:8px 10px;margin:2px;font-size:13px}
button.on{background:#25694f;border-color:#50e4b0}
#info{color:#9fb0d0;font-size:12px;line-height:1.5}
.mark{position:fixed;z-index:7;width:50px;height:50px;margin:-27px 0 0 -27px;border-radius:50%;border:2px solid #ffcf4a;background:rgba(255,207,74,.28);color:#fff;font-size:10px;font-weight:bold;text-align:center;line-height:50px;display:none;overflow:hidden;white-space:nowrap;text-shadow:0 0 3px #000}
#editBar{position:fixed;z-index:8;left:0;right:0;bottom:6px;text-align:center;display:none}
#editBar span{display:inline-block;padding:6px 10px;border-radius:8px;background:rgba(0,0,0,.75);font-size:12px}
</style>
</head>
<body>
<canvas id="screen" width="0" height="0"></canvas>
<div id="aim"></div>
<div id="menuBtn">☰</div>
<div id="hud"><span id="hudText"></span></div>
<div id="menu">
  <div class="row"><div class="title">Modo do Joy-Con</div>
    <button data-mode="game">Jogo · Wuthering Waves</button><button data-mode="cursor">Cursor</button></div>
  <div class="row"><div class="title">Qualidade (menor = mais FPS no Switch)</div>
    <button data-q="baixa">Baixa 640</button><button data-q="media">Média 924</button><button data-q="alta">Alta 1136</button><button data-q="max">Máx 1280</button></div>
  <div class="row"><div class="title">Tela</div>
    <button data-fit="fit">Ajustar (tela inteira)</button><button data-fit="fill">Preencher (corta bordas)</button></div>
  <div class="row"><div class="title">Transmissão</div>
    <button data-t="ws">WebSocket (recomendado)</button><button data-t="poll">Quadro a quadro</button></div>
  <div class="row"><div class="title">Sensibilidade da câmera (analógico direito)</div>
    <button id="camMinus">−</button> <b id="camVal"></b> <button id="camPlus">+</button></div>
  <div class="row"><button id="editBtn">Ajustar posições dos botões</button><button id="closeBtn">Fechar</button></div>
  <div class="row" id="info"></div>
</div>
<div id="editBar"><span>Arraste cada círculo para cima do botão do jogo</span><br>
  <button id="resetBtn">Restaurar padrão</button><button id="doneBtn">Concluir</button></div>
<script>
(function(){
  function byId(id){return document.getElementById(id);}
  var screen=byId('screen'),ctx=screen.getContext('2d'),aim=byId('aim'),hud=byId('hud'),hudText=byId('hudText');
  var menu=byId('menu'),editBar=byId('editBar');
  var clientId=String(Math.random()).slice(2,12);
  var URLx=window.URL||window.webkitURL;

  // ---------- settings (kept on the phone: the Switch browser forgets localStorage) ----------
  var stored={},saveTimer=0;
  try{
    var cfgReq=new XMLHttpRequest();cfgReq.open('GET','/config?r='+Date.now(),false);cfgReq.send(null);
    if(cfgReq.status===200)stored=JSON.parse(cfgReq.responseText)||{};
  }catch(e){stored={};}
  function load(key,fallback){return stored.hasOwnProperty(key)?stored[key]:fallback;}
  function save(key,value){
    stored[key]=value;clearTimeout(saveTimer);
    saveTimer=setTimeout(function(){
      var r=new XMLHttpRequest();r.open('POST','/config',true);r.setRequestHeader('Content-Type','text/plain');r.send(JSON.stringify(stored));
    },400);
  }

  // The B button would otherwise navigate the browser back and close the stream.
  try{
    history.pushState(null,null,location.href);
    window.onpopstate=function(){history.pushState(null,null,location.href);};
  }catch(e){}

  // Switch browser gamepad indices: 0 B, 1 A, 2 Y, 3 X, 4 L, 5 R, 6 ZL, 7 ZR, 8 −, 9 +, 12-15 D-pad.
  var DEFAULT_LAYOUT={joy:[.16,.70],cam:[.62,.42],attack:[.87,.80],dodge:[.95,.84],jump:[.94,.64],
    skill:[.77,.84],echo:[.76,.66],ult:[.83,.58],lock:[.70,.52],interact:[.66,.62],
    c1:[.95,.30],c2:[.95,.40],c3:[.95,.50],tool:[.84,.42]};
  var LABELS={joy:'Mover',cam:'Câmera',attack:'A Atq',dodge:'ZR Esq',jump:'B Pulo',skill:'X Hab',echo:'L Eco',
    ult:'R Lib',lock:'ZL Mira',interact:'Y Usar',c1:'← P1',c2:'↑ P2',c3:'→ P3',tool:'↓ Ferr'};
  var BUTTONS={1:'attack',0:'jump',3:'skill',2:'interact',4:'echo',5:'ult',6:'lock',7:'dodge',12:'c2',13:'tool',14:'c1',15:'c3'};
  var CURSOR_KEYS={0:4,3:99,2:100,4:102,5:103,12:19,13:20,14:21,15:22,9:66};
  var layout=load('layout',null)||{};
  for(var k in DEFAULT_LAYOUT){if(!layout[k])layout[k]=DEFAULT_LAYOUT[k].slice(0);}
  var mode=load('mode','game'),camSpeed=load('cam',5),fit=load('fit','fit'),transport=load('stream','ws');
  var touchKind='?',quality='?',editing=false;

  // ---------- picture ----------
  // The canvas keeps each frame's own size and is placed by explicit CSS size, so the
  // picture cannot be stretched and the last frame stays visible while the next decodes.
  var rect={x:0,y:0,w:1,h:1},natW=0,natH=0,frames=0,fpsAt=Date.now(),phoneFps=0,switchFps=0;
  function applyLayout(){
    if(!natW||!natH)return;
    var W=window.innerWidth,H=window.innerHeight;
    var s=fit==='fill'?Math.max(W/natW,H/natH):Math.min(W/natW,H/natH);
    rect={w:natW*s,h:natH*s,x:(W-natW*s)/2,y:(H-natH*s)/2};
    screen.style.left=rect.x+'px';screen.style.top=rect.y+'px';
    screen.style.width=rect.w+'px';screen.style.height=rect.h+'px';
    if(editing)placeMarks();
    if(mode==='cursor')placeAim();
  }
  window.addEventListener('resize',applyLayout,false);
  function draw(img){
    var w=img.naturalWidth||img.width,h=img.naturalHeight||img.height;
    if(!w||!h)return;
    if(screen.width!==w||screen.height!==h){screen.width=w;screen.height=h;natW=w;natH=h;applyLayout();}
    try{ctx.drawImage(img,0,0);frames++;}catch(e){}
  }
  function aspect(){return rect.w/Math.max(1,rect.h);}
  function toNorm(cx,cy,clamp){
    var x=(cx-rect.x)/rect.w,y=(cy-rect.y)/rect.h;
    if(!clamp&&(x<0||y<0||x>1||y>1))return null;
    return [Math.max(0,Math.min(1,x)),Math.max(0,Math.min(1,y))];
  }

  // WebSocket: the phone pushes binary JPEGs and this page acknowledges every frame it
  // draws, so the phone only sends as fast as the Switch can decode (no backlog).
  var ws=null,wsFails=0,wsGotFrame=false,pendingFrame=null,rendering=false,consumed=0;
  function sendAck(){if(ws&&ws.readyState===1)ws.send('a '+consumed);}
  function renderNext(){
    var data=pendingFrame;
    if(!data){rendering=false;return;}
    pendingFrame=null;rendering=true;
    var blob;
    try{blob=(data instanceof ArrayBuffer)?new Blob([data],{type:'image/jpeg'}):data.slice(0,data.size,'image/jpeg');}
    catch(e){rendering=false;return;}
    var url=URLx.createObjectURL(blob),img=new Image();
    img.onload=function(){draw(img);URLx.revokeObjectURL(url);consumed++;sendAck();renderNext();};
    img.onerror=function(){URLx.revokeObjectURL(url);consumed++;sendAck();renderNext();};
    img.src=url;
  }
  function startWs(){
    if(!window.WebSocket||!URLx||!window.Blob){setTransport('poll',false);return;}
    var socket;
    try{socket=new WebSocket('ws://'+location.hostname+':8767/');}catch(e){wsFailed();return;}
    ws=socket;socket.binaryType='arraybuffer';consumed=0;pendingFrame=null;
    socket.onopen=function(){flush();};
    socket.onmessage=function(ev){
      if(socket!==ws||typeof ev.data==='string')return;
      wsGotFrame=true;wsFails=0;
      if(pendingFrame){consumed++;sendAck();}
      pendingFrame=ev.data;
      if(!rendering)renderNext();
    };
    socket.onclose=function(){if(socket!==ws)return;ws=null;pendingFrame=null;if(transport==='ws')wsFailed();};
  }
  function wsFailed(){
    wsFails++;
    if(!wsGotFrame&&wsFails>=3){setTransport('poll',false);say('WebSocket indisponível · usando quadro a quadro',4000);return;}
    setTimeout(function(){if(transport==='ws'&&!ws)startWs();},1500);
  }
  function closeWs(){if(ws){var socket=ws;ws=null;try{socket.close();}catch(e){}}}

  // Quadro a quadro: one long-polled request per frame.
  var loader=new Image(),frameN=0;
  function loadFrame(){frameN++;loader.src='/frame.jpg?c='+clientId+'&n='+frameN;}
  loader.onload=function(){if(transport!=='poll')return;draw(loader);loadFrame();};
  loader.onerror=function(){if(transport==='poll')setTimeout(loadFrame,500);};

  function setTransport(next,remember){
    transport=next;if(remember)save('stream',next);
    closeWs();
    if(next==='ws'){wsFails=0;startWs();}else loadFrame();
    refreshMenu();
  }

  // ---------- HUD ----------
  var hudTimer=0;
  function say(text,ms){
    hudText.innerHTML=text;hud.style.display='block';
    clearTimeout(hudTimer);
    // Hidden with display:none: Switch WebKit repaints anything over the video every frame.
    hudTimer=setTimeout(function(){hud.style.display='none';},ms||2500);
  }
  function modeName(){return mode==='game'?'Jogo (Wuthering Waves)':'Cursor';}
  function showStats(){
    byId('menuBtn').innerHTML='☰ '+switchFps;
    if(menu.style.display!=='block')return;
    byId('info').innerHTML='Imagem: '+natW+'×'+natH+' · '+(transport==='ws'?'WebSocket':'quadro a quadro')+
      '<br>Telefone gera: '+phoneFps+' fps · Switch desenha: '+switchFps+' fps<br>'+
      (phoneFps<20?'Gargalo no telefone: baixe a qualidade ou os gráficos do jogo.<br>':
       switchFps<phoneFps*0.7?'Gargalo no Switch/Wi-Fi: baixe a qualidade.<br>':'')+
      'Toque: '+(touchKind==='multi'?'multitoque ativo':touchKind==='basic'?'básico (só toques/deslizes)':'ative os Joy-Cons no Android');
  }
  function fetchStatus(){
    var r=new XMLHttpRequest();r.open('GET','/status?r='+Date.now(),true);
    r.onreadystatechange=function(){
      if(r.readyState!==4||r.status!==200)return;
      var p=r.responseText.split('|');
      touchKind=p[0];quality=p[1];phoneFps=parseInt(p[2],10)||0;
      refreshMenu();showStats();
    };
    r.send(null);
  }
  setInterval(function(){
    var now=Date.now();switchFps=Math.round(frames*1000/(now-fpsAt));frames=0;fpsAt=now;
    fetchStatus();
  },1000);

  // ---------- sending touches ----------
  var pending=[],inflight=0,inflightAt=0,requestId=0,flushTimer=0;
  function queue(type,id,x,y){
    if(type==='m'){
      for(var i=pending.length-1;i>=0;i--){
        if(pending[i].id===id){if(pending[i].t==='m'){pending[i].x=x;pending[i].y=y;scheduleFlush();return;}break;}
      }
    }
    pending.push({t:type,id:id,x:x,y:y});scheduleFlush();
  }
  function scheduleFlush(){if(!flushTimer)flushTimer=setTimeout(function(){flushTimer=0;flush();},8);}
  function n4(v){return Math.round(Math.max(0,Math.min(1,v))*10000);}
  function encode(list){
    var parts=[];
    for(var i=0;i<list.length;i++){
      var e=list[i];
      parts.push(e.t==='u'?'u.'+e.id:e.t+'.'+e.id+'.'+n4(e.x)+'.'+n4(e.y));
    }
    return parts.join('_');
  }
  function flush(){
    if(!pending.length)return;
    if(ws&&ws.readyState===1){ws.send('t '+encode(pending));pending=[];return;}
    if(inflight&&Date.now()-inflightAt<1500)return;
    var body=encode(pending);pending=[];
    var my=++requestId;inflight=my;inflightAt=Date.now();
    var r=new XMLHttpRequest();
    r.open('GET','/touch?e='+body+'&r='+my,true);
    r.onreadystatechange=function(){
      if(r.readyState!==4)return;
      if(inflight===my)inflight=0;
      flush();
    };
    r.send(null);
  }
  function sendKey(code){
    if(ws&&ws.readyState===1){ws.send('k '+code);return;}
    var r=new XMLHttpRequest();r.open('GET','/input?type=key&code='+code+'&r='+Date.now(),true);r.send(null);
  }
  var used={};
  function allocId(){for(var i=0;i<9;i++){if(!used[i]){used[i]=true;return i;}}return -1;}
  function freeId(i){delete used[i];}

  // ---------- touch screen ----------
  var touchIds={},lastTouchAt=0;
  function onTouch(ev){
    ev.preventDefault();lastTouchAt=Date.now();
    if(editing)return;
    var list=ev.changedTouches;
    for(var i=0;i<list.length;i++){
      var t=list[i],key='t'+t.identifier,pid=touchIds[key],n;
      if(ev.type==='touchstart'){
        n=toNorm(t.clientX,t.clientY,false);
        if(!n||pid!==undefined)continue;
        pid=allocId();if(pid<0)continue;
        touchIds[key]=pid;queue('d',pid,n[0],n[1]);
      }else if(ev.type==='touchmove'){
        if(pid===undefined)continue;
        n=toNorm(t.clientX,t.clientY,true);queue('m',pid,n[0],n[1]);
      }else{
        if(pid===undefined)continue;
        delete touchIds[key];queue('u',pid);freeId(pid);
      }
    }
  }
  screen.addEventListener('touchstart',onTouch,false);
  screen.addEventListener('touchmove',onTouch,false);
  screen.addEventListener('touchend',onTouch,false);
  screen.addEventListener('touchcancel',onTouch,false);
  var mousePid=-1;
  screen.addEventListener('mousedown',function(ev){
    if(editing||Date.now()-lastTouchAt<1000)return;
    var n=toNorm(ev.clientX,ev.clientY,false);if(!n)return;
    mousePid=allocId();if(mousePid<0)return;queue('d',mousePid,n[0],n[1]);ev.preventDefault();
  },false);
  screen.addEventListener('mousemove',function(ev){
    if(mousePid<0)return;var n=toNorm(ev.clientX,ev.clientY,true);queue('m',mousePid,n[0],n[1]);
  },false);
  function mouseUp(){if(mousePid<0)return;queue('u',mousePid);freeId(mousePid);mousePid=-1;}
  screen.addEventListener('mouseup',mouseUp,false);
  screen.addEventListener('mouseleave',mouseUp,false);
  screen.addEventListener('contextmenu',function(ev){ev.preventDefault();},false);

  // ---------- Joy-Con: game mode ----------
  var held={},camOff=[0,0];
  function gDown(name,x,y){if(held[name]!==undefined)return;var pid=allocId();if(pid<0)return;held[name]=pid;queue('d',pid,x,y);}
  function gMove(name,x,y){if(held[name]!==undefined)queue('m',held[name],x,y);}
  function gUp(name){var pid=held[name];if(pid===undefined)return;delete held[name];queue('u',pid);freeId(pid);}
  function releaseGame(){for(var k in held)gUp(k);}
  function gameTick(lx,ly,rx,ry,btn,prev,dt){
    var asp=aspect(),c;
    if(Math.sqrt(lx*lx+ly*ly)>.18){
      c=layout.joy;
      if(held.joy===undefined)gDown('joy',c[0],c[1]);
      gMove('joy',c[0]+lx*.085,c[1]+ly*.085*asp);
    }else gUp('joy');
    if(Math.sqrt(rx*rx+ry*ry)>.15){
      c=layout.cam;
      if(held.cam===undefined){camOff=[0,0];gDown('cam',c[0],c[1]);}
      var speed=dt*.00018*camSpeed;
      camOff[0]+=rx*speed;camOff[1]+=ry*speed*asp;
      if(Math.abs(camOff[0])>.2||Math.abs(camOff[1])>.25){
        // Lift and press again so the camera keeps turning.
        gUp('cam');camOff=[0,0];gDown('cam',c[0],c[1]);
      }else gMove('cam',c[0]+camOff[0],c[1]+camOff[1]);
    }else gUp('cam');
    for(var b in BUTTONS){
      var name=BUTTONS[b],p=layout[name];
      if(btn[b]&&!prev[b])gDown(name,p[0],p[1]);
      else if(!btn[b]&&prev[b])gUp(name);
    }
    if(btn[9]&&!prev[9])sendKey(4);
  }

  // ---------- Joy-Con: cursor mode ----------
  var cursor=[.5,.5];
  function placeAim(){
    aim.style.left=(rect.x+cursor[0]*rect.w)+'px';aim.style.top=(rect.y+cursor[1]*rect.h)+'px';
  }
  function cursorTick(lx,ly,btn,prev,dt){
    if(Math.abs(lx)>.16||Math.abs(ly)>.16){
      cursor[0]=Math.max(0,Math.min(1,cursor[0]+lx*dt*.001));
      cursor[1]=Math.max(0,Math.min(1,cursor[1]+ly*dt*.001*aspect()));
      aim.style.display='block';placeAim();
    }
    if(btn[1]&&!prev[1])gDown('tap',cursor[0],cursor[1]);
    else if(!btn[1]&&prev[1])gUp('tap');
    if(held.tap!==undefined)gMove('tap',cursor[0],cursor[1]);
    for(var b in CURSOR_KEYS){if(btn[b]&&!prev[b])sendKey(CURSOR_KEYS[b]);}
  }

  function setMode(next){
    releaseGame();mode=next;save('mode',mode);
    aim.style.display='none';refreshMenu();
    say('Modo: '+modeName()+(mode==='game'?' · analógicos movem e giram a câmera':' · A toca · B volta'),3500);
  }

  var prevBtn={},lastTime=Date.now(),seenPad=false;
  var raf=window.requestAnimationFrame||window.webkitRequestAnimationFrame||function(f){return setTimeout(f,16);};
  function pollPads(){
    var now=Date.now(),dt=Math.min(50,now-lastTime);lastTime=now;
    var pads=navigator.getGamepads?navigator.getGamepads():[];
    var lx=0,ly=0,rx=0,ry=0,btn={},found=false;
    for(var p=0;p<pads.length;p++){
      var pad=pads[p];if(!pad)continue;found=true;
      var a=pad.axes||[];
      if(a.length>=2&&Math.abs(a[0])+Math.abs(a[1])>Math.abs(lx)+Math.abs(ly)){lx=a[0];ly=a[1];}
      if(a.length>=4&&Math.abs(a[2])+Math.abs(a[3])>Math.abs(rx)+Math.abs(ry)){rx=a[2];ry=a[3];}
      var bs=pad.buttons||[];
      for(var i=0;i<bs.length;i++){if(bs[i]&&bs[i].pressed)btn[i]=true;}
    }
    if(found){
      if(!seenPad){seenPad=true;say('Joy-Con conectado · Modo: '+modeName()+' · ☰ no topo troca o modo',4000);}
      if(!editing){
        if(mode==='game')gameTick(lx,ly,rx,ry,btn,prevBtn,dt);
        else cursorTick(lx,ly,btn,prevBtn,dt);
      }
    }
    prevBtn=btn;
    raf(pollPads);
  }
  raf(pollPads);

  // ---------- menu ----------
  function refreshMenu(){
    var bs=menu.getElementsByTagName('button');
    for(var i=0;i<bs.length;i++){
      var m=bs[i].getAttribute('data-mode'),q=bs[i].getAttribute('data-q'),t=bs[i].getAttribute('data-t'),f=bs[i].getAttribute('data-fit');
      bs[i].className=(m&&m===mode)||(q&&q===quality)||(t&&t===transport)||(f&&f===fit)?'on':'';
    }
    byId('camVal').innerHTML=camSpeed;
  }
  byId('menuBtn').onclick=function(){
    var open=menu.style.display==='block';
    menu.style.display=open?'none':'block';
    if(!open){refreshMenu();showStats();}
  };
  byId('closeBtn').onclick=function(){menu.style.display='none';};
  var menuButtons=menu.getElementsByTagName('button');
  for(var i=0;i<menuButtons.length;i++){
    menuButtons[i].addEventListener('click',function(){
      var m=this.getAttribute('data-mode'),q=this.getAttribute('data-q'),t=this.getAttribute('data-t'),f=this.getAttribute('data-fit');
      if(m)setMode(m);
      if(t)setTransport(t,true);
      if(f){fit=f;save('fit',f);applyLayout();refreshMenu();}
      if(q){
        quality=q;refreshMenu();
        var r=new XMLHttpRequest();r.open('GET','/quality?level='+q+'&r='+Date.now(),true);r.send(null);
        say('Qualidade: '+this.innerHTML,2000);
      }
    },false);
  }
  byId('camMinus').onclick=function(){camSpeed=Math.max(1,camSpeed-1);save('cam',camSpeed);refreshMenu();};
  byId('camPlus').onclick=function(){camSpeed=Math.min(15,camSpeed+1);save('cam',camSpeed);refreshMenu();};

  // ---------- layout editor ----------
  var marks={},dragging=null;
  for(var name in LABELS){
    var el=document.createElement('div');el.className='mark';el.innerHTML=LABELS[name];
    el.setAttribute('data-name',name);document.body.appendChild(el);marks[name]=el;
    el.addEventListener('touchstart',startDrag,false);
    el.addEventListener('mousedown',startDrag,false);
  }
  function placeMarks(){
    for(var n in marks){
      marks[n].style.left=(rect.x+layout[n][0]*rect.w)+'px';
      marks[n].style.top=(rect.y+layout[n][1]*rect.h)+'px';
    }
  }
  function startDrag(ev){ev.preventDefault();dragging=this.getAttribute('data-name');}
  function moveDrag(ev){
    if(!dragging)return;ev.preventDefault();
    var t=ev.touches?ev.touches[0]:ev;if(!t)return;
    layout[dragging]=toNorm(t.clientX,t.clientY,true);placeMarks();
  }
  function endDrag(){if(dragging){dragging=null;save('layout',layout);}}
  document.addEventListener('touchmove',moveDrag,false);
  document.addEventListener('mousemove',moveDrag,false);
  document.addEventListener('touchend',endDrag,false);
  document.addEventListener('mouseup',endDrag,false);
  function setEditing(on){
    editing=on;releaseGame();
    for(var n in marks)marks[n].style.display=on?'block':'none';
    editBar.style.display=on?'block':'none';
    menu.style.display='none';
    if(on)placeMarks();
  }
  byId('editBtn').onclick=function(){setEditing(true);};
  byId('doneBtn').onclick=function(){setEditing(false);save('layout',layout);say('Posições salvas no telefone',2000);};
  byId('resetBtn').onclick=function(){
    for(var k in DEFAULT_LAYOUT)layout[k]=DEFAULT_LAYOUT[k].slice(0);save('layout',layout);placeMarks();
  };

  window.addEventListener('gamepadconnected',function(){seenPad=false;},false);
  setTransport(transport,false);
  say('Toque na tela para jogar · ☰ no topo abre o menu',4000);
})();
</script>
</body>
</html>
"""
    }
}
