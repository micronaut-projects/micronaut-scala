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
package io.micronaut.docs.documentation

import io.micronaut.context.annotation.ConfigurationProperties

// tag::clazz[]
/** Configures the outbound mailer.
  *
  * @param host the SMTP host to connect to
  * @param port the port the SMTP host listens on
  */
@ConfigurationProperties("mailer")
case class MailerConfig(host: String = "localhost", port: Int = 25)
// end::clazz[]
