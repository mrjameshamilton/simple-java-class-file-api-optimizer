import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.*;
import static java.util.jar.Attributes.Name.MAIN_CLASS;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;

@SuppressWarnings("preview")
public class TestJarGenerator {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java -jar test.jar <output.jar>");
            System.exit(1);
        }

        byte[] classBytes = ClassFile.of().build(ClassDesc.of("Test"), cb -> cb
            .withFlags(PUBLIC)
            .withMethod("main", MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"),
            PUBLIC.mask() | STATIC.mask(),
            mb -> mb.withCode(codeBuilder -> codeBuilder
                .new_(ClassDesc.of("java.lang.StringBuilder"))
                .dup()
                .invokespecial(ClassDesc.of("java.lang.StringBuilder"), "<init>", MethodTypeDesc.of(CD_void))
                .loadConstant("The length")
                .invokevirtual(ClassDesc.of("java.lang.StringBuilder"), "append", MethodTypeDesc.of(ClassDesc.of("java.lang.StringBuilder"), ClassDesc.of("java.lang.String")))
                .loadConstant(" of the")
                .invokevirtual(ClassDesc.of("java.lang.StringBuilder"), "append", MethodTypeDesc.of(ClassDesc.of("java.lang.StringBuilder"), ClassDesc.of("java.lang.String")))
                .loadConstant(" arguments array is ")
                .invokevirtual(ClassDesc.of("java.lang.StringBuilder"), "append", MethodTypeDesc.of(ClassDesc.of("java.lang.StringBuilder"), ClassDesc.of("java.lang.String")))
                .aload(0)
                .arraylength()
                .iconst_0()
                .iadd() // args.length + 0 = args.length
                .invokevirtual(ClassDesc.of("java.lang.StringBuilder"), "append", MethodTypeDesc.of(ClassDesc.of("java.lang.StringBuilder"), CD_int))
                .invokevirtual(ClassDesc.of("java.lang.StringBuilder"), "toString", MethodTypeDesc.of(ClassDesc.of("java.lang.String")))
                .getstatic(ClassDesc.of("java.lang.System"), "out", ClassDesc.of("java.io.PrintStream"))
                .swap()
                .invokevirtual(ClassDesc.of("java.io.PrintStream"), "println", MethodTypeDesc.of(CD_void, ClassDesc.of("java.lang.String")))
                .return_()
            )));

        var manifest = new Manifest();
        var attr = manifest.getMainAttributes();
        attr.put(MANIFEST_VERSION, "1.0");
        attr.put(MAIN_CLASS, "Test");

        try (var jos = new JarOutputStream(Files.newOutputStream(Path.of(args[0])), manifest)) {
            var entry = new JarEntry("Test.class");
            jos.putNextEntry(entry);
            jos.write(classBytes);
            jos.closeEntry();
        }
    }
}
