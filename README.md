# Micronaut Scala

Micronaut Scala contains the Scala 3 compiler plugin and its parity test
harness. The initial supported compiler release is Scala 3.3.8.

The compiler plugin is a fully cross-versioned compiler artifact. Because Scala
compiler APIs are not binary compatible across compiler releases, the compiler
version is part of both published artifact IDs:

* `io.micronaut.scala:micronaut-inject-scala_3.3.8`
* `io.micronaut.scala:micronaut-inject-scala-test_3.3.8`

The repository version is independent of Micronaut Core and starts at
`1.0.0-SNAPSHOT`.

## Local bootstrap

During SPI development, IncludeGit substitutes Core dependencies with the
unmerged Core checkout. Point it at a local checkout when running Gradle:

```bash
./gradlew :micronaut-inject-scala-test-3-3-8:test \
  -Plocal.git.micronaut-core=/path/to/micronaut-core
```

The branch include is opt-in. Set `-PincludeMicronautCore=true` to include the
configured Core branch over HTTPS, or provide `local.git.micronaut-core` for a
local source build. Once a released Core version contains the SPI, remove the
IncludeGit option and set the `micronaut-core` catalog version to that release
before publishing `1.0.0`.

The build uses the JVM/Gradle toolchain only; GraalPy is not part of the Scala
plugin or test harness.

## Verification

```bash
./gradlew verifyCompilerArtifacts \
  -Plocal.git.micronaut-core=/path/to/micronaut-core
```

The verification task checks the full-cross artifact IDs, BOM entries, and
that Scala compiler/runtime classes are not bundled into the compiler plugin.
