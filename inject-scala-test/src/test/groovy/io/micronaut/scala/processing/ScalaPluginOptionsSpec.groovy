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

import io.micronaut.scala.processing.MicronautScalaCompilerPlugin
import spock.lang.Specification

/**
 * The plugin parses options but declared no {@code optionsHelp}, so
 * {@code -P:micronaut-scala:help} printed nothing at all and there was no discoverable
 * record of which options mean anything.
 */
class ScalaPluginOptionsSpec extends Specification {

    void 'the plugin documents its options'() {
        given:
        def plugin = new MicronautScalaCompilerPlugin()

        when:
        def help = plugin.optionsHelp()

        then:
        help.isDefined()

        and: 'the syntax, and the keys a user is most likely to need'
        help.get().contains('-P:micronaut-scala:<key>=<value>')
        help.get().contains('micronaut.processing.incremental')
        help.get().contains('micronaut.processing.group')
        help.get().contains('micronaut.processing.module')
    }
}
