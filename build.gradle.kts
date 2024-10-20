import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)
}
apply(plugin = "com.vanniktech.maven.publish.base")

group = "io.github.ichizero"

allprojects {
    repositories {
        mavenCentral()
    }

    apply(plugin = "com.diffplug.spotless")
    spotless {
        // ratchetFrom("origin/main")
        isEnforceCheck = false
        kotlin {
            ktlint().editorConfigOverride(
                mapOf(
                    "ktlint_string-template-indent" to "disabled",
                ),
            )
            target("**/*.kt")
        }
        kotlinGradle {
            ktlint().editorConfigOverride(
                mapOf(
                    "ktlint_string-template-indent" to "disabled",
                ),
            )
            target("**/*.kts")
        }
    }

    plugins.withId("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
            signAllPublications()

            pom {
                name.set("connect-ktor") // Rewrite on each project.
                description.set("Use Connect with Ktor Server.")
                inceptionYear.set("2024")
                url.set("https://github.com/ichizero/connect-ktor")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("ichizero")
                        name.set("ichizero")
                        url.set("https://github.com/ichizero")
                    }
                }
                scm {
                    url.set("https://github.com/ichizero/connect-ktor")
                    connection.set("scm:git:git://github.com/ichizero/connect-ktor.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ichizero/connect-ktor.git")
                }
            }
        }
    }
}
