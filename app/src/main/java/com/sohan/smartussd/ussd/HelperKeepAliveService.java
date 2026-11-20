package com.sohan.smartussd.ussd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.sohan.smartussd.MainActivity;
import com.sohan.smartussd.R;

/**
 * Lightweight foreground service that keeps the app process alive so
 * Xiaomi / MIUI / HyperOS cannot kill the accessibility service when
 * the user swipes the app away from recent apps.
 *
 * Started when the accessibility service connects, stopped when it unbinds.
 */
public class HelperKeepAliveService extends Service {

    public static final String KEY_NOTIFICATION_ENABLED = "notification_enabled";

    private static final String CHANNEL_ID = "smart_ussd_helper";
    private static final int NOTIFICATION_ID = 9001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Intent tap = new Intent(this, MainActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Smart USSD helper is active")
                .setContentText("Tap to open Smart USSD")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void createChannel() {

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "USSD Helper",
                NotificationManager.IMPORTANCE_LOW);

        channel.setDescription("Keeps the USSD helper alive so it works every time");
        channel.setShowBadge(false);

        NotificationManager nm = getSystemService(NotificationManager.class);

        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    /* ---- Static helpers ---- */

    public static boolean isNotificationEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences("smart_ussd", Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_ENABLED, true);
    }

    public static void setNotificationEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences("smart_ussd", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
                .apply();
        if (!enabled) {
            stop(context);
        } else {
            start(context);
        }
    }

    public static void start(Context context) {
        if (context == null) return;
        if (!isNotificationEnabled(context)) {
            return;
        }
        try {
            Intent intent = new Intent(context, HelperKeepAliveService.class);
            context.startForegroundService(intent);
        } catch (Exception ignored) {
            // SecurityException on some ROMs; the service is not critical
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, HelperKeepAliveService.class));
        } catch (Exception ignored) {
        }
    }
}

