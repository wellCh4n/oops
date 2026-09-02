package podfs

import (
	"sort"
	"strconv"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
)

// parseListing turns the list script's stdout into sorted entries: marker
// lines become BizErrors, each "<kind>\t<size>\t<name>" line becomes an Entry
// under basePath, directories (real or symlinked) sort first, then names
// case-insensitively.
func parseListing(output, basePath string) ([]Entry, error) {
	switch {
	case strings.Contains(output, "__OOPS_NOT_FOUND__"):
		return nil, domain.Biz("Path not found")
	case strings.Contains(output, "__OOPS_NOT_DIR__"):
		return nil, domain.Biz("Not a directory")
	case strings.Contains(output, "__OOPS_DENIED__"):
		return nil, domain.Biz("Permission denied")
	}

	entries := make([]Entry, 0)
	for _, line := range strings.Split(output, "\n") {
		if line == "" {
			continue
		}
		parts := strings.SplitN(line, "\t", 3)
		if len(parts) < 3 {
			continue
		}
		entryType := fileTypeOf(parts[0])
		var size *int64
		if parsed, err := strconv.ParseInt(parts[1], 10, 64); err == nil {
			size = &parsed
		}
		name := parts[2]
		entries = append(entries, Entry{Name: name, Path: joinPath(basePath, name), Type: entryType, Size: size})
	}

	sort.SliceStable(entries, func(left, right int) bool {
		leftDirectory := entries[left].Type.IsDirectory()
		rightDirectory := entries[right].Type.IsDirectory()
		if leftDirectory != rightDirectory {
			return leftDirectory
		}
		return strings.ToLower(entries[left].Name) < strings.ToLower(entries[right].Name)
	})
	return entries, nil
}

func fileTypeOf(kind string) FileType {
	switch kind {
	case "D":
		return FileTypeDirectory
	case "F":
		return FileTypeFile
	case "LD":
		return FileTypeSymlinkDirectory
	case "LF":
		return FileTypeSymlinkFile
	default:
		return FileTypeOther
	}
}

func joinPath(base, name string) string {
	if strings.HasSuffix(base, "/") {
		return base + name
	}
	return base + "/" + name
}
