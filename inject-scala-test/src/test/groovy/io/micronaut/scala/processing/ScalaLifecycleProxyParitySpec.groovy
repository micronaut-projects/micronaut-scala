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

import io.micronaut.aop.InterceptedProxy
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P2 parity, ported from {@code inject-java}'s {@code LifeCycleWithProxySpec} and
 * {@code LifeCycleWithProxyTargetSpec}.
 *
 * <p>Two questions these ask that nothing here asked. First, <em>which object</em> runs the
 * hooks: under proxy-target advice the proxy delegates and only the target is initialised,
 * while under a proxy-subclass the proxy <em>is</em> the bean and runs them itself. Asserting
 * only that the target's counter reached 1, as {@code ScalaMicronautFeatureSpec} does, passes
 * equally if the proxy ran the hook a second time on itself.</p>
 *
 * <p>Second, whether hook collection survives declaration order. The plugin walks a class body
 * in source order, and method-level advice splits that walk in two — the advised method is
 * rewritten into the proxy while the hooks stay behind. Java cannot express the difference
 * because its sources are ordered by the same visitor; Scala can, so both orders are pinned.</p>
 */
class ScalaLifecycleProxyParitySpec extends AbstractScalaTypeElementSpec {

    private static final String PROXY_TARGET_ADVICE = '''
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around(proxyTarget = true)
class Mutating extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Mutating]

@Singleton
@InterceptorBean(Array(classOf[Mutating]))
class MutatingInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.proceed()
'''

    private static final String SUBCLASS_ADVICE = '''
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class Mutating extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Mutating]

@Singleton
@InterceptorBean(Array(classOf[Mutating]))
class MutatingInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.proceed()
'''

    private static String source(String advice, String bean) {
        '''
package lifecycleproxy

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.core.convert.ConversionService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
''' + advice + bean
    }

    void "runs lifecycle hooks on the proxy itself when the advice is a proxy subclass"() {
        when: 'class-level advice without proxyTarget, so the proxy extends the bean'
        def context = buildContext(source(SUBCLASS_ADVICE, '''
@Singleton
@Mutating
class MyBean:
  @Inject var conversionService: ConversionService = null
  var count: Int = 0

  def someMethod(): String = "good"

  @PostConstruct
  def created(): Unit = count = count + 1

  @PreDestroy
  def destroyed(): Unit = count = count - 1
'''))
        def definition = getBeanDefinition(context, 'lifecycleproxy.MyBean')
        def instance = getBean(context, 'lifecycleproxy.MyBean')

        then: 'the definition carrying the hooks is the one the context resolves'
        !definition.isAbstract()
        definition.postConstructMethods.size() == 1
        definition.preDestroyMethods.size() == 1

        and: 'a subclass proxy is the bean, so its own counter moved'
        !(instance instanceof InterceptedProxy)
        instance.conversionService() != null
        instance.someMethod() == 'good'
        instance.count() == 1

        cleanup:
        context?.close()
    }

    void "runs lifecycle hooks only on the target when the advice proxies a target"() {
        when: 'the same bean under proxyTarget advice, counting hook runs outside the bean'
        def context = buildContext(source(PROXY_TARGET_ADVICE, '''
object Hooks:
  var created: Int = 0
  var destroyed: Int = 0

@Singleton
@Mutating
class MyBean:
  @Inject var conversionService: ConversionService = null
  var count: Int = 0

  def someMethod(): String = "good"

  @PostConstruct
  def created(): Unit =
    count = count + 1
    Hooks.created = Hooks.created + 1

  @PreDestroy
  def destroyed(): Unit =
    count = count - 1
    Hooks.destroyed = Hooks.destroyed + 1
'''))
        def definition = getBeanDefinition(context, 'lifecycleproxy.MyBean')
        def instance = getBean(context, 'lifecycleproxy.MyBean')
        def hooks = context.classLoader.loadClass('lifecycleproxy.Hooks$').getField('MODULE$').get(null)

        then:
        definition.postConstructMethods.size() == 1
        definition.preDestroyMethods.size() == 1

        and: 'the proxy is a distinct object that delegates'
        instance instanceof InterceptedProxy
        instance.someMethod() == 'good'
        !instance.is(instance.interceptedTarget())

        and: 'exactly one object was initialised -- the target, not the proxy as well'
        hooks.created() == 1
        instance.interceptedTarget().count() == 1

        and: '''reading the counter through the proxy also reports 1, where the Java spec
             reads 0. Java's `instance.count` is a field read that lands on the proxy's own
             uninitialised copy; Scala has no public field, only an accessor, and the accessor
             is intercepted and delegated to the target. The hook still ran once, on the target
             alone -- which is what the Java assertion pair exists to establish.'''
        instance.count() == 1

        when:
        context.close()

        then: 'and the destroy hook likewise runs once, on the target'
        hooks.destroyed() == 1
    }

    void "collects lifecycle hooks declared after the advised method"() {
        when: 'method-level advice, hooks last'
        def context = buildContext(source(SUBCLASS_ADVICE, '''
@Singleton
class MyBean:
  @Inject var conversionService: ConversionService = null
  var count: Int = 0

  @Mutating
  def someMethod(): String = "good"

  @PostConstruct
  def created(): Unit = count = count + 1

  @PreDestroy
  def destroyed(): Unit = count = count - 1
'''))
        def definition = getBeanDefinition(context, 'lifecycleproxy.MyBean')
        def instance = getBean(context, 'lifecycleproxy.MyBean')

        then:
        !definition.isAbstract()
        definition.postConstructMethods.size() == 1
        definition.preDestroyMethods.size() == 1

        and:
        instance.conversionService() != null
        instance.someMethod() == 'good'
        instance.count() == 1

        cleanup:
        context?.close()
    }

    void "collects lifecycle hooks declared before the advised method"() {
        when: 'the same members, reordered so the hooks precede the advised method'
        def context = buildContext(source(SUBCLASS_ADVICE, '''
@Singleton
class MyBean:
  @Inject var conversionService: ConversionService = null
  var count: Int = 0

  @PostConstruct
  def created(): Unit = count = count + 1

  @PreDestroy
  def destroyed(): Unit = count = count - 1

  @Mutating
  def someMethod(): String = "good"
'''))
        def definition = getBeanDefinition(context, 'lifecycleproxy.MyBean')
        def instance = getBean(context, 'lifecycleproxy.MyBean')

        then:
        !definition.isAbstract()
        definition.postConstructMethods.size() == 1
        definition.preDestroyMethods.size() == 1

        and:
        instance.conversionService() != null
        instance.someMethod() == 'good'
        instance.count() == 1

        cleanup:
        context?.close()
    }
}
