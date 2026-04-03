plugins {
    alias(libs.plugins.android.application) apply false

    // 🔥 ADD THIS
    id("com.google.gms.google-services") version "4.4.0" apply false
}