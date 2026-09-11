/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.scala.processing

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Annotations
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameKinds
import dotty.tools.dotc.core.Comments
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols.ClassSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.AnnotatedType
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.transform.ValueClasses
import dotty.tools.dotc.core.Types.AndType
import dotty.tools.dotc.core.Types.AppliedType
import dotty.tools.dotc.core.Types.ConstantType
import dotty.tools.dotc.core.Types.ExprType
import dotty.tools.dotc.core.Types.JavaArrayType
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.core.Types.OrType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.TypeBounds
import dotty.tools.dotc.core.Types.TypeRef
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.plugins.StandardPlugin
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.Pickler
import dotty.tools.dotc.transform.PostTyper
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.SrcPos
import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.scala.processing.visitor.ScalaAnnotationData
import io.micronaut.scala.processing.visitor.ScalaAnnotationMemberData
import io.micronaut.scala.processing.visitor.ScalaAnnotationTypeData
import io.micronaut.scala.processing.visitor.ClassFileParameterAnnotations
import io.micronaut.scala.processing.visitor.ScalaClassValueData
import io.micronaut.scala.processing.visitor.ScalaClassData
import io.micronaut.scala.processing.visitor.ScalaFieldData
import io.micronaut.scala.processing.visitor.ScalaMethodData
import io.micronaut.scala.processing.visitor.ScalaParameterData
import io.micronaut.scala.processing.visitor.ScalaProcessingEngine
import io.micronaut.scala.processing.visitor.ScalaPropertyData
import io.micronaut.scala.processing.visitor.ScalaTypeData

import dotty.tools.io.JarArchive

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.IdentityHashMap as JIdentityHashMap
import java.util.Map as JMap
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Scala 3 compiler plugin that adapts typed Scala symbols to Micronaut's Element API.
 */
final class MicronautScalaCompilerPlugin extends StandardPlugin:

  override val name: String = "micronaut-scala"
  override val description: String = "Generates Micronaut metadata for Scala 3 sources"
  /**
   * Documents the options the plugin accepts, so `-P:micronaut-scala:help` prints
   * something. Options are passed straight through to `VisitorContext.getOptions()`,
   * where Micronaut itself and any user `TypeElementVisitor` read them, so the set is
   * open by design and cannot be validated against a fixed list -- the same contract as
   * javac's `-A` options.
   */
  override val optionsHelp: Option[String] = Some(
    s"""  -P:$name:<key>=<value>   Sets a Micronaut processing option. An option given with
                                    no `=` is set to "true". Options are passed to
                                    VisitorContext.getOptions(), which Micronaut and any
                                    TypeElementVisitor on the compilation classpath may read,
                                    so the set is open and unrecognised keys are accepted.
       Well-known keys:
         micronaut.processing.incremental=true|false
         micronaut.processing.group=<maven group of the module being compiled>
         micronaut.processing.module=<name of the module being compiled>
         micronaut.processing.project.dir=<project directory>
         micronaut.processing.annotations=<comma-separated package prefixes>
         micronaut.processing.use.context.classloader=true|false"""
  )

  override def init(options: List[String]): List[PluginPhase] =
    // The adapter is loaded in an isolated class loader so it can use Micronaut's
    // annotation-processing APIs without leaking compiler-plugin implementation
    // classes into the compiler's parent class loader.
    val delegate = MicronautScalaCompilerPlugin.delegate(getClass)
    val initMethod = delegate.getClass.getMethod("init", classOf[List[?]])
    initMethod.invoke(delegate, options).asInstanceOf[List[PluginPhase]]

private object MicronautScalaCompilerPlugin:

  private val DelegateClassName = "io.micronaut.scala.processing.MicronautScalaCompilerPluginImpl"

  def delegate(pluginClass: Class[?]): AnyRef =
    val codeSource = pluginClass.getProtectionDomain.getCodeSource
    val urls = if codeSource == null then Array.empty[URL] else Array(codeSource.getLocation)
    val classLoader = MicronautScalaPluginClassLoader(urls, pluginClass.getClassLoader)
    Class.forName(DelegateClassName, true, classLoader).getDeclaredConstructor().newInstance()

private final class MicronautScalaPluginClassLoader(urls: Array[URL], parent: ClassLoader)
    extends URLClassLoader(urls, parent):

  private val platformClassLoader = ClassLoader.getPlatformClassLoader
  private val parentLoadsJavaCompiler = canLoadFromParent("javax.lang.model.element.Modifier")

  override protected def loadClass(name: String, resolve: Boolean): Class[?] =
    getClassLoadingLock(name).synchronized {
      var loaded = findLoadedClass(name)
      if loaded == null then
        loaded = loadUncachedClass(name)
      if resolve then
        resolveClass(loaded)
      loaded
    }

  private def loadUncachedClass(name: String): Class[?] =
    if name.startsWith("javax.lang.model.") then
      platformClassLoader.loadClass(name)
    else if isParentFirst(name) then
      loadFromParent(name)
    else
      try
        findClass(name)
      catch
        case _: ClassNotFoundException => loadFromParent(name)

  private def loadFromParent(name: String): Class[?] =
    try
      getParent.loadClass(name)
    catch
      case parentFailure: ClassNotFoundException =>
        try
          platformClassLoader.loadClass(name)
        catch
          case _: ClassNotFoundException =>
            try
              findClass(name)
            catch
              case _: ClassNotFoundException => throw parentFailure

  private def isParentFirst(name: String): Boolean =
    name.startsWith("scala.") ||
      name.startsWith("dotty.") ||
      name.startsWith("java.") ||
      name.startsWith("jdk.") ||
      name.startsWith("io.micronaut.core.") ||
      (parentLoadsJavaCompiler && isParentMicronautApi(name)) ||
      name == "io.micronaut.scala.processing.MicronautScalaCompilerPlugin" ||
      name == "io.micronaut.scala.processing.MicronautScalaCompilerPlugin$"

  private def isParentMicronautApi(name: String): Boolean =
    name.startsWith("io.micronaut.aop.") ||
      name.startsWith("io.micronaut.context.") ||
      name.startsWith("io.micronaut.expressions.context.") ||
      name.startsWith("io.micronaut.inject.annotation.") ||
      name.startsWith("io.micronaut.inject.ast.") ||
      name.startsWith("io.micronaut.inject.processing.") ||
      name.startsWith("io.micronaut.inject.visitor.") ||
      name.startsWith("io.micronaut.inject.writer.") ||
      name.startsWith("io.micronaut.sourcegen.")

  private def canLoadFromParent(name: String): Boolean =
    try
      getParent.loadClass(name)
      true
    catch
      case _: ClassNotFoundException =>
        false
      case _: LinkageError =>
        false

final class MicronautScalaCompilerPluginImpl:

  def init(options: List[String]): List[PluginPhase] =
    val state = ProcessingState(parseOptions(options))
    List(TypeVisitorPhase(state), BeanDefinitionPhase(state))

  private def parseOptions(options: List[String]): JMap[String, String] =
    val parsed = LinkedHashMap[String, String]()
    options.foreach { option =>
      val separator = option.indexOf('=')
      if separator > -1 then
        parsed.put(option.substring(0, separator), option.substring(separator + 1))
      else
        parsed.put(option, "true")
    }
    parsed

private final class ProcessingState(options: JMap[String, String]):

  private var engine: ScalaProcessingEngine | Null = null
  private var outputReported = false
  private var outputUsable = true

  def addClasses(classes: List[ScalaClassData])(using ctx: Context): Unit =
    try
      engineInstance.addClasses(classes.asJava)
    catch
      case exception: ProcessingException =>
        reportProcessingException(exception)

  def addDocumentation(comments: JMap[Object, String])(using ctx: Context): Unit =
    if !comments.isEmpty then
      try
        engineInstance.addDocumentation(comments)
      catch
        case exception: ProcessingException =>
          reportProcessingException(exception)

  def processTypeVisitors()(using ctx: Context): Unit =
    if !usableOutput then
      return
    try
      engineInstance.processTypeVisitors()
    catch
      case exception: ProcessingException =>
        reportProcessingException(exception)

  /**
   * Whether the compiler's output can be written to at all, reported once.
   *
   * `-d` may name a jar, which scalac supports and this plugin does not: generated classes
   * go through `java.io.File`, not the compiler's `AbstractFile`. Without this the plugin
   * ran anyway and died inside the writer with
   * `ClassGenerationException: Unable to generate Bean entry at path: META-INF/micronaut/...`,
   * a crash report rather than a diagnostic. Reporting is not enough on its own -- the engine
   * is constructed lazily inside the generating phase, by which point the phase's own
   * runnability has already been decided -- so processing is skipped outright.
   */
  private def usableOutput(using ctx: Context): Boolean =
    if outputReported then
      outputUsable
    else
      outputReported = true
      val value = ctx.settings.outputDir.valueIn(ctx.settingsState)
      // Detected by type, not by path or `isDirectory`, because neither says what it looks
      // like it says: for `-d out.jar` the setting is a `JarArchive` whose `path` is "/" and
      // whose `isDirectory` is true. The plugin was therefore not writing "into a directory
      // beside the jar" as this was first described -- it was writing to the filesystem root.
      outputUsable = !value.isInstanceOf[JarArchive]
      if !outputUsable then
        report.error(
          "micronaut-scala cannot write to an archive output. " +
            "Compile to a directory with -d and package it afterwards."
        )
      outputUsable

  def processBeanDefinitions()(using ctx: Context): Unit =
    if usableOutput then
      try
        engineInstance.processBeanDefinitions()
      catch
        case exception: ProcessingException =>
          reportProcessingException(exception)

  private def engineInstance(using ctx: Context): ScalaProcessingEngine =
    var current = engine
    if current == null then
      current = ScalaProcessingEngine(
        outputDirectory,
        classpath.asJava,
        options,
        name => ScalaModelExtractor.resolveAnnotationType(name),
        name => ScalaModelExtractor.resolveClasspathClass(name),
        (message, element) => report.inform(message, sourcePosition(element)),
        (message, element) => report.warning(message, sourcePosition(element)),
        (message, element) => report.error(message, sourcePosition(element))
      )
      engine = current
    current

  /** The directory generated classes are written to; see `usableOutput`. */
  private def outputDirectory(using ctx: Context): File =
    val value = ctx.settings.outputDir.valueIn(ctx.settingsState)
    val output = File(value.path)
    if !output.isDirectory && !output.mkdirs() && !output.isDirectory then
      report.error(s"micronaut-scala could not create the output directory '${output.getPath}'")
    output

  private def classpath(using ctx: Context): List[File] =
    val value = ctx.settings.classpath.valueIn(ctx.settingsState)
    value.split(File.pathSeparator).toList.filter(_.nonEmpty).map(File(_))

  private def reportProcessingException(exception: ProcessingException)(using Context): Unit =
    val message = exception.getMessage
    val position = sourcePosition(exception.getOriginatingElement)
    if message != null && !message.isBlank then
      report.error(message, position)
    else
      report.error(processingExceptionMessage(exception), position)

  // Every extracted record keeps the dotty tree or symbol it came from as its native
  // type, and both `Positioned` and `Symbol` are already `SrcPos`. Without this the
  // whole diagnostic channel is just strings, so `report.error` falls back to
  // `NoSourcePosition` and every Micronaut error -- `@Inject` on a final field, an
  // invalid `@ConfigurationProperties`, any visitor `fail(...)` -- is printed with no
  // file, line or caret.
  private def sourcePosition(nativeType: Object | Null): SrcPos =
    nativeType match
      case position: SrcPos => position
      case _ => NoSourcePosition

  private def processingExceptionMessage(exception: ProcessingException): String =
    val element = exception.getElement
    val elementDescription = if element == null then "" else s" [${element.getName}]"
    s"Error processing Scala element$elementDescription: ${exceptionMessage(exception)}"

  /**
   * The first non-blank message in a cause chain.
   *
   * The chain is followed with a set of the exceptions already seen. Comparing only
   * against the head caught a two-element cycle and nothing deeper: a chain of
   * `a -> b -> c -> b` never returns to `a`, so the walk ran forever and hung the
   * compiler on a diagnostic.
   */
  private def exceptionMessage(exception: Throwable): String =
    val visited = java.util.IdentityHashMap[Throwable, java.lang.Boolean]()
    var current: Throwable | Null = exception
    var fallback: Throwable = exception
    var searching = true
    while searching do
      val candidate = current
      if candidate == null || visited.put(candidate, java.lang.Boolean.TRUE) != null then
        searching = false
      else
        fallback = candidate
        val message = candidate.getMessage
        if message != null && !message.isBlank then
          return message
        current = candidate.getCause
    val stackTrace = fallback.getStackTrace
    if stackTrace.isEmpty then fallback.getClass.getName
    else s"${fallback.getClass.getName} at ${stackTrace(0)}"

private final class TypeVisitorPhase(state: ProcessingState) extends PluginPhase:

  override val phaseName: String = "micronaut-scala-type-visitors"
  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(BeanDefinitionPhase.PhaseName)

  // Collected across every unit before any is extracted, so a class can see the defaults
  // of an annotation declared in a different file of the same compilation.
  private var annotationDefaults: Map[String, Map[String, Object]] = Map.empty

  override def run(using Context): Unit =
    val classes = ScalaModelExtractor.collect(summon[Context].compilationUnit, annotationDefaults)
    state.addClasses(classes)
    state.addDocumentation(ScalaModelExtractor.documentation(classes))

  // `Phase.runOn` invokes `run` only for units that pass `ctx.run.enterUnit(unit)`, and a
  // unit that suspends on a macro is dropped from the list entirely, so counting `run`
  // calls against `ctx.run.units.size` is not a reliable "seen them all" signal. `runOn`
  // itself is called exactly once per phase per run, after every unit, which is the point
  // the visitor pass actually wants.
  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    annotationDefaults = ScalaModelExtractor.annotationDefaults(units)
    val processed = super.runOn(units)
    state.processTypeVisitors()
    processed

private object BeanDefinitionPhase:
  val PhaseName = "micronaut-scala-bean-definitions"

private final class BeanDefinitionPhase(state: ProcessingState) extends PluginPhase:

  override val phaseName: String = BeanDefinitionPhase.PhaseName
  override val runsAfter: Set[String] = Set(TypeVisitorPhase(state).phaseName)
  override val runsBefore: Set[String] = Set(Pickler.name)

  // Generation is a whole-compilation step, not a per-unit one.
  override def run(using Context): Unit = ()

  override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
    val processed = super.runOn(units)
    state.processBeanDefinitions()
    processed

/**
 * Where a type is being modelled, which decides whether a derived value class is unboxed.
 *
 * A value class is unboxed exactly at the top level of a signature -- a field, a parameter, a
 * return type -- and stays boxed everywhere it is nested inside another type. Checked with
 * `javap` on dotty 3.9.0 output for `class UserId(val value: String) extends AnyVal`:
 * `def ret(): UserId` compiles to `java.lang.String ret()`, while `def list():
 * java.util.List[UserId]` compiles to `java.util.List<probe3.UserId> list()` and
 * `def arr(): Array[UserId]` to `probe3.UserId[] arr()`.
 *
 * Nesting propagates, so this is a contextual value: once inside a type argument or an array
 * component everything below it is nested too.
 */
private enum TypePosition:
  case TopLevel, Nested

