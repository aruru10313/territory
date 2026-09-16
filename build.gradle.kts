plugins {
    id("java")
    alias(libs.plugins.neoforge.gradle)
}

val minecraftVersion = "1.21.1"
val neoVersion = "21.1.249"

group = "de.aruru"
version = "1.0.0"
base.archivesName = "territory-neoforge"

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

neoForge {
    version = neoVersion
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.neoforged:neoforge:$neoVersion")
    compileOnly(files("$rootDir/api/build/libs/bluemap-api-2.7.7-dirty.jar"))

    jarJar("org.xerial:sqlite-jdbc:3.46.1.0")
    jarJar("com.mysql:mysql-connector-j:8.4.0")
    jarJar("org.postgresql:postgresql:42.7.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<ProcessResources>().configureEach {
    val replacements = mapOf("version" to project.version, "minecraft_version" to minecraftVersion)
    inputs.properties(replacements)
    filesMatching("META-INF/neoforge.mods.toml") { expand(replacements) }
}

tasks.jar {
    archiveFileName = "territory-neoforge-$version.jar"
}
