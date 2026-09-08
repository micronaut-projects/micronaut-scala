# Scala 3 support: gap analysis and remediation plan

Companion to `SCALA3_SUPPORT_PLAN.md` (which covers compatibility policy, release
sequencing and packaging rules) and to `inject-scala-test/DISABLED_TESTS.md`
(which tracks parity-test coverage). This document records what a full review of
the implementation found, and the order in which it should be fixed.

Reviewed at commit `04c3d4f` on `codex/scala-3.3.8-support`.

## How this was produced

Four independent reviews of the checkout:

1. the dotty plugin driver and phase model (`MicronautScalaCompilerPlugin.scala`,
   `ScalaProcessingEngine`);
2. the Micronaut Element API adapter (`ScalaClassElement`, `ScalaLoadedClassElement`
   and the member/type elements);
3. the annotation-metadata pipeline (`ScalaAnnotationMetadataBuilder`,
   `ClasspathAnnotationMetadataReader`, `ScalaCollectionConverterRegistrar`);
4. build, packaging, publication, CI and documentation.

Findings marked **confirmed** were verified against primary sources
(`scala3-compiler_3:3.9.0` sources, the checked-in code). Findings marked
**suspected** are strongly evidenced by reading but need a test to settle.

---

## Executive summary

The implementation is substantial and in good shape structurally: the extraction
phase reduces dotty trees to plain records, the Element API and annotation
builder are then ordinary Java against Micronaut's SPI, and the 220-feature
Spock suite covers a genuinely wide surface. The problems are concentrated in
five areas.

1. **Flag testing is wrong.** Four uses of composite dotty `FlagSet`s go through
   `isOneOf` (intersection) where they need `isAllOf` (containment). Confirmed
   against the 3.9.0 compiler sources. Every Java class on the classpath is
   currently classified as an interface.
2. **The pipeline is driven by a unit-count heuristic** that can silently skip
   every `TypeElementVisitor` while still generating bean definitions, and that
   does not survive macro-suspended units or a second `Run`.
3. **Errors carry no source position.** The whole diagnostic channel is
   `Consumer<String>`, so `ProcessingException`'s originating element is
   discarded before it reaches `report.error`.
4. **Classpath types are a second, weaker pipeline.** `ScalaLoadedClassElement`
   is reflective, its annotation metadata bypasses
   `AbstractAnnotationMetadataBuilder` entirely (so no stereotypes, no aliases,
   no mappers, no defaults), and it disagrees with the source elements on
   modifiers, equality, generics and enum-ness.
5. **Packaging and publication are not releasable.** The fat jar is unrelocated
   while the POM still declares the same dependencies, `micronaut-core` is
   pinned to an unpublished snapshot, and the one packaging guard
   (`verifyCompilerArtifacts`) never runs and contains two assertions that can
   never fire.

Documentation is effectively absent for users: 51 lines of quick start, no
working build file for any tool, and the sbt/Mill advice as written produces a
double version suffix.

---

## A. Confirmed correctness defects

### A1 (BLOCKER, confirmed) Composite `FlagSet`s tested with `isOneOf`

`inject-scala/src/main/scala/io/micronaut/scala/processing/MicronautScalaCompilerPlugin.scala:1645`

```scala
private def hasFlag(symbol: Symbol, flag: Flags.FlagSet)(using Context): Boolean =
  symbol != Symbols.NoSymbol && symbol.denot.isOneOf(flag)
```

`isOneOf` is defined in `dotty/tools/dotc/core/Flags.scala:73` as a non-empty
intersection. Four call sites pass *conjunction* sets, which dotty itself always
tests with `isAllOf`:

| Site | Set | Definition in 3.9.0 | Effect of `isOneOf` |
| --- | --- | --- | --- |
| `:1614` | `Flags.JavaInterface` | `JavaDefined \| NoInits \| Trait` | true for **every** `JavaDefined` symbol, so `isInterfaceSymbol` reduces to `hasFlag(symbol, Flags.JavaDefined)` |
| `:1595` | `Flags.JavaEnumValue` | `JavaDefined \| EnumValue` | true for every `JavaDefined` symbol |
| `:1594` | `Flags.EnumValue` | `Enum \| StableRealizable` | true for every stable val |
| `:1596` | `Flags.EnumCase` | `Case \| Enum` | true for every case-class member |

Downstream consequences: in `toClassData` (`:468`) and `typeHierarchy` (`:921`)
a Java superclass is recorded as an interface and `superType` becomes `null`, so
the annotation hierarchy walk and `ClassElement.getSuperType()` are wrong for
every Scala class extending a Java class; `isEnumConstant` (`:1592`) misfires in
`annotationValue` (`:1360`), turning ordinary `Select` trees into enum-constant
names.

**Fix.** Add a second helper and use it for conjunction sets:

```scala
private def hasAllFlags(symbol: Symbol, flags: Flags.FlagSet)(using Context): Boolean =
  symbol != Symbols.NoSymbol && symbol.denot.isAllOf(flags)
```

Keep `isOneOf` only for the deliberate union at `:1633`
(`Flags.Synthetic | Flags.Artifact | Flags.Accessor`). Add a regression test that
asserts a Scala class extending a classpath Java class reports that class as its
super type, not as an interface.

### A2 (BLOCKER, confirmed) Pipeline gated on a compilation-unit count

`MicronautScalaCompilerPlugin.scala:188`, `:205`

```scala
if !typeVisitorsProcessed && typeUnitsSeen >= unitCount then
```
```scala
private def unitCount(using ctx: Context): Int =
  math.max(1, ctx.run.units.size)
```

`Phase.runOn` skips units for which `ctx.run.enterUnit(unit)` is false, so
`typeUnitsSeen` can never reach `ctx.run.units.size`. When that happens
`processTypeVisitors()` never runs, but `BeanDefinitionPhase.run` (`:278`) still
calls `processBeanDefinitions()`. The result is bean definitions generated with
no visitor having run: no AOP, no introspections, no validation, no user
visitors, and no diagnostic.

**Fix.** Drive the pipeline from a point that is guaranteed to run once after all
units — override `runOn(units)` rather than `run` — and make
`processBeanDefinitions()` unconditionally ensure `processTypeVisitors()` has
run first, so the ordering invariant cannot be violated by any future change.

### A3 (WITHDRAWN -- not a defect) State is not keyed per `Run`

This finding was wrong and no fix is needed. Re-checked against the 3.9.0
compiler sources while implementing A2.

The claim was that plugin phase instances are memoised in `ContextBase`, so the
`ProcessingState` booleans would already be `true` for the second `Run` that
`Driver.finish` creates for macro-suspended units, and those units would be
collected and silently discarded.

What is memoised on `ContextBase` is the `Plugin` *instance* (`Plugins._plugins`),
not the phases or their state. `Run.compileUnits` calls
`ctx.base.addPluginPhases(...)` (`Run.scala:379`), which calls
`plug.initialize(options)` -> `init(options)` on every run, and this plugin's
`init` (`MicronautScalaCompilerPlugin.scala:73`) constructs a fresh
`MicronautScalaPluginClassLoader`, a fresh `MicronautScalaCompilerPluginImpl` and
therefore a fresh `ProcessingState` and `ScalaProcessingEngine` each time. The
second `Run` starts clean.

The genuine costs in this area are a new classloader and a fully reloaded
Micronaut adapter per run -- see B4 (classloader leaks) and B6 (static caches),
which remain open. The correction does not affect A2, which was confirmed and is
fixed independently.

### A4 (BLOCKER, confirmed) `withTypeArguments` discards generics

`inject-scala/src/main/java/io/micronaut/scala/processing/visitor/ScalaClassElement.java:719`

```java
public ClassElement withTypeArguments(Map<String, ClassElement> typeArguments) {
    return this;
```

Micronaut calls this wherever it resolves generics (`foldBoundGenericTypes`,
factory return-type resolution, `AstBeanPropertiesUtils`). Returning `this` hands
the caller the unsubstituted type with no way to detect it. The classpath side
implements it correctly (`ScalaLoadedClassElement.java:300`), so source and
classpath elements disagree.

**Fix.** Add `ScalaTypeData.withTypeArguments(...)` alongside the existing
`withArrayDimensions`, and build a new `ScalaClassElement` that preserves
`classData` so members survive the copy.

### A5 (BLOCKER, confirmed) `MethodElement.overrides` and `hides` always return false

`ScalaMethodElement.java:218`, `:223`

```java
public boolean overrides(MethodElement overridden) {
    return false;
}
```

`BeanDefinitionCreatorFactory` uses `overrides` to merge annotation metadata from
the overridden parent method, to avoid emitting a second injection point, and for
interception decisions. An `@Inject`/`@PostConstruct`/`@Executable` method
overridden in a subclass is currently treated as two unrelated methods.

**Fix.** Implement as name plus erased parameter-signature match, guarded on
`!isPrivate() && !isStatic()` and on the declaring types being related. The
`MethodSignature`/`TypeSignature` records at `ScalaClassElement.java:744` already
provide the comparison key.

### A6 (BLOCKER, confirmed) Inherited members from classpath supertypes are invisible

`ScalaClassElement.java:455`

```java
Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(type.name());
if (sourceElement.isEmpty()) {
    return;
}
```

`collectInheritedMethods` gives up unless the supertype is in the current
compilation. A Scala bean extending an abstract class or trait from another
module reports none of its inherited methods: no inherited `@Inject` setters, no
inherited `@Executable`/`@Around` methods, no inherited abstract methods for
introspection. `ALL_FIELDS` is worse — `addFieldElements` (`:596`) never walks
the hierarchy at all, so inherited `@Inject`/`@Value` fields and inherited
`@ConfigurationProperties` state are always missed.

**Fix.** Mirror the method walk for fields, honouring `isOnlyDeclared()` — **done**.
Fields are de-duplicated by name, and an inherited field's type is resolved against
the parameterisation the subtype used.

