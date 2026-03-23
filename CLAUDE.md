# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4.0 alkalmazás Microsoft Outlook PST fájlok feldolgozásához. PST fájlokat keres, emaileket és csatolmányokat kinyeri belőlük, majd MongoDB-ben tárolja az adatokat. Synology NAS integrációval és Astro 6 + React 19 frontend dashboarddal rendelkezik.

## Architecture

```
Frontend (Astro 6 + React 19 + Tailwind CSS 4)
  :80 (nginx reverse proxy) → :8080 backend | :4321 (dev)
Backend (Spring Boot 4.0 / Spring Framework 7 / Jakarta EE 11)
  Controllers → Services → Repositories → MongoDB
Infrastructure
  MongoDB 7 | Docker Compose | nginx
```

Key components:
- `PstFinderController` / `PstFinderService`: PST fájl keresés és indexelés
- `PstProcessorController` / `PstProcessorService`: PST feldolgozás, email kinyerés
- `SynologyPstFinderController` / `SynologyApiClient`: Synology NAS integráció
- `EmailController`: Email CRUD + keresés
- `ProgressTracker` / `ProgressController`: Feldolgozás állapot követés

## Tech Stack

**Backend:** Java 25 LTS, Spring Boot 4.0.4, Jakarta EE 11, MongoDB, java-libpst, iText 8, PDFBox 3
**Frontend:** Astro 6, React 19.2, Tailwind CSS 4 (@tailwindcss/vite), TypeScript
**Infra:** Docker + Docker Compose, nginx, Eclipse Temurin 25

## Development Commands

```bash
# Backend
mvn clean install          # Build
mvn spring-boot:run        # Run
mvn test                   # Tesztek
mvn test -Dtest=ClassName  # Egy teszt

# Frontend
cd frontend
npm install                # Függőségek
npm run dev                # Dev szerver (:4321)
npm run build              # Production build

# Docker (teljes stack)
docker compose up -d       # Indítás
docker compose up -d --build  # Újraépítés
docker compose logs -f     # Logok
```

## Key Files and Directories

- `src/main/java/hu/fmdev/backend/controller/` - REST controllers
- `src/main/java/hu/fmdev/backend/service/` - Business logic
- `src/main/java/hu/fmdev/backend/repository/` - MongoDB repositories
- `src/main/java/hu/fmdev/backend/domain/` - Domain entities
- `src/main/java/hu/fmdev/backend/config/` - SecurityConfig, SynologyConfig, ModelMapperConfig
- `src/main/resources/application.properties` - Application configuration
- `frontend/src/` - Astro 6 + React frontend (pages, components, layouts, lib/api.ts)
- `frontend/astro.config.mjs` - Astro config (Vite proxy, @tailwindcss/vite)
- `frontend/nginx.conf` - Production reverse proxy
- `Dockerfile` - Backend multi-stage build (JDK 25 → JRE 25)
- `frontend/Dockerfile` - Frontend multi-stage build (Node 22 → nginx)
- `docker-compose.yml` - Full stack (frontend + backend + MongoDB)

## Important Configuration

```properties
# MongoDB
spring.data.mongodb.uri=mongodb://admin:example@localhost:27017/emails?authSource=admin

# Csatolmányok
attachments.directory=/app/attachments

# Synology NAS (opcionális)
synology.host=
synology.username=
synology.password=
synology.path-prefix=/volume1
synology.local-mount-prefix=/mnt/nas
synology.search-extensions=pst,ost
synology.batch-size=100
```

## Key Endpoints

- `/api/emails` - Email CRUD + `/api/emails/search` szűrőkkel
- `/api/file-infos` - Fájl információk
- `/api/progress` - Feldolgozás állapota
- `/find/pst` - PST fájlok keresése → adatbázisba
- `/find/synology` - Synology NAS keresés
- `/pst/processFromDb` - PST feldolgozás adatbázisból
- `/pst/pause` / `/pst/resume` - Szüneteltetés/folytatás
- `/pdf/fill` - PDF űrlap kitöltés
- `/api/files/upload` - Fájl feltöltés (ZIP titkosítással)
