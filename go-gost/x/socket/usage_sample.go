package socket

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/google/uuid"
	psnet "github.com/shirou/gopsutil/v3/net"
)

func readUsageSample() (usageSample, error) {
	boot, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
	if err != nil || strings.TrimSpace(string(boot)) == "" {
		return usageSample{}, fmt.Errorf("无法读取 Linux boot_id: %v", err)
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return usageSample{}, fmt.Errorf("读取网卡身份: %w", err)
	}
	counters, err := psnet.IOCounters(true)
	if err != nil {
		return usageSample{}, fmt.Errorf("读取网卡计数: %w", err)
	}
	values, err := usageInterfaceCounters(interfaces, counters)
	if err != nil {
		return usageSample{}, err
	}
	return usageSample{
		BootID: strings.TrimSpace(string(boot)), Interfaces: values,
		Identity: readUsageIdentity(os.ReadFile, interfaces),
	}, nil
}

func usageInterfaceCounters(interfaces []net.Interface, counters []psnet.IOCountersStat) (map[string]usageCounters, error) {
	indices := make(map[string]int, len(interfaces))
	for _, iface := range interfaces {
		indices[iface.Name] = iface.Index
	}
	values := make(map[string]usageCounters, len(counters))
	for _, counter := range counters {
		if strings.HasPrefix(counter.Name, "lo") {
			continue
		}
		index, exists := indices[counter.Name]
		if !exists || index <= 0 {
			return nil, fmt.Errorf("网卡 %s 在采样时变化，等待下次采样", counter.Name)
		}
		values[strconv.Itoa(index)] = usageCounters{Upload: counter.BytesSent, Download: counter.BytesRecv}
	}
	for _, iface := range interfaces {
		if strings.HasPrefix(iface.Name, "lo") {
			continue
		}
		if _, exists := values[strconv.Itoa(iface.Index)]; !exists {
			return nil, fmt.Errorf("网卡 %s 缺少本次计数，等待下次采样", iface.Name)
		}
	}
	return values, nil
}

func readUsageIdentity(readFile func(string) ([]byte, error), interfaces []net.Interface) usageIdentity {
	identity := usageIdentity{}
	if data, err := readFile("/sys/class/dmi/id/product_uuid"); err == nil {
		if id, err := uuid.Parse(strings.TrimSpace(string(data))); err == nil && id != uuid.Nil &&
			id.String() != "ffffffff-ffff-ffff-ffff-ffffffffffff" {
			identity.HardwareID = usageFingerprint("hardware", id.String())
		}
	}
	if data, err := readFile("/etc/machine-id"); err == nil {
		value := strings.ToLower(strings.TrimSpace(string(data)))
		if decoded, err := hex.DecodeString(value); err == nil && len(decoded) == 16 && value != strings.Repeat("0", 32) && value != strings.Repeat("f", 32) {
			identity.SystemID = usageFingerprint("system", value)
		}
	}
	macs := make(map[string]bool)
	for _, iface := range interfaces {
		mac := strings.ToLower(iface.HardwareAddr.String())
		if !strings.HasPrefix(iface.Name, "lo") && len(iface.HardwareAddr) == 6 && mac != "00:00:00:00:00:00" && mac != "ff:ff:ff:ff:ff:ff" {
			macs[mac] = true
		}
	}
	ordered := make([]string, 0, len(macs))
	for mac := range macs {
		ordered = append(ordered, mac)
	}
	sort.Strings(ordered)
	if len(ordered) > 0 {
		identity.MACID = usageFingerprint("mac", strings.Join(ordered, ","))
	}
	return identity
}

func usageFingerprint(kind, value string) string {
	valueHash := sha256.Sum256([]byte("flux-panel/vps-usage/v1:" + kind + ":" + value))
	return hex.EncodeToString(valueHash[:])
}
