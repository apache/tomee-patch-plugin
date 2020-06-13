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
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

public class MethodTransformer extends MethodVisitor {

    public MethodTransformer(final int api, final MethodVisitor methodVisitor) {
        super(api, methodVisitor);
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        return new AnnotationTransformer(this.api, super.visitAnnotationDefault());
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitAnnotation(descriptor, visible));
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(final int parameter, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitParameterAnnotation(parameter, descriptor, visible));
    }

    @Override
    public void visitFrame(final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {
        switch (type) {
            case -1:
            case 0:
//                add(bytecodeUsage, local);
//                add(bytecodeUsage, stack);
                break;
            case 1:
//                add(bytecodeUsage, local);
                break;
            case 2:
                break;
            case 3:
                break;
            case 4:
//                add(bytecodeUsage, stack);
                break;
            default:
                throw new IllegalArgumentException();
        }
        super.visitFrame(type, numLocal, local, numStack, stack);
    }

    @Override
    public void visitLdcInsn(Object cst) {
        if (cst instanceof Integer) {
            // ...
        } else if (cst instanceof Float) {
            // ...
        } else if (cst instanceof Long) {
            // ...
        } else if (cst instanceof Double) {
            // ...
        } else if (cst instanceof String) {
            cst = new Replace((String) cst)
                    .replace("javax.faces", "jakarta.faces")
                    .replace("javax_faces", "jakarta_faces")
                    .replace("javax.persistence.", "jakarta.persistence.")
                    .replace("javax.transaction.TransactionManager", "jakarta.transaction.TransactionManager")
                    .replace("javax.transaction.global.timeout", "jakarta.transaction.global.timeout")
                    .replace("javax.xml.ws.", "jakarta.xml.ws.")
                    .replace("Ljavax/persistence", "Ljakarta/persistence")
                    .get();

        } else if (cst instanceof Type) {
            // ...
        } else if (cst instanceof Handle) {
            // ...
        } else if (cst instanceof ConstantDynamic) {
            // ...
        } else {
            // throw an exception
        }
        super.visitLdcInsn(cst);
    }

    @Override
    public AnnotationVisitor visitInsnAnnotation(final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitInsnAnnotation(typeRef, typePath, descriptor, visible));
    }

    @Override
    public AnnotationVisitor visitTryCatchAnnotation(final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible));
    }

    @Override
    public AnnotationVisitor visitLocalVariableAnnotation(final int typeRef, final TypePath typePath, final Label[] start, final Label[] end,
                                                          final int[] index, final String descriptor, final boolean visible) {
        return new AnnotationTransformer(this.api, super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible));
    }


}
