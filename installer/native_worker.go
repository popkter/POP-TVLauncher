package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"sync"
	"time"
)

// Private parent/child pipes. No HTTP listener, browser, or web content.
func (a *App) serveNative(input io.Reader, output io.Writer) {
	var writeMu sync.Mutex
	send := func() {
		a.mu.Lock()
		data, err := json.Marshal(a.state)
		a.mu.Unlock()
		if err != nil {
			return
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		if _, err = output.Write(append(data, '\n')); err != nil {
			a.stop()
		}
	}
	send()
	go func() {
		scanner := bufio.NewScanner(input)
		scanner.Buffer(make([]byte, 4096), 65536)
		for scanner.Scan() {
			var req Request
			if err := json.Unmarshal(scanner.Bytes(), &req); err != nil {
				continue
			}
			if req.Action == "quit" {
				a.stop()
				return
			}
			if req.Action == "state" {
				send()
				continue
			}
			a.mu.Lock()
			if a.state.Busy {
				a.mu.Unlock()
				continue
			}
			a.state.Busy = true
			a.state.Error = ""
			a.mu.Unlock()
			send()
			go func(r Request) {
				err := a.execute(r)
				a.mu.Lock()
				a.state.Busy = false
				if err != nil {
					a.state.Error = err.Error()
					a.state.Phase = "操作未完成"
				}
				if r.Action == "reboot" {
					a.state.TV = nil
				}
				a.mu.Unlock()
				if err != nil {
					a.log(err.Error())
				}
				send()
			}(req)
		}
		a.stop() // Parent window/process ended: never leave a worker behind.
	}()
	ticker := time.NewTicker(400 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-a.done:
			return
		case <-ticker.C:
			send()
		}
	}
}

func nativeContext(a *App) {
	a.ctx, a.cancel = context.WithCancel(context.Background())
	a.done = make(chan struct{})
}
