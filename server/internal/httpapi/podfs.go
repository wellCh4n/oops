package httpapi

import (
	"fmt"
	"net/http"
	"net/url"
	"path"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

const (
	podFSMaxDownloadBytes = 52_428_800
	podFSMaxUploadBytes   = 52_428_800
	podFSMaxEditBytes     = 1_048_576
)

func respondPodFS(c *gin.Context, err error) {
	if k8s.IsPodFSError(err) {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusInternalServerError, fail(err.Error()))
}

// podFSTarget resolves the shared env/container/pod triple.
func (s *Server) podFSTarget(c *gin.Context) (*k8s.Cluster, string, string, string, bool) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return nil, "", "", "", false
	}
	container := c.Query("container")
	if container == "" {
		container = c.Param("name")
	}
	return cluster, c.Param("namespace"), c.Param("pod"), container, true
}

func (s *Server) podFSList(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	entries, err := k8s.ListPodDirectory(c.Request.Context(), cluster, namespace, pod, container, c.DefaultQuery("path", "/"))
	if err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(entries))
}

func (s *Server) podFSDownload(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	filePath := c.Query("path")
	size, err := k8s.GetPodFileSize(c.Request.Context(), cluster, namespace, pod, container, filePath)
	if err != nil {
		respondPodFS(c, err)
		return
	}
	if size > podFSMaxDownloadBytes {
		c.JSON(http.StatusOK, fail(fmt.Sprintf("File too large: %d bytes (max %d MB)", size, podFSMaxDownloadBytes/(1024*1024))))
		return
	}
	fileName := filePath[strings.LastIndex(filePath, "/")+1:]
	c.Header("Content-Type", "application/octet-stream")
	c.Header("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(fileName))
	c.Header("Content-Length", fmt.Sprintf("%d", size))
	_ = k8s.StreamPodFile(c.Request.Context(), cluster, namespace, pod, container, filePath, c.Writer)
}

func (s *Server) podFSContent(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	filePath := c.Query("path")
	content, err := k8s.ReadPodTextFile(c.Request.Context(), cluster, namespace, pod, container, filePath, podFSMaxEditBytes)
	if err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{"path": filePath, "content": content}))
}

func (s *Server) podFSSaveContent(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	var request struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || request.Path == "" {
		c.JSON(http.StatusOK, fail("Path is required"))
		return
	}
	if err := k8s.WritePodTextFile(c.Request.Context(), cluster, namespace, pod, container, request.Path, request.Content); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) podFSUpload(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	file, err := c.FormFile("file")
	if err != nil || file.Size == 0 {
		c.JSON(http.StatusOK, fail("File is required"))
		return
	}
	if file.Size > podFSMaxUploadBytes {
		c.JSON(http.StatusOK, fail(fmt.Sprintf("File too large: %d bytes (max %d MB)", file.Size, podFSMaxUploadBytes/(1024*1024))))
		return
	}
	targetPath := c.Query("path")
	if targetPath == "" {
		targetPath = "/"
	}
	if strings.HasSuffix(targetPath, "/") || !strings.Contains(path.Base(targetPath), ".") {
		targetPath = strings.TrimSuffix(targetPath, "/") + "/" + file.Filename
	}
	source, err := file.Open()
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	defer source.Close()
	if err := k8s.UploadPodFile(c.Request.Context(), cluster, namespace, pod, container, targetPath, source); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) podFSDelete(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	if err := k8s.DeletePodPath(c.Request.Context(), cluster, namespace, pod, container, c.Query("path")); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) podFSMkdir(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	var request struct {
		Path string `json:"path"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || request.Path == "" {
		c.JSON(http.StatusOK, fail("Path is required"))
		return
	}
	if err := k8s.CreatePodDirectory(c.Request.Context(), cluster, namespace, pod, container, request.Path); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) podFSRename(c *gin.Context) {
	cluster, namespace, pod, container, connected := s.podFSTarget(c)
	if !connected {
		return
	}
	var request struct {
		FromPath string `json:"fromPath"`
		ToPath   string `json:"toPath"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || request.FromPath == "" || request.ToPath == "" {
		c.JSON(http.StatusOK, fail("Path is required"))
		return
	}
	if err := k8s.RenamePodPath(c.Request.Context(), cluster, namespace, pod, container, request.FromPath, request.ToPath); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}
