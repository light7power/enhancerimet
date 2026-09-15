plugins {
    id("com.android.application")
}

android {
    namespace = "rimet.enhancement.modern"
    compileSdk = 35

    defaultConfig {
        applicationId = "rimet.enhancement.modern"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "**"
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation(files("libs/service-classes.jar", "libs/interface-classes.jar"))
}
