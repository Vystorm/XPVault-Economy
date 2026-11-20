plugins { java }
group = "local.xpeconomy"
version = "1.26.1-26.2"
val coreJar = file(providers.gradleProperty("vystormCoreJar").getOrElse("libs/Vystorm_Core.jar"))
val vaultJar = file(providers.gradleProperty("vaultJar").getOrElse("libs/VaultUnlocked.jar"))
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(coreJar, vaultJar))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(files(coreJar, vaultJar))
    testImplementation("org.mockito:mockito-core:5.20.0")
}
java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
sourceSets.main { java.setSrcDirs(listOf("src")); resources.setSrcDirs(emptyList<String>()) }
sourceSets.test { java.setSrcDirs(listOf("tests")) }
tasks.processResources {
    from("plugin.yml", "config.yml", "setup.yml", "tutorial.yml", "LICENSE")
    from(fileTree(".") { include("tutorial_*.yml") })
    from("lang") { into("lang") }
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 21 }
tasks.test { useJUnitPlatform() }
tasks.jar { archiveFileName = "XPVaultEconomy-${project.version}.jar" }

// Deliberately exclude the current Paper API and its libraries from this classpath.
val paper1206Libraries = file(providers.gradleProperty("paper1206Libraries")
    .getOrElse((System.getProperty("user.home") + "/source/tools/smoke-1206/libraries")))
val compat1206 by tasks.registering(JavaCompile::class) {
    description = "Compiles production sources against Paper 1.20.6 with Java 21 bytecode"
    group = "verification"
    source(sourceSets.main.get().allJava)
    classpath = files(coreJar, vaultJar) + fileTree(paper1206Libraries) { include("**/*.jar") }
    destinationDirectory = layout.buildDirectory.dir("classes/java/compat1206")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    doFirst {
        require(paper1206Libraries.resolve("io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-SNAPSHOT.jar").isFile) {
            "Paper 1.20.6 libraries missing: -Ppaper1206Libraries=/path/to/libraries"
        }
    }
}
tasks.check { dependsOn(compat1206) }
tasks.test {
    systemProperty("compat1206.classpath", (files(coreJar, vaultJar) + fileTree(paper1206Libraries) {
        include("**/*.jar")
    } + sourceSets.main.get().output).asPath)
}

tasks.compileJava {
    // Also ship baseline API descriptors (including Adventure), not just baseline bytecode.
    classpath = compat1206.get().classpath
    doFirst {
        require(coreJar.isFile) { "Vystorm Core JAR missing: -PvystormCoreJar=/path/to/Vystorm_Core-0.65.0.jar (or libs/Vystorm_Core.jar)" }
        require(vaultJar.isFile) { "VaultUnlocked JAR missing: -PvaultJar=/path/to/VaultUnlocked.jar (or libs/VaultUnlocked.jar)" }
    }
}

val integration by sourceSets.creating {
    java.setSrcDirs(listOf("test-harness/src"))
    resources { setSrcDirs(listOf("test-harness")); include("plugin.yml") }
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("integrationJar") {
    dependsOn(tasks.named(integration.classesTaskName))
    from(integration.output)
    archiveFileName = "XPVaultEconomyTest.jar"
}
