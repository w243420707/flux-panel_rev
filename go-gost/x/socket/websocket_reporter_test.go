package socket

import "testing"

func TestBuildWebSocketURL(t *testing.T) {
	tests := []struct {
		name    string
		addr    string
		wantURL string
	}{
		{
			name:    "https panel uses wss",
			addr:    "https://zf.114431.xyz",
			wantURL: "wss://zf.114431.xyz/system-info?secret=secret&type=1&version=1.2.0",
		},
		{
			name:    "bare host keeps ws fallback",
			addr:    "zf.114431.xyz",
			wantURL: "ws://zf.114431.xyz/system-info?secret=secret&type=1&version=1.2.0",
		},
		{
			name:    "http panel keeps explicit port",
			addr:    "http://127.0.0.1:6366",
			wantURL: "ws://127.0.0.1:6366/system-info?secret=secret&type=1&version=1.2.0",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := buildWebSocketURL(tt.addr, "secret", "1.2.0")
			if got != tt.wantURL {
				t.Fatalf("buildWebSocketURL() = %q, want %q", got, tt.wantURL)
			}
		})
	}
}
