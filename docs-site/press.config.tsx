import { defineConfig } from "fumapress";
import { fumadocsMdx } from "fumapress/adapters/mdx";
import { flexsearchPlugin } from "fumapress/plugins/flexsearch";
import { llmsPlugin } from "fumapress/plugins/llms.txt";
import { docs } from "./.source/server";

export default defineConfig({
  mode: "static",
  content: docs.toFumadocsSource(),
  site: {
    name: "Connect-Ktor",
    baseUrl: import.meta.env.DEV
      ? "http://localhost:3000"
      : "https://ichizero.github.io/connect-ktor",
    git: {
      user: "ichizero",
      repo: "connect-ktor",
      branch: "main",
    },
  },
  meta: {
    root() {
      return (
        <>
          <link rel="preconnect" href="https://fonts.googleapis.com" />
          <link
            rel="preconnect"
            href="https://fonts.gstatic.com"
            crossOrigin=""
          />
          <link
            href="https://fonts.googleapis.com/css2?family=Geist:ital,wght@0,100..900;1,100..900&family=JetBrains+Mono:ital,wght@0,100..800;1,100..800&display=swap"
            rel="stylesheet"
          />
        </>
      );
    },
  },
})
  .plugins(flexsearchPlugin(), llmsPlugin())
  .adapters(fumadocsMdx());
