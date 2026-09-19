package socket

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	"github.com/google/uuid"
)

const usageStatePath = "vps-usage.json"

// VPSUsage contains only durable totals. Replaying a report does not add traffic.
type VPSUsage struct {
	HardwareID     string `json:"hardware_id"`
	SystemID       string `json:"system_id"`
	MACID          string `json:"mac_id"`
	InstallationID string `json:"installation_id"`
	MeterEpoch     string `json:"meter_epoch"`
	Sequence       uint64 `json:"sequence"`
	UploadBytes    uint64 `json:"upload_bytes"`
	DownloadBytes  uint64 `json:"download_bytes"`
	BootID         string `json:"boot_id"`
}

type usageIdentity struct {
	HardwareID string
	SystemID   string
	MACID      string
}

type usageCounters struct {
	Upload   uint64 `json:"upload"`
	Download uint64 `json:"download"`
}

type usageSample struct {
	Identity   usageIdentity
	BootID     string
	Interfaces map[string]usageCounters
}

type usageState struct {
	Version int `json:"version"`
	VPSUsage
	Interfaces map[string]usageCounters `json:"interfaces"`
}

type usageCollector struct {
	mu       sync.Mutex
	path     string
	state    *usageState
	sample   func() (usageSample, error)
	write    func(string, []byte, os.FileMode) error
	interval time.Duration
	cancel   context.CancelFunc
	done     chan struct{}
	stopped  bool
	lastErr  string
}

func newUsageCollector(path string) *usageCollector {
	return &usageCollector{path: path, sample: readUsageSample, write: config.WriteFileAtomically, interval: 5 * time.Second}
}

// start samples before connecting to the panel, then keeps sampling while offline.
func (c *usageCollector) start(parent context.Context) {
	c.mu.Lock()
	if c.done != nil {
		c.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(parent)
	c.cancel, c.done = cancel, make(chan struct{})
	c.mu.Unlock()
	if err := c.cleanTemps(); err != nil {
		logger.Default().Warnf("清理 VPS 流量临时文件失败: %v", err)
	}
	c.sampleAndLog()
	go func() {
		defer close(c.done)
		ticker := time.NewTicker(c.interval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				c.sampleAndLog()
			case <-ctx.Done():
				c.sampleAndLog()
				c.mu.Lock()
				c.stopped = true
				c.mu.Unlock()
				return
			}
		}
	}()
}

func (c *usageCollector) stop() {
	if c == nil {
		return
	}
	c.mu.Lock()
	cancel, done := c.cancel, c.done
	c.mu.Unlock()
	if cancel != nil {
		cancel()
		<-done
	}
}

func (c *usageCollector) snapshot() *VPSUsage {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state == nil {
		return nil
	}
	value := c.state.VPSUsage
	return &value
}

func (c *usageCollector) sampleAndLog() {
	err := c.flush()
	c.mu.Lock()
	defer c.mu.Unlock()
	if err == nil {
		c.lastErr = ""
	} else if err.Error() != c.lastErr {
		c.lastErr = err.Error()
		logger.Default().Errorf("VPS 流量累计未更新，保留上次已保存值: %v", err)
	}
}

