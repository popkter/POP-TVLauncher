package main

import (
	"bufio"
	"encoding/json"
	"io"
	"testing"
	"time"
)

func TestNativeWorkerStateAndParentExit(t *testing.T) {
	for _, quit := range []bool{false, true} {
		a := &App{state: State{Phase: "ready", ConnectionHistory: []string{"192.168.1.2:5555"}}}
		nativeContext(a)
		input, commands := io.Pipe()
		output, events := io.Pipe()
		done := make(chan struct{})
		go func() { a.serveNative(input, events); events.Close(); close(done) }()
		scanner := bufio.NewScanner(output)
		if !scanner.Scan() {
			t.Fatal("no initial state")
		}
		var state State
		if err := json.Unmarshal(scanner.Bytes(), &state); err != nil {
			t.Fatal(err)
		}
		if state.Phase != "ready" || len(state.ConnectionHistory) != 1 {
			t.Fatal(state)
		}
		if quit {
			io.WriteString(commands, "{\"action\":\"quit\"}\n")
		} else {
			commands.Close()
		}
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("worker remained after parent exit")
		}
		if a.ctx.Err() == nil {
			t.Fatal("commands not cancelled")
		}
		output.Close()
		commands.Close()
	}
}
