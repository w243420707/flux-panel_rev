package socket

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	psnet "github.com/shirou/gopsutil/v3/net"
)

type usageTestSource struct {
	mu    sync.Mutex
	value usageSample
	err   error
}

func (s *usageTestSource) read() (usageSample, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.value, s.err
}

func (s *usageTestSource) set(boot string, counters map[string]usageCounters) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.value.BootID, s.value.Interfaces = boot, counters
}

func newUsageTest(t *testing.T) (*usageCollector, *usageTestSource) {
	t.Helper()
	source := &usageTestSource{value: usageSample{
		BootID: "boot-a", Identity: usageIdentity{HardwareID: usageFingerprint("hardware", "machine-a"), SystemID: usageFingerprint("system", "os-a")},
		Interfaces: map[string]usageCounters{"2": {Upload: 1000, Download: 2000}},
	}}
	collector := newUsageCollector(filepath.Join(t.TempDir(), usageStatePath))
	collector.sample = source.read
	return collector, source
}

func flushUsage(t *testing.T, c *usageCollector) *VPSUsage {
	t.Helper()
	if err := c.flush(); err != nil {
		t.Fatal(err)
	}
	return c.snapshot()
}

func TestUsageStartsAtZeroAndSurvivesProcessAndHostRestarts(t *testing.T) {
	c, source := newUsageTest(t)
	first := flushUsage(t, c)
	if first.Sequence != 1 || first.UploadBytes != 0 || first.DownloadBytes != 0 {
		t.Fatalf("first attachment must start at zero: %+v", first)
	}
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1100, Download: 2300}})
	second := flushUsage(t, c)
	if second.UploadBytes != 100 || second.DownloadBytes != 300 {
		t.Fatalf("wrong same-boot delta: %+v", second)
	}
	// A process update/restart recovers traffic since the last durable checkpoint.
	restarted := newUsageCollector(c.path)
	restarted.sample = source.read
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1200, Download: 2500}})
	third := flushUsage(t, restarted)
	if third.UploadBytes != 200 || third.DownloadBytes != 500 || third.Sequence != 3 {
		t.Fatalf("process restart lost or duplicated traffic: %+v", third)
	}
	// Multiple host boots can accumulate without ever connecting to a panel.
	for _, boot := range []string{"boot-b", "boot-c"} {
		source.set(boot, map[string]usageCounters{"2": {Upload: 50, Download: 80}})
		flushUsage(t, restarted)
	}
	final := restarted.snapshot()
	if final.UploadBytes != 300 || final.DownloadBytes != 660 || final.Sequence != 5 || final.MeterEpoch != first.MeterEpoch || final.InstallationID != first.InstallationID {
		t.Fatalf("host restarts must preserve the meter: %+v", final)
	}
	if again := flushUsage(t, restarted); again.UploadBytes != 300 || again.DownloadBytes != 660 {
		t.Fatalf("unchanged counters were counted twice: %+v", again)
	}
}

func TestUsageTracksEachInterfaceAcrossResetRemovalAndAddition(t *testing.T) {
	c, source := newUsageTest(t)
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 100, Download: 100}, "3": {Upload: 500, Download: 500}})
	flushUsage(t, c)
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 10, Download: 20}, "3": {Upload: 600, Download: 800}})
	if value := flushUsage(t, c); value.UploadBytes != 110 || value.DownloadBytes != 320 {
		t.Fatalf("one interface reset affected another: %+v", value)
	}
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 30, Download: 50}, "4": {Upload: 7, Download: 9}})
	if value := flushUsage(t, c); value.UploadBytes != 137 || value.DownloadBytes != 359 {
		t.Fatalf("interface removal/addition changed accumulated totals: %+v", value)
	}
}

