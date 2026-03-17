package se.bkorkortsteori.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash screen activity that shows the app branding for a brief moment
 * before transitioning to the main WebView activity.
 *
 * Uses a green (#1db954) background with the app name centered.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DELAY_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, SPLASH_DELAY_MS)
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Forward any extras from the launching intent (e.g., from notification)
            this@SplashActivity.intent?.extras?.let { putExtras(it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()

        // Smooth fade transition
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // Prevent back press during splash
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - prevent closing during splash
    }
}
