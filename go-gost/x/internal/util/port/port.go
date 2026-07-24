package port

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func ForceClosePort(port int) error {
	if port <= 0 || port > 65535 {
		return nil
	}
	if err := killProcessesByPort(port); err != nil {
		return err
	}
	return ForceClosePortConnections(fmt.Sprintf(":%d", port))
}

func ForceClosePortConnections(addr string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			fmt.Printf("⚠️ ForceClosePortConnections panic recovered: %v\n", r)
			err = nil // 永远返回 nil
		}
	}()

	if addr == "" {
		fmt.Println("⚠️ 地址为空")
		return nil
	}

	if strings.HasPrefix(addr, ":") {
		addr = "0.0.0.0" + addr
	}

	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		fmt.Printf("⚠️ 地址解析失败: %v\n", err)
		return nil
	}

	port, err := strconv.Atoi(portStr)
	if err != nil {
		fmt.Printf("⚠️ 端口非法: %v\n", err)
		return nil
	}

	cmd := exec.Command("tcpkill", "-i", "any", "port", fmt.Sprintf("%d", port))
	if err := cmd.Start(); err != nil {
		fmt.Printf("⚠️ 启动 tcpkill 失败: %v\n", err)
		return nil
	}

	go func() {
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("⚠️ tcpkill goroutine panic recovered: %v\n", r)
			}
		}()
		time.Sleep(2 * time.Second)
		if cmd.Process != nil {
			if err := cmd.Process.Kill(); err != nil {
				fmt.Printf("⚠️ 终止 tcpkill 失败: %v\n", err)
			}
		}
	}()

	fmt.Printf("✅ 正在断开端口 %d 上的所有连接...\n", port)
	return nil
}

func killProcessesByPort(port int) error {
	inodes, err := socketInodesByPort(port)
	if err != nil {
		return err
	}
	if len(inodes) == 0 {
		return nil
	}

	pids, err := pidsBySocketInodes(inodes)
	if err != nil {
		return err
	}

	currentPid := os.Getpid()
	for pid := range pids {
		if pid == currentPid {
			continue
		}
		process, err := os.FindProcess(pid)
		if err != nil {
			return err
		}
		if err := process.Kill(); err != nil {
			return err
		}
	}
	return nil
}

func socketInodesByPort(port int) (map[string]struct{}, error) {
	inodes := make(map[string]struct{})
	files := []struct {
		path          string
		requireListen bool
	}{
		{"/proc/net/tcp", true},
		{"/proc/net/tcp6", true},
		{"/proc/net/udp", false},
		{"/proc/net/udp6", false},
	}

	for _, file := range files {
		if err := collectSocketInodes(file.path, port, file.requireListen, inodes); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, err
		}
	}
	return inodes, nil
}

func collectSocketInodes(path string, port int, requireListen bool, inodes map[string]struct{}) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	if scanner.Scan() {
		// Skip header.
	}
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			continue
		}
		if requireListen && fields[3] != "0A" {
			continue
		}
		if !localAddressUsesPort(fields[1], port) {
			continue
		}
		inodes[fields[9]] = struct{}{}
	}
	return scanner.Err()
}

func localAddressUsesPort(localAddress string, port int) bool {
	parts := strings.Split(localAddress, ":")
	if len(parts) < 2 {
		return false
	}
	socketPort, err := strconv.ParseInt(parts[len(parts)-1], 16, 32)
	return err == nil && int(socketPort) == port
}

func pidsBySocketInodes(inodes map[string]struct{}) (map[int]struct{}, error) {
	pids := make(map[int]struct{})
	entries, err := os.ReadDir("/proc")
	if err != nil {
		if os.IsNotExist(err) {
			return pids, nil
		}
		return nil, err
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		fdDir := filepath.Join("/proc", entry.Name(), "fd")
		fds, err := os.ReadDir(fdDir)
		if err != nil {
			continue
		}
		for _, fd := range fds {
			target, err := os.Readlink(filepath.Join(fdDir, fd.Name()))
			if err != nil {
				continue
			}
			inode, ok := parseSocketInode(target)
			if !ok {
				continue
			}
			if _, exists := inodes[inode]; exists {
				pids[pid] = struct{}{}
				break
			}
		}
	}
	return pids, nil
}

func parseSocketInode(target string) (string, bool) {
	if !strings.HasPrefix(target, "socket:[") || !strings.HasSuffix(target, "]") {
		return "", false
	}
	return strings.TrimSuffix(strings.TrimPrefix(target, "socket:["), "]"), true
}
