import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class Mockable {
    public static void main(String[] a) throws Exception {
        try (ZipFile in = new ZipFile(a[0]); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]))) {
            for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements();) {
                ZipEntry z = e.nextElement();
                String n = z.getName();
                if (!n.endsWith(".class") || !(n.startsWith("android/") || n.startsWith("com/android/") || n.startsWith("dalvik/"))) continue;
                byte[] b = in.getInputStream(z).readAllBytes();
                byte[] r;
                try { r = fix(b); } catch (Throwable t) { System.err.println("skip " + n + " " + t); continue; }
                out.putNextEntry(new ZipEntry(n)); out.write(r); out.closeEntry();
            }
        }
    }
    static byte[] fix(byte[] b) {
        ClassNode c = new ClassNode();
        new ClassReader(b).accept(c, ClassReader.SKIP_FRAMES);
        c.access &= ~Opcodes.ACC_FINAL;
        for (MethodNode m : c.methods) {
            m.access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_FINAL);
            if ((m.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            InsnList keep = new InsnList();
            if (m.name.equals("<init>")) {
                AbstractInsnNode i = m.instructions.getFirst(); boolean found = false;
                List<AbstractInsnNode> prefix = new ArrayList<>();
                while (i != null) {
                    if (i instanceof JumpInsnNode || i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode) break;
                    if (!(i instanceof LabelNode || i instanceof LineNumberNode || i instanceof FrameNode)) prefix.add(i);
                    if (i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")
                        && (mi.owner.equals(c.superName) || mi.owner.equals(c.name))) { found = true; break; }
                    i = i.getNext();
                }
                if (!found) {
                    keep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    keep.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, c.superName, "<init>", "()V", false));
                } else for (AbstractInsnNode p : prefix) keep.add(p);
                keep.add(new InsnNode(Opcodes.RETURN));
            } else {
                Type t = Type.getReturnType(m.desc);
                switch (t.getSort()) {
                    case Type.VOID -> {}
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> keep.add(new InsnNode(Opcodes.ICONST_0));
                    case Type.LONG -> keep.add(new InsnNode(Opcodes.LCONST_0));
                    case Type.FLOAT -> keep.add(new InsnNode(Opcodes.FCONST_0));
                    case Type.DOUBLE -> keep.add(new InsnNode(Opcodes.DCONST_0));
                    default -> keep.add(new InsnNode(Opcodes.ACONST_NULL));
                }
                keep.add(new InsnNode(t.getOpcode(Opcodes.IRETURN)));
            }
            m.instructions = keep; m.tryCatchBlocks = new ArrayList<>(); m.localVariables = null;
            m.visibleLocalVariableAnnotations = null; m.invisibleLocalVariableAnnotations = null;
            m.visibleTypeAnnotations = null; m.invisibleTypeAnnotations = null;
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String x, String y) { return "java/lang/Object"; }
        };
        c.accept(w);
        return w.toByteArray();
    }
}
