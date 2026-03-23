# SuliWeb - PST Email Processor

Spring Boot alkalmazás Microsoft Outlook PST fájlok feldolgozásához. PST fájlokat keres, emaileket és csatolmányokat kinyeri belőlük, majd MongoDB-ben tárolja az adatokat. Synology NAS integrációval és modern Astro + React frontend dashboarddal rendelkezik.

## Funkciók

- **PST fájlkeresés** - Helyi könyvtárakban vagy Synology NAS-on keres PST fájlokat
- **Email kinyerés** - PST fájlokból emaileket és csatolmányokat nyer ki párhuzamos feldolgozással (10 szál)
- **Duplikátum-szűrés** - SHA-256 hash alapú egyedi azonosítás
- **Szüneteltetés/folytatás** - Hosszú feldolgozási műveletek vezérlése
- **Csatolmány mentés** - Konfigurálható könyvtárba ment
- **Keresés** - Tárgy, feladó, címzett, mappa, fontosság szerinti szűrés
- **Synology integráció** - NAS Universal Search API-n keresztül keres PST fájlokat
- **PDF űrlap kitöltés** - iText alapú PDF form filler
- **Modern dashboard** - Astro + React + Tailwind CSS frontend

## Architektúra

```
┌─────────────────────────────────────────────────────┐
│                    Frontend (Astro)                  │
│  Dashboard │ Email Browser │ File List │ Processing  │
│                  :4321 → proxy → :8080               │
├─────────────────────────────────────────────────────┤
│               Spring Boot Backend (:8080)            │
│  Controllers → Services → Repositories → MongoDB    │
├─────────────────────────────────────────────────────┤
│   MongoDB (emails, fileInfo, users, logs)            │
│   Synology NAS API  │  Fájlrendszer (PST fájlok)    │
└─────────────────────────────────────────────────────┘
```

## Előfeltételek

- Java 17 (Eclipse Temurin JDK ajánlott)
- Maven 3.8+
- MongoDB 6+ (vagy Docker)
- Node.js 18+ (frontend fejlesztéshez)

## Gyors indítás

### Docker-rel (ajánlott)

```bash
# Backend + MongoDB indítása
docker-compose up -d

# Frontend indítása
cd frontend && npm install && npm run dev
```

Az alkalmazás elérhető: `http://localhost:6969` (backend), `http://localhost:4321` (frontend)

### Lokálisan

```bash
# MongoDB indítása (ha nincs Docker)
mongod --auth

# Backend build és indítás
mvn clean install
mvn spring-boot:run

# Frontend indítása (külön terminálban)
cd frontend
npm install
npm run dev
```

## Projekt struktúra

```
suliweb/
├── src/main/java/hu/fmdev/backend/
│   ├── BackendApplication.java          # Belépési pont
│   ├── config/
│   │   ├── SecurityConfig.java          # Spring Security
│   │   ├── ModelMapperConfig.java       # ModelMapper bean
│   │   └── SynologyConfig.java          # Synology NAS konfiguráció
│   ├── controller/
│   │   ├── EmailController.java         # /api/emails - CRUD + keresés
│   │   ├── PstFinderController.java     # /find - PST fájl keresés
│   │   ├── PstProcessorController.java  # /pst - PST feldolgozás
│   │   ├── SynologyPstFinderController.java  # /find/synology
│   │   ├── ProgressController.java      # /api/progress
│   │   ├── FileInfoController.java      # /api/file-infos
│   │   ├── FileUploadController.java    # /api/files/upload
│   │   ├── FileController.java          # /index
│   │   └── PdfFormFillerController.java # /pdf/fill
│   ├── service/
│   │   ├── PstProcessorService.java     # PST feldolgozás (párhuzamos)
│   │   ├── PstFinderService.java        # PST fájl keresés
│   │   ├── SynologyApiClient.java       # Synology REST kliens
│   │   ├── SynologyPstFinderService.java # Synology PST keresés
│   │   ├── FileService.java             # Fájl indexelés
│   │   ├── FileUploadService.java       # ZIP tömörítés + titkosítás
│   │   ├── PdfFormFillerService.java    # PDF űrlap kitöltés
│   │   └── ProgressTracker.java         # Folyamat állapot követés
│   ├── domain/
│   │   ├── Email.java                   # Email MongoDB dokumentum
│   │   ├── FileInfo.java                # PST fájl információ
│   │   ├── FileEntity.java              # Általános fájl entitás
│   │   ├── User.java                    # Felhasználó
│   │   ├── Organization.java            # Szervezet
│   │   ├── Authority.java               # Jogosultság
│   │   ├── LogEntry.java                # Napló bejegyzés
│   │   └── ProgressState.java           # Feldolgozás állapota
│   ├── repository/                      # MongoDB repository-k
│   ├── dto/                             # Data Transfer Objektumok
│   ├── exceptionhandler/                # Globális hibakezelés
│   ├── logger/                          # Központi naplózás (MongoDB)
│   └── util/                            # Hash, fájl I/O, HTML sanitizer
├── src/main/resources/
│   └── application.properties           # Alkalmazás konfiguráció
├── frontend/                            # Astro + React dashboard
│   ├── src/
│   │   ├── pages/                       # Oldalak (index, emails, files, processing, synology)
│   │   ├── components/                  # React komponensek
│   │   ├── layouts/                     # Astro layout
│   │   └── lib/api.ts                   # API kliens
│   ├── package.json
│   └── astro.config.mjs
├── pom.xml                              # Maven konfiguráció
├── Dockerfile                           # Docker image (Eclipse Temurin 17)
└── docker-compose.yml                   # App + MongoDB stack
```

