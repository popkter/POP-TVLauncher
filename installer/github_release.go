package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type ReleaseSource struct {
	Enabled    bool   `json:"enabled"`
	Repository string `json:"repository"`
	Asset      string `json:"asset"`
}
type githubAsset struct {
	Name   string `json:"name"`
	URL    string `json:"browser_download_url"`
	Size   int64  `json:"size"`
	Digest string `json:"digest"`
}
type githubRelease struct {
	Tag        string        `json:"tag_name"`
	Draft      bool          `json:"draft"`
	Prerelease bool          `json:"prerelease"`
	Assets     []githubAsset `json:"assets"`
}

const maxReleaseAPK = 256 << 20

var releaseHTTP = &http.Client{Timeout: 5 * time.Minute, CheckRedirect: func(req *http.Request, via []*http.Request) error {
	if len(via) >= 8 {
		return errors.New("下载重定向过多")
	}
	host := req.URL.Hostname()
	if req.URL.Scheme != "https" || !(host == "github.com" || host == "release-assets.githubusercontent.com" || host == "objects.githubusercontent.com") {
		return errors.New("下载地址不是 GitHub 官方 HTTPS 地址")
	}
	return nil
}}

func normalizeReleaseSource(s ReleaseSource) (ReleaseSource, error) {
	s.Repository = strings.TrimSuffix(strings.TrimSpace(s.Repository), "/")
	s.Repository = strings.TrimPrefix(s.Repository, "https://github.com/")
	s.Repository = strings.TrimSuffix(s.Repository, ".git")
	s.Asset = strings.TrimSpace(s.Asset)
	if s.Asset == "" {
		s.Asset = "PTV.apk"
	}
	if s.Repository != "" && !regexp.MustCompile(`^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+$`).MatchString(s.Repository) {
		return s, errors.New("请输入 GitHub 仓库地址或 用户名/仓库名")
	}
	if s.Enabled && s.Repository == "" {
		return s, errors.New("请先填写 GitHub 仓库")
	}
	if filepath.Base(s.Asset) != s.Asset || strings.ContainsAny(s.Asset, `/\`) || !strings.HasSuffix(strings.ToLower(s.Asset), ".apk") {
		return s, errors.New("请填写 Release 中 PTV APK 的完整文件名")
	}
	return s, nil
}
func (a *App) loadReleaseSource() error {
	data, err := os.ReadFile(filepath.Join(a.dir, "release-source.json"))
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var s ReleaseSource
	if err = json.Unmarshal(data, &s); err != nil {
		return err
	}
	s, err = normalizeReleaseSource(s)
	if err != nil {
		return err
	}
	a.state.ReleaseSource = s
	return nil
}
func (a *App) saveReleaseSource(s ReleaseSource) error {
	s, err := normalizeReleaseSource(s)
	if err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(a.dir, 0700); err != nil {
		return err
	}
	path := filepath.Join(a.dir, "release-source.json")
	if err = os.WriteFile(path+".tmp", data, 0600); err != nil {
		return err
	}
	if err = replaceFile(path+".tmp", path); err != nil {
		return err
	}
	a.mu.Lock()
	a.state.ReleaseSource = s
	a.state.ReleaseInfo = ""
	a.mu.Unlock()
	a.phase("安装来源已保存", 100)
	return nil
}
func (a *App) releaseRequest(address string) (*http.Response, error) {
	ctx := a.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	req, err := http.NewRequestWithContext(ctx, "GET", address, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "PTV-Installer")
	req.Header.Set("Accept", "application/vnd.github+json")
	res, err := releaseHTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("GitHub 连接失败：%w", err)
	}
	if res.StatusCode != http.StatusOK {
		res.Body.Close()
		return nil, fmt.Errorf("GitHub 返回 %d：请检查公开仓库、正式 Release 或请求限额", res.StatusCode)
	}
	return res, nil
}
func (a *App) latestRelease(s ReleaseSource) (githubRelease, githubAsset, error) {
	var r githubRelease
	s, err := normalizeReleaseSource(s)
	if err != nil {
		return r, githubAsset{}, err
	}
	if s.Repository == "" {
		return r, githubAsset{}, errors.New("请先填写并保存 GitHub 仓库")
	}
	res, err := a.releaseRequest("https://api.github.com/repos/" + s.Repository + "/releases/latest")
	if err != nil {
		return r, githubAsset{}, err
	}
	defer res.Body.Close()
	if err = json.NewDecoder(io.LimitReader(res.Body, 2<<20)).Decode(&r); err != nil {
		return r, githubAsset{}, err
	}
	if r.Draft || r.Prerelease || r.Tag == "" {
		return r, githubAsset{}, errors.New("未找到正式发布版本")
	}
	for _, asset := range r.Assets {
		if asset.Name != s.Asset {
			continue
		}
		u, e := url.Parse(asset.URL)
		if e != nil || u.Scheme != "https" || u.Host != "github.com" || u.User != nil || !strings.HasPrefix(u.Path, "/"+s.Repository+"/releases/download/") {
			return r, asset, errors.New("Release 安装包下载地址无效")
		}
		if asset.Size <= 0 || asset.Size > maxReleaseAPK {
			return r, asset, errors.New("APK 大小无效或超过 256 MB")
		}
		if !regexp.MustCompile(`^sha256:[a-fA-F0-9]{64}$`).MatchString(asset.Digest) {
			return r, asset, errors.New("该 APK 缺少 GitHub SHA256 校验值，请在 Release 重新上传 APK")
		}
		return r, asset, nil
	}
	return r, githubAsset{}, fmt.Errorf("最新正式版 %s 没有 %s，请检查 Release 附件文件名", r.Tag, s.Asset)
}
func (a *App) checkRelease() error {
	a.mu.Lock()
	s := a.state.ReleaseSource
	a.mu.Unlock()
	a.phase("检查 GitHub 最新正式版", 10)
	r, asset, err := a.latestRelease(s)
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.state.ReleaseInfo = fmt.Sprintf("最新正式版 %s · %s · %.1f MB", r.Tag, asset.Name, float64(asset.Size)/(1<<20))
	a.mu.Unlock()
	a.phase("已找到最新正式版；点击安装／更新 PTV 安装", 100)
	return nil
}
func (a *App) downloadRelease(s ReleaseSource) (string, func(), error) {
	noop := func() {}
	a.phase("获取 GitHub 最新正式版", 15)
	r, asset, err := a.latestRelease(s)
	if err != nil {
		return "", noop, err
	}
	a.phase("下载 PTV "+r.Tag, 20)
	res, err := a.releaseRequest(asset.URL)
	if err != nil {
		return "", noop, err
	}
	defer res.Body.Close()
	dir := filepath.Join(a.dir, "downloads")
	if err = os.MkdirAll(dir, 0700); err != nil {
		return "", noop, err
	}
	f, err := os.CreateTemp(dir, "ptv-*.apk")
	if err != nil {
		return "", noop, err
	}
	cleanup := func() { os.Remove(f.Name()) }
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(res.Body, asset.Size+1))
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil && (n != asset.Size || !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), strings.TrimPrefix(asset.Digest, "sha256:"))) {
		err = errors.New("APK 大小或 SHA256 校验失败，已停止安装")
	}
	if err == nil {
		err = validatePTVAPK(f.Name())
	}
	if err != nil {
		cleanup()
		return "", noop, err
	}
	a.log("已校验 GitHub " + s.Repository + " / " + r.Tag + " / " + asset.Name)
	return f.Name(), cleanup, nil
}
