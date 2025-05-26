package com.example;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

public class ReferenceAnalyzerTest {
    private static final Logger logger = LoggerFactory.getLogger(ReferenceAnalyzerTest.class);

    @Test
    public void testAnalyzeTestProject() throws IOException, XmlPullParserException {
        // Initialize analyzers
        MethodReferenceAnalyzer methodAnalyzer = new MethodReferenceAnalyzer();
        ClassReferenceAnalyzer classAnalyzer = new ClassReferenceAnalyzer();

        // Analyze the test project
        ReferenceAnalyzerMain.analyzeProject("test-project/rsa-backend", methodAnalyzer, classAnalyzer);

        // Verify method references
        var methodRefs = methodAnalyzer.getFormattedMethodReferences();
        assertFalse(methodRefs.isEmpty(), "Should find method references");
        logger.info("Found {} method references", methodRefs.size());

        // Verify class references
        var classRefs = classAnalyzer.getClassReferences();
        assertFalse(classRefs.isEmpty(), "Should find class references");
        logger.info("Found {} class references", classRefs.size());

        // Write results to files
        methodAnalyzer.writeResultsToFile("method-references.txt");
        classAnalyzer.writeResultsToFile("class-references.txt");
        
        logger.info("Analysis complete. Results written to method-references.txt and class-references.txt");
    }
} 