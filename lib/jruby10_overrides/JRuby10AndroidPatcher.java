import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites JVM-only API calls in a tree of .class files to Android-safe
 * equivalents.
 *
 * java.lang.Module does not exist on ART:
 *
 *   Class.getModule()      becomes  POP; ACONST_NULL   (module = null)
 *   Module.canRead(Module) becomes  POP2; ICONST_1     (always readable)
 *
 * Android's java.nio buffers lack the Java 9+ covariant overrides
 * (ByteBuffer.limit(int) returns Buffer, not ByteBuffer), and
 * ConcurrentHashMap.keySet() returns Set, not KeySetView.  Calls compiled
 * against the JDK signatures are retargeted to the superclass signature
 * followed by a checkcast, which works on both the JVM (bridge methods)
 * and ART.
 *
 * com.sun.nio.file.ExtendedOpenOption has no fields on Android; reads
 * become null, which JRuby's ModeFlags null-guards.
 *
 * All rewrites preserve the stack shape, so existing stack map frames
 * remain valid.
 */
public class JRuby10AndroidPatcher {
    static final java.util.Set<String> BUFFER_OWNERS = java.util.Set.of(
            "java/nio/Buffer", "java/nio/ByteBuffer", "java/nio/CharBuffer",
            "java/nio/ShortBuffer", "java/nio/IntBuffer", "java/nio/LongBuffer",
            "java/nio/FloatBuffer", "java/nio/DoubleBuffer", "java/nio/MappedByteBuffer");
    static final java.util.Set<String> BUFFER_METHODS = java.util.Set.of(
            "clear", "flip", "limit", "mark", "position", "reset", "rewind");
    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args[0]);
        final int[] patched = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".class"))
                    // backport9 detects java.lang.Module at runtime and has its
                    // own fallback for platforms without it, so leave it alone.
                    .filter(p -> !root.relativize(p).toString().replace('\\', '/').startsWith("com/headius/backport9/"))
                    .forEach(p -> {
                        if (patch(p)) patched[0]++;
                    });
        }
        System.out.println("Patched " + patched[0] + " classes for Android");
    }

    static boolean patch(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            final boolean[] changed = {false};
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                            if (owner.equals("java/lang/Class") && mName.equals("getModule")
                                    && mDesc.equals("()Ljava/lang/Module;")) {
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ACONST_NULL);
                                changed[0] = true;
                            } else if (owner.equals("java/lang/Module") && mName.equals("canRead")
                                    && mDesc.equals("(Ljava/lang/Module;)Z")) {
                                super.visitInsn(Opcodes.POP2);
                                super.visitInsn(Opcodes.ICONST_1);
                                changed[0] = true;
                            } else if (op == Opcodes.INVOKEVIRTUAL && BUFFER_OWNERS.contains(owner)
                                    && BUFFER_METHODS.contains(mName)
                                    && mDesc.endsWith(")L" + owner + ";")) {
                                String baseDesc = mDesc.substring(0, mDesc.lastIndexOf(')') + 1) + "Ljava/nio/Buffer;";
                                super.visitMethodInsn(op, owner, mName, baseDesc, itf);
                                super.visitTypeInsn(Opcodes.CHECKCAST, owner);
                                changed[0] = true;
                            } else if (op == Opcodes.INVOKEVIRTUAL
                                    && owner.equals("java/util/concurrent/ConcurrentHashMap")
                                    && mName.equals("keySet")
                                    && mDesc.equals("()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;")) {
                                super.visitMethodInsn(op, owner, mName, "()Ljava/util/Set;", itf);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/concurrent/ConcurrentHashMap$KeySetView");
                                changed[0] = true;
                            } else {
                                super.visitMethodInsn(op, owner, mName, mDesc, itf);
                            }
                        }

                        @Override
                        public void visitFieldInsn(int op, String owner, String fName, String fDesc) {
                            if (op == Opcodes.GETSTATIC && owner.equals("com/sun/nio/file/ExtendedOpenOption")) {
                                super.visitInsn(Opcodes.ACONST_NULL);
                                changed[0] = true;
                            } else {
                                super.visitFieldInsn(op, owner, fName, fDesc);
                            }
                        }
                    };
                }
            }, 0);
            if (changed[0]) {
                Files.write(path, writer.toByteArray());
                return true;
            }
            return false;
        } catch (Throwable t) {
            System.err.println("Skipping " + path + ": " + t);
            return false;
        }
    }
}
