plugins {
    id("io.micronaut.build.internal.convention-test-library")
    id("scala")
}

description = "Functional tests for Micronaut Data repositories written in Scala"

// Micronaut Data is the heaviest consumer of the Element API there is: it reads entity shapes,
// resolves generics through the repository hierarchy, and derives queries from method names and
// return types. A repository is also introduction advice, so a working one exercises the whole
// pipeline at once. H2 in memory keeps that to one dependency and no external service.
dependencies {
    scalaCompilerPlugins(project(":micronaut-inject-scala-compiler"))

    implementation(libs.scala3.library)
    implementation(libs.micronaut.context)
    implementation(projects.micronautRuntimeScala)

    implementation(platform(libs.test.boms.micronaut.data))
    implementation(libs.micronaut.data.jdbc)
    implementation(libs.micronaut.data.runtime)
    compileOnly(libs.micronaut.data.processor)
    runtimeOnly(libs.micronaut.jdbc.hikari)
    runtimeOnly(libs.h2)

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
