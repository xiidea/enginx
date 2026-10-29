package gate

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"strings"
)

// A JSON Web Key, as far as verifying RS256 and ES256 needs.
type jwk struct {
	Kid string `json:"kid"`
	Kty string `json:"kty"`
	Alg string `json:"alg"`
	Use string `json:"use"`
	N   string `json:"n"`
	E   string `json:"e"`
	Crv string `json:"crv"`
	X   string `json:"x"`
	Y   string `json:"y"`
}

type jwkSet struct {
	Keys []jwk `json:"keys"`
}

func (k jwk) publicKey() (crypto.PublicKey, error) {
	switch k.Kty {
	case "RSA":
		n, err := base64.RawURLEncoding.DecodeString(k.N)
		if err != nil {
			return nil, err
		}
		e, err := base64.RawURLEncoding.DecodeString(k.E)
		if err != nil {
			return nil, err
		}
		exponent := new(big.Int).SetBytes(e)
		if !exponent.IsInt64() || exponent.Int64() < 3 {
			return nil, errors.New("bad RSA exponent")
		}
		return &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(exponent.Int64())}, nil
	case "EC":
		if k.Crv != "P-256" {
			return nil, fmt.Errorf("unsupported curve %q", k.Crv)
		}
		x, err := base64.RawURLEncoding.DecodeString(k.X)
		if err != nil {
			return nil, err
		}
		y, err := base64.RawURLEncoding.DecodeString(k.Y)
		if err != nil {
			return nil, err
		}
		key := &ecdsa.PublicKey{Curve: elliptic.P256(), X: new(big.Int).SetBytes(x), Y: new(big.Int).SetBytes(y)}
		if !key.Curve.IsOnCurve(key.X, key.Y) {
			return nil, errors.New("EC point is not on the curve")
		}
		return key, nil
	default:
		return nil, fmt.Errorf("unsupported key type %q", k.Kty)
	}
}

type jwtHeader struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
}

var errNotJWT = errors.New("not a JWT")

// parseJWT splits a compact JWS and decodes its header and claims, without verifying anything.
func parseJWT(token string) (jwtHeader, map[string]any, []byte, []byte, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	var header jwtHeader
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	claimsJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	var claims map[string]any
	if err := json.Unmarshal(claimsJSON, &claims); err != nil {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return jwtHeader{}, nil, nil, nil, errNotJWT
	}
	return header, claims, []byte(parts[0] + "." + parts[1]), signature, nil
}

// verifySignature checks a JWS signature. Only RS256 and ES256: "none", HMAC and anything else are
// refused, which is what keeps a token signed with the provider's public key as an HMAC secret —
// the classic algorithm confusion — from ever verifying.
func verifySignature(alg string, key crypto.PublicKey, signed, signature []byte) error {
	digest := sha256.Sum256(signed)
	switch alg {
	case "RS256":
		rsaKey, ok := key.(*rsa.PublicKey)
		if !ok {
			return errors.New("RS256 token with a non-RSA key")
		}
		return rsa.VerifyPKCS1v15(rsaKey, crypto.SHA256, digest[:], signature)
	case "ES256":
		ecKey, ok := key.(*ecdsa.PublicKey)
		if !ok || len(signature) != 64 {
			return errors.New("ES256 token with a non-EC key or a malformed signature")
		}
		r := new(big.Int).SetBytes(signature[:32])
		s := new(big.Int).SetBytes(signature[32:])
		if !ecdsa.Verify(ecKey, digest[:], r, s) {
			return errors.New("invalid ES256 signature")
		}
		return nil
	default:
		return fmt.Errorf("unsupported algorithm %q", alg)
	}
}

func claimString(claims map[string]any, name string) string {
	value, _ := claims[name].(string)
	return value
}

// claimAudience reports whether aud — a string or a list — contains want.
func claimAudience(claims map[string]any, want string) bool {
	switch aud := claims["aud"].(type) {
	case string:
		return aud == want
	case []any:
		for _, entry := range aud {
			if entry == want {
				return true
			}
		}
	}
	return false
}

func claimTime(claims map[string]any, name string) (int64, bool) {
	value, ok := claims[name].(float64)
	return int64(value), ok
}

// claimRoles reads Keycloak's role claims: realm_access.roles and resource_access.<client>.roles.
func claimRoles(claims map[string]any) (realm map[string]bool, clients map[string]map[string]bool) {
	realm = map[string]bool{}
	clients = map[string]map[string]bool{}
	if access, ok := claims["realm_access"].(map[string]any); ok {
		for _, role := range stringList(access["roles"]) {
			realm[role] = true
		}
	}
	if resources, ok := claims["resource_access"].(map[string]any); ok {
		for client, value := range resources {
			access, ok := value.(map[string]any)
			if !ok {
				continue
			}
			roles := map[string]bool{}
			for _, role := range stringList(access["roles"]) {
				roles[role] = true
			}
			clients[client] = roles
		}
	}
	return realm, clients
}

func stringList(value any) []string {
	list, _ := value.([]any)
	out := make([]string, 0, len(list))
	for _, entry := range list {
		if s, ok := entry.(string); ok {
			out = append(out, s)
		}
	}
	return out
}
