# Agent Guidelines for Clojure Land

This document provides guidance for AI agents working on the Clojure Land project.

## Project Overview

Clojure Land is a directory for discovering open-source Clojure libraries and frameworks. The project data lives in `resources/clojure.land/projects.edn`.

## Development tips

- Use user/stop and user/go to restart the service when connected to the repl.
- After making changes always evaluate the changes in the repl so the user can see them in the browser immediately.

## Working with Project Data

**IMPORTANT: Do NOT read the full `projects.edn` file directly.** The file is large and will consume excessive context. Instead, load it into a Clojure REPL and parse it into a map for analysis:

```clojure
(def projects (clojure.edn/read-string (slurp "resources/clojure.land/projects.edn")))

;; Example queries:
(count projects)                                    ; total project count
(filter #(contains? (:tags %) "web") projects)      ; find projects with a tag
(filter #(= (:key %) :ring-clojure/ring) projects)  ; find specific project
(->> projects (mapcat :tags) frequencies (sort-by val >)) ; tag frequency
```

Use the REPL to search, filter, and analyze projects rather than reading the raw file.

## Project Inclusion Criteria

When adding or evaluating projects for inclusion:

### ✅ DO Include

- **Open-source libraries** - Reusable code packages that other projects can depend on via `require`
- **Frameworks** - Application scaffolding or structure (web frameworks, testing frameworks, etc.)
- **Developer tools that are libraries** - Tools consumed as dependencies (linters, formatters, REPL utilities) that can be required and called from other Clojure projects
- **Projects primarily written in Clojure** - Must be >90% Clojure, ClojureScript, or other Clojure dialects

### ❌ DO NOT Include

- **Applications** - End-user software written in Clojure (e.g., a todo app, a blog, a presentation tool, a graphics editor)
- **Command-line tools that are not libraries** - If the project is only used as a standalone CLI tool and cannot be required as a library in other Clojure projects, exclude it
- **Starter templates** - Project templates/starters that are cloned rather than required as dependencies
- **Browser extensions** - Even if they use ClojureScript
- **Closed-source/proprietary software** - Must be open source
- **Projects not primarily in Clojure** - If the main language is Java, JavaScript, Rust, etc., exclude it

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
- `:group-id` - Maven group ID (e.g., `"org.clojure"`, `"metosin"`)
- `:artifact-id` - Maven artifact ID (e.g., `"core.async"`, `"reitit"`)
- `:repository` - Set to `:maven-central` if the artifact is on Maven Central instead of Clojars. Omit for Clojars (the default).
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
6. Discover Maven coordinates (see below)
7. Add the entry and run validation: `clj -X:validate-projects`

### Discovering Maven Coordinates

Most Clojure libraries are published to Clojars. Some (e.g., `org.clojure/*`) are on Maven Central. Always check for coordinates when adding a project.

**Check the project README first** — most libraries document their coordinates in installation/usage sections (look for `deps.edn`, Leiningen, or Maven snippets).

**Then verify on Clojars:**
```bash
curl -s "https://clojars.org/api/artifacts/{group-id}/{artifact-id}" | jq '{group_name, jar_name, latest_version}'
```

Common group-id patterns to try:
- Same as repo org/name: `{org}/{repo}` (e.g., `datalevin/datalevin`)
- Reversed domain: `io.github.{org}/{repo}`, `com.github.{org}/{repo}`
- Custom group: `me.{author}/{repo}`, `net.clojars.{author}/{repo}`
- Org-based: `org.{org}/{repo}`, `com.{org}/{repo}`

You can also search Clojars:
```bash
curl -s "https://clojars.org/search?q={project-name}&format=json" | jq '.results[:3] | .[] | {group_name, jar_name}'
```

**Check Maven Central** (for org.clojure projects or if not on Clojars):
```bash
curl -s "https://search.maven.org/solrsearch/select?q=a:{artifact-id}+AND+g:{group-id}&rows=1&wt=json" | jq '.response.docs[0] | {g, a, latestVersion}'
```

If a project is on Maven Central (not Clojars), add `:repository :maven-central` to the entry.

If a project has no published artifacts (git-dep only), omit `:group-id` and `:artifact-id`.

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

   Also check the README to confirm it can be required as a dependency in other Clojure projects (look for `deps.edn`, `:require`, or similar usage instructions).

2. **Check primary language:**
   ```bash
   curl -s "https://api.github.com/repos/{org}/{repo}/languages" | jq '.'
   ```
   Clojure should be >90% of the codebase.

3. **Check if repo exists and is public:**
   A 404 or private repo should not be included.

4. **Check for a project homepage:**
   ```bash
   curl -s "https://api.github.com/repos/{org}/{repo}" | jq '{homepage, html_url}'
   ```
   If `homepage` is set, use it as `:url` and the GitHub URL as `:repo-url`. Also check the top of the README for a documentation site URL.
