# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot application designed for processing PST (Personal Storage Table) files from Microsoft Outlook. The application allows users to:
- Search for PST files in specified directories
- Process PST files to extract email content and attachments
- Store extracted email data in MongoDB
- Handle file uploads and processing from various sources

## Architecture

The application follows a typical Spring Boot architecture with:
- REST controllers for handling HTTP requests
- Service classes for business logic
- Repository classes for data access
- Domain entities for data models
- Configuration classes for application settings

Key components:
- `PstFinderController` and `PstFinderService`: For searching and indexing PST files
- `PstProcessorController` and `PstProcessorService`: For processing PST files and extracting email data
- MongoDB integration for storing email data
- Support for processing PST files from uploaded files, text files listing paths, or database records

## Key Dependencies

- Spring Boot (web, data-jpa, security, validation, oauth2-client, thymeleaf)
- MySQL and MongoDB for data storage
- Apache PDFBox and iText for PDF processing
- java-libpst for PST file processing
- jsch for SSH operations

## Development Commands

To build the application:
```bash
mvn clean install
```

To run the application:
```bash
mvn spring-boot:run
```

To run tests:
```bash
mvn test
```

To run a specific test:
```bash
mvn test -Dtest=YourTestClass
```

## Key Files and Directories

- `src/main/java/hu/fmdev/backend/controller/` - REST controllers
- `src/main/java/hu/fmdev/backend/service/` - Business logic services
- `src/main/java/hu/fmdev/backend/repository/` - Data access layers
- `src/main/java/hu/fmdev/backend/domain/` - Domain entities
- `src/main/resources/application.properties` - Application configuration
- `src/main/java/hu/fmdev/frontend/` - Frontend files (Angular application)

## Important Configuration

The application uses MongoDB for email storage. The MongoDB connection is configured in `application.properties`:
```
spring.data.mongodb.uri=mongodb://admin:example@localhost:27017/emails
```

Attachments are stored in a directory specified by:
```
attachments.directory=C:/attachments
```

## Key Endpoints

- `/find/pstToTxt` - Search for PST files and save to text file
- `/find/pst` - Search for PST files and save to database
- `/find/updateDb` - Update database records for files
- `/pst/processFromFile` - Process PST file from upload
- `/pst/processFromTxt` - Process PST files from text file listing paths
- `/pst/processFromDb` - Process PST files from database records
- `/pst/pause` and `/pst/resume` - Control processing pause/resume

## Processing Features

The application supports:
- Parallel processing of multiple PST files
- Pause/resume functionality for long-running operations
- Attachment saving to configured directory
- Duplicate detection using SHA-256 unique entry IDs
- Support for various email message types (IPM.Note)
- Error handling and logging throughout the process