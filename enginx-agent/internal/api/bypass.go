package api

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// The host's custom files can answer a protected site's visitors before NGINX checks who they are:
// `return` and `rewrite` run in the rewrite phase, ahead of auth_basic and auth_request. Nothing
// the platform renders can stop that, and `nginx -t` accepts it. VERIFY catches it for the paths
// it asks for; this catches the directives themselves, wherever they apply, and says where they are.
//
// Advisory only: a custom file is root's on the host, and its author may mean it. So it is
// reported with the validation output, and the deployment goes ahead.

var (
	protectedMarkers = []string{"\n    auth_basic \"", "\n    auth_request /.enginx/auth/check;"}
	bypassDirective  = regexp.MustCompile(`^\s*(return|rewrite)\b`)
	includeDirective = regexp.MustCompile(`^\s*include\s+([^;\s]+)\s*;`)
)

const maxIncludeDepth = 5

// protectionWarnings lists the `return` and `rewrite` directives in the custom files each
// protected site of a release includes, following their own includes.
func protectionWarnings(releaseDir, customRoot, confDir string) []string {
	sites, _ := filepath.Glob(filepath.Join(releaseDir, "conf.d", "*.conf"))
	var warnings []string
	for _, siteFile := range sites {
		content, err := os.ReadFile(siteFile)
		if err != nil || !isProtected(content) {
			continue
		}
		domain := strings.TrimSuffix(filepath.Base(siteFile), ".conf")
		hooks := []string{
			filepath.Join(customRoot, "server", "*.conf"),
			filepath.Join(customRoot, "sites", domain, "*.conf"),
		}
		seen := map[string]bool{}
		for _, pattern := range hooks {
			for _, file := range glob(pattern) {
				warnings = append(warnings, scanForBypass(domain, file, confDir, seen, 0)...)
			}
		}
	}
	return warnings
}

func isProtected(siteConfig []byte) bool {
	for _, marker := range protectedMarkers {
		if bytes.Contains(siteConfig, []byte(marker)) {
			return true
		}
	}
	return false
}

func scanForBypass(domain, file, confDir string, seen map[string]bool, depth int) []string {
	if seen[file] || depth > maxIncludeDepth {
		return nil
	}
	seen[file] = true
	handle, err := os.Open(file)
	if err != nil {
		return nil
	}
	defer handle.Close()

	var warnings []string
	scanner := bufio.NewScanner(handle)
	for line := 1; scanner.Scan(); line++ {
		text := scanner.Text()
		if hash := strings.IndexByte(text, '#'); hash >= 0 {
			text = text[:hash]
		}
		if match := bypassDirective.FindStringSubmatch(text); match != nil {
			warnings = append(warnings, fmt.Sprintf(
				"%s is protected, but %s:%d has `%s`, which answers before the visitor is asked to sign in",
				domain, file, line, match[1]))
		}
		if match := includeDirective.FindStringSubmatch(text); match != nil {
			pattern := match[1]
			if !filepath.IsAbs(pattern) {
				// NGINX resolves a relative include against its configuration directory.
				pattern = filepath.Join(confDir, pattern)
			}
			for _, included := range glob(pattern) {
				warnings = append(warnings, scanForBypass(domain, included, confDir, seen, depth+1)...)
			}
		}
	}
	return warnings
}

func glob(pattern string) []string {
	matches, _ := filepath.Glob(pattern)
	sort.Strings(matches)
	return matches
}

// withProtectionWarnings appends the warnings to the validation output, which both a push
// activation and a pull job report, and which the platform records on the deployment.
func withProtectionWarnings(output string, warnings []string) string {
	if len(warnings) == 0 {
		return output
	}
	var out strings.Builder
	out.WriteString(strings.TrimRight(output, "\n"))
	out.WriteString("\n")
	for _, warning := range warnings {
		out.WriteString("\nwarning: " + warning)
	}
	return out.String()
}