// flush advances checkpoints only after the complete state is durable. Failed
// samples/writes cannot turn into zero counters or publish uncommitted totals.
func (c *usageCollector) flush() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopped {
		return nil
	}
	sample, err := c.sample()
	if err != nil {
		return err
	}
	if strings.TrimSpace(sample.BootID) == "" || sample.Interfaces == nil {
		return fmt.Errorf("无法取得本次启动或网卡计数，跳过采样")
	}
	previous, err := c.load()
	if err != nil {
		return err
	}
	var next usageState
	if previous == nil {
		installation, err := uuid.NewRandom()
		if err != nil {
			return err
		}
		next.Version, next.InstallationID = 1, installation.String()
	} else {
		next = *previous
	}
	changed := previous != nil && ((sample.Identity.HardwareID != "" && previous.HardwareID != "" && sample.Identity.HardwareID != previous.HardwareID) ||
		(sample.Identity.SystemID != "" && previous.SystemID != "" && sample.Identity.SystemID != previous.SystemID))
	if previous == nil || changed {
		epoch, err := uuid.NewRandom()
		if err != nil {
			return err
		}
		next.MeterEpoch, next.Sequence = epoch.String(), 0
		next.UploadBytes, next.DownloadBytes = 0, 0
		if changed {
			// Do not attach a previous machine's temporarily unreadable identity
			// to a confirmed replacement, then mistake its recovery for another replacement.
			next.HardwareID, next.SystemID, next.MACID = "", "", ""
		}
	} else {
		for name, current := range sample.Interfaces {
			last, exists := previous.Interfaces[name]
			if previous.BootID != sample.BootID || !exists {
				last = usageCounters{}
			}
			upload, download := counterDelta(current.Upload, last.Upload), counterDelta(current.Download, last.Download)
			if math.MaxUint64-next.UploadBytes < upload || math.MaxUint64-next.DownloadBytes < download {
				return fmt.Errorf("VPS 累计流量超出 uint64 范围")
			}
			next.UploadBytes += upload
			next.DownloadBytes += download
		}
	}
	if next.Sequence == math.MaxUint64 {
		return fmt.Errorf("VPS 流量序号超出 uint64 范围")
	}
	next.Sequence++
	next.BootID = sample.BootID
	next.Interfaces = make(map[string]usageCounters, len(sample.Interfaces))
	for name, counters := range sample.Interfaces {
		next.Interfaces[name] = counters
	}
	if sample.Identity.HardwareID != "" {
		next.HardwareID = sample.Identity.HardwareID
	}
	if sample.Identity.SystemID != "" {
		next.SystemID = sample.Identity.SystemID
	}
	if sample.Identity.MACID != "" {
		next.MACID = sample.Identity.MACID
	}
	data, err := json.Marshal(&next)
	if err != nil {
		return err
	}
	if err := c.write(c.path, data, 0600); err != nil {
		return fmt.Errorf("保存 VPS 流量状态: %w", err)
	}
	c.state = &next
	return nil
}

func counterDelta(current, previous uint64) uint64 {
	if current < previous {
		return current // The interface counter was reset; never subtract unsigned values.
	}
	return current - previous
}

func (c *usageCollector) load() (*usageState, error) {
	data, err := os.ReadFile(c.path)
	if os.IsNotExist(err) {
		return nil, nil // Loss of this file starts a meter segment, not a VPS identity.
	}
	if err != nil {
		return nil, fmt.Errorf("读取 VPS 流量状态: %w", err)
	}
	var state usageState
	if err := json.Unmarshal(data, &state); err != nil || !validUsageState(&state) {
		if err := c.write(c.path+".corrupt", data, 0600); err != nil {
			return nil, fmt.Errorf("备份损坏的 VPS 流量状态: %w", err)
		}
		return nil, nil
	}
	if c.state != nil && (state.InstallationID != c.state.InstallationID || state.MeterEpoch != c.state.MeterEpoch ||
		state.Sequence <= c.state.Sequence || state.UploadBytes < c.state.UploadBytes || state.DownloadBytes < c.state.DownloadBytes) {
		// An old file restored during this process must not roll back durable totals.
		// Whole-machine snapshot rollback is also checked by the panel's sequence watermark.
		return c.state, nil
	}
	return &state, nil
}

func validUsageState(state *usageState) bool {
	if state.Version != 1 || state.Sequence == 0 || state.BootID == "" || state.Interfaces == nil {
		return false
	}
	for _, id := range []string{state.InstallationID, state.MeterEpoch} {
		if parsed, err := uuid.Parse(id); err != nil || parsed == uuid.Nil {
			return false
		}
	}
	return true
}

func (c *usageCollector) cleanTemps() error {
	dir, base := filepath.Dir(c.path), filepath.Base(c.path)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if !entry.IsDir() && (strings.HasPrefix(entry.Name(), "."+base+".tmp-") ||
			strings.HasPrefix(entry.Name(), "."+base+".corrupt.tmp-")) {
			if err := os.Remove(filepath.Join(dir, entry.Name())); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
	}
	return nil
}
