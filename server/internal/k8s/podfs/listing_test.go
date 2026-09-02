package podfs

import (
	"testing"

	"github.com/wellch4n/oops/server/internal/domain"
)

func TestParseListingMarkers(t *testing.T) {
	cases := map[string]string{
		"__OOPS_NOT_FOUND__\n": "Path not found",
		"__OOPS_NOT_DIR__\n":   "Not a directory",
		"__OOPS_DENIED__\n":    "Permission denied",
	}
	for output, wantMessage := range cases {
		_, err := parseListing(output, "/x")
		if domain.BizMessage(err) != wantMessage {
			t.Fatalf("parseListing(%q) error = %v, want %q", output, err, wantMessage)
		}
	}
}

func TestParseListingSortsAndBuildsPaths(t *testing.T) {
	output := "F\t12\tzeta.txt\n" +
		"D\t0\tbin\n" +
		"LF\t3\tAlpha.link\n" +
		"O\tnotanumber\tsocket\n" +
		"LD\t0\tApp\n" +
		"broken line without tabs\n" +
		"F\t7\tname\twith\ttab\n" +
		"\n"
	entries, err := parseListing(output, "/srv")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	wantNames := []string{"App", "bin", "Alpha.link", "name\twith\ttab", "socket", "zeta.txt"}
	if len(entries) != len(wantNames) {
		t.Fatalf("got %d entries, want %d: %+v", len(entries), len(wantNames), entries)
	}
	for index, want := range wantNames {
		if entries[index].Name != want {
			t.Fatalf("entry %d name = %q, want %q (all: %+v)", index, entries[index].Name, want, entries)
		}
	}
	if entries[0].Type != FileTypeSymlinkDirectory || entries[1].Type != FileTypeDirectory {
		t.Fatalf("directories should sort first: %+v", entries[:2])
	}
	if entries[0].Path != "/srv/App" || entries[5].Path != "/srv/zeta.txt" {
		t.Fatalf("unexpected paths: %q %q", entries[0].Path, entries[5].Path)
	}
	if entries[4].Type != FileTypeOther || entries[4].Size != nil {
		t.Fatalf("unparsable size should be nil and kind O should be OTHER: %+v", entries[4])
	}
	if entries[5].Size == nil || *entries[5].Size != 12 {
		t.Fatalf("size not parsed: %+v", entries[5])
	}
	if entries[3].Size == nil || *entries[3].Size != 7 {
		t.Fatalf("names containing tabs must survive SplitN: %+v", entries[3])
	}
}

func TestParseListingRootPathJoinsWithoutDoubleSlash(t *testing.T) {
	entries, err := parseListing("D\t0\tetc\n", "/")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if entries[0].Path != "/etc" {
		t.Fatalf("path = %q, want /etc", entries[0].Path)
	}
	empty, err := parseListing("", "/empty")
	if err != nil || len(empty) != 0 {
		t.Fatalf("empty output should give empty slice, got %v %v", empty, err)
	}
}

func TestSanitizePath(t *testing.T) {
	if domain.BizMessage(sanitizePath("  ")) != "Path is required" {
		t.Fatal("blank path should be rejected")
	}
	if domain.BizMessage(sanitizePath("/a\nb")) != "Invalid path" {
		t.Fatal("newline should be rejected")
	}
	if sanitizePath("/ok") != nil {
		t.Fatal("plain path should pass")
	}
}
