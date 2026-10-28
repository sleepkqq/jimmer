import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { homedir } from "node:os";
import { join } from "node:path";
import { z } from "zod";
import { DocsIndex, parseDocument, readRange } from "./docs-index.js";
import { Repository, type Snapshot } from "./repository.js";

const cache = process.env.JIMMER_DOCS_CACHE_DIR ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), ".cache"), "jimmer-docs-mcp");
const repositories = {
	docs: new Repository("docs", "https://github.com/babyfish-ct/jimmer-doc.git", cache),
	jimmer: new Repository("jimmer", "https://github.com/babyfish-ct/jimmer.git", cache),
};
type Source = keyof typeof repositories;
const indexes = new Map<string, Promise<DocsIndex>>();

function provenance(source: Source, snapshot: Snapshot) {
	return { source, repository: repositories[source].url.replace(/\.git$/, ""), ...snapshot,
		scope: source === "docs" ? "Official English documentation; may lag library releases. Verify version-sensitive APIs in source." : "Official Jimmer core source, not third-party integrations or forks." };
}

function sourceUrl(source: Source, revision: string, path: string, line?: number) {
	return `${repositories[source].url.replace(/\.git$/, "")}/blob/${revision}/${path.split("/").map(encodeURIComponent).join("/")}${line ? `#L${line}` : ""}`;
}

async function docsIndex(snapshot: Snapshot): Promise<DocsIndex> {
	let pending = indexes.get(snapshot.revision);
	if (!pending) {
		pending = (async () => {
			const files = await repositories.docs.files(snapshot.revision);
			const paths = files.map(file => file.path).filter(path => /^(docs|faq)\/.*\.mdx?$/.test(path));
			if (!paths.length) throw new Error("The documentation snapshot contains no Markdown/MDX documents.");
			return new DocsIndex(await repositories.docs.contents(snapshot.revision, paths), new Set(files.map(file => file.path)));
		})();
		indexes.set(snapshot.revision, pending);
		pending.catch(() => indexes.delete(snapshot.revision));
		if (indexes.size > 2) indexes.delete(indexes.keys().next().value!);
	}
	return pending;
}

const server = new McpServer({ name: "jimmer-docs-mcp", version: "1.0.0" });
const ref = z.string().min(1).max(128).default("main").describe("Branch, tag, or full commit SHA in the selected repository. Use the returned revision for repeatable reads. Documentation refs are independent of Jimmer release tags.");
const refresh = z.boolean().default(false).describe("Check upstream now instead of waiting for the one-hour refresh interval.");
const limit = z.number().int().min(1).max(5).default(3);
const query = z.string().trim().min(1).max(500);

async function respond(action: () => Promise<unknown>) {
	try { return { content: [{ type: "text" as const, text: JSON.stringify(await action(), null, 2) }] }; }
	catch (error) { return { isError: true, content: [{ type: "text" as const, text: (error as Error).message }] }; }
}

server.tool("jimmer_docs_search", "Search official English Jimmer documentation by API identifiers or English keywords. Returns matching sections with commit-pinned read instructions, not complete pages. Translate non-English queries to English. Follow imports and read relevant sections before writing code; check version-sensitive APIs with jimmer_source_lookup.",
	{ query, limit, ref, refresh }, ({ query, limit, ref, refresh }) => respond(async () => {
		const snapshot = await repositories.docs.snapshot(ref, refresh);
		const index = await docsIndex(snapshot);
		const results = index.search(query, limit).map(({ document, matches }) => ({
			path: document.path, title: document.title, breadcrumbs: document.breadcrumbs, websiteUrl: document.websiteUrl,
			sourceUrl: sourceUrl("docs", snapshot.revision, document.path), imports: document.imports,
			matches: matches.map(match => ({ ...match, read: { source: "docs", ref: snapshot.revision, path: document.path, startLine: match.startLine, maxLines: Math.min(300, match.endLine - match.startLine + 1) } })),
		}));
		return { ...provenance("docs", snapshot), query, results,
			...(!results.length ? { hint: "No lexical match. Try English keywords or a precise API identifier. For types missing from docs, use jimmer_source_lookup with the project's Jimmer release tag." } : {}) };
	}));

server.tool("jimmer_docs_read", "Read unchanged Markdown/MDX or source at a pinned commit. Preserves Java/Kotlin tabs, code, links and line endings. Follow nextRead to continue without truncation. Imports are explicit references, never executed. Use source=jimmer for paths returned by jimmer_source_lookup.",
	{ source: z.enum(["docs", "jimmer"]).default("docs"), path: z.string().min(1).max(500), ref, refresh,
		startLine: z.number().int().min(1).default(1), maxLines: z.number().int().min(1).max(300).default(120) },
	({ source, path, ref, refresh, startLine, maxLines }) => respond(async () => {
		const repository = repositories[source];
		const snapshot = await repository.snapshot(ref, refresh);
		const contents = await repository.contents(snapshot.revision, [path]);
		const text = contents.get(path)!;
		const range = readRange(text, startLine, maxLines);
		const document = /\.mdx?$/.test(path) ? parseDocument(path, text, new Set((await repository.files(snapshot.revision)).map(file => file.path))) : undefined;
		return { ...provenance(source, snapshot), path, sourceUrl: sourceUrl(source, snapshot.revision, path, startLine),
			...(document ? { title: document.title, websiteUrl: source === "docs" ? document.websiteUrl : undefined, imports: document.imports } : {}),
			...range, nextRead: range.nextStartLine ? { source, ref: snapshot.revision, path, startLine: range.nextStartLine, maxLines } : null };
	}));

server.tool("jimmer_source_lookup", "Find official Jimmer production Java/Kotlin source files by type name, qualified class name, or filename (e.g. TriggerType, VersionMode, JSqlClient). Not a method-body or semantic search. Set ref to the project's release tag when checking compatibility; main can contain unreleased APIs. Read matched files with jimmer_docs_read.",
	{ symbol: query, limit, ref, refresh }, ({ symbol, limit, ref, refresh }) => respond(async () => {
		const snapshot = await repositories.jimmer.snapshot(ref, refresh);
		const name = symbol.replace(/\.(java|kt)$/, "").replace(/\./g, "/").toLowerCase();
		const results = (await repositories.jimmer.files(snapshot.revision))
			.filter(file => file.path.includes("/src/main/") && /\.(java|kt)$/.test(file.path))
			.map(file => ({ path: file.path, name: file.path.replace(/\.(java|kt)$/, "").toLowerCase() }))
			.filter(file => file.name.includes(name))
			.sort((a, b) => Number(b.name.endsWith("/" + name)) - Number(a.name.endsWith("/" + name)) || a.path.localeCompare(b.path))
			.slice(0, limit).map(({ path }) => ({ path, sourceUrl: sourceUrl("jimmer", snapshot.revision, path), read: { source: "jimmer", ref: snapshot.revision, path, startLine: 1, maxLines: 120 } }));
		return { ...provenance("jimmer", snapshot), symbol, results,
			...(!results.length ? { hint: "Use the declaring type's simple or qualified name. This source excludes external extensions and application code." } : {}) };
	}));

server.connect(new StdioServerTransport()).catch(error => {
	console.error(error);
	process.exitCode = 1;
});
