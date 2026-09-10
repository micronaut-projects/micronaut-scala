plugins {
    id("io.micronaut.build.internal.convention-test-library")
    id("scala")
}

description = "Functional tests for micronaut-openapi and JSON schema generation from Scala sources"

dependencies {
    scalaCompilerPlugins(project(":micronaut-inject-scala-compiler"))

    implementation(libs.scala3.library)
    implementation(libs.micronaut.context)
    implementation(libs.micronaut.http)
    implementation(projects.micronautRuntimeScala)

    implementation(libs.micronaut.openapi.annotations)
    compileOnly(libs.micronaut.openapi)

    implementation(libs.micronaut.json.schema.annotations)
    compileOnly(libs.micronaut.json.schema.processor)

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
