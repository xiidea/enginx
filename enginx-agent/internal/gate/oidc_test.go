package gate

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeProvider is an identity provider that signs real RS256 tokens. Its issuer is a public name
// browsers would use; the gate reaches it through the backchannel, its real address.
type fakeProvider struct {
	t        *testing.T
	key      *rsa.PrivateKey
	server   *httptest.Server
	issuer   string
	clientID string
	// What the next token carries.
	claims       map[string]any
	accessClaims map[string]any
	// Recorded from the authorize redirect and the token request.
	gotVerifier string
	gotRedirect string
	// Tampering, per test.
	nonceOverride string
	audOverride   any
}

func newFakeProvider(t *testing.T) *fakeProvider {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	p := &fakeProvider{t: t, key: key, issuer: "https://id.example.com/realms/staff", clientID: "enginx-sites"}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /realms/staff/.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{
			"issuer":                 p.issuer,
			"authorization_endpoint": p.issuer + "/protocol/openid-connect/auth",
			"token_endpoint":         p.issuer + "/protocol/openid-connect/token",
			"jwks_uri":               p.issuer + "/protocol/openid-connect/certs",
		})
	})
	mux.HandleFunc("GET /realms/staff/protocol/openid-connect/certs", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kid": "k1", "kty": "RSA", "alg": "RS256", "use": "sig",
			"n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes()),
		}}})
	})
	mux.HandleFunc("POST /realms/staff/protocol/openid-connect/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		p.gotVerifier = r.PostFormValue("code_verifier")
		p.gotRedirect = r.PostFormValue("redirect_uri")
		nonce := strings.TrimPrefix(r.PostFormValue("code"), "code-for-")
		if p.nonceOverride != "" {
			nonce = p.nonceOverride
		}
		id := map[string]any{"iss": p.issuer, "aud": p.clientID, "azp": p.clientID, "sub": "u-1", "nonce": nonce,
			"exp": time.Now().Add(5 * time.Minute).Unix()}
		if p.audOverride != nil {
			id["aud"] = p.audOverride
		}
		for k, v := range p.claims {
			id[k] = v
		}
		access := map[string]any{"iss": p.issuer, "azp": p.clientID, "sub": "u-1", "exp": time.Now().Add(5 * time.Minute).Unix()}
		for k, v := range p.accessClaims {
			access[k] = v
		}
		_ = json.NewEncoder(w).Encode(map[string]string{"id_token": p.sign(id), "access_token": p.sign(access)})
	})
	p.server = httptest.NewServer(mux)
	t.Cleanup(p.server.Close)
	return p
}

func (p *fakeProvider) sign(claims map[string]any) string {
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": "k1", "typ": "JWT"})
	body, _ := json.Marshal(claims)
	signed := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(body)
	digest := sha256.Sum256([]byte(signed))
	signature, err := rsa.SignPKCS1v15(rand.Reader, p.key, crypto.SHA256, digest[:])
	if err != nil {
		p.t.Fatal(err)
	}
	return signed + "." + base64.RawURLEncoding.EncodeToString(signature)
}

func (p *fakeProvider) site(policy OIDCConfig) *Site {
	policy.Issuer = p.issuer
	policy.Backchannel = p.server.URL + "/realms/staff"
	policy.ClientID = p.clientID
	policy.Scopes = "openid email profile"
	site := testSite(p.t)
	site.Mode = ModeOIDC
	site.OIDC = &policy
	return site
}

// signIn runs the browser's side: login → provider → callback. The provider is not visited:
// the test reads state and nonce off the authorize URL and answers as the provider would.
func signIn(t *testing.T, server *Server) *httptest.ResponseRecorder {
	t.Helper()
	handler := server.Handler()
	login := httptest.NewRecorder()
	r := request("GET", "/.enginx/auth/login?rd=/reports?q=1", nil)
	r.Header.Set("X-Forwarded-Host", "private.example.com:18453")
	handler.ServeHTTP(login, r)
	if login.Code != http.StatusFound {
		t.Fatalf("login: %d %s", login.Code, login.Body.String())
	}
	authorize, _ := url.Parse(login.Header().Get("Location"))
	q := authorize.Query()
	if q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") == "" || q.Get("redirect_uri") != "https://private.example.com:18453/.enginx/auth/callback" {
		t.Fatalf("authorize request: %s", authorize)
	}
	flow := cookie(login.Result(), FlowCookie)

	callback := httptest.NewRecorder()
	handler.ServeHTTP(callback, request("GET", "/.enginx/auth/callback?code=code-for-"+q.Get("nonce")+"&state="+q.Get("state"), nil, flow))
	return callback
}

