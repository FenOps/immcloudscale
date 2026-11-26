package core

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/google"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Moving away from Google: each person connects their Google accounts. Drive is copied into their files
// ("Google Drive (email)") and kept in sync; Google Takeout exports of their photos are imported, whether they
// arrive through that Drive copy (Takeout can deliver to Drive) or are put in their "Google Takeout" folder.

const (
	TakeoutFolder = "Google Takeout"
	driveEvery    = 6 * time.Hour
	// An export's archives must stop changing for this long before it's imported: all its parts have to be
	// there, because a photo's details can be in another archive than the photo.
	takeoutSettle = 30 * time.Minute
	maxImports    = 100
)

var (
	ErrNoGoogleKey   = errors.New("add your Google API key first (see Import from Google)")
	clientIDRe       = regexp.MustCompile(`^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$`)
	takeoutArchiveRe = regexp.MustCompile(`(?i)^(takeout-[0-9tz]+)(-[0-9]+)?\.zip$`)
)

type googleLogin struct {
	person   string // who connects
	owner    string // whose files the Drive goes into
	verifier string
	expires  time.Time
}

// googleWork is what runs in the background, so nothing runs twice at once.
type googleWork struct {
	building  bool
	buildErr  string
	syncing   map[string]bool // account IDs
	importing map[string]bool // people
}

// GoogleAccountInfo is a connected account as the app shows it (never its token).
type GoogleAccountInfo struct {
	ID      string    `json:"id"`
	Email   string    `json:"email"`
	Person  string    `json:"person"`
	Folder  string    `json:"folder"`
	Synced  time.Time `json:"synced,omitempty"`
	Error   string    `json:"error,omitempty"`
	Files   int       `json:"files"`
	Bytes   int64     `json:"bytes"`
	Syncing bool      `json:"syncing"`
}

// GoogleStatus is what Import from Google shows a person.
type GoogleStatus struct {
	KeySet        bool                  `json:"keySet"`
	ToolsReady    bool                  `json:"toolsReady"`
	ToolsBuilding bool                  `json:"toolsBuilding"`
	ToolsError    string                `json:"toolsError,omitempty"`
	Accounts      []GoogleAccountInfo   `json:"accounts"`
	Imports       []state.TakeoutImport `json:"imports"`
	Importing     bool                  `json:"importing"`
	TakeoutFolder string                `json:"takeoutFolder"`
}

func driveFolder(email string) string { return "Google Drive (" + email + ")" }

// filesRoot is a person's Nextcloud files on disk.
func filesRoot(c state.Config, person string) string {
	return filepath.Join(c.DataDir, "files", person, "files")
}

func (s *Server) GoogleStatus(person string) GoogleStatus {
	st := s.Store.Read()
	s.googleMu.Lock()
	defer s.googleMu.Unlock()
	out := GoogleStatus{
		KeySet: st.Config.GoogleClientID != "", ToolsReady: s.Google != nil && s.Google.Ready(),
		ToolsBuilding: s.work.building, ToolsError: s.work.buildErr, Importing: s.work.importing[person],
		TakeoutFolder: TakeoutFolder, Accounts: []GoogleAccountInfo{}, Imports: []state.TakeoutImport{},
	}
	for _, a := range st.Google {
		if a.Person == person {
			out.Accounts = append(out.Accounts, GoogleAccountInfo{a.ID, a.Email, a.Person, driveFolder(a.Email), a.Synced, a.Error, a.Files, a.Bytes, s.work.syncing[a.ID]})
		}
	}
	for i := len(st.Imports) - 1; i >= 0; i-- {
		if st.Imports[i].Person == person {
			out.Imports = append(out.Imports, st.Imports[i])
		}
	}
	return out
}

// SetGoogleKey saves the Google API key ("Desktop app" OAuth client) Drive access goes through.
func (s *Server) SetGoogleKey(id, secret string) error {
	id, secret = strings.TrimSpace(id), strings.TrimSpace(secret)
	if !clientIDRe.MatchString(id) {
		return errors.New("that isn't a Google client ID: it looks like 123456789-abc….apps.googleusercontent.com")
	}
	if len(secret) < 10 || strings.ContainsAny(secret, " \t\n") {
		return errors.New("that isn't a Google client secret: copy the whole secret (often starting with GOCSPX-)")
	}
	return s.Store.Update(func(st *state.State) error {
		st.Config.GoogleClientID, st.Config.GoogleClientSecret = id, secret
		return nil
	})
}

