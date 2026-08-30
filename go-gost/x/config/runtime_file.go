package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// EnsureRuntimeFile prevents a damaged gost.json from trapping systemd in a
// restart loop. The last damaged file is retained at path + ".corrupt".
func EnsureRuntimeFile(path string) (backupPath string, recovered bool, err error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", false, WriteFileAtomically(path, []byte("{}\n"), 0600)
	}
	if err != nil {
		return "", false, fmt.Errorf("read runtime config %s: %w", path, err)
	}

	if isJSONObject(data) {
		return "", false, nil
	}

	backupPath = path + ".corrupt"
	if err := os.Remove(backupPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", false, fmt.Errorf("remove previous damaged config backup %s: %w", backupPath, err)
	}
	if err := os.Rename(path, backupPath); err != nil {
		return "", false, fmt.Errorf("backup damaged runtime config %s: %w", path, err)
	}
	if err := WriteFileAtomically(path, []byte("{}\n"), 0600); err != nil {
		_ = os.Rename(backupPath, path)
		return "", false, fmt.Errorf("restore runtime config %s: %w", path, err)
	}

	return backupPath, true, nil
}

// WriteFileAtomically writes and fsyncs a temporary file before replacing the
// target. A process interruption can leave a temp file, but never a truncated
// target configuration.
func WriteFileAtomically(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	temp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}

	tempPath := temp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tempPath)
		}
	}()

	if err := temp.Chmod(mode); err != nil {
		_ = temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tempPath, path); err != nil {
		return err
	}
	cleanup = false

	if dirFile, err := os.Open(dir); err == nil {
		_ = dirFile.Sync()
		_ = dirFile.Close()
	}

	return nil
}

func isJSONObject(data []byte) bool {
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) == 0 || !json.Valid(trimmed) {
		return false
	}

	var object map[string]json.RawMessage
	return json.Unmarshal(trimmed, &object) == nil && object != nil
}
