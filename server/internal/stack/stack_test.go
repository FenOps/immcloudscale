package stack

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestComposeWithAndWithoutML(t *testing.T) {
	p := Params{DataDir: "/srv/ics", ImmichPort: 2283, NextcloudPort: 8080, MachineLearning: true}
	on, _ := Compose(p)
	p.MachineLearning = false
	off, _ := Compose(p)
	if !strings.Contains(on, "immich-machine-learning:") || !strings.Contains(on, "model-cache:") || !strings.Contains(on, "IMMICH_HOST: 0.0.0.0") {
		t.Fatal("ML service missing")
	}
	if strings.Contains(off, "immich-machine-learning:") || !strings.Contains(off, `IMMICH_MACHINE_LEARNING_ENABLED: "false"`) {
		t.Fatal("ML should be disabled")
	}
	for _, want := range []string{"nextcloud-cron:", "POSTGRES_DB: nextcloud", "${DATA_DIR}/photos:/data", "./initdb:/docker-entrypoint-initdb.d:ro"} {
		if !strings.Contains(on, want) {
			t.Errorf("missing %q", want)
		}
	}
}

func TestWritePermissions(t *testing.T) {
	dir := t.TempDir()
	p := Params{DataDir: filepath.Join(dir, "data"), ImmichPort: 1, NextcloudPort: 2, DBPassword: "x", TrustedDomains: []string{"a", "b"}}
	if err := Write(filepath.Join(dir, "stack"), p); err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(filepath.Join(dir, "stack", "initdb"))
	if fi.Mode().Perm()&0o005 != 0o005 {
		t.Fatalf("initdb must be world-readable for the postgres user, got %v", fi.Mode())
	}
	env, _ := os.ReadFile(filepath.Join(dir, "stack", ".env"))
	if !strings.Contains(string(env), "NC_TRUSTED_DOMAINS=a b") {
		t.Fatal(string(env))
	}
	fi, _ = os.Stat(filepath.Join(dir, "stack", ".env"))
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf(".env holds passwords; got %v", fi.Mode())
	}
}

func TestParsePS(t *testing.T) {
	lines := `{"Service":"database","State":"running","Health":"","Status":"Up 2 minutes"}
{"Service":"nextcloud","State":"running","Health":"","Status":"Up 1 minute"}`
	if s := ParsePS([]byte(lines)); len(s) != 2 || s[1].Name != "nextcloud" {
		t.Fatalf("%+v", s)
	}
	if s := ParsePS([]byte(`[{"Service":"redis","State":"running"}]`)); len(s) != 1 || s[0].State != "running" {
		t.Fatalf("%+v", s)
	}
}
