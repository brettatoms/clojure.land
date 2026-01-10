# Clojure Land

Discover open-source Clojure libraries and frameworks.

## Projects

The project data lives in [resources/clojure.land/projects.edn](./resources/clojure.land/projects.edn).  Each item in the list includes a project with the following keys:
- `:name`: The display name of that project. Required.
- `:key`: A keyword that uniquely identifies the project using the format `:{org}/{repo}` derived from the repository URL (e.g., `:juxt/aero` for `https://github.com/juxt/aero`). For repos starting with a number, prefix the name with `_` (e.g., `:BrunoBonacci/_1config`). Must be unique. Required. Projects are sorted alphabetically by key.
- `:url`: The URL of the project home page or repository. Supports GitHub, GitLab, Codeberg, and Bitbucket URLs. If the URL is a GitHub URL then the project description and number of stars will be synced daily.
- `:repo-url`: The URL of the project source repository. Use this when `:url` points to a project website instead of the repo. Overrides `:url` for fetching project data and deriving the `:key`.
- `:platforms`: A set of platform name strings. Current support "clj", "cljs" and "babashka".
- `:tags`: A set of tags strings for the project.  Only the first four tags will be used.
- `:description`: The project description.  This will override the project description from GitHub.
- `:ignore`:  If set to `true` then the project will not be displayed or synced.

To add a new project open a PR to edit  [resources/clojure.land/projects.edn](./resources/clojure.land/projects.edn)

Additional project metadata is synced daily for repositories with a GitHub repo url.

## Local Development

### Using [Devenv](https://devenv.sh/)

1. Install Nix. I recommend [DeterminateSystems/nix-installer](https://github.com/DeterminateSystems/nix-installer)
2. `nix profile install nixpkgs#devenv`
3. `ln -s .envrc.devenv .envrc`

### Environment variables

- `GITHUB_API_TOKEN`: Needed to update update the local project data with the repo data.
- `USE_LOCAL_PROJECTS`: Set to `true` if you don't want to use or don't have access to the
  remote projects.edn when running locally

The following environemnt variables are required to pushed the update project data to an S3 compatible API.
 - `AWS_ACCESS_KEY_ID`
 - `AWS_ENDPOINT_URL_S3`
 - `AWS_REGION`
 - `AWS_SECRET_ACCESS_KEY`
 - `BUCKET_NAME`

### Start the server locally

```
user> (go)
```

## Acknowledgements

- [weavejester](https://github.com/weavejester/): This site was inspired by the [The Clojure Toolbox](https://www.clojure-toolbox.com/) and provided the starting point for the project data.
