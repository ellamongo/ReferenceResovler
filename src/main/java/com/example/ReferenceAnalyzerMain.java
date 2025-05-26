package com.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

public class ReferenceAnalyzerMain {
    private static final Logger logger = LoggerFactory.getLogger(ReferenceAnalyzerMain.class);
    private static final JavaParser javaParser = new JavaParser(new ParserConfiguration());
    private static JavaParserFacade symbolSolver;

    public static void main(String[] args) {
        if (args.length < 1) {
            logger.error("Please provide the path to the Maven project root directory");
            return;
        }

        String projectPath = args[0];
        try {
            // Initialize analyzers
            MethodReferenceAnalyzer methodAnalyzer = new MethodReferenceAnalyzer();
            ClassReferenceAnalyzer classAnalyzer = new ClassReferenceAnalyzer();

            // Analyze project
            analyzeProject(projectPath, methodAnalyzer, classAnalyzer);

            // Log summary
            logger.info("Found {} method references", methodAnalyzer.getFormattedMethodReferences().size());
            logger.info("Found {} class references", classAnalyzer.getClassReferences().size());

            // Write results to files
            methodAnalyzer.writeResultsToFile("method-references.txt");
            classAnalyzer.writeResultsToFile("class-references.txt");
            
            logger.info("Analysis complete. Results written to method-references.txt and class-references.txt");

        } catch (Exception e) {
            logger.error("Error analyzing project", e);
        }
    }

    public static void analyzeProject(String projectPath, MethodReferenceAnalyzer methodAnalyzer, ClassReferenceAnalyzer classAnalyzer) 
            throws IOException, XmlPullParserException {
        // Read pom.xml
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileInputStream(new File(projectPath, "pom.xml")));
        
        // Get source directory
        String sourceDir = model.getBuild() != null && model.getBuild().getSourceDirectory() != null
                ? model.getBuild().getSourceDirectory()
                : "src/main/java";

        Path sourcePath = Paths.get(projectPath, sourceDir);
        if (!Files.exists(sourcePath)) {
            logger.error("Source directory not found: {}", sourcePath);
            return;
        }

        // Initialize symbol solver
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(true));
        typeSolver.add(new JavaParserTypeSolver(sourcePath));
        
        // Add type solvers for project dependencies
        if (model.getDependencies() != null) {
            for (org.apache.maven.model.Dependency dependency : model.getDependencies()) {
                try {
                    String groupId = dependency.getGroupId();
                    String artifactId = dependency.getArtifactId();
                    String version = dependency.getVersion();
                    
                    String localRepoPath = System.getProperty("user.home") + "/.m2/repository";
                    String jarPath = String.format("%s/%s/%s/%s/%s-%s.jar",
                        localRepoPath,
                        groupId.replace('.', '/'),
                        artifactId,
                        version,
                        artifactId,
                        version);
                    
                    if (Files.exists(Paths.get(jarPath))) {
                        typeSolver.add(new JarTypeSolver(jarPath));
                        logger.info("Added type solver for dependency: {}:{}:{}", groupId, artifactId, version);
                    }
                } catch (Exception e) {
                    logger.warn("Could not add type solver for dependency: {}:{}:{}", 
                        dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                }
            }
        }
        
        symbolSolver = JavaParserFacade.get(typeSolver);

        // Analyze all Java files
        Files.walk(sourcePath)
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> {
                try {
                    analyzeJavaFile(path, methodAnalyzer, classAnalyzer);
                } catch (IOException e) {
                    logger.error("Error analyzing file: " + path, e);
                }
            });
    }

    private static void analyzeJavaFile(Path filePath, MethodReferenceAnalyzer methodAnalyzer, ClassReferenceAnalyzer classAnalyzer) 
            throws IOException {
        logger.info("Analyzing file: {}", filePath);
        
        javaParser.parse(filePath).ifSuccessful(cu -> {
            // Analyze method references
            MethodReferenceVisitor methodVisitor = new MethodReferenceVisitor(filePath, methodAnalyzer);
            methodVisitor.visit(cu, null);

            // Analyze class references
            ClassReferenceVisitor classVisitor = new ClassReferenceVisitor(filePath, classAnalyzer);
            classVisitor.visit(cu, null);
        });
    }

    private static class MethodReferenceVisitor extends VoidVisitorAdapter<Void> {
        private final Path filePath;
        private final MethodReferenceAnalyzer analyzer;

        MethodReferenceVisitor(Path filePath, MethodReferenceAnalyzer analyzer) {
            this.filePath = filePath;
            this.analyzer = analyzer;
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            super.visit(n, arg);
            try {
                ResolvedMethodDeclaration method = symbolSolver.solve(n).getCorrespondingDeclaration();
                analyzer.addMethodReference(method.getQualifiedName(), filePath, n.getBegin().get().line);
            } catch (Exception e) {
                logger.warn("Could not resolve method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String className = n.getType().getNameAsString();
                analyzer.addConstructorReference(className, filePath, n.getBegin().get().line);
            } catch (Exception e) {
                logger.warn("Could not resolve constructor call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }
    }

    private static class ClassReferenceVisitor extends VoidVisitorAdapter<Void> {
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
                // Handle class definition
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
                logger.warn("Error processing class references in {}:{}", filePath, n.getBegin().get().line);
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
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
            } catch (Exception e) {
                // Ignore unresolved annotations
            }
        }

        @Override
        public void visit(NormalAnnotationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String annotationName = n.getNameAsString();
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
            } catch (Exception e) {
                // Ignore unresolved annotations
            }
        }

        @Override
        public void visit(SingleMemberAnnotationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String annotationName = n.getNameAsString();
                analyzer.addClassReference(annotationName, filePath, n.getBegin().get().line, "Annotation");
            } catch (Exception e) {
                // Ignore unresolved annotations
            }
        }
    }
} 