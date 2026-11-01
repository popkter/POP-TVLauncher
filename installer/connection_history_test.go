package main

import (
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestHistoryPersistsDeduplicatesAndDeletes(t *testing.T) {
	a := &App{dir: t.TempDir()}
	for _, address := range []string{"192.168.1.2", "TV.local:5556", " 192.168.1.2:5555 "} {
		if err := a.changeHistory(address, false); err != nil {
			t.Fatal(err)
		}
	}
	b := &App{dir: a.dir}
	if err := b.loadHistory(); err != nil {
		t.Fatal(err)
	}
	want := []string{"192.168.1.2:5555", "tv.local:5556"}
	if !reflect.DeepEqual(b.state.ConnectionHistory, want) {
		t.Fatal(b.state.ConnectionHistory)
	}
	if err := b.changeHistory("192.168.1.2", true); err != nil {
		t.Fatal(err)
	}
	c := &App{dir: a.dir}
	if err := c.loadHistory(); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(c.state.ConnectionHistory, want[1:]) {
		t.Fatal("deleted item returned", c.state.ConnectionHistory)
	}
	if err := c.changeHistory("192.168.1.2", false); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(c.state.ConnectionHistory, want) {
		t.Fatal("reconnect not remembered")
	}
}

func TestHistoryDeletionDoesNotDisconnectAndRequiresAuth(t *testing.T) {
	a := &App{dir: t.TempDir(), token: "secret", origin: "http://127.0.0.1:1234", state: State{Busy: true, TV: &TV{Serial: "192.168.1.2:5555"}}}
	a.runOverride = func(...string) (string, error) { t.Fatal("deleting history must not invoke adb"); return "", nil }
	if err := a.changeHistory("192.168.1.2", false); err != nil {
		t.Fatal(err)
	}
	send := func(token string) int {
		r := httptest.NewRequest("POST", a.origin+"/api/action", strings.NewReader(`{"action":"deleteHistory","address":"192.168.1.2"}`))
		r.Header.Set("X-PTV-Token", token)
		r.Header.Set("Origin", a.origin)
		w := httptest.NewRecorder()
		a.api(w, r)
		return w.Code
	}
	if send("wrong") != 403 || len(a.state.ConnectionHistory) != 1 {
		t.Fatal("unauthorized deletion")
	}
	if send("secret") != 200 || len(a.state.ConnectionHistory) != 0 {
		t.Fatal("deletion failed")
	}
	if a.state.TV.Serial != "192.168.1.2:5555" || !a.state.Busy {
		t.Fatal("changed current connection/task")
	}
}

func TestHistoryWriteFailureDoesNotChangeState(t *testing.T) {
	a := &App{dir: t.TempDir(), state: State{ConnectionHistory: []string{"192.168.1.2:5555"}}}
	if err := os.Mkdir(filepath.Join(a.dir, "connection-history.json.tmp"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := a.changeHistory("192.168.1.2", true); err == nil {
		t.Fatal("expected write failure")
	}
	if len(a.state.ConnectionHistory) != 1 {
		t.Fatal("lost history after failed write")
	}
}

func TestConnectRemembersOnlyAuthorizedSuccessfulDevices(t *testing.T) {
	for _, mode := range []string{"success", "failed", "unauthorized"} {
		t.Run(mode, func(t *testing.T) {
			a := &App{dir: t.TempDir()}
			a.runOverride = func(args ...string) (string, error) {
				s := strings.Join(args, " ")
				switch {
				case strings.HasPrefix(s, "connect "):
					if mode == "failed" {
						return "failed", errors.New("offline")
					}
					return "connected", nil
				case args[0] == "devices":
					state := "device"
					if mode == "unauthorized" {
						state = "unauthorized"
					}
					return "List of devices attached\n192.168.1.2:5555 " + state + "\n", nil
				case strings.Contains(s, "get-current-user"):
					return "0", nil
				case strings.Contains(s, "ro.build.version.sdk"):
					return "34", nil
				case strings.Contains(s, "android_id"):
					return "device-identity", nil
				case strings.Contains(s, "resolve-activity"):
					return bridge, nil
				}
				return "test", nil
			}
			err := a.execute(Request{Action: "connect", Address: "192.168.1.2"})
			if mode == "success" {
				if err != nil || len(a.state.ConnectionHistory) != 1 {
					t.Fatal(err, a.state.ConnectionHistory)
				}
			} else if err == nil || len(a.state.ConnectionHistory) != 0 {
				t.Fatal("remembered failed connection", err)
			}
		})
	}
}
