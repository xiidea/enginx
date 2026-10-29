package gate

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	oidcFlowValidFor = 10 * time.Minute
	// How long discovery and keys are trusted before being fetched again. An unknown key id
	// refetches sooner, which is how a provider's key rotation is picked up.
	providerCacheFor = time.Hour
	keyRefetchFloor  = 30 * time.Second
	clockSkew        = 60 * time.Second
	callbackPath     = "/.enginx/auth/callback"
)

// --- the provider -----------------------------------------------------------

type discovery struct {
	Issuer                string `json:"issuer"`
	AuthorizationEndpoint string `json:"authorization_endpoint"`
	TokenEndpoint         string `json:"token_endpoint"`
	JWKSURI               string `json:"jwks_uri"`
}

type providerState struct {
	doc       discovery
	fetched   time.Time
	keys      map[string]crypto.PublicKey
	keysFetch time.Time
}

// providers caches discovery and keys per issuer, shared by every site that uses it.
type providers struct {
	client *http.Client
	mu     sync.Mutex
	byIss  map[string]*providerState
}

func newProviders(client *http.Client) *providers {
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	return &providers{client: client, byIss: map[string]*providerState{}}
}

// backchannelURL rewrites a URL the provider published, which names it as browsers reach it, onto
// the origin this host reaches it at. Only when the URL is on the issuer's own origin: anything
// else the provider points at is left alone.
func backchannelURL(cfg *OIDCConfig, published string) string {
	if cfg.Backchannel == "" {
		return published
	}
	issuer, err1 := url.Parse(cfg.Issuer)
	back, err2 := url.Parse(cfg.Backchannel)
	target, err3 := url.Parse(published)
	if err1 != nil || err2 != nil || err3 != nil || !strings.EqualFold(target.Host, issuer.Host) ||
		target.Scheme != issuer.Scheme {
		return published
	}
	target.Scheme, target.Host = back.Scheme, back.Host
	return target.String()
}

func (p *providers) state(ctx context.Context, cfg *OIDCConfig, now time.Time) (*providerState, error) {
	p.mu.Lock()
	state := p.byIss[cfg.Issuer]
	p.mu.Unlock()
	if state != nil && now.Sub(state.fetched) < providerCacheFor {
		return state, nil
	}

	base := cfg.Issuer
	if cfg.Backchannel != "" {
		base = cfg.Backchannel
	}
	var doc discovery
	if err := p.getJSON(ctx, strings.TrimRight(base, "/")+"/.well-known/openid-configuration", &doc); err != nil {
		return nil, fmt.Errorf("discovery: %w", err)
	}
	// The issuer the provider names must be the one configured, or tokens it signs would be
	// checked against the wrong expectation.
	if doc.Issuer != cfg.Issuer {
		return nil, fmt.Errorf("the provider calls itself %q, not the configured %q", doc.Issuer, cfg.Issuer)
	}
	if doc.AuthorizationEndpoint == "" || doc.TokenEndpoint == "" || doc.JWKSURI == "" {
		return nil, errors.New("the discovery document is missing an endpoint")
	}
	fresh := &providerState{doc: doc, fetched: now}
	if state != nil && state.doc.JWKSURI == doc.JWKSURI {
		fresh.keys, fresh.keysFetch = state.keys, state.keysFetch
	}
	p.mu.Lock()
	p.byIss[cfg.Issuer] = fresh
	p.mu.Unlock()
	return fresh, nil
}

// key finds a signing key by id, fetching the key set again when the id is unknown and the last
// fetch is not too recent.
func (p *providers) key(ctx context.Context, cfg *OIDCConfig, state *providerState, kid string, now time.Time) (crypto.PublicKey, error) {
	p.mu.Lock()
	key, known := state.keys[kid]
	stale := state.keys == nil || now.Sub(state.keysFetch) > keyRefetchFloor
	p.mu.Unlock()
	if known {
		return key, nil
	}
	if !stale {
		return nil, fmt.Errorf("unknown signing key %q", kid)
	}
	var set jwkSet
	if err := p.getJSON(ctx, backchannelURL(cfg, state.doc.JWKSURI), &set); err != nil {
		return nil, fmt.Errorf("signing keys: %w", err)
	}
	keys := map[string]crypto.PublicKey{}
	for _, k := range set.Keys {
		if k.Use != "" && k.Use != "sig" {
			continue
		}
		if public, err := k.publicKey(); err == nil {
			keys[k.Kid] = public
		}
	}
	p.mu.Lock()
	state.keys, state.keysFetch = keys, now
	p.mu.Unlock()
	if key, ok := keys[kid]; ok {
		return key, nil
	}
	return nil, fmt.Errorf("unknown signing key %q", kid)
}

func (p *providers) getJSON(ctx context.Context, target string, into any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return err
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s answered %d", target, resp.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(into)
}

