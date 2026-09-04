import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.scala.ScalaCompile

plugins {
    id("io.micronaut.build.internal.convention-library")
    id("scala")
}

val scalaVersion = libs.versions.scala3.get()
val pluginArtifactId = "micronaut-inject-scala_$scalaVersion"
val pluginModuleId = "io.micronaut.scala:$pluginArtifactId"

description = "Micronaut Scala compiler plugin for Scala $scalaVersion"

micronautBuild {
    core {
        usesMicronautTest()
    }
    descriptor {
        // This is the root published module for this standalone repository.
        // Micronaut Build treats a self-parent as having no parent module.
        parentModuleId = pluginModuleId
    }
    binaryCompatibility.enabledAfter("1.0.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        java.srcDir("../inject-scala/src/main/java")
        scala.srcDir("../inject-scala/src/main/scala")
        resources.srcDir("../inject-scala/src/main/resources")
    }
}

dependencies {
    api(libs.micronaut.core.processor) {
        exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")
        exclude(group = "org.scala-lang", module = "scala3-library_3")
    }
    api(libs.scala3.library)

    implementation(libs.asm)

    compileOnly(libs.scala3.compiler)

    testImplementation(libs.micronaut.context)
}

// Sourcegen and released Core versions may carry a newer Scala BOM. A compiler
// plugin must never be assembled against that version accidentally: this variant
// is deliberately pinned to its one supported compiler release.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" &&
            (requested.name == "scala3-library_3" || requested.name == "scala3-compiler_3")) {
            useVersion(libs.versions.scala3.get())
            because("compiler plugins are binary-compatible only with their exact Scala compiler release")
        }
    }
}

tasks.withType<ScalaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    scalaCompileOptions.additionalParameters.add("-release:17")
    scalaCompileOptions.additionalParameters.add("-J--add-modules=java.compiler")
    scalaCompileOptions.forkOptions.jvmArgs = (scalaCompileOptions.forkOptions.jvmArgs ?: emptyList()) + "--add-modules=java.compiler"
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(pluginArtifactId)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    // The Scala compiler plugin is loaded by a compiler-owned classloader. Keep
    // Micronaut and ASM implementation classes in the plugin, but let the host
    // compiler provide scala-library and scala3-library.
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.extension == "jar" }
            .filterNot {
                it.name.startsWith("scala3-library_3-") ||
                    it.name.startsWith("scala-library-")
            }
            .map { zipTree(it) }
    })
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = pluginArtifactId
    }
}
