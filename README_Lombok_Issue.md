Based on the compilation errors, the project has a Lombok configuration issue. The Lombok annotations (@Data) in the domain classes (FileInfo, Email, etc.) are not being processed during compilation, which means the getter and setter methods that should be generated are missing.

This is a common Maven compilation issue where Lombok needs to be properly integrated with the Maven compiler plugin. The project includes Lombok in dependencies but lacks proper Maven plugin configuration for Lombok processing.

To fix this issue, you would need to:

1. Add the Lombok Maven plugin to your pom.xml:
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.8.1</version>
            <configuration>
                <source>11</source>
                <target>11</target>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>1.18.20</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

2. Or ensure that Lombok is properly installed in your IDE (IntelliJ IDEA, Eclipse, etc.) for proper compilation support.

3. Alternatively, you could try installing Lombok in your Maven environment with:
```bash
mvn install:install-file -Dfile=lombok.jar -DgroupId=org.projectlombok -DartifactId=lombok -Dversion=1.18.20 -Dpackaging=jar
```

However, since I cannot modify files in this repository, you'll need to address this Lombok configuration issue to successfully compile and run the application.

The application itself is a Spring Boot application designed for processing PST files from Microsoft Outlook, with functionality to:
- Search for PST files in specified directories
- Process PST files to extract email content and attachments
- Store extracted email data in MongoDB
- Support for processing from file uploads, text files listing paths, or database records
- Pause/resume functionality for long-running operations