func TestUsageReadAndPersistenceFailuresNeverPublishUncommittedTotals(t *testing.T) {
	c, source := newUsageTest(t)
	before := flushUsage(t, c)
	source.err = errors.New("cannot read counters")
	if err := c.flush(); err == nil || !reflect.DeepEqual(c.snapshot(), before) {
		t.Fatal("failed read changed the published counters")
	}
	source.err = nil
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1300, Download: 2700}})
	persist := c.write
	c.write = func(string, []byte, os.FileMode) error { return errors.New("disk full") }
	if err := c.flush(); err == nil || !reflect.DeepEqual(c.snapshot(), before) {
		t.Fatal("failed persistence published new totals")
	}
	stored, err := os.ReadFile(c.path)
	if err != nil {
		t.Fatal(err)
	}
	var disk usageState
	if err := json.Unmarshal(stored, &disk); err != nil || disk.Sequence != before.Sequence {
		t.Fatalf("failed write changed the durable checkpoint: %v", err)
	}
	c.write = persist
	if value := flushUsage(t, c); value.UploadBytes != 300 || value.DownloadBytes != 700 || value.Sequence != 2 {
		t.Fatalf("recovery did not catch up from the durable checkpoint: %+v", value)
	}
}

func TestUsageMissingOrCorruptFileStartsSegmentWithoutChangingMachineIdentity(t *testing.T) {
	for _, corrupt := range []bool{false, true} {
		t.Run(map[bool]string{false: "missing", true: "corrupt"}[corrupt], func(t *testing.T) {
			c, source := newUsageTest(t)
			first := flushUsage(t, c)
			source.set("boot-a", map[string]usageCounters{"2": {Upload: 1100, Download: 2200}})
			flushUsage(t, c)
			if corrupt {
				if err := os.WriteFile(c.path, []byte("{broken"), 0600); err != nil {
					t.Fatal(err)
				}
			} else if err := os.Remove(c.path); err != nil {
				t.Fatal(err)
			}
			next := flushUsage(t, c)
			if next.MeterEpoch == first.MeterEpoch || next.InstallationID == first.InstallationID || next.Sequence != 1 || next.UploadBytes != 0 || next.DownloadBytes != 0 {
				t.Fatalf("missing/corrupt file did not create a zero-based segment: %+v", next)
			}
			if next.HardwareID != first.HardwareID || next.SystemID != first.SystemID {
				t.Fatal("state loss changed the machine/OS identity")
			}
			if corrupt {
				backup, err := os.ReadFile(c.path + ".corrupt")
				if err != nil || string(backup) != "{broken" {
					t.Fatalf("missing corrupt backup: %s %v", backup, err)
				}
				if err := os.WriteFile(c.path, []byte("[]"), 0600); err != nil {
					t.Fatal(err)
				}
				flushUsage(t, c)
				entries, _ := os.ReadDir(filepath.Dir(c.path))
				if len(entries) != 2 {
					t.Fatalf("corrupt recovery accumulated files: %v", entries)
				}
			}
		})
	}
}

func TestUsageFileRollbackCannotRegressLiveDurableTotals(t *testing.T) {
	c, source := newUsageTest(t)
	first := flushUsage(t, c)
	oldFile, err := os.ReadFile(c.path)
	if err != nil {
		t.Fatal(err)
	}
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1200, Download: 2500}})
	flushUsage(t, c)
	if err := os.WriteFile(c.path, oldFile, 0600); err != nil {
		t.Fatal(err)
	}
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1300, Download: 2700}})
	next := flushUsage(t, c)
	if next.MeterEpoch != first.MeterEpoch || next.Sequence != 3 || next.UploadBytes != 300 || next.DownloadBytes != 700 {
		t.Fatalf("file rollback replayed or lost traffic: %+v", next)
	}
}

func TestUsageOnlyStrongIdentityChangesRestartTheMeter(t *testing.T) {
	for _, kind := range []string{"hardware", "system", "mac", "missing"} {
		t.Run(kind, func(t *testing.T) {
			c, source := newUsageTest(t)
			first := flushUsage(t, c)
			source.set("boot-a", map[string]usageCounters{"2": {Upload: 1200, Download: 2500}})
			switch kind {
			case "hardware":
				source.value.Identity.HardwareID = usageFingerprint("hardware", "machine-b")
			case "system":
				source.value.Identity.SystemID = usageFingerprint("system", "os-b")
			case "mac":
				source.value.Identity.MACID = usageFingerprint("mac", "another-mac")
			case "missing":
				source.value.Identity = usageIdentity{}
			}
			next := flushUsage(t, c)
			strong := kind == "hardware" || kind == "system"
			if (next.MeterEpoch != first.MeterEpoch) != strong || next.InstallationID != first.InstallationID {
				t.Fatalf("incorrect identity transition: before=%+v after=%+v", first, next)
			}
			if strong && (next.UploadBytes != 0 || next.Sequence != 1) || !strong && (next.UploadBytes != 200 || next.Sequence != 2) {
				t.Fatalf("incorrect meter transition: %+v", next)
			}
			if kind == "missing" && (next.HardwareID != first.HardwareID || next.SystemID != first.SystemID) {
				t.Fatal("temporarily missing identity erased a known fingerprint")
			}
		})
	}

	c, source := newUsageTest(t)
	source.value.Identity = usageIdentity{}
	first := flushUsage(t, c)
	source.value.Identity.HardwareID = usageFingerprint("hardware", "newly-readable")
	if next := flushUsage(t, c); next.MeterEpoch != first.MeterEpoch || next.HardwareID == "" {
		t.Fatal("a newly readable identity must enrich, not replace, the meter")
	}
}

