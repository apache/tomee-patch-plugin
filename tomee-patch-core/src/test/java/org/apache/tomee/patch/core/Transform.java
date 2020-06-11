/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.patch.core;

import org.junit.Assert;
import org.tomitribe.jkta.usage.Jar;
import org.tomitribe.jkta.usage.JarUsage;
import org.tomitribe.jkta.usage.Usage;
import org.tomitribe.util.Archive;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

public class Transform {
    private Transform() {
    }

    public static Usage<Jar> usage(final Object o) {
        return usage(o.getClass());
    }

    public static Usage<Jar> usage(final Class<?> aClass) {
        try {
            final File jar = new TestArchive().add(aClass).toJar();

            final Usage<Jar> usageBefore = JarUsage.of(jar);

            final File transformed = Transformation.transform(jar);

            final String contentsBefore = Asmifier.asmifyJar(jar);
            final String contentsAfter = Asmifier.asmifyJar(transformed);

            Assert.assertEquals(contentsBefore, contentsAfter);

            return JarUsage.of(transformed);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static class TestArchive extends Archive {
        public Archive add(final Class<?> clazz) {
            try {
                final String name = clazz.getName().replace('.', '/') + ".class";

                final URL resource = this.getClass().getClassLoader().getResource(name);
                if (resource == null) throw new IllegalStateException("Cannot find class file for " + clazz.getName());
                add(name, resource);

                // Add any anonymous nested classes
                Stream.of(clazz.getDeclaredClasses())
                        .filter(Class::isAnonymousClass)
                        .forEach(this::add);

                return this;
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
