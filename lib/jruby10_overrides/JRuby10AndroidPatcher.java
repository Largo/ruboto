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
 * equivalents.  ART has no java.lang.Module, so:
 *
 *   Class.getModule()      becomes  POP; ACONST_NULL   (module = null)
 *   Module.canRead(Module) becomes  POP2; ICONST_1     (always readable)
 *
 * Both rewrites preserve the stack shape, so existing stack map frames
 * remain valid (null is assignable to any reference frame type).
 */
public class JRuby10AndroidPatcher {
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
                            } else {
                                super.visitMethodInsn(op, owner, mName, mDesc, itf);
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