func TestUsageFingerprintsArePurposeBoundAndNeverUseBootID(t *testing.T) {
	values := map[string]string{
		"/sys/class/dmi/id/product_uuid": "AABBCCDD-1122-3344-5566-778899AABBCC\n",
		"/etc/machine-id":                "AABBCCDD112233445566778899AABBCC\n",
	}
	read := func(path string) ([]byte, error) {
		value, ok := values[path]
		if !ok {
			t.Fatalf("unexpected identity source: %s", path)
		}
		return []byte(value), nil
	}
	macA, _ := net.ParseMAC("02:00:00:00:00:01")
	macB, _ := net.ParseMAC("02:00:00:00:00:02")
	first := readUsageIdentity(read, []net.Interface{{Name: "eth0", HardwareAddr: macA}, {Name: "eth1", HardwareAddr: macB}})
	reordered := readUsageIdentity(read, []net.Interface{{Name: "renamed", HardwareAddr: macB}, {Name: "eth0", HardwareAddr: macA}, {Name: "lo", HardwareAddr: macB}})
	if first != reordered || len(first.HardwareID) != 64 || len(first.SystemID) != 64 || len(first.MACID) != 64 || first.HardwareID == first.SystemID {
		t.Fatalf("identity is not stable/purpose-bound: %+v %+v", first, reordered)
	}
	values["/sys/class/dmi/id/product_uuid"], values["/etc/machine-id"] = strings.Repeat("0", 32), "uninitialized"
	missing := readUsageIdentity(read, nil)
	if missing != (usageIdentity{}) {
		t.Fatalf("invalid identity produced a fingerprint: %+v", missing)
	}
}

func TestUsageReplacementDoesNotInheritAnUnreadableOldSystemIdentity(t *testing.T) {
	c, source := newUsageTest(t)
	flushUsage(t, c)
	source.value.Identity = usageIdentity{HardwareID: usageFingerprint("hardware", "new-machine")}
	replacement := flushUsage(t, c)
	if replacement.SystemID != "" {
		t.Fatal("new machine inherited the old OS fingerprint")
	}
	source.value.Identity.SystemID = usageFingerprint("system", "new-os")
	if recovered := flushUsage(t, c); recovered.MeterEpoch != replacement.MeterEpoch {
		t.Fatal("newly readable identity caused a second replacement")
	}
}

func TestUsageInterfaceSelectionMatchesExistingNonLoPolicy(t *testing.T) {
	interfaces := []net.Interface{{Index: 1, Name: "lo"}, {Index: 2, Name: "eth0"}, {Index: 3, Name: "veth0"}, {Index: 4, Name: "local"}}
	counters := []psnet.IOCountersStat{{Name: "lo", BytesSent: 1000}, {Name: "eth0", BytesSent: 100, BytesRecv: 200}, {Name: "veth0", BytesSent: 50, BytesRecv: 70}, {Name: "local", BytesSent: 3000}}
	got, err := usageInterfaceCounters(interfaces, counters)
	want := map[string]usageCounters{"2": {Upload: 100, Download: 200}, "3": {Upload: 50, Download: 70}}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("counters=%v err=%v", got, err)
	}
	interfaces[1].Name, counters[1].Name = "renamed", "renamed"
	if got, err := usageInterfaceCounters(interfaces, counters); err != nil || !reflect.DeepEqual(got, want) {
		t.Fatal("renaming an interface changed its checkpoint key")
	}
	if _, err := usageInterfaceCounters(nil, counters); err == nil {
		t.Fatal("interface discovery failure must not become zero counters")
	}
	if _, err := usageInterfaceCounters(interfaces, counters[:2]); err == nil {
		t.Fatal("a partial counter read must not remove an existing interface checkpoint")
	}
	if _, err := usageInterfaceCounters(interfaces, nil); err == nil {
		t.Fatal("an empty counter read must not remove existing interface checkpoints")
	}
	if values, err := usageInterfaceCounters([]net.Interface{{Index: 1, Name: "lo"}}, nil); err != nil || len(values) != 0 {
		t.Fatal("excluded loopback interfaces must not require counters")
	}
}

