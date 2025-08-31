{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable { system = pkgs.stdenv.system; };
in
{
  # https://devenv.sh/packages/
  packages = [
    pkgs.git
    pkgs-unstable.cljfmt
    pkgs-unstable.clj-kondo
    pkgs-unstable.flyctl
    pkgs.docker
  ];

  languages.clojure.enable = true;
  languages.typescript.enable = true ;
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_24;
  };

  overlays = [
    (final: prev: {
      # languages.clojure bundles clojure-lsp but the version tends to lag behind so we'll
      # use the version from unstable instead
      clojure-lsp = (import inputs.nixpkgs-unstable {
        system = prev.stdenv.system;
      }).clojure-lsp;
    })
  ];

  dotenv.enable = true;
}
