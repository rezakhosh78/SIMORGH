package com.rkh.vpn.widget
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rkh.vpn.MainActivity
import com.rkh.vpn.R
class RkhVpnWidgetProvider: AppWidgetProvider(){ override fun onUpdate(c:Context,m:AppWidgetManager,ids:IntArray){ ids.forEach{ val rv=RemoteViews(c.packageName,R.layout.rkh_vpn_widget); val pi=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE); rv.setOnClickPendingIntent(R.id.widget_status,pi); m.updateAppWidget(it,rv) } } }
