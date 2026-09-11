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

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Ported from the Kotlin module's {@code ClassElementSpec} -- "override and default
 * methods", "abstract and overridden methods" and "type isAssignable". Core decides whether
 * to proxy a method, whether it needs an implementation, and whether a bean satisfies an
 * injection point from exactly these answers.
 *
 * <p>A concrete trait method really is a Java default method: dotty 3.9.0 compiles
 * {@code trait Greeter { def greet(): String = ... }} to
 * {@code public default java.lang.String greet();} on the interface.</p>
 */
class ScalaOverrideParitySpec extends AbstractScalaTypeElementSpec {

    private Map methods(String className, String source) {
        buildClassElement(className, source)
                .getEnclosedElements(ElementQuery.ALL_METHODS)
                .collectEntries { [it.name, it] }
    }

    void 'a concrete trait method is default and not abstract'() {
        given:
        def m = methods('test.MyBean', '''
package test

trait Parent {
  def getParentName(): String = "parent"
  def getDescription(): String
}

trait MyBean extends Parent {
  def test(): Int
  def getName(): String = "my-bean"
  override def getDescription(): String = "description"
}
''')

        expect: 'the one with no body'
        m.test.isAbstract()
        !m.test.isDefault()

        and: 'the ones with a body, including the one that implements an inherited abstract'
        !m.getName.isAbstract()
        m.getName.isDefault()
        !m.getDescription.isAbstract()
        m.getDescription.isDefault()

        and: 'an inherited concrete method keeps its declaring trait'
        m.getParentName.declaringType.simpleName == 'Parent'
        m.getParentName.isDefault()
    }

    void 'an implementation of an abstract method is not abstract'() {
        given:
        def m = methods('test.Impl', '''
package test

abstract class Base {
  def abstractOne(): String
  def concrete(): String = "c"
}

class Impl extends Base {
  override def abstractOne(): String = "i"
}
''')

        expect:
        !m.abstractOne.isAbstract()
        m.abstractOne.declaringType.simpleName == 'Impl'

        and: 'and a class method is never a default method, whatever it overrides'
        !m.abstractOne.isDefault()
        !m.concrete.isDefault()
    }

    void 'assignability follows the hierarchy in one direction'() {
        given:
        def m = methods('test.Holder', '''
package test

trait Api
class Impl extends Api

class Holder {
  def impl(): Impl = null
  def api(): Api = null
  def str(): String = null
}
''')
        def types = m.collectEntries { name, method -> [name, method.getReturnType()] }

        expect:
        types.impl.isAssignable(types.api)
        types.impl.isAssignable('test.Api')

        and:
        !types.api.isAssignable(types.impl)

        and:
        types.str.isAssignable('java.lang.CharSequence')
        types.str.isAssignable('java.lang.Object')
    }

    void 'a scala Option is not a java Optional'() {
        given:
        def m = methods('test.Holder', '''
package test

class Holder {
  def opt(): Option[String] = None
}
''')

        expect: 'core keys optionality on java.util.Optional, which this is not'
        !m.opt.getReturnType().isOptional()
    }
}
