package se.bkorkortsteori.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging service for Bkörkortsteori.
 *
 * Handles both:
 * - Data messages (processed here in both foreground and background)
 * - Notification messages (shown by system in background, handled here in foreground)
 *
 * Notification payload keys:
 *   - title: Notification title
 *   - body: Notification body text
 *   - url: (optional) URL to navigate to when tapped
 *   - image: (optional) URL of image to show (not implemented, can be added)
 *
 * Data payload keys:
 *   - title: Notification title
 *   - body: Notification body text
 *   - target_url: URL to open when notification is tapped
 *   - type: Notification type (e.g., "theory_update", "reminder", "announcement")
 */
class BKFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "BKorkortsteoriFCM"
        private const val CHANNEL_ID = "bkorkortsteori_channel"
        private const val CHANNEL_NAME = "Körkortsteori"
        private const val CHANNEL_DESCRIPTION = "Notiser om körkortsövningar och uppdateringar"
        private const val DEFAULT_URL = "https://bkorkortsteori.se"
    }

    /**
     * Called when a new FCM token is generated.
     * This can happen on:
     * - App first install
     * - App data cleared
     * - App restored on new device
     * - Token refresh by Firebase
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")

        // TODO: Send this token to your backend server for targeted notifications
        // Example: sendTokenToServer(token)
        sendTokenToServer(token)
    }

    /**
     * Called when a message is received from FCM.
     * This is called for both data messages and notification messages
     * when the app is in the foreground.
     *
     * For background notification messages, the system generates the notification
     * automatically using the notification payload.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received from: ${message.from}")

        // Extract data from either notification payload or data payload
        val title: String
        val body: String
        val targetUrl: String

        if (message.data.isNotEmpty()) {
            // Data message (works in both foreground and background)
            Log.d(TAG, "Data payload: ${message.data}")
            title = message.data["title"] ?: getString(R.string.app_name)
            body = message.data["body"] ?: message.data["message"] ?: ""
            targetUrl = message.data["target_url"] ?: message.data["url"] ?: DEFAULT_URL
        } else if (message.notification != null) {
            // Notification message (only received here when app is in foreground)
            Log.d(TAG, "Notification payload: ${message.notification}")
            title = message.notification?.title ?: getString(R.string.app_name)
            body = message.notification?.body ?: ""
            targetUrl = message.data["url"] ?: message.data["target_url"] ?: DEFAULT_URL
        } else {
            Log.w(TAG, "Empty message received, ignoring")
            return
        }

        if (body.isNotEmpty()) {
            showNotification(title, body, targetUrl)
        }
    }

    /**
     * Build and display a local notification.
     */
    private fun showNotification(title: String, body: String, targetUrl: String) {
        // Create intent to open MainActivity with target URL
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_FROM_NOTIFICATION"
            putExtra("target_url", targetUrl)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            pendingIntentFlags
        )

        // Default notification sound
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Build notification
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(getColor(R.color.green_primary))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body)
                    .setBigContentTitle(title)
            )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Use timestamp as notification ID for uniqueness
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())

        Log.d(TAG, "Notification shown: id=$notificationId, title=$title")
    }

    /**
     * Send the FCM token to your backend server.
     * This should be implemented to enable targeted push notifications.
     */
    private fun sendTokenToServer(token: String) {
        // TODO: Implement API call to send token to bkorkortsteori.se backend
        // Example:
        // val url = "https://bkorkortsteori.se/api/fcm/register"
        // val json = JSONObject().apply {
        //     put("token", token)
        //     put("platform", "android")
        //     put("app_version", BuildConfig.VERSION_NAME)
        // }
        // ... HTTP POST request ...

        Log.d(TAG, "Token should be sent to server: $token")

        // Store token locally for reference
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }
}
