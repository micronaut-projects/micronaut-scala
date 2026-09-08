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

// The plugin is loaded by whatever JVM runs the user's Scala compiler, and JDK 25 is
// the supported baseline for this repository. One constant for both halves of the
// plugin: the Java and Scala sources are compiled by different tasks, and letting them
// drift is how the jar ended up half Java 17 and half Java 25 bytecode.
val javaTarget = 25

sourceSets {
    named("main") {
        java.srcDir("../inject-scala/src/main/java")
        scala.srcDir("../inject-scala/src/main/scala")
        resources.srcDir("../inject-scala/src/main/resources")
    }
}

// What the jar bundles, and therefore what the POM must NOT declare. A consumer of the
// published plugin would otherwise get every one of these classes twice -- once inside the
// jar and once from the POM -- with classpath order deciding which copy wins. It is also
// what makes the documented usage work: Gradle turns each resolved `scalaCompilerPlugins`
// file into its own `-Xplugin` argument, so a transitive POM hands `dotc` a dozen jars with
// no plugin descriptor.
//
// Not shading: relocation was considered and declined. The bundle stays at original package
// paths, so a consumer must not also resolve these coordinates.
val bundled: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundled(libs.micronaut.core.processor) {
        exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")
        exclude(group = "org.scala-lang", module = "scala3-library_3")
    }
    bundled(libs.asm)

    // Compiled against, bundled into the jar, and deliberately absent from the POM.
    compileOnly(libs.micronaut.core.processor) {
        exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")
        exclude(group = "org.scala-lang", module = "scala3-library_3")
    }
    compileOnly(libs.asm)

    // The one real dependency: the host compiler supplies scala3-library, and the jar
    // deliberately excludes it, so a consumer does need to resolve it.
    api(libs.scala3.library)

    compileOnly(libs.scala3.compiler)

    testImplementation(libs.micronaut.context)
}

// Sourcegen and released Core versions may carry a newer Scala BOM. A compiler
// plugin must never be assembled against that version accidentally: this variant
// is deliberately pinned to its one supported compiler release.
configurations.configureEach {
    // Gradle resolves its own Zinc into a `zinc` configuration, and Zinc is built for
    // Scala 2.13: it wants `org.scala-lang:scala-library` and `scala-reflect` at 2.13.x.
    // Pinning inside it fails the build -- with the whole group pinned, first "Version
    // 3.9.0 is not compatible with org.scala-sbt:zinc_2.13:1.12.0", then
    // "Could not find org.scala-lang:scala-reflect:3.9.0". Both were seen; neither is
    // this project's toolchain.
    if (name == "zinc") {
        return@configureEach
    }
    resolutionStrategy.eachDependency {
        // Every Scala artifact the compiler reads as one unit, not just the library and
        // the compiler: `tasty-core_3` and `scala3-interfaces` ship from the same release,
        // and a constraint from elsewhere moving one of them produces exactly the ABI
        // mismatch this pin exists to prevent.
        //
        // `org.scala-lang.modules` is a different group and is deliberately not matched:
        // `scala-asm` carries its own versioning (9.9.0-scala-1).
        if (requested.group == "org.scala-lang") {
            useVersion(libs.versions.scala3.get())
            because("compiler plugins are binary-compatible only with their exact Scala compiler release")
        }
    }
}

// `options.release` is what actually decides the emitted class file version;
// `sourceCompatibility`/`targetCompatibility` alone were being overridden by the
// shared convention plugin.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaTarget)
}

tasks.withType<ScalaCompile>().configureEach {
    sourceCompatibility = javaTarget.toString()
    targetCompatibility = javaTarget.toString()
    scalaCompileOptions.additionalParameters.add("-release:$javaTarget")
    scalaCompileOptions.additionalParameters.add("-J--add-modules=java.compiler")
    scalaCompileOptions.forkOptions.jvmArgs = (scalaCompileOptions.forkOptions.jvmArgs ?: emptyList()) + "--add-modules=java.compiler"
}

// The Scala compiler plugin is loaded by a compiler-owned classloader. Keep
// Micronaut and ASM implementation classes in the plugin, but let the host
// compiler provide scala-library and scala3-library.
fun bundledRuntimeJars(): List<File> =
    bundled.resolve()
        .filter { it.isFile && it.extension == "jar" }
        .filterNot {
            it.name.startsWith("scala3-library_3-") ||
                it.name.startsWith("scala-library-")
        }

val ownServiceDirectory = layout.projectDirectory.dir("../inject-scala/src/main/resources/META-INF/services")
val mergedServiceDirectory = layout.buildDirectory.dir("merged-service-files")

// `DuplicatesStrategy.EXCLUDE` keeps the first entry for a path and drops the rest
// with no message. For `META-INF/services/**` that is silent data loss: this
// project's own single-line `TypeConverterRegistrar` descriptor was winning over
// Core's, so `AnnotationConvertersRegistrar` and `ReactiveTypeConverterRegistrar`
// never reached the plugin jar. Services are resolved by `ServiceLoader`, so a
// dropped descriptor is a missing implementation with no build error anywhere.
// Union every descriptor instead, and let the jar take services only from here.
val mergeServiceFiles = tasks.register("mergeServiceFiles") {
    val serviceDirectory = ownServiceDirectory
    val outputDirectory = mergedServiceDirectory
    inputs.files(bundled)
    inputs.dir(serviceDirectory).withPropertyName("ownServiceDescriptors")
    outputs.dir(outputDirectory)
    doLast {
        fun entries(text: String) =
            text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

        val merged = linkedMapOf<String, LinkedHashSet<String>>()
        // This project's own implementations come first so they lead each descriptor.
        serviceDirectory.asFile.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            merged.getOrPut(file.name) { LinkedHashSet() }.addAll(entries(file.readText()))
        }
        bundledRuntimeJars().forEach { jar ->
            zipTree(jar).matching { include("META-INF/services/*") }.forEach { file ->
                merged.getOrPut(file.name) { LinkedHashSet() }.addAll(entries(file.readText()))
            }
        }

        val target = outputDirectory.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        merged.forEach { (name, implementations) ->
            target.resolve(name).writeText(implementations.joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(pluginArtifactId)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    // Taken from the merged descriptors below instead.
    exclude("META-INF/services/**")
    from({ bundledRuntimeJars().map { zipTree(it) } })
    // Sourced flat and mapped in, so the exclusion above does not match them.
    from(mergeServiceFiles) {
        into("META-INF/services")
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = pluginArtifactId
    }
}
