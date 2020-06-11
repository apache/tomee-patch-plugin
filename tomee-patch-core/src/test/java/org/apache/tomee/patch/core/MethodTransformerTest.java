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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.tomitribe.jkta.usage.BytecodeUsage;
import org.tomitribe.jkta.usage.Jar;
import org.tomitribe.jkta.usage.MethodScanner;
import org.tomitribe.jkta.usage.Package;
import org.tomitribe.jkta.usage.Usage;

import javax.ejb.EnterpriseBean;
import javax.ejb.Process;
import javax.ejb.Schedule;
import javax.ejb.ScheduleExpression;
import javax.ejb.SessionBean;
import javax.enterprise.context.MockScoped;
import javax.jms.EnterpriseBeanConsumer;
import javax.persistence.EntityBean;
import javax.persistence.Persist;
import javax.persistence.PersistenceException;
import javax.servlet.http.HttpServlet;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Cookie;
import java.util.ArrayList;
import java.util.List;

import static org.apache.tomee.patch.core.Scan.assertUsage;
import static org.apache.tomee.patch.core.Transform.usage;

public class MethodTransformerTest {

    // ------------------------------------------------------

    @Test
    public void visitAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            @Schedule
            public void get() {
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            @ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)})
            public void get() {
            }
        });

        assertUsage(usage, Package.JAVAX_SERVLET, Package.JAVAX_WS_RS);
    }

    // ------------------------------------------------------

    @Test
    public void visitTypeAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            public <@MockScoped V> V get() {
                return null;
            }
        });

        assertUsage(usage, Package.JAVAX_ENTERPRISE);
    }

    // ------------------------------------------------------

    @Test
    public void visitTypeAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            public <@ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) V> V get() {
                return null;
            }
        });

        assertUsage(usage, Package.JAVAX_SERVLET, Package.JAVAX_WS_RS);
    }

    // ------------------------------------------------------

    @Test
    public void visitParameterAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(@PathParam("id") int id) {
            }
        });

        assertUsage(usage, Package.JAVAX_WS_RS);
    }

    // ------------------------------------------------------

    @Ignore
    @Test
    public void visitParameterAnnotation_Deep_PotentialAsmBug() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(@ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) int id) {
            }
        });

        assertUsage(usage, Package.JAVAX_WS_RS, Package.JAVAX_SERVLET);
    }

    // ------------------------------------------------------

    @Test
    public void visitParameterAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(@Data(type = HttpServlet.class) int id) {
            }
        });

        assertUsage(usage, Package.JAVAX_SERVLET);
    }

    // ------------------------------------------------------

    @Test
    public void visitFrame() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(Object o) {
                EnterpriseBean bean = (SessionBean) o;
                try {
                    m(bean);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        assertUsage(usage, Package.JAVAX_EJB, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitTypeInsn() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(Object o) {
                final Object bean = (SessionBean) o;
                System.out.println(bean);
            }
        });

        assertUsage(usage, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitFieldInsn() {
        final Usage<Jar> usage = usage(new Object() {
            final SessionBean bean = null;
        });

        assertUsage(usage, Package.JAVAX_EJB, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethodInsn() {
        final Usage<Jar> usage = usage(new Object() {

            public void m() {
                new ScheduleExpression();
            }
        });

        assertUsage(usage, Package.JAVAX_EJB, Package.JAVAX_EJB);
    }

    // ------------------------------------------------------

    @Test
    public void visitMethodInsn_Descriptor() {
        final Usage<Jar> usage = usage(new Object() {
            public void m(final EnterpriseBeanConsumer consumer, final EntityBean bean) {
                consumer.accept(bean);
            }
        });

        assertUsage(usage, Package.JAVAX_JMS, Package.JAVAX_JMS, Package.JAVAX_EJB, Package.JAVAX_PERSISTENCE);
    }

    // ------------------------------------------------------

    @Test
    public void visitInvokeDynamicInsn_Direct() {
        final Usage usage = new Usage();
        final MethodScanner methodScanner = new MethodScanner(Opcodes.ASM8, new BytecodeUsage(usage, Opcodes.ASM8));
        methodScanner.visitInvokeDynamicInsn(
                "accept",
                "(Ljavax/ejb/Process;)Ljavax/jms/Consumer;",
                new Handle(Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false),
                new Object[]{
                        Type.getType("(Ljava/lang/Object;)V"),
                        new Handle(Opcodes.H_INVOKEVIRTUAL,
                                "javax/ejb/Process",
                                "process",
                                "(Ljavax/ejb/EnterpriseBean;)V",
                                false),
                        Type.getType("(Ljavax/ejb/EnterpriseBean;)V")
                }
        );

        assertUsage(usage,
                Package.JAVAX_EJB,
                Package.JAVAX_EJB,
                Package.JAVAX_EJB,
                Package.JAVAX_EJB,
                Package.JAVAX_JMS
        );

    }

    @Test
    public void visitInvokeDynamicInsn() {
        final Usage<Jar> usage = usage(new Object() {
            public void invokedynamic(Persist<EnterpriseBean> persist, Process process) {
                persist.forEach(process::process);
            }
        });

        assertUsage(usage,
                Package.JAVAX_PERSISTENCE, // visitMethod
                Package.JAVAX_EJB, // visitMethod
                Package.JAVAX_EJB, // visitMethod
                Package.JAVAX_EJB, // visitInvokeDynamicInsn
                Package.JAVAX_EJB, // visitInvokeDynamicInsn
                Package.JAVAX_EJB, // visitInvokeDynamicInsn
                Package.JAVAX_EJB, // visitInvokeDynamicInsn
                Package.JAVAX_JMS, // visitInvokeDynamicInsn
                Package.JAVAX_PERSISTENCE, // visitMethodInsn
                Package.JAVAX_JMS // visitMethodInsn
        );

    }

    // ------------------------------------------------------

    @Test
    public void visitLdcInsn_Type() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                System.out.println(GET.class);
            }
        });

        assertUsage(usage,
                Package.JAVAX_WS_RS
        );
    }

    // ------------------------------------------------------

    /**
     * TODO This is a stub.  Real test needed.
     */
    @Ignore("https://github.com/tomitribe/jkta/issues/2")
    @Test
    public void visitLdcInsn_Handle() {
    }

    // ------------------------------------------------------


    /**
     * TODO This is a stub.  Real test needed.
     */
    @Ignore("https://github.com/tomitribe/jkta/issues/3")
    @Test
    public void visitLdcInsn_ConstantDynamic() {
    }

    // ------------------------------------------------------

    @Test
    public void visitMultiANewArrayInsn() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                final Cookie[][][] cookies = new Cookie[4][8][16];
            }
        });

        assertUsage(usage,
                Package.JAVAX_WS_RS
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitInsnAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                final List list = (@MockScoped ArrayList) null;
            }
        });

        assertUsage(usage,
                Package.JAVAX_ENTERPRISE
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitInsnAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                final List list = (@ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) ArrayList) null;
            }
        });

        assertUsage(usage,
                Package.JAVAX_SERVLET,
                Package.JAVAX_WS_RS
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitTryCatchBlock() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                try {
                    System.out.println();
                } catch (PersistenceException e) {
                    System.out.println();
                }
            }
        });

        assertUsage(usage,
                Package.JAVAX_PERSISTENCE, // visitTryCatchBlock
                Package.JAVAX_PERSISTENCE // visitFrame
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitTryCatchAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                try {
                    System.out.println();
                } catch (@MockScoped PersistenceException e) {
                    System.out.println();
                }
            }
        });

        assertUsage(usage,
                Package.JAVAX_ENTERPRISE, // visitTryCatchBlock
                Package.JAVAX_PERSISTENCE, // visitTryCatchBlock
                Package.JAVAX_PERSISTENCE // visitFrame
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitTryCatchAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                try {
                    System.out.println();
                } catch (@ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) PersistenceException e) {
                    System.out.println();
                }
            }
        });

        assertUsage(usage,
                Package.JAVAX_SERVLET,
                Package.JAVAX_WS_RS,
                Package.JAVAX_PERSISTENCE,
                Package.JAVAX_PERSISTENCE
        );
    }

    // ------------------------------------------------------

    @Test
    public void visitLocalVariableAnnotation() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                @MockScoped long e = System.nanoTime();
            }
        });

        assertUsage(usage,
                Package.JAVAX_ENTERPRISE
        );
    }
    // ------------------------------------------------------

    @Test
    public void visitLocalVariableAnnotation_Deep() {
        final Usage<Jar> usage = usage(new Object() {
            public void m() {
                @ArrayData(data = {@Data(path = @Path("/foo")), @Data(type = HttpServlet.class)}) long e = System.nanoTime();
            }
        });

        assertUsage(usage,
                Package.JAVAX_SERVLET,
                Package.JAVAX_WS_RS
        );
    }

}
