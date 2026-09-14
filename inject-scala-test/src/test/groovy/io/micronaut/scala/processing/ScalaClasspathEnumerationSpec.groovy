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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * Classpath members were enumerated with {@code getMethods()}/{@code getFields()}, which
 * return only *public* members and additionally include {@code java.lang.Object}'s. Every
 * member was also told it was declared by the class being queried, whatever it actually
 * inherited it from.
 */
class ScalaClasspathEnumerationSpec extends AbstractScalaTypeElementSpec {

    private static final String FIXTURES = 'io.micronaut.scala.processing.fixtures.'

    private ClassElement child
    private Map<String, List> members = [:]

    private void inspect() {
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            child = context.getClassElement(FIXTURES + 'ExternalChild').orElse(null)
            if (child != null) {
                members.methods = child.getEnclosedElements(ElementQuery.ALL_METHODS)
                members.fields = child.getEnclosedElements(ElementQuery.ALL_FIELDS)
                members.declaredFields = child.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())
            }
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })
    }

    void 'non-public inherited methods are visible'() {
        given:
        inspect()

        expect:
        members.methods*.name.containsAll(['childMethod', 'basePublic', 'baseProtected', 'basePackagePrivate'])
    }

    void 'java.lang.Object methods are not reported'() {
        given:
        inspect()

        expect: 'the source path never produces them, so the two element kinds must not disagree'
        !members.methods*.name.any { it in ['equals', 'hashCode', 'toString', 'wait', 'notify', 'notifyAll', 'getClass'] }
    }

    void 'an inherited member reports the type that actually declares it'() {
        given:
        inspect()

        expect:
        members.methods.find { it.name == 'basePublic' }.declaringType.name == FIXTURES + 'ExternalBase'
        members.methods.find { it.name == 'childMethod' }.declaringType.name == FIXTURES + 'ExternalChild'
    }

    void 'private and protected fields are visible, which field injection needs'() {
        given:
        inspect()

        expect: 'getFields() returned only the public one, hiding even the class own private field'
        members.fields*.name.containsAll(['count', 'secret', 'shared', 'open'])

        and: 'onlyDeclared still means declared'
        members.declaredFields*.name == ['count']
    }
}