// verify checks a token's signature against the provider's keys, and that it was issued by the
// configured issuer and is in date. What the token is for is the caller's to check.
func (p *providers) verify(ctx context.Context, cfg *OIDCConfig, state *providerState, token string, now time.Time) (map[string]any, error) {
	header, claims, signed, signature, err := parseJWT(token)
	if err != nil {
		return nil, err
	}
	key, err := p.key(ctx, cfg, state, header.Kid, now)
	if err != nil {
		return nil, err
	}
	if err := verifySignature(header.Alg, key, signed, signature); err != nil {
		return nil, err
	}
	if claimString(claims, "iss") != cfg.Issuer {
		return nil, errors.New("the token was issued by someone else")
	}
	exp, ok := claimTime(claims, "exp")
	if !ok || now.Add(-clockSkew).Unix() >= exp {
		return nil, errors.New("the token has expired")
	}
	return claims, nil
}

// --- the flow ---------------------------------------------------------------

// oidcFlow is one sign-in on its way through the provider. The state is also the value of the
// flow cookie, so the callback is accepted only in the browser that started it.
type oidcFlow struct {
	siteID      string
	nonce       string
	verifier    string
	redirect    string
	redirectURI string
	expires     time.Time
}

type oidcFlows struct {
	mu    sync.Mutex
	flows map[string]*oidcFlow
}

func newOIDCFlows() *oidcFlows {
	return &oidcFlows{flows: map[string]*oidcFlow{}}
}

func (f *oidcFlows) put(state string, flow *oidcFlow, now time.Time) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	for id, existing := range f.flows {
		if now.After(existing.expires) {
			delete(f.flows, id)
		}
	}
	if len(f.flows) >= maxPending {
		return errors.New("too many sign-ins in progress")
	}
	f.flows[state] = flow
	return nil
}

// take removes and returns a flow: a state is good for one callback.
func (f *oidcFlows) take(state string, now time.Time) (*oidcFlow, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	flow, ok := f.flows[state]
	delete(f.flows, state)
	if !ok || now.After(flow.expires) {
		return nil, false
	}
	return flow, true
}

func randomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// callbackURI is where the provider sends the visitor back: the site's own domain, with the port
// the browser used, so it survives a port mapping. X-Forwarded-Host is set by the rendered
// configuration from what the browser sent; it is trusted only when it names this site.
func callbackURI(site *Site, r *http.Request) string {
	host := site.Domain
	if forwarded := r.Header.Get("X-Forwarded-Host"); forwarded != "" {
		name := forwarded
		if h, _, err := net.SplitHostPort(forwarded); err == nil {
			name = h
		}
		if strings.EqualFold(name, site.Domain) {
			host = forwarded
		}
	}
	return "https://" + host + callbackPath
}

