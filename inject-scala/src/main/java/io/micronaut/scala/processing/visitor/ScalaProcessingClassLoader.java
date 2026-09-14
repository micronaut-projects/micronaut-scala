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
package io.micronaut.scala.processing.visitor;

import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The classloader a compilation's visitors run in: the compiler's output directory and the
 * compilation classpath, searched before the plugin for everything a visitor does not have to
 * share with it.
 *
 * <p>Parent-first delegation is the wrong default here, because the parent chain does not end at
 * the plugin: the plugin's loader falls back to the loader that loaded the compiler, and under
 * Gradle that loader is built from the whole {@code scalaClasspath} -- not just the compiler but
 * scaladoc and everything scaladoc depends on, among them Jackson, jackson-dataformat-yaml,
 * snakeyaml-engine, flexmark and jsoup. Those are micronaut-openapi's dependencies too, so a
 * parent-first loader handed the OpenAPI visitor the compiler's copies -- Jackson 3.1.2 where
 * the project declared 3.1.5, jsoup 1.22.2 where it declared 1.23.2 -- and not one class from
 * the compilation classpath. That loader is also one Gradle caches and shares between every
 * compilation in a compiler daemon, and closes when it evicts it, so whether a class still
 * resolves through it depends on what earlier compilations loaded. The moment one class of a
 * package came from there and the next one did not, the package was split across two loaders,
 * which the JVM reports as {@code IllegalAccessError: class LoadSettings tried to access method
 * LoadSettingsBuilder.<init>()}: the package-private access the two classes are entitled to
 * only while they share a loader.</p>
 *
 * <p>So the compilation classpath is searched first, and the parent only for what a visitor must
 * share with the plugin: what the JDK supplies, the compiler's own classes (an element's native
 * type is a dotty symbol), the plugin's own package, and whatever the plugin jar bundles -- Micronaut,
 * whose Element API both sides implement, and the annotations it ships. Bundled is decided by
 * looking in the jar, not by package name: {@code io.micronaut.validation.visitor} is a Micronaut
 * package too, but it is a visitor on the compilation classpath, and the constraint validators it
 * finds by the {@code Class} of an annotation have to see the same {@code Digits} the compilation
 * classpath gave it, or it reports "Cannot find a constraint validator" for a constraint it has.
 * That is the model javac gives a visitor, whose processor path carries the visitor and its
 * dependencies together, and it makes the versions a project declares the versions its visitors
 * run with. Resources follow the same rule as classes, so a class and its class file are always
 * read from the same place.</p>
 */
@Internal
public final class ScalaProcessingClassLoader extends URLClassLoader {

    // The compiler's, and the plugin's own. The JDK is not a list of prefixes: `javax.inject`
    // and `com.sun.activation` are ordinary libraries a project declares, and a prefix wide
    // enough to cover `javax.lang.model` hands them to the compiler's copy as well. The
    // platform loader is asked instead, and answers for exactly what the JDK supplies.
    private static final String[] TOOLCHAIN = {
        "java.",
        "scala.",
        "dotty."
    };
    private static final String PLUGIN_PACKAGE = "io.micronaut.scala.processing.";
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    static {
        registerAsParallelCapable();
    }

    // The plugin jar on its own, consulted only to ask whether it bundles a class. The parent
    // cannot answer that: its own lookups fall through to the compiler's loader.
    private final URLClassLoader pluginContents;

    /**
     * @param classpath The output directory and the compilation classpath
     * @param parent The plugin's classloader
     * @param plugin The plugin jar, whose contents a visitor shares with the plugin
     */
    public ScalaProcessingClassLoader(URL[] classpath, ClassLoader parent, URL plugin) {
        super(classpath, parent);
        this.pluginContents = new URLClassLoader(new URL[] {plugin}, null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && !isSharedWithPlugin(name.replace('.', '/') + ".class")) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException notOnClasspath) {
                    // The parent's, if anyone has it.
                }
            }
            if (loaded == null) {
                loaded = super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        if (!isSharedWithPlugin(name)) {
            URL resource = findResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (isSharedWithPlugin(name)) {
            return super.getResources(name);
        }
        List<URL> resources = Collections.list(findResources(name));
        resources.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(resources);
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            pluginContents.close();
        }
    }

    /**
     * Whether a resource is one the visitor and the plugin must agree on, and so is the parent's
     * before it is the classpath's.
     *
     * @param resource The resource path; a class file's, for a class
     * @return Whether the parent is asked first
     */
    private boolean isSharedWithPlugin(String resource) {
        String name = resource.replace('/', '.');
        for (String prefix : TOOLCHAIN) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return name.startsWith(PLUGIN_PACKAGE)
            || PLATFORM.getResource(resource) != null
            || pluginContents.findResource(resource) != null;
    }
}
