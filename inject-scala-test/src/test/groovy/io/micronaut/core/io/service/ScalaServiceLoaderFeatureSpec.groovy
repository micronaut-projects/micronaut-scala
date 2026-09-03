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
package io.micronaut.core.io.service

import io.micronaut.core.expressions.EvaluatedExpression
import io.micronaut.core.graal.GraalReflectionConfigurer
import io.micronaut.inject.BeanDefinitionReference
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import org.graalvm.nativeimage.hosted.Feature
import org.jspecify.annotations.Nullable

import java.lang.reflect.Field
import java.lang.reflect.Method

class ScalaServiceLoaderFeatureSpec extends AbstractScalaTypeElementSpec {

    void "registers Scala evaluated expressions for build time initialization"() {
        when:
        def definition = buildBeanDefinition('test.Test', '''
package test

import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Requires
import io.micronaut.context.condition.Condition
import io.micronaut.context.condition.ConditionContext
import jakarta.inject.Singleton

@Singleton
@Requires(value = "#{ '123' + 'abc' }")
@Requires(condition = classOf[CustomCondition])
class Test:
  @Executable
  @Requires(value = "#{ '456' + 'abc' }")
  def test(first: String, second: String): Unit = ()

class CustomCondition extends Condition:
  override def matches(context: ConditionContext[?]): Boolean = false
''')

        then:
        definition != null
        definition.getRequiredMethod('test', String, String).annotationMetadata.stringValue(io.micronaut.context.annotation.Requires).get() == "#{ '456' + 'abc' }"

        when:
        Set<Class<?>> buildTimeInitialized = []
        ServiceLoaderFeature serviceLoaderFeature = new ServiceLoaderFeature() {
            @Override
            protected void initializeAtBuildTime(@Nullable Class<?> buildInitClass) {
                buildTimeInitialized.add(buildInitClass)
            }

            @Override
            protected void addImageSingleton(ServiceScanner.ExclusiveStaticServiceDefinitions staticServiceDefinitions) {
                // no-op
            }

            @Override
            protected void registerForReflectiveInstantiation(Class<?> c) {
                // no-op
            }

            @Override
            protected void registerRuntimeReflection(Class<?> c) {
                // no-op
            }

            @Override
            protected void registerRuntimeReflection(Method... methods) {
                // no-op
            }

            @Override
            protected void registerRuntimeReflection(Field... fields) {
                // no-op
            }

            @Override
            protected Collection<GraalReflectionConfigurer> loadReflectionConfigurers(Feature.BeforeAnalysisAccess access) {
                return []
            }

            @Override
            protected ServiceScanner.ExclusiveStaticServiceDefinitions buildStaticServiceDefinitions(Feature.BeforeAnalysisAccess access) {
                return new ServiceScanner.ExclusiveStaticServiceDefinitions((BeanDefinitionReference.name): [definition.getClass().name] as Set)
            }
        }

        def mockAccess = Mock(Feature.BeforeAnalysisAccess)
        mockAccess.findClassByName(_) >> { String typeName -> definition.getClass().classLoader.loadClass(typeName) }
        serviceLoaderFeature.beforeAnalysis(mockAccess)

        then:
        !buildTimeInitialized.isEmpty()
        buildTimeInitialized.size() == 5
        buildTimeInitialized.any { EvaluatedExpression.isAssignableFrom(it) }
    }
}
