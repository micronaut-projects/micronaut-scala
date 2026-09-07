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
package io.micronaut.scala.processing.test

import io.micronaut.context.ApplicationContext
import io.micronaut.context.Qualifier
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.BeanDefinitionReference
import io.micronaut.inject.ast.ClassElement
import spock.lang.Specification

import java.util.function.Consumer

/**
 * Base specification for inline Scala 3 compiler-plugin tests.
 */
abstract class AbstractScalaTypeElementSpec extends Specification {

    protected ClassLoader buildClassLoader(String className, String source) {
        ScalaCompiler.buildClassLoader(className, source)
    }

    protected ClassElement buildClassElement(String className, String source) {
        ScalaCompiler.buildClassElement(className, source)
    }

    protected ClassElement buildClassElement(String className, String source, List<String> compilerOptions) {
        ScalaCompiler.buildClassElement(className, source, compilerOptions)
    }

    protected boolean buildClassElement(String className, String source, Consumer<ClassElement> processor) {
        ClassElement element = ScalaCompiler.buildClassElement(className, source)
        if (element != null) {
            processor.accept(element)
        }
        return element != null
    }

    protected BeanDefinition<?> buildBeanDefinition(String className, String source) {
        ScalaCompiler.buildBeanDefinition(className, source)
    }

    protected BeanDefinition<?> buildBeanDefinition(String className, String source, List<String> compilerOptions) {
        ScalaCompiler.buildBeanDefinition(className, source, compilerOptions)
    }

    /**
     * Loads a generated bean definition whose class name does not follow the
     * {@code $<Class>$Definition} convention, such as an intercepted or factory-produced definition.
     */
    protected BeanDefinition<?> buildGeneratedBeanDefinition(String packageName, String generatedClassName, String source) {
        ScalaCompiler.buildBeanDefinition(packageName, generatedClassName, source)
    }

    /**
     * Compiles several sources in one compiler run. This is the only way to exercise separate
     * compilation, and the only way to compile Java and Scala sources jointly.
     */
    protected ClassElement buildClassElement(List<ScalaCompiler.SourceFile> sources, String className) {
        List<ClassElement> elements = []
        ScalaCompiler.compile(sources, List.of(), { ClassElement element -> elements.add(element) } as Consumer<ClassElement>)
        elements.find { it.name == className }
    }

    /**
     * Compiles several sources in one compiler run and returns a classloader over the output.
     */
    protected ClassLoader buildClassLoader(List<ScalaCompiler.SourceFile> sources) {
        ScalaCompiler.compile(sources, List.of(), { ClassElement element -> } as Consumer<ClassElement>).classLoader()
    }

    /**
     * Compiles the source and returns the compiler warnings, formatted as
     * {@code file:line:column: message} where a source position is available.
     */
    protected List<String> buildAndGetWarnings(String className, String source) {
        ScalaCompiler.buildAndGetWarnings(className, source)
    }

    protected BeanDefinitionReference<?> buildBeanDefinitionReference(String className, String source) {
        ScalaCompiler.buildBeanDefinitionReference(className, source)
    }

    protected BeanIntrospection<?> buildBeanIntrospection(String className, String source) {
        ScalaCompiler.buildBeanIntrospection(className, source)
    }

    protected BeanIntrospection<?> buildBeanIntrospection(String className, String source, List<String> compilerOptions) {
        ScalaCompiler.buildBeanIntrospection(className, source, compilerOptions)
    }

    protected ApplicationContext buildContext(String source, boolean includeAllBeans = false) {
        ScalaCompiler.buildContext(source, includeAllBeans)
    }

    protected ApplicationContext buildContext(String source, Map<String, Object> config, boolean includeAllBeans = false) {
        ScalaCompiler.buildContext(source, includeAllBeans, config)
    }

    protected ApplicationContext buildContext(String source, Map<String, Object> config, List<String> compilerOptions, boolean includeAllBeans = false) {
        ScalaCompiler.buildContext(source, includeAllBeans, config, compilerOptions)
    }

    protected Object getBean(ApplicationContext context, String className, Qualifier qualifier = null) {
        context.getBean(context.classLoader.loadClass(className), qualifier)
    }

    protected BeanDefinition<?> getBeanDefinition(ApplicationContext context, String className, Qualifier qualifier = null) {
        context.getBeanDefinition(context.classLoader.loadClass(className), qualifier)
    }
}
