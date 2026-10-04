package com.example.aiscalper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val NIFTY_TOKEN = "99926000"
private const val INSTRUMENT_URL = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json"
private const val LOGIN_URL = "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword"
private const val WS_URL = "wss://smartapisocket.angelone.in/smart-stream"

private data class Instrument(val token:String, val symbol:String, val expiry:String, val strike:Double, val lot:Int, val type:String, val segment:String)
private data class OptionPair(val expiry:String, val strike:Double, val ce:Instrument, val pe:Instrument)
data class PaperTrade(
    val symbol:String,
    val direction:String,
    val entry:Double,
    val sl:Double,
    val target:Double,
    val time:String
)

class AngelEngine(private val scope:CoroutineScope) {
    private val client=OkHttpClient.Builder().build()
    private var ws:WebSocket?=null
    private var apiKey=""; private var clientCode=""; private var jwt=""; private var feedToken=""
    private var spotToken=NIFTY_TOKEN; private var ceToken=""; private var peToken=""

    var status by mutableStateOf("Disconnected")
    var connected by mutableStateOf(false)
    var instrumentsReady by mutableStateOf(false)
    var spot by mutableDoubleStateOf(0.0)
    var ceLtp by mutableDoubleStateOf(0.0)
    var peLtp by mutableDoubleStateOf(0.0)
    var ceSymbol by mutableStateOf("")
    var peSymbol by mutableStateOf("")
    var expiry by mutableStateOf("")
    var strike by mutableDoubleStateOf(0.0)
    var lotSize by mutableIntStateOf(0)
    var openingHigh by mutableDoubleStateOf(0.0)
    var openingLow by mutableDoubleStateOf(Double.MAX_VALUE)
    var openingReady by mutableStateOf(false)
    var signal by mutableStateOf("WAITING")
    var confidence by mutableIntStateOf(0)
    var paperTrade by mutableStateOf<PaperTrade?>(null)
    var paperPnl by mutableDoubleStateOf(0.0)
    var tradesToday by mutableIntStateOf(0)
    val journal=mutableStateListOf<String>()

    fun login(key:String, code:String, pin:String, totp:String){
        apiKey=key.trim(); clientCode=code.trim(); status="Authenticating…"
        scope.launch(Dispatchers.IO){
            try{
                val body=JSONObject().put("clientcode",clientCode).put("password",pin).put("totp",totp)
                    .toString().toRequestBody("application/json".toMediaType())
                val req=Request.Builder().url(LOGIN_URL).post(body)
                    .addHeader("Content-Type","application/json")
                    .addHeader("X-PrivateKey",apiKey).build()
                client.newCall(req).execute().use { r ->
                    val text=r.body?.string() ?: ""
                    val j=JSONObject(text)
                    if(!r.isSuccessful || !j.optBoolean("status",false)){
                        status="Login failed: "+j.optString("message","HTTP "+r.code); return@use
                    }
                    val d=j.getJSONObject("data"); jwt=d.optString("jwtToken"); feedToken=d.optString("feedToken")
                    status="Login OK — loading instruments…"
                    loadInstruments()
                }
            }catch(e:Exception){ status="Login error: "+(e.message ?: "unknown") }
        }
    }

