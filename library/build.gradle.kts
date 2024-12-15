import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
    id("com.vanniktech.maven.publish.base")
}

kotlin {
    compilerOptions.allWarningsAsErrors.set(true)
}

dependencies {
    implementation(libs.bundles.connect)
    implementation(libs.bundles.ktor)
    implementation(libs.okio)
    implementation(libs.protovalidate)
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.bundles.test)
    testImplementation(libs.bundles.protobuf)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    test {
        java {
            srcDir("build/generated/sources/bufgen")
        }
    }
}

mavenPublishing {
    configure(KotlinJvm())
}

extensions.getByType<PublishingExtension>().apply {
    publications
        .filterIsInstance<MavenPublication>()
        .forEach { publication ->
            publication.artifactId = "connect-ktor"
        }
}