func TestUsagePartialCounterReadPreservesThePreviousBaseline(t *testing.T) {
	c, source := newUsageTest(t)
	interfaces := []net.Interface{{Index: 2, Name: "eth0"}, {Index: 3, Name: "eth1"}}
	counters := []psnet.IOCountersStat{{Name: "eth0", BytesSent: 1000, BytesRecv: 2000}, {Name: "eth1", BytesSent: 3000, BytesRecv: 4000}}
	c.sample = func() (usageSample, error) {
		values, err := usageInterfaceCounters(interfaces, counters)
		return usageSample{BootID: source.value.BootID, Identity: source.value.Identity, Interfaces: values}, err
	}
	baseline := flushUsage(t, c)
	counters = counters[:1]
	if err := c.flush(); err == nil || !reflect.DeepEqual(c.snapshot(), baseline) {
		t.Fatal("partial counter read changed the durable baseline")
	}
	counters = []psnet.IOCountersStat{{Name: "eth0", BytesSent: 1100, BytesRecv: 2200}, {Name: "eth1", BytesSent: 3300, BytesRecv: 4400}}
	if next := flushUsage(t, c); next.UploadBytes != 400 || next.DownloadBytes != 600 || next.Sequence != 2 {
		t.Fatalf("recovered interface replayed its entire boot counter: %+v", next)
	}
}

func TestUsageRunsWhileDisconnectedAndFlushesAtStop(t *testing.T) {
	c, source := newUsageTest(t)
	c.interval = 10 * time.Millisecond
	persisted := make(chan uint64, 20)
	write := c.write
	c.write = func(path string, data []byte, mode os.FileMode) error {
		if err := write(path, data, mode); err != nil {
			return err
		}
		var state usageState
		if err := json.Unmarshal(data, &state); err != nil {
			return err
		}
		persisted <- state.Sequence
		return nil
	}
	c.start(context.Background())
	defer c.stop()
	awaitUsageSequence(t, persisted, 1)
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1400, Download: 2900}})
	deadline := time.After(2 * time.Second)
	for c.snapshot().UploadBytes != 400 {
		select {
		case <-persisted:
		case <-deadline:
			t.Fatal("offline collector did not persist the changed counters")
		}
	}
	if got := c.snapshot(); got.UploadBytes != 400 || got.DownloadBytes != 900 {
		t.Fatalf("offline sampling did not advance: %+v", got)
	}
	source.set("boot-a", map[string]usageCounters{"2": {Upload: 1500, Download: 3100}})
	c.stop()
	if got := c.snapshot(); got.UploadBytes != 500 || got.DownloadBytes != 1100 {
		t.Fatalf("stop did not persist a final sample: %+v", got)
	}
}

