import java.io.RandomAccessFile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val erpTestToken = localProperties.getProperty("erp.test.token")?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: error("Configure erp.test.token no arquivo local.properties para compilar o aplicativo.")
val escapedErpTestToken = erpTestToken.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r")

val generatesVersion = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':').lowercase()
    name == "build" || name.startsWith("assemble") || name.startsWith("package") ||
        name.startsWith("bundle") || name.startsWith("install")
}
check(!generatesVersion || !gradle.startParameter.isConfigurationCacheRequested) {
    "Desative o configuration cache para gerar uma versão nova do aplicativo."
}
val versionCounter = layout.projectDirectory.file("version.properties").asFile
check(versionCounter.isFile) { "Arquivo de versionamento não encontrado: $versionCounter" }
val appVersionCode = RandomAccessFile(versionCounter, "rw").use { file ->
    file.channel.lock().use {
        val previous = file.readLine()?.trim()?.toIntOrNull()
            ?: error("Número de versão inválido em $versionCounter")
        check(previous in 1 until 2_100_000_000) { "Número de versão fora do intervalo permitido" }
        val current = if (generatesVersion) previous + 1 else previous
        if (generatesVersion) {
            file.seek(0)
            file.setLength(0)
            file.write("$current\n".toByteArray(Charsets.UTF_8))
            file.fd.sync()
        }
        current
    }
}

android {
    namespace = "com.example.forcavendas"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.forcavendas"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.0.${appVersionCode - 1}"
        buildConfigField("String", "ERP_TEST_TOKEN", "\"$escapedErpTestToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}