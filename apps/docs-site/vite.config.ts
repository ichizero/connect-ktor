import { fumadocsMdx } from "fumadocs-mdx/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import press from "fumapress/vite";

const isProd = process.env.NODE_ENV === "production";

export default defineConfig({
    // Project Pages: https://ichizero.github.io/connect-ktor/
    plugins: [
        press({
            basePath: isProd ? "/connect-ktor/" : "/",
        }),
        fumadocsMdx(),
        tailwindcss(),
    ],
});
