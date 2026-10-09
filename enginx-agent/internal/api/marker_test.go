package api

import (
	"context"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/xiidea/enginx/enginx-agent/internal/nginx"
)

// listenLikeASharedHost answers like NGINX on a host that already ran it: the platform's site
// answers the marker path with its marker, and every other name gets the distribution's default
// page, with a 200.
func listenLikeASharedHost(t *testing.T, siteName, marker string) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Host == siteName && r.URL.Path == SiteMarkerPath {
				_, _ = w.Write([]byte(marker + "\n"))
				return
			}
			_, _ = w.Write([]byte("<title>Welcome to nginx!</title>"))
		}),
		ReadHeaderTimeout: time.Second,
	}
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(func() { _ = server.Close() })
	return listener.Addr().(*net.TCPAddr).Port
}

// A default page answering 200 is not the site. Only the marker proves the deployed configuration
// is what answered.
func TestVerifyIdentifiesTheSiteByItsMarker(t *testing.T) {
	port := listenLikeASharedHost(t, "app.example.com", "site-1")

	body := verifyRequest{Port: port, Sites: []verifyTarget{
		{ServerName: "app.example.com", Marker: "site-1"},
		{ServerName: "missing.example.com", Marker: "site-2"},
	}}
	var decoded verifyResponse
	decode(t, postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body), &decoded)

	byName := map[string]verifyResult{}
	for _, r := range decoded.Results {
		byName[r.ServerName] = r
	}
	if r := byName["app.example.com"]; !r.Responded || !r.Identified {
		t.Errorf("the deployed site must be identified, got %+v", r)
	}
	if r := byName["missing.example.com"]; !r.Responded || r.Identified || r.Marker != "" {
		t.Errorf("a default page answering 200 must not identify a site, nor be reported as a marker, got %+v", r)
	}
	if r := byName["app.example.com"]; r.Marker != "site-1" {
		t.Errorf("the marker seen must be reported, got %q", r.Marker)
	}
}

// After an update the old config answers with the same site but an earlier marker. It must not
// count, and what it said must come back so the platform can name it.
func TestVerifyReportsAnOlderMarkerOfTheSameSite(t *testing.T) {
	port := listenLikeASharedHost(t, "app.example.com", "site-1 v11 aaaaaaaaaaaa")

	body := verifyRequest{Port: port, Sites: []verifyTarget{
		{ServerName: "app.example.com", Marker: "site-1 v12 bbbbbbbbbbbb"},
	}}
	var decoded verifyResponse
	decode(t, postJSON(t, (&Server{}).handleVerify, "/agent/v1/verify", body), &decoded)

	r := decoded.Results[0]
	if r.Identified {
		t.Fatal("an earlier render of the site must not identify the deployment")
	}
	if r.Marker != "site-1 v11 aaaaaaaaaaaa" {
		t.Errorf("expected the older marker reported, got %q", r.Marker)
	}
}

// fakeNginx writes an executable that answers -t successfully and prints conf for -T, which is
// the only part of `nginx -T` the include check reads.
func fakeNginx(t *testing.T, conf string) string {
	t.Helper()
	dir := t.TempDir()
	confFile := filepath.Join(dir, "nginx.conf")
	if err := os.WriteFile(confFile, []byte(conf), 0o644); err != nil {
		t.Fatal(err)
	}
	script := "#!/bin/sh\ncase \"$*\" in\n*-T*) cat " + confFile + ";;\nesac\nexit 0\n"
	binary := filepath.Join(dir, "nginx")
	if err := os.WriteFile(binary, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return binary
}

func TestValidationRefusesATreeNginxDoesNotLoad(t *testing.T) {
	dir := "/etc/nginx/enginx/current/conf.d"
	cases := []struct {
		name string
		conf string
		want bool
	}{
		{"included", "http {\n    include /etc/nginx/enginx/current/conf.d/*.conf;\n}\n", true},
		{"a stock distribution config", "http {\n    include /etc/nginx/sites-enabled/*;\n}\n", false},
		{"only in a comment", "http {\n    # include /etc/nginx/enginx/current/conf.d/*.conf;\n}\n", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			controller := nginx.New(fakeNginx(t, c.conf), "/unused.conf", 5*time.Second)
			ok, output := nginxForBundle{controller: controller, includeDir: dir}.Test(context.Background())
			if ok != c.want {
				t.Fatalf("Test() = %v, want %v (output %q)", ok, c.want, output)
			}
			if !c.want && !strings.Contains(output, "include "+dir+"/*.conf;") {
				t.Errorf("the refusal must say which line to add, got %q", output)
			}
		})
	}
}

func TestAnExternalNginxIsNeverStartedByTheAgent(t *testing.T) {
	controller := nginx.NewExternal("/bin/true", "/unused.conf", time.Second,
		filepath.Join(t.TempDir(), "nginx.pid"))
	if err := controller.Start(); err != nginx.ErrExternallyManaged {
		t.Fatalf("Start() = %v, want ErrExternallyManaged", err)
	}
	if controller.Running() {
		t.Error("no pid file means not running")
	}
	if controller.Exited() != nil {
		t.Error("an external NGINX's lifecycle is not the agent's to watch")
	}
}

func TestPIDAliveIgnoresStaleAndGarbagePIDFiles(t *testing.T) {
	dir := t.TempDir()
	for name, content := range map[string]string{"garbage": "not-a-pid", "stale": "999999999"} {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		if _, alive := nginx.PIDAlive(path); alive {
			t.Errorf("%s pid file reported alive", name)
		}
	}
	if _, alive := nginx.PIDAlive(filepath.Join(dir, "absent")); alive {
		t.Error("a missing pid file reported alive")
	}
}
