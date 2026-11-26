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
	ErrNoGoogleKey = errors.New("add your Google API key first (see Import from Google)")
	clientIDRe     = regexp.MustCompile(`^[0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com$`)
	// e.g. takeout-20251126T101500Z-001.zip, or takeout-20250115T101010Z-3-001.zip in newer exports.
	takeoutArchiveRe = regexp.MustCompile(`(?i)^(takeout-[0-9tz]+)(-[0-9]+)*\.zip$`)
	emailRe          = regexp.MustCompile(`^[^/\\\x00-\x1f@]+@[^/\\\x00-\x1f@]+$`)
)

const (
	buildBackoff = 6 * time.Hour // after the tools failed to build, before trying again on its own
	maxAttempts  = 3             // tries for an export that keeps failing; then it waits for the person
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
	buildAt   time.Time       // when building last failed
	syncing   map[string]bool // account IDs
	importing map[string]bool // people
	retry     map[string]bool // people who asked to try failed exports again
	// The archives seen per export, and since when unchanged: an export is imported only once its archives have
	// stopped changing here (their own dates can be old: copies keep Google's).
	seen map[string]seenSet
}

type seenSet struct {
	sig   string
	since time.Time
}

// GoogleAccountInfo is a connected account as the app shows it (never its token).
type GoogleAccountInfo struct {
	ID      string    `json:"id"`
	Email   string    `json:"email"`
	Person  string    `json:"person"`
	Folder  string    `json:"folder"`
	Synced  time.Time `json:"synced,omitzero"`
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
	if !emailRe.MatchString(email) || strings.Contains(email, "..") {
		return GoogleAccountInfo{}, fmt.Errorf("Google gave an unusable account address %q", email) // it goes into folder names
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

// ImportNow looks for Takeout archives to import right away, trying [person]'s failed exports again too.
func (s *Server) ImportNow(person string) {
	s.googleMu.Lock()
	if s.work.retry == nil {
		s.work.retry = map[string]bool{}
	}
	s.work.retry[person] = true
	s.googleMu.Unlock()
	s.kickGoogle()
}

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
	// "Try again" requests apply to this round.
	s.googleMu.Lock()
	retry := s.work.retry
	s.work.retry = nil
	s.googleMu.Unlock()
	exports := s.pendingExports(st, retry)
	due := []state.GoogleAccount{}
	for _, a := range st.Google {
		if s.Now().Sub(a.Synced) >= driveEvery {
			due = append(due, a)
		}
	}
	if len(due) == 0 && len(exports) == 0 {
		s.keepRetries(retry, nil)
		return
	}
	if !s.ensureTools(ctx) {
		s.keepRetries(retry, nil)
		return
	}
	for _, a := range due {
		s.syncDrive(ctx, a)
	}
	// Looked for again: the Drive copies may have brought archives (imported once they've settled).
	tried := map[string]bool{}
	for _, e := range s.pendingExports(s.Store.Read(), retry) {
		s.importExport(ctx, e)
		tried[e.person] = true
	}
	s.keepRetries(retry, tried)
}

// keepRetries keeps "Try again" requests that no export answered yet (still settling, say) for the next round.
func (s *Server) keepRetries(retry, tried map[string]bool) {
	s.googleMu.Lock()
	defer s.googleMu.Unlock()
	for p := range retry {
		if tried[p] {
			continue
		}
		if s.work.retry == nil {
			s.work.retry = map[string]bool{}
		}
		s.work.retry[p] = true
	}
}

func (s *Server) ensureTools(ctx context.Context) bool {
	if s.Google.Ready() {
		return true
	}
	s.googleMu.Lock()
	if !s.work.buildAt.IsZero() && s.Now().Sub(s.work.buildAt) < buildBackoff {
		s.googleMu.Unlock()
		return false // failed recently (often memory on a small NAS): not again straight away
	}
	s.work.building, s.work.buildErr = true, ""
	s.googleMu.Unlock()
	s.Log.Printf("Getting the Google import tools ready (a few minutes, once)")
	err := s.Google.Ensure(ctx)
	s.googleMu.Lock()
	s.work.building = false
	if err != nil {
		s.work.buildErr, s.work.buildAt = "Couldn't get the import tools ready (tried again in a few hours): "+google.Short(err), s.Now()
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
	tok, files, size := a.Token, a.Files, a.Bytes
	err := makeNextcloudDirs(filesRoot(c, a.Person), folder)
	if err == nil {
		tok, files, size, err = s.Google.SyncDrive(ctx, google.DriveAuth{ClientID: c.GoogleClientID, Secret: c.GoogleClientSecret, Token: a.Token}, dest, a.SkipTakeout)
		// What did arrive is shown even when some files failed (a Doc too big to export, say).
		s.handToNextcloud(c, a.Person, folder)
	}
	if ctx.Err() != nil {
		return // shutting down: not a failure, and it carries on next time
	}
	_ = s.Store.Update(func(st *state.State) error {
		for i := range st.Google {
			g := &st.Google[i]
			if g.ID != a.ID {
				continue
			}
			if g.Token == a.Token {
				g.Token = tok // unless it was connected again meanwhile, with a newer token
			}
			if g.Synced.Equal(a.Synced) {
				g.Synced = s.Now() // unless "Copy now" was asked for meanwhile
			}
			g.Error = ""
			if err != nil {
				g.Error = google.Short(err)
			}
			if files > 0 || err == nil {
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

// makeNextcloudDirs creates root/folder (and root, for someone who never opened Files) owned by Nextcloud.
func makeNextcloudDirs(root, folder string) error {
	p := root
	for _, part := range []string{"", folder} {
		if part != "" {
			p = filepath.Join(p, part)
		}
		if err := os.MkdirAll(p, 0o750); err != nil {
			return err
		}
		_ = os.Lchown(p, 33, 33)
		_ = os.Lchown(filepath.Dir(p), 33, 33)
	}
	return nil
}

// handToNextcloud makes files written on disk belong to Nextcloud and shows them in the person's Files.
func (s *Server) handToNextcloud(c state.Config, person, folder string) {
	dir := filepath.Join(filesRoot(c, person), folder)
	if _, err := os.Stat(dir); err != nil {
		return
	}
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

// pendingExports finds Takeout exports whose archives have stopped changing for a while. Imported archives are
// deleted, so whatever is there is new, apart from exports that failed recently or too often.
func (s *Server) pendingExports(st state.State, retry map[string]bool) []export {
	failed := map[string]int{}
	recent := map[string]bool{}
	for _, im := range st.Imports {
		k := im.Person + "\x00" + im.Source + "\x00" + im.Export
		if im.Error != "" || im.Errors > 0 {
			failed[k]++
			if s.Now().Sub(im.At) < 24*time.Hour {
				recent[k] = true
			}
		} else {
			delete(failed, k) // a later success (late parts, say) starts over
		}
	}
	var out []export
	seen := map[string]bool{}
	scan := func(person, dir, source, accountID, folder string, copied time.Time) {
		groups := map[string][]string{}
		sizes := map[string][]string{}
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
			key := person + "\x00" + src + "\x00" + strings.ToLower(m[1])
			groups[key] = append(groups[key], p)
			sizes[key] = append(sizes[key], fmt.Sprintf("%s:%d", fi.Name(), fi.Size()))
			return nil
		})
		for key, archives := range groups {
			seen[key] = true
			sort.Strings(sizes[key])
			sig := strings.Join(sizes[key], ",")
			s.googleMu.Lock()
			if s.work.seen == nil {
				s.work.seen = map[string]seenSet{}
			}
			cur, ok := s.work.seen[key]
			if !ok || cur.sig != sig {
				cur = seenSet{sig, s.Now()}
				s.work.seen[key] = cur
			}
			s.googleMu.Unlock()
			if s.Now().Sub(cur.since) < takeoutSettle {
				continue
			}
			// From a Drive copy: unchanged across one more copy, since Google may still have been adding parts.
			if accountID != "" && !copied.After(cur.since) {
				continue
			}
			if !retry[person] && (recent[key] || failed[key] >= maxAttempts) {
				continue
			}
			parts := strings.SplitN(key, "\x00", 3)
			sort.Strings(archives)
			out = append(out, export{person, parts[2], parts[1], "From Google/" + parts[1], archives, accountID, folder})
		}
	}
	for _, u := range st.Users {
		scan(u.Name, filepath.Join(filesRoot(st.Config, u.Name), TakeoutFolder), "Takeout", "", "", time.Time{})
	}
	for _, a := range st.Google {
		if s.isBusy(s.work.syncing, a.ID) {
			continue // still copying: its archives may be incomplete
		}
		folder := driveFolder(a.Email)
		scan(a.Person, filepath.Join(filesRoot(st.Config, a.Person), folder, "Takeout"), a.Email, a.ID, folder, a.Synced)
	}
	s.googleMu.Lock()
	for k := range s.work.seen {
		if !seen[k] {
			delete(s.work.seen, k)
		}
	}
	s.googleMu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].person+out[i].name < out[j].person+out[j].name })
	return out
}

func (s *Server) isBusy(m map[string]bool, key string) bool {
	s.googleMu.Lock()
	defer s.googleMu.Unlock()
	return m[key]
}

func (s *Server) importExport(ctx context.Context, e export) {
	s.setBusy(&s.work.importing, e.person, true)
	defer s.setBusy(&s.work.importing, e.person, false)
	rec := state.TakeoutImport{Person: e.person, Export: e.name, Source: e.source, At: s.Now()}
	r, err := s.importWithKey(ctx, e)
	if ctx.Err() != nil {
		return // shutting down: tried again after the restart, not counted as a failure
	}
	rec.Imported, rec.Duplicates, rec.Errors = r.Imported, r.Duplicates, r.Errors
	if err != nil {
		rec.Error = google.Short(err)
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
	defer func() {
		_ = im.DeleteAPIKey(context.Background(), tok, key.ID)
		_ = im.Logout(context.Background(), tok)
	}()
	return s.Google.ImportTakeout(ctx, s.ImmichURL(st.Config), key.Secret, e.tag, e.archives)
}
