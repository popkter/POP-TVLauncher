package main

import (
	"os/exec"
	"syscall"
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