The classpath half — falling back to `visitorContext.getClassElement(type.name())`
and merging its enclosed elements — was implemented, tested and reverted, and is
**blocked on A7 and A18**. Those elements are reflective and immutable, so any
visitor that annotates an inherited method fails outright with *"Element of type
[MethodElement$1] does not support adding annotations at compilation time"*. Seven
existing specs fail that way, all of them visitors annotating inherited
introduction or bean methods. Merging in `ScalaLoadedClassElement`'s members also
drags in `scala.Product`, `scala.Equals` and `java.io.Serializable` surface that
the source path never produces, which is the `excludedHierarchyType` item in B15.
This ordering needs to change: A6's classpath half belongs *after* A7 and A18, not
before them.

Note also that Scala does not permit field shadowing — neither `override var` nor
a `private var` of the same name compiles when the superclass field is visible —
so the shadowing rule is only reachable through a Java or classpath supertype.

### A7 (BLOCKER, confirmed) Classpath annotation metadata bypasses the metadata builder

`ClasspathAnnotationMetadataReader.java:156`

```java
return new AnnotationValueVisitor(descriptor, annotationValue ->
    annotationMetadata.addDeclaredAnnotation(
        annotationValue.getAnnotationName(),
        annotationValue.getValues(),
        visible ? RetentionPolicy.RUNTIME : RetentionPolicy.CLASS
    )
);
```

Raw ASM output goes straight into a `MutableAnnotationMetadata`. Nothing walks
meta-annotations, so on a classpath type `hasStereotype(AnnotationUtil.SCOPE)`
and `hasStereotype(AnnotationUtil.QUALIFIER)` are false, `@AliasFor` never
resolves, the `AnnotationMapper`/`AnnotationTransformer`/`AnnotationRemapper` SPI
never runs, repeatable containers are not unwrapped, and defaults are absent.
The same type inspected from source and from the classpath gives different
answers.

**Fix — done for declared annotations.** The ASM reader now caches the *raw*
annotation values rather than finished metadata -- resolution depends on the
annotation types of the compilation currently running, and that cache is shared
across compilations -- and `LoadedAnnotatedElementData` adapts them into
`ScalaAnnotationData`, resolving each annotation type through the compiler (A11)
rather than by re-reading class files. `ScalaLoadedClassElement` then builds its
class, method, constructor, field and parameter metadata through
`ScalaAnnotationMetadataBuilder`, so both element kinds share one pipeline.

Reproduced before fixing: `@ExternalInheritedSingleton` on a classpath type gave
`hasStereotype(SCOPE) == false` while the identical annotation on a source type
gave true. Pinned by `ScalaClasspathMetadataSpec`, which asserts both that the
stereotype resolves and that the two element kinds agree.

Still open: annotations *inherited* from a classpath supertype. `buildHierarchy`
only walks a hierarchy for `ScalaClassData`, and a loaded element is adapted as a
flat member, so this covers declared annotations only. That is the same boundary
as A6's classpath half and belongs with it and A18.

### A8 (BLOCKER, confirmed) No source positions on any diagnostic

`MicronautScalaCompilerPlugin.scala:215`

```scala
message => report.inform(message),
message => report.warning(message),
message => report.error(message)
```

The reporter channel is `Consumer<String>`. `VisitorContext.fail(message, element)`
reports and throws, but the element never reaches `report.error`, which then uses
`NoSourcePosition`. Every Micronaut error — `@Inject` on a final field, invalid
`@ConfigurationProperties`, any visitor `fail(...)` — is emitted with no file,
line or caret. `reportProcessingException` (`:232`) drops the element too. The
information exists: every data record carries a `nativeType` that is a `tpd.Tree`
or `Symbol` with a usable `srcPos`.

**Fix.** Widen the reporter to `BiConsumer<String, Object>`, resolve `srcPos`
from the native type in the plugin, and pass it to `report.error`/`report.warning`.
Then extend the harness (see C1) so tests can assert on positions.

### A9 (MAJOR, confirmed) Non-constant annotation values become printed tree text

`MicronautScalaCompilerPlugin.scala:1366`

```scala
case _ =>
  renderedClassLiteralValue(tree.show).map(name => classValueData(name)).getOrElse(tree.show)
```

Any annotation argument the extractor does not understand is stored as the
compiler's *display string*. In Scala 3 a plain `val BAR = "x"` in an `object`
has no `ConstantType`, so `@Value(Foo.BAR)` silently stores the literal string
`"Foo.BAR"` and bakes it into the bean definition. `tree.show` can also embed
ANSI escapes — `renderedClassLiteralValue` (`:1552`) strips them, which is an
admission that this depends on printer formatting rather than an API contract.

The mirror-image defect is at `:1405` and `:1432`: any `String` constant whose
*content* contains `classOf` is converted into an `AnnotationClassValue`, so
`@Value("classOf[Int] is the tag")` becomes a class reference.

**Fix.** Handle the remaining tree shapes structurally (`Ident`/`Select` to a
`ConstantType`, `Inlined`, `Block`, array construction), and report an error at
the argument position for anything left over. Delete every
`renderedClassLiteralValue` fallback; detect class literals from the tree
(`isClassOf`, already present at `:1479`), never from string content.

### A10 (MAJOR, confirmed) Annotation defaults exist only for the current compilation unit

`MicronautScalaCompilerPlugin.scala:349`

```scala
given AnnotationDefaults = AnnotationDefaults(annotationDefaultValues(unit.tpdTree))
```

Defaults are harvested by scanning the current unit's trees for
`$lessinit$greater$default$N`. So an annotation declared in another file of the
same compilation has no defaults, and every classpath annotation
(`@Requires`, `@Property`, `@Executable`, `@Bean`, `@ConfigurationProperties`)
has none at all. `ClasspathAnnotationMetadataReader` compounds this: its
`MethodVisitor` (`:247`) never overrides `visitAnnotationDefault`.

**Fix — done, in two parts, and not the way the finding proposes.**

Defaults for annotations declared in this compilation are now collected from
*every* unit before any unit is extracted, which the move to `runOn` made
possible. Previously an annotation picked up its defaults only when it was
declared in the same file as its use.

Resolving defaults from the annotation *symbol* does **not** work for classpath
annotations, contrary to the finding: dotty's classfile parser records only a
marker for the attribute and discards the value
(`ClassfileParser.scala:998` adds `defn.AnnotationDefaultAnnot` with no value), so
the value is not on the symbol to be read. Only the other half of the proposed fix
reaches them, and that is what is implemented -- `visitAnnotationDefault` in the
ASM reader, with the defaults merged into the annotation type when it is
registered with the metadata builder. That is the single point both extracted and
on-demand-resolved types pass through.

Note the two kinds surface differently, and both are correct: Scala passes a
synthetic default-getter argument for each omitted member, so a Scala annotation's
defaults are resolved into the annotation's *values*, while a Java annotation's
stay in its default values -- which is what inject-java produces too.

Pinned by `ScalaAnnotationDefaultsSpec`.

### A11 (MAJOR, confirmed) `getAnnotationMirror` makes metadata order-dependent

`ScalaAnnotationMetadataBuilder.java:270`

```java
protected Optional<Object> getAnnotationMirror(String annotationName) {
    return Optional.ofNullable(nativeAnnotationTypes.get(annotationName))
        .map(nativeType -> new AnnotationTypeElement(annotationName, nativeType));
}
```

`nativeAnnotationTypes` is populated lazily as elements are visited. The
superclass uses this mirror to resolve stereotypes for annotations added
programmatically (`element.annotate(...)`, mappers, `AbstractBeanDefinitionBuilder`).
If the type has not been seen yet, no meta-annotations are processed — so
`annotate(Executable.class)` can produce a bare `@Executable` with no stereotypes,
and whether it does depends on compilation-unit order. `getRepeatableContainerNameForType`
(`:265`) and `getRetentionPolicy` (`:306`) have the same shape.

**Fix — done.** The plugin now exposes `resolveAnnotationType(name)`, which looks the
type up through the dotty context and builds a `ScalaAnnotationTypeData` from the
symbol. The builder calls it whenever a name is not already in
`nativeAnnotationTypes`, registering the result so the type's own meta-annotations
and members are pulled in, and remembering names that do not resolve so the
compiler is asked only once each.

Reproduced before fixing: a visitor adding `@Location` -- a fixture annotation
meta-annotated `@Qualifier` -- to a class in a compilation that never mentions it
gave `hasStereotype(QUALIFIER) == false`. It is now true, and the annotation is
additionally wrapped in its `@Repeatable` container, which also depended on the
mirror. Pinned by `ScalaAnnotationMirrorSpec`.

Member defaults are not available through this path, since those are harvested per
compilation unit -- that is A10, still open.

### A12 (MAJOR, confirmed) Retention defaults to RUNTIME, and `valueOf` is unguarded

`ScalaAnnotationMetadataBuilder.java:305`

```java
if (nativeType != null && nativeType.retentionPolicyName() != null) {
    return RetentionPolicy.valueOf(nativeType.retentionPolicyName());
}
return RetentionPolicy.RUNTIME;
```

The JLS default is `CLASS`. A `StaticAnnotation` subclass or a Java annotation
with no `@Retention` is treated as `RUNTIME` and written into the bean
definition. `RetentionPolicy.valueOf` is also unguarded on a value that, by A9,
can be rendered tree text, and will throw `IllegalArgumentException` out of the
plugin. `@Target` is captured nowhere at all — which matters more in Scala than
in Java, because Scala copies an annotation on a `val` onto the field, the getter
and the constructor parameter unless meta-annotations restrict it.

**Fix — retention done, `@Target` still open.** `valueOf` is guarded, and the
default is now `CLASS` *for Java annotation types only*.

