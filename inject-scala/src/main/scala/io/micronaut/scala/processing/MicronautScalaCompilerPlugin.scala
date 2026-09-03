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
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.AnnotatedType
import dotty.tools.dotc.core.Types.AndType
import dotty.tools.dotc.core.Types.AppliedType
import dotty.tools.dotc.core.Types.ConstantType
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.core.Types.OrType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.TypeBounds
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.plugins.StandardPlugin
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.Pickler
import dotty.tools.dotc.transform.PostTyper
import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.scala.processing.visitor.ScalaAnnotationData
import io.micronaut.scala.processing.visitor.ScalaAnnotationMemberData
import io.micronaut.scala.processing.visitor.ScalaAnnotationTypeData
import io.micronaut.scala.processing.visitor.ScalaClassValueData
import io.micronaut.scala.processing.visitor.ScalaClassData
import io.micronaut.scala.processing.visitor.ScalaFieldData
import io.micronaut.scala.processing.visitor.ScalaMethodData
import io.micronaut.scala.processing.visitor.ScalaParameterData
import io.micronaut.scala.processing.visitor.ScalaProcessingEngine
import io.micronaut.scala.processing.visitor.ScalaPropertyData
import io.micronaut.scala.processing.visitor.ScalaTypeData

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Map as JMap
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 * Scala 3 compiler plugin that adapts typed Scala symbols to Micronaut's Element API.
 */
final class MicronautScalaCompilerPlugin extends StandardPlugin:

  override val name: String = "micronaut-scala"
  override val description: String = "Generates Micronaut metadata for Scala 3 sources"
  override val optionsHelp: Option[String] = None

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
  private var typeUnitsSeen = 0
  private var typeVisitorsProcessed = false
  private var beanDefinitionsProcessed = false

  def addClasses(classes: List[ScalaClassData])(using ctx: Context): Unit =
    try
      engineInstance.addClasses(classes.asJava)
      typeUnitsSeen += 1
      if !typeVisitorsProcessed && typeUnitsSeen >= unitCount then
        typeVisitorsProcessed = true
        engineInstance.processTypeVisitors()
        processBeanDefinitions()
    catch
      case exception: ProcessingException =>
        reportProcessingException(exception)

  def processBeanDefinitions()(using ctx: Context): Unit =
    try
      if !beanDefinitionsProcessed then
        beanDefinitionsProcessed = true
        engineInstance.processBeanDefinitions()
    catch
      case exception: ProcessingException =>
        reportProcessingException(exception)

  private def unitCount(using ctx: Context): Int =
    math.max(1, ctx.run.units.size)

  private def engineInstance(using ctx: Context): ScalaProcessingEngine =
    var current = engine
    if current == null then
      current = ScalaProcessingEngine(
        outputDirectory,
        classpath.asJava,
        options,
        message => report.inform(message),
        message => report.warning(message),
        message => report.error(message)
      )
      engine = current
    current

  private def outputDirectory(using ctx: Context): File =
    val value = ctx.settings.outputDir.valueIn(ctx.settingsState)
    val output = File(value.path)
    output.mkdirs()
    output

  private def classpath(using ctx: Context): List[File] =
    val value = ctx.settings.classpath.valueIn(ctx.settingsState)
    value.split(File.pathSeparator).toList.filter(_.nonEmpty).map(File(_))

  private def reportProcessingException(exception: ProcessingException)(using Context): Unit =
    val message = exception.getMessage
    if message != null && !message.isBlank then
      report.error(message)
    else
      report.error(processingExceptionMessage(exception))

  private def processingExceptionMessage(exception: ProcessingException): String =
    val element = exception.getElement
    val elementDescription = if element == null then "" else s" [${element.getName}]"
    s"Error processing Scala element$elementDescription: ${exceptionMessage(exception)}"

  private def exceptionMessage(exception: Throwable): String =
    var current: Throwable | Null = exception
    var fallback: Throwable = exception
    while current != null do
      fallback = current
      val message = current.getMessage
      if message != null && !message.isBlank then
        return message
      current = current.getCause
      if current == exception then
        current = null
    val stackTrace = fallback.getStackTrace
    if stackTrace.isEmpty then fallback.getClass.getName
    else s"${fallback.getClass.getName} at ${stackTrace(0)}"

