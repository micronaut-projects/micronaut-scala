plugins {
    id("io.micronaut.build.internal.convention-test-library")
    id("scala")
}

description = "Compiled Scala examples included by the guide"

// The guide's Scala shows up here rather than inside the AsciiDoc, so that every line
// printed in the documentation is a line this build compiles with the plugin and this
// module's tests run. `snippet::` in `src/main/docs/guide` resolves against the directory
// name and `source=main`, so neither may be renamed without updating the guide.
dependencies {
    scalaCompilerPlugins(project(":micronaut-inject-scala-compiler"))

    implementation(libs.scala3.library)
    implementation(libs.micronaut.context)
    implementation(projects.micronautRuntimeScala)

    // Serialization is not part of the plugin: it is micronaut-serde reading the
    // introspection the plugin generates. The published serde release resolves the
    // Micronaut modules it needs at its own line, so the io.micronaut ones are excluded
    // and taken from this build's version instead.
    implementation(platform(libs.test.boms.micronaut.serde))
    implementation(libs.micronaut.serde.jackson) {
        exclude(group = "io.micronaut")
    }
    implementation(libs.micronaut.json.core)
    implementation(libs.micronaut.jackson.core)
    implementation(libs.micronaut.http)
    implementation(libs.micronaut.openapi.annotations)
    compileOnly(libs.micronaut.openapi)
    compileOnly(libs.slf4j.simple)
    implementation(libs.jackson.databind)

    testImplementation(libs.spock) {
        exclude(module = "groovy-all")
    }
    testImplementation(libs.managed.groovy)
}

configurations.configureEach {
    // Same reasoning as the compiler variants: Gradle resolves its own Zinc, which is built
    // for Scala 2.13, into a `zinc` configuration that must not be pinned to the toolchain.
    if (name == "zinc") {
        return@configureEach
    }
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang") {
            useVersion(libs.versions.scala3.get())
            because("the examples must compile against the compiler version this plugin variant publishes")
        }
    }
}

val javaTarget = 25

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaTarget)
}

tasks.withType<ScalaCompile>().configureEach {
    // Without an explicit target, Gradle derives `-Xunchecked-java-output-version` from the
    // shared convention's compatibility settings and hands scalac a release it rejects.
    sourceCompatibility = javaTarget.toString()
    targetCompatibility = javaTarget.toString()
    scalaCompileOptions.additionalParameters.addAll(
        listOf(
            "-release:$javaTarget",
            // The plugin reads the JDK compiler API through `java.compiler`, which is not in
            // the default module graph of a non-modular compilation.
            "-J--add-modules=java.compiler"
        )
    )
    scalaCompileOptions.forkOptions.jvmArgs =
        (scalaCompileOptions.forkOptions.jvmArgs ?: emptyList()) + "--add-modules=java.compiler"
}
