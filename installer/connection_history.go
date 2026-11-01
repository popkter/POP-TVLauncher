package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

const historyLimit = 50

func historyAddress(raw string) (string, error) {
	return endpoint(strings.ToLower(strings.TrimSpace(raw)))
}

func (a *App) loadHistory() error {
	data, err := os.ReadFile(filepath.Join(a.dir, "connection-history.json"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var saved []string
	if err = json.Unmarshal(data, &saved); err != nil {
		return err
	}
	clean := []string{}
	seen := map[string]bool{}
	for _, raw := range saved {
		address, err := historyAddress(raw)
		if err == nil && !seen[address] {
			clean = append(clean, address)
			seen[address] = true
		}
		if len(clean) == historyLimit {
			break
		}
	}
	a.mu.Lock()
	a.state.ConnectionHistory = clean
	a.mu.Unlock()
	return nil
}

// Commit the file before publishing state so failed writes never look saved.
func (a *App) changeHistory(raw string, remove bool) error {
	address, err := historyAddress(raw)
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	next := []string{}
	if !remove {
		next = append(next, address)
	}
	for _, old := range a.state.ConnectionHistory {
		if old != address && len(next) < historyLimit {
			next = append(next, old)
		}
	}
	data, err := json.MarshalIndent(next, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(a.dir, 0700); err != nil {
		return err
	}
	path := filepath.Join(a.dir, "connection-history.json")
	if err = os.WriteFile(path+".tmp", data, 0600); err != nil {
		return err
	}
	if err = replaceFile(path+".tmp", path); err != nil {
		return err
	}
	a.state.ConnectionHistory = next
	return nil
}

func (a *App) rememberConnection(address string) {
	if err := a.changeHistory(address, false); err != nil {
		a.log("电视已连接，但地址历史保存失败：" + err.Error())
	}
}
