import { changelogPlugin } from "@fumapress/tegami";
import { defineConfig } from "fumapress";
import { fumadocsMdx } from "fumapress/adapters/mdx";
import { createGlassLayoutPage } from "fumapress/layouts/glass";
import { createHomeLayoutPage } from "fumapress/layouts/home";
import { flexsearchPlugin } from "fumapress/plugins/flexsearch";
import { llmsPlugin } from "fumapress/plugins/llms.txt";
import { changelog, docs } from "./.source/server";
import { LandingPage } from "./src/components/landing";

const HomeLayout = createHomeLayoutPage<typeof config.$context>({
    render() {
        return {
            body: (
                <div data-landing="">
                    <LandingPage />
                </div>
            ),
        };
    },
});
const GlassLayout = createGlassLayoutPage<typeof config.$context>();

const config = defineConfig({
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
    defaultLayoutProps: {
        nav: {
            title: "Connect-Ktor",
        },
        githubUrl: "https://github.com/ichizero/connect-ktor",
        // Header-only: Docs/Changelog stay out of the left sidebar page tree.
        // Sidebar Changelog order is controlled by content/meta.json.
        links: [
            {
                text: "Docs",
                url: "/introduction",
                on: "nav",
            },
            {
                text: "Changelog",
                url: "/changelog",
                on: "nav",
            },
        ],
    },
    renderPage: (props) => {
        if (props.slugs.length === 0) {
            return <HomeLayout {...props} />;
        }

        return <GlassLayout {...props} />;
    },
})
    .plugins(flexsearchPlugin(), llmsPlugin(), changelogPlugin())
    .adapters(fumadocsMdx());

export default config;