private final class TypeVisitorPhase(state: ProcessingState) extends PluginPhase:

  override val phaseName: String = "micronaut-scala-type-visitors"
  override val runsAfter: Set[String] = Set(PostTyper.name)
  override val runsBefore: Set[String] = Set(BeanDefinitionPhase.PhaseName)

  override def run(using Context): Unit =
    val classes = ScalaModelExtractor.collect(summon[Context].compilationUnit)
    state.addClasses(classes)

private object BeanDefinitionPhase:
  val PhaseName = "micronaut-scala-bean-definitions"

private final class BeanDefinitionPhase(state: ProcessingState) extends PluginPhase:

  override val phaseName: String = BeanDefinitionPhase.PhaseName
  override val runsAfter: Set[String] = Set(TypeVisitorPhase(state).phaseName)
  override val runsBefore: Set[String] = Set(Pickler.name)

  override def run(using Context): Unit =
    state.processBeanDefinitions()

private object ScalaModelExtractor:

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

  def collect(unit: CompilationUnit)(using Context): List[ScalaClassData] =
    given AnnotationDefaults = AnnotationDefaults(annotationDefaultValues(unit.tpdTree))
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

  private def toClassData(typeDef: tpd.TypeDef, enclosingTypeName: String | Null)(using Context, AnnotationDefaults): Option[ScalaClassData] =
    val symbol = typeDef.symbol
    if skipClass(symbol) then
      None
    else
      typeDef.rhs match
        case template: tpd.Template =>
          val declarations = symbol.info.decls.toList
          val allMethods = template.body.collect { case method: tpd.DefDef => method }
          val methodByName = LinkedHashMap[String, ScalaMethodData]()
          allMethods.foreach { method =>
            if !skipAccessorCandidate(method.symbol) then
              methodByName.put(method.name.toString, methodData(method, constructor = false, owner = symbol))
          }
          declarations.foreach { declaration =>
            val declarationName = declaration.name.toString
            if isPropertyDeclaration(declaration, declarationName) || isPropertySetterDeclaration(declaration, declarationName) then
              methodByName.put(declarationName, methodData(declaration))
          }
          val methods = allMethods
            .filterNot(method => skipMethod(method.symbol))
            .map(method => methodData(method, constructor = false, owner = symbol))
          val enumMethods =
            if hasFlag(symbol, Flags.Enum) then List(enumValueOfMethodData(symbol))
            else Nil
          val fields = template.body.collect {
            case field: tpd.ValDef if !skipField(field.symbol) => fieldData(field)
          }
          val enumConstants = enumConstantSymbols(symbol)
            .map(enumConstantFieldData(_, symbol))
          val allFields = (fields ++ enumConstants).distinctBy(_.name())
          val constructors = List(methodData(template.constr, constructor = true, owner = symbol))
          val constructorProps = constructorProperties(template.constr, methodByName, allFields)
          val properties = constructorProps ++ bodyProperties(declarations, methodByName, allFields, constructorProps.map(_.name).toSet)
          val parents = template.parents
            .filterNot(parent => typeName(parent.tpe) == classOf[Object].getName)
            .map(typeData)
          val superType = parents.find(parent => !parent.interfaceType()).orNull
          val interfaces = parents.filter(_.interfaceType())
          Some(ScalaClassData(
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
            (methods ++ enumMethods).asJava,
            allFields.asJava,
            properties.asJava,
            enclosingTypeName,
            typeDef
          ))
        case _ =>
          None

  private def constructorProperties(
      constructor: tpd.DefDef,
      methods: LinkedHashMap[String, ScalaMethodData],
      fields: List[ScalaFieldData]
  )(using Context, AnnotationDefaults): List[ScalaPropertyData] =
    constructor.termParamss.flatten
      .filter { param =>
        val propertyName = param.name.toString
        val readMethod = methods.get(propertyName)
        val field = fields.find(_.name == propertyName).orNull
        val propertyAccessor = hasFlag(param.symbol, Flags.ParamAccessor) ||
          hasFlag(param.symbol, Flags.CaseAccessor)
        propertyAccessor &&
          ((readMethod != null && !readMethod.modifiers().contains(ElementModifier.PRIVATE)) ||
            (field != null && !field.modifiers().contains(ElementModifier.PRIVATE)))
      }
      .map { param =>
        val propertyName = param.name.toString
        val readMethod = methods.get(propertyName)
        val writeMethod = methods.get(propertyName + "_=")
        val field = fields.find(_.name == propertyName).orNull
        ScalaPropertyData(
          propertyName,
          typeData(param.tpt.tpe),
          readMethod,
          writeMethod,
          field,
          annotations(param.symbol).asJava,
          modifiers(param.symbol).asJava,
          param
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

  private def propertyAnnotations(readMethod: ScalaMethodData, field: ScalaFieldData | Null): java.util.List[ScalaAnnotationData] =
    if readMethod != null && !readMethod.annotations().isEmpty then
      readMethod.annotations()
    else if field != null then
      field.annotations()
    else
      java.util.List.of()

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
      method
    )

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

  private def thrownTypes(symbol: Symbol)(using Context, AnnotationDefaults): List[ScalaTypeData] =
    if symbol == Symbols.NoSymbol then
      Nil
    else
      symbol.denot.annotations
        .filter(annotation => className(annotation.symbol) == "scala.throws")
        .flatMap(annotation => annotation.arguments.flatMap(thrownType))

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
        Some(classValueTypeData(renderedClassLiteralValue(value).getOrElse(value)))
      case _ =>
        None

  private def fieldData(field: tpd.ValDef)(using Context, AnnotationDefaults): ScalaFieldData =
    val fieldType = typeData(field.tpt)
    val fieldAnnotations = annotations(field.symbol) ++ typeUseNullabilityAnnotations(fieldType)
    ScalaFieldData(
      field.name.toString,
      fieldType,
      fieldAnnotations.asJava,
      modifiers(field.symbol).asJava,
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

  private def constantValue(tree: tpd.Tree): Object | Null =
    tree match
      case literal: tpd.Literal =>
        literal.const.value match
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
      case _ =>
        null

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
    val parameterType = typeData(parameter.tpt)
    val parameterAnnotations = annotations(parameter.symbol) ++ typeUseNullabilityAnnotations(parameterType)
    ScalaParameterData(
      parameter.name.toString,
      parameterType,
      parameterAnnotations.asJava,
      parameter
    )

  private def parameterData(name: String, tpe: Type, nativeType: Object)(using Context, AnnotationDefaults): ScalaParameterData =
    val parameterType = typeData(tpe)
    ScalaParameterData(
      name,
      parameterType,
      typeUseNullabilityAnnotations(parameterType).asJava,
      nativeType
    )

  private def typeData(tpe: Type)(using Context, AnnotationDefaults): ScalaTypeData =
    typeData(tpe, Set.empty)

  private def typeData(tpe: Type, visitedTypes: Set[String])(using Context, AnnotationDefaults): ScalaTypeData =
    typeData(tpe, visitedTypes, Map.empty, None, Nil)

  private def typeData(tpt: tpd.Tree)(using Context, AnnotationDefaults): ScalaTypeData =
    typeData(tpt, Set.empty)

  private def typeData(tpt: tpd.Tree, visitedTypes: Set[String])(using Context, AnnotationDefaults): ScalaTypeData =
    val (baseTree, treeAnnotations) = annotatedTree(tpt)
    typeData(baseTree.tpe, visitedTypes, Map.empty, Some(baseTree), treeAnnotations)

  private def typeData(
      tpe: Type,
      visitedTypes: Set[String],
      visitedTypeParameters: Map[String, Int],
      typeTree: Option[tpd.Tree],
      extraAnnotations: List[Annotation]
  )(using Context, AnnotationDefaults): ScalaTypeData =
    val (annotatedWidened, typeAnnotations) = annotatedType(tpe.widenDealiasKeepAnnots)
    val (widened, explicitNullable) = explicitNullableType(annotatedWidened)
    val allTypeAnnotations = extraAnnotations ++ typeAnnotations
    if widened.isInstanceOf[TypeBounds] then
      wildcardTypeData(widened.asInstanceOf[TypeBounds], allTypeAnnotations, explicitNullable)
    else
      val widenedSymbol = widened.typeSymbol
      if widenedSymbol != Symbols.NoSymbol && widenedSymbol.isTypeParam then
        typeParameterData(widenedSymbol, allTypeAnnotations, explicitNullable, visitedTypeParameters)
      else widened match
        case applied: AppliedType if typeName(applied.tycon) == "scala.Array" && applied.args.nonEmpty =>
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
          val hierarchy = typeHierarchy(widened, symbol, name, primitiveName.isDefined, visitedTypes)
          ScalaTypeData(name, primitiveName.isDefined, 0, interfaceType, typeArguments(symbol, applied.args, visitedTypes, visitedTypeParameters, typeTree.flatMap(appliedTypeArguments).getOrElse(Nil)), hierarchy.superType, hierarchy.interfaces.asJava, typeAnnotationsFor(symbol, allTypeAnnotations, explicitNullable).asJava, allTypeAnnotations.nonEmpty || explicitNullable, symbol)
        case _ =>
          val rawName = typeName(widened)
          val primitiveName = ScalaPrimitiveNames.get(rawName)
          val name = primitiveName.getOrElse(rawName)
          val symbol = widened.classSymbol
          val interfaceType = isInterfaceSymbol(symbol)
          val hierarchy = typeHierarchy(widened, symbol, name, primitiveName.isDefined, visitedTypes)
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
    (current, typeAnnotations.toList)

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
    (current, typeAnnotations.toList)

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

  private def typeAnnotationsFor(
      symbol: Symbol,
      typeAnnotations: List[Annotation],
      explicitNullable: Boolean
  )(using Context, AnnotationDefaults): List[ScalaAnnotationData] =
    val nullable = if explicitNullable then List(NullableAnnotationData) else Nil
    nullable ++ typeAnnotations.map(annotationData(_, Set.empty)) ++ annotations(symbol)

  private def typeHierarchy(tpe: Type, symbol: Symbol, name: String, primitive: Boolean, visitedTypes: Set[String])(using Context, AnnotationDefaults): TypeHierarchy =
    if primitive || symbol == Symbols.NoSymbol || visitedTypes.contains(name) then
      TypeHierarchy(null, Nil)
    else
      val nextVisited = visitedTypes + name
      val parents = tpe.parents
        .filterNot(parent => typeName(parent) == classOf[Object].getName)
        .map(parent => typeData(parent, nextVisited))
      TypeHierarchy(
        parents.find(parent => !parent.interfaceType()).orNull,
        parents.filter(_.interfaceType())
      )

  private def typeArguments(symbol: Symbol, arguments: List[Type], visitedTypes: Set[String], visitedTypeParameters: Map[String, Int], argumentTrees: List[tpd.Tree] = Nil)(using Context, AnnotationDefaults): java.util.Map[String, ScalaTypeData] =
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

  private def wildcardTypeData(
      bounds: TypeBounds,
      typeAnnotations: List[Annotation],
      explicitNullable: Boolean
  )(using Context, AnnotationDefaults): ScalaTypeData =
    val upper = upperBounds(bounds, Map.empty)
    val lower = lowerBounds(bounds, Map.empty)
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
      symbol.denot.annotations.map(annotationData(_, Set.empty))

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

  private def annotationTypeData(symbol: Symbol, visitedAnnotationTypes: Set[String])(using Context, AnnotationDefaults): ScalaAnnotationTypeData | Null =
    if !isAnnotationSymbol(symbol) then
      null
    else
      val name = className(symbol)
      if visitedAnnotationTypes.contains(name) then
        ScalaAnnotationTypeData(name, java.util.List.of(), java.util.Map.of(), null, null, symbol)
      else
        val nextVisited = visitedAnnotationTypes + name
        val annotations = symbol.denot.annotations
          .filterNot(annotation => className(annotation.symbol) == name)
          .map(annotationData(_, nextVisited))
        val members = annotationMembers(symbol)
        ScalaAnnotationTypeData(
          name,
          annotations.asJava,
          members,
          retentionPolicyName(annotations).orNull,
          repeatableContainerName(annotations).orNull,
          symbol
        )

  private def annotationMembers(symbol: Symbol)(using Context, AnnotationDefaults): LinkedHashMap[String, ScalaAnnotationMemberData] =
    val members = LinkedHashMap[String, ScalaAnnotationMemberData]()
    val defaults = summon[AnnotationDefaults].values.getOrElse(className(symbol), Map.empty)
    symbol.info.decls.toList.foreach { member =>
      val memberName = member.name.toString
      if isAnnotationMember(member, memberName) then
        val memberType = annotationMemberType(member)
        members.put(
          memberName,
          ScalaAnnotationMemberData(
            memberName,
            annotations(member).asJava,
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
        normalized.put(legacyPositionalAnnotationMemberName(key).getOrElse(key), value)
      }
      normalized
    else
      val normalized = LinkedHashMap[String, Object]()
      val memberNames = annotationType.members().keySet().asScala.toList
      values.asScala.foreach { case (key, value) =>
        val defaultIndex = defaultGetterReferenceIndex(value, annotationType.name())
        val memberName = defaultIndex
          .flatMap(index => memberNames.lift(index - 1))
          .orElse(positionalAnnotationMemberName(key, memberNames))
          .getOrElse(key)
        val memberValue = defaultIndex
          .map(_ => annotationType.members().get(memberName))
          .filter(_ != null)
          .map(_.defaultValue())
          .filter(_ != null)
          .getOrElse(value)
        normalized.put(memberName, memberValue)
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

  private def defaultGetterReferenceIndex(value: Object, annotationName: String): Option[Int] =
    value match
      case rendered: String =>
        val prefix = annotationName + ".$lessinit$greater$default$"
        if rendered.startsWith(prefix) then
          rendered.stripPrefix(prefix).toIntOption
        else
          None
      case _ =>
        None

  private def annotationValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Object | Null =
    arrayLiteralValues(tree)
      .orElse(classLiteralValue(tree))
      .orElse(nestedAnnotationValue(tree))
      .orElse(typedConstantValue(tree))
      .getOrElse {
        tree match
          case literal: tpd.Literal =>
            annotationConstantValue(literal.const.value).orNull
          case select: tpd.Select if isEnumConstant(select.symbol) =>
            select.name.toString
          case ident: tpd.Ident if ident.name.toString == "_" =>
            null
          case ident: tpd.Ident if isEnumConstant(ident.symbol) =>
            ident.name.toString
          case _ =>
            renderedClassLiteralValue(tree.show).map(name => classValueData(name)).getOrElse(tree.show)
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
      case value: String => Some(renderedClassLiteralValue(value).map(name => classValueData(name)).getOrElse(value))
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
    val normalized = values.map {
      case value: String => renderedClassLiteralValue(value).map(name => classValueData(name)).getOrElse(value)
      case value => value
    }
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
    typeApply.args.nonEmpty && typeApply.fun.symbol != Symbols.NoSymbol && typeApply.fun.symbol.showFullName == "scala.Predef.classOf"

  private def annotationValuesFromArguments(
      arguments: List[tpd.Tree],
      annotationType: ScalaAnnotationTypeData | Null
  )(using Context, AnnotationDefaults): JMap[String, Object] =
    normalizeAnnotationArgumentValues(annotationArgumentValues(arguments), annotationType)

  private def classLiteralValue(tree: tpd.Tree)(using Context, AnnotationDefaults): Option[ScalaClassValueData] =
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
        renderedClassLiteralValue(tree.show).map(name => classValueData(name))

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
    val erasedName = renderedClassLiteralValue(rawName).getOrElse(rawName)
    ScalaPrimitiveNames.getOrElse(erasedName, erasedName)

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

  private def renderedClassLiteralValue(rendered: String): Option[String] =
    val sanitized = rendered
      .replaceAll("\u001B\\[[;\\d]*m", "")
      .replaceAll("\\[[;\\d]*m", "")
    val start = sanitized.indexOf("classOf")
    val open = if start > -1 then sanitized.indexOf('[', start) else -1
    val end = if open > -1 then matchingBracketIndex(sanitized, open) else -1
    if start > -1 && open > start && end > open then
      val rawName = eraseRenderedTypeArguments(sanitized.substring(open + 1, end))
      val name = rawName
        .replace('/', '.')
        .replaceAll("[^A-Za-z0-9_.$]", "")
      Some(ScalaClassLiteralAliases.getOrElse(name, name))
    else
      None

  private def matchingBracketIndex(value: String, open: Int): Int =
    var depth = 0
    var index = open
    while index < value.length do
      value.charAt(index) match
        case '[' => depth += 1
        case ']' =>
          depth -= 1
          if depth == 0 then return index
        case _ =>
      index += 1
    -1

  private def eraseRenderedTypeArguments(value: String): String =
    val erased = StringBuilder()
    var depth = 0
    value.foreach {
      case '[' => depth += 1
      case ']' if depth > 0 => depth -= 1
      case character if depth == 0 => erased.append(character)
      case _ =>
    }
    erased.toString

  private def isEnumConstant(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (hasFlag(symbol, Flags.EnumValue) ||
        hasFlag(symbol, Flags.JavaEnumValue) ||
        hasFlag(symbol, Flags.EnumCase))

  private def modifiers(symbol: Symbol)(using Context): Set[ElementModifier] =
    val modifiers = LinkedHashSet[ElementModifier]()
    if hasFlag(symbol, Flags.Private) then modifiers.add(ElementModifier.PRIVATE)
    if hasFlag(symbol, Flags.Protected) then modifiers.add(ElementModifier.PROTECTED)
    if hasFlag(symbol, Flags.Deferred) || hasFlag(symbol, Flags.Abstract) then modifiers.add(ElementModifier.ABSTRACT)
    if hasFlag(symbol, Flags.Final) then modifiers.add(ElementModifier.FINAL)
    if hasFlag(symbol, Flags.JavaStatic) || hasFlag(symbol, Flags.Module) then modifiers.add(ElementModifier.STATIC)
    if !modifiers.contains(ElementModifier.PRIVATE) && !modifiers.contains(ElementModifier.PROTECTED) then modifiers.add(ElementModifier.PUBLIC)
    modifiers.asScala.toSet

  private def isAnnotationSymbol(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (symbol.denot.isAnnotation || hasFlag(symbol, Flags.JavaAnnotation))

  private def isInterfaceSymbol(symbol: Symbol)(using Context): Boolean =
    symbol != Symbols.NoSymbol &&
      (hasFlag(symbol, Flags.Trait) || (hasFlag(symbol, Flags.JavaDefined) && hasFlag(symbol, Flags.JavaInterface)))

  private def classSymbolForName(name: String)(using Context): Symbol =
    val symbol = Symbols.getClassIfDefined(name)
    if symbol != Symbols.NoSymbol then
      symbol
    else
      Symbols.getClassIfDefined(name.replace('$', '.'))

  private def skipClass(symbol: Symbol)(using Context): Boolean =
    symbol == Symbols.NoSymbol ||
      hasFlag(symbol, Flags.ModuleClass) ||
      hasFlag(symbol, Flags.PackageClass) ||
      hasFlag(symbol, Flags.Synthetic) ||
      hasFlag(symbol, Flags.Artifact)

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