Defaulting everything to `CLASS`, as the finding says, is wrong and breaks a real
case: a Scala annotation is a class extending `StaticAnnotation`, not a Java
annotation type, and has no JLS retention at all -- it exists purely as
compile-time metadata that Micronaut bakes into the generated definition. Applying
the JLS default to it made `@Qualifier class MyQualifier extends StaticAnnotation`
stop being a qualifier, which the existing suite caught. `ScalaAnnotationTypeData`
therefore carries `javaDefined`, and only Java annotation types get `CLASS`.

Pinned by `ScalaRetentionSpec`, which asserts all three: a Java annotation with no
retention is not written into the bean definition, one declaring `RUNTIME` is, and
a Scala annotation with no retention still reaches the runtime.

`@Target` is still not captured. That remains worth doing -- it matters more in
Scala than in Java, because Scala copies an annotation on a `val` onto the field,
the getter and the constructor parameter unless meta-annotations restrict it.

### A13 (MAJOR, confirmed) Field annotations are dropped when the getter has any annotation

`MicronautScalaCompilerPlugin.scala:570`

```scala
if readMethod != null && !readMethod.annotations().isEmpty then
  readMethod.annotations()
else if field != null then
  field.annotations()
```

For `@(Inject @getter) @(Named @field)("x") val foo`, the getter carries
`@Inject`, so `@Named` on the field is discarded. This is an either/or where it
must be a union.

**Fix — applied, but the described symptom does not reproduce.** The two lists are
now unioned, de-duplicated by name with the getter winning a clash, so the record
holds what it claims to.

Nothing observable changed, and on inspection nothing could: `ScalaPropertyElement`
builds its metadata through Micronaut's own `PropertyElementAnnotationMetadata`,
which already composes the read method, the write method *and* the field. The
either/or in `propertyAnnotations` was therefore masked on every public path --
`getBeanProperties()` and `getEnclosedElements(PropertyElement)` alike, both
checked against the previous code.

### A14 (MAJOR, confirmed) Overloaded methods collide in the accessor map

`MicronautScalaCompilerPlugin.scala:440`

```scala
val methodByName = LinkedHashMap[String, ScalaMethodData]()
allMethods.foreach { method =>
  if !skipAccessorCandidate(method.symbol) then
    methodByName.put(method.name.toString, methodData(method, constructor = false, owner = symbol))
```

Keyed by simple name, so `def value: String` and `def value(i: Int): String`
collide and the last wins, giving the property the wrong type, modifiers and
annotations. The same loop also computes `methodData` twice per method (again at
`:450`).

**Fix — applied, but the described symptom does not reproduce.** The accessor map
now takes only accessor-shaped methods (zero-argument reads, one-argument `_=`
writes) and the record is computed once instead of twice per method.

The collision itself could not be reproduced, and on reading the code it cannot
occur today: the `declarations` pass runs *after* the body pass and re-registers
the accessor for anything that can become a property, overwriting whatever an
overload put there. `isPropertyDeclaration` requires a non-`Method` symbol, so a
`def`-only property never reaches the property model at all. The narrowing is
therefore a guard against a latent defect rather than a fix for a live one; the
duplicate `methodData` call it removes was real.

### A15 (MAJOR, confirmed) Only the primary constructor is modelled

`MicronautScalaCompilerPlugin.scala:462`

```scala
val constructors = List(methodData(template.constr, constructor = true, owner = symbol))
```

Every Scala secondary constructor (`def this(...)`) is dropped, so `@Inject` on
one cannot be honoured and `getDefaultConstructor()` returns empty for a class
whose only no-arg constructor is secondary. The classpath side enumerates all
constructors (`ScalaLoadedClassElement.java:197`) and picks the primary one by a
*different* rule (public-then-fewest-params) than the source side
(`classData.constructors().get(0)`, `ScalaClassElement.java:356`).

**Fix.** Collect `template.body` `DefDef`s whose symbol `isConstructor`, and
align the two primary-constructor selection rules.

### A16 (MAJOR, confirmed) Qualified private/protected mapped as JVM private/protected

`MicronautScalaCompilerPlugin.scala:1600`

```scala
if hasFlag(symbol, Flags.Private) then modifiers.add(ElementModifier.PRIVATE)
if hasFlag(symbol, Flags.Protected) then modifiers.add(ElementModifier.PROTECTED)
```

`private[pkg]` and `protected[pkg]` carry those flags but compile to public JVM
members, so Micronaut refuses to inject or introspect members it could legally
call. `SEALED`, `TRANSIENT`, `VOLATILE`, `SYNCHRONIZED` and `NATIVE` are not
mapped at all on the source path while `ScalaLoadedClassElement.java:512` maps
them faithfully — another source/classpath divergence.

**Fix — done, but the finding was half wrong.** Checked against the emitted
bytecode rather than assumed:

| declaration | bytecode | was reported |
| --- | --- | --- |
| `private def` | private | private (correct) |
| `private[pkg] def` | public | public (already correct) |
| `protected def` | **public** | protected (wrong) |
| `protected[pkg] def` | **public** | protected (wrong) |

`private[pkg]` was never affected: dotty leaves `Flags.Private` unset for a
qualified private, so no `privateWithin` check is needed. The real defect is
wider than the finding says — *plain* `protected` is also emitted as public,
because Scala's `protected` means subclass-only and JVM `protected` additionally
grants package access, so scalac emits public and enforces at compile time.
`PROTECTED` is now emitted only for a `JavaDefined` symbol, which really is
JVM-protected. `SEALED` is now mapped too.

`TRANSIENT`, `VOLATILE`, `SYNCHRONIZED` and `NATIVE` are still unmapped: in Scala
these come from `@transient`/`@volatile` annotations rather than symbol flags, so
they need their own verification against emitted bytecode before being mapped.

### A17 (MAJOR, confirmed) Every Scala field claims to require reflection and to be private

`ScalaFieldElement.java:73`, `:88`

```java
public boolean isReflectionRequired() {
    return true;
}
```
```java
fieldModifiers.remove(ElementModifier.PUBLIC);
fieldModifiers.remove(ElementModifier.PROTECTED);
fieldModifiers.add(ElementModifier.PRIVATE);
```

`isReflectionRequired(ClassElement callingType)` ignores its argument entirely,
so Micronaut emits reflective field access and GraalVM reflection metadata even
where direct access is possible. Forcing `PRIVATE` means `isPublic()` is false
for every field, `ElementQuery.ALL_FIELDS.modifiers(...)` can never match a
public field, and `PropertyElementQuery` visibility filtering rejects
field-backed properties.

**Fix — done.** `isReflectionRequired(callingType)` is now
`!isAccessible(callingType)`, and `ScalaFieldElement` keeps the modifier set it is
given. The backing-field rule moved into the extractor, where it belongs: a Scala
`val`/`var` really is emitted as a private field plus accessors whatever the
declaration's visibility says, but that is a fact about *those* declarations, not
about fields in general — and forcing it in the element made every synthetic field
private too, which is what blocked `object` support in B11.

### A18 (MAJOR, confirmed) Classpath enumeration uses `getMethods()`/`getFields()`

`ScalaLoadedClassElement.java:224`, `:227`, `:310`

```java
Method[] methods = result.isOnlyDeclared() ? componentType.getDeclaredMethods() : componentType.getMethods();
```

Three defects: the non-declared branch returns public members only (so
protected, package-private and — critically for field injection — private
declared members vanish); it includes `java.lang.Object`'s public methods, which
the source path never produces; and `methodElement`/`fieldElement` pass
`this, this` as owning *and* declaring type, so an inherited method reports the
subclass as its declaring type, defeating inheritance and override logic.

**Fix — done.** The hierarchy is walked explicitly with `getDeclaredMethods()`/
`getDeclaredFields()`, stopping before `java.lang.Object`, de-duplicating methods by
name and erased parameter types and fields by name, and each element is given the
class element that actually declares it. Constructors always use
`getDeclaredConstructors()`, since constructors are not inherited and the previous
non-declared branch was additionally hiding the non-public ones.

Measured before and after on a two-level classpath fixture. Before: `ALL_METHODS`
returned two of the four real methods plus eight `java.lang.Object` methods, all
claiming the queried class as their declaring type; `ALL_FIELDS` returned *one*
field -- the public inherited one -- omitting even the class's own private field,
which is what made field injection on a classpath type impossible. After: all four
methods with their real declaring types, no `Object` methods, and all four fields.

Pinned by `ScalaClasspathEnumerationSpec`; all four cases fail against the previous
code.

---

## B. Robustness, isolation and resource handling

### B1 (MAJOR) One failing visitor aborts the rest, and reports twice

`ScalaProcessingEngine.java:133` catches `Throwable` from `visitor.start()` and
calls `context.fail(...)`, which reports *and throws*. The throw escapes the loop
so no later visitor is started, then `ProcessingState` catches the
`ProcessingException` and reports the identical message a second time. The
comment claims parity with inject-java, but inject-java's reporting methods do
not throw.

**Fix.** Report without throwing and continue to the next visitor.

### B2 (MAJOR) Unhandled exceptions crash the compiler instead of reporting

`ScalaProcessingEngine.java:229` throws `IllegalStateException` for a missing
originating element; the caller (`:196`) catches only `ProcessingException` and
`IOException`, and `ProcessingState.processBeanDefinitions` catches only
`ProcessingException`. The result is a compiler crash stack trace rather than a
diagnostic. `ScalaVisitorContext.sourceClassElement(...).orElseThrow()`
(`ScalaProcessingEngine.java:146`, `:185`) has the same shape and additionally
throws a bare `NoSuchElementException` with no message.

**Fix.** Convert to `ProcessingException` with the offending class element, and
catch `RuntimeException` in the per-class loop.

### B3 (MAJOR) Service files may be lost when a visitor fails

