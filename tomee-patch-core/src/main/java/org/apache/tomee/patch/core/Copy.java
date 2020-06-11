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

import java.util.function.BiConsumer;
import java.util.function.Function;

public class Copy<T> {
    private final T from;
    private final T to;

    public Copy(final T from, final T to) {
        this.from = from;
        this.to = to;

    }

    public <Value> Copy<T> att(final Function<T, Value> getter, final BiConsumer<T, Value> setter) {
        final Value value = getter.apply(from);
        if (value != null) setter.accept(to, value);
        return this;
    }

    public static <T> Copy<T> copy(final T from, T to) {
        return new Copy<>(from, to);
    }
}
