// Package gitremote reads branch information straight from a git remote, so the
// deploy dialog can offer a branch picker without cloning anything.
package gitremote

import (
	"context"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/go-git/go-git/v5"
	"github.com/go-git/go-git/v5/config"
	"github.com/go-git/go-git/v5/plumbing"
	gitobject "github.com/go-git/go-git/v5/plumbing/object"
	"github.com/go-git/go-git/v5/plumbing/transport"
	githttp "github.com/go-git/go-git/v5/plumbing/transport/http"
	gitssh "github.com/go-git/go-git/v5/plumbing/transport/ssh"
	"github.com/go-git/go-git/v5/storage/memory"
	cryptossh "golang.org/x/crypto/ssh"

	"github.com/wellch4n/oops/server/internal/domain"
)

const (
	branchRefPrefix = "refs/heads/"
	// listTimeout caps a remote that accepts the connection and then stalls.
	listTimeout = 15 * time.Second
	// cacheTTL keeps a branch picker responsive without hammering the remote:
	// branches change rarely, and the picker is opened repeatedly.
	cacheTTL = 60 * time.Second
	// maxMessageLength trims a commit subject to something a dropdown can show.
	maxMessageLength = 200
)

// Branch is one remote branch. Everything past CommitID is best effort: it costs
// a second round trip, and a remote that refuses it must not cost the picker its
// branch list.
type Branch struct {
	Name          string     `json:"name"`
	CommitID      string     `json:"commitId"`
	CommitMessage *string    `json:"commitMessage"`
	CommitAuthor  *string    `json:"commitAuthor"`
	CommittedAt   *time.Time `json:"committedAt"`
}

type cacheEntry struct {
	branches []Branch
	storedAt time.Time
}

// Lister reads remote branches, memoizing each remote for a minute.
type Lister struct {
	mu      sync.Mutex
	entries map[string]cacheEntry
}

func NewLister() *Lister { return &Lister{entries: map[string]cacheEntry{}} }

// ListBranches returns the branches of repositoryURL, authenticating with the
// environment's git credential.
func (l *Lister) ListBranches(ctx context.Context, environment *domain.Environment, repositoryURL string) ([]Branch, error) {
	key := environment.Name + "|" + repositoryURL
	l.mu.Lock()
	if entry, found := l.entries[key]; found && time.Since(entry.storedAt) < cacheTTL {
		l.mu.Unlock()
		return entry.branches, nil
	}
	l.mu.Unlock()

	branches, err := fetchBranches(ctx, environment.GitCredential, repositoryURL)
	if err != nil {
		return nil, err
	}
	l.mu.Lock()
	l.entries[key] = cacheEntry{branches: branches, storedAt: time.Now()}
	l.mu.Unlock()
	return branches, nil
}

func fetchBranches(ctx context.Context, credential *domain.GitCredential, repositoryURL string) ([]Branch, error) {
	auth, err := authFor(credential, repositoryURL)
	if err != nil {
		return nil, err
	}
	listCtx, cancel := context.WithTimeout(ctx, listTimeout)
	defer cancel()

	remote := git.NewRemote(memory.NewStorage(), &config.RemoteConfig{
		Name: "origin", URLs: []string{repositoryURL},
	})
	refs, err := remote.ListContext(listCtx, &git.ListOptions{Auth: auth})
	if err != nil {
		return nil, describeFailure(repositoryURL, err)
	}

	branches := make([]Branch, 0, len(refs))
	seen := map[string]bool{}
	var tips []plumbing.Hash
	for _, ref := range refs {
		name := ref.Name().String()
		if !strings.HasPrefix(name, branchRefPrefix) || ref.Hash().IsZero() {
			continue
		}
		short := strings.TrimPrefix(name, branchRefPrefix)
		if seen[short] {
			continue
		}
		seen[short] = true
		branches = append(branches, Branch{Name: short, CommitID: ref.Hash().String()})
		tips = append(tips, ref.Hash())
	}
	sort.Slice(branches, func(i, j int) bool { return branches[i].Name < branches[j].Name })

	// Best effort: a remote that refuses the shallow fetch still yields a usable
	// picker, just without the commit subject beside each branch.
	if commits := fetchTipCommits(listCtx, repositoryURL, auth, tips); len(commits) > 0 {
		for index := range branches {
			if detail, found := commits[branches[index].CommitID]; found {
				branches[index].CommitMessage = detail.message
				branches[index].CommitAuthor = detail.author
				branches[index].CommittedAt = detail.committedAt
			}
		}
	}
	return branches, nil
}

type commitDetail struct {
	message     *string
	author      *string
	committedAt *time.Time
}

