package main

import (
	"strings"
	"testing"
)

func TestEligibleInstallAlwaysCompletesBridgeMode(t *testing.T) {
	for _, initialHome := range []string{stockHome, bridge} {
		for _, bridgeExists := range []bool{true, false} {
			t.Run(initialHome+"/"+map[bool]string{true: "reuse", false: "new"}[bridgeExists], func(t *testing.T) {
				a, tv, calls := fakeApp(t)
				tv.BridgeEligible = true
				tv.SDK = 34
				tv.Home = initialHome
				disabled := false
				selected := false
				original := a.runOverride
				a.runOverride = func(args ...string) (string, error) {
					out, err := original(args...)
					s := strings.Join(args, " ")
					switch {
					case strings.Contains(s, "'pm' 'list' 'packages' '--user' '0' 'com.ts.launcher'"):
						if bridgeExists {
							return "package:com.ts.launcher", nil
						}
						return "", nil
					case strings.Contains(s, "query-activities"):
						return bridge, nil
					case strings.Contains(s, "'pm' 'list' 'packages' '-d'"):
						if disabled {
							return "package:" + stock, nil
						}
						return "", nil
					case strings.Contains(s, "'pm' 'disable-user'"):
						disabled = true
					case strings.Contains(s, "set-home-activity"):
						selected = true
					case strings.Contains(s, "resolve-activity"):
						if selected {
							return bridge, nil
						}
						return initialHome, nil
					case strings.Contains(s, "get-role-holders"):
						return "com.ts.launcher", nil
					}
					return out, err
				}
				// An unchecked/old UI must not skip required compatibility.
				if err := a.install(tv, false); err != nil {
					t.Fatal(err)
				}
				if !disabled || a.state.Checks[4].Status != "ok" {
					t.Fatal("incomplete compatibility", a.state.Checks)
				}
				all := strings.Join(*calls, "\n")
				if strings.Contains(all, "HomeBridge.apk") == bridgeExists {
					t.Fatal("incorrect bridge reuse")
				}
				b, err := a.load(tv)
				if err != nil {
					t.Fatal(err)
				}
				if b.BridgeInstalled == bridgeExists || !b.StockDisabled {
					t.Fatal("incorrect recovery ownership")
				}
			})
		}
	}
}

func TestEligibleInstallRejectsForeignBridge(t *testing.T) {
	a, tv, calls := fakeApp(t)
	tv.BridgeEligible = true
	original := a.runOverride
	a.runOverride = func(args ...string) (string, error) {
		out, err := original(args...)
		s := strings.Join(args, " ")
		if strings.Contains(s, "'pm' 'list' 'packages'") {
			return "package:com.ts.launcher", nil
		}
		if strings.Contains(s, "query-activities") {
			return "com.ts.launcher/.Other", nil
		}
		return out, err
	}
	if err := a.install(tv, false); err == nil {
		t.Fatal("accepted unrelated app")
	}
	all := strings.Join(*calls, "\n")
	if strings.Contains(all, "disable-user") || strings.Contains(all, "install -r") {
		t.Fatal("mutated before rejecting conflict")
	}
}

func TestEligibleVerifyWarnsWhenStockIsEnabled(t *testing.T) {
	a, tv, _ := fakeApp(t)
	tv.BridgeEligible = true
	tv.SDK = 34
	original := a.runOverride
	a.runOverride = func(args ...string) (string, error) {
		s := strings.Join(args, " ")
		if strings.Contains(s, "resolve-activity") {
			return bridge, nil
		}
		if strings.Contains(s, "get-role-holders") {
			return "com.ts.launcher", nil
		}
		return original(args...)
	}
	if err := a.verify(tv); err != nil {
		t.Fatal(err)
	}
	if a.state.Checks[4].Status != "warning" {
		t.Fatal("missed enabled stock launcher")
	}
}
