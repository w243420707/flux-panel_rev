package socket

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"testing/fstest"
	"time"

	"github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
	"github.com/gorilla/websocket"
)

func TestMain(m *testing.M) {
	logger.SetDefault(xlogger.Nop())
	os.Exit(m.Run())
}

func TestRebootAcknowledgesBeforeDelayedExecution(t *testing.T) {
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
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	reporter := NewWebSocketReporter(server.URL, "test-node-secret")
	reporter.conn, reporter.connected = conn, true
	defer reporter.Stop()

	release := make(chan struct{})
	executed := make(chan struct{}, 1)
	reporter.rebooter.prepare = func() (func(context.Context) error, error) {
		return func(context.Context) error {
			executed <- struct{}{}
			return nil
		}, nil
	}
	reporter.rebooter.wait = func(context.Context) error { <-release; return nil }

	// The route must return without waiting for reboot and skip saveConfig.
	routed := make(chan struct{})
	go func() {
		reporter.routeCommand(CommandMessage{Type: "RebootNode", RequestId: "reboot-1"})
		close(routed)
	}()
	awaitSignal(t, routed)
	var frame []byte
	select {
	case frame = <-frames:
	case <-time.After(2 * time.Second):
		t.Fatal("reboot acknowledgement was not received")
	}
	var wrapper struct {
		Encrypted bool   `json:"encrypted"`
		Data      string `json:"data"`
	}
	if err := json.Unmarshal(frame, &wrapper); err != nil || !wrapper.Encrypted {
		t.Fatalf("expected encrypted acknowledgement, frame=%s err=%v", frame, err)
	}
	payload, err := reporter.aesCrypto.Decrypt(wrapper.Data)
	if err != nil {
		t.Fatal(err)
	}
	var response CommandResponse
	if err := json.Unmarshal(payload, &response); err != nil {
		t.Fatal(err)
	}
	if !response.Success || response.Type != "RebootNodeResponse" || response.RequestId != "reboot-1" || response.Message != "OK" {
		t.Fatalf("unexpected acknowledgement: %+v", response)
	}
	assertNotExecuted(t, executed)
	close(release)
	awaitSignal(t, executed)
}

func TestRebootDoesNotExecuteWhenAcknowledgementFails(t *testing.T) {
	reporter := NewWebSocketReporter("ws://unused", "test-node-secret")
	defer reporter.Stop()
	executed := make(chan struct{}, 1)
	reporter.rebooter.prepare = func() (func(context.Context) error, error) {
		return func(context.Context) error { executed <- struct{}{}; return nil }, nil
	}
	reporter.rebooter.wait = func(context.Context) error { return nil }
	// sendResponse has no connection, so the request must never be scheduled.
	reporter.routeCommand(CommandMessage{Type: "RebootNode", RequestId: "no-ack"})
	if reporter.rebooter.pending || reporter.rebooter.requestID != "" {
		t.Fatal("failed acknowledgement left a pending reboot")
	}
	assertNotExecuted(t, executed)
}

func TestRebootRejectsDuplicateExecution(t *testing.T) {
	r := newNodeRebooter()
	release := make(chan struct{})
	executed := make(chan struct{}, 2)
	r.prepare = func() (func(context.Context) error, error) {
		return func(context.Context) error { executed <- struct{}{}; return nil }, nil
	}
	r.wait = func(context.Context) error { <-release; return nil }
	var responses []CommandResponse
	send := func(response CommandResponse) error { responses = append(responses, response); return nil }
	for _, id := range []string{"request-1", "request-1", "request-2"} {
		r.handle(context.Background(), CommandMessage{RequestId: id}, send)
	}
	if len(responses) != 3 || !responses[0].Success || !responses[1].Success || responses[2].Success {
		t.Fatalf("unexpected duplicate responses: %+v", responses)
	}
	close(release)
	awaitSignal(t, executed)
	r.handle(context.Background(), CommandMessage{RequestId: "request-3"}, send)
	if responses[3].Success {
		t.Fatal("accepted another reboot after systemd already accepted the operation")
	}
	assertNotExecuted(t, executed)
}

