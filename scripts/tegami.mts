import { fumapressPlugin } from "@fumapress/tegami/tegami";
import { tegami } from "tegami";
import { createCli } from "tegami/cli";
import { github } from "tegami/plugins/github";
import { gradleVersionPlugin } from "./gradle-tegami-plugin.mts";

const paper = tegami({
    // No npm packages are published; only the custom Gradle package is released.
    ignore: [/^npm:/],
    plugins: [
        gradleVersionPlugin(),
        fumapressPlugin({
            dir: "apps/docs-site/changelog",
        }),
        github({
            repo: "ichizero/connect-ktor",
            versionPr: {
                base: "main",
            },
            // Draft GitHub releases + artifacts are created by release.yml / GoReleaser
            // with --release-notes from Tegami changelog content (CHANGELOG.md).
            release: false,
        }),
    ],
    packages: {
        "connect-ktor": {},
    },
});

await createCli(paper).parseAsync();