// StartGoogleLogin returns the Google sign-in address for [person]; the Drive goes into [owner]'s files.
func (s *Server) StartGoogleLogin(person, owner string) (string, error) {
	st := s.Store.Read()
	if st.Config.GoogleClientID == "" {
		return "", ErrNoGoogleKey
	}
	if st.FindUser(owner) == nil {
		return "", fmt.Errorf("no user %q", owner)
	}
	l := google.NewLogin(st.Config.GoogleClientID)
	s.googleMu.Lock()
	defer s.googleMu.Unlock()
	if s.logins == nil {
		s.logins = map[string]googleLogin{}
	}
	for k, v := range s.logins {
		if s.Now().After(v.expires) {
			delete(s.logins, k)
		}
	}
	s.logins[l.State] = googleLogin{person, owner, l.Verifier, s.Now().Add(15 * time.Minute)}
	return l.URL, nil
}

// FinishGoogleLogin takes the address the browser ended on after Google's sign-in and connects the account.
func (s *Server) FinishGoogleLogin(ctx context.Context, person, pasted string) (GoogleAccountInfo, error) {
	u, err := url.Parse(strings.TrimSpace(pasted))
	if err != nil {
		return GoogleAccountInfo{}, errors.New("paste the whole address from the browser's address bar")
	}
	stateParam := u.Query().Get("state")
	s.googleMu.Lock()
	l, ok := s.logins[stateParam]
	delete(s.logins, stateParam) // one try per sign-in, whoever makes it
	if ok && (l.person != person || s.Now().After(l.expires)) {
		ok = false
	}
	s.googleMu.Unlock()
	if !ok {
		return GoogleAccountInfo{}, errors.New("that sign-in has expired or belongs to someone else; start again")
	}
	code, err := google.CodeFrom(pasted, stateParam)
	if err != nil {
		return GoogleAccountInfo{}, err
	}
	c := s.Store.Read().Config
	tok, email, err := google.Exchange(ctx, s.googleTokenURL(), c.GoogleClientID, c.GoogleClientSecret, code, l.verifier)
	if err != nil {
		return GoogleAccountInfo{}, err
	}
	var acct state.GoogleAccount
	err = s.Store.Update(func(st *state.State) error {
		if st.FindUser(l.owner) == nil {
			return ErrUserGone
		}
		for i := range st.Google {
			if st.Google[i].Email == email && st.Google[i].Person == l.owner {
				st.Google[i].Token, st.Google[i].Error = tok, "" // connected again: a fresh token
				acct = st.Google[i]
				return nil
			}
		}
		acct = state.GoogleAccount{ID: "g_" + strings.ToLower(state.Secret(10)), Email: email, Person: l.owner, Token: tok, Added: s.Now()}
		st.Google = append(st.Google, acct)
		return nil
	})
	if err != nil {
		return GoogleAccountInfo{}, err
	}
	s.Log.Printf("Google account %s connected for %s", email, l.owner)
	s.kickGoogle()
	return GoogleAccountInfo{ID: acct.ID, Email: acct.Email, Person: acct.Person, Folder: driveFolder(acct.Email)}, nil
}

// RemoveGoogle disconnects an account. What was copied stays in the person's files.
func (s *Server) RemoveGoogle(person, id string, admin bool) error {
	found := false
	err := s.Store.Update(func(st *state.State) error {
		kept := st.Google[:0]
		for _, a := range st.Google {
			if a.ID == id && (a.Person == person || admin) {
				found = true
				continue
			}
			kept = append(kept, a)
		}
		st.Google = kept
		return nil
	})
	if err == nil && !found {
		err = errors.New("no such Google account")
	}
	return err
}

// SyncGoogleNow copies an account's Drive again soon (in the background).
func (s *Server) SyncGoogleNow(person, id string) error {
	err := s.Store.Update(func(st *state.State) error {
		for i := range st.Google {
			if st.Google[i].ID == id && st.Google[i].Person == person {
				st.Google[i].Synced = time.Time{}
				return nil
			}
		}
		return errors.New("no such Google account")
	})
	if err == nil {
		s.kickGoogle()
	}
	return err
}

// ImportNow looks for Takeout archives to import right away.
func (s *Server) ImportNow() { s.kickGoogle() }

func (s *Server) googleTokenURL() string {
	if s.GoogleTokenURL != "" {
		return s.GoogleTokenURL
	}
	return google.TokenURL
}

func (s *Server) kickGoogle() {
	s.googleMu.Lock()
	if s.kick == nil {
		s.kick = make(chan struct{}, 1)
	}
	k := s.kick
	s.googleMu.Unlock()
	select {
	case k <- struct{}{}:
	default:
	}
}