If a visitor calls `context.fail(...)`, the throw skips `processBeanDefinitions()`,
and because dotty's `Phase.isRunnable` is false once errors are reported,
`BeanDefinitionPhase` is skipped too. `context.finish()` and
`BeanDefinitionWriter.finish()` never run, so `META-INF/services` entries are not
written for definitions that *were* generated. Conversely, visitor errors that
only report (without throwing) let generation proceed for a compilation that has
already failed.

**Fix.** Check `ctx.reporter.hasErrors` before generating, and always run
`context.finish()` in a `finally`.

### B4 (MAJOR) Classloader and file-handle leaks

Three unclosed `URLClassLoader`s per compilation: the plugin's own
(`MicronautScalaCompilerPlugin.scala:85`), the visitor-context classpath loader
(`ScalaVisitorContext.java:116`), and the test harness's
(`ScalaCompiler.java:273`). The middle one holds an open handle on every jar of
the compile classpath. In a Gradle Scala compile daemon this is metaspace growth
plus jar-handle exhaustion, and on Windows it prevents deleting jars.

**Fix.** Make `ScalaVisitorContext` `Closeable` and close in `finish()`; cache the
plugin loader statically rather than creating one per `init`; make the harness's
`Compilation` `AutoCloseable`.

### B5 (MAJOR) The "isolated" plugin classloader isolates nothing

`MicronautScalaCompilerPlugin.scala:92`

```scala
private val parentLoadsJavaCompiler = canLoadFromParent("javax.lang.model.element.Modifier")
```

That probe always succeeds (the `java.compiler` module is defined to the platform
loader), so every package in `isParentMicronautApi` is parent-first. The child
loader therefore loads only `io.micronaut.scala.processing.**` plus a handful of
unlisted `io.micronaut.*` packages — a *partial* duplicate of the Micronaut
packages, which is exactly the setup that produces surprising `LinkageError` when
an object crosses the boundary. The jar is already self-contained on the
compiler's plugin loader.

**Fix.** Either drop the child loader, or make it genuinely child-first for all
`io.micronaut.**` and parent-first only for `scala.`/`dotty.`/`java*.`. The
current halfway state has the costs of both.

### B6 (MAJOR) JVM-global system properties and unclear static caches

`ScalaProcessingEngine.java:372` promotes every `micronaut.*` option to a JVM
system property and never restores it, so in a shared Scala compile daemon one
project's `micronaut.processing.*` settings leak into every later compile and
race between concurrent compiles. `AbstractAnnotationMetadataBuilder`'s static
caches are never cleared (inject-java clears them per round), and
`ClasspathAnnotationMetadataReader.CACHE` (`:60`) hands out live
`MutableAnnotationMetadata` instances.

**Fix.** Snapshot and restore the properties around processing; clear the
builder's static caches when the engine finishes; return copies from the ASM
cache.

### B7 (MAJOR) Visitor dispatch is visitor-major, not class-major

`ScalaProcessingEngine.java:139` iterates visitors in the outer loop and classes
in the inner loop; inject-java does the opposite. For classes A, B and visitors
high, low, Java produces `high:A, low:A, high:B, low:B` and Scala produces
`high:A, high:B, low:A, low:B`. Aggregating visitors that accumulate per-class
state behave differently. `ScalaVisitorOrderingSpec` compiles one class and so
cannot distinguish the two.

**Fix.** Invert the loops to match `TypeElementVisitorProcessor`.

### B8 (MAJOR) `new LinkedHashSet<>(sourceClasses.values())` deep-hashes the whole model

`ScalaProcessingEngine.java:142`, `:181`. `ScalaClassData` is a record whose
components include `Object nativeType` — a dotty `tpd.TypeDef`, itself a Scala
case class — so building the set structurally hashes the entire typed tree of
every class, once per visitor plus once for bean definitions, with real recursion
risk on large classes. `sourceClasses` is already a `LinkedHashMap` keyed by
name, so the values are distinct and ordered.

**Fix.** Iterate `sourceClasses.values()` directly, or `List.copyOf(...)` for a
snapshot.

### B9 (MAJOR) `-d` pointing at a jar silently writes to a directory

`MicronautScalaCompilerPlugin.scala:222` takes `ctx.settings.outputDir`'s path and
`mkdirs()` it. `outputDir` may be a jar (`-d out.jar` is supported by scalac), in
which case generated definitions and `META-INF/services` never enter the jar and
the application silently has no beans. The `mkdirs()` result is ignored.

**Fix.** Detect a jar/virtual output and either write through the compiler's
`AbstractFile` API or fail with a clear message.

### B10 (MAJOR) No incremental-compilation story

Bean definitions are written straight into the class output directory behind the
compiler's back, so zinc/Gradle never records them as products: they are not
cleaned when the source bean is deleted, not invalidated when it changes, and on
a partial recompile `META-INF/services/io.micronaut.inject.BeanDefinitionReference`
is regenerated from only the recompiled subset. There is no equivalent of javac's
incremental-annotation-processing registration and no `micronaut.processing.incremental`
handling. Additionally the generation phase runs before `Pickler`, so a failing
compile leaves orphaned `$Foo$Definition.class` files that the next compile can
load — `ScalaVisitorContext.createClassLoader` puts the output directory first on
the visitor classpath.

**Fix.** Verify `DirectoryClassWriterOutputVisitor.finish()`'s merge behaviour
against a pre-existing service file and read-merge-write if it truncates; move
generation after `GenBCode`; document that a clean build is required until this
is properly incremental.

### B11 (MAJOR) Scala `object` is excluded from processing

`MicronautScalaCompilerPlugin.scala:1623` skips every `ModuleClass`, so
`@Singleton object Foo`, `@Factory object Beans` and top-level `given`/`def`s
(which live in the synthetic `Foo$package` module class) produce no metadata at
all. `object` is *the* idiomatic Scala singleton, so this is a first-order
functional gap rather than an edge case. Worse, `collectTree` (`:363`) still
descends into a skipped module class and derives `enclosingTypeName` from
`companionClassName` — the module name with `$` stripped — so a class nested in a
companion-less `object Foo` gets an enclosing type that does not exist.

**Fix — done; objects are supported as beans.** A module class carrying a
user-written annotation is now extracted. An `object` compiles to a class with a
*private* constructor plus a public static `MODULE$` holding the one instance
(verified from the emitted bytecode), so Micronaut cannot construct it: it is
modelled as a `@Factory` whose synthetic `MODULE$` field carries the object's own
annotations and produces the bean. The injected bean is therefore the same
instance Scala code reaches through `Config`, which a constructed bean would not
be.

Two things this required. `DeclaredBeanElementCreator` always emits the factory
class's own bean definition, which here has the same bean type as the produced one
and would be built through the private constructor, so resolution failed with
`NonUniqueBeanException`; that one definition is suppressed for module classes.
And the gate cannot be "has annotations": dotty attaches
`scala.annotation.internal.SourceFile` to *every* module class, so an unfiltered
check treats every companion object in the compilation as a bean declaration.

The `companionClassName` half of this finding is **not** a defect and was left
alone — companion-less objects legitimately enclose their nested classes, and
guarding it breaks their bean introspection.

### B12 (MAJOR) Placeholders and wildcards compare equal to their erasure

`ScalaClassElement.java:731` pins `equalityType()` to `ScalaClassElement.class`
with key `(name, arrayDimensions)`, and the plugin sets a placeholder's `name` to
its erasure (`MicronautScalaCompilerPlugin.scala:1017`). So for `class Repo[T, U]`
the elements for `T`, `U` and a plain `ClassElement` for `java.lang.Object` are
all `equals` with identical hash codes, and Micronaut's aggressive element caching
silently collapses distinct type arguments. Separately,
`ScalaWildcardElement` declares no copy methods, so
`wildcard.withAnnotationMetadata(...)` returns a plain `ScalaClassElement` and
`instanceof WildcardElement` checks in the generics writers fail. And a source
element and a classpath element for the same type are never equal, because
`ScalaLoadedClassElement` keys on the `Class` object.

**Fix — placeholders and wildcards done; loaded elements still open.**

Both halves reproduced. For `class Repo[T, U]` the elements for `T` and `U` were
`equals` with identical hash codes, and equal to a plain `java.lang.Object`;
`wildcard.withAnnotationMetadata(...)` returned a plain `ScalaClassElement`.
Placeholders are now keyed on the type variable, wildcards on their bounds, and
`ScalaWildcardElement` keeps its type across an annotation copy.

One correction to the finding: `withArrayDimensions` must **not** be added to the
wildcard, and the annotation copy has to erase when the element carries array
dimensions. `Array[_]` is modelled as a single type carrying both the wildcard
bounds and the dimension, and an array of a wildcard is not itself a wildcard --
`Array[_]` is `Object[]`. Adding the copy override without that rule broke
`ScalaBeanIntrospectionSpec`, which is now guarded by a test of its own.

Unifying `equalityType()` so a source element and a classpath element for the same
type compare equal is **not** done; that belongs with A18.

### B13 (MAJOR) Scala type shapes that are modelled incorrectly

| Shape | Current behaviour | Consequence |
| --- | --- | --- |
| by-name `=> T` | **fixed** — modelled as `scala.Function0` | — |
| varargs `T*` | **not a defect** — see below | — |
| default arguments | **blocked on a Core SPI change** — see below | a Scala default argument is invisible, so the parameter is treated as required |
| value classes (`AnyVal`) | not handled | parameter modelled by its own class while the JVM signature uses the underlying type |
| union types other than `A \| Null` | fall through to the lub's class symbol (`:109`) | silently widened |
| intersection types | truncated to the first bound (`:1083`) | documented in a comment, but not diagnosed |
| `Any`/`AnyRef` in ordinary position | not mapped to `java.lang.Object` | wrong type name |
| trait parameters (`trait Foo(x: Int)`) | only a class's `template.constr` is read | trait constructor parameters are not represented |
| extension methods, `export`, `inline` | not handled | invisible (they live on the skipped module class) |

