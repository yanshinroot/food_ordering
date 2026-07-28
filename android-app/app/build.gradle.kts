plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foodorder.staff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foodorder.staff"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.4.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "role"
    productFlavors {
        create("cashier") {
            dimension = "role"
            applicationIdSuffix = ".cashier"
            resValue("string", "app_name", "Food Cashier")
            buildConfigField("String", "FOOD_ROLE", "\"cashier\"")
        }
        create("kitchen") {
            dimension = "role"
            applicationIdSuffix = ".kitchen"
            resValue("string", "app_name", "Food Kitchen")
            buildConfigField("String", "FOOD_ROLE", "\"kitchen\"")
        }
    }

    // Matches the debug APK naming the enrollment/CI docs expect:
    // food-cashier-debug.apk / food-kitchen-debug.apk.
    setProperty("archivesBaseName", "food")

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "META-INF/LICENSE*"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Android Keystore-backed encrypted storage for the device credential
    // (replaces plaintext SharedPreferences — see storage/EncryptedDeviceStore.kt).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
