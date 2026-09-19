package socket

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/core/logger"
)

// A reboot belongs to the host, so it must not run through the configuration
// command handlers or accept command text from the panel.
type nodeRebooter struct {
	mu        sync.Mutex
	requestID string
	pending   bool
	response  CommandResponse
	prepare   func() (func(context.Context) error, error)
	wait      func(context.Context) error
}

func newNodeRebooter() *nodeRebooter {
	return &nodeRebooter{
		prepare: prepareHostReboot,
		wait: func(ctx context.Context) error {
			timer := time.NewTimer(time.Second)
			defer timer.Stop()
			select {
			case <-timer.C:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		},
	}
}

func (r *nodeRebooter) handle(ctx context.Context, cmd CommandMessage, send func(CommandResponse) error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	response := CommandResponse{Type: "RebootNodeResponse", RequestId: cmd.RequestId}
	reject := func(message string) {
		response.Message = message
		logger.Default().Warnf("拒绝节点重启 requestId=%s: %s", cmd.RequestId, message)
		if err := send(response); err != nil {
			logger.Default().Errorf("发送节点重启拒绝响应失败 requestId=%s: %v", cmd.RequestId, err)
		}
	}
	if strings.TrimSpace(cmd.RequestId) == "" {
		reject("重启请求缺少 requestId")
		return
	}
	if cmd.Data != nil {
		data, ok := cmd.Data.(map[string]interface{})
		if !ok || len(data) != 0 {
			reject("重启指令不接受命令或参数")
			return
		}
	}
	if cmd.RequestId == r.requestID {
		if err := send(r.response); err != nil {
			logger.Default().Errorf("重发节点重启回执失败 requestId=%s: %v", cmd.RequestId, err)
		}
		return
	}
	if r.pending {
		reject("节点正在重启，请勿重复操作")
		return
	}
	execute, err := r.prepare()
	if err != nil {
		reject(err.Error())
		return
	}
	response.Success = true
	response.Message = "OK"
	response.Data = map[string]string{"status": "accepted"}
	if err := send(response); err != nil {
		logger.Default().Errorf("节点重启回执发送失败，未执行重启 requestId=%s: %v", cmd.RequestId, err)
		return
	}
	r.requestID, r.pending, r.response = cmd.RequestId, true, response
	logger.Default().Infof("已接受 VPS 重启，将执行 systemctl reboot requestId=%s", cmd.RequestId)
	go r.execute(ctx, cmd.RequestId, execute, send)
}

func (r *nodeRebooter) execute(ctx context.Context, requestID string, execute func(context.Context) error, send func(CommandResponse) error) {
	err := r.wait(ctx)
	if err == nil {
		err = execute(ctx)
	}
	if err == nil {
		// systemctl returning only confirms that systemd accepted the operation.
		// Keep the guard until this process exits; it does not prove a reboot yet.
		logger.Default().Infof("systemd 已接受 VPS 重启 requestId=%s", requestID)
		return
	}

	message := fmt.Sprintf("重启执行失败：%v", err)
	response := CommandResponse{
		Type:      "RebootNodeStatus",
		RequestId: requestID,
		Success:   false,
		Message:   message,
		Data:      map[string]string{"status": "failed"},
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pending = false
	r.response = response
	r.response.Type = "RebootNodeResponse"
	logger.Default().Errorf("%s requestId=%s", message, requestID)
	if err := send(response); err != nil {
		logger.Default().Errorf("回报节点重启失败状态失败 requestId=%s: %v", requestID, err)
	}
}

func prepareHostReboot() (func(context.Context) error, error) {
	command, err := hostRebootCommand(runtime.GOOS, os.Geteuid(), os.Stat, os.ReadFile)
	if err != nil {
		return nil, err
	}
	return func(ctx context.Context) error {
		return runHostReboot(ctx, command, func(ctx context.Context, path string, args ...string) ([]byte, error) {
			return exec.CommandContext(ctx, path, args...).CombinedOutput()
		})
	}, nil
}

func hostRebootCommand(goos string, uid int, stat func(string) (os.FileInfo, error), readFile func(string) ([]byte, error)) (string, error) {
	if goos != "linux" {
		return "", fmt.Errorf("仅支持重启 Linux VPS")
	}
	if uid != 0 {
		return "", fmt.Errorf("节点需要以 root 身份运行才能重启 VPS")
	}
	for _, marker := range []string{"/.dockerenv", "/run/.containerenv", "/run/systemd/container"} {
		if _, err := stat(marker); err == nil {
			return "", fmt.Errorf("容器内节点不支持重启宿主 VPS")
		} else if !os.IsNotExist(err) {
			return "", fmt.Errorf("无法检查节点运行环境：%w", err)
		}
	}
	info, err := stat("/run/systemd/system")
	if err != nil || !info.IsDir() {
		return "", fmt.Errorf("节点仅支持通过 systemd 重启 VPS")
	}
	initName, err := readFile("/proc/1/comm")
	if err != nil || strings.TrimSpace(string(initName)) != "systemd" {
		return "", fmt.Errorf("节点仅支持重启由 systemd 管理的 VPS")
	}
	for _, path := range []string{"/usr/bin/systemctl", "/bin/systemctl"} {
		if info, err := stat(path); err == nil && info.Mode().IsRegular() && info.Mode().Perm()&0111 != 0 {
			return path, nil
		}
	}
	return "", fmt.Errorf("节点未找到可执行的 systemctl，无法重启 VPS")
}

func runHostReboot(ctx context.Context, command string, run func(context.Context, string, ...string) ([]byte, error)) error {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	output, err := run(ctx, command, "reboot")
	if err != nil {
		detail := strings.TrimSpace(string(output))
		if len(detail) > 512 {
			detail = detail[:512]
		}
		if detail != "" {
			return fmt.Errorf("systemctl reboot: %w (%s)", err, detail)
		}
		return fmt.Errorf("systemctl reboot: %w", err)
	}
	return nil
}
