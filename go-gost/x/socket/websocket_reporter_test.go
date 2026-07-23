package socket

import "testing"

func TestBuildWebSocketURL(t *testing.T) {
	tests := []struct {
		name     string
		addr     string
		publicIp string
		wantURL  string
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
		{
			name:     "public ip is appended",
			addr:     "https://zf.114431.xyz",
			publicIp: "1.2.3.4",
			wantURL:  "wss://zf.114431.xyz/system-info?publicIp=1.2.3.4&secret=secret&type=1&version=1.2.0",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := buildWebSocketURL(tt.addr, "secret", "1.2.0", tt.publicIp)
			if got != tt.wantURL {
				t.Fatalf("buildWebSocketURL() = %q, want %q", got, tt.wantURL)
			}
		})
	}
}

func TestParsePublicIPResponse(t *testing.T) {
	tests := []struct {
		name string
		body string
		want string
	}{
		{
			name: "plain ip",
			body: "1.2.3.4\n",
			want: "1.2.3.4",
		},
		{
			name: "trace format",
			body: "fl=12345\nip=8.8.8.8\nh=example\n",
			want: "8.8.8.8",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parsePublicIPResponse(tt.body)
			if got != tt.want {
				t.Fatalf("parsePublicIPResponse() = %q, want %q", got, tt.want)
			}
		})
	}
}
