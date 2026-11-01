package main

import (
	"os"
	"strings"
	"testing"
)

func nativeFixture(t *testing.T) (*App, *TV, *[]string) {
	a, tv, calls := fakeApp(t)
	tv.BridgeEligible = true
	tv.SDK = 34
	installed := map[string]bool{stock: true, pkg: true, "com.ts.launcher": true}
	services := service + ":other/.Service"
	original := a.runOverride
	a.runOverride = func(args ...string) (string, error) {
		out, err := original(args...)
		s := strings.Join(args, " ")
		switch {
		case strings.Contains(s, "'pm' 'list' 'packages'"):
			for name, yes := range installed {
				if yes && strings.HasSuffix(s, "'"+name+"'") {
					return "package:" + name, nil
				}
			}
			return "", nil
		case strings.Contains(s, "query-activities"):
			return bridge, nil
		case strings.Contains(s, "resolve-activity"):
			return stockHome, nil
		case strings.Contains(s, "get-role-holders"):
			return stock, nil
		case strings.Contains(s, "'pm' 'uninstall'"):
			for name := range installed {
				if strings.HasSuffix(s, "'"+name+"'") {
					installed[name] = false
				}
			}
			return "Success", nil
		case strings.Contains(s, "'settings' 'get' 'secure' 'enabled_accessibility_services'"):
			return services, nil
		case strings.Contains(s, "'settings' 'put' 'secure' 'enabled_accessibility_services'"):
			services = "other/.Service"
			return "", nil
		}
		return out, err
	}
	return a, tv, calls
}

func TestNativeRestoreRemovesPTVAndPreservesOtherServices(t *testing.T) {
	a, tv, calls := nativeFixture(t)
	if err := a.save(tv, &Backup{Identity: tv.Identity, Home: bridge, SecureGranted: true, Services: service}); err != nil {
		t.Fatal(err)
	}
	if err := a.restoreNative(tv, true); err != nil {
		t.Fatal(err)
	}
	all := strings.Join(*calls, "\n")
	for _, expected := range []string{"'pm' 'enable' '--user' '0' '" + stock + "'", "'pm' 'revoke'", "'input' 'keyevent' 'KEYCODE_HOME'", "'pm' 'uninstall' '--user' '0' '" + pkg + "'", "'pm' 'uninstall' '--user' '0' 'com.ts.launcher'", "'enabled_accessibility_services' 'other/.Service'"} {
		if !strings.Contains(all, expected) {
			t.Fatalf("missing %s", expected)
		}
	}
	if strings.Contains(all, "'accessibility_enabled' '0'") {
		t.Fatal("disabled another accessibility service")
	}
	if strings.Index(all, "get-role-holders") > strings.Index(all, "'pm' 'uninstall'") {
		t.Fatal("removed fallback before verifying home")
	}
	if _, err := os.Stat(a.backupPath(tv)); !os.IsNotExist(err) {
		t.Fatal("old baseline was not archived")
	}
	if a.state.TV.Home != stockHome || a.state.Backup {
		t.Fatal("stale state")
	}
}

func TestNativeRestoreWithoutBackup(t *testing.T) {
	a, tv, _ := nativeFixture(t)
	if err := a.restoreNative(tv, true); err != nil {
		t.Fatal(err)
	}
	// Idempotent: interrupted removal / repeated clicks do not need old APKs.
	if err := a.restoreNative(tv, true); err != nil {
		t.Fatal(err)
	}
}

func TestNativeRestoreStopsSafely(t *testing.T) {
	for _, failure := range []string{"unsupported", "missing stock", "foreign bridge", "wrong home", "wrong role", "uninstall failure", "package remains"} {
		t.Run(failure, func(t *testing.T) {
			a, tv, calls := nativeFixture(t)
			original := a.runOverride
			if failure == "unsupported" {
				tv.BridgeEligible = false
			}
			a.runOverride = func(args ...string) (string, error) {
				out, err := original(args...)
				s := strings.Join(args, " ")
				switch {
				case failure == "missing stock" && strings.Contains(s, "'pm' 'list'") && strings.HasSuffix(s, "'"+stock+"'"):
					return "", nil
				case failure == "foreign bridge" && strings.Contains(s, "query-activities"):
					return "com.ts.launcher/.Other", nil
				case failure == "wrong home" && strings.Contains(s, "resolve-activity"):
					return bridge, nil
				case failure == "wrong role" && strings.Contains(s, "get-role-holders"):
					return pkg, nil
				case failure == "uninstall failure" && strings.Contains(s, "'pm' 'uninstall'"):
					return "Failure [DELETE_FAILED_INTERNAL_ERROR]", nil
				case failure == "package remains" && strings.Contains(s, "'pm' 'list'") && strings.HasSuffix(s, "'"+pkg+"'"):
					return "package:" + pkg, nil
				}
				return out, err
			}
			if err := a.restoreNative(tv, true); err == nil {
				t.Fatal("reported success")
			}
			all := strings.Join(*calls, "\n")
			if failure != "uninstall failure" && failure != "package remains" && strings.Contains(all, "'pm' 'uninstall'") {
				t.Fatal("removed fallback on preflight failure")
			}
			if a.state.Progress == 100 {
				t.Fatal("false completion")
			}
		})
	}
}

func TestNativeRestoreDisablesAccessibilityWhenEmpty(t *testing.T) {
	a, tv, calls := nativeFixture(t)
	original := a.runOverride
	cleared := false
	a.runOverride = func(args ...string) (string, error) {
		out, err := original(args...)
		s := strings.Join(args, " ")
		if strings.Contains(s, "'settings' 'get' 'secure' 'enabled_accessibility_services'") {
			if cleared {
				return "", nil
			}
			return service, nil
		}
		if strings.Contains(s, "'settings' 'put' 'secure' 'enabled_accessibility_services'") {
			cleared = true
		}
		return out, err
	}
	if err := a.restoreNative(tv, true); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.Join(*calls, "\n"), "'accessibility_enabled' '0'") {
		t.Fatal("left accessibility enabled without any services")
	}
}
