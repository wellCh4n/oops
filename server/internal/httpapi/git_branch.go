package httpapi

import (
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	gogit "github.com/go-git/go-git/v5"
	gitconfig "github.com/go-git/go-git/v5/config"
	"github.com/go-git/go-git/v5/plumbing/transport"
	githttp "github.com/go-git/go-git/v5/plumbing/transport/http"
	gitssh "github.com/go-git/go-git/v5/plumbing/transport/ssh"
	"golang.org/x/crypto/ssh"

	"github.com/wellch4n/oops/server/internal/store"
)

// gitBranchView mirrors GitBranchView. Commit details beyond the id are best
// effort on the Java side too; here the branch list carries name + commitId.
type gitBranchView struct {
	Name          string  `json:"name"`
	CommitID      string  `json:"commitId"`
	CommitMessage *string `json:"commitMessage"`
	CommitAuthor  *string `json:"commitAuthor"`
	CommittedAt   *string `json:"committedAt"`
}

type branchCacheEntry struct {
	fetched  time.Time
	branches []gitBranchView
}

var (
	branchCacheMutex sync.Mutex
	branchCache      = map[string]branchCacheEntry{}
)

func gitAuthFor(repository string, credential *store.GitCredential) (transport.AuthMethod, error) {
	if strings.HasPrefix(repository, "ssh://") || strings.HasPrefix(repository, "git@") {
		if credential == nil || credential.PrivateKey == nil || *credential.PrivateKey == "" {
			return nil, nil
		}
		keys, err := gitssh.NewPublicKeys("git", []byte(*credential.PrivateKey), "")
		if err != nil {
			return nil, err
		}
		keys.HostKeyCallback = ssh.InsecureIgnoreHostKey()
		return keys, nil
	}
	if credential != nil && credential.Username != nil && *credential.Username != "" {
		password := ""
		if credential.Password != nil {
			password = *credential.Password
		}
		return &githttp.BasicAuth{Username: *credential.Username, Password: password}, nil
	}
	return nil, nil
}

func listRemoteBranches(repository string, credential *store.GitCredential) ([]gitBranchView, error) {
	auth, err := gitAuthFor(repository, credential)
	if err != nil {
		return nil, err
	}
	remote := gogit.NewRemote(nil, &gitconfig.RemoteConfig{
		Name: "origin", URLs: []string{repository},
	})
	refs, err := remote.List(&gogit.ListOptions{Auth: auth})
	if err != nil {
		return nil, err
	}
	branches := []gitBranchView{}
	for _, ref := range refs {
		name := ref.Name().String()
		if branch, isBranch := strings.CutPrefix(name, "refs/heads/"); isBranch {
			branches = append(branches, gitBranchView{Name: branch, CommitID: ref.Hash().String()})
		}
	}
	sort.Slice(branches, func(i, j int) bool { return branches[i].Name < branches[j].Name })
	return branches, nil
}

func (s *Server) getApplicationBranches(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	buildConfig, err := s.store.FindBuildConfig(c.Request.Context(), namespace, name)
	if err != nil || buildConfig.SourceType == nil || *buildConfig.SourceType != "GIT" ||
		buildConfig.Repository == nil || *buildConfig.Repository == "" {
		c.JSON(http.StatusOK, ok([]gitBranchView{}))
		return
	}
	environment, err := s.store.FindEnvironmentFullByName(c.Request.Context(), c.Query("environment"))
	if err != nil {
		c.JSON(http.StatusOK, fail("Environment not found: "+c.Query("environment")))
		return
	}

	cacheKey := environment.Name + "|" + *buildConfig.Repository
	branchCacheMutex.Lock()
	cached, present := branchCache[cacheKey]
	branchCacheMutex.Unlock()
	if present && time.Since(cached.fetched) < time.Minute {
		c.JSON(http.StatusOK, ok(cached.branches))
		return
	}

	branches, err := listRemoteBranches(*buildConfig.Repository, environment.GitCredential)
	if err != nil {
		c.JSON(http.StatusOK, fail("Failed to read branches of "+*buildConfig.Repository+": "+err.Error()))
		return
	}
	branchCacheMutex.Lock()
	branchCache[cacheKey] = branchCacheEntry{fetched: time.Now(), branches: branches}
	branchCacheMutex.Unlock()
	c.JSON(http.StatusOK, ok(branches))
}
