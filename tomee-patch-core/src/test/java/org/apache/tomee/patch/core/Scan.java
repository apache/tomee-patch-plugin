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

import org.tomitribe.jkta.usage.Jar;
import org.tomitribe.jkta.usage.JarUsage;
import org.tomitribe.jkta.usage.Package;
import org.tomitribe.jkta.usage.Usage;
import org.tomitribe.util.Archive;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertEquals;

public class Scan {
    private Scan() {
    }

    public static Usage<Jar> usage(final Object o) {
        return usage(o.getClass());
    }

    public static Usage<Jar> usage(final Class<?> aClass) {
        try {
//            final ClassLoader loader = aClass.getClassLoader();
//            System.out.println(Asmifier.asmify(Bytecode.readClassFile(loader, aClass)));
//            System.out.println();
            final File jar = Archive.archive().add(aClass).toJar();
            return JarUsage.of(jar);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static void assertUsage(final Usage<Jar> usage, final Package... expectedUses) {
        int javax = 0;
        int jakarta = 0;
        int[] uses = new int[Package.values().length];

        for (final Package use : expectedUses) {
            uses[use.ordinal()]++;
            if (use.getName().startsWith("javax.")) javax++;
            if (use.getName().startsWith("jakarta.")) jakarta++;
        }

        for (int i = 0; i < uses.length; i++) {
            final Package pkg = Package.values()[i];
            assertEquals(pkg.getName(), uses[i], usage.get(pkg));
        }

        assertEquals(javax, usage.getJavax());
        assertEquals(jakarta, usage.getJakarta());
    }
}
