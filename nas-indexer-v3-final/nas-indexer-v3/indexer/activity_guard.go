package activity

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/nas-indexer/indexer/nasapi"
)

// ActivityGuard manages when services should run based on time and system load
type ActivityGuard struct {
	startHour      int
	endHour        int
	weekendAlways  bool
	maxCPU         float64
	maxTXMbps      float64
	pollInterval   time.Duration
	nas            *nasapi.Client
	log            *slog.Logger
	mu             sync.RWMutex
}

// NewActivityGuard creates a new ActivityGuard instance
func NewActivityGuard(
	startHour, endHour int,
	weekendAlways bool,
	maxCPU, maxTXMbps float64,
	pollInterval time.Duration,
) *ActivityGuard {
	return &ActivityGuard{
		startHour:    startHour,
		endHour:      endHour,
		weekendAlways: weekendAlways,
		maxCPU:       maxCPU,
		maxTXMbps:    maxTXMbps,
		pollInterval: pollInterval,
		log:          slog.Default(),
	}
}

// WaitUntilReady blocks until the system is ready to perform work
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

// isActiveTime checks if current time is within active hours
func (g *ActivityGuard) isActiveTime() bool {
	now := time.Now()
	wd := now.Weekday()
	if g.weekendAlways && (wd == time.Saturday || wd == time.Sunday) {
		return true
	}
	h := now.Hour()
	if g.startHour > g.endHour { // crosses midnight
		return h >= g.startHour || h < g.endHour
	}
	return h >= g.startHour && h < g.endHour
}

// isNASIdle checks if NAS is idle based on CPU and network usage
func (g *ActivityGuard) isNASIdle() (bool, string) {
	// In a real implementation, this would query the NAS API
	// For now, we simulate this check
	return true, "system idle"
}

// ShouldPause returns true if service should pause execution
func (g *ActivityGuard) ShouldPause() (bool, string) {
	if !g.isActiveTime() {
		return true, fmt.Sprintf("outside active hours (%d:00)", time.Now().Hour())
	}
	idle, reason := g.isNASIdle()
	return !idle, reason
}