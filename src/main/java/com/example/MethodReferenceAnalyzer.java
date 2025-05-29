package com.example;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

public class MethodReferenceAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MethodReferenceAnalyzer.class);
    private static final JavaParser javaParser = new JavaParser(new ParserConfiguration());
    private static final Map<String, ConstructorInfo> constructorDefinitions = new HashMap<>();
    private static final List<MethodReferenceInfo> methodReferences = new ArrayList<>();
    private static JavaParserFacade symbolSolver;
    private static PrintWriter outputWriter;
    private static String neo4jUri;

    public static void main(String[] args) {
        System.out.println("Starting application..."); // Temporary debug print
        
        if (args.length < 2) {
            System.out.println("Please provide: <project_path> <neo4j_uri>");
            return;
        }

        String projectPath = args[0];
        neo4jUri = args[1];

        try {
            // Create logs directory if it doesn't exist
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
                System.out.println("Created logs directory: " + logsDir.toAbsolutePath());
            }

            // Delete old log file
            Path logFile = Paths.get("logs/analyzer.log");
            if (Files.exists(logFile)) {
                Files.delete(logFile);
                logger.info("Deleted old log file: {}", logFile);
            }

            // Initialize Neo4j driver
            Neo4jReferenceWriter.initialize(neo4jUri);

            // Create output file
            outputWriter = new PrintWriter("method-references.txt");
            outputWriter.println("Method Reference Analysis Results");
            outputWriter.println("================================");
            outputWriter.println();

            analyzeProject(projectPath);

            // Write summary
            outputWriter.println("\nSummary");
            outputWriter.println("=======");
            outputWriter.println("Total method references found: " + methodReferences.size());
            outputWriter.println("Total constructor definitions found: " + constructorDefinitions.size());
            outputWriter.println("\nMethod References:");
            outputWriter.println("=================");
            for (MethodReferenceInfo ref : methodReferences) {
                outputWriter.println(ref);
            }

            outputWriter.close();
            Neo4jReferenceWriter.closeDriver();
            logger.info("Analysis complete. Results written to method-references.txt and Neo4j");
        } catch (Exception e) {
            logger.error("Error analyzing project", e);
        }
    }

    private static void analyzeProject(String projectPath) throws IOException, XmlPullParserException {
        System.out.println("Starting analyzeProject for: " + projectPath); // Debug print
        logger.debug("Starting analyzeProject for: {}", projectPath);
        
        // Run delombok using Maven
        logger.info("Running delombok on project: {}", projectPath);
        System.out.println("About to run delombok..."); // Debug print
        
        try {
            // Find lombok jar in local maven repository
            String lombokJar = System.getProperty("user.home") + "/.m2/repository/org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar";
            System.out.println("Looking for lombok jar at: " + lombokJar); // Debug print
            logger.debug("Looking for lombok jar at: {}", lombokJar);
            
            if (!Files.exists(Paths.get(lombokJar))) {
                String error = "Lombok jar not found at: " + lombokJar;
                System.out.println(error); // Debug print
                logger.error(error);
                throw new RuntimeException(error);
            }
            System.out.println("Found lombok jar"); // Debug print

            ProcessBuilder processBuilder = new ProcessBuilder(
                "java", "-jar", lombokJar, "delombok",
                "-n", // Skip dependency resolution
                "-d", "target/delombok",
                "src/main/java"
            );
            System.out.println("Created process builder with command: " + String.join(" ", processBuilder.command())); // Debug print
            processBuilder.directory(new File(projectPath));
            Process process = processBuilder.start();
            
            // Read both output and error streams
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                
                // Read standard output
                String line;
                System.out.println("Reading Maven output..."); // Debug print
                while ((line = reader.readLine()) != null) {
                    System.out.println("Maven output: " + line); // Debug print
                    logger.info("Maven output: {}", line);
                }
                
                // Read error output
                System.out.println("Reading Maven errors..."); // Debug print
                while ((line = errorReader.readLine()) != null) {
                    System.out.println("Maven error: " + line); // Debug print
                    logger.error("Maven error: {}", line);
                }
            }
            
            int exitCode = process.waitFor();
            System.out.println("Maven process exited with code: " + exitCode); // Debug print
            if (exitCode != 0) {
                String error = "Maven process failed with exit code: " + exitCode;
                System.out.println(error); // Debug print
                logger.error(error);
                throw new RuntimeException(error);
            }
            System.out.println("Delombok completed successfully"); // Debug print
            logger.info("Delombok completed successfully");
        } catch (InterruptedException e) {
            logger.error("Delombok process was interrupted", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Delombok process was interrupted", e);
        }

        // Read pom.xml
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileInputStream(new File(projectPath, "pom.xml")));
        
        // Get source directory - use delombok output if available
        String sourceDir = "target/delombok";
        if (!Files.exists(Paths.get(projectPath, sourceDir))) {
            sourceDir = model.getBuild() != null && model.getBuild().getSourceDirectory() != null
                    ? model.getBuild().getSourceDirectory()
                    : "src/main/java";
            logger.info("Using original source directory: {}", sourceDir);
        } else {
            logger.info("Using delombok output directory: {}", sourceDir);
        }

        Path sourcePath = Paths.get(projectPath, sourceDir);
        if (!Files.exists(sourcePath)) {
            logger.error("Source directory not found: {}", sourcePath);
            return;
        }

        // Initialize symbol solver with more comprehensive type solvers
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        
        // Add reflection type solver for Java core classes and standard library
        typeSolver.add(new ReflectionTypeSolver(true));
        logger.debug("Added ReflectionTypeSolver");
        
        // Add JavaParser type solver for project source code
        typeSolver.add(new JavaParserTypeSolver(sourcePath));
        logger.debug("Added JavaParserTypeSolver for source path: {}", sourcePath);
        
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
                        logger.debug("Added JarTypeSolver for dependency: {}:{}:{} at {}", 
                            groupId, artifactId, version, jarPath);
                    } else {
                        logger.warn("JAR not found for dependency: {}:{}:{} at {}", 
                            groupId, artifactId, version, jarPath);
                    }
                } catch (Exception e) {
                    logger.warn("Could not add type solver for dependency: {}:{}:{}", 
                        dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                    logger.debug("Dependency error details:", e);
                }
            }
        }
        
        symbolSolver = JavaParserFacade.get(typeSolver);
        logger.debug("Initialized JavaParserFacade with type solver");

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
        final String type;

        public MethodReferenceInfo(String reference, Path filePath, int lineNumber, String type) {
            this.reference = reference;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.type = type;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Type: ").append(type).append("\n");
            sb.append("Reference: ").append(reference).append("\n");
            sb.append("Location: ").append(filePath).append(":").append(lineNumber).append("\n");
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

        private String getFullClassName(ClassOrInterfaceDeclaration n) {
            String className = n.getNameAsString();
            // Get package name if available
            String packageName = n.findAncestor(com.github.javaparser.ast.CompilationUnit.class)
                .flatMap(cu -> cu.getPackageDeclaration())
                .map(pkg -> pkg.getNameAsString())
                .orElse("");
            
            return packageName.isEmpty() ? className : packageName + "." + className;
        }

        private String formatMethodIdentifier(ResolvedMethodDeclaration method) {
            StringBuilder identifier = new StringBuilder();
            // Get full package and class name from qualified name
            String qualifiedName = method.getQualifiedName();
            // Replace the last dot with double colon
            identifier.append(qualifiedName.substring(0, qualifiedName.lastIndexOf('.')))
                     .append("::")
                     .append(method.getName());
            
            // Add parameter types
            identifier.append("(");
            String paramTypes = method.getNumberOfParams() > 0 ? 
                IntStream.range(0, method.getNumberOfParams())
                    .mapToObj(i -> {
                        String type = method.getParam(i).getType().describe();
                        // Extract class names from full type while preserving generics
                        // Remove spaces within generic type parameters
                        return type.replaceAll("([a-zA-Z0-9_]+\\.)+([a-zA-Z0-9_]+)", "$2")
                                 .replaceAll("\\s+", "");
                    })
                    .collect(Collectors.joining(", ")) : 
                "";
            identifier.append(paramTypes);
            identifier.append(")");
            
            return identifier.toString();
        }

        private String formatConstructorIdentifier(ResolvedConstructorDeclaration constructor) {
            StringBuilder identifier = new StringBuilder();
            // Get full package and class name
            String packageName = constructor.getPackageName();
            String className = constructor.getClassName();
            String fullClassName = packageName + "." + className;
            
            // Format as ClassName::ClassName()
            identifier.append(fullClassName)
                     .append("::")
                     .append(className)
                     .append("(");
            
            // Add parameter types
            String paramTypes = constructor.getNumberOfParams() > 0 ? 
                IntStream.range(0, constructor.getNumberOfParams())
                    .mapToObj(i -> {
                        String type = constructor.getParam(i).getType().describe();
                        // Extract class names from full type while preserving generics
                        // Remove spaces within generic type parameters
                        return type.replaceAll("([a-zA-Z0-9_]+\\.)+([a-zA-Z0-9_]+)", "$2")
                                 .replaceAll("\\s+", "");
                    })
                    .collect(Collectors.joining(", ")) : 
                "";
            identifier.append(paramTypes);
            identifier.append(")");
            
            return identifier.toString();
        }

        private boolean isAdvancedSearchController() {
            return filePath.toString().contains("AdvancedSearchController");
        }

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            super.visit(n, arg);
            try {
                ResolvedConstructorDeclaration constructor = symbolSolver.solve(n).getCorrespondingDeclaration();
                String constructorIdentifier = formatConstructorIdentifier(constructor);
                methodReferences.add(new MethodReferenceInfo(constructorIdentifier, filePath, n.getBegin().get().line, "Constructor Call"));
                // Add Neo4j reference using createMethodReference since constructors are stored as methods
                Neo4jReferenceWriter writer = new Neo4jReferenceWriter(filePath, n.getBegin().get().line);
                writer.createMethodReference(constructorIdentifier);
            } catch (Exception e) {
                logger.warn("Could not resolve constructor call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            if (isAdvancedSearchController()) {
                logger.info("Visiting method in AdvancedSearchController: {} at line {}", 
                    n.getNameAsString(), n.getBegin().get().line);
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            if (isAdvancedSearchController()) {
                logger.info("Found method call in AdvancedSearchController: {} at line {}", 
                    n.toString(), n.getBegin().get().line);
            }
            
            super.visit(n, arg);
            try {
                logger.debug("Attempting to resolve method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
                
                // Special logging for AdvancedSearchUtils.scriptDataOutputToXml
                if (n.toString().contains("scriptDataOutputToXml")) {
                    logger.info("Found potential scriptDataOutputToXml call: {} in {}:{}", 
                        n.toString(), filePath, n.getBegin().get().line);
                }
                
                var resolvedMethod = symbolSolver.solve(n);
                if (!resolvedMethod.isSolved()) {
                    logger.warn("Could not resolve method call: {} in {}:{}", 
                        n.toString(), filePath, n.getBegin().get().line);
                    return;
                }
                
                ResolvedMethodDeclaration method = resolvedMethod.getCorrespondingDeclaration();
                String methodIdentifier = formatMethodIdentifier(method);
                
                // Special logging for AdvancedSearchUtils.scriptDataOutputToXml
                if (methodIdentifier.contains("scriptDataOutputToXml")) {
                    logger.info("Successfully resolved scriptDataOutputToXml call to: {} in {}:{}", 
                        methodIdentifier, filePath, n.getBegin().get().line);
                }
                
                String type;
                if (method.isStatic()) {
                    type = "Static Method Call";
                    logger.debug("Found static method call: {}", methodIdentifier);
                } else {
                    type = "Method Call";
                    logger.debug("Found instance method call: {}", methodIdentifier);
                }
                
                methodReferences.add(new MethodReferenceInfo(methodIdentifier, filePath, n.getBegin().get().line, type));
                
                // Add Neo4j reference
                Neo4jReferenceWriter writer = new Neo4jReferenceWriter(filePath, n.getBegin().get().line);
                writer.createMethodReference(methodIdentifier);
            } catch (Exception e) {
                logger.warn("Could not resolve method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
                logger.debug("Resolution error details:", e);
            }
        }

        @Override
        public void visit(MethodReferenceExpr n, Void arg) {
            if (isAdvancedSearchController()) {
                logger.info("Found method reference in AdvancedSearchController: {} at line {}", 
                    n.toString(), n.getBegin().get().line);
            }
            
            super.visit(n, arg);
            try {
                ResolvedMethodDeclaration method = symbolSolver.solve(n).getCorrespondingDeclaration();
                String methodIdentifier = formatMethodIdentifier(method);
                String type;
                
                // Check if this is a static method reference
                if (method.isStatic()) {
                    type = "Static Method Reference";
                    logger.debug("Found static method reference: {}", methodIdentifier);
                } else {
                    type = "Method Reference";
                    logger.debug("Found instance method reference: {}", methodIdentifier);
                }
                
                methodReferences.add(new MethodReferenceInfo(methodIdentifier, filePath, n.getBegin().get().line, type));
                // Add Neo4j reference
                Neo4jReferenceWriter writer = new Neo4jReferenceWriter(filePath, n.getBegin().get().line);
                writer.createMethodReference(methodIdentifier);
            } catch (Exception e) {
                logger.warn("Could not resolve method reference: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }

        @Override
        public void visit(LambdaExpr n, Void arg) {
            if (isAdvancedSearchController()) {
                logger.info("Found lambda expression in AdvancedSearchController at line {}", 
                    n.getBegin().get().line);
            }
            
            super.visit(n, arg);
            try {
                if (n.getBody().isExpressionStmt()) {
                    var expr = n.getBody().asExpressionStmt().getExpression();
                    if (expr.isMethodCallExpr()) {
                        var methodCall = expr.asMethodCallExpr();
                        ResolvedMethodDeclaration method = symbolSolver.solve(methodCall).getCorrespondingDeclaration();
                        String methodIdentifier = formatMethodIdentifier(method);
                        methodReferences.add(new MethodReferenceInfo(methodIdentifier, filePath, n.getBegin().get().line, "Lambda Method Call"));
                        // Add Neo4j reference
                        Neo4jReferenceWriter writer = new Neo4jReferenceWriter(filePath, n.getBegin().get().line);
                        writer.createMethodReference(methodIdentifier);
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not resolve lambda method call: {} in {}:{}", 
                    n.toString(), filePath, n.getBegin().get().line);
            }
        }
    }

    public void addMethodReference(String qualifiedName, Path filePath, int lineNumber) {
        methodReferences.add(new MethodReferenceInfo(qualifiedName, filePath, lineNumber, "Method Call"));
    }

    public void addConstructorReference(String className, Path filePath, int lineNumber) {
        String constructorIdentifier = className + "::" + className + "()";
        methodReferences.add(new MethodReferenceInfo(constructorIdentifier, filePath, lineNumber, "Constructor Call"));
    }

    public List<MethodReferenceInfo> getMethodReferences() {
        return methodReferences;
    }

    public List<String> getFormattedMethodReferences() {
        return methodReferences.stream()
                .map(MethodReferenceInfo::toString)
                .collect(Collectors.toList());
    }

    public Map<String, ConstructorInfo> getConstructorDefinitions() {
        return constructorDefinitions;
    }

    public void writeResultsToFile(String outputPath) {
        try (PrintWriter writer = new PrintWriter(outputPath)) {
            writer.println("Method Reference Analysis Results");
            writer.println("================================");
            writer.println();

            writer.println("\nSummary");
            writer.println("=======");
            writer.println("Total method references found: " + methodReferences.size());
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