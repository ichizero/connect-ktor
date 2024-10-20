plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(libs.bundles.connect)
    implementation(libs.bundles.ktor)
    implementation(libs.okio)

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