**Varargs — not a defect.** Checked against emitted bytecode: `def f(xs: String*)`
emits a single method taking `scala.collection.immutable.Seq` with `ACC_VARARGS`
*unset*, because Scala varargs are `Seq`-based rather than array-based. So the
current model is already right on both counts — the parameter type name is a real
JVM type name, and leaving `isVarArgs()` false is correct. (`@scala.annotation.varargs`
additionally emits an array overload with `ACC_VARARGS` set; that forwarder is
generated in the backend and so is not visible to this phase. The `Seq` method is
the real one, so nothing is lost.) Already covered by
`ScalaLanguageFeatureSpec`.

**Default arguments — blocked on Core.** Micronaut does model optional
parameters, but only through `io.micronaut.inject.ast.KotlinParameterElement`,
and `MethodGenUtils` generates *Kotlin's* calling convention for them: a bitmask
plus a synthetic `$default` overload. Scala's convention is unrelated — a
`foo$default$N()` getter per parameter, no mask and no overload — so implementing
that interface would make Core emit calls to methods that do not exist. Honouring
Scala defaults at injection points needs a language-neutral SPI in Core, or a
Scala equivalent of the Kotlin one. This is out of scope for this repository and
is tracked in **F2. Blocked on Micronaut Core**, with an investigation open
against micronaut-core.

**Fix.** For the rest: diagnose with a clear "unsupported for Scala" error rather
than silently producing a wrong model.

### B14 (MAJOR) Scala collection converters ignore the element type

`ScalaCollectionConverterRegistrar.java:47` and its siblings register
source/target class pairs but never read the `ConversionContext`, so no element is
converted to the target element type. Injecting `Seq[Duration]` or `List[Int]`
from a `@Value("${x}")` (a `List<String>`) yields a `Seq[String]` typed as
`Seq[Int]` — a `ClassCastException` at first use rather than a conversion error.
There is also no `scala.collection.Map -> java.util.Map` converter and no
converter into `scala.Option` from a non-`Optional` source, and Micronaut does
not chain converters.

**Fix — element conversion done.** Every converter now resolves the target element
type from the `ConversionContext` and converts each element through the conversion
service; maps convert keys and values, and `Option` converts its contained value.
An element that cannot be converted is rejected on the context and fails the
conversion, which is the difference between a binding error and a
`ClassCastException` later.

Reproduced before fixing: `List[Int]` bound from configuration held
`java.lang.String` elements, and `sum` threw
*"class java.lang.String cannot be cast to class java.lang.Integer"*. Pinned by
`ScalaCollectionElementConversionSpec`.

Still open: the missing `scala.collection.Map -> java.util.Map` reverse converter,
and a converter into `scala.Option` from a non-`Optional` source.

### B15 (MINOR) Smaller items worth folding into the same passes

- `ScalaMethodElement.getOwningType()` returns `declaringType` for "default"
  methods (`:213`), making `withNewOwningType` a no-op for the one case it exists
  for; and `isDefault()` (`:208`) treats every concrete trait method as a Java
  default method.
- `ScalaClassElement.getSyntheticBeanProperties()` (`:250`) returns all
  properties; `ScalaPropertyData` carries no synthetic flag to distinguish them.
- `ScalaEnumElement.elements()` (`:56`) bypasses the element cache, so annotations
  added through `ElementQuery` are invisible through `EnumElement`.
- `ScalaConstructorElement` calls `declaringType.getBeanProperties()` from inside
  a `computeIfAbsent` on an `IdentityHashMap` (`:40`, `ScalaClassElement.java:612`) —
  re-entrant modification during `computeIfAbsent` is undefined behaviour.
- `ScalaLoadedClassElement.getEnclosedElements` has no `PropertyElement` or
  `ClassElement` branch (`:216`), so property queries and nested-class discovery
  return empty for classpath types.
- `LoadedPropertyElement` overrides `getAnnotationMetadata()` but not
  `getAnnotationMetadataToWrite()` (`:908`), so `annotate(...)` writes to metadata
  nobody reads.
- `ScalaPackageElement` never carries package annotations and is reallocated per
  `getPackage()` call.
- `isAssignable` ignores array dimensions and returns true for `java.lang.Object`
  unconditionally, including for primitives (`ScalaClassElement.java:123`).
- Neither implementation overrides `getTypeArguments(String)` / `getAllTypeArguments()`,
  which is how Micronaut answers "what is `T` for the `EventListener<T>` this bean
  implements".
- **Done.** `isValidDefaultValue` (`ScalaAnnotationMetadataBuilder.java:542`) dropped
  empty-string defaults, which are extremely common (`@Named`, `@Property`,
  `@Requires.property`). An empty string is a real default, not the absence of one.
  Pinned by `ScalaAnnotationDefaultsSpec`.
- **Does not reproduce; left alone.** `annotationClassValue` / `enumValue` coerce with
  `String.valueOf` (`:480`, `:502`). Instrumented across the whole suite: the
  `annotationClassValue` fallback is never reached at all, and the `enumValue` fallback
  receives only `String` values that are already correct enum constant names
  (`RUNTIME`, `TYPE`, `AROUND`...), for which `String.valueOf` is a no-op. The
  `AnnotationClassValue("null")` the finding describes needs a null value, and none
  arrives -- null values are filtered before reaching either. Worth re-checking if a
  future change lets nulls through.
- **Does not reproduce; left alone.** `normalizeLooseValue` (`:438`) does not handle
  `ScalaAnnotationData`, `ScalaAnnotationData[]` or `ScalaClassValueData[]`.
  Instrumented across the whole suite: no raw Scala record ever reaches the
  fall-through. The "loose" path is only taken when a member has no resolved mirror,
  and A11 made mirrors resolve on demand, so that is now rare. Re-check alongside any
  change to mirror resolution.
- Positional annotation arguments are matched against `symbol.info.decls`
  iteration order (`MicronautScalaCompilerPlugin.scala:1325`) rather than the
  primary constructor's parameter list, and invent names like `value1` when the
  annotation type is unresolved (`:1331`).
- `excludedHierarchyType` (`:653`) excludes only `Object` and `Enum`; add
  `scala.Any`, `scala.AnyRef`, `scala.Product`, `scala.Equals`, `java.io.Serializable`.
- **Found while fixing B11, now done:** `annotations(symbol)` returned the compiler's own
  `scala.annotation.internal.*` bookkeeping. dotty attaches `SourceFile` to every class it
  compiles, so that annotation was written into the metadata of every generated bean
  definition. Filtered out at extraction; pinned by `ScalaRetentionSpec`.
- Cycle guards in `exceptionMessage` (`MicronautScalaCompilerPlugin.scala:244`,
  `ScalaProcessingEngine.java:426`) only compare against the head exception and
  loop forever on a deeper cycle.
- `optionsHelp` is `None` (`:68`) although options *are* parsed, so
  `-P:micronaut-scala:help` prints nothing and misspelled options are silently
  accepted as `key -> "true"`.

---

## C. Test harness and parity coverage

The corpus is 20 spec files and 220 feature methods, all extending
`AbstractScalaTypeElementSpec`. `DISABLED_TESTS.md` records the shortfall against
Java (194 specs / 1065 features), Groovy (86/473) and Kotlin (19/194). The
harness itself is the limiting factor for several whole categories, so harness
work comes first.

### C1 (MAJOR) Harness capabilities that block parity tests

| Capability | Status | Why it matters |
| --- | --- | --- |
| multi-source compilation | missing — `ScalaCompiler.compile` takes one `source` | separate compilation across files is where phase-ordering and symbol-completion bugs live; nothing currently exercises it |
| joint Java + Scala compilation | missing | Java interop is only tested against *pre-compiled* fixtures, i.e. through `ClasspathAnnotationMetadataReader`, never through the compiler's own Java symbol reader |
| assert compiler warnings | missing — `ScalaCompiler.java:257` consults only `hasErrors()` | `VisitorContext.warn` is entirely untested; zero occurrences of `warn` in the whole spec corpus |
| assert error source position | missing — `errorMessage` (`:263`) joins `Diagnostic::message` only | blocked by A8; once positions exist, tests must be able to assert them |
| compiler options on `buildBeanDefinition`/`buildContext` | missing — only `buildClassElement`/`buildBeanIntrospection` take them | `-Yexplicit-nulls` is therefore never verified at definition or runtime level, only at Element API level |
| `buildBeanDefinition(package, generatedName, source)` | missing — `:141` hardcodes the `$…$Definition` suffix | `$Foo$Intercepted$Definition`, factory definitions etc. cannot be fetched directly; every AOP/factory test is forced through `buildContext` |
| `buildIntroducedBeanDefinition` | missing | worked around via `buildContext` |
| inspect generated bytecode | missing — `Compilation.outputDirectory` is package-private | no test verifies what was actually emitted |

Two of these unlock most of the rest: threading `compilerOptions` through
`buildBeanDefinition`/`buildContext` (a three-line change each) and adding
`compile(List<SourceFile>)` (roughly 25 lines).

### C2 (MAJOR) The harness hides failures

`ScalaCompiler.java:200` swallows `NoSuchMethodException | InvocationTargetException |
InstantiationException | IllegalAccessException` when loading generated bean
definition references, and `loadDefinition` (`:311`) returns `null` on
`ClassNotFoundException`. For a compiler plugin under development this is the
most dangerous default in the codebase: a generated definition with invalid
bytecode or a throwing constructor is dropped, and the test either fails much
later with a confusing `NoSuchBeanException` or *passes* because the assertion was
about a different bean. `buildBeanIntrospection` (`:124`) and `buildClassElement`
(`:93`) likewise collapse "not generated" and "generated but broken" into `null`.

**Fix.** Keep `null` only for `ClassNotFoundException`; rethrow everything else as
`IllegalStateException` naming the target class and cause.

