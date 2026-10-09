package runner

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/xiidea/enginx/enginx-agent/internal/api"
	"github.com/xiidea/enginx/enginx-agent/internal/bundle"
	"github.com/xiidea/enginx/enginx-agent/internal/client"
	"github.com/xiidea/enginx/enginx-agent/internal/config"
)

const acmeToken = "evaGxfADs6pSRb2LAv9IZf17Dt3juxGJ-PCt92wr-oA"

func acmeRunner(t *testing.T) (*Runner, string) {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Config{ReleasesDir: dir}
	return New(cfg, api.NewServer(cfg, nil, bundle.NewStore(dir))), dir
}

// A pull host answers HTTP-01 from the same place a dialled one does: the file NGINX serves.
func TestChallengeJobsPublishAndRemoveTheResponse(t *testing.T) {
	r, dir := acmeRunner(t)
	path := filepath.Join(dir, "acme-challenge", acmeToken)

	result := r.execute(context.Background(), nil, client.Job{
		Type: "PUBLISH_ACME_CHALLENGE", AcmeToken: acmeToken, AcmeAuthorization: acmeToken + ".thumbprint",
	})
	if !result.Succeeded {
		t.Fatalf("publish failed: %s", result.Error)
	}
	if body, err := os.ReadFile(path); err != nil || string(body) != acmeToken+".thumbprint" {
		t.Fatalf("expected the key authorization at %s, got %q (%v)", path, body, err)
	}

	result = r.execute(context.Background(), nil, client.Job{Type: "REMOVE_ACME_CHALLENGE", AcmeToken: acmeToken})
	if !result.Succeeded {
		t.Fatalf("remove failed: %s", result.Error)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("expected the response removed, stat says %v", err)
	}
}

// The token becomes a filename, so the job path must refuse what the HTTP path refuses.
func TestChallengeJobsRefuseATokenThatIsNotOne(t *testing.T) {
	r, dir := acmeRunner(t)

	result := r.execute(context.Background(), nil, client.Job{
		Type: "PUBLISH_ACME_CHALLENGE", AcmeToken: "../../etc/passwd-is-long-enough", AcmeAuthorization: "x",
	})
	if result.Succeeded {
		t.Fatal("a path-shaped token must be refused")
	}
	if entries, _ := os.ReadDir(filepath.Join(dir, "acme-challenge")); len(entries) != 0 {
		t.Fatalf("nothing should have been written, found %d entries", len(entries))
	}
}
