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
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static java.lang.classfile.ClassFile.ConstantPoolSharingOption.NEW_POOL;
import static java.lang.classfile.ClassFile.DebugElementsOption.DROP_DEBUG;
import static java.lang.classfile.ClassFile.LineNumbersOption.DROP_LINE_NUMBERS;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.IADD;
import static java.lang.classfile.Opcode.ISUB;
import static java.lang.classfile.Opcode.LDC;
import static java.lang.classfile.Opcode.LDC_W;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A rewrite produced by a matching peephole: the number of elements it
 * matched at the start of the window, and the elements that replace them.
 */
record Rewrite(int matched, List<CodeElement> replacement) {}

/**
 * A peephole optimisation: looks at the start of the window and, if its
 * pattern is there, returns the rewrite to apply. Returns null if there is
 * no match. The pool builder is for replacements that need new constant
 * pool entries.
 */
interface Peephole {
    String name();
    Rewrite apply(CodeElement[] window, ConstantPoolBuilder pool);
}

/**
 * Removes the redundant addition or subtraction of zero: X +- 0 -> X.
 */
class AddZero implements Peephole {
    public String name() { return "addZero"; }

    public Rewrite apply(CodeElement[] window, ConstantPoolBuilder pool) {
        if (window[0] instanceof ConstantInstruction c && c.constantValue().equals(0) &&
            window[1] instanceof Instruction i && (i.opcode() == IADD || i.opcode() == ISUB)) {
            // Replace the two matched elements with nothing.
            return new Rewrite(2, List.of());
        }
        return null;
    }
}

/**
 * Merges two consecutive StringBuilder.append calls with constant strings:
 * append("foo").append("bar") -> append("foobar").
 */
class AppendMerge implements Peephole {
    public String name() { return "appendMerge"; }

    public Rewrite apply(CodeElement[] window, ConstantPoolBuilder pool) {
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

            // Replace with the concatenated string constant, if it fits.
            // ldc takes a one byte constant pool index; use ldc_w otherwise.
            if (concat.getBytes(UTF_8).length <= 65535) {
                var entry = pool.stringEntry(concat);
                return new Rewrite(4, List.of(
                    ConstantInstruction.ofLoad(entry.index() <= 0xFF ? LDC : LDC_W, entry),
                    i2));
            }
        }
        return null;
    }
}

/**
 * Optimize the bytecode in a given jar by applying peephole optimizations.
 */
public class Optimizer {

    /**
     * The peephole optimisations to apply. Add new optimisations here.
     */
    private static final List<Peephole> PEEPHOLES = List.of(
        new AddZero(),
        new AppendMerge()
    );

    /**
     * The total number of times each peephole matched, printed at the end.
     */
    private static final Map<String, Integer> matchCounts = new TreeMap<>();

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java Optimizer.java <input-jar> <output-jar>");
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

        optimizeJar(input, output);
    }

    private static void optimizeJar(File input, File output) {
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
                            outputStream.write(optimizeClass(resolver, originalBytes));
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

            // Print a summary of the applied optimisations.
            matchCounts.forEach((name, count) -> System.out.println(name + ": " + count));
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static byte[] optimizeClass(ClassHierarchyResolver resolver, byte[] bytes) {
        // Parse the class bytes into a class model.
        // Drop line numbers and debug info, to simplify the peephole pattern matching.
        var classModel = ClassFile.of(DROP_LINE_NUMBERS, DROP_DEBUG).parse(bytes);

        // When transforming the class, use a new constant pool instead of adding new
        // entries to the existing one.
        return ClassFile.of(NEW_POOL, ClassHierarchyResolverOption.of(resolver))
            .transformClass(classModel, transformingMethods(
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
        var elements = new ArrayList<>(codeAttribute.elementList());
        var currentIndex = 0;

        // The rewrite budget guards against peepholes that undo each other's
        // work and would otherwise keep rewriting forever: no converging set
        // of peepholes gets anywhere near it. Exhausting it fails the class,
        // which is then copied to the output unoptimised.
        var budget = elements.size() * 4;

        while (currentIndex < elements.size()) {
            // Create a fixed size window with up to windowSize elements and the remainder nulls.
            //
            // Since line numbers and debug info were dropped when parsing, instructions
            // sit directly next to each other in the window. The pseudo-instructions that
            // remain (labels marking jump targets and exception handler ranges) do not
            // match any pattern and therefore end any potential match. This is essential
            // for correctness, not just a simplification: a label in the middle of a
            // matched pattern is a position that other code can jump to, and rewriting
            // across it would change what that jumping code executes.
            var window = new CodeElement[windowSize];
            for (int i = 0; i < windowSize && currentIndex + i < elements.size(); i++) {
                window[i] = elements.get(currentIndex + i);
            }

            // Try each peephole in turn; the first that matches wins.
            Rewrite rewrite = null;
            for (var peephole : PEEPHOLES) {
                if ((rewrite = peephole.apply(window, codeBuilder.constantPool())) != null) {
                    matchCounts.merge(peephole.name(), 1, Integer::sum);
                    break;
                }
            }

            if (rewrite == null) {
                // Nothing matched here; slide the window forward.
                currentIndex++;
                continue;
            }

            if (--budget < 0) {
                throw new IllegalStateException("peepholes are not converging");
            }

            // Splice the replacement over the matched elements...
            elements.subList(currentIndex, currentIndex + rewrite.matched()).clear();
            elements.addAll(currentIndex, rewrite.replacement());

            // ...and back the window up over the seam, so a rewrite enabled
            // by this one (on either side of it) is found in the same sweep.
            currentIndex = Math.max(0, currentIndex - (windowSize - 1));
        }

        // Emit the optimised elements.
        elements.forEach(codeBuilder::accept);
    }

    /**
     * Provides a {@link ClassHierarchyResolver} to resolve classes from a given
     * {@link JarFile}.
     */
    private static class JarClassHierarchyResolver implements ClassHierarchyResolver {
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