## API végpontok

### Emailek
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/emails` | GET | Összes email lekérdezése |
| `/api/emails/search` | GET | Keresés szűrőkkel (subject, sender, recipient, folder, importance, isRead) |
| `/api/emails/{id}` | GET | Email ID alapján |
| `/api/emails/{id}` | PUT | Email módosítása |
| `/api/emails/{id}` | DELETE | Email törlése |

### PST feldolgozás
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/find/pstToTxt` | GET | PST fájlok keresése → szövegfájlba mentés |
| `/find/pst` | GET | PST fájlok keresése → adatbázisba mentés |
| `/find/synology` | GET | PST keresés Synology NAS-on |
| `/find/synologyToDb` | GET | Synology PST fájlok → adatbázisba mentés |
| `/pst/processFromFile` | POST | Feltöltött PST fájl feldolgozása |
| `/pst/processFromTxt` | POST | PST fájlok feldolgozása szövegfájl alapján |
| `/pst/processFromDb` | POST | PST fájlok feldolgozása adatbázisból |
| `/pst/pause` | POST | Feldolgozás szüneteltetése |
| `/pst/resume` | POST | Feldolgozás folytatása |

### Egyéb
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/progress` | GET | Feldolgozás állapota |
| `/api/file-infos` | GET | Fájl információk |
| `/api/files/upload` | POST | Fájl feltöltés (ZIP titkosítással) |
| `/pdf/fill` | POST | PDF űrlap kitöltése |

## Konfiguráció

Az `application.properties` fő beállításai:

```properties
# MongoDB kapcsolat
spring.data.mongodb.uri=mongodb://admin:example@localhost:27018/emails?authSource=admin

# Csatolmányok mentési helye
attachments.directory=/app/attachments

# Synology NAS (opcionális)
synology.host=                          # NAS IP/hostname
synology.username=                      # NAS felhasználó
synology.password=                      # NAS jelszó
synology.path-prefix=/volume1           # NAS elérési út prefix
synology.local-mount-prefix=/mnt/nas    # Lokális mount pont
synology.search-extensions=pst,ost      # Keresett kiterjesztések
synology.batch-size=100                 # Keresési batch méret
```

## Technológiai stack

**Backend:**
- Spring Boot 2.5 (Web, Data JPA, Security, MongoDB, Thymeleaf, OAuth2)
- java-libpst - PST fájl feldolgozás
- iText PDF + Apache PDFBox - PDF kezelés
- zip4j - ZIP tömörítés/titkosítás
- JSch - SSH műveletek
- Lombok, ModelMapper

**Frontend:**
- Astro 4.16 - Statikus oldal generálás
- React 18 - Interaktív komponensek
- Tailwind CSS 3.4 - Stílusok

**Infrastruktúra:**
- MongoDB - Email és fájl adatok tárolása
- Docker + Docker Compose - Konténerizáció
- Java 17 (Eclipse Temurin)

## Fejlesztés

```bash
# Tesztek futtatása
mvn test

# Egy adott teszt futtatása
mvn test -Dtest=YourTestClass

# Frontend fejlesztői szerver
cd frontend && npm run dev

# Frontend build
cd frontend && npm run build
```

## Licenc

Privát projekt.
