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

import org.objectweb.asm.AnnotationVisitor;

public class AnnotationTransformer extends AnnotationVisitor {

    public AnnotationTransformer(final int api, final AnnotationVisitor annotationVisitor) {
        super(api, annotationVisitor);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String name, final String descriptor) {
        return new AnnotationTransformer(this.api, super.visitAnnotation(name, descriptor));
    }

    @Override
    public AnnotationVisitor visitArray(final String name) {
        return new AnnotationTransformer(this.api, super.visitArray(name));
    }

    @Override
    public void visit(final String name, final Object value) {
        if (!(value instanceof String)) {
            super.visit(name, value);
            return;
        }

        final String updated = new Replace((String) value)
                .prefix("{javax.validation.", "{jakarta.validation.")
                .prefix("javax.persistence.", "jakarta.persistence.")
                .prefix("javax.xml.ws.", "jakarta.xml.ws.")
                .get();

        super.visit(name, updated);
    }

}
