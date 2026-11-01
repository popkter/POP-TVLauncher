//go:build !windows

package main

import (
	"os"
	"os/exec"
)

func hideWindow(c *exec.Cmd)            {}
func replaceFile(from, to string) error { return os.Rename(from, to) }
func openUI(url string) (<-chan struct{}, func(), error) {
	return nil, func() {}, exec.Command("open", url).Start()
}

func singleInstance() bool { return true }

func releaseInstance() {}
