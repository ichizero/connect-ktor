import { changelogMetaSchema, changelogPageSchema } from "@fumapress/tegami/schema";
import { defineDocs } from "fumadocs-mdx/config";
import { metaSchema, pageSchema } from "fumapress/adapters/mdx/schema";

export const docs = defineDocs({
    dir: "content",
    docs: {
        async: true,
        schema: pageSchema,
        postprocess: {
            includeProcessedMarkdown: true,
        },
    },
    meta: {
        schema: metaSchema,
    },
});

export const changelog = defineDocs({
    dir: "changelog",
    docs: {
        async: true,
        schema: changelogPageSchema,
        lastModified: true,
        postprocess: {
            includeProcessedMarkdown: true,
        },
    },
    meta: {
        schema: changelogMetaSchema,
    },
});
