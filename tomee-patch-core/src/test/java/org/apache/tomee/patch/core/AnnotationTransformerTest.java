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

import org.junit.Test;
import org.tomitribe.jkta.usage.Jar;
import org.tomitribe.jkta.usage.Package;
import org.tomitribe.jkta.usage.Usage;

import javax.ejb.EnterpriseBean;
import javax.ejb.LockType;
import javax.servlet.http.HttpServlet;
import javax.ws.rs.Path;

import static org.apache.tomee.patch.core.Scan.assertUsage;
import static org.apache.tomee.patch.core.Transform.usage;

public class AnnotationTransformerTest {
    // ------------------------------------------------------

    @Test
    public void visit_primitive() {
        final Usage<Jar> usage = usage(new Object() {
            @Data(length = 4)
            public void get() {
            }
        });

        assertUsage(usage);

    }

    // ------------------------------------------------------

    @Test
    public void visit_String() {
        final Usage<Jar> usage = usage(new Object() {
            @Data(name = "javax.ejb.EnterpriseBean")
            public void m1() {
            }

            @Data(name = "javax/ejb/EnterpriseBean")
            public void m2() {
            }

            @Data(name = "Ljavax/ejb/EnterpriseBean")
            public void m3() {
            }
        });

        assertUsage(usage);

    }

    // ------------------------------------------------------

    @Test
    public void visit_Class() {
        final Usage<Jar> usage = usage(new Object() {
            @Data(type = EnterpriseBean.class)
            public void get() {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitEnum() {
        final Usage<Jar> usage = usage(new Object() {
            @Data(lock = LockType.WRITE)
            public void get() {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);

    }

    // ------------------------------------------------------

    @Test
    public void visitAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            @Data(path = @Path("/foo"))
            public void get() {
            }
        });

        assertUsage(usage, Package.JAVAX_WS_RS);

    }
    // ------------------------------------------------------

    @Test
    public void visitAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            @ArrayData(data = @Data(path = @Path("/foo")))
            public void m() {
            }

            @ArrayData(data = @Data(type = HttpServlet.class))
            public void m2() {
            }
        });

        assertUsage(usage, Package.JAVAX_WS_RS, Package.JAVAX_SERVLET);

    }

    // ------------------------------------------------------

    @Test
    public void visitArray() {
        final Usage<Jar> usage = usage(new Object() {
            @ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)})
            public void m() {
            }
        });

        assertUsage(usage, Package.JAVAX_WS_RS, Package.JAVAX_SERVLET);
    }

}