func newOIDCServer(t *testing.T, p *fakeProvider, policy OIDCConfig) *Server {
	server := NewServer(&fakeRelay{sent: map[string]string{}})
	server.SetSites(map[string]*Site{testSiteID: p.site(policy)})
	return server
}

func TestOIDCSignInAdmitsAnyUser(t *testing.T) {
	p := newFakeProvider(t)
	p.claims = map[string]any{"email": "Bob@Example.com", "preferred_username": "bob"}
	server := newOIDCServer(t, p, OIDCConfig{AnyUser: true})

	callback := signIn(t, server)
	session := cookie(callback.Result(), SessionCookie)
	if callback.Code != http.StatusSeeOther || callback.Header().Get("Location") != "/reports?q=1" || session == nil {
		t.Fatalf("callback: %d %q %s", callback.Code, callback.Header().Get("Location"), callback.Body.String())
	}
	// The verifier sent to the token endpoint must be the one the challenge was made from.
	if p.gotVerifier == "" || p.gotRedirect != "https://private.example.com:18453/.enginx/auth/callback" {
		t.Fatalf("token request: verifier %q redirect %q", p.gotVerifier, p.gotRedirect)
	}

	check := httptest.NewRecorder()
	server.Handler().ServeHTTP(check, request("GET", "/check", nil, session))
	if check.Code != http.StatusNoContent || check.Header().Get("X-Enginx-Email") != "bob@example.com" ||
		check.Header().Get("X-Enginx-User") != "bob" {
		t.Fatalf("check: %d %v", check.Code, check.Header())
	}
}

func TestOIDCAccessPolicy(t *testing.T) {
	for name, tc := range map[string]struct {
		policy OIDCConfig
		claims map[string]any
		access map[string]any
		admit  bool
	}{
		"listed by email": {OIDCConfig{Users: []string{"bob@example.com"}},
			map[string]any{"email": "bob@example.com"}, nil, true},
		"listed by username": {OIDCConfig{Users: []string{"bob"}},
			map[string]any{"preferred_username": "Bob"}, nil, true},
		"not listed": {OIDCConfig{Users: []string{"alice"}},
			map[string]any{"preferred_username": "bob", "email": "bob@example.com"}, nil, false},
		"realm role in the access token": {OIDCConfig{RealmRoles: []string{"auditor"}},
			nil, map[string]any{"realm_access": map[string]any{"roles": []any{"auditor"}}}, true},
		"client role in the access token": {OIDCConfig{ClientRoles: []ClientRole{{Client: "reports", Role: "viewer"}}},
			nil, map[string]any{"resource_access": map[string]any{"reports": map[string]any{"roles": []any{"viewer"}}}}, true},
		"the same role on another client": {OIDCConfig{ClientRoles: []ClientRole{{Client: "reports", Role: "viewer"}}},
			nil, map[string]any{"resource_access": map[string]any{"billing": map[string]any{"roles": []any{"viewer"}}}}, false},
		"a realm role is not a client role": {OIDCConfig{ClientRoles: []ClientRole{{Client: "reports", Role: "viewer"}}},
			nil, map[string]any{"realm_access": map[string]any{"roles": []any{"viewer"}}}, false},
	} {
		t.Run(name, func(t *testing.T) {
			p := newFakeProvider(t)
			p.claims, p.accessClaims = tc.claims, tc.access
			callback := signIn(t, newOIDCServer(t, p, tc.policy))
			admitted := cookie(callback.Result(), SessionCookie) != nil
			if admitted != tc.admit {
				t.Fatalf("admitted = %v, want %v (%d)", admitted, tc.admit, callback.Code)
			}
			if !tc.admit && callback.Code != http.StatusForbidden {
				t.Fatalf("a refused user got %d, want 403", callback.Code)
			}
		})
	}
}

