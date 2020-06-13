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
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

public class ClassTransformer extends ClassVisitor {

    private String className;

    public ClassTransformer(final ClassWriter classVisitor) {
        super(Opcodes.ASM8, classVisitor);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitAnnotation(descriptor, visible));
    }

    @Override
    public FieldVisitor visitField(final int access, final String name, final String descriptor, final String signature, Object value) {

        if (value instanceof String) {
            value = new Replace((String) value)
                    .replace("javax.faces", "jakarta.faces")
                    .replace("javax_faces", "jakarta_faces")
                    .replace("javax.persistence.", "jakarta.persistence.")
                    .get();
        }

        return new FieldTransformer(this.api, super.visitField(access, name, descriptor, signature, value));
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
        return new MethodTransformer(this.api, super.visitMethod(access, name, descriptor, signature, exceptions));
    }

    @Override
    public ModuleVisitor visitModule(final String name, final int access, final String version) {
        return new ModuleTransformer(this.api, super.visitModule(name, access, version));
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
    }


}
