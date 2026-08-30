package socket

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/go-gost/x/config"
)

var configSaveMu sync.Mutex

// saveConfig writes the current runtime configuration without ever exposing a
// partially written gost.json to the parser or the next process start.
func saveConfig() error {
	configSaveMu.Lock()
	defer configSaveMu.Unlock()

	var data []byte
	if err := config.OnUpdate(func(current *config.Config) error {
		var buf bytes.Buffer
		encoder := json.NewEncoder(&buf)
		encoder.SetIndent("", "  ")
		if err := encoder.Encode(current); err != nil {
			return err
		}

		data = append(data[:0], buf.Bytes()...)
		if len(bytes.TrimSpace(data)) == 0 || !json.Valid(bytes.TrimSpace(data)) {
			return fmt.Errorf("generated configuration is not valid JSON")
		}
		return nil
	}); err != nil {
		return fmt.Errorf("serialize runtime configuration: %w", err)
	}

	if err := config.WriteFileAtomically("gost.json", data, 0600); err != nil {
		return fmt.Errorf("persist runtime configuration: %w", err)
	}

	return nil
}
