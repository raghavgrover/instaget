package com.instaget.downloader

import android.content.Intent
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.instaget.downloader.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dismiss the Android 12+ system splash instantly — only our custom
        // SplashActivity animation will be visible.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animateAndNavigate()
    }

    private fun animateAndNavigate() {
        val duration = 600L

        binding.ivLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        binding.tvAppName.animate()
            .alpha(1f)
            .setStartDelay(300L)
            .setDuration(400L)
            .start()

        binding.tvTagline.animate()
            .alpha(1f)
            .setStartDelay(500L)
            .setDuration(400L)
            .withEndAction {
                binding.root.postDelayed({
                    val nextActivity = if (WelcomeActivity.isTermsAccepted(this)) {
                        MainActivity::class.java
                    } else {
                        WelcomeActivity::class.java
                    }
                    startActivity(Intent(this, nextActivity))
                    finish()
                }, 1500L)
            }
            .start()
    }
}
