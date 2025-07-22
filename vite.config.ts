import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
    root: __dirname.concat("/src/clojure_land"),
    plugins: [tailwindcss()],
    mode: "production",
    resolve: {
        alias: {
            "~": __dirname.concat("/src/clojure_land"),
        },
    },
    build: {
        outDir: __dirname.concat("/resources/clojure.land/build"),
        manifest: true,
        rollupOptions: {
            input: [
                "~/main.css",
                "~/main.ts",
                "~/clojure-land-logo-small.jpg",
                "~/the-end-4.png",
            ],
        },
    },
});
