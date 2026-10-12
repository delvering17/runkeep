import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapKey = local.getProperty("KAKAO_NATIVE_APP_KEY", "").trim()
require(mapKey.isEmpty() || mapKey.matches(Regex("[a-zA-Z0-9]+"))) { "Invalid Kakao native app key format" }
android {
    namespace = "com.runkeep.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.runkeep.app"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "com.runkeep.app.AudioRegressionInstrumentation"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$mapKey\"")
    }
    flavorDimensions += "map"
    productFlavors {
        create("kakao") { dimension = "map" }
        create("preview") { dimension = "map"; applicationIdSuffix = ".preview"; versionNameSuffix = "-preview" }
    }
    buildTypes {
        create("simulation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".gpstest"
            versionNameSuffix = "-gps-test"
            matchingFallbacks += "debug"
            val testLabel = providers.gradleProperty("gpsTestLabel").getOrElse("공통 GPS 테스트")
            require(!testLabel.contains('"') && !testLabel.contains('\\') && !testLabel.contains('\n'))
            buildConfigField("String", "GPS_TEST_LABEL", "\"$testLabel\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
androidComponents {
    beforeVariants(selector().withBuildType("simulation")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    "kakaoImplementation"("com.kakao.maps.open:android:2.15.2")
}
tasks.withType<Test>().configureEach { useJUnitPlatform { includeEngines("junit-jupiter") } }
