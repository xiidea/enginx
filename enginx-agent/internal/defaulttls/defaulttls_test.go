package defaulttls

import (
	"crypto/x509"
	"encoding/pem"
	"os"
	"testing"
)

func TestEnsureCreatesAPairThatValidatesForNothing(t *testing.T) {
	root := t.TempDir()
	if err := Ensure(root); err != nil {
		t.Fatal(err)
	}

	certPath, keyPath := Paths(root)
	raw, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(raw)
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}

	// The whole point: no SANs, so no client can ever accept it for a real name. If this ever
	// gains a DNS name, the catch-all starts vouching for a domain it does not serve.
	if len(certificate.DNSNames) != 0 || len(certificate.IPAddresses) != 0 {
		t.Errorf("default certificate must carry no SANs, got DNS=%v IP=%v",
			certificate.DNSNames, certificate.IPAddresses)
	}

	info, err := os.Stat(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Errorf("key mode = %o, want 600", mode)
	}
}

func TestEnsureIsIdempotent(t *testing.T) {
	root := t.TempDir()
	if err := Ensure(root); err != nil {
		t.Fatal(err)
	}
	certPath, _ := Paths(root)
	first, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatal(err)
	}

	if err := Ensure(root); err != nil {
		t.Fatal(err)
	}
	second, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatal(err)
	}

	// Regenerating on every call would change the certificate NGINX is serving on each agent
	// restart, and on each activation, for no reason.
	if string(first) != string(second) {
		t.Error("Ensure regenerated an existing, usable certificate")
	}
}

func TestEnsureReplacesAnUnreadableCertificate(t *testing.T) {
	root := t.TempDir()
	if err := Ensure(root); err != nil {
		t.Fatal(err)
	}
	certPath, _ := Paths(root)
	if err := os.WriteFile(certPath, []byte("not a certificate"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := Ensure(root); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatal(err)
	}
	if block, _ := pem.Decode(raw); block == nil {
		t.Fatal("a corrupt certificate was left in place; nginx -t would fail on every deployment")
	}
}
