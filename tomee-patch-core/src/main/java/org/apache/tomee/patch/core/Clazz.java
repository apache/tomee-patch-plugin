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

import java.io.File;
import java.util.Objects;

public class Clazz {
    private final String name;
    private final String prefix;
    private final File file;
    private int applied;

    public Clazz(final String name, final File file) {
        this.name = name;
        this.prefix = name.replaceAll("\\.class$", "");
        this.file = file;
    }

    public void applied() {
        this.applied++;
    }

    public boolean isApplied() {
        return applied > 0;
    }

    public int getApplied() {
        return applied;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getName() {
        return name;
    }

    public File getFile() {
        return file;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final Clazz clazz = (Clazz) o;
        return name.equals(clazz.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
