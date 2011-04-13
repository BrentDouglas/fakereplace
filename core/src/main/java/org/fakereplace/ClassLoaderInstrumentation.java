/*
 * Copyright 2011, Stuart Douglas
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.fakereplace;

import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import org.fakereplace.classloading.ClassLookupManager;
import org.fakereplace.com.google.common.collect.MapMaker;
import org.fakereplace.manip.FinalMethodManipulator;
import org.fakereplace.util.JumpMarker;
import org.fakereplace.util.JumpUtils;

import java.lang.instrument.UnmodifiableClassException;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

public class ClassLoaderInstrumentation {

    private static final ConcurrentMap<Class, Object> instrumentedLoaders = new MapMaker().weakKeys().makeMap();


    public static synchronized void instrumentClassLoaderIfRequired(Class<?> classLoader){
        if(!instrumentedLoaders.containsKey(classLoader)) {
            instrumentedLoaders.put(classLoader, ClassLoaderInstrumentation.class);
            try {
                Agent.getInstrumentation().retransformClasses(classLoader);
            } catch (UnmodifiableClassException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This method instruments class loaders so that they can load our helper
     * classes.
     */
    public static boolean redefineClassLoader(ClassFile classFile, boolean classLoader) throws BadBytecode {
        if(!classLoader) {
            if(classFile.getSuperclass() == null) {
                return false;
            }
            if (!classFile.getSuperclass().equals("java.lang.ClassLoader") && !classFile.getName().endsWith("ClassLoader")) {
                return false;
            }
        }

        for (MethodInfo method : (List<MethodInfo>) classFile.getMethods()) {
            if (method.getName().equals("loadClass") && (method.getDescriptor().equals("(Ljava/lang/String;)Ljava/lang/Class;") || method.getDescriptor().equals("(Ljava/lang/String;Z)Ljava/lang/Class;"))) {
                if (method.getCodeAttribute().getMaxLocals() < 4) {
                    method.getCodeAttribute().setMaxLocals(4);
                }
                classLoader = true;
                // now we instrument the loadClass
                // if the system requests a class from the generated class package
                // then
                // we check to see if it is already loaded.
                // if not we try and get the class definition from GlobalData
                // we do not need to delegate as GlobalData will only
                // return the data to the correct classloader.
                // if the data is not null then we define the class, link
                // it if requested and return it.
                final CodeIterator iterator = method.getCodeAttribute().iterator();
                final Bytecode b = new Bytecode(classFile.getConstPool());
                b.addAload(1);
                b.addAload(0);
                b.addInvokestatic(ClassLookupManager.class.getName(), "getClassData", "(Ljava/lang/String;Ljava/lang/Object;)[B");
                b.add(Opcode.DUP);
                b.add(Opcode.IFNULL);
                JumpMarker jumpEnd = JumpUtils.addJumpInstruction(b);

                //now we need to do the findLoadedClasses thing
                b.addAload(0);
                b.addAload(1);
                b.addInvokevirtual("java.lang.ClassLoader", "findLoadedClass", "(Ljava/lang/String;)Ljava/lang/Class;");
                b.add(Opcode.DUP);
                b.add(Opcode.IFNULL);
                JumpMarker notFound = JumpUtils.addJumpInstruction(b);
                b.add(Opcode.ARETURN);
                notFound.mark();
                b.add(Opcode.POP);
                b.addAstore(3);
                b.addAload(0);
                b.addAload(1);
                b.addAload(3);
                b.addIconst(0);
                b.addAload(3);
                b.add(Opcode.ARRAYLENGTH);
                b.addInvokevirtual("java.lang.ClassLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;");
                if (method.getDescriptor().equals("Ljava/lang/String;Z)Ljava/lang/Class;")) {
                    b.addIload(2);
                } else {
                    b.addIconst(0);
                }
                b.add(Opcode.IFEQ);
                final JumpMarker linkJumpEnd = JumpUtils.addJumpInstruction(b);
                b.add(Opcode.DUP);
                b.addAload(0);
                b.add(Opcode.SWAP);
                b.addInvokevirtual("java.lang.ClassLoader", "resolveClass", "(Ljava/lang/Class;)V");
                linkJumpEnd.mark();
                b.add(Opcode.ARETURN);
                jumpEnd.mark();
                b.add(Opcode.POP);
                iterator.insert(b.get());
                FinalMethodManipulator.addClassLoader(classFile.getName());
                method.getCodeAttribute().computeMaxStack();
            }
        }
        return classLoader;
    }


    private ClassLoaderInstrumentation() {

    }

}