import java.util.Properties

plugins {
    id("com.android.application")
    id("maven-publish")
}

// ---- Release 签名配置 ----
// 取值优先级：CI 环境变量（由 .github/workflows/publish.yml 从 GitHub Secrets 注入） > local.properties > 默认值
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingStoreFile = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("signing.storeFile", "keystore/release.jks")
val signingStorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("signing.storePassword", "")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("signing.keyAlias", "release")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("signing.keyPassword", signingStorePassword)

// 仅当 keystore 文件存在且密码非空时才启用 release 签名；否则保持 unsigned，本地无密钥构建不受影响
val releaseSigningEnabled = signingStorePassword.isNotBlank() && rootProject.file(signingStoreFile).isFile
val releaseApkFileName = if (releaseSigningEnabled) "app-release.apk" else "app-release-unsigned.apk"

android {
    namespace = "rimet.enhancement.modern"
    compileSdk = 35

    defaultConfig {
        applicationId = "rimet.enhancement.modern"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingStoreFile)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    }

    buildTypes {
        release {
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "rimet.enhancement"
            artifactId = "modern"
            version = "2.0.2"
            artifact(layout.buildDirectory.file("outputs/apk/release/$releaseApkFileName"))
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/light7power/enhancerimet")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

tasks.named("publish") {
    dependsOn("assembleRelease")
}
