import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.diffplug.spotless") version "8.10.0" apply false
    id("com.github.spotbugs") version "6.5.10" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

allprojects {
    group = "io.github.aincraft"

    val calverDate = LocalDate.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    version = providers.gradleProperty("buildVersion")
        .orElse(
            providers.environmentVariable("GITHUB_RUN_NUMBER")
                .map { "$calverDate.$it" },
        )
        .orElse("$calverDate-SNAPSHOT")
        .get()
}
