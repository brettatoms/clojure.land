# Clojure Land

Discover open-source Clojure libraries and frameworks.

## Projects

The project data lives in [resources/clojure.land/projects.edn](./resources/clojure.land/projects.edn).  Each item in the list includes a project with the following keys:
- `:key`: A keyword that uniquely identifies the project. Must be unique. Required.
- `:name`: The display name of that project. Required.
- `:url`: The URL of the project home page..
- `:platforms`: A set of platform name strings, usually "clj" or "cljs".
- `:tags`: A set of tags strings for the project.  Only the first four tags will be used.
- `:repo-url`: The URL of the project source repository. If the URL is a GitHub URL then
  the project description and number of stars will be synced weekly.
- `:description`: The project description.  This will override the project description from GitHub.
- `:ignore`:  If set to `true` then the project will not be displayed or synced.

To add a new project open a PR to edit  [resources/clojure.land/projects.edn](./resources/clojure.land/projects.edn)

### Acknowledgements

- [weavejester](https://github.com/weavejester/): This site was inspired by the [The Clojure Toolbox](https://www.clojure-toolbox.com/) and provided starting point for the the project data.
