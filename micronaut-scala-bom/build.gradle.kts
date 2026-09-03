plugins {
    id("io.micronaut.build.internal.bom")
}

group = properties["projectGroupId"].toString()
version = properties["projectVersion"].toString()

micronautBom {
    propertyName = "scala"
    extraExcludedProjects = listOf(
        "inject-scala",
        "inject-scala-3-3-8",
        "inject-scala-test",
        "inject-scala-test-3-3-8",
        "micronaut-inject-scala",
        "micronaut-inject-scala-3-3-8",
        "micronaut-inject-scala-test",
        "micronaut-inject-scala-test-3-3-8"
    )
}

// The build plugin derives managed artifact IDs from Gradle project names. The
// compiler version is intentionally encoded with dots in the Maven coordinates,
// so declare the two fully-crossed publications explicitly in the platform.
dependencies {
    constraints {
        add("api", "io.micronaut.scala:micronaut-inject-scala_3.3.8:${project.version}")
        add("api", "io.micronaut.scala:micronaut-inject-scala-test_3.3.8:${project.version}")
    }
}