func TestOIDCRefusesTokensNotMeantForThisSignIn(t *testing.T) {
	for name, tamper := range map[string]func(*fakeProvider){
		"another sign-in's nonce": func(p *fakeProvider) { p.nonceOverride = "someone-elses" },
		"another client's token":  func(p *fakeProvider) { p.audOverride = "other-app" },
	} {
		t.Run(name, func(t *testing.T) {
			p := newFakeProvider(t)
			tamper(p)
			callback := signIn(t, newOIDCServer(t, p, OIDCConfig{AnyUser: true}))
			if cookie(callback.Result(), SessionCookie) != nil {
				t.Fatal("signed in with a token not meant for this sign-in")
			}
		})
	}
}

func TestOIDCCallbackMustComeBackToTheSameBrowser(t *testing.T) {
	p := newFakeProvider(t)
	server := newOIDCServer(t, p, OIDCConfig{AnyUser: true})
	login := httptest.NewRecorder()
	server.Handler().ServeHTTP(login, request("GET", "/.enginx/auth/login", nil))
	authorize, _ := url.Parse(login.Header().Get("Location"))
	q := authorize.Query()

	// No flow cookie: a callback link sent to someone else, or forged.
	callback := httptest.NewRecorder()
	server.Handler().ServeHTTP(callback, request("GET", "/.enginx/auth/callback?code=code-for-"+q.Get("nonce")+"&state="+q.Get("state"), nil))
	if cookie(callback.Result(), SessionCookie) != nil || callback.Code != http.StatusBadRequest {
		t.Fatalf("a callback without its browser's flow cookie: %d", callback.Code)
	}
}

func TestBackchannelRewritesOnlyTheIssuersOrigin(t *testing.T) {
	cfg := &OIDCConfig{Issuer: "https://id.example.com/realms/staff", Backchannel: "http://keycloak:8080/realms/staff"}
	for published, want := range map[string]string{
		"https://id.example.com/realms/staff/protocol/openid-connect/token": "http://keycloak:8080/realms/staff/protocol/openid-connect/token",
		"https://elsewhere.example/keys":                                    "https://elsewhere.example/keys",
	} {
		if got := backchannelURL(cfg, published); got != want {
			t.Errorf("backchannelURL(%q) = %q, want %q", published, got, want)
		}
	}
}

func TestAForwardedHostForAnotherNameIsIgnored(t *testing.T) {
	site := testSite(t)
	r := request("GET", "/.enginx/auth/login", nil)
	r.Header.Set("X-Forwarded-Host", "evil.example")
	if got := callbackURI(site, r); got != "https://private.example.com/.enginx/auth/callback" {
		t.Fatalf("callbackURI = %q", got)
	}
}

func TestOnlyRS256AndES256Verify(t *testing.T) {
	p := newFakeProvider(t)
	if err := verifySignature("HS256", &p.key.PublicKey, []byte("x"), []byte("y")); err == nil {
		t.Fatal("HS256 must never verify")
	}
	if err := verifySignature("none", &p.key.PublicKey, []byte("x"), nil); err == nil {
		t.Fatal("none must never verify")
	}
}

// An address the provider has not verified is not an address: it neither matches a user rule nor
// reaches the upstream.
func TestAnUnverifiedEmailIsNotTrusted(t *testing.T) {
	p := newFakeProvider(t)
	p.claims = map[string]any{"email": "alice@example.com", "email_verified": false, "preferred_username": "mallory"}
	callback := signIn(t, newOIDCServer(t, p, OIDCConfig{Users: []string{"alice@example.com"}}))
	if cookie(callback.Result(), SessionCookie) != nil {
		t.Fatal("an unverified email matched a user rule")
	}

	p.claims["email_verified"] = true
	callback = signIn(t, newOIDCServer(t, p, OIDCConfig{Users: []string{"alice@example.com"}}))
	if cookie(callback.Result(), SessionCookie) == nil {
		t.Fatalf("a verified email was refused: %d", callback.Code)
	}
}

func TestOIDCStartsAreLimitedPerClient(t *testing.T) {
	p := newFakeProvider(t)
	server := newOIDCServer(t, p, OIDCConfig{AnyUser: true})
	last := 0
	for i := 0; i < 121; i++ {
		res := httptest.NewRecorder()
		server.Handler().ServeHTTP(res, request("GET", "/.enginx/auth/login", nil))
		last = res.Code
	}
	if last != http.StatusTooManyRequests {
		t.Fatalf("the 121st start from one client: %d", last)
	}
}
