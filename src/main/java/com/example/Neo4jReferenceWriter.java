package com.example;

import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.nio.file.Path;

public class Neo4jReferenceWriter {
    private static final Logger logger = LoggerFactory.getLogger(Neo4jReferenceWriter.class);
    private static Driver driver;
    private final Path currentFilePath;
    private final int currentLineNumber;

    public static void initialize(String uri) {
        if (driver == null) {
            driver = GraphDatabase.driver(uri);
        }
    }

    public static void closeDriver() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    public Neo4jReferenceWriter(Path currentFilePath, int currentLineNumber) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j driver not initialized. Call initialize() first.");
        }
        this.currentFilePath = currentFilePath;
        this.currentLineNumber = currentLineNumber;
    }

    private String findCurrentMethodId() {
        try (Session session = driver.session()) {
            // Log the current file path and line number for debugging
            logger.debug("Finding method ID for file: {} at line: {}", currentFilePath, currentLineNumber);

            // Convert to absolute path
            String absoluteFilePath = currentFilePath.toAbsolutePath().toString();

            // Try to find the method with exact file path match first
            String query = "MATCH (method:Method) " +
                          "WHERE method.file_path = $file_path " +
                          "AND method.line_range[0] <= $row AND method.line_range[1] >= $row " +
                          "RETURN method.identifier as methodId";
            
            logger.debug("Executing query to find method: {}", query);
            logger.debug("Parameters - file_path: {}, row: {}", absoluteFilePath, currentLineNumber);
            
            Result result = session.run(query, 
                Map.of("file_path", absoluteFilePath, 
                      "row", currentLineNumber));
            
            if (result.hasNext()) {
                String methodId = result.next().get("methodId").asString();
                logger.debug("Found method with ID: {}", methodId);
                return methodId;
            }

            logger.warn("No method found at {}:{}", absoluteFilePath, currentLineNumber);
            return null;
        } catch (Exception e) {
            logger.error("Error finding current method in Neo4j: {}", e.getMessage(), e);
            return null;
        }
    }

    public void createMethodReference(String referencedMethodId) {
        logger.debug("Attempting to create method reference to: {}", referencedMethodId);
        String currentMethodId = findCurrentMethodId();
        if (currentMethodId == null) {
            logger.warn("Skipping method reference creation - current method not found");
            return;
        }

        try (Session session = driver.session()) {
            String query = "MATCH (source:Method {identifier: $sourceId}), (target:Method {identifier: $targetId}) " +
                          "MERGE (source)-[r:REFERENCES]->(target)";
            
            logger.debug("Executing query to create method reference: {}", query);
            logger.debug("Parameters - sourceId: {}, targetId: {}", currentMethodId, referencedMethodId);
            
            Result result = session.run(query, 
                Map.of("sourceId", currentMethodId, "targetId", referencedMethodId));
            
            logger.info("Created reference from {} to {}", currentMethodId, referencedMethodId);
        } catch (Exception e) {
            logger.error("Error creating method reference in Neo4j: {}", e.getMessage(), e);
        }
    }
} 