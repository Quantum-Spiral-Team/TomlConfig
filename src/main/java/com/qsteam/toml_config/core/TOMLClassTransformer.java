package com.qsteam.toml_config.core;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * ASM-based Class Transformer that dynamically modifies bytecode at runtime.
 * <p>
 * This transformer scans for classes annotated with {@link com.qsteam.toml_config.api.TOMLConfig}.
 * It injects a bytecode sequence into the static initializer ({@code <clinit>})
 * equivalent to:
 * <pre>
 * {@code ConfigManager.init(CurrentClass.class);}
 * </pre>
 * This ensures configurations are loaded as soon as the class is first accessed.
 * <p>
 * Injection strategy:
 * the call is inserted before every {@code RETURN} instruction in {@code <clinit>} to reduce the risk
 * of changing early static-init side effects, while still guaranteeing execution on all paths.
 */
public class TOMLClassTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            reader.accept(cn, ClassReader.EXPAND_FRAMES);

            if (cn.visibleAnnotations != null) {
                for (AnnotationNode an : cn.visibleAnnotations) {
                    if ("Lcom/qsteam/toml_config/api/TOMLConfig;".equals(an.desc)) {
                        return injectConfigInit(cn, reader, basicClass);
                    }
                }
            }
        } catch (Exception ignored) {
            return basicClass;
        }

        return basicClass;
    }

    private byte[] injectConfigInit(ClassNode cn, ClassReader reader, byte[] fallbackBytes) {
        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) {
                clinit = mn;
                break;
            }
        }

        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(clinit);
        }

        if (alreadyInjected(clinit)) {
            return fallbackBytes;
        }

        // Insert before every RETURN to avoid early clinit side effects.
        // Each insertion must use a fresh InsnList: ASM moves nodes out of an InsnList on insert,
        // so reusing one list would only inject before the first RETURN.
        AbstractInsnNode[] insns = clinit.instructions.toArray();
        boolean inserted = false;
        for (AbstractInsnNode insn : insns) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                clinit.instructions.insertBefore(insn, createConfigInitInsnList(cn));
                inserted = true;
            }
        }
        if (!inserted) {
            clinit.instructions.add(createConfigInitInsnList(cn));
        }

        try {
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try {
                        ClassLoader cl = Launch.classLoader != null ? Launch.classLoader : getClass().getClassLoader();
                        Class<?> c1 = Class.forName(type1.replace('/', '.'), false, cl);
                        Class<?> c2 = Class.forName(type2.replace('/', '.'), false, cl);
                        if (c1.isAssignableFrom(c2)) return type1;
                        if (c2.isAssignableFrom(c1)) return type2;
                        if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                        do {
                            c1 = c1.getSuperclass();
                        } while (c1 != null && !c1.isAssignableFrom(c2));
                        return c1 == null ? "java/lang/Object" : c1.getName().replace('.', '/');
                    } catch (Exception e) {
                        return "java/lang/Object";
                    }
                }
            };
            cn.accept(writer);
            return writer.toByteArray();
        } catch (Exception ignored) {
            return fallbackBytes;
        }
    }

    private boolean alreadyInjected(MethodNode clinit) {
        AbstractInsnNode[] instructions = clinit.instructions.toArray();
        for (int i = 0; i < instructions.length - 1; i++) {
            AbstractInsnNode first = instructions[i];
            AbstractInsnNode second = instructions[i + 1];
            if (first instanceof LdcInsnNode && second instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) second;
                if ("com/qsteam/toml_config/core/ConfigManager".equals(call.owner)
                        && "init".equals(call.name)
                        && "(Ljava/lang/Class;)V".equals(call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InsnList createConfigInitInsnList(ClassNode cn) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(Type.getObjectType(cn.name)));
        list.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/qsteam/toml_config/core/ConfigManager",
                "init",
                "(Ljava/lang/Class;)V",
                false));
        return list;
    }
}
