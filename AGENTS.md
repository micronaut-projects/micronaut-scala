# Repository Guidance

This repository is Micronaut Scala: a Scala 3 compiler plugin that runs
Micronaut's `TypeElementVisitor` and bean-definition pipeline during `dotc`
compilation, plus the test harness that exercises it. Keep root guidance short
and update it when the build or module layout changes.

## Repository Shape

Sources and published artifacts are deliberately separated. The two
`*-compiler` projects own the published, fully-crossed Maven coordinates; the
two source projects apply only the `base` plugin and publish nothing.

- `inject-scala/` — the plugin implementation. `src/main/scala` holds the dotty
  driver and model extractor (`MicronautScalaCompilerPlugin.scala`);
  `src/main/java` holds the Micronaut Element API adapter and annotation
  pipeline under `io.micronaut.scala.processing.visitor`.
- `inject-scala-test/` — the test harness (`ScalaCompiler`, the Spock base spec,
  the always-on test visitors) and the Spock parity suites.
- `inject-scala-compiler/` — compiles the `inject-scala` source trees and
  publishes `io.micronaut.scala:micronaut-inject-scala_<scalaVersion>`.
- `inject-scala-test-compiler/` — compiles the `inject-scala-test` source trees,
  publishes `micronaut-inject-scala-test_<scalaVersion>`, and **runs the test
  suite**.
- `micronaut-scala-bom/` — the published platform.

The Scala compiler version comes from one place, `gradle/libs.versions.toml`
(`scala3`), and is crossed into the artifact IDs because compiler-plugin APIs
are not binary compatible across compiler releases. Gradle project names stay
version-free: Core enables `TYPESAFE_PROJECT_ACCESSORS` across the whole
composite, and it rejects project names containing a dot.

Micronaut Build's `useStandardizedProjectNames` prefixes every project with
`micronaut-`, so task paths are `:micronaut-inject-scala-compiler:jar`,
`:micronaut-inject-scala-test-compiler:test`, and so on — not the directory
names above.

## Building And Testing

The build requires **JDK 25**. Set `LANG=C.UTF-8` (or another UTF-8 locale) or
settings evaluation fails on a non-ASCII test resource inside micronaut-core.

```
export JAVA_HOME=/path/to/jdk-25
export LANG=C.UTF-8 LC_ALL=C.UTF-8
./gradlew -PincludeMicronautCore=true :micronaut-inject-scala-test-compiler:test
```

`-PincludeMicronautCore=true` is currently **required**: the catalog pins
`micronaut-core = "5.2.0-SNAPSHOT"`, which is published nowhere, so the build
clones micronaut-core `5.2.x` into `checkouts/` via IncludeGit and builds it
from source. The first such build is slow. To develop against a local Core
checkout instead, use `-Plocal.git.micronaut-core=/path/to/micronaut-core`; to
select a different Core branch, use `-PmicronautCoreBranch=<branch>`.

- `./gradlew check` for general validation.
- `./gradlew verifyCompilerArtifacts` for the packaging and publication guards.
- `./gradlew publishGuide` (or `pG`) after guide or `toc.yml` changes;
  `./gradlew docs` when Javadoc output matters.

There is no `doc-examples/` module and there are no native or Testcontainers
tests. The test harness runs `dotty.tools.dotc.Main` in-process; Docker is
needed only by the vulnerability-audit script.

## Working On The Plugin

- The compiler plugin is named `micronaut-scala`, registered through
  `inject-scala/src/main/resources/plugin.properties`. Its options are therefore
  spelled `-P:micronaut-scala:<key>=<value>`.
- It inserts two phases: `micronaut-scala-type-visitors` (after `PostTyper`) and
  `micronaut-scala-bean-definitions` (before `Pickler`). Both are useful targets
  for `-Xprint` when troubleshooting.
- Add-on `TypeElementVisitor`s are discovered by `ServiceLoader` over the
  **compile classpath**, not an `annotationProcessor` configuration. This is a
  materially different model from javac/kapt.
- The only runtime contribution is `ScalaCollectionConverterRegistrar`.
- Tests drive `dotc` with `-Xplugin:<the built jar>` through two system
  properties (`micronaut.scala.plugin.jar`, `micronaut.scala.test.classpath`)
  that this repository's build sets.
- Behaviour claims about dotty must be checked against the compiler sources for
  the pinned release, not assumed:
  `org.scala-lang:scala3-compiler_3:<scala3>:sources`. Composite `FlagSet`s in
  particular need `isAllOf`, not `isOneOf`.

## Planning Documents

- `SCALA3_SUPPORT_PLAN.md` — compatibility policy, release sequencing,
  packaging rules.
- `SCALA3_REMEDIATION_PLAN.md` — the reviewed gap analysis and the wave-ordered
  work plan. Findings are marked *confirmed* or *suspected*; re-verify the
  suspected ones against primary sources before acting.
- `inject-scala-test/DISABLED_TESTS.md` — parity coverage against the Java,
  Groovy and Kotlin suites, with the P-1..P3 priority buckets.

## Contributing Guidelines

- Before opening or updating a pull request, read `CONTRIBUTING.md` and follow
  every repo-specific requirement it names.
- Run the test task after every change; do not push a red build.
- When a fix changes observable behaviour, add or update a test that pins it,
  and say so in the commit message.

## Documentation

- User guide sources live in `src/main/docs/guide`, with navigation in
  `src/main/docs/guide/toc.yml`.
- Release-note behaviour is maintained through `.github/release.yml`,
  `.github/workflows/release.yml`, and the process in `MAINTAINING.md`.
