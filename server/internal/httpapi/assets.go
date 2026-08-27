package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/objectstorage"
)

func respondStorage(c *gin.Context, err error) {
	if objectstorage.IsBizError(err) {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusInternalServerError, fail(err.Error()))
}

func (s *Server) listAssets(c *gin.Context) {
	entries, err := s.storage.ListAssets(c.Request.Context(), c.Query("path"))
	if err != nil {
		respondStorage(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(entries))
}

func (s *Server) createAssetUploadURL(c *gin.Context) {
	var request struct {
		Path        string `json:"path"`
		FileName    string `json:"fileName"`
		ContentType string `json:"contentType"`
		FileSize    *int64 `json:"fileSize"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	fileSize := int64(0)
	if request.FileSize != nil {
		fileSize = *request.FileSize
	}
	result, err := s.storage.CreateAssetUploadURL(c.Request.Context(), request.Path, request.FileName, request.ContentType, fileSize)
	if err != nil {
		respondStorage(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(result))
}

func (s *Server) deleteAsset(c *gin.Context) {
	if err := s.storage.DeleteAsset(c.Request.Context(), c.Query("key")); err != nil {
		respondStorage(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

// createBuildSourceUpload backs POST .../deployments/source-upload (ZIP builds).
func (s *Server) createBuildSourceUpload(c *gin.Context) {
	var request struct {
		FileName    string `json:"fileName"`
		ContentType string `json:"contentType"`
		FileSize    *int64 `json:"fileSize"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Upload request is required"))
		return
	}
	fileSize := int64(0)
	if request.FileSize != nil {
		fileSize = *request.FileSize
	}
	result, err := s.storage.CreateBuildSourceUpload(c.Request.Context(),
		c.Param("namespace"), c.Param("name"), request.FileName, request.ContentType, fileSize)
	if err != nil {
		respondStorage(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(result))
}
