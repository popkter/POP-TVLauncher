package main

import (
	"strings"
	"testing"
)

func TestSavedWriteSettingsMode(t *testing.T) {
	for input, want := range map[string]string{"No operations.": "default", "WRITE_SETTINGS: allow; time=1s": "allow", "WRITE_SETTINGS: ignore": "ignore", "unknown output": ""} {
		if got := savedWriteSettingsMode(input); got != want {
			t.Fatalf("%q: got %q want %q", input, got, want)
		}
	}
}

func TestKeepDataRestoreThenConfigureWithoutReinstall(t *testing.T) {
	a, tv, calls := nativeFixture(t)
	disabled := map[string]bool{stock: true}
	home := bridge
	original := a.runOverride
	a.runOverride = func(args ...string) (string, error) {
		out, err := original(args...)
		s := strings.Join(args, " ")
		for _, name := range []string{pkg, "com.ts.launcher", stock} {
			if strings.Contains(s, "'pm' 'disable-user'") && strings.HasSuffix(s, "'"+name+"'") {
				disabled[name] = true
			}
			if strings.Contains(s, "'pm' 'enable'") && strings.HasSuffix(s, "'"+name+"'") {
				disabled[name] = false
			}
			if strings.Contains(s, "'pm' 'list' 'packages' '-d'") && strings.HasSuffix(s, "'"+name+"'") {
				if disabled[name] {
					return "package:" + name, nil
				}
				return "", nil
			}
		}
		if strings.Contains(s, "set-home-activity") {
			if strings.Contains(s, stockHome) {
				home = stockHome
			} else {
				home = bridge
			}
		}
		if strings.Contains(s, "resolve-activity") {
			return home, nil
		}
		if strings.Contains(s, "get-role-holders") {
			return strings.SplitN(home, "/", 2)[0], nil
		}
		if strings.Contains(s, "query-activities") && disabled["com.ts.launcher"] && !strings.Contains(s, "'--query-flags' '512'") {
			return "", nil
		}
		return out, err
	}
	if err := a.restoreNative(tv, false); err != nil {
		t.Fatal(err)
	}
	if !disabled[pkg] || !disabled["com.ts.launcher"] || disabled[stock] {
		t.Fatal("restore did not disable only PTV")
	}
	if err := a.install(tv, false); err != nil {
		t.Fatal(err)
	}
	if disabled[pkg] || disabled["com.ts.launcher"] || !disabled[stock] || home != bridge {
		t.Fatal("configuration did not reactivate PTV")
	}
	all := strings.Join(*calls, "\n")
	for _, forbidden := range []string{"uninstall", "install -r", "'pm' 'clear'"} {
		// A newly introduced companion may be installed; existing PTV data must remain intact.
		filtered := all
		if forbidden == "install -r" {
			var lines []string
			for _, line := range strings.Split(all, "\n") {
				if !strings.Contains(line, "NetworkBridge.apk") {
					lines = append(lines, line)
				}
			}
			filtered = strings.Join(lines, "\n")
		}
		if strings.Contains(filtered, forbidden) {
			t.Fatalf("preservation flow used %s", forbidden)
		}
	}
	if a.state.Checks[4].Status != "ok" {
		t.Fatal(a.state.Checks)
	}
}

func TestConfigureInstallsOnlyWhenMissing(t *testing.T) {
	for _, exists := range []bool{true, false} {
		a, tv, calls := fakeApp(t)
		original := a.runOverride
		a.runOverride = func(args ...string) (string, error) {
			out, err := original(args...)
			s := strings.Join(args, " ")
			if strings.Contains(s, "'pm' 'list' 'packages' '--user' '0' '"+pkg+"'") && exists {
				return "package:" + pkg, nil
			}
			return out, err
		}
		if err := a.install(tv, false); err != nil {
			t.Fatal(err)
		}
		all := strings.Join(*calls, "\n")
		installedPTV := false
		for _, line := range strings.Split(all, "\n") {
			if strings.Contains(line, "install -r") && strings.Contains(line, "PTV.apk") {
				installedPTV = true
			}
		}
		if installedPTV == exists {
			t.Fatalf("wrong installation behavior: exists=%v", exists)
		}
		if !strings.Contains(all, "'pm' 'enable' '--user' '0' '"+pkg+"'") {
			t.Fatal("did not enable PTV before configuration")
		}
	}
}

func TestInstallAPKDoesNotConfigureDevice(t *testing.T) {
	a, tv, calls := fakeApp(t)
	if err := a.installAPK(tv); err != nil {
		t.Fatal(err)
	}
	if len(*calls) != 1 || !strings.Contains((*calls)[0], "install -r") {
		t.Fatal("installation changed device settings", *calls)
	}
}
