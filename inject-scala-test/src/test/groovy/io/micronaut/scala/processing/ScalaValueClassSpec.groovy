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
 * A value class is unboxed by the JVM at the top level of a signature and stays boxed
 * everywhere it is nested inside another type. The model reported the value class
 * everywhere, so a bean's constructor argument and a method's return type named a class
 * the bytecode does not use.
 *
 * <p>The expected values are what {@code javap} reports for the same declarations compiled
 * by dotty 3.9.0.</p>
 */
class ScalaValueClassSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package probe

import jakarta.inject.Singleton

class UserId(val value: String) extends AnyVal
class Wrapped(val n: Int) extends AnyVal

@Singleton
class Holder(val id: UserId, val count: Wrapped) {
  def ret(): UserId = id
  def take(x: UserId): String = x.value
  def arr(): Array[UserId] = null
  def list(): java.util.List[UserId] = null
}
'''

    private Map methods() {
        buildClassElement('probe.Holder', SOURCE)
                .getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [it.name, it] }
    }

    void 'a value class return type is its underlying type'() {
        expect: 'javap: java.lang.String ret();'
        methods().ret.getReturnType().getName() == 'java.lang.String'
    }

    void 'a value class parameter is its underlying type'() {
        expect: 'javap: java.lang.String take(java.lang.String);'
        methods().take.getParameters()[0].getType().getName() == 'java.lang.String'
    }

    void 'a value class stays boxed as an array component'() {
        when: 'javap: probe.UserId[] arr();'
        def returnType = methods().arr.getReturnType()

        then:
        returnType.getName() == 'probe.UserId'
        returnType.getArrayDimensions() == 1
    }

    void 'a value class stays boxed as a type argument'() {
        when: 'javap: java.util.List<probe.UserId> list();'
        def returnType = methods().list.getReturnType()

        then:
        returnType.getName() == 'java.util.List'
        returnType.getFirstTypeArgument().get().getName() == 'probe.UserId'
    }

    void 'a constructor parameter is the underlying type, primitive included'() {
        when: 'javap: public probe.Holder(java.lang.String, int);'
        def parameters = buildClassElement('probe.Holder', SOURCE)
                .getPrimaryConstructor().get().getParameters()
                .collectEntries { [it.name, it.getType()] }

        then:
        parameters.id.getName() == 'java.lang.String'
        !parameters.id.isPrimitive()

        and: 'a value class over a primitive unboxes to that primitive'
        parameters.count.getName() == 'int'
        parameters.count.isPrimitive()
    }

    void 'a bean property is the underlying type'() {
        when: 'javap: private final java.lang.String id; private final int count;'
        def properties = buildClassElement('probe.Holder', SOURCE)
                .getBeanProperties()
                .collectEntries { [it.name, it.getType()] }

        then:
        properties.id.getName() == 'java.lang.String'
        properties.count.getName() == 'int'
    }
}
