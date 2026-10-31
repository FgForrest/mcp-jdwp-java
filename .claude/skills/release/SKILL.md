---
name: release
description: Use when cutting a new release of the jdwp-debugging plugin — bumping the user-facing version, writing a CHANGELOG entry, committing, tagging, and pushing. Triggers — "do a release", "ship X.Y.Z", "release the last commits", "bump the version", "tag a release".
---

# Plugin Release

End-to-end workflow for releasing the `jdwp-debugging` plugin from this repo.

**Never push, force-push, tag-delete-on-remote, or merge without explicit user permission.** Local commits and local tags are reversible; remote pushes are not.

## What gets released (vs not)

| User-facing artifact                            | Bump on release?                |
|-------------------------------------------------|---------------------------------|
| `.claude-plugin/marketplace.json` `version`     | **YES** — both occurrences      |
| `CHANGELOG.md` new section at top               | **YES**                         |
| Annotated git tag `vX.Y.Z`                      | **YES**                         |
| `pom.xml` `<version>` (Maven artifact)          | NO — internal build id only     |
| `jdwp-mcp-server/pom.xml` `<version>`           | NO                              |
| `README.md`                                     | NO (no version reference there) |

The plugin version (in `marketplace.json`) is **decoupled** from the Maven version. Don't try to keep them in sync.

## Pre-flight checks

Run all of these before touching anything.

1. **Identify the last release.** `git for-each-ref --sort=-taggerdate --format='%(refname:short) %(taggerdate:short) %(subject)' refs/tags/ | head -5`. The most recent `vX.Y.Z` tag is the baseline. **Sanity-check:** the latest `[X.Y.Z]` entry at the top of `CHANGELOG.md` must equal the `version` in `.claude-plugin/marketplace.json` and equal the most recent tag. If any of those three disagree, fix that drift before bumping. (We hit this in 2.2.0 — `v2.1.2` tag was missing and had to be back-filled.)

2. **Enumerate unreleased commits.** `git log <last-tag>..HEAD --oneline --no-merges`. Read the full bodies for anything ambiguous — the feature/fix/breaking signal lives in the commit body, not the subject.

3. **Pick the SemVer bump** from the commit set:
   - Any `feat:` (new MCP tool, new behavior) → **minor** (`X.Y+1.0`).
   - Only `fix:` / `chore:` / `docs:` / `review:` → **patch** (`X.Y.Z+1`).
   - Any breaking change (removed/renamed MCP tool, changed default that breaks user scripts) → **major** (`X+1.0.0`).
   - When in doubt between minor and patch, ask the user. The release plays out in public — over-bumping is fine, under-bumping is awkward.

4. **Check branch topology.** `git log --all --decorate --oneline --graph -10`. The release commit must land on `main`. Three possible starting states:
   - **On `main` with clean unreleased commits ahead** → simplest case, commit there.
   - **On a feature branch that's NOT yet merged** → commit on the branch, PR will carry the release in. Bump before merging so the PR review sees the version change.
   - **On a feature branch that's ALREADY merged via PR** → you're on an orphan tip. Cherry-pick onto `main` (see "Cherry-pick recovery" below). This is the trap we hit in 2.2.0.

5. **Confirm the working tree.** `git status --short`. Untracked junk like `FIX_*.md`, `core/`, `.factorypath`, IDE files must stay out of the release commit. Stage explicitly (`git add <file> <file>`), never `git add -A` / `git add .`.

## Procedure

### 1. Bump `.claude-plugin/marketplace.json`

Two `"version"` strings to change — top-level marketplace version AND the inner `plugins[0].version`. Both must move to the new `X.Y.Z` together.

### 2. Write the `CHANGELOG.md` entry

New section at the top, above the previous version. Format is **Keep a Changelog**, already established in the file:

```markdown
## [X.Y.Z] — YYYY-MM-DD

### New — <one-line headline>

<2-4 sentence paragraph explaining the motivation, what was broken or
missing before, and what the change does. User-perspective, not
implementation-perspective.>

- **Bold lead-in** — body sentence explaining one bullet.
- **Another bullet** — …

### Fixed — <one-line headline>

<…>
```

Section headings to choose from (use the ones that fit): `### New`, `### Fixed`, `### Breaking`, `### Output token savings`, `### Docs`. Mirror the past entries (`[2.1.0]`, `[2.2.0]`) for tone — paragraphs first, bulleted detail second. Resolve any `#N` GitHub issue references in the text (`resolves #4`) — they're useful for cross-linking.

**Date:** today's date in `YYYY-MM-DD`. The em-dash separator `—` is part of the convention.

### 3. The release commit

```bash
git add .claude-plugin/marketplace.json CHANGELOG.md
git commit -m "chore: release X.Y.Z — <short headline>"
```

Use a HEREDOC for the body. Three paragraphs is the established length (see `7b95e87`, `1c40a71`):
1. Headline feature, what it does, resolves which issue.
2. Concurrency / behavior fixes that ship alongside.
3. Review polish summary.

Stage only those two files. Never include untracked files or unrelated edits.

### 4. Annotated tag

```bash
git tag -a vX.Y.Z -m "vX.Y.Z — <same short headline as commit>"
```

All past release tags are **annotated** (not lightweight) — keep it that way. Subject convention: `vX.Y.Z — short description`, em-dash separator. Verify with `git for-each-ref --format='%(refname:short) %(objecttype) %(subject)' refs/tags/vX.Y.Z` — `objecttype` must read `tag`, not `commit`.

### 5. Push (requires explicit user permission)

```bash
git push origin main
git push origin vX.Y.Z
```

Confirm with the user before each push. Never `--force` to `main`.

## Cherry-pick recovery (when the release commit is orphaned)

If you bumped the version on a feature branch and the PR was already merged into `main` before you committed, the release commit sits on an orphan tip. Recovery:

```bash
git checkout main
git merge --ff-only origin/main          # catch local main up
git cherry-pick <release-commit-sha>     # replays onto main, new SHA
git tag -d vX.Y.Z                        # if the tag was made on the orphan
git tag -a vX.Y.Z <new-sha> -m "vX.Y.Z — …"
```

The cherry-pick produces a new SHA. The old tag (if any) still points at the orphan commit — recreate it at the new SHA before pushing. Don't push the orphan; let the feature branch die naturally.

## Back-filling a missing tag

A past release commit without a tag (we found `v2.1.2` was never tagged):

```bash
git tag -a vX.Y.Z <release-commit-sha> -m "vX.Y.Z — <copy headline from commit>"
git push origin vX.Y.Z
```

Tag the release commit itself (the one with `chore: release X.Y.Z` in the subject), not the merge that pulled it in.

## Red flags

- `marketplace.json` and `CHANGELOG.md` show different versions after your edits → you forgot one of the two `version` strings in JSON, or skipped the changelog.
- `git log <last-tag>..HEAD` lists merge commits but no feature commits → you're tagging from the wrong baseline; find the actual last release commit.
- The release commit's parent is not the current `origin/main` tip → either rebase or cherry-pick before pushing.
- `git for-each-ref refs/tags/vX.Y.Z` shows `objecttype commit` not `tag` → lightweight tag by accident, redo with `-a`.
- Working tree has untracked files when you stage → audit them; `git add -A` will swallow them.

## Verification after push

```bash
git fetch origin
git log HEAD..origin/main --oneline      # should be empty
git log origin/main..HEAD --oneline      # should be empty
git ls-remote --tags origin | grep vX.Y.Z   # tag exists on remote
```
