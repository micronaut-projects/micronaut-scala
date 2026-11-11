plugins {
    id("io.micronaut.build.internal.bom")
}

group = properties["projectGroupId"].toString()
version = properties["projectVersion"].toString()

val scalaCompilerProject = "inject-scala-compiler"
val scalaLtsCompilerProject = "inject-scala-compiler-lts"
val scalaLatestCompilerProject = "inject-scala-compiler-latest"
val scalaTestCompilerProject = "inject-scala-test-compiler"

micronautBuild {
    // A BOM has no binary API baseline. The component publications enable
    // compatibility checks once the independent 1.0.0 line is released.
    binaryCompatibility.enabled = false
}

micronautBom {
    propertyName = "scala"
    extraExcludedProjects = listOf(
        "inject-scala",
        scalaCompilerProject,
        scalaLtsCompilerProject,
        scalaLatestCompilerProject,
        "inject-scala-test",
        scalaTestCompilerProject,
        "micronaut-inject-scala",
        "micronaut-$scalaCompilerProject",
        "micronaut-$scalaLtsCompilerProject",
        "micronaut-$scalaLatestCompilerProject",
        "micronaut-inject-scala-test",
        "micronaut-$scalaTestCompilerProject"
    )
    suppressions {
        dependencies.add("com.fasterxml.jackson.core:jackson-annotations:2.22")
    }
}

// The build plugin derives managed artifact IDs from Gradle project names. The
// compiler version is intentionally encoded with dots in the Maven coordinates,
// so declare the fully-crossed publications explicitly in the platform.
dependencies {
    constraints {
        add("api", "io.micronaut.scala:micronaut-inject-scala_${libs.versions.scala3.get()}:${project.version}")
        add("api", "io.micronaut.scala:micronaut-inject-scala_${libs.versions.scalaLts.get()}:${project.version}")
        add("api", "io.micronaut.scala:micronaut-inject-scala_${libs.versions.scalaLatest.get()}:${project.version}")
        add("api", "io.micronaut.scala:micronaut-inject-scala-test_${libs.versions.scala3.get()}:${project.version}")
    }
}