### C3 (MINOR) Harness hygiene

- Every compilation leaks a temp directory; nothing deletes them (no
  `deleteOnExit`/`Files.delete` anywhere in `inject-scala-test/src/main`).
- Three unclosed `URLClassLoader`s per compilation (see B4), in a single JVM
  capped at `-Xmx2048m` with no `forkEvery`.
- `ScalaTypeElementQueryVisitor` uses plain `public static` mutable state while
  every sibling visitor uses a `ThreadLocal` + `withXxx(...)` scope; its `query()`
  is consulted on every compilation regardless of the `enabled` guard.
- `-release:25` is hardcoded at `:253` and cannot be overridden.
- The published test artifact registers **10** always-on `TypeElementVisitor`s
  plus an `AnnotationMapper`, `AnnotationRemapper` and `AnnotationTransformer` in
  its *main* source set, so any consumer putting it on a compile classpath
  activates all of them.
- `ScalaCompiler.compileSource` (`:243`) requires two system properties
  (`micronaut.scala.test.classpath`, `micronaut.scala.plugin.jar`) set only by
  this repository's own build, so the published artifact is unusable externally
  without replicating that wiring by hand.

### C4 Language-feature coverage gaps (each verified by grep over the corpus)

Nothing in the corpus exercises: Scala `object` as a bean (all 10 `object`
occurrences are companions or namespaces — no `@Singleton object`, no
`@Factory object`); varargs; by-name parameters; default arguments; secondary
constructors; `given`/`using` clauses; multiple parameter lists; value classes;
opaque types; extension methods; `lazy val`; `scala.beans.@BeanProperty`;
`case object`; higher-kinded types; tuple types; `Try`/`Either`/`Future` bean
signatures; `@targetName`/`transparent`/`erased`; `private[pkg]`; trait
parameters; `export` clauses; self-types; top-level definitions.

Several of these are the *most idiomatic* Scala constructs, and B11/B13 above
say the implementation gets some of them wrong. They should be tested before, or
alongside, the broader Java/Groovy parity backlog — a Scala user hits `object`
and default arguments on day one and hits `AbstractTypeElementSpec` parity never.

### C5 Mapping to the existing priority buckets

`DISABLED_TESTS.md` already defines P0–P3. This plan does not replace them; it
inserts a **P-1 (Scala-native)** bucket ahead of P0, and reorders the rest by
whether the underlying adapter defect is fixed:

- **P-1 (new, Scala-native):** `object` beans, default arguments, varargs, by-name
  parameters, secondary constructors, `using`/`given` clauses, multiple parameter
  lists, `lazy val`, `@BeanProperty`, trait parameters, value classes, top-level
  definitions. Cheap to write with the existing harness; each pins down a
  currently-unspecified behaviour.
- **P0** (Element API, annotation parity): now largely gated on A4, A5, A6, A7,
  A9, A10, A11, B12. Port the parity specs *after* those land, so the tests
  encode the fixed behaviour rather than the current one.
- **P1** (introspection, bean definition, configuration): gated on A6, A14, A15,
  A17, B14.
- **P2** (AOP, lifecycle, executable): gated on A5, B15's `isDefault`/`getOwningType`
  items.
- **P3** (visitor-generated beans, build-time): gated on B7 and B10.

---

## D. Build, packaging, publication

### D1 (BLOCKER) `micronaut-core` is pinned to an unpublished snapshot

`gradle/libs.versions.toml:5` — `micronaut-core = "5.2.0-SNAPSHOT"`, while
`settings.gradle:34` declares only `mavenCentral()`. `README.md` already
acknowledges this. The consequence beyond "cannot release": all four publishing
workflows (`gradle.yml:111`, `publish-snapshot.yml:39`, `central-sync.yml:45`,
`release.yml:74`) run *without* `-PincludeMicronautCore=true`, unlike the build
step at `gradle.yml:76`, so they cannot resolve the dependency and fail on every
push to `master`. Even if they were fixed, the published POM would declare a
version no consumer can resolve.

**Fix.** Pin a resolvable Core version before merge. Until then, add the snapshot
repository plus the Core include to every publishing workflow, and mark the
module explicitly unpublishable so a green CI does not imply a publishable
artifact.

### D2 (BLOCKER) Fat jar without relocation, plus the same dependencies in the POM

`inject-scala-compiler/build.gradle.kts:77` merges every runtime-classpath jar
except the two Scala stdlib jars, at original package paths with no relocation —
`io.micronaut.**`, `org.objectweb.asm.**`, `org.slf4j.**`. Meanwhile lines 42–54
declare `api(libs.micronaut.core.processor)`, `api(libs.scala3.library)` and
`implementation(libs.asm)`, which become POM `compile`/`runtime` dependencies. A
consumer therefore gets every one of those classes twice, and which copy wins
depends on classpath order.

This also breaks the documented usage. Gradle passes *every file* resolved from
`scalaCompilerPlugins` as its own `-Xplugin:` argument, and sbt's
`addCompilerPlugin` does the same for the `plugin` configuration. Because the POM
is transitive, `dotc` is handed `-Xplugin:` for a dozen jars with no
`plugin.properties`. The only form that works today is
`scalaCompilerPlugins("...") { isTransitive = false }`, documented nowhere.

`src/main/docs/guide/quickStart.adoc:13` compounds it by telling users to put the
same fat jar on the application runtime classpath via `runtimeOnly`, dragging an
un-relocated Micronaut and ASM into the running application.

**Fix.** Choose one model:

- **(a) Thin jar.** Drop the merge; publish real dependencies. Simplest, and it is
  what `inject-scala-test-compiler` already does. Users then need the plugin
  configuration to be intransitive, or the plugin needs a `runtimeClasspath` that
  is genuinely minimal.
- **(b) Shaded jar.** Relocate with the Shadow plugin, merge service files, and
  publish a POM with no compile/runtime dependencies except `scala3-library_3` as
  provided.

Either way, extract a tiny `micronaut-scala-runtime` artifact containing only
`ScalaCollectionConverterRegistrar` and its service file, and point `runtimeOnly`
at that.

### D3 (MAJOR) `DuplicatesStrategy.EXCLUDE` silently drops merged metadata

With `EXCLUDE`, the first entry for a path wins and later ones are dropped with
no message. Concretely: `META-INF/services/**` is **not** merged, so a second
`TypeElementVisitor`/`AnnotationMapper`/`AnnotationTransformer`/`BeanElementVisitor`
descriptor from a bundled jar is lost — and because visitors are discovered by
`ServiceLoader`, a dropped descriptor is a silently missing visitor with no build
error. `META-INF/LICENSE`/`NOTICE` aggregation is broken the same way, which is a
redistribution problem for Apache-2.0 code. Nothing excludes `module-info.class`
(ASM is a modular jar), and the manifest never sets `Multi-Release: true`, so any
copied `META-INF/versions/**` classes are inert. The signature-file exclusion
misses `META-INF/*.EC`, `META-INF/SIG-*` and `META-INF/INDEX.LIST`.

**Fix.** If the fat jar survives D2, use a service-merging transformer, exclude
`module-info.class` and `META-INF/versions/*/module-info.class`, complete the
signature-file exclusions, and set `duplicatesStrategy = FAIL` for everything else
so collisions are loud.

### D4 (MAJOR) The packaging guard never runs and half of it cannot fire

`build.gradle:45`

```groovy
def forbiddenPluginEntries = zipTree(pluginJar).matching {
    include("scala/**")
    include("dotty/**")
    include("scala3-library_3-*")
    include("scala-library-*")
}.files
```

`scala3-library_3-*` and `scala-library-*` are *jar file names*; inside a jar the
entries are class paths, so those two patterns match zero entries under all
circumstances and give false confidence that the filename-based `filterNot` in the
jar task is being cross-checked. Worse, `verifyCompilerArtifacts` is not a
dependency of `check` and is invoked by no workflow — the only packaging guard in
the repository runs only if a human types the task name.

It also does not assert the things most likely to break at release: that the test
POM depends on the plugin by its crossed coordinate rather than the Gradle project
name, that `scala3-compiler_3` does not leak into the plugin POM, that
`plugin.properties` is present at the jar root, or anything about dropped service
files.

**Fix.** Delete the two dead patterns; assert on the *inputs*
(`configurations.runtimeClasspath` must contain no `scala3-compiler_3` /
`scala3-library_3` / `scala-library` after filtering); add
`include("META-INF/versions/*/scala/**")`; add the four missing assertions; and
wire `check.dependsOn("verifyCompilerArtifacts")`.

### D5 (MAJOR) The Scala toolchain pin is incomplete

`inject-scala-compiler/build.gradle.kts:59` pins only `scala3-library_3` and
`scala3-compiler_3`. The rest of the toolchain that `scala3-compiler_3` drags in
is unpinned: `tasty-core_3`, `scala3-interfaces`, `scala-asm`, `compiler-interface`,
and the 2.13 `scala-library`. A bump elsewhere can produce a `tasty-core_3` that
does not match the pinned compiler — exactly the ABI mismatch the pin exists to
prevent.

Separately, `exclude(group = "io.micronaut.sourcegen", module = "micronaut-sourcegen-bom")`
(`:44`) removes *version constraints*, not sourcegen itself, leaving its
transitive versions to unmanaged conflict resolution and then bundling the result.

**Fix.** Pin the whole `org.scala-lang` group; exclude the specific Scala BOM
rather than the sourcegen platform, and comment which versions that is expected
to move.

### D6 (MAJOR) CI runs one Java version — the multi-JDK half is withdrawn

The multi-JDK part of this finding no longer applies: JDK 25 is the supported
baseline and earlier JDKs are explicitly out of scope, so a 17 or 21 matrix entry
is not wanted. The artifact/toolchain mismatch it noticed was real and is fixed —
the plugin targeted `-release:17` on its Scala sources only while the harness used
`-release:25`, and both now come from one constant.

