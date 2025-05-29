# Method Reference Analyzer

This project uses the Java Compiler API to analyze method references in a Maven project. It scans all Java files in the project and identifies both method declarations and method references.

## Features

- Analyzes all Java files in a Maven project
- Identifies method declarations
- Identifies method references
- Supports custom source directories (via pom.xml configuration)
- Provides detailed logging of findings

## Requirements

- Java 11 or higher
- Maven 3.6 or higher

## Building the Project

To build the project, run:

```bash
mvn clean package
```

This will create a runnable JAR file in the `target` directory.

## Usage

Run the analyzer by providing the path to your Maven project:

```bash
java -jar target/method-reference-analyzer-1.0-SNAPSHOT-jar-with-dependencies.jar /path/to/your/maven/project
```

The analyzer will:
1. Read the project's pom.xml to determine the source directory
2. Scan all Java files in the project
3. Log all method declarations and method references found

## Output

The analyzer will output:
- The path of each Java file being analyzed
- All method declarations found in each file
- All method references found in each file
- Line numbers where methods and references are found

## Example Output

```
INFO: Analyzing file: /path/to/project/src/main/java/com/example/MyClass.java
INFO: Found method: myMethod in 10
INFO: Found method reference: MyClass::myMethod in 15
```

## Error Handling

The analyzer includes error handling for:
- Invalid project paths
- Missing source directories
- File parsing errors
- Maven configuration issues

All errors are logged with appropriate context information. 

## Running Without Building

You can run the analyzer directly using Maven without building the JAR file by using the following command:

```bash
mvn exec:java -Dexec.mainClass="com.example.ReferenceAnalyzerMain" -Dexec.args="/path/to/your/maven/project"
```

Replace `/path/to/your/maven/project` with the path to the Maven project you want to analyze.

This command will:
- Compile the code if necessary
- Run the analyzer directly
- Output results as described below

## Running Tests

To run the tests for the project, use the following command:

```bash
mvn test
```

This will execute all the tests defined in the project. 