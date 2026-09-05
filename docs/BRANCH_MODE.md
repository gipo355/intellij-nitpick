# Branch mode: annotating a tree that has no diff

Why this exists, what was considered, what was built, and the performance
work that came with it. Written while building it so the next change knows
the reasoning.

## The problem

Nitpick reviews diffs. Every scope (uncommitted, staged, unstaged, range,
commit) produces `Change` objects from git, comments render inside diff
viewers, and the tool window tree is the list of changed files. Planning a
big refactor on `main` with a clean working tree has nothing to review:
no changes, so no tree, no diff viewer, no place to put a comment.

The ask: annotate code anywhere in the repo, in place, and hand those
annotations to an agent as the plan. Staged, unstaged and stash were asked
about too; the first two already existed, stash is a range.

## Alternatives considered

| Option | Verdict |
|---|---|
| Comments-derived tree + Project view context menu | Least tree cost, most entry-point plumbing (Project view submenu, "Add file" button, editor binding). Dropped once the full tree turned out cheap enough. |
| Full tree from `git ls-files` | Works, spawns git, loses IDE excludes. |
| **Full tree from `ProjectFileIndex`** | **Chosen.** No process, respects excludes and libraries, folder-limited variant is a one-argument change. |
| Empty-tree diff (`git diff 4b825dc..HEAD`) | Zero new code: every file is an "added" change through the RANGE collector. Read-only content via `git cat-file` per file, so slow and not live. Good for a prototype only. |
| Annotate a branch that is not checked out | Rejected. Marks going stale, snippet relocation and the editor binding all work on the working tree. Nobody plans a refactor of a branch they are not looking at. |
| Key the session per commit | Rejected. Marks are path to content hash and survive commits on their own; per-commit keys would empty the plan after every commit. The start commit is kept as `Scope.base` for provenance instead. |

## What was built

- `ScopeKind.BRANCH`, `Scope.root` (optional folder, trailing `/`).
  Key `branch:<name>` or `branch:<name>@<folder>/`. `head` is the branch
  name, `base` the HEAD hash when the plan started (kept across re-picks of
  the same session).
- `ScopeChanges.branchTree`: iterate project content (or one folder), skip
  directories, binary files and libraries, wrap each file as
  `Change(null, CurrentContentRevision(path))`. Sorted by path like every
  other scope, so the existing tree, filters, folder comments and Next
  Unreviewed work unchanged. The walk is a cancellable read action: a write
  action restarts it instead of waiting for it.
- Tool window: *Current Branch, Whole Tree*, *Current Branch, Folder…*,
  *Stash…*. In branch mode single click, double click, Enter and every
  navigation open the source file (`OpenFileDescriptor`) instead of the
  preview diff tab.
- `BranchEditorBinder`: project service plus an `editorFactoryListener`.
  While the scope is BRANCH every main editor of a project file gets an
  `EditorReviewBinding` (comments, gutter "+", Alt+Shift+C, editor popup
  entries). Any other scope tears all of them down. Ordinary coding never
  sees Nitpick in editors.
- Checkout follows: `GIT_REPO_CHANGE` with a different branch name switches
  to that branch's session (created if new). A detached HEAD is not followed
  (every commit there would be a new session). Other repository events do
  nothing in branch mode: the tree comes from the VFS, not from git.
- Export: Markdown uses a planning intro when the intro setting is the
  default, names the start commit and suggests `git diff <start>..HEAD`.
  JSON and the MCP description carry `scope_kind: branch`, `head`, `root`,
  `base`.

## Performance work bundled in

The full tree exposed costs that were already there:

1. **Every refresh read and kept every file.** `ReviewedChange` held both
   sides' full text for the life of the scope. Now hash and content are
   `lazy` per change. Diff scopes still prime hashes in the background
   task, so the EDT never reads git. The branch tree primes only files that
   have a mark in any session or a line comment. `state()` returns
   UNREVIEWED for unmarked files without touching the hash.
2. **Placement recomputed per call.** `comments()` ran `CommentPlacer`
   over all comments times all changes on every tree node paint and every
   editor render. Now memoized on `(changes version, ReviewStore.version)`
   with a per-path index; `find(path)` is a map lookup.
3. **Relocation copied the file per comment.** Normalized text is cached
   on the change (`text`, `beforeText`).
4. **Notes typing re-rendered every editor.** `setNotes` fired
   `sessionChanged` per keystroke and every binding rebuilt its inlays.
   Notes are now flushed after 400 ms, keyed to the session they were typed
   in, and `EditorReviewBinding.render()` skips when the placed comments for
   its file and the document stamp are unchanged (a reload from disk moves
   the inlay markers, so it redraws). Diff rediffs still force a redraw.
5. **Immutable scopes refreshed on every changelist update.** Only
   uncommitted, staged and unstaged follow `changeListUpdateDone`. Branch
   mode listens to `VFS_CHANGES`: a content change invalidates that file's
   cached hash and repaints; create, delete, move and rename schedule a
   refresh. Repository events (commit, fetch) do not refresh the branch tree
   either; only a checkout does, through the session switch.
6. **`ContentHash.of` made four copies.** One normalizing pass into a char
   array, one encode. Same output, marks stay valid.

## Not done, deliberately

- No badges in the native Project view. A `ProjectViewNodeDecorator` is
  easy to add behind a setting if wanted.
- No cap on tree size. Measure on real repos first; the folder scope is
  the escape hatch for monorepos.
- Untracked files inside a stash (third parent) are not part of the stash
  range.
- Comments still stay project-wide and hash-anchored. A plan on `main` and
  a review of `feature/x` see the same comments where the text matches.

## Follow-ups worth doing

- *Since plan start* entry: RANGE from `Scope.base` to HEAD, one line in
  the scope combo, reuses the merge-base path. Plan in branch mode, agent
  works, review exactly its diff with marks carried over by hash.
- Optional setting to disable the gutter "+" in regular editors while
  keeping comment cards.
