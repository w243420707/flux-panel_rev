package service

import "testing"

func TestBuildReportURL(t *testing.T) {
	tests := []struct {
		name    string
		addr    string
		path    string
		wantURL string
	}{
		{
			name:    "https panel keeps https",
			addr:    "https://zf.114431.xyz",
			path:    "/flow/upload",
			wantURL: "https://zf.114431.xyz/flow/upload?secret=secret",
		},
		{
			name:    "bare host keeps http fallback",
			addr:    "zf.114431.xyz",
			path:    "/flow/config",
			wantURL: "http://zf.114431.xyz/flow/config?secret=secret",
		},
		{
			name:    "explicit port is preserved",
			addr:    "http://127.0.0.1:6366",
			path:    "/flow/upload",
			wantURL: "http://127.0.0.1:6366/flow/upload?secret=secret",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := buildReportURL(tt.addr, tt.path, "secret")
			if got != tt.wantURL {
				t.Fatalf("buildReportURL() = %q, want %q", got, tt.wantURL)
			}
		})
	}
}
