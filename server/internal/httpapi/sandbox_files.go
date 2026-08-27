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

func (s *Server) sandboxFilesList(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	entries, err := k8s.ListPodDirectory(c.Request.Context(), cluster, workNamespace, podName, "sandbox", c.DefaultQuery("path", "/"))
	if err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(entries))
}

func (s *Server) sandboxFilesDownload(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	filePath := c.Query("path")
	size, err := k8s.GetPodFileSize(c.Request.Context(), cluster, workNamespace, podName, "sandbox", filePath)
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
	_ = k8s.StreamPodFile(c.Request.Context(), cluster, workNamespace, podName, "sandbox", filePath, c.Writer)
}

func (s *Server) sandboxFilesContent(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	filePath := c.Query("path")
	content, err := k8s.ReadPodTextFile(c.Request.Context(), cluster, workNamespace, podName, "sandbox", filePath, podFSMaxEditBytes)
	if err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{"path": filePath, "content": content}))
}

func (s *Server) sandboxFilesSave(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
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
	if err := k8s.WritePodTextFile(c.Request.Context(), cluster, workNamespace, podName, "sandbox", request.Path, request.Content); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) sandboxFilesUpload(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
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
	if err := k8s.UploadPodFile(c.Request.Context(), cluster, workNamespace, podName, "sandbox", targetPath, source); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) sandboxFilesDelete(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	if err := k8s.DeletePodPath(c.Request.Context(), cluster, workNamespace, podName, "sandbox", c.Query("path")); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) sandboxFilesRename(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
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
	if err := k8s.RenamePodPath(c.Request.Context(), cluster, workNamespace, podName, "sandbox", request.FromPath, request.ToPath); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

func (s *Server) sandboxFilesMkdir(c *gin.Context) {
	cluster, workNamespace, podName, found := s.sandboxFilesTarget(c)
	if !found {
		return
	}
	var request struct {
		Path string `json:"path"`
	}
	if err := c.ShouldBindJSON(&request); err != nil || request.Path == "" {
		c.JSON(http.StatusOK, fail("Path is required"))
		return
	}
	if err := k8s.CreatePodDirectory(c.Request.Context(), cluster, workNamespace, podName, "sandbox", request.Path); err != nil {
		respondPodFS(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}

// registerSandbox wires /api/sandbox and /openapi/sandbox.
func (s *Server) registerSandbox(api *gin.RouterGroup) {
	sandbox := api.Group("/sandbox")
	sandbox.GET("/images", s.listSandboxImages)
	sandbox.POST("/executions", s.sandboxExecute)
	sandbox.POST("/instances", s.createSandboxInstance)
	sandbox.GET("/instances", s.listSandboxInstances)
	sandbox.GET("/instances/:id", s.getSandboxInstance)
	sandbox.DELETE("/instances/:id", s.deleteSandboxInstance)
	sandbox.POST("/instances/:id/exec", s.execSandboxInstance)
	sandbox.GET("/instances/:id/terminal", s.sandboxTerminalWebSocket)
	files := sandbox.Group("/instances/:id/files")
	files.GET("", s.sandboxFilesList)
	files.GET("/download", s.sandboxFilesDownload)
	files.GET("/content", s.sandboxFilesContent)
	files.PUT("/content", s.sandboxFilesSave)
	files.POST("/upload", s.sandboxFilesUpload)
	files.DELETE("", s.sandboxFilesDelete)
	files.POST("/rename", s.sandboxFilesRename)
	files.POST("/directory", s.sandboxFilesMkdir)
}
