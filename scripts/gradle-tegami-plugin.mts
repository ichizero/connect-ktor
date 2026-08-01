import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { WorkspacePackage, type PackagePublishResult, type TegamiPlugin } from "tegami";

const PACKAGE_NAME = "connect-ktor";
const VERSION_FILE = "VERSION";

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