private object ScalaModelExtractor:

  /** Types are modelled at the top level of a signature unless a caller says otherwise. */
  private given TypePosition = TypePosition.TopLevel


  private val CreatorAnnotationName = "io.micronaut.core.annotation.Creator"

  /** Synthetic marker carrying a Scala default-argument accessor across classloaders. */
  private val DefaultAccessorAnnotationName = "io.micronaut.scala.ScalaDefaultValue"

  private val ScalaPrimitiveNames = Map(
    "scala.Boolean" -> "boolean",
    "scala.Byte" -> "byte",
    "scala.Char" -> "char",
    "scala.Double" -> "double",
    "scala.Float" -> "float",
    "scala.Int" -> "int",
    "scala.Long" -> "long",
    "scala.Short" -> "short",
    "scala.Unit" -> "void"
  )

  private val ScalaClassLiteralAliases = Map(
    "Boolean" -> "boolean",
    "Byte" -> "byte",
    "Char" -> "char",
    "Double" -> "double",
    "Float" -> "float",
    "Int" -> "int",
    "Long" -> "long",
    "Short" -> "short",
    "String" -> classOf[String].getName,
    "Unit" -> "void"
  )

  private val BoxedPrimitiveNames = Map(
    "boolean" -> classOf[java.lang.Boolean].getName,
    "byte" -> classOf[java.lang.Byte].getName,
    "char" -> classOf[java.lang.Character].getName,
    "double" -> classOf[java.lang.Double].getName,
    "float" -> classOf[java.lang.Float].getName,
    "int" -> classOf[java.lang.Integer].getName,
    "long" -> classOf[java.lang.Long].getName,
    "short" -> classOf[java.lang.Short].getName,
    "void" -> classOf[java.lang.Void].getName
  )

  private val VoidTypeData = ScalaTypeData("void", primitive = true, arrayDimensions = 0, interfaceType = false, java.util.Map.of())

  private case class AnnotationMemberType(
      name: String,
      array: Boolean,
      classType: Boolean,
      enumType: Boolean,
      annotationType: Boolean
  )

  private case class AnnotationDefaults(values: Map[String, Map[String, Object]])

  private case class TypeHierarchy(superType: ScalaTypeData | Null, interfaces: List[ScalaTypeData])

  private val PositionalAnnotationMemberPrefix = "$micronaut$pos$"
  private val NullableAnnotationName = "io.micronaut.core.annotation.Nullable"
  private val NullabilityAnnotationNames = Set(
    NullableAnnotationName,
    "jakarta.annotation.Nullable",
    "javax.annotation.Nullable",
    "org.jspecify.annotations.Nullable",
    "io.micronaut.core.annotation.NonNull",
    "jakarta.annotation.Nonnull",
    "javax.annotation.Nonnull",
    "org.jspecify.annotations.NonNull"
  )
  private val NullableAnnotationData = ScalaAnnotationData(NullableAnnotationName, JMap.of[CharSequence, Object]())

  /**
   * Annotation member defaults declared anywhere in this compilation.
   *
   * Harvesting them from a single unit made an annotation's defaults depend on which file
   * it was declared in: `@MyAnn` used in one file picked up no defaults at all when
   * `MyAnn` was declared in another, even though both were being compiled together.
   */
  def annotationDefaults(units: List[CompilationUnit])(using Context): Map[String, Map[String, Object]] =
    units.foldLeft(Map.empty[String, Map[String, Object]]) { (collected, unit) =>
      collected ++ annotationDefaultValues(unit.tpdTree)
    }

  /**
   * The documentation comments written on the declarations of the collected classes.
   *
   * A comment is not part of the tree it documents. The parser puts it in a side table keyed
   * by symbol -- `Comments.docCtx`, which dotty populates without any compiler flag -- so
   * unlike an annotation it cannot be carried inside the extracted model, and is paired here
   * with the native object each element reports as its own identity.
   *
   * Parameters are absent on purpose: no language documents a parameter where the parameter
   * is. A `@param` tag on the method, or on the class for a case class's properties, is what
   * documents one, and reading it is the parameter element's job.
   *
   * @param classes The classes just collected from this unit
   * @return The raw comments, keyed by native compiler object
   */
  def documentation(classes: List[ScalaClassData])(using Context): JMap[Object, String] =
    val comments = JIdentityHashMap[Object, String]()
    Comments.docCtx(summon[Context]).foreach { docstrings =>
      def record(nativeType: Object): Unit =
        symbolOf(nativeType).foreach { symbol =>
          if symbol.exists then
            docstrings.docstring(symbol).foreach(comment => comments.put(nativeType, comment.raw))
        }
      classes.foreach { classData =>
        record(classData.nativeType())
        classData.methods().asScala.foreach(method => record(method.nativeType()))
        classData.constructors().asScala.foreach(constructor => record(constructor.nativeType()))
        classData.fields().asScala.foreach(field => record(field.nativeType()))
        classData.properties().asScala.foreach(property => record(property.nativeType()))
      }
    }
    comments

  /**
   * The symbol behind whatever an element reports as its native type, which is a tree for some
   * kinds of declaration and the symbol itself for others.
   */
  private def symbolOf(nativeType: Object)(using Context): Option[Symbol] =
    nativeType match
      case symbol: Symbol => Some(symbol)
      case tree: tpd.Tree @unchecked => Some(tree.symbol)
      case _ => None

  def collect(unit: CompilationUnit, defaults: Map[String, Map[String, Object]])(using Context): List[ScalaClassData] =
    given AnnotationDefaults = AnnotationDefaults(defaults)
    val classes = ListBuffer.empty[ScalaClassData]
    collectTree(unit.tpdTree, classes, null)
    classes.toList

  private def collectTree(tree: tpd.Tree, classes: ListBuffer[ScalaClassData], enclosingTypeName: String | Null)(using Context, AnnotationDefaults): Unit =
    tree match
      case packageDef: tpd.PackageDef =>
        packageDef.stats.foreach(stat => collectTree(stat, classes, null))
      case typeDef: tpd.TypeDef if typeDef.isClassDef =>
        val classData = toClassData(typeDef, enclosingTypeName)
        classData.foreach(classes += _)
        typeDef.rhs match
          case template: tpd.Template =>
            val nestedEnclosingTypeName = classData
              .map(_.name())
              .orElse(companionClassName(typeDef.symbol))
              .orNull
            if nestedEnclosingTypeName != null then
              template.body.foreach(stat => collectTree(stat, classes, nestedEnclosingTypeName))
          case _ =>
      case _ =>

  private def annotationDefaultValues(tree: tpd.Tree)(using Context): Map[String, Map[String, Object]] =
    given AnnotationDefaults = AnnotationDefaults(Map.empty)
    val parameterNames = scala.collection.mutable.LinkedHashMap[String, List[String]]()
    val defaultValues = scala.collection.mutable.LinkedHashMap[String, scala.collection.mutable.LinkedHashMap[String, Object]]()
    collectAnnotationParameterNames(tree, parameterNames)
    collectAnnotationDefaultValues(tree, parameterNames.toMap, defaultValues)
    defaultValues.view.mapValues(_.toMap).toMap

  private def collectAnnotationParameterNames(
      tree: tpd.Tree,
      parameterNames: scala.collection.mutable.LinkedHashMap[String, List[String]]
  )(using Context): Unit =
    tree match
      case packageDef: tpd.PackageDef =>
        packageDef.stats.foreach(collectAnnotationParameterNames(_, parameterNames))
      case typeDef: tpd.TypeDef if typeDef.isClassDef =>
        typeDef.rhs match
          case template: tpd.Template =>
            val symbol = typeDef.symbol
            if isAnnotationSymbol(symbol) then
              parameterNames.put(className(symbol), template.constr.termParamss.flatten.map(_.name.toString))
            template.body.foreach(collectAnnotationParameterNames(_, parameterNames))
          case _ =>
      case _ =>

  private def collectAnnotationDefaultValues(
      tree: tpd.Tree,
      parameterNames: Map[String, List[String]],
      defaultValues: scala.collection.mutable.LinkedHashMap[String, scala.collection.mutable.LinkedHashMap[String, Object]]
  )(using Context, AnnotationDefaults): Unit =
    tree match
      case packageDef: tpd.PackageDef =>
        packageDef.stats.foreach(collectAnnotationDefaultValues(_, parameterNames, defaultValues))
      case typeDef: tpd.TypeDef if typeDef.isClassDef =>
        typeDef.rhs match
          case template: tpd.Template =>
            val ownerName = className(typeDef.symbol).stripSuffix("$")
            parameterNames.get(ownerName).foreach { names =>
              template.body.collect { case method: tpd.DefDef => method }.foreach { method =>
                defaultGetterIndex(method.name.toString).foreach { index =>
                  if index > 0 && index <= names.size then
                    val value = annotationValue(method.rhs)
                    if value != null then
                      val values = defaultValues.getOrElseUpdate(ownerName, scala.collection.mutable.LinkedHashMap[String, Object]())
                      values.put(names(index - 1), value)
                }
              }
            }
            template.body.foreach(collectAnnotationDefaultValues(_, parameterNames, defaultValues))
          case _ =>
      case _ =>

  private def defaultGetterIndex(name: String): Option[Int] =
    val prefix = "$lessinit$greater$default$"
    if name.startsWith(prefix) then
      name.stripPrefix(prefix).toIntOption
    else
      None

  private val InternalAnnotationPrefix = "scala.annotation.internal."

  private val FactoryAnnotationName = "io.micronaut.context.annotation.Factory"
  private val BeanAnnotationName = "io.micronaut.context.annotation.Bean"
  private val ModuleInstanceFieldName = "MODULE$"

  private def syntheticAnnotation(name: String)(using Context, AnnotationDefaults): ScalaAnnotationData =
    val symbol = classSymbolForName(name)
    val annotationType = if isAnnotationSymbol(symbol) then annotationTypeData(symbol, Set.empty) else null
    ScalaAnnotationData(name, java.util.Map.of[CharSequence, Object](), annotationType)

  /**
   * A Scala `object` compiles to a class with a private constructor and a public static
   * `MODULE$` holding the single instance, so Micronaut cannot instantiate it the way it
   * instantiates a class. It is modelled instead as a factory that produces that field:
   * the synthetic `MODULE$` field carries the object's own annotations, so the bean keeps
   * its scope and qualifiers, and the class carries only `@Factory` -- annotating the
   * class itself would make Micronaut try to construct the private constructor.
   */
  private def moduleClassData(data: ScalaClassData, symbol: Symbol)(using Context, AnnotationDefaults): ScalaClassData =
    val instanceType = ScalaTypeData(data.name(), primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of())
    val instanceField = ScalaFieldData(
      ModuleInstanceFieldName,
      instanceType,
      (data.annotations().asScala.toList :+ syntheticAnnotation(BeanAnnotationName)).asJava,
      java.util.Set.of(ElementModifier.PUBLIC, ElementModifier.STATIC, ElementModifier.FINAL),
      false,
      null,
      symbol
    )
    ScalaClassData(
      data.name(),
      List(syntheticAnnotation(FactoryAnnotationName)).asJava,
      data.modifiers(),
      data.annotationType(),
      data.interfaceType(),
      data.enumType(),
      data.typeParameters(),
      data.superType(),
      data.interfaces(),
      data.constructors(),
      data.methods(),
      (data.fields().asScala.toList :+ instanceField).asJava,
      data.properties(),
      data.enclosingTypeName(),
      data.nativeType()
    )

  private def toClassData(typeDef: tpd.TypeDef, enclosingTypeName: String | Null)(using Context, AnnotationDefaults): Option[ScalaClassData] =
    val symbol = typeDef.symbol
    if skipClass(symbol) then
      None
    else
      typeDef.rhs match
        case template: tpd.Template =>
          val declarations = symbol.info.decls.toList
          val allMethods = template.body.collect { case method: tpd.DefDef => method }
          // Computed once per method: this record was previously built twice for every
          // method in the body, once for the accessor map and once for the method list.
          val parameterAnnotations = constructorParameterAnnotations(template.constr)
          val extractedMethods = allMethods.map(method =>
            method -> withParameterAnnotations(
              methodData(method, constructor = false, owner = symbol), parameterAnnotations))
          val methodByName = accessorCandidates(
            extractedMethods.map((method, data) => method.symbol -> data), declarations, parameterAnnotations)
          val methods = extractedMethods
            .filterNot((method, _) => skipMethod(method.symbol))
            .map((_, data) => data)
          val enumMethods =
            if hasFlag(symbol, Flags.Enum) then List(enumValueOfMethodData(symbol))
            else Nil
          val companionCreators = companionStaticCreators(symbol)
          val fields = template.body.collect {
            case field: tpd.ValDef if !skipField(field.symbol) => fieldData(field)
          }
          val enumConstants = enumConstantSymbols(symbol)
            .map(enumConstantFieldData(_, symbol))
          val allFields = (fields ++ enumConstants).distinctBy(_.name())
          // `template.constr` is only the primary constructor. Scala secondary constructors are
          // ordinary `DefDef`s in the template body whose symbol is a constructor, and Micronaut
          // needs them so `@Inject` on one is honoured and so a class whose only no-argument
          // constructor is secondary still reports a default constructor.
          val secondaryConstructors = allMethods
            .filter(method => method.symbol != template.constr.symbol && method.symbol.denot.isConstructor)
            .filterNot(method => hasFlag(method.symbol, Flags.Synthetic) || hasFlag(method.symbol, Flags.Artifact))
            .map(method => methodData(method, constructor = true, owner = symbol))
          val constructors = methodData(template.constr, constructor = true, owner = symbol) +: secondaryConstructors
          val constructorProps = constructorProperties(constructorParameters(template.constr), methodByName, allFields)
          val properties = withPropertyAnnotations(
            constructorProps ++ bodyProperties(declarations, methodByName, allFields, constructorProps.map(_.name).toSet),
            parameterAnnotations
          )
          val parents = withoutObject(template.parents.map(typeData))
          val superType = parents.find(parent => !parent.interfaceType()).orNull
          val interfaces = parents.filter(_.interfaceType())
          val classData = ScalaClassData(
            className(symbol),
            annotations(symbol).asJava,
            modifiers(symbol).asJava,
            isAnnotationSymbol(symbol),
            isInterfaceSymbol(symbol),
            hasFlag(symbol, Flags.Enum),
            typeParameters(symbol).asJava,
            superType,
            interfaces.asJava,
            constructors.asJava,
            (methods ++ enumMethods ++ companionCreators).asJava,
            allFields.asJava,
            properties.asJava,
            enclosingTypeName,
            typeDef
          )
          Some(if hasFlag(symbol, Flags.ModuleClass) then moduleClassData(classData, symbol) else classData)
        case _ =>
          None

  /**
   * A class's parents as the model reports them, without `Object`.
   *
   * `Object` is never a supertype worth reporting, and filtering it by the parent's own name
   * was not enough: the compiler gives `java.lang.Object` itself the parent `scala.Any`, and
   * `Any` is modelled as `Object` because that is what it erases to -- so `Object` reported
   * `Object` as its superclass, and every walk up a hierarchy that reached it never returned.
   * The filter has to apply to the name the model reports.
   */
  private def withoutObject(parents: List[ScalaTypeData]): List[ScalaTypeData] =
    parents.filterNot(parent => parent.name() == classOf[Object].getName)

  /**
   * The members that may back a property, by name: accessor-shaped methods and the `val`/`var`
   * declarations themselves.
   *
   * Only accessor-shaped methods are candidates. Keying every method by its bare name meant an
   * overload displaced the accessor -- for `def value: String` plus `def value(i: Int): String`
   * the last one written won, and the property took its type, modifiers and annotations from
   * the overload.
   */
  private def accessorCandidates(
      extractedMethods: List[(Symbol, ScalaMethodData)],
      declarations: List[Symbol],
      parameterAnnotations: Map[String, List[ScalaAnnotationData]]
  )(using Context, AnnotationDefaults): LinkedHashMap[String, ScalaMethodData] =
    val methodByName = LinkedHashMap[String, ScalaMethodData]()
    extractedMethods.foreach { (method, data) =>
      if !skipAccessorCandidate(method) then
        val name = method.name.toString
        val isWriteAccessor = name.endsWith("_=")
        val parameterCount = data.parameters().size
        if (isWriteAccessor && parameterCount == 1) || (!isWriteAccessor && parameterCount == 0) then
          methodByName.put(name, data)
    }
    declarations.foreach { declaration =>
      val declarationName = declaration.name.toString
      if isPropertyDeclaration(declaration, declarationName) || isPropertySetterDeclaration(declaration, declarationName) then
        methodByName.put(declarationName, withParameterAnnotations(methodData(declaration), parameterAnnotations))
    }
    methodByName

  /**
   * The model of a Scala class the compiler read from the classpath, or `null` when the name is
   * not a Scala class it knows.
   *
   * This is the shape of every incremental build: the files that changed are compiled, and
   * every type they refer to is read back from its class file and TASTy. Reading those with
   * Java's conventions -- the only thing a classloader can do -- described a case class by the
   * tuple accessors it compiles to, `_1, _2, _3`, because a Scala accessor is named for its
   * property and follows no getter convention there is to recognise. Micronaut Data rejected
   * every entity on the second compilation of a repository.
   *
   * The compiler already knows better. A classpath Scala type is unpickled into the same
   * symbols a source type has, flags and all, so the model is built from those with the same
   * rules the source path applies. The two paths differ only where a class file has nothing to
   * read: constant expressions and doc comments.
   *
   * A Java class is read the same way. Its symbol is the compiler's reading of the class file
   * -- generics from the `Signature` attribute, names from `MethodParameters`, annotations of
   * both retentions on the class and its members -- with one omission: dotty's parser does not
   * read the annotations on parameters, and those are where Micronaut looks most. They are
   * read from the class file's bytes with the JDK's class-file API and added to the model;
   * see [[ClassFileParameterAnnotations]]. Nothing is loaded for any of it.
   */
  def resolveClasspathClass(name: String)(using Context): ScalaClassData | Null =
    given AnnotationDefaults = AnnotationDefaults(Map.empty)
    val symbol = classSymbolForName(name)
    if symbol == Symbols.NoSymbol || !symbol.isClass || hasFlag(symbol, Flags.PackageClass) then
      null
    else
      classpathClassData(symbol.asClass)

  private def classpathClassData(symbol: ClassSymbol)(using Context, AnnotationDefaults): ScalaClassData =
    val java = hasFlag(symbol, Flags.JavaDefined)
    val enclosingTypeName = enclosingClassName(symbol)
    // The compiler keeps a Java class's static members on its companion module class, which
    // is where a Scala object's members live too; for a Java class they are simply the rest
    // of the class.
    val declarations = symbol.info.decls.toList ++ (if java then javaStaticDeclarations(symbol) else Nil)
    val classFile = if java then classFileParameterAnnotations(symbol) else None
    val primaryConstructor = symbol.primaryConstructor
    val parameters = constructorParameters(primaryConstructor)
    val parameterAnnotations = constructorParameterAnnotations(parameters)
    val extractedMethods = declarations
      .filter(member => member.isTerm && hasFlag(member, Flags.Method) && !member.denot.isConstructor)
      .map(method => method -> withParameterAnnotations(
        methodData(method, constructor = false, owner = symbol, classFileAnnotations(classFile, method)), parameterAnnotations))
    val methodByName = accessorCandidates(extractedMethods, declarations, parameterAnnotations)
    val methods = extractedMethods
      .filterNot((method, _) => skipMethod(method))
      .map((_, data) => data) ++ classFile.toList.flatMap(reader => javaPrivateMethods(symbol, reader))
    // A Java enum has real `values` and `valueOf` statics, and a Java class's statics are
    // already its own; the two companion-object devices are Scala's.
    val enumMethods =
      if hasFlag(symbol, Flags.Enum) && !java then List(enumValueOfMethodData(symbol))
      else Nil
    val companionCreators = if java then Nil else companionStaticCreators(symbol)
    val fields = declarations
      .filter(member => member.isTerm && !hasFlag(member, Flags.Method) && !member.denot.isConstructor && !skipField(member))
      .map(field => fieldData(field, classFileFieldAnnotations(classFile, field)))
      ++ classFile.toList.flatMap(reader => javaPrivateFields(symbol, reader))
    val enumConstants = enumConstantSymbols(symbol)
      .map(enumConstantFieldData(_, symbol))
    val allFields = (fields ++ enumConstants).distinctBy(_.name())
    val secondaryConstructors = declarations
      .filter(member => member != primaryConstructor && member.denot.isConstructor)
      .filterNot(member => hasFlag(member, Flags.Synthetic) || hasFlag(member, Flags.Artifact))
      .map(constructor => methodData(constructor, constructor = true, owner = symbol, classFileAnnotations(classFile, constructor)))
    val constructors =
      if primaryConstructor == Symbols.NoSymbol then secondaryConstructors
      else methodData(primaryConstructor, constructor = true, owner = symbol, classFileAnnotations(classFile, primaryConstructor)) +: secondaryConstructors
    // A Java class has no Scala properties. What it has are getters and setters, which core
    // derives from the methods by Java's own conventions when the element is asked.
    val constructorProps = if java then Nil else constructorProperties(parameters, methodByName, allFields)
    val properties =
      if java then Nil
      else withPropertyAnnotations(
        constructorProps ++ bodyProperties(declarations, methodByName, allFields, constructorProps.map(_.name).toSet),
        parameterAnnotations
      )
    val parents = withoutObject(symbol.classInfo.declaredParents.map(typeData))
    val superType = parents.find(parent => !parent.interfaceType()).orNull
    val interfaces = parents.filter(_.interfaceType())
    val classData = ScalaClassData(
      className(symbol),
      annotations(symbol).asJava,
      modifiers(symbol).asJava,
      isAnnotationSymbol(symbol),
      isInterfaceSymbol(symbol),
      hasFlag(symbol, Flags.Enum),
      typeParameters(symbol).asJava,
      superType,
      interfaces.asJava,
      constructors.asJava,
      (methods ++ enumMethods ++ companionCreators).asJava,
      allFields.asJava,
      properties.asJava,
      enclosingTypeName,
      symbol,
      nestedClassNames(symbol, java).asJava
    )
    if hasFlag(symbol, Flags.ModuleClass) then moduleClassData(classData, symbol) else classData

  /**
   * The classes declared inside a class. A Java class's static nested classes live on its
   * module class, and each of them has a module class of its own for *its* statics; those are
   * the compiler's device, not types the class file declares, and are left out.
   */
  private def nestedClassNames(symbol: ClassSymbol, java: Boolean)(using Context): List[String] =
    def declaredClasses(owner: Symbol): List[Symbol] =
      owner.info.decls.toList.filter { member =>
        member.isClass && !hasFlag(member, Flags.Synthetic) && !(java && hasFlag(member, Flags.ModuleClass))
      }
    val statics =
      if java && symbol.companionModule != Symbols.NoSymbol then declaredClasses(symbol.companionModule.moduleClass)
      else Nil
    (declaredClasses(symbol) ++ statics).map(className).distinct

  /**
   * A method or constructor read from its symbol, for a class the compiler read from the
   * classpath. The tree form reads the same things from the definition; a classpath class has
   * no definition to read, and everything needed is on the symbol.
   */
  private def methodData(
      symbol: Symbol,
      constructor: Boolean,
      owner: Symbol,
      classFile: ClassFileAnnotations = ClassFileAnnotations.None
  )(using Context, AnnotationDefaults): ScalaMethodData =
    val returnType =
      if constructor then
        ScalaTypeData(className(owner), primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of())
      else
        methodReturnType(symbol.name.toString, symbol.info.finalResultType)
    val declared = annotations(symbol) ++ typeUseNullabilityAnnotations(returnType)
    val methodAnnotations = declared ++ newAnnotations(declared, classFile.returnType)
    val parameters = symbol.paramSymss.flatten.filter(_.isTerm).map(parameterData)
      .zipAll(classFile.parameters, null, Nil)
      .collect { case (parameter, fromClassFile) if parameter != null => withAnnotations(parameter, fromClassFile) }
    ScalaMethodData(
      if constructor then "<init>" else methodName(symbol.name.toString),
      returnType,
      parameters.asJava,
      (if constructor then Nil else typeParameters(symbol)).asJava,
      thrownTypes(symbol).asJava,
      methodAnnotations.asJava,
      modifiers(symbol).asJava,
      constructor,
      symbol,
      (if constructor then Nil else overriddenMethods(symbol)).asJava
    )

  /** A field read from its symbol; see [[methodData(Symbol, Boolean, Symbol)]]. */
  private def fieldData(symbol: Symbol, fromClassFile: List[ScalaAnnotationData] = Nil)(using Context, AnnotationDefaults): ScalaFieldData =
    val fieldType = typeData(symbol.info)
    val declared = annotations(symbol) ++ typeUseNullabilityAnnotations(fieldType)
    val fieldAnnotations = declared ++ newAnnotations(declared, fromClassFile)
    // A Scala `val` is a private field behind a public accessor, whatever it was declared as; a
    // Java field is the member itself, and its modifiers are what the class file says.
    val fieldModifiers = if hasFlag(symbol, Flags.JavaDefined) then modifiers(symbol) else backingFieldModifiers(symbol)
    ScalaFieldData(
      symbol.name.toString,
      fieldType,
      fieldAnnotations.asJava,
      fieldModifiers.asJava,
      isEnumConstant(symbol),
      // A `final val` keeps its literal as a constant type, which is all a class file keeps of
      // a constant expression either.
      if hasFlag(symbol, Flags.Mutable) then null else constantValue(symbol.info),
      symbol
    )

  /**
   * The annotations written on each primary-constructor parameter, by parameter name.
   *
   * A Scala class parameter is one declaration that becomes a private field, a pair of
   * accessors and a property, and an annotation written on it stays on the parameter: Scala
   * applies an annotation where it is written unless the annotation opts into
   * `scala.annotation.meta` targets. Java has the same shape in a record and resolves it in the
   * compiler, propagating a record component's annotations to the field and the accessor, so
   * `@Id` on a record component is already on the property by the time a visitor looks.
   *
   * Without the same step the annotation is reachable only from the constructor parameter,
   * which is not where anything looks: `@Id` on a `@MappedEntity` case class was invisible and
   * Micronaut Data rejected the entity for having no identity.
   *
   * @param constructor The primary constructor
   * @return The annotations of each parameter that has any
   */
  private def constructorParameterAnnotations(
      constructor: tpd.DefDef
  )(using Context, AnnotationDefaults): Map[String, List[ScalaAnnotationData]] =
    constructorParameterAnnotations(constructorParameters(constructor))

  private def constructorParameterAnnotations(
      parameters: List[ConstructorParameter]
  )(using Context, AnnotationDefaults): Map[String, List[ScalaAnnotationData]] =
    parameters
      .map(parameter => parameter.symbol.name.toString -> annotations(parameter.symbol))
      .filter(_._2.nonEmpty)
      .toMap

  /**
   * Carries a class parameter's annotations onto the accessor it generates.
   *
   * The accessor is where they have to land rather than only on the property: annotation
   * metadata is cached against the native symbol an element was built from, and a property
   * backed by an accessor shares that symbol with the accessor, so whichever is built first
   * decides what both report.
   *
   * @param method The extracted method
   * @param parameterAnnotations The annotations of each constructor parameter
   * @return The method, carrying the annotations of the parameter it accesses
   */
  private def withParameterAnnotations(
      method: ScalaMethodData,
      parameterAnnotations: Map[String, List[ScalaAnnotationData]]
  ): ScalaMethodData =
    if parameterAnnotations.isEmpty || method.parameters().size() > 0 then
      method
    else
      parameterAnnotations.get(method.name()) match
        case None => method
        case Some(fromParameter) =>
          val added = newAnnotations(method.annotations().asScala.toList, fromParameter)
          if added.isEmpty then
            method
          else
            ScalaMethodData(
              method.name(),
              method.returnType(),
              method.parameters(),
              method.typeParameters(),
              method.thrownTypes(),
              (method.annotations().asScala.toList ++ added).asJava,
              method.modifiers(),
              method.constructor(),
              method.nativeType(),
              method.overriddenMethods()
            )

  /**
   * Carries a class parameter's annotations onto the property it declares, for a property that
   * has no accessor to carry them instead.
   *
   * @param properties The properties collected for the class
   * @param parameterAnnotations The annotations of each constructor parameter
   * @return The properties, each carrying the annotations of the parameter of the same name
   */
  private def withPropertyAnnotations(
      properties: List[ScalaPropertyData],
      parameterAnnotations: Map[String, List[ScalaAnnotationData]]
  ): List[ScalaPropertyData] =
    if parameterAnnotations.isEmpty then
      properties
    else
      properties.map { property =>
        parameterAnnotations.get(property.name()) match
          case None => property
          case Some(fromParameter) =>
            val added = newAnnotations(property.annotations().asScala.toList, fromParameter)
            if added.isEmpty then
              property
            else
              ScalaPropertyData(
                property.name(),
                property.`type`(),
                property.readMethod(),
                property.writeMethod(),
                property.field(),
                (property.annotations().asScala.toList ++ added).asJava,
                property.modifiers(),
                property.nativeType()
              )
      }

  /** The annotations of `candidates` that `existing` does not already declare. */
  private def newAnnotations(
      existing: List[ScalaAnnotationData],
      candidates: List[ScalaAnnotationData]
  ): List[ScalaAnnotationData] =
    val declared = existing.map(_.name()).toSet
    candidates.filterNot(annotation => declared.contains(annotation.name()))

  /**
   * A primary-constructor parameter, from whichever form of the class is at hand.
   *
   * @param symbol The parameter symbol
   * @param tpe The declared type
   * @param nativeType The object an element built from this parameter is keyed on: the tree for
   *     a source class, the symbol for a classpath one. A property and the parameter declaring it
   *     share it, so the annotation metadata cached for one is what the other reports.
   */
  private final case class ConstructorParameter(symbol: Symbol, tpe: Type, nativeType: Object)

  private def constructorParameters(constructor: tpd.DefDef)(using Context): List[ConstructorParameter] =
    constructor.termParamss.flatten.map(param => ConstructorParameter(param.symbol, param.tpt.tpe, param))

  private def constructorParameters(constructor: Symbol)(using Context): List[ConstructorParameter] =
    if constructor == Symbols.NoSymbol then Nil
    else constructor.paramSymss.flatten.filter(_.isTerm).map(param => ConstructorParameter(param, param.info, param))

  private def constructorProperties(
      parameters: List[ConstructorParameter],
      methods: LinkedHashMap[String, ScalaMethodData],
      fields: List[ScalaFieldData]
  )(using Context, AnnotationDefaults): List[ScalaPropertyData] =
    parameters
      .filter { param =>
        val propertyName = param.symbol.name.toString
        val readMethod = methods.get(propertyName)
        val field = fields.find(_.name == propertyName).orNull
        val propertyAccessor = hasFlag(param.symbol, Flags.ParamAccessor) ||
          hasFlag(param.symbol, Flags.CaseAccessor)
        propertyAccessor &&
          ((readMethod != null && !readMethod.modifiers().contains(ElementModifier.PRIVATE)) ||
            (field != null && !field.modifiers().contains(ElementModifier.PRIVATE)))
      }
      .map { param =>
        val propertyName = param.symbol.name.toString
        val readMethod = methods.get(propertyName)
        val writeMethod = methods.get(propertyName + "_=")
        val field = fields.find(_.name == propertyName).orNull
        ScalaPropertyData(
          propertyName,
          typeData(param.tpe),
          readMethod,
          writeMethod,
          field,
          annotations(param.symbol).asJava,
          modifiers(param.symbol).asJava,
          param.nativeType
        )
      }

  private def bodyProperties(
      declarations: List[Symbol],
      methods: LinkedHashMap[String, ScalaMethodData],
      fields: List[ScalaFieldData],
      knownProperties: Set[String]
  )(using Context): List[ScalaPropertyData] =
    val fieldsByName = fields.map(field => field.name -> field).toMap
    val added = LinkedHashSet[String]()
    knownProperties.foreach(added.add)
    val properties = ListBuffer.empty[ScalaPropertyData]
    declarations.foreach { declaration =>
      val propertyName = declaration.name.toString
      if isPropertyDeclaration(declaration, propertyName) && !added.contains(propertyName) then
        val readMethod = methods.get(propertyName)
        val writeMethod = methods.get(propertyName + "_=")
        if readMethod != null && !readMethod.modifiers().contains(ElementModifier.PRIVATE) then
          val field = fieldsByName.getOrElse(propertyName, null)
          properties += ScalaPropertyData(
            propertyName,
            if field == null then readMethod.returnType() else field.`type`(),
            readMethod,
            writeMethod,
            field,
            propertyAnnotations(readMethod, field),
            propertyModifiers(readMethod, writeMethod, field),
            declaration
          )
          added.add(propertyName)
      }
    properties ++= accessorPairProperties(methods, added)
    properties.toList

  /**
   * Properties written as a pair of methods rather than as a `val` or `var`.
   *
   * `def size: Int` with `def size_=(v: Int): Unit` is how Scala spells a property whose
   * storage is not a field -- computed, delegated, or backed by something the class does not
   * own. Only field-backed members were assembled, so both halves were visible as ordinary
   * methods and `@Introspected` exposed no property at all, while the equivalent `var` did.
   *
   * The pair is required. A lone `def size` stays a method: without a prefix to strip there is
   * nothing to tell an accessor from any other no-argument method, and treating every one of
   * them as a property would make a property out of `toString`. `@AccessorsStyle` remains the
   * way to declare that prefix-free reads are accessors. A setter whose parameter type does not
   * match the getter's return type is not a pair either -- it is an unrelated method that
   * happens to be named for one.
   */
  private def accessorPairProperties(
      methods: LinkedHashMap[String, ScalaMethodData],
      added: LinkedHashSet[String]
  ): List[ScalaPropertyData] =
    val properties = ListBuffer.empty[ScalaPropertyData]
    methods.asScala.foreach { (name, readMethod) =>
      if !name.endsWith("_=") && !added.contains(name)
        && !readMethod.modifiers().contains(ElementModifier.PRIVATE) then
        val writeMethod = methods.get(name + "_=")
        if writeMethod != null
          && !writeMethod.modifiers().contains(ElementModifier.PRIVATE)
          && writeMethod.parameters().size == 1
          && writeMethod.parameters().get(0).`type`().name() == readMethod.returnType().name() then
          properties += ScalaPropertyData(
            name,
            readMethod.returnType(),
            readMethod,
            writeMethod,
            null,
            propertyAnnotations(readMethod, null),
            propertyModifiers(readMethod, writeMethod, null),
            readMethod.nativeType()
          )
          added.add(name)
    }
    properties.toList

  private def isPropertyDeclaration(symbol: Symbol, name: String)(using Context): Boolean =
    name.nonEmpty &&
      !name.contains("$") &&
      !name.endsWith("_=") &&
      !name.startsWith("<") &&
      symbol.isTerm &&
      !symbol.denot.isConstructor &&
      !hasFlag(symbol, Flags.Method) &&
      !hasFlag(symbol, Flags.Module) &&
      !hasFlag(symbol, Flags.Artifact)

  private def isPropertySetterDeclaration(symbol: Symbol, name: String)(using Context): Boolean =
    name.endsWith("_=") &&
      symbol.isTerm &&
      hasFlag(symbol, Flags.Accessor)

  // Scala lets an annotation be targeted at the getter or the backing field
  // independently -- `@(Inject @getter) @(Named @field)("x") val foo` puts one on each --
  // so this is a union, not an either/or. Taking only the getter's annotations whenever it
  // had any silently discarded every field-targeted annotation on such a property.
  private def propertyAnnotations(readMethod: ScalaMethodData, field: ScalaFieldData | Null): java.util.List[ScalaAnnotationData] =
    val readAnnotations = if readMethod == null then Nil else readMethod.annotations().asScala.toList
    val fieldAnnotations = if field == null then Nil else field.annotations().asScala.toList
    val readNames = readAnnotations.map(_.name()).toSet
    // The getter wins a name clash: it is the member Micronaut reads the property through.
    (readAnnotations ++ fieldAnnotations.filterNot(annotation => readNames.contains(annotation.name()))).asJava

  private def propertyModifiers(
      readMethod: ScalaMethodData,
      writeMethod: ScalaMethodData | Null,
      field: ScalaFieldData | Null
  ): java.util.Set[ElementModifier] =
    if readMethod != null then
      readMethod.modifiers()
    else if writeMethod != null then
      writeMethod.modifiers()
    else if field != null then
      field.modifiers()
    else
      java.util.Set.of(ElementModifier.PUBLIC)

  private def methodData(method: tpd.DefDef, constructor: Boolean, owner: Symbol)(using Context, AnnotationDefaults): ScalaMethodData =
    val returnType =
      if constructor then
        ScalaTypeData(className(owner), primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of())
      else
        methodReturnType(method.name.toString, method.tpt)
    val methodAnnotations = annotations(method.symbol) ++ typeUseNullabilityAnnotations(returnType)
    ScalaMethodData(
      if constructor then "<init>" else methodName(method.name.toString),
      returnType,
      method.termParamss.flatten.map(parameterData).asJava,
      (if constructor then Nil else typeParameters(method)).asJava,
      thrownTypes(method.symbol).asJava,
      methodAnnotations.asJava,
      modifiers(method.symbol).asJava,
      constructor,
      method,
      (if constructor then Nil else overriddenMethods(method.symbol)).asJava
    )

  /**
   * The declarations a method overrides, least specific first.
   *
   * An annotation on an overridden declaration belongs to the override too -- core's Java module
   * puts the overridden methods in the annotation hierarchy ahead of the method itself, so they
   * arrive as inherited rather than declared. Nothing here did, and the hierarchy for a method
   * was the method alone. A concrete trait method hid it, because the trait's own method is
   * inherited whole and carries its annotations with it; an *abstract* trait method has nothing
   * to inherit, so `@Executable` on one reached no implementation and the method was simply
   * absent from the definition.
   *
   * The overridden data is built without its own overridden list: the walk is already transitive
   * through `allOverriddenSymbols`, and recursing would rebuild the same declarations once per
   * level.
   */
  private def overriddenMethods(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaMethodData] =
    if symbol == Symbols.NoSymbol || !symbol.isTerm then
      Nil
    else
      symbol.allOverriddenSymbols
        .filter(overridden =>
          overridden != Symbols.NoSymbol &&
            overridden.isTerm &&
            // Only declarations being compiled here. A classpath declaration's annotations reach
            // an implementation through the loaded-element path instead, and modelling one from
            // this side means reading annotation values the model has no representation for --
            // a `@throws[Exception]` on an overridden library method reported four errors and
            // failed the compilation outright.
            overridden.owner.denot.symbol.source.exists &&
            overridden.owner.denot.symbol.source == symbol.owner.denot.symbol.source)
        .map(methodData)
        .toList
        .reverse

  private def methodData(symbol: Symbol)(using Context, AnnotationDefaults): ScalaMethodData =
    symbol.info match
      case methodType: MethodType =>
        val returnType = methodReturnType(symbol.name.toString, methodType.resultType)
        val methodAnnotations = annotations(symbol) ++ typeUseNullabilityAnnotations(returnType)
        ScalaMethodData(
          methodName(symbol.name.toString),
          returnType,
          methodType.paramNames.zip(methodType.paramInfos)
            .map { case (name, info) => parameterData(name.toString, info, symbol) }
            .asJava,
          typeParameters(symbol).asJava,
          thrownTypes(symbol).asJava,
          methodAnnotations.asJava,
          modifiers(symbol).asJava,
          constructor = false,
          symbol
        )
      case info =>
        val returnType = methodReturnType(symbol.name.toString, info)
        val methodAnnotations = annotations(symbol) ++ typeUseNullabilityAnnotations(returnType)
        ScalaMethodData(
          methodName(symbol.name.toString),
          returnType,
          java.util.List.of(),
          typeParameters(symbol).asJava,
          thrownTypes(symbol).asJava,
          methodAnnotations.asJava,
          modifiers(symbol).asJava,
          constructor = false,
          symbol
        )

  private def methodName(name: String): String =
    if name.endsWith("_=") then
      name.stripSuffix("_=") + "_$eq"
    else
      name

  private def methodReturnType(name: String, tpe: Type)(using Context, AnnotationDefaults): ScalaTypeData =
    if name.endsWith("_=") then
      VoidTypeData
    else
      typeData(tpe)

  private def methodReturnType(name: String, tpt: tpd.Tree)(using Context, AnnotationDefaults): ScalaTypeData =
    if name.endsWith("_=") then
      VoidTypeData
    else
      typeData(tpt)

  /**
   * The exceptions a method declares, from its `@throws` annotations.
   *
   * The compiler spells the annotation three ways -- `@throws[E]`, the older
   * `@throws(classOf[E])`, and the form it builds itself for a Java method's `throws` clause,
   * whose one argument is a type reference rather than a value. Its own extractor knows all
   * three; reading the arguments as annotation values did not, and a Java classpath method
   * with a `throws` clause failed the whole class with "Unsupported Scala annotation value".
   */
  private def thrownTypes(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaTypeData] =
    if symbol == Symbols.NoSymbol then
      Nil
    else
      declaredAnnotations(symbol)
        .filter(isThrowsAnnotation)
        .flatMap {
          case Annotations.ThrownException(thrown) => Some(typeData(thrown))
          case annotation => annotation.arguments.flatMap(thrownType).headOption
        }

  private def isThrowsAnnotation(annotation: Annotation)(using Context): Boolean =
    annotation.symbol == Symbols.defn.ThrowsAnnot

  private def thrownType(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaTypeData] =
    thrownTypeArgument(tree).orElse(annotationClassValueTypeData(tree))

  private def thrownTypeArgument(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaTypeData] =
    tree match
      case typeApply: tpd.TypeApply if typeApply.args.nonEmpty =>
        Some(typeData(typeApply.args.head.tpe))
      case typed: tpd.Typed =>
        thrownTypeArgument(typed.expr)
      case apply: tpd.Apply =>
        thrownTypeArgument(apply.fun)
          .orElse(apply.args.iterator.map(thrownTypeArgument).collectFirst { case Some(thrownType) => thrownType })
      case _ =>
        None

  private def classValueTypeData(name: String)(using Context): ScalaTypeData =
    val symbol = classSymbolForName(name)
    ScalaTypeData(
      name,
      primitive = false,
      arrayDimensions = 0,
      interfaceType = isInterfaceSymbol(symbol),
      java.util.Map.of(),
      null,
      Nil.asJava,
      Nil.asJava,
      annotatedTypeUse = false,
      symbol
    )

  private def annotationClassValueTypeData(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaTypeData] =
    annotationValue(tree) match
      case classValue: ScalaClassValueData =>
        Some(classValueTypeData(classValue.name()))
      case value: String if value.nonEmpty =>
        Some(classValueTypeData(value))
      case _ =>
        None

  // A Scala `val`/`var` is a private backing field plus accessors, whatever the
  // declaration's own visibility says, so the emitted field is always private. This lives
  // here rather than in `ScalaFieldElement` because it is a fact about how the compiler
  // emits *these* declarations, not about fields in general -- a synthetic field the
  // extractor builds itself keeps the modifiers it was given.
  private def backingFieldModifiers(symbol: Symbol)(using Context): Set[ElementModifier] =
    modifiers(symbol) - ElementModifier.PUBLIC - ElementModifier.PROTECTED + ElementModifier.PRIVATE

  private def fieldData(field: tpd.ValDef)(using Context, AnnotationDefaults): ScalaFieldData =
    val fieldType = typeData(field.tpt)
    val fieldAnnotations = annotations(field.symbol) ++ typeUseNullabilityAnnotations(fieldType)
    ScalaFieldData(
      field.name.toString,
      fieldType,
      fieldAnnotations.asJava,
      backingFieldModifiers(field.symbol).asJava,
      isEnumConstant(field.symbol),
      fieldConstantValue(field),
      field
    )

  private def enumConstantFieldData(symbol: Symbol, owner: Symbol)(using Context, AnnotationDefaults): ScalaFieldData =
    ScalaFieldData(
      symbol.name.toString,
      ScalaTypeData(className(owner), primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of()),
      annotations(symbol).asJava,
      java.util.Set.of(ElementModifier.PUBLIC, ElementModifier.STATIC, ElementModifier.FINAL),
      true,
      null,
      symbol
    )

  /**
   * The companion object's `@Creator` methods, as static methods of the class itself.
   *
   * Scala's idiomatic factory is a method on the companion object, and the backend emits a
   * *static forwarder* for it on the companion class: `object Widget { def of(n: String) }`
   * really does produce `public static Widget of(String)` on `Widget`. That forwarder is
   * generated after these phases, so it is not visible here, but it is what a generated call
   * binds against -- `MethodGenUtils` emits `beanType.invokeStatic(...)`, which is exactly the
   * forwarder. Without this the method is invisible and core falls back to the primary
   * constructor, silently ignoring the annotation.
   *
   * The conditions mirror dotty's own `BCodeSkelBuilder`/`BCodeHelpers.addForwarders`, because
   * predicting a forwarder that is not emitted would generate a call to a method that does not
   * exist -- a linkage error, which is worse than the fallback. A forwarder is emitted only
   * when the companion object is static (top level, or nested in another object -- not inside
   * a class), the member is a public, concrete, non-constructor method that is not inherited
   * from `Object` and carries no expanded (private-mangled) name, no term member of the class
   * shares its name, and `-Xno-forwarders` is not set.
   */
  private def companionStaticCreators(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaMethodData] =
    val companion = symbol.companionModule
    if hasFlag(symbol, Flags.ModuleClass)
      || summon[Context].settings.XnoForwarders.value
      || companion == Symbols.NoSymbol
      || !companion.is(Flags.Module)
      || !companion.isStatic then
      Nil
    else
      val conflicting = symbol.info.allMembers.collect {
        case member if member.name.isTermName => member.name.toString
      }.toSet
      companion.moduleClass.info.decls.toList
        .filter(member => member.is(Flags.Method))
        .filter(member => forwardedCreator(member, conflicting))
        .map(methodData)
        .map(data => staticMethodData(data))

  private def forwardedCreator(member: Symbol, conflicting: Set[String])(using Context, AnnotationDefaults): Boolean =
    !member.isType
      && !member.is(Flags.Deferred)
      && (member.owner ne Symbols.defn.ObjectClass)
      && !member.denot.isConstructor
      && !member.name.is(NameKinds.ExpandedName)
      && !conflicting.contains(member.name.toString)
      && member.accessBoundary(Symbols.defn.RootClass) == Symbols.defn.RootClass
      && annotations(member).exists(annotation => annotation.name() == CreatorAnnotationName)

  private def staticMethodData(data: ScalaMethodData): ScalaMethodData =
    ScalaMethodData(
      data.name(),
      data.returnType(),
      data.parameters(),
      data.typeParameters(),
      data.thrownTypes(),
      data.annotations(),
      (data.modifiers().asScala + ElementModifier.STATIC).asJava,
      data.constructor(),
      data.nativeType()
    )

  private def enumValueOfMethodData(symbol: Symbol)(using Context): ScalaMethodData =
    val enumType = ScalaTypeData(className(symbol), primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of())
    val stringType = ScalaTypeData(classOf[String].getName, primitive = false, arrayDimensions = 0, interfaceType = false, java.util.Map.of())
    ScalaMethodData(
      "valueOf",
      enumType,
      List(ScalaParameterData("name", stringType, Nil.asJava, new Object())).asJava,
      Nil.asJava,
      Nil.asJava,
      Nil.asJava,
      java.util.Set.of(ElementModifier.PUBLIC, ElementModifier.STATIC),
      constructor = false,
      new Object()
    )

  private def fieldConstantValue(field: tpd.ValDef)(using Context): Object | Null =
    if hasFlag(field.symbol, Flags.Mutable) then null else constantValue(field.rhs)

  private def withAnnotations(parameter: ScalaParameterData, added: List[ScalaAnnotationData]): ScalaParameterData =
    val fresh = newAnnotations(parameter.annotations().asScala.toList, added)
    if fresh.isEmpty then parameter
    else ScalaParameterData(
      parameter.name(),
      parameter.`type`(),
      (parameter.annotations().asScala.toList ++ fresh).asJava,
      parameter.defaultAccessor(),
      parameter.defaultAccessorStatic(),
      parameter.nativeType(),
      parameter.overriddenParameters()
    )

  /** A Java class's static members, which the compiler keeps on the companion module class. */
  private def javaStaticDeclarations(symbol: ClassSymbol)(using Context): List[Symbol] =
    val companion = symbol.companionModule
    if companion == Symbols.NoSymbol then Nil
    else companion.moduleClass.info.decls.toList.filter(member => member.isTerm && !member.isType)

  /**
   * The class file a Java class was read from, opened for its parameter annotations, or `None`
   * when the symbol did not come from a class file -- a Java source compiled jointly is parsed
   * by the compiler's own Java parser, which reads parameter annotations itself.
   */
  private def classFileParameterAnnotations(symbol: ClassSymbol)(using Context): Option[ClassFileParameterAnnotations] =
    val file = symbol.associatedFile
    if file == null || !file.hasExtension("class") then None
    else
      try Some(ClassFileParameterAnnotations.parse(file.toByteArray))
      catch case _: Exception => None

  /** A method's annotations read from its class file, in the model's terms. */
  private final case class ClassFileAnnotations(returnType: List[ScalaAnnotationData], parameters: List[List[ScalaAnnotationData]])

  private object ClassFileAnnotations:
    val None: ClassFileAnnotations = ClassFileAnnotations(Nil, Nil)

  private def classFileAnnotations(
      classFile: Option[ClassFileParameterAnnotations],
      method: Symbol
  )(using Context, AnnotationDefaults): ClassFileAnnotations =
    classFile match
      case None => ClassFileAnnotations.None
      case Some(reader) =>
        val parameters = method.paramSymss.flatten.filter(_.isTerm)
        val name = if method.denot.isConstructor then "<init>" else method.name.toString
        val read = reader.forMethod(name, parameters.map(parameter => erasedBinaryName(parameter.info)).asJava)
        ClassFileAnnotations(
          read.returnType.asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation))),
          read.parameters.asScala.toList.map(_.asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation))))
        )

  private def classFileFieldAnnotations(
      classFile: Option[ClassFileParameterAnnotations],
      field: Symbol
  )(using Context, AnnotationDefaults): List[ScalaAnnotationData] =
    classFile match
      case None => Nil
      case Some(reader) =>
        reader.forField(field.name.toString).asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation)))

  /**
   * The binary name of the class a nested class is declared in, or `null` at the top level.
   * The compiler keeps a Java class's static members -- its static nested classes among them --
   * on the companion module class; the enclosing class the language knows is the class itself.
   */
  private def enclosingClassName(symbol: Symbol)(using Context): String | Null =
    val owner = symbol.owner
    if owner == Symbols.NoSymbol || !owner.isClass || hasFlag(owner, Flags.PackageClass) then null
    else if hasFlag(owner, Flags.JavaDefined) && hasFlag(owner, Flags.ModuleClass) && owner.linkedClass != Symbols.NoSymbol then
      className(owner.linkedClass)
    else className(owner)

  private def classFileAnnotationDefaults(symbol: Symbol)(using Context, AnnotationDefaults): Map[String, Object] =
    if !symbol.isClass then Map.empty
    else
      classFileParameterAnnotations(symbol.asClass) match
        case None => Map.empty
        case Some(reader) =>
          reader.annotationDefaults.asScala.toList
            .flatMap((member, value) => Option(classFileAnnotationValue(value)).map(member -> _))
            .toMap

  /**
   * A member the compiler did not enter, as the identity an element built from it is keyed on.
   * Annotation metadata is cached by native type, so the same member has to be the same object
   * every time; the class data is built once per class and holds these, which makes it so.
   */
  private final case class ClassFileMember(owner: String, name: String, descriptor: String)

  /**
   * The private fields of a Java class, which the compiler does not enter; see
   * [[ClassFileParameterAnnotations.privateFields]].
   *
   * Their types are built by turning the class file's signature into the compiler's own type
   * and handing it to the same `typeData` every other member goes through, so a private
   * `List<Foo>` is modelled as one and not as a second reading of what the descriptor meant.
   */
  private def javaPrivateFields(symbol: ClassSymbol, reader: ClassFileParameterAnnotations)(using Context, AnnotationDefaults): List[ScalaFieldData] =
    reader.privateFields.asScala.toList.map { field =>
      val fieldType = typeData(signatureType(field.signature, symbol, Map.empty))
      val declared = (field.annotations.asScala.toList ++ field.typeAnnotations.asScala.toList)
        .flatMap(annotation => Option(classFileAnnotationData(annotation)))
      val modifiers = LinkedHashSet[ElementModifier]()
      modifiers.add(ElementModifier.PRIVATE)
      if field.isStatic then modifiers.add(ElementModifier.STATIC)
      if field.isFinal then modifiers.add(ElementModifier.FINAL)
      ScalaFieldData(
        field.name,
        fieldType,
        (declared ++ newAnnotations(declared, typeUseNullabilityAnnotations(fieldType))).asJava,
        modifiers,
        false,
        classFileConstant(field.constantValue, field.signature),
        ClassFileMember(className(symbol), field.name, field.signature.signatureString)
      )
    }

  /**
   * The private methods of a Java class, which the compiler does not enter; see
   * [[ClassFileParameterAnnotations.privateMethods]]. A type variable the method declares
   * itself is read as its bound: nothing outside the method can name it, and its erasure is
   * what the method compiles to.
   */
  private def javaPrivateMethods(symbol: ClassSymbol, reader: ClassFileParameterAnnotations)(using Context, AnnotationDefaults): List[ScalaMethodData] =
    reader.privateMethods.asScala.toList.map { method =>
      val signature = method.signature
      val methodTypeVariables = signature.typeParameters.asScala.toList.map { parameter =>
        val bound = parameter.classBound.toScala
          .orElse(parameter.interfaceBounds.asScala.headOption)
          .map(bound => signatureType(bound, symbol, Map.empty))
          .getOrElse(Symbols.defn.ObjectType)
        parameter.identifier -> bound
      }.toMap
      val owner = className(symbol)
      val parameters = signature.arguments.asScala.toList.zipWithIndex.map { (argument, index) =>
        val parameterType = typeData(signatureType(argument, symbol, methodTypeVariables))
        val name = method.parameterNames.asScala.lift(index).flatMap(Option(_)).getOrElse(s"arg$index")
        val fromClassFile = method.parameterAnnotations.parameters.asScala.lift(index)
          .map(_.asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation))))
          .getOrElse(Nil)
        ScalaParameterData(
          name,
          parameterType,
          (fromClassFile ++ newAnnotations(fromClassFile, typeUseNullabilityAnnotations(parameterType))).asJava,
          ClassFileMember(owner, method.name, s"${signature.signatureString}#$index")
        )
      }
      val returnType = typeData(signatureType(signature.result, symbol, methodTypeVariables))
      val declared = method.annotations.asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation)))
        ++ method.parameterAnnotations.returnType.asScala.toList.flatMap(annotation => Option(classFileAnnotationData(annotation)))
      val modifiers = LinkedHashSet[ElementModifier]()
      modifiers.add(ElementModifier.PRIVATE)
      if method.isStatic then modifiers.add(ElementModifier.STATIC)
      ScalaMethodData(
        method.name,
        returnType,
        parameters.asJava,
        Nil.asJava,
        signature.throwableSignatures.asScala.toList.map(thrown => typeData(signatureType(thrown, symbol, methodTypeVariables))).asJava,
        (declared ++ newAnnotations(declared, typeUseNullabilityAnnotations(returnType))).asJava,
        modifiers,
        false,
        ClassFileMember(owner, method.name, signature.signatureString)
      )
    }

  /**
   * A JVM signature as the compiler's type, so that the model built from it is the model
   * built from everything else. A class the compiler cannot find is `Object`, which is what
   * javac's model says of a missing type too.
   */
  private def signatureType(
      signature: java.lang.classfile.Signature,
      owner: ClassSymbol,
      methodTypeVariables: Map[String, Type]
  )(using Context): Type =
    signature match
      case base: java.lang.classfile.Signature.BaseTypeSig =>
        base.baseType match
          case 'B' => Symbols.defn.ByteType
          case 'C' => Symbols.defn.CharType
          case 'D' => Symbols.defn.DoubleType
          case 'F' => Symbols.defn.FloatType
          case 'I' => Symbols.defn.IntType
          case 'J' => Symbols.defn.LongType
          case 'S' => Symbols.defn.ShortType
          case 'Z' => Symbols.defn.BooleanType
          case _ => Symbols.defn.UnitType
      case array: java.lang.classfile.Signature.ArrayTypeSig =>
        Symbols.defn.ArrayOf(signatureType(array.componentSignature, owner, methodTypeVariables))
      case variable: java.lang.classfile.Signature.TypeVarSig =>
        methodTypeVariables.get(variable.identifier)
          .orElse(owner.typeParams.find(_.name.toString == variable.identifier).map(_.typeRef))
          .getOrElse(Symbols.defn.ObjectType)
      case classType: java.lang.classfile.Signature.ClassTypeSig =>
        val symbol = classSymbolForName(signatureClassName(classType))
        if symbol == Symbols.NoSymbol then
          Symbols.defn.ObjectType
        else
          val arguments = classType.typeArgs.asScala.toList.map(argument => signatureTypeArgument(argument, owner, methodTypeVariables))
          if arguments.isEmpty then symbol.typeRef else AppliedType(symbol.typeRef, arguments)

  private def signatureTypeArgument(
      argument: java.lang.classfile.Signature.TypeArg,
      owner: ClassSymbol,
      methodTypeVariables: Map[String, Type]
  )(using Context): Type =
    argument match
      case _: java.lang.classfile.Signature.TypeArg.Unbounded =>
        TypeBounds.empty
      case bounded: java.lang.classfile.Signature.TypeArg.Bounded =>
        val bound = signatureType(bounded.boundType, owner, methodTypeVariables)
        bounded.wildcardIndicator match
          case java.lang.classfile.Signature.TypeArg.Bounded.WildcardIndicator.EXTENDS => TypeBounds.upper(bound)
          case java.lang.classfile.Signature.TypeArg.Bounded.WildcardIndicator.SUPER => TypeBounds.lower(bound)
          case _ => bound

  private def signatureClassName(classType: java.lang.classfile.Signature.ClassTypeSig): String =
    classType.outerType.toScala match
      case Some(outer) => signatureClassName(outer) + "$" + classType.className.replace('/', '.')
      case None => classType.className.replace('/', '.')

  /** A field's `ConstantValue`, which the class file keeps as an int for the small types. */
  private def classFileConstant(value: Object | Null, signature: java.lang.classfile.Signature): Object | Null =
    (value, signature) match
      case (null, _) => null
      case (number: Integer, base: java.lang.classfile.Signature.BaseTypeSig) =>
        base.baseType match
          case 'Z' => java.lang.Boolean.valueOf(number.intValue != 0)
          case 'C' => java.lang.Character.valueOf(number.intValue.toChar)
          case 'S' => java.lang.Short.valueOf(number.shortValue)
          case 'B' => java.lang.Byte.valueOf(number.byteValue)
          case _ => number
      case (other, _) => other

  /**
   * The binary name of a parameter type's erasure, as a JVM descriptor spells it -- what a
   * class file's method has to be matched by. A Java varargs parameter is an array.
   */
  private def erasedBinaryName(tpe: Type)(using Context): String =
    tpe match
      case AppliedType(tycon, List(element)) if tycon.typeSymbol == Symbols.defn.RepeatedParamClass =>
        erasedBinaryName(element) + "[]"
      case _ =>
        TypeErasure.erasure(tpe) match
          case JavaArrayType(element) => erasedBinaryName(element) + "[]"
          case erased =>
            val symbol = erased.classSymbol
            if symbol == Symbols.NoSymbol then classOf[Object].getName
            else
              val name = className(symbol)
              ScalaPrimitiveNames.getOrElse(name, name)

  /**
   * An annotation read from a class file, in the model's terms. The type is resolved through
   * the compiler like any other, so its meta-annotations and members are the same ones an
   * annotation written in source would have.
   */
  private def classFileAnnotationData(annotation: ClassFileParameterAnnotations.Annotation)(using Context, AnnotationDefaults): ScalaAnnotationData | Null =
    val symbol = classSymbolForName(annotation.typeName)
    val annotationType = if isAnnotationSymbol(symbol) then annotationTypeData(symbol, Set.empty) else null
    val values = LinkedHashMap[String, Object]()
    annotation.values.forEach { (member, value) =>
      val converted = classFileAnnotationValue(value)
      if converted != null then values.put(member, converted)
    }
    ScalaAnnotationData(
      if symbol == Symbols.NoSymbol then annotation.typeName else className(symbol),
      normalizeAnnotationArgumentValues(values, annotationType).asInstanceOf[JMap[CharSequence, Object]],
      annotationType
    )

  private def classFileAnnotationValue(value: Object)(using Context, AnnotationDefaults): Object | Null =
    value match
      case enumValue: ClassFileParameterAnnotations.EnumValue => enumValue.constantName
      case classLiteral: ClassFileParameterAnnotations.ClassLiteral => classValueData(classLiteral.typeName)
      case nested: ClassFileParameterAnnotations.Annotation => classFileAnnotationData(nested)
      case values: java.util.List[?] =>
        annotationArray(values.asScala.toList.map(element => classFileAnnotationValue(element.asInstanceOf[Object])).filter(_ != null))
      case other => other

  private def constantValue(tree: tpd.Tree): Object | Null =
    tree match
      case literal: tpd.Literal => constantValue(literal.const)
      case _ => null

  /**
   * A `final val`'s literal, read from its type. A constant type is all a class file keeps of
   * a constant expression, and it is the same literal the source tree holds.
   */
  private def constantValue(tpe: Type)(using Context): Object | Null =
    tpe.dealias match
      case constantType: ConstantType => constantValue(constantType.value)
      case _ => null

  private def constantValue(constant: Constants.Constant): Object | Null =
    constant.value match
      case value: String => value
      case value: java.lang.Boolean => value
      case value: java.lang.Byte => value
      case value: java.lang.Short => value
      case value: java.lang.Integer => value
      case value: java.lang.Long => value
      case value: java.lang.Float => value
      case value: java.lang.Double => value
      case value: java.lang.Character => value
      case _ => null

  private def enumConstantSymbols(symbol: Symbol)(using Context): List[Symbol] =
    val symbols = if hasFlag(symbol, Flags.Enum) then
      val companion = symbol.companionModule
      if companion == Symbols.NoSymbol then symbol.info.decls.toList else companion.info.decls.toList
    else
      symbol.info.decls.toList
    symbols.filter(isEnumConstantField)

  private def isEnumConstantField(symbol: Symbol)(using Context): Boolean =
    isEnumConstant(symbol) &&
      symbol.isTerm &&
      !symbol.denot.isConstructor &&
      !symbol.name.toString.startsWith("<") &&
      !hasFlag(symbol, Flags.Method)

  private def parameterData(parameter: tpd.ValDef)(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterType = byNameTypeData(parameter.tpt.tpe).getOrElse(typeData(parameter.tpt))
    parameterData(parameter.symbol, parameterType, parameter)

  /**
   * A parameter read from its symbol alone, for a method the compiler read from the classpath.
   *
   * Everything the tree form reads is on the symbol as well -- the type, the annotations, the
   * `HasDefault` flag -- with one difference: a source parameter's type tree may carry type-use
   * annotations, and the symbol's type does not. A classpath type has no trees, so there is
   * nothing to read there in any model.
   */
  private def parameterData(symbol: Symbol)(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterType = byNameTypeData(symbol.info)
      .orElse(javaVarargsTypeData(symbol))
      .getOrElse(typeData(symbol.info))
    parameterData(symbol, parameterType, symbol)

  // A Java varargs parameter is typed `T*` by the compiler and compiled as `T[]`; the array is
  // what a generated call binds against.
  private def javaVarargsTypeData(symbol: Symbol)(using Context, AnnotationDefaults): Option[ScalaTypeData] =
    symbol.info match
      case AppliedType(tycon, List(element))
        if tycon.typeSymbol == Symbols.defn.RepeatedParamClass && hasFlag(symbol.owner, Flags.JavaDefined) =>
        Some(typeData(Symbols.defn.ArrayOf(element)))
      case _ =>
        None

  private def parameterData(
      symbol: Symbol,
      parameterType: ScalaTypeData,
      nativeType: Object
  )(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterAnnotations = annotations(symbol) ++ typeUseNullabilityAnnotations(parameterType)
    val accessor = defaultAccessor(symbol)
    // Carried as annotation metadata as well as on the record. Core hands the parameter to a
    // `ParameterDefaultValueProvider` loaded by *Core's* classloader, which in a test harness
    // is not the plugin's isolated one, so an `instanceof` check against this plugin's own
    // element type is false even though the class names match. Metadata is data rather than a
    // type, so it crosses that boundary.
    val defaultAnnotations = accessor.map { case (name, isStatic) =>
      ScalaAnnotationData(
        DefaultAccessorAnnotationName,
        java.util.Map.of[CharSequence, Object]("accessor", name, "static", java.lang.Boolean.valueOf(isStatic)),
        null
      )
    }
    ScalaParameterData(
      symbol.name.toString,
      parameterType,
      (parameterAnnotations ++ defaultAnnotations).asJava,
      accessor.map(_._1).orNull,
      accessor.exists(_._2),
      nativeType,
      overriddenParameters(symbol).asJava
    )

  /**
   * The same-index parameters of the declarations this parameter's method overrides.
   *
   * Core takes the parameter at each index from every overridden method
   * (`JavaAnnotationMetadataBuilder`, the `VariableElement` branch), so an `@Inherited`
   * annotation written once on a trait member's parameter applies to every implementation --
   * which is what `InheritedNullableAnnotationSpec` asserts of a `@Nullable(inherited = true)`
   * header parameter.
   *
   * Attached here, where a parameter is first built from its tree, rather than alongside the
   * method's overridden list: annotation metadata is cached under the native symbol, so whichever
   * instance is built first decides what every later one reports, and attaching it on the method
   * paths left the cached instance without it.
   */
  private def overriddenParameters(parameterSymbol: Symbol)(using Context, AnnotationDefaults): List[ScalaParameterData] =
    val owner = parameterSymbol.owner
    if owner == Symbols.NoSymbol || !owner.isTerm then
      Nil
    else
      val declared = owner.paramSymss.flatten.filter(_.isTerm)
      val index = declared.indexWhere(_ == parameterSymbol)
      if index < 0 then
        Nil
      else
        owner.allOverriddenSymbols
          .filter(overridden =>
            overridden != Symbols.NoSymbol &&
              overridden.isTerm &&
              // Same scope as the method walk: only declarations being compiled here, since
              // modelling a classpath one means reading annotation values the model cannot
              // represent.
              overridden.owner.denot.symbol.source.exists &&
              overridden.owner.denot.symbol.source == owner.owner.denot.symbol.source)
          .flatMap { overridden =>
            val overriddenParams = overridden.paramSymss.flatten.filter(_.isTerm)
            if overriddenParams.size > index then Some(overriddenParameterData(overriddenParams(index)))
            else None
          }
          .toList
          .reverse

  /**
   * An overridden parameter, without the parameters it in turn overrides: `allOverriddenSymbols`
   * is already transitive, and recursing would rebuild the same declarations once per level.
   */
  private def overriddenParameterData(parameterSymbol: Symbol)(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterType = byNameTypeData(parameterSymbol.info).getOrElse(typeData(parameterSymbol.info))
    ScalaParameterData(
      parameterSymbol.name.toString,
      parameterType,
      (annotations(parameterSymbol) ++ typeUseNullabilityAnnotations(parameterType)).asJava,
      parameterSymbol
    )

  /**
   * The generated accessor that supplies a parameter's default value, if a caller can reach it.
   *
   * Scala compiles a default argument to a zero-argument accessor rather than to anything in
   * the method signature: `def repeat(word: String, count: Int = 3)` emits
   * `repeat$default$2()` as an instance method, and a constructor default emits
   * `$lessinit$greater$default$<n>()`. Core's `ParameterDefaultValueProvider` is built for
   * exactly this -- an expression the *caller* evaluates -- so the model only has to say
   * which accessor to call and whether it is static.
   *
   * The constructor accessor is static on the class only through the companion's static
   * forwarder, which the backend emits only for a *static* companion. For a class nested
   * inside another class the accessor is an instance method on the inner companion and is
   * not reachable from a call site, so no default is reported and the parameter stays
   * required -- which is the current behaviour, and better than emitting a call to a method
   * that does not exist or silently injecting a type default.
   */
  private def defaultAccessor(symbol: Symbol)(using Context): Option[(String, Boolean)] =
    if !hasFlag(symbol, Flags.HasDefault) then
      None
    else
      val owner = symbol.owner
      val index = owner.info.paramNamess.flatten.indexWhere(_ == symbol.name) + 1
      if index <= 0 then
        None
      else if owner.denot.isConstructor then
        val cls = owner.owner
        Option.when(cls.isStatic)((s"$$lessinit$$greater$$default$$$index", true))
      else
        Some((s"${methodName(owner.name.toString)}$$default$$$index", false))

  private def parameterData(name: String, tpe: Type, nativeType: Object)(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterType = byNameTypeData(tpe).getOrElse(typeData(tpe))
    ScalaParameterData(
      name,
      parameterType,
      typeUseNullabilityAnnotations(parameterType).asJava,
      nativeType
    )

  // A Scala by-name parameter `x: => T` carries an `ExprType`, and `widenDealias` reduces that to
  // `T`. The JVM signature of such a parameter is `scala.Function0[T]`, so reporting `T` would
  // describe an argument type the generated bean definition cannot bind against the bytecode.
  private def byNameTypeData(tpe: Type)(using Context, AnnotationDefaults): Option[ScalaTypeData] =
    tpe match
      case exprType: ExprType =>
        Some(typeData(AppliedType(Symbols.requiredClassRef("scala.Function0"), List(exprType.resType))))
      case _ =>
        None

  private def typeData(tpe: Type)(using Context, AnnotationDefaults, TypePosition): ScalaTypeData =
    typeData(tpe, Set.empty)

  private def typeData(tpe: Type, visitedTypes: Set[String])(using Context, AnnotationDefaults, TypePosition): ScalaTypeData =
    typeData(tpe, visitedTypes, Map.empty, None, Nil)

  private def typeData(tpt: tpd.Tree)(using Context, AnnotationDefaults, TypePosition): ScalaTypeData =
    typeData(tpt, Set.empty)

  private def typeData(tpt: tpd.Tree, visitedTypes: Set[String])(using Context, AnnotationDefaults, TypePosition): ScalaTypeData =
    val (baseTree, treeAnnotations) = annotatedTree(tpt)
    typeData(baseTree.tpe, visitedTypes, Map.empty, Some(baseTree), treeAnnotations)

  private def typeData(
      tpe: Type,
      visitedTypes: Set[String],
      visitedTypeParameters: Map[String, Int],
      typeTree: Option[tpd.Tree],
      extraAnnotations: List[Annotation]
  )(using Context, AnnotationDefaults, TypePosition): ScalaTypeData =
    val (annotatedWidened, typeAnnotations) = annotatedType(tpe.widenDealiasKeepAnnots)
    val (nullableWidened, explicitNullable) = explicitNullableType(annotatedWidened)
    val widened = jvmModelledType(nullableWidened)
    val allTypeAnnotations = extraAnnotations ++ typeAnnotations
    if widened.isInstanceOf[TypeBounds] then
      wildcardTypeData(widened.asInstanceOf[TypeBounds], allTypeAnnotations, explicitNullable, visitedTypeParameters)
    else
      val widenedSymbol = widened.typeSymbol
      if widenedSymbol != Symbols.NoSymbol && widenedSymbol.isTypeParam then
        typeParameterData(widenedSymbol, allTypeAnnotations, explicitNullable, visitedTypeParameters)
      else widened match
        case applied: AppliedType if typeName(applied.tycon) == "scala.Array" && applied.args.nonEmpty =>
          // An array component is a nested position: `Array[UserId]` really is `UserId[]`.
          given TypePosition = TypePosition.Nested
          val componentType = typeTree.flatMap(appliedTypeArguments).flatMap(_.headOption) match
            case Some(componentTree) => typeData(componentTree, visitedTypes)
            case None => typeData(applied.args.head, visitedTypes)
          componentType.withArrayDimensions(componentType.arrayDimensions + 1).asInstanceOf[ScalaTypeData]
        case applied: AppliedType =>
          val rawName = typeName(applied.tycon)
          val primitiveName = ScalaPrimitiveNames.get(rawName)
          val name = primitiveName.getOrElse(rawName)
          val symbol = applied.tycon.classSymbol
          val interfaceType = isInterfaceSymbol(symbol)
          val hierarchy = typeHierarchy(widened, symbol, name, primitiveName.isDefined, visitedTypes, visitedTypeParameters)
          ScalaTypeData(name, primitiveName.isDefined, 0, interfaceType, typeArguments(symbol, applied.args, visitedTypes, visitedTypeParameters, typeTree.flatMap(appliedTypeArguments).getOrElse(Nil)), hierarchy.superType, hierarchy.interfaces.asJava, typeAnnotationsFor(symbol, allTypeAnnotations, explicitNullable).asJava, allTypeAnnotations.nonEmpty || explicitNullable, symbol)
        case _ =>
          val rawName = typeName(widened)
          val primitiveName = ScalaPrimitiveNames.get(rawName)
          val name = primitiveName.getOrElse(rawName)
          val symbol = widened.classSymbol
          val interfaceType = isInterfaceSymbol(symbol)
          val hierarchy = typeHierarchy(widened, symbol, name, primitiveName.isDefined, visitedTypes, visitedTypeParameters)
          ScalaTypeData(name, primitiveName.isDefined, 0, interfaceType, java.util.Map.of(), hierarchy.superType, hierarchy.interfaces.asJava, typeAnnotationsFor(symbol, allTypeAnnotations, explicitNullable).asJava, allTypeAnnotations.nonEmpty || explicitNullable, symbol)

  private def annotatedTree(tpt: tpd.Tree)(using Context): (tpd.Tree, List[Annotation]) =
    val typeAnnotations = ListBuffer.empty[Annotation]
    var current = tpt
    var continue = true
    while continue do
      current match
        case annotated: tpd.Annotated =>
          typeAnnotations += Annotation(annotated.annot)
          current = annotated.arg
        case _ =>
          continue = false
    // Peeling runs outermost-first, and the outermost annotation is the last one written:
    // `String @Size(min = 1) @Size(max = 5)` parses as `(String @Size(min = 1)) @Size(max = 5)`.
    // Without the reverse the repeats reach the metadata backwards, and so does the
    // `@Repeatable` container built from them.
    (current, typeAnnotations.toList.reverse)

  private def appliedTypeArguments(tpt: tpd.Tree): Option[List[tpd.Tree]] =
    tpt match
      case applied: tpd.AppliedTypeTree =>
        Some(applied.args)
      case _ =>
        None

  private def annotatedType(tpe: Type): (Type, List[Annotation]) =
    val typeAnnotations = ListBuffer.empty[Annotation]
    var current = tpe
    var continue = true
    while continue do
      current match
        case annotated: AnnotatedType =>
          typeAnnotations += annotated.annot
          current = annotated.parent
        case _ =>
          continue = false
    // See `annotatedTree`: the peel runs outermost-first, which is last-written-first.
    (current, typeAnnotations.toList.reverse)

  /**
   * The type the JVM signature actually names, for the Scala types whose own name is not a JVM
   * type at all.
   *
   * A union other than `A | Null`, an intersection, and `Any`/`Matchable`/`AnyVal` in ordinary
   * position each compile to something else. Checked against `javap`: `String | Int` becomes
   * `java.lang.Object`, `String | CharSequence` becomes `java.lang.CharSequence`,
   * `Alpha & Beta` becomes `Alpha`, and `Any` becomes `java.lang.Object`. Reporting the source
   * type put names into the model that no bytecode carries: `scala.Matchable` for a union,
   * `scala.Any` for `Any` -- neither of which is a loadable class -- and for an intersection
   * the string `"probe.Alpha & probe.Beta"`, which is not a class name at all. The same
   * erasure applies in type-argument position (`List[String | Int]` has the signature
   * `List<java.lang.Object>`), so this is safe to apply wherever a type is modelled.
   *
   * Deliberately not applied to value classes. A value class erases to its underlying type in
   * ordinary position but stays boxed as a type argument -- `List[UserId]` really does have the
   * signature `List<UserId>` -- so the answer depends on where the type appears, which this
   * cannot see. Substituting the erasure there was tried and reverted: it broke `@Adapter`,
   * which failed with an AbstractMethodError at context startup.
   */
  private def jvmModelledType(tpe: Type)(using Context, TypePosition): Type =
    tpe match
      // A wildcard is `TypeBounds`, and an unbounded one is `Nothing .. Any`. Its `typeSymbol`
      // is the upper bound's, so without this a `List[?]` would be rewritten to `Object` and
      // stop being modelled as a wildcard at all.
      case _: TypeBounds => tpe
      case _: OrType | _: AndType => TypeErasure.erasure(tpe)
      case _ =>
        val symbol = tpe.typeSymbol
        if symbol == Symbols.defn.AnyClass
          || symbol == Symbols.defn.MatchableClass
          || symbol == Symbols.defn.AnyValClass then
          Symbols.defn.ObjectType
        else
          unboxedValueClass(tpe, symbol).getOrElse(tpe)

  /**
   * The underlying type of a derived value class, when the JVM unboxes it here.
   *
   * `class UserId(val value: String) extends AnyVal` is not a type any signature carries at
   * the top level: `def ret(): UserId` compiles to `java.lang.String ret()`, and
   * `class Holder(val id: UserId)` to a field of type `java.lang.String`. Reporting `UserId`
   * described a constructor argument and a return type the generated bean definition cannot
   * bind against the bytecode. A value class over a primitive unboxes to that primitive --
   * `class Wrapped(val n: Int)` compiles to `int` -- and the primitive mapping applied to the
   * result handles that.
   *
   * Nested positions are left alone, because the JVM leaves them boxed there.
   */
  private def unboxedValueClass(tpe: Type, symbol: Symbol)(using Context, TypePosition): Option[Type] =
    if summon[TypePosition] != TypePosition.TopLevel then
      None
    else
      symbol match
        case classSymbol: Symbols.ClassSymbol if isDerivedValueClass(classSymbol) =>
          Some(ValueClasses.underlyingOfValueClass(classSymbol))
        case _ =>
          None

  /**
   * Whether a class is a user-declared value class, mirroring dotty's own
   * `SymUtils.isDerivedValueClass`. `scala.Int` and friends also derive from `AnyVal`, and
   * `AnyVal` itself is a value class, so neither can be treated as one to unbox.
   */
  private def isDerivedValueClass(symbol: Symbols.ClassSymbol)(using Context): Boolean =
    val denotation = symbol.denot
    !denotation.isRefinementClass
      && denotation.isValueClass
      && (denotation.initial.symbol ne Symbols.defn.AnyValClass)
      && !denotation.isPrimitiveValueClass

  private def explicitNullableType(tpe: Type)(using Context): (Type, Boolean) =
    tpe match
      case orType: OrType if isNullType(orType.tp1) =>
        (orType.tp2, true)
      case orType: OrType if isNullType(orType.tp2) =>
        (orType.tp1, true)
      case _ =>
        (tpe, false)

  private def isNullType(tpe: Type)(using Context): Boolean =
    typeName(tpe.widenDealias) == "scala.Null"

  private def typeUseNullabilityAnnotations(typeData: ScalaTypeData): List[ScalaAnnotationData] =
    if typeData.annotatedTypeUse() then
      typeData.annotations().asScala.toList.filter(annotation => NullabilityAnnotationNames.contains(annotation.name()))
    else
      Nil

  /**
   * The annotations of a type as written, plus -- outside a nested position -- the ones its own
   * symbol declares.
   *
   * The symbol's annotations are what carries a supertype's stereotypes down to a subclass, and
   * what lets a type variable report the annotations of its bound. At a type argument they are
   * wrong: `List[Foo]` says nothing about Foo beyond naming it, and merging Foo's own
   * annotations in made the argument report whatever Foo happens to be annotated with --
   * `@Introspected`, `@Singleton`, a validation annotation -- as though it had been written at
   * the use. Java builds a type argument from the type mirror and reports only what was written
   * there, and an annotation that acts by its presence alone acts on the difference.
   *
   * A type variable is unaffected: it is built by `typeParameterData`, not here.
   */
  private def typeAnnotationsFor(
      symbol: Symbol,
      typeAnnotations: List[Annotation],
      explicitNullable: Boolean
  )(using Context, AnnotationDefaults, TypePosition): List[ScalaAnnotationData] =
    val nullable = if explicitNullable then List(NullableAnnotationData) else Nil
    val declared =
      if summon[TypePosition] == TypePosition.TopLevel then annotations(symbol) else Nil
    nullable ++ typeAnnotations.map(annotationData(_, Set.empty)) ++ declared

  /**
   * The supertypes of a type.
   *
   * The visited-type-parameter counts have to be carried into the parents. Reading them with
   * the two-argument `typeData` reset the counts, and a self-referential bound reaches its own
   * type parameter through a parent: `class SelfRef[T <: Ordered[T]]` walks into
   * `Ordered[T]`'s parent `Comparable[T]`, which mentions `T` again with the counts cleared.
   * The depth guard in `typeParameterData` then never tripped and the compiler died with a
   * StackOverflowError on a completely ordinary Scala declaration.
   */
  private def typeHierarchy(tpe: Type, symbol: Symbol, name: String, primitive: Boolean, visitedTypes: Set[String], visitedTypeParameters: Map[String, Int])(using Context, AnnotationDefaults): TypeHierarchy =
    if primitive || symbol == Symbols.NoSymbol || visitedTypes.contains(name) then
      TypeHierarchy(null, Nil)
    else
      val nextVisited = visitedTypes + name
      val parents = tpe.parents
        .filterNot(parent => typeName(parent) == classOf[Object].getName)
        .map(parent => typeData(parent, nextVisited, visitedTypeParameters, None, Nil))
      TypeHierarchy(
        parents.find(parent => !parent.interfaceType()).orNull,
        parents.filter(_.interfaceType())
      )

  private def typeArguments(symbol: Symbol, arguments: List[Type], visitedTypes: Set[String], visitedTypeParameters: Map[String, Int], argumentTrees: List[tpd.Tree] = Nil)(using Context, AnnotationDefaults): java.util.Map[String, ScalaTypeData] =
    // A type argument is a nested position: `List[UserId]` really is `List<UserId>`.
    given TypePosition = TypePosition.Nested
    if symbol == Symbols.NoSymbol || arguments.isEmpty then
      java.util.Map.of()
    else
      val converted = LinkedHashMap[String, ScalaTypeData]()
      symbol.typeParams.zip(arguments).zipWithIndex.foreach { case ((parameter, argument), index) =>
        val argumentData = argumentTrees.lift(index) match
          case Some(argumentTree) =>
            val (baseTree, treeAnnotations) = annotatedTree(argumentTree)
            typeData(baseTree.tpe, visitedTypes, visitedTypeParameters, Some(baseTree), treeAnnotations)
          case None => typeData(argument, visitedTypes, visitedTypeParameters, None, Nil)
        converted.put(parameter.name.toString, boxPrimitiveTypeArgument(resolveUnboundedWildcard(argumentData, parameter)))
      }
      converted

  private def resolveUnboundedWildcard(argumentData: ScalaTypeData, parameter: Symbol)(using Context, AnnotationDefaults): ScalaTypeData =
    if !isObjectWildcard(argumentData) then
      argumentData
    else
      val parameterBounds = typeParameterBounds(parameter, Map.empty)
      if isObjectOnlyBound(parameterBounds) then
        argumentData
      else
        val primaryBound = parameterBounds.head
        ScalaTypeData(
          primaryBound.name(),
          primaryBound.primitive(),
          primaryBound.arrayDimensions(),
          primaryBound.interfaceType(),
          primaryBound.typeArguments(),
          primaryBound.superType(),
          primaryBound.interfaces(),
          argumentData.annotations(),
          argumentData.annotatedTypeUse(),
          argumentData.nativeType(),
          false,
          null,
          Nil.asJava,
          true,
          parameterBounds.asJava,
          argumentData.lowerBounds()
        )

  private def isObjectWildcard(typeData: ScalaTypeData): Boolean =
    typeData.wildcard() &&
      typeData.lowerBounds().isEmpty &&
      typeData.upperBounds().size() == 1 &&
      typeData.upperBounds().get(0).name() == classOf[Object].getName

  private def isObjectOnlyBound(bounds: List[ScalaTypeData]): Boolean =
    bounds.size == 1 && bounds.head.name() == classOf[Object].getName

  private def boxPrimitiveTypeArgument(typeData: ScalaTypeData)(using Context): ScalaTypeData =
    val boxedName = BoxedPrimitiveNames.get(typeData.name())
    if typeData.primitive() && typeData.arrayDimensions() == 0 && boxedName.isDefined then
      val symbol = classSymbolForName(boxedName.get)
      ScalaTypeData(
        boxedName.get,
        primitive = false,
        arrayDimensions = 0,
        interfaceType = false,
        java.util.Map.of(),
        null,
        Nil.asJava,
        typeData.annotations(),
        typeData.annotatedTypeUse(),
        symbol
      )
    else
      typeData

  private def typeParameters(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaTypeData] =
    symbol.typeParams.map(typeParameterData(_, Nil, explicitNullable = false, Map.empty))

  private def typeParameters(method: tpd.DefDef)(using Context, AnnotationDefaults): List[ScalaTypeData] =
    method.leadingTypeParams.map(typeParameter => typeParameterData(typeParameter.symbol, Nil, explicitNullable = false, Map.empty))

  private def typeParameterData(
      symbol: Symbol,
      typeAnnotations: List[Annotation],
      explicitNullable: Boolean,
      visitedTypeParameters: Map[String, Int]
  )(using Context, AnnotationDefaults): ScalaTypeData =
    val symbolId = typeParameterId(symbol)
    val visitedCount = visitedTypeParameters.getOrElse(symbolId, 0)
    val bounds =
      if visitedCount >= 2 then
        List(objectTypeData)
      else
        typeParameterBounds(symbol, visitedTypeParameters.updated(symbolId, visitedCount + 1))
    val primaryBound = bounds.head
    ScalaTypeData(
      primaryBound.name(),
      primitive = false,
      arrayDimensions = 0,
      primaryBound.interfaceType(),
      primaryBound.typeArguments(),
      primaryBound.superType(),
      primaryBound.interfaces(),
      typeAnnotationsFor(symbol, typeAnnotations, explicitNullable).asJava,
      typeAnnotations.nonEmpty || explicitNullable,
      symbol,
      genericPlaceholder = true,
      symbol.name.toString,
      bounds.asJava
    )

  private def typeParameterId(symbol: Symbol)(using Context): String =
    val owner = symbol.owner
    val ownerName =
      if owner == Symbols.NoSymbol then ""
      else if owner.isClass then className(owner)
      else owner.showFullName
    s"$ownerName#${symbol.name}"

  private def typeParameterBounds(symbol: Symbol, visitedTypeParameters: Map[String, Int])(using Context, AnnotationDefaults): List[ScalaTypeData] =
    symbol.info match
      case bounds: TypeBounds =>
        upperBounds(bounds, visitedTypeParameters)
      case _ =>
        List(objectTypeData)

  /**
   * A wildcard's bounds.
   *
   * The visited-type-parameter counts have to be carried in. A recursive bound reached
   * through a wildcard -- `class Sorted[T <: Ordered[T]]`, or
   * `T <: Comparable[? >: T]` -- comes back to the same type parameter through here, and
   * resetting the counts to empty meant the depth guard in `typeParameterData` never
   * tripped: modelling such a class overflowed the stack and took the compiler down with it.
   */
  private def wildcardTypeData(
      bounds: TypeBounds,
      typeAnnotations: List[Annotation],
      explicitNullable: Boolean,
      visitedTypeParameters: Map[String, Int]
  )(using Context, AnnotationDefaults): ScalaTypeData =
    val upper = upperBounds(bounds, visitedTypeParameters)
    val lower = lowerBounds(bounds, visitedTypeParameters)
    val primaryBound = upper.head
    ScalaTypeData(
      primaryBound.name(),
      primitive = false,
      arrayDimensions = 0,
      primaryBound.interfaceType(),
      java.util.Map.of(),
      primaryBound.superType(),
      primaryBound.interfaces(),
      typeAnnotationsFor(Symbols.NoSymbol, typeAnnotations, explicitNullable).asJava,
      typeAnnotations.nonEmpty || explicitNullable,
      bounds,
      genericPlaceholder = false,
      null,
      Nil.asJava,
      wildcard = true,
      upper.asJava,
      lower.asJava
    )

  private def upperBounds(bounds: TypeBounds, visitedTypeParameters: Map[String, Int])(using Context, AnnotationDefaults): List[ScalaTypeData] =
    val upperBound = bounds.hi.widenDealias
    val upperBoundTypes = upperBoundTypesFor(upperBound)
    if upperBoundTypes.isEmpty then
      List(objectTypeData)
    else
      upperBoundTypes.map(typeData(_, Set.empty, visitedTypeParameters, None, Nil))

  private def upperBoundTypesFor(tpe: Type)(using Context): List[Type] =
    val bounds = intersectionTypes(tpe)
    if tpe.widenDealias.isInstanceOf[AndType] then
      // Micronaut's generic metadata needs a JVM type, not a Scala intersection
      // display name such as Number & Comparable[T]. The first bound is the
      // primary bound Scala emits for Java-style bounded wildcards.
      bounds.take(1)
    else
      bounds

  private def intersectionTypes(tpe: Type)(using Context): List[Type] =
    tpe.widenDealias match
      case andType: AndType =>
        intersectionTypes(andType.tp1) ++ intersectionTypes(andType.tp2)
      case widened if typeName(widened) == "scala.Any" =>
        Nil
      case widened =>
        List(widened)

  private def lowerBounds(bounds: TypeBounds, visitedTypeParameters: Map[String, Int])(using Context, AnnotationDefaults): List[ScalaTypeData] =
    val lowerBound = bounds.lo.widenDealias
    if typeName(lowerBound) == "scala.Nothing" then
      Nil
    else
      List(typeData(lowerBound, Set.empty, visitedTypeParameters, None, Nil))

  private def objectTypeData(using Context): ScalaTypeData =
    ScalaTypeData(
      classOf[Object].getName,
      primitive = false,
      arrayDimensions = 0,
      interfaceType = false,
      java.util.Map.of(),
      null,
      Nil.asJava,
      Nil.asJava,
      annotatedTypeUse = false,
      classSymbolForName(classOf[Object].getName),
      genericPlaceholder = false,
      null,
      Nil.asJava
    )

  private def typeName(tpe: Type)(using Context): String =
    val symbol = tpe.classSymbol
    if symbol != Symbols.NoSymbol then
      className(symbol)
    else
      tpe.show

  private def className(symbol: Symbol)(using Context): String =
    if hasFlag(symbol, Flags.JavaDefined) &&
        symbol.owner != Symbols.NoSymbol &&
        symbol.owner.isClass &&
        !hasFlag(symbol.owner, Flags.PackageClass)
    then
      s"${className(symbol.owner)}$$${symbol.name}"
    else
      val binaryName = symbol.denot.binaryClassName
      if binaryName == null || binaryName.isBlank then
        symbol.showFullName
      else
        binaryName

  private def companionClassName(symbol: Symbol)(using Context): Option[String] =
    if hasFlag(symbol, Flags.ModuleClass) then
      val name = className(symbol)
      if name.endsWith("$") then Some(name.stripSuffix("$")) else None
    else
      None

  private def annotations(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaAnnotationData] =
    if symbol == Symbols.NoSymbol then
      Nil
    else
      // `scala.annotation.internal.*` is the compiler's own bookkeeping -- dotty attaches
      // `SourceFile` to every class it compiles, for instance. It is not part of the user's
      // model, and leaving it in wrote it into the annotation metadata of every generated
      // bean definition.
      // `@throws` is a declaration of thrown types, which `thrownTypes` models, not an
      // annotation on the member -- javac reports a `throws` clause the same way. Its argument
      // is a type reference in one of its spellings, which no annotation value can hold.
      declaredAnnotations(symbol)
        .filterNot(annotation => className(annotation.symbol).startsWith(InternalAnnotationPrefix))
        .filterNot(isThrowsAnnotation)
        .map(annotationData(_, Set.empty))

  /**
   * A symbol's annotations in the order they were written.
   *
   * dotty's `addAnnotation` prepends (`annot :: myAnnotations`), so `denot.annotations` is in
   * reverse declaration order. That is visible wherever order carries meaning: two
   * `@Location` annotations written `first` then `second` reached the metadata as
   * `second, first`, and so did the entries of the `@Repeatable` container built from them,
   * and the `@throws` clauses of a method.
   */
  private def declaredAnnotations(symbol: Symbol)(using Context): List[Annotation] =
    symbol.denot.annotations.reverse

  private def annotationData(
      annotation: dotty.tools.dotc.core.Annotations.Annotation,
      visitedAnnotationTypes: Set[String]
  )(using Context, AnnotationDefaults): ScalaAnnotationData =
    val symbol = annotation.symbol
    val name = className(symbol)
    val annotationType = annotationTypeData(symbol, visitedAnnotationTypes)
    ScalaAnnotationData(
      name,
      annotationValues(annotation, annotationType).asInstanceOf[JMap[CharSequence, Object]],
      annotationType
    )

  /**
   * Resolves an annotation type by name, for annotations that were never seen on an
   * extracted element -- one a visitor added programmatically, or one read from a class
   * file. Without this the metadata builder has no mirror for such an annotation and
   * silently resolves none of its meta-annotations.
   *
   * Member defaults are not available here: they are harvested per compilation unit (A10),
   * and this type may not be in the compilation at all.
   */
  def resolveAnnotationType(name: String)(using Context): ScalaAnnotationTypeData | Null =
    given AnnotationDefaults = AnnotationDefaults(Map.empty)
    val symbol = classSymbolForName(name)
    if isAnnotationSymbol(symbol) then annotationTypeData(symbol, Set.empty) else null

  private def annotationTypeData(symbol: Symbol, visitedAnnotationTypes: Set[String])(using Context, AnnotationDefaults): ScalaAnnotationTypeData | Null =
    if !isAnnotationSymbol(symbol) then
      null
    else
      val name = className(symbol)
      if visitedAnnotationTypes.contains(name) then
        ScalaAnnotationTypeData(name, java.util.List.of(), java.util.Map.of(), null, null, hasFlag(symbol, Flags.JavaDefined), symbol)
      else
        val nextVisited = visitedAnnotationTypes + name
        val annotations = declaredAnnotations(symbol)
          .filterNot(annotation => className(annotation.symbol) == name)
          .map(annotationData(_, nextVisited))
        val members = annotationMembers(symbol)
        ScalaAnnotationTypeData(
          name,
          annotations.asJava,
          members,
          retentionPolicyName(annotations).orNull,
          repeatableContainerName(annotations).orNull,
          hasFlag(symbol, Flags.JavaDefined),
          symbol
        )

  private def annotationMembers(symbol: Symbol)(using Context, AnnotationDefaults): LinkedHashMap[String, ScalaAnnotationMemberData] =
    val members = LinkedHashMap[String, ScalaAnnotationMemberData]()
    // Defaults of an annotation declared in this compilation are harvested from its trees. For
    // one read from a class file the compiler keeps only that a default exists, so the value is
    // read from the class file itself -- no annotation class is loaded for it.
    val defaults = summon[AnnotationDefaults].values.get(className(symbol)) match
      case Some(harvested) if harvested.nonEmpty => harvested
      case _ => classFileAnnotationDefaults(symbol)
    val constructorParameters =
      if symbol.primaryConstructor == Symbols.NoSymbol then Map.empty[String, Symbol]
      else
        symbol.primaryConstructor.paramSymss.flatten
          .filter(_.isTerm)
          .map(parameter => parameter.name.toString -> parameter)
          .toMap
    symbol.info.decls.toList.foreach { member =>
      val memberName = member.name.toString
      if isAnnotationMember(member, memberName) then
        val memberType = annotationMemberType(member)
        members.put(
          memberName,
          ScalaAnnotationMemberData(
            memberName,
            memberAnnotations(member, constructorParameters.get(memberName)).asJava,
            defaults.getOrElse(memberName, null),
            memberType.name,
            memberType.array,
            memberType.classType,
            memberType.enumType,
            memberType.annotationType,
            member
          )
        )
    }
    members

  /**
   * An annotation member is one thing, but Scala spreads a declaration across up to three
   * symbols: the constructor parameter, the backing field, and the accessor. An annotation
   * written as `@NonBinding val debug: Boolean` lands on the parameter and the field, not on
   * the accessor -- Scala targets the accessor only for `@(NonBinding @getter)`, a
   * meta-annotation nobody writes on an annotation class, because in Java there is only one
   * place the annotation could go. Reading the accessor alone therefore dropped it, and Core,
   * which collects `@NonBinding` and `@InstantiatedMember` from the member, saw a bare member:
   * a member excluded from interceptor binding was compared anyway, and an interceptor that
   * should have matched did not.
   */
  private def memberAnnotations(member: Symbol, parameter: Option[Symbol])(using
      Context,
      AnnotationDefaults
  ): List[ScalaAnnotationData] =
    val fromMember = annotations(member)
    val field = member.field
    val extra =
      parameter.toList.flatMap(annotations) ++
        (if field == Symbols.NoSymbol || field == member then Nil else annotations(field))
    if extra.isEmpty then
      fromMember
    else
      val seen = collection.mutable.LinkedHashSet.from(fromMember.map(_.name()))
      fromMember ++ extra.filter(annotation => seen.add(annotation.name()))

  private def isAnnotationMember(symbol: Symbol, name: String)(using Context): Boolean =
    name.nonEmpty &&
      !name.contains("$") &&
      !name.startsWith("<") &&
      symbol.isTerm &&
      !symbol.denot.isConstructor &&
      !hasFlag(symbol, Flags.Module) &&
      !hasFlag(symbol, Flags.Synthetic) &&
      !hasFlag(symbol, Flags.Artifact)

  private def annotationMemberType(symbol: Symbol)(using Context): AnnotationMemberType =
    val resultType = symbol.info match
      case methodType: MethodType => methodType.resultType
      case info => info
    annotationMemberType(resultType, array = false)

  private def annotationMemberType(tpe: Type, array: Boolean)(using Context): AnnotationMemberType =
    val widened = tpe.widenDealias
    widened match
      case applied: AppliedType if typeName(applied.tycon) == "scala.Array" && applied.args.nonEmpty =>
        annotationMemberType(applied.args.head, array = true)
      case _ =>
        val name = typeName(widened)
        val symbol = widened.classSymbol
        AnnotationMemberType(
          name,
          array,
          name == classOf[Class[?]].getName,
          symbol != Symbols.NoSymbol && hasFlag(symbol, Flags.Enum),
          isAnnotationSymbol(symbol)
        )

  private def retentionPolicyName(annotations: List[ScalaAnnotationData]): Option[String] =
    annotations.find(_.name() == classOf[java.lang.annotation.Retention].getName)
      .flatMap(annotation => annotationValue(annotation, "value"))
      .map(_.toString)

  private def repeatableContainerName(annotations: List[ScalaAnnotationData]): Option[String] =
    annotations.find(_.name() == classOf[java.lang.annotation.Repeatable].getName)
      .flatMap(annotation => annotationValue(annotation, "value"))
      .map(classValueName)

  private val ConstructorDefaultGetterPrefix = "$lessinit$greater$default$"

  /** A reference to an annotation constructor's default-argument getter. */
  private final case class DefaultGetterReference(index: Int)

  private def annotationValue(annotation: ScalaAnnotationData, memberName: String): Option[Object] =
    annotation.values().asScala.collectFirst {
      case (key, value) if memberName.contentEquals(key) => value
    }

  private def classValueName(value: Object): String =
    value match
      case classValueData: ScalaClassValueData => classValueData.name()
      case other => other.toString

  private def annotationValues(
      annotation: dotty.tools.dotc.core.Annotations.Annotation,
      annotationType: ScalaAnnotationTypeData | Null
  )(using Context, AnnotationDefaults): JMap[String, Object] =
    normalizeAnnotationArgumentValues(annotationArgumentValues(annotation.arguments), annotationType)

  private def annotationArgumentValues(arguments: List[tpd.Tree])(using Context, AnnotationDefaults): JMap[String, Object] =
    val values = LinkedHashMap[String, Object]()
    var positionalIndex = 0
    arguments.foreach {
      case named: tpd.NamedArg =>
        val value = annotationValue(named.arg)
        if value != null then
          values.put(named.name.toString, value)
      case tree =>
        val memberName = PositionalAnnotationMemberPrefix + positionalIndex
        val value = annotationValue(tree)
        if value != null then
          values.put(memberName, value)
        positionalIndex += 1
    }
    values

  private def normalizeAnnotationArgumentValues(
      values: JMap[String, Object],
      annotationType: ScalaAnnotationTypeData | Null
  ): JMap[String, Object] =
    if values.isEmpty then
      values
    else if annotationType == null then
      val normalized = LinkedHashMap[String, Object]()
      values.asScala.foreach { case (key, value) =>
        if defaultGetterReferenceIndex(value).isEmpty then
          normalized.put(legacyPositionalAnnotationMemberName(key).getOrElse(key), value)
      }
      normalized
    else
      val normalized = LinkedHashMap[String, Object]()
      val memberNames = annotationType.members().keySet().asScala.toList
      values.asScala.foreach { case (key, value) =>
        val defaultIndex = defaultGetterReferenceIndex(value)
        val memberName = defaultIndex
          .flatMap(index => memberNames.lift(index - 1))
          .orElse(positionalAnnotationMemberName(key, memberNames))
          .getOrElse(key)
        defaultIndex match
          case None =>
            normalized.put(memberName, value)
          case Some(_) =>
            // The member was not supplied. Its value is the annotation's own default, and
            // if the annotation declares none there is no value to record -- storing the
            // getter reference itself would put a synthetic method name into the metadata.
            annotationType.members().asScala.get(memberName)
              .map(_.defaultValue())
              .filter(_ != null)
              .foreach(defaultValue => normalized.put(memberName, defaultValue))
      }
      normalized

  private def positionalAnnotationMemberName(key: String, memberNames: List[String]): Option[String] =
    if memberNames.isEmpty || !key.startsWith(PositionalAnnotationMemberPrefix) then
      None
    else
      key.stripPrefix(PositionalAnnotationMemberPrefix).toIntOption.flatMap(index => memberNames.lift(index))

  private def legacyPositionalAnnotationMemberName(key: String): Option[String] =
    if !key.startsWith(PositionalAnnotationMemberPrefix) then
      None
    else
      key.stripPrefix(PositionalAnnotationMemberPrefix).toIntOption.map {
        case 0 => "value"
        case index => "value" + index
      }

  private def defaultGetterReferenceIndex(value: Object): Option[Int] =
    value match
      case reference: DefaultGetterReference => Some(reference.index)
      case _ => None

  // Scala passes a call to the annotation constructor's default-argument getter for every
  // member the author did not supply. That call is not a value -- the annotation's own
  // declared default is -- so it is recognised from the callee's *symbol name*, which the
  // compiler encodes stably, rather than from the printed form of the tree.
  private def defaultGetterReference(tree: tpd.Tree)(using Context): Option[DefaultGetterReference] =
    val symbol = tree.symbol
    if symbol == Symbols.NoSymbol then
      None
    else
      val name = symbol.name.toString
      if name.startsWith(ConstructorDefaultGetterPrefix) then
        name.stripPrefix(ConstructorDefaultGetterPrefix).toIntOption.map(DefaultGetterReference.apply)
      else
        None

  // Wrappers the typer adds around an annotation argument that carry no value of their
  // own. Unwrapping them structurally is what lets the cases below stay exact.
  private def unwrapAnnotationTree(tree: tpd.Tree): tpd.Tree =
    tree match
      case typed: tpd.Typed => unwrapAnnotationTree(typed.expr)
      case inlined: tpd.Inlined => unwrapAnnotationTree(inlined.expansion)
      case block: tpd.Block if block.stats.isEmpty => unwrapAnnotationTree(block.expr)
      case _ => tree

  private def annotationValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Object | Null =
    val unwrapped = unwrapAnnotationTree(tree)
    arrayLiteralValues(unwrapped)
      .orElse(classLiteralValue(unwrapped))
      .orElse(nestedAnnotationValue(unwrapped))
      .orElse(typedConstantValue(unwrapped))
      .orElse(defaultGetterReference(unwrapped))
      .getOrElse {
        unwrapped match
          case literal: tpd.Literal =>
            annotationConstantValue(literal.const.value).orNull
          case select: tpd.Select if isEnumConstant(select.symbol) =>
            select.name.toString
          case ident: tpd.Ident if ident.name.toString == "_" =>
            null
          case ident: tpd.Ident if isEnumConstant(ident.symbol) =>
            ident.name.toString
          case _ =>
            // Previously this stored the compiler's *display string* for the argument, so
            // `@Value(Foo.BAR)` -- where `BAR` is a plain `val`, which in Scala 3 has no
            // `ConstantType` -- was baked into the bean definition as the literal text
            // "Foo.BAR". Silently recording something that was never the author's value is
            // worse than refusing, so say so, at the argument's own position.
            report.error(
              "Unsupported Scala annotation value: " + unwrapped.show +
                ". Annotation members must be compile-time constants, class literals, enum " +
                "constants, arrays or nested annotations. A plain `val` is not a constant in " +
                "Scala 3; declare it `final val` to use it here.",
              unwrapped.srcPos
            )
            null
      }

  private def typedConstantValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[Object] =
    def symbolConstantValue(symbol: Symbol): Option[Object] =
      if symbol == Symbols.NoSymbol then None
      else constantTypeValue(symbol.info)

    constantTypeValue(tree.tpe)
      .orElse(symbolConstantValue(tree.symbol))
      .orElse {
        tree match
          case typed: tpd.Typed =>
            typedConstantValue(typed.expr)
          case apply: tpd.Apply =>
            typedConstantValue(apply.fun)
          case typeApply: tpd.TypeApply =>
            typedConstantValue(typeApply.fun)
          case select: tpd.Select =>
            symbolConstantValue(select.symbol)
          case ident: tpd.Ident =>
            symbolConstantValue(ident.symbol)
          case _ =>
            None
      }

  private def constantTypeValue(tpe: Type)(using Context, AnnotationDefaults): Option[Object] =
    tpe match
      case constant: ConstantType =>
        annotationConstantValue(constant.value.value)
      case method: MethodType =>
        constantTypeValue(method.resultType)
      case _ =>
        None

  private def annotationConstantValue(value: Any)(using Context, AnnotationDefaults): Option[Object] =
    value match
      case null => None
      case value: String => Some(value)
      case value: java.lang.Boolean => Some(value)
      case value: java.lang.Byte => Some(value)
      case value: java.lang.Short => Some(value)
      case value: java.lang.Integer => Some(value)
      case value: java.lang.Long => Some(value)
      case value: java.lang.Float => Some(value)
      case value: java.lang.Double => Some(value)
      case value: java.lang.Character => Some(value)
      case _ => None

  private def arrayLiteralValues(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[Object] =
    tree match
      case seq: tpd.SeqLiteral =>
        Some(annotationArray(seq.elems.map(annotationValue).filter(_ != null)))
      case typed: tpd.Typed =>
        arrayLiteralValues(typed.expr)
      case apply: tpd.Apply =>
        apply.args.iterator.map(arrayLiteralValues).collectFirst { case Some(values) => values }
          .orElse(arrayLiteralValues(apply.fun))
      case typeApply: tpd.TypeApply =>
        arrayLiteralValues(typeApply.fun)
      case _ =>
        None

  private def annotationArray(values: List[Object])(using Context, AnnotationDefaults): Object =
    val normalized = values
    if normalized.forall(_.isInstanceOf[String]) then
      normalized.map(_.asInstanceOf[String]).toArray[String]
    else if normalized.forall(_.isInstanceOf[ScalaAnnotationData]) then
      normalized.map(_.asInstanceOf[ScalaAnnotationData]).toArray[ScalaAnnotationData]
    else
      normalized.toArray

  private def nestedAnnotationValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaAnnotationData] =
    tree match
      case typed: tpd.Typed =>
        nestedAnnotationValue(typed.expr)
      case apply: tpd.Apply =>
        val symbol = annotationClassSymbol(apply)
        if isAnnotationSymbol(symbol) then
          val annotationType = annotationTypeData(symbol, Set.empty)
          Some(
            ScalaAnnotationData(
              className(symbol),
              annotationValuesFromArguments(apply.args, annotationType).asInstanceOf[JMap[CharSequence, Object]],
              annotationType
            )
          )
        else
          nestedAnnotationValue(apply.fun)
      case typeApply: tpd.TypeApply =>
        nestedAnnotationValue(typeApply.fun)
      case _ =>
        None

  private def annotationClassSymbol(tree: tpd.Tree)(using Context): Symbol =
    tree match
      case typed: tpd.Typed =>
        annotationClassSymbol(typed.expr)
      case apply: tpd.Apply =>
        annotationClassSymbol(apply.fun)
      case typeApply: tpd.TypeApply =>
        annotationClassSymbol(typeApply.fun)
      case select: tpd.Select =>
        annotationClassSymbol(select.qualifier)
      case newTree: tpd.New =>
        newTree.tpt.tpe.classSymbol
      case _ =>
        tree.tpe.classSymbol

  private def isClassOf(typeApply: tpd.TypeApply)(using Context): Boolean =
    typeApply.args.nonEmpty && typeApply.fun.symbol != Symbols.NoSymbol &&
      (typeApply.fun.symbol.showFullName == "scala.Predef.classOf" || typeApply.fun.symbol.name.toString == "classOf")

  private def annotationValuesFromArguments(
      arguments: List[tpd.Tree],
      annotationType: ScalaAnnotationTypeData | Null
  )(using Context, AnnotationDefaults): JMap[String, Object] =
    normalizeAnnotationArgumentValues(annotationArgumentValues(arguments), annotationType)

  // A class literal carries its referenced type in a `ClazzTag` constant on the tree's
  // *type*, whatever tree shape it takes -- `classOf[Foo]` types as
  // `ConstantType(Constant(Foo, ClazzTag))`. Reading it here is what makes recognising a
  // class literal exact, where matching the callee symbol is not: the symbol of
  // `classOf` reports its name as plain `classOf`, not `scala.Predef.classOf`.
  private def classConstantType(tpe: Type)(using Context): Option[Type] =
    tpe match
      case constant: ConstantType if constant.value.tag == Constants.ClazzTag =>
        Some(constant.value.typeValue)
      case _ =>
        None

  private def classLiteralValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaClassValueData] =
    classConstantType(tree.tpe)
      .map(referenced => classValueData(classLiteralTypeName(referenced), referenced.classSymbol))
      .orElse(classLiteralValueFromTree(tree))

  private def classLiteralValueFromTree(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaClassValueData] =
    tree match
      case typeApply: tpd.TypeApply if isClassOf(typeApply) =>
        val name = classLiteralTypeName(typeApply.args.head.tpe)
        Some(classValueData(name, typeApply.args.head.tpe.classSymbol))
      case typed: tpd.Typed =>
        classLiteralValue(typed.expr)
      case apply: tpd.Apply =>
        classLiteralValue(apply.fun)
      case typeApply: tpd.TypeApply =>
        classLiteralValue(typeApply.fun)
      case _ =>
        None

  private def classValueData(name: String, fallback: Symbol = Symbols.NoSymbol)(using Context, AnnotationDefaults): ScalaClassValueData =
    val resolvedName = resolveClassLiteralName(name, fallback)
    val classSymbol =
      if fallback != Symbols.NoSymbol then fallback
      else classSymbolForName(resolvedName)
    val annotationType =
      if isAnnotationSymbol(classSymbol) then annotationTypeData(classSymbol, Set.empty)
      else null
    ScalaClassValueData(resolvedName, annotationType)

  private def classLiteralTypeName(tpe: Type)(using Context): String =
    val rawName = tpe.widenDealias match
      case applied: AppliedType if typeName(applied.tycon) != "scala.Array" => typeName(applied.tycon)
      case widened => typeName(widened)
    ScalaPrimitiveNames.getOrElse(rawName, rawName)

  private def resolveClassLiteralName(name: String, fallback: Symbol)(using Context): String =
    if fallback != Symbols.NoSymbol then
      val symbolName = className(fallback)
      ScalaPrimitiveNames.getOrElse(symbolName, symbolName)
    else
      val aliased = ScalaClassLiteralAliases.getOrElse(name, name)
      val symbol = classSymbolForName(aliased)
      if symbol != Symbols.NoSymbol then
        className(symbol)
      else if aliased.contains(".") then
        javaNestedClassLiteralName(aliased).getOrElse(aliased)
      else if aliased.indexOf('$') > -1 then
        aliased
      else
        val javaLangName = s"java.lang.$aliased"
        if classSymbolForName(javaLangName) != Symbols.NoSymbol then javaLangName else aliased

  private def javaNestedClassLiteralName(name: String)(using Context): Option[String] =
    val packageIndex = name.lastIndexOf('.')
    if packageIndex < 0 then
      None
    else
      val prefix = name.substring(0, packageIndex + 1)
      val simpleName = name.substring(packageIndex + 1)
      simpleName.indices.drop(1).iterator
        .filter(index => simpleName.charAt(index).isUpper)
        .map { index =>
          val candidate = s"$prefix${simpleName.substring(0, index)}$$${simpleName.substring(index)}"
          val symbol = classSymbolForName(candidate)
          if symbol != Symbols.NoSymbol then Some(className(symbol)) else None
        }
        .collectFirst { case Some(resolved) => resolved }

  // `Flags.EnumValue` (`Enum | StableRealizable`) covers simple Scala enum cases and
  // `Flags.EnumCase` (`Case | Enum`) the parameterised ones. Java enum constants are read by
  // dotty's classfile parser, which translates `ACC_ENUM` to a bare `Enum` flag without
  // `StableRealizable`, so `Flags.JavaEnumValue` never matches one; `JavaDefined | Enum` is
  // the correct set, narrowed to term symbols so the enum class itself is not mistaken for
  // one of its constants.
  //
  // Written as the union rather than as `Flags.JavaEnum`, which is exactly this pair but
  // exists only from 3.4 onwards -- the union compiles on every supported compiler.
  private def isEnumConstant(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (hasAllFlags(symbol, Flags.EnumValue) ||
        (symbol.isTerm && hasAllFlags(symbol, Flags.JavaDefined | Flags.Enum)) ||
        hasAllFlags(symbol, Flags.EnumCase))

  private def modifiers(symbol: Symbol)(using Context): Set[ElementModifier] =
    val modifiers = LinkedHashSet[ElementModifier]()
    // dotty leaves `Flags.Private` unset for a qualified `private[pkg]`, which is already
    // the right answer: it compiles to a public member.
    if hasFlag(symbol, Flags.Private) then modifiers.add(ElementModifier.PRIVATE)
    // Scala's `protected` means subclass-only, which the JVM cannot express -- JVM
    // `protected` also grants package access -- so scalac emits both `protected` and
    // `protected[pkg]` members as *public* and enforces the restriction at compile time.
    // Reporting them as protected made Micronaut refuse to inject or introspect members it
    // could legally call. A Java-defined member really is JVM-protected.
    if hasFlag(symbol, Flags.Protected) && hasFlag(symbol, Flags.JavaDefined) then modifiers.add(ElementModifier.PROTECTED)
    if hasFlag(symbol, Flags.Deferred) || hasFlag(symbol, Flags.Abstract) then modifiers.add(ElementModifier.ABSTRACT)
    if hasFlag(symbol, Flags.Final) then modifiers.add(ElementModifier.FINAL)
    if symbol.isClass && hasFlag(symbol, Flags.Sealed) then modifiers.add(ElementModifier.SEALED)
    if hasFlag(symbol, Flags.JavaStatic) || hasFlag(symbol, Flags.Module) then modifiers.add(ElementModifier.STATIC)
    // The compiler keeps a Java member's `transient`, `volatile` and `native` as annotations
    // of its own; Scala spells the same three the same way.
    if symbol.hasAnnotation(Symbols.defn.TransientAnnot) then modifiers.add(ElementModifier.TRANSIENT)
    if symbol.hasAnnotation(Symbols.defn.VolatileAnnot) then modifiers.add(ElementModifier.VOLATILE)
    if symbol.hasAnnotation(Symbols.defn.NativeAnnot) then modifiers.add(ElementModifier.NATIVE)
    // A Java member that is neither public nor private is package-private, which the compiler
    // records as a private-within of its package; Micronaut reads package-private as none of
    // the three, and generates reflective access for one it cannot call from another package.
    // Reporting it public made the generated code call it directly. A Scala `private[pkg]`
    // compiles to a public member and stays one.
    val packagePrivate = hasFlag(symbol, Flags.JavaDefined) && symbol.privateWithin != Symbols.NoSymbol
    if !modifiers.contains(ElementModifier.PRIVATE) && !modifiers.contains(ElementModifier.PROTECTED) && !packagePrivate then
      modifiers.add(ElementModifier.PUBLIC)
    modifiers.asScala.toSet

  private def isAnnotationSymbol(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (symbol.denot.isAnnotation || hasFlag(symbol, Flags.JavaAnnotation))

  private def isInterfaceSymbol(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (hasFlag(symbol, Flags.Trait) || hasAllFlags(symbol, Flags.JavaInterface))

  /**
   * The class symbol for a binary name.
   *
   * A nested class is looked up as a member of its enclosing class first. The compiler also
   * enters a Java class file named `Map$Entry` as a top-level class of that name when it lists
   * the package, and that symbol knows nothing of `Map`: an element built from it reported no
   * enclosing type and a canonical name with the dollar still in it. A name ending in `$` is a
   * module class and is looked up as written.
   */
  private def classSymbolForName(name: String)(using Context): Symbol =
    val nested =
      if name.contains('$') && !name.endsWith("$") then Symbols.getClassIfDefined(name.replace('$', '.'))
      else Symbols.NoSymbol
    if nested != Symbols.NoSymbol then nested
    else Symbols.getClassIfDefined(name)

  private def skipClass(symbol: Symbol)(using Context, AnnotationDefaults): Boolean =
    symbol == Symbols.NoSymbol ||
      hasFlag(symbol, Flags.PackageClass) ||
      hasFlag(symbol, Flags.Synthetic) ||
      hasFlag(symbol, Flags.Artifact) ||
      // `object` is the idiomatic Scala singleton, so a module class carrying annotations
      // is processed. An unannotated one -- the ordinary companion of a class -- is still
      // skipped, since it declares no beans and processing it would generate nothing.
      (hasFlag(symbol, Flags.ModuleClass) && annotations(symbol).isEmpty)

  private def skipMethod(symbol: Symbol)(using Context): Boolean =
    symbol == Symbols.NoSymbol ||
      symbol.denot.isConstructor ||
      hasFlag(symbol, Flags.Synthetic | Flags.Artifact | Flags.Accessor)

  private def skipAccessorCandidate(symbol: Symbol)(using Context): Boolean =
    symbol == Symbols.NoSymbol ||
      symbol.denot.isConstructor ||
      hasFlag(symbol, Flags.Synthetic) ||
      hasFlag(symbol, Flags.Artifact)

  private def skipField(symbol: Symbol)(using Context, AnnotationDefaults): Boolean =
    symbol == Symbols.NoSymbol ||
      ((hasFlag(symbol, Flags.Synthetic) || hasFlag(symbol, Flags.Artifact)) && annotations(symbol).isEmpty)

  private def hasFlag(symbol: Symbol, flag: Flags.FlagSet)(using Context): Boolean =
    symbol != Symbols.NoSymbol && symbol.denot.isOneOf(flag)

  // Several `Flags.FlagSet` constants are conjunctions rather than unions:
  // `JavaInterface = JavaDefined | NoInits | Trait`, `JavaEnumValue = JavaDefined | EnumValue`,
  // `EnumValue = Enum | StableRealizable` and `EnumCase = Case | Enum`. `isOneOf` tests for a
  // non-empty intersection, so testing those with `hasFlag` matches far too much: every
  // `JavaDefined` symbol would satisfy `JavaInterface`, and every stable val would satisfy
  // `EnumValue`. Conjunction sets must go through `isAllOf`, as dotty itself does.
  private def hasAllFlags(symbol: Symbol, flags: Flags.FlagSet)(using Context): Boolean =
    symbol != Symbols.NoSymbol && symbol.denot.isAllOf(flags)
