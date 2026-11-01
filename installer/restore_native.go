package main

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"time"
)

const stockHome = stock + "/.MainActivityUserMode"

func (a *App) installedForOwner(s, name string) (bool, error) {
	out, err := a.sh(s, "pm", "list", "packages", "--user", "0", name)
	for _, line := range strings.Fields(out) {
		if line == "package:"+name {
			return true, err
		}
	}
	return false, err
}

func (a *App) verifyNativeHome(t *TV) error {
	h, err := a.home(t.Serial)
	if err != nil {
		return err
	}
	if h != stockHome && h != stock+"/"+stock+".MainActivityUserMode" {
		return errors.New("原生桌面路由未恢复，已停止清理；请重新连接后重试")
	}
	role, err := a.sh(t.Serial, "cmd", "role", "get-role-holders", "--user", "0", "android.app.role.HOME")
	if err != nil || strings.TrimSpace(role) != stock {
		return errors.New("原生默认桌面角色未确认，已停止清理")
	}
	return nil
}

// This is an explicit removal of PTV, not restoration of the install snapshot:
// the snapshot may already contain a manually configured PTV/HomeBridge setup.
func (a *App) restoreNative(t *TV, removePTV bool) error {
	if !t.BridgeEligible {
		return errors.New("一键还原原生系统目前仅支持已适配的小米型号与固件；其他设备请使用恢复安装前配置")
	}
	s := t.Serial
	a.phase("检查原生桌面与 PTV 组件", 10)
	present := map[string]bool{}
	for _, name := range []string{stock, pkg, "com.ts.launcher"} {
		yes, err := a.installedForOwner(s, name)
		if err != nil {
			return err
		}
		present[name] = yes
	}
	if !present[stock] {
		return errors.New("未找到小米原生桌面，未执行还原")
	}
	if present["com.ts.launcher"] {
		out, err := a.sh(s, "cmd", "package", "query-activities", "--query-flags", "512", "--brief", "--components", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME", "-p", "com.ts.launcher")
		if err != nil {
			return err
		}
		found := false
		for _, line := range strings.Fields(out) {
			if line == bridge {
				found = true
			}
		}
		if !found {
			return errors.New("com.ts.launcher 不是可识别的 PTV 兼容入口，未执行还原")
		}
	}
	a.phase("启用并验证小米原生桌面", 25)
	for _, args := range [][]string{
		{"pm", "enable", "--user", "0", stock},
		{"cmd", "package", "set-home-activity", "--user", "0", stockHome},
	} {
		if err := a.mustSh(s, args...); err != nil {
			return err
		}
	}
	if err := a.verifyNativeHome(t); err != nil {
		return err
	}

	a.phase("关闭 PTV 接管并撤销授权", 45)
	if present[pkg] {
		d, err := a.sh(s, "dumpsys", "package", pkg)
		if err != nil {
			return err
		}
		// Revoke self-recovery permission first so the service cannot restore
		// its accessibility entry while it is being removed.
		if strings.Contains(d, "android.permission.WRITE_SECURE_SETTINGS: granted=true") {
			if err = a.mustSh(s, "pm", "revoke", "--user", "0", pkg, "android.permission.WRITE_SECURE_SETTINGS"); err != nil {
				return err
			}
		}
		if err = a.mustSh(s, "am", "force-stop", "--user", "0", pkg); err != nil {
			return err
		}
	}
	raw, err := a.sh(s, "settings", "get", "secure", "enabled_accessibility_services")
	if err != nil {
		return err
	}
	merged := mergeServices(raw, false)
	if err = a.mustSh(s, "settings", "put", "secure", "enabled_accessibility_services", merged); err != nil {
		return err
	}
	// Preserve the global state when other services remain.
	if merged == "" {
		if err = a.mustSh(s, "settings", "put", "secure", "accessibility_enabled", "0"); err != nil {
			return err
		}
	}
	if err = a.mustSh(s, "settings", "delete", "secure", "ptv_installer_shortcuts"); err != nil {
		return err
	}
	if err = a.mustSh(s, "sh", "-c", "pkill -f '^ptv-control-shell$' || true"); err != nil {
		return err
	}

	if removePTV {
		a.phase("移除 PTV、兼容入口及 PTV 数据", 65)
		for _, name := range []string{pkg, "com.ts.launcher"} {
			if !present[name] {
				continue
			}
			out, err := a.sh(s, "pm", "uninstall", "--user", "0", name)
			if err != nil {
				return err
			}
			if strings.TrimSpace(out) != "Success" {
				return fmt.Errorf("移除 %s 未成功：%s；可重新连接后重试", name, out)
			}
		}
	} else {
		a.phase("停用 PTV 与兼容入口，保留全部数据", 65)
		for _, name := range []string{pkg, "com.ts.launcher"} {
			if present[name] {
				if err = a.mustSh(s, "pm", "disable-user", "--user", "0", name); err != nil {
					return err
				}
			}
		}
	}
	// Package removal changes HOME candidates; apply and verify again.
	if err = a.mustSh(s, "cmd", "package", "set-home-activity", "--user", "0", stockHome); err != nil {
		return err
	}
	if err = a.verifyNativeHome(t); err != nil {
		return err
	}
	for _, name := range []string{pkg, "com.ts.launcher"} {
		yes, err := a.installedForOwner(s, name)
		if err != nil {
			return err
		}
		if removePTV && yes {
			return fmt.Errorf("%s 仍存在，还原尚未完成", name)
		}
		if !removePTV && present[name] {
			if !yes {
				return fmt.Errorf("%s 意外缺失，无法确认数据保留", name)
			}
			disabled, err := a.sh(s, "pm", "list", "packages", "-d", "--user", "0", name)
			if err != nil {
				return err
			}
			if !strings.Contains(disabled, "package:"+name) {
				return fmt.Errorf("%s 尚未停用", name)
			}
		}
	}
	raw, err = a.sh(s, "settings", "get", "secure", "enabled_accessibility_services")
	if err != nil {
		return err
	}
	if hasOwn(raw) {
		return errors.New("PTV 无障碍配置仍存在，还原尚未完成")
	}
	// Xiaomi's HOME activity requires HOME_STATE, which shell does not hold.
	// Let system policy launch the verified default through the HOME key.
	if err = a.mustSh(s, "input", "keyevent", "KEYCODE_HOME"); err != nil {
		return err
	}
	// Do not let a later installation reuse the old PTV-based baseline.
	path := a.backupPath(t)
	if _, err = os.Stat(path); err == nil {
		if err = os.Rename(path, path+".native-restored-"+time.Now().Format("20060102-150405.000")); err != nil {
			return err
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	detail := "PTV 与兼容入口已停用，布局、壁纸和偏好已保留；点击一键配置可重新启用"
	phase := "已还原原生桌面，PTV 数据已保留"
	if removePTV {
		detail = "PTV、兼容入口及其数据已移除；其他应用保留"
		phase = "已还原原生桌面并移除 PTV"
	}
	a.mu.Lock()
	a.state.Backup = false
	a.state.TV.Home = stockHome
	a.state.Checks = []Check{
		{"小米原生桌面", "ok", "默认角色与实际主页入口已恢复"},
		{"PTV 处理结果", "ok", detail},
		{"遥控器与重启", "manual", "请按主页键确认原生桌面，再重启检查开机画面"},
	}
	a.mu.Unlock()
	a.phase(phase, 100)
	a.log("还原完成。其他应用、账号和系统设置保留；电脑上的 ADB 与安装备份保留。")
	return nil
}
