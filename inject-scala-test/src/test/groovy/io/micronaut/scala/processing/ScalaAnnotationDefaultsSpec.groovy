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
import io.micronaut.scala.processing.test.ScalaCompiler

/**
 * Annotation member defaults were harvested from the trees of one compilation unit, so an
 * annotation's defaults depended on which file it happened to be declared in.
 */
class ScalaAnnotationDefaultsSpec extends AbstractScalaTypeElementSpec {

    void 'defaults are read from an annotation declared in the same file'() {
        given:
        def element = buildClassElement('probe.Target', '''
package probe

import scala.annotation.StaticAnnotation

class MyAnn(val value: String = "from-same-file", val enabled: Boolean = true) extends StaticAnnotation

@MyAnn
class Target
''')

        expect:
        def annotation = element.getAnnotation('probe.MyAnn')
        annotation.stringValue().get() == 'from-same-file'
        annotation.booleanValue('enabled').get()
    }

    void 'defaults are read from an annotation declared in another file of the same compilation'() {
        given:
        def element = buildClassElement([
                ScalaCompiler.SourceFile.scala('probe.MyAnn', '''
package probe

import scala.annotation.StaticAnnotation

class MyAnn(val value: String = "from-other-file", val enabled: Boolean = true) extends StaticAnnotation
'''),
                ScalaCompiler.SourceFile.scala('probe.Target', '''
package probe

@MyAnn
class Target
''')
        ], 'probe.Target')

        expect: 'which file the annotation is declared in must not change its defaults'
        def annotation = element.getAnnotation('probe.MyAnn')
        annotation.stringValue().get() == 'from-other-file'
        annotation.booleanValue('enabled').get()
    }
}
