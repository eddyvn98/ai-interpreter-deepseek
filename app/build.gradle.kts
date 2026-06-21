import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

fun readSecretFromFile(file: File, key: String): String? {
  if (!file.exists()) return null
  return file.readLines()
    .mapNotNull { line ->
      val trimmed = line.trim()
      if (trimmed.startsWith("$key=")) trimmed.substringAfter("=").trim() else null
    }
    .firstOrNull()
    ?.takeIf { it.isNotBlank() }
}

fun escapeBuildConfigValue(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

fun loadSecretKey(projectRoot: File, key: String): String {
  val candidates = listOf(
    File("D:/hermes/.env"),
    File(projectRoot, ".env"),
    File(projectRoot, ".env.local"),
    File(projectRoot, ".env.example")
  )
  for (candidate in candidates) {
    val value = readSecretFromFile(candidate, key)
    if (!value.isNullOrBlank()) return value
  }
  return when (key) {
    "GEMINI_API_KEY" -> "MY_GEMINI_API_KEY"
    "DEEPSEEK_API_KEY" -> "MY_DEEPSEEK_API_KEY"
    "SILICONFLOW_API_KEY" -> "MY_SILICONFLOW_API_KEY"
    "MINIMAX_API_KEY" -> "MY_MINIMAX_API_KEY"
    "ASR_BASE_URL" -> "http://127.0.0.1:8000/v1"
    "ASR_API_KEY" -> ""
    else -> ""
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aiinterpreter.deepseek"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val secretKeys = mapOf(
      "GEMINI_API_KEY" to loadSecretKey(rootDir, "GEMINI_API_KEY"),
      "DEEPSEEK_API_KEY" to loadSecretKey(rootDir, "DEEPSEEK_API_KEY"),
      "SILICONFLOW_API_KEY" to loadSecretKey(rootDir, "SILICONFLOW_API_KEY"),
      "MINIMAX_API_KEY" to loadSecretKey(rootDir, "MINIMAX_API_KEY"),
      "ASR_BASE_URL" to loadSecretKey(rootDir, "ASR_BASE_URL"),
      "ASR_API_KEY" to loadSecretKey(rootDir, "ASR_API_KEY")
    )
    secretKeys.forEach { (name, value) ->
      buildConfigField("String", name, "\"${escapeBuildConfigValue(value)}\"")
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
