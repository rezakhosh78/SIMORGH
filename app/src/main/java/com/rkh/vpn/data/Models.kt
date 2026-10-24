package com.rkh.vpn.data

data class ServerConfig(val id:String, val name:String, val raw:String, val host:String?=null, val port:Int?=null, val pingMs:Long?=null, val error:String?=null)
data class UsageInfo(val usedBytes:Long=0, val totalBytes:Long=0){ val remainingBytes:Long get()=(totalBytes-usedBytes).coerceAtLeast(0); val usedPercent:Float get()= if(totalBytes<=0) 0f else (usedBytes.toFloat()/totalBytes).coerceIn(0f,1f) }
data class SpeedSample(val label:String,val downKbps:Long,val upKbps:Long)
data class AppState(
 val primaryToken:String="", val premiumToken:String="", val selectedBaseUrl:String=SubscriptionRepository.PRIMARY_BASE,
 val usage:UsageInfo=UsageInfo(), val servers:List<ServerConfig> = emptyList(), val selectedServerId:String?=null,
 val connected:Boolean=false, val smartConnect:Boolean=true, val autoUpdate:Boolean=true, val darkTheme:Boolean=true, val monet:Boolean=true,
 val downloadKbps:Long=0, val uploadKbps:Long=0, val traffic:List<SpeedSample> = emptyList(), val status:String="Ready",
 val logs:List<String> = emptyList()
)
