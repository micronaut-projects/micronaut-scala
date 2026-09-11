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
package io.micronaut.scala.processing.fixtures;

/**
 * A classpath supertype with a bean property, for checking that Core's configuration
 * metadata writer can annotate an accessor it inherits from outside the compilation.
 */
public class ExternalConfigBase {

    private String host = "";

    /** @return the host */
    public String getHost() {
        return host;
    }

    /** @param host the host */
    public void setHost(String host) {
        this.host = host;
    }
}
