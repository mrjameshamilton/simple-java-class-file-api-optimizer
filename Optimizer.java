import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static java.lang.classfile.ClassFile.ConstantPoolSharingOption.NEW_POOL;
import static java.lang.classfile.ClassFile.DebugElementsOption.DROP_DEBUG;
import static java.lang.classfile.ClassFile.LineNumbersOption.DROP_LINE_NUMBERS;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.IADD;
import static java.lang.classfile.Opcode.ISUB;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Optimize the bytecode in a given jar by applying peephole optimizations.
 */
@SuppressWarnings("preview")
public class Optimizer {

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java --enable-preview Optimizer.java <input-jar> <output-jar> [number of passes: default 1]");
            System.exit(1);
        }

        var input = new File(args[0]);
        if (!input.exists()) {
            System.err.println("Input file " + args[0] + " does not exist");
            System.exit(2);
        }

        var output = new File(args[1]);
        if (output.exists()) {
            output.delete();
        }

        var numberOfPasses = 1;
        if (args.length == 3) {
            try {
                numberOfPasses = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid number of passes: " + args[2]);
                System.exit(3);
            }
        }

        optimizeJar(input, output, numberOfPasses);
    }

    private static void optimizeJar(File input, File output, int numberOfPasses) {
        try (
            var jarFile      = new JarFile(input);
            var outputStream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(output)))
        ) {
            var resolver = ClassHierarchyResolver.defaultResolver()
                .orElse(new JarClassHierarchyResolver(jarFile))
                .cached();

            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();

                try (var inputStream = jarFile.getInputStream(entry)) {
                    var newEntry = new JarEntry(entry);

                    outputStream.putNextEntry(newEntry);

                    if (entry.getName().endsWith(".class")) {
                        var originalBytes = inputStream.readAllBytes();

                        try {
                            var optimizedBytes = originalBytes;

                            for (int pass = 0; pass < numberOfPasses; pass++) {
                                optimizedBytes = optimizeClass(resolver, optimizedBytes);
                            }

                            outputStream.write(optimizedBytes);
                        } catch (Exception e) {
                            // If there's an error during optimization,
                            // copy over the original bytes instead.
                            System.err.println("Error optimizing " + entry.getName() + ": " + e.getMessage());

                            outputStream.write(originalBytes);
                        }
                    } else {
                        // Copy other files across unchanged.
                        inputStream.transferTo(outputStream);
                    }

                    outputStream.closeEntry();
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    public static byte[] optimizeClass(ClassHierarchyResolver resolver, byte[] bytes) {
        // Parse the class bytes into a class model.
        // Drop line numbers and debug info, to simplify the peephole pattern matching.
        var classModel = ClassFile.of(DROP_LINE_NUMBERS, DROP_DEBUG).parse(bytes);

        // When transforming the class, use a new constant pool instead of adding new
        // entries to the existing one.
        return ClassFile.of(NEW_POOL, ClassHierarchyResolverOption.of(resolver))
            .transform(classModel, transformingMethods(
                (methodBuilder, methodElement) -> {
                    if (methodElement instanceof CodeAttribute codeAttribute) {
                        methodBuilder.withCode(codeBuilder -> {
                            optimizeCodeAttribute(codeAttribute, codeBuilder);
                        });
                    } else {
                        methodBuilder.with(methodElement);
                    }
                }
            ));
    }

    private static void optimizeCodeAttribute(CodeAttribute codeAttribute, CodeBuilder codeBuilder) {
        var windowSize = 5;
        var elements = codeAttribute.elementList();
        var currentIndex = 0;

        while (currentIndex < elements.size()) {
            // Create a fixed size window with up to windowSize elements and the remainder nulls.
            var window = new CodeElement[windowSize];
            for (int i = 0; i < windowSize && currentIndex + i < elements.size(); i++) {
                window[i] = elements.get(currentIndex + i);
            }

            // Optimize X +- 0 -> X
            if (window[0] instanceof ConstantInstruction c && c.constantValue().equals(0) &&
                window[1] instanceof Instruction i && (i.opcode() == IADD || i.opcode() == ISUB)) {
                // Skip the two matched elements and emit no new elements.
                currentIndex += 2;
                continue;
            }

            // Optimize append("foo").append("bar") -> append("foobar")
            if (window[0] instanceof ConstantInstruction c1 && c1.constantValue() instanceof String s1 &&
                window[1] instanceof InvokeInstruction i1 &&
                i1.owner().asSymbol().equals(ClassDesc.of("java.lang.StringBuilder")) &&
                i1.method().name().equalsString("append") &&
                i1.typeSymbol().equals(MethodTypeDesc.of(ClassDesc.of("java.lang.StringBuilder"), ClassDesc.of("java.lang.String"))) &&
                window[2] instanceof ConstantInstruction c2 && c2.constantValue() instanceof String s2 &&
                window[3] instanceof InvokeInstruction i2 &&
                i2.owner().equals(i1.owner()) && i1.method().equals(i2.method()) && i1.type().equals(i2.type())
            ) {
                var concat = s1 + s2;

                // Emit the concatenated string constant, if it fits.
                if (concat.getBytes(UTF_8).length <= 65535) {
                    codeBuilder
                        .loadConstant(concat)
                        .invokevirtual(i1.owner().asSymbol(), i1.method().name().stringValue(), i1.typeSymbol());

                    // Skip the four matched instructions.
                    currentIndex += 4;
                    continue;
                }
            }

            // No optimizations, so continue to the next element.
            codeBuilder.accept(elements.get(currentIndex++));
        }
    }

    /**
     * Provides a {@link ClassHierarchyResolver} to resolve classes from a given
     * {@link JarFile}.
     */
    public static class JarClassHierarchyResolver implements ClassHierarchyResolver {
        private final ClassHierarchyResolver resourceClassHierarchyResolver;

        public JarClassHierarchyResolver(JarFile jarFile) {
            this.resourceClassHierarchyResolver = ClassHierarchyResolver.ofResourceParsing(
                classDesc -> {
                    var desc = classDesc.descriptorString();
                    // Remove the L and ; from the descriptor e.g. Ljava/lang/Object -> java/lang/Object
                    var internalName = desc.substring(1, desc.length() - 1);
                    var jarEntry = jarFile.getJarEntry(internalName + ".class");

                    // Class not found
                    if (jarEntry == null) return null;

                    try {
                        return jarFile.getInputStream(jarEntry);
                    } catch (IOException e) {
                        // Error reading class
                        return null;
                    }
                }
            );
        }

        @Override
        public ClassHierarchyInfo getClassInfo(ClassDesc classDesc) {
            return resourceClassHierarchyResolver.getClassInfo(classDesc);
        }
    }
}
