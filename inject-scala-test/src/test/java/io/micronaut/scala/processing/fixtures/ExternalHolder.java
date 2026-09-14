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
 * A classpath type with a JavaBean property and a nested class, for checking that a
 * classpath element answers property and nested-class queries the way a source element does.
 */
public class ExternalHolder {

    private String name = "";

    /** @return the property */
    public String getName() {
        return name;
    }

    /**
     * Sets the property.
     *
     * @param name the property
     */
    public void setName(String name) {
        this.name = name;
    }

    /** A nested class, which nested-class discovery has to find. */
    public static class Nested {
    }
}
