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

    protected Object getBean(ApplicationContext context, String className, Qualifier qualifier = null) {
        context.getBean(context.classLoader.loadClass(className), qualifier)
    }

    protected BeanDefinition<?> getBeanDefinition(ApplicationContext context, String className, Qualifier qualifier = null) {
        context.getBeanDefinition(context.classLoader.loadClass(className), qualifier)
    }
}