// GoogleWorker copies Drives and imports Takeout exports until ctx ends: every few minutes, and right away
// when something is connected or asked for.
func (s *Server) GoogleWorker(ctx context.Context) {
	s.kickGoogle()
	s.googleMu.Lock()
	k := s.kick
	s.googleMu.Unlock()
	for {
		select {
		case <-ctx.Done():
			return
		case <-k:
		case <-time.After(10 * time.Minute):
		}
		s.googleRound(ctx)
	}
}

func (s *Server) googleRound(ctx context.Context) {
	st := s.Store.Read()
	if s.Google == nil || st.Config.DataDir == "" {
		return
	}
	exports := s.pendingExports(st)
	due := []state.GoogleAccount{}
	for _, a := range st.Google {
		if s.Now().Sub(a.Synced) >= driveEvery {
			due = append(due, a)
		}
	}
	if len(due) == 0 && len(exports) == 0 {
		return
	}
	if !s.ensureTools(ctx) {
		return
	}
	for _, a := range due {
		s.syncDrive(ctx, a)
	}
	// Archives the Drive copy just brought in are imported on a later round, once they've settled.
	for _, e := range exports {
		s.importExport(ctx, e)
	}
}

func (s *Server) ensureTools(ctx context.Context) bool {
	if s.Google.Ready() {
		return true
	}
	s.googleMu.Lock()
	s.work.building, s.work.buildErr = true, ""
	s.googleMu.Unlock()
	s.Log.Printf("Getting the Google import tools ready (a few minutes, once)")
	err := s.Google.Ensure(ctx)
	s.googleMu.Lock()
	s.work.building = false
	if err != nil {
		s.work.buildErr = "Couldn't get the import tools ready: " + err.Error()
	}
	s.googleMu.Unlock()
	if err != nil {
		s.Log.Printf("Google import tools: %v", err)
		return false
	}
	return true
}

func (s *Server) setBusy(m *map[string]bool, key string, on bool) {
	s.googleMu.Lock()
	defer s.googleMu.Unlock()
	if *m == nil {
		*m = map[string]bool{}
	}
	if on {
		(*m)[key] = true
	} else {
		delete(*m, key)
	}
}

func (s *Server) syncDrive(ctx context.Context, a state.GoogleAccount) {
	s.setBusy(&s.work.syncing, a.ID, true)
	defer s.setBusy(&s.work.syncing, a.ID, false)
	c := s.Store.Read().Config
	folder := driveFolder(a.Email)
	dest := filepath.Join(filesRoot(c, a.Person), folder)
	tok, files, size, err := s.Google.SyncDrive(ctx, google.DriveAuth{ClientID: c.GoogleClientID, Secret: c.GoogleClientSecret, Token: a.Token}, dest, a.SkipTakeout)
	if err == nil {
		s.handToNextcloud(c, a.Person, folder)
	}
	_ = s.Store.Update(func(st *state.State) error {
		for i := range st.Google {
			g := &st.Google[i]
			if g.ID != a.ID {
				continue
			}
			g.Token, g.Synced, g.Error = tok, s.Now(), ""
			if err != nil {
				g.Error = err.Error()
			} else {
				g.Files, g.Bytes = files, size
			}
		}
		return nil
	})
	if err != nil {
		s.Log.Printf("Copying Google Drive of %s: %v", a.Email, err)
	} else {
		s.Log.Printf("Copied Google Drive of %s for %s: %d files", a.Email, a.Person, files)
	}
}

// handToNextcloud makes files written on disk belong to Nextcloud and shows them in the person's Files.
func (s *Server) handToNextcloud(c state.Config, person, folder string) {
	dir := filepath.Join(filesRoot(c, person), folder)
	_ = filepath.Walk(dir, func(p string, _ os.FileInfo, err error) error {
		if err == nil {
			_ = os.Lchown(p, 33, 33) // www-data, as in the Nextcloud image
		}
		return nil
	})
	if _, err := s.Docker.Exec(c.StackDir, "www-data", "nextcloud", "php", "occ", "files:scan", "--path=/"+person+"/files/"+folder); err != nil {
		s.Log.Printf("Showing %s in %s's files: %v", folder, person, err)
	}
}

// export is one Takeout export's archives, ready to import.
type export struct {
	person, name, source, tag string
	archives                  []string // full paths
	fromDrive                 string   // the account ID when the archives came through its Drive copy
	driveFolder               string
}

