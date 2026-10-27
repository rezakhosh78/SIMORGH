package com.rkh.callisto.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.rkh.vpn.MainActivity;
import com.rkh.vpn.R;

final class ServiceSupport {
    static final String CHANNEL_ID = "callisto_connection";
    static final int NOTIFICATION_ID = 4107;

    private ServiceSupport() {}

    static void cancelNotification(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    static Notification notification(Context context, String detail,
                                     Class<?> serviceClass, String stopAction) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SIMORGH connection",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Connection status and active tunnel controls");
            manager.createNotificationChannel(channel);
        }

        Intent launch = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launch, flags);

        Intent stop = new Intent(context, serviceClass).setAction(stopAction);
        PendingIntent stopIntent = PendingIntent.getService(context, 1, stop, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        return builder
                .setSmallIcon(R.drawable.ic_simorgh_notification)
                .setColor(0xFFFFFFFF)
                .setContentTitle("SIMORGH")
                .setContentText(detail)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .addAction(0, "Disconnect", stopIntent)
                .build();
    }
}
