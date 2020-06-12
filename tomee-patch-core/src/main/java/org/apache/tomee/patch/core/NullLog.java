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

public class NullLog implements Log {
    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public void debug(final CharSequence var1) {

    }

    @Override
    public void debug(final CharSequence var1, final Throwable var2) {

    }

    @Override
    public void debug(final Throwable var1) {

    }

    @Override
    public boolean isInfoEnabled() {
        return false;
    }

    @Override
    public void info(final CharSequence var1) {

    }

    @Override
    public void info(final CharSequence var1, final Throwable var2) {

    }

    @Override
    public void info(final Throwable var1) {

    }

    @Override
    public boolean isWarnEnabled() {
        return false;
    }

    @Override
    public void warn(final CharSequence var1) {

    }

    @Override
    public void warn(final CharSequence var1, final Throwable var2) {

    }

    @Override
    public void warn(final Throwable var1) {

    }

    @Override
    public boolean isErrorEnabled() {
        return false;
    }

    @Override
    public void error(final CharSequence var1) {

    }

    @Override
    public void error(final CharSequence var1, final Throwable var2) {

    }

    @Override
    public void error(final Throwable var1) {

    }
}
