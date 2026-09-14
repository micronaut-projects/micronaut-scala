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

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P2 parity, ported from {@code inject-java}'s {@code InjectFieldAbstractIntroductionSpec},
 * {@code IntroductionInnerInterfaceSpec} and {@code InterfaceIntroductionAdviceSpec}.
 *
 * <p>Introduction advice is covered here for the abstract/concrete split. These are the shapes
 * around it: an introduced abstract class that also has dependencies to inject, so the generated
 * proxy has to be a real bean and not only an interceptor holder; an introduced type nested
 * inside another, where the generated name has to survive nesting; and an introduced method
 * whose return type is generic, which the interceptor sees through the written metadata rather
 * than through the source.</p>
 */
class ScalaIntroductionShapeParitySpec extends AbstractScalaTypeElementSpec {

    private static final String STUB = '''
@Singleton
class StubIntroduction extends MethodInterceptor[AnyRef, Object]:
  override def intercept(context: MethodInvocationContext[AnyRef, Object]): Object =
    val returnType = context.getReturnType.getType
    if returnType == classOf[java.util.List[?]] then java.util.List.of("one", "two")
    else if returnType == classOf[String] then "introduced"
    else null

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation
'''

    private static String source(String body) {
        '''
package introshape

import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
''' + STUB + body
    }

    void "injects into an introduced abstract class alongside the methods it introduces"() {
        when: 'the abstract class both needs a dependency and has an abstract member to introduce'
        def context = buildContext(source('''
@Singleton
class Helper:
  def help(): String = "helped"

@Stub
abstract class Repository:
  @Inject
  var helper: Helper = null

  def find(): String

  def helped(): String = helper.help()
'''), [:], true)
        def repository = getBean(context, 'introshape.Repository')

        then: 'the abstract member is implemented by the interceptor'
        repository.find() == 'introduced'

        and: 'and the concrete one still runs, against a dependency that was injected'
        repository.helped() == 'helped'

        cleanup:
        context?.close()
    }

    void "introduces a trait nested inside another type"() {
        when:
        def context = buildContext(source('''
object Outer:
  @Stub
  trait Inner:
    def find(): String
'''), [:], true)
        def inner = getBean(context, 'introshape.Outer$Inner')

        then: 'nesting does not stop the proxy being generated or resolved'
        inner.find() == 'introduced'

        cleanup:
        context?.close()
    }

    void "introduces a method whose return type is generic"() {
        when:
        def context = buildContext(source('''
@Stub
trait Listing:
  def names(): java.util.List[String]
'''), [:], true)
        def listing = getBean(context, 'introshape.Listing')

        then: 'the interceptor sees the erased return type through the written metadata'
        listing.names().toList() == ['one', 'two']

        cleanup:
        context?.close()
    }
}
