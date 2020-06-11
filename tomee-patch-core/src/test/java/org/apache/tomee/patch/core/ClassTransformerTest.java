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

import org.junit.Ignore;
import org.junit.Test;
import org.tomitribe.jkta.usage.Jar;
import org.tomitribe.jkta.usage.Package;
import org.tomitribe.jkta.usage.Usage;

import javax.ejb.EJBException;
import javax.ejb.EnterpriseBean;
import javax.ejb.SessionBean;
import javax.enterprise.context.MockScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.inject.spi.Bean;
import javax.persistence.Persistence;
import javax.servlet.http.HttpServlet;
import javax.ws.rs.Path;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.util.Set;

import static org.apache.tomee.patch.core.Scan.assertUsage;
import static org.apache.tomee.patch.core.Transform.usage;

public class ClassTransformerTest {
    // ------------------------------------------------------

    @Test
    public void visit_Negative() {
        final Usage<Jar> usage = usage(VisitNegative.class);

        assertUsage(usage);
    }

    public static class Parent {
    }

    public static interface Contract {
    }

    public static class VisitNegative extends Parent implements Serializable, Contract {
    }

    // ------------------------------------------------------

    @Test
    public void visit_SuperClass() {
        final Usage<Jar> usage = usage(HasSuper.class);

        assertUsage(usage, Package.JAVAX_SERVLET, Package.JAVAX_SERVLET);

    }

    public static class HasSuper extends HttpServlet implements Serializable, Contract {
    }

    // ------------------------------------------------------

    @Test
    public void visit_Interfaces() {
        final Usage<Jar> usage = usage(HasInterface.class);

        assertUsage(usage, Package.JAVAX_EJB);

    }

    public static class HasInterface implements Serializable, EnterpriseBean, Contract {
    }

    // ------------------------------------------------------

    @Test
    public void visit_Signature() {
        final Usage<Jar> usage = usage(HasSignature.class);

        assertUsage(usage, Package.JAVAX_EJB);

    }

    public static class HasSignature<T extends SessionBean> {
    }

    // ------------------------------------------------------

    @Test
    public void visit_Signature2() {
        final Usage<Jar> usage = usage(HasSignature2.class);

        assertUsage(usage, Package.JAVAX_EJB);

    }

    public static interface HasSignature2 extends Set<SessionBean> {
    }

    // ------------------------------------------------------

    @Test
    public void visit_All() {
        final Usage<Jar> usage = usage(HasAll.class);

        assertUsage(usage,
                Package.JAVAX_EJB,
                Package.JAVAX_SERVLET,
                Package.JAVAX_SERVLET,
                Package.JAVAX_PERSISTENCE
        );

    }

    public static interface Generic<S> {
    }

    public static class HasAll extends HttpServlet implements Serializable, EnterpriseBean, Generic<Persistence> {
    }

    // ------------------------------------------------------

    @Test
    public void visitAnnotation() {
        final Usage<Jar> usage = usage(HasAnnotation.class);

        assertUsage(usage, Package.JAVAX_ENTERPRISE);
    }

    @RequestScoped
    public static class HasAnnotation {
    }

    // ------------------------------------------------------

    @Test
    public void visitAnnotation_Deep() {
        final Usage<Jar> usage = usage(HasAnnotationData.class);

        assertUsage(usage, Package.JAVAX_WS_RS, Package.JAVAX_SERVLET);
    }

    @ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)})
    public static class HasAnnotationData {
    }

    // ------------------------------------------------------

    @Test
    public void visitTypeAnnotation() {
        final Usage<Jar> usage = usage(HasTypeAnnotation.class);

        assertUsage(usage, Package.JAVAX_ENTERPRISE);
    }

    public static class HasTypeAnnotation implements Generic<@MockScoped Reference> {
    }

    // ------------------------------------------------------

    @Ignore
    @Test
    public void visitTypeAnnotation_Deep_PossibleAsmBug() {
        final Usage<Jar> usage = usage(HasTypeAnnotationDeep.class);

        assertUsage(usage, Package.JAVAX_WS_RS, Package.JAVAX_SERVLET);
    }

    public static class HasTypeAnnotationDeep implements Generic<@ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) Reference> {
    }

    // ------------------------------------------------------

    @Test
    public void visitField() {
        final Usage<Jar> usage = usage(new Object() {
            SessionBean sb;
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitField_Signature1() {
        final Usage<Jar> usage = usage(new Object() {
            final Reference<SessionBean> sb = null;
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    /**
     * The "Bean" type will show up both in ASM's field descriptor
     * and in the generic signature string.  We should only count
     * one of them.
     */
    @Test
    public void visitField_SignatureNoDuplicate() {
        final Usage<Jar> usage = usage(new Object() {
            Bean<SessionBean> bean;
        });

        assertUsage(usage, Package.JAVAX_EJB, Package.JAVAX_ENTERPRISE);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethod_Return() {
        final Usage<Jar> usage = usage(new Object() {
            public SessionBean get() {
                return null;
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethod_ReturnGeneric() {
        final Usage<Jar> usage = usage(new Object() {
            public Generic<SessionBean> get() {
                return null;
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethod_Parameter() {
        final Usage<Jar> usage = usage(new Object() {
            public void get(SessionBean sb) {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethod_ParameterGeneric() {
        final Usage<Jar> usage = usage(new Object() {
            public void get(Object o, Generic<SessionBean> sb, int i) {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethod_Throws() {
        final Usage<Jar> usage = usage(new Object() {
            public void get(Serializable s) throws EJBException, IOException {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

}