// fetchTipCommits shallow-fetches just the branch tips to read their subjects.
func fetchTipCommits(ctx context.Context, repositoryURL string, auth transport.AuthMethod, tips []plumbing.Hash) map[string]commitDetail {
	if len(tips) == 0 {
		return nil
	}
	storage := memory.NewStorage()
	remote := git.NewRemote(storage, &config.RemoteConfig{Name: "origin", URLs: []string{repositoryURL}})
	refSpecs := make([]config.RefSpec, 0, len(tips))
	for _, tip := range tips {
		refSpecs = append(refSpecs, config.RefSpec(tip.String()+":refs/tips/"+tip.String()))
	}
	err := remote.FetchContext(ctx, &git.FetchOptions{
		RefSpecs: refSpecs, Depth: 1, Auth: auth, Tags: git.NoTags,
	})
	if err != nil && !errors.Is(err, git.NoErrAlreadyUpToDate) {
		return nil
	}
	details := map[string]commitDetail{}
	for _, tip := range tips {
		commit, err := gitobject.GetCommit(storage, tip)
		if err != nil {
			continue
		}
		message := commit.Message
		if index := strings.IndexByte(message, '\n'); index >= 0 {
			message = message[:index]
		}
		message = strings.TrimSpace(message)
		if len(message) > maxMessageLength {
			message = message[:maxMessageLength]
		}
		detail := commitDetail{}
		if message != "" {
			detail.message = &message
		}
		if commit.Author.Name != "" {
			author := commit.Author.Name
			detail.author = &author
		}
		if !commit.Committer.When.IsZero() {
			when := commit.Committer.When
			detail.committedAt = &when
		}
		details[tip.String()] = detail
	}
	return details
}

// authFor picks the credential the URL's transport can actually use: SSH wants
// the private key, HTTP(S) wants the username and password.
func authFor(credential *domain.GitCredential, repositoryURL string) (transport.AuthMethod, error) {
	if isSSHURL(repositoryURL) {
		if credential == nil || domain.IsBlank(credential.PrivateKey) {
			return nil, domain.Biz("The repository is cloned over SSH, but the environment has no git private key configured.")
		}
		// A key without its trailing newline is rejected by the parser, the same
		// way it is in the build pod.
		key := domain.Deref(credential.PrivateKey)
		if !strings.HasSuffix(key, "\n") {
			key += "\n"
		}
		user := sshUserOf(repositoryURL)
		auth, err := gitssh.NewPublicKeys(user, []byte(key), "")
		if err != nil {
			return nil, domain.Bizf("Failed to parse the environment git private key: %s", err.Error())
		}
		// Mirrors the StrictHostKeyChecking=no the clone container runs with.
		auth.HostKeyCallback = cryptossh.InsecureIgnoreHostKey()
		return auth, nil
	}
	if credential.IsEmpty() {
		// A public repository over HTTP(S) still works anonymously.
		return nil, nil
	}
	username := domain.Deref(credential.Username)
	password := domain.Deref(credential.Password)
	if strings.TrimSpace(username) == "" && strings.TrimSpace(password) == "" {
		return nil, nil
	}
	if strings.TrimSpace(username) == "" {
		// A token-only credential still needs a non-empty user for basic auth.
		username = "oops"
	}
	return &githttp.BasicAuth{Username: username, Password: password}, nil
}

// isSSHURL matches both the explicit scheme and the scp-like git@host:path form.
func isSSHURL(repositoryURL string) bool {
	url := strings.TrimSpace(repositoryURL)
	if strings.HasPrefix(url, "ssh://") {
		return true
	}
	return !strings.Contains(url, "://") && strings.Contains(url, "@") && strings.Contains(url, ":")
}

// sshUserOf reads the user out of the URL, defaulting to git.
func sshUserOf(repositoryURL string) string {
	url := strings.TrimPrefix(strings.TrimSpace(repositoryURL), "ssh://")
	if index := strings.Index(url, "@"); index > 0 {
		return url[:index]
	}
	return "git"
}

// describeFailure turns a transport error into something worth showing a user.
func describeFailure(repositoryURL string, err error) error {
	if errors.Is(err, transport.ErrRepositoryNotFound) {
		return domain.Bizf("Repository not found: %s", repositoryURL)
	}
	if errors.Is(err, transport.ErrAuthenticationRequired) || errors.Is(err, transport.ErrAuthorizationFailed) {
		return domain.Biz("Git authentication failed, check the git credential of the environment.")
	}
	reason := strings.ToLower(err.Error())
	if strings.Contains(reason, "auth") || strings.Contains(reason, "credential") || strings.Contains(reason, "403") {
		return domain.Biz("Git authentication failed, check the git credential of the environment.")
	}
	return domain.Bizf("Failed to read branches from %s: %s", repositoryURL, err.Error())
}
