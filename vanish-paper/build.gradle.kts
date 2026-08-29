import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.language.jvm.tasks.ProcessResources


plugins {
    java
    checkstyle
    pmd
    id("com.diffplug.spotless") version "8.10.0"
    id("com.github.spotbugs") version "6.5.10"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation(project(":vanish-common"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}

checkstyle {
    toolVersion = "13.11.0"
    config = resources.text.fromUri(
        "https://raw.githubusercontent.com/checkstyle/checkstyle/checkstyle-13.11.0/src/main/resources/google_checks.xml",
    )
}

pmd {
    toolVersion = "7.26.0"
    isIgnoreFailures = false
}

spotbugs {
    toolVersion.set("4.9.7")
    ignoreFailures.set(false)
}

spotless {
    java {
        googleJavaFormat("1.36.1")
    }
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Pmd>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("xml") { required.set(true) }
        create("html") { required.set(true) }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<Checkstyle>())
    dependsOn(tasks.withType<Pmd>())
    dependsOn(tasks.withType<com.github.spotbugs.snom.SpotBugsTask>())
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    relocate("redis.clients.jedis", "io.github.aincraft.vanish.libs.redis.clients.jedis")
    relocate("com.google.gson", "io.github.aincraft.vanish.libs.com.google.gson")
    relocate("org.apache.commons.pool2", "io.github.aincraft.vanish.libs.org.apache.commons.pool2")
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks {
    runServer {
        minecraftVersion("26.2")
    }
}
