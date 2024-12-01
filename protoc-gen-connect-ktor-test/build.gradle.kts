plugins {
    application
    java
    kotlin("jvm")
    alias(libs.plugins.kotlinx.serialization)
}

application {
    mainClass.set("io.github.ichizero.protocgen.connect.ktor.Main")
}

kotlin {
    compilerOptions.allWarningsAsErrors.set(true)
}

dependencies {
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinpoet)
    implementation(libs.protoc.gen.connect.kotlin)
    implementation(libs.protobuf.java)

    testImplementation(project(":library"))
    testImplementation(libs.bundles.test)
    testImplementation(libs.bundles.connect)
    testImplementation(libs.bundles.protobuf)
    testImplementation(libs.kotlinx.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    test {
        java {
            srcDir(layout.buildDirectory.dir("generated/sources/bufgen"))
        }
    }
}
