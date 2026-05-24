import com.vanniktech.maven.publish.KotlinJvm
import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.cyclonedx.bom)
    id("com.vanniktech.maven.publish.base")
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    schemaVersion.set(org.cyclonedx.Version.VERSION_16)
    jsonOutput.set(layout.buildDirectory.file("reports/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/bom.xml"))
    includeConfigs.set(listOf("runtimeClasspath"))
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
