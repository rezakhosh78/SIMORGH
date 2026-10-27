package com.rkh.vpn.data
object FormatUtils{ fun bytes(v:Long):String{ val u=listOf("B","KB","MB","GB","TB"); var n=v.toDouble(); var i=0; while(n>=1024 && i<u.lastIndex){n/=1024;i++}; return if(i==0) "${v}B" else "%.2f %s".format(n,u[i])}; fun kbps(v:Long)= if(v>=1024) "%.1f MB/s".format(v/1024.0) else "$v KB/s" }