// pendingExports finds Takeout exports that are complete (unchanged for a while) and not imported yet.
func (s *Server) pendingExports(st state.State) []export {
	// Imported, or tried in the last day (a failed import is tried again daily, not every round).
	done := map[string]bool{}
	for _, im := range st.Imports {
		if (im.Error == "" && im.Errors == 0) || s.Now().Sub(im.At) < 24*time.Hour {
			done[im.Person+"/"+im.Export] = true
		}
	}
	var out []export
	scan := func(person, dir, source, accountID, folder string) {
		groups := map[string][]string{}
		newest := map[string]time.Time{}
		_ = filepath.Walk(dir, func(p string, fi os.FileInfo, err error) error {
			if err != nil || fi.IsDir() {
				return nil
			}
			m := takeoutArchiveRe.FindStringSubmatch(fi.Name())
			if m == nil {
				return nil
			}
			// A subfolder of "Google Takeout" names where it came from, e.g. "work account".
			src := source
			if rel, _ := filepath.Rel(dir, filepath.Dir(p)); accountID == "" && rel != "." {
				src = strings.SplitN(rel, string(filepath.Separator), 2)[0]
			}
			key := src + "\x00" + strings.ToLower(m[1])
			groups[key] = append(groups[key], p)
			if fi.ModTime().After(newest[key]) {
				newest[key] = fi.ModTime()
			}
			return nil
		})
		for key, archives := range groups {
			src, name, _ := strings.Cut(key, "\x00")
			if done[person+"/"+name] || s.Now().Sub(newest[key]) < takeoutSettle {
				continue
			}
			sort.Strings(archives)
			out = append(out, export{person, name, src, "From Google/" + src, archives, accountID, folder})
		}
	}
	for _, u := range st.Users {
		scan(u.Name, filepath.Join(filesRoot(st.Config, u.Name), TakeoutFolder), "Takeout", "", "")
	}
	for _, a := range st.Google {
		folder := driveFolder(a.Email)
		scan(a.Person, filepath.Join(filesRoot(st.Config, a.Person), folder, "Takeout"), a.Email, a.ID, folder)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].person+out[i].name < out[j].person+out[j].name })
	return out
}

func (s *Server) importExport(ctx context.Context, e export) {
	s.setBusy(&s.work.importing, e.person, true)
	defer s.setBusy(&s.work.importing, e.person, false)
	rec := state.TakeoutImport{Person: e.person, Export: e.name, Source: e.source, At: s.Now()}
	r, err := s.importWithKey(ctx, e)
	rec.Imported, rec.Duplicates, rec.Errors = r.Imported, r.Duplicates, r.Errors
	if err != nil {
		rec.Error = err.Error()
		s.Log.Printf("Importing %s for %s: %v", e.name, e.person, err)
	} else {
		s.Log.Printf("Imported %s for %s: %d photos, %d already there", e.name, e.person, r.Imported, r.Duplicates)
	}
	c := s.Store.Read().Config
	ok := err == nil && r.Errors == 0
	if ok {
		// Imported: the archives aren't needed any more (the photos are in Photos now).
		for _, p := range e.archives {
			_ = os.Remove(p)
		}
		folder := TakeoutFolder
		if e.fromDrive != "" {
			folder = e.driveFolder
		}
		s.handToNextcloud(c, e.person, folder)
	}
	_ = s.Store.Update(func(st *state.State) error {
		st.Imports = append(st.Imports, rec)
		if len(st.Imports) > maxImports {
			st.Imports = st.Imports[len(st.Imports)-maxImports:]
		}
		if ok && e.fromDrive != "" {
			for i := range st.Google {
				if st.Google[i].ID != e.fromDrive {
					continue
				}
				base := filepath.Join(filesRoot(c, e.person), e.driveFolder)
				for _, p := range e.archives {
					if rel, err := filepath.Rel(base, p); err == nil {
						st.Google[i].SkipTakeout = append(st.Google[i].SkipTakeout, filepath.ToSlash(rel))
					}
				}
			}
		}
		return nil
	})
}

// importWithKey imports with a short-lived Immich API key of the person's own, removed afterwards.
func (s *Server) importWithKey(ctx context.Context, e export) (google.Report, error) {
	st := s.Store.Read()
	u := st.FindUser(e.person)
	if u == nil {
		return google.Report{}, ErrUserGone
	}
	im := s.immich(st.Config)
	tok, err := im.Login(ctx, u.Email, u.Password)
	if err != nil {
		return google.Report{}, fmt.Errorf("signing in to Photos: %w", err)
	}
	key, err := im.CreateAPIKey(ctx, tok, "ImmCloudScale: Google import")
	if err != nil {
		return google.Report{}, fmt.Errorf("creating a Photos key: %w", err)
	}
	defer func() { _ = im.DeleteAPIKey(context.Background(), tok, key.ID) }()
	return s.Google.ImportTakeout(ctx, s.ImmichURL(st.Config), key.Secret, e.tag, e.archives)
}
