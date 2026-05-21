package com.instaget.downloader

import android.content.Intent
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.instaget.downloader.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
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
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }, 1500L)
            }
            .start()
    }
}
