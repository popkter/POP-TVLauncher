package main

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestStopCancelsCommandsAndReleasesWaiters(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	a := &App{ctx: ctx, cancel: cancel, done: make(chan struct{})}
	a.stop()
	a.stop()
	select {
	case <-a.done:
	default:
		t.Fatal("exit not signalled")
	}
	if ctx.Err() != context.Canceled {
		t.Fatal("commands not cancelled")
	}
}

func TestCloseNotificationRequiresAuthAndAllowsRefresh(t *testing.T) {
	now := time.Now()
	a := &App{origin: "http://127.0.0.1:1234", token: "secret", lastSeen: now.Add(-time.Second), state: State{Busy: true}}
	send := func(token string) int {
		r := httptest.NewRequest("POST", a.origin+"/api/action", strings.NewReader(`{"action":"windowClosed"}`))
		r.Header.Set("Origin", a.origin)
		r.Header.Set("X-PTV-Token", token)
		w := httptest.NewRecorder()
		a.api(w, r)
		return w.Code
	}
	if send("wrong") != 403 || !a.uiGoneAt.IsZero() {
		t.Fatal("unauthorized close")
	}
	if send("secret") != 202 {
		t.Fatal("busy close not handled")
	}
	if a.browserGone(time.Now()) {
		t.Fatal("closed before refresh grace")
	}
	if !a.browserGone(time.Now().Add(2 * time.Second)) {
		t.Fatal("closed window left backend alive")
	}
	a.lastSeen = time.Now().Add(time.Second)
	if a.browserGone(time.Now().Add(2 * time.Second)) {
		t.Fatal("page refresh killed app")
	}
}

func TestQuitSignalsShutdown(t *testing.T) {
	a := &App{done: make(chan struct{})}
	if err := a.execute(Request{Action: "quit"}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-a.done:
	case <-time.After(time.Second):
		t.Fatal("quit did not stop app")
	}
}
