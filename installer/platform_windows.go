package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

func hideWindow(c *exec.Cmd) { c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true} }
func replaceFile(from, to string) error {
	f, e := syscall.UTF16PtrFromString(from)
	if e != nil {
		return e
	}
	t, e := syscall.UTF16PtrFromString(to)
	if e != nil {
		return e
	}
	p := syscall.NewLazyDLL("kernel32.dll").NewProc("MoveFileExW")
	r, _, e := p.Call(uintptr(unsafe.Pointer(f)), uintptr(unsafe.Pointer(t)), 1|8)
	if r == 0 {
		return e
	}
	return nil
}

// A separate browser profile prevents Edge from handing this window to a
// user's existing browser process. Only this owned process/window is observed.
func openUI(url string) (<-chan struct{}, func(), error) {
	for _, root := range []string{os.Getenv("ProgramFiles(x86)"), os.Getenv("ProgramFiles"), os.Getenv("LOCALAPPDATA")} {
		edge := filepath.Join(root, "Microsoft", "Edge", "Application", "msedge.exe")
		if _, err := os.Stat(edge); err != nil {
			continue
		}
		profile, err := os.MkdirTemp("", "PTVInstaller-browser-")
		if err != nil {
			return nil, func() {}, err
		}
		// Edge may hand off startup to another process even with an isolated
		// profile. Track the newly created app window, not the launcher PID.
		before := map[uintptr]bool{}
		for _, h := range browserWindows(0) {
			before[h] = true
		}
		c := exec.Command(edge, "--user-data-dir="+profile, "--no-first-run", "--no-default-browser-check", "--disable-background-mode", "--app="+url, "--window-size=1120,820")
		if err = c.Start(); err != nil {
			os.RemoveAll(profile)
			continue
		}
		exited := make(chan struct{})
		go func() { c.Wait(); close(exited) }()
		closed := make(chan struct{})
		cancelWatch := make(chan struct{})
		var targetMu sync.Mutex
		var target uintptr
		go func() {
			ticker := time.NewTicker(100 * time.Millisecond)
			defer ticker.Stop()
			deadline := time.Now().Add(30 * time.Second)
			for {
				select {
				case <-cancelWatch:
					return
				case <-ticker.C:
					targetMu.Lock()
					h := target
					targetMu.Unlock()
					if h != 0 {
						alive, _, _ := isWindow.Call(h)
						if alive == 0 {
							close(closed)
							return
						}
					} else {
						for _, candidate := range browserWindows(0) {
							if !before[candidate] {
								targetMu.Lock()
								target = candidate
								targetMu.Unlock()
								break
							}
						}
						if time.Now().After(deadline) {
							close(closed)
							return
						}
					}
				}
			}
		}()
		cleanup := func() {
			close(cancelWatch)
			targetMu.Lock()
			h := target
			targetMu.Unlock()
			if h != 0 {
				postClose.Call(h, 0x0010, 0, 0)
				deadline := time.Now().Add(time.Second)
				for time.Now().Before(deadline) {
					alive, _, _ := isWindow.Call(h)
					if alive == 0 {
						break
					}
					time.Sleep(25 * time.Millisecond)
				}
			}
			select {
			case <-exited:
			case <-time.After(time.Second):
				c.Process.Kill()
				<-exited
			}
			os.RemoveAll(profile)
		}

		return closed, cleanup, nil
	}
	c := exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", url)
	hideWindow(c)
	err := c.Start()
	if err == nil {
		go c.Wait()
	}
	return nil, func() {}, err
}

var user32 = syscall.NewLazyDLL("user32.dll")
var enumWindows = user32.NewProc("EnumWindows")
var windowPID = user32.NewProc("GetWindowThreadProcessId")
var isWindow = user32.NewProc("IsWindow")
var getWindowText = user32.NewProc("GetWindowTextW")
var windowVisible = user32.NewProc("IsWindowVisible")
var postClose = user32.NewProc("PostMessageW")

type windowList struct {
	pid     uint32
	handles []uintptr
}

var enumerateMu sync.Mutex
var enumerating windowList
var enumBrowserWindow = syscall.NewCallback(func(hwnd, data uintptr) uintptr {
	list := &enumerating
	var pid uint32
	windowPID.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
	visible, _, _ := windowVisible.Call(hwnd)
	var title [256]uint16
	getWindowText.Call(hwnd, uintptr(unsafe.Pointer(&title[0])), 256)
	matches := pid == list.pid
	if list.pid == 0 {
		matches = strings.HasPrefix(syscall.UTF16ToString(title[:]), "PTV 安装助手")
	}
	if matches && visible != 0 {
		list.handles = append(list.handles, hwnd)
	}
	return 1
})

func browserWindows(pid uint32) []uintptr {
	enumerateMu.Lock()
	defer enumerateMu.Unlock()
	enumerating = windowList{pid: pid}
	enumWindows.Call(enumBrowserWindow, 0)
	return enumerating.handles
}

var instanceHandle syscall.Handle

func releaseInstance() {
	if instanceHandle != 0 {
		syscall.CloseHandle(instanceHandle)
		instanceHandle = 0
	}
}

func singleInstance() bool {
	p := syscall.NewLazyDLL("kernel32.dll").NewProc("CreateMutexW")
	h, _, e := p.Call(0, 0, uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr("Local\\PTVInstaller"))))
	if h == 0 {
		return false
	}
	if e == syscall.Errno(183) {
		syscall.CloseHandle(syscall.Handle(h))
		syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr("安装助手已在运行，请返回已打开的窗口，或关闭该窗口后重新启动。"))), uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr("PTV 安装助手"))), 0)
		return false
	}
	instanceHandle = syscall.Handle(h)
	return true
}
