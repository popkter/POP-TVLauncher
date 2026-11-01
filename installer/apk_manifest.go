package main

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"io"
	"unicode/utf16"
)

// Parse Android's binary manifest before passing a downloaded APK to ADB.
// Bounds checks reject malformed chunks without installing another application.
func validatePTVAPK(path string) error {
	z, err := zip.OpenReader(path)
	if err != nil {
		return err
	}
	defer z.Close()
	for _, f := range z.File {
		if f.Name != "AndroidManifest.xml" {
			continue
		}
		if f.UncompressedSize64 > 2<<20 {
			break
		}
		r, e := f.Open()
		if e != nil {
			return e
		}
		b, e := io.ReadAll(io.LimitReader(r, 2<<20))
		r.Close()
		if e != nil {
			return e
		}
		if manifestPackage(b) == pkg {
			return nil
		}
		break
	}
	return errors.New("下载的 APK 不是有效的 PTV 安装包，已停止安装")
}
func manifestPackage(b []byte) string {
	u16 := func(b []byte) int { return int(binary.LittleEndian.Uint16(b)) }
	u32 := func(b []byte) int { return int(binary.LittleEndian.Uint32(b)) }
	if len(b) < 8 || u16(b) != 3 || u32(b[4:]) != len(b) {
		return ""
	}
	var pool []byte
	var count, start, header int
	var utf8 bool
	str := func(index int) string {
		if index < 0 || index >= count || header+index*4+4 > len(pool) {
			return ""
		}
		pos := start + u32(pool[header+index*4:])
		if pos < start || pos >= len(pool) {
			return ""
		}
		length := func(wide bool) int {
			if wide {
				if pos+2 > len(pool) {
					return -1
				}
				n := u16(pool[pos:])
				pos += 2
				if n&0x8000 != 0 {
					if pos+2 > len(pool) {
						return -1
					}
					n = ((n & 0x7fff) << 16) | u16(pool[pos:])
					pos += 2
				}
				return n
			}
			if pos >= len(pool) {
				return -1
			}
			n := int(pool[pos])
			pos++
			if n&0x80 != 0 {
				if pos >= len(pool) {
					return -1
				}
				n = ((n & 0x7f) << 8) | int(pool[pos])
				pos++
			}
			return n
		}
		if utf8 {
			if length(false) < 0 {
				return ""
			}
			n := length(false)
			if n < 0 || n > len(pool)-pos-1 || pool[pos+n] != 0 {
				return ""
			}
			return string(pool[pos : pos+n])
		}
		n := length(true)
		if n < 0 || n > (len(pool)-pos-2)/2 {
			return ""
		}
		chars := make([]uint16, n)
		for i := range chars {
			chars[i] = uint16(u16(pool[pos+i*2:]))
		}
		return string(utf16.Decode(chars))
	}
	for pos := u16(b[2:]); pos >= 8 && pos+8 <= len(b); {
		c := b[pos:]
		kind, h, size := u16(c), u16(c[2:]), u32(c[4:])
		if h < 8 || size < h || size > len(c) {
			return ""
		}
		c = c[:size]
		switch kind {
		case 1:
			if h < 28 {
				return ""
			}
			pool = c
			header = h
			count = u32(c[8:])
			start = u32(c[20:])
			utf8 = u32(c[16:])&0x100 != 0
			if count > (len(c)-h)/4 || start < h+count*4 || start > len(c) {
				return ""
			}
		case 0x102:
			if h < 16 || h+20 > len(c) {
				return ""
			}
			ext := c[h:]
			if str(u32(ext[4:])) != "manifest" {
				break
			}
			offset, stride, n := u16(ext[8:]), u16(ext[10:]), u16(ext[12:])
			if offset < 20 || stride < 20 || offset > len(ext) || n > (len(ext)-offset)/stride {
				return ""
			}
			for i := 0; i < n; i++ {
				attr := ext[offset+i*stride:]
				if u32(attr) == int(uint32(0xffffffff)) && str(u32(attr[4:])) == "package" {
					if u32(attr[8:]) != int(uint32(0xffffffff)) {
						return str(u32(attr[8:]))
					}
					if attr[15] == 3 {
						return str(u32(attr[16:]))
					}
					return ""
				}
			}
		}
		pos += size
	}
	return ""
}
