package gate

import (
	"crypto/sha512"
	"crypto/subtle"
	"strconv"
	"strings"
)

// SHA-512-crypt ($6$), as glibc and musl implement it and as the platform stores basic
// authentication passwords: Ulrich Drepper's "Unix crypt using SHA-256 and SHA-512". The standard
// library has no crypt, and the gate must check the same hashes NGINX's auth_basic does.

const (
	cryptAlphabet  = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
	defaultRounds  = 5000
	minRounds      = 1000
	maxRounds      = 999999999
	maxSaltLength  = 16
	sha512CryptTag = "$6$"
)

// verifySHA512Crypt reports whether password produces hash. The comparison is constant-time.
func verifySHA512Crypt(password, hash string) bool {
	computed, ok := sha512Crypt(password, hash)
	return ok && subtle.ConstantTimeCompare([]byte(computed), []byte(hash)) == 1
}

// sha512Crypt hashes password with the salt and rounds named in setting ($6$[rounds=N$]salt[$...]).
func sha512Crypt(password, setting string) (string, bool) {
	if !strings.HasPrefix(setting, sha512CryptTag) {
		return "", false
	}
	rest := setting[len(sha512CryptTag):]
	rounds, explicitRounds := defaultRounds, false
	if strings.HasPrefix(rest, "rounds=") {
		end := strings.IndexByte(rest, '$')
		if end < 0 {
			return "", false
		}
		n, err := strconv.Atoi(rest[len("rounds="):end])
		if err != nil {
			return "", false
		}
		rounds, explicitRounds = min(max(n, minRounds), maxRounds), true
		rest = rest[end+1:]
	}
	salt := rest
	if i := strings.IndexByte(salt, '$'); i >= 0 {
		salt = salt[:i]
	}
	if len(salt) > maxSaltLength {
		salt = salt[:maxSaltLength]
	}

	p, s := []byte(password), []byte(salt)

	// Digest B: password, salt, password.
	b := sha512.New()
	b.Write(p)
	b.Write(s)
	b.Write(p)
	digestB := b.Sum(nil)

	// Digest A: password, salt, then B once per 64 bytes of the password and the remainder, then
	// B or the password for each bit of the password's length.
	a := sha512.New()
	a.Write(p)
	a.Write(s)
	n := len(p)
	for ; n > 64; n -= 64 {
		a.Write(digestB)
	}
	a.Write(digestB[:n])
	for n = len(p); n > 0; n >>= 1 {
		if n&1 != 0 {
			a.Write(digestB)
		} else {
			a.Write(p)
		}
	}
	digestA := a.Sum(nil)

	// P: the password, hashed once for each of its bytes, stretched to its length.
	dp := sha512.New()
	for range p {
		dp.Write(p)
	}
	pSeq := repeatTo(dp.Sum(nil), len(p))

	// S: the salt, hashed 16 + A[0] times, stretched to its length.
	ds := sha512.New()
	for i := 0; i < 16+int(digestA[0]); i++ {
		ds.Write(s)
	}
	sSeq := repeatTo(ds.Sum(nil), len(s))

	c := digestA
	for i := 0; i < rounds; i++ {
		h := sha512.New()
		if i&1 != 0 {
			h.Write(pSeq)
		} else {
			h.Write(c)
		}
		if i%3 != 0 {
			h.Write(sSeq)
		}
		if i%7 != 0 {
			h.Write(pSeq)
		}
		if i&1 != 0 {
			h.Write(c)
		} else {
			h.Write(pSeq)
		}
		c = h.Sum(nil)
	}

	var out strings.Builder
	out.WriteString(sha512CryptTag)
	if explicitRounds {
		out.WriteString("rounds=" + strconv.Itoa(rounds) + "$")
	}
	out.WriteString(salt)
	out.WriteByte('$')
	for _, t := range [][3]int{{0, 21, 42}, {22, 43, 1}, {44, 2, 23}, {3, 24, 45}, {25, 46, 4}, {47, 5, 26},
		{6, 27, 48}, {28, 49, 7}, {50, 8, 29}, {9, 30, 51}, {31, 52, 10}, {53, 11, 32}, {12, 33, 54},
		{34, 55, 13}, {56, 14, 35}, {15, 36, 57}, {37, 58, 16}, {59, 17, 38}, {18, 39, 60}, {40, 61, 19},
		{62, 20, 41}} {
		encode24(&out, c[t[0]], c[t[1]], c[t[2]], 4)
	}
	encode24(&out, 0, 0, c[63], 2)
	return out.String(), true
}

func repeatTo(digest []byte, length int) []byte {
	out := make([]byte, 0, length)
	for len(out) < length {
		out = append(out, digest[:min(len(digest), length-len(out))]...)
	}
	return out
}

func encode24(out *strings.Builder, b2, b1, b0 byte, chars int) {
	w := uint(b2)<<16 | uint(b1)<<8 | uint(b0)
	for ; chars > 0; chars-- {
		out.WriteByte(cryptAlphabet[w&0x3f])
		w >>= 6
	}
}
