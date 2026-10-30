package gate

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Server answers NGINX's auth_request checks and serves the sign-in pages.
//
// Every request arrives from NGINX over a unix socket, carrying X-Enginx-Site set by the rendered
// configuration, which overwrites anything a client sent under that name.
type Server struct {
	relay Relay
	now   func() time.Time

	mu    sync.RWMutex
	sites map[string]*Site

	codes   *codeStore
	byIP    *limiter
	byEmail *limiter
	// starts limits identity provider sign-ins begun per client. Generous: a whole office behind
	// one address signs in through it, and a full flow store makes room rather than refusing, so
	// this only has to stop a flood, not ration honest use.
	starts *limiter

	providers *providers
	oidcFlows *oidcFlows

	// Basic authentication on a login page: attempts per client and per username, form and
	// Authorization header alike, and a short memory of headers already checked, so a script
	// sending one on every request does not pay a SHA-512-crypt each time.
	passwordByIP   *limiter
	passwordByUser *limiter
	checkedHeaders *headerCache

	// events waits to be shipped to the platform's audit trail; oldPlatform says once that the
	// platform cannot take them.
	events      *eventLog
	oldPlatform sync.Once

	// background tracks codes still being handed to the platform, so a shutdown, or a test, can
	// wait for them.
	background sync.WaitGroup
}

// Wait blocks until every code handed off for mailing has been.
func (s *Server) Wait() {
	s.background.Wait()
}

func NewServer(relay Relay) *Server {
	return &Server{
		relay:   relay,
		now:     time.Now,
		sites:   map[string]*Site{},
		codes:   newCodeStore(),
		byIP:    newLimiter(10, 15*time.Minute),
		byEmail: newLimiter(5, 15*time.Minute),
		starts:  newLimiter(120, 15*time.Minute),

		passwordByIP:   newLimiter(20, 15*time.Minute),
		passwordByUser: newLimiter(10, 15*time.Minute),
		checkedHeaders: newHeaderCache(time.Minute),

		providers: newProviders(nil),
		oidcFlows: newOIDCFlows(),
		events:    newEventLog(),
	}
}

// SetProviderClient sets the client used to reach identity providers: the same trust as the
// relay, so a provider behind a private CA works with GATE_RELAY_CA.
func (s *Server) SetProviderClient(client *http.Client) {
	s.providers = newProviders(client)
}

// SetSites replaces the protected sites. Sessions are unaffected: they carry everything needed
// to check them, so a reload signs nobody out unless a site's key changed.
func (s *Server) SetSites(sites map[string]*Site) {
	s.mu.Lock()
	s.sites = sites
	s.mu.Unlock()
}

func (s *Server) site(r *http.Request) *Site {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.sites[r.Header.Get("X-Enginx-Site")]
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /check", s.handleCheck)
	mux.HandleFunc("GET /.enginx/auth/login", s.handleLoginForm)
	mux.HandleFunc("POST /.enginx/auth/login", s.handleLogin)
	mux.HandleFunc("POST /.enginx/auth/verify", s.handleVerify)
	mux.HandleFunc("GET /.enginx/auth/callback", s.handleCallback)
	mux.HandleFunc("GET /.enginx/auth/logout", s.handleLogout)
	return mux
}

