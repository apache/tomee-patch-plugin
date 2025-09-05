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

import org.apache.commons.compress.archivers.zip.UnixStat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class FileMode {

    /**
     * Java regex matched against the entry path inside the zip (forward slashes).
     * Examples:
     *  - ^bin/.*            (everything under bin/)
     *  - ^bin/[^/]+$        (direct children of bin/)
     *  - .*\.sh$            (all .sh files)
     */
    private String pattern;

    /**
     * File mode in octal string, e.g. "0755", "0644".
     * We'll OR in the appropriate UnixStat FILE_FLAG or DIR_FLAG automatically.
     */
    private String mode;

    public String getPattern() { return pattern; }
    public void setPattern(final String pattern) { this.pattern = pattern; }

    public String getMode() { return mode; }
    public void setMode(final String mode) { this.mode = mode; }


    public static class ModeOverride {
        final Pattern pattern;
        final int mode; // already parsed octal, e.g. 0755
        ModeOverride(Pattern p, int m) { this.pattern = p; this.mode = m; }
    }

    public static List<ModeOverride> compileModeOverrides(final List<FileMode> rules) {
        final List<ModeOverride> list = new ArrayList<>();
        if (rules != null) {
            for (final FileMode r : rules) {
                if (r == null || r.getPattern() == null || r.getMode() == null) continue;
                final Pattern p = Pattern.compile(r.getPattern());
                final int mode = parseOctal(r.getMode().trim());
                list.add(new ModeOverride(p, mode));
            }
        }
        return list;
    }

    private static int parseOctal(final String s) {
        // accept "755" or "0755"
        return Integer.parseInt(s.startsWith("0") ? s.substring(1) : s, 8);
    }

    /** returns null if no override matches */
    public static Integer overrideModeFor(final String path, final boolean directory, List<ModeOverride> modeOverrides) {
        if (modeOverrides == null || modeOverrides.isEmpty()) return null;
        for (final ModeOverride o : modeOverrides) {
            if (o.pattern.matcher(path).matches()) {
                // Apply the right file/dir flag; ignore any FILE_FLAG/DIR_FLAG the user might imply
                return (directory ? UnixStat.DIR_FLAG : UnixStat.FILE_FLAG) | o.mode;
            }
        }
        return null;
    }

    public static int normalizeDirMode(final int old) {
        final int mode = (old != 0 ? old : UnixStat.DIR_FLAG | 0755);
        return (mode & ~UnixStat.FILE_FLAG) | UnixStat.DIR_FLAG;
    }
}
