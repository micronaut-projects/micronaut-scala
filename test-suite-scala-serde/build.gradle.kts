plugins {
    id("io.micronaut.build.internal.convention-test-library")
    id("scala")
}

description = "Functional tests for Micronaut Serialization from Scala sources"

// Serialization is where Scala's own vocabulary meets a format that has none of it: Option has
// to become presence or absence, a case class's defaults have to survive a field the document
// omits, and the collections have to round-trip as arrays. None of that is visible from
// compiling -- only from writing a document and reading it back.
dependencies {
    scalaCompilerPlugins(project(":micronaut-inject-scala-compiler"))

    implementation(libs.scala3.library)
    implementation(libs.micronaut.context)
    implementation(projects.micronautRuntimeScala)

    implementation(platform(libs.test.boms.micronaut.serde))
    implementation(libs.micronaut.serde.jackson) {
        exclude(group = "io.micronaut")
    }
    implementation(libs.micronaut.json.core)
    implementation(libs.micronaut.jackson.core)
    implementation(libs.jackson.databind)
    compileOnly(libs.micronaut.serde.processor)
    // serde-processor declares these as provided, so they do not arrive transitively; its
    // source-generating visitor cannot initialise without them.
    compileOnly(libs.micronaut.sourcegen.annotations)
    compileOnly(libs.micronaut.sourcegen.generator)
    compileOnly(libs.micronaut.sourcegen.generator.java)

    testImplementation(libs.spock) { exclude(module = "groovy-all") }
    testImplementation(libs.managed.groovy)
}

configurations.configureEach {
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
    sourceCompatibility = javaTarget.toString()
    targetCompatibility = javaTarget.toString()
    scalaCompileOptions.additionalParameters.addAll(
        listOf(
            "-release:$javaTarget",
            "-J--add-modules=java.compiler"
        )
    )
    scalaCompileOptions.forkOptions.jvmArgs =
        (scalaCompileOptions.forkOptions.jvmArgs ?: emptyList()) + "--add-modules=java.compiler"
}
