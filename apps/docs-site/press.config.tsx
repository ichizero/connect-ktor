import { changelogPlugin } from "@fumapress/tegami";
import { defineConfig } from "fumapress";
import { fumadocsMdx } from "fumapress/adapters/mdx";
import { createDocsLayoutPage } from "fumapress/layouts/docs";
import { createHomeLayoutPage } from "fumapress/layouts/home";
import { createLayoutSwitch } from "fumapress/layouts/switch";
import { flexsearchPlugin } from "fumapress/plugins/flexsearch";
import { llmsPlugin } from "fumapress/plugins/llms.txt";
import { changelog, docs } from "./.source/server";
import { LandingPage } from "./src/components/landing";

export default defineConfig({
    mode: "static",
    content: {
        docs: docs.toFumadocsSource(),
        changelog: changelog.toFumadocsSource({
            baseDir: "changelog",
        }),
    },
    site: {
        name: "Connect-Ktor",
        baseUrl: import.meta.env.DEV ? "http://localhost:3000" : "https://ichizero.github.io/connect-ktor",
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
                    <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
                    <link
                        href="https://fonts.googleapis.com/css2?family=Geist:ital,wght@0,100..900;1,100..900&family=JetBrains+Mono:ital,wght@0,100..800;1,100..800&display=swap"
                        rel="stylesheet"
                    />
                </>
            );
        },
    },
})
    .layouts({
        page: createLayoutSwitch((page) => (page.slugs.length === 0 ? "home" : "docs"), {
            home: createHomeLayoutPage({
                render() {
                    return {
                        body: (
                            <div data-landing="">
                                <LandingPage />
                            </div>
                        ),
                    };
                },
            }),
            docs: createDocsLayoutPage(),
        }),
        defaultProps() {
            return {
                nav: {
                    title: "Connect-Ktor",
                },
                githubUrl: "https://github.com/ichizero/connect-ktor",
                // Keep docs entry points on the home landing; expose Changelog only.
                links: [
                    {
                        text: "Changelog",
                        url: "/changelog",
                    },
                ],
            };
        },
    })
    .plugins(flexsearchPlugin(), llmsPlugin(), changelogPlugin())
    .adapters(fumadocsMdx());
