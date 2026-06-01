package com.instaget.downloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.instaget.downloader.billing.BillingManager
import com.instaget.downloader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — no action needed, notifications are optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extend content behind ONLY the top status bar (not the bottom nav bar).
        // The root layout background (colorStatusBar) shows through the transparent
        // status bar, turning it purple. paddingTop is set to the status bar height
        // so actual content starts below it. BottomNavigationView handles its own
        // bottom insets via fitsSystemWindows=true, keeping the nav bar area white.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarTop)
            insets  // pass through so BottomNavigationView handles bottom insets
        }
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        BillingManager.getInstance(this)

        // Initialise AdMob immediately (required before loading any ads)
        MobileAds.initialize(this) { Log.d("AdMob", "Initialized") }
        // Then handle UMP consent for GDPR regions (non-blocking for non-EU)
        initConsentAndAds()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNavigationView.setupWithNavController(navController)
        binding.bottomNavigationView.setOnItemReselectedListener { /* consume reselect, no-op */ }

        handleNavigateIntent(intent, navController)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as androidx.navigation.fragment.NavHostFragment
        handleNavigateIntent(intent, navHostFragment.navController)
    }

    /**
     * Requests consent info (UMP / GDPR), shows a consent form if required,
     * then initialises the AdMob SDK. Safe for non-EU users — the form is
     * skipped automatically by the UMP SDK when not required.
     */
    private fun initConsentAndAds() {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()
        val consentInfo = UserMessagingPlatform.getConsentInformation(this)
        consentInfo.requestConsentInfoUpdate(this, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { formError ->
                if (formError != null) Log.w("Consent", "Form error: ${formError.message}")
            }
        }, { requestError ->
            Log.w("Consent", "Request error: ${requestError.message}")
        })
    }

    private fun handleNavigateIntent(intent: android.content.Intent?, navController: androidx.navigation.NavController) {
        when (intent?.getStringExtra("navigate_to")) {
            "library" -> navController.navigate(R.id.libraryFragment)
            "home"    -> navController.navigate(R.id.homeFragment)
            "threads" -> navController.navigate(R.id.threadsFragment)
        }
    }
}
