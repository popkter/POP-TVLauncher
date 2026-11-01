package main

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed payload/*
var assets embed.FS

const pkg = "com.popkter.ptvlauncher"
const service = pkg + "/.SwitcherService"
const activity = pkg + "/.MainActivity"
const bridge = "com.ts.launcher/com.popkter.ptvlauncher.homebridge.HomeActivity"
const stock = "com.mitv.tvhome"

type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
}
type TV struct {
	Serial         string `json:"serial"`
	Model          string `json:"model"`
	Manufacturer   string `json:"manufacturer"`
	Android        string `json:"android"`
	SDK            int    `json:"sdk"`
	Firmware       string `json:"firmware"`
	Home           string `json:"home"`
	BridgeEligible bool   `json:"bridgeEligible"`
	Identity       string `json:"-"`
}
type State struct {
	ReleaseSource     ReleaseSource `json:"releaseSource"`
	ReleaseInfo       string        `json:"releaseInfo"`
	ConnectionHistory []string      `json:"connectionHistory"`
	Busy              bool          `json:"busy"`
	Phase             string        `json:"phase"`
	Progress          int           `json:"progress"`
	Logs              []string      `json:"logs"`
	Error             string        `json:"error"`
	Devices           []Device      `json:"devices"`
	TV                *TV           `json:"tv"`
	Checks            []Check       `json:"checks"`
	Backup            bool          `json:"backup"`
}
type Check struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	Detail string `json:"detail"`
}
type Backup struct {
	Identity               string
	Serial                 string
	Home                   string
	Services               string
	Accessibility          string
	SecureGranted          bool
	WriteSettingsMode      string
	HadApp                 bool
	BridgeInstalled        bool
	NetworkBridgeInstalled bool
	ControlHelperStarted   bool
	StockDisabled          bool
	Created                string
}
type Request struct {
	ReleaseSource ReleaseSource `json:"releaseSource"`
	RemovePTV     bool          `json:"removePTV"`
	ConfirmNative bool          `json:"confirmNative"`
	Action        string        `json:"action"`
	Address       string        `json:"address"`
	Serial        string        `json:"serial"`
	Code          string        `json:"code"`
	Bridge        bool          `json:"bridge"`
}
type App struct {
	mu                      sync.Mutex
	state                   State
	dir, adb, token, origin string
	runOverride             func(...string) (string, error)
	lastSeen                time.Time
	ctx                     context.Context
	cancel                  context.CancelFunc
	done                    chan struct{}
	stopOnce                sync.Once
	uiGoneAt                time.Time
}

func (a *App) log(s string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.state.Logs = append(a.state.Logs, time.Now().Format("15:04:05")+"  "+s)
	if len(a.state.Logs) > 250 {
		a.state.Logs = a.state.Logs[len(a.state.Logs)-250:]
	}
}
func (a *App) phase(s string, p int) {
	a.mu.Lock()
	a.state.Phase = s
	a.state.Progress = p
	a.mu.Unlock()
	a.log(s)
}
func (a *App) run(args ...string) (string, error) {
	if a.runOverride != nil {
		return a.runOverride(args...)
	}
	base := a.ctx
	if base == nil {
		base = context.Background()
	}
	ctx, cancel := context.WithTimeout(base, 100*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, a.adb, args...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	s := strings.TrimSpace(string(out))
	if ctx.Err() != nil {
		return s, errors.New("设备响应超时。请检查电视连接后重新验证或恢复配置")
	}
	if err != nil {
		return s, fmt.Errorf("ADB 操作失败：%s", s)
	}
	if strings.Contains(s, "Failure [") || strings.HasPrefix(s, "Error:") || strings.Contains(s, "SecurityException") || strings.Contains(s, "Exception occurred") {
		return s, errors.New(s)
	}
	return s, nil
}
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
func (a *App) sh(serial string, args ...string) (string, error) {
	q := make([]string, len(args))
	for i, v := range args {
		q[i] = quote(v)
	}
	return a.run("-s", serial, "shell", strings.Join(q, " "))
}
func (a *App) mustSh(serial string, args ...string) error { _, e := a.sh(serial, args...); return e }

var endpointRE = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9.\-]*$`)

func endpoint(s string) (string, error) {
	s = strings.TrimSpace(s)
	if !strings.Contains(s, ":") {
		s += ":5555"
	}
	h, p, e := net.SplitHostPort(s)
	n, ne := strconv.Atoi(p)
	if e != nil || !endpointRE.MatchString(h) || ne != nil || n < 1 || n > 65535 {
		return "", errors.New("请输入有效的电视 IP 与端口，例如 192.168.1.20:5555")
	}
	return net.JoinHostPort(h, p), nil
}
func parseDevices(s string) []Device {
	d := []Device{}
	for _, line := range strings.Split(s, "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 && (f[1] == "device" || f[1] == "offline" || f[1] == "unauthorized") {
			d = append(d, Device{f[0], f[1]})
		}
	}
	return d
}
func (a *App) scan() error {
	o, e := a.run("devices", "-l")
	if e != nil {
		return e
	}
	a.mu.Lock()
	a.state.Devices = parseDevices(o)
	a.mu.Unlock()
	return nil
}
func (a *App) inspect(serial string) error {
	if e := a.scan(); e != nil {
		return e
	}
	a.mu.Lock()
	ds := append([]Device{}, a.state.Devices...)
	a.mu.Unlock()
	ok := false
	for _, d := range ds {
		if d.Serial == serial {
			if d.State != "device" {
				return errors.New("设备未授权或离线，请在电视上允许 USB/网络调试，再重新连接")
			}
			ok = true
		}
	}
	if !ok {
		return errors.New("未找到所选设备，请刷新连接")
	}
	prop := func(k string) (string, error) { return a.sh(serial, "getprop", k) }
	user, e := a.sh(serial, "am", "get-current-user")
	if e != nil {
		return e
	}
	if strings.TrimSpace(user) != "0" {
		return errors.New("请切换到电视的主用户（用户 0）后安装，避免修改其他用户的配置")
	}
	model, e := prop("ro.product.model")
	if e != nil {
		return e
	}
	maker, e := prop("ro.product.manufacturer")
	if e != nil {
		return e
	}
	android, e := prop("ro.build.version.release")
	if e != nil {
		return e
	}
	sdkS, e := prop("ro.build.version.sdk")
	if e != nil {
		return e
	}
	sdk, _ := strconv.Atoi(sdkS)
	if sdk < 24 {
		return errors.New("PTV 需要 Android 7.0 或更新系统")
	}
	firmware, e := prop("ro.build.version.incremental")
	if e != nil {
		return e
	}
	id, e := a.sh(serial, "settings", "get", "secure", "android_id")
	if e != nil || id == "null" || id == "" {
		return errors.New("无法读取设备身份，未进行配置")
	}
	finger, e := prop("ro.product.device")
	if e != nil {
		return e
	}
	hash := sha256.Sum256([]byte(id + "/" + finger))
	home, e := a.home(serial)
	if e != nil {
		return e
	}
	t := &TV{Serial: serial, Model: model, Manufacturer: maker, Android: android, SDK: sdk, Firmware: firmware, Home: home, Identity: hex.EncodeToString(hash[:16]), BridgeEligible: strings.EqualFold(maker, "Xiaomi") && model == "MiTV-MFTR0" && firmware == "OS3.0.102.0.UFTMATV"}
	a.mu.Lock()
	a.state.TV = t
	a.state.Checks = nil
	_, e = os.Stat(a.backupPath(t))
	a.state.Backup = e == nil
	a.mu.Unlock()
	a.log("已连接 " + maker + " " + model + " · Android " + android)
	return nil
}
func (a *App) home(s string) (string, error) {
	o, e := a.sh(s, "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
	if e != nil {
		return "", e
	}
	for _, l := range strings.Split(o, "\n") {
		l = strings.TrimSpace(l)
		if componentRE.MatchString(l) {
			return l, nil
		}
	}
	return "", errors.New("无法识别当前默认桌面，已停止以保留恢复入口")
}

var componentRE = regexp.MustCompile(`^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$`)

func own(s string) bool { return s == service || s == pkg+"/"+pkg+".SwitcherService" }
func mergeServices(raw string, enabled bool) string {
	v := []string{}
	seen := map[string]bool{}
	for _, s := range strings.Split(raw, ":") {
		if s != "" && s != "null" && !own(s) && !seen[s] {
			v = append(v, s)
			seen[s] = true
		}
	}
	if enabled {
		v = append(v, service)
	}
	return strings.Join(v, ":")
}
func hasOwn(raw string) bool {
	for _, s := range strings.Split(raw, ":") {
		if own(s) {
			return true
		}
	}
	return false
}
func (a *App) backupPath(t *TV) string { return filepath.Join(a.dir, "backups", t.Identity+".json") }
func (a *App) save(t *TV, b *Backup) error {
	p := a.backupPath(t)
	if e := os.MkdirAll(filepath.Dir(p), 0700); e != nil {
		return e
	}
	data, e := json.MarshalIndent(b, "", "  ")
	if e != nil {
		return e
	}
	if e = os.WriteFile(p+".tmp", data, 0600); e != nil {
		return e
	} // Windows does not replace an existing file with Rename.
	if e = replaceFile(p+".tmp", p); e != nil {
		return e
	}
	a.mu.Lock()
	a.state.Backup = true
	a.mu.Unlock()
	return nil
}
func (a *App) load(t *TV) (*Backup, error) {
	data, e := os.ReadFile(a.backupPath(t))
	if e != nil {
		return nil, e
	}
	var b Backup
	if e = json.Unmarshal(data, &b); e != nil {
		return nil, e
	}
	if b.Identity != t.Identity {
		return nil, errors.New("恢复记录与当前电视不匹配")
	}
	return &b, nil
}
func (a *App) snapshot(t *TV) (*Backup, error) {
	if b, e := a.load(t); e == nil {
		return b, nil
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	s := t.Serial
	if strings.Contains(t.Home, "ResolverActivity") || strings.Contains(t.Home, "FallbackHome") {
		return nil, errors.New("请先在电视系统设置中选择一个可用的默认桌面，再运行安装助手，以便保存可靠的恢复入口")
	}
	b := &Backup{Identity: t.Identity, Serial: s, Home: t.Home, Created: time.Now().Format(time.RFC3339)}
	var e error
	if b.Services, e = a.sh(s, "settings", "get", "secure", "enabled_accessibility_services"); e != nil {
		return nil, e
	}
	if b.Accessibility, e = a.sh(s, "settings", "get", "secure", "accessibility_enabled"); e != nil {
		return nil, e
	}
	p, e := a.sh(s, "pm", "list", "packages", "--user", "0", pkg)
	if e != nil {
		return nil, e
	}
	b.HadApp = strings.Contains(p, "package:")
	d, e := a.sh(s, "dumpsys", "package", pkg)
	if e != nil {
		return nil, e
	}
	b.SecureGranted = strings.Contains(d, "android.permission.WRITE_SECURE_SETTINGS: granted=true")
	if b.HadApp {
		op, err := a.sh(s, "cmd", "appops", "get", pkg, "WRITE_SETTINGS")
		if err != nil {
			return nil, err
		}
		b.WriteSettingsMode = savedWriteSettingsMode(op)
	}
	return b, a.save(t, b)
}
func savedWriteSettingsMode(output string) string {
	if strings.Contains(output, "No operations") {
		return "default"
	}
	index := strings.Index(output, "WRITE_SETTINGS:")
	if index < 0 {
		return ""
	}
	fields := strings.Fields(output[index+len("WRITE_SETTINGS:"):])
	if len(fields) == 0 {
		return ""
	}
	mode := strings.TrimSuffix(fields[0], ";")
	switch mode {
	case "allow", "deny", "ignore", "default", "foreground":
		return mode
	}
	return ""
}
func (a *App) selected() (*TV, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.state.TV == nil {
		return nil, errors.New("请先连接并选择一台电视")
	}
	v := *a.state.TV
	return &v, nil
}
func (a *App) install(t *TV, useBridge bool) (err error) {
	s := t.Serial
	// This exact firmware requires the bridge plus disabling the stock launcher.
	useBridge = useBridge || t.BridgeEligible
	a.phase("保存电视原配置", 8)
	b, e := a.snapshot(t)
	if e != nil {
		return e
	}
	// An existing, active bridge belongs to the original configuration. Preserve
	// its HOME role without reinstalling it or claiming ownership for restore.
	preserveBridge := t.Home == bridge
	if useBridge && !preserveBridge && !t.BridgeEligible {
		return errors.New("此型号或固件不在已验证的桌面兼容范围内")
	}
	if useBridge && t.BridgeEligible {
		exists, err := a.installedForOwner(s, "com.ts.launcher")
		if err != nil {
			return err
		}
		preserveBridge = false
		if exists {
			out, err := a.sh(s, "cmd", "package", "query-activities", "--query-flags", "512", "--brief", "--components", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME", "-p", "com.ts.launcher")
			if err != nil {
				return err
			}
			for _, field := range strings.Fields(out) {
				if field == bridge {
					preserveBridge = true
				}
			}
			if !preserveBridge {
				return errors.New("兼容入口包名已被其他应用占用，不会覆盖现有应用")
			}
		}
	}
	installed, e := a.installedForOwner(s, pkg)
	if e != nil {
		return e
	}
	if !installed {
		a.phase("未安装 PTV，正在自动安装", 24)
		if e = a.installAPK(t); e != nil {
			return e
		}
	} else {
		a.log("PTV 已安装，保留当前版本与数据，仅重新配置")
	}
	if e = a.mustSh(s, "pm", "enable", "--user", "0", pkg); e != nil {
		return e
	}
	a.phase("配置快捷服务与自动恢复权限", 48)
	const networkBridge = "com.popkter.ptvlauncher.networkbridge"
	hadNetworkBridge, bridgeErr := a.installedForOwner(s, networkBridge)
	if bridgeErr != nil {
		return bridgeErr
	}
	if !hadNetworkBridge {
		b.NetworkBridgeInstalled = true
		if e = a.save(t, b); e != nil {
			return e
		}
	}
	bridgeOutput, bridgeErr := a.run("-s", s, "install", "-r", filepath.Join(a.dir, "payload", "NetworkBridge.apk"))
	if bridgeErr != nil {
		return bridgeErr
	}
	if !strings.Contains(bridgeOutput, "Success") {
		return errors.New(bridgeOutput)
	}
	process, _ := a.sh(s, "pidof", "ptv-control-shell")
	if strings.TrimSpace(process) == "" {
		b.ControlHelperStarted = true
		if e = a.save(t, b); e != nil {
			return e
		}
	}
	if _, e = a.run("-s", s, "push", filepath.Join(a.dir, "payload", "activate-local-control.sh"), "/data/local/tmp/ptv-activate-local-control.sh"); e != nil {
		return e
	}
	if e = a.mustSh(s, "sh", "/data/local/tmp/ptv-activate-local-control.sh"); e != nil {
		return e
	}
	if e = a.mustSh(s, "cmd", "appops", "set", pkg, "WRITE_SETTINGS", "allow"); e != nil {
		return e
	}
	if e = a.mustSh(s, "pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"); e != nil {
		return e
	}
	current, e := a.sh(s, "settings", "get", "secure", "enabled_accessibility_services")
	if e != nil {
		return e
	}
	for _, args := range [][]string{{"settings", "put", "secure", "enabled_accessibility_services", mergeServices(current, true)}, {"settings", "put", "secure", "accessibility_enabled", "1"}, {"settings", "put", "secure", "ptv_installer_shortcuts", "enable"}} {
		if e = a.mustSh(s, args...); e != nil {
			return e
		}
	}
	a.phase("设置默认桌面", 66)
	if preserveBridge || useBridge {
		if !preserveBridge {
			b.BridgeInstalled = true
			if e = a.save(t, b); e != nil {
				return e
			}
			o, e := a.run("-s", s, "install", "-r", filepath.Join(a.dir, "payload", "HomeBridge.apk"))
			if e != nil {
				return e
			}
			if !strings.Contains(o, "Success") {
				return errors.New(o)
			}
		} else {
			a.log("复用已有的 PTV 系统桌面入口")
		}
		if e = a.mustSh(s, "pm", "enable", "--user", "0", "com.ts.launcher"); e != nil {
			return e
		}
		// Reusing a bridge must also disable the stock launcher again after a
		// native restore. Never claim a pre-existing bridge as newly installed.
		disabledNow := false
		if t.BridgeEligible {
			disabled, err := a.sh(s, "pm", "list", "packages", "-d", "--user", "0", stock)
			if err != nil {
				return err
			}
			if !strings.Contains(disabled, "package:"+stock) {
				b.StockDisabled = true
				if e = a.save(t, b); e != nil {
					return e
				}
				if e = a.mustSh(s, "pm", "disable-user", "--user", "0", stock); e != nil {
					return e
				}
				disabledNow = true
			}
		}
		rollback := func() {
			if disabledNow {
				a.mustSh(s, "pm", "enable", "--user", "0", stock)
			}
		}
		if e = a.mustSh(s, "cmd", "package", "set-home-activity", "--user", "0", bridge); e != nil {
			rollback()
			return e
		}
		home, err := a.home(s)
		if err != nil || home != bridge {
			rollback()
			return errors.New("系统未接受兼容入口，已尝试撤销本次原桌面停用；请运行恢复配置")
		}
	} else {
		if e = a.mustSh(s, "cmd", "package", "set-home-activity", "--user", "0", activity); e != nil {
			a.log("系统未接受默认桌面设置，将在验证中标记需要手动处理")
		}
	}
	a.phase("启动并等待快捷服务连接", 83)
	if e = a.mustSh(s, "am", "start", "-n", activity); e != nil {
		return e
	}
	for i := 0; i < 12; i++ {
		time.Sleep(time.Second)
		d, e := a.sh(s, "dumpsys", "accessibility")
		if e == nil && bound(d) {
			break
		}
	}
	return a.verify(t)
}
func bound(d string) bool {
	// Android dumps a list across lines when more than one service is bound.
	start := strings.Index(strings.ToLower(d), "bound services:")
	if start < 0 {
		return false
	}
	section := d[start+len("bound services:"):]
	open := strings.Index(section, "{")
	if open < 0 {
		return false
	}
	depth := 0
	for i, c := range section[open:] {
		if c == '{' {
			depth++
		}
		if c == '}' {
			depth--
			if depth == 0 {
				body := section[open : open+i+1]
				return strings.Contains(body, pkg) || strings.Contains(body, "label=PTV 快速切换")
			}
		}
	}
	return false
}

func (a *App) verify(t *TV) error {
	s := t.Serial
	a.phase("验证安装结果", 92)
	checks := []Check{}
	add := func(name string, ok bool, detail string) {
		st := "warning"
		if ok {
			st = "ok"
		}
		checks = append(checks, Check{name, st, detail})
	}
	p, e := a.sh(s, "pm", "list", "packages", "--user", "0", pkg)
	if e != nil {
		return e
	}
	add("PTV 已安装", strings.Contains(p, "package:"), "保留桌面布局与自定义壁纸数据")
	d, e := a.sh(s, "dumpsys", "package", pkg)
	if e != nil {
		return e
	}
	add("自动恢复权限", strings.Contains(d, "android.permission.WRITE_SECURE_SETTINGS: granted=true"), "WRITE_SECURE_SETTINGS")
	ac, e := a.sh(s, "dumpsys", "accessibility")
	if e != nil {
		return e
	}
	add("快捷服务已连接", bound(ac), "跨应用长按返回依赖服务实际连接")
	svc, e := a.sh(s, "dumpsys", "activity", "services", pkg+"/.SwitcherService")
	if e != nil {
		return e
	}
	add("按键防冻结服务", strings.Contains(svc, "isForeground=true"), "防止后台冻结造成方向键重复")
	h, e := a.home(s)
	if e != nil {
		return e
	}
	homeOK := h == activity || h == pkg+"/"+pkg+".MainActivity" || h == bridge
	detail := h
	if t.SDK >= 29 && homeOK {
		// Xiaomi can resolve HOME to its bridge even while ROLE_HOME points to
		// PTV. Resolution alone therefore misses a broken boot configuration.
		role, roleErr := a.sh(s, "cmd", "role", "get-role-holders", "--user", "0", "android.app.role.HOME")
		expected := strings.SplitN(h, "/", 2)[0]
		homeOK = roleErr == nil && strings.TrimSpace(role) == expected
		detail += "；默认桌面角色：" + strings.TrimSpace(role)
		if !homeOK {
			detail += "；角色与实际入口不一致或无法确认，请修复后重启验证"
		}
	}
	if t.BridgeEligible {
		disabled, err := a.sh(s, "pm", "list", "packages", "-d", "--user", "0", stock)
		if err != nil || !strings.Contains(disabled, "package:"+stock) || h != bridge {
			homeOK = false
			detail += "；小米兼容配置不完整，请重新安装并配置"
		}
	}
	add("默认桌面", homeOK, detail)
	checks = append(checks, Check{"实体遥控器验收", "manual", "在视频应用轻按方向键，再长按返回；按主页键应回到 PTV"}, Check{"重启验收", "manual", "点击重启验证，电视重连后再次检查"}, Check{"原生控制中心", "manual", "菜单键依赖电视固件入口，请在电视上实测"})
	a.mu.Lock()
	a.state.Checks = checks
	a.state.Progress = 100
	a.state.Phase = "配置检查完成"
	a.state.TV.Home = h
	a.mu.Unlock()
	a.log("检查完成。黄色项目需要处理；实体按键与重启需实测确认。")
	return nil
}
func (a *App) restore(t *TV) error {
	b, e := a.load(t)
	if e != nil {
		return errors.New("未找到这台电视的恢复记录")
	}
	s := t.Serial
	a.phase("恢复原桌面与快捷服务配置", 25)
	if b.StockDisabled {
		if e = a.mustSh(s, "pm", "enable", "--user", "0", stock); e != nil {
			return e
		}
	}
	if !componentRE.MatchString(b.Home) {
		return errors.New("备份的桌面组件无效")
	}
	if e = a.mustSh(s, "cmd", "package", "set-home-activity", "--user", "0", b.Home); e != nil {
		return e
	}
	h, e := a.home(s)
	if e != nil || h != b.Home {
		return errors.New("原桌面路由尚未恢复，保留兼容入口，请在电视系统设置中选择原桌面后重试")
	}
	// Do not touch other services added since installation.
	d, e := a.sh(s, "dumpsys", "package", pkg)
	if e != nil {
		return e
	}
	granted := strings.Contains(d, "android.permission.WRITE_SECURE_SETTINGS: granted=true")
	enabled := hasOwn(b.Services)
	if granted {
		mode := "disable"
		if enabled {
			mode = "enable"
		}
		if e = a.mustSh(s, "settings", "put", "secure", "ptv_installer_shortcuts", mode); e != nil {
			return e
		}
		if e = a.mustSh(s, "am", "start", "-n", activity); e != nil {
			return e
		}
		time.Sleep(2 * time.Second)
		request, e := a.sh(s, "settings", "get", "secure", "ptv_installer_shortcuts")
		if e != nil {
			return e
		}
		if request != "null" && request != "" {
			return errors.New("PTV 尚未确认停止自动恢复，请解锁电视并重新恢复")
		}
	}
	raw, e := a.sh(s, "settings", "get", "secure", "enabled_accessibility_services")
	if e != nil {
		return e
	}
	merged := mergeServices(raw, enabled)
	if e = a.mustSh(s, "settings", "put", "secure", "enabled_accessibility_services", merged); e != nil {
		return e
	}
	flag := "1"
	if merged == "" {
		flag = "0"
	}
	if e = a.mustSh(s, "settings", "put", "secure", "accessibility_enabled", flag); e != nil {
		return e
	}
	if granted && !b.SecureGranted {
		if e = a.mustSh(s, "pm", "revoke", pkg, "android.permission.WRITE_SECURE_SETTINGS"); e != nil {
			return e
		}
	}

	if b.NetworkBridgeInstalled {
		if e = a.mustSh(s, "sh", "-c", "pkill -f '^ptv-control-shell$' || true"); e != nil {
			return e
		}
		if _, e = a.run("-s", s, "uninstall", "com.popkter.ptvlauncher.networkbridge"); e != nil {
			return e
		}
	}
	if b.ControlHelperStarted && !b.NetworkBridgeInstalled {
		if e = a.mustSh(s, "sh", "-c", "pkill -f '^ptv-control-shell$' || true"); e != nil {
			return e
		}
	}
	if b.HadApp && b.WriteSettingsMode != "" {
		if e = a.mustSh(s, "cmd", "appops", "set", pkg, "WRITE_SETTINGS", b.WriteSettingsMode); e != nil {
			return e
		}
	}
	if b.BridgeInstalled {
		p, e := a.sh(s, "pm", "list", "packages", "--user", "0", "com.ts.launcher")
		if e != nil {
			return e
		}
		if strings.Contains(p, "package:com.ts.launcher") {
			if _, e = a.run("-s", s, "uninstall", "com.ts.launcher"); e != nil {
				return e
			}
		}
	}
	if e = a.mustSh(s, "input", "keyevent", "KEYCODE_HOME"); e != nil {
		return e
	}
	if e = os.Rename(a.backupPath(t), a.backupPath(t)+".restored-"+time.Now().Format("20060102-150405")); e != nil {
		return e
	}
	a.mu.Lock()
	a.state.Backup = false
	a.state.Checks = nil
	a.mu.Unlock()
	a.phase("原配置已恢复，PTV 与用户数据保留", 100)
	return nil
}
func (a *App) execute(r Request) error {
	switch r.Action {
	case "saveReleaseSource":
		return a.saveReleaseSource(r.ReleaseSource)
	case "checkRelease":
		return a.checkRelease()
	case "quit":
		time.AfterFunc(150*time.Millisecond, a.stop)
		return nil
	case "scan":
		return a.scan()
	case "connect":
		ep, e := historyAddress(r.Address)
		if e != nil {
			return e
		}
		a.phase("连接电视，请留意电视上的授权提示", 10)
		o, e := a.run("connect", ep)
		a.log(o)
		if e != nil {
			return e
		}
		if e = a.inspect(ep); e != nil {
			return e
		}
		a.rememberConnection(ep)
		a.phase("已连接电视，可以安装或配置 PTV", 100)
		return nil
	case "deleteHistory":
		return a.changeHistory(r.Address, true)
	case "pair":
		ep, e := endpoint(r.Address)
		if e != nil {
			return e
		}
		if !regexp.MustCompile(`^\d{6}$`).MatchString(r.Code) {
			return errors.New("配对码需要 6 位数字")
		}
		o, e := a.run("pair", ep, r.Code)
		if e == nil {
			a.log(o)
		}
		return e
	case "select":
		if e := a.inspect(r.Serial); e != nil {
			return e
		}
		// USB serials are not network addresses; selecting a remote device is.
		if strings.Contains(r.Serial, ":") {
			if address, e := historyAddress(r.Serial); e == nil {
				a.rememberConnection(address)
			}
		}
		return nil
	case "install", "configure", "installOnly", "verify", "restore", "restoreNative", "reboot", "homeSettings":
		t, e := a.selected()
		if e != nil {
			return e
		} // Recheck identity before every mutation, including IP address reuse.
		if e = a.inspect(t.Serial); e != nil {
			return e
		}
		fresh, _ := a.selected()
		if fresh.Identity != t.Identity {
			return errors.New("此地址的设备已改变，请重新选择并确认电视")
		}
		switch r.Action {
		case "homeSettings":
			if e = a.mustSh(t.Serial, "am", "start", "-a", "android.settings.HOME_SETTINGS"); e != nil {
				return a.mustSh(t.Serial, "am", "start", "-n", activity, "--ez", "open_settings", "true")
			}
			return nil
		case "install", "configure":
			return a.install(fresh, r.Bridge)
		case "installOnly":
			if _, e = a.snapshot(fresh); e != nil {
				return e
			}
			a.phase("安装或更新 PTV，保留数据", 25)
			if e = a.installAPK(fresh); e != nil {
				return e
			}
			a.mu.Lock()
			a.state.Checks = []Check{{"PTV 已安装", "ok", "已安装所选来源版本；点击一键配置启用桌面与快捷功能"}}
			a.mu.Unlock()
			a.phase("PTV 安装完成", 100)
			return nil
		case "verify":
			return a.verify(fresh)
		case "restore":
			return a.restore(fresh)
		case "restoreNative":
			if !r.ConfirmNative {
				return errors.New("请确认还原原生桌面及所选的数据处理方式")
			}
			return a.restoreNative(fresh, r.RemovePTV)
		case "reboot":
			a.phase("电视正在重启，请稍后重新连接并检查", 10)
			_, e = a.run("-s", t.Serial, "reboot")
			return e
		}
	}
	return errors.New("未知操作")
}
func (a *App) api(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if r.Host != strings.TrimPrefix(a.origin, "http://") || r.Header.Get("X-PTV-Token") != a.token {
		http.Error(w, "Forbidden", 403)
		return
	}
	if r.Method == "GET" && r.URL.Path == "/api/state" {
		a.mu.Lock()
		defer a.mu.Unlock()
		a.lastSeen = time.Now()
		json.NewEncoder(w).Encode(a.state)
		return
	}
	if r.Method != "POST" || r.URL.Path != "/api/action" || r.Header.Get("Origin") != a.origin {
		http.Error(w, "Forbidden", 403)
		return
	}
	var req Request
	if e := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); e != nil {
		http.Error(w, "Invalid request", 400)
		return
	}
	if req.Action == "windowClosed" {
		a.mu.Lock()
		a.uiGoneAt = time.Now()
		a.mu.Unlock()
		w.WriteHeader(202)
		return
	}
	if req.Action == "deleteHistory" {
		if err := a.changeHistory(req.Address, true); err != nil {
			http.Error(w, "无法删除连接记录："+err.Error(), 500)
			return
		}
		w.Write([]byte(`{"deleted":true}`))
		return
	}
	a.mu.Lock()
	if a.state.Busy {
		a.mu.Unlock()
		http.Error(w, "已有操作正在执行", 409)
		return
	}
	a.state.Busy = true
	a.state.Error = ""
	a.mu.Unlock()
	go func() {
		e := a.execute(req)
		a.mu.Lock()
		a.state.Busy = false
		if e != nil {
			a.state.Error = e.Error()
			a.state.Phase = "操作未完成"
		}
		if req.Action == "reboot" {
			a.state.TV = nil
		}
		a.mu.Unlock()
		if e != nil {
			a.log(e.Error())
		}
	}()
	w.WriteHeader(202)
	w.Write([]byte(`{"accepted":true}`))
}
func main() {
	if !singleInstance() {
		return
	}
	defer releaseInstance()
	dir, e := os.UserConfigDir()
	if e != nil {
		panic(e)
	}
	dir = filepath.Join(dir, "PTVInstaller")
	if e = os.MkdirAll(filepath.Join(dir, "payload"), 0700); e != nil {
		panic(e)
	}
	entries, _ := assets.ReadDir("payload")
	for _, v := range entries {
		if v.IsDir() {
			continue
		}
		b, e := assets.ReadFile("payload/" + v.Name())
		if e != nil {
			panic(e)
		}
		p := filepath.Join(dir, "payload", v.Name())
		old, _ := os.ReadFile(p)
		if sha256.Sum256(b) != sha256.Sum256(old) {
			if e = os.WriteFile(p, b, 0700); e != nil {
				panic(e)
			}
		}
	}
	a := &App{dir: dir, adb: filepath.Join(dir, "payload", "adb.exe"), state: State{Phase: "准备连接电视", Logs: []string{}, Devices: []Device{}, Checks: []Check{}}}
	if err := a.loadHistory(); err != nil {
		a.log("无法读取连接历史：" + err.Error())
	}
	if err := a.loadReleaseSource(); err != nil {
		a.log("无法读取更新来源：" + err.Error())
	}
	nativeContext(a)
	defer a.stop()
	a.serveNative(os.Stdin, os.Stdout)

}

// Installation is explicit; configuration calls this only if PTV is absent.
func (a *App) installAPK(t *TV) error {
	path := filepath.Join(a.dir, "payload", "PTV.apk")
	a.mu.Lock()
	source := a.state.ReleaseSource
	a.mu.Unlock()
	if source.Enabled {
		downloaded, cleanup, err := a.downloadRelease(source)
		if err != nil {
			return err
		}
		defer cleanup()
		path = downloaded
		// A download can take minutes; recheck identity before installing.
		if err = a.inspect(t.Serial); err != nil {
			return err
		}
		fresh, err := a.selected()
		if err != nil {
			return err
		}
		if fresh.Identity != t.Identity {
			return errors.New("下载期间设备已改变，请重新连接电视")
		}
	}
	a.phase("安装 PTV，保留已有数据", 50)
	out, err := a.run("-s", t.Serial, "install", "-r", path)
	if err != nil {
		return fmt.Errorf("安装失败（不会卸载旧版或清除数据）：%w", err)
	}
	if !strings.Contains(out, "Success") {
		return errors.New("安装结果未确认：" + out)
	}
	return nil
}

func (a *App) stop() {
	a.stopOnce.Do(func() {
		if a.cancel != nil {
			a.cancel()
		}
		if a.done != nil {
			close(a.done)
		}
	})
}

func (a *App) browserGone(now time.Time) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	// A new page heartbeat cancels an earlier pagehide (e.g. refresh).
	return (!a.uiGoneAt.IsZero() && a.lastSeen.Before(a.uiGoneAt) && now.Sub(a.uiGoneAt) >= time.Second) ||
		(!a.state.Busy && now.Sub(a.lastSeen) > 3*time.Minute)
}

func (a *App) watchBrowserFallback() {
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-a.done:
			return
		case now := <-ticker.C:
			if a.browserGone(now) {
				a.stop()
				return
			}
		}
	}
}
