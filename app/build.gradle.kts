plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

// Load signing credentials from local.properties (never committed to git)
import java.util.Properties
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

// Firebase (Analytics, Crashlytics, App Check) needs app/google-services.json from the Firebase
// console (gitignored). Without it the plugins are skipped so debug builds still work —
// Analytics then no-ops — but release builds refuse to proceed.
val hasGoogleServicesJson = file("google-services.json").exists()
if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
gradle.taskGraph.whenReady {
    if (allTasks.none { it.name.contains("Release") }) return@whenReady
    if (!hasGoogleServicesJson) {
        throw GradleException("app/google-services.json is missing — download it from the Firebase console before building a release.")
    }
}

android {
    namespace = "com.instaget.downloader"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.instaget.downloader"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val admobAppId = localProps.getProperty("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
        val adBannerIg = localProps.getProperty("AD_BANNER_IG") ?: "ca-app-pub-3940256099942544/6300978111"
        val adBannerThreads = localProps.getProperty("AD_BANNER_THREADS") ?: "ca-app-pub-3940256099942544/6300978111"
        val adRewarded = localProps.getProperty("AD_REWARDED_INTERSTITIAL") ?: "ca-app-pub-3940256099942544/5354046379"
        val adNativeThreads = localProps.getProperty("AD_NATIVE_THREADS") ?: "ca-app-pub-3940256099942544/2247696110"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "AD_BANNER_IG", "\"$adBannerIg\"")
        buildConfigField("String", "AD_BANNER_THREADS", "\"$adBannerThreads\"")
        buildConfigField("String", "AD_REWARDED_INTERSTITIAL", "\"$adRewarded\"")
        buildConfigField("String", "AD_NATIVE_THREADS", "\"$adNativeThreads\"")
        // Supabase project used for the one-time free-credit claim (see supabase/README.md).
        // Both values are public by design; the function is protected by Firebase App Check.
        val supabaseUrl = localProps.getProperty("SUPABASE_URL") ?: "https://dwojyygknmodevuvqhar.supabase.co"
        val supabaseAnonKey = localProps.getProperty("SUPABASE_ANON_KEY") ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        val releaseStoreFile = localProps.getProperty("RELEASE_STORE_FILE")
        if (releaseStoreFile != null) {
            create("release") {
                storeFile     = file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias      = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword   = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        // Scraper parsing tests run on the JVM: android.util.Log calls become no-ops.
        unitTests.isReturnDefaultValues = true
    }
}

kapt {
    // Exported Room schemas (app/schemas) let future migrations be written and tested
    // instead of silently wiping the library.
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // OkHttp (scrapers + DownloadWorker)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Billing
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Material
    implementation("com.google.android.material:material:1.11.0")

    // Activity / Fragment KTX
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // AppCompat
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // AdMob + UMP
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    // Firebase: Analytics, Crashlytics, App Check (Play Integrity in release, debug provider in debug)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    testImplementation(libs.junit)
    // Real org.json for JVM tests (android.jar only ships stubs)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
