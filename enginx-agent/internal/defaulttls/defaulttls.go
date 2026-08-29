// Package defaulttls generates the certificate the HTTPS catch-all server uses.
//
// NGINX picks the first server block as the default when no server_name matches. Without an
// explicit default on 443, a domain pointed at this host that the platform does not serve — an
// expired site, a stale DNS record, someone else's domain aimed at the IP — reaches whichever
// site happens to be first in the configuration, and is answered by it. Phase 5 fixed exactly
// this on port 80; the same hole survived on 443 because a TLS server block needs a certificate
// and there was none to give it.
//
// This certificate exists to be rejected. It is self-signed and carries no subject alternative
// names, so it validates for nothing: a client reaching an unserved name over HTTPS gets a
// certificate error, which is the correct answer. The alternative — presenting a real site's
// certificate to a domain that site does not own — is the failure this prevents.
package defaulttls

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"log/slog"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

// Dir is the directory holding the pair, relative to the releases root.
//
// Outside the release tree deliberately, like the ACME challenge directory: the certificate is
// referenced by an absolute path from every rendered bundle, so it must not move or vanish when
// a release is swapped or a superseded bundle is discarded.
const Dir = "default-tls"

const (
	certFile = "default.crt"
	keyFile  = "default.key"
	// Long, because rotation would be pure churn. Nothing trusts this certificate, so an expiry
	// date protects no one — and an expired one would break `nginx -t` and with it every
	// deployment to the host, turning a cosmetic detail into an outage.
	validity = 20 * 365 * 24 * time.Hour
)

// Paths returns the absolute certificate and key paths for a releases root.
func Paths(releasesDir string) (cert string, key string) {
	base := filepath.Join(releasesDir, Dir)
	return filepath.Join(base, certFile), filepath.Join(base, keyFile)
}

// Ensure creates the pair if it is missing or unusable, and does nothing otherwise.
//
// Called at startup and again before each activation. A bundle rendered by a current management
// server references these paths, so if they are absent `nginx -t` fails and the deployment is
// rejected — correctly, but for a reason that has nothing to do with the operator's change.
// Checking twice costs a stat and removes that whole class of confusing failure.
func Ensure(releasesDir string) error {
	certPath, keyPath := Paths(releasesDir)

	if usable(certPath, keyPath) {
		return nil
	}

	if err := os.MkdirAll(filepath.Dir(certPath), 0o755); err != nil {
		return fmt.Errorf("creating %s: %w", filepath.Dir(certPath), err)
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("generating default server key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return fmt.Errorf("generating serial: %w", err)
	}

	now := time.Now()
	template := x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "enginx-default-server",
			Organization: []string{"Easy NGINX Admin"},
		},
		NotBefore: now.Add(-time.Hour),
		NotAfter:  now.Add(validity),
		KeyUsage:  x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{
			x509.ExtKeyUsageServerAuth,
		},
		BasicConstraintsValid: true,
		// No DNSNames and no IPAddresses, on purpose. A modern client validates the name against
		// the SANs and ignores the common name entirely, so this certificate is valid for nothing
		// at all — which is precisely the guarantee wanted from a catch-all.
	}

	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return fmt.Errorf("creating default server certificate: %w", err)
	}

	if err := writePEM(certPath, "CERTIFICATE", der, 0o644); err != nil {
		return err
	}

	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return fmt.Errorf("encoding default server key: %w", err)
	}
	// 0600 for the key, the same rule every private key on this host follows, even one that
	// protects nothing. A directory of keys where one is readable is a directory nobody audits.
	if err := writePEM(keyPath, "EC PRIVATE KEY", keyDER, 0o600); err != nil {
		return err
	}

	slog.Info("generated the default HTTPS server certificate",
		"path", certPath, "notAfter", template.NotAfter.UTC().Format(time.RFC3339))
	return nil
}

// usable reports whether both files exist and the certificate still parses and is in date.
func usable(certPath, keyPath string) bool {
	if _, err := os.Stat(keyPath); err != nil {
		return false
	}
	raw, err := os.ReadFile(certPath)
	if err != nil {
		return false
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return false
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return false
	}
	// Regenerate well before expiry rather than on the day, so a long-running host never reaches
	// the point where nginx -t starts refusing every deployment.
	return time.Now().Before(certificate.NotAfter.Add(-30 * 24 * time.Hour))
}

func writePEM(path, blockType string, der []byte, mode os.FileMode) error {
	encoded := pem.EncodeToMemory(&pem.Block{Type: blockType, Bytes: der})
	if encoded == nil {
		return fmt.Errorf("encoding %s for %s", blockType, path)
	}
	if err := os.WriteFile(path, encoded, mode); err != nil {
		return fmt.Errorf("writing %s: %w", path, err)
	}
	return nil
}
