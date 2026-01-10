# Agent Guidelines for Clojure Land

This document provides guidance for AI agents working on the Clojure Land project.

## Project Overview

Clojure Land is a directory for discovering open-source Clojure libraries and frameworks. The project data lives in `resources/clojure.land/projects.edn`.

## Project Inclusion Criteria

When adding or evaluating projects for inclusion:

### ✅ DO Include

- **Open-source libraries** - Reusable code packages that other projects can depend on
- **Frameworks** - Application scaffolding or structure (web frameworks, testing frameworks, etc.)
- **Developer tools that are libraries** - Tools consumed as dependencies (linters, formatters, REPL utilities)
- **Projects primarily written in Clojure** - Must be >90% Clojure, ClojureScript, or other Clojure dialects

### ❌ DO NOT Include

- **Applications** - End-user software written in Clojure (e.g., a todo app, a blog, a game)
- **Browser extensions** - Even if they use ClojureScript
- **Closed-source/proprietary software** - Must be open source
- **Projects not primarily in Clojure** - If the main language is Java, JavaScript, etc., exclude it

## Project Data Format

Each project entry requires:

```clojure
{:name "Project Name"
 :url "https://github.com/org/repo"
 :platforms #{"clj" "cljs" "babashka"}  ; one or more
 :key :org/repo                          ; derived from repo URL
 :tags #{"tag1" "tag2"}}                 ; lowercase tags
```

Optional keys:
- `:repo-url` - Use when `:url` is a project website, not the repo
- `:description` - Overrides the GitHub description
- `:ignore` - Set to `true` to hide the project
- `:deprecated` - Set to `true` for deprecated projects
- `:archived` - Set to `true` for archived projects

### Key Format

- Keys use the format `:{org}/{repo}` derived from the repository URL
- Supports GitHub, GitLab, Codeberg, and Bitbucket URLs
- For repos starting with a number, prefix with `_` (e.g., `:BrunoBonacci/_1config`)
- Projects are sorted alphabetically by key

### Tag Guidelines

- **All lowercase** - Use `"web frameworks"` not `"Web Frameworks"`
- **Use existing tags** - Always check existing tags first and prefer them over creating new ones
- **Ask before creating new tags** - If no existing tag fits, ask the user before adding a new tag
- **Be specific but not too granular** - Avoid single-use tags
- **Consolidate similar tags** - e.g., use `"react"` not `"react frameworks"`, `"react interfaces"`, etc.

To list existing tags with counts:
```bash
grep -oE ':tags #\{[^}]+\}' ./resources/clojure.land/projects.edn | grep -oE '"[^"]+"' | sort | uniq -c | sort -rn
```

When suggesting tags for a new project:
1. Search for similar existing tags first
2. Show the user which existing tags you recommend
3. If a new tag seems necessary, explicitly ask: "Should I create a new tag for X, or use existing tag Y?"

## Common Tasks

### Adding a New Project

1. Verify it meets inclusion criteria (library/framework, open source, primarily Clojure)
2. Find the GitHub/GitLab/Codeberg/Bitbucket URL
3. Determine the key from the repo URL (`:org/repo` format)
4. Find the correct alphabetical position by key
5. Choose appropriate existing tags (check tag list first)
6. Add the entry and run validation: `clj -X:validate-projects`

### Validating Projects

```bash
clj -X:validate-projects
```

No output means validation passed.

### Bulk Editing with rewrite-clj

For large-scale changes that need to preserve formatting, use rewrite-clj:

```bash
clj -Sdeps '{:deps {rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}}}' -M:dev -e '...'
```

After using rewrite-clj, restore the leading space formatting:
```bash
sed 's/^{/ {/' resources/clojure.land/projects.edn > /tmp/projects_fixed.edn && mv /tmp/projects_fixed.edn resources/clojure.land/projects.edn
```

## Checking Project Eligibility

To verify a project should be included:

1. **Check if it's a library/framework:**
   ```bash
   curl -s "https://api.github.com/repos/{org}/{repo}" | jq '{description, topics}'
   ```

2. **Check primary language:**
   ```bash
   curl -s "https://api.github.com/repos/{org}/{repo}/languages" | jq '.'
   ```
   Clojure should be >90% of the codebase.

3. **Check if repo exists and is public:**
   A 404 or private repo should not be included.
