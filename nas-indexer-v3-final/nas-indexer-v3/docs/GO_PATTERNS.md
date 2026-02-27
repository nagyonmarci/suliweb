# Go Patterns – NAS Indexer

## Module setup

```bash
# Minden Go service saját module-lal:
go mod init github.com/nas-indexer/[service-name]
go get github.com/mattn/go-sqlite3   # CGO required
go get github.com/chi/chi/v5          # dashboard HTTP router
```

## SQLite kapcsolat (kötelező DSN)

```go
import (
    "database/sql"
    _ "github.com/mattn/go-sqlite3"
)

func openDB(path string) (*sql.DB, error) {
    dsn := path + "?_busy_timeout=5000&_journal_mode=WAL&_foreign_keys=on&_synchronous=NORMAL"
    db, err := sql.Open("sqlite3", dsn)
    if err != nil {
        return nil, err
    }
    db.SetMaxOpenConns(1)        // SQLite: csak 1 writer
    db.SetMaxIdleConns(1)
    return db, nil
}
```

## ActivityGuard – goroutine alapú

```go
type ActivityGuard struct {
    startHour      int           // 22
    endHour        int           // 6
    weekendAlways  bool          // true
    maxCPU         float64       // 50.0
    maxTXMbps      float64       // 40.0
    pollInterval   time.Duration // 60s
    nas            *SynologyClient
    log            *slog.Logger
}

func (g *ActivityGuard) WaitUntilReady(ctx context.Context) error {
    for {
        if g.isActiveTime() {
            idle, reason := g.isNASIdle()
            if idle {
                return nil
            }
            g.log.Info("NAS busy, waiting", "reason", reason)
        } else {
            g.log.Info("Outside active hours, waiting",
                "hour", time.Now().Hour())
        }
        select {
        case <-time.After(g.pollInterval):
        case <-ctx.Done():
            return ctx.Err()
        }
    }
}

func (g *ActivityGuard) isActiveTime() bool {
    now := time.Now()
    wd := now.Weekday()
    if g.weekendAlways && (wd == time.Saturday || wd == time.Sunday) {
        return true
    }
    h := now.Hour()
    if g.startHour > g.endHour { // átnyúlik éjfélen: 22–6
        return h >= g.startHour || h < g.endHour
    }
    return h >= g.startHour && h < g.endHour
}

func (g *ActivityGuard) ShouldPause() (bool, string) {
    if !g.isActiveTime() {
        return true, fmt.Sprintf("outside active hours (%d:00)", time.Now().Hour())
    }
    idle, reason := g.isNASIdle()
    return !idle, reason
}
```

## Synology API kliens

```go
type SynologyClient struct {
    baseURL  string
    user     string
    password string
    sid      string
    mu       sync.Mutex
    http     *http.Client
}

func (c *SynologyClient) Login(ctx context.Context) error {
    // GET /webapi/auth.cgi?api=SYNO.API.Auth&version=3&method=login&...
    // Store sid
}

func (c *SynologyClient) GetUtilization(ctx context.Context) (*Utilization, error) {
    // GET /webapi/entry.cgi?api=SYNO.Core.System.Utilization&version=1&method=get&_sid=...
    // Re-login on 403/session expired
}

type Utilization struct {
    CPU struct {
        UserLoad   float64 `json:"user_load"`
        SystemLoad float64 `json:"system_load"`
    } `json:"cpu"`
    Network []struct {
        TX float64 `json:"tx"`
        RX float64 `json:"rx"`
    } `json:"network"`
}
```

## Scanner worker pool

