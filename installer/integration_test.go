package main

import (
	"os"
	"path/filepath"
	"testing"
)

// Opt-in integration test; intentionally restricted to the local emulator.
func TestEmulatorInstallAndRestore(t *testing.T) {
	if os.Getenv("PTV_TEST_EMULATOR") != "1" {
		t.Skip("set PTV_TEST_EMULATOR=1 to test local emulator-5554")
	}
	adb := os.Getenv("PTV_TEST_ADB")
	if adb == "" {
		t.Fatal("PTV_TEST_ADB is required")
	}
	dir := t.TempDir()
	if e := os.MkdirAll(filepath.Join(dir, "payload"), 0700); e != nil {
		t.Fatal(e)
	}
	for _, name := range []string{"PTV.apk", "HomeBridge.apk"} {
		data, e := assets.ReadFile("payload/" + name)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(filepath.Join(dir, "payload", name), data, 0600); e != nil {
			t.Fatal(e)
		}
	}
	a := &App{dir: dir, adb: adb}
	if e := a.inspect("emulator-5554"); e != nil {
		t.Fatal(e)
	}
	tv, _ := a.selected()
	b, e := a.snapshot(tv)
	if e != nil {
		t.Fatal(e)
	}
	t.Logf("Original home: %s; accessibility: %s; secure permission: %v", b.Home, b.Services, b.SecureGranted)
	defer func() {
		if e := a.restore(tv); e != nil {
			t.Errorf("Restore failed: %v; backup at %s", e, dir)
		}
	}()
	if e := a.install(tv, false); e != nil {
		t.Fatal(e)
	}
	for _, c := range a.state.Checks {
		t.Logf("%s: %s (%s)", c.Name, c.Status, c.Detail)
		if c.Status == "warning" {
			if c.Name == "默认桌面" && c.Detail == "com.google.android.tvlauncher/.MainActivity" {
				holder, e := a.sh(tv.Serial, "cmd", "role", "get-role-holders", "--user", "0", "android.app.role.HOME")
				if e != nil || holder != pkg {
					t.Errorf("HOME role not set: %s %v", holder, e)
				}
				t.Log("Emulator forces Google TV HOME despite PTV role; warning correctly retained")
			} else {
				t.Errorf("check failed: %s", c.Name)
				d, _ := a.sh(tv.Serial, "dumpsys", "accessibility")
				t.Log(d)
			}
		}
	}

	pending, e := a.sh(tv.Serial, "settings", "get", "secure", "ptv_installer_shortcuts")
	if e != nil || pending != "null" {
		t.Errorf("installer request not consumed: %q %v", pending, e)
	}
}
