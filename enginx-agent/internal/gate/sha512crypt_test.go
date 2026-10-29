package gate

import "testing"

func TestSHA512CryptMatchesTheSystemCrypt(t *testing.T) {
	for _, tc := range []struct{ password, hash string }{
		// openssl passwd -6 -salt fixturesalt0001 secret: the platform's own fixtures.
		{"secret", "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1"},
		{"other", "$6$fixturesalt0002$SVJ/Qcuj5pOFgDaMim7YMeD0zspwX5SMLItbweCxpQAE8vOisbTGKkZoa6BxlnMLKacxFEmmhFpz8g7P7athb."},
		// The reference test vectors from Drepper's specification.
		{"Hello world!", "$6$saltstring$svn8UoSVapNtMuq1ukKS4tPQd8iKwSMHWjl/O817G3uBnIFNjnQJuesI68u4OTLiBFdcbYEdFCoEOfaS35inz1"},
		{"Hello world!", "$6$rounds=10000$saltstringsaltst$OW1/O6BYHV6BcXZu8QVeXbDWra3Oeqh0sbHbbMCVNSnCM/UrjmM0Dp8vOuZeHBy/YTBmSK6H9qs/y3RnOaw5v."},
	} {
		if !verifySHA512Crypt(tc.password, tc.hash) {
			got, _ := sha512Crypt(tc.password, tc.hash)
			t.Errorf("%q: got %s, want %s", tc.password, got, tc.hash)
		}
		if verifySHA512Crypt(tc.password+"x", tc.hash) {
			t.Errorf("%q: a wrong password verified", tc.password)
		}
	}
}

func TestSHA512CryptRefusesOtherSchemes(t *testing.T) {
	if verifySHA512Crypt("secret", "$2a$12$abcdefghijklmnopqrstuu") || verifySHA512Crypt("secret", "secret") {
		t.Fatal("a non-$6$ hash verified")
	}
}
