<!-- Checklist: https://github.com/micronaut-projects/micronaut-core/wiki/New-Module-Checklist -->

# Micronaut Scala

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.scala/micronaut-inject-scala_3.9.0.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.micronaut.scala/micronaut-inject-scala_3.9.0)
[![Build Status](https://github.com/micronaut-projects/micronaut-scala/actions/workflows/gradle.yml/badge.svg)](https://github.com/micronaut-projects/micronaut-scala/actions/workflows/gradle.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=micronaut-projects_micronaut-scala&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=micronaut-projects_micronaut-scala)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.micronaut.io/scans)

Micronaut Scala contains the Scala 3 compiler plugin and its parity test
harness. The initial supported compiler release is Scala 3.9.0.

Compiler-plugin APIs are not binary compatible across all Scala compiler
releases. The complete compiler version is therefore part of the artifact
coordinates:

* `io.micronaut.scala:micronaut-inject-scala_3.9.0`
* `io.micronaut.scala:micronaut-inject-scala-test_3.9.0`

The Micronaut Scala version is independent of the Micronaut Core version.

## Documentation

See the [Documentation](https://micronaut-projects.github.io/micronaut-scala/latest/guide/) for more information.

See the [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-scala/snapshot/guide/) for the current development docs.

The guide sources are in [`src/main/docs/guide`](src/main/docs/guide). They
cover the compiler plugin, artifact coordinates, bootstrap process, release
policy, and compiler compatibility. The user-facing Micronaut Framework Scala
examples are maintained in the [Core documentation follow-up](https://github.com/micronaut-projects/micronaut-core/pull/12981).

<!-- ## Examples

Examples can be found in the [examples](https://github.com/micronaut-projects/micronaut-scala/tree/master/examples) directory. -->

## Local bootstrap

During development of the Core SPI, IncludeGit can substitute the Core
dependencies with a local Core checkout:

```bash
./gradlew :micronaut-inject-scala-test-compiler:test \
  -Plocal.git.micronaut-core=/path/to/micronaut-core
```

The remote branch include is opt-in with `-PincludeMicronautCore=true`, which
clones the Core `5.2.x` branch (override with `-PmicronautCoreBranch=`) instead
of using a local checkout.

The catalog currently pins `micronaut-core = "5.2.0-SNAPSHOT"`, which is not
published anywhere, so **one of the two Core includes is required** — a
standalone `./gradlew check` configures but cannot resolve
`io.micronaut:micronaut-core-processor`. CI therefore builds the composite.
Once a released Core version contains the SPI, pin that released version in the
catalog, drop `-PincludeMicronautCore` from CI, and publish `1.0.0`.

The Scala compiler and integration versions are separate. Scala 2 and GraalPy
are out of scope.

## Snapshots and Releases

Snapshots are automatically published to [Sonatype Snapshots](https://s01.oss.sonatype.org/content/repositories/snapshots/io/micronaut/scala/) using [GitHub Actions](https://github.com/micronaut-projects/micronaut-scala/actions).

See the documentation in the [Micronaut Docs](https://docs.micronaut.io/latest/guide/index.html#usingsnapshots) for how to configure your build to use snapshots.

Releases are published to Maven Central via [GitHub Actions](https://github.com/micronaut-projects/micronaut-scala/actions).

Releases are completely automated. To perform a release use the following steps:

* [Publish the draft release](https://github.com/micronaut-projects/micronaut-scala/releases). There should be already a draft release created, edit and publish it. The Git Tag should start with `v`. For example `v1.0.0`.
* [Monitor the Workflow](https://github.com/micronaut-projects/micronaut-scala/actions?query=workflow%3ARelease) to check it passed successfully.
* If everything went fine, [publish to Maven Central](https://github.com/micronaut-projects/micronaut-scala/actions?query=workflow%3A"Maven+Central+Sync").
* Celebrate!

Each compiler-plugin release must retain the full Scala compiler suffix. For
example, support for another compiler release adds a parallel artifact instead
of replacing `micronaut-inject-scala_3.9.0`.