The rest of this finding stands.

`verifyCompilerArtifacts` runs in no workflow (D4). And nothing tests the plugin
as an end user consumes it: there is no `doc-examples/`, no sample application,
and the `functional-test` convention plugin in `buildSrc` is applied by zero
projects. Every test drives `dotc` with `-Xplugin:<absolute path to the built jar>`,
which bypasses coordinate resolution and `-Xplugin:` fan-out — precisely the two
things D2 shows are broken.

**Fix.** Wire `verifyCompilerArtifacts` into CI (done); add a functional test that
resolves the published coordinate through a real `scalaCompilerPlugins`
configuration and compiles one `@Singleton` Scala class, plus an sbt equivalent.

### D7 (MAJOR) Template residue

This repository was generated from the Micronaut module template and a
significant amount of template-only content is now wrong:

- `AGENTS.md:3` still says *"This repository is the Micronaut template for
  generated module repositories"* and describes `project-template/`,
  `project-template-bom/`, `io.micronaut.build.internal.project-template-*.gradle`
  and `template-cleanup.yml` — none of which exist here. The whole file needs
  rewriting for the real shape (`inject-scala`, `inject-scala-compiler`,
  `inject-scala-test`, `inject-scala-test-compiler`, `micronaut-scala-bom`).
- `CONTRIBUTING.md:19` claims the parity tests require Docker — they do not;
  `ScalaCompiler` runs `dotty.tools.dotc.Main` in-process. Docker is needed only by
  the vulnerability-audit script. The `TESTCONTAINERS_RYUK_DISABLED` env in
  `gradle.yml:25` is the same residue.
- `CONTRIBUTING.md:88` tells maintainers to update `githubCoreBranch` in
  `gradle.properties`; no such property exists (the real knobs are
  `local.git.micronaut-core`, `includeMicronautCore`, `micronautCoreBranch`), and
  the file documents those correctly 40 lines earlier.
- `README.md:1` retains the template checklist HTML comment and a commented-out
  Examples section pointing at a non-existent `examples/`.
- `config/checkstyle/suppressions.xml:11` suppresses `.*doc-examples.*`, which does
  not exist; the commented block above names micronaut-core files.
- `buildSrc/.../convention-base.gradle` is a verbatim copy of micronaut-core's:
  Spotless excludes reference core files absent here, `jvmArgs "--enable-preview"`
  is core-specific, and `inputs.file("config/accepted-api-changes.json")` names a
  file that does not exist and will fail the first 1.0.x release build once
  japicmp is enabled.
- `convention-geb-base.gradle` and `functional-test.gradle` are applied by no
  project, keeping `geb`, `htmlunit`, `selenium` (6 aliases) and
  `native-gradle-plugin` alive in the version catalog for Renovate to churn on.
- `files-sync.yml`, `update-gradle-wrapper.yml` and `skills-validation.yml` are
  gated on `github.repository == 'micronaut-projects/micronaut-project-template'`
  and no-op forever; `scala` is not in the `files-sync.yml` repository list, so the
  "do not edit this file directly" headers on seven workflows are misleading here.
- `graalvm-latest.yml` / `graalvm-dev.yml` build a native-test matrix for a
  repository with no native tests, and `introduction.adoc:28` says native is out
  of scope.
- `sonatype.yml` triggers only on PRs to `[0-9]+.[0-9]+.x`, but the default branch
  is `master`, so the vulnerability audit never runs on a PR.

### D8 (MAJOR) The multi-Scala-version story is not built for

`SCALA3_SUPPORT_PLAN.md:17` requires a parallel variant per Scala release, but
adding one today is a hand-edit across seven places: a single `scala3` catalog
alias read by four build files; two new Gradle projects that cannot be named after
their version (`settings.gradle:47` explains that `TYPESAFE_PROJECT_ACCESSORS`
rejects dots in project names); `verifyCompilerArtifacts` hardcoding one version
and two project paths; the BOM's hand-maintained `extraExcludedProjects` list
(8 entries for 4 projects) and hand-added constraints; `parentModuleId` becoming
ambiguous with two self-declared root modules; duplicated Checkstyle/Spotless/
NullAway/Javadoc runs over the same shared sources; and 20 hardcoded `3.9.0`
occurrences across README, plan, CONTRIBUTING and the guide.

A BOM listing two mutually exclusive ABI variants under one `propertyName = "scala"`
is also semantically wrong — consumers would get both.

**Fix.** Introduce a declared list of Scala variants and generate the compiler
subprojects in `settings.gradle` from it (mangling `.` to `_` in project names),
with a shared convention plugin that reads a `scalaVersion` project property and
owns the per-variant `verifyCompilerArtifacts`. Publish one BOM per variant, or
drop the plugin constraints from the BOM entirely.

---

## E. Documentation

### E1 What exists

Four guide pages totalling about 110 lines, plus `toc.yml`:

- `introduction.adoc` — what the project is, supported Scala version, the two
  coordinates, a prose feature list, and a pointer to "the Scala language-support
  documentation in the Micronaut Core repository" for actual usage.
- `quickStart.adoc` — the only usage page. Two lines of Gradle, a bare Maven
  `<dependency>` block, and three sentences of prose for sbt and Mill with no code.
- `repository.adoc` — contributor-facing description of the five Gradle projects.
- `releaseHistory.adoc` — release policy prose, no history.

`README.md` is maintainer-facing. There is no working example anywhere, no
runtime-dependency guidance, no option reference, no testing guidance and no
limitations page.

### E2 Defects in what exists

- **sbt/Mill advice double-suffixes.** `quickStart.adoc:41` says to use
  `CrossVersion.full` / Mill's `:::`, while every coordinate printed elsewhere
  already contains `_3.9.0`. Following both yields
  `micronaut-inject-scala_3.9.0_3.9.0`. The page must pick one form and mark the
  other as wrong.
- **The Maven section cannot work.** `quickStart.adoc:25` shows a plain
  `<dependency>`; registering a Scala compiler plugin in Maven needs
  `scala-maven-plugin` with `<compilerPlugins>` or an explicit `<arg>-Xplugin:…</arg>`.
  As written the plugin never runs and no bean definitions are generated — a silent
  failure.
- **The `-Xplugin:` fan-out trap is undocumented** (see D2).
- Contributor bootstrap instructions (`-Plocal.git.micronaut-core`) are mixed into
  the user quick start, and the examples use `1.0.0-SNAPSHOT`.

### E3 Facts a doc author needs (verified from source)

- Group `io.micronaut.scala`; plugin `micronaut-inject-scala_3.9.0`; test support
  `micronaut-inject-scala-test_3.9.0`; BOM `micronaut-scala-bom`; version
  `1.0.0-SNAPSHOT`; Scala `3.9.0`, requiring `org.scala-lang:scala3-compiler_3:3.9.0`.
- Compiler-plugin name is **`micronaut-scala`**
  (`MicronautScalaCompilerPlugin.scala:66`), registered through
  `inject-scala/src/main/resources/plugin.properties`
  (`pluginClass=io.micronaut.scala.processing.MicronautScalaCompilerPlugin`).
- Option prefix is therefore `-P:micronaut-scala:<key>=<value>`. There is no
  literal `-P:` string in the repository; the prefix is the Scala 3 default derived
  from the plugin name. Options are split on the first `=`, and a bare token
  becomes `key -> "true"` (`:167`).
- `ScalaProcessingEngine.setMicronautOptionsAsSystemProperties` (`:372`) promotes
  every key starting with `micronaut` to a system property. This is the Scala
  equivalent of javac's `-Amicronaut.processing.*` flags, so the documented forms
  are `-P:micronaut-scala:micronaut.processing.group=…`,
  `…module=…`, `…incremental=true`.
- Output directory and classpath come from the compiler settings
  (`ctx.settings.outputDir`, `ctx.settings.classpath`), not from options.
- The plugin inserts two phases, useful for `-Xprint` when troubleshooting:
  `micronaut-scala-type-visitors` (after `PostTyper`) and
  `micronaut-scala-bean-definitions` (before `Pickler`).
- Processing failures surface as dotc errors formatted
  `"Error processing Scala element[<name>]: <message>"` (`:241`).
- The only genuine runtime contribution is `ScalaCollectionConverterRegistrar`,
  registered via `META-INF/services/io.micronaut.core.convert.TypeConverterRegistrar`.
  Without it, Scala collection injection and Scala collection configuration
  binding do not convert.
- **Add-on processors go on the compiler's `-classpath`, not on an
  `annotationProcessor` configuration**, because visitors are discovered by
  `ServiceLoader` over the compile classpath. This is a materially different mental
  model from javac/kapt and needs its own section. The build demonstrates it at
  `inject-scala-test-compiler/build.gradle.kts:55`.
- JDK 25 is the supported baseline: the plugin jar, the build and the tests all
  target it, and JDKs below 25 are explicitly out of scope.
- Scala 2 is out of scope.

### E4 Target documentation set

New `toc.yml`:

`introduction` · `quickStart` · `buildTools` (Gradle, sbt, Mill, Maven — complete
working build files for each) · `configuration` (the `-P:micronaut-scala:` option
reference) · `dependencies` (compile vs. runtime vs. classpath-visible visitors;
the runtime converter registrar) · `testing` (the test artifact and its required
system properties) · `limitations` (distilled from `DISABLED_TESTS.md` and B11/B13)
· `troubleshooting` (plugin not applied, no bean definitions generated,
`-Xplugin:` fan-out, `java.compiler` module, version-suffix mismatch) ·
`repository` · `releaseHistory`.

---

## F. Sequenced work plan

Each wave is independently mergeable and leaves the build green.

### Wave 0 — make the build honest (prerequisite) — **done**

