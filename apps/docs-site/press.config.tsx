import { changelogPlugin } from "@fumapress/tegami";
import { HomeLayout } from "fumadocs-ui/layouts/home";
import { createPress } from "fumapress";
import { changelog, docs } from "./.source/server";
import { LandingPage } from "./src/components/landing";

const app = createPress(
    {
        docs: docs.toFumadocsSource(),
        changelog: changelog.toFumadocsSource({
            baseDir: "changelog",
        }),
    },
    { mode: "force-static" },
).plugins(changelogPlugin({ source: "changelog" }));

export default app.config({
    site: {
        name: "Connect-Ktor",
        baseUrl: import.meta.env.DEV ? "http://localhost:3000" : "https://ichizero.github.io/connect-ktor",
        git: {
            user: "ichizero",
            repo: "connect-ktor",
            branch: "main",
        },
    },
    head: [
        { tag: "link", attrs: { rel: "preconnect", href: "https://fonts.googleapis.com" } },
        { tag: "link", attrs: { rel: "preconnect", href: "https://fonts.gstatic.com", crossorigin: "" } },
        {
            tag: "link",
            attrs: {
                rel: "stylesheet",
                href: "https://fonts.googleapis.com/css2?family=Geist:ital,wght@0,100..900;1,100..900&family=JetBrains+Mono:ital,wght@0,100..800;1,100..800&display=swap",
            },
        },
    ],
    ui: {
        layoutProps: {
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
        renderPage(props) {
            if (props.type === "docs" && props.slugs.length === 0) {
                const { nav, githubUrl, links, themeSwitch, searchToggle, slots } = props.layoutProps;

                return (
                    <HomeLayout
                        nav={nav}
                        githubUrl={githubUrl}
                        links={links}
                        themeSwitch={themeSwitch}
                        searchToggle={searchToggle}
                        slots={slots}
                    >
                        <div data-landing="">
                            <LandingPage />
                        </div>
                    </HomeLayout>
                );
            }

            return props.renderDefault({ layout: "glass" });
        },
    },
});
