package com.example;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;

public class ClassReferenceAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(ClassReferenceAnalyzer.class);
    private final Map<String, ClassInfo> classDefinitions = new HashMap<>();
    private final List<ClassReferenceInfo> classReferences = new ArrayList<>();

    public void addClassDefinition(String qualifiedName, Path filePath, int lineNumber) {
        classDefinitions.put(qualifiedName, new ClassInfo(qualifiedName, filePath, lineNumber, false));
    }

    public void addClassReference(String className, Path filePath, int lineNumber, String type) {
        ClassInfo classDef = classDefinitions.get(className);
        if (classDef == null) {
            classDef = new ClassInfo(className, null, -1, true);
        }
        classReferences.add(new ClassReferenceInfo(className, filePath, lineNumber, classDef, type));
    }

    public List<ClassReferenceInfo> getClassReferences() {
        return classReferences;
    }

    public Map<String, ClassInfo> getClassDefinitions() {
        return classDefinitions;
    }

    public void writeResultsToFile(String outputPath) {
        try (PrintWriter writer = new PrintWriter(outputPath)) {
            writer.println("Class Reference Analysis Results");
            writer.println("==============================");
            writer.println();

            // Write summary
            writer.println("Summary");
            writer.println("=======");
            writer.println("Total class references found: " + classReferences.size());
            writer.println("Total class definitions found: " + classDefinitions.size());
            writer.println();

            // Group references by type
            Map<String, List<ClassReferenceInfo>> referencesByType = classReferences.stream()
                .collect(Collectors.groupingBy(ref -> ref.type));

            // Write detailed results by type
            writer.println("Detailed Results by Type");
            writer.println("======================");
            writer.println();

            for (Map.Entry<String, List<ClassReferenceInfo>> entry : referencesByType.entrySet()) {
                String type = entry.getKey();
                List<ClassReferenceInfo> refs = entry.getValue();

                writer.println(type + " References (" + refs.size() + "):");
                writer.println("-".repeat(type.length() + 20));
                writer.println();

                for (ClassReferenceInfo ref : refs) {
                    writer.println(formatClassReference(ref.type, ref.classDefinition != null ? ref.classDefinition.qualifiedName : ref.reference, ref.filePath.toString(), ref.classDefinition != null ? ref.classDefinition.toString() : "Not found in project"));
                    writer.println();
                }
                writer.println();
            }

            // Write all class definitions
            writer.println("Class Definitions");
            writer.println("================");
            writer.println();
            for (Map.Entry<String, ClassInfo> entry : classDefinitions.entrySet()) {
                writer.println("Class: " + entry.getValue().qualifiedName);
                writer.println("Location: " + entry.getValue().filePath + ":" + entry.getValue().lineNumber);
                writer.println("Status: " + (entry.getValue().isExternal ? "External Library" : "Project Class"));
                writer.println();
            }

        } catch (IOException e) {
            logger.error("Error writing results to file: " + outputPath, e);
        }
    }

    private String formatClassReference(String type, String reference, String location, String definition) {
        return String.format("Type: %s\nReference: %s\nLocation: %s\nDefinition: %s\n", 
            type, 
            reference.contains(".") ? reference : "java.lang." + reference, // Add java.lang. prefix if no package specified
            location,
            definition);
    }

    public static class ClassInfo {
        final String qualifiedName;
        final Path filePath;
        final int lineNumber;
        final boolean isExternal;

        ClassInfo(String qualifiedName, Path filePath, int lineNumber, boolean isExternal) {
            this.qualifiedName = qualifiedName;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.isExternal = isExternal;
        }

        @Override
        public String toString() {
            if (isExternal) {
                return String.format("%s (external library)", qualifiedName);
            } else {
                return String.format("%s (defined in %s:%d)", qualifiedName, filePath, lineNumber);
            }
        }
    }

    public static class ClassReferenceInfo {
        final String reference;
        final Path filePath;
        final int lineNumber;
        final ClassInfo classDefinition;
        final String type; // e.g., "Class Reference", "Type Parameter", "Extends", "Implements"

        ClassReferenceInfo(String reference, Path filePath, int lineNumber, ClassInfo classDefinition, String type) {
            this.reference = reference;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.classDefinition = classDefinition;
            this.type = type;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Type: ").append(type).append("\n");
            sb.append("Reference: ").append(classDefinition != null ? classDefinition.qualifiedName : reference).append("\n");
            sb.append("Location: ").append(filePath).append(":").append(lineNumber).append("\n");
            if (classDefinition != null) {
                sb.append("Definition: ").append(classDefinition).append("\n");
            } else {
                sb.append("Definition: Not found in project\n");
            }
            return sb.toString();
        }
    }

    public static class ClassReferenceVisitor extends VoidVisitorAdapter<Void> {
        private final Path filePath;
        private final ClassReferenceAnalyzer analyzer;

        ClassReferenceVisitor(Path filePath, ClassReferenceAnalyzer analyzer) {
            this.filePath = filePath;
            this.analyzer = analyzer;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            super.visit(n, arg);
            try {
                ResolvedReferenceTypeDeclaration resolved = n.resolve();
                analyzer.addClassDefinition(resolved.getQualifiedName(), filePath, n.getBegin().get().line);

                // Handle extends
                if (n.getExtendedTypes().isNonEmpty()) {
                    for (var extendedType : n.getExtendedTypes()) {
                        try {
                            String className = extendedType.getNameAsString();
                            analyzer.addClassReference(className, filePath, extendedType.getBegin().get().line, "Extends");
                            // Handle type parameters in extends
                            if (extendedType.getTypeArguments().isPresent()) {
                                for (var typeArg : extendedType.getTypeArguments().get()) {
                                    if (typeArg.isClassOrInterfaceType()) {
                                        String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                                        analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Extends Type Parameter");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore unresolved extended types
                        }
                    }
                }

                // Handle implements
                if (n.getImplementedTypes().isNonEmpty()) {
                    for (var implementedType : n.getImplementedTypes()) {
                        try {
                            String className = implementedType.getNameAsString();
                            analyzer.addClassReference(className, filePath, implementedType.getBegin().get().line, "Implements");
                            // Handle type parameters in implements
                            if (implementedType.getTypeArguments().isPresent()) {
                                for (var typeArg : implementedType.getTypeArguments().get()) {
                                    if (typeArg.isClassOrInterfaceType()) {
                                        String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                                        analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Implements Type Parameter");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore unresolved implemented types
                        }
                    }
                }

                // Handle type parameters
                for (var typeParam : n.getTypeParameters()) {
                    for (var bound : typeParam.getTypeBound()) {
                        try {
                            String boundName = bound.getNameAsString();
                            analyzer.addClassReference(boundName, filePath, bound.getBegin().get().line, "Type Parameter Bound");
                        } catch (Exception e) {
                            // Ignore unresolved type parameter bounds
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore unresolved class declarations
            }
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            super.visit(n, arg);
            try {
                Type type = n.getElementType();
                if (type.isClassOrInterfaceType()) {
                    String className = type.asClassOrInterfaceType().getNameAsString();
                    analyzer.addClassReference(className, filePath, type.getBegin().get().line, "Field Type");
                    // Handle type parameters in field type
                    if (type.asClassOrInterfaceType().getTypeArguments().isPresent()) {
                        for (var typeArg : type.asClassOrInterfaceType().getTypeArguments().get()) {
                            if (typeArg.isClassOrInterfaceType()) {
                                String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                                analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Field Type Parameter");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore unresolved field types
            }
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            super.visit(n, arg);
            try {
                // Handle return type
                Type returnType = n.getType();
                if (returnType.isClassOrInterfaceType()) {
                    String className = returnType.asClassOrInterfaceType().getNameAsString();
                    analyzer.addClassReference(className, filePath, returnType.getBegin().get().line, "Return Type");
                    // Handle type parameters in return type
                    if (returnType.asClassOrInterfaceType().getTypeArguments().isPresent()) {
                        for (var typeArg : returnType.asClassOrInterfaceType().getTypeArguments().get()) {
                            if (typeArg.isClassOrInterfaceType()) {
                                String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                                analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Return Type Parameter");
                            }
                        }
                    }
                }

                // Handle parameters
                for (Parameter param : n.getParameters()) {
                    Type paramType = param.getType();
                    if (paramType.isClassOrInterfaceType()) {
                        String className = paramType.asClassOrInterfaceType().getNameAsString();
                        analyzer.addClassReference(className, filePath, paramType.getBegin().get().line, "Parameter Type");
                        // Handle type parameters in parameter type
                        if (paramType.asClassOrInterfaceType().getTypeArguments().isPresent()) {
                            for (var typeArg : paramType.asClassOrInterfaceType().getTypeArguments().get()) {
                                if (typeArg.isClassOrInterfaceType()) {
                                    String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                                    analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Parameter Type Parameter");
                                }
                            }
                        }
                    }
                }

                // Handle type parameters
                for (var typeParam : n.getTypeParameters()) {
                    for (var bound : typeParam.getTypeBound()) {
                        try {
                            String boundName = bound.getNameAsString();
                            analyzer.addClassReference(boundName, filePath, bound.getBegin().get().line, "Method Type Parameter Bound");
                        } catch (Exception e) {
                            // Ignore unresolved type parameter bounds
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore unresolved method types
            }
        }

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String className = n.getType().getNameAsString();
                analyzer.addClassReference(className, filePath, n.getBegin().get().line, "Object Creation");
                // Handle type parameters in object creation
                if (n.getType().getTypeArguments().isPresent()) {
                    for (var typeArg : n.getType().getTypeArguments().get()) {
                        if (typeArg.isClassOrInterfaceType()) {
                            String typeArgName = typeArg.asClassOrInterfaceType().getNameAsString();
                            analyzer.addClassReference(typeArgName, filePath, typeArg.getBegin().get().line, "Object Creation Type Parameter");
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore unresolved object creation expressions
            }
        }

        @Override
        public void visit(MarkerAnnotationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String annotationName = n.getNameAsString();
                // Try to resolve the annotation to get its fully qualified name
                try {
                    ResolvedAnnotationDeclaration resolved = n.resolve();
                    annotationName = resolved.getQualifiedName();
                } catch (Exception e) {
                    // If resolution fails, use the simple name
                    if (!annotationName.contains(".")) {
                        annotationName = "java.lang." + annotationName;
                    }
                }
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
            } catch (Exception e) {
                logger.debug("Could not process marker annotation: " + n.getNameAsString(), e);
            }
        }

        @Override
        public void visit(NormalAnnotationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String annotationName = n.getNameAsString();
                // Try to resolve the annotation to get its fully qualified name
                try {
                    ResolvedAnnotationDeclaration resolved = n.resolve();
                    annotationName = resolved.getQualifiedName();
                } catch (Exception e) {
                    // If resolution fails, use the simple name
                    if (!annotationName.contains(".")) {
                        annotationName = "java.lang." + annotationName;
                    }
                }
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
                
                // Process annotation values that might contain class references
                for (var pair : n.getPairs()) {
                    if (pair.getValue().isClassExpr()) {
                        String className = pair.getValue().asClassExpr().getType().asString();
                        analyzer.addClassReference(className, filePath, pair.getValue().getBegin().get().line, "Annotation Value");
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not process normal annotation: " + n.getNameAsString(), e);
            }
        }

        @Override
        public void visit(SingleMemberAnnotationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String annotationName = n.getNameAsString();
                // Try to resolve the annotation to get its fully qualified name
                try {
                    ResolvedAnnotationDeclaration resolved = n.resolve();
                    annotationName = resolved.getQualifiedName();
                } catch (Exception e) {
                    // If resolution fails, use the simple name
                    if (!annotationName.contains(".")) {
                        annotationName = "java.lang." + annotationName;
                    }
                }
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
                
                // Process the single member value if it's a class expression
                if (n.getMemberValue().isClassExpr()) {
                    String className = n.getMemberValue().asClassExpr().getType().asString();
                    analyzer.addClassReference(className, filePath, n.getMemberValue().getBegin().get().line, "Annotation Value");
                }
            } catch (Exception e) {
                logger.debug("Could not process single member annotation: " + n.getNameAsString(), e);
            }
        }
    }
} 