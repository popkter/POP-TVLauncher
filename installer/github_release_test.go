package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type releaseTransport func(*http.Request) (*http.Response, error)

func (f releaseTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func mockRelease(t *testing.T, data []byte, mutate func(*githubRelease)) {
	t.Helper()
	digest := sha256.Sum256(data)
	r := githubRelease{Tag: "v9.0", Assets: []githubAsset{{Name: "PTV.apk", URL: "https://github.com/test/ptv/releases/download/v9.0/PTV.apk", Size: int64(len(data)), Digest: "sha256:" + hex.EncodeToString(digest[:])}}}
	if mutate != nil {
		mutate(&r)
	}
	original := releaseHTTP
	t.Cleanup(func() { releaseHTTP = original })
	releaseHTTP = &http.Client{Transport: releaseTransport(func(req *http.Request) (*http.Response, error) {
		if err := req.Context().Err(); err != nil {
			return nil, err
		}
		body := data
		if req.URL.Host == "api.github.com" {
			body, _ = json.Marshal(r)
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(body)), Header: make(http.Header)}, nil
	})}
}
func TestReleaseDownloadValidation(t *testing.T) {
	valid, err := assets.ReadFile("payload/PTV.apk")
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name   string
		data   []byte
		mutate func(*githubRelease)
		ok     bool
	}{
		{"valid", valid, nil, true},
		{"hash mismatch", valid, func(r *githubRelease) { r.Assets[0].Digest = "sha256:" + strings.Repeat("0", 64) }, false},
		{"truncated", valid, func(r *githubRelease) { r.Assets[0].Size++ }, false},
		{"wrong package", []byte("not APK"), nil, false},
		{"wrong host", valid, func(r *githubRelease) { r.Assets[0].URL = "https://example.com/PTV.apk" }, false},
		{"missing asset", valid, func(r *githubRelease) { r.Assets[0].Name = "HomeBridge.apk" }, false},
		{"missing digest", valid, func(r *githubRelease) { r.Assets[0].Digest = "" }, false},
		{"oversized", valid, func(r *githubRelease) { r.Assets[0].Size = maxReleaseAPK + 1 }, false},
		{"prerelease", valid, func(r *githubRelease) { r.Prerelease = true }, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			mockRelease(t, test.data, test.mutate)
			a := &App{dir: t.TempDir()}
			path, cleanup, err := a.downloadRelease(ReleaseSource{Repository: "test/ptv", Asset: "PTV.apk"})
			if (err == nil) != test.ok {
				t.Fatalf("unexpected result: %v", err)
			}
			if test.ok {
				b, _ := os.ReadFile(path)
				if !bytes.Equal(b, valid) {
					t.Fatal("wrong APK")
				}
			}
			cleanup()
			files, _ := filepath.Glob(filepath.Join(a.dir, "downloads", "*"))
			if len(files) != 0 {
				t.Fatal("download not cleaned")
			}
		})
	}
}
func TestReleaseSettingsAndCancellation(t *testing.T) {
	a := &App{dir: t.TempDir()}
	if err := a.saveReleaseSource(ReleaseSource{Enabled: true, Repository: "https://github.com/test/ptv.git"}); err != nil {
		t.Fatal(err)
	}
	b := &App{dir: a.dir}
	if err := b.loadReleaseSource(); err != nil {
		t.Fatal(err)
	}
	if b.state.ReleaseSource.Repository != "test/ptv" || b.state.ReleaseSource.Asset != "PTV.apk" || !b.state.ReleaseSource.Enabled {
		t.Fatal(b.state.ReleaseSource)
	}
	if err := b.saveReleaseSource(ReleaseSource{Enabled: true, Repository: "https://example.com/test/ptv"}); err == nil {
		t.Fatal("accepted foreign source")
	}
	valid, _ := assets.ReadFile("payload/PTV.apk")
	mockRelease(t, valid, nil)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	a.ctx = ctx
	if _, _, err := a.downloadRelease(a.state.ReleaseSource); err == nil {
		t.Fatal("ignored cancellation")
	}
}
func TestManifestRejectsOtherPackageAndMalformed(t *testing.T) {
	if err := validatePTVAPK(filepath.Join("payload", "HomeBridge.apk")); err == nil {
		t.Fatal("accepted HomeBridge as PTV")
	}
	for n := 0; n < 100; n++ {
		manifestPackage(make([]byte, n))
	}
}
func TestOnlineFailureNeverInstallsEmbeddedAPK(t *testing.T) {
	a := &App{dir: t.TempDir(), state: State{ReleaseSource: ReleaseSource{Enabled: true, Repository: "test/ptv"}}, runOverride: func(...string) (string, error) { t.Fatal("ADB called after download failure"); return "", nil }}
	valid, _ := assets.ReadFile("payload/PTV.apk")
	mockRelease(t, valid, func(r *githubRelease) { r.Assets = nil })
	if err := a.installAPK(&TV{}); err == nil {
		t.Fatal("expected download failure")
	}
}