    private fun loadInstruments(){
        scope.launch(Dispatchers.IO){
            try{
                val req=Request.Builder().url(INSTRUMENT_URL).get().build()
                client.newCall(req).execute().use { r ->
                    if(!r.isSuccessful) throw IllegalStateException("Instrument master HTTP ${r.code}")
                    val reader=JsonReader(InputStreamReader(r.body!!.byteStream()))
                    val now=Date(); val expiryFmt=SimpleDateFormat("ddMMMMyyyy",Locale.ENGLISH)
                    val candidates=mutableListOf<Instrument>()
                    reader.beginArray()
                    while(reader.hasNext()){
                        reader.beginObject()
                        var token="";var symbol="";var exp="";var strike=-1.0;var lot=0;var type="";var seg=""
                        while(reader.hasNext()){
                            when(reader.nextName()){
                                "token"->token=reader.nextString()
                                "symbol"->symbol=reader.nextString()
                                "expiry"->exp=reader.nextString()
                                "strike"->strike=reader.nextString().toDoubleOrNull() ?: -1.0
                                "lotsize"->lot=reader.nextString().toDoubleOrNull()?.toInt() ?: 0
                                "instrumenttype"->type=reader.nextString()
                                "exch_seg"->seg=reader.nextString()
                                else->reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if(seg=="NFO" && type=="OPTIDX" && symbol.startsWith("NIFTY") && exp.isNotBlank() && strike>0){
                            try{
                                val d=expiryFmt.parse(exp)
                                if(d!=null && !d.before(now)) candidates.add(Instrument(token,symbol,exp,strike/100.0,lot,type,seg))
                            }catch(_:Exception){}
                        }
                    }
                    reader.endArray(); reader.close()
                    val pairs=candidates.groupBy{it.expiry to it.strike}
                    val spotNow=spot
                    val nearest=pairs.keys.sortedWith(compareBy<Pair<String,Double>>{ expiryFmt.parse(it.first) }.thenBy{abs(it.second-spotNow) }).firstOrNull()
                    if(nearest==null) throw IllegalStateException("No future NIFTY option contracts found")
                    val group=pairs[nearest] ?: throw IllegalStateException("No option pair")
                    val ce=group.firstOrNull{it.symbol.endsWith("CE")}; val pe=group.firstOrNull{it.symbol.endsWith("PE")}
                    if(ce==null || pe==null) throw IllegalStateException("Could not find CE/PE pair")
                    ceToken=ce.token; peToken=pe.token; expiry=ce.expiry; strike=ce.strike; lotSize=ce.lot
                    ceSymbol=ce.symbol; peSymbol=pe.symbol; instrumentsReady=true
                    status="Instrument ready — connecting feed"
                    connect()
                }
            }catch(e:Exception){status="Instrument error: "+(e.message ?: "unknown")}
        }
    }

    private fun connect(){
        val req=Request.Builder().url(WS_URL)
            .addHeader("Authorization",jwt).addHeader("x-api-key",apiKey)
            .addHeader("x-client-code",clientCode).addHeader("x-feed-token",feedToken).build()
        ws=client.newWebSocket(req,object:WebSocketListener(){
            override fun onOpen(w:WebSocket,r:Response){
                connected=true; status="LIVE FEED CONNECTED"
                val tokens=JSONArray().put(NIFTY_TOKEN).put(ceToken).put(peToken)
                val list=JSONArray().put(JSONObject().put("exchangeType",1).put("tokens",JSONArray().put(NIFTY_TOKEN)))
                // NFO uses exchangeType 2; NSE index uses 1.
                list.put(JSONObject().put("exchangeType",2).put("tokens",JSONArray().put(ceToken).put(peToken)))
                w.send(JSONObject().put("correlationID","scalper").put("action",1).put("params",JSONObject().put("mode",1).put("tokenList",list)).toString())
            }
            override fun onMessage(w:WebSocket,b:okio.ByteString){
                try{
                    val a=b.toByteArray(); if(a.size<47)return
                    val token=String(a.copyOfRange(2,27)).trimEnd('\u0000')
                    val ltp=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getInt(43)/100.0
                    when(token){NIFTY_TOKEN->spot=ltp;ceToken->ceLtp=ltp;peToken->peLtp=ltp}
                }catch(_:Exception){}
            }
            override fun onFailure(w:WebSocket,t:Throwable,r:Response?){connected=false;status="Feed error: "+(t.message ?: "unknown")}
            override fun onClosed(w:WebSocket,c:Int,s:String){connected=false;status="Feed closed"}
        })
    }

    fun startMarketClock(){
        scope.launch{
            while(isActive){
                val c=Calendar.getInstance(); val h=c.get(Calendar.HOUR_OF_DAY); val m=c.get(Calendar.MINUTE)
                if(h==9 && m in 15..29 && spot>0){openingHigh=max(openingHigh,spot);openingLow=min(openingLow,spot)}
                if(h==9 && m>=30 && openingHigh>0 && !openingReady){
                    openingReady=true
                    signal=when{spot>openingHigh->"LONG CALL";spot<openingLow->"LONG PUT";else->"NO TRADE"}
                    confidence=if(signal=="NO TRADE")50 else 65
                }
                manageTrade()
                delay(1000)
            }
        }
    }

    private fun manageTrade(){
        val t=paperTrade ?: return
        val price=if(t.direction=="LONG CALL")ceLtp else peLtp
        if(price<=0)return
        if(price<=t.sl){closeTrade(price,"STOP LOSS")} else if(price>=t.target){closeTrade(price,"TARGET")}
    }

    fun enterPaper(){
        if(tradesToday>=1 || !openingReady || signal=="NO TRADE" || paperTrade!=null)return
        val price=if(signal=="LONG CALL")ceLtp else peLtp
        val symbol=if(signal=="LONG CALL")ceSymbol else peSymbol
        if(price<=0)return
        val sl=price*0.82; val target=price*1.30
        paperTrade=PaperTrade(symbol,signal,price,sl,target,SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date()))
        tradesToday=1; journal.add("ENTRY $symbol @ %.2f".format(price))
    }

    private fun closeTrade(price:Double,reason:String){
        val t=paperTrade ?: return
        val pnl=(price-t.entry)*lotSize
        paperPnl+=pnl
        journal.add("EXIT ${t.symbol} @ %.2f | $reason | P&L ₹%.2f".format(price,pnl))
        paperTrade=null
    }

    fun disconnect(){ws?.close(1000,"user");ws=null;connected=false;status="Disconnected"}
}

@Composable fun ScalperDashboard(){
    val scope=rememberCoroutineScope(); val e=remember{AngelEngine(scope)}
    var api by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var pin by remember{mutableStateOf("")};var totp by remember{mutableStateOf("")}
    LaunchedEffect(Unit){e.startMarketClock()}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("AI F&O SCALPER",style=MaterialTheme.typography.headlineMedium)
        Text("V2.1 • LIVE DATA / PAPER TRADING")
        HorizontalDivider()
        Text("Angel One SmartAPI")
        OutlinedTextField(api,{api=it},label={Text("API Key")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(code,{code=it},label={Text("Client Code")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(pin,{pin=it},label={Text("MPIN / PIN")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        OutlinedTextField(totp,{totp=it},label={Text("Current TOTP (6 digits)")},modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button({e.login(api,code,pin,totp)}){Text("CONNECT LIVE")}
            OutlinedButton({e.disconnect()}){Text("DISCONNECT")}
        }
        Text("Status: ${e.status}")
        Text("NIFTY: ${if(e.spot>0)"%.2f".format(e.spot) else "--"}")
        if(e.instrumentsReady){
            Text("Expiry: ${e.expiry}  ATM: ${"%.0f".format(e.strike)}  Lot: ${e.lotSize}")
            Text("CE: ${e.ceSymbol}  ₹${if(e.ceLtp>0)"%.2f".format(e.ceLtp) else "--"}")
            Text("PE: ${e.peSymbol}  ₹${if(e.peLtp>0)"%.2f".format(e.peLtp) else "--"}")
        }
        Text("Opening range: ${if(e.openingHigh>0)"%.2f".format(e.openingLow) else "--"} – ${if(e.openingHigh>0)"%.2f".format(e.openingHigh) else "--"}")
        Text("Signal: ${e.signal}   Confidence: ${e.confidence}%")
        Text("Paper trades today: ${e.tradesToday} / 1")
        Text("Paper P&L: ₹${"%.2f".format(e.paperPnl)}")
        if(e.openingReady && e.signal!="NO TRADE" && e.paperTrade==null && e.tradesToday==0){Button({e.enterPaper()}){Text("ENTER PAPER TRADE")}}
        e.paperTrade?.let{t->Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("PAPER TRADE ACTIVE",style=MaterialTheme.typography.titleMedium);Text(t.symbol);Text("${t.direction}  Entry ₹${"%.2f".format(t.entry)}");Text("SL ₹${"%.2f".format(t.sl)}  Target ₹${"%.2f".format(t.target)}");Text("Real market price • NO broker order")
        }}}
        Text("Journal",style=MaterialTheme.typography.titleMedium)
        LazyColumn{items(e.journal){Text(it)}}
        Text("LIVE ORDERS: LOCKED",style=MaterialTheme.typography.titleMedium)
    }
}

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{MaterialTheme{ScalperDashboard()}}}}