func TestRebootReportsExecutionFailureAndAllowsNewRequest(t *testing.T) {
	r := newNodeRebooter()
	var calls atomic.Int32
	r.prepare = func() (func(context.Context) error, error) {
		return func(context.Context) error {
			calls.Add(1)
			return errors.New("Access denied")
		}, nil
	}
	r.wait = func(context.Context) error { return nil }
	responses := make(chan CommandResponse, 8)
	send := func(response CommandResponse) error { responses <- response; return nil }
	r.handle(context.Background(), CommandMessage{RequestId: "failed-request"}, send)
	ack, failure := awaitResponse(t, responses), awaitResponse(t, responses)
	if !ack.Success || failure.Success || failure.Type != "RebootNodeStatus" || failure.RequestId != "failed-request" || !strings.Contains(failure.Message, "Access denied") {
		t.Fatalf("unexpected failure lifecycle: ack=%+v failure=%+v", ack, failure)
	}
	if failure.Data.(map[string]string)["status"] != "failed" {
		t.Fatalf("missing failure state: %+v", failure)
	}
	r.handle(context.Background(), CommandMessage{RequestId: "failed-request"}, send)
	if replay := awaitResponse(t, responses); replay.Success || replay.Type != "RebootNodeResponse" || calls.Load() != 1 {
		t.Fatalf("replayed failed request executed again: %+v calls=%d", replay, calls.Load())
	}
	r.handle(context.Background(), CommandMessage{RequestId: "new-request"}, send)
	if response := awaitResponse(t, responses); !response.Success {
		t.Fatalf("could not accept a new request after failure: %+v", response)
	}
	awaitResponse(t, responses)
	if calls.Load() != 2 {
		t.Fatalf("got %d execution attempts, want 2", calls.Load())
	}
}

func TestRebootCancellationDoesNotExecute(t *testing.T) {
	r := newNodeRebooter()
	executed := make(chan struct{}, 1)
	r.prepare = func() (func(context.Context) error, error) {
		return func(context.Context) error { executed <- struct{}{}; return nil }, nil
	}
	responses := make(chan CommandResponse, 2)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r.handle(ctx, CommandMessage{RequestId: "cancelled"}, func(response CommandResponse) error {
		responses <- response
		return nil
	})
	awaitResponse(t, responses)
	if response := awaitResponse(t, responses); response.Success || !strings.Contains(response.Message, "context canceled") {
		t.Fatalf("unexpected cancelled reboot status: %+v", response)
	}
	assertNotExecuted(t, executed)
}

func TestRebootRejectsInvalidRequestAndUnsupportedEnvironment(t *testing.T) {
	for _, tc := range []struct {
		name    string
		command CommandMessage
		prepare error
	}{
		{name: "missing request ID", command: CommandMessage{}},
		{name: "command text", command: CommandMessage{RequestId: "1", Data: "reboot; rm -rf /"}},
		{name: "arbitrary parameters", command: CommandMessage{RequestId: "1", Data: map[string]interface{}{"command": "reboot"}}},
		{name: "unsupported environment", command: CommandMessage{RequestId: "1", Data: map[string]interface{}{}}, prepare: errors.New("root required")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := newNodeRebooter()
			r.prepare = func() (func(context.Context) error, error) {
				if tc.prepare == nil {
					t.Fatal("invalid command reached host preparation")
				}
				return nil, tc.prepare
			}
			r.handle(context.Background(), tc.command, func(response CommandResponse) error {
				if response.Success || response.Type != "RebootNodeResponse" || response.Message == "" {
					t.Fatalf("unexpected rejection: %+v", response)
				}
				return nil
			})
			if r.pending {
				t.Fatal("invalid request was scheduled")
			}
		})
	}
}

