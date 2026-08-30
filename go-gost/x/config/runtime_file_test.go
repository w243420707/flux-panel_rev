package config

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestEnsureRuntimeFileCreatesMissingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gost.json")
	backup, recovered, err := EnsureRuntimeFile(path)
	if err != nil {
		t.Fatalf("EnsureRuntimeFile() error = %v", err)
	}
	if backup != "" || recovered {
		t.Fatalf("missing file should be initialized without recovery: backup=%q recovered=%v", backup, recovered)
	}
	assertValidJSONObject(t, path)
}

func TestEnsureRuntimeFileBacksUpDamagedFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gost.json")
	damaged := []byte("{\n")
	if err := os.WriteFile(path, damaged, 0600); err != nil {
		t.Fatalf("write damaged config: %v", err)
	}

	backup, recovered, err := EnsureRuntimeFile(path)
	if err != nil {
		t.Fatalf("EnsureRuntimeFile() error = %v", err)
	}
	if !recovered || backup != path+".corrupt" {
		t.Fatalf("backup=%q recovered=%v", backup, recovered)
	}

	backupData, err := os.ReadFile(backup)
	if err != nil {
		t.Fatalf("read damaged backup: %v", err)
	}
	if !bytes.Equal(backupData, damaged) {
		t.Fatalf("backup = %q, want %q", backupData, damaged)
	}
	assertValidJSONObject(t, path)
}

func TestEnsureRuntimeFileRejectsNonObjectJSON(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gost.json")
	if err := os.WriteFile(path, []byte("[]\n"), 0600); err != nil {
		t.Fatalf("write invalid top-level config: %v", err)
	}

	_, recovered, err := EnsureRuntimeFile(path)
	if err != nil {
		t.Fatalf("EnsureRuntimeFile() error = %v", err)
	}
	if !recovered {
		t.Fatal("non-object JSON should be recovered")
	}
	assertValidJSONObject(t, path)
}

func TestWriteFileAtomicallyReplacesExistingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gost.json")
	if err := os.WriteFile(path, []byte("old"), 0600); err != nil {
		t.Fatalf("write existing file: %v", err)
	}
	if err := WriteFileAtomically(path, []byte("{}\n"), 0600); err != nil {
		t.Fatalf("WriteFileAtomically() error = %v", err)
	}
	assertValidJSONObject(t, path)
}

func assertValidJSONObject(t *testing.T, path string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if !json.Valid(bytes.TrimSpace(data)) || !isJSONObject(data) {
		t.Fatalf("%s is not a valid JSON object: %q", path, data)
	}
}