```go
func (s *Scanner) Scan(ctx context.Context) error {
    jobs := make(chan string, 1000)
    results := make(chan ScanResult, 1000)
    
    // Worker pool
    var wg sync.WaitGroup
    nWorkers := runtime.NumCPU()
    for i := 0; i < nWorkers; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for path := range jobs {
                result, err := s.processFile(ctx, path)
                if err != nil {
                    s.log.Warn("file error", "path", path, "err", err)
                    continue
                }
                results <- result
            }
        }()
    }
    
    // DB writer goroutine
    go func() {
        batch := make([]ScanResult, 0, 500)
        for r := range results {
            batch = append(batch, r)
            if len(batch) >= 500 {
                s.batchInsert(ctx, batch)
                batch = batch[:0]
            }
        }
        if len(batch) > 0 {
            s.batchInsert(ctx, batch)
        }
    }()
    
    // Walker
    err := filepath.WalkDir(s.basePath, func(path string, d fs.DirEntry, err error) error {
        if err != nil || d.IsDir() {
            return nil
        }
        // Throttle check every 100 files
        if s.fileCount.Add(1) % 100 == 0 {
            if pause, reason := s.guard.ShouldPause(); pause {
                s.saveProgress(path)
                if err := s.guard.WaitUntilReady(ctx); err != nil {
                    return err
                }
            }
        }
        jobs <- path
        return nil
    })
    
    close(jobs)
    wg.Wait()
    close(results)
    return err
}
```

## SHA256 + content_hash

```go
func sha256File(path string) (string, error) {
    f, err := os.Open(path)
    if err != nil {
        return "", err
    }
    defer f.Close()
    h := sha256.New()
    if _, err := io.Copy(h, f); err != nil {
        return "", err
    }
    return hex.EncodeToString(h.Sum(nil)), nil
}

func contentHash(text string) string {
    // normalize: lowercase + collapse whitespace
    words := strings.Fields(strings.ToLower(text))
    normalized := strings.Join(words, " ")
    h := sha256.Sum256([]byte(normalized))
    return hex.EncodeToString(h[:])
}
```

## HTTP szerver (dashboard)

```go
import "github.com/go-chi/chi/v5"

func main() {
    r := chi.NewRouter()
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)
    
    // API routes
    r.Route("/api", func(r chi.Router) {
        r.Get("/stats", handleStats)
        r.Get("/duplicates", handleDuplicates)
        r.Post("/duplicates/{id}/decision", handleDecision)
        r.Post("/search", handleSearch)
    })
    
    // Astro static files
    r.Handle("/*", http.FileServer(http.Dir("./frontend/dist")))
    
    slog.Info("Dashboard listening", "port", 8080)
    http.ListenAndServe(":8080", r)
}
```

## Logging (slog – stdlib, Go 1.21+)

```go
logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
    Level: slog.LevelDebug,
}))
slog.SetDefault(logger)

// Használat:
slog.Info("scan started", "path", scanPath)
slog.Warn("file skipped", "path", path, "err", err)
slog.Error("db error", "err", err)
```

## Environment (envconfig minta)

```go
type Config struct {
    NASHost          string        `env:"NAS_HOST,required"`
    NASUser          string        `env:"NAS_USER,required"`
    NASPass          string        `env:"NAS_PASS,required"`
    DBPath           string        `env:"DB_PATH" envDefault:"/data/index.db"`
    ScanPath         string        `env:"SCAN_PATH" envDefault:"/mnt/nas"`
    ActiveHoursStart int           `env:"ACTIVE_HOURS_START" envDefault:"22"`
    ActiveHoursEnd   int           `env:"ACTIVE_HOURS_END" envDefault:"6"`
    WeekendAlways    bool          `env:"WEEKEND_ALWAYS_ACTIVE" envDefault:"true"`
    MaxNASCPU        float64       `env:"MAX_NAS_CPU" envDefault:"50"`
    MaxNASTXMbps     float64       `env:"MAX_NAS_TX_MBPS" envDefault:"40"`
    PollInterval     time.Duration `env:"POLL_INTERVAL_SECONDS" envDefault:"60s"`
    RescanDelay      time.Duration `env:"RESCAN_DELAY_SECONDS" envDefault:"6h"`
}
```

## Docker – Go egybináris

```dockerfile
FROM golang:1.22-alpine AS builder
RUN apk add --no-cache gcc musl-dev sqlite-dev
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=1 GOOS=linux go build -o service .

FROM alpine:3.19
RUN apk add --no-cache sqlite-libs ca-certificates
COPY --from=builder /app/service /service
CMD ["/service"]
```