func TestUsageCleansOnlyItsOwnTemporaryFiles(t *testing.T) {
	c, _ := newUsageTest(t)
	for _, name := range []string{".vps-usage.json.tmp-old", ".vps-usage.json.corrupt.tmp-old", ".gost.json.tmp-keep", "vps-usage.json.corrupt"} {
		if err := os.WriteFile(filepath.Join(filepath.Dir(c.path), name), []byte("old"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	c.interval = time.Hour
	c.start(context.Background())
	c.stop()
	entries, err := os.ReadDir(filepath.Dir(c.path))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	if !reflect.DeepEqual(names, []string{".gost.json.tmp-keep", "vps-usage.json", "vps-usage.json.corrupt"}) {
		t.Fatalf("cleanup touched unrelated files: %v", names)
	}
}

func TestUsageIsSentImmediatelyWithoutAnyPanelCommand(t *testing.T) {
	frames := make(chan []byte, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, req, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		_, frame, err := conn.ReadMessage()
		if err == nil {
			frames <- frame
		}
		_, _, _ = conn.ReadMessage()
	}))
	defer server.Close()
	reporter := NewWebSocketReporter("ws"+strings.TrimPrefix(server.URL, "http"), "usage-test-secret")
	reporter.usage, _ = newUsageTest(t)
	reporter.pingInterval, reporter.configInterval = time.Hour, time.Hour
	reporter.Start()
	defer reporter.Stop()
	select {
	case frame := <-frames:
		var wrapper struct {
			Data string `json:"data"`
		}
		if err := json.Unmarshal(frame, &wrapper); err != nil {
			t.Fatal(err)
		}
		plain, err := reporter.aesCrypto.Decrypt(wrapper.Data)
		if err != nil {
			t.Fatal(err)
		}
		var info SystemInfo
		if err := json.Unmarshal(plain, &info); err != nil {
			t.Fatal(err)
		}
		if info.VPSUsage == nil || info.VPSUsage.Sequence == 0 || info.VPSUsage.UploadBytes != 0 {
			t.Fatalf("missing immediate usage report: %s", plain)
		}
		if strings.Contains(string(plain), "interfaces") {
			t.Fatal("internal checkpoints leaked into the report")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("node waited for a panel command/ticker before sending identity")
	}
}

func TestUsageReportContainsTheAgreedProtocolFields(t *testing.T) {
	c, _ := newUsageTest(t)
	data, err := json.Marshal(SystemInfo{VPSUsage: flushUsage(t, c)})
	if err != nil {
		t.Fatal(err)
	}
	var report map[string]json.RawMessage
	if err := json.Unmarshal(data, &report); err != nil {
		t.Fatal(err)
	}
	var usage map[string]json.RawMessage
	if err := json.Unmarshal(report["vps_usage"], &usage); err != nil {
		t.Fatal(err)
	}
	var fields []string
	for field := range usage {
		fields = append(fields, field)
	}
	sort.Strings(fields)
	want := []string{"boot_id", "download_bytes", "hardware_id", "installation_id", "mac_id", "meter_epoch", "sequence", "system_id", "upload_bytes"}
	if !reflect.DeepEqual(fields, want) {
		t.Fatalf("unexpected usage protocol fields: %v", fields)
	}
	if string(usage["upload_bytes"]) != "0" || string(usage["download_bytes"]) != "0" || string(usage["sequence"]) != "1" {
		t.Fatalf("incorrect numeric protocol values: %s", report["vps_usage"])
	}
}

func TestRebootFlushesUsageAfterAckAndBeforeExecution(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(map[bool]string{false: "flush succeeds", true: "flush fails"}[fail], func(t *testing.T) {
			c, source := newUsageTest(t)
			flushUsage(t, c)
			source.set("boot-a", map[string]usageCounters{"2": {Upload: 1800, Download: 3000}})
			r := newNodeRebooter()
			events := make(chan string, 4)
			r.wait = func(context.Context) error { return nil }
			r.before = func() error {
				events <- "flush"
				if fail {
					return errors.New("cannot save usage")
				}
				return c.flush()
			}
			r.prepare = func() (func(context.Context) error, error) {
				return func(context.Context) error { events <- "execute"; return nil }, nil
			}
			r.handle(context.Background(), CommandMessage{RequestId: "flush-before-reboot"}, func(response CommandResponse) error {
				if response.Success {
					events <- "ack"
				} else {
					events <- "failed"
				}
				return nil
			})
			want := []string{"ack", "flush", "execute"}
			if fail {
				want[2] = "failed"
			}
			for _, expected := range want {
				select {
				case actual := <-events:
					if actual != expected {
						t.Fatalf("got %s, want %s", actual, expected)
					}
				case <-time.After(time.Second):
					t.Fatal("reboot hook did not complete")
				}
			}
			if !fail && c.snapshot().UploadBytes != 800 {
				t.Fatal("execution started without durable final traffic")
			}
		})
	}
}

func awaitUsageSequence(t *testing.T, values <-chan uint64, minimum uint64) {
	t.Helper()
	timeout := time.After(2 * time.Second)
	for {
		select {
		case value := <-values:
			if value >= minimum {
				return
			}
		case <-timeout:
			t.Fatal("independent collector did not persist another sample")
		}
	}
}
