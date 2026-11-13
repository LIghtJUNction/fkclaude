plugins { id("com.android.application") }
android {
    namespace = "io.github.lightjunction.fkclaude"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.lightjunction.fkclaude"
        minSdk = 32
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
    bundle { language { enableSplit = false } }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
