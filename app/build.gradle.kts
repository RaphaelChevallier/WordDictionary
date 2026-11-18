import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val appVersion = releaseProperties.getProperty("versionName")
val appVersionCode = releaseProperties.getProperty("versionCode").toInt()

android {
    namespace = "com.maxfluency.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maxfluency.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "mflx"
        noCompress += "mfpr"
        localeFilters += listOf("en", "fr", "es")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val prepareStarterLexicon = tasks.register<Exec>("prepareStarterLexicon") {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/starter.mjs", "prepare")
}

val preparePronunciationGuides = tasks.register<Exec>("preparePronunciationGuides") {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/pronunciation.mjs", "prepare")
}

abstract class BundleLexiconTask @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packsDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licensesDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ranksDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pronunciationDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        files.sync {
            from(packsDirectory) { into("lexicon") }
            from(manifestFile) { into("lexicon") }
            from(licensesDirectory) { into("licenses") }
            from(ranksDirectory) { into("ranks") }
            from(pronunciationDirectory) { into("pronunciations") }
            into(outputDirectory)
        }
    }
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar(Char::uppercaseChar)
    val bundle = tasks.register<BundleLexiconTask>("bundle${capitalized}Lexicon") {
        dependsOn(prepareStarterLexicon, preparePronunciationGuides)
        packsDirectory.set(rootProject.layout.projectDirectory.dir("content/starter/packs"))
        manifestFile.set(rootProject.layout.projectDirectory.file("content/starter/manifest.json"))
        licensesDirectory.set(rootProject.layout.projectDirectory.dir("content/licenses"))
        ranksDirectory.set(rootProject.layout.projectDirectory.dir("content/starter/ranks"))
        pronunciationDirectory.set(rootProject.layout.projectDirectory.dir("content/pronunciations"))
        outputDirectory.set(layout.buildDirectory.dir("generated/lexiconAssets/${variant.name}"))
    }

    tasks.withType<Test>().configureEach {
        systemProperty("maxfluency.projectDir", rootProject.projectDir.absolutePath)
        systemProperty("maxfluency.publicDictionaryIntegration", providers.gradleProperty("publicDictionaryIntegration").orElse("false").get())
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle) { it.outputDirectory }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") {
            because("Patch the legacy TLS certificate validation dependency pulled in by ML Kit.")
        }
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.github.luben:zstd-jni:1.5.7-12")
}
