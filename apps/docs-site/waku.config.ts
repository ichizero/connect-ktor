import { defineConfig } from "waku/config";
import tailwindcss from "@tailwindcss/vite";
import press from "fumapress/vite";
import mdx from "fumadocs-mdx/vite";

const isProd = process.env.NODE_ENV === "production";

export default defineConfig({
    // Project Pages: https://ichizero.github.io/connect-ktor/
    basePath: isProd ? "/connect-ktor/" : "/",
    vite: {
        plugins: [press(), mdx(), tailwindcss()],
    },
});
