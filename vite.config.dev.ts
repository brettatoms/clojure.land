import config from "./vite.config";

config.mode = "development";
config.build.watch = {};
config.build.minify = false;
config.build.cssMinify = false;

export default config;
