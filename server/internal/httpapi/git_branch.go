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
	"github.com/go-git/go-git/v5/plumbing"
	"github.com/go-git/go-git/v5/plumbing/object"
	"github.com/go-git/go-git/v5/plumbing/transport"
	githttp "github.com/go-git/go-git/v5/plumbing/transport/http"
	gitssh "github.com/go-git/go-git/v5/plumbing/transport/ssh"
	"github.com/go-git/go-git/v5/storage/memory"
	"golang.org/x/crypto/ssh"

	"github.com/wellch4n/oops/server/internal/store"
)

// gitBranchView mirrors GitBranchView. Everything but name and commitId is
// best effort: when the remote refuses a shallow fetch of the tips, the
// commit details stay null and the UI shows the short SHA only.
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
	enrichBranchCommits(repository, auth, branches)
	return branches, nil
}

const maxCommitMessageLength = 200

// enrichBranchCommits mirrors JGitRepositoryGateway: after ls-remote, fetch
// only the tip commits (depth 1) into an in-memory repository and read each
// branch head's subject, author and time. Best effort — a remote that rejects
// the shallow fetch leaves the branch list SHA-only.
func enrichBranchCommits(repository string, auth transport.AuthMethod, branches []gitBranchView) {
	if len(branches) == 0 {
		return
	}
	storer := memory.NewStorage()
	remote := gogit.NewRemote(storer, &gitconfig.RemoteConfig{
		Name: "origin", URLs: []string{repository},
	})
	err := remote.Fetch(&gogit.FetchOptions{
		Auth:     auth,
		Depth:    1,
		RefSpecs: []gitconfig.RefSpec{"+refs/heads/*:refs/heads/*"},
		Tags:     gogit.NoTags,
	})
	if err != nil && err != gogit.NoErrAlreadyUpToDate {
		return
	}
	for i := range branches {
		commit, err := object.GetCommit(storer, plumbing.NewHash(branches[i].CommitID))
		if err != nil {
			continue
		}
		subject, _, _ := strings.Cut(commit.Message, "\n")
		subject = strings.TrimSpace(subject)
		if len(subject) > maxCommitMessageLength {
			subject = subject[:maxCommitMessageLength]
		}
		author := strings.TrimSpace(commit.Author.Name)
		committedAt := commit.Author.When.UTC().Format(time.RFC3339)
		branches[i].CommitMessage = &subject
		branches[i].CommitAuthor = &author
		branches[i].CommittedAt = &committedAt
	}
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
