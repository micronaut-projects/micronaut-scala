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
 * A classpath supertype with members of every visibility, for checking that classpath
 * enumeration finds the same members the source path would.
 */
public class ExternalBase {

    /** A private declared field. */
    private String secret = "";

    /** A protected field. */
    protected String shared = "";

    /** A public field. */
    public String open = "";

    /** @return a public method */
    public String basePublic() {
        return secret + shared + open;
    }

    /** @return a protected method */
    protected String baseProtected() {
        return "protected";
    }

    /** @return a package-private method */
    String basePackagePrivate() {
        return "package";
    }
}
