//go:build windows

package main

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"
	"unsafe"
)

// Explicit opt-in: creates and closes only our own Edge test windows.
func TestWindowsWindowCloseAndReopen(t *testing.T) {
	if os.Getenv("PTV_TEST_WINDOWS_UI") != "1" {
		t.Skip("opt-in Windows UI test")
	}
	for i := 0; i < 2; i++ {
		title := fmt.Sprintf("PTV 安装助手 test %d-%d", os.Getpid(), i)
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprintf(w, "<!doctype html><title>%s</title><p>PTV window lifecycle test</p>", title)
		}))
		closed, cleanup, err := openUI(server.URL + "/#" + strings.Repeat("a", 64))
		if err != nil {
			server.Close()
			t.Fatal(err)
		}
		if closed == nil {
			cleanup()
			server.Close()
			t.Fatal("Edge not available")
		}
		var hwnd uintptr
		getText := user32.NewProc("GetWindowTextW")
		callback := syscall.NewCallback(func(h, unused uintptr) uintptr {
			var buf [256]uint16
			getText.Call(h, uintptr(unsafe.Pointer(&buf[0])), 256)
			if strings.Contains(syscall.UTF16ToString(buf[:]), title) {
				hwnd = h
				return 0
			}
			return 1
		})
		deadline := time.Now().Add(20 * time.Second)
		for hwnd == 0 && time.Now().Before(deadline) {
			enumWindows.Call(callback, 0)
			time.Sleep(100 * time.Millisecond)
		}
		if hwnd == 0 {
			cleanup()
			server.Close()
			t.Fatal("test window did not appear")
		}
		time.Sleep(250 * time.Millisecond)
		select {
		case <-closed:
			cleanup()
			server.Close()
			t.Fatal("lifecycle ended before window close")
		default:
		}
		postClose.Call(hwnd, 0x0010, 0, 0)
		select {
		case <-closed:
		case <-time.After(3 * time.Second):
			cleanup()
			server.Close()
			t.Fatal("window close did not stop lifecycle")
		}
		cleanup()
		server.Close()
	}
}

func TestInstallerProcessHelper(t *testing.T) {
	if os.Getenv("PTV_LIFECYCLE_CHILD") != "1" {
		return
	}
	main()
	os.Exit(0)
}

func TestWindowsInstallerExitAndRestart(t *testing.T) {
	if os.Getenv("PTV_TEST_WINDOWS_UI") != "1" {
		t.Skip("opt-in Windows UI test")
	}
	getText := user32.NewProc("GetWindowTextW")
	windows := map[uintptr]bool{}
	callback := syscall.NewCallback(func(h, unused uintptr) uintptr {
		var buf [256]uint16
		getText.Call(h, uintptr(unsafe.Pointer(&buf[0])), 256)
		if strings.Contains(syscall.UTF16ToString(buf[:]), "PTV 安装助手") {
			windows[h] = true
		}
		return 1
	})
	enumWindows.Call(callback, 0)
	previous := windows
	for _, mode := range []string{"window-close", "quit"} {
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestInstallerProcessHelper$")
		if binary := os.Getenv("PTV_LIFECYCLE_BINARY"); binary != "" {
			cmd = exec.CommandContext(ctx, binary)
		}
		var childErrors bytes.Buffer
		cmd.Stderr = &childErrors
		cmd.Env = append(os.Environ(), "PTV_LIFECYCLE_CHILD=1")
		cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x08000000}
		output, err := cmd.StdoutPipe()
		if err != nil {
			cancel()
			t.Fatal(err)
		}
		if err = cmd.Start(); err != nil {
			cancel()
			t.Fatal(err)
		}
		scanner := bufio.NewScanner(output)
		address := ""
		for scanner.Scan() {
			if strings.HasPrefix(scanner.Text(), "http://127.0.0.1:") {
				address = scanner.Text()
				break
			}
		}
		if address == "" {
			cancel()
			cmd.Wait()
			t.Fatal("installer did not start (possibly stale instance lock)")
		}
		var hwnd uintptr
		deadline := time.Now().Add(10 * time.Second)
		for hwnd == 0 && time.Now().Before(deadline) {
			windows = map[uintptr]bool{}
			enumWindows.Call(callback, 0)
			for h := range windows {
				if !previous[h] {
					hwnd = h
					break
				}
			}
			time.Sleep(100 * time.Millisecond)
		}
		if hwnd == 0 {
			u, _ := url.Parse(address)
			res, probeErr := (&http.Client{Timeout: time.Second}).Get("http://" + u.Host + "/")
			if probeErr == nil {
				t.Log("HTTP status", res.StatusCode)
				res.Body.Close()
			} else {
				t.Log("HTTP probe", probeErr)
			}
			cancel()
			cmd.Wait()
			t.Log("child errors", childErrors.String())
			t.Fatal("installer window missing")
		}
		if mode == "window-close" {
			postClose.Call(hwnd, 0x0010, 0, 0)
		} else {
			u, err := url.Parse(address)
			if err != nil {
				cancel()
				cmd.Wait()
				t.Fatal(err)
			}
			origin := "http://" + u.Host
			req, _ := http.NewRequest("POST", origin+"/api/action", strings.NewReader(`{"action":"quit"}`))
			req.Header.Set("X-PTV-Token", u.Fragment)
			req.Header.Set("Origin", origin)
			req.Header.Set("Content-Type", "application/json")
			client := &http.Client{Timeout: 2 * time.Second}
			res, err := client.Do(req)
			if err != nil {
				cancel()
				cmd.Wait()
				t.Fatal(err)
			}
			res.Body.Close()
			if res.StatusCode != 202 {
				cancel()
				cmd.Wait()
				t.Fatal("quit request rejected")
			}
		}
		exited := make(chan error, 1)
		go func() { exited <- cmd.Wait() }()
		select {
		case err := <-exited:
			if err != nil {
				cancel()
				t.Fatal(err)
			}
		case <-time.After(4 * time.Second):
			cancel()
			<-exited
			t.Fatal("installer process remained after exit")
		}
		cancel()
		if alive, _, _ := isWindow.Call(hwnd); alive != 0 {
			t.Fatal("installer window remained after process exit")
		}
		t.Log(mode + ": process exited; next launch checks released mutex")
	}
}