1. **Done.** `verifyCompilerArtifacts` is a dependency of `check`, the two dead
   assertions are replaced with an input-side check on what the jar task actually
   bundles, and the four missing assertions are in (D4). The service-descriptor
   assertion failed on the first run: `DuplicatesStrategy.EXCLUDE` had been
   dropping Core's `AnnotationConvertersRegistrar`,
   `ReactiveTypeConverterRegistrar` and `ReactiveStreamsTypeInformationProvider`
   from every plugin jar ever built, so the merge fix from D3 came forward into
   the same commit.
2. **Withdrawn; JDK 25 is the supported baseline.** Nothing below 25 is
   supported, so there is no 17 or 21 matrix entry to add -- and there could not
   be one anyway, since `micronaut-gradle-plugins` 8.0.1 refuses to run on
   anything below JVM 25. The finding did expose a real defect: `-release:17` was
   applied only to `ScalaCompile`, so the plugin jar was 23 classes at class file
   version 61 and 64 at version 69. Both halves now compile from one
   `javaTarget` constant, and `verifyCompilerArtifacts` asserts every entry is at
   exactly the expected version rather than merely under a ceiling, because the
   defect was divergence between the two compile tasks.
3. **Done, by refusing to publish.** Passing `-PincludeMicronautCore=true` to the
   publishing workflows would have made them succeed while uploading POMs
   declaring an unresolvable Core version, which is worse than the current
   failure. A task-graph check now fails any Sonatype or Maven Central
   publication while the Core version ends in `-SNAPSHOT`, naming the catalog
   entry to change. `publishToMavenLocal` is unaffected (D1).
4. **Done.** `AGENTS.md` is rewritten for the real repository; the
   `CONTRIBUTING.md` Docker and `githubCoreBranch` claims are corrected, as is
   the `TESTCONTAINERS_RYUK_DISABLED` env that expressed the Docker claim in CI;
   the README checklist and Examples residue, the `doc-examples` checkstyle
   suppression and `convention-base.gradle`'s micronaut-core spotless excludes
   and `--enable-preview` are gone. `config/accepted-api-changes.json` now
   exists, having been a declared japicmp input that did not. The unused
   `convention-geb-base.gradle` and its eight catalog aliases are deleted (D7).

Rationale: without this, later waves cannot tell a real regression from an
already-broken guard.

### Wave 1 — confirmed correctness defects — **done**, except A6's classpath half

5. **Done** (`e55b469`). `hasAllFlags` and the four composite-flag call sites (A1).
6. **Done.** Both phases now override `runOn`, which the compiler calls exactly
   once per phase per run; the unit counter and the duplicated `processed`
   booleans are gone, and `processBeanDefinitions` starts by calling the
   idempotent `processTypeVisitors` so the ordering invariant lives in the engine
   (A2). A3 needs no work -- see its section above, the state was never shared
   across runs. Pinned by `ScalaPipelineOrderingSpec`.
7. **Done.** The reporter channel is `BiConsumer<String, Object>` carrying the
   native type, and the plugin resolves it to a `SrcPos` -- both `Positioned` and
   `Symbol` already extend it (A8). `ScalaCompiler.buildAndGetDiagnostics` exposes
   errors, which were previously observable only as a thrown exception. Pinned by
   `ScalaDiagnosticPositionSpec`.
8. **Done.** Annotation values are read from the typed tree: class literals from
   the `ClazzTag` constant on the tree's type, default arguments from the callee's
   symbol name, anything else reported as an error at the argument position (A9).
   Both `classOf`-substring scans and all three text-scanning helpers are gone.
   Pinned by `ScalaAnnotationValueSpec`.
9. **Done.** `withTypeArguments` (A4), `overrides`/`hides` (A5). Both copy paths on
   `ScalaClassElement` also stopped discarding the class's members. Pinned by
   `ScalaGenericsAndOverridesSpec`.
10. **Half done.** The field-inheritance walk, which did not exist at all, now
    runs for supertypes in the compilation (A6), pinned by
    `ScalaInheritedFieldSpec`. The classpath half is blocked on A7 and A18 — see
    A6 above — and moves to Wave 4, after them.

### Wave 2 — harness, then Scala-native tests

11. `compile(List<SourceFile>)`, `compilerOptions` on `buildBeanDefinition`/
    `buildContext`, the three-arg `buildBeanDefinition`, `buildIntroducedBeanDefinition`
    (C1). Stop swallowing load failures (C2). Clean up temp dirs and classloaders
    (C3, B4).
12. Write the P-1 Scala-native specs (C4/C5), starting with `object` beans,
    default arguments, varargs, by-name parameters and secondary constructors —
    these will fail, and the failures define the next fixes.
13. B11 (`object` as a bean) and A17 (field modifiers and reflection) are
    **done**, pinned by `ScalaObjectBeanSpec`. A15 (secondary constructors) and
    the by-name part of B13 landed earlier on this branch. Still open: the varargs
    and default-argument parts of B13, A14 (accessor collisions) and A16
    (qualified access).

### Wave 3 — annotation metadata unification — **fixes done; item 17 outstanding**

14. **Done.** Classpath elements build their metadata through
    `ScalaAnnotationMetadataBuilder` (A7), so stereotypes, aliases, mappers and
    repeatable containers resolve for them. Declared annotations only —
    annotations *inherited* from a classpath supertype wait on A18, and are
    recorded with A6's classpath half.
15. **Done, in two parts and not as proposed.** Defaults are collected across every
    unit of the compilation, and classpath defaults are read from the class file
    via `visitAnnotationDefault`, because dotty discards the value (A10).
16. **Done**, except `@Target` capture, which is still open. A11 resolves mirrors
    on demand; A12's retention default applies to Java annotation types only; A13
    unions getter and field annotations; of the B15 items, `isValidDefaultValue`
    is fixed and the two coercion items do not reproduce — see them for the
    evidence. Also fixed here, though not in the plan: the compiler's own
    `scala.annotation.internal.*` annotations were being written into every
    generated bean definition.
17. **Outstanding.** Port the P0 parity specs, which now encode fixed behaviour.
    This is bulk test work rather than adapter fixes, and is the largest remaining
    item in the plan.

### Wave 4 — element model consistency

18. Equality and copy semantics for placeholders, wildcards and loaded elements
    (B12); `getTypeArguments(String)`/`getAllTypeArguments()`; the remaining B15
    items.
19. Classpath enumeration via the declared-member walk (A18), and then the
    classpath half of A6, which depends on it and on A7.
20. Collection converters with element-type conversion (B14).
21. Then port P1 and P2 parity specs.

### Wave 5 — packaging, runtime artifact, docs

22. Resolve the fat-jar question (D2) and, whichever way it goes, extract
    `micronaut-scala-runtime` for the converter registrar.
23. Service-file merging and the remaining jar hygiene items (D3); complete the
    Scala toolchain pin (D5).
24. Add the end-user functional test (Gradle, and sbt if practical) (D6).
25. Write the documentation set in E4.

### Wave 6 — structural

26. Incremental compilation: service-file merge behaviour, generated-file
    registration, output-after-`GenBCode` (B10).
27. Multi-Scala-variant build layout (D8).
28. Visitor dispatch order and the remaining engine hygiene items (B1, B2, B3,
    B6, B7, B8, B9).

---

## F2. Blocked on Micronaut Core

Items that cannot be closed in this repository. Each needs a change to Core
first; they are listed here so they are not mistaken for oversights.

### Scala default arguments at injection points (from B13)

A parameter with a default is treated as required, so a Scala bean whose
constructor or `@Executable` method relies on defaults cannot be satisfied the
way the source implies.

Core models optional parameters only through
`io.micronaut.inject.ast.KotlinParameterElement`, and the blocker is the
*generated code* rather than the marker interface:
`inject/writer/MethodGenUtils` builds an integer bitmask and calls Kotlin's
synthetic `$default` overload, which is Kotlin's calling convention. Scala emits
a separate zero-argument `<method>$default$<n>` getter per defaulted parameter
(`$lessinit$greater$default$<n>` for constructors), with no mask and no overload,
so implementing the Kotlin interface here would make Core generate calls to
methods that do not exist.

**Blocked on:** a language-neutral optional-parameter SPI in Core — either
`ParameterElement.hasDefault()` plus a per-language way to supply the default
value, or a narrower "this parameter is optional" signal if relaxing
required-ness is useful on its own. An investigation is open against
micronaut-core.

**Come back when:** Core exposes such an SPI. The work here is then small —
recognise the `$default$` getters in the extractor (the symbol-name detection
added for annotation members in `defaultGetterReference` is the same mechanism)
and implement the new interface on `ScalaParameterElement`. Add specs for a
constructor default and an `@Executable` method default, and move this item back
into a numbered wave.

### Classpath supertypes contributing inherited members (the second half of A6)

Blocked on A7 and A18, which are already in Waves 3 and 4 of this plan rather
than on Core. Recorded here only so the two blocked halves are visible together;
see A6 for the detail.

---

## G. Open questions to settle with a test

These were identified by review but need execution to confirm:

1. Does dotty synthesise `x_=` setter symbols with `Flags.Accessor` before
   `Pickler`? If not, `isPropertySetterDeclaration`
   (`MicronautScalaCompilerPlugin.scala:565`) never finds a write method and every
   extracted property is read-only, breaking setter injection.
2. Does `DirectoryClassWriterOutputVisitor.finish()` merge with a pre-existing
   service file, or truncate it? This decides how bad B10 is in practice.
3. What are the declared-vs-inherited semantics of the `TypeElementQuery` SPI that
   `ScalaProcessingEngine.java:287` uses `ElementQuery.ALL_FIELD_AND_METHODS` for?
   If declared-only is intended, it needs `.onlyDeclared()`.
4. Does `report.inform` print without `-verbose`? `VisitorContext.info(...)` is
   expected by Micronaut visitors to be user-visible.
