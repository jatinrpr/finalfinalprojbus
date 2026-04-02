package com.example.finalfinalproj;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Handles incoming FCM push notifications.
 *
 * 📋 SETUP STEPS:
 * 1. Add to AndroidManifest.xml inside <application>:
 *
 *    <service
 *        android:name=".MyFirebaseMessagingService"
 *        android:exported="false">
 *        <intent-filter>
 *            <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *        </intent-filter>
 *    </service>
 *
 * 2. Add to app/build.gradle dependencies:
 *    implementation 'com.google.firebase:firebase-messaging:23.4.0'
 *
 * 3. Add to project/build.gradle plugins:
 *    id 'com.google.gms.google-services' version '4.4.0' apply false
 *
 * 4. Add to app/build.gradle plugins:
 *    id 'com.google.gms.google-services'
 *
 * 5. Download google-services.json from Firebase Console
 *    and place it in the app/ folder.
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID   = "bus_alerts";
    private static final String CHANNEL_NAME = "Bus Alerts";

    // ── Called when a push notification arrives ──────────────────────
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        if (remoteMessage.getNotification() == null) return;

        String title = remoteMessage.getNotification().getTitle();
        String body  = remoteMessage.getNotification().getBody();

        showNotification(title, body);
    }

    // ── Called when FCM assigns/refreshes token ───────────────────────
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        // Send new token to our server so we can target this device
        sendTokenToServer(token);
    }

    // ── Show notification in system tray ──────────────────────────────
    private void showNotification(String title, String body) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Create channel (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("IIT Ropar Bus arrival alerts");
            manager.createNotificationChannel(channel);
        }

        // Tap notification → open HomeActivity
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bus_notification)   // add a bus icon to drawable
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    // ── Send FCM token to backend ──────────────────────────────────────
    private void sendTokenToServer(String fcmToken) {
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("fcmToken", fcmToken);
                ApiClient.postSilent(this, "/updateFcmToken", body);
            } catch (Exception ignored) {}
        }).start();
    }
}