func TestHostRebootCommandRequiresLinuxRootSystemd(t *testing.T) {
	for _, tc := range []struct {
		name   string
		goos   string
		uid    int
		change func(fstest.MapFS)
		want   string
		err    string
	}{
		{name: "standard Linux host", goos: "linux", want: "/usr/bin/systemctl"},
		{name: "non Linux", goos: "windows", err: "Linux"},
		{name: "non root", goos: "linux", uid: 1000, err: "root"},
		{name: "no systemd", goos: "linux", change: func(f fstest.MapFS) { delete(f, "run/systemd/system") }, err: "systemd"},
		{name: "systemd is not init", goos: "linux", change: func(f fstest.MapFS) { f["proc/1/comm"].Data = []byte("init\n") }, err: "systemd"},
		{name: "Docker", goos: "linux", change: func(f fstest.MapFS) { f[".dockerenv"] = &fstest.MapFile{} }, err: "容器"},
		{name: "Podman", goos: "linux", change: func(f fstest.MapFS) { f["run/.containerenv"] = &fstest.MapFile{} }, err: "容器"},
		{name: "systemd container", goos: "linux", change: func(f fstest.MapFS) { f["run/systemd/container"] = &fstest.MapFile{} }, err: "容器"},
		{name: "missing systemctl", goos: "linux", change: func(f fstest.MapFS) { delete(f, "usr/bin/systemctl") }, err: "systemctl"},
		{name: "non executable systemctl", goos: "linux", change: func(f fstest.MapFS) { f["usr/bin/systemctl"].Mode = 0644 }, err: "systemctl"},
		{name: "alternate fixed systemctl path", goos: "linux", change: func(f fstest.MapFS) { f["bin/systemctl"] = f["usr/bin/systemctl"]; delete(f, "usr/bin/systemctl") }, want: "/bin/systemctl"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files := fstest.MapFS{
				"run/systemd/system": {Mode: fs.ModeDir | 0755},
				"proc/1/comm":        {Data: []byte("systemd\n")},
				"usr/bin/systemctl":  {Mode: 0755},
			}
			if tc.change != nil {
				tc.change(files)
			}
			command, err := hostRebootCommand(tc.goos, tc.uid,
				func(path string) (os.FileInfo, error) { return fs.Stat(files, strings.TrimPrefix(path, "/")) },
				func(path string) ([]byte, error) { return fs.ReadFile(files, strings.TrimPrefix(path, "/")) },
			)
			if tc.err != "" {
				if err == nil || !strings.Contains(err.Error(), tc.err) {
					t.Fatalf("got command=%q err=%v, want error containing %q", command, err, tc.err)
				}
			} else if err != nil || command != tc.want {
				t.Fatalf("got command=%q err=%v, want %q", command, err, tc.want)
			}
		})
	}
}

func TestRunHostRebootUsesFixedArgumentsAndReportsSystemdError(t *testing.T) {
	err := runHostReboot(context.Background(), "/usr/bin/systemctl", func(ctx context.Context, command string, args ...string) ([]byte, error) {
		if command != "/usr/bin/systemctl" || !reflect.DeepEqual(args, []string{"reboot"}) {
			t.Fatalf("unexpected reboot command: %s %v", command, args)
		}
		if _, ok := ctx.Deadline(); !ok {
			t.Fatal("reboot execution has no time limit")
		}
		return []byte("Failed to connect to bus"), errors.New("exit status 1")
	})
	if err == nil || !strings.Contains(err.Error(), "Failed to connect to bus") {
		t.Fatalf("systemd failure was lost: %v", err)
	}
}

func awaitSignal(t *testing.T, ch <-chan struct{}) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for test operation")
	}
}

func assertNotExecuted(t *testing.T, ch <-chan struct{}) {
	t.Helper()
	select {
	case <-ch:
		t.Fatal("reboot executed unexpectedly")
	default:
	}
}

func awaitResponse(t *testing.T, responses <-chan CommandResponse) CommandResponse {
	t.Helper()
	select {
	case response := <-responses:
		return response
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for reboot response")
		return CommandResponse{}
	}
}
