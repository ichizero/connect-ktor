import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { WorkspacePackage, type PackagePublishResult, type TegamiPlugin } from "tegami";

const PACKAGE_NAME = "connect-ktor";
const VERSION_FILE = "VERSION";
const MAVEN_COORDINATE = "io.github.ichizero:connect-ktor";

/** Install-guide files that pin the published Maven coordinate. */
const VERSION_PIN_FILES = ["README.md", "apps/docs-site/content/getting-started.mdx"] as const;

export class GradlePackage extends WorkspacePackage {
    readonly manager = "gradle";
    readonly name = PACKAGE_NAME;
    readonly path: string;
    #version: string;

    constructor(root: string, version: string) {
        super();
        this.path = root;
        this.#version = version;
    }

    get version(): string {
        return this.#version;
    }

    setVersion(version: string): void {
        this.#version = version;
    }
}

async function readVersionFile(cwd: string): Promise<string> {
    const versionPath = path.join(cwd, VERSION_FILE);
    const version = (await readFile(versionPath, "utf8")).trim();
    if (!version) {
        throw new Error(`${VERSION_FILE} is empty`);
    }
    return version;
}

/**
 * Rewrite `io.github.ichizero:connect-ktor:<semver>` pins in install guides.
 * Called from applyDraft when Tegami bumps VERSION for a release PR.
 */
export async function syncMavenVersionPins(cwd: string, version: string): Promise<void> {
    const pattern = new RegExp(`${MAVEN_COORDINATE.replace(/\./g, "\\.")}:\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?`, "g");
    const replacement = `${MAVEN_COORDINATE}:${version}`;

    for (const relativePath of VERSION_PIN_FILES) {
        const filePath = path.join(cwd, relativePath);
        const original = await readFile(filePath, "utf8");
        const updated = original.replace(pattern, replacement);
        if (updated !== original) {
            await writeFile(filePath, updated, "utf8");
        }
    }
}

/**
 * Discovers the Gradle library as a tegami package and keeps VERSION in sync.
 *
 * Publishing is tag-only: the git/GitHub plugins create `vX.Y.Z`, and the
 * existing tag-triggered release workflow handles Maven Central + GoReleaser.
 */
export function gradleVersionPlugin(): TegamiPlugin {
    return {
        name: "gradle-version",
        async resolve() {
            const version = await readVersionFile(this.cwd);
            this.graph.add(new GradlePackage(this.cwd, version));
        },
        async applyDraft(draft) {
            const pkg = this.graph.get(`gradle:${PACKAGE_NAME}`);
            if (!(pkg instanceof GradlePackage)) return;

            const bumped = draft.getPackageDraft(pkg.id)?.bumpVersion(pkg);
            if (!bumped) return;

            pkg.setVersion(bumped);
            await writeFile(path.join(this.cwd, VERSION_FILE), `${bumped}\n`, "utf8");
            await syncMavenVersionPins(this.cwd, bumped);
        },
        initPublishPlan({ plan }) {
            for (const [id, packagePlan] of plan.packages) {
                const pkg = this.graph.get(id);
                if (!(pkg instanceof GradlePackage)) continue;

                packagePlan.git ??= {};
                packagePlan.git.tag = `v${pkg.version}`;
            }
        },
        async publishPreflight({ pkg }) {
            if (!(pkg instanceof GradlePackage)) return;
            return { shouldPublish: true };
        },
        async publish({ pkg }): Promise<PackagePublishResult | undefined> {
            if (!(pkg instanceof GradlePackage)) return;
            // Tag creation is handled by the git plugin after publish succeeds.
            return { type: "published" };
        },
    };
}