func (s *Server) startOIDC(w http.ResponseWriter, r *http.Request, site *Site) {
	cfg := site.OIDC
	now := s.now()
	state, err := s.providers.state(r.Context(), cfg, now)
	if err != nil {
		slog.Error("the identity provider could not be reached", "site", site.Domain, "error", err)
		render(w, http.StatusBadGateway, page{Domain: site.Domain, Step: "error",
			Note: "The sign-in service cannot be reached right now. Try again in a moment."})
		return
	}
	flowState, err1 := randomToken(24)
	nonce, err2 := randomToken(24)
	verifier, err3 := randomToken(48)
	if err1 != nil || err2 != nil || err3 != nil {
		s.unavailable(w)
		return
	}
	redirectURI := callbackURI(site, r)
	flow := &oidcFlow{siteID: site.ID, nonce: nonce, verifier: verifier,
		redirect: redirectFromQuery(r.URL.RawQuery), redirectURI: redirectURI, expires: now.Add(oidcFlowValidFor)}
	if err := s.oidcFlows.put(flowState, flow, now); err != nil {
		s.unavailable(w)
		return
	}

	challenge := sha256.Sum256([]byte(verifier))
	query := url.Values{
		"response_type":         {"code"},
		"client_id":             {cfg.ClientID},
		"redirect_uri":          {redirectURI},
		"scope":                 {cfg.Scopes},
		"state":                 {flowState},
		"nonce":                 {nonce},
		"code_challenge":        {base64.RawURLEncoding.EncodeToString(challenge[:])},
		"code_challenge_method": {"S256"},
	}
	// "Use another account" asks the provider to sign in afresh rather than reuse its session.
	if r.URL.Query().Has("switch") {
		query.Set("prompt", "login")
	}
	authorize, err := url.Parse(state.doc.AuthorizationEndpoint)
	if err != nil {
		s.unavailable(w)
		return
	}
	authorize.RawQuery = query.Encode()

	http.SetCookie(w, &http.Cookie{Name: FlowCookie, Value: flowState, Path: "/", MaxAge: int(oidcFlowValidFor.Seconds()),
		Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, authorize.String(), http.StatusFound)
}

type tokenResponse struct {
	IDToken     string `json:"id_token"`
	AccessToken string `json:"access_token"`
	Error       string `json:"error"`
	Description string `json:"error_description"`
}

func (s *Server) handleCallback(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site == nil || site.Mode != ModeOIDC {
		s.unavailable(w)
		return
	}
	query := r.URL.Query()
	if problem := query.Get("error"); problem != "" {
		slog.Info("the identity provider refused the sign-in", "site", site.Domain, "error", problem)
		render(w, http.StatusUnauthorized, page{Domain: site.Domain, Step: "retry",
			Note: "Signing in did not complete. Try again."})
		return
	}
	cookie, err := r.Cookie(FlowCookie)
	stateParam := query.Get("state")
	if err != nil || stateParam == "" || cookie.Value != stateParam {
		render(w, http.StatusBadRequest, page{Domain: site.Domain, Step: "retry",
			Note: "That sign-in was started in another browser, or has expired. Try again."})
		return
	}
	now := s.now()
	flow, ok := s.oidcFlows.take(stateParam, now)
	if !ok || flow.siteID != site.ID {
		render(w, http.StatusBadRequest, page{Domain: site.Domain, Step: "retry",
			Note: "That sign-in has expired. Try again."})
		return
	}

	who, realm, clients, err := s.exchange(r.Context(), site, flow, query.Get("code"), now)
	if err != nil {
		slog.Error("an identity provider sign-in failed", "site", site.Domain, "error", err)
		render(w, http.StatusBadGateway, page{Domain: site.Domain, Step: "retry",
			Note: "Signing in did not complete. Try again."})
		return
	}
	http.SetCookie(w, &http.Cookie{Name: FlowCookie, Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})

	if !site.OIDC.admits(who.Email, who.User, realm, clients) {
		slog.Info("signed in at the provider, but not allowed here", "site", site.Domain, "user", who.User, "email", who.Email)
		name := who.Email
		if name == "" {
			name = who.User
		}
		render(w, http.StatusForbidden, page{Domain: site.Domain, Step: "denied", Email: name})
		return
	}

	value, expires := issueSession(site, who, now)
	http.SetCookie(w, &http.Cookie{Name: SessionCookie, Value: value, Path: "/", Expires: expires,
		MaxAge: int(site.SessionTTL.Seconds()), Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	slog.Info("signed in", "site", site.Domain, "user", who.User, "email", who.Email)
	http.Redirect(w, r, flow.redirect, http.StatusSeeOther)
}

// exchange trades the code for tokens, verifies the ID token, and reads the identity and roles.
// Roles come from the access token when it is a JWT this provider signed for this client, which
// is where Keycloak puts them by default; otherwise from the ID token.
func (s *Server) exchange(ctx context.Context, site *Site, flow *oidcFlow, code string, now time.Time) (identity, map[string]bool, map[string]map[string]bool, error) {
	cfg := site.OIDC
	if code == "" {
		return identity{}, nil, nil, errors.New("no code in the callback")
	}
	state, err := s.providers.state(ctx, cfg, now)
	if err != nil {
		return identity{}, nil, nil, err
	}
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {flow.redirectURI},
		"client_id":     {cfg.ClientID},
		"code_verifier": {flow.verifier},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, backchannelURL(cfg, state.doc.TokenEndpoint),
		strings.NewReader(form.Encode()))
	if err != nil {
		return identity{}, nil, nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cfg.ClientSecret != "" {
		req.SetBasicAuth(url.QueryEscape(cfg.ClientID), url.QueryEscape(cfg.ClientSecret))
	}
	resp, err := s.providers.client.Do(req)
	if err != nil {
		return identity{}, nil, nil, err
	}
	defer resp.Body.Close()
	var tokens tokenResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tokens); err != nil {
		return identity{}, nil, nil, fmt.Errorf("the token response: %w", err)
	}
	if resp.StatusCode != http.StatusOK || tokens.IDToken == "" {
		return identity{}, nil, nil, fmt.Errorf("the token endpoint answered %d: %s %s", resp.StatusCode, tokens.Error, tokens.Description)
	}

	claims, err := s.providers.verify(ctx, cfg, state, tokens.IDToken, now)
	if err != nil {
		return identity{}, nil, nil, fmt.Errorf("the ID token: %w", err)
	}
	if !claimAudience(claims, cfg.ClientID) {
		return identity{}, nil, nil, errors.New("the ID token was issued for another client")
	}
	if azp := claimString(claims, "azp"); azp != "" && azp != cfg.ClientID {
		return identity{}, nil, nil, errors.New("the ID token was issued to another party")
	}
	if claimString(claims, "nonce") != flow.nonce {
		return identity{}, nil, nil, errors.New("the ID token does not answer this sign-in")
	}

	who := identity{Email: normaliseEmail(claimString(claims, "email")), User: claimString(claims, "preferred_username")}
	if who.User == "" {
		who.User = claimString(claims, "sub")
	}

	realm, clients := claimRoles(claims)
	if tokens.AccessToken != "" {
		if access, err := s.providers.verify(ctx, cfg, state, tokens.AccessToken, now); err == nil &&
			claimString(access, "azp") == cfg.ClientID && claimString(access, "sub") == claimString(claims, "sub") {
			realm, clients = claimRoles(access)
		}
	}
	return who, realm, clients, nil
}
