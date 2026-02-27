package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"github.com/nas-indexer/indexer/activity"
	"github.com/nas-indexer/indexer/db"
	"github.com/nas-indexer/indexer/scanner"
)

type Config struct {
	DBPath       string
	ScanPath     string
	ActiveHoursStart int
	ActiveHoursEnd   int
	WeekendAlways    bool
	MaxNASCPU        float64
	MaxNASTXMbps     float64
	PollInterval     time.Duration
	RescanDelay      time.Duration
}

func main() {
	// Parse command line flags
	dbPath := flag.String("db", "/data/index.db", "Database path")
	scanPath := flag.String("scan", "/mnt/nas", "Scan path")
	activeHoursStart := flag.Int("active-start", 22, "Active hours start")
	activeHoursEnd := flag.Int("active-end", 6, "Active hours end")
	weekendAlways := flag.Bool("weekend-always", true, "Weekend always active")
	maxNASCPU := flag.Float64("max-cpu", 50.0, "Max NAS CPU percentage")
	maxNASTXMbps := flag.Float64("max-tx", 40.0, "Max NAS TX Mbps")
	pollInterval := flag.Duration("poll-interval", 60*time.Second, "Poll interval")
	rescanDelay := flag.Duration("rescan-delay", 6*time.Hour, "Rescan delay")
	flag.Parse()

	// Create config
	config := Config{
		DBPath:           *dbPath,
		ScanPath:         *scanPath,
		ActiveHoursStart: *activeHoursStart,
		ActiveHoursEnd:   *activeHoursEnd,
		WeekendAlways:    *weekendAlways,
		MaxNASCPU:        *maxNASCPU,
		MaxNASTXMbps:     *maxNASTXMbps,
		PollInterval:     *pollInterval,
		RescanDelay:      *rescanDelay,
	}

	// Initialize database
	database, err := db.OpenDB(config.DBPath)
	if err != nil {
		log.Fatal("Failed to open database:", err)
	}
	defer database.Close()

	// Initialize ActivityGuard
	guard := activity.NewActivityGuard(
		config.ActiveHoursStart,
		config.ActiveHoursEnd,
		config.WeekendAlways,
		config.MaxNASCPU,
		config.MaxNASTXMbps,
		config.PollInterval,
	)

	// Initialize scanner
	scanner := scanner.NewScanner(config.ScanPath, database, guard)

	// Set up signal handling for graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	c := make(chan os.Signal, 1)
	signal.Notify(c, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-c
		log.Println("Shutting down gracefully...")
		cancel()
	}()

	log.Println("Starting indexer service...")
	log.Printf("Scanning path: %s", config.ScanPath)
	log.Printf("Database path: %s", config.DBPath)

	// Main loop
	for {
		select {
		case <-ctx.Done():
			log.Println("Indexer service shutting down...")
			return
		default:
			// Wait until ready (respects ActivityGuard)
			if err := guard.WaitUntilReady(ctx); err != nil {
				log.Printf("Activity guard error: %v", err)
				return
			}

			// Perform scan
			if err := scanner.Scan(ctx); err != nil {
				log.Printf("Scan error: %v", err)
				// Continue with next cycle instead of stopping
				time.Sleep(5 * time.Second)
				continue
			}

			// Wait for rescan delay
			log.Printf("Scan completed, waiting %v before next scan", config.RescanDelay)
			time.Sleep(config.RescanDelay)
		}
	}
}