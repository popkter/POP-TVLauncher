package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestEndpoints(t *testing.T) {
	for _, s := range []string{"192.168.1.20", "192.168.1.20:5555", "tv.local:12345"} {
		if _, e := endpoint(s); e != nil {
			t.Fatal(s, e)
		}
	}
	for _, s := range []string{"a;reboot", "x:0", "x:65536", "$(whoami)", "", "a:abc", "x:1;reboot", "-a:5555"} {
		if _, e := endpoint(s); e == nil {
			t.Fatal("accepted " + s)
		}
	}
}
func TestPreserveOtherServices(t *testing.T) {
	raw := "other/.A:" + pkg + "/" + pkg + ".SwitcherService:third/.B:other/.A"
	if got := mergeServices(raw, true); got != "other/.A:third/.B:"+service {
		t.Fatal(got)
	}
	if got := mergeServices(raw, false); got != "other/.A:third/.B" {
		t.Fatal(got)
	}
	if got := mergeServices("null", false); got != "" {
		t.Fatal(got)
	}
}
func TestDevices(t *testing.T) {
	d := parseDevices("List of devices attached\na device product:tv\nb unauthorized\nc offline\n* daemon started successfully *")
	if len(d) != 3 || d[1].State != "unauthorized" {
		t.Fatal(d)
	}
}
func TestBoundNotJustRegistered(t *testing.T) {
	if bound("Enabled services:{" + service + "}\nBound services:{}") {
		t.Fatal("false positive")
	}
	if !bound("Bound services:{" + service + "}") {
		t.Fatal("missed bound service")
	}
}
func TestShellQuote(t *testing.T) {
	if quote("x'; reboot") != "'x'\\''; reboot'" {
		t.Fatal(quote("x'; reboot"))
	}
}
func TestAuthAndOrigin(t *testing.T) {
	a := &App{token: "secret", origin: "http://127.0.0.1:1234"}
	for _, v := range []struct {
		token, origin, host, method string
		status                      int
	}{{"", "", "127.0.0.1:1234", "GET", 403}, {"secret", "", "evil.example", "GET", 403}, {"secret", "https://evil.example", "127.0.0.1:1234", "POST", 403}, {"secret", "", "127.0.0.1:1234", "GET", 200}} {
		r := httptest.NewRequest(v.method, "http://"+v.host+"/api/state", nil)
		r.Header.Set("X-PTV-Token", v.token)
		r.Header.Set("Origin", v.origin)
		w := httptest.NewRecorder()
		a.api(w, r)
		if w.Code != v.status {
			t.Fatalf("%+v: %d", v, w.Code)
		}
	}
}
func TestConcurrentMutationRejected(t *testing.T) {
	a := &App{token: "secret", origin: "http://127.0.0.1:1234", state: State{Busy: true}}
	r := httptest.NewRequest("POST", a.origin+"/api/action", strings.NewReader(`{"action":"install"}`))
	r.Header.Set("X-PTV-Token", "secret")
	r.Header.Set("Origin", a.origin)
	w := httptest.NewRecorder()
	a.api(w, r)
	if w.Code != 409 {
		t.Fatal(w.Code)
	}
}
func TestBackupCannotCrossDevices(t *testing.T) {
	a := &App{dir: t.TempDir()}
	tv := &TV{Identity: "first"}
	if e := a.save(tv, &Backup{Identity: "second"}); e != nil {
		t.Fatal(e)
	}
	if _, e := a.load(tv); e == nil {
		t.Fatal("accepted foreign backup")
	}
}
func TestBackupKeepsBaseline(t *testing.T) {
	a := &App{dir: t.TempDir()}
	tv := &TV{Identity: "first"}
	if e := a.save(tv, &Backup{Identity: "first", Home: "old/.Home"}); e != nil {
		t.Fatal(e)
	}
	a.runOverride = func(...string) (string, error) { t.Fatal("should not re-snapshot modified state"); return "", nil }
	b, e := a.snapshot(tv)
	if e != nil || b.Home != "old/.Home" {
		t.Fatal(b, e)
	}
}
func fakeApp(t *testing.T) (*App, *TV, *[]string) {
	t.Helper()
	calls := []string{}
	tv := &TV{Identity: "tv", Serial: "test:5555", Home: "old/.Home"}
	a := &App{dir: t.TempDir(), state: State{TV: tv}}
	a.runOverride = func(args ...string) (string, error) {
		s := strings.Join(args, " ")
		calls = append(calls, s)
		switch {
		case strings.Contains(s, "resolve-activity"):
			return "old/.Home", nil
		case strings.Contains(s, "'enabled_accessibility_services'"):
			return "other/.Service", nil
		case strings.Contains(s, "'accessibility_enabled'"):
			return "1", nil
		case strings.Contains(s, "install -r"):
			return "Success", nil
		case strings.Contains(s, "'dumpsys' 'accessibility'"):
			return "Bound services:{" + service + "}", nil
		case strings.Contains(s, "'dumpsys' 'activity'"):
			return "isForeground=true", nil
		case strings.Contains(s, "'dumpsys' 'package'"):
			return "android.permission.WRITE_SECURE_SETTINGS: granted=true", nil
		case strings.Contains(s, "'ptv_installer_shortcuts'"):
			return "null", nil
		case strings.Contains(s, "'pm' 'path' '"+pkg+"'"):
			return "package:/data/app/base.apk", nil
		}
		return "", nil
	}
	return a, tv, &calls
}
func TestInstallPreservesAndVerifies(t *testing.T) {
	a, tv, calls := fakeApp(t)
	if e := a.install(tv, false); e != nil {
		t.Fatal(e)
	}
	all := strings.Join(*calls, "\n")
	if !strings.Contains(all, "'other/.Service:"+service+"'") {
		t.Fatal("lost other service")
	}
	if strings.Contains(all, "disable-user") || strings.Contains(all, "uninstall") {
		t.Fatal("unexpected destructive action")
	}
	if len(a.state.Checks) != 8 {
		t.Fatal(a.state.Checks)
	}
	if a.state.Checks[4].Status != "warning" {
		t.Fatal("ignored firmware home routing")
	}
	if _, e := os.Stat(a.backupPath(tv)); e != nil {
		t.Fatal(e)
	}
}
func TestInstallFailureStopsBeforePermissions(t *testing.T) {
	a, tv, calls := fakeApp(t)
	base := a.runOverride
	a.runOverride = func(args ...string) (string, error) {
		if strings.Contains(strings.Join(args, " "), "install -r") {
			return "", errors.New("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
		}
		return base(args...)
	}
	if e := a.install(tv, false); e == nil {
		t.Fatal("expected failure")
	}
	if strings.Contains(strings.Join(*calls, "\n"), "'grant'") {
		t.Fatal("changed permissions after failed install")
	}
}
func TestUnsupportedBridgeNeverDisablesLauncher(t *testing.T) {
	a, tv, calls := fakeApp(t)
	if e := a.install(tv, true); e == nil {
		t.Fatal("expected refusal")
	}
	if strings.Contains(strings.Join(*calls, "\n"), "disable-user") {
		t.Fatal("disabled launcher")
	}
}
func TestRestoreStopsBeforeUninstallWhenHomeFails(t *testing.T) {
	a, tv, calls := fakeApp(t)
	b := &Backup{Identity: tv.Identity, Home: "different/.Home", StockDisabled: true, BridgeInstalled: true}
	if e := a.save(tv, b); e != nil {
		t.Fatal(e)
	}
	if e := a.restore(tv); e == nil {
		t.Fatal("expected home mismatch")
	}
	all := strings.Join(*calls, "\n")
	if !strings.Contains(all, "'pm' 'enable'") {
		t.Fatal("did not re-enable stock")
	}
	if strings.Contains(all, "uninstall") {
		t.Fatal("removed fallback before verifying original home")
	}
}
func TestRestorePreservesNewServicesAndArchives(t *testing.T) {
	a, tv, calls := fakeApp(t)
	b := &Backup{Identity: tv.Identity, Home: tv.Home, SecureGranted: false}
	if e := a.save(tv, b); e != nil {
		t.Fatal(e)
	}
	if e := a.restore(tv); e != nil {
		t.Fatal(e)
	}
	all := strings.Join(*calls, "\n")
	if !strings.Contains(all, "'enabled_accessibility_services' 'other/.Service'") {
		t.Fatal("lost other service")
	}
	if !strings.Contains(all, "'revoke'") {
		t.Fatal("did not revoke installer-granted permission")
	}
	if a.state.Backup {
		t.Fatal("stale backup")
	}
}
func TestStateDoesNotExposeIdentity(t *testing.T) {
	b, _ := json.Marshal(TV{Identity: "secret-device-id"})
	if strings.Contains(string(b), "secret-device-id") {
		t.Fatal(string(b))
	}
}

func TestBoundServiceLabelOnAndroidTV(t *testing.T) {
	if !bound("Bound services:{Service[label=PTV 快速切换, feedbackType[FEEDBACK_GENERIC]]}") {
		t.Fatal("Android TV bound service label not recognized")
	}
	if bound("Enabled services:{Service[label=PTV 快速切换]}") {
		t.Fatal("enabled is not bound")
	}
}

func TestMultipleBoundServices(t *testing.T) {
	if !bound("Bound services:{Service[label=TalkBack],\n Service[label=PTV 快速切换]}\nEnabled services:{}") {
		t.Fatal("missed second bound service")
	}
	if bound("Bound services:{}\nEnabled services:{{" + service + "}}") {
		t.Fatal("enabled service leaked into bound list")
	}
}

// Updating a TV already using the bridge must not replace its HOME role,
// overwrite the pre-existing bridge, or claim it for later uninstallation.
func TestInstallPreservesExistingBridge(t *testing.T) {
	for _, requested := range []bool{false, true} {
		t.Run(fmt.Sprint(requested), func(t *testing.T) {
			a, tv, calls := fakeApp(t)
			tv.Home = bridge
			original := a.runOverride
			a.runOverride = func(args ...string) (string, error) {
				out, err := original(args...)
				if strings.Contains(strings.Join(args, " "), "resolve-activity") {
					return bridge, nil
				}
				return out, err
			}
			if err := a.install(tv, requested); err != nil {
				t.Fatal(err)
			}
			all := strings.Join(*calls, "\n")
			if !strings.Contains(all, "'set-home-activity' '--user' '0' '"+bridge+"'") {
				t.Fatal("bridge role not preserved")
			}
			for _, forbidden := range []string{"'set-home-activity' '--user' '0' '" + activity + "'", "HomeBridge.apk", "disable-user", "uninstall"} {
				if strings.Contains(all, forbidden) {
					t.Fatalf("unexpected mutation: %s", forbidden)
				}
			}
			b, err := a.load(tv)
			if err != nil {
				t.Fatal(err)
			}
			if b.BridgeInstalled || b.StockDisabled {
				t.Fatal("claimed original configuration")
			}
		})
	}
}

func TestVerifyDetectsHomeRoleMismatch(t *testing.T) {
	for _, role := range []string{pkg, "com.ts.launcher", ""} {
		t.Run(role, func(t *testing.T) {
			a, tv, _ := fakeApp(t)
			tv.SDK = 34
			original := a.runOverride
			a.runOverride = func(args ...string) (string, error) {
				s := strings.Join(args, " ")
				if strings.Contains(s, "resolve-activity") {
					return bridge, nil
				}
				if strings.Contains(s, "get-role-holders") {
					return role, nil
				}
				return original(args...)
			}
			if err := a.verify(tv); err != nil {
				t.Fatal(err)
			}
			ok := a.state.Checks[4].Status == "ok"
			if ok != (role == "com.ts.launcher") {
				t.Fatalf("role %q: %+v", role, a.state.Checks[4])
			}
		})
	}
}
