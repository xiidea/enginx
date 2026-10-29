package gate

import (
	"html/template"
	"net/http"
)

// Plain pages on the protected site's own domain. html/template escapes everything, which matters
// here more than anywhere: the domain and the address typed are the only dynamic parts.
var pages = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Sign in · {{.Domain}}</title>
<style>
body{font:16px/1.5 system-ui,sans-serif;background:#f6f6f4;color:#1d1d1b;display:grid;place-items:center;min-height:100vh;margin:0}
main{background:#fff;border:1px solid #ddd;border-radius:8px;padding:2rem;width:min(22rem,90vw)}
h1{font-size:1.15rem;margin:0 0 .25rem}p{color:#555;margin:.25rem 0 1rem;font-size:.92rem}
label{display:block;font-size:.85rem;margin-bottom:.25rem}
input{width:100%;box-sizing:border-box;padding:.55rem;font-size:1rem;border:1px solid #bbb;border-radius:6px}
button{margin-top:1rem;width:100%;padding:.6rem;font-size:1rem;border:0;border-radius:6px;background:#1d4ed8;color:#fff;cursor:pointer}
.code{letter-spacing:.3em;text-align:center;font-family:ui-monospace,monospace}
.note{color:#9a6212}a{color:#1d4ed8}
</style></head><body><main>
<h1>{{.Domain}}</h1>
{{if eq .Step "email"}}
<p>Enter your email address. If it is allowed here, we will send you a sign-in code.</p>
{{if .Note}}<p class="note">{{.Note}}</p>{{end}}
<form method="post" action="/.enginx/auth/login">
<input type="hidden" name="rd" value="{{.Redirect}}">
<label for="email">Email</label>
<input id="email" name="email" type="email" autocomplete="email" required autofocus>
<button type="submit">Send code</button>
</form>
{{else if eq .Step "code"}}
<p>If <strong>{{.Email}}</strong> may sign in here, a code is on its way. It works for 10 minutes.</p>
{{if .Note}}<p class="note">{{.Note}}</p>{{end}}
<form method="post" action="/.enginx/auth/verify">
<label for="code">Code</label>
<input id="code" class="code" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required autofocus>
<button type="submit">Sign in</button>
</form>
<p><a href="/.enginx/auth/login?rd={{.Redirect}}">Use a different address</a></p>
{{else if eq .Step "signed-out"}}
<p>You are signed out.</p>
<p><a href="/.enginx/auth/login">Sign in again</a></p>
{{else}}
<p class="note">{{.Note}}</p>
{{end}}
</main></body></html>`))

type page struct {
	Domain   string
	Step     string
	Email    string
	Redirect string
	Note     string
}

func render(w http.ResponseWriter, status int, p page) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	// Nothing on these pages is meant to be framed, scripted or loaded from elsewhere.
	h.Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(status)
	_ = pages.Execute(w, p)
}