// handleCheck is NGINX's auth_request subrequest: 204 with the address, or 401. Only the cookie
// is read, so answering costs one HMAC and no I/O.
func (s *Server) handleCheck(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site == nil {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	who, ok := s.sessionOf(site, r)
	if !ok && site.Mode == ModeBasic {
		// A script with credentials, rather than a browser with a session.
		who, ok = s.basicHeader(site, r)
	}
	if !ok {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	w.Header().Set("X-Enginx-Email", who.Email)
	w.Header().Set("X-Enginx-User", who.User)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) sessionOf(site *Site, r *http.Request) (identity, bool) {
	cookie, err := r.Cookie(SessionCookie)
	if err != nil {
		return identity{}, false
	}
	who, err := verifySession(site, cookie.Value, s.now())
	return who, err == nil
}

func (s *Server) unavailable(w http.ResponseWriter) {
	render(w, http.StatusServiceUnavailable, page{Domain: "Sign in", Step: "error",
		Note: "Sign-in is not available for this site right now."})
}

func (s *Server) handleLoginForm(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site == nil {
		s.unavailable(w)
		return
	}
	switch site.Mode {
	case ModeOIDC:
		s.startOIDC(w, r, site)
	case ModeBasic:
		render(w, http.StatusOK, page{Domain: site.Domain, Step: "password", Redirect: redirectFromQuery(r.URL.RawQuery)})
	default:
		render(w, http.StatusOK, page{Domain: site.Domain, Step: "email", Redirect: redirectFromQuery(r.URL.RawQuery)})
	}
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site != nil && site.Mode == ModeBasic {
		s.handlePasswordLogin(w, r, site)
		return
	}
	if site == nil || site.Mode != ModeEmailOTP {
		s.unavailable(w)
		return
	}
	if !sameOrigin(r) {
		http.Error(w, "cross-origin request refused", http.StatusForbidden)
		return
	}
	redirect := safeRedirect(r.PostFormValue("rd"))
	email := normaliseEmail(r.PostFormValue("email"))
	if len(email) > 254 || !strings.Contains(email, "@") {
		render(w, http.StatusOK, page{Domain: site.Domain, Step: "email", Redirect: redirect,
			Note: "That does not look like an email address."})
		return
	}
	now := s.now()
	if !s.byIP.allow(site.ID+"|"+r.Header.Get("X-Real-IP"), now) || !s.byEmail.allow(site.ID+"|"+email, now) {
		render(w, http.StatusTooManyRequests, page{Domain: site.Domain, Step: "email", Redirect: redirect,
			Note: "Too many attempts. Wait a few minutes and try again."})
		return
	}

	// An address that is not allowed gets a flow that can never succeed, and the same page, so
	// the form cannot be used to learn who is on the list.
	code := ""
	if !site.Admits(email) {
		s.record(site, r, Event{Kind: EventAccessDenied, Method: MethodCode, Email: email, Reason: "not-on-list"})
	} else {
		generated, err := randomCode()
		if err != nil {
			s.unavailable(w)
			return
		}
		code = generated
	}
	flow, err := s.codes.start(site.ID, email, code, redirect, now)
	if err != nil {
		s.unavailable(w)
		return
	}
	if code != "" {
		// In the background, so the reply takes as long for an allowed address as for any other:
		// waiting on the platform here would let the response time say who is on the list.
		// Failures are logged, not shown, for the same reason.
		s.background.Add(1)
		go func() {
			defer s.background.Done()
			if err := s.relay.SendCode(context.Background(), site, email, code); err != nil {
				slog.Error("could not have a sign-in code mailed", "site", site.Domain, "error", err)
			} else {
				slog.Info("sign-in code sent", "site", site.Domain, "email", email)
			}
		}()
	}
	http.SetCookie(w, &http.Cookie{Name: FlowCookie, Value: flow, Path: "/", MaxAge: int(codeValidFor.Seconds()),
		Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	render(w, http.StatusOK, page{Domain: site.Domain, Step: "code", Email: email, Redirect: redirect})
}

func (s *Server) handleVerify(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site == nil || site.Mode != ModeEmailOTP {
		s.unavailable(w)
		return
	}
	if !sameOrigin(r) {
		http.Error(w, "cross-origin request refused", http.StatusForbidden)
		return
	}
	cookie, err := r.Cookie(FlowCookie)
	if err != nil {
		render(w, http.StatusOK, page{Domain: site.Domain, Step: "email", Redirect: "/",
			Note: "That sign-in has expired. Ask for a new code."})
		return
	}
	now := s.now()
	flow, ok := s.codes.verify(cookie.Value, site.ID, strings.TrimSpace(r.PostFormValue("code")), now)
	if !ok {
		if flow != nil {
			s.record(site, r, Event{Kind: EventSignInFailed, Method: MethodCode, Email: flow.email, Reason: "wrong-code"})
		}
		render(w, http.StatusOK, page{Domain: site.Domain, Step: "code", Redirect: "/", Email: "your address",
			Note: "That code is not right, or has expired. After five wrong codes, ask for a new one."})
		return
	}

	value, expires := issueSession(site, identity{Email: flow.email, User: flow.email}, now)
	http.SetCookie(w, &http.Cookie{Name: SessionCookie, Value: value, Path: "/", Expires: expires,
		MaxAge: int(site.SessionTTL.Seconds()), Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	http.SetCookie(w, &http.Cookie{Name: FlowCookie, Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})
	slog.Info("signed in", "site", site.Domain, "email", flow.email)
	s.record(site, r, Event{Kind: EventSignedIn, Method: MethodCode, Email: flow.email})
	http.Redirect(w, r, flow.redirect, http.StatusSeeOther)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	site := s.site(r)
	if site == nil {
		s.unavailable(w)
		return
	}
	if who, ok := s.sessionOf(site, r); ok {
		s.record(site, r, Event{Kind: EventSignedOut, Method: methodOf(site), User: who.User, Email: who.Email})
	}
	http.SetCookie(w, &http.Cookie{Name: SessionCookie, Value: "", Path: "/", MaxAge: -1, Secure: true, HttpOnly: true})
	render(w, http.StatusOK, page{Domain: site.Domain, Step: "signed-out"})
}

// methodOf is how visitors sign in to a site, for an event that is not itself a sign-in.
func methodOf(site *Site) string {
	switch site.Mode {
	case ModeBasic:
		return MethodPassword
	case ModeOIDC:
		return MethodOIDC
	default:
		return MethodCode
	}
}

// redirectFromQuery reads rd from the login URL NGINX built: `login?rd=$request_uri`, where the
// original URI is appended raw. Everything after `rd=` is the redirect, query string included —
// splitting on & would cut it off at its own first parameter.
func redirectFromQuery(rawQuery string) string {
	if !strings.HasPrefix(rawQuery, "rd=") {
		return "/"
	}
	return safeRedirect(rawQuery[len("rd="):])
}

// safeRedirect keeps a redirect on this site. "//evil.example" and "/\evil.example" are
// protocol-relative to a browser, so only a single leading slash followed by something else is
// let through.
func safeRedirect(target string) string {
	if target == "" || target[0] != '/' || len(target) > 2048 {
		return "/"
	}
	// Any control character, not only CR and LF: browsers strip tabs and newlines from a URL, so
	// "/\t/evil.example" arrives as "//evil.example", protocol-relative to another site.
	for _, c := range target {
		if c < 0x20 || c == 0x7f {
			return "/"
		}
	}
	if strings.HasPrefix(target, "//") || strings.HasPrefix(target, "/\\") {
		return "/"
	}
	return target
}

// sameOrigin refuses a form posted from another site. Browsers send Origin on every POST; a
// request without one is not from a browser form, and is let through to the code check.
//
// The host is compared without its port. NGINX passes $host, which has none, while a browser's
// Origin carries one whenever the site is reached on a port other than 443, as it is behind any
// port mapping. A different port on the same name is not another site.
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Scheme != "https" {
		return false
	}
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	return strings.EqualFold(parsed.Hostname(), host)
}

// Watch reloads the site files whenever they change, until ctx ends.
func (s *Server) Watch(ctx context.Context, dir string, every time.Duration) {
	last := ""
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		if signature := dirSignature(dir); signature != last {
			sites, problems := LoadSites(dir)
			for _, problem := range problems {
				slog.Error("a protected site's gate file could not be read; that site refuses everyone", "error", problem)
			}
			s.SetSites(sites)
			slog.Info("protected sites loaded", "count", len(sites), "dir", dir)
			last = signature
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
