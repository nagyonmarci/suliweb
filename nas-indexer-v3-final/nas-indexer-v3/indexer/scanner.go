package scanner

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/nas-indexer/indexer/activity"
	"github.com/nas-indexer/indexer/db"
)

// Scanner scans the filesystem and indexes files
type Scanner struct {
	basePath   string
	db         *db.DB
	guard      *activity.ActivityGuard
	fileCount  int64
	log        *slog.Logger
}

// NewScanner creates a new Scanner instance
func NewScanner(basePath string, database *db.DB, guard *activity.ActivityGuard) *Scanner {
	return &Scanner{
		basePath: basePath,
		db:       database,
		guard:    guard,
		log:      slog.Default(),
	}
}

// Scan performs a filesystem scan
func (s *Scanner) Scan(ctx context.Context) error {
	s.log.Info("Starting scan", "path", s.basePath)

	// Start a new scan run
	scanRunID, err := s.db.InsertScanRun()
	if err != nil {
		return fmt.Errorf("failed to start scan run: %w", err)
	}

	// Reset file count
	s.fileCount = 0

	// Track scan statistics
	var filesNew, filesUpdated, filesSkipped, filesDeleted int

	// Create channels for workers
	jobs := make(chan string, 1000)
	results := make(chan db.FileRecord, 1000)

	// Start worker pool
	var wg sync.WaitGroup
	nWorkers := runtime.NumCPU()
	for i := 0; i < nWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for path := range jobs {
				result, err := s.processFile(ctx, path)
				if err != nil {
					s.log.Warn("file processing error", "path", path, "err", err)
					continue
				}
				results <- result
			}
		}()
	}

	// Start DB writer goroutine
	go func() {
		batch := make([]db.FileRecord, 0, 500)
		for r := range results {
			batch = append(batch, r)
			if len(batch) >= 500 {
				s.batchInsert(ctx, batch, scanRunID)
				batch = batch[:0]
			}
		}
		if len(batch) > 0 {
			s.batchInsert(ctx, batch, scanRunID)
		}
	}()

	// Walk the directory
	err = filepath.WalkDir(s.basePath, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}

		// Throttle check every 100 files
		if s.fileCount%100 == 0 {
			if pause, reason := s.guard.ShouldPause(); pause {
				s.log.Info("Pausing scan due to system load", "reason", reason)
				s.saveProgress(path)
				if err := s.guard.WaitUntilReady(ctx); err != nil {
					return err
				}
			}
		}

		s.fileCount++
		jobs <- path
		return nil
	})

	close(jobs)
	wg.Wait()
	close(results)

	// Update scan run statistics
	finishedAt := time.Now().Unix()
	s.db.UpdateScanRun(scanRunID, finishedAt, filesNew, filesUpdated, filesSkipped, filesDeleted)

	s.log.Info("Scan completed", "path", s.basePath, "files_processed", s.fileCount)

	return err
}

// processFile processes a single file
func (s *Scanner) processFile(ctx context.Context, path string) (db.FileRecord, error) {
	info, err := os.Stat(path)
	if err != nil {
		return db.FileRecord{}, fmt.Errorf("failed to stat file: %w", err)
	}

	// Check if we should cancel
	select {
	case <-ctx.Done():
		return db.FileRecord{}, ctx.Err()
	default:
	}

	// Get file metadata
	name := filepath.Base(path)
	extension := filepath.Ext(path)
	mtime := info.ModTime().Unix()
	size := info.Size()

	// Calculate SHA256
	sha256Hash, err := s.calculateSHA256(path)
	if err != nil {
		s.log.Warn("failed to calculate SHA256", "path", path, "err", err)
		sha256Hash = ""
	}

	// Calculate content hash (simplified)
	contentHash := ""

	// Create file record
	record := db.FileRecord{
		Path:        path,
		Name:        name,
		Extension:   extension,
		Size:        size,
		Mtime:       float64(mtime),
		Sha256:      sha256Hash,
		ContentHash: contentHash,
		ScannedAt:   float64(time.Now().Unix()),
		SourceType:  "file",
	}

	return record, nil
}

// calculateSHA256 calculates the SHA256 hash of a file
func (s *Scanner) calculateSHA256(path string) (string, error) {
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

// batchInsert inserts a batch of file records
func (s *Scanner) batchInsert(ctx context.Context, batch []db.FileRecord, scanRunID int) {
	for _, record := range batch {
		if err := s.db.InsertFile(record); err != nil {
			s.log.Warn("failed to insert file record", "path", record.Path, "err", err)
		}
	}
}

// saveProgress saves the current progress for crash recovery
func (s *Scanner) saveProgress(path string) {
	if err := s.db.SaveProgress("last_scanned_path", path); err != nil {
		s.log.Warn("failed to save progress", "err", err)
	}
}