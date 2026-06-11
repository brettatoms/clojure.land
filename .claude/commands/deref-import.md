Import projects from the Clojure Deref page at: $1

## Workflow

1. **Fetch the page** - Use curl to fetch the HTML and extract the "Libraries and Tools" section (look for `<h2 id="_libraries_and_tools">` through the closing `</div>` tags)

2. **Parse project URLs** - Extract repo URLs from the `<li>` items. Each item has format:
   ```html
   <li><p><a href="REPO_URL">name</a> VERSION - DESCRIPTION</p></li>
   ```

3. **Check existing projects** - Use the REPL to get existing project keys:
   ```clojure
   (set (map :key (clojure-land.projects/read-projects-edn)))
   ```
   Do NOT read projects.edn directly into context.

4. **For each new project with a GitHub URL:** (Use the `gh` CLI for GitHub API calls — it is authenticated and has a much higher rate limit than unauthenticated `curl https://api.github.com/...` which gets rate-limited at 60/hour.)
   - Fetch repo metadata: `gh api "repos/{org}/{repo}" --jq '{homepage, description, language, archived, default_branch}'`
   - **Check for homepage** - If the repo has a `homepage` field set, use it as `:url` and the GitHub URL as `:repo-url`. Otherwise just use GitHub URL as `:url`.
   - **Check language breakdown**: `gh api "repos/{org}/{repo}/languages"` - Clojure should be >90%
   - **Verify it's a library** - Check README for `deps.edn`, `:require`, or similar usage as a dependency. Fetch via `gh api "repos/{org}/{repo}/contents/README.md?ref={branch}" --jq '.content' | base64 -d`. Exclude applications, CLI-only tools, templates, etc. per AGENTS.md criteria.
   - **Determine platforms** - Look for clj, cljs, babashka support in deps.edn or README
   - **Discover Maven coordinates** - Check the project README first — most libraries document their coordinates in installation/usage sections (look for `deps.edn`, Leiningen, or Maven snippets). Then verify on Clojars:
     ```bash
     curl -s "https://clojars.org/search?q={project-name}&format=json" | jq '.results[:3] | .[] | {group_name, jar_name}'
     ```
     Common group-id patterns: `{org}/{repo}`, `io.github.{org}/{repo}`, `me.{author}/{repo}`, `org.{org}/{repo}`.
     You can also check directly: `curl -s "https://clojars.org/api/artifacts/{group-id}/{artifact-id}" | jq '{group_name, jar_name}'`
     If not on Clojars, check Maven Central:
     ```bash
     curl -s "https://search.maven.org/solrsearch/select?q=a:{artifact-id}&rows=3&wt=json" | jq '.response.docs[] | {g, a, latestVersion}'
     ```
     If on Maven Central, add `:repository :maven-central` to the entry. If no published artifact exists (git-dep only), omit `:group-id` and `:artifact-id`.

5. **Select tags** - First check existing tags:
   ```bash
   grep -oE ':tags #\{[^}]+\}' ./resources/clojure.land/projects.edn | grep -oE '"[^"]+"' | sort | uniq -c | sort -rn
   ```
   Prefer existing tags. New tags can be suggested when no existing tag fits well.

6. **Add projects** - Insert into `resources/clojure.land/projects.edn` in alphabetical order by `:key` (format `:{org}/{repo}`). Use this format:
   ```clojure
   {:name "Project Name"
    :url "https://example.com"           ; homepage if available
    :repo-url "https://github.com/..."   ; only if :url is a homepage
    :key :org/repo
    :platforms #{"clj"}
    :group-id "group.id"                 ; from Clojars/Maven Central lookup
    :artifact-id "artifact-id"           ; from Clojars/Maven Central lookup
    :repository :maven-central           ; ONLY if on Maven Central, omit for Clojars
    :tags #{"tag1" "tag2"}}
   ```

7. **Validate** - Run `clj -X:validate-projects` (no output means success)

8. **Stage changes** - `git add resources/clojure.land/projects.edn`

## Inclusion Criteria

We are building a directory of **open-source Clojure libraries and frameworks** - reusable code that other projects can depend on via `require`.

**DO include:**
- Libraries (reusable packages)
- Frameworks (web frameworks, testing frameworks, etc.)
- Developer tools that are libraries (can be required in other projects)

**DO NOT include:**
- Applications built with Clojure (todo apps, editors, games, etc.)
- CLI-only tools that cannot be required as a library
- Starter templates (cloned, not required as deps)
- Browser extensions
- Closed-source or proprietary software
- Projects not primarily in Clojure (<90%)

## When Uncertain

If a project doesn't clearly fit as a library/framework, **ask the user** before adding or skipping it. Describe what the project does and let them decide.

## Notes

- The `:key` format is `:{org}/{repo}` derived from the repo URL
- For repos starting with a number, prefix with `_` (e.g., `:BrunoBonacci/_1config`)
- Only the first four tags are displayed
- All tags should be lowercase
- Projects with Clojars/Maven artifacts should always include `:group-id` and `:artifact-id`
- Add `:repository :maven-central` only for Maven Central artifacts (Clojars is the default)
- Projects distributed only as git deps should omit `:group-id`, `:artifact-id`, and `:repository`
