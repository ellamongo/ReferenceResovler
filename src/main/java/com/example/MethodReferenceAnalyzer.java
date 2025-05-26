package com.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

public class MethodReferenceAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MethodReferenceAnalyzer.class);
    private static final JavaParser javaParser = new JavaParser(new ParserConfiguration());
    private static final Map<String, MethodInfo> methodDefinitions = new HashMap<>();
    private static final Map<String, ConstructorInfo> constructorDefinitions = new HashMap<>();
    private static final List<MethodReferenceInfo> methodReferences = new ArrayList<>();
    private static JavaParserFacade symbolSolver;
    private static PrintWriter outputWriter;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Please provide the path to the Maven project root directory");
            return;
        }

        String projectPath = args[0];
        try {
            // Create output file
            outputWriter = new PrintWriter("method-references.txt");
            outputWriter.println("Method Reference Analysis Results");
            outputWriter.println("================================");
            outputWriter.println();

            analyzeProject(projectPath);

            // Debug: print all method definition keys
            System.out.println("Collected method definition keys:");
            for (String key : methodDefinitions.keySet()) {
                System.out.println(key);
            }

            // Write summary
            outputWriter.println("\nSummary");
            outputWriter.println("=======");
            outputWriter.println("Total method references found: " + methodReferences.size());
            outputWriter.println("Total method definitions found: " + methodDefinitions.size());
            outputWriter.println("Total constructor definitions found: " + constructorDefinitions.size());
            outputWriter.println("\nMethod References:");
            outputWriter.println("=================");
            for (MethodReferenceInfo ref : methodReferences) {
                outputWriter.println(ref);
            }

            outputWriter.close();
            logger.info("Analysis complete. Results written to method-references.txt");
        } catch (Exception e) {
            logger.error("Error analyzing project", e);
        }
    }

    private static void analyzeProject(String projectPath) throws IOException, XmlPullParserException {
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

        // Initialize symbol solver with more comprehensive type solvers
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        
        // Add reflection type solver for Java core classes and standard library
        typeSolver.add(new ReflectionTypeSolver(true));
        
        // Add JavaParser type solver for project source code
        typeSolver.add(new JavaParserTypeSolver(sourcePath));
        
        // Add type solvers for project dependencies
        if (model.getDependencies() != null) {
            for (org.apache.maven.model.Dependency dependency : model.getDependencies()) {
                try {
                    String groupId = dependency.getGroupId();
                    String artifactId = dependency.getArtifactId();
                    String version = dependency.getVersion();
                    
                    // Try to find the JAR in the local Maven repository
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

        // First pass: collect all method and constructor definitions
        Files.walk(sourcePath)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        collectDefinitions(path);
                    } catch (IOException e) {
                        logger.error("Error collecting definitions: " + path, e);
                    }
                });

        // Second pass: analyze method references
        Files.walk(sourcePath)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        analyzeJavaFile(path);
                    } catch (IOException e) {
                        logger.error("Error analyzing file: " + path, e);
                    }
                });
    }

    private static void collectDefinitions(Path filePath) throws IOException {
        javaParser.parse(filePath).ifSuccessful(cu -> {
            DefinitionCollector collector = new DefinitionCollector(filePath);
            collector.visit(cu, null);
        });
    }

    private static void analyzeJavaFile(Path filePath) throws IOException {
        logger.info("Analyzing file: {}", filePath);
        
        javaParser.parse(filePath).ifSuccessful(cu -> {
            MethodReferenceVisitor methodVisitor = new MethodReferenceVisitor(filePath);
            methodVisitor.visit(cu, null);
        });
    }

    private static class MethodInfo {
        final String className;
        final String methodName;
        final Path filePath;
        final int lineNumber;
        final boolean isExternal;

        MethodInfo(String className, String methodName, Path filePath, int lineNumber, boolean isExternal) {
            this.className = className;
            this.methodName = methodName;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.isExternal = isExternal;
        }

        @Override
        public String toString() {
            if (isExternal) {
                return String.format("%s.%s (external library)", className, methodName);
            } else {
                return String.format("%s.%s (defined in %s:%d)", className, methodName, filePath, lineNumber);
            }
        }
    }

    private static class ConstructorInfo {
        final String className;
        final Path filePath;
        final int lineNumber;
        final boolean isExternal;

        ConstructorInfo(String className, Path filePath, int lineNumber, boolean isExternal) {
            this.className = className;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.isExternal = isExternal;
        }

        @Override
        public String toString() {
            if (isExternal) {
                return String.format("%s constructor (external library)", className);
            } else {
                return String.format("%s constructor (defined in %s:%d)", className, filePath, lineNumber);
            }
        }
    }

    private static class MethodReferenceInfo {
        final String reference;
        final Path filePath;
        final int lineNumber;
        final MethodInfo methodDefinition;
        final ConstructorInfo constructorDefinition;
        final String type;

        public MethodReferenceInfo(String reference, Path filePath, int lineNumber, MethodInfo methodDefinition, ConstructorInfo constructorDefinition, String type) {
            this.reference = reference;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.methodDefinition = methodDefinition;
            this.constructorDefinition = constructorDefinition;
            this.type = type;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Type: ").append(type).append("\n");
            sb.append("Reference: ").append(reference).append("\n");
            sb.append("Location: ").append(filePath).append(":").append(lineNumber).append("\n");
            if (methodDefinition != null) {
                sb.append("Definition: ").append(methodDefinition).append("\n");
            } else if (constructorDefinition != null) {
                sb.append("Definition: ").append(constructorDefinition).append("\n");
            } else {
                sb.append("Definition: Not found in project\n");
            }
            return sb.toString();
        }
    }

    private static class DefinitionCollector extends VoidVisitorAdapter<Void> {
        private final Path filePath;
        private String currentClassName;

        DefinitionCollector(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            currentClassName = n.getNameAsString();
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            super.visit(n, arg);
            String methodName = n.getNameAsString();
            String key = currentClassName + "." + methodName;
            methodDefinitions.put(key, new MethodInfo(currentClassName, methodName, filePath, n.getBegin().get().line, false));
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            super.visit(n, arg);
            String key = currentClassName + ".constructor";
            constructorDefinitions.put(key, new ConstructorInfo(currentClassName, filePath, n.getBegin().get().line, false));
        }
    }

    private static class MethodReferenceVisitor extends VoidVisitorAdapter<Void> {
        private final Path filePath;

        MethodReferenceVisitor(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                String className = n.getType().getNameAsString();
                String key = className + ".constructor";
                ConstructorInfo constructorDef = constructorDefinitions.get(key);
                if (constructorDef == null) {
                    constructorDef = new ConstructorInfo(className, null, -1, true);
                }
                methodReferences.add(new MethodReferenceInfo(className, filePath, n.getBegin().get().line, null, constructorDef, "Constructor Call"));
            } catch (Exception e) {
                logger.warn("Could not resolve constructor call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(MethodReferenceExpr n, Void arg) {
            super.visit(n, arg);
            try {
                ResolvedMethodDeclaration method = symbolSolver.solve(n).getCorrespondingDeclaration();
                String className = method.getClassName();
                String methodName = method.getName();
                String key = className + "." + methodName;
                MethodInfo methodDef = methodDefinitions.get(key);
                if (methodDef == null) {
                    methodDef = new MethodInfo(className, methodName, null, -1, true);
                }
                methodReferences.add(new MethodReferenceInfo(method.getQualifiedName(), filePath, n.getBegin().get().line, methodDef, null, "Method Reference"));
            } catch (Exception e) {
                logger.warn("Could not resolve method reference: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(LambdaExpr n, Void arg) {
            super.visit(n, arg);
            try {
                if (n.getBody().isExpressionStmt()) {
                    var expr = n.getBody().asExpressionStmt().getExpression();
                    if (expr.isMethodCallExpr()) {
                        var methodCall = expr.asMethodCallExpr();
                        ResolvedMethodDeclaration method = symbolSolver.solve(methodCall).getCorrespondingDeclaration();
                        String className = method.getClassName();
                        String methodName = method.getName();
                        String key = className + "." + methodName;
                        MethodInfo methodDef = methodDefinitions.get(key);
                        if (methodDef == null) {
                            methodDef = new MethodInfo(className, methodName, null, -1, true);
                        }
                        methodReferences.add(new MethodReferenceInfo(method.getQualifiedName(), filePath, n.getBegin().get().line, methodDef, null, "Lambda Method Call"));
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not resolve lambda method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            super.visit(n, arg);
            try {
                ResolvedMethodDeclaration method = symbolSolver.solve(n).getCorrespondingDeclaration();
                String className = method.getClassName();
                String methodName = method.getName();
                String key = className + "." + methodName;
                MethodInfo methodDef = methodDefinitions.get(key);
                if (methodDef == null) {
                    methodDef = new MethodInfo(className, methodName, null, -1, true);
                }
                methodReferences.add(new MethodReferenceInfo(method.getQualifiedName(), filePath, n.getBegin().get().line, methodDef, null, "Method Call"));
            } catch (Exception e) {
                logger.warn("Could not resolve method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }
    }

    public void addMethodReference(String qualifiedName, Path filePath, int lineNumber) {
        String[] parts = qualifiedName.split("\\.");
        String className = parts[parts.length - 2];
        String methodName = parts[parts.length - 1];
        String key = className + "." + methodName;
        MethodInfo methodDef = methodDefinitions.get(key);
        if (methodDef == null) {
            methodDef = new MethodInfo(className, methodName, null, -1, true);
        }
        methodReferences.add(new MethodReferenceInfo(qualifiedName, filePath, lineNumber, methodDef, null, "Method Call"));
    }

    public void addConstructorReference(String className, Path filePath, int lineNumber) {
        String key = className + ".constructor";
        ConstructorInfo constructorDef = constructorDefinitions.get(key);
        if (constructorDef == null) {
            constructorDef = new ConstructorInfo(className, null, -1, true);
        }
        methodReferences.add(new MethodReferenceInfo(className, filePath, lineNumber, null, constructorDef, "Constructor Call"));
    }

    public List<MethodReferenceInfo> getMethodReferences() {
        return methodReferences;
    }

    public List<String> getFormattedMethodReferences() {
        return methodReferences.stream()
                .map(MethodReferenceInfo::toString)
                .collect(Collectors.toList());
    }

    public Map<String, MethodInfo> getMethodDefinitions() {
        return methodDefinitions;
    }

    public void writeResultsToFile(String outputPath) {
        try (PrintWriter writer = new PrintWriter(outputPath)) {
            writer.println("Method Reference Analysis Results");
            writer.println("================================");
            writer.println();

            writer.println("\nSummary");
            writer.println("=======");
            writer.println("Total method references found: " + methodReferences.size());
            writer.println("Total method definitions found: " + methodDefinitions.size());
            writer.println("Total constructor definitions found: " + constructorDefinitions.size());
            writer.println("\nMethod References:");
            writer.println("=================");
            for (MethodReferenceInfo ref : methodReferences) {
                writer.println(ref);
            }
        } catch (IOException e) {
            logger.error("Error writing results to file: " + outputPath, e);
        }
    }
} 